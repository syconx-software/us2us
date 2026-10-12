package com.syconx.us2us.ui.bubbles

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion

/**
 * Shows `content(value)`; when [value] changes, the old and the new content cross-fade on top of
 * each other (0.36 s on the design curve; reduce motion: the short cross-fade). State-driven, so
 * it does not rely on an enter/exit transition, which a scrolling column may not run.
 */
@Composable
fun <T> StateCrossFade(
    value: T,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable (T) -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    // What is on screen: the new value replaces it in the effect, so the frame in between still
    // shows the old one (no flash of the new).
    var shown by remember { mutableStateOf(value) }
    var previous by remember { mutableStateOf<Holder<T>?>(null) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(value) {
        if (value == shown) return@LaunchedEffect
        previous = Holder(shown)
        shown = value
        progress.snapTo(0f)
        progress.animateTo(1f, U2Motion.resolve(U2Motion.state, reduceMotion).spec())
        previous = null
    }
    Box(modifier, contentAlignment = contentAlignment) {
        val old = previous
        if (old != null) {
            Box(Modifier.graphicsLayer { alpha = 1f - progress.value }.clearAndSetSemantics {}, contentAlignment = contentAlignment) {
                content(old.value)
            }
        }
        Box(Modifier.graphicsLayer { alpha = if (previous == null) 1f else progress.value }, contentAlignment = contentAlignment) {
            content(shown)
        }
    }
}

/** A previous value (which may itself be `null`). */
private class Holder<T>(val value: T)
