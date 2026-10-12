package com.syconx.us2us.ui.components.atoms

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.shot.Frame
import com.syconx.us2us.shot.LayoutSpec
import com.syconx.us2us.shot.ShotEnvironment
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotPaths
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.env.LocalU2Clock
import com.syconx.us2us.ui.theme.U2Theme
import java.io.File
import java.time.Instant
import kotlin.math.abs

/** The environment, output folder and geometry helpers of the atom tests. */
internal object AtomShots {
    /** The theme of the shot and the design's frozen clock. */
    val environment = ShotEnvironment { spec, content ->
        U2Theme(dark = spec.isDark, reduceMotion = false) {
            val clock = if (spec.frozenClock) DesignClock.frozen(Instant.ofEpochMilli(spec.nowMillis)) else DesignClock.Live
            CompositionLocalProvider(LocalU2Clock provides clock) { content() }
        }
    }

    /** Specimens and probes are not app screens: they go beside them, where the parity compare does not collect them. */
    val outputDir = File(ShotPaths.shotsDir, "android/designsystem/atoms")

    fun host(): ShotHost = ShotHost(environment = environment, outputDir = outputDir)

    /** The drawn reference of [screen] in [theme]. */
    fun reference(screen: String, theme: ShotTheme): File =
        File(ShotPaths.designDir, if (theme.isDark) "reference/$screen.png" else "reference/light/$screen.png")

    /** The window frame (dp) of the single node matching [matcher] in the unmerged tree. */
    fun SemanticsNodeInteractionsProvider.frameOf(matcher: SemanticsMatcher): Frame {
        val nodes = onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
        check(nodes.size == 1) { "${nodes.size} nodes match ${matcher.description}" }
        return nodes.single().frame()
    }

    fun SemanticsNode.frame(): Frame {
        val density = layoutInfo.density.density
        return Frame(positionInWindow.x / density, positionInWindow.y / density, size.width / density, size.height / density)
    }

    /**
     * Compares measured frames with layout elements (label → (frame, element id)) and reports every
     * mismatch beyond [tolerance] dp at once.
     */
    fun assertFrames(layout: LayoutSpec, frames: Map<String, Pair<Frame, String>>, tolerance: Float = 1f) {
        val problems = frames.mapNotNull { (label, pair) ->
            val (actual, element) = pair
            val expected = layout[element].frame
            val deltas = listOf(
                "x" to actual.x - expected.x,
                "y" to actual.y - expected.y,
                "w" to actual.w - expected.w,
                "h" to actual.h - expected.h,
            ).filter { abs(it.second) > tolerance + 0.001f }
            if (deltas.isEmpty()) {
                null
            } else {
                "$label ($element): expected [$expected], got [$actual] — " + deltas.joinToString(" ") { (k, d) -> "$k %+.2f".format(d) }
            }
        }
        if (problems.isNotEmpty()) throw AssertionError("${problems.size} of ${frames.size} frames differ from ${layout.name}:\n" + problems.joinToString("\n"))
    }
}
