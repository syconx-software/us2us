package com.syconx.us2us.runtime.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import com.syconx.us2us.core.profile.InvalidProfilePhotoException
import com.syconx.us2us.core.profile.PhotoFormat
import com.syconx.us2us.core.profile.PhotoProblem
import com.syconx.us2us.core.profile.ProfilePicture
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Why a picked photo could not be made into a profile photo. */
enum class AvatarPhotoProcessingError {
    /** The platform cannot read the picked file. */
    UNREADABLE,

    /** The crop lies outside the photo or is empty. */
    EMPTY_CROP,

    /** No encoder produced a photo that passes §8.9 and fits the record. */
    ENCODING_FAILED,
}

class AvatarPhotoProcessingException(val error: AvatarPhotoProcessingError) : Exception("profile photo not made: $error")

/**
 * Makes a profile photo on the phone (PROTOCOL.md §8.9), in memory only: nothing is written to a
 * file, and the uncropped original is not kept.
 *
 * 1. The header is read first (size and orientation, no pixels): a file the platform cannot read
 *    stops here.
 * 2. The square the user cropped (the centred one without a crop), in the photo as it is displayed:
 *    its orientation applied to the pixels.
 * 3. Decoded at no more pixels than the crop needs, scaled to 256 × 256 (smaller only when the crop
 *    is smaller; never enlarged), drawn opaque over white into sRGB, so the encoder gets pixels and
 *    nothing else of the source file: no EXIF, GPS, XMP, IPTC, maker notes, thumbnails, depth maps
 *    or other auxiliary images.
 * 4. Encoded as HEIC where the phone has an encoder, else JPEG (the platform's bitmap encoder has no
 *    HEIC, so Android phones send JPEG), at quality 0.8 down to 0.4, then smaller, until the
 *    serialized `ProfileAvatar` fits 49110 bytes. A JPEG is also scrubbed byte by byte: only the
 *    frame, tables, the scans and the JFIF header (without a thumbnail) stay: no ICC profile, nothing
 *    between scans but tables, nothing after the end.
 * 5. Checked like a received photo (the core's `ProfilePicture.photo`: signature, header size, byte
 *    limit, square, no metadata in the bytes) and by [metadataProblems] before it is returned.
 */
object AvatarPhotoProcessor {
    const val MAX_PIXELS = ProfilePicture.MAX_DIMENSION
    internal val QUALITIES = listOf(0.8, 0.7, 0.6, 0.5, 0.4)

    /** Each smaller attempt is this much narrower; below [MINIMUM_SIDE] it stops. */
    internal const val SHRINK_FACTOR = 0.8
    internal const val MINIMUM_SIDE = 32

    /**
     * The profile photo of [source] (any format the platform decodes).
     *
     * @param crop the square the user chose, in pixels of the photo as displayed (orientation
     *   applied, origin top left). A crop that is not square is reduced to its centred square;
     *   `null` takes the centred square of the whole photo.
     * @param formats encoders to try, in order (tests pin one).
     * @throws AvatarPhotoProcessingException
     */
    fun prepare(
        source: ByteArray,
        crop: RectF? = null,
        formats: List<PhotoFormat> = listOf(PhotoFormat.HEIC, PhotoFormat.JPEG),
    ): ProfilePicture.Photo {
        val header = ImageDecoderProbe.header(ImageDecoder.createSource(ByteBuffer.wrap(source)))
            ?: throw AvatarPhotoProcessingException(AvatarPhotoProcessingError.UNREADABLE)
        val (displayedWidth, displayedHeight) = header.first
        if (displayedWidth <= 0 || displayedHeight <= 0) throw AvatarPhotoProcessingException(AvatarPhotoProcessingError.UNREADABLE)
        val square = squareCrop(crop, displayedWidth, displayedHeight)
        val cropSide = square.width()
        var side = min(MAX_PIXELS, cropSide)

        while (side >= min(MINIMUM_SIDE, cropSide)) {
            val pixels = render(source, displayedWidth, displayedHeight, square, side)
            encoders@ for (format in formats) {
                if (!canEncode(format)) continue
                for (quality in QUALITIES) {
                    // An encoder that fails, or leaves metadata, is not used at all.
                    val encoded = encode(pixels, format, quality) ?: continue@encoders
                    if (metadataProblems(encoded).isNotEmpty()) continue@encoders
                    val photo = try {
                        ProfilePicture.photo(format.contentType, encoded)
                    } catch (e: InvalidProfilePhotoException) {
                        if (e.problem == PhotoProblem.TOO_MANY_BYTES) continue // lower quality next
                        continue@encoders
                    }
                    if (photo.isSendable) return photo
                    continue@encoders
                }
            }
            val next = floor(side * SHRINK_FACTOR).toInt()
            if (next >= side || next < 1) break
            side = next
        }
        throw AvatarPhotoProcessingException(AvatarPhotoProcessingError.ENCODING_FAILED)
    }

