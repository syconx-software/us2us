package com.syconx.us2us.core.security

import com.syconx.us2us.core.encoding.Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.signal.libsignal.protocol.ServiceId.Aci
import org.signal.libsignal.zkgroup.ServerPublicParams
import org.signal.libsignal.zkgroup.ServerSecretParams
import org.signal.libsignal.zkgroup.VerificationFailedException
import org.signal.libsignal.zkgroup.auth.AuthCredentialPresentation
import org.signal.libsignal.zkgroup.auth.AuthCredentialWithPniResponse
import org.signal.libsignal.zkgroup.auth.ClientZkAuthOperations
import org.signal.libsignal.zkgroup.auth.ServerZkAuthOperations
import org.signal.libsignal.zkgroup.groups.ClientZkGroupCipher
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import java.io.File
import java.time.Instant
import java.util.UUID

/** Upstream feasibility only: no application transport, membership policy or production keys. */
class PrivateGroupUpstreamProbeTest {
    private val fixture = Json.parseToJsonElement(File(System.getProperty("us2us.specDir"),
        "test-vectors/communities-privacy-probe.json").readText()).jsonObject
    private fun text(name: String) = fixture.getValue(name).jsonPrimitive.content
    private fun bytes(name: String) = Hex.decode(text(name))
    private val aci = Aci(UUID.fromString(text("accountId")))
    private val salt = bytes("saltHex")
    private val redemption = text("redemptionTime").toLong()
    private val now = Instant.ofEpochSecond(text("verificationTime").toLong())
    private val group = GroupSecretParams.deriveFromMasterKey(GroupMasterKey(bytes("masterKeyHex")))
    private val issuer = ServerZkAuthOperations(ServerSecretParams(bytes("serverSecretParamsHex")))
    private val client = ClientZkAuthOperations(ServerPublicParams(bytes("serverPublicParamsHex")))
    private val response get() = AuthCredentialWithPniResponse(bytes("credentialResponseHex"))
    private val credential get() = client.receiveAuthCredentialWithoutPni(aci, salt, redemption, response)
    private val proof get() = AuthCredentialPresentation(bytes("presentationHex"))
    private fun otherGroup() = GroupSecretParams.deriveFromMasterKey(GroupMasterKey(ByteArray(32) { 0x12 }))

    @Test
    fun `shared no-phone credential and presentation verify with the pinned upstream library`() {
        assertEquals("0.105.0", text("libsignalVersion"))
        assertArrayEquals(bytes("groupPublicParamsHex"), group.publicParams.serialize())
        issuer.verifyAuthCredentialPresentation(group.publicParams, proof, now)
        assertArrayEquals(bytes("memberCiphertextHex"), proof.uuidCiphertext.serialize())
        val fresh = client.createAuthCredentialPresentation(group, credential)
        issuer.verifyAuthCredentialPresentation(group.publicParams, fresh, now)
        assertArrayEquals(bytes("memberCiphertextHex"), fresh.uuidCiphertext.serialize())
    }

    @Test
    fun `encrypted member identifiers are deterministic within one group and differ across groups`() {
        val cipher = ClientZkGroupCipher(group)
        val encrypted = cipher.encrypt(aci)
        assertArrayEquals(bytes("memberCiphertextHex"), encrypted.serialize())
        assertArrayEquals(encrypted.serialize(), cipher.encrypt(aci).serialize())
        assertEquals(aci, cipher.decrypt(encrypted))
        val other = ClientZkGroupCipher(otherGroup())
        assertFalse(encrypted.serialize().contentEquals(other.encrypt(aci).serialize()))
        assertThrows(VerificationFailedException::class.java) { other.decrypt(encrypted) }
    }

    @Test
    fun `credential response cannot be received under another account salt or issuer`() {
        assertThrows(VerificationFailedException::class.java) {
            client.receiveAuthCredentialWithoutPni(Aci(UUID(0, 2)), salt, redemption, response)
        }
        assertThrows(VerificationFailedException::class.java) {
            client.receiveAuthCredentialWithoutPni(aci, ByteArray(32) { 0x45 }, redemption, response)
        }
        val other = ClientZkAuthOperations(ServerSecretParams.generate().publicParams)
        assertThrows(VerificationFailedException::class.java) {
            other.receiveAuthCredentialWithoutPni(aci, salt, redemption, response)
        }
    }

