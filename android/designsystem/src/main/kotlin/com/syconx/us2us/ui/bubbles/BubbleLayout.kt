package com.syconx.us2us.ui.bubbles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.tokens.U2Radius
import kotlin.math.max
import kotlin.math.min

// MARK: - Geometry

/**
 * C15 bubble outline: r20; the last bubble of a run gets a 6 dp tail corner (bottom-left
 * incoming `20/20/20/6`, bottom-right outgoing `20/20/6/20`). Corners are circular, as in the
 * references (Chrome's `border-radius`).
 */
@Immutable
data class BubbleShape(val direction: MessageDirection, val isTail: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { U2Radius.bubble.toPx() }
        val tail = with(density) { U2Radius.bubbleTail.toPx() }
        val bottomLeading = if (isTail && direction == MessageDirection.Incoming) tail else r
        val bottomTrailing = if (isTail && direction == MessageDirection.Outgoing) tail else r
        val ltr = layoutDirection == LayoutDirection.Ltr
        return Outline.Rounded(
            RoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = size.height,
                topLeftCornerRadius = CornerRadius(r),
                topRightCornerRadius = CornerRadius(r),
                bottomRightCornerRadius = CornerRadius(if (ltr) bottomTrailing else bottomLeading),
                bottomLeftCornerRadius = CornerRadius(if (ltr) bottomLeading else bottomTrailing),
            ).scaledToFit(),
        )
    }
}

/** CSS (and Skia) shrink radii that do not fit their box: all of them by the same factor. */
private fun RoundRect.scaledToFit(): RoundRect {
    fun ratio(side: Float, a: Float, b: Float) = if (a + b > side && a + b > 0f) side / (a + b) else 1f
    val factor = minOf(
        ratio(width, topLeftCornerRadius.x, topRightCornerRadius.x),
        ratio(width, bottomLeftCornerRadius.x, bottomRightCornerRadius.x),
        ratio(height, topLeftCornerRadius.y, bottomLeftCornerRadius.y),
        ratio(height, topRightCornerRadius.y, bottomRightCornerRadius.y),
    )
    if (factor >= 1f) return this
    fun CornerRadius.scaled() = CornerRadius(x * factor, y * factor)
    return RoundRect(
        left, top, right, bottom,
        topLeftCornerRadius.scaled(), topRightCornerRadius.scaled(), bottomRightCornerRadius.scaled(), bottomLeftCornerRadius.scaled(),
    )
}

// MARK: - Bubble column

/** What a [BubbleStack] child marks itself with. */
interface BubbleStackScope {
    /** Right-aligns this child inside the bubble (the time row, `align-self: flex-end`). */
    fun Modifier.bubbleTrailing(): Modifier

    /** Stretches this child to the bubble's width (the reply quote, a footer); its own ideal width still counts. */
    fun Modifier.bubbleFill(): Modifier
}

private object BubbleStackScopeInstance : BubbleStackScope {
    override fun Modifier.bubbleTrailing(): Modifier = this then BubbleChildElement(BubbleChildKind.Trailing)

    override fun Modifier.bubbleFill(): Modifier = this then BubbleChildElement(BubbleChildKind.Fill)
}

private enum class BubbleChildKind { Leading, Trailing, Fill }

private data class BubbleChildElement(val kind: BubbleChildKind) : ModifierNodeElement<BubbleChildNode>() {
    override fun create() = BubbleChildNode(kind)

    override fun update(node: BubbleChildNode) {
        node.kind = kind
    }
}

private class BubbleChildNode(var kind: BubbleChildKind) : Modifier.Node(), ParentDataModifierNode {
    override fun Density.modifyParentData(parentData: Any?): Any = kind
}

private val Measurable.bubbleKind: BubbleChildKind get() = parentData as? BubbleChildKind ?: BubbleChildKind.Leading

private val IntrinsicMeasurable.bubbleKind: BubbleChildKind get() = parentData as? BubbleChildKind ?: BubbleChildKind.Leading

