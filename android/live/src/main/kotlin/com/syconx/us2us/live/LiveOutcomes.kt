package com.syconx.us2us.live

import com.syconx.us2us.core.messenger.BackupNoLongerValidException
import com.syconx.us2us.core.messenger.InvalidBackupException
import com.syconx.us2us.core.relay.RelayErrorCode
import com.syconx.us2us.core.relay.RelayException
import com.syconx.us2us.core.vault.BackupPasswordPolicy
import com.syconx.us2us.core.vault.BackupPasswordProblem
import com.syconx.us2us.core.vault.InvalidBackupPasswordException
import com.syconx.us2us.core.vault.InvalidVaultFileException
import com.syconx.us2us.core.vault.SecretCodePolicy
import com.syconx.us2us.core.vault.SecretCodeProblem
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
import com.syconx.us2us.domain.UpcomingDelay
import com.syconx.us2us.runtime.facade.RuntimeError
import com.syconx.us2us.runtime.facade.RuntimeException
import com.syconx.us2us.runtime.facade.TokenRotationCheck
import com.syconx.us2us.runtime.vault.UnlockError
import com.syconx.us2us.runtime.vault.UnlockException
import com.syconx.us2us.runtime.vault.VaultError
import com.syconx.us2us.runtime.vault.VaultException
import java.security.SecureRandom

/**
 * How the runtime's and the core's answers read on the screens: the pure decisions of the live
 * service (iOS `RealMessengerService`'s static functions), so unit tests drive them directly.
 */
object LiveOutcomes {
    /**
     * Only a code the vault rejected is a wrong code. A Keystore error, a store that did not open, a
     * missing device secret are never counted (PROTOCOL §5.2 › Errors) and must not read as “Kod
     * yanlış”: the user would retype a right code, or give up on a vault a retry would open.
     */
    fun attemptResult(error: Throwable, status: UnlockStatus): UnlockAttemptResult {
        val vault = when (error) {
            is UnlockException -> (error.error as? UnlockError.Vault)?.error
            is VaultException -> error.error
            else -> null
        }
        return when (vault) {
            is VaultError.LockedOut -> UnlockAttemptResult.Throttled(vault.until)
            VaultError.MalformedCode -> UnlockAttemptResult.Malformed
            VaultError.WrongCode -> UnlockAttemptResult.WrongCode(status)
            // The optional wipe fired: everything is gone, which is no “Kod yanlış”.
            VaultError.Wiped -> UnlockAttemptResult.Wiped
            VaultError.DeviceSecretMissing, VaultError.Corrupted -> UnlockAttemptResult.VaultUnusable
            else -> UnlockAttemptResult.VaultUnavailable
        }
    }

    /** The relay refused the name (409 `username_taken`): nothing was kept, so 2.3 offers another name. Any other error passes through. */
    fun creationFailure(error: Throwable): Throwable {
        if (error is RelayException && error.code == RelayErrorCode.USERNAME_TAKEN) {
            return MessengerException.AccountCreation(AccountCreationFailure.UsernameTaken)
        }
        return error
    }

    /** The restore failures that trying again cannot fix; `null` for the rest (no connection, the relay busy), after which nothing has changed and 2.3 offers to try again. */
    fun restoreFailure(error: Throwable): RestoreFailure? = when (error) {
        is RuntimeException -> when (error.error) {
            RuntimeError.BackupRevoked -> RestoreFailure.BackupNoLongerValid
            RuntimeError.UnreadableBackup -> RestoreFailure.BackupDamaged
            else -> null
        }
        is RelayException -> if (refusesBackup(error)) RestoreFailure.BackupNoLongerValid else null
        // The relay refused both tokens of the rotation. A refused new token alone (TokenRefusedException) leaves the backup working: try again.
        is BackupNoLongerValidException -> RestoreFailure.BackupNoLongerValid
        is InvalidBackupException, is WrongBackupPasswordException, is InvalidVaultFileException -> RestoreFailure.BackupDamaged
        else -> null
    }

