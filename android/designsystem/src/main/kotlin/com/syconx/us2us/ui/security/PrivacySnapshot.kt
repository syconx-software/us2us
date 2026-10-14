package com.syconx.us2us.ui.security

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2ColorScheme
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The blurred picture of the current screen under the 3.4 cover: the window drawn at half a pixel
 * per dp (iOS renders half a pixel per point) and blurred with σ 13 dp (CSS blur 9 on the 0.70
 * thumbnail), in encoded sRGB like the browser's `filter: blur()`.
 */
object PrivacySnapshot {
    /** Pixels per dp of the snapshot. */
    const val PIXELS_PER_DP = 0.5f

    /** The blur's standard deviation in dp. */
    const val SIGMA_DP = 13f

    /**
     * The window under [view] (its root), blurred; `null` if it cannot be drawn (no size yet,
     * out of memory). Synchronous: it runs before the cover is put up.
     */
    fun capture(view: View): ImageBitmap? {
        val root = view.rootView ?: return null
        if (root.width <= 0 || root.height <= 0) return null
        val density = root.resources.displayMetrics.density
        val scale = PIXELS_PER_DP / density
        val width = (root.width * scale).roundToInt().coerceAtLeast(1)
        val height = (root.height * scale).roundToInt().coerceAtLeast(1)
        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            root.draw(canvas)
            blur(bitmap, SIGMA_DP * PIXELS_PER_DP).asImageBitmap()
        } catch (failure: OutOfMemoryError) {
            null
        } catch (failure: RuntimeException) {
            // A view that cannot draw into a software canvas: the solid cover instead.
            null
        }
    }

    /**
     * The abstract chat list the design blurs under the cover (`r.3.0` of 3.4, drawn at full size:
     * thumbnail values × 1 / 0.7015), blurred like a capture. For the harness states and shots.
     */
    fun skeleton(colors: U2ColorScheme, widthDp: Float = 402f, heightDp: Float = 874f): ImageBitmap {
        val s = PIXELS_PER_DP
        val k = COVER_SCALE
        val bitmap = Bitmap.createBitmap((widthDp * s).roundToInt(), (heightDp * s).roundToInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(colors.bg.toArgb())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun rect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Color) {
            paint.color = color.toArgb()
            canvas.drawRoundRect(RectF(x * s, y * s, (x + w) * s, (y + h) * s), r * s, r * s, paint)
        }
        val left = 18f * k
        var y = 64f * k
        rect(left, y, 127.91f * k, 22f * k, 6f * k, colors.text.copy(alpha = 0.45f))
        y += 22f * k + 22f * k
        repeat(7) {
            paint.color = colors.accent800.toArgb()
            val d = 38f * k
            canvas.drawCircle((left + d / 2f) * s, (y + d / 2f) * s, d / 2f * s, paint)
            val textLeft = left + d + 12f * k
            val top = y + (d - 24f * k) / 2f
            rect(textLeft, top, 90.16f * k, 9f * k, 5f * k, colors.text.copy(alpha = 0.40f))
            rect(textLeft, top + 9f * k + 7f * k, 152.88f * k, 8f * k, 4f * k, colors.text.copy(alpha = 0.22f))
            y += d + 22f * k
        }
        return blur(bitmap, SIGMA_DP * s).asImageBitmap()
    }

    /**
     * A Gaussian blur of [source] with standard deviation [sigma] (pixels), edges clamped, on the
     * encoded channel values: three box blurs, which match a Gaussian to the eye. Returns a new
     * bitmap.
     */
    fun blur(source: Bitmap, sigma: Float): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val scratch = IntArray(pixels.size)
        for (radius in boxRadii(sigma)) {
            boxBlur(pixels, scratch, width, height, radius, horizontal = true)
            boxBlur(scratch, pixels, width, height, radius, horizontal = false)
        }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    /** The radii of three box blurs whose sequence has the variance of a Gaussian with [sigma]. */
    internal fun boxRadii(sigma: Float, passes: Int = 3): List<Int> {
        if (sigma <= 0f) return emptyList()
        val ideal = sqrt(12f * sigma * sigma / passes + 1f)
        var lower = floor(ideal).toInt()
        if (lower % 2 == 0) lower--
        val upper = lower + 2
        val m = ((12f * sigma * sigma - passes * lower * lower - 4f * passes * lower - 3f * passes) / (-4f * lower - 4f)).roundToInt()
        return List(passes) { pass -> ((if (pass < m) lower else upper) - 1) / 2 }
    }

    /** One box blur pass of [radius] along a row ([horizontal]) or a column, from [input] to [output]. */
    private fun boxBlur(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
        if (radius <= 0) {
            input.copyInto(output)
            return
        }
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val window = 2 * radius + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val clamped = i.coerceIn(0, length - 1)
                return if (horizontal) input[line * width + clamped] else input[clamped * width + line]
            }
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val c = at(i)
                a += c ushr 24
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
            }
            for (i in 0 until length) {
                val index = if (horizontal) line * width + i else i * width + line
                output[index] = ((a + radius) / window shl 24) or
                    ((r + radius) / window shl 16) or
                    ((g + radius) / window shl 8) or
                    ((b + radius) / window)
                val outgoing = at(i - radius)
                val incoming = at(i + radius + 1)
                a += (incoming ushr 24) - (outgoing ushr 24)
                r += ((incoming shr 16) and 0xFF) - ((outgoing shr 16) and 0xFF)
                g += ((incoming shr 8) and 0xFF) - ((outgoing shr 8) and 0xFF)
                b += (incoming and 0xFF) - (outgoing and 0xFF)
            }
        }
    }
}

/** The blurred design skeleton for the current theme (the `lock:cover` / `lock:switcher` states and shots). */
@Composable
fun rememberPrivacySkeletonSnapshot(): ImageBitmap {
    val colors = U2Theme.colors
    return remember(colors) { PrivacySnapshot.skeleton(colors) }
}
