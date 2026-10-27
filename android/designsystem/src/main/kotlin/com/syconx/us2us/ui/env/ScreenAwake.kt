package com.syconx.us2us.ui.env

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private class ScreenAwakeRequests {
    private val owners = mutableStateMapOf<Any, Boolean>()
    val hasMedia: Boolean get() = owners.isNotEmpty()
    fun update(owner: Any, enabled: Boolean) {
        if (enabled) owners[owner] = true else owners.remove(owner)
    }
}

private val LocalScreenAwakeRequests = staticCompositionLocalOf<ScreenAwakeRequests?> { null }

/** A single writer combines the optional preference and independent media requests. */
@Composable
fun ScreenAwakeScope(preference: Boolean, contentVisible: Boolean, content: @Composable () -> Unit) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val requests = remember { ScreenAwakeRequests() }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState == Lifecycle.State.RESUMED) }

    DisposableEffect(view, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState == Lifecycle.State.RESUMED
            // Release synchronously before a picker, system lock or app-switcher snapshot.
            if (!resumed) view.keepScreenOn = false
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            view.keepScreenOn = false
        }
    }
    val keepAwake = resumed && contentVisible && (preference || requests.hasMedia)
    SideEffect { view.keepScreenOn = keepAwake }
    CompositionLocalProvider(LocalScreenAwakeRequests provides requests, content = content)
}

/** Releasing one recording must never clear another request or the user's preference. */
@Composable
fun KeepScreenAwakeForMedia(enabled: Boolean) {
    val requests = LocalScreenAwakeRequests.current
    val owner = remember { Any() }
    DisposableEffect(requests, owner, enabled) {
        requests?.update(owner, enabled)
        onDispose { requests?.update(owner, false) }
    }
}
