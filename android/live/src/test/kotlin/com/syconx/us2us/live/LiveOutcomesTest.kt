package com.syconx.us2us.live

import com.syconx.us2us.core.messenger.BackupNoLongerValidException
import com.syconx.us2us.core.messenger.InvalidBackupException
import com.syconx.us2us.core.messenger.TokenRefusedException
import com.syconx.us2us.core.messenger.TokenRotationPendingException
import com.syconx.us2us.core.relay.RelayErrorCode
import com.syconx.us2us.core.relay.RelayException
import com.syconx.us2us.core.vault.BackupPasswordProblem
import com.syconx.us2us.core.vault.InvalidBackupPasswordException
import com.syconx.us2us.core.vault.UnlockAttemptPolicy
import com.syconx.us2us.core.vault.UnlockStatus as CoreUnlockStatus
import com.syconx.us2us.core.vault.WrongBackupPasswordException
import com.syconx.us2us.domain.AccountCreationFailure
import com.syconx.us2us.domain.BackupOpenResult
import com.syconx.us2us.domain.BackupPasswordError
import com.syconx.us2us.domain.BackupPasswordRules
import com.syconx.us2us.domain.MessengerException
import com.syconx.us2us.domain.RestoreAccessCheck
import com.syconx.us2us.domain.RestoreFailure
import com.syconx.us2us.domain.SecretCodeError
import com.syconx.us2us.domain.UnlockAttemptResult
import com.syconx.us2us.domain.UnlockStatus
import com.syconx.us2us.domain.UnlockThrottle
import com.syconx.us2us.domain.UpcomingDelay
import com.syconx.us2us.runtime.facade.RuntimeError
import com.syconx.us2us.runtime.facade.RuntimeException
import com.syconx.us2us.runtime.facade.TokenRotationCheck
import com.syconx.us2us.runtime.vault.BiometricError
import com.syconx.us2us.runtime.vault.UnlockError
import com.syconx.us2us.runtime.vault.UnlockException
import com.syconx.us2us.runtime.vault.VaultError
import com.syconx.us2us.runtime.vault.VaultException
import java.io.IOException
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live service's decisions on the runtime's answers (iOS `RealMessengerService`'s static functions). */
class LiveOutcomesTest {
    private val status = UnlockStatus(failures = 9, nextAttemptAllowedAt = null, upcoming = UnlockThrottle.upcomingDelay(9))

    private fun vault(error: VaultError) = UnlockException(UnlockError.Vault(error))

    @Test
    fun theWipeIsReportedAsSuchNeverAsAWrongCode() {
        assertEquals(UnlockAttemptResult.Wiped, LiveOutcomes.attemptResult(vault(VaultError.Wiped), status))
        assertEquals(UnlockAttemptResult.Wiped, LiveOutcomes.attemptResult(VaultException(VaultError.Wiped), status))
        assertEquals(UnlockAttemptResult.WrongCode(status), LiveOutcomes.attemptResult(vault(VaultError.WrongCode), status))
    }

    @Test
    fun onlyACodeTheVaultRejectedIsAWrongCode() {
        val until = Instant.ofEpochSecond(1_791_000_600)
        assertEquals(UnlockAttemptResult.Throttled(until), LiveOutcomes.attemptResult(vault(VaultError.LockedOut(until)), status))
        assertEquals(UnlockAttemptResult.Malformed, LiveOutcomes.attemptResult(vault(VaultError.MalformedCode), status))
        assertEquals(UnlockAttemptResult.VaultUnusable, LiveOutcomes.attemptResult(vault(VaultError.DeviceSecretMissing), status))
        assertEquals(UnlockAttemptResult.VaultUnusable, LiveOutcomes.attemptResult(vault(VaultError.Corrupted), status))
        // Keystore, storage and biometric failures may pass: never counted, never “Kod yanlış”.
        assertEquals(UnlockAttemptResult.VaultUnavailable, LiveOutcomes.attemptResult(vault(VaultError.Storage("keystore")), status))
        assertEquals(UnlockAttemptResult.VaultUnavailable, LiveOutcomes.attemptResult(UnlockException(UnlockError.Busy), status))
        assertEquals(
            UnlockAttemptResult.VaultUnavailable,
            LiveOutcomes.attemptResult(UnlockException(UnlockError.Biometric(BiometricError.entries.first())), status),
        )
        assertEquals(UnlockAttemptResult.VaultUnavailable, LiveOutcomes.attemptResult(IOException("disk"), status))
        assertEquals(UnlockAttemptResult.VaultUnavailable, LiveOutcomes.attemptResult(RuntimeException(RuntimeError.Locked), status))
    }

    @Test
    fun theLockShowsTheCoresStatus() {
        assertEquals(UnlockStatus.Initial, LiveOutcomes.unlockStatus(null))
        val until = Instant.ofEpochSecond(1_791_000_060)
        val mapped = LiveOutcomes.unlockStatus(CoreUnlockStatus(3, until, UnlockAttemptPolicy.upcomingDelay(3)))
        assertEquals(3, mapped.failures)
        assertEquals(until, mapped.nextAttemptAllowedAt)
        assertEquals(UnlockThrottle.upcomingDelay(3), mapped.upcoming)
        assertEquals(UpcomingDelay(1, 5.minutes), mapped.upcoming)
    }

    @Test
    fun aTakenNameIsOfferedAnotherOneEverythingElsePassesThrough() {
        val taken = LiveOutcomes.creationFailure(RelayException(409, RelayErrorCode.USERNAME_TAKEN, "taken"))
        assertTrue(taken is MessengerException.AccountCreation)
        assertEquals(AccountCreationFailure.UsernameTaken, (taken as MessengerException.AccountCreation).failure)
        val offline = IOException("offline")
        assertSame(offline, LiveOutcomes.creationFailure(offline))
    }

    @Test
    fun aDeadOrDamagedBackupIsSaidAsSuch() {
        assertEquals(RestoreFailure.BackupNoLongerValid, LiveOutcomes.restoreFailure(RuntimeException(RuntimeError.BackupRevoked)))
        assertEquals(RestoreFailure.BackupDamaged, LiveOutcomes.restoreFailure(RuntimeException(RuntimeError.UnreadableBackup)))
        assertEquals(RestoreFailure.BackupNoLongerValid, LiveOutcomes.restoreFailure(relay(401)))
        assertEquals(RestoreFailure.BackupNoLongerValid, LiveOutcomes.restoreFailure(relay(404)))
        assertEquals(RestoreFailure.BackupDamaged, LiveOutcomes.restoreFailure(InvalidBackupException("malformed")))
        // No connection, the relay busy: nothing changed, 2.3 offers to try again.
        assertNull(LiveOutcomes.restoreFailure(relay(503)))
        assertNull(LiveOutcomes.restoreFailure(IOException("offline")))
        assertNull(LiveOutcomes.restoreFailure(RuntimeException(RuntimeError.Locked)))
    }

    @Test
    fun aRestoresTokenRotationIsSaidAsTheRelayAnswered() {
        // 401 for the backup's token and the new one: restored elsewhere first, or deleted.
        assertEquals(RestoreFailure.BackupNoLongerValid, LiveOutcomes.restoreFailure(BackupNoLongerValidException()))
        assertEquals(BackupOpenResult.NoLongerValid, LiveOutcomes.openResult(BackupNoLongerValidException()))
        // 400 for the new token alone: the backup still works, 2.3 offers to try again.
        assertNull(LiveOutcomes.restoreFailure(TokenRefusedException()))
        // No answer yet: nothing was sent with either token.
        assertNull(LiveOutcomes.restoreFailure(TokenRotationPendingException()))
        assertEquals(BackupOpenResult.Unverified, LiveOutcomes.openResult(TokenRotationPendingException()))
    }

    @Test
    fun sevenThreeAnswersEveryBackupCheck() {
        assertEquals(BackupOpenResult.WrongPassword, LiveOutcomes.openResult(WrongBackupPasswordException()))
        assertEquals(BackupOpenResult.NoLongerValid, LiveOutcomes.openResult(RuntimeException(RuntimeError.BackupRevoked)))
        assertEquals(BackupOpenResult.Damaged, LiveOutcomes.openResult(InvalidBackupException("version")))
        assertEquals(BackupOpenResult.Unverified, LiveOutcomes.openResult(IOException("offline")))
        assertEquals(BackupOpenResult.Unverified, LiveOutcomes.openResult(relay(500)))
    }

    @Test
    fun theRestoreAccessCheckMapsOneToOne() {
        assertEquals(RestoreAccessCheck.Closed, LiveOutcomes.restoreAccess(TokenRotationCheck.SETTLED))
        assertEquals(RestoreAccessCheck.Unanswered, LiveOutcomes.restoreAccess(TokenRotationCheck.UNANSWERED))
        assertEquals(RestoreAccessCheck.Refused, LiveOutcomes.restoreAccess(TokenRotationCheck.REFUSED))
    }

    @Test
    fun theCoresRulesInTheScreensWords() {
        assertNull(LiveOutcomes.secretCodeProblem("482916"))
        assertEquals(SecretCodeError.NotSixDigits, LiveOutcomes.secretCodeProblem("12345"))
        assertEquals(SecretCodeError.RepeatedDigit, LiveOutcomes.secretCodeProblem("000000"))
        assertEquals(SecretCodeError.Sequence, LiveOutcomes.secretCodeProblem("123456"))
        assertEquals(SecretCodeError.TooCommon, LiveOutcomes.secretCodeProblem("121212"))
        assertEquals(BackupPasswordError.TooShort, LiveOutcomes.backupPasswordProblem("kısa"))
        assertEquals(BackupPasswordError.RepeatedCharacter, LiveOutcomes.backupPasswordProblem("AAAAAaaaaa"))
        assertEquals(BackupPasswordError.SimpleSequence, LiveOutcomes.backupPasswordProblem("abcdefghij"))
        assertNull(LiveOutcomes.backupPasswordProblem("deniz-moda-iskelesi-2026"))
        assertEquals(BackupPasswordError.TooCommon, LiveOutcomes.backupPasswordError(BackupPasswordProblem.COMMON))
        assertEquals(BackupPasswordError.TooShort, LiveOutcomes.exportPasswordError(InvalidBackupPasswordException(BackupPasswordProblem.TOO_SHORT)))
        assertEquals(BackupPasswordError.Mismatch, LiveOutcomes.exportPasswordError(RuntimeException(RuntimeError.BackupPasswordMismatch)))
        assertNull(LiveOutcomes.exportPasswordError(IOException("disk")))
    }

    @Test
    fun aGeneratedPasswordHasTheShapeAndPassesTheRules() {
        repeat(50) {
            val password = LiveOutcomes.generateBackupPassword()
            assertTrue(password, BackupPasswordRules.isGeneratedFormat(password))
            assertNull(LiveOutcomes.backupPasswordProblem(password))
        }
        // Rejection sampling: bytes from 248 on are skipped, so every symbol is equally likely.
        var calls = 0
        val fixed = LiveOutcomes.generateBackupPassword { count ->
            calls += 1
            ByteArray(count) { index -> if (calls == 1 && index % 2 == 0) 0xF8.toByte() else index.toByte() }
        }
        assertTrue(BackupPasswordRules.isGeneratedFormat(fixed))
        assertTrue("skipped bytes are drawn again", calls >= 2)
    }

    @Test
    fun theRelayIsTheOnlyEndpoint() {
        assertEquals("https://relay.us2us.app", RelayEndpoint.PRODUCTION)
        assertEquals("http://127.0.0.1:3000", RelayEndpoint.debugOverride("http://127.0.0.1:3000"))
        assertEquals("http://localhost:3000", RelayEndpoint.debugOverride("http://localhost:3000/"))
        assertEquals("https://relay.example.test", RelayEndpoint.debugOverride("https://relay.example.test"))
        assertNull("plain http only to loopback", RelayEndpoint.debugOverride("http://relay.example.test"))
        assertNull("the emulator's host alias is not loopback", RelayEndpoint.debugOverride("http://10.0.2.2:3000"))
        assertNull(RelayEndpoint.debugOverride("https://user:pw@relay.us2us.app"))
        assertNull(RelayEndpoint.debugOverride(null))
        assertNull(RelayEndpoint.debugOverride(""))
    }

    private fun relay(status: Int) = RelayException(status, RelayErrorCode.entries.first(), "status $status")
}
