package com.syconx.us2us.runtime.storage

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.community.CommunityState
import com.syconx.us2us.core.community.SignedCommunityState
import com.syconx.us2us.core.encoding.Base64Url
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * An in-memory relay for the communities routes and `/v1/media` (COMMUNITIES.md), shared by several
 * accounts: each account talks to it through its own [MockWebServer], which is how it knows who is
 * asking. Membership follows the signed states it is given; envelopes queue per recipient; blobs
 * are kept with their delete tokens. Thread-safe enough for sequential test scripts.
 */
class FakeCommunityRelay {
    private val states = LinkedHashMap<AccountId, SignedCommunityState>()
    private val inboxes = HashMap<AccountId, MutableList<Queued>>()
    private val blobs = HashMap<String, Pair<ByteArray, String>>()
    private val servers = ArrayList<MockWebServer>()
    private val counter = AtomicLong()

    /** Every request, as "<account short> METHOD path". */
    val log: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    /** Media uploads and deletes. */
    var uploads = 0
        private set
    val deletedBlobs = ArrayList<String>()
    val downloads = ArrayList<Pair<AccountId, String>>()

    class Queued(val id: String, val community: AccountId, val revision: Long, val bytes: ByteArray, val sender: AccountId)

    fun publish(signed: SignedCommunityState) = synchronized(this) {
        val state = signed.verify()
        states[state.id] = signed
    }

    private fun state(id: AccountId): CommunityState? = states[id]?.verify()

    /** A server for [account]: give its URL to that account's relay client. */
    fun serverFor(account: AccountId): MockWebServer {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@FakeCommunityRelay) { handle(account, request) }
        }
        server.start()
        servers += server
        return server
    }

    fun close() = servers.forEach { it.close() }

    fun inbox(account: AccountId): MutableList<Queued> = synchronized(this) { inboxes.getOrPut(account) { ArrayList() } }

    /** Puts an envelope into [recipient]'s inbox, as if [sender] had sent it. */
    fun deliver(recipient: AccountId, community: AccountId, sender: AccountId, bytes: ByteArray) = synchronized(this) {
        inbox(recipient) += Queued("env${counter.incrementAndGet()}", community, state(community)!!.revision, bytes, sender)
    }

    private fun handle(account: AccountId, request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        val method = request.method
        log += "${account.toString().take(8)} $method $path"
        val parts = path.trim('/').split('/')
        return when {
            method == "GET" && path == "/v1/communities/mine" -> json(
                """{"communities":[${states.values.filter { it.verify().member(account) != null }.joinToString(",") { signed(it) }}]}""",
            )
            method == "GET" && path == "/v1/communities/inbox" -> {
                val list = inbox(account)
                json("""{"envelopes":[${list.joinToString(",") { """{"id":"${it.id}","communityId":"${it.community}","revision":${it.revision},"serverTimestamp":1,"envelope":"${Base64Url.encode(it.bytes)}"}""" }}],"more":false}""")
            }
            method == "POST" && path == "/v1/communities/inbox/ack" -> {
                val body = request.body!!.utf8()
                inbox(account).removeAll { body.contains("\"${it.id}\"") }
                MockResponse.Builder().code(204).build()
            }
            method == "PUT" && parts.size == 6 && parts[2] != "inbox" && parts[3] == "messages" -> {
                val community = AccountId.parse(parts[2])
                val recipient = AccountId.parse(parts[4])
                val state = state(community) ?: return error(404, "not_found")
                if (state.member(account) == null || state.member(recipient) == null || recipient == account) return error(404, "not_found")
                val revision = request.headers["X-Community-Revision"]!!.toLong()
                if (revision != state.revision) return error(409, "stale_version")
                inbox(recipient) += Queued("env${counter.incrementAndGet()}", community, revision, request.body!!.toByteArray(), account)
                MockResponse.Builder().code(202).addHeader("Content-Type", "application/json").body("""{"id":"AAAAAAAAAAAAAAAAAAAAAA","serverTimestamp":1}""").build()
            }
            method == "GET" && parts.size == 3 && parts[1] == "communities" -> {
                val id = AccountId.parse(parts[2])
                val signed = states[id] ?: return error(404, "not_found")
                if (signed.verify().member(account) == null) return error(404, "not_found")
                json(signed(signed))
            }
            method == "POST" && path == "/v1/media" -> {
                uploads += 1
                val mediaId = ByteArray(16).also(SecureRandom()::nextBytes)
                val token = ByteArray(32).also(SecureRandom()::nextBytes)
                blobs[Base64Url.encode(mediaId)] = request.body!!.toByteArray() to Base64Url.encode(token)
                MockResponse.Builder().code(201).addHeader("Content-Type", "application/json")
                    .body("""{"mediaId":"${Base64Url.encode(mediaId)}","deleteToken":"${Base64Url.encode(token)}","expiresAt":1}""").build()
            }
            method == "GET" && parts.size == 3 && parts[1] == "media" -> {
                downloads += account to parts[2]
                val blob = blobs[parts[2]] ?: return error(404, "not_found")
                MockResponse.Builder().code(200).addHeader("Content-Type", "application/octet-stream").body(okio.Buffer().write(blob.first)).build()
            }
            method == "DELETE" && parts.size == 3 && parts[1] == "media" -> {
                val blob = blobs[parts[2]] ?: return error(404, "not_found")
                if (request.headers["X-Delete-Token"] != blob.second) return error(403, "access_denied")
                blobs.remove(parts[2])
                deletedBlobs += parts[2]
                MockResponse.Builder().code(204).build()
            }
            else -> error(404, "not_found")
        }
    }

    fun hasBlob(mediaId: String) = synchronized(this) { mediaId in blobs }

    private fun signed(signed: SignedCommunityState) = """{"state":"${signed.state}","signature":"${signed.signature}"}"""
    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()
    private fun error(code: Int, wire: String) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json")
        .body("""{"error":{"code":"$wire","message":"test"}}""").build()
}
