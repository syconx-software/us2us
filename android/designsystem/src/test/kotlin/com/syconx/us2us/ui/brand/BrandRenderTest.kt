package com.syconx.us2us.ui.brand

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.syconx.us2us.domain.sticker.BrandStickerSelection
import com.syconx.us2us.domain.sticker.StickerCatalog
import com.syconx.us2us.domain.sticker.StickerLanguage
import com.syconx.us2us.shot.ShotHost
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The art as pixels: every sticker and preset draws, words follow the language, the paper is solid. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class BrandRenderTest {
    private fun selection(id: Int, language: StickerLanguage = StickerLanguage.Turkish) =
        BrandStickerSelection(requireNotNull(StickerCatalog.sticker(id)), language)

    private fun alpha(pixel: Int) = (pixel ushr 24) and 0xFF

    @Test
    fun dotoIsMonospacedAsTheFittingAssumes() {
        val type = StickerTypesetter(BrandShots.measurer())
        for (word in listOf("Merhaba!", "Teşekkürler", "İyi geceler", "Şşş")) {
            val layout = type.layout(word, tracking = 0f)
            val width = layout.getLineRight(0) - layout.getLineLeft(0)
            assertEquals(word, BrandStickerType.length(word) * BrandStickerType.ADVANCE * BrandStickerType.REFERENCE_SIZE, width, 0.5f)
        }
        // Tracking goes between the glyphs.
        val tracked = type.layout("Merhaba!", tracking = -0.02f)
        assertEquals(8 * 60f - 7 * 2f, tracked.getLineRight(0) - tracked.getLineLeft(0), 0.5f)
    }

    @Test
    fun everyStickerDrawsOnItsPaperInsideItsBox() {
        for (sticker in StickerCatalog.all) {
            val bitmap = BrandShots.sticker(BrandStickerSelection(sticker, StickerLanguage.Turkish), dark = true, side = 200)
            val pixels = BrandShots.pixels(bitmap)
            val opaque = pixels.count { alpha(it) == 0xFF }
            // The paper covers a good part of the box, never all of it.
            assertTrue("${sticker.key}: $opaque opaque px", opaque in 8_000..36_000)
            // The ink outline is there.
            val ink = pixels.count { alpha(it) == 0xFF && closeTo(it, 0x2b2741) }
            assertTrue("${sticker.key}: $ink ink px", ink > 300)
            // Roughly centred: the opaque area's centroid is near the middle (the cups stand low).
            var sx = 0L
            var sy = 0L
            pixels.forEachIndexed { index, pixel -> if (alpha(pixel) == 0xFF) { sx += index % 200; sy += index / 200 } }
            val cx = sx / opaque.toFloat()
            val cy = sy / opaque.toFloat()
            assertTrue("${sticker.key} centred at $cx, $cy", abs(cx - 100) < 30 && abs(cy - 100) < 36)
        }
    }

    @Test
    fun wordsAreDrawnInTheLanguageTheyWereSentIn() {
        for (sticker in StickerCatalog.all) {
            val turkish = BrandShots.pixels(BrandShots.sticker(BrandStickerSelection(sticker, StickerLanguage.Turkish), dark = true, side = 160))
            val english = BrandShots.pixels(BrandShots.sticker(BrandStickerSelection(sticker, StickerLanguage.English), dark = true, side = 160))
            val differs = !turkish.contentEquals(english)
            val lines = sticker.lines
            if (lines == null || lines.turkish == lines.english) {
                assertFalse("${sticker.key} has no words of its own in English", differs)
            } else {
                assertTrue("${sticker.key} draws its English words", differs)
            }
        }
    }

    @Test
    fun theThemeChangesOnlyWhatLiesAroundTheSticker() {
        val dark = BrandShots.sticker(selection(1), dark = true)
        val light = BrandShots.sticker(selection(1), dark = false)
        assertFalse(BrandShots.pixels(dark).contentEquals(BrandShots.pixels(light)))
        // The cup's body is printed in the same ink in both themes.
        assertEquals(dark.getPixel(150, 170) and 0xFFFFFF, light.getPixel(150, 170) and 0xFFFFFF)
    }

    @Test
    fun smallDotsKeepSolidPaper() {
        // 202's bottom row: dots of radius 1.15 with 3.6 units of paper round them (no ring of background).
        val bitmap = BrandShots.sticker(selection(202), dark = true, side = 400)
        val pixels = BrandShots.pixels(bitmap)
        val unit = 4f
        // The dot at (51.5, 93) before the 5° lean, 2 units to its side: paper.
        val tilt = Math.toRadians(5.0)
        val dx = 51.5 + 2 - 50
        val dy = 93.0 - 50
        val x = (50 + dx * kotlin.math.cos(tilt) - dy * kotlin.math.sin(tilt)) * unit
        val y = (50 + dx * kotlin.math.sin(tilt) + dy * kotlin.math.cos(tilt)) * unit
        val pixel = pixels[y.toInt() * 400 + x.toInt()]
        assertEquals("paper at the dot's side", 0xFF, alpha(pixel))
    }

    @Test
    fun everyPresetDrawsInItsDiscAndSmallOnesDropTheExtras() {
        fun render(preset: BrandAvatarPreset, sidePx: Int, diameterDp: Float, dark: Boolean = true): Bitmap {
            val image = ImageBitmap(sidePx, sidePx)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(sidePx.toFloat(), sidePx.toFloat())) {
                renderBrandAvatar(preset, dark, diameterDp)
            }
            return image.asAndroidBitmap()
        }
        for (preset in BrandAvatarCatalog.all) {
            val pixels = BrandShots.pixels(render(preset, 120, 120f))
            // The corners stay clear (a circle), the centre is drawn.
            assertEquals(preset.key, 0, alpha(pixels[0]))
            assertEquals(preset.key, 0, alpha(pixels[119]))
            assertEquals(preset.key, 0xFF, alpha(pixels[60 * 120 + 60]))
            // The cup's ink outline is on the disc.
            assertTrue(preset.key, pixels.count { closeTo(it, 0x2b2741) } > 100)
        }
        // Preset 1's sparkles are drawn from 40 dp on.
        val classic = requireNotNull(BrandAvatarCatalog.preset(1))
        assertFalse(BrandShots.pixels(render(classic, 120, 38f)).contentEquals(BrandShots.pixels(render(classic, 120, 40f))))
        // The light theme's pale disc of the ink preset gets a hairline.
        val ink = requireNotNull(BrandAvatarCatalog.preset(8))
        assertFalse(BrandShots.pixels(render(ink, 120, 120f, dark = false)).contentEquals(BrandShots.pixels(render(ink, 120, 120f, dark = true))))
    }

    @Test
    fun continuousCornersStayInsideTheirRectangle() {
        val rect = rect(10, 20, 80, 50)
        val bounds = StickerShape.rounded(rect, 16).getBounds()
        assertEquals(rect.left, bounds.left, 0.01f)
        assertEquals(rect.top, bounds.top, 0.01f)
        assertEquals(rect.right, bounds.right, 0.01f)
        assertEquals(rect.bottom, bounds.bottom, 0.01f)
        // A pill: the radius is limited so the corners meet.
        val pill = StickerShape.rounded(rect(0, 0, 41, 7.5), 3.75).getBounds()
        assertEquals(41f, pill.width, 0.01f)
        assertEquals(7.5f, pill.height, 0.01f)
    }

    @Test
    fun tangentArcsMeetBothLines() {
        // A 90° corner of radius 4: the arc starts 4 before the corner and ends 4 after it.
        val path = PathBuilder().moveTo(Offset(0f, 0f)).arcTangent(Offset(20f, 0f), Offset(20f, 20f), 4).lineTo(Offset(20f, 20f)).path
        val bounds = path.getBounds()
        assertEquals(20f, bounds.right, 0.01f)
        val measure = android.graphics.PathMeasure(path.asAndroidPath(), false)
        // 16 straight + a quarter circle of radius 4 + 16 straight.
        assertEquals(32f + (Math.PI * 2 * 4 / 4).toFloat(), measure.length, 0.3f)
    }

    private fun closeTo(pixel: Int, rgb: Int, tolerance: Int = 6): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return abs(r - ((rgb shr 16) and 0xFF)) <= tolerance && abs(g - ((rgb shr 8) and 0xFF)) <= tolerance && abs(b - (rgb and 0xFF)) <= tolerance
    }
}
