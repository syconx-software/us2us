package com.syconx.us2us.core.testing

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.AuthToken
import com.syconx.us2us.core.encoding.Base64Url
import com.syconx.us2us.core.pow.ProofOfWork
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json as KJson

/**
 * An in-memory relay implementing the HTTP and WebSocket API of PROTOCOL.md §4 on MockWebServer,
 * close enough to the real one to run [com.syconx.us2us.core.messenger.Messenger] end to end.
 *
 * It also watches the client: [violations] collects every request that would let a relay link a
 * sender to a recipient: an auth token on a sealed send, a message proof-of-work challenge, an
 * ephemeral batch or a media download/delete, or such an anonymous request travelling over a
 * connection that also carried authenticated requests.
 */
class FakeRelay(private val registerDifficulty: Int = 4, private val messageDifficulty: Int = 2) : Dispatcher(), Closeable {
    private val server = MockWebServer()
    private val random = SecureRandom()
    private val lock = Any()

    private class Account(val id: AccountId, var tokenHash: ByteArray, var usernameHash: String, val identityKey: String, val registrationId: Int) {
        var accessKeyHash: ByteArray? = null
        lateinit var signedPreKey: JsonObject
        lateinit var kyberLastResortKey: JsonObject
        val oneTimePreKeys = ArrayDeque<JsonObject>()
        val kyberOneTimePreKeys = ArrayDeque<JsonObject>()
        val mailbox = LinkedHashMap<String, JsonObject>()
        var socket: WebSocket? = null

        /** §4.12: the highest version of a store or delete, which outlives the record. */
        var avatarHighestVersion = 0L
        var avatarRecord: ByteArray? = null
    }

    private class Blob(val bytes: ByteArray, val deleteToken: String)

    private class Failure(val status: Int, val code: String, message: String) : Exception(message)

    private class StaleVersion(val highest: Long) : Exception("stale avatar version")

    private val accounts = HashMap<AccountId, Account>()
    private val directory = HashMap<String, AccountId>()
    private val reservedUsernames = HashMap<String, AccountId>()
    private val challenges = HashMap<String, Pair<String, String>>()
    private val media = HashMap<String, Blob>()

    /** Requests that would have leaked who talks to whom. Must stay empty. */
    val violations = CopyOnWriteArrayList<String>()

    /** How each sealed send was authorized, in order: `"access-key"` or `"pow"`, per recipient. */
    private val deliveries = CopyOnWriteArrayList<Pair<AccountId, String>>()

    /** Server-side connection indices that carried authenticated or anonymous requests. */
    private val authenticatedConnections = HashSet<Int>()
    private val anonymousConnections = HashSet<Int>()

    /** Number of `POST /v1/messages/ephemeral` requests received. */
    @Volatile
    var ephemeralBatches = 0
        private set

    /** Number of directory lookups (`GET /v1/directory/{hash}`) received. */
    @Volatile
    var directoryLookups = 0
        private set

    /** Avatar fetches (`GET /v1/accounts/{id}/avatar`) and how many of them were answered `304`. */
    @Volatile
    var avatarFetches = 0
        private set

    @Volatile
    var avatarNotModified = 0
        private set

    /** Avatar writes (`PUT` and `DELETE /v1/accounts/me/avatar`) received, whatever the answer. */
    @Volatile
    var avatarWrites = 0
        private set

    /** Behave like a relay from before §4.12: the avatar paths are unknown (`404`). */
    @Volatile
    var avatarsSupported = true

    /**
     * Behave like a relay from before client-chosen tokens (§4.3): a rotation ignores the body,
     * chooses the token itself and answers `200 {"authToken"}`.
     */
    @Volatile
    var choosesTokens = false

    /** Every request as `"METHOD /path"`, in order, also those answered by an injected failure. */
    val requests = CopyOnWriteArrayList<String>()

    /** The token each rotation (`POST /v1/accounts/me/token`) was authenticated with, in order. */
    val tokenRotations = CopyOnWriteArrayList<AuthToken>()

    /** Whether the relay holds an account [accountId]. */
    fun hasAccount(accountId: AccountId): Boolean = synchronized(lock) { accountId in accounts }