    @Test
    fun `presentation is rejected for another group issuer or expired window`() {
        assertThrows(VerificationFailedException::class.java) {
            issuer.verifyAuthCredentialPresentation(otherGroup().publicParams, proof, now)
        }
        assertThrows(VerificationFailedException::class.java) {
            ServerZkAuthOperations(ServerSecretParams.generate()).verifyAuthCredentialPresentation(group.publicParams, proof, now)
        }
        assertThrows(VerificationFailedException::class.java) {
            issuer.verifyAuthCredentialPresentation(group.publicParams, proof, now.plusSeconds(7 * 86400))
        }
    }

    @Test
    fun `randomized presentations differ while referring to the same encrypted member`() {
        val first = client.createAuthCredentialPresentation(group, credential)
        val second = client.createAuthCredentialPresentation(group, credential)
        assertFalse(first.serialize().contentEquals(second.serialize()))
        for (p in listOf(first, second)) {
            issuer.verifyAuthCredentialPresentation(group.publicParams, p, now)
            assertArrayEquals(bytes("memberCiphertextHex"), p.uuidCiphertext.serialize())
        }
        // Different bytes are not a proof of unlinkability; the verifier still sees the group member ciphertext.
    }

    @Test
    fun `cryptographic authentication alone does not enforce the approved member list`() {
        val outsider = Aci(UUID(0, 3))
        val issued = issuer.issueAuthCredentialZkcWithoutPni(outsider, salt, Instant.ofEpochSecond(redemption))
        val received = client.receiveAuthCredentialWithoutPni(outsider, salt, redemption, issued)
        val outsiderProof = client.createAuthCredentialPresentation(group, received)
        // A removed member can still know group secrets and possess a real account credential.
        issuer.verifyAuthCredentialPresentation(group.publicParams, outsiderProof, now)
        assertFalse(bytes("memberCiphertextHex").contentEquals(outsiderProof.uuidCiphertext.serialize()))
        // A future service MUST compare this ciphertext to active approved entries and enforce role/revision.
    }

    @Test
    fun `upstream encrypted state rejects tampering and another group secret`() {
        val cipher = ClientZkGroupCipher(group)
        val plaintext = "SYNTHETIC-GROUP-STATE".toByteArray()
        val blob = cipher.encryptBlob(plaintext)
        assertArrayEquals(plaintext, cipher.decryptBlob(blob))
        assertThrows(VerificationFailedException::class.java) { ClientZkGroupCipher(otherGroup()).decryptBlob(blob) }
        // Flip authenticated ciphertext, not the upstream's deliberately ignored reserved trailer.
        val tampered = blob.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(VerificationFailedException::class.java) { cipher.decryptBlob(tampered) }
    }

    @Test
    fun `reserved blob trailer cannot serve as an authenticated application version`() {
        val cipher = ClientZkGroupCipher(group)
        val plaintext = "SYNTHETIC-GROUP-STATE".toByteArray()
        val blob = cipher.encryptBlob(plaintext)
        val changed = blob.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertArrayEquals(plaintext, cipher.decryptBlob(changed))
        // Pinned upstream encrypt_blob/decrypt_blob explicitly append/ignore this reserved byte.
        // A future protocol must bind its version/state through its reviewed authenticated encoding.
    }

    @Test
    fun `one hundred encrypted member identifiers stay distinct and roundtrip`() {
        val cipher = ClientZkGroupCipher(group)
        val ids = (1L..100L).map { Aci(UUID(0, it)) }
        val encrypted = ids.map { cipher.encrypt(it) }
        assertEquals(100, encrypted.map { Hex.encode(it.serialize()) }.toSet().size)
        assertEquals(ids, encrypted.map { cipher.decrypt(it) })
        // This probes identifier handling, not a 100-member service authorization/load gate.
    }
}
