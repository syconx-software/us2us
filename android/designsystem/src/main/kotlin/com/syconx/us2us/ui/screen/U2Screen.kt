package com.syconx.us2us.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.syconx.us2us.ui.env.LocalU2SafeArea
import com.syconx.us2us.ui.env.windowSafeArea
import com.syconx.us2us.ui.theme.U2Theme

/**
 * The root of every screen.
 *
 * The design measures everything from the *physical* top of a 402 × 874 screen with a 62 dp status
 * bar and a 34 dp home indicator (headers sit inside the status-bar band, e.g. icon buttons at y 56).
 * `U2Screen` lays its content out edge to edge on [background] and publishes the window's safe-area
 * insets as `LocalU2SafeArea`, so components place themselves with `Modifier.u2Top(designY)` and
 * `u2Bottom(padding)`: identical on the design's phone and proportionally right on every other one.
 *
 * The keyboard still pushes the content up ([avoidsKeyboard]); only the system bars are ignored.
 */
@Composable
fun U2Screen(
    modifier: Modifier = Modifier,
    background: Color = U2Theme.colors.bg,
    avoidsKeyboard: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val safeArea = windowSafeArea()
    CompositionLocalProvider(LocalU2SafeArea provides safeArea) {
        Box(
            modifier
                .fillMaxSize()
                .background(background)
                .then(if (avoidsKeyboard) Modifier.windowInsetsPadding(WindowInsets.ime) else Modifier),
            contentAlignment = Alignment.TopCenter,
            content = content,
        )
    }
}
