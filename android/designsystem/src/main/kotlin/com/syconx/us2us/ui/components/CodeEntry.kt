package com.syconx.us2us.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.u2PressEffect
import com.syconx.us2us.ui.motion.u2Spec
import com.syconx.us2us.ui.theme.U2BoxShadows
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2BoxShadow
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.tokens.U2Opacity
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text

// MARK: - C9 · Page dots

/**
 * C9, the onboarding page dots: active 22 × 6 `accent400`, inactive 6 × 6 `text·26%`, r3, gap 6.
 * The active pill morphs between pages (0.36 s). [current] is zero-based.
 */
@Composable
fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.common_a11y_pager, current + 1, count)
    Row(
        modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(U2Size.pageDotGap),
    ) {
        repeat(count) { index -> PageDot(active = index == current) }
    }
}

@Composable
private fun PageDot(active: Boolean) {
    val colors = U2Theme.colors
    val width by animateDpAsState(
        targetValue = if (active) U2Size.pageDotActive.width else U2Size.pageDotInactive.width,
        animationSpec = u2Spec(U2Motion.state),
        label = "pageDotWidth",
    )
    val fill by animateColorAsState(
        targetValue = if (active) colors.accent400 else colors.pageDotInactive,
        animationSpec = u2Spec(U2Motion.state),
        label = "pageDotFill",
    )
    Box(
        Modifier
            .width(width)
            .height(U2Size.pageDotActive.height)
            .background(fill, RoundedCornerShape(U2Radius.pageDot)),
    )
}

// MARK: - C10 · Code dots

/** How the six code dots look. */
enum class CodeDotsAppearance {
    Normal,

    /** All six solid `danger` (a wrong code). */
    Error,

    /** Lock-out wait: dimmed with the keypad. */
    Dimmed,
}

/**
 * C10, the six secret-code dots: 15 × 15, gap 20 (190 wide). Empty = inner 1.5 dp `text·40%`
 * ring; filled = `accent400` + glow `0 0 12px accent·70%` (scales in 0.6 → 1 over 0.24 s);
 * error = all six solid `danger`, with a soft, damped shake whenever [shakeTrigger] changes
 * (none under reduce motion).
 */
@Composable
fun CodeDots(
    filled: Int,
    modifier: Modifier = Modifier,
    length: Int = 6,
    appearance: CodeDotsAppearance = CodeDotsAppearance.Normal,
    shakeTrigger: Int = 0,
) {
    val reduceMotion = LocalReduceMotion.current
    val shake = remember { Animatable(0f) }
    val firstTrigger = remember { shakeTrigger }
    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger == firstTrigger || reduceMotion) return@LaunchedEffect
        shake.snapTo(0f)
        shake.animateTo(0f, CodeDotsMetrics.shakeSpec)
    }
    val description = stringResource(R.string.common_a11y_code_entered, filled, length)
    Row(
        modifier
            .graphicsLayer { translationX = shake.value * density }
            .u2Opacity(if (appearance == CodeDotsAppearance.Dimmed) U2Opacity.disabled else 1f)
            .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(U2Size.codeDotGap),
    ) {
        repeat(length) { index ->
            val state = when {
                appearance == CodeDotsAppearance.Error -> CodeDotState.Error
                index < filled -> CodeDotState.Filled
                else -> CodeDotState.Empty
            }
            CodeDot(state)
        }
    }
}

/** Metrics of [CodeDots]. */
object CodeDotsMetrics {
    /** ±6 dp, three damped cycles, 0.45 s, on the soft curve (dp, not px). */
    val shakeSpec = keyframes {
        durationMillis = 450
        0f at 0 using U2Motion.SoftEasing
        6f at 70 using U2Motion.SoftEasing
        -5f at 160 using U2Motion.SoftEasing
        3.5f at 250 using U2Motion.SoftEasing
        -2f at 330 using U2Motion.SoftEasing
        0.8f at 390 using U2Motion.SoftEasing
    }
}

private enum class CodeDotState { Empty, Filled, Error }

