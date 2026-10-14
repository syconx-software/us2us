package com.syconx.us2us.feature.settings.avatar

import com.syconx.us2us.domain.CropRect
import com.syconx.us2us.feature.settings.avatar.AvatarCropGeometry.Vector
import kotlin.math.abs
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The framing screen's arithmetic (iOS AvatarCropGeometryTests). */
class AvatarCropGeometryTest {
    private val landscape = AvatarCropGeometry(2_000.0, 1_000.0, 300.0)

    @Test
    fun atZoomOneTheShorterSideFillsTheSquareCentred() {
        assertEquals(0.3, landscape.baseScale, 1e-9)
        val displayed = landscape.displayedSize(1.0)
        assertEquals(600.0, displayed.x, 1e-6)
        assertEquals(300.0, displayed.y, 1e-6)
        assertEquals(CropRect(500.0, 0.0, 1_000.0, 1_000.0), landscape.cropRect(1.0, Vector.Zero))
        val portrait = AvatarCropGeometry(900.0, 1_600.0, 320.0)
        assertEquals(CropRect(0.0, 350.0, 900.0, 900.0), portrait.cropRect(1.0, Vector.Zero))
    }

    @Test
    fun thePhotoOnlyMovesAsFarAsItStillCoversTheSquare() {
        val clamped = landscape.clampedOffset(Vector(1_000.0, 50.0), 1.0)
        assertEquals(150.0, clamped.x, 1e-6)
        assertEquals(0.0, clamped.y, 1e-6)
        assertEquals(0.0, landscape.cropRect(1.0, Vector(1_000.0, 0.0)).x, 0.0)
        val right = landscape.cropRect(1.0, Vector(-1_000.0, 0.0))
        assertEquals(2_000.0, right.x + right.width, 0.0)
        assertEquals(Vector.Zero, landscape.clampedOffset(Vector(Double.NaN, Double.POSITIVE_INFINITY), 1.0))
    }

    @Test
    fun zoomingInFramesASmallerSquareNeverBelowTheMinimum() {
        assertEquals(6.0, landscape.maxZoom, 0.0)
        assertEquals(CropRect(750.0, 250.0, 500.0, 500.0), landscape.cropRect(2.0, Vector.Zero))
        val deepest = landscape.cropRect(40.0, Vector.Zero)
        assertEquals(166.0, deepest.width, 0.0)
        assertEquals(deepest.width, deepest.height, 0.0)
        assertEquals(1.0, landscape.clampedZoom(0.2), 0.0)
        assertEquals(1.0, landscape.clampedZoom(Double.NaN), 0.0)
        val small = AvatarCropGeometry(120.0, 100.0, 300.0)
        assertTrue(small.maxZoom >= 1)
        assertTrue(small.cropRect(small.maxZoom, Vector.Zero).width >= AvatarCropGeometry.MINIMUM_CROP_PIXELS)
    }

    @Test
    fun everyFramingStaysInsideThePhotoInWholePixels() {
        var zoom = 1.0
        while (zoom <= 6.0) {
            var dx = -900.0
            while (dx <= 900.0) {
                var dy = -900.0
                while (dy <= 900.0) {
                    val rect = landscape.cropRect(zoom, Vector(dx, dy))
                    assertTrue(rect.x >= 0 && rect.y >= 0)
                    assertTrue(rect.x + rect.width <= 2_000 && rect.y + rect.height <= 1_000)
                    assertEquals(rect.width, rect.height, 0.0)
                    assertTrue(rect.x == floor(rect.x) && rect.width == floor(rect.width))
                    dy += 300.0
                }
                dx += 300.0
            }
            zoom += 0.7
        }
    }

    @Test
    fun accessibilityStepsZoomWithinTheLimits() {
        val up = landscape.steppedZoom(1.0, 1)
        assertTrue(up > 1)
        assertTrue(abs(landscape.steppedZoom(up, -1) - 1.0) < 1e-9)
        assertEquals(1.0, landscape.steppedZoom(1.0, -1), 0.0)
        assertEquals(landscape.maxZoom, landscape.steppedZoom(landscape.maxZoom, 1), 0.0)
    }
}
