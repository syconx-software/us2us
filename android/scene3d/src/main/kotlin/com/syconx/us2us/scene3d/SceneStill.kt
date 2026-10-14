// us2us — still frames of the welcome scene for environments without OpenGL
// (JVM screenshot tests, layout previews): rendered once by SceneRaster and
// composited like the GPU surface is.

package com.syconx.us2us.scene3d

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.roundToInt

internal object SceneStills {
    /** Robolectric runs no GPU; the scene is drawn from [SceneRaster] there. */
    val isGpuAvailable: Boolean by lazy { Build.FINGERPRINT != "robolectric" }

    /** Time of the still when the scene is not pinned (the reduce-motion still frame). */
    const val UNPINNED_TIME = Us2usSceneDefaults.STILL_FRAME_TIME

    /**
     * A rendered still as two images that composite like a premultiplied GPU surface (which may
     * hold more light than alpha, as additive glows do): [coverage] removes `alpha` of what is
     * behind (destination-out), [light] adds the colour (plus).
     */
    class Still(val coverage: ImageBitmap, val light: ImageBitmap, val opacity: Double)

    private data class Key(val content: SceneContent, val size: IntSize, val density: Float)

    private val cache = object : LinkedHashMap<Key, Still>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Still>?): Boolean = size > 4
    }

    /** The still of [content] for a view of [size] px at [density], rendered at most 2 px per point like the surface. */
    fun still(content: SceneContent, size: IntSize, density: Float): Still = synchronized(cache) {
        cache.getOrPut(Key(content, size, density)) { render(content, size, density) }
    }

    private fun render(content: SceneContent, size: IntSize, density: Float): Still {
        val canvasWidth = size.width / density.toDouble()
        val canvasHeight = size.height / density.toDouble()
        val scale = density.coerceIn(1f, 2f)
        val width = max(1, (canvasWidth * scale).roundToInt())
        val height = max(1, (canvasHeight * scale).roundToInt())
        val time = content.frozenTime ?: UNPINNED_TIME
        val frame = SceneFrameBuilder.build(
            content.state, content.palette, time, time,
            canvasWidth = canvasWidth, canvasHeight = canvasHeight,
            targetWidth = width.toFloat(), targetHeight = height.toFloat(),
        )
        val pixels = SceneRaster(width, height).render(frame)
        return Still(
            coverage = bitmap(pixels) { _, _, _, a -> argb(a, 0.0, 0.0, 0.0) },
            light = bitmap(pixels) { r, g, b, a ->
                val alpha = maxOf(a, r, g, b)
                if (alpha <= 0) 0 else argb(alpha, r / alpha, g / alpha, b / alpha)
            },
            opacity = pixels.opacity,
        )
    }

    private inline fun bitmap(pixels: ScenePixels, color: (Double, Double, Double, Double) -> Int): ImageBitmap {
        val argb = IntArray(pixels.width * pixels.height) { i ->
            val o = i * 4
            color(pixels.rgba[o].toDouble(), pixels.rgba[o + 1].toDouble(), pixels.rgba[o + 2].toDouble(), pixels.rgba[o + 3].toDouble())
        }
        val bitmap = Bitmap.createBitmap(pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(argb, 0, pixels.width, 0, 0, pixels.width, pixels.height)
        return bitmap.asImageBitmap()
    }

    private fun argb(a: Double, r: Double, g: Double, b: Double): Int {
        fun c(v: Double) = (v.coerceIn(0.0, 1.0) * 255).roundToInt()
        return (c(a) shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }
}

/** The scene as a still image, with the canvas [alpha] the GPU view would have. */
@Composable
internal fun SceneStill(
    content: SceneContent,
    alpha: () -> Float,
    onFirstFrame: (opacity: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(IntSize.Zero) }
    val still = remember(content, size, density) {
        if (size.width > 0 && size.height > 0) SceneStills.still(content, size, density) else null
    }
    LaunchedEffect(still) {
        still?.let { onFirstFrame(it.opacity) }
    }
    Canvas(modifier.onSizeChanged { size = it }) {
        val image = still ?: return@Canvas
        val a = alpha()
        if (a <= 0f) return@Canvas
        val target = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt())
        drawImage(image.coverage, dstOffset = IntOffset.Zero, dstSize = target, alpha = a, blendMode = BlendMode.DstOut, filterQuality = FilterQuality.Low)
        drawImage(image.light, dstOffset = IntOffset.Zero, dstSize = target, alpha = a, blendMode = BlendMode.Plus, filterQuality = FilterQuality.Low)
    }
}
