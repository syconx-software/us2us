package com.syconx.us2us.core.store

/**
 * Persistence for [ProtocolStore]. Implementations only store and load bytes; every protocol rule
 * (one-time key deletion, base-key reuse, identity trust) lives in [ProtocolStore], so a SQLCipher
 * implementation in the app cannot get the semantics wrong.
 *
 * All methods are called with [ProtocolStore]'s lock held, from whatever thread does session
 * crypto. Records are libsignal serializations and must be stored verbatim.
 */
interface ProtocolStorage {
    // Remote identities, keyed by libsignal address name (the account id).

    fun loadIdentity(name: String): StoredIdentity?
    fun storeIdentity(name: String, identity: StoredIdentity)
    fun removeIdentity(name: String)

    // One-time EC pre-keys.

    fun loadPreKey(id: Int): ByteArray?
    fun storePreKey(id: Int, record: ByteArray)
    fun removePreKey(id: Int)

    // Signed EC pre-keys.

    fun loadSignedPreKey(id: Int): ByteArray?
    fun loadSignedPreKeys(): List<ByteArray>
    fun storeSignedPreKey(id: Int, record: ByteArray)
    fun removeSignedPreKey(id: Int)

    // Kyber pre-keys, one-time and last-resort in one id space.

    fun loadKyberPreKey(id: Int): StoredKyberPreKey?
    fun loadKyberPreKeys(): List<StoredKyberPreKey>
    fun storeKyberPreKey(key: StoredKyberPreKey)

    /** Removes the key and every base-key record of it. */
    fun removeKyberPreKey(id: Int)

    /**
     * Records that a last-resort Kyber key was used with this signed pre-key and sender base key.
     * Must be atomic: returns `false` (and records nothing) if the triple was recorded before.
     */
    fun recordKyberBaseKey(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ByteArray): Boolean

    // Sessions, keyed by address name and device id.

    fun loadSession(name: String, deviceId: Int): ByteArray?
    fun storeSession(name: String, deviceId: Int, record: ByteArray)
    fun removeSession(name: String, deviceId: Int)
    fun removeAllSessions(name: String)
    fun sessionDeviceIds(name: String): List<Int>

    // Next key ids to hand out, so ids are never reused while the relay may still serve them.

    fun loadKeyIdCounters(): KeyIdCounters?
    fun storeKeyIdCounters(counters: KeyIdCounters)
}

/** Trust state of a remote identity key (trust on first use, PROTOCOL.md §5). */
enum class IdentityTrust {
    /** First seen, or explicitly accepted by the user after a change. Sending is allowed. */
    TRUSTED,

    /** The key replaced a previously trusted one. Sending is blocked until the user accepts it. */
    CHANGED,
}

class StoredIdentity(
    val identityKey: ByteArray,
    val trust: IdentityTrust,
    /** The user compared safety numbers. Reset whenever the key changes. */
    val verified: Boolean,
    val firstSeenAt: Long,
)

class StoredKyberPreKey(val id: Int, val record: ByteArray, val lastResort: Boolean)

data class KeyIdCounters(val nextPreKeyId: Int, val nextSignedPreKeyId: Int, val nextKyberPreKeyId: Int)
