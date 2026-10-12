package com.syconx.us2us.runtime.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.runtime.vault.AppDirectories
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaPreparationTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var directories: AppDirectories

    @Before
    fun setUp() {
        directories = AppDirectories(folder.newFolder("us2us"))
        directories.prepare()
    }

    private fun file(name: String, bytes: ByteArray): File = File(folder.root, name).also { it.writeBytes(bytes) }

    @Test
    fun imagesAreResizedAndStripped() {
        val original = makeCameraJpeg(3000, 1500)
        assertTrue("control: the source carries metadata", "{Exif}" in MediaPreparation.identifyingMetadata(original))
        assertTrue(original.contains("PhoneMaker"))

        val prepared = MediaPreparation.prepareImage(file("source.jpg", original), directories, caption = "hi")
        val output = prepared.file.readBytes()
        assertEquals(emptyList<String>(), MediaPreparation.identifyingMetadata(output))
        assertFalse(output.contains("secret place"))
        assertFalse(output.contains("PhoneMaker"))
        assertFalse(0xE1 in jpegMarkers(output))
        assertEquals(2048, prepared.width)
        assertEquals(1024, prepared.height)
        assertEquals("image/jpeg", prepared.contentType)
        assertEquals("hi", prepared.caption)
        assertEquals(Attachment.Kind.IMAGE, prepared.kind)
        assertEquals(directories.staging, prepared.file.parentFile)
        assertNotNull(prepared.thumbnail)
        val thumbnail = prepared.thumbnail!!.toByteArray()
        assertTrue(thumbnail.size <= MediaPreparation.MAX_THUMBNAIL_BYTES)
        assertEquals(emptyList<String>(), MediaPreparation.identifyingMetadata(thumbnail))
        assertEquals(output.size.toLong(), prepared.pendingAttachment.size)
        assertTrue(prepared.pendingAttachment.mediaId.isEmpty)
        assertEquals("hi", prepared.pendingAttachment.caption)
        val decoded = BitmapFactory.decodeByteArray(output, 0, output.size)
        assertEquals(2048, decoded.width)
    }

    @Test
    fun theOrientationIsAppliedToThePixels() {
        // 300 × 100 pixels shown turned by 90° (orientation 6): 100 × 300 on screen.
        val prepared = MediaPreparation.prepareImage(file("turned.jpg", makeCameraJpeg(300, 100, orientation = 6)), directories)
        assertEquals(100, prepared.width)
        assertEquals(300, prepared.height)
        val output = prepared.file.readBytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(output, 0, output.size, bounds)
        assertEquals("no orientation tag is needed", 100, bounds.outWidth)
    }

    @Test
    fun smallImagesAreNotEnlarged() {
        val prepared = MediaPreparation.prepareImage(file("small.png", testBitmap(64, 48).encoded(Bitmap.CompressFormat.PNG)), directories)
        assertEquals(64, prepared.width)
        assertEquals(48, prepared.height)
        assertEquals("image/jpeg", prepared.contentType)
    }

    @Test
    fun anUnreadableImageIsRefused() {
        val error = assertThrows(MediaPreparationException::class.java) {
            MediaPreparation.prepareImage(file("broken.jpg", byteArrayOf(1, 2, 3)), directories)
        }
        assertEquals(MediaPreparationError.UNREADABLE, error.error)
    }

    @Test
    fun aPhotoPickedAsADocumentLosesItsMetadataAtFullSize() {
        val original = makeCameraJpeg(1200, 900)
        val prepared = MediaPreparation.prepareFile(file("IMG_0001.JPG", original), directories, "IMG_0001.JPG", "image/jpeg", caption = "doc")
        val sent = prepared.file.readBytes()
        assertEquals(emptyList<String>(), MediaPreparation.identifyingMetadata(sent))
        assertFalse(sent.contains("PhoneMaker"))
        assertEquals(Attachment.Kind.FILE, prepared.kind)
        assertEquals("IMG_0001.jpeg", prepared.fileName)
        assertEquals("image/jpeg", prepared.contentType)
        assertEquals(1200, prepared.width)
        assertEquals(900, prepared.height)
        assertEquals("doc", prepared.caption)
        assertNull("documents carry no thumbnail", prepared.thumbnail)
    }

    @Test
    fun aPngKeepsItsFormatAndLosesItsText() {
        val png = testBitmap(40, 30, alpha = true).encoded(Bitmap.CompressFormat.PNG)
        val tagged = insertPngChunks(
            png,
            pngChunk("tEXt", "Author\u0000Selin".toByteArray(Charsets.ISO_8859_1)),
            pngChunk("tIME", byteArrayOf(0x07, 0xEA.toByte(), 10, 11, 10, 0, 0)),
        )
        assertTrue("{PNG}.tEXt" in MediaPreparation.identifyingMetadata(tagged))
        val prepared = MediaPreparation.prepareFile(file("screen.png", tagged), directories, "screen.png", "image/png")
        val sent = prepared.file.readBytes()
        assertEquals("image/png", prepared.contentType)
        assertEquals("screen.png", prepared.fileName)
        assertEquals(emptyList<String>(), MediaPreparation.identifyingMetadata(sent))
        assertFalse(sent.contains("Selin"))
        assertEquals(40, prepared.width)
    }

    @Test
    fun anAnimatedPngIsCleanedWithoutDecoding() {
        val png = testBitmap(24, 24).encoded(Bitmap.CompressFormat.PNG)
        // acTL: one frame, played once; the default image is the first frame.
        val animated = insertPngChunks(
            png,
            pngChunk("acTL", be32(1) + be32(1)),
            pngChunk("iTXt", "XML:com.adobe.xmp\u0000\u0000\u0000\u0000\u0000PhoneMaker".toByteArray(Charsets.ISO_8859_1)),
        )
        assertTrue(ImageScrubber.isAnimatedPng(animated))
        val prepared = MediaPreparation.prepareFile(file("loop.png", animated), directories, "loop.png", "image/png")
        val sent = prepared.file.readBytes()
        assertEquals(emptyList<String>(), MediaPreparation.identifyingMetadata(sent))
        assertFalse(sent.contains("PhoneMaker"))
        assertTrue("the animation control stays", sent.contains("acTL"))
        assertEquals(24, prepared.width)
    }

    @Test
    fun anAnimatedGifKeepsItsFramesAndLosesItsMetadata() {
        val gif = animatedGifWithMetadata()
        assertEquals(listOf("{GIF}.Comment", "{Trailer}", "{XMP}"), MediaPreparation.identifyingMetadata(gif))
        val prepared = MediaPreparation.prepareFile(file("party.gif", gif), directories, "party.gif", "image/gif")
        val sent = prepared.file.readBytes()
        assertEquals("image/gif", prepared.contentType)
        assertEquals("party.gif", prepared.fileName)
        assertEquals(emptyList<String>(), MediaPreparation.identifyingMetadata(sent))
        assertFalse(sent.contains("secret place"))
        assertFalse(sent.contains("PhoneMaker"))
        assertTrue("the loop count stays", sent.contains("NETSCAPE2.0"))
        assertEquals(2, GifBlocks.read(sent)!!.frames)
        assertEquals(4, prepared.width)
    }

    @Test
    fun aWebpLosesItsExifChunk() {
        val webp = testBitmap(16, 16).encoded(Bitmap.CompressFormat.WEBP_LOSSLESS, 100)
        val chunks = WebpChunks.read(webp)!!
        val exif = "Exif\u0000\u0000PhoneMaker".toByteArray(Charsets.ISO_8859_1)
        val exifChunk = "EXIF".toByteArray(Charsets.ISO_8859_1) + le32(exif.size) + exif + (if (exif.size % 2 == 1) byteArrayOf(0) else ByteArray(0))
        val body = webp.copyOfRange(12, webp.size) + exifChunk
        val tagged = "RIFF".toByteArray(Charsets.ISO_8859_1) + le32(body.size + 4) + "WEBP".toByteArray(Charsets.ISO_8859_1) + body
        if (chunks.list.first().type == "VP8X") tagged[20] = (tagged[20].toInt() or 0x08).toByte()
        assertTrue("{Exif}" in MediaPreparation.identifyingMetadata(tagged))
        val prepared = MediaPreparation.prepareFile(file("sticker.webp", tagged), directories, "sticker.webp", "image/webp")
        val sent = prepared.file.readBytes()
        assertEquals("image/webp", prepared.contentType)
        assertEquals(emptyList<String>(), MediaPreparation.identifyingMetadata(sent))
        assertFalse(sent.contains("PhoneMaker"))
        assertTrue(BitmapFactory.decodeByteArray(sent, 0, sent.size).width == 16)
    }

    @Test
    fun aVideoPickedAsADocumentNeedsTheVideoPath() {
        val error = assertThrows(MediaPreparationException::class.java) {
            MediaPreparation.prepareFile(file("clip.mov", byteArrayOf(0)), directories, "clip.mov", "application/octet-stream")
        }
        assertEquals(MediaPreparationError.REQUIRES_VIDEO_PREPARATION, error.error)
        assertTrue(MediaPreparation.isMovie("video/mp4", "x"))
        assertTrue(MediaPreparation.isMovie("", "a.M4V"))
        assertFalse(MediaPreparation.isMovie("audio/mp4", "voice.m4a"))
    }

    @Test
    fun otherDocumentsAreSentAsTheyAre() {
        val pdf = "%PDF-1.7\n%Author Selin\n".toByteArray()
        val longName = "ş".repeat(300) + ".pdf"
        val prepared = MediaPreparation.prepareFile(file("lease.pdf", pdf), directories, longName, "application/pdf")
        assertArrayEquals(pdf, prepared.file.readBytes())
        assertEquals(255, prepared.fileName!!.length)
        assertEquals("application/pdf", prepared.contentType)
        assertEquals(pdf.size.toLong(), prepared.size)
        assertEquals(Attachment.Kind.FILE, prepared.kind)
        // An "image" the platform cannot read carries nothing it could strip either.
        val fake = "not really a jpeg".toByteArray()
        val copied = MediaPreparation.prepareFile(file("fake.jpg", fake), directories, "fake.jpg", "image/jpeg")
        assertArrayEquals(fake, copied.file.readBytes())
        assertEquals("fake.jpg", copied.fileName)
    }

    @Test
    fun voiceNotesAreStaged() {
        val audio = ByteArray(2048) { it.toByte() }
        val waveform = ByteArray(150) { (it % 256).toByte() }
        val fromMemory = MediaPreparation.prepareVoice(audio, directories, durationMs = 4200, waveform = waveform)
        assertArrayEquals(audio, fromMemory.file.readBytes())
        assertEquals("audio/mp4", fromMemory.contentType)
        assertEquals(Attachment.Kind.VOICE, fromMemory.kind)
        assertEquals(4200, fromMemory.durationMs)
        assertEquals(MediaPreparation.MAX_WAVEFORM_SAMPLES, fromMemory.waveform!!.size())
        assertTrue(fromMemory.file.name.endsWith(".m4a"))

        val recording = file("recording.m4a", audio)
        val moved = MediaPreparation.prepareVoice(recording, directories, durationMs = 1000, waveform = ByteArray(10))
        assertFalse("moved, not copied", recording.exists())
        assertArrayEquals(audio, moved.file.readBytes())
        assertEquals(10, moved.waveform!!.size())
        assertEquals(audio.size.toLong(), moved.size)
    }

    @Test
    fun jpegMetadataIsFoundWhereverItHides() {
        val clean = testBitmap(32, 32).encoded(Bitmap.CompressFormat.JPEG)
        assertEquals(emptyList<String>(), MediaPreparation.identifyingMetadata(clean))
        val comment = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x06) + "note".toByteArray()
        val betweenScans = clean.copyOfRange(0, clean.size - 2) + comment + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        assertEquals(listOf("{Comment}"), MediaPreparation.identifyingMetadata(betweenScans))
        assertEquals(listOf("{Trailer}"), MediaPreparation.identifyingMetadata(clean + "appended".toByteArray()))
        val mpf = byteArrayOf(0xFF.toByte(), 0xE2.toByte(), 0x00, 0x08) + "MPF\u0000II".toByteArray(Charsets.ISO_8859_1)
        val withMpf = clean.copyOfRange(0, 2) + mpf + clean.copyOfRange(2, clean.size)
        assertEquals(listOf("{MPF}"), MediaPreparation.identifyingMetadata(withMpf))
    }

    @Test
    fun videosFitTheFrame() {
        assertEquals(720, VideoPreparation.targetShortSide(1920, 1080))
        assertEquals(720, VideoPreparation.targetShortSide(1080, 1920))
        assertEquals(720, VideoPreparation.targetShortSide(3840, 2160))
        assertEquals(548, VideoPreparation.targetShortSide(2520, 1080))
        assertNull(VideoPreparation.targetShortSide(1280, 720))
        assertNull(VideoPreparation.targetShortSide(640, 480))
        assertNull(VideoPreparation.targetShortSide(0, 0))
    }

    @Test
    fun mp4MetadataBoxesAreFound() {
        fun box(type: String, vararg children: ByteArray): ByteArray {
            val body = children.fold(ByteArray(0)) { all, child -> all + child }
            return be32(body.size + 8) + type.toByteArray(Charsets.ISO_8859_1) + body
        }
        val ftyp = box("ftyp", "isom".toByteArray(), be32(0))
        val mvhd = box("mvhd", ByteArray(100))
        val trak = box("trak", box("tkhd", ByteArray(84)), box("mdia", box("mdhd", ByteArray(24))))
        val clean = ftyp + box("moov", mvhd, trak) + box("mdat", ByteArray(16))
        val location = box("udta", box("©xyz", "+41.0082+028.9784/".toByteArray()))
        val dirty = ftyp + box("moov", mvhd, box("trak", box("tkhd", ByteArray(84)), box("meta", ByteArray(8))), location) + box("mdat", ByteArray(16))
        assertEquals(emptyList<String>(), Mp4Metadata.problems(file("clean.mp4", clean)))
        assertEquals(listOf("moov.trak.meta", "moov.udta"), Mp4Metadata.problems(file("dirty.mp4", dirty)))
        assertEquals(listOf("unreadable"), Mp4Metadata.problems(file("broken.mp4", be32(400) + "moov".toByteArray())))
        assertEquals(listOf("uuid"), Mp4Metadata.problems(file("xmp.mp4", clean + box("uuid", ByteArray(16)))))
    }
}
