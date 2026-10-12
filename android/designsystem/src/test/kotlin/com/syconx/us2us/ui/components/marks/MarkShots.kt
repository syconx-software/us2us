package com.syconx.us2us.ui.components.marks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.shot.Frame
import com.syconx.us2us.shot.LayoutSpec
import com.syconx.us2us.shot.ShotEnvironment
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotPaths
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.env.LocalU2Clock
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.Shots
import java.io.File
import java.time.Instant
import kotlin.math.abs
import org.junit.Assert.assertTrue

/** The environment, output folder, geometry and pixel helpers of the mark tests. */
internal object MarkShots {
    /** The theme of the shot and the design's frozen clock. */
    val environment = ShotEnvironment { spec, content ->
        U2Theme(dark = spec.isDark, reduceMotion = false) {
            val clock = if (spec.frozenClock) DesignClock.frozen(Instant.ofEpochMilli(spec.nowMillis)) else DesignClock.Live
            CompositionLocalProvider(LocalU2Clock provides clock) { content() }
        }
    }

    /** Specimens and probes are not app screens: they go beside them, where the parity compare does not collect them. */
    val outputDir = File(ShotPaths.shotsDir, "android/designsystem/marks")

    fun host(): ShotHost = ShotHost(environment = environment, outputDir = outputDir)

    /** The drawn reference of [screen] (`4.1-sohbet-listesi`, `urgent/5.1-sohbet-urgent`) in [theme]. */
    fun reference(screen: String, theme: ShotTheme): File {
        val urgent = screen.startsWith("urgent/")
        val path = when {
            theme.isDark -> "reference/$screen.png"
            urgent -> "reference/$screen-light.png"
            else -> "reference/light/$screen.png"
        }
        return File(ShotPaths.designDir, path)
    }

    fun SemanticsNode.frame(): Frame {
        val density = layoutInfo.density.density
        return Frame(positionInWindow.x / density, positionInWindow.y / density, size.width / density, size.height / density)
    }

    /** The window frame (dp) of the [index]th node matching [matcher]. */
    fun SemanticsNodeInteractionsProvider.frameOf(matcher: SemanticsMatcher, merged: Boolean = false, index: Int = 0): Frame {
        val nodes = onAllNodes(matcher, useUnmergedTree = !merged).fetchSemanticsNodes()
        check(nodes.size > index) { "${nodes.size} nodes match ${matcher.description}" }
        return nodes[index].frame()
    }

    /** The frame of the node tagged [tag]. */
    fun SemanticsNodeInteractionsProvider.frameOf(tag: String): Frame = frameOf(hasTestTag(tag))

    /**
     * Compares measured frames with layout elements (label → (frame, element id)) and reports every
     * mismatch beyond [tolerance] dp at once.
     */
    fun assertFrames(layout: LayoutSpec, frames: Map<String, Pair<Frame, String>>, tolerance: Float = 1f) {
        val problems = frames.mapNotNull { (label, pair) ->
            val (actual, element) = pair
            mismatch(label, layout[element].frame, actual, tolerance)?.let { "$it ($element)" }
        }
        if (problems.isNotEmpty()) throw AssertionError("${problems.size} of ${frames.size} frames differ from ${layout.name}:\n" + problems.joinToString("\n"))
    }

    /** [actual] is within [tolerance] dp of [expected] (each of x, y, w, h). */
    fun assertNear(label: String, expected: Frame, actual: Frame, tolerance: Float = 1f) {
        mismatch(label, expected, actual, tolerance)?.let { throw AssertionError(it) }
    }

    private fun mismatch(label: String, expected: Frame, actual: Frame, tolerance: Float): String? {
        val deltas = listOf(
            "x" to actual.x - expected.x,
            "y" to actual.y - expected.y,
            "w" to actual.w - expected.w,
            "h" to actual.h - expected.h,
        ).filter { abs(it.second) > tolerance + 0.001f }
        if (deltas.isEmpty()) return null
        return "$label: expected [$expected], got [$actual] — " + deltas.joinToString(" ") { (k, d) -> "$k %+.2f".format(d) }
    }

    /** Places content at a design position (dp). */
    fun Modifier.at(x: Number, y: Number): Modifier = offset(x.toFloat().dp, y.toFloat().dp)

    /** A region of the 1206 × 2622 px reference, given in dp. */
    fun region(x: Number, y: Number, w: Number, h: Number): IntArray =
        intArrayOf(px(x), px(y), px(w), px(h))

    private fun px(value: Number): Int = (value.toFloat() * 3f).toInt()

    /**
     * Shoots [content] over `bg` as `probe-<screen>` and compares each region with the same pixels of
     * the reference; a region far from it fails (a broken render), the side-by-sides are for review.
     */
    fun probe(
        host: ShotHost,
        screen: String,
        theme: ShotTheme,
        regions: List<IntArray>,
        limit: Double = 12.0,
        surface: Boolean = false,
        content: @Composable BoxScope.() -> Unit,
    ): List<Shots.Diff> {
        val name = "probe-" + screen.substringAfter('/')
        val shot = host.shoot(name, theme, "tr") {
            // Sheets (4.2, 5.4, 5.5) are `surface`; screens are `bg`.
            val fill = if (surface) U2Theme.colors.surface else U2Theme.colors.bg
            Box(Modifier.fillMaxSize().background(fill)) { content() }
        }
        val reference = reference(screen, theme)
        val report = StringBuilder()
        val diffs = regions.mapIndexed { index, region ->
            val diff = Shots.compare(shot, reference, region, "marks-$name-${theme.raw}-$index")
            report.append("$name ${theme.raw} region $index ${region.joinToString(",")}: $diff\n")
            diff
        }
        print(report)
        File(outputDir, "$name-${theme.raw}.txt").apply { parentFile?.mkdirs() }.writeText(report.toString())
        val worst = diffs.maxOf { it.meanAbsolute }
        assertTrue("$name ${theme.raw}: a region is far from the reference\n$report", worst < limit)
        return diffs
    }
}
