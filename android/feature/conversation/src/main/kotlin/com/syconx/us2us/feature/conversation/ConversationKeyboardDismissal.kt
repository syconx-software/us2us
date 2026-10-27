package com.syconx.us2us.feature.conversation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.syconx.us2us.ui.env.rememberU2Keyboard

/** Observe taps without consuming links, media, voice controls or the list's own drag. */
@Composable
internal fun Modifier.conversationKeyboardDismissal(listState: LazyListState): Modifier {
    val keyboard = rememberU2Keyboard()
    val dismiss by rememberUpdatedState(keyboard::dismiss)
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            // User drags only: history loading and "return to latest" keep typing focus.
            if (interaction is DragInteraction.Start) dismiss()
        }
    }
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var tap = true
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                if (event.changes.size != 1 || (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop) tap = false
                if (!pointer.pressed) {
                    if (tap && pointer.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) dismiss()
                    break
                }
            }
        }
    }
}
