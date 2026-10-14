package com.syconx.us2us.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.u2Spec
import com.syconx.us2us.ui.theme.U2Theme
import kotlin.math.ceil

/**
 * C19 — every rule in this design is dotted, never solid: anti-aliased discs (solid to [radius],
 * fading to transparent at [edgeRadius]) repeated at a fixed [pitch], the first centre at pitch / 2
 * — exactly the CSS `radial-gradient(circle, C r, transparent edge) 0 50% / pitch h repeat-x`.
 */
@Immutable
data class DotPattern(val radius: Dp, val edgeRadius: Dp, val pitch: Dp, val thickness: Dp) {
    companion object {
        /** List separators, chat-header rule, menu separators: r .75 / 1.1, pitch 5, 2 high. */
        val Rule = DotPattern(radius = 0.75.dp, edgeRadius = 1.1.dp, pitch = 5.dp, thickness = 2.dp)

        /** Date separator (4.4): r .8 / 1.2, pitch 5. */
        val DateRule = DotPattern(radius = 0.8.dp, edgeRadius = 1.2.dp, pitch = 5.dp, thickness = 2.dp)

        /** Message-info timeline (5.4, vertical): r .9 / 1.3, pitch 5, 2 wide. */
        val Timeline = DotPattern(radius = 0.9.dp, edgeRadius = 1.3.dp, pitch = 5.dp, thickness = 2.dp)

        /** Progress meters (1.3 hours, 7.3 restore): r 2.5 / 3, pitch 12, 8 high. */
        val Progress = DotPattern(radius = 2.5.dp, edgeRadius = 3.dp, pitch = 12.dp, thickness = 8.dp)

        /** Password strength (7.2): r 2 / 2.5, pitch 8, 6 high. */
        val Strength = DotPattern(radius = 2.dp, edgeRadius = 2.5.dp, pitch = 8.dp, thickness = 6.dp)

        /** Skeleton lines in the 1.2 illustration: r 2 / 2.5, pitch 8, 6 high. */
        val Skeleton = DotPattern(radius = 2.dp, edgeRadius = 2.5.dp, pitch = 8.dp, thickness = 6.dp)
    }
}

/**
 * Draws a row (or column) of [pattern] dots in [rect]: [count] dots, or as many as start inside it
 * (CSS `repeat-x` paints the partial tile at the end too, clipped by the box).
 */
fun DrawScope.drawDots(rect: Rect, pattern: DotPattern, color: Color, vertical: Boolean = false, count: Int? = null) {
    val pitch = pattern.pitch.toPx()
    val length = if (vertical) rect.height else rect.width
    val tiles = count ?: ceil(length / pitch - DOT_EPSILON).toInt()
    if (tiles <= 0 || color.alpha <= 0f) return
    val brush = dotBrush(pattern, color)
    val edge = pattern.edgeRadius.toPx()
    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
        for (i in 0 until tiles) {
            val along = pitch * (i + 0.5f)
            val center = if (vertical) Offset(rect.center.x, rect.top + along) else Offset(rect.left + along, rect.center.y)
            translate(center.x, center.y) { drawCircle(brush, radius = edge, center = Offset.Zero) }
        }
    }
}

/** One disc's fill, centred on the origin: solid to the radius, then fading out to the edge. */
private fun DrawScope.dotBrush(pattern: DotPattern, color: Color): Brush = Brush.radialGradient(
    0f to color,
    pattern.radius.toPx() / pattern.edgeRadius.toPx() to color,
    1f to color.copy(alpha = 0f),
    center = Offset.Zero,
    radius = pattern.edgeRadius.toPx(),
)

/** Lengths within this fraction of a tile do not start another dot (float noise on whole tiles). */
private const val DOT_EPSILON = 1e-4f

/** A dotted rule (C19), horizontal by default, filling the offered length. */
@Composable
fun DottedRule(
    modifier: Modifier = Modifier,
    color: Color = U2Theme.colors.dotRule,
    pattern: DotPattern = DotPattern.Rule,
    orientation: Orientation = Orientation.Horizontal,
) {
    val vertical = orientation == Orientation.Vertical
    val size = if (vertical) Modifier.fillMaxHeight().width(pattern.thickness) else Modifier.fillMaxWidth().height(pattern.thickness)
    Box(
        modifier
            .then(size)
            .clearAndSetSemantics {}
            .drawBehind { drawDots(Rect(Offset.Zero, this.size), pattern, color, vertical) },
    )
}

/**
 * A dotted meter: a track of unlit dots with the first [progress] (0…1, as the CSS overlay's width)
 * in [litColor] (1.3 Ghost hours, 7.3 restore progress, 7.2 password strength). Animate [progress]
 * and the lit part sweeps left → right.
 */
@Composable
fun DottedMeter(
    progress: Float,
    modifier: Modifier = Modifier,
    pattern: DotPattern = DotPattern.Progress,
    trackColor: Color = U2Theme.colors.dotTrack,
    litColor: Color = U2Theme.colors.accent400,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(pattern.thickness)
            .clearAndSetSemantics {}
            .drawWithCache {
                onDrawBehind {
                    drawDots(Rect(Offset.Zero, size), pattern, trackColor)
                    val lit = size.width * progress.coerceIn(0f, 1f)
                    if (lit > 0f) drawDots(Rect(0f, 0f, lit, size.height), pattern, litColor)
                }
            },
    )
}

/**
 * The 1.3 hour meter: one dot per hour of the Ghost period, lit for the hours still remaining; the
 * right-most lit dot fades to the track over 0.8 s when an hour elapses.
 */
@Composable
fun DottedHourMeter(totalHours: Int, litHours: Int, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val pattern = DotPattern.Progress
    Row(modifier.clearAndSetSemantics {}) {
        for (index in 0 until totalHours) {
            val color by animateColorAsState(
                targetValue = if (index < litHours) colors.accent400 else colors.dotTrack,
                animationSpec = u2Spec(U2Motion.ambient),
                label = "hourDot",
            )
            Box(
                Modifier
                    .size(pattern.pitch, pattern.thickness)
                    .drawBehind { drawDots(Rect(Offset.Zero, size), pattern, color, count = 1) },
            )
        }
    }
}
