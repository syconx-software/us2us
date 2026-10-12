package com.syconx.us2us.ui.tokens

import androidx.compose.runtime.Immutable

/**
 * The three families the design uses (tokens.md › Typography › Families), with the
 * hhea metrics of the bundled variable fonts (identical to their OS/2 typo metrics).
 */
enum class U2FontFamily(
    val unitsPerEm: Int,
    val ascender: Int,
    /** Negative, as in the font. */
    val descender: Int,
    val lineGap: Int,
) {
    /** Doto: brand moments, titles, counters. Always ROND = 100 (round dots). */
    Doto(unitsPerEm = 1000, ascender = 950, descender = -250, lineGap = 0),

    /**
     * Martian Mono: labels, usernames, codes. Pinned to wdth = 100, the instance the design
     * was rendered with (“1 / 3” at 11 is 38.5 wide). The font's own default is wdth 112.5,
     * which is 7 % wider.
     */
    MartianMono(unitsPerEm = 1000, ascender = 1000, descender = -200, lineGap = 0),

    /** Inter 4 at the text optical size (opsz = 14): all interface text. */
    Inter(unitsPerEm = 2048, ascender = 1984, descender = -494, lineGap = 0),
    ;

    /** (ascender + |descender| + lineGap) / unitsPerEm: one line at size 1, CSS's “normal” line height. */
    val naturalLineHeightRatio: Float get() = (ascender - descender + lineGap).toFloat() / unitsPerEm

    /** The ascender at a font size (points), unrounded. */
    fun ascent(size: Float): Float = ascender.toFloat() / unitsPerEm * size

    /** The descender's magnitude at a font size (points), unrounded. */
    fun descent(size: Float): Float = -descender.toFloat() / unitsPerEm * size
}

/**
 * A type style of the design (tokens.json › typography.styles). The instances are
 * generated into U2TextStyles.gen.kt; `ui.type` turns them into Compose text styles.
 *
 * Sizes and line heights are CSS pixels = points on the 402 × 874 frame = dp.
 * [lineHeight] is the CSS line height: the block height of one line.
 */
@Immutable
data class U2TextStyle(
    val name: String,
    val family: U2FontFamily,
    /** CSS weight (100…900). */
    val weight: Int,
    val size: Float,
    val lineHeight: Float,
    /** Letter spacing in points. */
    val tracking: Float,
    /** Upper-cased with the current locale (Turkish İ/ı, inventory 9.3). */
    val uppercase: Boolean,
    /** Tabular figures (ticking counters, times). */
    val monospacedDigits: Boolean,
    /** Synthetic oblique (the design loads no italic face). */
    val italic: Boolean,
    /**
     * Whether the style follows the system font size. Display (Doto) moments, avatar
     * initials and glyphs inside fixed-size shapes stay at the design size.
     */
    val scalesWithFontSize: Boolean,
) {
    /** The same style at another weight (e.g. the emphasised word of a sentence). */
    fun weight(weight: Int): U2TextStyle = copy(name = "$name@$weight", weight = weight)

    /**
     * The same style scaled by [factor] (size, line height and tracking), e.g. the 3.4 cover,
     * drawn in the design at 0.70 scale. A scaled style no longer follows the font size.
     */
    fun scaled(factor: Float): U2TextStyle = copy(
        name = "$name@x$factor",
        size = size * factor,
        lineHeight = lineHeight * factor,
        tracking = tracking * factor,
        scalesWithFontSize = false,
    )

    /** The same style with tabular figures. */
    val tabular: U2TextStyle get() = copy(name = "$name@tnum", monospacedDigits = true)

    /**
     * The same style with proportional figures. Some generated styles are tabular (`caption`,
     * for the 2.1 counter), but the 7.2 / 7.3 card captions are drawn with proportional digits.
     */
    val proportional: U2TextStyle get() = copy(name = "$name@pnum", monospacedDigits = false)

    /**
     * The same Doto style, but a heading that has to wrap pushes a word down instead of leaving
     * one alone on the last line (`text-wrap: balance`; the display text layout reads the name
     * suffix “Balanced”). One-line headings are unchanged.
     */
    val balanced: U2TextStyle get() = if (name.endsWith("Balanced")) this else copy(name = "${name}Balanced")

    /** Whether a heading in this style wraps balanced (see [balanced]). */
    val isBalanced: Boolean get() = name.endsWith("Balanced")

    /**
     * `displayPunctuation`: the trailing “.” of a Doto headline is set in Inter 700 at the
     * Doto run's size, line height and tracking (1.1, 1.2, 1.3, 2.3, 3.3, 6.3).
     */
    val punctuation: U2TextStyle get() = copy(
        name = "$name@punctuation",
        family = U2FontFamily.Inter,
        weight = 700,
        uppercase = false,
        monospacedDigits = false,
        italic = false,
    )

    /** The natural (font) line height at this size: what the text needs without a line box. */
    val naturalLineHeight: Float get() = size * family.naturalLineHeightRatio
}
