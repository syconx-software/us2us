package com.syconx.us2us.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.GhostState
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.env.rememberU2Now
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.l10n.Emphasis
import com.syconx.us2us.ui.l10n.U2Format
import com.syconx.us2us.ui.l10n.rememberU2Strings
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.u2PressEffect
import com.syconx.us2us.ui.motion.u2Spec
import com.syconx.us2us.ui.theme.U2BackdropSource
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.tokens.U2TextStyle
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import com.syconx.us2us.ui.type.textStyle
import java.time.Instant
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/**
 * C17 — Ghost indicators. Calm: `accent900` with `accent300` / `accent200` ink, regular ghost.
 * Urgent (last hour): `danger·14%` with `danger`, **filled** ghost; colours cross-fade over 0.8 s
 * when the hour starts. Off (header chip only): no fill, inner `divider`, “kapalı”.
 */
enum class GhostIndicatorState {
    Off,
    Calm,
    Urgent,
    ;

    companion object {
        fun of(ghost: GhostState, now: Instant): GhostIndicatorState = when {
            !ghost.isEnabled || ghost.deadline == null -> Off
            ghost.isUrgent(now) -> Urgent
            else -> Calm
        }
    }
}

/**
 * The chat-list pill: 22 high, pad 0 8, r11, gap 4 — `ph:ghost` 12 and the short remaining time in
 * `monoTag` (“23 sa”, “38 dk”, “6 gün”). Updates every half minute, from the screen's shared
 * [LocalU2Ticker] when there is one (4.1: one timer for every row), else from its own. A new value
 * rolls in. Draws nothing when Ghost is off.
 */
@Composable
fun GhostPill(ghost: GhostState, modifier: Modifier = Modifier) {
    val ticker = LocalU2Ticker.current
    val now = ticker?.now ?: rememberU2Now(30.seconds)
    val remaining = ghost.remaining(now) ?: return
    val strings = rememberU2Strings()
    val urgent = U2Format.ghostIsUrgent(remaining)
    val label = U2Format.ghostShort(remaining, strings)
    val colors = U2Theme.colors
    val ink by animateColorAsState(if (urgent) colors.danger else colors.accent300, u2Spec(U2Motion.ambient), label = "ghostInk")
    val fill by animateColorAsState(if (urgent) colors.dangerTint else colors.accent900, u2Spec(U2Motion.ambient), label = "ghostFill")
    val description = stringResource(R.string.common_a11y_ghost_remaining, label)
    Row(
        modifier
            .height(22.dp)
            .background(fill, CircleShape)
            .padding(horizontal = 8.dp)
            .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhosphorIcon(PhosphorGlyph.Ghost, size = 12.dp, weight = if (urgent) PhosphorWeight.Fill else PhosphorWeight.Regular, tint = ink)
        RollingText(label, U2TextStyles.monoTag, ink)
    }
}

/**
 * Text whose changed characters roll in, counting down (“23 sa” → “22 sa” rolls the digit instead of
 * swapping the whole label); a cross-fade under reduce motion. Each character is its own view, placed
 * where the whole text sets it, so the label is exactly as wide as the text.
 */
