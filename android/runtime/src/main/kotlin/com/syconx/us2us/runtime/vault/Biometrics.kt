package com.syconx.us2us.runtime.vault

import androidx.biometric.BiometricPrompt

/** The biometric hardware the phone offers for quick unlock. */
enum class BiometryKind { NONE, FINGERPRINT, FACE, IRIS }

/** Why a biometric unlock did not produce the key. */
enum class BiometricError {
    /** No biometric hardware, nothing enrolled, or no secure lock screen. */
    UNAVAILABLE,

    /** The user or the system cancelled (e.g. the app went to the background). Does not count as a failed attempt. */
    CANCELLED,

    /** The face or finger was not recognized. Counts towards the limit of 5. */
    FAILED,

    /** The system locked biometrics after too many failures; the code is required. */
    LOCKOUT,

    /** The enrolled biometrics changed or the key is gone; the code is required and the wrap is discarded. */
    INVALIDATED,

    /** Creating or using the key failed for another reason. */
    KEY_FAILURE,
}

class BiometricException(val error: BiometricError, cause: Throwable? = null) : Exception("biometric: $error", cause)

/** The current biometric situation of the phone. */
data class BiometricAvailability(
    val kind: BiometryKind,
    /** Biometrics of [kind] are enrolled and usable for a strong (Class 3) match. */
    val enrolled: Boolean,
    /** The system has locked biometrics (too many failures). */
    val lockedOut: Boolean = false,
) {
    val isUsable: Boolean get() = kind != BiometryKind.NONE && enrolled && !lockedOut

    companion object {
        val NONE = BiometricAvailability(BiometryKind.NONE, enrolled = false)
    }
}

/**
 * A hardware-bound key that wraps the DEK and can only be used after a biometric match (PROTOCOL.md
 * §5.2 "Biometric quick unlock").
 */
interface BiometricKeyProtector {
    fun availability(): BiometricAvailability

    /**
     * Creates a fresh key (replacing any previous one) and returns [secret] encrypted to it. No
     * prompt: only decrypting needs the match.
     *
     * @throws BiometricException
     */
    fun wrap(secret: ByteArray): ByteArray

    /**
     * Whether the key behind [wrapped] still exists and was not invalidated by an enrollment
     * change; checked without a prompt.
     */
    fun isKeyValid(wrapped: ByteArray): Boolean

    /**
     * Shows the biometric prompt with [reason] as its title and [cancelTitle] on its negative
     * button, and decrypts [wrapped].
     *
     * @throws BiometricException
     */
    suspend fun unwrap(wrapped: ByteArray, reason: String, cancelTitle: String): ByteArray

    fun deleteKey()
}

/** How a finished biometric prompt maps to [BiometricError]. */
object BiometricOutcomes {
    /**
     * Maps a `BiometricPrompt.ERROR_*` code. The prompt lets the user try again after a finger or
     * face is not recognized, so a prompt that ends without a match after at least one [mismatches]
     * counts as [BiometricError.FAILED]; one closed without trying is [BiometricError.CANCELLED].
     */
    fun map(errorCode: Int, mismatches: Int): BiometricError = when (errorCode) {
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_CANCELED,
        BiometricPrompt.ERROR_TIMEOUT,
        -> if (mismatches > 0) BiometricError.FAILED else BiometricError.CANCELLED

        BiometricPrompt.ERROR_LOCKOUT,
        BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
        -> BiometricError.LOCKOUT

        BiometricPrompt.ERROR_HW_NOT_PRESENT,
        BiometricPrompt.ERROR_HW_UNAVAILABLE,
        BiometricPrompt.ERROR_NO_BIOMETRICS,
        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
        BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED,
        -> BiometricError.UNAVAILABLE

        else -> BiometricError.FAILED
    }
}

/** A stand-in for phones and builds without quick unlock: never available, never wraps. */
object NoBiometrics : BiometricKeyProtector {
    override fun availability() = BiometricAvailability.NONE

    override fun wrap(secret: ByteArray): ByteArray = throw BiometricException(BiometricError.UNAVAILABLE)

    override fun isKeyValid(wrapped: ByteArray) = false

    override suspend fun unwrap(wrapped: ByteArray, reason: String, cancelTitle: String): ByteArray =
        throw BiometricException(BiometricError.UNAVAILABLE)

    override fun deleteKey() = Unit
}
