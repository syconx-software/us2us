package com.syconx.us2us.core.session

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ContentPaddingTest {

    @Test
    fun `pads to the next multiple of 160 with 0x80 then zeros`() {
        for (length in listOf(0, 1, 158, 159, 160, 161, 319, 320, 1000)) {
            val plaintext = ByteArray(length) { 0x41 }
            val padded = ContentPadding.pad(plaintext)
            assertEquals((length / 160 + 1) * 160, padded.size, "length $length")
            assertEquals(0x80.toByte(), padded[length])
            assert(padded.drop(length + 1).all { it == 0.toByte() })
            assertArrayEquals(plaintext, ContentPadding.unpad(padded))
        }
    }

    @Test
    fun `plaintext ending in zeros or 0x80 survives`() {
        val tricky = byteArrayOf(1, 0x80.toByte(), 0, 0)
        assertArrayEquals(tricky, ContentPadding.unpad(ContentPadding.pad(tricky)))
    }

    @Test
    fun `malformed padding is rejected`() {
        assertThrows<InvalidPaddingException> { ContentPadding.unpad(ByteArray(160)) }
        assertThrows<InvalidPaddingException> { ContentPadding.unpad(ByteArray(0)) }
        assertThrows<InvalidPaddingException> { ContentPadding.unpad(byteArrayOf(1, 2, 3)) }
        assertThrows<InvalidPaddingException> { ContentPadding.unpad(byteArrayOf(1, 0x81.toByte(), 0)) }
    }
}
