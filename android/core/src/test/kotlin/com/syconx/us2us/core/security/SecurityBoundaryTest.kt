package com.syconx.us2us.core.security

import com.google.protobuf.ByteString
import com.syconx.us2us.core.attachment.AttachmentCipher
import com.syconx.us2us.core.attachment.AttachmentIntegrityException
import com.syconx.us2us.core.proto.sealedContent
import com.syconx.us2us.core.session.SealedEnvelope
import com.syconx.us2us.core.session.SessionCrypto
import com.syconx.us2us.core.store.InMemoryProtocolStore
import com.syconx.us2us.core.testing.Party
import com.syconx.us2us.core.vault.DataKey
import com.syconx.us2us.core.vault.Vault
import com.syconx.us2us.core.vault.WrongSecretCodeException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.message.CiphertextMessage

/** Local synthetic accounts only. Demonstrates boundaries, not a break of AES or libsignal. */
class SecurityBoundaryTest {
    @Test
    fun `stolen long term identity opens recorded sender metadata but not an established ratchet`() {
        val alice = Party()
        val bob = Party()
        alice.sessions.startSession(bob.accountId, bob.bundle())
        bob.decryptFrom(alice, alice.encryptTo(bob, "handshake"))
        alice.decryptFrom(bob, bob.encryptTo(alice, "reply"))
        val inner = alice.encryptTo(bob, "PRIVATE-CONTENT")
        assertEquals(CiphertextMessage.WHISPER_TYPE, inner.type)
        val envelope = SealedEnvelope.seal(sealedContent {
            senderAccountId = ByteString.copyFrom(alice.accountId.toBytes())
            senderDeviceId = 1
            type = inner.type
            ciphertext = ByteString.copyFrom(inner.serialize())
        }, bob.identityKey)
        // Assume the private identity was already extracted. This does not extract anything from a device.
        val stolenIdentity = IdentityKeyPair(bob.store.identityKeyPair.serialize())
        val opened = SealedEnvelope.open(envelope, stolenIdentity)
        assertArrayEquals(alice.accountId.toBytes(), opened.senderAccountId.toByteArray())
        val noSessions = InMemoryProtocolStore(stolenIdentity, bob.store.localRegistrationId)
        assertThrows<NoSessionException> {
            SessionCrypto(noSessions, bob.accountId).decrypt(alice.accountId, opened.type, opened.ciphertext.toByteArray())
        }
        assertEquals("PRIVATE-CONTENT", bob.decryptFrom(alice, inner))
    }

    @Test
    fun `copied media is opaque with a wrong key but anyone given the real key can read it`() {
        val original = "SYNTHETIC-PHOTO-OR-FILE".toByteArray()
        val key = AttachmentCipher.generateKey()
        val (blob, info) = AttachmentCipher.encrypt(original, key)
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("SYNTHETIC-PHOTO-OR-FILE"))
        assertThrows<AttachmentIntegrityException> {
            AttachmentCipher.decrypt(blob, AttachmentCipher.generateKey(), info.digest, info.plaintextSize)
        }
        // The crypto API takes no membership: authorization must protect the key, not just the URL.
        assertArrayEquals(original, AttachmentCipher.decrypt(blob, key, info.digest, info.plaintextSize))
    }

    @Test
    fun `v1 with an extracted device secret permits offline PIN checks at production KDF cost`() {
        val deviceSecret = ByteArray(32) { 0x42 }
        val dek = DataKey.generate()
        val vault = Vault.create("472913", deviceSecret, dek) // real 600,000 PBKDF2 rounds
        assertEquals(1, vault[4].toInt())
        assertThrows<WrongSecretCodeException> { Vault.open(vault, "472913", ByteArray(32) { 0x43 }) }
        val started = System.nanoTime()
        repeat(3) {
            assertThrows<WrongSecretCodeException> { Vault.open(vault, "472914", deviceSecret) }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        println("SECURITY_AUDIT synthetic-v1 production-kdf wrong-attempts=3 elapsed-ms=$elapsedMs")
        assertArrayEquals(dek.toBytes(), Vault.open(vault, "472913", deviceSecret).toBytes())
        // No app lockout store is consulted by Vault.open. Extraction is an explicit assumption.
        // The measured timing is not an optimized GPU/ASIC cracking estimate.
    }
}
