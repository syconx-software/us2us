package com.syconx.us2us.core.keys

import com.syconx.us2us.core.relay.KeyStatus
import com.syconx.us2us.core.relay.KeyUploadRequest
import com.syconx.us2us.core.relay.PowSubmission
import com.syconx.us2us.core.relay.RegistrationRequest
import com.syconx.us2us.core.relay.RelayJson
import com.syconx.us2us.core.store.InMemoryProtocolStore
import com.syconx.us2us.core.testing.MutableClock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.signal.libsignal.protocol.kem.KEMPublicKey
import kotlin.time.Duration.Companion.days

class KeysTest {
    private val identity = Keys.generateIdentityKeyPair()

    @Test
    fun `pre-key signatures cover the serialized public keys`() {
        val signed = Keys.signedPreKey(identity, 1, 1000)
        assertEquals(Keys.EC_PUBLIC_KEY_LENGTH, signed.keyPair.publicKey.serialize().size)
        assertEquals(Keys.SIGNATURE_LENGTH, signed.signature.size)
        assertTrue(identity.publicKey.publicKey.verifySignature(signed.keyPair.publicKey.serialize(), signed.signature))

        val kyber = Keys.kyberPreKey(identity, 2, 1000)
        val kyberPublic: KEMPublicKey = kyber.keyPair.publicKey
        assertEquals(Keys.KYBER_PUBLIC_KEY_LENGTH, kyberPublic.serialize().size)
        assertEquals(0x08, kyberPublic.serialize()[0].toInt())
        assertTrue(identity.publicKey.publicKey.verifySignature(kyberPublic.serialize(), kyber.signature))
    }

    @Test
    fun `registration ids and key ids stay in range`() {
        repeat(200) {
            assertTrue(Keys.generateRegistrationId() in 1..16380)
            assertTrue(KeyIds.isValid(KeyIds.random()))
        }
        assertEquals(KeyIds.MIN, KeyIds.next(KeyIds.MAX))
        assertFalse(KeyIds.isValid(0))
        assertFalse(KeyIds.isValid(0x1000000))
    }

    @Test
    fun `registration JSON uses the field names of the spec`() {
        val store = InMemoryProtocolStore(identity, 1234)
        val keys = PreKeyManager(store, PreKeyPolicy(batchSize = 3, refillThreshold = 1)).generateFullSet()
        val request = RegistrationRequest(ByteArray(32), identity.publicKey.serialize(), 1234, keys.signedPreKey, keys.kyberLastResortKey, keys.oneTimePreKeys, keys.kyberOneTimePreKeys, PowSubmission("t", "123"))
        val json = RelayJson.parseToJsonElement(RelayJson.encodeToString(RegistrationRequest.serializer(), request)).jsonObject

        assertEquals(setOf("usernameHash", "identityKey", "registrationId", "signedPreKey", "kyberLastResortKey", "oneTimePreKeys", "kyberOneTimePreKeys", "pow"), json.keys)
        assertEquals(setOf("keyId", "publicKey", "signature"), json["signedPreKey"]!!.jsonObject.keys)
        assertEquals(setOf("keyId", "publicKey"), json["oneTimePreKeys"]!!.jsonArray[0].jsonObject.keys)
        assertEquals(setOf("keyId", "publicKey", "signature"), json["kyberOneTimePreKeys"]!!.jsonArray[0].jsonObject.keys)
        assertEquals("123", json["pow"]!!.jsonObject["nonce"]!!.jsonPrimitive.content)
        assertEquals(44, json["identityKey"]!!.jsonPrimitive.content.length, "33 bytes in unpadded base64url")
        assertEquals(3, json["oneTimePreKeys"]!!.jsonArray.size)
    }

    @Test
    fun `key upload JSON omits absent groups`() {
        val store = InMemoryProtocolStore(identity, 1)
        val upload = PreKeyManager(store).generateFullSet().toUploadRequest(replaceAll = true)
        val json = RelayJson.parseToJsonElement(RelayJson.encodeToString(KeyUploadRequest.serializer(), upload)) as JsonObject
        assertEquals("true", json["replaceAll"]!!.jsonPrimitive.content)

        val partial = KeyUploadRequest(oneTimePreKeys = upload.oneTimePreKeys)
        val partialJson = RelayJson.parseToJsonElement(RelayJson.encodeToString(KeyUploadRequest.serializer(), partial)) as JsonObject
        assertEquals(setOf("oneTimePreKeys"), partialJson.keys)
    }

