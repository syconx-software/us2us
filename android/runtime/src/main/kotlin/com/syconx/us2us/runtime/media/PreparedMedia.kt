package com.syconx.us2us.runtime.media

import android.content.ContentResolver
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.protobuf.ByteString
import com.syconx.us2us.core.proto.Attachment
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * A file ready to be sent: compressed and stripped of metadata, in the staging directory. Sending
 * moves it into encrypted storage.
 */
data class PreparedMedia(
    val file: File,
    val kind: Attachment.Kind,
    val contentType: String,
    val fileName: String? = null,
    val size: Long,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Int = 0,
    /** Inline JPEG ≤ 10 KiB. */
    val thumbnail: ByteString? = null,
    val caption: String? = null,
    /** Voice notes: up to 100 amplitude samples. */
    val waveform: ByteString? = null,
) {
    /**
     * The `Attachment` without its relay pointer; the outbox uploads the blob and fills in media
     * id, delete token, key and digest.
     */
    val pendingAttachment: Attachment
        get() = Attachment.newBuilder()
            .setKind(kind)
            .setContentType(contentType)
            .setFileName(fileName ?: "")
            .setSize(size)
            .setWidth(width)
            .setHeight(height)
            .setDurationMs(durationMs)
            .setThumbnail(thumbnail ?: ByteString.EMPTY)
            .setCaption(caption ?: "")
            .setWaveform(waveform ?: ByteString.EMPTY)
            .build()
}

enum class MediaPreparationError {
    UNREADABLE,
    ENCODING_FAILED,

    /** Metadata could not be removed; nothing is sent (§7 is a MUST). */
    METADATA_NOT_REMOVABLE,

    /**
     * A video picked as a document: use [VideoPreparation.prepareVideoFile] (asynchronous), which
     * removes its metadata.
     */
    REQUIRES_VIDEO_PREPARATION,
}

class MediaPreparationException(val error: MediaPreparationError, cause: Throwable? = null) :
    IOException("media preparation failed: $error", cause)

/**
 * Where picked media is read from: a file (a recording, a camera capture), or a `content:` URI of
 * the Photo Picker or the document picker, read in place so no unstripped copy is written first.
 */
sealed interface MediaInput {
    fun openStream(): InputStream

    fun imageSource(): ImageDecoder.Source

    /** The URI a media extractor opens. */
    val uri: Uri

    class FromFile(val file: File) : MediaInput {
        override fun openStream(): InputStream = file.inputStream()

        override fun imageSource(): ImageDecoder.Source = ImageDecoder.createSource(file)

        override val uri: Uri get() = Uri.fromFile(file)
    }

    class FromContent(private val resolver: ContentResolver, override val uri: Uri) : MediaInput {
        override fun openStream(): InputStream = resolver.openInputStream(uri) ?: throw FileNotFoundException("no stream")

        override fun imageSource(): ImageDecoder.Source = ImageDecoder.createSource(resolver, uri)
    }

    companion object {
        fun of(file: File): MediaInput = FromFile(file)

        fun of(resolver: ContentResolver, uri: Uri): MediaInput = FromContent(resolver, uri)
    }
}
