package com.syconx.us2us.ui.type

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.isSpecified
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2TextStyle
import kotlin.math.roundToInt

/**
 * Text in a design type style: the Android counterpart of `Text(…).u2Font(style)`.
 * The font, size, CSS line box, tracking and figures come from [style]
 * ([toTextStyle]); upper-case styles are cased with the current locale (Turkish İ/ı).
 * The view is exactly `lines × lineHeight` high, like the design's CSS block.
 */
@Composable
fun U2Text(
    text: String,
    style: U2TextStyle,
    modifier: Modifier = Modifier,
    color: Color = U2Theme.colors.text,
    textAlign: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val locale = currentTextLocale()
    val cased = remember(text, style.uppercase, locale) { style.applyCase(text, locale) }
    val textStyle = style.textStyle()
    // iOS `Text` never leaves one word alone on the second of two lines (PushOut). Once a text has
    // needed it, it keeps its last two words together (harmless when they share a line anyway), so a
    // countdown that ticks inside it never flickers between the two breaks.
    var pushesOut by remember { mutableStateOf(false) }
    BasicText(
        text = if (pushesOut) PushOut.join(cased) else cased,
        modifier = modifier.cssTrailingTracking(textStyle),
        style = textStyle.merge(color = color, textAlign = textAlign),
        onTextLayout = { layout ->
            if (softWrap) {
                if (!pushesOut && PushOut.leavesWidow(layout)) pushesOut = true else if (pushesOut && layout.lineCount > 2) pushesOut = false
            }
            onTextLayout?.invoke(layout)
        },
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
    )
}

/** [U2Text] for styled runs (an emphasised word, a run in another font). */
@Composable
fun U2Text(
    text: AnnotatedString,
    style: U2TextStyle,
    modifier: Modifier = Modifier,
    color: Color = U2Theme.colors.text,
    textAlign: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val locale = currentTextLocale()
    val cased = remember(text, style.uppercase, locale) {
        val upper = if (style.uppercase) style.applyCase(text.text, locale) else null
        when {
            upper == null -> text
            // Casing that changes the length (ß → SS) cannot keep the runs' ranges.
            upper.length != text.length -> AnnotatedString(upper)
            else -> AnnotatedString.Builder(upper).apply {
                text.spanStyles.forEach { addStyle(it.item, it.start, it.end) }
                text.paragraphStyles.forEach { addStyle(it.item, it.start, it.end) }
            }.toAnnotatedString()
        }
    }
    val textStyle = style.textStyle()
    var pushesOut by remember { mutableStateOf(false) }
    BasicText(
        text = if (pushesOut) PushOut.join(cased) else cased,
        modifier = modifier.cssTrailingTracking(textStyle),
        style = textStyle.merge(color = color, textAlign = textAlign),
        onTextLayout = { layout ->
            if (softWrap) {
                if (!pushesOut && PushOut.leavesWidow(layout)) pushesOut = true else if (pushesOut && layout.lineCount > 2) pushesOut = false
            }
            onTextLayout?.invoke(layout)
        },
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        inlineContent = inlineContent,
    )
}

/**
 * CSS letter-spacing follows every character, the last one included; Android's only goes between
 * characters. The text box therefore grows (or, for negative tracking, shrinks) by one letter
 * spacing at the end, so a tracked label (`monoKicker`, `monoSectionLabel`) is as wide as in the
 * design. The glyphs stay where they are.
 */
@Composable
private fun Modifier.cssTrailingTracking(style: TextStyle): Modifier {
    if (!style.letterSpacing.isSpecified || style.letterSpacing.value == 0f) return this
    val trailing = with(LocalDensity.current) { style.letterSpacing.toPx() }
    return layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val width = (placeable.width + trailing).roundToInt().coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, placeable.height) { placeable.place(0, 0) }
    }
}
