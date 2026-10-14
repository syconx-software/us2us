package com.syconx.us2us.core.messenger

import com.syconx.us2us.core.AccessKey
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.AuthToken
import com.syconx.us2us.core.contacts.InMemoryContactStore
import com.syconx.us2us.core.keys.Keys
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.store.InMemoryProtocolStorage
import com.syconx.us2us.core.store.ProtocolStore
import com.syconx.us2us.core.testing.TestClient
import com.syconx.us2us.core.username.Username
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Answers token rotations from a script, in order, and records which token each one was
 * authenticated with and which new token it carried. "No answer" closes the connection: the client
 * does not retry on its own ([OkHttpClient.Builder.retryOnConnectionFailure] is off).
 */
private class ScriptedRotationRelay(answers: List<Answer>) : Closeable {
    sealed interface Answer {
        data class Status(val code: Int) : Answer

        /** The connection drops: no answer at all. */
        data object NoAnswer : Answer
    }

    enum class Token { OLD, NEW, OTHER }

    private val script = ArrayDeque(answers)
    val asked = CopyOnWriteArrayList<Token>()
    val sentTokens = CopyOnWriteArrayList<String?>()

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val token = request.headers["X-Auth-Token"]?.let { runCatching { AuthToken.parse(it) }.getOrNull() }
                asked += when (token) {
                    OLD -> Token.OLD
                    NEW -> Token.NEW
                    else -> Token.OTHER
                }
                sentTokens += request.body?.utf8()?.let { Json.parseToJsonElement(it).jsonObject["newAuthToken"]?.jsonPrimitive?.content }
                return when (val answer = synchronized(script) { script.removeFirstOrNull() } ?: Answer.NoAnswer) {
                    Answer.NoAnswer -> MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
                    is Answer.Status -> if (answer.code == 204) {
                        MockResponse.Builder().code(204).build()
                    } else {
                        MockResponse.Builder()
                            .code(answer.code)
                            .addHeader("Content-Type", "application/json")
                            .body("""{"error":{"code":"refused","message":"refused"}}""")
                            .build()
                    }
                }
            }
        }
        start()
    }

    val client = RelayClient(server.url("/").toString(), OkHttpClient.Builder().retryOnConnectionFailure(false).build())

    override fun close() = server.close()

    companion object {
        /** `T`, the backup's token. */
        val OLD = AuthToken(ByteArray(32) { 0x0A })

        /** `N`, the token the client chose. */
        val NEW = AuthToken(ByteArray(32) { (255 - it).toByte() })
    }
}

/** Token rotation: every lost answer still leaves a token that works (PROTOCOL.md §4.3, §5.5 step 3). */
class TokenRotationTest {
    private enum class Expected { COMMITTED, UNCHANGED, NO_LONGER_VALID, UNKNOWN }

    private class Case(
        /** Step 3.4 (a start with a pending token) instead of step 3.2. */
        val resuming: Boolean,
        val answers: List<ScriptedRotationRelay.Answer>,
        val expected: Expected,
        val asked: List<ScriptedRotationRelay.Token>,
    )

