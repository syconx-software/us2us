package com.syconx.us2us.runtime.media

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import androidx.core.graphics.scale
import com.google.protobuf.ByteString
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.runtime.support.ProtectedFiles
import com.syconx.us2us.runtime.support.RuntimeLog
import com.syconx.us2us.runtime.support.logLabel
import com.syconx.us2us.runtime.vault.AppDirectories
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Phone-side media work of PROTOCOL.md §1.1 and §7: images ≤ 2048 px long edge as JPEG q 0.8,
 * video ≤ 720p ([VideoPreparation]), an inline thumbnail ≤ 10 KiB, and (a MUST of §7) no EXIF, GPS
 * or other metadata unless the user explicitly keeps it. Images are re-encoded from pixels only,
 * so nothing of the original container (EXIF, GPS, TIFF, maker notes, XMP, IPTC) survives.
 *
 * Everything here reads and writes files: call it off the main thread.
 */
object MediaPreparation {
    const val MAX_IMAGE_EDGE = 2048
    const val IMAGE_QUALITY = 0.8

    /** brp.proto `Attachment.thumbnail`. */
    const val MAX_THUMBNAIL_BYTES = 10 * 1024

    /** brp.proto `Attachment.waveform`. */
    const val MAX_WAVEFORM_SAMPLES = 100

    /** The longest file name sent with a document. */
    const val MAX_FILE_NAME_CHARACTERS = 255

    internal val THUMBNAIL_EDGES = listOf(160, 120, 96, 64)
    internal val THUMBNAIL_QUALITIES = listOf(0.6, 0.45, 0.3)

    /** Quality of a photo rewritten as a document (kept as close to the original as JPEG allows). */
    internal const val DOCUMENT_QUALITY = 0.92

    /** A document photo larger than this is rewritten at this many pixels: more does not fit in memory. */
    internal const val MAX_DOCUMENT_PIXELS = 100_000_000L

    /** Animated images are cleaned without decoding up to this size; larger ones are rewritten from their first frame. */
    private const val MAX_SCRUB_BYTES = 64 * 1024 * 1024

    // Images

    /** A photo to send: at most [MAX_IMAGE_EDGE] on the long edge, orientation applied, JPEG from pixels. */
    fun prepareImage(
        source: MediaInput,
        directories: AppDirectories,
        caption: String? = null,
        kind: Attachment.Kind = Attachment.Kind.IMAGE,
    ): PreparedMedia {
        val image = decode(source.imageSource(), maxEdge = MAX_IMAGE_EDGE) ?: throw MediaPreparationException(MediaPreparationError.UNREADABLE)
        val jpeg = encodeJpeg(image, IMAGE_QUALITY) ?: throw MediaPreparationException(MediaPreparationError.ENCODING_FAILED)
        val output = directories.stagingFile("jpg")
        try {
            ProtectedFiles.write(jpeg, output)
        } catch (e: IOException) {
            output.delete()
            throw MediaPreparationException(MediaPreparationError.ENCODING_FAILED, e)
        }
        return PreparedMedia(
            file = output,
            kind = kind,
            contentType = "image/jpeg",
            size = jpeg.size.toLong(),
            width = image.width,
            height = image.height,
            thumbnail = thumbnail(image)?.let(ByteString::copyFrom),
            caption = caption,
        )
    }

    fun prepareImage(source: File, directories: AppDirectories, caption: String? = null, kind: Attachment.Kind = Attachment.Kind.IMAGE): PreparedMedia =
        prepareImage(MediaInput.of(source), directories, caption, kind)

    /**
     * Decodes at most [maxEdge] pixels on the long edge (and at most [maxPixels] in all), with the
     * EXIF orientation applied to the pixels (so no orientation tag is needed), into a software
     * bitmap without an HDR gain map. `null` if it does not decode.
     */
    internal fun decode(source: ImageDecoder.Source, maxEdge: Int? = null, maxPixels: Long? = null): Bitmap? = try {
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            val width = info.size.width
            val height = info.size.height
            var scale = 1.0
            if (maxEdge != null) scale = minOf(scale, maxEdge.toDouble() / max(width, height))
            if (maxPixels != null) scale = minOf(scale, sqrt(maxPixels.toDouble() / (width.toDouble() * height)))
            if (scale < 1.0) {
                decoder.setTargetSize(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
            }
        }.also(::dropGainmap)
    } catch (e: IOException) {
        RuntimeLog.media.info("image did not decode", e.logLabel)
        null
    } catch (e: IllegalArgumentException) {
        RuntimeLog.media.info("image did not decode", e.logLabel)
        null
    } catch (e: OutOfMemoryError) {
        RuntimeLog.media.notice("image too large to decode")
        null
    }

