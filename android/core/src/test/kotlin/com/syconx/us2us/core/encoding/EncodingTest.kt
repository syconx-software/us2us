package com.syconx.us2us.core.encoding

import com.syconx.us2us.core.AccountId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class EncodingTest {

    @Test
    fun `base64url round-trips without padding`() {
        for (size in 0..40) {
            val bytes = ByteArray(size) { (it * 37 + 250).toByte() }
            val text = Base64Url.encode(bytes)
            assert('=' !in text && '+' !in text && '/' !in text) { text }
            assertArrayEquals(bytes, Base64Url.decode(text))
        }
        assertEquals("-_8", Base64Url.encode(byteArrayOf(0xfb.toByte(), 0xff.toByte())))
    }

    @Test
    fun `base64url rejects padding, the standard alphabet and wrong lengths`() {
        assertThrows<IllegalArgumentException> { Base64Url.decode("AA==") }
        assertThrows<IllegalArgumentException> { Base64Url.decode("+/8") }
        assertThrows<IllegalArgumentException> { Base64Url.decode("AA AA") }
        // Non-zero leftover bits would give one value two spellings.
        assertThrows<IllegalArgumentException> { Base64Url.decode("AB") }
        assertThrows<IllegalArgumentException> { Base64Url.decode("AAB") }
        assertThrows<IllegalArgumentException> { Base64Url.decode("A") }
        assertThrows<IllegalArgumentException> { Base64Url.decode(Base64Url.encode(ByteArray(15)), 16) }
        assertEquals(16, Base64Url.decode(Base64Url.encode(ByteArray(16)), 16).size)
    }

    @Test
    fun `hex encodes lowercase and decodes both cases`() {
        val bytes = byteArrayOf(0x00, 0x0f, 0x7f, 0x80.toByte(), 0xff.toByte())
        assertEquals("000f7f80ff", Hex.encode(bytes))
        assertArrayEquals(bytes, Hex.decode("000F7F80FF"))
        assertThrows<IllegalArgumentException> { Hex.decode("abc") }
        assertThrows<IllegalArgumentException> { Hex.decode("zz") }
    }

    @Test
    fun `account id converts between UUID string and 16 raw bytes`() {
        val id = AccountId.parse("0123abcd-4567-89ef-0123-456789abcdef")
        assertEquals("0123abcd456789ef0123456789abcdef", Hex.encode(id.toBytes()))
        assertEquals(id, AccountId.fromBytes(id.toBytes()))
        assertEquals("0123abcd-4567-89ef-0123-456789abcdef", id.toString())

        val random = AccountId(UUID.randomUUID())
        assertEquals(random, AccountId.fromBytes(random.toBytes()))
    }

    @Test
    fun `account id parsing accepts uppercase but always prints lowercase`() {
        val id = AccountId.parse("0123ABCD-4567-89EF-0123-456789ABCDEF")
        assertEquals("0123abcd-4567-89ef-0123-456789abcdef", id.toString())
        assertEquals("0123abcd-4567-89ef-0123-456789abcdef", id.protocolAddress().name)
        assertEquals(1, id.protocolAddress().deviceId)
    }

    @Test
    fun `account id rejects non-canonical forms`() {
        assertThrows<IllegalArgumentException> { AccountId.parse("1-1-1-1-1") }
        assertThrows<IllegalArgumentException> { AccountId.parse("0123abcd456789ef0123456789abcdef") }
        assertThrows<IllegalArgumentException> { AccountId.fromBytes(ByteArray(15)) }
    }
}
