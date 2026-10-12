package com.syconx.us2us.ui.tokens

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max

/** JVM screenshots of this module's specimens, and a region compare against the iOS references. */
object Shots {
    /** Captures the whole compose root into android/build/shots/android/designsystem/[name].png. */
    @OptIn(ExperimentalRoborazziApi::class)
    fun capture(rule: ComposeContentTestRule, name: String): File {
        val file = DesignSpec.shotFile("$name.png")
        rule.onRoot().captureRoboImage(file, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        return file
    }

    /** How far a region of a shot is from the same region of a reference (both 1206 × 2622 px). */
    data class Diff(val meanAbsolute: Double, val pixelsOver16: Double, val maxDelta: Int) {
        override fun toString() = "mean |Δ| %.2f, %.2f %% of pixels off by > 16, max Δ %d".format(meanAbsolute, pixelsOver16 * 100, maxDelta)
    }

    /**
     * Compares [region] (x, y, w, h in px) of [shot] with [reference] and writes a side-by-side
     * (reference left, Android middle, amplified difference right) next to the shot.
     */
    fun compare(shot: File, reference: File, region: IntArray, name: String): Diff {
        val a = ImageIO.read(reference)
        val b = ImageIO.read(shot)
        val (x0, y0, w, h) = region.toList()
        val side = BufferedImage(w * 3, h, BufferedImage.TYPE_INT_RGB)
        var sum = 0L
        var over = 0L
        var maxDelta = 0
        for (y in 0 until h) for (x in 0 until w) {
            val pa = a.getRGB(x0 + x, y0 + y)
            val pb = b.getRGB(x0 + x, y0 + y)
            var delta = 0
            for (shift in intArrayOf(16, 8, 0)) delta = max(delta, abs(((pa shr shift) and 0xFF) - ((pb shr shift) and 0xFF)))
            sum += delta
            if (delta > 16) over++
            maxDelta = max(maxDelta, delta)
            side.setRGB(x, y, pa)
            side.setRGB(w + x, y, pb)
            val d = (delta * 4).coerceAtMost(255)
            side.setRGB(2 * w + x, y, (d shl 16) or (d shl 8) or d)
        }
        ImageIO.write(side, "png", DesignSpec.shotFile("$name-compare.png"))
        val n = (w * h).toDouble()
        return Diff(sum / n, over / n, maxDelta)
    }
}