    /** An Ultra HDR photo's gain map would be written back as an MPF image with XMP: the SDR pixels go alone. */
    private fun dropGainmap(bitmap: Bitmap) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && bitmap.hasGainmap()) {
            bitmap.gainmap = null
        }
    }

    /** JPEG from pixels only: the platform encoder writes a JFIF header and no metadata. */
    internal fun encodeJpeg(image: Bitmap, quality: Double): ByteArray? = encode(image, Bitmap.CompressFormat.JPEG, quality)

    internal fun encode(image: Bitmap, format: Bitmap.CompressFormat, quality: Double): ByteArray? {
        val output = ByteArrayOutputStream()
        return if (image.compress(format, (quality * 100).roundToInt().coerceIn(0, 100), output)) output.toByteArray() else null
    }

    /** A small JPEG for the bubble, ≤ 10 KiB (brp.proto `Attachment.thumbnail`). */
    internal fun thumbnail(image: Bitmap): ByteArray? {
        for (edge in THUMBNAIL_EDGES) {
            val small = scaledToFit(image, edge)
            for (quality in THUMBNAIL_QUALITIES) {
                val data = encodeJpeg(small, quality)
                if (data != null && data.size <= MAX_THUMBNAIL_BYTES) return data
            }
        }
        return null
    }

    /** [image] with its long edge at most [edge] (never enlarged). */
    internal fun scaledToFit(image: Bitmap, edge: Int): Bitmap {
        val long = max(image.width, image.height)
        if (long <= edge) return image
        val scale = edge.toDouble() / long
        return image.scale(max(1, (image.width * scale).roundToInt()), max(1, (image.height * scale).roundToInt()))
    }

    // Voice notes and files

    /**
     * A voice note recorded by the app (AAC-LC in M4A, no location metadata), moved into the
     * staging directory.
     */
    fun prepareVoice(source: File, directories: AppDirectories, durationMs: Int, waveform: ByteArray): PreparedMedia {
        val output = directories.stagingFile("m4a")
        if (!source.renameTo(output)) {
            try {
                source.inputStream().use { input -> writeSynced(input, output) }
            } catch (e: IOException) {
                output.delete()
                throw e
            }
            source.delete()
        }
        return PreparedMedia(
            file = output,
            kind = Attachment.Kind.VOICE,
            contentType = "audio/mp4",
            size = output.length(),
            durationMs = durationMs,
            waveform = ByteString.copyFrom(waveform, 0, minOf(waveform.size, MAX_WAVEFORM_SAMPLES)),
        )
    }

    /**
     * A voice note the app recorded and holds in memory (AAC-LC in M4A): its bytes go to a staging
     * file, which storing the message encrypts and deletes at once. The staging folder is emptied
     * at every lock and launch, so a copy left by a crash goes too.
     */
    fun prepareVoice(audio: ByteArray, directories: AppDirectories, durationMs: Int, waveform: ByteArray): PreparedMedia {
        val output = directories.stagingFile("m4a")
        try {
            ProtectedFiles.write(audio, output)
        } catch (e: IOException) {
            output.delete()
            throw e
        }
        return PreparedMedia(
            file = output,
            kind = Attachment.Kind.VOICE,
            contentType = "audio/mp4",
            size = audio.size.toLong(),
            durationMs = durationMs,
            waveform = ByteString.copyFrom(waveform, 0, minOf(waveform.size, MAX_WAVEFORM_SAMPLES)),
        )
    }

    /**
     * A document the user picked, sent as it is (its name is shown to the recipient; documents can
     * carry their own author metadata, which the app cannot strip for every format), except
     * photos: picking one as a document is not the explicit choice §7 requires to keep EXIF/GPS, so
     * an image is rewritten without its metadata at full size (format kept when it can be written,
     * else JPEG or PNG). A video throws [MediaPreparationError.REQUIRES_VIDEO_PREPARATION]: use
     * [VideoPreparation.prepareVideoFile].
     */
    fun prepareFile(
        source: MediaInput,
        directories: AppDirectories,
        fileName: String,
        contentType: String,
        caption: String? = null,
    ): PreparedMedia {
        if (isMovie(contentType, fileName)) throw MediaPreparationException(MediaPreparationError.REQUIRES_VIDEO_PREPARATION)
        if (isImage(contentType, fileName)) {
            stripImageMetadata(source, directories, fileName)?.let { stripped ->
                return PreparedMedia(
                    file = stripped.file,
                    kind = Attachment.Kind.FILE,
                    contentType = stripped.contentType,
                    fileName = stripped.fileName.characterPrefix(MAX_FILE_NAME_CHARACTERS),
                    size = stripped.size,
                    width = stripped.width,
                    height = stripped.height,
                    caption = caption,
                )
            }
        }
        val output = directories.stagingFile()
        try {
            source.openStream().use { input -> writeSynced(input, output) }
        } catch (e: IOException) {
            output.delete()
            throw e
        }
        return PreparedMedia(
            file = output,
            kind = Attachment.Kind.FILE,
            contentType = contentType,
            fileName = fileName.characterPrefix(MAX_FILE_NAME_CHARACTERS),
            size = output.length(),
            caption = caption,
        )
    }

    fun prepareFile(source: File, directories: AppDirectories, fileName: String, contentType: String, caption: String? = null): PreparedMedia =
        prepareFile(MediaInput.of(source), directories, fileName, contentType, caption)

    private val imageExtensions = setOf(
        "jpg", "jpeg", "jpe", "jfif", "png", "apng", "gif", "webp", "heic", "heics", "heif", "heifs", "hif", "avif", "avci",
        "bmp", "dib", "tif", "tiff", "ico", "cur", "svg", "dng", "cr2", "cr3", "nef", "nrw", "arw", "orf", "rw2", "raf", "srw", "pef",
    )
    private val movieExtensions = setOf(
        "mp4", "m4v", "mov", "qt", "3gp", "3gpp", "3g2", "mkv", "webm", "avi", "mpg", "mpeg", "m2v", "m2ts", "mts", "ts",
        "wmv", "flv", "ogv", "f4v",
    )

    internal fun isImage(contentType: String, fileName: String): Boolean =
        contentType.lowercase(Locale.ROOT).startsWith("image/") || fileName.fileExtension() in imageExtensions

    fun isMovie(contentType: String, fileName: String): Boolean =
        contentType.lowercase(Locale.ROOT).startsWith("video/") || fileName.fileExtension() in movieExtensions

    internal class StrippedImage(
        val file: File,
        val contentType: String,
        val fileName: String,
        val size: Long,
        val width: Int,
        val height: Int,
    )

    /**
     * Rewrites an image without metadata. GIF, WebP and animated PNG are cleaned in place, keeping
     * their frames and timing (the platform has no encoder for animated GIF, WebP or PNG); every
     * other image is rewritten from its pixels with the orientation applied, as JPEG or PNG. `null`
     * when the platform cannot read it (then it carries no metadata the platform could strip
     * either). Fails closed: a result that still shows metadata is not used.
     */
    internal fun stripImageMetadata(source: MediaInput, directories: AppDirectories, fileName: String): StrippedImage? {
        val head = source.openStream().use { it.readUpTo(32) } ?: return null
        val format = ImageFormat.sniff(head)
        val base = fileName.substringBeforeLast('.', fileName).ifEmpty { "image" }
        if (format == ImageFormat.GIF || format == ImageFormat.WEBP || format == ImageFormat.PNG) {
            val bytes = source.openStream().use { it.readUpTo(MAX_SCRUB_BYTES + 1) }
            if (bytes != null && bytes.size <= MAX_SCRUB_BYTES) {
                val cleaned = when (format) {
                    ImageFormat.GIF -> ImageScrubber.gif(bytes)
                    ImageFormat.WEBP -> ImageScrubber.webp(bytes)
                    else -> if (ImageScrubber.isAnimatedPng(bytes)) ImageScrubber.png(bytes) else null
                }
                if (cleaned != null) {
                    val size = ImageDecoderProbe.size(ImageDecoder.createSource(ByteBuffer.wrap(cleaned)))
                    if (size != null) return writeStripped(cleaned, format, base, directories, size.first, size.second)
                }
            }
        }
        val image = decode(source.imageSource(), maxPixels = MAX_DOCUMENT_PIXELS) ?: return null
        val outputFormat = when {
            format == ImageFormat.JPEG -> ImageFormat.JPEG
            format == ImageFormat.PNG -> ImageFormat.PNG
            image.hasAlpha() -> ImageFormat.PNG
            else -> ImageFormat.JPEG
        }
        val encoded = when (outputFormat) {
            ImageFormat.PNG -> encode(image, Bitmap.CompressFormat.PNG, 1.0)
            else -> encode(image, Bitmap.CompressFormat.JPEG, DOCUMENT_QUALITY)
        } ?: throw MediaPreparationException(MediaPreparationError.ENCODING_FAILED)
        return writeStripped(encoded, outputFormat, base, directories, image.width, image.height)
    }

    private fun writeStripped(data: ByteArray, format: ImageFormat, base: String, directories: AppDirectories, width: Int, height: Int): StrippedImage {
        // Fail closed: nothing identifying may be left.
        val left = ImageMetadata.identifying(data)
        if (left.isNotEmpty()) {
            RuntimeLog.media.notice("metadata left after cleaning", format.name)
            throw MediaPreparationException(MediaPreparationError.METADATA_NOT_REMOVABLE)
        }
        val output = directories.stagingFile(format.fileExtension)
        try {
            ProtectedFiles.write(data, output)
        } catch (e: IOException) {
            output.delete()
            throw MediaPreparationException(MediaPreparationError.ENCODING_FAILED, e)
        }
        return StrippedImage(output, format.contentType, "$base.${format.fileExtension}", data.size.toLong(), width, height)
    }

    /** The metadata of an encoded image that can identify a person, place, device or time (see [ImageMetadata]). */
    internal fun identifyingMetadata(data: ByteArray): List<String> = ImageMetadata.identifying(data)

    private fun writeSynced(input: InputStream, output: File) {
        output.parentFile?.mkdirs()
        FileOutputStream(output).use { stream ->
            input.copyTo(stream)
            stream.flush()
            stream.fd.sync()
        }
    }
}

