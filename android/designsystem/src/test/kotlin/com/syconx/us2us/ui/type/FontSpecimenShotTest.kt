package com.syconx.us2us.ui.type

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.Shots
import com.syconx.us2us.ui.tokens.U2FontFamily
import com.syconx.us2us.ui.tokens.U2TextStyle
import com.syconx.us2us.ui.tokens.U2TextStyles
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Specimens of every type style (dark and light) and of each variable-font axis, proving the
 * axes render: Doto's ROND, Martian Mono's wdth and Inter's opsz change the drawn glyphs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class FontSpecimenShotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w402dp-h2700dp-xxhdpi")
    fun everyStyleDark() = styles(dark = true)

    @Test
    @Config(qualifiers = "w402dp-h2700dp-xxhdpi")
    fun everyStyleLight() = styles(dark = false)

    private fun styles(dark: Boolean) {
        compose.setContent {
            U2Theme(dark = dark, reduceMotion = false) {
                Column(
                    Modifier.fillMaxSize().background(U2Theme.colors.bg).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for (style in U2TextStyles.all) {
                        U2Text(
                            text = sample(style),
                            style = style,
                            color = if (style.family == U2FontFamily.Doto) U2Theme.colors.accent300 else U2Theme.colors.text,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
            }
        }
        Shots.capture(compose, "type-specimen-${if (dark) "dark" else "light"}")
    }

    private fun sample(style: U2TextStyle): String = when (style.family) {
        U2FontFamily.Doto -> "09:41 us2us"
        U2FontFamily.MartianMono -> "${style.name} 1 / 3 deniz"
        U2FontFamily.Inter -> "${style.name} · Bu akşam boş musun? İı Şş"
    }

    /** Rows of the axis specimen: (label, family, variation settings) drawn at fixed heights. */
    private val axisRows: List<Triple<String, Int, FontVariation.Settings>> = listOf(
        Triple("Doto ROND 100 (design)", U2Fonts.resource(U2FontFamily.Doto), U2Fonts.variationSettings(U2FontFamily.Doto, 900)),
        Triple("Doto ROND 0", U2Fonts.resource(U2FontFamily.Doto), FontVariation.Settings(FontVariation.weight(900), FontVariation.Setting("ROND", 0f))),
        Triple("Martian Mono wdth 100 (design)", U2Fonts.resource(U2FontFamily.MartianMono), U2Fonts.variationSettings(U2FontFamily.MartianMono, 400)),
        Triple("Martian Mono wdth 112.5", U2Fonts.resource(U2FontFamily.MartianMono), FontVariation.Settings(FontVariation.weight(400), FontVariation.width(112.5f))),
        Triple("Inter opsz 14 (design)", U2Fonts.resource(U2FontFamily.Inter), U2Fonts.variationSettings(U2FontFamily.Inter, 400)),
        Triple("Inter opsz 32", U2Fonts.resource(U2FontFamily.Inter), FontVariation.Settings(FontVariation.weight(400), FontVariation.Setting("opsz", 32f))),
    )

    private val rowHeight = 120.dp

    @Test
    fun variationAxesRender() {
        val samples = listOf("09:41", "09:41", "1 / 3 deniz", "1 / 3 deniz", "Bu akşam boş musun?", "Bu akşam boş musun?")
        val sizes = listOf(64, 64, 24, 24, 28, 28)
        compose.setContent {
            U2Theme(dark = true, reduceMotion = false) {
                Column(Modifier.fillMaxSize().background(U2Theme.colors.bg).padding(horizontal = 16.dp)) {
                    axisRows.forEachIndexed { i, (label, res, settings) ->
                        Box(Modifier.fillMaxWidth().height(rowHeight)) {
                            Column {
                                U2Text(label, U2TextStyles.monoMeta, color = U2Theme.colors.textSecondary)
                                AxisSample(samples[i], res, settings, sizes[i])
                            }
                        }
                    }
                    U2Text("Bu mesaj herkesten silindi", U2TextStyles.bubbleTombstone, color = U2Theme.colors.textSecondary)
                    U2Text("18:10 · 11:11 (tnum)", U2TextStyles.caption, color = U2Theme.colors.text)
                    U2Text("18:10 · 11:11 (pnum)", U2TextStyles.caption.proportional, color = U2Theme.colors.text)
                }
            }
        }
        val file = Shots.capture(compose, "type-axes")
        val image = ImageIO.read(file)
        val rowPx = 120 * 3
        // Each axis value changes the drawn glyphs: the design row differs from its neighbour.
        for (pair in 0 until 3) {
            val design = image.row(2 * pair, rowPx)
            val other = image.row(2 * pair + 1, rowPx)
            val changed = design.indices.count { design[it] != other[it] }
            assertTrue("${axisRows[2 * pair].first} vs ${axisRows[2 * pair + 1].first}: $changed pixels differ", changed > 500)
        }
    }

    private fun BufferedImage.row(index: Int, rowPx: Int): IntArray {
        // Skip the label (top 60 px of the row), keep the sample.
        val top = index * rowPx + 60
        return getRGB(48, top, width - 96, rowPx - 60, null, 0, width - 96)
    }

    @OptIn(ExperimentalTextApi::class)
    @Composable
    private fun AxisSample(text: String, res: Int, settings: FontVariation.Settings, size: Int) {
        val family = FontFamily(Font(res, FontWeight.Normal, variationSettings = settings))
        val style = U2TextStyles.bodyL.textStyle().copy(
            fontFamily = family,
            fontWeight = FontWeight.Normal,
            fontSize = with(LocalDensity.current) { size.dp.toSp() },
            lineHeight = with(LocalDensity.current) { (size * 1.3f).dp.toSp() },
            color = Color(0xFFD2CEFD),
        )
        BasicText(text, style = style, maxLines = 1, softWrap = false)
    }
}