    @Test
    fun `full key set is stored locally with unique ids`() {
        val store = InMemoryProtocolStore(identity, 1)
        val set = PreKeyManager(store, PreKeyPolicy(batchSize = 10, refillThreshold = 2)).generateFullSet()
        assertEquals(10, set.oneTimePreKeys.map { it.keyId }.toSet().size)
        set.oneTimePreKeys.forEach { assertTrue(store.containsPreKey(it.keyId)) }
        val kyberIds = set.kyberOneTimePreKeys.map { it.keyId } + set.kyberLastResortKey.keyId
        assertEquals(11, kyberIds.toSet().size, "one-time and last-resort Kyber keys share an id space")
        assertTrue(store.isLastResortKyberPreKey(set.kyberLastResortKey.keyId))
        set.kyberOneTimePreKeys.forEach { assertFalse(store.isLastResortKyberPreKey(it.keyId)) }
        assertTrue(store.containsSignedPreKey(set.signedPreKey.keyId))
    }

    @Test
    fun `maintenance refills, rotates and retires on schedule`() {
        val clock = MutableClock()
        val store = InMemoryProtocolStore(identity, 1, clock)
        val manager = PreKeyManager(store, PreKeyPolicy(batchSize = 10, refillThreshold = 3), clock)
        val initial = manager.generateFullSet()
        fun status(oneTime: Int, kyber: Int, signed: Int = initial.signedPreKey.keyId, lastResort: Int = initial.kyberLastResortKey.keyId) =
            KeyStatus(oneTime, kyber, signed, lastResort)

        assertNull(manager.maintain(status(10, 10)), "nothing due")
        assertNull(manager.maintain(status(3, 3)), "at the threshold is still enough")

        val refill = manager.maintain(status(2, 0))!!
        assertEquals(8, refill.oneTimePreKeys!!.size)
        assertEquals(10, refill.kyberOneTimePreKeys!!.size)
        assertNull(refill.signedPreKey)
        assertNull(refill.kyberLastResortKey)

        clock.advance(30.days)
        val rotation = manager.maintain(status(10, 10))!!
        val newSigned = rotation.signedPreKey!!
        val newLastResort = rotation.kyberLastResortKey!!
        assertTrue(newSigned.keyId != initial.signedPreKey.keyId)
        assertTrue(store.containsSignedPreKey(initial.signedPreKey.keyId), "the old key stays for late messages")
        assertTrue(store.containsKyberPreKey(initial.kyberLastResortKey.keyId))

        clock.advance(29.days)
        assertNull(manager.maintain(status(10, 10, newSigned.keyId, newLastResort.keyId)))
        assertTrue(store.containsSignedPreKey(initial.signedPreKey.keyId))

        // Day 60: the first keys are retired 30 days after their successors appeared, and those
        // successors are now due for rotation themselves.
        clock.advance(1.days)
        val second = manager.maintain(status(10, 10, newSigned.keyId, newLastResort.keyId))!!
        assertFalse(store.containsSignedPreKey(initial.signedPreKey.keyId), "retired 30 days after its successor")
        assertFalse(store.containsKyberPreKey(initial.kyberLastResortKey.keyId))
        assertTrue(store.containsSignedPreKey(newSigned.keyId))
        assertTrue(store.containsKyberPreKey(newLastResort.keyId))
        assertTrue(store.containsSignedPreKey(second.signedPreKey!!.keyId))
        assertTrue(store.isLastResortKyberPreKey(second.kyberLastResortKey!!.keyId))
    }

    @Test
    fun `maintenance re-uploads keys the relay does not have`() {
        val clock = MutableClock()
        val store = InMemoryProtocolStore(identity, 1, clock)
        val manager = PreKeyManager(store, clock = clock)
        manager.generateFullSet()
        val upload = manager.maintain(KeyStatus(100, 100, signedPreKeyId = null, kyberLastResortKeyId = null))!!
        assertNotNull(upload.signedPreKey)
        assertNotNull(upload.kyberLastResortKey)
        // Made within the same millisecond as the first set, the new keys are still the current ones.
        assertNull(manager.maintain(KeyStatus(100, 100, upload.signedPreKey!!.keyId, upload.kyberLastResortKey!!.keyId)))
    }
}