    private companion object {
        val NO_ANSWER = ScriptedRotationRelay.Answer.NoAnswer
        fun status(code: Int) = ScriptedRotationRelay.Answer.Status(code)
        val OLD = ScriptedRotationRelay.Token.OLD
        val NEW = ScriptedRotationRelay.Token.NEW
        val OTHER = ScriptedRotationRelay.Token.OTHER

        val cases = listOf(
            // Step 3.2, then 3.4 at once when no answer arrives.
            Case(false, listOf(status(204)), Expected.COMMITTED, listOf(OLD)),
            // The 204 was lost: asking with N finds it out.
            Case(false, listOf(NO_ANSWER, status(204)), Expected.COMMITTED, listOf(OLD, NEW)),
            // 401 for T: an earlier attempt completed in between, N works.
            Case(false, listOf(status(401), status(204)), Expected.COMMITTED, listOf(OLD, NEW)),
            Case(false, listOf(status(401), status(401)), Expected.NO_LONGER_VALID, listOf(OLD, NEW)),
            Case(false, listOf(NO_ANSWER, status(401), status(204)), Expected.COMMITTED, listOf(OLD, NEW, OLD)),
            Case(false, listOf(NO_ANSWER, status(401), status(401), status(204)), Expected.COMMITTED, listOf(OLD, NEW, OLD, NEW)),
            Case(false, listOf(NO_ANSWER, status(401), status(401), status(401)), Expected.NO_LONGER_VALID, listOf(OLD, NEW, OLD, NEW)),
            Case(false, listOf(NO_ANSWER, NO_ANSWER), Expected.UNKNOWN, listOf(OLD, NEW)),
            Case(false, listOf(status(500), status(429)), Expected.UNKNOWN, listOf(OLD, NEW)),
            Case(false, listOf(status(401), NO_ANSWER), Expected.UNKNOWN, listOf(OLD, NEW)),
            Case(false, listOf(NO_ANSWER, status(401), NO_ANSWER), Expected.UNKNOWN, listOf(OLD, NEW, OLD)),
            // 400: nothing changed.
            Case(false, listOf(status(400)), Expected.UNCHANGED, listOf(OLD)),
            Case(false, listOf(NO_ANSWER, status(401), status(400)), Expected.UNCHANGED, listOf(OLD, NEW, OLD)),
            // Step 3.4 on its own: N, then T, then N once more.
            Case(true, listOf(status(204)), Expected.COMMITTED, listOf(NEW)),
            Case(true, listOf(status(401), status(204)), Expected.COMMITTED, listOf(NEW, OLD)),
            Case(true, listOf(status(401), status(401), status(204)), Expected.COMMITTED, listOf(NEW, OLD, NEW)),
            Case(true, listOf(status(401), status(401), status(401)), Expected.NO_LONGER_VALID, listOf(NEW, OLD, NEW)),
            Case(true, listOf(NO_ANSWER), Expected.UNKNOWN, listOf(NEW)),
            Case(true, listOf(status(429)), Expected.UNKNOWN, listOf(NEW)),
            Case(true, listOf(status(503)), Expected.UNKNOWN, listOf(NEW)),
            Case(true, listOf(status(401), NO_ANSWER), Expected.UNKNOWN, listOf(NEW, OLD)),
        )
    }

    @Test
    fun `the recovery matrix`() = runBlocking {
        for ((index, case) in cases.withIndex()) {
            ScriptedRotationRelay(case.answers).use { relay ->
                val old = ScriptedRotationRelay.OLD
                val new = ScriptedRotationRelay.NEW
                val outcome = try {
                    val result = if (case.resuming) TokenRotation.resolve(old, new, relay.client) else TokenRotation.rotate(old, new, relay.client)
                    when (result) {
                        is TokenRotationOutcome.Committed -> {
                            assertEquals(new, result.token, "case $index")
                            Expected.COMMITTED
                        }
                        TokenRotationOutcome.Unchanged -> Expected.UNCHANGED
                        TokenRotationOutcome.BackupNoLongerValid -> Expected.NO_LONGER_VALID
                    }
                } catch (_: IOException) {
                    Expected.UNKNOWN
                }
                assertEquals(case.expected, outcome, "case $index")
                assertEquals(case.asked, relay.asked.toList(), "case $index")
                // Every request carries the same new token: it is never replaced while the outcome is unknown.
                assertTrue(relay.sentTokens.all { it == new.headerValue }, "case $index")
                // Finding out costs at most three 401s (§5.5 step 3.4).
                assertTrue(case.answers.take(relay.asked.size).count { it == status(401) } <= 3, "case $index")
            }
        }
    }

