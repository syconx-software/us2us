package com.syconx.us2us.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode
import com.syconx.us2us.domain.ThemePreference
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.rememberSystemReduceMotion
import com.syconx.us2us.ui.tokens.U2ColorScheme
import com.syconx.us2us.ui.type.U2Typography

/** The colours of the current theme (`Color.u2` on iOS). Provided by [U2Theme]. */
val LocalU2Colors = staticCompositionLocalOf { U2ColorScheme.Dark }

/** The type-style resolver for the current density. Provided by [U2Theme]. */
val LocalU2Type = staticCompositionLocalOf { U2Typography.Default }

/** Accessors for the current theme: `U2Theme.colors.textSecondary`. */
object U2Theme {
    /** The current theme's colours. */
    val colors: U2ColorScheme
        @Composable @ReadOnlyComposable
        get() = LocalU2Colors.current

    /** The current type-style resolver (see `U2TextStyle.textStyle()`). */
    val type: U2Typography
        @Composable @ReadOnlyComposable
        get() = LocalU2Type.current

    /** True in the dark theme (the design's default). */
    val isDark: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalU2Colors.current.isDark

    /** Whether reduce motion is on. */
    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalReduceMotion.current
}

/**
 * The us2us theme: the dark or light token set, the type resolver and reduce motion.
 *
 * The theme preference (system / dark / light, 7.1) resolves to [dark] at the root; screens
 * that are always dark (5.6 viewer, 6.2 scanner) nest `U2Theme(dark = true)`. No Material:
 * the default press indication is off (`LocalIndication`), because every control draws its
 * own press feedback (`u2PressEffect` and its fill), as on iOS.
 */
@Composable
fun U2Theme(
    dark: Boolean = isSystemInDarkTheme(),
    reduceMotion: Boolean = rememberSystemReduceMotion(),
    content: @Composable () -> Unit,
) {
    val colors = if (dark) U2ColorScheme.Dark else U2ColorScheme.Light
    val selection = remember(colors) {
        TextSelectionColors(handleColor = colors.accent, backgroundColor = colors.accent.copy(alpha = 0.3f))
    }
    CompositionLocalProvider(
        LocalU2Colors provides colors,
        LocalReduceMotion provides reduceMotion,
        LocalIndication provides U2NoIndication,
        LocalTextSelectionColors provides selection,
        content = content,
    )
}

/**
 * [U2Theme] for the 7.1 theme preference: `system` follows the device's dark mode, `dark` and
 * `light` force a token set (iOS applies it as `overrideUserInterfaceStyle`).
 */
@Composable
fun U2Theme(
    preference: ThemePreference,
    reduceMotion: Boolean = rememberSystemReduceMotion(),
    content: @Composable () -> Unit,
) = U2Theme(dark = preference.isDark(), reduceMotion = reduceMotion, content = content)

/** Whether this preference shows the dark token set right now. */
@Composable
@ReadOnlyComposable
fun ThemePreference.isDark(): Boolean = when (this) {
    ThemePreference.System -> isSystemInDarkTheme()
    ThemePreference.Dark -> true
    ThemePreference.Light -> false
}

/** No press indication: the U2 controls draw their own feedback. */
object U2NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}

    override fun hashCode(): Int = -1

    override fun equals(other: Any?): Boolean = other === this
}
