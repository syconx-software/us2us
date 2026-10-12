package com.syconx.us2us.runtime.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Gainmap
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syconx.us2us.core.profile.PhotoFormat
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.runtime.vault.AppDirectories
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Media preparation with the phone's own codecs: H.264 transcoding and re-muxing through media3
 * (no metadata left), the platform decoder's orientation handling, and Ultra HDR gain maps.
 */
@RunWith(AndroidJUnit4::class)
class MediaDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var root: File
    private lateinit var directories: AppDirectories

    @Before
    fun setUp() {
        root = File(context.cacheDir, "media-device-test").also { it.deleteRecursively() }
        directories = AppDirectories(File(root, "us2us"))
        directories.prepare()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun aVideoIsScaledTo720pWithoutMetadata() = runBlocking {
        val source = recordClip(File(root, "clip.mp4"), 1920, 1080)
        assertFalse("control: the platform muxer writes location and version", Mp4Metadata.problems(source).isEmpty())

        val prepared = VideoPreparation.prepareVideo(context, source, directories, caption = "clip")
        assertEquals(emptyList<String>(), Mp4Metadata.problems(prepared.file))
        assertEquals(Attachment.Kind.VIDEO, prepared.kind)
        assertEquals("video/mp4", prepared.contentType)
        assertEquals(1280, prepared.width)
        assertEquals(720, prepared.height)
        assertTrue(prepared.durationMs > 500)
        assertTrue(prepared.thumbnail!!.size() <= MediaPreparation.MAX_THUMBNAIL_BYTES)
        assertEquals("clip", prepared.caption)
        assertEquals(prepared.file.length(), prepared.size)
        assertEquals("H.264", MediaFormat.MIMETYPE_VIDEO_AVC, mimeOfFirstTrack(prepared.file))
    }

    @Test
    fun aVideoDocumentIsRemuxedWithoutMetadata() = runBlocking {
        val source = recordClip(File(root, "clip.mp4"), 640, 360)
        val prepared = VideoPreparation.prepareVideoFile(context, MediaInput.of(source), directories, "Holiday clip.mov")
        assertEquals(emptyList<String>(), Mp4Metadata.problems(prepared.file))
        assertEquals(Attachment.Kind.FILE, prepared.kind)
        assertEquals("video/mp4", prepared.contentType)
        assertEquals("Holiday clip.mp4", prepared.fileName)
        assertEquals(0, prepared.width)
    }

    @Test
    fun theDecoderAppliesTheOrientation() {
        val file = File(root, "turned.jpg")
        file.writeBytes(bitmap(300, 100).jpeg())
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_MAKE, "PhoneMaker")
            setLatLong(41.0, 29.0)
            saveAttributes()
        }
        val prepared = MediaPreparation.prepareImage(file, directories)
        assertEquals(100, prepared.width)
        assertEquals(300, prepared.height)
        assertEquals(emptyList<String>(), MediaPreparation.identifyingMetadata(prepared.file.readBytes()))

        val photo = AvatarPhotoProcessor.prepare(file.readBytes(), formats = listOf(PhotoFormat.HEIC, PhotoFormat.JPEG))
        assertEquals(100, photo.width)
        assertTrue(photo.isSendable)
        assertTrue(AvatarPhotoProcessor.isDisplayable(photo))
    }

    /** An Ultra HDR photo carries a gain map as a second image with XMP: only the SDR pixels are sent. */
    @Test
    fun anUltraHdrGainMapIsNotSent() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val image = bitmap(400, 300)
        image.gainmap = Gainmap(bitmap(100, 75))
        val hdr = image.jpeg()
        val file = File(root, "hdr.jpg").also { it.writeBytes(hdr) }
        val prepared = MediaPreparation.prepareImage(file, directories)
        assertEquals(emptyList<String>(), MediaPreparation.identifyingMetadata(prepared.file.readBytes()))
        val document = MediaPreparation.prepareFile(file, directories, "hdr.jpg", "image/jpeg")
        assertEquals(emptyList<String>(), MediaPreparation.identifyingMetadata(document.file.readBytes()))
    }

    private fun bitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.RED)
        canvas.drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = Color.BLUE })
        return bitmap
    }

    private fun Bitmap.jpeg(): ByteArray = ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()

    private fun mimeOfFirstTrack(file: File): String? {
        val extractor = android.media.MediaExtractor()
        extractor.setDataSource(file.path)
        val mime = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }.firstOrNull { it?.startsWith("video/") == true }
        extractor.release()
        return mime
    }

    /**
     * Two seconds of H.264 drawn on the encoder's surface, muxed by the platform's `MediaMuxer`
     * with a location (which it writes as `©xyz`, next to the Android version in a `meta` box).
     */
    private fun recordClip(output: File, width: Int, height: Int): File {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setLocation(41.0082f, 28.9784f)
        var track = -1
        val info = MediaCodec.BufferInfo()

        fun drain(endOfStream: Boolean) {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                            muxer.writeSampleData(track, buffer, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        for (frame in 0 until 60) {
            val canvas = surface.lockHardwareCanvas()
            canvas.drawColor(if (frame % 2 == 0) Color.RED else Color.BLUE)
            surface.unlockCanvasAndPost(canvas)
            Thread.sleep(33)
            drain(endOfStream = false)
        }
        codec.signalEndOfInputStream()
        drain(endOfStream = true)
        codec.stop()
        codec.release()
        surface.release()
        muxer.stop()
        muxer.release()
        assertNotNull(output.takeIf { it.length() > 0 })
        return output
    }
}
