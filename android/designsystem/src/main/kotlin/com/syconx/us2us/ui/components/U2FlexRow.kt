package com.syconx.us2us.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A horizontal row whose children share the width by CSS `flex: N` (basis 0 %) arithmetic on
 * content boxes, e.g. the request decision buttons 1 : 1 : 1.3 (4.3) and 1 : 1 : 1.4 (4.4).
 *
 * A child's fixed horizontal border (`Modifier.u2Flex(1.3f, border = 2.dp)` for the outlined
 * “Kabul et”, 1 dp each side) is taken off the free space first and added back to that child.
 * That is why the design's buttons are 97.58 / 97.58 / 128.84 (4.3) and 103.53 / 103.53 / 146.94
 * (4.4) rather than a plain 1 : 1 : 1.3 split. Children without a factor get 1. The row is as
 * tall as its tallest child; children are centred vertically.
 */
@Composable
fun U2FlexRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val items = measurables.map { (it.parentData as? U2FlexItem) ?: U2FlexItem.Default }
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth.toFloat()
        } else {
            measurables.sumOf { it.maxIntrinsicWidth(constraints.maxHeight) }.toFloat()
        }
        val widths = U2FlexRowLayout.widths(
            width = width,
            spacing = spacing.toPx(),
            items = items.map { it.grow to it.border.toPx() },
        )
        // Whole pixels that add up: each child ends where the exact arithmetic ends.
        val edges = FloatArray(widths.size + 1)
        var x = 0f
        widths.forEachIndexed { index, w ->
            edges[index] = x
            x += w + if (index < widths.lastIndex) spacing.toPx() else 0f
        }
        val placeables = measurables.mapIndexed { index, measurable ->
            val start = edges[index].roundToInt()
            val end = (edges[index] + widths[index]).roundToInt()
            measurable.measure(Constraints(minWidth = end - start, maxWidth = end - start, minHeight = 0, maxHeight = constraints.maxHeight))
        }
        val height = max(constraints.minHeight, placeables.maxOfOrNull { it.height } ?: 0)
        layout(width.roundToInt(), height) {
            placeables.forEachIndexed { index, placeable ->
                placeable.place(edges[index].roundToInt(), (height - placeable.height) / 2)
            }
        }
    }
}

/** The arithmetic of [U2FlexRow]. */
object U2FlexRowLayout {
    /**
     * The children's widths for [width]: grow factors share what is left after the gaps and the
     * fixed borders; each border is added back. [items] are (grow, border) pairs.
     */
    fun widths(width: Float, spacing: Float, items: List<Pair<Float, Float>>): List<Float> {
        val grow = items.sumOf { it.first.toDouble() }.toFloat()
        val borders = items.sumOf { it.second.toDouble() }.toFloat()
        val free = max(0f, width - spacing * max(0, items.size - 1) - borders)
        return items.map { (g, border) -> (if (grow > 0f) free * g / grow else 0f) + border }
    }
}

/**
 * The CSS `flex` grow factor of this child inside a [U2FlexRow], and its fixed horizontal border
 * width (both sides together).
 */
fun Modifier.u2Flex(grow: Float, border: Dp = 0.dp): Modifier = this then U2FlexItem(grow, border)

@Immutable
private data class U2FlexItem(val grow: Float, val border: Dp) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = this@U2FlexItem

    companion object {
        val Default = U2FlexItem(grow = 1f, border = 0.dp)
    }
}
