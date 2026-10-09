package com.syconx.us2us.core.store

import com.syconx.us2us.core.encoding.Hex
import org.signal.libsignal.protocol.IdentityKeyPair
import java.time.Clock

/**
 * [ProtocolStorage] in process memory, for tests, the CLI bot and short-lived tools. Everything is
 * lost when the process exits. Thread-safe on its own, although [ProtocolStore] already serializes
 * access.
 */
class InMemoryProtocolStorage : ProtocolStorage {
    private val lock = Any()
    private val identities = HashMap<String, StoredIdentity>()
    private val preKeys = HashMap<Int, ByteArray>()
    private val signedPreKeys = HashMap<Int, ByteArray>()
    private val kyberPreKeys = HashMap<Int, StoredKyberPreKey>()
    private val kyberBaseKeys = HashMap<Int, MutableSet<String>>()
    private val sessions = HashMap<Pair<String, Int>, ByteArray>()
    private var counters: KeyIdCounters? = null

    override fun loadIdentity(name: String) = synchronized(lock) { identities[name] }
    override fun storeIdentity(name: String, identity: StoredIdentity) = synchronized(lock) { identities[name] = identity }
    override fun removeIdentity(name: String) = synchronized(lock) { identities.remove(name); Unit }

    override fun loadPreKey(id: Int) = synchronized(lock) { preKeys[id]?.copyOf() }
    override fun storePreKey(id: Int, record: ByteArray) = synchronized(lock) { preKeys[id] = record.copyOf() }
    override fun removePreKey(id: Int) = synchronized(lock) { preKeys.remove(id); Unit }

    override fun loadSignedPreKey(id: Int) = synchronized(lock) { signedPreKeys[id]?.copyOf() }
    override fun loadSignedPreKeys() = synchronized(lock) { signedPreKeys.values.map { it.copyOf() } }
    override fun storeSignedPreKey(id: Int, record: ByteArray) = synchronized(lock) { signedPreKeys[id] = record.copyOf() }
    override fun removeSignedPreKey(id: Int) = synchronized(lock) { signedPreKeys.remove(id); Unit }

    override fun loadKyberPreKey(id: Int) = synchronized(lock) { kyberPreKeys[id] }
    override fun loadKyberPreKeys() = synchronized(lock) { kyberPreKeys.values.toList() }
    override fun storeKyberPreKey(key: StoredKyberPreKey) = synchronized(lock) { kyberPreKeys[key.id] = key }

    override fun removeKyberPreKey(id: Int) = synchronized(lock) {
        kyberPreKeys.remove(id)
        kyberBaseKeys.remove(id)
        Unit
    }

    override fun recordKyberBaseKey(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ByteArray): Boolean =
        synchronized(lock) {
            kyberBaseKeys.getOrPut(kyberPreKeyId) { HashSet() }.add("$signedPreKeyId:${Hex.encode(baseKey)}")
        }

    override fun loadSession(name: String, deviceId: Int) = synchronized(lock) { sessions[name to deviceId]?.copyOf() }
    override fun storeSession(name: String, deviceId: Int, record: ByteArray) =
        synchronized(lock) { sessions[name to deviceId] = record.copyOf() }
    override fun removeSession(name: String, deviceId: Int) = synchronized(lock) { sessions.remove(name to deviceId); Unit }
    override fun removeAllSessions(name: String) = synchronized(lock) { sessions.keys.removeAll { it.first == name }; Unit }
    override fun sessionDeviceIds(name: String) = synchronized(lock) { sessions.keys.filter { it.first == name }.map { it.second } }

    override fun loadKeyIdCounters() = synchronized(lock) { counters }
    override fun storeKeyIdCounters(counters: KeyIdCounters) = synchronized(lock) { this.counters = counters }
}

/** A [ProtocolStore] backed by [InMemoryProtocolStorage]. */
fun InMemoryProtocolStore(
    identityKeyPair: IdentityKeyPair,
    registrationId: Int,
    clock: Clock = Clock.systemUTC(),
): ProtocolStore = ProtocolStore(identityKeyPair, registrationId, InMemoryProtocolStorage(), clock)
