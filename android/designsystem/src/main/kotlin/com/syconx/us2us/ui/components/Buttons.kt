package com.syconx.us2us.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.u2PressEffect
import com.syconx.us2us.ui.motion.u2Spec
import com.syconx.us2us.ui.motion.u2TransitionBlur
import com.syconx.us2us.ui.theme.U2BoxShadow
import com.syconx.us2us.ui.theme.U2BoxShadows
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.onMediaGlass
import com.syconx.us2us.ui.theme.u2BoxShadow
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.tokens.U2Opacity
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2TextStyle
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text

// MARK: - Labels

/**
 * The type style and ink a control gives its label: what SwiftUI's `.u2Font(…)` and
 * `.foregroundStyle(…)` on a button label do. Every button below provides it; [U2ButtonLabel]
 * and custom labels read it.
 */
@Immutable
data class U2LabelStyle(val style: U2TextStyle, val color: Color)

/** The label style of the enclosing control ([Color.Unspecified]: the theme's `text`). */
val LocalU2LabelStyle = staticCompositionLocalOf { U2LabelStyle(U2TextStyles.button, Color.Unspecified) }

/** The ink of the enclosing control's label. */
@Composable
fun currentLabelColor(): Color = LocalU2LabelStyle.current.color.takeOrElse { U2Theme.colors.text }

private inline fun Color.takeOrElse(block: () -> Color): Color = if (this != Color.Unspecified) this else block()

/**
 * A button label: an optional 20 dp leading glyph, gap 8, the title. While [isWorking] a softly
 * spinning `circle-notch` takes the glyph's place (7.2 export, erase); the glyph fades in and out
 * and the title cross-fades when it changes. Type and ink come from the enclosing button.
 */
@Composable
fun U2ButtonLabel(
    title: String,
    modifier: Modifier = Modifier,
    glyph: PhosphorGlyph? = null,
    glyphSize: Dp = 20.dp,
    isWorking: Boolean = false,
) {
    val label = LocalU2LabelStyle.current
    val ink = currentLabelColor()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val lead: LabelLead = when {
            isWorking -> LabelLead.Working
            glyph != null -> LabelLead.Glyph(glyph)
            else -> LabelLead.None
        }
        val fade = U2Transitions.fade()
        AnimatedContent(
            targetState = lead,
            transitionSpec = { fade.enter togetherWith fade.exit },
            contentAlignment = Alignment.CenterStart,
            label = "buttonLabelLead",
        ) { shown ->
            // The 8 dp gap belongs to the glyph, so a title without one stays centred.
            val gap = Modifier.then(u2TransitionBlur(fade)).padding(end = 8.dp)
            when (shown) {
                LabelLead.Working -> SpinningGlyph(size = glyphSize, tint = ink, modifier = gap)
                is LabelLead.Glyph -> PhosphorIcon(shown.glyph, size = glyphSize, tint = ink, modifier = gap)
                LabelLead.None -> Box(Modifier)
            }
        }
        Crossfade(targetState = title, animationSpec = U2Motion.resolve(U2Motion.state, LocalReduceMotion.current).spec(), label = "buttonLabelTitle") { text ->
            U2Text(text, label.style, color = ink)
        }
    }
}

private sealed interface LabelLead {
    data object None : LabelLead

    data object Working : LabelLead

    data class Glyph(val glyph: PhosphorGlyph) : LabelLead
}

// MARK: - C1 · Primary button (outline + glow)

/** The three heights of C1. */
enum class U2PrimaryButtonSize(val height: Dp, val radius: Dp) {
    /** 54 high, r16: screens. */
    Regular(U2Size.buttonPrimary, U2Radius.button),

    /** 48 high, r16: inside cards (4.2 “Mesaj yaz”). */
    InCard(U2Size.buttonInCard, U2Radius.button),

    /** 50 high, r14, smaller glow: the 4.4 decision bar. */
    Bar(U2Size.buttonBar, U2Radius.buttonSheet),
}

/**
 * C1, the primary CTA: transparent, 1 dp `accent` outline, `accent300` label (Inter 500 17),
 * outer glow `0 0 32px -8px accent·70%`. Press fills it with `accent·12%` and scales it to 0.97;
 * disabled draws the whole control at 45 %.
 */
