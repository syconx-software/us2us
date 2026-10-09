package com.syconx.us2us.core.username

import com.syconx.us2us.core.encoding.Hex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class UsernameTest {

    @Test
    fun `normalization trims, applies NFKC and lowercases`() {
        assertEquals("alice", Username.parse("alice")?.value)
        assertEquals("alice_01", Username.parse("ALICE_01")?.value)
        assertEquals("bob_smith", Username.parse("  bob_smith \t")?.value)
        assertEquals("carol12", Username.parse("ｃａｒｏｌ１２")?.value) // fullwidth
        assertEquals("file_x", Username.parse("ﬁle_x")?.value) // U+FB01 ligature
        assertEquals("kelvin", Username.parse("Kelvin")?.value) // Kelvin sign
    }

    @Test
    fun `trimming removes exactly the Unicode White_Space characters`() {
        assertEquals("alice", Username.parse("\u0085alice\u3000")?.value) // NEL, ideographic space
        assertEquals("alice", Username.parse("\u00a0\u2007alice\u202f")?.value) // no-break spaces
        assertNull(Username.parse("\u001calice"), "U+001C is not White_Space, so it stays and is invalid")
    }

    @Test
    fun `length bounds are 3 and 24`() {
        assertEquals("abc", Username.parse("abc")?.value)
        assertNull(Username.parse("ab"))
        val max = "a" + "1".repeat(23)
        assertEquals(max, Username.parse(max)?.value)
        assertNull(Username.parse(max + "1"))
    }

    @Test
    fun `invalid characters and leading non-letters are rejected`() {
        listOf("1alice", "_alice", "ali-ce", "ali.ce", "şule", "ıvan", "İstanbul", "ali ce", "", "   ").forEach {
            assertNull(Username.parse(it), it)
        }
    }

    @Test
    fun `the value class only holds normalized names`() {
        assertThrows<IllegalArgumentException> { Username("Alice") }
        assertThrows<IllegalArgumentException> { Username(" alice") }
    }

    @Test
    fun `hash is PBKDF2-HMAC-SHA256 with the BRP salt and 100k iterations`() {
        val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("alice_01".toCharArray(), "BRP/username/v1".toByteArray(), 100_000, 256))
            .encoded
        assertEquals(Hex.encode(expected), Hex.encode(Username("alice_01").hash()))
        assertEquals(32, Username("bob").hash().size)
    }
}
