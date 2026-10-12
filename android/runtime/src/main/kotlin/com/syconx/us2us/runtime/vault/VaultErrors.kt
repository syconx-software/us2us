package com.syconx.us2us.runtime.vault

import com.syconx.us2us.core.vault.SecretCodePolicy
import com.syconx.us2us.core.vault.SecretCodeProblem
import java.time.Instant

/** Why a newly chosen secret code was refused (PROTOCOL.md §5.2); the first rule that applies is reported. */
enum class SecretCodeError {
    /** Not exactly six ASCII digits. */
    NOT_SIX_DIGITS,

    /** Six times the same digit, like `000000`. */
    REPEATED_DIGIT,

    /** A run up or down the digits, also wrapping from 9 to 0: `123456`, `890123`, `654321`. */
    SEQUENCE,

    /** Listed in `spec/common-secret-codes.txt`, like `121212` or `159753`. */
    TOO_COMMON,

    /** The confirmation does not match. */
    MISMATCH,
    ;

    companion object {
        fun of(problem: SecretCodeProblem): SecretCodeError = when (problem) {
            SecretCodeProblem.FORMAT -> NOT_SIX_DIGITS
            SecretCodeProblem.REPEATED_DIGIT -> REPEATED_DIGIT
            SecretCodeProblem.SEQUENCE -> SEQUENCE
            SecretCodeProblem.COMMON -> TOO_COMMON
        }

        /** The first problem with a new [code] entered twice, or `null` if it is accepted. */
        fun check(code: String, confirmation: String): SecretCodeError? {
            SecretCodePolicy.check(code)?.let { return of(it) }
            return if (code == confirmation) null else MISMATCH
        }
    }
}

/** Why the vault did not open, or did not do what was asked. */
sealed interface VaultError {
    /** GCM authentication failed: wrong code (or a different device secret). */
    data object WrongCode : VaultError

    /** The input is not six ASCII digits, so it cannot be the code. Refused before anything is derived and not counted (§5.2). */
    data object MalformedCode : VaultError

    /** Too many wrong attempts; the next attempt is allowed at [until]. */
    data class LockedOut(val until: Instant) : VaultError

    /** The optional "wipe after 10 wrong attempts" fired; everything was crypto-shredded. */
    data object Wiped : VaultError

    /**
     * The device secret is gone (or no longer readable), so the vault can never be opened here.
     * Not a wrong code (§5.2).
     */
    data object DeviceSecretMissing : VaultError

    data object Corrupted : VaultError

    data object AlreadyExists : VaultError

    data object NotFound : VaultError

    data class InvalidNewCode(val problem: SecretCodeError) : VaultError

    /** A file or Keystore error that may pass; the code may well be right. Not a wrong code. */
    data class Storage(val detail: String) : VaultError
}

class VaultException(val error: VaultError, cause: Throwable? = null) : Exception("vault: $error", cause)

/** Why an unlock attempt did not open the vault. */
sealed interface UnlockError {
    /** From the code path: wrong code, malformed input, lockout, the optional wipe, a missing device secret, … */
    data class Vault(val error: VaultError) : UnlockError

    /** From the biometric path; [BiometricError.CANCELLED] and [BiometricError.FAILED] leave the code keypad as the fallback. */
    data class Biometric(val error: BiometricError) : UnlockError

    /** Biometric unlock is not possible right now; ask for the code. */
    data class CodeRequired(val reason: CodeRequiredReason) : UnlockError

    /** Quick unlock was never turned on. */
    data object QuickUnlockNotEnabled : UnlockError

    /** Another unlock is already in progress. */
    data object Busy : UnlockError
}

class UnlockException(val error: UnlockError, cause: Throwable? = null) : Exception("unlock: $error", cause)