@Composable
fun U2PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: U2PrimaryButtonSize = U2PrimaryButtonSize.Regular,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val colors = U2Theme.colors
    OutlineButton(
        onClick = onClick,
        modifier = modifier,
        height = size.height,
        radius = size.radius,
        outline = colors.accent,
        pressFill = colors.accent.copy(alpha = 0.12f),
        glow = if (size == U2PrimaryButtonSize.Bar) U2BoxShadows.glowButtonAccept else U2BoxShadows.glowButton,
        label = U2LabelStyle(U2TextStyles.button, colors.accent300),
        enabled = enabled,
        interactionSource = interactionSource,
        content = content,
    )
}

/** C1 with a [U2ButtonLabel]. */
@Composable
fun U2PrimaryButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: PhosphorGlyph? = null,
    size: U2PrimaryButtonSize = U2PrimaryButtonSize.Regular,
    enabled: Boolean = true,
    isWorking: Boolean = false,
) {
    U2PrimaryButton(onClick, modifier, size, enabled) { U2ButtonLabel(title, glyph = glyph, isWorking = isWorking) }
}

/**
 * C1 in `danger`, for destructive confirmations (3.3 “Her şeyi sil”, 7.1 erase, 7.3 cancel):
 * 54 high, r16, 1 dp `danger` outline, `danger` label (Inter 500 17), a soft `danger` glow;
 * press fills `danger·12%`.
 */
@Composable
fun U2DangerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val colors = U2Theme.colors
    OutlineButton(
        onClick = onClick,
        modifier = modifier,
        height = U2Size.buttonPrimary,
        radius = U2Radius.button,
        outline = colors.danger,
        pressFill = colors.danger.copy(alpha = 0.12f),
        glow = U2BoxShadows.glowDanger,
        label = U2LabelStyle(U2TextStyles.button, colors.danger),
        enabled = enabled,
        interactionSource = interactionSource,
        content = content,
    )
}

/** C1 in `danger` with a [U2ButtonLabel]. */
@Composable
fun U2DangerButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: PhosphorGlyph? = null,
    enabled: Boolean = true,
    isWorking: Boolean = false,
) {
    U2DangerButton(onClick, modifier, enabled) { U2ButtonLabel(title, glyph = glyph, isWorking = isWorking) }
}

@Composable
private fun OutlineButton(
    onClick: () -> Unit,
    modifier: Modifier,
    height: Dp,
    radius: Dp,
    outline: Color,
    pressFill: Color,
    glow: U2BoxShadow,
    label: U2LabelStyle,
    enabled: Boolean,
    interactionSource: MutableInteractionSource?,
    content: @Composable () -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val alpha by animateFloatAsState(if (enabled) 1f else U2Opacity.disabled, u2Spec(U2Motion.state), label = "buttonEnabled")
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .u2PressEffect(source)
            .u2Opacity { alpha }
            .u2BoxShadow(shape, glow)
            .background(if (pressed) pressFill else Color.Transparent, shape)
            .u2InnerStroke(outline, 1.dp, shape)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalU2LabelStyle provides label, content = content)
    }
}

// MARK: - C2 · Secondary button

/**
 * C2: the primary's geometry with no border, an inner 1 dp `divider` stroke and the label in
 * `text` (“Kodla aç”, “Tekrar dene”, “İptal”). Press fill `text·7%`.
 */
@Composable
fun U2SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val colors = U2Theme.colors
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(U2Radius.button)
    Box(
        modifier
            .fillMaxWidth()
            .height(U2Size.buttonPrimary)
            .u2PressEffect(source)
            .u2Opacity(if (enabled) 1f else U2Opacity.disabled)
            .background(colors.text.copy(alpha = if (pressed) 0.07f else 0f), shape)
            .u2InnerStroke(colors.divider, 1.dp, shape)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalU2LabelStyle provides U2LabelStyle(U2TextStyles.button, colors.text), content = content)
    }
}

/** C2 with a [U2ButtonLabel]. */
@Composable
fun U2SecondaryButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: PhosphorGlyph? = null,
    enabled: Boolean = true,
    isWorking: Boolean = false,
) {
    U2SecondaryButton(onClick, modifier, enabled) { U2ButtonLabel(title, glyph = glyph, isWorking = isWorking) }
}

// MARK: - C3 · Text button

/** The ink of a C3 text button. */
enum class U2TextButtonTone {
    /** `text·80%` (“Yedekten geri yükle”). */
    Quiet,

