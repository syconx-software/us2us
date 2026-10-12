package com.syconx.us2us.ui.components.text

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2FontFamily
import com.syconx.us2us.ui.tokens.U2TextStyle
import com.syconx.us2us.ui.type.CSSLineMetrics
import com.syconx.us2us.ui.type.U2Fonts
import com.syconx.us2us.ui.type.toTextStyle
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A Doto display heading, set exactly like the design:
 *
 * - Doto with ROND = 100 at the style's weight;
 * - every “.” in Inter 700 at the same size (`displayPunctuation`: 1.1, 1.2, 1.3, 2.3, 3.3, 6.3);
 * - CSS line boxes: the line height (often tighter than the font's own, e.g. 40/40.8) with
 *   Blink's rounding, and a line that contains an Inter “.” grows to fit it as an inline box does
 *   in CSS ([CSSLineMetrics]: “İki uç. Bir hat.” has 47 dp lines and its baseline 40 dp down).
 * - Lines break greedily, as the browser that drew the design does; a “…Balanced” style
 *   ([U2TextStyle.balanced], `text-wrap: balance`) evens the lines out instead.
 *
 * Compose gives every line the same height, so the lines are measured and placed here one by
 * one. The view takes the full width offered (alignment applies inside it) and is exactly as
 * tall as the CSS block; glyphs overflow a line box tighter than the font, as in CSS. Single-line
 * Doto (counters, “Sohbetler”) can use `U2Text`.
 */
@Composable
fun U2DisplayText(
    text: String,
    style: U2TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
    color: Color = U2Theme.colors.text,
    isHeader: Boolean = false,
) {
    DisplayTextBlock(text, style, modifier, textAlign, color, isHeader, paintedLineHeight = null)
}

/** A localized heading: [U2DisplayText] for a string resource, a heading for accessibility by default. */
@Composable
fun U2DisplayText(
    @StringRes id: Int,
    style: U2TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
    color: Color = U2Theme.colors.text,
    isHeader: Boolean = true,
) {
    U2DisplayText(stringResource(id), style, modifier, textAlign, color, isHeader)
}

@Composable
internal fun DisplayTextBlock(
    text: String,
    style: U2TextStyle,
    modifier: Modifier,
    textAlign: TextAlign,
    color: Color,
    isHeader: Boolean,
    paintedLineHeight: Float?,
) {
    Spacer(
        modifier
            .semantics {
                this.text = AnnotatedString(text)
                if (isHeader) heading()
            }
            .then(DisplayTextElement(DisplayTextParams(text, style, textAlign, color, paintedLineHeight))),
    )
}

/** What a display block is laid out from. */
@Immutable
internal data class DisplayTextParams(
    val text: String,
    val style: U2TextStyle,
    val textAlign: TextAlign,
    val color: Color,
    /**
     * The line height the lines are laid out at, when it differs from the style's: [SetupTitle]
     * paints its lines on whole points and adds the fraction back under the block.
     */
    val paintedLineHeight: Float?,
)

/** A laid-out display block: its lines, each drawn at its own baseline. */
internal class DisplayBlock(
    val width: Int,
    val height: Int,
    /** Line boxes in dp: top and baseline of each line from the block's top. */
    val lineBoxes: List<CSSLineMetrics>,
    private val lines: List<TextLayoutResult>,
    private val baselines: List<Float>,
) {
    val lineCount: Int get() = lines.size

    fun draw(scope: DrawScope) {
        lines.forEachIndexed { index, line ->
            scope.drawText(line, topLeft = Offset(0f, baselines[index] - line.firstBaseline))
        }
    }
}

/** Measures display text the way [U2DisplayText] sets it. */
internal object DisplayTextLayout {
    fun measure(
        params: DisplayTextParams,
        measurer: TextMeasurer,
        density: Density,
        layoutDirection: LayoutDirection,
        maxWidth: Int,
    ): DisplayBlock {
        val style = params.style
        val lineHeight = params.paintedLineHeight ?: style.lineHeight
        val strut = CSSLineMetrics.of(style.family, style.size, lineHeight)
        val stop = CSSLineMetrics.of(U2FontFamily.Inter, style.size, lineHeight)

        val base = style.toTextStyle(density).copy(color = params.color, textAlign = params.textAlign)
        val breaking = base.copy(lineBreak = if (style.isBalanced) LineBreak.Heading else LineBreak.Simple)
        // One line on its own: the font's natural box, placed by its baseline below.
        val natural = base.copy(lineHeight = TextUnit.Unspecified, lineHeightStyle = null)
        val annotated = annotated(params.text)

        val paragraph = measurer.measure(
            text = annotated,
            style = breaking,
            constraints = Constraints(maxWidth = maxWidth),
            layoutDirection = layoutDirection,
            density = density,
        )
        val width = if (maxWidth != Constraints.Infinity) {
            maxWidth
        } else {
            (0 until paragraph.lineCount).maxOfOrNull { ceil(paragraph.getLineRight(it)).toInt() } ?: 0
        }

        val boxes = ArrayList<CSSLineMetrics>(paragraph.lineCount)
        val layouts = ArrayList<TextLayoutResult>(paragraph.lineCount)
        val baselines = ArrayList<Float>(paragraph.lineCount)
        var top = 0f
        for (line in 0 until paragraph.lineCount) {
            val start = paragraph.getLineStart(line)
            val end = paragraph.getLineEnd(line, visibleEnd = true)
            val run = annotated.subSequence(start, max(start, end))
            val box = if (run.text.contains('.')) strut.union(stop) else strut
            layouts += measurer.measure(
                text = run,
                style = natural,
                softWrap = false,
                maxLines = 1,
                constraints = Constraints.fixedWidth(max(width, 1)),
                layoutDirection = layoutDirection,
                density = density,
            )
            boxes += box
            // Glyphs sit on whole pixels, as the design's renderer painted them.
            baselines += ((top + box.ascent) * density.density).roundToInt().toFloat()
            top += box.height
        }
        val height = if (params.paintedLineHeight == null) {
            top
        } else {
            fractionalBlockHeight(top, params.paintedLineHeight, style.lineHeight - params.paintedLineHeight)
        }
        return DisplayBlock(
            width = width,
            height = (height * density.density).roundToInt(),
            lineBoxes = boxes,
            lines = layouts,
            baselines = baselines,
        )
    }

    /**
     * The CSS block height of text laid out at a whole-point line height: `height + lines ×
     * remainder`. A line grown by an Inter “.” already holds the fraction: the design's renderer
     * drew “Geri dönüş / yok.” (2.3) 71.0 dp tall, the grown text itself, not 71 + 2 × 0.36.
     */
    fun fractionalBlockHeight(height: Float, paintedLineHeight: Float, remainder: Float): Float {
        val lines = max(1f, floor(height / paintedLineHeight))
        val grown = height - lines * paintedLineHeight
        return height + max(0f, lines * remainder - grown)
    }

    /** Doto runs with every full stop in Inter 700. */
    fun annotated(text: String): AnnotatedString = buildAnnotatedString {
        for (character in text) {
            if (character == '.') {
                withStyle(stopStyle) { append(character) }
            } else {
                append(character)
            }
        }
    }

    private val stopStyle = SpanStyle(
        fontFamily = U2Fonts.family(U2FontFamily.Inter),
        fontWeight = FontWeight(700),
        fontSynthesis = FontSynthesis.None,
    )
}

private data class DisplayTextElement(val params: DisplayTextParams) : ModifierNodeElement<DisplayTextNode>() {
    override fun create() = DisplayTextNode(params)

    override fun update(node: DisplayTextNode) = node.update(params)

    override fun InspectorInfo.inspectableProperties() {
        name = "u2DisplayText"
        properties["text"] = params.text
        properties["style"] = params.style.name
    }
}

private class DisplayTextNode(private var params: DisplayTextParams) :
    Modifier.Node(), LayoutModifierNode, DrawModifierNode, CompositionLocalConsumerModifierNode {
    private var measurer: TextMeasurer? = null
    private var measurerKey: Any? = null
    private var block: DisplayBlock? = null
    private var blockKey: Any? = null

    fun update(params: DisplayTextParams) {
        if (params == this.params) return
        this.params = params
        blockKey = null
        invalidateMeasurement()
        invalidateDraw()
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val resolver: FontFamily.Resolver = currentValueOf(LocalFontFamilyResolver)
        val density = Density(density, fontScale)
        val textMeasurer = measurer.takeIf { measurerKey == resolver } ?: TextMeasurer(resolver, density, layoutDirection, CACHE_SIZE).also {
            measurer = it
            measurerKey = resolver
        }
        val key = listOf(params, constraints.maxWidth, density, layoutDirection)
        val laidOut = block.takeIf { blockKey == key } ?: DisplayTextLayout
            .measure(params, textMeasurer, density, layoutDirection, constraints.maxWidth)
            .also {
                block = it
                blockKey = key
                invalidateDraw()
            }
        val width = laidOut.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = laidOut.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        val placeable = measurable.measure(Constraints.fixed(width, height))
        return layout(width, height) { placeable.place(0, 0) }
    }

    override fun ContentDrawScope.draw() {
        block?.draw(this)
        drawContent()
    }

    private companion object {
        const val CACHE_SIZE = 8
    }
}
