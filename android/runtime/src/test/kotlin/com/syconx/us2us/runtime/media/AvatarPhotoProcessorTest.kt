package com.syconx.us2us.runtime.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.RectF
import com.syconx.us2us.core.profile.PhotoFormat
import com.syconx.us2us.core.profile.ProfilePicture
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Profile photos made on the phone (PROTOCOL.md §8.9). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AvatarPhotoProcessorTest {
    @Test
    fun aLargeCameraPhotoBecomesASquareWithoutMetadataWithinTheByteLimit() {
        val source = makeCameraJpeg(3000, 2000)
        assertTrue(AvatarPhotoProcessor.metadataProblems(source).isNotEmpty())
        val photo = AvatarPhotoProcessor.prepare(source)
        assertEquals(256, photo.width)
        assertEquals(256, photo.height)
        assertEquals(emptyList<String>(), AvatarPhotoProcessor.metadataProblems(photo.data))
        assertEquals(emptyList<String>(), MediaPreparation.identifyingMetadata(photo.data))
        assertFalse(photo.data.contains("PhoneMaker"))
        assertFalse(photo.data.contains("secret place"))
        assertTrue(photo.toProto().serializedSize <= ProfilePicture.MAX_SERIALIZED_BYTES)
        // What a receiver checks, and what the platform decoder says before drawing.
        assertEquals(photo, ProfilePicture.fromProto(photo.toProto()))
        assertTrue(photo.isSendable)
        assertTrue(AvatarPhotoProcessor.isDisplayable(photo))
        val image = AvatarPhotoProcessor.image(photo)
        assertNotNull(image)
        image!!
        assertEquals(256, image.width)
        assertEquals(256, image.height)
    }

    @Test
    fun jpegOutputKeepsOnlyWhatADecoderNeeds() {
        val source = makeCameraJpeg(640, 640)
        assertTrue("the source carries EXIF", 0xE1 in jpegMarkers(source))
        val scrubbed = AvatarPhotoProcessor.scrubJpeg(source)!!
        assertFalse(0xE1 in jpegMarkers(scrubbed))
        assertTrue(jpegMarkers(scrubbed).all { it == 0xE0 || it == 0xEE || it !in 0xE0..0xEF })
        assertEquals(emptyList<String>(), AvatarPhotoProcessor.metadataProblems(scrubbed))
        assertEquals(640, BitmapFactory.decodeByteArray(scrubbed, 0, scrubbed.size).width)

        val photo = AvatarPhotoProcessor.prepare(source, formats = listOf(PhotoFormat.JPEG))
        assertEquals(PhotoFormat.JPEG, photo.format)
        val markers = jpegMarkers(photo.data)
        assertFalse(0xE1 in markers)
        assertFalse(0xFE in markers)
        assertFalse("no ICC profile: the pixels are sRGB", 0xE2 in markers)
        assertTrue(photo.isSendable)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD9.toByte()), photo.data.copyOfRange(photo.data.size - 2, photo.data.size))
        assertNull(AvatarPhotoProcessor.scrubJpeg("not a jpeg".toByteArray()))
        assertNull(AvatarPhotoProcessor.scrubJpeg(source.copyOfRange(0, 30)))
        assertNull("no EOI", AvatarPhotoProcessor.scrubJpeg(source.copyOfRange(0, source.size - 2)))

        // Between scans and after the end: dropped (a camera's trailer, a comment).
        val comment = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x06) + "note".toByteArray()
        val tampered = source.copyOfRange(0, source.size - 2) + comment + byteArrayOf(0xFF.toByte(), 0xD9.toByte()) + "trailer".toByteArray()
        val cleaned = AvatarPhotoProcessor.scrubJpeg(tampered)!!
        assertFalse(cleaned.contains("note"))
        assertFalse(cleaned.contains("trailer"))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD9.toByte()), cleaned.copyOfRange(cleaned.size - 2, cleaned.size))
        assertEquals(640, BitmapFactory.decodeByteArray(cleaned, 0, cleaned.size).width)
    }

    @Test
    fun theOrientationIsAppliedAndSmallCropsAreNeverEnlarged() {
        // 300 × 100 pixels shown turned by 90° (orientation 6): 100 × 300 on screen.
        val turned = makeCameraJpeg(300, 100, orientation = 6)
        val centred = AvatarPhotoProcessor.prepare(turned, formats = listOf(PhotoFormat.JPEG))
        assertEquals(100, centred.width)
        assertEquals(100, centred.height)
        assertEquals(emptyList<String>(), AvatarPhotoProcessor.metadataProblems(centred.data))

        val source = makeCameraJpeg(400, 400)
        val cropped = AvatarPhotoProcessor.prepare(source, crop = RectF(10f, 20f, 130f, 220f), formats = listOf(PhotoFormat.JPEG))
        assertEquals(120, cropped.width)
        assertEquals(120, cropped.height)
        val outside = assertThrows(AvatarPhotoProcessingException::class.java) {
            AvatarPhotoProcessor.prepare(source, crop = RectF(500f, 500f, 550f, 550f))
        }
        assertEquals(AvatarPhotoProcessingError.EMPTY_CROP, outside.error)
        val unreadable = assertThrows(AvatarPhotoProcessingException::class.java) { AvatarPhotoProcessor.prepare(byteArrayOf(1, 2, 3)) }
        assertEquals(AvatarPhotoProcessingError.UNREADABLE, unreadable.error)
        assertEquals(Rect(100, 0, 200, 100), AvatarPhotoProcessor.squareCrop(null, 300, 100))
    }

    @Test
    fun theCropFollowsWhatIsShown() {
        // Left half red, right half blue: a crop of the right half is blue.
        val source = testBitmap(400, 200).encoded(Bitmap.CompressFormat.JPEG, 95)
        val photo = AvatarPhotoProcessor.prepare(source, crop = RectF(200f, 0f, 400f, 200f), formats = listOf(PhotoFormat.JPEG))
        val pixel = AvatarPhotoProcessor.image(photo)!!.getPixel(100, 100)
        assertTrue("blue", android.graphics.Color.blue(pixel) > 180 && android.graphics.Color.red(pixel) < 80)
    }

    @Test
    fun aPhotoThatDoesNotCompressIsMadeSmallerUntilItFits() {
        val noise = makeCameraJpeg(1024, 1024, noise = true)
        for (formats in listOf(listOf(PhotoFormat.JPEG), listOf(PhotoFormat.HEIC, PhotoFormat.JPEG))) {
            val photo = AvatarPhotoProcessor.prepare(noise, formats = formats)
            assertEquals(photo.width, photo.height)
            assertTrue(photo.width <= 256)
            assertTrue(photo.toProto().serializedSize <= ProfilePicture.MAX_SERIALIZED_BYTES)
            assertEquals(emptyList<String>(), AvatarPhotoProcessor.metadataProblems(photo.data))
        }
    }

    @Test
    fun jpegWhereThePhoneHasNoHeicEncoder() {
        val photo = AvatarPhotoProcessor.prepare(makeCameraJpeg(800, 600))
        assertFalse(AvatarPhotoProcessor.canEncode(PhotoFormat.HEIC))
        assertEquals(PhotoFormat.JPEG, photo.format)
        assertTrue(AvatarPhotoProcessor.isDisplayable(photo))
        // A received photo larger than §8.9 allows is not drawn.
        val large = ProfilePicture.fromProto(
            com.syconx.us2us.core.proto.ProfileAvatar.newBuilder().setPhoto(
                com.syconx.us2us.core.proto.AvatarPhoto.newBuilder()
                    .setContentType("image/jpeg")
                    .setData(com.google.protobuf.ByteString.copyFrom(AvatarPhotoProcessor.scrubJpeg(testBitmap(300, 300).encoded(Bitmap.CompressFormat.JPEG))!!)),
            ).build(),
        )
        assertNull("the core refuses it already", large)
    }
}
