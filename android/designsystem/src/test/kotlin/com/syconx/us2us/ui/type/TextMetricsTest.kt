package com.syconx.us2us.ui.type

import android.content.Context
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.robolectric.RuntimeEnvironment
import com.syconx.us2us.ui.tokens.DesignSpec
import com.syconx.us2us.ui.tokens.U2FontFamily
import com.syconx.us2us.ui.tokens.U2TextStyle
import com.syconx.us2us.ui.tokens.U2TextStyles
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The fonts, axes and CSS line boxes measured like the design (ports of DesignTokenTests'
 * widths and DisplayTextMetricsTests), on Robolectric's native text stack at 3× (1 dp = 3 px).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class TextMetricsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val density = Density(3f)
    private val measurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)

    private fun layout(text: String, style: U2TextStyle, width: Float? = null): TextLayoutResult = measurer.measure(
        text = AnnotatedString(text),
        style = style.toTextStyle(density),
        softWrap = width != null,
        constraints = width?.let { Constraints(maxWidth = (it * 3f).roundToInt()) } ?: Constraints(),
    )

    /** The advance width of one line, in dp. */
    private fun width(text: String, style: U2TextStyle): Float {
        val result = layout(text, style)
        return (result.multiParagraph.getLineRight(0) - result.multiParagraph.getLineLeft(0)) / 3f
    }

    /** The axes that make the design's measured widths: Martian Mono at wdth 100, Inter's text cut, Doto. */
    @Test
    fun fontsRenderTheDesignsWidths() {
        assertEquals(38.5f, width("1 / 3", U2TextStyles.monoMeta), 0.1f)
        assertEquals(52.5f, width("deniz", U2TextStyles.monoUsername), 0.1f)
        assertEquals(73.17f, width("Kullanılabilir", U2TextStyles.caption), 0.35f)
        assertEquals(169.5f, width("Bu akşam boş musun?", U2TextStyles.bubble), 0.6f)
        assertEquals(60f, width("us2us", U2TextStyles.brandWordmark), 0.1f)
        assertEquals(307.2f, width("23:41:07", U2TextStyles.displayCountdownL), 0.2f)
        assertEquals(37.22f, width("18:10", U2TextStyles.caption.tabular), 0.2f)
    }

    /** Martian Mono's default instance is wdth 112.5: without the axis “1 / 3” would be 7 % wider. */
    @Test
    fun martianMonoIsPinnedToWidth100() {
        val pinned = width("1 / 3", U2TextStyles.monoMeta)
        assertEquals(38.5f, pinned, 0.1f)
        assertTrue("wdth 112.5 would give ${38.5f * 1.125f}", abs(pinned - 38.5f * 1.125f) > 3f)
    }

    @Test
    fun lineMetricsRoundLikeBlink() {
        // Doto 46: ascent 43.7 → 44, descent 11.5 → 12; half-leading −5.
        val doto = CSSLineMetrics.of(U2TextStyles.displayHero)
        assertEquals(CSSLineMetrics(ascent = 39f, descent = 7f), doto)
        // Inter 700 46: ascent 44.56 → 45, descent 11.10 → 11; half-leading −5.
        val inter = CSSLineMetrics.of(U2TextStyles.displayHero.punctuation)
        assertEquals(CSSLineMetrics(ascent = 40f, descent = 6f), inter)
        // A line with both grows to 47, baseline 40 below its top.
        assertEquals(CSSLineMetrics(ascent = 40f, descent = 7f), doto.union(inter))
        // Doto 40 at 1.02: half-leading −3.6 floors to −4.
        val title = CSSLineMetrics.of(U2TextStyles.displayTitleL)
        assertEquals(34f, title.ascent, 0.001f)
        assertEquals(40.8f, title.height, 0.001f)
    }

    /**
     * Every style: one line is its CSS line height (to the pixel) and the baseline sits where
     * Blink puts it (to the pixel), including the tight Doto lines whose glyphs overflow.
     */
    @Test
    fun everyStyleHasTheCssLineBox() {
        val failures = mutableListOf<String>()
        for (style in U2TextStyles.all + U2TextStyles.displayHero.punctuation + U2TextStyles.displayTitleL.punctuation) {
            val result = layout("Hg", style)
            val expectedHeight = (style.lineHeight * 3f).roundToInt()
            val expectedBaseline = (CSSLineMetrics.of(style.family, style.size, expectedHeight / 3f).ascent * 3f).roundToInt()
            val baseline = result.firstBaseline.roundToInt()
            if (result.size.height != expectedHeight || abs(baseline - expectedBaseline) > 1) {
                failures += "${style.name}: height ${result.size.height} (want $expectedHeight), baseline $baseline (want $expectedBaseline)"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** Multi-line text keeps the line height on every line (lines × lineHeight). */
    @Test
    fun paragraphsAreLinesTimesLineHeight() {
        val result = layout("Bildirimler Apple'ın sunucularından geçer ve kimin ne zaman yazdığını görür.", U2TextStyles.body, width = 200f)
        assertTrue("${result.lineCount} lines", result.lineCount >= 3)
        assertEquals(result.lineCount * 72, result.size.height)
        assertEquals(result.getLineBaseline(0) + 72f, result.getLineBaseline(1), 0.5f)
    }

    /** Display styles keep the design size at a larger font scale; text styles follow it. */
    @Test
    fun displayStylesIgnoreTheFontScale() {
        val scaled = Density(3f, fontScale = 1.3f)
        val doto = U2TextStyles.displayTitle.toTextStyle(scaled)
        val body = U2TextStyles.body.toTextStyle(scaled)
        with(scaled) {
            assertEquals(38f * 3f, doto.fontSize.toPx(), 0.5f)
            assertEquals(38f * 3f, doto.lineHeight.toPx(), 1f)
            // Android scales large text non-linearly (API 34+): bigger, but not quite × 1.3.
            assertTrue("${body.fontSize.toPx()}", body.fontSize.toPx() > 16f * 3f * 1.2f)
        }
    }

    @Test
    fun upperCaseFollowsTheLocale() {
        assertEquals("İSTEKLER", U2TextStyles.monoSectionLabel.applyCase("istekler", Locale.forLanguageTag("tr")))
        assertEquals("ISTEKLER", U2TextStyles.monoSectionLabel.applyCase("istekler", Locale.ENGLISH))
        assertEquals("istekler", U2TextStyles.monoMeta.applyCase("istekler", Locale.forLanguageTag("tr")))
    }

    /**
     * Every single-line text of the design (ios/design/layout) measured with the bundled fonts:
     * the advance widths match the reference renderer's.
     */
    @Test
    fun designTextWidthsMatchTheLayoutJson() {
        val families = mapOf("Inter" to U2FontFamily.Inter, "Martian Mono" to U2FontFamily.MartianMono, "Doto" to U2FontFamily.Doto)
        val errors = mutableListOf<Pair<String, Float>>()
        val files = File(DesignSpec.directory, "layout").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        for (file in files) {
            val english = file.name.startsWith("9.")
            fun walk(element: JSONObject) {
                val font = element.optJSONObject("font")
                val rects = element.optJSONArray("textLineRects")
                val text = element.optString("text", "")
                val family = font?.optString("family")?.let(families::get)
                if (element.optString("kind") == "text" && font != null && family != null && rects != null &&
                    rects.length() == 1 && text.isNotBlank() && !text.contains('\n') &&
                    font.optString("textOverflow") != "ellipsis"
                ) {
                    val tracking = font.optString("letterSpacing", "0px").removeSuffix("px").toFloatOrNull() ?: 0f
                    val style = U2TextStyle(
                        name = "layout",
                        family = family,
                        weight = font.getDouble("weight").toInt(),
                        size = font.getDouble("size").toFloat(),
                        lineHeight = font.getDouble("lineHeight").toFloat(),
                        tracking = tracking,
                        uppercase = font.optString("textTransform") == "uppercase",
                        monospacedDigits = font.optString("fontVariantNumeric") == "tabular-nums",
                        italic = font.optString("fontStyle") == "italic",
                        scalesWithFontSize = true,
                    )
                    val locale = if (english) Locale.ENGLISH else Locale.forLanguageTag("tr")
                    val shown = when (font.optString("textTransform")) {
                        "uppercase" -> text.uppercase(locale)
                        "lowercase" -> text.lowercase(locale)
                        else -> text
                    }
                    val expected = rects.getJSONObject(0).getDouble("w").toFloat()
                    val measured = width(shown, style)
                    errors += "${file.name} ${element.optString("id")} “$shown” ${family.name} ${style.weight} ${style.size}: " +
                        "design $expected, measured $measured" to (measured - expected)
                }
                element.optJSONArray("children")?.let { children ->
                    for (i in 0 until children.length()) walk(children.getJSONObject(i))
                }
            }
            val elements = DesignSpec.json("layout/${file.name}").getJSONArray("elements")
            for (i in 0 until elements.length()) walk(elements.getJSONObject(i))
        }
        val sorted = errors.sortedByDescending { abs(it.second) }
        val within05 = errors.count { abs(it.second) <= 0.5f }
        val within1 = errors.count { abs(it.second) <= 1f }
        val report = "samples ${errors.size}, ≤0.5 dp $within05, ≤1 dp $within1, mean |error| " +
            "${errors.map { abs(it.second) }.average()}\nworst:\n" + sorted.take(15).joinToString("\n") { it.first }
        File(DesignSpec.androidDir, "build/shots/android/designsystem/text-widths.txt").apply { parentFile?.mkdirs() }.writeText(report + "\n")
        assertTrue(report, errors.size > 300)
        assertTrue(report, within1 >= errors.size * 0.95)
    }
}
