package com.syconx.us2us.scene3d

import com.syconx.us2us.shot.ShotPaths
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt

/** Frames as premultiplied RGBA bytes, composited and compared like the screen does it. */
internal object SceneImages {
    /** Where the scene's comparison images go (`android/build/shots/android/scene3d/`). */
    val outputDir: File get() = File(ShotPaths.shotsDir, "android/scene3d").apply { mkdirs() }

    /** The theme backgrounds (`bg`). */
    fun background(dark: Boolean): Int = if (dark) 0x161826 else 0xF3F5FE

    /** A premultiplied frame stored byte for byte in a PNG (test resources). */
    fun loadPremultiplied(resource: String): ScenePixels {
        val stream = checkNotNull(SceneImages::class.java.getResourceAsStream(resource)) { "missing $resource" }
        val image = stream.use { ImageIO.read(it) }
        val raster = image.raster
        val rgba = FloatArray(image.width * image.height * 4)
        val px = IntArray(4)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                raster.getPixel(x, y, px)
                for (c in 0 until 4) rgba[(y * image.width + x) * 4 + c] = px[c] / 255f
            }
        }
        return ScenePixels(image.width, image.height, rgba, 1.0)
    }

    /** Premultiplied src-over onto [background], as opaque 0xRRGGBB. */
    fun composite(pixels: ScenePixels, background: Int): IntArray {
        val bg = intArrayOf(background shr 16 and 0xFF, background shr 8 and 0xFF, background and 0xFF)
        return IntArray(pixels.width * pixels.height) { i ->
            val o = i * 4
            val a = pixels.rgba[o + 3]
            var out = 0
            for (c in 0 until 3) {
                val v = (pixels.rgba[o + c] * 255 + bg[c] * (1 - a)).roundToInt().coerceIn(0, 255)
                out = out or (v shl (16 - 8 * c))
            }
            out
        }
    }

    /** Mean |Δ| per channel and the share of pixels with a channel off by more than [threshold]. */
    fun compare(a: IntArray, b: IntArray, threshold: Int = 24): Pair<Double, Double> {
        require(a.size == b.size)
        var sum = 0L
        var over = 0
        for (i in a.indices) {
            var worst = 0
            for (shift in intArrayOf(16, 8, 0)) {
                val d = abs((a[i] shr shift and 0xFF) - (b[i] shr shift and 0xFF))
                sum += d
                if (d > worst) worst = d
            }
            if (worst > threshold) over++
        }
        return sum.toDouble() / (a.size * 3) to over * 100.0 / a.size
    }

    /** Writes [images] side by side (each [width] wide) to [name].png. */
    fun writeSideBySide(name: String, width: Int, height: Int, vararg images: IntArray): File {
        val out = BufferedImage(width * images.size, height, BufferedImage.TYPE_INT_RGB)
        images.forEachIndexed { k, image -> out.setRGB(k * width, 0, width, height, image, 0, width) }
        return File(outputDir, "$name.png").also { ImageIO.write(out, "png", it) }
    }

    /** A heat map of |Δ| ×4. */
    fun diff(a: IntArray, b: IntArray): IntArray = IntArray(a.size) { i ->
        var worst = 0
        for (shift in intArrayOf(16, 8, 0)) worst = maxOf(worst, abs((a[i] shr shift and 0xFF) - (b[i] shr shift and 0xFF)))
        val v = (worst * 4).coerceAtMost(255)
        (v shl 16) or (v / 3 shl 8) or (v / 3)
    }
}
