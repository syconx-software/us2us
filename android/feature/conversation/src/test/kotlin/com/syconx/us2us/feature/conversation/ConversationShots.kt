package com.syconx.us2us.feature.conversation

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.harness.HarnessUiState
import com.syconx.us2us.fixtures.FixturesMessengerService
import com.syconx.us2us.shot.Frame
import com.syconx.us2us.shot.LayoutSpec
import com.syconx.us2us.shot.ShotEnvironment
import com.syconx.us2us.ui.env.PreviewAppModel
import com.syconx.us2us.ui.env.U2PreviewRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.time.Duration

/**
 * The world of one conversation shot: the fixtures service (frozen at the design moment, in the
 * shot's language, urgent or not), the app model around it and the harness state the screen reads.
 * Make it before rendering; the environment picks it up.
 */
internal class ConversationRig(english: Boolean = false, ghostUrgent: Boolean = false) {
    val fixtures = FixturesMessengerService(
        hasAccount = true,
        clock = DesignClock.frozen(DesignClock.referenceDate),
        ghostUrgent = ghostUrgent,
        english = english,
        latency = Duration.ZERO,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )
    val model = PreviewAppModel(fixtures, clock = fixtures.clock)
    var harness: HarnessUiState? = null

    /** Plans [state] of [chat] like the app's harness registry: the service side now, the screen side once it shows. */
    fun plan(chat: String, state: String): ConversationHarnessState {
        val plan = checkNotNull(ConversationHarness.plan(chat, state, fixtures))
        runBlocking { plan.prepare?.invoke() }
        val ui = plan.uiState as ConversationHarnessState
        harness = ui
        return ui
    }
}

/** The environment of this module's shots: the theme of the shot, design insets, the rig's app model and harness state. */
internal fun conversationShots(rig: () -> ConversationRig) = ShotEnvironment { spec, content ->
    val current = rig()
    U2PreviewRoot(current.model, dark = spec.isDark, designInsets = spec.designInsets, harnessState = current.harness) { content() }
}

/**
 * Frame checks against a design layout that report every mismatch together. A check compares the
 * listed edges of a node (found by tag, text or description in the unmerged tree) with an
 * element's frame, optionally adjusted.
 */
internal class FrameChecks(private val compose: SemanticsNodeInteractionsProvider, private val layout: LayoutSpec) {
    private val problems = mutableListOf<String>()
    private var count = 0

    /** The largest difference of any checked edge, in dp. */
    var largest = 0f
        private set

    enum class Edge { X, Y, W, H, Right, Bottom }

    fun tag(tag: String, element: String, edges: Set<Edge> = All, tolerance: Float = 1f, adjust: (Frame) -> Frame = { it }) =
        check("tag $tag", hasTestTag(tag), element, edges, tolerance, adjust)

    fun text(text: String, element: String, edges: Set<Edge> = All, tolerance: Float = 1f, adjust: (Frame) -> Frame = { it }) =
        check("text '$text'", hasText(text), element, edges, tolerance, adjust)

    fun description(description: String, element: String, edges: Set<Edge> = All, tolerance: Float = 1f, adjust: (Frame) -> Frame = { it }) =
        check("description '$description'", hasContentDescription(description), element, edges, tolerance, adjust)

    fun check(label: String, matcher: SemanticsMatcher, element: String, edges: Set<Edge>, tolerance: Float, adjust: (Frame) -> Frame) {
        count++
        val expected = adjust(layout[element].frame)
        val node = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
        if (node == null) {
            problems += "$element ($label): no node"
            return
        }
        val actual = node.frame()
        val deltas = edges.associateWith { edge ->
            when (edge) {
                Edge.X -> actual.x - expected.x
                Edge.Y -> actual.y - expected.y
                Edge.W -> actual.w - expected.w
                Edge.H -> actual.h - expected.h
                Edge.Right -> actual.x + actual.w - (expected.x + expected.w)
                Edge.Bottom -> actual.y + actual.h - (expected.y + expected.h)
            }
        }
        largest = maxOf(largest, deltas.values.maxOf { abs(it) })
        val off = deltas.filter { abs(it.value) > tolerance + 0.001f }
        if (off.isNotEmpty()) {
            problems += "$element ($label): expected [$expected], got [$actual], off by " +
                off.entries.joinToString(" ") { (edge, d) -> "${edge.name.lowercase()}${if (d > 0) "+" else ""}${"%.2f".format(java.util.Locale.ROOT, d)}" }
        }
    }

    /** Fails with every mismatch found. */
    fun assertAll() {
        println("${layout.name}: $count frames checked, largest difference ${"%.2f".format(java.util.Locale.ROOT, largest)} dp")
        if (problems.isNotEmpty()) throw AssertionError("${problems.size} of $count frames differ from ${layout.name}:\n" + problems.joinToString("\n"))
    }

    companion object {
        val All = setOf(Edge.X, Edge.Y, Edge.W, Edge.H)
        val Position = setOf(Edge.X, Edge.Y)
        val Vertical = setOf(Edge.Y, Edge.H)
        val RightAndVertical = setOf(Edge.Right, Edge.Y, Edge.H)
    }
}

/** The node's layout bounds in window dp. */
internal fun SemanticsNode.frame(): Frame {
    val density = layoutInfo.density.density
    return Frame(positionInWindow.x / density, positionInWindow.y / density, size.width / density, size.height / density)
}
