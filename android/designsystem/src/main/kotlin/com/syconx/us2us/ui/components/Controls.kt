package com.syconx.us2us.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.env.LocalU2Clock
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.u2Spec
import com.syconx.us2us.ui.nav.LocalIsLayerVisible
import com.syconx.us2us.ui.theme.U2BoxShadows
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2BoxShadow
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.tokens.U2MotionTokens
import com.syconx.us2us.ui.tokens.U2Opacity
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import com.syconx.us2us.ui.type.textStyle

// MARK: - Ornament motion

/**
 * Whether the looping ornaments (the caret blink, spinners, pulse rings) hold still: under reduce
 * motion, and on a frozen design clock (previews, design comparisons), so a screenshot always
 * shows the same frame, as on iOS.
 */
@Composable
@ReadOnlyComposable
internal fun ornamentsHeld(): Boolean = LocalReduceMotion.current || LocalU2Clock.current.isFrozen

/**
 * SwiftUI's `.opacity(_:)`: fades the drawing without an offscreen layer, so the glows and shadows
 * a control draws outside its bounds fade with it instead of being cut at its edges.
 */
internal fun Modifier.u2Opacity(alpha: () -> Float): Modifier = graphicsLayer {
    this.alpha = alpha()
    compositingStrategy = CompositingStrategy.ModulateAlpha
}

/** [u2Opacity] with a fixed value. */
internal fun Modifier.u2Opacity(alpha: Float): Modifier = u2Opacity { alpha }

// MARK: - C14 · Toggle

/**
 * C14: 51 × 31, r16, padding 2. On: track `accent`, `#f5f4ff` knob (27) at the right with
 * `0 2px 6px #000@25%`. Off (not drawn in the design, proposed): track `divider`, knob at the left.
 * The knob glides on the critically damped spring, no overshoot. The whole row (label included)
 * toggles, with a selection haptic.
 */
@Composable
fun U2Toggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: (@Composable RowScope.() -> Unit)? = null,
) {
    val haptics = LocalHaptics.current
    val on = stringResource(R.string.common_a11y_on)
    val off = stringResource(R.string.common_a11y_off)
    Row(
        modifier
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Switch,
            ) {
                haptics.selection()
                onCheckedChange(it)
            }
            .semantics(mergeDescendants = true) { stateDescription = if (checked) on else off },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The label takes what the switch leaves: a long subtitle wraps, the switch keeps its size.
        if (label != null) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { label() }
        }
        U2Switch(checked, enabled)
    }
}

/** The C14 track and knob alone (no interaction). */
@Composable
fun U2Switch(checked: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val reduceMotion = LocalReduceMotion.current
    val travel = U2Size.toggleWidth - U2Size.toggleKnob - U2Size.togglePadding * 2
    val knobX by animateDpAsState(
        targetValue = if (checked) travel else 0.dp,
        animationSpec = if (reduceMotion) U2Motion.reducedCrossFade.spec() else U2Motion.spring(),
        label = "toggleKnob",
    )
    val track by animateColorAsState(
        targetValue = if (checked) colors.accent else colors.divider,
        animationSpec = if (reduceMotion) U2Motion.reducedCrossFade.spec() else U2Motion.spring(),
        label = "toggleTrack",
    )
    val offOutline by animateFloatAsState(
        targetValue = if (checked) 0f else 1f,
        animationSpec = if (reduceMotion) U2Motion.reducedCrossFade.spec() else U2Motion.spring(),
        label = "toggleOutline",
    )
    Box(
        modifier
            .size(U2Size.toggleWidth, U2Size.toggleHeight)
            .u2Opacity(if (enabled) 1f else U2Opacity.disabled)
            .background(track, CircleShape)
            // Off, the track alone is too close to the card behind it (1.3–1.6 : 1); an outline in
            // the secondary ink gives the switch a visible edge (WCAG 1.4.11), as Android's own does.
            .u2InnerStroke(colors.textSecondary.copy(alpha = colors.textSecondary.alpha * offOutline), 1.dp, CircleShape)
            .padding(U2Size.togglePadding),
    ) {
        Box(
            Modifier
                .offset(x = knobX)
                .size(U2Size.toggleKnob)
                .u2BoxShadow(CircleShape, U2BoxShadows.toggleKnob)
                .background(colors.toggleKnob, CircleShape),
        )
    }
}

// MARK: - Checkbox (2.3)

/**
 * The 2.3 acknowledgement: a 24 × 24 r7 box. Checked: fill `accent` with a 16 dp `check` in `bg`;
 * unchecked (proposed): inner 1.5 dp `text·40%` ring. Fill and tick cross-fade and scale over
 * 0.24 s. The label sits 12 dp to the right, top-aligned; the whole row toggles.
 */