    /** Whether [token] is [accountId]'s token now. */
    fun holdsToken(accountId: AccountId, token: AuthToken): Boolean =
        synchronized(lock) { accounts[accountId]?.tokenHash?.contentEquals(sha256(token.toBytes())) == true }

    /** Another phone restored the account's backup and rotated its token first: no token this test knows works. */
    fun rotateTokenElsewhere(accountId: AccountId) = synchronized(lock) {
        accounts.getValue(accountId).tokenHash = sha256(ByteArray(32).also(random::nextBytes))
    }

    /** The avatar record the relay holds for [accountId], or `null`. */
    fun avatarRecord(accountId: AccountId): ByteArray? = synchronized(lock) { accounts[accountId]?.avatarRecord?.copyOf() }

    /** The highest avatar version of [accountId] (0 if none, or no such account). */
    fun avatarHighestVersion(accountId: AccountId): Long = synchronized(lock) { accounts[accountId]?.avatarHighestVersion ?: 0 }

    /**
     * Serves [record] (or a `404` for `null`) for [accountId] from now on, whatever was written, as a
     * hostile relay replaying an earlier record would (PROTOCOL.md §9 "Rollback"). The highest
     * version is left alone.
     */
    fun replaceAvatarRecord(accountId: AccountId, record: ByteArray?) = synchronized(lock) {
        accounts.getValue(accountId).avatarRecord = record?.copyOf()
    }

    /** JSON `ping` frames received, per account (WebSocket-level pings are not counted, as on the real relay). */
    private val pings = CopyOnWriteArrayList<AccountId>()

    fun pingsFrom(accountId: AccountId): Int = pings.count { it == accountId }

    /** Makes the next request matching [method] and [path] fail with a 503, as a flaky network would. */
    fun failNext(method: String, path: String) = failNext(matching(method, path))

    /** Makes the next request matching [matches] fail with a 503. */
    fun failNext(matches: (RecordedRequest) -> Boolean) = inject(matches, Injection.Fail)

    /**
     * The next request matching [method] and [path] is carried out, but its answer is lost: the
     * client gets a 503, as if the connection dropped on the way back.
     */
    fun loseNextAnswer(method: String, path: String) = inject(matching(method, path), Injection.LoseAnswer)

    /** Makes the next request matching [method] and [path] fail with `429` and `Retry-After: [seconds]`. */
    fun rateLimitNext(method: String, path: String, seconds: Long) = inject(matching(method, path), Injection.RateLimit(seconds))

    /** Answers the next request matching [method] and [path] with [status] and [code], as the relay refuses a request; not carried out. */
    fun refuseNext(method: String, path: String, status: Int, code: String = "bad_request") =
        inject(matching(method, path), Injection.Refuse(status, code))

    /** What happens to a request an injection matches. */
    private sealed interface Injection {
        /** A 503; the request is not carried out. */
        data object Fail : Injection

        /** Carried out, then a 503. */
        data object LoseAnswer : Injection

        /** `429` with `Retry-After`; not carried out. */
        class RateLimit(val seconds: Long) : Injection

        /** [status] with the error [code]; not carried out. */
        class Refuse(val status: Int, val code: String) : Injection
    }

    /** Used up in order: a request meets the first one registered that matches it. */
    private val injections = ArrayList<Pair<(RecordedRequest) -> Boolean, Injection>>()

    private fun inject(matches: (RecordedRequest) -> Boolean, injection: Injection) = synchronized(lock) { injections += matches to injection }

    private fun matching(method: String, path: String): (RecordedRequest) -> Boolean = { it.method == method && it.url.encodedPath == path }

    /** How the most recent sealed send to [recipient] was authorized. */
    fun lastDeliveryTo(recipient: AccountId): String? = deliveries.lastOrNull { it.first == recipient }?.second

    val url: String get() = server.url("/").toString()

    fun start(): FakeRelay {
        server.dispatcher = this
        server.start()
        return this
    }

    override fun close() = server.close()

    fun mailboxSize(accountId: AccountId): Int = synchronized(lock) { accounts[accountId]?.mailbox?.size ?: 0 }

    fun mediaCount(): Int = synchronized(lock) { media.size }