    /**
     * The centred square of [crop] (or of the whole photo), inside it, in whole pixels.
     *
     * @throws AvatarPhotoProcessingException ([AvatarPhotoProcessingError.EMPTY_CROP])
     */
    internal fun squareCrop(crop: RectF?, width: Int, height: Int): Rect {
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val area = RectF(crop ?: bounds).apply { sort() }
        if (!area.intersect(bounds) || area.width() < 1 || area.height() < 1) throw AvatarPhotoProcessingException(AvatarPhotoProcessingError.EMPTY_CROP)
        val side = floor(min(area.width(), area.height()).toDouble())
        val left = area.centerX() - side / 2
        val top = area.centerY() - side / 2
        // "integral": the smallest whole-pixel rectangle that contains it.
        val square = RectF(floor(left).toFloat(), floor(top).toFloat(), ceil(left + side).toFloat(), ceil(top + side).toFloat())
        if (!square.intersect(bounds)) throw AvatarPhotoProcessingException(AvatarPhotoProcessingError.EMPTY_CROP)
        val finalSide = floor(min(square.width(), square.height()).toDouble()).toInt()
        if (finalSide < 1) throw AvatarPhotoProcessingException(AvatarPhotoProcessingError.EMPTY_CROP)
        val x = square.left.toInt()
        val y = square.top.toInt()
        return Rect(x, y, x + finalSide, y + finalSide)
    }

