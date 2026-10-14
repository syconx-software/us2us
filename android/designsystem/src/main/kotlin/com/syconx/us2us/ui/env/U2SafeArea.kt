package com.syconx.us2us.ui.env

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import com.syconx.us2us.ui.tokens.U2Size

/**
 * The device's safe-area insets, published by `U2Screen` so components can place themselves at
 * design coordinates. The design measures everything from the *physical* top of a 402 × 874 screen
 * with a 62 dp status bar and a 34 dp home indicator (headers sit inside the status-bar band, e.g.
 * icon buttons at y 56); a component sits at `top + (designY − 62)` on any phone.
 */
@Immutable
data class U2SafeArea(val top: Dp, val bottom: Dp, val statusBarReference: Dp = U2Size.designStatusBar) {
    /**
     * Maps a design y (from the physical top) to this device. Never negative: a phone's status bar
     * is shorter than the design's 62, and before the first insets arrive it is 0.
     *
     * The design's headers sit inside its 62 dp status-bar band, below the iPhone's clock and
     * island. An Android status bar is a thin strip with nothing below it, so on a phone the band
     * is taken as [ANDROID_STATUS_BAR_REFERENCE]: headers keep a gap under the status bar instead
     * of touching it.
     */
    fun top(designY: Dp): Dp = (top + (designY - statusBarReference)).coerceAtLeast(0.dp)

    /** Maps a design bottom padding (from the physical bottom) to this device; never negative. */
    fun bottom(designPadding: Dp): Dp = (bottom + (designPadding - U2Size.designHomeIndicator)).coerceAtLeast(0.dp)

    companion object {
        /** The design's own insets: 62 at the top, 34 at the bottom. */
        val Design = U2SafeArea(top = U2Size.designStatusBar, bottom = U2Size.designHomeIndicator)

        /** The part of the design's status-bar band a phone's status bar stands for (see [top]). */
        val ANDROID_STATUS_BAR_REFERENCE: Dp = 50.dp
    }
}

/**
 * Design-insets mode: the safe area is the design's 62 / 34 whatever the window reports (screenshot
 * tests, and the debug `U2DesignInsets` launch extra), so geometry equals the iOS frames.
 */
val LocalDesignInsets = staticCompositionLocalOf { false }

/** The safe area of the current `U2Screen` (the design's own until one is laid out). */
val LocalU2SafeArea = compositionLocalOf { U2SafeArea.Design }

/**
 * The window's safe area: the status bar (with any display cutout) and the navigation bar, or the
 * design's 62 / 34 in design-insets mode. The keyboard is not part of it.
 */
@Composable
fun windowSafeArea(): U2SafeArea {
    if (LocalDesignInsets.current) return U2SafeArea.Design
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.union(WindowInsets.displayCutout).getTop(density)
    val bottom = WindowInsets.navigationBars.getBottom(density)
    return with(density) {
        U2SafeArea(top = top.toDp(), bottom = bottom.toDp(), statusBarReference = U2SafeArea.ANDROID_STATUS_BAR_REFERENCE)
    }
}

/** The safe area components place themselves with (iOS `\.u2SafeArea`). */
val currentSafeArea: U2SafeArea
    @Composable @ReadOnlyComposable
    get() = LocalU2SafeArea.current

/** Pads the top so the content starts at design y [designY] (e.g. 56 for header buttons). */
fun Modifier.u2Top(designY: Dp): Modifier = this then DesignPaddingElement(designY, isTop = true)

/**
 * Pads the bottom by a design bottom padding (e.g. 44 under a CTA), adjusted for this device's
 * navigation bar; never less than the padding above the design's 34 dp home indicator.
 */
fun Modifier.u2Bottom(designPadding: Dp): Modifier = this then DesignPaddingElement(designPadding, isTop = false)

private data class DesignPaddingElement(val design: Dp, val isTop: Boolean) : ModifierNodeElement<DesignPaddingNode>() {
    override fun create() = DesignPaddingNode(design, isTop)

    override fun update(node: DesignPaddingNode) {
        node.design = design
        node.isTop = isTop
        node.invalidateMeasurement()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = if (isTop) "u2Top" else "u2Bottom"
        value = design
    }
}

private class DesignPaddingNode(var design: Dp, var isTop: Boolean) :
    Modifier.Node(), LayoutModifierNode, CompositionLocalConsumerModifierNode {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val safeArea = currentValueOf(LocalU2SafeArea)
        val pad = (if (isTop) max(0.dp, safeArea.top(design)) else u2BottomPadding(design, safeArea)).roundToPx()
        val inner = measurable.measure(constraints.offset(vertical = -pad))
        val height = constraints.constrainHeight(inner.height + pad)
        return layout(inner.width, height) { inner.place(0, if (isTop) pad else 0) }
    }
}

/** The bottom padding [u2Bottom] applies for [designPadding] in [safeArea]. */
fun u2BottomPadding(designPadding: Dp, safeArea: U2SafeArea): Dp =
    max(0.dp, max(designPadding - U2Size.designHomeIndicator, safeArea.bottom(designPadding)))
