package com.syconx.us2us.core.attachment

import com.syconx.us2us.core.crypto.HmacSha256
import com.syconx.us2us.core.crypto.Sha256
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class AttachmentCipherTest {
    private val key = ByteArray(64) { it.toByte() }

    @Test
    fun `padded sizes follow the integer sequence`() {
        val expected = mapOf(
            0L to 541L, 1L to 541L, 541L to 541L, 542L to 568L, 568L to 568L, 569L to 596L,
            1000L to 1011L, 10_000L to 10_433L, 1_048_576L to 1_073_892L, 52_428_800L to 53_223_193L,
        )
        expected.forEach { (n, p) -> assertEquals(p, AttachmentCipher.paddedSize(n), "P($n)") }
    }

    @Test
    fun `padded sizes match a direct transcription of the spec formula`() {
        fun reference(n: Long): Long {
            if (n <= 541) return 541
            var s = 541L
            while (s < n) s += maxOf(1L, s / 20)
            return s
        }
        val rnd = Random(1)
        repeat(2000) {
            val n = rnd.nextLong(0, 200_000_000)
            assertEquals(reference(n), AttachmentCipher.paddedSize(n))
        }
    }

    @Test
    fun `blob layout is iv, CBC ciphertext of the padded plaintext, then the HMAC`() {
        val plaintext = "hello".toByteArray()
        val iv = ByteArray(16) { 9 }
        val (blob, info) = AttachmentCipher.encrypt(plaintext, key, iv)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, 0, 32, "AES"), IvParameterSpec(iv))
        }
        val ciphertext = cipher.doFinal(plaintext.copyOf(541))
        val mac = HmacSha256.compute(key.copyOfRange(32, 64), iv, ciphertext)
        assertArrayEquals(iv + ciphertext + mac, blob)
        assertArrayEquals(Sha256.digest(blob), info.digest)
        assertEquals(5L, info.plaintextSize)
        assertEquals(blob.size.toLong(), info.blobSize)
        assertEquals(AttachmentCipher.blobSize(5), info.blobSize)
    }

    @Test
    fun `round-trips across padding boundaries and buffer sizes`() {
        val rnd = Random(2)
        for (size in listOf(0, 1, 15, 16, 541, 542, 600, 5000, 65_535, 65_536, 200_001, 3_000_000)) {
            val plaintext = rnd.nextBytes(size)
            val (blob, info) = AttachmentCipher.encrypt(plaintext, key)
            assertEquals(AttachmentCipher.blobSize(size.toLong()), blob.size.toLong(), "size $size")
            assertArrayEquals(plaintext, AttachmentCipher.decrypt(blob, key, info.digest, info.plaintextSize), "size $size")
        }
    }

    @Test
    fun `encrypting a stream that delivers short reads gives the same blob`() {
        val plaintext = Random(3).nextBytes(100_000)
        val iv = ByteArray(16) { 1 }
        val dribble = object : InputStream() {
            private val inner = ByteArrayInputStream(plaintext)
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, minOf(len, 7))
        }
        val out = ByteArrayOutputStream()
        AttachmentCipher.encrypt(dribble, key, out, iv)
        assertArrayEquals(AttachmentCipher.encrypt(plaintext, key, iv).first, out.toByteArray())
    }

    @Test
    fun `any tampering is detected before decryption`() {
        val plaintext = Random(4).nextBytes(2000)
        val (blob, info) = AttachmentCipher.encrypt(plaintext, key)
        for (position in listOf(0, 15, 16, 100, blob.size - 33, blob.size - 32, blob.size - 1)) {
            val tampered = blob.copyOf().also { it[position] = (it[position].toInt() xor 0x40).toByte() }
            assertThrows<AttachmentIntegrityException>("byte $position") { AttachmentCipher.decrypt(tampered, key, info.digest, 2000) }
            // Even with a digest computed over the tampered blob, the MAC still catches it.
            assertThrows<AttachmentIntegrityException>("byte $position, digest") {
                AttachmentCipher.decrypt(tampered, key, Sha256.digest(tampered), 2000)
            }
        }
        assertThrows<AttachmentIntegrityException> { AttachmentCipher.decrypt(blob.copyOf(blob.size - 1), key, info.digest, 2000) }
        assertThrows<AttachmentIntegrityException> { AttachmentCipher.decrypt(blob + 0, key, Sha256.digest(blob + 0), 2000) }
        assertThrows<AttachmentIntegrityException> { AttachmentCipher.decrypt(ByteArray(10), key, Sha256.digest(ByteArray(10)), 0) }
        val wrongKey = key.copyOf().also { it[40] = 0 }
        assertThrows<AttachmentIntegrityException> { AttachmentCipher.decrypt(blob, wrongKey, info.digest, 2000) }
    }

    @Test
    fun `hostile sizes are refused instead of overflowing`() {
        assertThrows<IllegalArgumentException> { AttachmentCipher.paddedSize(-1) }
        assertThrows<IllegalArgumentException> { AttachmentCipher.paddedSize(Long.MAX_VALUE) }
        assertThrows<IllegalArgumentException> { AttachmentCipher.paddedSize(AttachmentCipher.MAX_PLAINTEXT_SIZE + 1) }
        val largest = AttachmentCipher.blobSize(AttachmentCipher.MAX_PLAINTEXT_SIZE)
        assertTrue(largest > AttachmentCipher.MAX_PLAINTEXT_SIZE)
        // A declared size far beyond the blob neither allocates it nor passes.
        val (blob, info) = AttachmentCipher.encrypt(ByteArray(10), key)
        assertThrows<AttachmentIntegrityException> { AttachmentCipher.decrypt(blob, key, info.digest, Long.MAX_VALUE) }
    }

    @Test
    fun `a declared size beyond the padded plaintext is rejected`() {
        val (blob, info) = AttachmentCipher.encrypt(ByteArray(10), key)
        assertThrows<AttachmentIntegrityException> { AttachmentCipher.decrypt(blob, key, info.digest, 542) }
        assertEquals(541, AttachmentCipher.decrypt(blob, key, info.digest, 541).size)
    }
}
