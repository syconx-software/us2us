package com.syconx.us2us.ui.bubbles

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.Message
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.media.LocalMediaClock
import com.syconx.us2us.ui.media.rememberClockTime
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.u2PressEffect
import com.syconx.us2us.ui.theme.U2BoxShadows
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2BoxShadow
import com.syconx.us2us.ui.theme.u2DropShadowFilter
import com.syconx.us2us.ui.theme.u2OuterRing
import com.syconx.us2us.ui.tokens.U2MotionTokens
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2ShadowTokens
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A shared place: padding 8 12 8 8: a 64 × 64 r12 dotted map with the pin, the title
 * `bubbleTitleS`, the locality `metaInBubble`.
 */
@Composable
fun LocationBubble(message: Message, name: String, locality: String, modifier: Modifier = Modifier, isTail: Boolean = false) {
    MessageBubble(
        message.direction,
        modifier,
        isTail = isTail,
        padding = PaddingValues(start = 8.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            DottedMap(showsPin = true, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(U2Radius.iconTileXL)))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                U2Text(name, U2TextStyles.bubbleTitleS, color = bubbleInk(message))
                U2Text(locality, U2TextStyles.metaInBubble, color = U2Theme.colors.textSecondary)
            }
        }
        BubbleMeta(message.sentAt, Modifier.bubbleTrailing(), status = message.status, disappearing = message.disappearing)
    }
}

/**
 * Live location: max 266, padding 5 5 8 5: a 256 × 92 r14 map with the pulsing live dot; the
 * footer “Canlı konum” with the minutes left (every 30 s) and, for the sender, the “Durdur” pill.
 */
@Composable
fun LiveLocationBubble(
    message: Message,
    expiresAt: Instant,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    isTail: Boolean = false,
    onStop: () -> Unit = {},
) {
    val colors = U2Theme.colors
    MessageBubble(message.direction, modifier, isTail = isTail, maxWidth = 266.dp, padding = BubblePadding.Media) {
        Box(Modifier.size(MapWidth, MapHeight).clip(RoundedCornerShape(U2Radius.bubbleMediaS))) {
            DottedMap(showsPin = false, modifier = Modifier.size(MapWidth, MapHeight))
            Box(Modifier.size(MapWidth, MapHeight).livePoint(), contentAlignment = Alignment.Center) {
                if (isActive) PulseRings()
                Box(
                    Modifier
                        .size(18.dp)
                        .u2BoxShadow(CircleShape, U2BoxShadows.glowLiveDot)
                        .u2OuterRing(colors.bg, 3.dp, CircleShape)
                        .background(colors.accent400, CircleShape),
                )
            }
        }
        Row(
            Modifier.padding(start = 6.dp, top = 2.dp, end = 4.dp).bubbleFill(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    PhosphorIcon(PhosphorGlyph.NavigationArrow, 15.dp, tint = colors.accent300)
                    U2Text(stringResource(R.string.common_location_live), U2TextStyles.bubbleTitleS.weight(500), color = bubbleInk(message))
                }
                val now = rememberClockTime(intervalMillis = 30_000)
                val minutes = max(0, ceil((expiresAt.toEpochMilli() - now.toEpochMilli()) / 60_000.0).toInt())
                U2Text(stringResource(R.string.common_location_live_remaining, minutes), U2TextStyles.monoFileMeta, color = colors.textSecondary)
            }
            if (isActive && message.isOutgoing) InlinePillButton(stringResource(R.string.common_location_live_stop), onStop)
        }
        BubbleMeta(message.sentAt, Modifier.padding(end = 6.dp).bubbleTrailing(), status = message.status, disappearing = message.disappearing)
    }
}

/**
 * Live location after sharing stopped or expired: the same bubble, the map grey and still, the
 * footer “Canlı konum paylaşımı bitti” in `text·68%` (inventory 5.2 › States).
 */
