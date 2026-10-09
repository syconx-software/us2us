package com.syconx.us2us.core.store

import com.syconx.us2us.core.AccountId
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.IdentityKeyStore.Direction
import org.signal.libsignal.protocol.state.IdentityKeyStore.IdentityChange
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import java.time.Clock
import java.time.Instant

/** The trust state of a contact's identity key, as shown to the user. */
class IdentityState(val identityKey: IdentityKey, val trust: IdentityTrust, val verified: Boolean)

/**
 * The libsignal stores of one account, implementing the rules of PROTOCOL.md §5 on top of a
 * swappable [ProtocolStorage]:
 *
 * * One-time Kyber pre-keys are deleted when used; last-resort Kyber keys are kept and each
 *   `(kyberId, signedId, baseKey)` triple is accepted only once. (libsignal's own in-memory store
 *   does neither.)
 * * Identities are trust-on-first-use. A changed identity is accepted for receiving but recorded as
 *   [IdentityTrust.CHANGED], which blocks sending until [acceptIdentity]; changes are announced on
 *   [identityChanges].
 *
 * Sender-key (group) storage is intentionally absent: groups are not part of BRP v1.
 */
class ProtocolStore(
    private val identityKeyPair: IdentityKeyPair,
    private val registrationId: Int,
    private val storage: ProtocolStorage,
    private val clock: Clock = Clock.systemUTC(),
) : IdentityKeyStore, PreKeyStore, SignedPreKeyStore, KyberPreKeyStore, SessionStore {

    private val lock = Any()
    private val changes = MutableSharedFlow<AccountId>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Contacts whose identity key just changed. The state itself is persistent ([identity]), so a
     * missed emission only delays the warning until the next lookup.
     */
    val identityChanges: SharedFlow<AccountId> = changes.asSharedFlow()

    // IdentityKeyStore

    override fun getIdentityKeyPair(): IdentityKeyPair = identityKeyPair

    override fun getLocalRegistrationId(): Int = registrationId

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityChange {
        val serialized = identityKey.serialize()
        val changed = synchronized(lock) {
            val existing = storage.loadIdentity(address.name)
            when {
                existing == null -> {
                    storage.storeIdentity(address.name, StoredIdentity(serialized, IdentityTrust.TRUSTED, false, clock.millis()))
                    false
                }
                existing.identityKey.contentEquals(serialized) -> false
                else -> {
                    storage.storeIdentity(address.name, StoredIdentity(serialized, IdentityTrust.CHANGED, false, existing.firstSeenAt))
                    true
                }
            }
        }
        if (!changed) return IdentityChange.NEW_OR_UNCHANGED
        runCatching { AccountId.fromAddress(address) }.onSuccess { changes.tryEmit(it) }
        return IdentityChange.REPLACED_EXISTING
    }

    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: Direction): Boolean {
        val existing = synchronized(lock) { storage.loadIdentity(address.name) } ?: return true
        return when (direction) {
            // Messages from a changed identity are still delivered; saveIdentity flags the change.
            Direction.RECEIVING -> true
            Direction.SENDING -> existing.trust == IdentityTrust.TRUSTED && existing.identityKey.contentEquals(identityKey.serialize())
        }
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        synchronized(lock) { storage.loadIdentity(address.name) }?.let { IdentityKey(it.identityKey) }

    fun identity(accountId: AccountId): IdentityState? =
        synchronized(lock) { storage.loadIdentity(accountId.toString()) }
            ?.let { IdentityState(IdentityKey(it.identityKey), it.trust, it.verified) }

    /** The user acknowledged a safety-number change; sending to this contact is allowed again. */
    fun acceptIdentity(accountId: AccountId) {
        updateIdentity(accountId) { StoredIdentity(it.identityKey, IdentityTrust.TRUSTED, it.verified, it.firstSeenAt) }
    }

    /**
     * Records a contact's identity from an account backup as it was on the old phone (PROTOCOL.md
     * §5.5): [IdentityTrust.TRUSTED], or [IdentityTrust.CHANGED] for a change the user had not
     * accepted, which keeps sending blocked until [acceptIdentity]. An unaccepted key is never
     * verified. Replaces whatever is stored for [accountId].
     */
    fun restoreIdentity(accountId: AccountId, identityKey: IdentityKey, trust: IdentityTrust, verified: Boolean) {
        val identity = StoredIdentity(identityKey.serialize(), trust, verified && trust == IdentityTrust.TRUSTED, clock.millis())
        synchronized(lock) { storage.storeIdentity(accountId.toString(), identity) }
        if (trust == IdentityTrust.CHANGED) changes.tryEmit(accountId)
    }

    /** Records whether the user compared safety numbers with this contact. */
    fun setVerified(accountId: AccountId, verified: Boolean) {
        updateIdentity(accountId) { StoredIdentity(it.identityKey, it.trust, verified, it.firstSeenAt) }
    }

    private inline fun updateIdentity(accountId: AccountId, update: (StoredIdentity) -> StoredIdentity) = synchronized(lock) {
        val name = accountId.toString()
        storage.loadIdentity(name)?.let { storage.storeIdentity(name, update(it)) }
    }

    /** Forgets a contact's identity and sessions ("leave no trace", PROTOCOL.md §8.2). */
    fun forget(accountId: AccountId) = synchronized(lock) {
        storage.removeAllSessions(accountId.toString())
        storage.removeIdentity(accountId.toString())
    }

    // PreKeyStore

    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        synchronized(lock) { storage.loadPreKey(preKeyId) }?.let(::PreKeyRecord)
            ?: throw InvalidKeyIdException("no one-time pre-key $preKeyId")

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) =
        synchronized(lock) { storage.storePreKey(preKeyId, record.serialize()) }

    override fun containsPreKey(preKeyId: Int): Boolean = synchronized(lock) { storage.loadPreKey(preKeyId) != null }

    override fun removePreKey(preKeyId: Int) = synchronized(lock) { storage.removePreKey(preKeyId) }

    // SignedPreKeyStore

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        synchronized(lock) { storage.loadSignedPreKey(signedPreKeyId) }?.let(::SignedPreKeyRecord)
            ?: throw InvalidKeyIdException("no signed pre-key $signedPreKeyId")

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        synchronized(lock) { storage.loadSignedPreKeys() }.map(::SignedPreKeyRecord)

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) =
        synchronized(lock) { storage.storeSignedPreKey(signedPreKeyId, record.serialize()) }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        synchronized(lock) { storage.loadSignedPreKey(signedPreKeyId) != null }

    override fun removeSignedPreKey(signedPreKeyId: Int) = synchronized(lock) { storage.removeSignedPreKey(signedPreKeyId) }

    // KyberPreKeyStore

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        synchronized(lock) { storage.loadKyberPreKey(kyberPreKeyId) }?.let { KyberPreKeyRecord(it.record) }
            ?: throw InvalidKeyIdException("no Kyber pre-key $kyberPreKeyId")

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        synchronized(lock) { storage.loadKyberPreKeys() }.map { KyberPreKeyRecord(it.record) }

    /** Stores a Kyber key, keeping the one-time/last-resort kind of an existing entry (default one-time). */
    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) = synchronized(lock) {
        val lastResort = storage.loadKyberPreKey(kyberPreKeyId)?.lastResort ?: false
        storage.storeKyberPreKey(StoredKyberPreKey(kyberPreKeyId, record.serialize(), lastResort))
    }

    fun storeKyberPreKey(record: KyberPreKeyRecord, lastResort: Boolean) =
        synchronized(lock) { storage.storeKyberPreKey(StoredKyberPreKey(record.id, record.serialize(), lastResort)) }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        synchronized(lock) { storage.loadKyberPreKey(kyberPreKeyId) != null }

    /**
     * Deletes a used one-time key; for a last-resort key, rejects a repeated
     * `(kyberId, signedId, baseKey)` so a replayed first message cannot open a second session.
     */
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) = synchronized(lock) {
        val key = storage.loadKyberPreKey(kyberPreKeyId)
            ?: throw ReusedBaseKeyException("Kyber pre-key $kyberPreKeyId was already used")
        if (!key.lastResort) {
            storage.removeKyberPreKey(kyberPreKeyId)
        } else if (!storage.recordKyberBaseKey(kyberPreKeyId, signedPreKeyId, baseKey.serialize())) {
            throw ReusedBaseKeyException("base key reused with last-resort Kyber pre-key $kyberPreKeyId")
        }
    }

    fun lastResortKyberPreKeys(): List<KyberPreKeyRecord> =
        synchronized(lock) { storage.loadKyberPreKeys() }.filter { it.lastResort }.map { KyberPreKeyRecord(it.record) }

    fun removeKyberPreKey(kyberPreKeyId: Int) = synchronized(lock) { storage.removeKyberPreKey(kyberPreKeyId) }

    fun isLastResortKyberPreKey(kyberPreKeyId: Int): Boolean =
        synchronized(lock) { storage.loadKyberPreKey(kyberPreKeyId)?.lastResort == true }

    // SessionStore

    /** Returns `null` when there is no session, which libsignal's bridge treats as "none". */
    override fun loadSession(address: SignalProtocolAddress): SessionRecord? =
        synchronized(lock) { storage.loadSession(address.name, address.deviceId) }?.let(::SessionRecord)

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { loadSession(it) ?: throw NoSessionException(it, "no session") }

    override fun getSubDeviceSessions(name: String): List<Int> =
        synchronized(lock) { storage.sessionDeviceIds(name) }.filter { it != AccountId.DEVICE_ID }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) =
        synchronized(lock) { storage.storeSession(address.name, address.deviceId, record.serialize()) }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        synchronized(lock) { storage.loadSession(address.name, address.deviceId) != null }

    override fun deleteSession(address: SignalProtocolAddress) =
        synchronized(lock) { storage.removeSession(address.name, address.deviceId) }

    override fun deleteAllSessions(name: String) = synchronized(lock) { storage.removeAllSessions(name) }

    /**
     * Whether messages to [accountId] can be encrypted right now. libsignal refuses to encrypt on a
     * session that is not PQXDH + SPQR, or that the peer never acknowledged within 30 days; then a
     * fresh pre-key bundle is needed.
     */
    fun hasUsableSession(accountId: AccountId, now: Instant = clock.instant()): Boolean =
        loadSession(accountId.protocolAddress())?.hasSenderChain(now) == true

    // Key id counters

    fun keyIdCounters(): KeyIdCounters? = synchronized(lock) { storage.loadKeyIdCounters() }

    fun storeKeyIdCounters(counters: KeyIdCounters) = synchronized(lock) { storage.storeKeyIdCounters(counters) }
}
