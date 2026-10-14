package com.syconx.us2us.feature.settings.avatar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import com.syconx.us2us.domain.AvatarPhotoData
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.CropRect
import com.syconx.us2us.domain.harness.AvatarPhotoPreparer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Makes a framed photo into a profile photo on the phone (PROTOCOL §8.9) for the fixtures service
 * (the live service uses the runtime's own processor): the square [CropRect] of the photo as
 * displayed, drawn opaque over white into at most [MAX_SIDE] px (never enlarged), as a JPEG with
 * no metadata at the first quality that fits [MAX_BYTES], else smaller.
 */
object AvatarPhotoScaler : AvatarPhotoPreparer {
    /** The picture's side at most (§8.9). */
    const val MAX_SIDE = 256

    /** The largest photo the receiver draws (the decoder's limit). */
    const val MAX_BYTES = 49_110

    override suspend fun prepare(data: Bytes, crop: CropRect?): AvatarPhotoData? =
        withContext(Dispatchers.Default) { prepareNow(data.toByteArray(), crop) }

    /** The same, on the calling thread. */
    fun prepareNow(data: ByteArray, crop: CropRect?): AvatarPhotoData? {
        val photo = decode(data) ?: return null
        val square = square(photo.width, photo.height, crop) ?: return null
        var side = min(MAX_SIDE, square.width())
        while (side >= MIN_SIDE) {
            val scaled = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            Canvas(scaled).apply {
                drawColor(Color.WHITE)
                drawBitmap(photo, square, Rect(0, 0, side, side), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            for (quality in QUALITIES) {
                val out = ByteArrayOutputStream()
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
                if (out.size() <= MAX_BYTES) return AvatarPhotoData(Bytes.wrap(out.toByteArray()), "image/jpeg")
            }
            side = floor(side * 0.8).toInt()
        }
        return null
    }

    /** The framed square inside the photo, or the centred square without a crop; `null` when it misses the photo. */
    internal fun square(width: Int, height: Int, crop: CropRect?): Rect? {
        if (crop == null) {
            val side = min(width, height)
            return Rect((width - side) / 2, (height - side) / 2, (width - side) / 2 + side, (height - side) / 2 + side)
        }
        val left = crop.x.roundToInt().coerceIn(0, width)
        val top = crop.y.roundToInt().coerceIn(0, height)
        val right = (crop.x + crop.width).roundToInt().coerceIn(0, width)
        val bottom = (crop.y + crop.height).roundToInt().coerceIn(0, height)
        val side = min(right - left, bottom - top)
        if (side < 1) return null
        return Rect(left, top, left + side, top + side)
    }

    private fun decode(data: ByteArray): Bitmap? =
        runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() ?: BitmapFactory.decodeByteArray(data, 0, data.size)

    private const val MIN_SIDE = 32
    private val QUALITIES = listOf(80, 70, 60, 50, 40)
}