    fun oneTimeKeyCounts(accountId: AccountId): Pair<Int, Int> =
        synchronized(lock) { accounts.getValue(accountId).let { it.oneTimePreKeys.size to it.kyberOneTimePreKeys.size } }

    override fun dispatch(request: RecordedRequest): MockResponse = synchronized(lock) {
        try {
            watchConnection(request)
            requests += "${request.method} ${request.url.encodedPath}"
            if (request.method == "POST" && request.url.encodedPath == "/v1/accounts/me/token") {
                request.headers["X-Auth-Token"]?.let { header -> runCatching { AuthToken.parse(header) }.getOrNull()?.let(tokenRotations::add) }
            }
            val injected = injections.indexOfFirst { it.first(request) }
            when (val injection = if (injected >= 0) injections.removeAt(injected).second else null) {
                Injection.Fail -> error(503, "internal", "injected failure")
                Injection.LoseAnswer -> {
                    runCatching { route(request) }
                    error(503, "internal", "answer lost")
                }
                is Injection.RateLimit ->
                    error(429, "rate_limited", "injected limit").newBuilder().addHeader("Retry-After", injection.seconds.toString()).build()
                is Injection.Refuse -> error(injection.status, injection.code, "injected refusal")
                null -> route(request)
            }
        } catch (e: StaleVersion) {
            error(409, "stale_version", "avatar version is not above the highest").newBuilder()
                .addHeader("X-Avatar-Version", e.highest.toString())
                .build()
        } catch (e: Failure) {
            error(e.status, e.code, e.message ?: "")
        } catch (e: IllegalArgumentException) {
            error(400, "bad_request", e.message ?: "")
        }
    }

    private fun route(r: RecordedRequest): MockResponse {
        val path = r.url.pathSegments
        if (path.firstOrNull() != "v1") throw Failure(404, "not_found", "unknown path")
        val p = path.drop(1)
        return when {
            r.method == "GET" && p == listOf("health") -> json(200, buildJsonObject { put("ok", true); put("version", "fake") })
            r.method == "GET" && p == listOf("pow") -> challenge(r)
            r.method == "POST" && p == listOf("accounts") -> register(r)
            r.method == "GET" && p == listOf("accounts", "me") -> me(authenticate(r))
            r.method == "PUT" && p == listOf("accounts", "me", "username") -> changeUsername(authenticate(r), r)
            r.method == "POST" && p == listOf("accounts", "me", "token") -> rotateToken(authenticate(r), r)
            r.method == "PUT" && p == listOf("accounts", "me", "access-key") -> setAccessKey(authenticate(r), r)
            r.method == "DELETE" && p == listOf("accounts", "me") -> deleteAccount(authenticate(r))
            p.size == 3 && p[0] == "accounts" && p[2] == "avatar" && !avatarsSupported -> unknownAvatarPath(r)
            r.method == "PUT" && p == listOf("accounts", "me", "avatar") -> putAvatar(authenticate(r), r)
            r.method == "DELETE" && p == listOf("accounts", "me", "avatar") -> deleteAvatar(authenticate(r), r)
            r.method == "GET" && p.size == 3 && p[0] == "accounts" && p[2] == "avatar" -> fetchAvatar(r, p[1])
            r.method == "GET" && p.size == 3 && p[0] == "accounts" && p[2] == "identity" -> identity(r, AccountId.parse(p[1]))
            r.method == "GET" && p.size == 2 && p[0] == "directory" -> lookup(r, p[1])
            r.method == "PUT" && p == listOf("keys") -> uploadKeys(authenticate(r), body(r))
            r.method == "GET" && p == listOf("keys", "status") -> keyStatus(authenticate(r))
            r.method == "GET" && p.size == 2 && p[0] == "keys" -> bundle(r, AccountId.parse(p[1]))
            r.method == "POST" && p == listOf("messages", "ephemeral") -> ephemeralBatch(r)
            r.method == "PUT" && p.size == 2 && p[0] == "messages" -> send(r, AccountId.parse(p[1]))
            r.method == "GET" && p == listOf("messages") -> fetch(authenticate(r), r.url.queryParameter("limit")?.toInt() ?: 100)
            r.method == "POST" && p == listOf("messages", "ack") -> ack(authenticate(r), body(r))
            r.method == "GET" && p == listOf("ws") -> webSocket(authenticate(r))
            r.method == "POST" && p == listOf("media") -> upload(authenticate(r), r)
            r.method == "GET" && p.size == 2 && p[0] == "media" -> download(r, p[1])
            r.method == "DELETE" && p.size == 2 && p[0] == "media" -> deleteMedia(r, p[1])
            else -> throw Failure(404, "not_found", "no route for ${r.method} ${r.url.encodedPath}")
        }
    }

