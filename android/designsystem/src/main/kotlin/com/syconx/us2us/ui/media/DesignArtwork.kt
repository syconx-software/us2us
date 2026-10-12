package com.syconx.us2us.ui.media

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.DesignArtwork
import com.syconx.us2us.domain.harness.DesignArtworkEncoder
import java.io.ByteArrayOutputStream
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Paints the design's CSS-gradient stand-ins for photos and video posters, so fixture chats look
 * exactly like the references (5.1, 5.2, 5.6). Fills its box; decorative.
 */
@Composable
fun DesignArtworkView(artwork: DesignArtwork, modifier: Modifier = Modifier) {
    Canvas(modifier.clearAndSetSemantics {}) { DesignArtworkPainter.paint(this, artwork, pointPx = density) }
}

/** The artwork's drawing, shared by [DesignArtworkView] and [DesignArtworkBitmap]. */
object DesignArtworkPainter {
    /** Paints [artwork] over the whole [scope]; [pointPx] is one design point in px (the lights' radii are in points). */
    fun paint(scope: DrawScope, artwork: DesignArtwork, pointPx: Float) {
        when (artwork) {
            DesignArtwork.NightPier -> scope.paintNightPier(pointPx)
            DesignArtwork.SunsetVideo -> scope.paintSunset()
        }
    }

    private fun rgba(r: Int, g: Int, b: Int, a: Float = 1f) = Color(r / 255f, g / 255f, b / 255f, a)

    /** `linear-gradient(to bottom, stops)`. */
    private fun DrawScope.linear(vararg stops: Pair<Float, Color>) {
        drawRect(Brush.verticalGradient(*stops, startY = 0f, endY = size.height))
    }

    /** `radial-gradient(rx% ry% at cx% cy%, color, transparent end%)`. */
    private fun DrawScope.ellipse(rx: Float, ry: Float, cx: Float, cy: Float, color: Color, end: Float) {
        val w = size.width
        val h = size.height
        translate(w * cx, h * cy) {
            scale(w * rx, h * ry, pivot = Offset.Zero) {
                drawCircle(
                    Brush.radialGradient(0f to color, end to color.copy(alpha = 0f), center = Offset.Zero, radius = 1f),
                    radius = 1f,
                    center = Offset.Zero,
                )
            }
        }
    }

    /** A light: solid to `r` points, fading out at `r + 1` (`radial-gradient(circle at x y, C r, transparent r+1)`). */
    private fun DrawScope.light(x: Float, y: Float, r: Float, color: Color, pointPx: Float) {
        val center = Offset(size.width * x, size.height * y)
        val edge = (r + 1f) * pointPx
        drawCircle(
            Brush.radialGradient(0f to color, r / (r + 1f) to color, 1f to color.copy(alpha = 0f), center = center, radius = edge),
            radius = edge,
            center = center,
        )
    }

    /** 5.1 “Şu yeni yer, Moda'da”: a night pier with lights. */
    private fun DrawScope.paintNightPier(pointPx: Float) {
        linear(
            0f to rgba(28, 30, 62),
            0.44f to rgba(46, 43, 94),
            0.56f to rgba(70, 58, 108),
            0.58f to rgba(21, 22, 42),
            1f to rgba(13, 14, 25),
        )
        ellipse(rx = 0.40f, ry = 0.30f, cx = 0.74f, cy = 0.22f, color = rgba(220, 214, 255, 0.35f), end = 0.7f)
        ellipse(rx = 1.30f, ry = 0.30f, cx = 0.50f, cy = 0.57f, color = rgba(255, 190, 140, 0.22f), end = 0.7f)
        light(0.16f, 0.57f, 1.4f, rgba(255, 210, 150, 0.95f), pointPx)
        light(0.24f, 0.55f, 1.1f, rgba(255, 222, 172, 0.9f), pointPx)
        light(0.37f, 0.58f, 1.6f, rgba(255, 200, 140, 0.9f), pointPx)
        light(0.49f, 0.56f, 1.1f, rgba(200, 206, 255, 0.85f), pointPx)
        light(0.63f, 0.54f, 1.8f, rgba(255, 214, 160, 0.95f), pointPx)
        light(0.77f, 0.57f, 1.2f, rgba(255, 226, 182, 0.85f), pointPx)
        light(0.88f, 0.55f, 1.4f, rgba(255, 205, 150, 0.9f), pointPx)
    }

    /** 5.2 video poster: a warm sunset glow. */
    private fun DrawScope.paintSunset() {
        linear(
            0f to rgba(44, 39, 82),
            0.38f to rgba(107, 74, 124),
            0.57f to rgba(199, 122, 114),
            0.59f to rgba(35, 32, 61),
            1f to rgba(18, 18, 34),
        )
        // circle at 62 % 58 %, farthest-corner: stops at 8 / 12 / 30 % of the ray.
        val center = Offset(size.width * 0.62f, size.height * 0.58f)
        val corners = listOf(Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height))
        val ray = corners.maxOf { hypot(it.x - center.x, it.y - center.y) }
        drawRect(
            Brush.radialGradient(
                0f to rgba(255, 214, 170, 0.95f),
                0.08f to rgba(255, 214, 170, 0.95f),
                0.12f to rgba(255, 170, 120, 0.5f),
                0.30f to rgba(255, 170, 120, 0f),
                center = center,
                radius = ray,
            ),
        )
    }
}

/**
 * The artwork as a picture of its own proportions (the 5.1 photo 3 : 2, the 5.2 poster 16 : 9):
 * what the viewer, the player and the fixtures' payloads show (iOS `renderedImage`).
 */
object DesignArtworkBitmap {
    /** The aspect ratio (width / height) the artwork is rendered at. */
    fun aspectRatio(artwork: DesignArtwork): Float = if (artwork == DesignArtwork.SunsetVideo) 16f / 9f else 3f / 2f

    /** [artwork] painted [pixelWidth] wide at 2 px per point, as iOS renders it. */
    fun render(artwork: DesignArtwork, pixelWidth: Int): Bitmap {
        val points = Size((pixelWidth / 2f).roundToInt().toFloat(), (pixelWidth / 2f / aspectRatio(artwork)).roundToInt().toFloat())
        val image = ImageBitmap((points.width * 2).toInt(), (points.height * 2).toInt())
        CanvasDrawScope().draw(Density(2f), LayoutDirection.Ltr, Canvas(image), Size(points.width * 2, points.height * 2)) {
            DesignArtworkPainter.paint(this, artwork, pointPx = 2f)
        }
        return image.asAndroidBitmap()
    }

    /** [artwork] as a JPEG (quality 90), [pixelWidth] wide. */
    fun jpeg(artwork: DesignArtwork, pixelWidth: Int): Bytes {
        val bitmap = render(artwork, pixelWidth)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return Bytes.wrap(out.toByteArray())
    }
}

/**
 * Encodes the design's stand-ins as real files for the fixtures (iOS `FixtureMedia`): the photo as
 * a 1536 px JPEG. The clip is encoded by [video] (MediaCodec lives with the player); without it the
 * design video has no payload.
 */
class BitmapDesignArtworkEncoder(
    private val video: (suspend (DesignArtwork) -> Bytes?)? = null,
) : DesignArtworkEncoder {
    override suspend fun jpeg(artwork: DesignArtwork, pixelWidth: Int): Bytes? =
        withContext(Dispatchers.Default) { DesignArtworkBitmap.jpeg(artwork, pixelWidth) }

    override suspend fun mp4(artwork: DesignArtwork): Bytes? = video?.invoke(artwork)
}
