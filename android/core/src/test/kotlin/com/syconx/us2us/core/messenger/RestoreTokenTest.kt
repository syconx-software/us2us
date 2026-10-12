package com.syconx.us2us.core.messenger

import com.syconx.us2us.core.AuthToken
import com.syconx.us2us.core.contacts.InMemoryContactStore
import com.syconx.us2us.core.proto.AccountBackup
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.relay.RelayException
import com.syconx.us2us.core.store.InMemoryProtocolStorage
import com.syconx.us2us.core.store.ProtocolStore
import com.syconx.us2us.core.testing.FakeRelay
import com.syconx.us2us.core.testing.TestClient
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.core.vault.BackupFile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/** Restore with a token the client chose (PROTOCOL.md §5.5 step 3), against [FakeRelay]. */
class RestoreTokenTest {
    private val relay = FakeRelay().start()
    private val saves = mutableListOf<LocalAccount>()

    /** An installation's stores, kept across its restarts. */
    private class Phone {
        val storage = InMemoryProtocolStorage()
        val contacts = InMemoryContactStore()
    }

    private val oldPhone = Phone()
    private val newPhone = Phone()

    @AfterEach
    fun stopRelay() {
        assertEquals(emptyList<String>(), relay.violations)
        relay.close()
    }

    private suspend fun register(name: String): Messenger =
        Messenger.register(Username(name), RelayClient(relay.url), oldPhone.storage, oldPhone.contacts, TestClient.testConfig)

    private suspend fun restore(file: ByteArray): Messenger =
        Messenger.restore(file, PASSWORD, RelayClient(relay.url), newPhone.storage, newPhone.contacts, TestClient.testConfig) { saves += it }

    /** The installation on [phone] started again from what was saved. */
    private fun restart(account: LocalAccount, phone: Phone = oldPhone): Messenger = Messenger(
        account,
        ProtocolStore(account.identityKeyPair, account.registrationId, phone.storage),
        phone.contacts,
        RelayClient(relay.url),
        TestClient.testConfig,
    ) { saves += it }

    @Test
    fun `a lost 204 is found out with the new token, and the old phone is locked out`() = runBlocking {
        val bob = register("bob_02")
        val file = bob.exportBackup(PASSWORD, iterations = 1000)
        val backupToken = bob.account.authToken
        relay.loseNextAnswer("POST", "/v1/accounts/me/token")

        val restored = restore(file)
        val account = restored.account
        assertNull(account.pendingAuthToken)
        assertNotEquals(backupToken, account.authToken)
        assertTrue(relay.holdsToken(bob.accountId, account.authToken))
        assertEquals(listOf(backupToken, account.authToken), relay.tokenRotations.toList())
        assertEquals(setOf(PendingSetup.ANNOUNCE_PROFILE), account.pendingSetup)
        assertFalse(restored.tokenRotationPending.value)
        assertEquals(401, assertThrows<RelayException> { runBlocking { bob.maintainPreKeys() } }.httpStatus)
        assertFalse(restored.maintainPreKeys())
    }

