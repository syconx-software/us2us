package com.syconx.us2us.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import com.syconx.us2us.ui.security.PrivacySnapshot
import com.syconx.us2us.ui.tokens.U2ColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The cover's picture: half a pixel per dp, blurred like CSS `filter: blur()`. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class PrivacySnapshotTest {
    @Test
    fun theBlurSoftensAnEdgeAndKeepsFlatColour() {
        val source = Bitmap.createBitmap(80, 20, Bitmap.Config.ARGB_8888)
        for (x in 0 until 80) for (y in 0 until 20) source.setPixel(x, y, if (x < 40) Color.BLACK else Color.WHITE)
        val blurred = PrivacySnapshot.blur(source, sigma = 4f)
        val red = { x: Int -> Color.red(blurred.getPixel(x, 10)) }
        // Far from the edge: unchanged (edges are clamped, nothing darkens at the borders).
        assertEquals(0, red(2))
        assertEquals(255, red(77))
        // Across the edge: a monotonic ramp around the middle.
        assertTrue(red(36) in 1..127)
        assertTrue(red(43) in 128..254)
        assertTrue((30 until 50).all { red(it) <= red(it + 1) })
        assertEquals(127.5, red(39).plus(red(40)) / 2.0, 12.0)
    }

    @Test
    fun theSkeletonIsHalfAPixelPerDp() {
        val skeleton = PrivacySnapshot.skeleton(U2ColorScheme.Dark).asAndroidBitmap()
        assertEquals(201, skeleton.width)
        assertEquals(437, skeleton.height)
        // The corner is the background (#161826), the first row's avatar is accent800 blurred.
        val corner = skeleton.getPixel(0, 436)
        assertEquals(0x16, Color.red(corner))
        assertEquals(0x18, Color.green(corner))
        assertEquals(0x26, Color.blue(corner))
    }
}