    // Accounts

    private fun challenge(r: RecordedRequest): MockResponse {
        val purpose = r.url.queryParameter("purpose")
        val target = when (purpose) {
            "register" -> "-"
            "message" -> {
                if (r.headers["X-Auth-Token"] != null) violations += "auth token on a message challenge"
                r.url.queryParameter("to") ?: throw Failure(400, "bad_request", "missing to")
            }
            else -> throw Failure(400, "bad_request", "bad purpose")
        }
        val token = "fake.$purpose.$target.${randomB64(16)}"
        challenges[token] = purpose to target
        val difficulty = if (purpose == "register") registerDifficulty else messageDifficulty
        return json(200, buildJsonObject { put("token", token); put("difficulty", difficulty); put("expiresAt", System.currentTimeMillis() + 600_000) })
    }

    private fun verifyPow(token: String, nonce: String, purpose: String, target: String) {
        val (issuedPurpose, issuedTarget) = challenges.remove(token) ?: throw Failure(403, "pow_invalid", "unknown or reused token")
        val difficulty = if (purpose == "register") registerDifficulty else messageDifficulty
        if (issuedPurpose != purpose || issuedTarget != target || !ProofOfWork.isValid(token, nonce.toULong(), difficulty)) {
            throw Failure(403, "pow_invalid", "wrong proof-of-work")
        }
    }

    private fun register(r: RecordedRequest): MockResponse {
        val body = body(r)
        val pow = body.obj("pow")
        verifyPow(pow.str("token"), pow.str("nonce"), "register", "-")
        val usernameHash = body.str("usernameHash").also { require(Base64Url.decode(it).size == 32) { "usernameHash" } }
        val identityKey = body.str("identityKey").also {
            val key = Base64Url.decode(it)
            require(key.size == 33 && key[0] == 0x05.toByte()) { "identityKey" }
        }
        val registrationId = body.int("registrationId").also { require(it in 1..16380) { "registrationId" } }
        if (usernameHash in directory || usernameHash in reservedUsernames) throw Failure(409, "username_taken", "taken")

        val token = ByteArray(32).also(random::nextBytes)
        val account = Account(AccountId(UUID.randomUUID()), sha256(token), usernameHash, identityKey, registrationId)
        storeKeys(account, body, replaceAll = false)
        accounts[account.id] = account
        directory[usernameHash] = account.id
        return json(201, buildJsonObject { put("accountId", account.id.toString()); put("authToken", Base64Url.encode(token)) })
    }

    private fun me(account: Account) =
        json(200, buildJsonObject { put("accountId", account.id.toString()); put("usernameHash", account.usernameHash) })

    private fun changeUsername(account: Account, r: RecordedRequest): MockResponse {
        val hash = body(r).str("usernameHash")
        val owner = directory[hash] ?: reservedUsernames[hash]
        if (owner != null && owner != account.id) throw Failure(409, "username_taken", "taken")
        directory.remove(account.usernameHash)
        reservedUsernames[account.usernameHash] = account.id
        reservedUsernames.remove(hash)
        directory[hash] = account.id
        account.usernameHash = hash
        account.avatarRecord = null // §4.3: the record goes with the old username; the version stays.
        return noContent()
    }

