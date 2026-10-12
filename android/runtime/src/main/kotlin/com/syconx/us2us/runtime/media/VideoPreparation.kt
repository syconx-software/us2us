package com.syconx.us2us.runtime.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4TimestampData
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.Transformer
import com.google.protobuf.ByteString
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.runtime.support.RuntimeLog
import com.syconx.us2us.runtime.support.logLabel
import com.syconx.us2us.runtime.vault.AppDirectories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Videos (PROTOCOL.md §1.1, §7): a video to send is re-encoded at most 720p (fits 1280 × 720 in
 * either orientation) as H.264 with AAC audio in MP4; a video picked as a document is re-muxed
 * without re-encoding. Both are written by media3's own MP4 muxer with every metadata entry
 * removed (location, capture date, `com.android.*` keys, XMP; the movie's creation time is 0),
 * and checked afterwards to carry no metadata box at all (fail closed:
 * [MediaPreparationError.METADATA_NOT_REMOVABLE]).
 */
@OptIn(UnstableApi::class)
object VideoPreparation {
    const val MAX_LONG_SIDE = 1280
    const val MAX_SHORT_SIDE = 720

    /** Edge of the frame a thumbnail is made from. */
    private const val THUMBNAIL_SOURCE_EDGE = 320

    /** A video to send: ≤ 720p H.264 / AAC in MP4, without metadata. */
    suspend fun prepareVideo(context: Context, source: MediaInput, directories: AppDirectories, caption: String? = null): PreparedMedia {
        val probe = withContext(Dispatchers.IO) { probe(context, source) } ?: throw MediaPreparationException(MediaPreparationError.UNREADABLE)
        val output = directories.stagingFile("mp4")
        val effects = targetShortSide(probe.displayWidth, probe.displayHeight)?.let { side ->
            listOf<Effect>(Presentation.createForShortSide(side).copyWithUnsetSideRoundedTo(2))
        } ?: emptyList()
        try {
            export(context, source, output, effects, transcode = true)
            ensureClean(output)
        } catch (e: Exception) {
            output.delete()
            throw e
        }
        return withContext(Dispatchers.IO) { describe(context, output, Attachment.Kind.VIDEO, "video/mp4", null, caption, withThumbnail = true) }
    }

    suspend fun prepareVideo(context: Context, source: File, directories: AppDirectories, caption: String? = null): PreparedMedia =
        prepareVideo(context, MediaInput.of(source), directories, caption)

    /**
     * A video picked as a document: re-muxed (no re-encoding, quality kept) without any metadata
     * (location, device, software, capture date). The platform writes MP4 only, so a QuickTime
     * movie is sent as `.mp4`.
     */
    suspend fun prepareVideoFile(
        context: Context,
        source: MediaInput,
        directories: AppDirectories,
        fileName: String,
        caption: String? = null,
    ): PreparedMedia {
        val extension = fileName.fileExtension().takeIf { it == "mp4" || it == "m4v" } ?: "mp4"
        val output = directories.stagingFile(extension)
        try {
            export(context, source, output, emptyList(), transcode = false)
            ensureClean(output)
        } catch (e: Exception) {
            output.delete()
            throw e
        }
        val base = fileName.substringBeforeLast('.', fileName).ifEmpty { "video" }
        val name = "$base.$extension".characterPrefix(MediaPreparation.MAX_FILE_NAME_CHARACTERS)
        return withContext(Dispatchers.IO) { describe(context, output, Attachment.Kind.FILE, "video/mp4", name, caption, withThumbnail = false) }
    }

    /**
     * The short side to scale to so the video fits 1280 × 720 in its orientation, or `null` when
     * it already does (never enlarged).
     */
    internal fun targetShortSide(width: Int, height: Int): Int? {
        if (width <= 0 || height <= 0) return null
        val long = max(width, height)
        val short = min(width, height)
        val scale = min(1.0, min(MAX_LONG_SIDE.toDouble() / long, MAX_SHORT_SIDE.toDouble() / short))
        if (scale >= 1.0) return null
        return max(2, (floor(short * scale / 2) * 2).toInt())
    }

    private fun ensureClean(output: File) {
        val problems = Mp4Metadata.problems(output)
        if (problems.isNotEmpty()) {
            RuntimeLog.media.notice("metadata left in video")
            throw MediaPreparationException(MediaPreparationError.METADATA_NOT_REMOVABLE)
        }
    }

    /** Removes every metadata entry and pins the movie's creation and modification time to 0. */
    private val metadataFree = InAppMp4Muxer.MetadataProvider { entries ->
        entries.clear()
        entries.add(Mp4TimestampData(0, 0))
    }

    private suspend fun export(context: Context, source: MediaInput, output: File, effects: List<Effect>, transcode: Boolean) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val builder = Transformer.Builder(context)
                    .setMuxerFactory(InAppMp4Muxer.Factory(metadataFree))
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            continuation.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            RuntimeLog.media.info("video export failed", exportException.errorCodeName)
                            continuation.resumeWithException(MediaPreparationException(MediaPreparationError.ENCODING_FAILED, exportException))
                        }
                    })
                if (transcode) {
                    builder.setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                }
                val transformer = builder.build()
                val item = EditedMediaItem.Builder(MediaItem.fromUri(source.uri))
                    .setEffects(Effects(emptyList(), effects))
                    .build()
                val main = Handler(Looper.getMainLooper())
                continuation.invokeOnCancellation { main.post { transformer.cancel() } }
                try {
                    transformer.start(item, output.path)
                } catch (e: IllegalStateException) {
                    continuation.resumeWithException(MediaPreparationException(MediaPreparationError.ENCODING_FAILED, e))
                }
            }
        }
    }

    internal class Probe(val displayWidth: Int, val displayHeight: Int, val durationMs: Long)

    /** Size as displayed (rotation applied) and duration, or `null` if the platform cannot read it. */
    internal fun probe(context: Context, source: MediaInput): Probe? {
        val retriever = MediaMetadataRetriever()
        return try {
            when (source) {
                is MediaInput.FromFile -> retriever.setDataSource(source.file.path)
                is MediaInput.FromContent -> retriever.setDataSource(context, source.uri)
            }
            probe(retriever)
        } catch (e: RuntimeException) {
            RuntimeLog.media.info("video did not open", e.logLabel)
            null
        } finally {
            try {
                retriever.release()
            } catch (_: IOException) {
            }
        }
    }

    private fun probe(retriever: MediaMetadataRetriever): Probe? {
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        val turned = rotation % 180 != 0
        return Probe(if (turned) height else width, if (turned) width else height, max(0, duration))
    }

    private fun describe(
        context: Context,
        output: File,
        kind: Attachment.Kind,
        contentType: String,
        fileName: String?,
        caption: String?,
        withThumbnail: Boolean,
    ): PreparedMedia {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(output.path)
            val probe = probe(retriever)
            val thumbnail = if (withThumbnail) {
                retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMBNAIL_SOURCE_EDGE, THUMBNAIL_SOURCE_EDGE)
                    ?.let(MediaPreparation::thumbnail)
                    ?.let(ByteString::copyFrom)
            } else {
                null
            }
            return PreparedMedia(
                file = output,
                kind = kind,
                contentType = contentType,
                fileName = fileName,
                size = output.length(),
                width = if (withThumbnail) probe?.displayWidth ?: 0 else 0,
                height = if (withThumbnail) probe?.displayHeight ?: 0 else 0,
                durationMs = if (withThumbnail) (probe?.durationMs ?: 0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0,
                thumbnail = thumbnail,
                caption = caption,
            )
        } catch (e: RuntimeException) {
            RuntimeLog.media.info("prepared video did not open", e.logLabel)
            return PreparedMedia(output, kind, contentType, fileName, output.length(), caption = caption)
        } finally {
            try {
                retriever.release()
            } catch (_: IOException) {
            }
        }
    }

    private val ExportException.errorCodeName: String get() = ExportException.getErrorCodeName(errorCode)
}
