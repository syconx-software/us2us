package com.syconx.us2us.core.keys

import com.syconx.us2us.core.relay.KeyStatus
import com.syconx.us2us.core.relay.KeyUploadRequest
import com.syconx.us2us.core.relay.PreKeyEntity
import com.syconx.us2us.core.relay.SignedPreKeyEntity
import com.syconx.us2us.core.store.KeyIdCounters
import com.syconx.us2us.core.store.ProtocolStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/** Pre-key lifecycle parameters (PROTOCOL.md §5). */
data class PreKeyPolicy(
    /** One-time keys uploaded per type at registration and when refilling. */
    val batchSize: Int = 100,
    /** Refill when the relay reports fewer one-time keys than this. */
    val refillThreshold: Int = 20,
    /** Age at which the signed pre-key and the Kyber last-resort key are replaced. */
    val rotationInterval: Duration = 30.days,
    /** How long a replaced key stays usable for late messages. */
    val retention: Duration = 30.days,
) {
    init {
        require(batchSize in 1..MAX_BATCH) { "batch size must be in 1..$MAX_BATCH" }
        require(refillThreshold in 0..batchSize) { "refill threshold must not exceed the batch size" }
    }

    companion object {
        /** The relay accepts at most 100 entries per one-time list in one request (§4.3). */
        const val MAX_BATCH = 100
    }
}

/** The public halves of a complete key set, as registration and `PUT /v1/keys` expect them. */
class PreKeySet(
    val signedPreKey: SignedPreKeyEntity,
    val kyberLastResortKey: SignedPreKeyEntity,
    val oneTimePreKeys: List<PreKeyEntity>,
    val kyberOneTimePreKeys: List<SignedPreKeyEntity>,
) {
    /** With [replaceAll] the relay first drops all stored one-time keys (used after a restore). */
    fun toUploadRequest(replaceAll: Boolean = false) =
        KeyUploadRequest(signedPreKey, kyberLastResortKey, oneTimePreKeys, kyberOneTimePreKeys, replaceAll)
}

/**
 * Creates, rotates, refills and retires the account's pre-keys. Private halves are written to the
 * [ProtocolStore] before the returned upload request is sent, so a message using a new key can
 * always be decrypted.
 */