    @Test
    fun `without any answer both tokens are kept, neither is used, and the next start settles it`() = runBlocking {
        val bob = register("bob_02")
        val carol = register("carol_03")
        val file = bob.exportBackup(PASSWORD, iterations = 1000)
        val backupToken = bob.account.authToken
        // Carried out, answer lost; then the question with the new token gets no answer either.
        relay.loseNextAnswer("POST", "/v1/accounts/me/token")
        relay.failNext("POST", "/v1/accounts/me/token")

        val restored = restore(file)
        val pending = restored.account
        val chosen = checkNotNull(pending.pendingAuthToken)
        assertEquals(backupToken, pending.authToken)
        assertEquals(pending, saves.last())
        assertTrue(relay.holdsToken(bob.accountId, chosen))
        assertTrue(restored.tokenRotationPending.value)
        // Nothing else uses either token meanwhile: no key upload, no avatar write, and a call fails
        // before anything is sent.
        assertFalse("PUT /v1/keys" in relay.requests)
        assertEquals(AvatarRecordStatus.PENDING, restored.avatarRecordStatus.value)
        val sent = relay.requests.size
        assertThrows<TokenRotationPendingException> { runBlocking { restored.maintainPreKeys() } }
        assertThrows<TokenRotationPendingException> { runBlocking { restored.findAccount(Username("bob_02")) } }
        assertEquals(AvatarRecordStatus.PENDING, restored.trySyncAvatarRecord())
        assertNull(restored.searchAvatar(Username("carol_03"), carol.accountId))
        assertThrows<TokenRotationPendingException> { restored.exportBackupBlocking(PASSWORD, iterations = 1000) }
        assertEquals(sent, relay.requests.size)

        // The next start, from what was saved.
        val resumed = restart(pending, newPhone)
        assertTrue(resumed.tokenRotationPending.value)
        resumed.performMaintenance()
        val account = resumed.account
        assertEquals(chosen, account.authToken)
        assertNull(account.pendingAuthToken)
        assertEquals(emptySet<PendingSetup>(), account.pendingSetup)
        assertEquals(listOf(backupToken, chosen, chosen), relay.tokenRotations.toList())
        assertEquals(account, saves.last())
        assertFalse(resumed.tokenRotationPending.value)
        assertEquals(AvatarRecordStatus.IN_SYNC, resumed.avatarRecordStatus.value)
    }

    @Test
    fun `401 for both tokens - the backup is no longer valid and nothing else is sent`() = runBlocking {
        val bob = register("bob_02")
        val file = bob.exportBackup(PASSWORD, iterations = 1000)
        // Another phone restored the same backup and rotated first.
        relay.rotateTokenElsewhere(bob.accountId)

        assertThrows<BackupNoLongerValidException> { runBlocking { restore(file) } }
        assertEquals(2, relay.tokenRotations.size)
        assertNull(saves.last().pendingAuthToken)
        assertFalse("PUT /v1/keys" in relay.requests)
    }

    @Test
    fun `400 for the new token - the restore fails, the backup's token alone is saved, and the backup still works`() = runBlocking {
        val bob = register("bob_02")
        val file = bob.exportBackup(PASSWORD, iterations = 1000)
        val backupToken = bob.account.authToken
        relay.refuseNext("POST", "/v1/accounts/me/token", 400)

        assertThrows<TokenRefusedException> { runBlocking { restore(file) } }
        val left = saves.last()
        assertEquals(backupToken, left.authToken)
        assertNull(left.pendingAuthToken, "nothing pending: the relay's answer is known")
        assertEquals(1, relay.tokenRotations.size)
        assertTrue(relay.holdsToken(bob.accountId, backupToken), "nothing changed on the relay")
        assertFalse("PUT /v1/keys" in relay.requests)

        // The app erases what the failed restore left; the next try on fresh stores works.
        val again = Phone()
        val restored = Messenger.restore(file, PASSWORD, RelayClient(relay.url), again.storage, again.contacts, TestClient.testConfig) { saves += it }
        assertNull(restored.account.pendingAuthToken)
        assertNotEquals(backupToken, restored.account.authToken)
        assertTrue(relay.holdsToken(bob.accountId, restored.account.authToken))
    }

    @Test
    fun `a relay that chooses the token itself is answered with that token`() = runBlocking {
        val bob = register("bob_02")
        val file = bob.exportBackup(PASSWORD, iterations = 1000)
        relay.choosesTokens = true

        val restored = restore(file)
        val chosen = checkNotNull(saves.first().pendingAuthToken)
        assertNotEquals(chosen, restored.account.authToken, "the relay's own token is the account's")
        assertTrue(relay.holdsToken(bob.accountId, restored.account.authToken))
        assertNull(restored.account.pendingAuthToken)
        assertFalse(restored.maintainPreKeys())
    }

