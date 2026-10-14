package com.syconx.us2us.ui.bubbles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.shot.Frame
import com.syconx.us2us.shot.LayoutSpec
import com.syconx.us2us.shot.ShotEnvironment
import com.syconx.us2us.shot.ShotPaths
import com.syconx.us2us.ui.composer.Composer
import com.syconx.us2us.ui.media.LocalMediaClock
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Spacing
import java.io.File
import java.time.Instant
import kotlin.math.abs

/** The environment of this package's shots: the theme of the shot and the frozen design clock. */
internal val BubbleShotEnvironment = ShotEnvironment { spec, content ->
    U2Theme(dark = spec.isDark, reduceMotion = false) {
        CompositionLocalProvider(LocalMediaClock provides DesignClock.frozen(Instant.ofEpochMilli(spec.nowMillis))) { content() }
    }
}

/** Where this package's shots go: android/build/shots/android/designsystem/<theme>-<lang>/ (never compared as app screens). */
internal val BubbleShotDir: File get() = File(ShotPaths.shotsDir, "android/designsystem")

/** The last bubble of a run of the same direction gets the tail (C15; ConversationViewModel.isTail). */
internal fun isTail(index: Int, messages: List<Message>): Boolean {
    val message = messages[index]
    if (message.isSystem) return false
    val next = messages.getOrNull(index + 1) ?: return true
    if (next.isSystem) return true
    return next.direction != message.direction
}

/**
 * A conversation column laid out like 5.1: gutters 14, rows 6 apart, a bubble's chips keeping
 * 14 below, the status sentence (6 above it) under [sentenceFor], the first row at [top], and the
 * composer at the bottom. Each bubble is tagged `msg.<id>`.
 */
@Composable
internal fun ConversationSpecimen(messages: List<Message>, top: Dp, sentenceFor: MessageId? = null, showsComposer: Boolean = true) {
    Box(Modifier.fillMaxSize().background(U2Theme.colors.bg)) {
        Column(
            Modifier.offset(y = top).padding(horizontal = U2Spacing.chatGutter),
            verticalArrangement = Arrangement.spacedBy(U2Spacing.bubbleGap),
        ) {
            messages.forEachIndexed { index, message ->
                Column {
                    if (message.isSystem) {
                        MessageBubbleView(message, Modifier.testTag("msg.${message.id}"))
                    } else {
                        Box(Modifier.fillMaxWidth(), contentAlignment = if (message.isOutgoing) Alignment.TopEnd else Alignment.TopStart) {
                            MessageBubbleView(message, Modifier.testTag("msg.${message.id}"), isTail = isTail(index, messages))
                        }
                    }
                    val status = message.status
                    if (message.id == sentenceFor && status != null) {
                        MessageStatusSentence(status, Modifier.padding(top = U2Spacing.bubbleGap).testTag("sentence"))
                    }
                }
            }
        }
        if (showsComposer) {
            Composer(text = "", onTextChange = {}, onSend = {}, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().testTag("composer"))
        }
    }
}

/**
 * Frame checks against a design layout that report every mismatch together. A check compares the
 * listed edges of a node (found by tag, text or description in the unmerged tree) with an element's
 * frame, optionally adjusted (a caption measured without its padding, a bubble with its chip room).
 */
internal class FrameChecks(private val compose: SemanticsNodeInteractionsProvider, private val layout: LayoutSpec) {
    private val problems = mutableListOf<String>()
    private var count = 0

    enum class Edge { X, Y, W, H, Right, Bottom }

    fun tag(tag: String, element: String, tolerance: Float = 1f, edges: Set<Edge> = All, adjust: (Frame) -> Frame = { it }) =
        check("tag $tag", hasTestTag(tag), element, tolerance, edges, adjust)

    fun text(text: String, element: String, tolerance: Float = 1f, edges: Set<Edge> = All, adjust: (Frame) -> Frame = { it }) =
        check("text '$text'", hasText(text), element, tolerance, edges, adjust)

    fun description(description: String, element: String, tolerance: Float = 1f, edges: Set<Edge> = All, adjust: (Frame) -> Frame = { it }) =
        check("description '$description'", hasContentDescription(description), element, tolerance, edges, adjust)

    fun check(label: String, matcher: SemanticsMatcher, element: String, tolerance: Float, edges: Set<Edge>, adjust: (Frame) -> Frame) {
        count++
        val expected = adjust(layout[element].frame)
        val node = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
        if (node == null) {
            problems += "$element ($label): no node"
            return
        }
        val actual = node.frame()
        val deltas = edges.mapNotNull { edge ->
            val d = when (edge) {
                Edge.X -> actual.x - expected.x
                Edge.Y -> actual.y - expected.y
                Edge.W -> actual.w - expected.w
                Edge.H -> actual.h - expected.h
                Edge.Right -> actual.right - expected.right
                Edge.Bottom -> actual.bottom - expected.bottom
            }
            if (abs(d) > tolerance + 0.001f) "${edge.name.lowercase()}${if (d > 0) "+" else ""}${"%.2f".format(d)}" else null
        }
        if (deltas.isNotEmpty()) problems += "$element ($label): expected [$expected], got [$actual], off by ${deltas.joinToString(" ")}"
        val worst = edges.maxOf { edge ->
            abs(
                when (edge) {
                    Edge.X -> actual.x - expected.x
                    Edge.Y -> actual.y - expected.y
                    Edge.W -> actual.w - expected.w
                    Edge.H -> actual.h - expected.h
                    Edge.Right -> actual.right - expected.right
                    Edge.Bottom -> actual.bottom - expected.bottom
                },
            )
        }
        largest = maxOf(largest, worst)
    }

    /** The largest difference of any checked edge, in dp. */
    var largest = 0f
        private set

    /** Fails with every mismatch found. */
    fun assertAll() {
        println("${layout.name}: $count frames checked, largest difference ${"%.2f".format(java.util.Locale.ROOT, largest)} dp")
        if (problems.isNotEmpty()) throw AssertionError("${problems.size} of $count frames differ from ${layout.name}:\n" + problems.joinToString("\n"))
    }

    companion object {
        val All = Edge.entries.toSet() - setOf(Edge.Right, Edge.Bottom)
        val Vertical = setOf(Edge.Y, Edge.H)
        val RightAndVertical = setOf(Edge.Right, Edge.Y, Edge.H)
    }
}

internal fun SemanticsNode.frame(): Frame {
    val density = layoutInfo.density.density
    return Frame(positionInWindow.x / density, positionInWindow.y / density, size.width / density, size.height / density)
}
