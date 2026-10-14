package com.syconx.us2us.ui.env

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.Executor
import java.util.function.Consumer

/**
 * Hears whether this app's window is being recorded or mirrored (Android 15+,
 * `WindowManager.addScreenRecordingCallback`, install-time permission `DETECT_SCREEN_RECORDING`)
 * while composed: [onChange] gets the current state at once and every change after it. Older
 * versions report nothing; the window is `FLAG_SECURE`, so a recording shows black anyway. The iOS
 * counterpart is `UIScreen.isCaptured`.
 */
@Composable
fun ScreenRecordingProbe(onChange: (Boolean) -> Unit) {
    if (Build.VERSION.SDK_INT < 35) return
    val activity = LocalContext.current.findActivity() ?: return
    val current by rememberUpdatedState(onChange)
    DisposableEffect(activity) {
        val executor = Executor { command -> activity.runOnUiThread(command) }
        val unregister = Recording.register(activity, executor) { current(it) }
        onDispose { unregister?.invoke() }
    }
}

@RequiresApi(35)
private object Recording {
    /** Registers, returning the unregistration; `null` when the platform refused (no permission). */
    fun register(activity: Activity, executor: Executor, onChange: (Boolean) -> Unit): (() -> Unit)? {
        val manager = activity.getSystemService(WindowManager::class.java) ?: return null
        val consumer = Consumer<Int> { state -> onChange(state == WindowManager.SCREEN_RECORDING_STATE_VISIBLE) }
        return runCatching {
            val initial = manager.addScreenRecordingCallback(executor, consumer)
            onChange(initial == WindowManager.SCREEN_RECORDING_STATE_VISIBLE)
            val unregister: () -> Unit = { runCatching { manager.removeScreenRecordingCallback(consumer) } }
            unregister
        }.getOrNull()
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
