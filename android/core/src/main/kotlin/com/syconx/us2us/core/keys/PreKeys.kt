package com.syconx.us2us.core.keys

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.relay.PreKeyBundleEntity
import com.syconx.us2us.core.relay.PreKeyEntity
import com.syconx.us2us.core.relay.SignedPreKeyEntity
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import java.security.SecureRandom

/** Key ids are 24-bit and never 0 (PROTOCOL.md §4.3, §5); libsignal reserves `0xFFFFFFFF`. */
object KeyIds {
    const val MIN = 1
    const val MAX = 0xFFFFFF

    fun next(id: Int): Int = if (id >= MAX) MIN else id + 1

    private val secureRandom = SecureRandom()

    fun random(): Int = secureRandom.nextInt(MAX) + MIN

    fun isValid(id: Int): Boolean = id in MIN..MAX
}

/** Generation of the account's long-term key material (PROTOCOL.md §5). */
object Keys {
    const val REGISTRATION_ID_MAX = 16380

    /** EC public keys and identity keys: `0x05 ‖ X25519` (§2). */
    const val EC_PUBLIC_KEY_LENGTH = 33

    /** Kyber-1024 public keys: type byte `0x08` ‖ 1568-byte encapsulation key. */
    const val KYBER_PUBLIC_KEY_LENGTH = 1569
    const val SIGNATURE_LENGTH = 64

    fun generateIdentityKeyPair(): IdentityKeyPair = IdentityKeyPair.generate()

    /** Random in 1..16380. */
    fun generateRegistrationId(): Int = KeyHelper.generateRegistrationId(false)

    /** A signed EC pre-key; the identity key signs the 33-byte serialized public key. */
    fun signedPreKey(identity: IdentityKeyPair, id: Int, timestamp: Long): SignedPreKeyRecord {
        require(KeyIds.isValid(id)) { "invalid key id" }
        val keyPair = ECKeyPair.generate()
        val signature = identity.privateKey.calculateSignature(keyPair.publicKey.serialize())
        return SignedPreKeyRecord(id, timestamp, keyPair, signature)
    }

    /** A Kyber-1024 pre-key (one-time or last-resort); the identity key signs the serialized `KEMPublicKey`. */
    fun kyberPreKey(identity: IdentityKeyPair, id: Int, timestamp: Long): KyberPreKeyRecord {
        require(KeyIds.isValid(id)) { "invalid key id" }
        val keyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val signature = identity.privateKey.calculateSignature(keyPair.publicKey.serialize())
        return KyberPreKeyRecord(id, timestamp, keyPair, signature)
    }

    fun oneTimePreKey(id: Int): PreKeyRecord {
        require(KeyIds.isValid(id)) { "invalid key id" }
        return PreKeyRecord(id, ECKeyPair.generate())
    }
}

fun SignedPreKeyRecord.toEntity() = SignedPreKeyEntity(id, keyPair.publicKey.serialize(), signature)

fun KyberPreKeyRecord.toEntity() = SignedPreKeyEntity(id, keyPair.publicKey.serialize(), signature)

fun PreKeyRecord.toEntity() = PreKeyEntity(id, keyPair.publicKey.serialize())

/** A relay bundle that does not satisfy the wire format of PROTOCOL.md §4.5. */
class InvalidBundleException(message: String) : Exception(message)

/**
 * Converts a relay bundle into libsignal's [PreKeyBundle], checking every length and range first.
 * libsignal accepts trailing bytes after an EC key, so lengths are checked here; signatures are
 * verified by libsignal when the bundle is processed.
 */
fun PreKeyBundleEntity.toPreKeyBundle(expectedAccountId: AccountId): PreKeyBundle {
    fun check(condition: Boolean, what: String) {
        if (!condition) throw InvalidBundleException(what)
    }
    check(accountId == expectedAccountId, "bundle is for a different account")
    check(deviceId == AccountId.DEVICE_ID, "unexpected device id")
    check(registrationId in 1..Keys.REGISTRATION_ID_MAX, "registration id out of range")
    check(identityKey.size == Keys.EC_PUBLIC_KEY_LENGTH, "identity key length")
    check(KeyIds.isValid(signedPreKey.keyId) && signedPreKey.publicKey.size == Keys.EC_PUBLIC_KEY_LENGTH, "signed pre-key")
    check(signedPreKey.signature.size == Keys.SIGNATURE_LENGTH, "signed pre-key signature length")
    check(KeyIds.isValid(kyberPreKey.keyId) && kyberPreKey.publicKey.size == Keys.KYBER_PUBLIC_KEY_LENGTH, "Kyber pre-key")
    check(kyberPreKey.signature.size == Keys.SIGNATURE_LENGTH, "Kyber pre-key signature length")
    oneTimePreKey?.let { check(KeyIds.isValid(it.keyId) && it.publicKey.size == Keys.EC_PUBLIC_KEY_LENGTH, "one-time pre-key") }

    return try {
        PreKeyBundle(
            registrationId,
            deviceId,
            oneTimePreKey?.keyId ?: PreKeyBundle.NULL_PRE_KEY_ID,
            oneTimePreKey?.let { ECPublicKey(it.publicKey) },
            signedPreKey.keyId,
            ECPublicKey(signedPreKey.publicKey),
            signedPreKey.signature,
            IdentityKey(identityKey),
            kyberPreKey.keyId,
            KEMPublicKey(kyberPreKey.publicKey),
            kyberPreKey.signature,
        )
    } catch (e: InvalidKeyException) {
        throw InvalidBundleException("malformed key: ${e.message}")
    }
}
