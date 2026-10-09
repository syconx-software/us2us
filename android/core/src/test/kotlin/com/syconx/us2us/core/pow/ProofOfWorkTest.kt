package com.syconx.us2us.core.pow

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class ProofOfWorkTest {

    @Test
    fun `leading zero bits are counted across bytes`() {
        assertEquals(0, ProofOfWork.leadingZeroBits(byteArrayOf(0x80.toByte())))
        assertEquals(7, ProofOfWork.leadingZeroBits(byteArrayOf(0x01)))
        assertEquals(8, ProofOfWork.leadingZeroBits(byteArrayOf(0x00, 0xff.toByte())))
        assertEquals(12, ProofOfWork.leadingZeroBits(byteArrayOf(0x00, 0x08)))
        assertEquals(16, ProofOfWork.leadingZeroBits(byteArrayOf(0x00, 0x00)))
    }

    @Test
    fun `the hashed input is the UTF-8 token followed by the big-endian nonce`() {
        val token = "v1.message.x.1760000000.abc.def"
        val nonce = 0x0102030405060708uL
        val hash = MessageDigest.getInstance("SHA-256").digest(
            token.toByteArray() + ByteBuffer.allocate(8).putLong(nonce.toLong()).array(),
        )
        val bits = ProofOfWork.leadingZeroBits(hash)
        assertTrue(ProofOfWork.isValid(token, nonce, bits))
        assertFalse(ProofOfWork.isValid(token, nonce, bits + 1))
    }

    @Test
    fun `sequential solving finds the smallest valid nonce`() {
        val solution = ProofOfWork.solveSequentially("token-1", 12)
        assertTrue(ProofOfWork.isValid("token-1", solution.nonce, 12))
        for (n in 0uL until solution.nonce) assertFalse(ProofOfWork.isValid("token-1", n, 12))
        assertEquals("token-1:${solution.nonce}", solution.headerValue)
    }

    @Test
    fun `parallel solving produces valid nonces`() = runBlocking {
        for (difficulty in listOf(0, 1, 8, 16)) {
            val solution = ProofOfWork.solve("token-$difficulty", difficulty, parallelism = 4)
            assertTrue(ProofOfWork.isValid(solution.token, solution.nonce, difficulty))
        }
    }

    @Test
    fun `nonces above 2^63 are handled as unsigned`() {
        val start = ULong.MAX_VALUE - 5000u
        val solution = ProofOfWork.solveSequentially("high", 4, start)
        assertTrue(solution.nonce >= start)
        assertTrue(solution.nonce.toString().toULong() == solution.nonce)
        assertTrue(ProofOfWork.isValid("high", solution.nonce, 4))
    }

    @Test
    fun `solving is cancellable`() = runBlocking {
        val started = TimeSource.Monotonic.markNow()
        val result = withTimeoutOrNull(200.milliseconds) { ProofOfWork.solve("never", 200, parallelism = 2) }
        assertNull(result)
        assertTrue(started.elapsedNow() < 5000.milliseconds, "workers did not stop promptly")
    }
}
