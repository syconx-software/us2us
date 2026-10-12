package com.syconx.us2us.core.relay

import com.syconx.us2us.core.AccessKey
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.AuthToken
import com.syconx.us2us.core.encoding.Base64Url
import com.syconx.us2us.core.pow.PowSolution
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RelayClientTest {
    private val server = MockWebServer()
    private val token = AuthToken(ByteArray(32) { 7 })
    private val peer = AccountId(UUID.fromString("0123abcd-4567-89ef-0123-456789abcdef"))

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() = server.close()

    private val anonymous get() = RelayClient(server.url("/").toString())
    private val authed get() = anonymous.withAuthToken(token)

    private fun json(code: Int, body: String, vararg headers: Pair<String, String>) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
        .body(body)
        .build()

    private fun noContent() = MockResponse.Builder().code(204).build()

    private fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

    private fun RecordedRequest.jsonBody() = RelayJson.parseToJsonElement(body!!.utf8()).jsonObject

    @Test
    fun `authenticated endpoints send X-Auth-Token`() = runBlocking {
        server.enqueue(json(200, """{"accountId":"$peer","usernameHash":"${Base64Url.encode(ByteArray(32))}","extra":1}"""))
        val me = authed.me()
        assertEquals(peer, me.accountId)
        val request = take()
        assertEquals("GET", request.method)
        assertEquals("/v1/accounts/me", request.url.encodedPath)
        assertEquals(token.headerValue, request.headers["X-Auth-Token"])
        assertNull(request.headers["Authorization"])
    }

    @Test
    fun `authenticated endpoints refuse to run without a token`() {
        assertThrows<IllegalStateException> { runBlocking { anonymous.keyStatus() } }
    }

    @Test
    fun `sealed sends and challenges never carry the auth token`() = runBlocking {
        server.enqueue(json(200, """{"token":"v1.message.x","difficulty":18,"expiresAt":1760000000000}"""))
        server.enqueue(json(202, """{"id":"AAAAAAAAAAAAAAAAAAAAAA","serverTimestamp":1760000000000}"""))
        server.enqueue(json(202, """{"id":"AQEBAQEBAQEBAQEBAQEBAQ","serverTimestamp":1760000000001}"""))

        val challenge = authed.messageChallenge(peer)
        assertEquals(18, challenge.difficulty)
        val receipt = authed.sendEnvelope(peer, byteArrayOf(1, 2, 3), DeliveryAuthorization.WithProofOfWork(PowSolution(challenge.token, 42u)))
        assertEquals(SendReceipt("AAAAAAAAAAAAAAAAAAAAAA", 1760000000000), receipt)
        val ephemeral = authed.sendEnvelope(peer, byteArrayOf(4), DeliveryAuthorization.WithAccessKey(AccessKey(ByteArray(16) { 3 })), ephemeral = true)
        assertEquals(1760000000001, ephemeral.serverTimestamp)

        val pow = take()
        assertEquals("/v1/pow", pow.url.encodedPath)
        assertEquals("message", pow.url.queryParameter("purpose"))
        assertEquals(peer.toString(), pow.url.queryParameter("to"))
        assertNull(pow.headers["X-Auth-Token"])

        val withPow = take()
        assertEquals("PUT", withPow.method)
        assertEquals("/v1/messages/$peer", withPow.url.encodedPath)
        assertNull(withPow.url.query)
        assertNull(withPow.headers["X-Auth-Token"])
        assertEquals("v1.message.x:42", withPow.headers["X-PoW"])
        assertEquals("application/octet-stream", withPow.headers["Content-Type"])
        assertArrayEquals(byteArrayOf(1, 2, 3), withPow.body!!.toByteArray())

        val withKey = take()
        assertNull(withKey.headers["X-Auth-Token"])
        assertEquals(Base64Url.encode(ByteArray(16) { 3 }), withKey.headers["X-Access-Key"])
        assertEquals("1", withKey.url.queryParameter("ephemeral"))
        assertNull(withKey.url.queryParameter("push"), "push is not part of the protocol")
    }

    @Test
    fun `ephemeral sends refuse proof-of-work`() {
        assertThrows<IllegalArgumentException> {
            runBlocking { anonymous.sendEnvelope(peer, byteArrayOf(1), DeliveryAuthorization.WithProofOfWork(PowSolution("t", 1u)), ephemeral = true) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `ephemeral batches are anonymous and split by count and body size`() = runBlocking {
        repeat(4) { server.enqueue(json(202, "{}")) }
        val key = AccessKey(ByteArray(16) { 8 })
        val small = (1..250).map { EphemeralEnvelope(peer, key, ByteArray(100) { it.toByte() }) }
        authed.sendEphemeral(small)
        val first = take()
        assertEquals("POST", first.method)
        assertEquals("/v1/messages/ephemeral", first.url.encodedPath)
        assertNull(first.headers["X-Auth-Token"])
        val items = first.jsonBody()["messages"]!!.jsonArray
        assertEquals(200, items.size)
        val item = items[0].jsonObject
        assertEquals(peer.toString(), item["to"]!!.jsonPrimitive.content)
        assertEquals(key.headerValue, item["accessKey"]!!.jsonPrimitive.content)
        assertEquals(Base64Url.encode(ByteArray(100) { it.toByte() }), item["envelope"]!!.jsonPrimitive.content)
        assertEquals(50, take().jsonBody()["messages"]!!.jsonArray.size)

        // 4 KiB envelopes: a 1 MiB body holds fewer than 200 of them.
        authed.sendEphemeral((1..200).map { EphemeralEnvelope(peer, key, ByteArray(4096)) })
        val sizes = listOf(take(), take()).map { it.bodySize }
        assertTrue(sizes.all { it <= 1024 * 1024 }, "bodies stay within 1 MiB: $sizes")

        assertThrows<IllegalArgumentException> { runBlocking { authed.sendEphemeral(listOf(EphemeralEnvelope(peer, key, ByteArray(4097)))) } }
        authed.sendEphemeral(emptyList())
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `anonymous requests never share a connection with authenticated ones`() = runBlocking {
        repeat(2) {
            server.enqueue(json(200, """{"accountId":"$peer","usernameHash":"${Base64Url.encode(ByteArray(32))}"}"""))
            server.enqueue(json(202, """{"id":"AAAAAAAAAAAAAAAAAAAAAA","serverTimestamp":1}"""))
        }
        val client = authed
        client.me()
        client.sendEnvelope(peer, byteArrayOf(1), DeliveryAuthorization.WithAccessKey(AccessKey(ByteArray(16))))
        client.me()
        client.sendEnvelope(peer, byteArrayOf(2), DeliveryAuthorization.WithAccessKey(AccessKey(ByteArray(16))))
        val (me1, send1, me2, send2) = (1..4).map { take().connectionIndex }
        assertEquals(me1, me2, "authenticated requests reuse their pool")
        assertEquals(send1, send2, "anonymous requests reuse theirs")
        assertTrue(me1 != send1, "but the two never mix")
    }

    @Test
    fun `responses above the size limit are refused instead of buffered`() = runBlocking {
        val huge = "{\"ok\":true,\"version\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}"
        server.enqueue(json(200, huge))
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(ByteArray(2000))).build())
        val error = assertThrows<java.io.IOException> { runBlocking { anonymous.health() } }
        assertTrue(error !is RelayException)
        assertThrows<java.io.IOException> { runBlocking { anonymous.downloadMedia(ByteArray(16), ByteArrayOutputStream(), maxBytes = 1999) } }
    }

    @Test
    fun `errors are typed from the error body`() = runBlocking {
        server.enqueue(json(409, """{"error":{"code":"username_taken","message":"taken"}}"""))
        server.enqueue(json(429, """{"error":{"code":"rate_limited","message":"slow down"}}""", "Retry-After" to "30"))
        server.enqueue(MockResponse.Builder().code(502).body("<html>bad gateway</html>").build())
        server.enqueue(json(418, """{"error":{"code":"teapot","message":"?"}}"""))

        val taken = assertThrows<RelayException> { runBlocking { anonymous.register(dummyRegistration()) } }
        assertEquals(RelayErrorCode.USERNAME_TAKEN, taken.code)
        assertEquals(409, taken.httpStatus)
        assertEquals("taken", taken.relayMessage)

        val limited = assertThrows<RelayException> { runBlocking { authed.keyStatus() } }
        assertEquals(RelayErrorCode.RATE_LIMITED, limited.code)
        assertEquals(30L, limited.retryAfterSeconds)

        assertEquals(RelayErrorCode.UNKNOWN, assertThrows<RelayException> { runBlocking { anonymous.health() } }.code)
        assertEquals(RelayErrorCode.UNKNOWN, assertThrows<RelayException> { runBlocking { anonymous.health() } }.code)
    }

    @Test
    fun `registration posts the spec JSON and parses credentials`() = runBlocking {
        val newToken = Base64Url.encode(ByteArray(32) { 9 })
        server.enqueue(json(201, """{"accountId":"$peer","authToken":"$newToken"}"""))
        val registered = anonymous.register(dummyRegistration())
        assertEquals(peer, registered.accountId)
        assertEquals(AuthToken(ByteArray(32) { 9 }), registered.authToken)

        val request = take()
        assertEquals("POST", request.method)
        assertEquals("/v1/accounts", request.url.encodedPath)
        assertNull(request.headers["X-Auth-Token"])
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
        val body = request.jsonBody()
        assertEquals("1234", body["registrationId"]!!.jsonPrimitive.content)
        assertEquals("99", body["pow"]!!.jsonObject["nonce"]!!.jsonPrimitive.content)
    }

    @Test
    fun `directory misses are null and hashes go into the path as base64url`() = runBlocking {
        server.enqueue(json(404, """{"error":{"code":"not_found","message":"no"}}"""))
        val hash = ByteArray(32) { 0xfb.toByte() }
        assertNull(authed.lookupUsername(hash))
        assertEquals("/v1/directory/${Base64Url.encode(hash)}", take().url.encodedPath)
    }

    @Test
    fun `access key updates send an explicit null to disable`() = runBlocking {
        server.enqueue(noContent())
        server.enqueue(noContent())
        authed.setAccessKey(AccessKey(ByteArray(16) { 1 }))
        authed.setAccessKey(null)
        assertEquals(Base64Url.encode(AccessKey(ByteArray(16) { 1 }).hash()), take().jsonBody()["accessKeyHash"]!!.jsonPrimitive.content)
        assertEquals("""{"accessKeyHash":null}""", take().body!!.utf8())
    }

    @Test
    fun `acknowledgements are split into batches of 500`() = runBlocking {
        repeat(3) { server.enqueue(noContent()) }
        authed.acknowledge((1..1100).map { "id$it" })
        assertEquals(listOf(500, 500, 100), (1..3).map { take().jsonBody()["ids"]!!.jsonArray.size })
        authed.acknowledge(emptyList())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `media upload, ranged download and delete`() = runBlocking {
        val mediaId = ByteArray(16) { 4 }
        val deleteToken = ByteArray(32) { 5 }
        server.enqueue(json(201, """{"mediaId":"${Base64Url.encode(mediaId)}","deleteToken":"${Base64Url.encode(deleteToken)}","expiresAt":1}"""))
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(ByteArray(1000) { 6 })).build())
        server.enqueue(MockResponse.Builder().code(206).body(Buffer().write(ByteArray(10) { 6 })).build())
        server.enqueue(noContent())

        val upload = authed.uploadMedia(ByteArray(1000) { 6 })
        assertArrayEquals(mediaId, upload.mediaId)
        val full = ByteArrayOutputStream()
        assertEquals(1000, anonymous.downloadMedia(mediaId, full))
        assertEquals(10, anonymous.downloadMedia(mediaId, ByteArrayOutputStream(), offset = 990))
        anonymous.deleteMedia(mediaId, deleteToken)

        assertEquals(token.headerValue, take().headers["X-Auth-Token"])
        val download = take()
        assertEquals("/v1/media/${Base64Url.encode(mediaId)}", download.url.encodedPath)
        assertNull(download.headers["X-Auth-Token"], "downloads are capability-based")
        assertEquals("bytes=990-", take().headers["Range"])
        val delete = take()
        assertEquals("DELETE", delete.method)
        assertEquals(Base64Url.encode(deleteToken), delete.headers["X-Delete-Token"])
    }

    @Test
    fun `websocket delivers frames in order and sends acks`() = runBlocking {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"message","id":"m1","serverTimestamp":5,"envelope":"AQID"}""")
                        webSocket.send("""{"type":"future_frame","x":1}""")
                        webSocket.send("""{"type":"queue_empty"}""")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        received += text
                        if (text.contains("ping")) webSocket.send("""{"type":"pong"}""") else webSocket.close(4001, "replaced")
                    }
                },
            ).build(),
        )

        val socket = authed.openSocket()
        val events = withTimeout(10_000) {
            val collected = mutableListOf<SocketEvent>()
            socket.events.collect { event ->
                collected += event
                when (event) {
                    SocketEvent.QueueEmpty -> socket.ping()
                    SocketEvent.Pong -> socket.ack(listOf("m1"))
                    else -> Unit
                }
            }
            collected
        }
        assertEquals(SocketEvent.Connected, events[0])
        val message = events[1] as SocketEvent.Message
        assertEquals("m1", message.envelope.id)
        assertArrayEquals(byteArrayOf(1, 2, 3), message.envelope.envelope)
        assertEquals(listOf(SocketEvent.QueueEmpty, SocketEvent.Pong), events.subList(2, 4))
        val closed = events.last() as SocketEvent.Closed
        assertEquals(4001, closed.code)
        assertFalse(closed.isRetryable)

        assertEquals("""{"type":"ping"}""", received.poll(5, TimeUnit.SECONDS))
        assertEquals("""{"type":"ack","ids":["m1"]}""", received.poll(5, TimeUnit.SECONDS))
        assertEquals(token.headerValue, take().headers["X-Auth-Token"])
    }

    @Test
    fun `a rejected websocket upgrade is a non-retryable failure`() = runBlocking {
        server.enqueue(json(401, """{"error":{"code":"unauthorized","message":"bad token"}}"""))
        val events = withTimeout(10_000) { authed.openSocket().events.toList() }
        val failed = events.single() as SocketEvent.Failed
        assertEquals(401, failed.httpStatus)
        assertFalse(failed.isRetryable)
    }

    @Test
    fun `avatar writes carry the record or the version, and a stale one learns the highest`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(204).addHeader("ETag", "\"3\"").build())
        server.enqueue(json(409, """{"error":{"code":"stale_version","message":"stale"}}""", "X-Avatar-Version" to "7"))
        server.enqueue(noContent())

        val record = ByteArray(8192) { it.toByte() }
        authed.putAvatar(record)
        val stale = assertThrows<RelayException> { runBlocking { authed.deleteAvatar(5) } }
        assertEquals(RelayErrorCode.STALE_VERSION, stale.code)
        assertEquals(409, stale.httpStatus)
        assertEquals(7L, stale.avatarVersion)
        authed.deleteAvatar(Long.MAX_VALUE)
        assertThrows<IllegalArgumentException> { runBlocking { authed.deleteAvatar(0) } }

        val put = take()
        assertEquals("PUT", put.method)
        assertEquals("/v1/accounts/me/avatar", put.url.encodedPath)
        assertEquals(token.headerValue, put.headers["X-Auth-Token"])
        assertEquals("application/octet-stream", put.headers["Content-Type"])
        assertEquals("8192", put.headers["Content-Length"])
        assertArrayEquals(record, put.body!!.toByteArray())
        assertEquals("5", take().headers["X-Avatar-Version"])
        val delete = take()
        assertEquals("DELETE", delete.method)
        assertEquals("/v1/accounts/me/avatar", delete.url.encodedPath)
        assertEquals(Long.MAX_VALUE.toString(), delete.headers["X-Avatar-Version"])
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `avatar fetches revalidate, bound the body and never use an HTTP cache`() = runBlocking {
        val cacheDirectory = kotlin.io.path.createTempDirectory("us2us-cache").toFile()
        try {
            // An app that gave the client an HTTP cache, and a relay that wrongly allows caching.
            val cached = RelayClient(server.url("/").toString(), OkHttpClient.Builder().cache(Cache(cacheDirectory, 1024 * 1024)).build()).withAuthToken(token)
            val record = ByteArray(8192) { 1 }
            repeat(2) {
                server.enqueue(MockResponse.Builder().code(200).addHeader("ETag", "\"4\"").addHeader("Cache-Control", "max-age=3600").body(Buffer().write(record)).build())
            }
            server.enqueue(MockResponse.Builder().code(304).addHeader("ETag", "\"4\"").build())
            server.enqueue(json(404, """{"error":{"code":"not_found","message":"no"}}"""))
            server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(ByteArray(49153))).build())
            server.enqueue(json(405, """{"error":{"code":"bad_request","message":"method"}}"""))

            assertArrayEquals(record, (cached.fetchAvatar(peer) as AvatarFetch.Found).record)
            assertArrayEquals(record, (cached.fetchAvatar(peer) as AvatarFetch.Found).record)
            assertEquals(AvatarFetch.NotModified, cached.fetchAvatar(peer, ifNoneMatch = 4))
            assertEquals(AvatarFetch.None, cached.fetchAvatar(peer))
            assertThrows<java.io.IOException> { runBlocking { cached.fetchAvatar(peer) } }
            assertEquals(405, assertThrows<RelayException> { runBlocking { cached.fetchAvatar(peer) } }.httpStatus)

            val first = take()
            assertEquals("GET", first.method)
            assertEquals("/v1/accounts/$peer/avatar", first.url.encodedPath)
            assertEquals(token.headerValue, first.headers["X-Auth-Token"])
            assertNull(first.headers["If-None-Match"])
            assertNull(take().headers["If-None-Match"], "the second fetch reached the relay, not a cache")
            assertEquals("\"4\"", take().headers["If-None-Match"])
            assertEquals(6, server.requestCount)
            assertEquals(0, cacheDirectory.walk().count { it.isFile && it.length() >= record.size }, "nothing of the record on disk")
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }

    private fun dummyRegistration(): RegistrationRequest {
        val signed = SignedPreKeyEntity(1, ByteArray(33), ByteArray(64))
        return RegistrationRequest(ByteArray(32), ByteArray(33), 1234, signed, signed, listOf(PreKeyEntity(2, ByteArray(33))), listOf(signed), PowSubmission("t", "99"))
    }
}