@Composable
fun U2Checkbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable RowScope.() -> Unit,
) {
    val haptics = LocalHaptics.current
    val on = stringResource(R.string.common_a11y_on)
    val off = stringResource(R.string.common_a11y_off)
    Row(
        modifier
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Checkbox,
            ) {
                haptics.selection()
                onCheckedChange(it)
            }
            .semantics(mergeDescendants = true) { stateDescription = if (checked) on else off }
            // Disabled, the whole row is at half strength (SwiftUI's plain button style, which the
            // iOS checkbox uses; measured on the simulator's 2.3 after a failed restore).
            .u2Opacity(if (enabled) 1f else DISABLED_CHECKBOX_OPACITY),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        U2CheckboxBox(checked)
        Box(Modifier.weight(1f)) { Row(content = label) }
    }
}

private const val DISABLED_CHECKBOX_OPACITY = 0.5f

/** The 2.3 checkbox square alone. */
@Composable
fun U2CheckboxBox(checked: Boolean, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val progress by animateFloatAsState(if (checked) 1f else 0f, u2Spec(U2Motion.micro), label = "checkbox")
    val shape = RoundedCornerShape(U2Radius.checkbox)
    Box(modifier.size(U2Size.checkbox), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().alpha(1f - progress).u2InnerStroke(colors.textTertiary, 1.5.dp, shape))
        Box(Modifier.matchParentSize().alpha(progress).background(colors.accent, shape))
        PhosphorIcon(
            PhosphorGlyph.Check,
            size = 16.dp,
            tint = colors.bg,
            modifier = Modifier.scale(0.6f + 0.4f * progress).alpha(progress),
        )
    }
}

// MARK: - C22 · Segmented chips (5.5)

/**
 * C22: a row of equal chips, gap 6, 40 high, r12, `chip` (Inter 400 14). Unselected: inner 1 dp
 * `divider`, `text`; selected: fill `accent900`, inner 1.5 dp `accent`, `accent300`. Selection
 * cross-fades over 0.24 s. Also the contact screen's tabs. Each chip is tagged
 * `<identifierPrefix>.<option>` for UI tests when a prefix is given.
 */