    /** `accent300` (“Kodu unuttum”, sheet “Vazgeç”). */
    Accent,

    /** `danger`. */
    Danger,
}

/**
 * C3: a centred `rowTitle` (Inter 400 16) label, 44–46 high. Pressed, it dims to 55 %
 * (0.16 s in, 0.24 s out).
 */
@Composable
fun U2TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: U2TextButtonTone = U2TextButtonTone.Quiet,
    height: Dp = U2Size.textButton,
    fillsWidth: Boolean = true,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val colors = U2Theme.colors
    val ink = when (tone) {
        U2TextButtonTone.Quiet -> colors.textQuiet
        U2TextButtonTone.Accent -> colors.accent300
        U2TextButtonTone.Danger -> colors.danger
    }
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (pressed) 0.55f else 1f,
        animationSpec = (if (pressed) U2Motion.press else U2Motion.release).spec(),
        label = "textButtonPress",
    )
    Box(
        modifier
            .then(if (fillsWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = height)
            .u2Opacity { alpha }
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalU2LabelStyle provides U2LabelStyle(U2TextStyles.rowTitle, ink), content = content)
    }
}

/** C3 with a [U2ButtonLabel]. */
@Composable
fun U2TextButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: U2TextButtonTone = U2TextButtonTone.Quiet,
    height: Dp = U2Size.textButton,
    fillsWidth: Boolean = true,
    enabled: Boolean = true,
) {
    U2TextButton(onClick, modifier, tone, height, fillsWidth, enabled) { U2ButtonLabel(title) }
}

// MARK: - C4 · Glass icon button

/** What a glass icon button sits on. */
enum class U2GlassSurface {
    /** The screen: fill `text·9%` (14 % pressed), glyph `text`. */
    Standard,

    /** Over media (5.6): `#0c0d16@40%` (55 % pressed), `#f5f4ff` glyph. */
    Media,

    /** Over the camera (6.2): `#f5f4ff@12%` (20 % pressed), `#f5f4ff` glyph. */
    Camera,
}

/**
 * C4, a round glass button: fill `text·9%`, inner hairline 0.5 dp `text·18%`, a centred Phosphor
 * glyph. 44 dp (40 in chat headers, 36 in sheets). [contentDescription] is what accessibility
 * reads.
 */
@Composable
fun U2GlassIconButton(
    glyph: PhosphorGlyph,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    weight: PhosphorWeight = PhosphorWeight.Regular,
    diameter: Dp = U2Size.iconButton,
    glyphSize: Dp = 21.dp,
    tint: Color? = null,
    surface: U2GlassSurface = U2GlassSurface.Standard,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = U2Theme.colors
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fill = when (surface) {
        U2GlassSurface.Standard -> colors.text.copy(alpha = if (pressed) 0.14f else 0.09f)
        U2GlassSurface.Media -> colors.onMediaGlass(if (pressed) 0.55f else 0.4f)
        U2GlassSurface.Camera -> colors.onMedia.copy(alpha = if (pressed) 0.2f else 0.12f)
    }
    val ink = tint ?: if (surface == U2GlassSurface.Standard) colors.text else colors.onMedia
    val nudge = if (surface == U2GlassSurface.Standard) U2GlassIconButtonMetrics.glyphBaselineNudge(diameter, glyphSize) else 0f
    Box(
        modifier
            .size(diameter)
            .u2PressEffect(source)
            .background(fill, CircleShape)
            .u2InnerStroke(colors.strokeGlass, 0.5.dp, CircleShape)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        PhosphorIcon(glyph, size = glyphSize, weight = weight, tint = ink, modifier = Modifier.offset(y = nudge.dp))
    }
}

/** Metrics of [U2GlassIconButton]. */
object U2GlassIconButtonMetrics {
    /**
     * The design's icon-font glyphs sit lower in a standard 44 dp circle than a centred vector:
     * 2/3 dp for 21 dp glyphs (7.1–7.3 headers, the 4.1 / 9.1 header buttons), 1/3 dp for the
     * 22 dp composer “+” and setup back button (measured centroids). The camera and media buttons
     * (6.2, 5.6) already match.
     */
    fun glyphBaselineNudge(diameter: Dp, glyphSize: Dp): Float {
        if (diameter != U2Size.iconButton) return 0f
        return when (glyphSize) {
            21.dp -> 2f / 3f
            22.dp -> 1f / 3f
            else -> 0f
        }
    }
}

