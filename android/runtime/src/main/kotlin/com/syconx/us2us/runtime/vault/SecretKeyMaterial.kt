package com.syconx.us2us.runtime.vault

import com.syconx.us2us.core.vault.DataKey

/** The vault was locked; the key no longer exists in memory. */
class SecretKeyWipedException : IllegalStateException("the key was wiped")

/**
 * Key material (the vault DEK) held in memory only while the app is unlocked, in a buffer this
 * object owns and overwrites with zeros on [wipe].
 *
 * The JVM does not promise to keep a single copy of anything, so this is best effort: [withBytes]
 * hands out a short-lived copy and zeroes it afterwards, but callees (HKDF, SQLCipher) may hold
 * their own copies for as long as they need them. What this type guarantees is that *its* copy is
 * gone the moment the vault locks.
 */
class SecretKeyMaterial(bytes: ByteArray) : AutoCloseable {
    private val buffer = bytes.copyOf()
    private var wiped = false

    val size: Int = bytes.size

    val isWiped: Boolean
        @Synchronized get() = wiped

    /**
     * Runs [body] with a temporary copy, zeroed when [body] returns.
     *
     * @throws SecretKeyWipedException after [wipe].
     */
    fun <R> withBytes(body: (ByteArray) -> R): R {
        val copy = synchronized(this) {
            if (wiped) throw SecretKeyWipedException()
            buffer.copyOf()
        }
        try {
            return body(copy)
        } finally {
            copy.fill(0)
        }
    }

    /**
     * Runs [body] with the key as the core's [DataKey] (for `databaseKey()` and `slotKey()`),
     * wiped when [body] returns.
     */
    fun <R> withDataKey(body: (DataKey) -> R): R = withBytes { bytes ->
        val key = DataKey(bytes)
        try {
            body(key)
        } finally {
            key.wipe()
        }
    }

    /** Overwrites the key with zeros; every later use throws. */
    @Synchronized
    fun wipe() {
        if (wiped) return
        wiped = true
        buffer.fill(0)
    }

    override fun close() = wipe()

    /** Constant-time comparison with [other]; `false` once either is wiped. */
    fun matches(other: SecretKeyMaterial): Boolean = try {
        withBytes { mine ->
            other.withBytes { theirs ->
                if (mine.size != theirs.size) return@withBytes false
                var difference = 0
                for (i in mine.indices) difference = difference or (mine[i].toInt() xor theirs[i].toInt())
                difference == 0
            }
        }
    } catch (_: SecretKeyWipedException) {
        false
    }

    override fun toString(): String = "SecretKeyMaterial(redacted)"
}