    /**
     * §4.3: the client chooses the token. Only the token that authenticated the request is replaced
     * (a stale one is `401`, as any unknown token here); asking again with the new token is `204`
     * without a change. An open WebSocket of the replaced token is closed with `4401`. The
     * deprecated empty body is refused, so a client that still sends it fails the tests, unless
     * [choosesTokens] makes this an older relay.
     */
    private fun rotateToken(account: Account, r: RecordedRequest): MockResponse {
        if (choosesTokens) {
            val token = ByteArray(32).also(random::nextBytes)
            replaceToken(account, sha256(token))
            return json(200, buildJsonObject { put("authToken", Base64Url.encode(token)) })
        }
        val encoded = body(r)["newAuthToken"]?.jsonPrimitive?.content ?: throw Failure(400, "bad_request", "newAuthToken missing")
        val token = runCatching { Base64Url.decode(encoded, 32) }.getOrNull() ?: throw Failure(400, "bad_request", "newAuthToken must be 32 bytes")
        if (token.toSet().size < 16) throw Failure(400, "bad_request", "newAuthToken must be 32 random bytes")
        val hash = sha256(token)
        if (account.tokenHash.contentEquals(hash)) return noContent()
        if (accounts.values.any { it.tokenHash.contentEquals(hash) }) throw Failure(400, "bad_request", "newAuthToken cannot be used")
        replaceToken(account, hash)
        return noContent()
    }

    private fun replaceToken(account: Account, hash: ByteArray) {
        account.tokenHash = hash
        account.socket?.close(4401, "token rotated")
    }

    private fun setAccessKey(account: Account, r: RecordedRequest): MockResponse {
        val value = body(r)["accessKeyHash"] ?: throw Failure(400, "bad_request", "accessKeyHash missing")
        account.accessKeyHash = if (value is JsonNull) null else Base64Url.decode(value.jsonPrimitive.content, 32)
        return noContent()
    }

    private fun deleteAccount(account: Account): MockResponse {
        accounts.remove(account.id)
        directory.remove(account.usernameHash)
        account.socket?.close(1000, null)
        return noContent()
    }

    private fun identity(r: RecordedRequest, id: AccountId): MockResponse {
        authenticate(r)
        val account = accounts[id] ?: throw Failure(404, "not_found", "no account")
        return json(200, buildJsonObject { put("accountId", id.toString()); put("identityKey", account.identityKey) })
    }

    private fun lookup(r: RecordedRequest, hash: String): MockResponse {
        authenticate(r)
        directoryLookups++
        val account = directory[hash]?.let(accounts::get) ?: throw Failure(404, "not_found", "no such username")
        return json(200, buildJsonObject { put("accountId", account.id.toString()); put("identityKey", account.identityKey) })
    }

    // Avatar records (§4.12)

    /** A relay from before §4.12: the request is counted, and the path is unknown. */
    private fun unknownAvatarPath(r: RecordedRequest): MockResponse {
        if (r.method == "GET") avatarFetches++ else avatarWrites++
        throw Failure(404, "not_found", "no route for ${r.method} ${r.url.encodedPath}")
    }

    private fun putAvatar(account: Account, r: RecordedRequest): MockResponse {
        avatarWrites++
        val record = r.body?.toByteArray() ?: ByteArray(0)
        if (record.size > AVATAR_MAX_LENGTH) throw Failure(413, "payload_too_large", "avatar record too large")
        if (record.size !in AVATAR_LENGTHS) throw Failure(400, "bad_request", "avatar record length")
        if (!record.copyOfRange(0, 5).contentEquals(byteArrayOf(0x42, 0x52, 0x50, 0x41, 0x01))) throw Failure(400, "bad_request", "avatar record header")
        val version = (5 until 13).fold(0L) { v, i -> (v shl 8) or (record[i].toLong() and 0xff) }
        if (version < 1) throw Failure(400, "bad_request", "avatar record version")
        checkAvatarVersion(account, version)
        account.avatarHighestVersion = version
        account.avatarRecord = record
        return MockResponse.Builder().code(204).addHeader("ETag", "\"$version\"").build()
    }

    private fun deleteAvatar(account: Account, r: RecordedRequest): MockResponse {
        avatarWrites++
        val header = r.headers["X-Avatar-Version"] ?: throw Failure(400, "bad_request", "X-Avatar-Version missing")
        val version = header.takeIf { it.matches(Regex("[0-9]{1,19}")) }?.toLongOrNull()?.takeIf { it >= 1 }
            ?: throw Failure(400, "bad_request", "malformed X-Avatar-Version")
        checkAvatarVersion(account, version)
        account.avatarHighestVersion = version
        account.avatarRecord = null
        return noContent()
    }