/** Header reads through the platform decoder, without decoding pixels. */
internal object ImageDecoderProbe {
    /** Width and height as displayed (orientation applied), or `null` if the header does not read. */
    fun size(source: ImageDecoder.Source): Pair<Int, Int>? = header(source)?.let { it.first }

    /** Size and MIME type, or `null`. */
    fun header(source: ImageDecoder.Source): Pair<Pair<Int, Int>, String>? {
        var result: Pair<Pair<Int, Int>, String>? = null
        try {
            ImageDecoder.decodeDrawable(source) { _, info, _ ->
                result = (info.size.width to info.size.height) to info.mimeType
                throw HeaderRead()
            }
        } catch (_: HeaderRead) {
            // Stopped on purpose after the header.
        } catch (_: IOException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return result
    }

    private class HeaderRead : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }
}

/** At most [limit] bytes of the stream, or `null` if it is empty. */
internal fun InputStream.readUpTo(limit: Int): ByteArray? {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(64 * 1024)
    var total = 0
    while (total < limit) {
        val read = read(chunk, 0, minOf(chunk.size, limit - total))
        if (read < 0) break
        buffer.write(chunk, 0, read)
        total += read
    }
    return if (total == 0) null else buffer.toByteArray()
}

/** The lower-case extension of a file name, or "". */
internal fun String.fileExtension(): String = substringAfterLast('.', "").lowercase(Locale.ROOT)

/** At most [count] user-perceived characters. */
internal fun String.characterPrefix(count: Int): String {
    if (length <= count) return this
    val characters = BreakIterator.getCharacterInstance(Locale.ROOT).also { it.setText(this) }
    var end = 0
    repeat(count) {
        val next = characters.following(end)
        if (next == BreakIterator.DONE) return this
        end = next
    }
    return substring(0, end)
}
