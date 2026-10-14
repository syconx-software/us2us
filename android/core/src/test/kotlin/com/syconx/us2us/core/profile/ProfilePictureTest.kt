package com.syconx.us2us.core.profile

import com.google.protobuf.kotlin.toByteString
import com.syconx.us2us.core.proto.ProfileAvatar
import com.syconx.us2us.core.proto.avatarPhoto
import com.syconx.us2us.core.proto.profileAvatar
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** PROTOCOL.md §8.9: what a sender may send, and what a receiver shows. */
class ProfilePictureTest {
    private fun refused(contentType: String, data: ByteArray): PhotoProblem =
        assertThrows<InvalidProfilePhotoException> { ProfilePicture.photo(contentType, data) }.problem

    private fun received(contentType: String, data: ByteArray): ProfilePicture? =
        ProfilePicture.fromProto(profileAvatar { photo = avatarPhoto { this.contentType = contentType; this.data = data.toByteString() } })

    @Test
    fun `a square JPEG of at most 256 pixels without metadata is sent and received unchanged`() {
        val jpeg = TestImages.jpeg(256, 256)
        val photo = ProfilePicture.photo("image/jpeg", jpeg)
        assertEquals(PhotoFormat.JPEG, photo.format)
        assertEquals(256 to 256, photo.width to photo.height)
        assertTrue(photo.isSendable && photo.isPhoto)
        assertArrayEquals(jpeg, photo.data)

        val proto = photo.toProto()
        assertEquals(ProfileAvatar.KindCase.PHOTO, proto.kindCase)
        assertEquals("image/jpeg", proto.photo.contentType)
        assertEquals(photo, ProfilePicture.parse(proto.toByteArray()))
        assertEquals(photo, ProfilePicture.photo("image/jpeg", TestImages.jpeg(256, 256)), "equal by content")
        assertEquals(1 to 1, ProfilePicture.photo("image/jpeg", TestImages.jpeg(1, 1)).let { it.width to it.height })
    }