@Composable
fun <T> SegmentedChips(
    options: List<T>,
    selection: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    identifierPrefix: String? = null,
    title: @Composable (T) -> String,
) {
    val haptics = LocalHaptics.current
    val titles = options.map { title(it) }
    val measurer = rememberTextMeasurer()
    val textStyle = U2TextStyles.chip.textStyle()
    val gap = 6.dp
    BoxWithConstraints(modifier.u2Opacity(if (enabled) 1f else U2Opacity.disabled)) {
        val density = LocalDensity.current
        val widths = remember(titles, textStyle) {
            titles.map { measurer.measure(it, textStyle, softWrap = false, maxLines = 1).size.width }
        }
        fun chipWidth(columns: Int): Int = with(density) {
            ((constraints.maxWidth - gap.roundToPx() * (columns - 1)) / columns.coerceAtLeast(1)) - SegmentChipMetrics.labelInset.roundToPx() * 2
        }
        // One row as drawn; when a label would have to shrink past 80 % (a large system font), the
        // chips go on two rows (or more) instead of cutting “24 sa” to “24 …”.
        var columns = options.size.coerceAtLeast(1)
        while (columns > 1 && widths.any { it * SegmentChipMetrics.MIN_SCALE > chipWidth(columns) - with(density) { 3.dp.roundToPx() } }) columns--
        // One factor for every chip (the longest title's), so the labels never differ in size.
        val width = chipWidth(columns)
        // A shrunk label is laid out again at its new size: a few pixels of slack keep the rounding
        // from tipping it into an ellipsis.
        val slack = with(density) { 3.dp.roundToPx() }
        val factor = widths.minOfOrNull { natural ->
            if (natural <= width || natural == 0) 1f else ((width - slack).toFloat() / natural).coerceAtLeast(SegmentChipMetrics.MIN_SCALE)
        } ?: 1f
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            options.indices.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { index ->
                        val option = options[index]
                        val selected = option == selection
                        SegmentChip(
                            title = titles[index],
                            factor = factor,
                            selected = selected,
                            enabled = enabled,
                            modifier = Modifier
                                .weight(1f)
                                .then(if (identifierPrefix != null) Modifier.testTag("$identifierPrefix.$option") else Modifier),
                        ) {
                            haptics.selection()
                            onSelect(option)
                        }
                    }
                    // A shorter last row keeps the chips' width.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private object SegmentChipMetrics {
    /** `minimumScaleFactor(0.8)`. */
    const val MIN_SCALE = 0.8f

    /** Kept clear inside a chip around its label (none: the label may use the chip's whole width, as drawn). */
    val labelInset = 0.dp
}

@Composable
private fun SegmentChip(title: String, factor: Float, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = U2Theme.colors
    val spec = u2Spec<Float>(U2Motion.micro)
    val progress by animateFloatAsState(if (selected) 1f else 0f, spec, label = "chipSelection")
    val ink by animateColorAsState(if (selected) colors.accent300 else colors.text, u2Spec(U2Motion.micro), label = "chipInk")
    val shape = RoundedCornerShape(U2Radius.chip)
    Box(
        modifier
            .heightIn(min = 40.dp)
            .background(colors.accent900.copy(alpha = colors.accent900.alpha * progress), shape)
            .u2InnerStroke(colors.divider.copy(alpha = colors.divider.alpha * (1f - progress)), 1.dp, shape)
            .u2InnerStroke(colors.accent.copy(alpha = colors.accent.alpha * progress), 1.5.dp, shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        ShrinkingLabel(title, factor, ink)
    }
}

/**
 * One line of `chip` text at [factor] of its size (`minimumScaleFactor(0.8)`, shared by the row).
 * The factor applies on top of the system font size, which the label keeps following.
 */
@Composable
private fun ShrinkingLabel(title: String, factor: Float, color: Color) {
    val style = U2TextStyles.chip
    val fontScale = LocalDensity.current.fontScale
    val shown = when {
        factor >= 1f -> style
        style.scalesWithFontSize -> style.scaled(factor * fontScale)
        else -> style.scaled(factor)
    }
    U2Text(
        title,
        shown,
        Modifier.padding(horizontal = SegmentChipMetrics.labelInset),
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

// MARK: - Count badge

/**
 * The unread / requests count: 22 high, min width 22, pad 0 7, r11, fill `accent`, number
 * Inter 600 12.5 in `bg`. A new count rolls in (0.36 s; a cross-fade under reduce motion) and the
 * pill eases to its new width.
 */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val reduceMotion = LocalReduceMotion.current
    val description = pluralStringResource(R.plurals.common_a11y_unread, count, count)
    Box(
        modifier
            .heightIn(min = 22.dp, max = 22.dp)
            .widthIn(min = 22.dp)
            .background(colors.accent, CircleShape)
            .padding(horizontal = 7.dp)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = count,
            transitionSpec = {
                val timing = U2Motion.resolve(U2Motion.state, reduceMotion)
                if (reduceMotion) {
                    fadeIn(timing.spec()) togetherWith fadeOut(timing.spec())
                } else {
                    val up = targetState > initialState
                    (slideInVertically(timing.spec()) { if (up) it else -it } + fadeIn(timing.spec())) togetherWith
                        (slideOutVertically(timing.spec()) { if (up) -it else it } + fadeOut(timing.spec()))
                }
            },
            contentAlignment = Alignment.Center,
            label = "countBadge",
        ) { shown ->
            U2Text(shown.toString(), U2TextStyles.badge, color = colors.bg)
        }
    }
}

// MARK: - Pulse rings (3.1 Face ID, 5.2 live location)

/**
 * `pulseRing`: two 116 dp circles with a 1.5 dp `accent400` inner stroke, each scaling 0.15 → 1
 * while fading 0.9 → 0 over 2.8 s ease-out, forever, the second 1.4 s later. Hidden under reduce
 * motion (the rings stop); held 2 s into the loop in design comparisons; paused while the app is
 * not in the foreground or the screen is hidden.
 */
@Composable
fun PulseRings(
    modifier: Modifier = Modifier,
    diameter: Dp = U2Size.faceIdDisc,
    color: Color = U2Theme.colors.accent400,
) {
    if (LocalReduceMotion.current) {
        Spacer(modifier.size(diameter))
        return
    }
    val held = LocalU2Clock.current.isFrozen
    var time by remember { mutableFloatStateOf(if (held) PulseRingsMetrics.HELD_AT else 0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    // Paused on screens nobody can see (under a pushed screen or the lock), and while the app is not active.
    val running = !held && LocalIsLayerVisible.current && state.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val resumedAt = time
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> time = resumedAt + (now - start) / 1_000_000_000f }
        }
    }
    Canvas(modifier.size(diameter).clearAndSetSemantics {}) {
        val period = U2MotionTokens.PulseRing.duration
        val now = if (held) PulseRingsMetrics.HELD_AT else time
        for (phase in listOf(now % period / period, (now + U2MotionTokens.PulseRing.stagger) % period / period)) {
            val t = U2Motion.OutEasing.transform(phase)
            val scale = U2MotionTokens.PulseRing.fromScale + (U2MotionTokens.PulseRing.toScale - U2MotionTokens.PulseRing.fromScale) * t
            val alpha = U2MotionTokens.PulseRing.fromOpacity * (1f - t)
            val stroke = 1.5.dp.toPx() * scale
            drawCircle(
                color = color.copy(alpha = color.alpha * alpha),
                radius = (size.minDimension / 2f) * scale - stroke / 2f,
                center = Offset(size.width / 2f, size.height / 2f),
                style = Stroke(stroke),
            )
        }
    }
}

/** Metrics of [PulseRings]. */
object PulseRingsMetrics {
    /** Where the rings are held in design comparisons, seconds into the loop (the CSS pause). */
    const val HELD_AT: Float = 2f
}
