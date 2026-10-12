package com.syconx.us2us.scene3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame logic and shading against the iOS app's own Metal renderer: `src/test/resources/scene3d/`
 * holds the iOS scene (SceneRenderer.swift) rendered offscreen at the reference times, 804 × 660 px
 * (402 × 330 pt at 2×), premultiplied. The same frame drawn by [SceneRaster] must match it.
 *
 * Writes `android/build/shots/android/scene3d/parity-<theme>.png` (iOS | Android | |Δ| ×4).
 */
class SceneRenderParityTest {
    private fun renderAndCompare(dark: Boolean, time: Double, resource: String): Pair<Double, Double> {
        val frame = SceneFrameBuilder.build(
            SceneState.Welcome, ScenePalette.of(dark), time, time,
            canvasWidth = 402.0, canvasHeight = 330.0, targetWidth = 804f, targetHeight = 660f,
        )
        assertEquals(1.0, frame.opacity, 0.0)
        val android = SceneRaster(804, 660).render(frame)
        val ios = SceneImages.loadPremultiplied(resource)
        val bg = SceneImages.background(dark)
        val a = SceneImages.composite(ios, bg)
        val b = SceneImages.composite(android, bg)
        SceneImages.writeSideBySide("parity-${if (dark) "dark" else "light"}", 804, 660, a, b, SceneImages.diff(a, b))
        return SceneImages.compare(a, b).also { println("scene parity ${if (dark) "dark" else "light"}: mean |Δ| %.3f, %.3f %% over 24".format(java.util.Locale.ROOT, it.first, it.second)) }
    }

    @Test
    fun darkMatchesTheIosRenderer() {
        val (mean, over) = renderAndCompare(dark = true, time = 4.5, resource = "/scene3d/ios-metal-dark-4.5.png")
        assertTrue("mean |Δ| $mean", mean < MAX_MEAN)
        assertTrue("$over % of the pixels off by more than 24", over < MAX_OVER)
    }

    @Test
    fun lightMatchesTheIosRenderer() {
        val (mean, over) = renderAndCompare(dark = false, time = 39.95, resource = "/scene3d/ios-metal-light-39.95.png")
        assertTrue("mean |Δ| $mean", mean < MAX_MEAN)
        assertTrue("$over % of the pixels off by more than 24", over < MAX_OVER)
    }

    private companion object {
        const val MAX_MEAN = 1.0
        const val MAX_OVER = 1.0
    }
}
