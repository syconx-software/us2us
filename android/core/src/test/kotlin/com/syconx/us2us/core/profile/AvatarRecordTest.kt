package com.syconx.us2us.core.profile

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.crypto.AesGcm
import com.syconx.us2us.core.proto.profileAvatar
import com.syconx.us2us.core.username.Username
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/** PROTOCOL.md §8.10 (key and record) and §4.12 (header and lengths). */
class AvatarRecordTest {
    private val alice = AccountId(UUID.fromString("0123abcd-4567-89ef-0123-456789abcdef"))
    private val bob = AccountId(UUID.fromString("fedcba98-7654-4321-8fed-cba987654321"))

    // PBKDF2 at 100 000 iterations: derive each key once.
    private val aliceSeed = AvatarKey.seed(Username("alice_01"))
    private val aliceKey = AvatarKey.derive(aliceSeed, alice)

    private val preset = profileAvatar { presetId = 1 }.toByteArray()

    @Test
    fun `the smallest of the four lengths that fits is used`() {
        val expected = mapOf(
            0 to 8192, 1 to 8192, 8150 to 8192, 8151 to 16384, 16342 to 16384, 16343 to 32768,
            32726 to 32768, 32727 to 49152, 49110 to 49152, 49111 to null,
        )
        for ((size, length) in expected) assertEquals(length, AvatarRecord.lengthFor(size), "ProfileAvatar of $size bytes")
        assertEquals(49110, AvatarRecord.MAX_PROFILE_AVATAR_BYTES)
        assertEquals(41, AvatarRecord.OVERHEAD)
    }

    @Test
    fun `a sealed record opens with the username and account it was made for`() {
        val record = AvatarRecord.seal(preset, 7, aliceKey, ByteArray(12) { 3 })
        assertEquals(8192, record.size)
        assertArrayEquals("BRPA".toByteArray() + byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 7), record.copyOf(13))
        assertArrayEquals(ByteArray(12) { 3 }, record.copyOfRange(13, 25))
        assertEquals(7L, AvatarRecord.version(record))
        assertArrayEquals(preset, AvatarRecord.open(record, aliceKey))
        assertEquals(1, AvatarRecord.openAvatar(record, aliceKey).presetId)

        // Another username, or the same username of another account, derives another key.
        assertThrows<InvalidAvatarRecordException> { AvatarRecord.open(record, AvatarKey.derive(Username("alice_02"), alice)) }
        assertThrows<InvalidAvatarRecordException> { AvatarRecord.open(record, AvatarKey.derive(aliceSeed, bob)) }

        // Random nonces: the same picture never gives the same record twice.
        val again = AvatarRecord.seal(profileAvatar { presetId = 1 }, 7, aliceKey)
        assertFalse(again.contentEquals(AvatarRecord.seal(profileAvatar { presetId = 1 }, 7, aliceKey)))
        assertArrayEquals(preset, AvatarRecord.open(again, aliceKey))
    }

    @Test
    fun `the relay's username hash does not give the avatar key`() {
        val username = Username("alice_01")
        assertFalse(username.hash().contentEquals(aliceSeed), "different PBKDF2 salts")
        assertEquals(32, aliceSeed.size)
        assertFalse(AvatarKey.derive(aliceSeed, alice).bytes().contentEquals(AvatarKey.derive(aliceSeed, bob).bytes()))
    }

    @Test
    fun `the header is checked before anything is derived or decrypted`() {
        val record = AvatarRecord.seal(preset, 1, aliceKey)
        fun changed(index: Int, value: Int) = record.copyOf().also { it[index] = value.toByte() }
        val broken = listOf(
            record.copyOf(8191), record.copyOf(8193), ByteArray(0),
            changed(0, 'b'.code), changed(4, 2), changed(5, 0x80), // another magic, format, version >= 2^63
            record.copyOf().also { it.fill(0, 5, 13) }, // version 0
        )
        for (bad in broken) {
            assertNull(AvatarRecord.version(bad))
            assertThrows<InvalidAvatarRecordException> { AvatarRecord.open(bad, aliceKey) }
        }
        // The header is authenticated: another version in it does not open.
        assertThrows<InvalidAvatarRecordException> { AvatarRecord.open(changed(12, 2), aliceKey) }
        assertThrows<InvalidAvatarRecordException> { AvatarRecord.open(changed(4000, record[4000].toInt() xor 1), aliceKey) }
    }

    @Test
    fun `versions span 1 to 2^63 - 1`() {
        val max = AvatarRecord.seal(preset, Long.MAX_VALUE, aliceKey)
        assertEquals(Long.MAX_VALUE, AvatarRecord.version(max))
        assertArrayEquals(preset, AvatarRecord.open(max, aliceKey))
        assertEquals(1L shl 32, AvatarRecord.version(AvatarRecord.seal(preset, 1L shl 32, aliceKey)))
        assertThrows<IllegalArgumentException> { AvatarRecord.seal(preset, 0, aliceKey) }
        assertThrows<IllegalArgumentException> { AvatarRecord.seal(preset, -1, aliceKey) }
        assertThrows<IllegalArgumentException> { AvatarRecord.seal(preset, 1, aliceKey, ByteArray(11)) }
    }

    @Test
    fun `every length holds what it should and nothing more`() {
        fun photoOfSize(size: Int) = TestImages.photoAvatarOfSize(size).toByteArray()
        for ((size, length) in listOf(8150 to 8192, 8151 to 16384, 32727 to 49152, 49110 to 49152)) {
            val avatar = photoOfSize(size)
            val record = AvatarRecord.seal(avatar, 2, aliceKey)
            assertEquals(length, record.size, "ProfileAvatar of $size bytes")
            assertArrayEquals(avatar, AvatarRecord.open(record, aliceKey))
        }
        assertThrows<IllegalArgumentException> { AvatarRecord.seal(photoOfSize(49111), 2, aliceKey) }
    }

    @Test
    fun `plaintext without the padding marker is refused`() {
        val header = AvatarRecord.seal(preset, 3, aliceKey).copyOf(13)
        val nonce = ByteArray(12) { 9 }
        fun record(plaintext: ByteArray) = header + nonce + AesGcm.seal(aliceKey.bytes(), nonce, header + alice.toBytes(), plaintext)
        val size = 8192 - AvatarRecord.OVERHEAD
        assertThrows<InvalidAvatarRecordException> { AvatarRecord.open(record(ByteArray(size)), aliceKey) }
        assertThrows<InvalidAvatarRecordException> { AvatarRecord.open(record(ByteArray(size) { 1 }), aliceKey) }
        // A ProfileAvatar that does not parse is refused by openAvatar, not by open.
        val garbage = byteArrayOf(0x12, 0x7f, 0x01, 0x80.toByte()) + ByteArray(size - 4)
        assertArrayEquals(byteArrayOf(0x12, 0x7f, 0x01), AvatarRecord.open(record(garbage), aliceKey))
        assertThrows<InvalidAvatarRecordException> { AvatarRecord.openAvatar(record(garbage), aliceKey) }
    }
}
