package com.syconx.us2us.ui.motion

import android.os.Build
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.theme.U2Effects
import com.syconx.us2us.ui.tokens.U2Radius
import kotlin.math.roundToInt

/**
 * A soft transition: how content enters and leaves. [enter] and [exit] carry the opacity,
 * offset and scale; the slight blur (not expressible as an Enter/ExitTransition) is added
 * inside `AnimatedVisibility` with [u2TransitionBlur]. Under reduce motion only the
 * opacity remains, on the short cross-fade.
 */
@Immutable
class U2Transition(
    val enter: EnterTransition,
    val exit: ExitTransition,
    /** Blur at the hidden end (0 when reduce motion is on). */
    val blur: Dp,
    /** The timing actually used (the cross-fade under reduce motion). */
    val timing: U2Timing,
)

/** The design's transitions (`AnyTransition.u2*` on iOS). */
object U2Transitions {
    /** Fade + 8 dp rise + slight blur: new content (messages, cards, captions). */
    @Composable
    fun rise(timing: U2Timing = U2Motion.state): U2Transition = soft(DpOffset(0.dp, 8.dp), blur = 4.dp, timing = timing)

    /** Fade + 8 dp drop + slight blur: warnings sliding in from above. */
    @Composable
    fun drop(timing: U2Timing = U2Motion.state): U2Transition = soft(DpOffset(0.dp, (-8).dp), blur = 4.dp, timing = timing)

    /** Fade + slight blur, no movement. */
    @Composable
    fun fade(timing: U2Timing = U2Motion.state): U2Transition = soft(DpOffset.Zero, blur = 3.dp, timing = timing)

    /** Fade + scale from 0.96 (menus, the reaction bar). */
    @Composable
    fun pop(timing: U2Timing = U2Motion.state): U2Transition = soft(DpOffset.Zero, blur = 2.dp, scale = 0.96f, timing = timing)

    /**
     * `screenPush`: cross-fade + 16 dp horizontal drift, 0.5 s soft. Both the inserted and the
     * removed screen sit 16 dp towards the end while hidden, as on iOS.
     */
    @Composable
    fun push(timing: U2Timing = U2Motion.screen): U2Transition = soft(DpOffset(16.dp, 0.dp), blur = 0.dp, timing = timing)

    /**
     * A configurable soft transition: opacity always; offset, blur and scale unless reduce
     * motion is on. The same hidden state is used for entering and leaving.
     */
    @Composable
    fun soft(offset: DpOffset, blur: Dp, scale: Float = 1f, timing: U2Timing = U2Motion.state): U2Transition {
        val reduceMotion = LocalReduceMotion.current
        val density = LocalDensity.current
        return remember(offset, blur, scale, timing, reduceMotion, density) {
            val t = U2Motion.resolve(timing, reduceMotion)
            var enter = fadeIn(t.spec())
            var exit = fadeOut(t.spec())
            if (!reduceMotion) {
                val shift = with(density) { IntOffset(offset.x.roundToPx(), offset.y.roundToPx()) }
                if (shift != IntOffset.Zero) {
                    enter += slideIn(t.spec()) { shift }
                    exit += slideOut(t.spec()) { shift }
                }
                if (scale != 1f) {
                    enter += scaleIn(t.spec(), initialScale = scale)
                    exit += scaleOut(t.spec(), targetScale = scale)
                }
            }
            U2Transition(enter, exit, blur = if (reduceMotion) 0.dp else blur, timing = t)
        }
    }

    /**
     * 4.3 “Engelle / Sil”, 5.3 “Benden sil”: the item fades while its height collapses to nothing
     * from the bottom, and what follows glides up into its place. 0.36 s soft. For the rounded
     * shrinking edge use [u2CollapseProgress] on an item that collapses before it is removed.
     */
    @Composable
    fun collapse(timing: U2Timing = U2Motion.state): ExitTransition {
        val t = U2Motion.resolve(timing, LocalReduceMotion.current)
        return remember(t) { fadeOut(t.spec()) + shrinkVertically(t.spec(), shrinkTowards = Alignment.Top, clip = true) }
    }
}

/**
 * The blur part of a [U2Transition], inside `AnimatedVisibility`: blurred by
 * [U2Transition.blur] while hidden, sharp when shown (RenderEffect, API 31+).
 */
@Composable
fun AnimatedVisibilityScope.u2TransitionBlur(transition: U2Transition): Modifier {
    if (transition.blur <= 0.dp || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Modifier
    val hidden by this.transition.animateFloat(
        transitionSpec = { transition.timing.spec() },
        label = "u2TransitionBlur",
    ) { state -> if (state == EnterExitState.Visible) 0f else 1f }
    return Modifier.graphicsLayer {
        val sigma = transition.blur.toPx() * hidden
        renderEffect = if (sigma > 0.25f) {
            val radius = U2Effects.blurRadiusForSigma(sigma)
            BlurEffect(radius, radius, TileMode.Decal)
        } else {
            null
        }
    }
}

/**
 * The `u2Collapse` look at a given [progress] (1 = full height, 0 = collapsed and transparent),
 * for an item that collapses *before* it is removed (5.1 “Benden sil”), so its height leaves the
 * layout gradually. The item is pinned to the top, and clipped with [cornerRadius] only while
 * collapsing, so at rest nothing that overhangs it (a reaction chip, a shadow) is cut.
 */
fun Modifier.u2CollapseProgress(progress: Float, cornerRadius: Dp = U2Radius.cardL): Modifier {
    val p = progress.coerceIn(0f, 1f)
    return this
        .graphicsLayer {
            alpha = p
            if (p < 1f) {
                clip = true
                shape = RoundedCornerShape(cornerRadius)
            }
        }
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            val height = (placeable.height * p).roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(placeable.width, height) { placeable.place(0, 0) }
        }
}