    /**
     * Decodes only as many pixels as the crop needs (the platform's downsampling decoder,
     * orientation applied), crops and scales to [side] × [side], opaque sRGB.
     */
    internal fun render(source: ByteArray, displayedWidth: Int, displayedHeight: Int, square: Rect, side: Int): Bitmap {
        val longEdge = max(displayedWidth, displayedHeight)
        val needed = ceil(longEdge.toDouble() * side / square.width())
        val target = min(longEdge.toDouble(), max(needed, side.toDouble()))
        val scale = target / longEdge
        val decoded = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(source))) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                if (scale < 1.0) {
                    decoder.setTargetSize(max(1, (displayedWidth * scale).roundToInt()), max(1, (displayedHeight * scale).roundToInt()))
                }
            }
        } catch (_: IOException) {
            throw AvatarPhotoProcessingException(AvatarPhotoProcessingError.UNREADABLE)
        } catch (_: IllegalArgumentException) {
            throw AvatarPhotoProcessingException(AvatarPhotoProcessingError.UNREADABLE)
        } catch (_: OutOfMemoryError) {
            throw AvatarPhotoProcessingException(AvatarPhotoProcessingError.UNREADABLE)
        }
        val scaleX = decoded.width.toDouble() / displayedWidth
        val scaleY = decoded.height.toDouble() / displayedHeight
        val region = Rect(
            floor(square.left * scaleX).toInt(),
            floor(square.top * scaleY).toInt(),
            ceil(square.right * scaleX).toInt(),
            ceil(square.bottom * scaleY).toInt(),
        )
        if (!region.intersect(0, 0, decoded.width, decoded.height) || region.width() < 1 || region.height() < 1) {
            throw AvatarPhotoProcessingException(AvatarPhotoProcessingError.UNREADABLE)
        }
        val canvasBitmap = createBitmap(side, side, Bitmap.Config.ARGB_8888, hasAlpha = false, colorSpace = ColorSpace.get(ColorSpace.Named.SRGB))
        val canvas = Canvas(canvasBitmap)
        // Transparent areas become white instead of undefined.
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(decoded, region, Rect(0, 0, side, side), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        decoded.recycle()
        return canvasBitmap
    }

    /**
     * Whether this phone has an encoder for [format]. The platform's bitmap encoder writes JPEG,
     * PNG and WebP only, so HEIC is never available here.
     */
    internal fun canEncode(format: PhotoFormat): Boolean = format == PhotoFormat.JPEG

    /** The encoder is given the pixels and the quality, never anything of the source; a JPEG is scrubbed afterwards. */
    internal fun encode(image: Bitmap, format: PhotoFormat, quality: Double): ByteArray? = when (format) {
        PhotoFormat.JPEG -> MediaPreparation.encodeJpeg(image, quality)?.let(JpegScrubber::scrub)
        PhotoFormat.HEIC -> null
    }

    /** [JpegScrubber.scrub]: the decoder's segments of a JPEG only; `null` for anything that is not a well-formed JPEG. */
    internal fun scrubJpeg(data: ByteArray): ByteArray? = JpegScrubber.scrub(data)

    /**
     * What the container still shows besides bare pixels; empty for a photo that may be sent.
     * Reports EXIF, XMP, IPTC, MPF and other application segments, comments, thumbnails, bytes
     * after the end, colour profiles (the pixels are sRGB), an image the platform cannot read, and
     * more than one image.
     */
    internal fun metadataProblems(data: ByteArray): List<String> {
        val format = ImageFormat.sniff(data.copyOfRange(0, min(data.size, 32))) ?: return listOf("unreadable")
        val problems = ImageMetadata.identifying(data).toMutableList()
        if (format == ImageFormat.JPEG && hasIccProfile(data)) problems += "{ICC}"
        if (ImageDecoderProbe.header(ImageDecoder.createSource(ByteBuffer.wrap(data))) == null) problems += "unreadable"
        return problems.sorted()
    }

    /**
     * A received photo as the platform decoder sees its header, without decoding pixels: one image
     * of the declared type, at most 256 × 256 (§8.9). The core's own header checks ran when it
     * arrived; this is the decoder's view, asked right before drawing.
     */
    fun isDisplayable(photo: ProfilePicture.Photo): Boolean {
        val header = ImageDecoderProbe.header(ImageDecoder.createSource(ByteBuffer.wrap(photo.data))) ?: return false
        val (size, mimeType) = header
        val matches = when (photo.format) {
            PhotoFormat.JPEG -> mimeType == "image/jpeg"
            PhotoFormat.HEIC -> mimeType == "image/heif" || mimeType == "image/heic"
        }
        return matches && size.first in 1..MAX_PIXELS && size.second in 1..MAX_PIXELS
    }

    /**
     * Decodes a profile photo for drawing, in memory, after [isDisplayable]; `null` for anything
     * else. The caller crops it to the centred circle.
     */
    fun image(photo: ProfilePicture.Photo): Bitmap? {
        if (!isDisplayable(photo)) return null
        return try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(photo.data))) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun hasIccProfile(data: ByteArray): Boolean {
        var index = 2
        while (index + 4 <= data.size && data.u8(index) == 0xFF) {
            val marker = data.u8(index + 1)
            if (marker == 0xDA || marker == 0xD9) return false
            val length = (data.u8(index + 2) shl 8) or data.u8(index + 3)
            if (marker == 0xE2 && data.startsWith(index + 4, index + 2 + length, "ICC_PROFILE\u0000")) return true
            index += 2 + length
        }
        return false
    }
}
