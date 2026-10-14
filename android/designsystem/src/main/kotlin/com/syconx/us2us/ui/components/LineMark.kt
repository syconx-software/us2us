package com.syconx.us2us.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.Peer
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.env.LocalOwnAvatar
import com.syconx.us2us.ui.env.LocalU2Clock
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.u2TransitionBlur
import com.syconx.us2us.ui.nav.LocalIsLayerVisible
import com.syconx.us2us.ui.theme.U2BoxShadow
import com.syconx.us2us.ui.theme.U2BoxShadows
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.lineGradient
import com.syconx.us2us.ui.theme.linePacketGradient
import com.syconx.us2us.ui.theme.u2BoxShadow
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlin.math.roundToInt

/** The sizes of the line mark (C20). */
@Immutable
data class LineMarkMetrics(val ringDiameter: Dp, val ringStroke: Dp, val width: Dp, val barHeight: Dp) {
    companion object {
        /** 3.1 lock brand block: Ø14, stroke 2, 52 wide. */
        val Lock = LineMarkMetrics(ringDiameter = 14.dp, ringStroke = 2.dp, width = 52.dp, barHeight = 2.dp)

        /** 3.4 privacy cover at full size (thumbnail 60 × 16 ÷ 0.7015). */
        val Cover = LineMarkMetrics(ringDiameter = 23.dp, ringStroke = 3.dp, width = 86.dp, barHeight = 3.dp)

        /** 4.1 “Hat açık”: Ø9, stroke 2, 60 wide, gradient bar with packets. */
        val Connection = LineMarkMetrics(ringDiameter = 9.dp, ringStroke = 2.dp, width = 60.dp, barHeight = 2.dp)

        /**
         * 5.2 sticker mini mark: Ø10, stroke 2, 36 wide, bar 2 (the design's own box sizes, not the
         * −6° card's rotated bounding box).
         */
        val Sticker = LineMarkMetrics(ringDiameter = 10.dp, ringStroke = 2.dp, width = 36.dp, barHeight = 2.dp)
    }
}

/** C20 — the line mark: two rings joined by a bar (“İki uç. Bir hat.”). [isFlowing]: the 4.1 bar, `lineGradient` with flowing light packets. */
@Composable
fun LineMark(
    modifier: Modifier = Modifier,
    metrics: LineMarkMetrics = LineMarkMetrics.Lock,
    color: Color = U2Theme.colors.accent400,
    isFlowing: Boolean = false,
) {
    Row(
        modifier
            .size(metrics.width, metrics.ringDiameter)
            .clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineMarkRing(metrics, color)
        if (isFlowing) {
            LineBar(Modifier.weight(1f), height = metrics.barHeight, isFlowing = true)
        } else {
            Box(
                Modifier
                    .weight(1f)
                    .height(metrics.barHeight)
                    .background(color),
            )
        }
        LineMarkRing(metrics, color)
    }
}

@Composable
private fun LineMarkRing(metrics: LineMarkMetrics, color: Color) {
    Box(
        Modifier
            .size(metrics.ringDiameter)
            .border(metrics.ringStroke, color, CircleShape),
    )
}

/**
 * The bar of the line: `lineGradient` (accent400 → accent·30 % → accent400), with light packets
 * travelling both ways when flowing. It fills the offered width.
 */
@Composable
fun LineBar(modifier: Modifier = Modifier, height: Dp = 2.dp, isFlowing: Boolean = true) {
    val colors = U2Theme.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(colors.lineGradient),
    ) {
        val fade = U2Transitions.fade(U2Motion.state)
        AnimatedVisibility(visible = isFlowing, modifier = Modifier.matchParentSize(), enter = fade.enter, exit = fade.exit) {
            LinePackets(Modifier.fillMaxSize().then(u2TransitionBlur(fade)))
        }
    }
}

/**
 * `linePacket` (design keyframes u2-packet-r / u2-packet-l): a 44 × 4 capsule (transparent →
 * `accent200` → transparent, glow `0 0 12px 2px accent·50%`) whose centre travels the full bar in
 * 6 s on the soft curve: 0–6 % fade in · 0–60 % travel · 54–60 % fade out · 60–100 % rest. The
 * right-to-left packet starts 3 s later.
 *
 * Hidden under reduce motion. On a frozen design clock it stands 2 s into the loop, where the
 * references were paused. Otherwise it holds its frame while the app is not in front and while
 * its screen is covered by another on the stack (4.1 under 5.1 / 7.1 …), so a hidden screen never
 * redraws 60 times a second.
 */