    @Test
    fun `a start with a pending token that never went out - N is refused, T rotates now`() = runBlocking {
        val bob = register("bob_02")
        val original = bob.account
        val chosen = TokenRotation.newToken()

        val resumed = restart(original.copy(pendingAuthToken = chosen))
        assertEquals(TokenRotationResult.READY, resumed.finishPendingTokenRotation())
        assertEquals(chosen, resumed.account.authToken)
        assertNull(resumed.account.pendingAuthToken)
        assertEquals(listOf(chosen, original.authToken), relay.tokenRotations.toList())
        assertEquals(401, assertThrows<RelayException> { runBlocking { bob.maintainPreKeys() } }.httpStatus)
        // Nothing pending any more: no further requests.
        assertEquals(TokenRotationResult.READY, resumed.finishPendingTokenRotation())
        assertEquals(2, relay.tokenRotations.size)
    }

    @Test
    fun `while the outcome is unknown the pending token is never replaced, and concurrent callers share one attempt`() = runBlocking {
        val bob = register("bob_02")
        val original = bob.account
        val chosen = TokenRotation.newToken()
        val resumed = restart(original.copy(pendingAuthToken = chosen))

        repeat(3) {
            relay.failNext("POST", "/v1/accounts/me/token")
            assertThrows<IOException> { runBlocking { resumed.finishPendingTokenRotation() } }
            assertEquals(chosen, resumed.account.pendingAuthToken)
            assertEquals(original.authToken, resumed.account.authToken)
        }
        assertTrue(saves.isEmpty())
        assertEquals(listOf(chosen, chosen, chosen), relay.tokenRotations.toList())

        val results = coroutineScope { List(2) { async { resumed.finishPendingTokenRotation() } }.awaitAll() }
        assertEquals(listOf(TokenRotationResult.READY, TokenRotationResult.READY), results)
        assertEquals(5, relay.tokenRotations.size, "N refused, then T rotated: one attempt")
        assertEquals(1, saves.size)
        assertEquals(chosen, resumed.account.authToken)
    }

    @Test
    fun `the receive loop settles a pending rotation before it connects, and stops when both tokens are refused`() = runBlocking {
        val bob = register("bob_02")
        val original = bob.account
        val chosen = TokenRotation.newToken()
        coroutineScope {
            // The first question gets no answer: the loop asks again after its backoff.
            relay.failNext("POST", "/v1/accounts/me/token")
            val resumed = TestClient(restart(original.copy(pendingAuthToken = chosen)), this)
            resumed.awaitUpToDate()
            assertEquals(chosen, resumed.messenger.account.authToken)
            assertEquals(listOf(chosen, chosen, original.authToken), relay.tokenRotations.toList())
            resumed.job.cancelAndJoin()
        }

        val carol = register("carol_03")
        relay.rotateTokenElsewhere(carol.accountId)
        val signedOut = restart(carol.account.copy(pendingAuthToken = TokenRotation.newToken()))
        val rejected = assertThrows<ConnectionRejectedException> { runBlocking { signedOut.runReceiveLoop { } } }
        assertEquals(401, rejected.httpStatus)
        assertEquals(TokenRotationResult.BACKUP_NO_LONGER_VALID, signedOut.finishPendingTokenRotation())
    }

    @Test
    fun `a backup and an account deletion settle a pending rotation first`() = runBlocking {
        val bob = register("bob_02")
        val chosen = TokenRotation.newToken()
        val resumed = restart(bob.account.copy(pendingAuthToken = chosen))
        val file = resumed.exportBackup(PASSWORD, iterations = 1000)
        val backup = AccountBackup.parseFrom(BackupFile.open(file, PASSWORD))
        assertEquals(chosen, AuthToken(backup.authToken.toByteArray()), "the token the relay holds")

        val dave = register("dave_04")
        val pending = restart(dave.account.copy(pendingAuthToken = TokenRotation.newToken()))
        pending.deleteAccount()
        assertFalse(relay.hasAccount(dave.accountId))
    }

    private companion object {
        const val PASSWORD = "yedek parolam 7"
    }
}
