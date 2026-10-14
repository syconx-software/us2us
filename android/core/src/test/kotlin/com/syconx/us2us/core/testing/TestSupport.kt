package com.syconx.us2us.core.testing

import com.google.protobuf.kotlin.toByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.keys.Keys
import com.syconx.us2us.core.keys.PreKeyManager
import com.syconx.us2us.core.keys.PreKeyPolicy
import com.syconx.us2us.core.keys.toPreKeyBundle
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.proto.content
import com.syconx.us2us.core.proto.dataMessage
import com.syconx.us2us.core.proto.text
import com.syconx.us2us.core.relay.PreKeyBundleEntity
import com.syconx.us2us.core.relay.PreKeyEntity
import com.syconx.us2us.core.relay.SignedPreKeyEntity
import com.syconx.us2us.core.session.SessionCrypto
import com.syconx.us2us.core.store.InMemoryProtocolStorage
import com.syconx.us2us.core.store.ProtocolStorage
import com.syconx.us2us.core.store.ProtocolStore
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration

/** A clock tests can move forward. */
class MutableClock(private var now: Instant = Instant.parse("2026-10-07T12:00:00Z")) : Clock() {
    override fun getZone(): ZoneOffset = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?): Clock = this
    override fun instant(): Instant = now

    fun advance(duration: Duration) {
        now = now.plusMillis(duration.inWholeMilliseconds)
    }
}

/** A key pair derived from fixed private-key bytes, so vectors and tests are reproducible. */
fun deterministicIdentity(seed: Int): IdentityKeyPair {
    val privateKey = ECPrivateKey(ByteArray(32) { (seed * 31 + it * 7 + 1).toByte() })
    return IdentityKeyPair(IdentityKey(privateKey.getPublicKey()), privateKey)
}

fun textContent(body: String): Content = content {
    data = dataMessage {
        messageId = ByteArray(16) { 7 }.toByteString()
        sentAt = 1_760_000_000_000
        text = text { this.body = body }
    }
}

/**
 * One account with in-memory stores, plus a pretend relay mailbox of its uploaded one-time keys, so
 * session tests can build bundles exactly as the relay would hand them out.
 */
class Party(
    val clock: MutableClock = MutableClock(),
    val accountId: AccountId = AccountId(UUID.randomUUID()),
    identity: IdentityKeyPair = Keys.generateIdentityKeyPair(),
    policy: PreKeyPolicy = PreKeyPolicy(batchSize = 5, refillThreshold = 2),
    storage: ProtocolStorage = InMemoryProtocolStorage(),
) {
    val store: ProtocolStore = ProtocolStore(identity, Keys.generateRegistrationId(), storage, clock)
    val sessions = SessionCrypto(store, accountId, clock)
    val keys = PreKeyManager(store, policy, clock)
    private val uploaded = keys.generateFullSet()
    private val oneTimeEc = ArrayDeque(uploaded.oneTimePreKeys)
    private val oneTimeKyber = ArrayDeque(uploaded.kyberOneTimePreKeys)

    val identityKey: IdentityKey get() = store.identityKeyPair.publicKey

    /** Like `GET /v1/keys/{id}`: consumes one key of each one-time kind, falling back as the relay does. */
    fun bundleEntity(withOneTimeEc: Boolean = true, withOneTimeKyber: Boolean = true): PreKeyBundleEntity {
        val ec: PreKeyEntity? = if (withOneTimeEc) oneTimeEc.removeFirstOrNull() else null
        val kyber: SignedPreKeyEntity = (if (withOneTimeKyber) oneTimeKyber.removeFirstOrNull() else null) ?: uploaded.kyberLastResortKey
        return PreKeyBundleEntity(
            accountId = accountId,
            deviceId = AccountId.DEVICE_ID,
            registrationId = store.localRegistrationId,
            identityKey = identityKey.serialize(),
            signedPreKey = uploaded.signedPreKey,
            kyberPreKey = kyber,
            oneTimePreKey = ec,
        )
    }

    fun bundle(withOneTimeEc: Boolean = true, withOneTimeKyber: Boolean = true): PreKeyBundle =
        bundleEntity(withOneTimeEc, withOneTimeKyber).toPreKeyBundle(accountId)

    val lastResortKyberId: Int get() = uploaded.kyberLastResortKey.keyId

    fun encryptTo(peer: Party, body: String): CiphertextMessage = sessions.encrypt(peer.accountId, textContent(body))

    fun decryptFrom(peer: Party, message: CiphertextMessage): String =
        sessions.decrypt(peer.accountId, message.type, message.serialize()).data.text.body
}

/** Storage that refuses everything while [locked], as the app's store does once its vault is closed. */
class LockableProtocolStorage(private val inner: ProtocolStorage = InMemoryProtocolStorage()) : ProtocolStorage by inner {
    var locked = false

    class LockedException : Exception("storage is locked")

    override fun loadSession(name: String, deviceId: Int): ByteArray? {
        if (locked) throw LockedException()
        return inner.loadSession(name, deviceId)
    }

    override fun storeSession(name: String, deviceId: Int, record: ByteArray) {
        if (locked) throw LockedException()
        inner.storeSession(name, deviceId, record)
    }
}