@Composable
fun EndedLiveLocationBubble(message: Message, modifier: Modifier = Modifier, isTail: Boolean = false) {
    val colors = U2Theme.colors
    MessageBubble(message.direction, modifier, isTail = isTail, maxWidth = 266.dp, padding = BubblePadding.Media) {
        Box(Modifier.size(MapWidth, MapHeight).clip(RoundedCornerShape(U2Radius.bubbleMediaS))) {
            DottedMap(showsPin = false, modifier = Modifier.size(MapWidth, MapHeight).alpha(0.55f), grayscale = true)
            Box(Modifier.size(MapWidth, MapHeight).livePoint(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(14.dp)
                        .u2OuterRing(colors.bg, 3.dp, CircleShape)
                        .background(colors.textSecondary, CircleShape),
                )
            }
        }
        Row(
            Modifier.padding(start = 6.dp, top = 2.dp, end = 4.dp).bubbleFill(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhosphorIcon(PhosphorGlyph.NavigationArrow, 15.dp, tint = colors.textSecondary)
            U2Text(stringResource(R.string.common_livelocation_ended), U2TextStyles.bubbleTitleS.weight(400), color = colors.textSecondary, maxLines = 2)
        }
        BubbleMeta(message.sentAt, Modifier.padding(end = 6.dp).bubbleTrailing(), status = message.status, disappearing = message.disappearing)
    }
}

private val MapWidth = 256.dp
private val MapHeight = 92.dp

/** Centres the content on the live point of the map (42 % across, 46 % down) without changing the box. */
private fun Modifier.livePoint(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        placeable.place(
            (placeable.width * 0.42f - placeable.width / 2f).toInt(),
            (placeable.height * 0.46f - placeable.height / 2f).toInt(),
        )
    }
}

/** “Durdur” inside the live-location bubble (5.2): 32 high, padding 0 13, a `bg·55%` capsule, Inter 500 13.5 `danger`. */
@Composable
private fun InlinePillButton(title: String, onClick: () -> Unit) {
    val colors = U2Theme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .u2PressEffect(source)
            .alpha(if (pressed) 0.7f else 1f)
            .height(32.dp)
            .background(colors.inlineButtonFill, CircleShape)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        U2Text(title, U2TextStyles.inlineButton, color = colors.danger, maxLines = 1, softWrap = false)
    }
}

/**
 * `pulseRing` around the live dot: two 116 dp circles with a 1.5 dp `accent400` stroke, each
 * scaling 0.15 → 1 while fading 0.9 → 0 over 2.8 s ease-out, forever, the second 1.4 s later.
 * A frozen clock holds them 2 s into the loop, as in the references; reduce motion hides them.
 */
@Composable
private fun PulseRings(diameter: Dp = 116.dp, color: Color = U2Theme.colors.accent400) {
    if (LocalReduceMotion.current) {
        Box(Modifier.requiredSize(diameter))
        return
    }
    val period = U2MotionTokens.PulseRing.duration
    // Read while drawing, so the loop redraws the rings without recomposing.
    val time: State<Float> = if (LocalMediaClock.current.isFrozen) {
        remember { mutableFloatStateOf(2f) }
    } else {
        rememberInfiniteTransition(label = "pulse")
            .animateFloat(0f, period, infiniteRepeatable(tween((period * 1000).toInt(), easing = LinearEasing)), label = "pulseTime")
    }
    Canvas(Modifier.requiredSize(diameter).clearAndSetSemantics {}) {
        for (offset in floatArrayOf(0f, period / 2f)) {
            val phase = ((time.value + offset) % period) / period
            val t = U2Motion.OutEasing.transform(phase)
            val scale = 0.15f + 0.85f * t
            val stroke = 1.5.dp.toPx() * scale
            val radius = size.minDimension / 2f * scale - stroke / 2f
            if (radius > 0f) drawCircle(color.copy(alpha = color.alpha * 0.9f * (1f - t)), radius = radius, style = Stroke(stroke))
        }
    }
}

/**
 * The stylised dotted map of the location bubbles: `bg` with an 8 dp dot grid (`text·18%`), two
 * dotted roads (`text·46%` / `text·38%`), a park of `accent·70%` dots and, for a place, the
 * `ph:map-pin` fill 24 pin. [grayscale] draws it without colour (an ended live location).
 */
@Composable
fun DottedMap(showsPin: Boolean, modifier: Modifier = Modifier, grayscale: Boolean = false) {
    val colors = U2Theme.colors
    Box(modifier.clearAndSetSemantics {}) {
        Canvas(Modifier.fillMaxSize()) {
            if (grayscale) {
                val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
                drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), paint) }
                drawDottedMap(colors.bg, colors.text, colors.accent)
                drawIntoCanvas { it.restore() }
            } else {
                drawDottedMap(colors.bg, colors.text, colors.accent)
            }
        }
        if (showsPin) {
            // `left 42 %, top 46 %, translate(−50 %, −100 %)`: the 24 dp glyph box stands on that point.
            PhosphorIcon(
                PhosphorGlyph.MapPin,
                24.dp,
                Modifier.pinPosition().u2DropShadowFilter(U2ShadowTokens.mapPin),
                weight = PhosphorWeight.Fill,
                tint = colors.accent300,
            )
        }
    }
}

