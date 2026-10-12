package com.syconx.us2us.ui.type

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.theme.LocalU2Type
import com.syconx.us2us.ui.tokens.U2TextStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Resolves the design's type styles into Compose text styles for a density and font scale,
 * and caches them. Provided by `U2Theme` as `LocalU2Type`; read a style with
 * [textStyle] (in composition) or [toTextStyle] (with an explicit density, e.g. for a
 * TextMeasurer).
 */
@Stable
class U2Typography {
    private data class Key(val style: U2TextStyle, val density: Float, val fontScale: Float)

    private val cache = ConcurrentHashMap<Key, TextStyle>()

    /** The Compose text style of [style] at [density] (and its font scale). */
    fun resolve(style: U2TextStyle, density: Density): TextStyle =
        cache.getOrPut(Key(style, density.density, density.fontScale)) { style.toTextStyle(density) }

    companion object {
        /** The process-wide resolver (the default of `LocalU2Type`). */
        val Default: U2Typography = U2Typography()
    }
}

/** This style as a Compose text style for the current density and font scale. */
@Composable
@ReadOnlyComposable
fun U2TextStyle.textStyle(): TextStyle = LocalU2Type.current.resolve(this, LocalDensity.current)

/**
 * This style as a Compose text style.
 *
 * - Font: the bundled variable font with the design's axes ([U2Fonts]); no synthetic bold;
 *   italic is the synthetic oblique.
 * - Size: dp-equivalent sp, so the layout matches the design at font scale 1. Styles that do
 *   not follow the font size (Doto, avatar initials, keypad…) are converted from dp, so they
 *   keep the design size at any font scale.
 * - Line height: the CSS line height, as a whole number of pixels (Compose rounds every line
 *   up to a pixel; rounding to the nearest keeps a line within half a pixel of the design).
 *   The baseline sits where the design's renderer puts it: Blink's line box (see
 *   [CSSLineMetrics]), reproduced with a per-style `LineHeightStyle.Alignment`.
 *   Lines tighter than the font (Doto at 1.0) keep their height; the glyphs overflow, as in CSS.
 * - Tracking in points; tabular figures through `tnum`.
 * - Glyphs unhinted at fractional advances (`TextMotion.Animated`), so widths match the design.
 *
 * Upper-casing is not part of a TextStyle: [U2Text] applies [U2TextStyle.uppercase] with the
 * current locale, or call [applyCase].
 */
fun U2TextStyle.toTextStyle(density: Density): TextStyle = with(density) {
    val fontSize: TextUnit = if (scalesWithFontSize) size.sp else size.dp.toSp()
    val fontSizePx = fontSize.toPx()
    val lineTarget = if (scalesWithFontSize) lineHeight.sp.toPx() else lineHeight.dp.toPx()
    val linePx = max(1, lineTarget.roundToInt())
    val letterSpacing: TextUnit = when {
        tracking == 0f -> TextUnit.Unspecified
        scalesWithFontSize -> tracking.sp
        else -> tracking.dp.toSp()
    }

    // The design's baseline: Blink's line box at the size and line height actually drawn.
    val pxPerPoint = density.density
    val css = CSSLineMetrics.of(family, fontSizePx / pxPerPoint, linePx / pxPerPoint)
    val baselinePx = (css.ascent * pxPerPoint).roundToInt()
    val fontAscentPx = ceil(family.ascent(fontSizePx)).toInt()
    val fontDescentPx = ceil(family.descent(fontSizePx)).toInt()
    // A line tighter than the font (Doto at 1.0, keypad digits, avatar initials) keeps its height
    // only in the tight mode with both ends trimmed to the computed line box; any other line is
    // laid out untrimmed, so its first and last lines are full line boxes too.
    val tight = linePx < fontAscentPx + fontDescentPx

    TextStyle(
        fontFamily = U2Fonts.family(family),
        fontWeight = FontWeight(weight),
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        fontSynthesis = if (italic) FontSynthesis.Style else FontSynthesis.None,
        fontSize = fontSize,
        // A hair under the whole pixel, so the platform's round-up lands exactly on it.
        lineHeight = (linePx - LINE_HEIGHT_EPSILON_PX).toSp(),
        letterSpacing = letterSpacing,
        fontFeatureSettings = if (monospacedDigits) "tnum" else null,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment(topRatio(linePx, fontAscentPx, fontDescentPx, baselinePx)),
            trim = if (tight) LineHeightStyle.Trim.Both else LineHeightStyle.Trim.None,
            mode = if (tight) LineHeightStyle.Mode.Tight else LineHeightStyle.Mode.Fixed,
        ),
        // Unhinted glyphs at fractional advances, like the renderer that drew the design:
        // hinted advances snap to whole pixels and every measured width drifts (“1 / 3” would be
        // 38.33 instead of 38.5).
        textMotion = TextMotion.Animated,
    )
}

/** [text] in this style's case (upper-cased with [locale] for `monoKicker`, `monoSectionLabel`). */
fun U2TextStyle.applyCase(text: String, locale: Locale): String = if (uppercase) text.uppercase(locale) else text

/**
 * The locale text is cased with: the UI language the resources resolved to (the in-app language,
 * with the configuration's region), never the JVM default, so Turkish copy keeps its dotted İ.
 */
@Composable
fun currentTextLocale(): Locale = currentAppLocale()

/**
 * The `LineHeightStyle.Alignment` ratio that puts the baseline [baselinePx] below the line's
 * top, given how Compose distributes a fixed line height: the line is [linePx] high, the font's
 * own extent is [fontAscentPx] + [fontDescentPx], and the difference is split by the ratio,
 * each part rounded up (`LineHeightStyleSpan`).
 */
internal fun topRatio(linePx: Int, fontAscentPx: Int, fontDescentPx: Int, baselinePx: Int): Float {
    val diff = linePx - (fontAscentPx + fontDescentPx)
    if (diff == 0) return 0.5f
    // Compose adds ceil(diff · (1 − ratio)) (diff > 0) or ceil(diff · ratio) (diff < 0) to the
    // font's descent; the baseline is the line height minus that descent.
    val descentOffset = linePx - fontDescentPx - baselinePx
    val ratio = if (diff > 0) 1f - (descentOffset - 0.5f) / diff else (descentOffset - 0.5f) / diff
    return ratio.coerceIn(0f, 1f)
}

private const val LINE_HEIGHT_EPSILON_PX = 0.01f