// MARK: - Decision buttons (4.3 cards, 4.4 bar)

/** The three request actions. */
enum class U2DecisionKind {
    /** “Engelle”: `danger` label, inner 1 dp `divider`. */
    Destructive,

    /** “Sil”: `text` label, inner 1 dp `divider`. */
    Neutral,

    /** “Kabul et”: 1 dp `accent` outline, `accent300` label. */
    Accept,
}

/** Where the decision buttons sit. */
enum class U2DecisionSize {
    /** 40 high, r12, Inter 14.5 (4.3 cards). */
    Card,

    /** 50 high, r14, Inter 16, with the accept glow (4.4 bar). */
    Bar,
}

/** A request decision button (4.3 cards, 4.4 bar). Use inside a [U2FlexRow]. */
@Composable
fun U2DecisionButton(
    kind: U2DecisionKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: U2DecisionSize = U2DecisionSize.Card,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val colors = U2Theme.colors
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val card = size == U2DecisionSize.Card
    val shape = RoundedCornerShape(if (card) U2Radius.buttonS else U2Radius.buttonSheet)
    val style = when {
        kind == U2DecisionKind.Accept && card -> U2TextStyles.buttonS
        card -> U2TextStyles.preview
        kind == U2DecisionKind.Accept -> U2TextStyles.rowTitleEmphasis
        else -> U2TextStyles.rowTitle
    }
    val ink = when (kind) {
        U2DecisionKind.Destructive -> colors.danger
        U2DecisionKind.Neutral -> colors.text
        U2DecisionKind.Accept -> colors.accent300
    }
    val accept = kind == U2DecisionKind.Accept
    val fill = if (accept) colors.accent.copy(alpha = if (pressed) 0.12f else 0f) else colors.text.copy(alpha = if (pressed) 0.07f else 0f)
    Box(
        modifier
            .fillMaxWidth()
            .height(if (card) U2Size.buttonSmall else U2Size.buttonBar)
            .u2PressEffect(source)
            .then(if (accept && !card) Modifier.u2BoxShadow(shape, U2BoxShadows.glowButtonAccept) else Modifier)
            .background(fill, shape)
            .u2InnerStroke(if (accept) colors.accent else colors.divider, 1.dp, shape)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalU2LabelStyle provides U2LabelStyle(style, ink), content = content)
    }
}

/** A decision button with a [U2ButtonLabel]. */
@Composable
fun U2DecisionButton(
    kind: U2DecisionKind,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: U2DecisionSize = U2DecisionSize.Card,
    enabled: Boolean = true,
) {
    U2DecisionButton(kind, onClick, modifier, size, enabled) { U2ButtonLabel(title) }
}

// MARK: - Small pill buttons

/** “Durdur” inside the live-location bubble (5.2): 32 high, pad 0 13, r16, fill `bg·55%`, Inter 500 13.5 `danger`. */
@Composable
fun U2InlinePillButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = U2Theme.colors
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .height(32.dp)
            .u2PressEffect(source)
            .u2Opacity(if (pressed) 0.7f else 1f)
            .background(colors.inlineButtonFill, CircleShape)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        U2Text(title, U2TextStyles.inlineButton, color = colors.danger)
    }
}

/**
 * The pill over the camera (6.2 “Kendi kodumu göster”): 44 high, pad 0 18, r22,
 * `#f5f4ff@12%`, Inter 400 15 `#f5f4ff`, 18 dp glyph, gap 8.
 */
@Composable
fun U2CameraPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val colors = U2Theme.colors
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .height(44.dp)
            .u2PressEffect(source)
            .background(colors.onMedia.copy(alpha = if (pressed) 0.2f else 0.12f), CircleShape)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalU2LabelStyle provides U2LabelStyle(U2TextStyles.label15, colors.onMedia), content = content)
    }
}

/** The camera pill with a [U2ButtonLabel] (18 dp glyph). */
@Composable
fun U2CameraPillButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: PhosphorGlyph? = null,
    enabled: Boolean = true,
) {
    U2CameraPillButton(onClick, modifier, enabled) { U2ButtonLabel(title, glyph = glyph, glyphSize = 18.dp) }
}
