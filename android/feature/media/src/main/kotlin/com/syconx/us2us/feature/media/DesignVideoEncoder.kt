package com.syconx.us2us.feature.media

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.DesignArtwork
import com.syconx.us2us.ui.media.DesignArtworkBitmap
import java.io.File
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The 5.2 design video for the fixtures (iOS `FixtureMedia.videoMP4`): 18 s of the sunset poster,
 * slowly pushing in while the sun's glow drifts and breathes, H.264 640 × 360 at 24 fps, no sound.
 * Encoded once with MediaCodec through its input surface, written by MediaMuxer to a temporary file
 * that is deleted as soon as it has been read, then kept in memory.
 *
 * Install it on the fixtures service: `BitmapDesignArtworkEncoder { DesignVideoEncoder.mp4(it, cacheDir) }`.
 */
object DesignVideoEncoder {
    private const val WIDTH = 640
    private const val HEIGHT = 360
    private const val FPS = 24
    private const val SECONDS = 18
    private const val TIMEOUT_US = 10_000L

    private val lock = Mutex()
    private var cached: Bytes? = null

    /** The clip for [artwork] (the design has one: the sunset), `null` when the device cannot encode it. */
    suspend fun mp4(artwork: DesignArtwork, cacheDir: File): Bytes? = lock.withLock {
        cached?.let { return@withLock it }
        val clip = withContext(Dispatchers.Default) {
            val poster = runCatching { DesignArtworkBitmap.render(artwork, 1_280) }.getOrNull() ?: return@withContext null
            val folder = File(cacheDir, "fixture-media").apply { mkdirs() }
            val file = File(folder, UUID.randomUUID().toString() + ".mp4")
            try {
                if (encode(poster, file)) Bytes.wrap(file.readBytes()) else null
            } catch (_: Exception) {
                null
            } finally {
                file.delete()
                poster.recycle()
            }
        }
        cached = clip
        clip
    }

    private fun encode(poster: Bitmap, file: File): Boolean {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_200_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Baseline: no reordered frames, so the output comes in display order.
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var muxer: MediaMuxer? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val drain = Drain(codec, muxer)
            val frames = SECONDS * FPS
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            for (frame in 0 until frames) {
                val canvas = surface.lockHardwareCanvas()
                try {
                    draw(canvas, poster, frame.toFloat() / frames, paint)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                drain.run(endOfStream = false)
            }
            codec.signalEndOfInputStream()
            drain.run(endOfStream = true)
            surface.release()
            return drain.frames > 0
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    /** Push-in 1.00 → 1.06 about the sun; the glow drifts a little and breathes twice. */
    private fun draw(canvas: android.graphics.Canvas, poster: Bitmap, progress: Float, paint: Paint) {
        val scale = 1f + 0.06f * progress
        val width = WIDTH * scale
        val height = HEIGHT * scale
        val left = (WIDTH - width) * 0.62f
        val top = (HEIGHT - height) * 0.42f
        canvas.drawBitmap(poster, null, RectF(left, top, left + width, top + height), paint)
        val breath = 0.5f + 0.5f * sin(progress * 4 * PI).toFloat()
        val cx = WIDTH * (0.6f + 0.04f * progress)
        val cy = HEIGHT * (0.42f - 0.03f * progress)
        val alpha = ((0.18f + 0.14f * breath) * 255).toInt()
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, HEIGHT * 0.45f,
                intArrayOf(android.graphics.Color.argb(alpha, 255, 214, 171), android.graphics.Color.argb(0, 255, 171, 120)),
                null, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(cx, cy, HEIGHT * 0.45f, glow)
    }

    /** Moves encoded frames to the muxer, timestamped by their index (the surface's own stamps are wall-clock). */
    private class Drain(private val codec: MediaCodec, private val muxer: MediaMuxer) {
        private val info = MediaCodec.BufferInfo()
        private var track = -1
        var frames = 0
            private set

        fun run(endOfStream: Boolean) {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buffer != null && info.size > 0 && !isConfig && track >= 0) {
                            info.presentationTimeUs = frames * 1_000_000L / FPS
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                            frames++
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
    }
}
