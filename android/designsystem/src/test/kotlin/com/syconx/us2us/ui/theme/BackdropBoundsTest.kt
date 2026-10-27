package com.syconx.us2us.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class BackdropBoundsTest {
    @Test fun aHeaderDoesNotAllocateAWholeScreenBlur() {
        val source = IntSize(1206, 2622)
        val radius = U2Effects.blurRadiusForSigma(60f)
        val sample = backdropSampleBounds(source, Size(1206f, 324f), Offset.Zero, 3 * radius)!!
        assertEquals(0, sample.top)
        assertEquals(source.width, sample.width)
        assertTrue(sample.bottom >= 324 + 3 * radius)
        assertTrue("at least 60% fewer blurred pixels for the header", sample.height < source.height * .4)
    }
    @Test fun translatedSurfacesKeepTheirSamplingHalo() {
        assertEquals(IntRect(180, 280, 320, 420),
            backdropSampleBounds(IntSize(500, 800), Size(100f, 100f), Offset(200f, 300f), 20f))
    }
    @Test fun sourceEdgesClampAndFractionalCoordinatesRoundOutward() {
        assertEquals(IntRect(0, 0, 121, 121),
            backdropSampleBounds(IntSize(500, 800), Size(100f, 100f), Offset(.5f, .5f), 20f))
        assertEquals(IntRect(380, 680, 500, 800),
            backdropSampleBounds(IntSize(500, 800), Size(100f, 100f), Offset(400f, 700f), 20f))
    }
    @Test fun completelyOffscreenSurfacesHaveNothingToBlur() {
        assertNull(backdropSampleBounds(IntSize(500, 800), Size(100f, 100f), Offset(600f, 900f), 20f))
    }
}