@Composable
private fun RollingText(text: String, style: U2TextStyle, color: Color) {
    val reduceMotion = LocalReduceMotion.current
    val timing = U2Motion.resolve(U2Motion.state, reduceMotion)
    val measurer = rememberTextMeasurer()
    val textStyle = style.textStyle()
    val whole = remember(text, textStyle) { measurer.measure(text, textStyle, maxLines = 1, softWrap = false) }
    Layout(
        content = {
            for (character in text) {
                AnimatedContent(
                    targetState = character,
                    transitionSpec = {
                        if (reduceMotion) {
                            fadeIn(timing.spec()) togetherWith fadeOut(timing.spec())
                        } else {
                            (slideInVertically(timing.spec()) { -it } + fadeIn(timing.spec())) togetherWith
                                (slideOutVertically(timing.spec()) { it } + fadeOut(timing.spec()))
                        }.using(SizeTransform(clip = false))
                    },
                    label = "rollingCharacter",
                ) { shown ->
                    U2Text(shown.toString(), style, color = color, maxLines = 1, softWrap = false)
                }
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(whole.size.width, height) {
            placeables.forEachIndexed { index, placeable ->
                val x = if (index < text.length) whole.getHorizontalPosition(index, usePrimaryDirection = true) else 0f
                placeable.place(x.roundToInt(), 0)
            }
        }
    }
}

/**
 * The chat-header chip: 34 high, pad 0 12 0 10, r17, gap 6 — `ph:ghost` 16 and `HH:MM:SS` ticking
 * every second in `monoCountdownChip` (tabular). Calm: inner ring `accent800`, ink `accent200`;
 * urgent: inner ring `danger·45%`, ink `danger`; off: inner `divider`, “kapalı” in `text·68%`.
 * Tapping opens the Ghost sheet (5.5); it presses like a card.
 */
@Composable
fun GhostChip(ghost: GhostState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val now = rememberU2Now(1.seconds)
    val strings = rememberU2Strings()
    val state = GhostIndicatorState.of(ghost, now)
    val remaining = ghost.remaining(now) ?: Duration.ZERO
    val colors = U2Theme.colors
    val spec = u2Spec<Color>(U2Motion.ambient)
    val ink by animateColorAsState(
        when (state) {
            GhostIndicatorState.Off -> colors.textSecondary
            GhostIndicatorState.Calm -> colors.accent200
            GhostIndicatorState.Urgent -> colors.danger
        },
        spec,
        label = "chipInk",
    )
    val fill by animateColorAsState(
        when (state) {
            GhostIndicatorState.Off -> Color.Transparent
            GhostIndicatorState.Calm -> colors.accent900
            GhostIndicatorState.Urgent -> colors.dangerTint
        },
        spec,
        label = "chipFill",
    )
    val ring by animateColorAsState(
        when (state) {
            GhostIndicatorState.Off -> colors.divider
            GhostIndicatorState.Calm -> colors.accent800
            GhostIndicatorState.Urgent -> colors.dangerStroke
        },
        spec,
        label = "chipRing",
    )
    val label = if (state == GhostIndicatorState.Off) stringResource(R.string.common_ghost_chip_off) else U2Format.ghostLong(remaining)
    val description = if (state == GhostIndicatorState.Off) {
        stringResource(R.string.common_a11y_ghost_off)
    } else {
        stringResource(R.string.common_a11y_ghost_remaining, U2Format.ghostShort(remaining, strings))
    }
    val hint = stringResource(R.string.common_a11y_ghost_hint)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        modifier
            .u2PressEffect(source, scale = 0.98f)
            .graphicsLayer { alpha = if (pressed) 0.85f else 1f }
            .clickable(source, indication = null, role = Role.Button, onClickLabel = hint, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description }
            .height(34.dp)
            .background(fill, CircleShape)
            .u2InnerStroke(ring, 1.dp, CircleShape)
            .padding(start = 10.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhosphorIcon(
            PhosphorGlyph.Ghost,
            size = 16.dp,
            weight = if (state == GhostIndicatorState.Urgent) PhosphorWeight.Fill else PhosphorWeight.Regular,
            tint = ink,
        )
        U2Text(label, U2TextStyles.monoCountdownChip, color = ink, maxLines = 1, softWrap = false)
    }
}

/**
 * A big live countdown in Doto (1.3 `displayCountdownL` 64, 5.5 `displayCountdownM` 56),
 * `accent200`, ticking every second.
 */
@Composable
fun GhostCountdown(
    deadline: Instant,
    modifier: Modifier = Modifier,
    style: U2TextStyle = U2TextStyles.displayCountdownM,
    color: Color = U2Theme.colors.accent200,
) {
    val now = rememberU2Now(1.seconds)
    val text = U2Format.ghostLong(remainingUntil(deadline, now))
    U2Text(text, style, color = color, maxLines = 1, softWrap = false, modifier = modifier.clearAndSetSemantics { contentDescription = text })
}

/**
 * 5.1 urgent banner (last hour): `ph:ghost` fill 18 `danger` and “Sohbet {43 dk} içinde iki taraftan
 * silinecek. Bir mesaj sayacı sıfırlar.” with the duration in Mono `danger`, on the floating banner
 * ([backdrop]: what it blurs).
 */
@Composable
fun GhostUrgentBanner(deadline: Instant, modifier: Modifier = Modifier, backdrop: U2BackdropSource? = null) {
    val now = rememberU2Now(30.seconds)
    val strings = rememberU2Strings()
    val colors = U2Theme.colors
    val remaining = U2Format.ghostShort(remainingUntil(deadline, now), strings)
    val count = U2TextStyles.monoBannerCount.textStyle().toSpanStyle().merge(SpanStyle(color = colors.danger))
    val sentence = remember(strings, remaining, count) {
        Emphasis.format(strings[R.string.common_ghost_banner_urgent], listOf(remaining), mapOf(1 to count), strings.locale)
    }
    FloatingBanner(FloatingBannerKind.Urgent, modifier, backdrop) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PhosphorIcon(PhosphorGlyph.Ghost, size = 18.dp, weight = PhosphorWeight.Fill, tint = colors.danger)
            U2Text(sentence, U2TextStyles.bullet, color = colors.text)
        }
    }
}

/** What is left until [deadline] at [now], never negative. */
private fun remainingUntil(deadline: Instant, now: Instant): Duration =
    java.time.Duration.between(now, deadline).toKotlinDuration().coerceAtLeast(Duration.ZERO)