    @Test
    fun `a sender's photo must follow every rule`() {
        val jpeg = TestImages.jpeg(64, 64)
        assertEquals(PhotoProblem.UNSUPPORTED_TYPE, refused("image/png", jpeg))
        assertEquals(PhotoProblem.UNSUPPORTED_TYPE, refused("IMAGE/JPEG", jpeg))
        assertEquals(PhotoProblem.NOT_AN_IMAGE, refused("image/heic", jpeg), "the signature must match the type")
        assertEquals(PhotoProblem.NOT_AN_IMAGE, refused("image/jpeg", TestImages.heic()))
        assertEquals(PhotoProblem.NOT_AN_IMAGE, refused("image/jpeg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())))
        assertEquals(PhotoProblem.NOT_AN_IMAGE, refused("image/jpeg", jpeg.copyOf(40)), "truncated before the frame header")
        assertEquals(PhotoProblem.TOO_LARGE_DIMENSIONS, refused("image/jpeg", TestImages.jpeg(257, 257)))
        assertEquals(PhotoProblem.NOT_SQUARE, refused("image/jpeg", TestImages.jpeg(200, 100)))
        assertEquals(PhotoProblem.METADATA, refused("image/jpeg", TestImages.withSegment(jpeg, 0xE1, TestImages.EXIF)))
        assertEquals(PhotoProblem.METADATA, refused("image/jpeg", TestImages.withSegment(jpeg, 0xE1, "http://ns.adobe.com/xap/1.0/\u0000".toByteArray())))
        assertEquals(PhotoProblem.METADATA, refused("image/jpeg", TestImages.withSegment(jpeg, 0xED, "Photoshop 3.0\u0000".toByteArray())))
        assertEquals(PhotoProblem.METADATA, refused("image/jpeg", TestImages.withSegment(jpeg, 0xFE, "a comment".toByteArray())))
        // A JFIF header that carries a thumbnail (1 × 1 pixels here).
        val jfifThumbnail = "JFIF\u0000".toByteArray() + byteArrayOf(1, 2, 0, 0, 1, 0, 1, 1, 1) + ByteArray(3)
        assertEquals(PhotoProblem.METADATA, refused("image/jpeg", TestImages.withSegment(jpeg.copyOfRange(0, 2) + jpeg.copyOfRange(20, jpeg.size), 0xE0, jfifThumbnail)))
        // An ICC colour profile may stay.
        val icc = TestImages.withSegment(jpeg, 0xE2, "ICC_PROFILE\u0000".toByteArray() + byteArrayOf(1, 1) + ByteArray(32))
        assertTrue(ProfilePicture.photo("image/jpeg", icc).isSendable)
        // A photo too detailed for the 48 KiB record: the app lowers quality or size until it fits.
        val bulky = TestImages.padded(jpeg, jpeg.size + ProfilePicture.MAX_SERIALIZED_BYTES)
        assertEquals(PhotoProblem.TOO_MANY_BYTES, refused("image/jpeg", bulky))
    }

    @Test
    fun `a sender's JPEG is checked to its end`() {
        val jpeg = TestImages.jpeg(64, 64)
        assertEquals(0xD9, jpeg.last().toInt() and 0xff)
        assertTrue(ProfilePicture.photo("image/jpeg", TestImages.padded(jpeg, jpeg.size + 100)).isSendable, "a longer scan")
        // Between scans, only tables, restart intervals and the next scan.
        val dqt = byteArrayOf(0x00, 0x00) + ByteArray(64) { 1 }
        assertTrue(ProfilePicture.photo("image/jpeg", TestImages.withSegmentAfterScan(jpeg, 0xDB, dqt)).isSendable)
        assertEquals(PhotoProblem.METADATA, refused("image/jpeg", TestImages.withSegmentAfterScan(jpeg, 0xE1, TestImages.EXIF)))
        assertEquals(PhotoProblem.METADATA, refused("image/jpeg", TestImages.withSegmentAfterScan(jpeg, 0xFE, "a comment".toByteArray())))
        assertEquals(PhotoProblem.METADATA, refused("image/jpeg", TestImages.withSegmentAfterScan(jpeg, 0xE2, "ICC_PROFILE\u0000".toByteArray())))
        // Nothing after EOI (camera trailers, appended files), and an EOI there must be.
        assertEquals(PhotoProblem.METADATA, refused("image/jpeg", jpeg + "trailer".toByteArray()))
        assertEquals(PhotoProblem.METADATA, refused("image/jpeg", jpeg + jpeg))
        assertEquals(PhotoProblem.METADATA, refused("image/jpeg", jpeg.copyOf(jpeg.size - 2)))
        // Stuffed bytes and restart markers in the scan are data, not segments.
        val stuffed = jpeg.copyOf(jpeg.size - 2) + byteArrayOf(0xFF.toByte(), 0x00, 0x12, 0xFF.toByte(), 0xD3.toByte(), 0x34) + jpeg.copyOfRange(jpeg.size - 2, jpeg.size)
        assertTrue(ProfilePicture.photo("image/jpeg", stuffed).isSendable)

        // A receiver needs the size alone: it still shows such files, which this client never sends.
        val trailer = received("image/jpeg", jpeg + "trailer".toByteArray())
        assertEquals(64 to 64, (trailer as ProfilePicture.Photo).let { it.width to it.height })
        assertFalse(trailer.isSendable)
        assertNotNull(received("image/jpeg", jpeg.copyOf(jpeg.size - 2)))
    }

    @Test
    fun `HEIC photos are read from their HEIF boxes`() {
        val photo = ProfilePicture.photo("image/heic", TestImages.heic(sizes = listOf(200 to 200)))
        assertEquals(PhotoFormat.HEIC, photo.format)
        assertEquals(200 to 200, photo.width to photo.height)
        assertTrue(ProfilePicture.photo("image/heic", TestImages.heic(brands = listOf("mif1", "heic"))).isSendable, "heic as a compatible brand")

        assertEquals(PhotoProblem.NOT_AN_IMAGE, refused("image/heic", TestImages.heic(brands = listOf("avif", "mif1"))), "not HEVC")
        assertEquals(PhotoProblem.NOT_AN_IMAGE, refused("image/heic", TestImages.heic(sizes = emptyList())), "no size in the header")
        assertEquals(PhotoProblem.NOT_AN_IMAGE, refused("image/heic", TestImages.heic().let { it.copyOf(it.size - 10) }), "truncated box")
        assertEquals(PhotoProblem.NOT_AN_IMAGE, refused("image/heic", TestImages.heic(sizes = listOf(0 to 0))))
        assertEquals(PhotoProblem.TOO_LARGE_DIMENSIONS, refused("image/heic", TestImages.heic(sizes = listOf(256 to 256, 512 to 512))))
        assertEquals(PhotoProblem.NOT_SQUARE, refused("image/heic", TestImages.heic(sizes = listOf(256 to 128))))
        assertEquals(PhotoProblem.METADATA, refused("image/heic", TestImages.heic(items = listOf("hvc1", "Exif"))))
        assertEquals(PhotoProblem.METADATA, refused("image/heic", TestImages.heic(items = listOf("hvc1", "mime"))))
        assertEquals(PhotoProblem.METADATA, refused("image/heic", TestImages.heic(items = listOf("hvc1", "hvc1"), reference = true)), "a thumbnail")
        assertEquals(PhotoProblem.METADATA, refused("image/heic", TestImages.heic(items = listOf("grid"))))
    }

    @Test
    fun `a receiver checks type, signature and size, but not what only the sender must do`() {
        val jpeg = TestImages.jpeg(64, 64)
        // Not square, or with metadata: shown (cropped to the circle), though never sent by this client.
        val wide = received("image/jpeg", TestImages.jpeg(200, 100)) as ProfilePicture.Photo
        assertEquals(200 to 100, wide.width to wide.height)
        assertFalse(wide.isSendable)
        val exif = received("image/jpeg", TestImages.withSegment(jpeg, 0xE1, TestImages.EXIF))
        assertNotNull(exif)
        assertFalse(exif!!.isSendable)
        assertNotNull(received("image/heic", TestImages.heic(items = listOf("hvc1", "Exif"))))

        assertNull(received("image/png", jpeg))
        assertNull(received("image/heic", jpeg))
        assertNull(received("image/jpeg", TestImages.heic()))
        assertNull(received("image/jpeg", ByteArray(0)))
        assertNull(received("image/jpeg", TestImages.jpeg(257, 10)))
        assertNull(received("image/heic", TestImages.heic(sizes = listOf(300 to 300))))
        assertNull(received("image/jpeg", TestImages.padded(jpeg, jpeg.size + ProfilePicture.MAX_SERIALIZED_BYTES)), "over 49110 bytes serialized")
        assertNull(ProfilePicture.fromProto(ProfileAvatar.getDefaultInstance()), "no kind")
        assertNull(ProfilePicture.parse(byteArrayOf(0x12, 0x05, 0x01)), "does not parse")
    }

    @Test
    fun `presets are sent only from the catalog, and kept when received`() {
        // Catalog version 1 (§8.9): ids 1–22, nothing else may be sent.
        assertEquals((1..22).toSet(), AvatarPresets.ids)
        val known = ProfilePicture.preset(1)
        assertEquals(1, known.id)
        assertTrue(known.isKnown)
        assertTrue(known.isSendable)
        assertEquals(22, ProfilePicture.preset(22).id)
        assertThrows<IllegalArgumentException> { ProfilePicture.preset(23) }
        assertThrows<IllegalArgumentException> { ProfilePicture.preset(0) }

        val unknown = ProfilePicture.fromProto(profileAvatar { presetId = 23 }) as ProfilePicture.Preset
        assertEquals(23, unknown.id)
        assertFalse(unknown.isKnown, "an unknown id is shown as the initial letter")
        assertFalse(unknown.isSendable)
        assertFalse(unknown.isPhoto)
        assertEquals(unknown, ProfilePicture.parse(unknown.toProto().toByteArray()))
        assertEquals("Preset(4294967295)", (ProfilePicture.fromProto(profileAvatar { presetId = -1 }) as ProfilePicture.Preset).toString())
        assertNull(ProfilePicture.fromProto(profileAvatar { presetId = 0 }), "0 is not an id")
    }
}