class PreKeyManager(
    private val store: ProtocolStore,
    private val policy: PreKeyPolicy = PreKeyPolicy(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val identity get() = store.identityKeyPair

    /**
     * A complete new key set: signed pre-key, Kyber last-resort key and a batch of each one-time
     * kind. Used at registration and after restoring a backup (§5.5).
     */
    fun generateFullSet(): PreKeySet {
        val now = clock.millis()
        return PreKeySet(
            signedPreKey = newSignedPreKey(now).toEntity(),
            kyberLastResortKey = newLastResortKyberPreKey(now).toEntity(),
            oneTimePreKeys = newOneTimePreKeys(policy.batchSize).map { it.toEntity() },
            kyberOneTimePreKeys = newOneTimeKyberPreKeys(policy.batchSize, now).map { it.toEntity() },
        )
    }

    /**
     * Compares the relay's [status] with local state and returns the upload that rotates expired
     * keys and refills one-time keys, or `null` if nothing is due. Also retires replaced keys whose
     * retention period has passed.
     */
    fun maintain(status: KeyStatus): KeyUploadRequest? {
        val now = clock.millis()
        retireExpiredKeys(now)

        val signed = currentSignedPreKey()
        val rotateSigned = signed == null || isDue(signed.timestamp, now) || status.signedPreKeyId != signed.id
        val lastResort = currentLastResortKyberPreKey()
        val rotateLastResort = lastResort == null || isDue(lastResort.timestamp, now) || status.kyberLastResortKeyId != lastResort.id

        val request = KeyUploadRequest(
            signedPreKey = if (rotateSigned) newSignedPreKey(now).toEntity() else null,
            kyberLastResortKey = if (rotateLastResort) newLastResortKyberPreKey(now).toEntity() else null,
            oneTimePreKeys = refillCount(status.oneTimePreKeys)?.let { n -> newOneTimePreKeys(n).map { it.toEntity() } },
            kyberOneTimePreKeys = refillCount(status.kyberOneTimePreKeys)?.let { n -> newOneTimeKyberPreKeys(n, now).map { it.toEntity() } },
        )
        return request.takeUnless { it.isEmpty }
    }

    /**
     * Deletes signed and last-resort keys that were replaced more than [PreKeyPolicy.retention]
     * ago. The current key of each kind is always kept.
     */
    fun retireExpiredKeys(now: Long = clock.millis()) {
        val retentionMillis = policy.retention.inWholeMilliseconds
        retire(store.loadSignedPreKeys().map { it.id to it.timestamp }, now, retentionMillis, store::removeSignedPreKey)
        retire(store.lastResortKyberPreKeys().map { it.id to it.timestamp }, now, retentionMillis, store::removeKyberPreKey)
    }

    private fun retire(keys: List<Pair<Int, Long>>, now: Long, retentionMillis: Long, remove: (Int) -> Unit) {
        // A key is replaced when a newer one exists; it stays for `retention` after its successor was created.
        val sorted = keys.sortedBy { it.second }
        for (i in 0 until sorted.size - 1) {
            val successorCreatedAt = sorted[i + 1].second
            if (now - successorCreatedAt >= retentionMillis) remove(sorted[i].first)
        }
    }

    private fun currentSignedPreKey(): SignedPreKeyRecord? = store.loadSignedPreKeys().maxByOrNull { it.timestamp }

    private fun currentLastResortKyberPreKey(): KyberPreKeyRecord? = store.lastResortKyberPreKeys().maxByOrNull { it.timestamp }

    /**
     * Creation timestamps of signed and last-resort keys strictly increase, so "the newest key" is
     * never ambiguous, even for two keys made within the same millisecond.
     */
    private fun after(latest: Long?, now: Long): Long = if (latest != null && latest >= now) latest + 1 else now

    private fun isDue(createdAt: Long, now: Long) = now - createdAt >= policy.rotationInterval.inWholeMilliseconds

    private fun refillCount(remaining: Int): Int? =
        if (remaining < policy.refillThreshold) policy.batchSize - remaining.coerceAtLeast(0) else null

    private fun newSignedPreKey(now: Long): SignedPreKeyRecord {
        val timestamp = after(currentSignedPreKey()?.timestamp, now)
        val record = Keys.signedPreKey(identity, allocateIds(KeyKind.SIGNED, 1).single(), timestamp)
        store.storeSignedPreKey(record.id, record)
        return record
    }

    private fun newLastResortKyberPreKey(now: Long): KyberPreKeyRecord {
        val timestamp = after(currentLastResortKyberPreKey()?.timestamp, now)
        val record = Keys.kyberPreKey(identity, allocateIds(KeyKind.KYBER, 1).single(), timestamp)
        store.storeKyberPreKey(record, lastResort = true)
        return record
    }

    private fun newOneTimePreKeys(count: Int) = allocateIds(KeyKind.ONE_TIME_EC, count).map { id ->
        Keys.oneTimePreKey(id).also { store.storePreKey(id, it) }
    }

    private fun newOneTimeKyberPreKeys(count: Int, now: Long) = allocateIds(KeyKind.KYBER, count).map { id ->
        Keys.kyberPreKey(identity, id, now).also { store.storeKyberPreKey(it, lastResort = false) }
    }

    private enum class KeyKind { ONE_TIME_EC, SIGNED, KYBER }

    /**
     * Hands out the next [count] ids of one kind (wrapping within the 24-bit range) and persists the
     * advanced counter first, so an id is never handed out twice even if generation is interrupted.
     * Counters start at random points, as libsignal's reference clients do.
     */
    private fun allocateIds(kind: KeyKind, count: Int): List<Int> {
        val counters = store.keyIdCounters() ?: KeyIdCounters(KeyIds.random(), KeyIds.random(), KeyIds.random())
        val first = when (kind) {
            KeyKind.ONE_TIME_EC -> counters.nextPreKeyId
            KeyKind.SIGNED -> counters.nextSignedPreKeyId
            KeyKind.KYBER -> counters.nextKyberPreKeyId
        }
        val ids = generateSequence(first, KeyIds::next).take(count).toList()
        val next = KeyIds.next(ids.last())
        store.storeKeyIdCounters(
            when (kind) {
                KeyKind.ONE_TIME_EC -> counters.copy(nextPreKeyId = next)
                KeyKind.SIGNED -> counters.copy(nextSignedPreKeyId = next)
                KeyKind.KYBER -> counters.copy(nextKyberPreKeyId = next)
            },
        )
        return ids
    }
}
