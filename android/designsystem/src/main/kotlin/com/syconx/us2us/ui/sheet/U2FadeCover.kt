package com.syconx.us2us.ui.sheet

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.nav.LocalIsLayerVisible

/**
 * A full-screen cover that fades in (0.36 s on the design curve) and fades out instead of a slide:
 * 4.1 / 4.2 / 6.1 → 6.2 and 5.1 → 5.6, “full-screen cover, fade 0.36 s”. The cover draws its own
 * background, so fading reveals the screen underneath (a 6.2 match reveals 6.3 that way, over
 * [fadeOut] = 0.5 s).
 *
 * No swipe dismissal: only the content's own close button, or back when [onEscape] is given (5.6
 * and 6.2 must not close by accident; without [onEscape] back does nothing while the cover is up).
 * [onDismissed] runs once the fade-out has finished.
 */
@Composable
fun U2FadeCover(
    isPresented: Boolean,
    onEscape: (() -> Unit)? = null,
    fadeOut: () -> Float = { U2Motion.Duration.state },
    onDismissed: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var isHosted by remember { mutableStateOf(false) }
    SideEffect { if (isPresented) isHosted = true }
    if (!isPresented && !isHosted) return
    val currentOnDismissed by rememberUpdatedState(onDismissed)
    OverlayPortal {
        FadeCoverContainer(
            isPresented = isPresented,
            fadeOut = fadeOut,
            onEscape = onEscape,
            finish = {
                isHosted = false
                currentOnDismissed?.invoke()
            },
            content = content,
        )
    }
}

/** The same, for an item: shown while [item] is set; the cover keeps the item it was opened with while it fades out. */
@Composable
fun <T : Any> U2FadeCover(
    item: T?,
    onEscape: (() -> Unit)? = null,
    fadeOut: () -> Float = { U2Motion.Duration.state },
    onDismissed: (() -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    var shown by remember { mutableStateOf(item) }
    SideEffect { if (item != null) shown = item }
    val current = item ?: shown
    U2FadeCover(
        isPresented = item != null,
        onEscape = onEscape,
        fadeOut = fadeOut,
        onDismissed = {
            shown = null
            onDismissed?.invoke()
        },
    ) {
        if (current != null) content(current)
    }
}

@Composable
private fun FadeCoverContainer(
    isPresented: Boolean,
    fadeOut: () -> Float,
    onEscape: (() -> Unit)?,
    finish: () -> Unit,
    content: @Composable () -> Unit,
) {
    val alpha = remember { Animatable(0f) }
    val currentFinish by rememberUpdatedState(finish)
    val currentFadeOut by rememberUpdatedState(fadeOut)
    LaunchedEffect(isPresented) {
        if (isPresented) {
            alpha.animateTo(1f, U2Motion.soft(U2Motion.Duration.state).spec())
        } else {
            alpha.animateTo(0f, U2Motion.soft(currentFadeOut()).spec())
            currentFinish()
        }
    }
    BackHandler(enabled = isPresented && LocalIsLayerVisible.current) { onEscape?.invoke() }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value }
            // The screen below takes no touches while the cover is up.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .semantics { isTraversalGroup = true },
    ) {
        content()
    }
}
