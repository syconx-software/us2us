package com.syconx.us2us.ui.motion

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Whether reduce motion is on: the iOS `accessibilityReduceMotion`. On Android this is the
 * system's “Remove animations” (animator duration scale 0). `U2Theme` provides it from
 * [rememberSystemReduceMotion]; tests and galleries can provide it directly.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** The system's reduce-motion setting, kept current while composed. */
@Composable
fun rememberSystemReduceMotion(): Boolean {
    val context = LocalContext.current
    var reduceMotion by remember(context) { mutableStateOf(isSystemReduceMotion(context)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduceMotion = isSystemReduceMotion(context)
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduceMotion
}

/** “Remove animations” (animator duration scale 0) — for code outside composition. */
fun isSystemReduceMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** [spec], or the short cross-fade when reduce motion is on (`.u2Animation(_:value:)`). */
@Composable
@ReadOnlyComposable
fun <T> u2Spec(spec: AnimationSpec<T>): AnimationSpec<T> = U2Motion.resolve(spec, LocalReduceMotion.current)

/** The spec of [timing], or the short cross-fade when reduce motion is on. */
@Composable
@ReadOnlyComposable
fun <T> u2Spec(timing: U2Timing = U2Motion.state): AnimationSpec<T> =
    U2Motion.resolve(timing, LocalReduceMotion.current).spec()
