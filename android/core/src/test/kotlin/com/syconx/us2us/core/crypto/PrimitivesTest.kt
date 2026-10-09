package com.syconx.us2us.core.crypto

import com.syconx.us2us.core.encoding.Hex
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PrimitivesTest {

    @Test
    fun `PBKDF2-HMAC-SHA256 matches the RFC 7914 test vectors`() {
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            Hex.encode(Pbkdf2.hmacSha256("passwd".toByteArray(), "salt".toByteArray(), 1, 64)),
        )
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            Hex.encode(Pbkdf2.hmacSha256("Password".toByteArray(), "NaCl".toByteArray(), 80000, 64)),
        )
    }

    @Test
    fun `PBKDF2 agrees with the JDK implementation for ASCII passwords`() {
        val salt = "BRP/username/v1".toByteArray()
        val jdk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("alice_01".toCharArray(), salt, 1000, 256)).encoded
        assertArrayEquals(jdk, Pbkdf2.hmacSha256("alice_01".toByteArray(), salt, 1000, 32))
    }

    @Test
    fun `HKDF-SHA256 matches RFC 5869 test cases 1 to 3`() {
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Hex.encode(Hkdf.sha256(Hex.decode("0b".repeat(22)), Hex.decode("000102030405060708090a0b0c"), Hex.decode("f0f1f2f3f4f5f6f7f8f9"), 42)),
        )
        val ikm2 = ByteArray(80) { it.toByte() }
        val salt2 = ByteArray(80) { (0x60 + it).toByte() }
        val info2 = ByteArray(80) { (0xb0 + it).toByte() }
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71cc30c58179ec3e87c14c01d5c1f3434f1d87",
            Hex.encode(Hkdf.sha256(ikm2, salt2, info2, 82)),
        )
        // Case 3: empty salt and info.
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            Hex.encode(Hkdf.sha256(Hex.decode("0b".repeat(22)), ByteArray(0), ByteArray(0), 42)),
        )
    }

    @Test
    fun `AES-GCM round-trips and rejects any modification`() {
        val key = ByteArray(32) { 1 }
        val nonce = ByteArray(12) { 2 }
        val aad = byteArrayOf(9, 9)
        val sealed = AesGcm.seal(key, nonce, aad, "secret".toByteArray())
        assertEquals(6 + AesGcm.TAG_LENGTH, sealed.size)
        assertArrayEquals("secret".toByteArray(), AesGcm.open(key, nonce, aad, sealed))

        for (i in sealed.indices) {
            val tampered = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertThrows<AEADBadTagException> { AesGcm.open(key, nonce, aad, tampered) }
        }
        assertThrows<AEADBadTagException> { AesGcm.open(key, nonce, byteArrayOf(9), sealed) }
        assertThrows<AEADBadTagException> { AesGcm.open(key, ByteArray(12), aad, sealed) }
        assertThrows<AEADBadTagException> { AesGcm.open(key, nonce, aad, ByteArray(5)) }
    }

    @Test
    fun `constant-time comparison`() {
        assertTrue(ConstantTime.equals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(ConstantTime.equals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(ConstantTime.equals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertTrue(ConstantTime.equals(ByteArray(0), ByteArray(0)))
    }
}
