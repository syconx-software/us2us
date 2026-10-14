package com.syconx.us2us.shot

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric's native graphics draw what the screens need: text through Minikin and Skia,
 * gradients, rounded shapes with shadows and RenderEffect blur, at 1206 × 2622 px.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class NativeGraphicsSmokeTest {
    @get:Rule
    val host = ShotHost(outputDir = File(ShotPaths.shotsDir, "smoke"))

    @Test
    fun drawsTextShapesGradientsAndBlur() {
        val background = Color(0xFF161826)
        val file = host.shoot("native-graphics", ShotTheme.Dark, "tr") {
            Box(Modifier.fillMaxSize().background(background)) {
                BasicText(
                    "Gizli kodunu gir",
                    Modifier.offset(24.dp, 100.dp),
                    style = TextStyle(color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black),
                )
                Box(
                    Modifier.offset(24.dp, 200.dp).size(354.dp, 54.dp)
                        .shadow(12.dp, RoundedCornerShape(27.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xFF8C7FF5), Color(0xFF52479B))), RoundedCornerShape(27.dp)),
                )
                Box(Modifier.offset(24.dp, 320.dp).size(100.dp).blur(12.dp, BlurredEdgeTreatment.Unbounded).background(Color.White))
            }
        }
        val image = ImageIO.read(file)
        assertEquals(1206, image.width)
        assertEquals(2622, image.height)

        // Text: light glyph pixels inside the first line's box, background around it.
        val textPixels = count(image, 72, 300, 600, 390) { luminance(it) > 200 }
        assertTrue("text pixels: $textPixels", textPixels > 2_000)
        assertEquals(background.toArgb(), image.getRGB(1100, 345))

        // Gradient: the button's left end is lighter than its right end; the corners are rounded.
        val left = image.getRGB(100, 681)
        val right = image.getRGB(1100, 681)
        assertTrue("gradient ${hex(left)} → ${hex(right)}", luminance(left) > luminance(right) + 20)
        assertTrue("rounded corner ${hex(image.getRGB(73, 601))}", luminance(image.getRGB(73, 601)) < 60)

        // Blur (RenderEffect on API 31+): the square's edge is soft, not a hard step.
        if (Build.VERSION.SDK_INT >= 31) {
            val ramp = (20..180).map { luminance(image.getRGB(it, 1110)) }
            val steps = ramp.zipWithNext().count { (a, b) -> b > a }
            assertTrue("blur ramp $ramp", steps > 10)
        }
    }

    private fun Color.toArgb(): Int = (alpha * 255 + 0.5f).toInt() shl 24 or
        ((red * 255 + 0.5f).toInt() shl 16) or ((green * 255 + 0.5f).toInt() shl 8) or (blue * 255 + 0.5f).toInt()

    private fun luminance(argb: Int): Int = ((argb shr 16 and 0xFF) * 299 + (argb shr 8 and 0xFF) * 587 + (argb and 0xFF) * 114) / 1000

    private fun hex(argb: Int) = "#%08x".format(argb)

    private fun count(image: BufferedImage, x0: Int, y0: Int, x1: Int, y1: Int, predicate: (Int) -> Boolean): Int {
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) if (predicate(image.getRGB(x, y))) n++
        return n
    }
}