@Composable
fun LinePackets(modifier: Modifier = Modifier) {
    if (LocalReduceMotion.current) return
    val frozen = LocalU2Clock.current.isFrozen
    val visible = LocalIsLayerVisible.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val running = !frozen && visible && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val time = remember { mutableDoubleStateOf(wallSeconds()) }
    if (running) {
        LaunchedEffect(Unit) {
            // The packets follow the wall clock (their phase survives a pause), stepped by frames.
            val start = wallSeconds()
            var first = -1L
            while (true) {
                withFrameNanos { nanos ->
                    if (first < 0) first = nanos
                    time.doubleValue = start + (nanos - first) / 1e9
                }
            }
        }
    }
    BoxWithConstraints(modifier.clearAndSetSemantics {}) {
        val width = constraints.maxWidth
        val centerY = constraints.maxHeight / 2f
        val now: () -> Double = if (frozen) ({ LinePacketTiming.FROZEN_TIME }) else ({ time.doubleValue })
        LinePacket(time = now, delay = 0.0, leftToRight = true, width = width, centerY = centerY)
        LinePacket(time = now, delay = LinePacketTiming.REVERSE_DELAY, leftToRight = false, width = width, centerY = centerY)
    }
}

@Composable
private fun LinePacket(time: () -> Double, delay: Double, leftToRight: Boolean, width: Int, centerY: Float) {
    val colors = U2Theme.colors
    val shape = RoundedCornerShape(percent = 50)
    Box(
        Modifier
            .layout { measurable, _ ->
                // The capsule is wider and taller than a short bar (42 × 2 on 4.1): never squeezed.
                val placeable = measurable.measure(Constraints())
                layout(0, 0) {
                    // Placement reads the time, so a frame moves the packet without recomposing.
                    val state = LinePacketTiming.state(time(), delay)
                    val fraction = if (leftToRight) state.position else 1 - state.position
                    val x = (width * fraction - placeable.width / 2.0).roundToInt()
                    val y = (centerY - placeable.height / 2f).roundToInt()
                    placeable.placeWithLayer(IntOffset(x, y)) { alpha = state.opacity.toFloat() }
                }
            }
            .size(44.dp, 4.dp)
            .u2BoxShadow(shape, U2BoxShadows.glowPacket)
            .background(colors.linePacketGradient, shape),
    )
}

/** The packets' keyframes as a function of time (seconds), as the design's CSS runs them. */
internal object LinePacketTiming {
    const val PERIOD = 6.0
    const val REVERSE_DELAY = 3.0

    /** The references are paused 2 s into the loop. */
    const val FROZEN_TIME = 2.0

    /** Where a packet is (0…1 along the bar) and how visible it is. */
    data class State(val position: Double, val opacity: Double)

    fun state(time: Double, delay: Double): State {
        val local = time - delay
        // Before its first run the packet is hidden (its inline opacity is 0).
        if (!(local >= 0 || time > 1_000)) return State(0.0, 0.0)
        val phase = ((local % PERIOD) + PERIOD) % PERIOD / PERIOD
        val travel = if (phase < 0.6) curve(phase / 0.6) else 1.0
        val opacity = when {
            phase < 0.06 -> curve(phase / 0.06)
            phase < 0.54 -> 1.0
            phase < 0.6 -> 1 - curve((phase - 0.54) / 0.06)
            else -> 0.0
        }
        return State(travel, opacity)
    }

    private fun curve(x: Double): Double = U2Motion.SoftEasing.transform(x.toFloat().coerceIn(0f, 1f)).toDouble()
}

private fun wallSeconds(): Double = System.currentTimeMillis() / 1000.0

/**
 * C20 · the sen ↔ peer line of 6.1 / 6.3, laid out like the design's CSS: the two ends (avatar 48
 * and a Mono 11 label, gap 8) and between them the 2 dp bar with a 22 dp bottom margin, centred on
 * the row — so the bar (and the seal on its midpoint) sits 25.5 dp below the avatars' top, 1.5 dp
 * under their centre line, exactly as drawn. The bar overlaps each avatar by 2 dp (painted over the
 * leading avatar, under the trailing one, like the DOM order).
 *
 * [animatesSeal] plays the seal's entrance (scale 0.6 → 1 with its glow, 0.5 s ease-out; the short
 * cross-fade under reduce motion), [entranceDelay] seconds after it appears (6.3 after the 6.2 cover
 * has gone).
 */