/** Places the pin's box with its bottom centre on the map point (42 %, 46 %). */
private fun Modifier.pinPosition(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(0, 0) {
        placeable.place(
            (constraints.maxWidth * 0.42f - placeable.width / 2f).toInt(),
            (constraints.maxHeight * 0.46f - placeable.height).toInt(),
        )
    }
}

private fun DrawScope.drawDottedMap(bg: Color, ink: Color, accent: Color) {
    val unit = 1.dp.toPx()
    val w = size.width
    val h = size.height
    drawRect(bg)
    // Grid: `radial-gradient(text·18% 1px, transparent 1.4px) 0 0 / 8px 8px`.
    val grid = DotStamp(ink.copy(alpha = ink.alpha * 0.18f), solid = 1f * unit, edge = 1.4f * unit)
    var y = 4f * unit
    while (y < h + 2f * unit) {
        var x = 4f * unit
        while (x < w + 2f * unit) {
            grid.draw(this, Offset(x, y))
            x += 8f * unit
        }
        y += 8f * unit
    }
    // Road 1: box x −20 %…120 %, y 54 % (+10), rotate(−11°) about its centre; dots every 8 along its centre line.
    val road1Dot = DotStamp(ink.copy(alpha = ink.alpha * 0.46f), solid = 1.6f * unit, edge = 2f * unit)
    val road1 = Offset(0.5f * w, 0.54f * h + 5f * unit)
    var x1 = -0.2f * w + 4f * unit
    while (x1 <= 1.2f * w) {
        road1Dot.draw(this, rotate(Offset(x1, road1.y), road1, -11f))
        x1 += 8f * unit
    }
    // Road 2: box x 30 % (+10), y −20 %…120 %, rotate(+16°) about its centre.
    val road2Dot = DotStamp(ink.copy(alpha = ink.alpha * 0.38f), solid = 1.6f * unit, edge = 2f * unit)
    val road2 = Offset(0.3f * w + 5f * unit, 0.5f * h)
    var y2 = -0.2f * h + 4f * unit
    while (y2 <= 1.2f * h) {
        road2Dot.draw(this, rotate(Offset(road2.x, y2), road2, 16f))
        y2 += 8f * unit
    }
    // Park (after the roads, so on top): right 0 bottom 0, 48 % × 44 %, top-left radius 46
    // (CSS scales it down to fit the box).
    val park = Rect(0.52f * w, 0.56f * h, w, h)
    val radius = min(46f * unit, min(park.width, park.height))
    val clip = Path().apply { addRoundRect(RoundRect(park, topLeft = CornerRadius(radius))) }
    val parkDot = DotStamp(accent.copy(alpha = accent.alpha * 0.7f), solid = 1.3f * unit, edge = 1.7f * unit)
    clipPath(clip) {
        var py = park.top + 4f * unit
        while (py < park.bottom + 2f * unit) {
            var px = park.left + 4f * unit
            while (px < park.right + 2f * unit) {
                parkDot.draw(this, Offset(px, py))
                px += 8f * unit
            }
            py += 8f * unit
        }
    }
}

/** One CSS `radial-gradient(circle, c solid, transparent edge)` dot, its brush made once. */
private class DotStamp(color: Color, solid: Float, private val edge: Float) {
    private val brush = Brush.radialGradient(
        0f to color,
        solid / edge to color,
        1f to color.copy(alpha = 0f),
        center = Offset(edge, edge),
        radius = edge,
    )

    fun draw(scope: DrawScope, center: Offset) {
        scope.translate(center.x - edge, center.y - edge) { drawCircle(brush, radius = edge, center = Offset(edge, edge)) }
    }
}

/** CSS `rotate(θ)` (clockwise on screen) of [point] about [center]. */
private fun rotate(point: Offset, center: Offset, degrees: Float): Offset {
    val a = Math.toRadians(degrees.toDouble())
    val dx = point.x - center.x
    val dy = point.y - center.y
    return Offset(
        (center.x + dx * cos(a) - dy * sin(a)).toFloat(),
        (center.y + dx * sin(a) + dy * cos(a)).toFloat(),
    )
}
