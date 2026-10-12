package com.syconx.us2us.core.relay

import com.syconx.us2us.core.AccessKey
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.AuthToken
import com.syconx.us2us.core.encoding.Base64Url
import com.syconx.us2us.core.pow.PowSolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** How a sealed, unauthenticated send proves it is not spam (PROTOCOL.md §4.6). */
sealed interface DeliveryAuthorization {
    /** The recipient's access key, given to accepted contacts inside E2EE. */
    class WithAccessKey(val accessKey: AccessKey) : DeliveryAuthorization

    /** A `message` proof-of-work bound to the recipient; used for message requests. Never ephemeral. */
    class WithProofOfWork(val solution: PowSolution) : DeliveryAuthorization
}

/** The result of `POST /v1/accounts`. */
class RegisteredAccount(val accountId: AccountId, val authToken: AuthToken)

/**
 * Client for every endpoint of the relay HTTP API (PROTOCOL.md §4) plus the WebSocket (§4.7).
 *
 * Requests that must not be linkable to the account (sealed sends, message proof-of-work
 * challenges, media download and delete) never carry the auth token and go through a separate
 * connection pool and TLS session cache, so the relay cannot tie them to an authenticated
 * connection or a resumed TLS session.
 *
 * Instances are cheap and immutable; [withAuthToken] derives an authenticated client that shares
 * both pools. All calls are main-safe suspend functions. Bodies are never logged, and responses are
 * read with size limits, so a misbehaving relay cannot exhaust the phone's memory.
 */