/**
 * The bubble column (`flex-direction: column; gap 5`): as wide as its widest child (up to the
 * available width), children leading-aligned except those marked [BubbleStackScope.bubbleTrailing].
 * Stretching children ([BubbleStackScope.bubbleFill]) count with their ideal width.
 */
@Composable
fun BubbleStack(
    modifier: Modifier = Modifier,
    spacing: Dp = 5.dp,
    content: @Composable BubbleStackScope.() -> Unit,
) {
    val policy = remember(spacing) { BubbleStackPolicy(spacing) }
    Layout(content = { BubbleStackScopeInstance.content() }, modifier = modifier, measurePolicy = policy)
}

private class BubbleStackPolicy(private val spacing: Dp) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        val loose = Constraints(maxWidth = maxWidth)
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        var width = 0
        measurables.forEachIndexed { index, child ->
            if (child.bubbleKind == BubbleChildKind.Fill) {
                width = max(width, min(child.maxIntrinsicWidth(Constraints.Infinity), maxWidth))
            } else {
                val placeable = child.measure(loose)
                placeables[index] = placeable
                width = max(width, placeable.width)
            }
        }
        width = max(width, constraints.minWidth)
        measurables.forEachIndexed { index, child ->
            if (child.bubbleKind == BubbleChildKind.Fill) placeables[index] = child.measure(Constraints.fixedWidth(width))
        }
        val gap = spacing.roundToPx()
        val height = placeables.sumOf { it!!.height } + gap * max(0, placeables.size - 1)
        return layout(width, max(height, constraints.minHeight)) {
            var y = 0
            placeables.forEachIndexed { index, placeable ->
                val p = placeable!!
                val x = if (measurables[index].bubbleKind == BubbleChildKind.Trailing) width - p.width else 0
                p.placeRelative(x, y)
                y += p.height + gap
            }
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.maxOfOrNull { it.maxIntrinsicWidth(height) } ?: 0

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.maxOfOrNull { it.minIntrinsicWidth(height) } ?: 0

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables.sumOf { it.maxIntrinsicHeight(width) } + spacing.roundToPx() * max(0, measurables.size - 1)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables.sumOf { it.minIntrinsicHeight(width) } + spacing.roundToPx() * max(0, measurables.size - 1)
}

// MARK: - Container

/** How a bubble is filled. */
enum class BubbleFill {
    /** `surface` (incoming) or `accent800` (outgoing). */
    Standard,

    /** The deleted-message trace: transparent with an inner 1 dp `divider`. */
    Tombstone,
}

/** The paddings the design gives the bubble kinds. */
object BubblePadding {
    /** Text: 9 13 8 13. */
    val Standard = PaddingValues(start = 13.dp, top = 9.dp, end = 13.dp, bottom = 8.dp)

    /** Photo, video, live location: 5 5 8 5. */
    val Media = PaddingValues(start = 5.dp, top = 5.dp, end = 5.dp, bottom = 8.dp)
}

/**
 * C15: a message bubble, `surface` (incoming) or `accent800` (outgoing) behind the content, at
 * most [maxWidth] wide (290; 270 with a reply quote, 266 for live location), padding 9 13 8 13,
 * gap 5. It takes the size of its content, so the row's alignment decides its side.
 */
@Composable
fun MessageBubble(
    direction: MessageDirection,
    modifier: Modifier = Modifier,
    isTail: Boolean = false,
    maxWidth: Dp = 290.dp,
    padding: PaddingValues = BubblePadding.Standard,
    fill: BubbleFill = BubbleFill.Standard,
    content: @Composable BubbleStackScope.() -> Unit,
) {
    val colors = U2Theme.colors
    val shape = BubbleShape(direction, isTail)
    val background = when (fill) {
        BubbleFill.Standard -> Modifier.background(if (direction == MessageDirection.Incoming) colors.bubbleIncoming else colors.bubbleOutgoing, shape)
        BubbleFill.Tombstone -> Modifier.u2InnerStroke(colors.divider, 1.dp, shape)
    }
    BubbleStack(modifier.widthIn(max = maxWidth).then(background).padding(padding), spacing = 5.dp, content = content)
}