    private fun checkAvatarVersion(account: Account, version: Long) {
        if (version <= account.avatarHighestVersion) throw StaleVersion(account.avatarHighestVersion)
    }

    private fun fetchAvatar(r: RecordedRequest, id: String): MockResponse {
        authenticate(r)
        avatarFetches++
        val accountId = AccountId.parse(id)
        val account = accounts[accountId]
        val record = account?.avatarRecord ?: throw Failure(404, "not_found", "no avatar record")
        // The version in the stored record's header, as the relay reads it (§4.12).
        val etag = "\"${(5 until 13).fold(0L) { version, i -> (version shl 8) or (record[i].toLong() and 0xff) }}\""
        if (r.headers["If-None-Match"] == etag) {
            avatarNotModified++
            return MockResponse.Builder().code(304).addHeader("ETag", etag).build()
        }
        return MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "application/octet-stream")
            .addHeader("ETag", etag)
            .addHeader("Cache-Control", "no-store")
            .body(Buffer().write(record))
            .build()
    }

    // Keys

    private fun storeKeys(account: Account, body: JsonObject, replaceAll: Boolean) {
        if (replaceAll) {
            account.oneTimePreKeys.clear()
            account.kyberOneTimePreKeys.clear()
        }
        body["signedPreKey"]?.let { account.signedPreKey = it.jsonObject.also(::checkSigned) }
        body["kyberLastResortKey"]?.let { account.kyberLastResortKey = it.jsonObject.also(::checkSigned) }
        fun append(target: ArrayDeque<JsonObject>, list: JsonElement?, signed: Boolean) {
            val entries = list?.jsonArray?.map { it.jsonObject } ?: return
            require(entries.size <= 100) { "too many keys" }
            for (entry in entries) {
                if (signed) checkSigned(entry)
                if (target.none { it.int("keyId") == entry.int("keyId") } && target.size < 200) target += entry
            }
        }
        append(account.oneTimePreKeys, body["oneTimePreKeys"], signed = false)
        append(account.kyberOneTimePreKeys, body["kyberOneTimePreKeys"], signed = true)
    }

    private fun checkSigned(entry: JsonObject) {
        require(entry.int("keyId") in 1..0xFFFFFF) { "keyId" }
        require(Base64Url.decode(entry.str("signature")).size == 64) { "signature" }
    }

    private fun uploadKeys(account: Account, body: JsonObject): MockResponse {
        storeKeys(account, body, replaceAll = body["replaceAll"]?.jsonPrimitive?.content == "true")
        return noContent()
    }

    private fun keyStatus(account: Account) = json(
        200,
        buildJsonObject {
            put("oneTimePreKeys", account.oneTimePreKeys.size)
            put("kyberOneTimePreKeys", account.kyberOneTimePreKeys.size)
            put("signedPreKeyId", account.signedPreKey.int("keyId"))
            put("kyberLastResortKeyId", account.kyberLastResortKey.int("keyId"))
        },
    )

    private fun bundle(r: RecordedRequest, id: AccountId): MockResponse {
        authenticate(r)
        val account = accounts[id] ?: throw Failure(404, "not_found", "no account")
        return json(
            200,
            buildJsonObject {
                put("accountId", id.toString())
                put("deviceId", 1)
                put("registrationId", account.registrationId)
                put("identityKey", account.identityKey)
                put("signedPreKey", account.signedPreKey)
                put("kyberPreKey", account.kyberOneTimePreKeys.removeFirstOrNull() ?: account.kyberLastResortKey)
                put("oneTimePreKey", account.oneTimePreKeys.removeFirstOrNull() ?: JsonNull)
            },
        )
    }

    // Messages

    private fun send(r: RecordedRequest, to: AccountId): MockResponse {
        if (r.headers["X-Auth-Token"] != null) violations += "auth token on a sealed send"
        val recipient = accounts[to] ?: throw Failure(404, "not_found", "no recipient")
        val accessKey = r.headers["X-Access-Key"]
        val pow = r.headers["X-PoW"]
        val envelope = r.body?.toByteArray() ?: ByteArray(0)
        if (envelope.size > 64 * 1024) throw Failure(413, "payload_too_large", "envelope too large")
        val id = randomB64(16)
        val timestamp = System.currentTimeMillis()
        val receipt = json(202, buildJsonObject { put("id", id); put("serverTimestamp", timestamp) })

        if (r.url.queryParameter("ephemeral") == "1") {
            // §4.6: access key only, and the same answer whether or not anything was delivered.
            if (accessKey == null) throw Failure(403, "access_denied", "ephemeral sends need an access key")
            val key = runCatching { Base64Url.decode(accessKey, 16) }.getOrNull()
            if (key != null && recipient.accessKeyHash?.contentEquals(sha256(key)) == true) forwardEphemeral(recipient, envelope)
            return receipt
        }
        when {
            accessKey != null -> {
                val expected = recipient.accessKeyHash
                if (expected == null || !expected.contentEquals(sha256(Base64Url.decode(accessKey, 16)))) {
                    throw Failure(403, "access_denied", "wrong access key")
                }
                deliveries += to to "access-key"
            }
            pow != null -> {
                val split = pow.lastIndexOf(':')
                verifyPow(pow.substring(0, split), pow.substring(split + 1), "message", to.toString())
                deliveries += to to "pow"
            }
            else -> throw Failure(403, "access_denied", "no access key or proof-of-work")
        }
        val frame = buildJsonObject {
            put("type", "message")
            put("id", id)
            put("serverTimestamp", timestamp)
            put("envelope", Base64Url.encode(envelope))
        }
        recipient.mailbox[id] = frame
        recipient.socket?.send(frame.toString())
        return receipt
    }

    private fun ephemeralBatch(r: RecordedRequest): MockResponse {
        if (r.headers["X-Auth-Token"] != null) violations += "auth token on an ephemeral batch"
        if (r.bodySize > 1024 * 1024) throw Failure(413, "payload_too_large", "batch too large")
        ephemeralBatches++
        val items = body(r)["messages"]?.jsonArray ?: throw Failure(400, "bad_request", "messages missing")
        require(items.size in 1..200) { "1..200 messages" }
        for (item in items.map { it.jsonObject }) {
            val envelope = Base64Url.decode(item.str("envelope"))
            require(envelope.size <= 4096) { "ephemeral envelope too large" }
            val recipient = accounts[AccountId.parse(item.str("to"))] ?: continue
            val key = Base64Url.decode(item.str("accessKey"), 16)
            if (recipient.accessKeyHash?.contentEquals(sha256(key)) == true) forwardEphemeral(recipient, envelope)
        }
        return json(202, buildJsonObject { })
    }

    /** Ephemeral traffic is forwarded to a connected recipient and never stored. */
    private fun forwardEphemeral(recipient: Account, envelope: ByteArray) {
        val socket = recipient.socket ?: return
        val frame = buildJsonObject {
            put("type", "message")
            put("id", randomB64(16))
            put("serverTimestamp", System.currentTimeMillis())
            put("envelope", Base64Url.encode(envelope))
        }
        socket.send(frame.toString())
    }

    private fun fetch(account: Account, limit: Int): MockResponse {
        val page = account.mailbox.values.take(limit)
        return json(
            200,
            buildJsonObject {
                put("messages", JsonArray(page.map { m -> JsonObject(m.filterKeys { it != "type" }) }))
                put("more", account.mailbox.size > page.size)
            },
        )
    }

    private fun ack(account: Account, body: JsonObject): MockResponse {
        body["ids"]!!.jsonArray.forEach { account.mailbox.remove(it.jsonPrimitive.content) }
        return noContent()
    }

    private fun webSocket(account: Account): MockResponse = MockResponse.Builder().webSocketUpgrade(
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
                account.socket?.close(4001, "replaced")
                account.socket = webSocket
                account.mailbox.values.forEach { webSocket.send(it.toString()) }
                webSocket.send("""{"type":"queue_empty"}""")
                Unit
            }

            override fun onMessage(webSocket: WebSocket, text: String) = synchronized(lock) {
                val frame = KJson.parseToJsonElement(text).jsonObject
                when (frame.str("type")) {
                    "ack" -> frame["ids"]!!.jsonArray.forEach { account.mailbox.remove(it.jsonPrimitive.content) }
                    "ping" -> {
                        pings += account.id
                        webSocket.send("""{"type":"pong"}""")
                    }
                }
                Unit
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = detach(webSocket)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = detach(webSocket)

            private fun detach(webSocket: WebSocket) = synchronized(lock) {
                if (account.socket == webSocket) account.socket = null
            }
        },
    ).build()

    // Media

    private fun upload(account: Account, r: RecordedRequest): MockResponse {
        val id = randomB64(16)
        val deleteToken = randomB64(32)
        media[id] = Blob(r.body?.toByteArray() ?: ByteArray(0), deleteToken)
        return json(201, buildJsonObject { put("mediaId", id); put("deleteToken", deleteToken); put("expiresAt", System.currentTimeMillis()) })
    }

    private fun download(r: RecordedRequest, id: String): MockResponse {
        if (r.headers["X-Auth-Token"] != null) violations += "auth token on a media download"
        val blob = media[id] ?: throw Failure(404, "not_found", "no blob")
        val range = r.headers["Range"]?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
        val bytes = if (range == null) blob.bytes else blob.bytes.copyOfRange(range, blob.bytes.size)
        return MockResponse.Builder().code(if (range == null) 200 else 206).body(Buffer().write(bytes)).build()
    }

    private fun deleteMedia(r: RecordedRequest, id: String): MockResponse {
        if (r.headers["X-Auth-Token"] != null) violations += "auth token on a media delete"
        val blob = media[id] ?: throw Failure(404, "not_found", "no blob")
        if (r.headers["X-Delete-Token"] != blob.deleteToken) throw Failure(403, "access_denied", "wrong delete token")
        media.remove(id)
        return noContent()
    }

    // Plumbing

    /** Requests the client must keep apart: authenticated ones, and those that hide the sender. */
    private fun watchConnection(r: RecordedRequest) {
        val p = r.url.pathSegments.drop(1)
        val anonymous = when {
            r.headers["X-Auth-Token"] != null -> false
            r.method == "PUT" && p.firstOrNull() == "messages" -> true
            r.method == "POST" && p == listOf("messages", "ephemeral") -> true
            r.method == "GET" && p == listOf("pow") && r.url.queryParameter("purpose") == "message" -> true
            p.firstOrNull() == "media" && (r.method == "GET" || r.method == "DELETE") -> true
            else -> return
        }
        val (same, other) = if (anonymous) anonymousConnections to authenticatedConnections else authenticatedConnections to anonymousConnections
        same += r.connectionIndex
        if (r.connectionIndex in other) violations += "an anonymous request shared a connection with authenticated ones"
    }

    private fun authenticate(r: RecordedRequest): Account {
        val header = r.headers["X-Auth-Token"] ?: throw Failure(401, "unauthorized", "missing token")
        val hash = sha256(Base64Url.decode(header))
        return accounts.values.firstOrNull { it.tokenHash.contentEquals(hash) } ?: throw Failure(401, "unauthorized", "bad token")
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun randomB64(bytes: Int) = Base64Url.encode(ByteArray(bytes).also(random::nextBytes))

    private fun body(r: RecordedRequest): JsonObject = KJson.parseToJsonElement(r.body?.utf8() ?: "{}").jsonObject

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content ?: throw IllegalArgumentException("missing $key")
    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.int ?: throw IllegalArgumentException("missing $key")
    private fun JsonObject.obj(key: String) = this[key]?.jsonObject ?: throw IllegalArgumentException("missing $key")

    private fun json(code: Int, body: JsonObject) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body.toString()).build()

    private fun noContent() = MockResponse.Builder().code(204).build()

    private companion object {
        val AVATAR_LENGTHS = setOf(8192, 16384, 32768, 49152)
        const val AVATAR_MAX_LENGTH = 49152
    }

    private fun error(status: Int, code: String, message: String) = json(
        status,
        buildJsonObject { put("error", buildJsonObject { put("code", code); put("message", message) }) },
    )
}