@Composable
fun ConnectionLine(
    ownUsername: String,
    ownLabel: String,
    peer: Peer,
    modifier: Modifier = Modifier,
    isFlowing: Boolean = true,
    showsSeal: Boolean = false,
    animatesSeal: Boolean = false,
    entranceDelay: Float = 0f,
) {
    val ownAvatar = LocalOwnAvatar.current
    val reduceMotion = LocalReduceMotion.current
    var sealShown by remember { mutableStateOf(!animatesSeal) }
    LaunchedEffect(Unit) { sealShown = true }
    val timing = (if (reduceMotion) U2Motion.reducedCrossFade else U2Motion.out(U2Motion.Duration.screen)).delayed(entranceDelay)
    val entrance by animateFloatAsState(if (sealShown) 1f else 0f, timing.spec(), label = "sealEntrance")
    Row(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConnectionEnd(label = ownLabel) {
            Avatar(ownUsername, AvatarPalette.D, size = AvatarSize.Verify, picture = ownAvatar)
        }
        ConnectionBar(Modifier.weight(1f), isFlowing, showsSeal, entrance = { entrance }, reduceMotion = reduceMotion)
        ConnectionEnd(label = peer.username) {
            Avatar(peer.username, peer.palette, size = AvatarSize.Verify, picture = peer.avatar)
        }
    }
}

/** The bar between the two ends, with the seal on its midpoint. */
@Composable
private fun ConnectionBar(modifier: Modifier, isFlowing: Boolean, showsSeal: Boolean, entrance: () -> Float, reduceMotion: Boolean) {
    Box(
        modifier
            // margin: 0 -2px 22px — the bar reaches 2 dp under each avatar.
            .layout { measurable, constraints ->
                val overlap = 2.dp.roundToPx()
                val width = constraints.maxWidth + 2 * overlap
                val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                layout(constraints.maxWidth, placeable.height) { placeable.place(-overlap, 0) }
            }
            .padding(bottom = 22.dp)
            .height(2.dp),
    ) {
        LineBar(Modifier.matchParentSize(), height = 2.dp, isFlowing = isFlowing)
        val fade = U2Transitions.fade(U2Motion.state)
        AnimatedVisibility(visible = showsSeal, modifier = Modifier.align(Alignment.Center), enter = fade.enter, exit = fade.exit) {
            // 32 high on a 2 dp bar: centred on it (iOS: top − 15).
            VerifiedSeal(
                Modifier
                    .requiredSize(32.dp)
                    .then(u2TransitionBlur(fade))
                    .graphicsLayer {
                        val scale = if (reduceMotion) 1f else 0.6f + 0.4f * entrance()
                        scaleX = scale
                        scaleY = scale
                        alpha = entrance()
                    },
            )
        }
    }
}

/** One end of the line: the avatar over its Mono 11 label (gap 8), 48 wide; a longer label overflows centred. */
@Composable
private fun ConnectionEnd(label: String, avatar: @Composable () -> Unit) {
    Column(
        Modifier.width(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        avatar()
        U2Text(
            label,
            U2TextStyles.monoMeta,
            color = U2Theme.colors.textSecondary,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.wrapContentWidth(unbounded = true),
        )
    }
}

/** 6.3 verified seal: 32 circle `bg`, ring `0 0 0 1px accent800`, glow `0 0 24px accent·55%`, `ph:seal-check` fill 22 `accent`. */
@Composable
fun VerifiedSeal(modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    Box(
        modifier
            .size(32.dp)
            .u2BoxShadow(CircleShape, U2BoxShadow(colors.accent800, blur = 0.dp, spread = 1.dp), U2BoxShadows.glowSeal)
            .background(colors.bg, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        PhosphorIcon(
            PhosphorGlyph.SealCheck,
            size = 22.dp,
            weight = PhosphorWeight.Fill,
            tint = colors.accent,
            contentDescription = stringResource(R.string.common_a11y_verified),
        )
    }
}
