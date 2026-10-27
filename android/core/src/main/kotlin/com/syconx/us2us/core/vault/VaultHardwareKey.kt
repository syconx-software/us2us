package com.syconx.us2us.core.vault

import java.io.IOException

/**
 * The per-guess hardware step already specified in PROTOCOL.md §5.2. Production implementations
 * must verify that the key lives in secure hardware before allowing a v2 vault to be sealed.
 * Software implementations belong in tests only. This does not enforce the app's attempt counter.
 */
interface VaultHardwareKey {
    val isAvailable: Boolean

    /** Replaces a leftover key. Call only when no committed v2 vault depends on it. */
    fun createKey()

    /** Deterministic 32-byte HWPRF output; the non-exportable key never leaves the hardware. */
    fun evaluate(codeKey: ByteArray): ByteArray

    fun deleteKey()
}

class VaultHardwareKeyMissingException : IOException("vault hardware key missing")
class VaultHardwareKeyException(message: String, cause: Throwable? = null) : IOException(message, cause)
