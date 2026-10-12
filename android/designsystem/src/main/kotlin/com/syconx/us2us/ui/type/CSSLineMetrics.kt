package com.syconx.us2us.ui.type

import androidx.compose.runtime.Immutable
import com.syconx.us2us.ui.tokens.U2FontFamily
import com.syconx.us2us.ui.tokens.U2TextStyle
import kotlin.math.floor
import kotlin.math.max

/**
 * The vertical metrics of one font in a CSS line box, computed the way the design's renderer
 * (Chrome/Blink) does: the font's ascent and descent are rounded to whole CSS pixels, the
 * half-leading `(line-height − content) / 2` is floored onto the ascent, and the descent takes
 * the rest. Units are design points (dp).
 *
 * A line box is the union of the block's strut (its own font at the line height) and every
 * inline font on the line. That is why “İki uç. Bir hat.” (Doto 46/46 with Inter 700 full
 * stops) has 47-point lines, not 46, and its baseline sits 40 points below the line's top.
 */
@Immutable
data class CSSLineMetrics(
    /** Line top → baseline. */
    val ascent: Float,
    /** Baseline → line bottom. */
    val descent: Float,
) {
    val height: Float get() = ascent + descent

    fun union(other: CSSLineMetrics): CSSLineMetrics =
        CSSLineMetrics(ascent = max(ascent, other.ascent), descent = max(descent, other.descent))

    /** The line box of a line set in [fonts] (plus this strut). */
    fun lineBox(fonts: Iterable<CSSLineMetrics>): CSSLineMetrics = fonts.fold(this) { box, font -> box.union(font) }

    companion object {
        /** A font of [family] at [size] in a line box [lineHeight] high. */
        fun of(family: U2FontFamily, size: Float, lineHeight: Float): CSSLineMetrics {
            val ascent = roundHalfAwayFromZero(family.ascent(size))
            val descent = roundHalfAwayFromZero(family.descent(size))
            val halfLeading = floor((lineHeight - (ascent + descent)) / 2f)
            val top = ascent + halfLeading
            return CSSLineMetrics(ascent = top, descent = lineHeight - top)
        }

        /** The strut of a style: its own font in its own line height. */
        fun of(style: U2TextStyle): CSSLineMetrics = of(style.family, style.size, style.lineHeight)

        /** Swift's `.rounded()` (half away from zero), which is how Blink rounds font metrics. */
        private fun roundHalfAwayFromZero(value: Float): Float =
            if (value >= 0f) floor(value + 0.5f) else -floor(-value + 0.5f)
    }
}