    /** 7.3's answer for a backup that `runtime.checkBackup` refused. */
    fun openResult(error: Throwable): BackupOpenResult {
        if (error is WrongBackupPasswordException) return BackupOpenResult.WrongPassword
        return when (restoreFailure(error)) {
            RestoreFailure.BackupNoLongerValid -> BackupOpenResult.NoLongerValid
            RestoreFailure.BackupDamaged -> BackupOpenResult.Damaged
            null -> BackupOpenResult.Unverified
        }
    }

    /** The relay's answers to a restore's token rotation that mean the backup's token is revoked for good (the same answers the runtime counts as “account gone”): 401 / 404. */
    fun refusesBackup(error: RelayException): Boolean = error.httpStatus == 401 || error.httpStatus == 404

    fun restoreAccess(check: TokenRotationCheck): RestoreAccessCheck = when (check) {
        TokenRotationCheck.SETTLED -> RestoreAccessCheck.Closed
        TokenRotationCheck.UNANSWERED -> RestoreAccessCheck.Unanswered
        TokenRotationCheck.REFUSED -> RestoreAccessCheck.Refused
    }

    // MARK: Rules (PROTOCOL §5.2, §5.5)

    fun secretCodeProblem(code: String): SecretCodeError? = when (SecretCodePolicy.check(code)) {
        null -> null
        SecretCodeProblem.FORMAT -> SecretCodeError.NotSixDigits
        SecretCodeProblem.REPEATED_DIGIT -> SecretCodeError.RepeatedDigit
        SecretCodeProblem.SEQUENCE -> SecretCodeError.Sequence
        SecretCodeProblem.COMMON -> SecretCodeError.TooCommon
    }

    fun backupPasswordProblem(password: String): BackupPasswordError? = BackupPasswordPolicy.check(password)?.let(::backupPasswordError)

    fun backupPasswordError(problem: BackupPasswordProblem): BackupPasswordError = when (problem) {
        BackupPasswordProblem.TOO_SHORT -> BackupPasswordError.TooShort
        BackupPasswordProblem.COMMON -> BackupPasswordError.TooCommon
        BackupPasswordProblem.REPEATED_CHARACTER -> BackupPasswordError.RepeatedCharacter
        BackupPasswordProblem.SEQUENCE -> BackupPasswordError.SimpleSequence
    }

    /** 7.2's refusal of a password the runtime would not export with. `null`: not a password problem. */
    fun exportPasswordError(error: Throwable): BackupPasswordError? = when {
        error is InvalidBackupPasswordException -> backupPasswordError(error.problem)
        error is RuntimeException && error.error == RuntimeError.BackupPasswordMismatch -> BackupPasswordError.Mismatch
        else -> null
    }

    /** The lock's status as the screens show it; the first one before the vault was read. */
    fun unlockStatus(status: CoreUnlockStatus?): UnlockStatus {
        status ?: return UnlockStatus.Initial
        return UnlockStatus(
            failures = status.failures,
            nextAttemptAllowedAt = status.nextAttemptAllowedAt,
            upcoming = status.upcoming?.let { UpcomingDelay(it.attemptsLeft, it.delay) },
        )
    }

    private val random = SecureRandom()

    /**
     * A new random backup password (PROTOCOL §5.5, generated passwords): 20 symbols drawn uniformly
     * from [BackupPasswordRules.GENERATED_ALPHABET], written as five groups of four joined by `-`.
     */
    fun generateBackupPassword(randomBytes: (Int) -> ByteArray = { count -> ByteArray(count).also(random::nextBytes) }): String {
        val alphabet = BackupPasswordRules.GENERATED_ALPHABET
        val count = BackupPasswordRules.GENERATED_SYMBOL_COUNT
        // Rejection sampling: only bytes below the largest multiple of the alphabet size are used,
        // so every symbol is exactly equally likely.
        val limit = 256 / alphabet.length * alphabet.length
        val symbols = StringBuilder(count)
        while (symbols.length < count) {
            for (byte in randomBytes(count)) {
                val value = byte.toInt() and 0xFF
                if (value < limit && symbols.length < count) symbols.append(alphabet[value % alphabet.length])
            }
        }
        return buildString {
            symbols.forEachIndexed { index, symbol ->
                if (index > 0 && index % BackupPasswordRules.GENERATED_GROUP_LENGTH == 0) append(BackupPasswordRules.GENERATED_SEPARATOR)
                append(symbol)
            }
        }
    }
}