@Composable
private fun CodeDot(state: CodeDotState) {
    val colors = U2Theme.colors
    val spec = u2Spec<Float>(U2Motion.micro)
    val empty by animateFloatAsState(if (state == CodeDotState.Empty) 1f else 0f, spec, label = "codeDotEmpty")
    val filled by animateFloatAsState(if (state == CodeDotState.Filled) 1f else 0f, spec, label = "codeDotFilled")
    val error by animateFloatAsState(if (state == CodeDotState.Error) 1f else 0f, spec, label = "codeDotError")
    Box(Modifier.size(U2Size.codeDot)) {
        Box(Modifier.matchParentSize().alpha(empty).u2InnerStroke(colors.textTertiary, 1.5.dp, CircleShape))
        Box(
            Modifier
                .matchParentSize()
                .scale(0.6f + 0.4f * filled)
                .u2Opacity { filled }
                .u2BoxShadow(CircleShape, U2BoxShadows.glowCodeDot)
                .background(colors.accent400, CircleShape),
        )
        Box(Modifier.matchParentSize().alpha(error).background(colors.danger, CircleShape))
    }
}

// MARK: - C11 · Numeric keypad

/** What sits left of the 0 in the keypad's bottom row. */
@Stable
sealed interface KeypadBottomLeading {
    /** Nothing (setup, 2.2). */
    data object Empty : KeypadBottomLeading

    /** The Face ID key (lock screen, 3.2). */
    class FaceId(val onClick: () -> Unit) : KeypadBottomLeading
}

/**
 * C11: 3 × 78 dp keys, column gap 26, row gap 16 (286 × 360). Keys are `text·9%` circles with an
 * Inter 400 32 digit; the bottom row is [empty | 0 | backspace] in setup (2.2) and
 * [Face ID | 0 | backspace] on the lock screen (3.2). Press: `text·14%` + scale 0.97; a light
 * keyboard haptic per digit. Disabled (lock-out wait), the whole pad is drawn at 45 %.
 */
@Composable
fun NumericKeypad(
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    bottomLeading: KeypadBottomLeading = KeypadBottomLeading.Empty,
    enabled: Boolean = true,
) {
    val alpha by animateFloatAsState(if (enabled) 1f else U2Opacity.disabled, u2Spec(U2Motion.state), label = "keypadEnabled")
    Column(
        modifier.u2Opacity { alpha },
        verticalArrangement = Arrangement.spacedBy(U2Size.keypadRowGap),
    ) {
        for (row in listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))) {
            Row(horizontalArrangement = Arrangement.spacedBy(U2Size.keypadColumnGap)) {
                row.forEach { DigitKey(it, enabled, onDigit) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(U2Size.keypadColumnGap)) {
            when (bottomLeading) {
                KeypadBottomLeading.Empty -> Spacer(Modifier.size(U2Size.keypadKey))
                is KeypadBottomLeading.FaceId -> IconKey(
                    description = stringResource(R.string.android_biometric_unlock),
                    enabled = enabled,
                    onClick = bottomLeading.onClick,
                ) {
                    FaceIdGlyph(size = 30.dp, lineWidth = 1.6f, color = U2Theme.colors.text)
                }
            }
            DigitKey(0, enabled, onDigit)
            IconKey(description = stringResource(R.string.common_a11y_keypad_delete), enabled = enabled, onClick = onDelete) {
                PhosphorIcon(PhosphorGlyph.Backspace, size = 28.dp, tint = U2Theme.colors.text)
            }
        }
    }
}

@Composable
private fun DigitKey(digit: Int, enabled: Boolean, onDigit: (Int) -> Unit) {
    val colors = U2Theme.colors
    val haptics = LocalHaptics.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .size(U2Size.keypadKey)
            .u2PressEffect(source)
            .background(colors.text.copy(alpha = if (pressed) 0.14f else 0.09f), CircleShape)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button) {
                haptics.keypadTap()
                onDigit(digit)
            }
            .semantics { contentDescription = digit.toString() },
        contentAlignment = Alignment.Center,
    ) {
        U2Text(digit.toString(), U2TextStyles.keypadDigit, color = colors.text)
    }
}

@Composable
private fun IconKey(description: String, enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .size(U2Size.keypadKey)
            .u2PressEffect(source)
            .alpha(if (pressed) 0.55f else 1f)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** Positions inside the keypad (for callers that place overlays on a key). */
object NumericKeypadMetrics {
    /** The keypad's size: 286 × 360. */
    val width = U2Size.keypadKey * 3 + U2Size.keypadColumnGap * 2
    val height = U2Size.keypadKey * 4 + U2Size.keypadRowGap * 3
}