    private fun pendingMessenger(relay: ScriptedRotationRelay, saves: MutableList<LocalAccount>): Messenger {
        val identity = Keys.generateIdentityKeyPair()
        val account = LocalAccount(
            accountId = AccountId(UUID.randomUUID()),
            authToken = ScriptedRotationRelay.OLD,
            username = Username("bob_02"),
            identityKeyPair = identity,
            registrationId = Keys.generateRegistrationId(),
            accessKey = AccessKey.generate(),
            pendingAuthToken = ScriptedRotationRelay.NEW,
        )
        val store = ProtocolStore(identity, account.registrationId, InMemoryProtocolStorage())
        return Messenger(account, store, InMemoryContactStore(), relay.client, TestClient.testConfig) { saves += it }
    }

    @Test
    fun `400 while settling - the old token still works, so a fresh token is pending and the next call rotates to it`() = runBlocking {
        // N unknown (401), T refuses the new token (400); then N' unknown, T to N' accepted.
        ScriptedRotationRelay(listOf(status(401), status(400), status(401), status(204))).use { relay ->
            val saves = mutableListOf<LocalAccount>()
            val messenger = pendingMessenger(relay, saves)
            assertTrue(messenger.tokenRotationPending.value)

            assertThrows<TokenRefusedException> { runBlocking { messenger.finishPendingTokenRotation() } }
            val retried = messenger.account
            val fresh = checkNotNull(retried.pendingAuthToken) { "never reported as settled: the old phone keeps T" }
            assertNotEquals(ScriptedRotationRelay.NEW, fresh)
            assertEquals(ScriptedRotationRelay.OLD, retried.authToken)
            assertEquals(retried, saves.last())
            assertTrue(messenger.tokenRotationPending.value)
            // Nothing else uses either token meanwhile: not even sent.
            val asked = relay.asked.size
            assertThrows<TokenRotationPendingException> { runBlocking { messenger.maintainPreKeys() } }
            assertEquals(asked, relay.asked.size)

            assertEquals(TokenRotationResult.READY, messenger.finishPendingTokenRotation())
            val settled = messenger.account
            assertEquals(fresh, settled.authToken)
            assertNull(settled.pendingAuthToken)
            assertEquals(false, messenger.tokenRotationPending.value)
            assertEquals(listOf(NEW, OLD, OTHER, OLD), relay.asked.toList())
            val first = ScriptedRotationRelay.NEW.headerValue
            assertEquals(listOf(first, first, fresh.headerValue, fresh.headerValue), relay.sentTokens.toList())
        }
    }

    @Test
    fun `a refusal is remembered - later calls say so instead of ready, without asking again`() = runBlocking {
        ScriptedRotationRelay(listOf(status(401), status(401), status(401))).use { relay ->
            val saves = mutableListOf<LocalAccount>()
            val messenger = pendingMessenger(relay, saves)

            assertEquals(TokenRotationResult.BACKUP_NO_LONGER_VALID, messenger.finishPendingTokenRotation())
            assertNull(messenger.account.pendingAuthToken)
            assertNull(saves.last().pendingAuthToken)
            assertEquals(TokenRotationResult.BACKUP_NO_LONGER_VALID, messenger.finishPendingTokenRotation())
            assertEquals(3, relay.asked.size)
            // No backup carries a token that works for nothing.
            assertThrows<BackupNoLongerValidException> { runBlocking { messenger.exportBackup("yedek parolam 7", iterations = 1000) } }
            assertThrows<BackupNoLongerValidException> { messenger.exportBackupBlocking("yedek parolam 7", iterations = 1000) }
        }
    }

    @Test
    fun `a new token is 32 random bytes the relay accepts as random`() {
        val seen = HashSet<AuthToken>()
        repeat(64) {
            val token = TokenRotation.newToken()
            assertEquals(AuthToken.LENGTH, token.toBytes().size)
            assertTrue(token.toBytes().toSet().size >= TokenRotation.MINIMUM_DISTINCT_BYTES)
            seen += token
        }
        assertEquals(64, seen.size)
    }
}
