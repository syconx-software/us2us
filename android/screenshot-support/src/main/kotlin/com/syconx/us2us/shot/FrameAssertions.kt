package com.syconx.us2us.shot

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The geometry gate: a node tagged with a layout element id (`Modifier.testTag("r.3.0.0")`) must sit
 * where the design measured that element, within a tolerance (1 dp by default) on every edge. This is
 * the Compose side of the iOS ComponentMetricsTests; image diffs are for review, frames are the gate.
 */

/** Where the node tagged [tag] is on screen, in dp from the top-left of the window (unclipped). */
fun SemanticsNodeInteractionsProvider.measuredFrame(tag: String): Frame = node(tag).screenFrame()

/** Asserts that the node tagged [tag] has [element]'s frame from [layout], within [tolerance]. */
fun SemanticsNodeInteractionsProvider.assertFrame(
    layout: LayoutSpec,
    tag: String,
    tolerance: Dp = 1.dp,
    element: String = tag,
) {
    frameMismatch(layout, tag, element, tolerance)?.let { throw AssertionError(it) }
}

/** Asserts every tag (each tag is also the element id) and reports all mismatches together. */
fun SemanticsNodeInteractionsProvider.assertFrames(layout: LayoutSpec, vararg tags: String, tolerance: Dp = 1.dp) {
    assertFrames(layout, tags.associateWith { it }, tolerance)
}

/** Asserts tag → element pairs and reports all mismatches together. */
fun SemanticsNodeInteractionsProvider.assertFrames(layout: LayoutSpec, tagToElement: Map<String, String>, tolerance: Dp = 1.dp) {
    val problems = tagToElement.mapNotNull { (tag, element) -> frameMismatch(layout, tag, element, tolerance) }
    if (problems.isNotEmpty()) {
        throw AssertionError("${problems.size} of ${tagToElement.size} frames differ from ${layout.name}:\n" + problems.joinToString("\n"))
    }
}

/**
 * Asserts only the size of the node tagged [tag] (a component measured on its own, as the iOS
 * ComponentMetricsTests do). A `null` dimension is not checked.
 */
fun SemanticsNodeInteractionsProvider.assertSize(tag: String, width: Dp?, height: Dp?, tolerance: Dp = 1.dp) {
    val frame = measuredFrame(tag)
    val problems = buildList {
        if (width != null && kotlin.math.abs(frame.w - width.value) > tolerance.value) add("width ${frame.w.fmt()} ≠ ${width.value.fmt()}")
        if (height != null && kotlin.math.abs(frame.h - height.value) > tolerance.value) add("height ${frame.h.fmt()} ≠ ${height.value.fmt()}")
    }
    if (problems.isNotEmpty()) throw AssertionError("$tag: ${problems.joinToString()} (±${tolerance.value.fmt()} dp)")
}

private fun SemanticsNodeInteractionsProvider.frameMismatch(layout: LayoutSpec, tag: String, element: String, tolerance: Dp): String? {
    val expected = layout[element].frame
    val actual = runCatching { measuredFrame(tag) }.getOrElse { return "$tag: ${it.message?.lineSequence()?.firstOrNull()}" }
    val distance = expected.distanceTo(actual)
    if (distance <= tolerance.value + EPSILON) return null
    val label = if (tag == element) tag else "$tag ($element)"
    val deltas = listOf(
        "x" to actual.x - expected.x,
        "y" to actual.y - expected.y,
        "w" to actual.w - expected.w,
        "h" to actual.h - expected.h,
    ).filter { kotlin.math.abs(it.second) > tolerance.value + EPSILON }
        .joinToString(" ") { (k, d) -> "$k${if (d > 0) "+" else ""}${d.fmt()}" }
    return "$label: expected [$expected], got [$actual] — off by $deltas (±${tolerance.value.fmt()} dp)"
}

private const val EPSILON = 0.001f

private fun SemanticsNodeInteractionsProvider.node(tag: String): SemanticsNode {
    val nodes = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = true)
    return when (nodes.size) {
        1 -> nodes.single()
        0 -> throw AssertionError("no node tagged '$tag'")
        else -> throw AssertionError("${nodes.size} nodes tagged '$tag'")
    }
}

private fun SemanticsNode.screenFrame(): Frame {
    val density = layoutInfo.density.density
    val position = positionInWindow
    return Frame(position.x / density, position.y / density, size.width / density, size.height / density)
}
