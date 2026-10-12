package com.syconx.us2us.ui.media

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.ui.bubbles.StateCrossFade
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.theme.U2BackdropSource
import com.syconx.us2us.ui.theme.U2BackdropStyle
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2Backdrop
import com.syconx.us2us.ui.theme.u2InnerStroke
import kotlin.math.max
import kotlin.math.min

// The media states of 5.2 (proposed: “downloading — progress ring”) and the glass controls of
// the viewer and the player (5.6 language): one glass circle, one ring, one state glyph, shared
// by the bubbles, the photo viewer and the video player.

/**
 * The 5.2 video button: a circle of `#0c0d16@45%` over an 8 dp backdrop blur with a 1 dp
 * `#f5f4ff@35%` inner ring (46 in the bubble, 64 in the player), its content centred and drawn
 * in `onMedia` (`#f5f4ff`). Pass the media under it as [source] (marked with
 * `u2BackdropSource`) for the real blur; without it only the tint is drawn.
 */
@Composable
fun MediaGlassCircle(
    modifier: Modifier = Modifier,
    diameter: Dp = 46.dp,
    source: U2BackdropSource? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(diameter)
            .u2Backdrop(U2BackdropStyle.MediaButton, CircleShape, source)
            .u2InnerStroke(U2Theme.colors.onMedia.copy(alpha = 0.35f), 1.dp, CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The outline a [MediaProgressRing] runs along: both start at 12 o'clock and run clockwise. */
@Immutable
sealed interface RingShape {
    /** The path in a box of [size] px. */
    fun path(size: Size, density: Density): Path

    /** A circle (the play button, the glass circle). */
    data object TopStartCircle : RingShape {
        override fun path(size: Size, density: Density): Path {
            val radius = min(size.width, size.height) / 2f
            val cx = size.width / 2f
            val cy = size.height / 2f
            val oval = Rect(cx - radius, cy - radius, cx + radius, cy + radius)
            return Path().apply {
                moveTo(cx, cy - radius)
                arcTo(oval, -90f, 180f, false)
                arcTo(oval, 90f, 180f, false)
            }
        }
    }

    /** A rounded rectangle (the file tile's ring), so a trimmed ring fills like a clock hand. */
    data class TopStartRoundedRect(val cornerRadius: Dp) : RingShape {
        override fun path(size: Size, density: Density): Path {
            val r = min(with(density) { cornerRadius.toPx() }, min(size.width / 2f, size.height / 2f))
            val w = size.width
            val h = size.height
            fun oval(cx: Float, cy: Float) = Rect(cx - r, cy - r, cx + r, cy + r)
            return Path().apply {
                moveTo(w / 2f, 0f)
                lineTo(w - r, 0f)
                arcTo(oval(w - r, r), -90f, 90f, false)
                lineTo(w, h - r)
                arcTo(oval(w - r, h - r), 0f, 90f, false)
                lineTo(r, h)
                arcTo(oval(r, h - r), 90f, 90f, false)
                lineTo(0f, r)
                arcTo(oval(r, r), 180f, 90f, false)
                close()
            }
        }
    }
}

/**
 * A transfer in progress: a 2 dp ring, the track at 25 %, the done part solid, from 12 o'clock
 * clockwise. Without a [fraction] a quarter of the ring travels round once per 1.2 s; under
 * reduce motion (or a frozen clock) it stands still. A change of fraction glides (0.24 s).
 */
@Composable
fun MediaProgressRing(
    fraction: Double?,
    modifier: Modifier = Modifier,
    shape: RingShape = RingShape.TopStartCircle,
    lineWidth: Dp = 2.dp,
    tint: Color = U2Theme.colors.onMedia,
    track: Color = U2Theme.colors.onMedia.copy(alpha = 0.25f),
) {
    val reduceMotion = LocalReduceMotion.current
    val frozen = LocalMediaClock.current.isFrozen
    val shown = fraction?.let { max(0.02, min(1.0, it)).toFloat() }
    val animatedFraction by animateFloatAsState(shown ?: 0f, U2Motion.resolve(U2Motion.micro, reduceMotion).spec(), label = "ring")
    val travelling = shown == null && !reduceMotion && !frozen
    // Read while drawing, so the travelling arc redraws without recomposing.
    val phase: State<Float>? = if (travelling) {
        rememberInfiniteTransition(label = "ringTravel")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(1_200, easing = LinearEasing)), label = "phase")
    } else {
        null
    }
    Canvas(modifier.clearAndSetSemantics {}) {
        val path = shape.path(size, this)
        val width = lineWidth.toPx()
        drawPath(path, track, style = Stroke(width))
        val stroke = Stroke(width, cap = StrokeCap.Round)
        when {
            shown != null -> drawTrimmed(path, 0f, animatedFraction, tint, stroke)
            phase != null -> {
                val start = phase.value
                val end = start + 0.25f
                drawTrimmed(path, start, min(1f, end), tint, stroke)
                if (end > 1f) drawTrimmed(path, 0f, end - 1f, tint, stroke)
            }
            else -> drawTrimmed(path, 0f, 0.25f, tint, stroke)
        }
    }
}

/** Strokes the part of [path] between the fractions [from] and [to] of its length. */
private fun DrawScope.drawTrimmed(path: Path, from: Float, to: Float, color: Color, stroke: Stroke) {
    if (to <= from) return
    val measure = PathMeasure()
    measure.setPath(path, false)
    val length = measure.length
    val segment = Path()
    if (measure.getSegment(from * length, to * length, segment, true)) drawPath(segment, color, style = stroke)
}

/** What a glass circle holds when its media is ready: play, or nothing (the photo bubble). */
enum class ReadyGlyph { Play, None }

/**
 * What the glass circle holds for a media state: play (ready), download (still on the relay),
 * the ring (downloading, or a sent video being prepared), the warning (gone, or the video could
 * not be prepared). [diameter] is the circle's: 46 in a bubble (glyph 19), 64 in the player (26).
 * The ink is `onMedia`.
 */
@Composable
fun MediaStateGlyph(
    availability: MediaAvailability,
    modifier: Modifier = Modifier,
    diameter: Dp = 46.dp,
    readyGlyph: ReadyGlyph = ReadyGlyph.Play,
) {
    val glyphSize = if (diameter >= 60.dp) 26.dp else 19.dp
    val ink = U2Theme.colors.onMedia
    val kind = when (availability) {
        MediaAvailability.Ready -> StateKind.Ready
        MediaAvailability.Remote -> StateKind.Remote
        is MediaAvailability.Downloading, MediaAvailability.Preparing -> StateKind.Downloading
        MediaAvailability.Held, MediaAvailability.Unavailable, MediaAvailability.PreparationFailed -> StateKind.Unavailable
    }
    val fraction = (availability as? MediaAvailability.Downloading)?.fraction
    StateCrossFade(kind, modifier) { shown ->
        when (shown) {
            StateKind.Ready -> if (readyGlyph == ReadyGlyph.Play) {
                PhosphorIcon(PhosphorGlyph.Play, glyphSize, weight = PhosphorWeight.Fill, tint = ink)
            }
            StateKind.Remote -> MediaGlyphIcon(MediaGlyph.Download, glyphSize, tint = ink)
            StateKind.Downloading -> MediaProgressRing(fraction, Modifier.size(diameter).padding(diameter * 0.17f))
            StateKind.Unavailable -> PhosphorIcon(PhosphorGlyph.WarningCircle, glyphSize, Modifier.alpha(0.72f), tint = ink)
        }
    }
}

private enum class StateKind { Ready, Remote, Downloading, Unavailable }
