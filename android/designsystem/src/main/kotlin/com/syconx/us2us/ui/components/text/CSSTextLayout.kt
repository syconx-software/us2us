package com.syconx.us2us.ui.components.text

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.isSpecified
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2TextStyle
import com.syconx.us2us.ui.type.applyCase
import com.syconx.us2us.ui.type.currentTextLocale
import com.syconx.us2us.ui.type.textStyle
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

// Text layouts that reproduce how the browser that drew the design sets text, where Compose's
// text differs.
//
// CSS block width (a wrapped 4.4 bubble is exactly 290 wide): Compose already sizes wrapped text
// like a CSS block inside a shrink-to-fit box (as wide as its longest line when it fits on one
// line, the full available width once it wraps), so no wrapper is needed.

// MARK: - Setup titles (2.1–2.3, 7.1 › code change)

/**
 * A setup-step heading (`displayTitleM` 36/36.72, `displayTitleS` 34/35.36): [U2DisplayText] with
 * its lines placed where the design's renderer painted them.
 *
 * Chrome lays these lines out 36.72 / 35.36 dp apart but paints each line on a whole point (the
 * 2nd line of “Gizli kodunu / belirle” is 36 dp below the 1st). The lines are laid out at the
 * whole-point line height and the fractional remainder is added back under the block, so the
 * lines sit at Chrome's positions and the block is exactly as tall as in CSS (the paragraph under
 * it stays at y 203.44).
 */
@Composable
fun SetupTitle(
    text: String,
    style: U2TextStyle,
    modifier: Modifier = Modifier,
    color: Color = U2Theme.colors.text,
) {
    DisplayTextBlock(
        text = text,
        style = style,
        modifier = modifier,
        textAlign = TextAlign.Start,
        color = color,
        isHeader = true,
        paintedLineHeight = floor(style.lineHeight),
    )
}

/** [SetupTitle] for a string resource. */
@Composable
fun SetupTitle(
    @StringRes id: Int,
    style: U2TextStyle,
    modifier: Modifier = Modifier,
    color: Color = U2Theme.colors.text,
) {
    SetupTitle(stringResource(id), style, modifier, color)
}

// MARK: - Greedy paragraph

/**
 * A multi-line paragraph that wraps like the browser that drew the design: greedily, with no
 * widow avoidance (7.3: “…sohbetler geri / gelmez.” as drawn), in the style's CSS line boxes.
 */
@Composable
fun GreedyParagraph(
    text: String,
    style: U2TextStyle,
    modifier: Modifier = Modifier,
    color: Color = U2Theme.colors.textSecondary,
    textAlign: TextAlign = TextAlign.Start,
) {
    val locale = currentTextLocale()
    val cased = remember(text, style.uppercase, locale) { style.applyCase(text, locale) }
    val textStyle = style.textStyle().merge(TextStyle(color = color, textAlign = textAlign, lineBreak = LineBreak.Simple))
    BasicText(
        text = cased,
        modifier = modifier.cssTrailingTracking(textStyle),
        style = textStyle,
    )
}

// MARK: - CSS line clamp

/**
 * `-webkit-line-clamp`: the text wraps by words; when it needs more than [lines] lines, the last
 * visible line ends with “…” right after its last word (4.3 “… slaytları paylaşabilir…”), where
 * Compose's ellipsis would fill the line with characters first (“… paylaşabilir mis…”).
 * The view fills the available width; accessibility reads the whole text.
 */
@Composable
fun CSSLineClampText(
    text: String,
    style: U2TextStyle,
    modifier: Modifier = Modifier,
    lines: Int = 2,
    color: Color = U2Theme.colors.text,
) {
    val measurer = rememberTextMeasurer()
    val locale = currentTextLocale()
    val cased = remember(text, style.uppercase, locale) { style.applyCase(text, locale) }
    val textStyle = style.textStyle().merge(TextStyle(color = color, lineBreak = LineBreak.Simple))
    BoxWithConstraints(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = text }) {
        val width = constraints.maxWidth
        val clamped = remember(cased, textStyle, width, lines) { CSSLineClamp.clamp(cased, measurer, textStyle, width, lines) }
        BasicText(
            text = clamped,
            style = textStyle,
            maxLines = lines,
            overflow = TextOverflow.Clip,
            modifier = Modifier.cssTrailingTracking(textStyle),
        )
    }
}

/** The arithmetic of [CSSLineClampText]. */
object CSSLineClamp {
    /** [text] cut after the last whole word that fits [lines] lines of [width] px, with “…”. */
    fun clamp(text: String, measurer: TextMeasurer, style: TextStyle, width: Int, lines: Int): String {
        if (width <= 1 || lines <= 0 || text.isEmpty() || width == Constraints.Infinity) return text
        val paragraph = measurer.measure(text, style, softWrap = true, constraints = Constraints(maxWidth = width))
        if (paragraph.lineCount <= lines) return text

        val lastStart = paragraph.getLineStart(lines - 1)
        val lastEnd = paragraph.getLineEnd(lines - 1)
        fun lastLineWidth(head: String): Float {
            val line = head.substring(min(lastStart, head.length)) + "…"
            return ceil(measurer.measure(line, style, softWrap = false, maxLines = 1).size.width.toFloat())
        }

        var head = text.substring(0, lastEnd).trim()
        while (head.isNotEmpty() && lastLineWidth(head) > width) {
            head = head.substring(0, head.offsetByCodePoints(head.length, -1)).trimEnd()
        }
        return "$head…"
    }
}

/**
 * CSS letter-spacing follows every character, the last one included; Android's only goes between
 * characters, so the box grows by one letter spacing at the end (as `U2Text` does).
 */
@Composable
internal fun Modifier.cssTrailingTracking(style: TextStyle): Modifier {
    if (!style.letterSpacing.isSpecified || style.letterSpacing.value == 0f) return this
    val trailing = with(LocalDensity.current) { style.letterSpacing.toPx() }
    return layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val width = (placeable.width + trailing).roundToInt().coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, placeable.height) { placeable.place(0, 0) }
    }
}
