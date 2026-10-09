package com.syconx.us2us.core.session

import com.google.protobuf.kotlin.toByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.keys.Keys
import com.syconx.us2us.core.proto.SealedContent
import com.syconx.us2us.core.proto.sealedContent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class SealedEnvelopeTest {
    private val recipient = Keys.generateIdentityKeyPair()
    private val sender = AccountId(UUID.randomUUID())

    private fun content(type: Int = 3, deviceId: Int = 1, id: ByteArray = sender.toBytes(), username: String = "alice_01"): SealedContent =
        sealedContent {
            senderAccountId = id.toByteString()
            senderDeviceId = deviceId
            this.type = type
            ciphertext = byteArrayOf(1, 2, 3, 4, 5).toByteString()
            senderUsername = username
        }

    @Test
    fun `seal and open round-trip`() {
        val envelope = SealedEnvelope.seal(content(), recipient.publicKey)
        assertEquals(0x01, envelope[0].toInt())
        assertEquals(content(), SealedEnvelope.open(envelope, recipient))
    }

    @Test
    fun `every envelope uses a fresh encapsulation`() {
        val a = SealedEnvelope.seal(content(), recipient.publicKey)
        val b = SealedEnvelope.seal(content(), recipient.publicKey)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `any flipped byte makes the envelope unopenable`() {
        val envelope = SealedEnvelope.seal(content(), recipient.publicKey)
        for (i in envelope.indices) {
            val tampered = envelope.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertThrows<InvalidEnvelopeException>("byte $i") { SealedEnvelope.open(tampered, recipient) }
        }
    }

    @Test
    fun `truncated, extended, empty and foreign envelopes are rejected`() {
        val envelope = SealedEnvelope.seal(content(), recipient.publicKey)
        assertThrows<InvalidEnvelopeException> { SealedEnvelope.open(envelope.copyOf(envelope.size - 1), recipient) }
        assertThrows<InvalidEnvelopeException> { SealedEnvelope.open(envelope + 0, recipient) }
        assertThrows<InvalidEnvelopeException> { SealedEnvelope.open(ByteArray(0), recipient) }
        assertThrows<InvalidEnvelopeException> { SealedEnvelope.open(byteArrayOf(0x01), recipient) }
        assertThrows<InvalidEnvelopeException> { SealedEnvelope.open(envelope, Keys.generateIdentityKeyPair()) }
        val wrongVersion = envelope.copyOf().also { it[0] = 0x02 }
        assertThrows<InvalidEnvelopeException> { SealedEnvelope.open(wrongVersion, recipient) }
    }

    @Test
    fun `contents that violate protocol v1 are rejected after opening`() {
        assertThrows<InvalidEnvelopeException> { SealedEnvelope.open(SealedEnvelope.seal(content(type = 8), recipient.publicKey), recipient) }
        assertThrows<InvalidEnvelopeException> { SealedEnvelope.open(SealedEnvelope.seal(content(deviceId = 2), recipient.publicKey), recipient) }
        assertThrows<InvalidEnvelopeException> { SealedEnvelope.open(SealedEnvelope.seal(content(id = ByteArray(15)), recipient.publicKey), recipient) }
        val notProtobuf = byteArrayOf(0x01) + recipient.publicKey.publicKey.seal(byteArrayOf(0x0a, 0x7f), SealedEnvelope.HPKE_INFO, byteArrayOf(0x01))
        assertThrows<InvalidEnvelopeException> { SealedEnvelope.open(notProtobuf, recipient) }
    }

    @Test
    fun `the HPKE info and associated data are bound`() {
        val plaintext = content().toByteArray()
        val wrongInfo = byteArrayOf(0x01) + recipient.publicKey.publicKey.seal(plaintext, "BRP/sealed/v2", byteArrayOf(0x01))
        val wrongAad = byteArrayOf(0x01) + recipient.publicKey.publicKey.seal(plaintext, SealedEnvelope.HPKE_INFO, byteArrayOf(0x02))
        assertThrows<InvalidEnvelopeException> { SealedEnvelope.open(wrongInfo, recipient) }
        assertThrows<InvalidEnvelopeException> { SealedEnvelope.open(wrongAad, recipient) }
    }
}