class RelayClient private constructor(
    private val base: HttpUrl,
    private val http: OkHttpClient,
    private val anonymousHttp: OkHttpClient,
    private val authToken: AuthToken?,
) {
    constructor(baseUrl: String = DEFAULT_URL, http: OkHttpClient = defaultHttpClient()) :
        this(baseUrl.toHttpUrl(), http, isolated(http), null)

    fun withAuthToken(token: AuthToken): RelayClient = RelayClient(base, http, anonymousHttp, token)

    // §4.1

    suspend fun health(): RelayHealth = execute(get(url("v1", "health"), auth = false), anonymous = true)

    // §4.2

    suspend fun registrationChallenge(): PowChallenge =
        execute(get(url("v1", "pow") { addQueryParameter("purpose", "register") }, auth = false))

    /** Part of a sealed send, so it is as anonymous as the send itself. */
    suspend fun messageChallenge(recipient: AccountId): PowChallenge = execute(
        get(
            url("v1", "pow") {
                addQueryParameter("purpose", "message")
                addQueryParameter("to", recipient.toString())
            },
            auth = false,
        ),
        anonymous = true,
    )

    // §4.3

    suspend fun register(request: RegistrationRequest): RegisteredAccount {
        val response: RegistrationResponse = execute(post(url("v1", "accounts"), json(request), auth = false))
        return RegisteredAccount(response.accountId, AuthToken.parse(response.authToken))
    }

    suspend fun me(): AccountInfo = execute(get(url("v1", "accounts", "me")))

    suspend fun changeUsername(usernameHash: ByteArray) =
        executeNoContent(put(url("v1", "accounts", "me", "username"), json(UsernameChangeRequest(usernameHash))))

    /** Rotates the auth token; the current one stops working immediately. */
    suspend fun rotateAuthToken(): AuthToken {
        val response: TokenResponse = execute(post(url("v1", "accounts", "me", "token"), EMPTY_BODY))
        return AuthToken.parse(response.authToken)
    }

    /** Publishes `SHA-256(accessKey)`, or disables access-key delivery with `null`. */
    suspend fun setAccessKey(accessKey: AccessKey?) =
        executeNoContent(put(url("v1", "accounts", "me", "access-key"), json(AccessKeyRequest(accessKey?.hash()))))

    /** Irreversibly deletes the account, its keys, mailbox and uploaded media. */
    suspend fun deleteAccount() = executeNoContent(request(url("v1", "accounts", "me")).delete().build())

    /** The identity key the relay holds for [accountId]; used to verify sealed senders (§6.3). */
    suspend fun identity(accountId: AccountId): AccountIdentity =
        execute(get(url("v1", "accounts", accountId.toString(), "identity")))

    // §4.4

    /** Exact-match directory lookup; `null` if no account has this username hash. */
    suspend fun lookupUsername(usernameHash: ByteArray): AccountIdentity? = try {
        execute(get(url("v1", "directory", Base64Url.encode(usernameHash))))
    } catch (e: RelayException) {
        if (e.code == RelayErrorCode.NOT_FOUND) null else throw e
    }

    // §4.5

    suspend fun uploadKeys(request: KeyUploadRequest) = executeNoContent(put(url("v1", "keys"), json(request)))

    suspend fun keyStatus(): KeyStatus = execute(get(url("v1", "keys", "status")))

    /** Fetches (and makes the relay consume) one pre-key bundle of [accountId]. */
    suspend fun preKeyBundle(accountId: AccountId): PreKeyBundleEntity =
        execute(get(url("v1", "keys", accountId.toString())))

    // §4.6

    /**
     * Delivers a sealed envelope. This request never carries the auth token: the relay must not
     * learn who is sending.
     *
     * @param ephemeral deliver only if the recipient is connected right now, never store. Needs
     *   an access key; the relay answers identically whether or not the envelope was delivered,
     *   and silently drops it if the key is wrong.
     */
    suspend fun sendEnvelope(
        recipient: AccountId,
        envelope: ByteArray,
        authorization: DeliveryAuthorization,
        ephemeral: Boolean = false,
    ): SendReceipt {
        require(!ephemeral || authorization is DeliveryAuthorization.WithAccessKey) { "ephemeral sends need an access key" }
        val url = url("v1", "messages", recipient.toString()) {
            if (ephemeral) addQueryParameter("ephemeral", "1")
        }
        val builder = Request.Builder().url(url).put(envelope.toRequestBody(OCTET_STREAM))
        when (authorization) {
            is DeliveryAuthorization.WithAccessKey -> builder.header("X-Access-Key", authorization.accessKey.headerValue)
            is DeliveryAuthorization.WithProofOfWork -> builder.header("X-PoW", authorization.solution.headerValue)
        }
        return execute(builder.build(), anonymous = true)
    }

    /**
     * Fans ephemeral envelopes out in as few `POST /v1/messages/ephemeral` requests as the relay's
     * limits allow (§4.6): each envelope at most 4 KiB, at most 200 per request and 1 MiB per body.
     * The relay never says who was online.
     */
    suspend fun sendEphemeral(envelopes: List<EphemeralEnvelope>) {
        require(envelopes.all { it.envelope.size <= MAX_EPHEMERAL_ENVELOPE_BYTES }) {
            "ephemeral envelopes must not exceed $MAX_EPHEMERAL_ENVELOPE_BYTES bytes"
        }
        val batch = mutableListOf<EphemeralBatchRequest.Item>()
        var batchBytes = 0
        suspend fun flush() {
            if (batch.isEmpty()) return
            executeNoContent(post(url("v1", "messages", "ephemeral"), json(EphemeralBatchRequest(batch.toList())), auth = false), anonymous = true)
            batch.clear()
            batchBytes = 0
        }
        for (item in envelopes) {
            // base64url of the envelope and key, the UUID and the JSON punctuation around them.
            val itemBytes = (item.envelope.size + 2) / 3 * 4 + EPHEMERAL_ITEM_OVERHEAD
            if (batch.size == MAX_EPHEMERAL_BATCH || batchBytes + itemBytes > MAX_EPHEMERAL_BODY_BYTES) flush()
            batch += EphemeralBatchRequest.Item(item.to, item.accessKey.toBytes(), item.envelope)
            batchBytes += itemBytes
        }
        flush()
    }

    suspend fun fetchMessages(limit: Int = 100): EnvelopePage {
        require(limit in 1..MAX_BATCH) { "limit must be in 1..$MAX_BATCH" }
        return execute(
            get(url("v1", "messages") { addQueryParameter("limit", limit.toString()) }),
            maxResponseBytes = limit.toLong() * MAX_ENVELOPE_JSON_BYTES + MAX_JSON_RESPONSE_BYTES,
        )
    }

    suspend fun acknowledge(ids: List<String>) {
        if (ids.isEmpty()) return
        ids.chunked(MAX_BATCH).forEach { chunk ->
            executeNoContent(post(url("v1", "messages", "ack"), json(AckRequest(chunk))))
        }
    }

    // §4.7

    /** Opens the account's WebSocket. Each [RelaySocket] is single-use; reconnect with a new one. */
    fun openSocket(): RelaySocket {
        val request = request(url("v1", "ws")).build()
        return RelaySocket.open(http.newBuilder().pingInterval(SOCKET_PING_SECONDS, TimeUnit.SECONDS).build(), request)
    }

    // §4.9

    suspend fun uploadMedia(blob: ByteArray): MediaUpload = uploadMedia(blob.toRequestBody(OCTET_STREAM))

    /** Streams [file] to the relay without loading it into memory. */
    suspend fun uploadMedia(file: File): MediaUpload = uploadMedia(file.asRequestBody(OCTET_STREAM))

    private suspend fun uploadMedia(body: RequestBody): MediaUpload = execute(post(url("v1", "media"), body))

    /**
     * Streams the blob into [sink], starting at byte [offset] (a `Range` request, to resume an
     * interrupted download). Needs no auth token: the media id is the capability.
     *
     * @param maxBytes the most this download may write; a longer response is cut off with an
     *   [IOException] instead of filling the disk. Recipients know the exact blob size (§7).
     * @return the number of bytes written to [sink].
     */
    suspend fun downloadMedia(mediaId: ByteArray, sink: OutputStream, offset: Long = 0, maxBytes: Long = Long.MAX_VALUE): Long {
        require(offset >= 0) { "offset must not be negative" }
        require(maxBytes >= 0) { "maxBytes must not be negative" }
        val builder = Request.Builder().url(url("v1", "media", Base64Url.encode(mediaId))).get()
        if (offset > 0) builder.header("Range", "bytes=$offset-")
        return anonymousHttp.newCall(builder.build()).await().use { response ->
            response.throwIfError()
            if (offset > 0 && response.code != HTTP_PARTIAL_CONTENT) {
                throw IOException("relay ignored the Range request (HTTP ${response.code})")
            }
            val declared = response.body.contentLength()
            if (declared > maxBytes) throw IOException("media is larger than expected")
            withContext(Dispatchers.IO) {
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                var written = 0L
                response.body.byteStream().use { input ->
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (written + n > maxBytes) throw IOException("media is larger than expected")
                        sink.write(buffer, 0, n)
                        written += n
                    }
                }
                written
            }
        }
    }

    /** Deletes a blob; recipients do this right after a successful download (§4.9). */
    suspend fun deleteMedia(mediaId: ByteArray, deleteToken: ByteArray) {
        val request = Request.Builder()
            .url(url("v1", "media", Base64Url.encode(mediaId)))
            .header("X-Delete-Token", Base64Url.encode(deleteToken))
            .delete()
            .build()
        executeNoContent(request, anonymous = true)
    }

    // §4.12. Never through an HTTP cache: a picture from search stays in memory or the encrypted
    // store only (§8.10), so these requests use a client without one, whatever [http] has.

    /**
     * Stores the account's avatar record (built by `AvatarRecord.seal`); its header carries the
     * version, which must be above the account's highest.
     *
     * @throws RelayException `409` [RelayErrorCode.STALE_VERSION] with [RelayException.avatarVersion]
     *   when the version is not above the highest; `404` or `405` from a relay without avatars.
     */
    suspend fun putAvatar(record: ByteArray) =
        executeNoContent(put(url("v1", "accounts", "me", "avatar"), record.toRequestBody(OCTET_STREAM)), uncached = true)

    /**
     * Deletes the account's avatar record with [version] (1..2^63 − 1, above the highest); also
     * succeeds when nothing is stored.
     *
     * @throws RelayException as [putAvatar].
     */
    suspend fun deleteAvatar(version: Long) {
        require(version >= 1) { "avatar versions are 1..2^63 - 1" }
        val request = request(url("v1", "accounts", "me", "avatar")).header(AVATAR_VERSION_HEADER, version.toString()).delete().build()
        executeNoContent(request, uncached = true)
    }

    /**
     * Fetches [accountId]'s avatar record. With [ifNoneMatch], the version of a cached copy, an
     * unchanged record is answered [AvatarFetch.NotModified]. No record, no such account and a relay
     * without avatars are all [AvatarFetch.None]. The body is not interpreted here.
     *
     * @throws IOException if the relay sends more than a record's 48 KiB.
     */
    suspend fun fetchAvatar(accountId: AccountId, ifNoneMatch: Long? = null): AvatarFetch {
        val builder = request(url("v1", "accounts", accountId.toString(), "avatar")).get()
        if (ifNoneMatch != null) builder.header("If-None-Match", "\"$ifNoneMatch\"")
        return uncachedHttp.newCall(builder.build()).await().use { response ->
            when (response.code) {
                HTTP_NOT_MODIFIED -> AvatarFetch.NotModified
                HTTP_NOT_FOUND -> AvatarFetch.None
                else -> {
                    response.throwIfError()
                    val record = withContext(Dispatchers.IO) { response.body.readBytes(MAX_AVATAR_RECORD_BYTES) }
                        ?: throw IOException("avatar record exceeds $MAX_AVATAR_RECORD_BYTES bytes")
                    AvatarFetch.Found(record)
                }
            }
        }
    }

    // Plumbing

    private fun url(vararg segments: String, configure: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        base.newBuilder().apply {
            segments.forEach(::addPathSegment)
            configure()
        }.build()

    private fun request(url: HttpUrl, auth: Boolean = true): Request.Builder {
        val builder = Request.Builder().url(url)
        if (auth) {
            val token = checkNotNull(authToken) { "this endpoint needs an authenticated RelayClient" }
            builder.header(AUTH_HEADER, token.headerValue)
        }
        return builder
    }

    private fun get(url: HttpUrl, auth: Boolean = true) = request(url, auth).get().build()
    private fun post(url: HttpUrl, body: RequestBody, auth: Boolean = true) = request(url, auth).post(body).build()
    private fun put(url: HttpUrl, body: RequestBody, auth: Boolean = true) = request(url, auth).put(body).build()

    private inline fun <reified T> json(value: T): RequestBody = RelayJson.encodeToString(value).toRequestBody(JSON)

    private fun client(anonymous: Boolean): OkHttpClient = if (anonymous) anonymousHttp else http

    /** [http] without an HTTP cache, for the avatar requests; shares its connections. */
    private val uncachedHttp: OkHttpClient by lazy { if (http.cache == null) http else http.newBuilder().cache(null).build() }

    private suspend inline fun <reified T> execute(
        request: Request,
        anonymous: Boolean = false,
        maxResponseBytes: Long = MAX_JSON_RESPONSE_BYTES,
    ): T = client(anonymous).newCall(request).await().use { response ->
        response.throwIfError()
        val text = withContext(Dispatchers.IO) { response.body.readUtf8(maxResponseBytes) }
            ?: throw IOException("relay response exceeds $maxResponseBytes bytes")
        try {
            RelayJson.decodeFromString<T>(text)
        } catch (e: SerializationException) {
            throw IOException("malformed relay response", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("malformed relay response", e)
        }
    }

    private suspend fun executeNoContent(request: Request, anonymous: Boolean = false, uncached: Boolean = false) {
        (if (uncached) uncachedHttp else client(anonymous)).newCall(request).await().use { it.throwIfError() }
    }

    companion object {
        const val DEFAULT_URL = "https://relay.us2us.app"
        const val AUTH_HEADER = "X-Auth-Token"

        /** Upper bound for fetch `limit` and for one ack request (§4.6). */
        const val MAX_BATCH = 500

        /** Limits of `POST /v1/messages/ephemeral` (§4.6). */
        const val MAX_EPHEMERAL_ENVELOPE_BYTES = 4 * 1024
        private const val MAX_EPHEMERAL_BATCH = 200
        private const val MAX_EPHEMERAL_BODY_BYTES = 1024 * 1024 - 64
        private const val EPHEMERAL_ITEM_OVERHEAD = 128

        /** A stored envelope in a fetch response: 64 KiB in base64url plus its id and timestamp. */
        private const val MAX_ENVELOPE_JSON_BYTES = 64L * 1024 * 4 / 3 + 256
        private const val MAX_JSON_RESPONSE_BYTES = 1024L * 1024

        /** WebSocket-level pings: OkHttp fails a socket whose pong is missing, so dead connections end quickly. */
        private const val SOCKET_PING_SECONDS = 25L
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val HTTP_NOT_MODIFIED = 304
        private const val HTTP_NOT_FOUND = 404
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024

        /** `X-Avatar-Version`: the version of an avatar delete, and the highest version in a `409` (§4.12). */
        const val AVATAR_VERSION_HEADER = "X-Avatar-Version"

        /** The longest avatar record (§4.12). */
        private const val MAX_AVATAR_RECORD_BYTES = 49_152L

        private val JSON = "application/json".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        /**
         * A copy of [http] that shares no connections and no TLS sessions with it: its own
         * connection pool, and its own `SSLContext` (and with it its own session cache), so a
         * resumed TLS session cannot link an anonymous request to an authenticated one.
         */
        private fun isolated(http: OkHttpClient): OkHttpClient {
            val builder = http.newBuilder().connectionPool(ConnectionPool())
            val trustManager = http.x509TrustManager ?: return builder.build()
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
            return builder.sslSocketFactory(tls.socketFactory, trustManager).build()
        }
    }
}

/** Error bodies are a short JSON object; anything longer is not worth reading. */
private const val MAX_ERROR_BODY_BYTES = 16L * 1024

/** Reads the body as UTF-8, or returns `null` if it is longer than [limit] bytes. */
private fun ResponseBody.readUtf8(limit: Long): String? {
    if (contentLength() > limit) return null
    val source = source()
    val buffer = Buffer()
    while (buffer.size <= limit) {
        if (source.read(buffer, 8192) == -1L) return buffer.readUtf8()
    }
    return null
}

/** Reads the body, or returns `null` if it is longer than [limit] bytes. */
private fun ResponseBody.readBytes(limit: Long): ByteArray? {
    if (contentLength() > limit) return null
    val source = source()
    val buffer = Buffer()
    while (buffer.size <= limit) {
        if (source.read(buffer, 8192) == -1L) return buffer.readByteArray()
    }
    return null
}

internal fun Response.throwIfError() {
    if (isSuccessful) return
    val bodyText = runCatching { body.readUtf8(MAX_ERROR_BODY_BYTES) }.getOrNull().orEmpty()
    val detail = runCatching { RelayJson.decodeFromString<ErrorBody>(bodyText).error }.getOrNull()
    throw RelayException(
        httpStatus = code,
        code = detail?.let { RelayErrorCode.fromWire(it.code) } ?: RelayErrorCode.UNKNOWN,
        relayMessage = detail?.message ?: message,
        retryAfterSeconds = header("Retry-After")?.trim()?.toLongOrNull(),
        avatarVersion = header(RelayClient.AVATAR_VERSION_HEADER)?.trim()?.toLongOrNull()?.takeIf { it >= 1 },
    )
}

internal val RelayJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    classDiscriminator = "type"
}

/** Suspends until the call completes; cancelling the coroutine cancels the HTTP call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

            override fun onResponse(call: Call, response: Response) =
                continuation.resume(response) { _, value, _ -> value.close() }
        },
    )
}
