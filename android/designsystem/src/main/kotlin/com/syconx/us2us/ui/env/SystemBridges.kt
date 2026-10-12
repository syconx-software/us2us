package com.syconx.us2us.ui.env

import android.content.ActivityNotFoundException
import android.os.SystemClock
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Our own system activities in flight: the photo picker, the document picker, the camera, the share
 * sheet, a permission dialog. They put the app in the background as far as Android is concerned,
 * but returning from them is not a return from the background: the app does not lock (iOS presents
 * these inside the app, so it never leaves the foreground).
 *
 * A launch [begin]s a guard that [end]s with its result, or at the next resume for launches without
 * one ([beginUntilResume]). A guard older than [maxAway] does not count: whoever stays away that
 * long comes back to the lock. A permission dialog ([beginDialog]) only pauses the app: it covers
 * the pause, never a stop.
 */
@Stable
class ExternalActivityGuard(
    val maxAway: Duration = 5.minutes,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private var inFlight by mutableIntStateOf(0)
    private var untilResume by mutableIntStateOf(0)
    private var dialogs by mutableIntStateOf(0)
    private var startedAt = 0L

    /** One of our system activities is in front (or its result has not arrived yet). */
    val isActive: Boolean get() = inFlight + untilResume > 0

    /** Only permission dialogs are in flight: the app stopped behind one was left (home, recents). */
    val onlyDialogs: Boolean get() = dialogs > 0 && inFlight == dialogs && untilResume == 0

    /** When the oldest guard still in flight began (`SystemClock.elapsedRealtime`). */
    val since: Long get() = startedAt

    /** A system activity with a result is about to start. */
    fun begin() {
        if (!isActive) startedAt = elapsedRealtime()
        inFlight++
    }

    /** Its result arrived (or it failed to start). */
    fun end() {
        if (inFlight > 0) inFlight--
    }

    /** A permission dialog is about to show over the app (translucent: the app is paused, not stopped). */
    fun beginDialog() {
        begin()
        dialogs++
    }

    /** The dialog's answer arrived (or it failed to show). */
    fun endDialog() {
        if (dialogs > 0) dialogs--
        end()
    }

    /** A system activity without a result (the share sheet, a link) is about to start; it ends at the next resume. */
    fun beginUntilResume() {
        if (!isActive) startedAt = elapsedRealtime()
        untilResume++
    }

    /** The app is in front again: guards without a result end. */
    fun resumed() {
        untilResume = 0
    }

    /** Whether a return after [awayMillis] in the background is covered by a guard that was active when the app left. */
    fun covers(awayMillis: Long): Boolean = awayMillis < maxAway.inWholeMilliseconds

    /** Runs [launch] (a `startActivity`) under a guard that ends at the next resume. */
    inline fun <T> launching(launch: () -> T): T {
        beginUntilResume()
        try {
            return launch()
        } catch (failure: ActivityNotFoundException) {
            resumed()
            throw failure
        }
    }
}

/** The guard of the app. Provided at the root; a fresh one outside the app. */
val LocalExternalActivityGuard = staticCompositionLocalOf { ExternalActivityGuard() }

/** An activity-result launcher whose launches are covered by the [ExternalActivityGuard]. */
@Stable
class GuardedLauncher<I, O> internal constructor(
    private val launcher: ManagedActivityResultLauncher<I, O>,
    private val guard: ExternalActivityGuard,
) {
    val contract: ActivityResultContract<I, O> get() = launcher.contract

    /** Starts the system activity; the app does not lock while it is in front. */
    fun launch(input: I) {
        guard.begin(isPermissionDialog(contract))
        try {
            launcher.launch(input)
        } catch (failure: ActivityNotFoundException) {
            guard.end(isPermissionDialog(contract))
            throw failure
        } catch (failure: IllegalStateException) {
            guard.end(isPermissionDialog(contract))
            throw failure
        }
    }
}

/** A permission request shows the system's dialog over the app, which only pauses it. */
private fun isPermissionDialog(contract: ActivityResultContract<*, *>): Boolean =
    contract is ActivityResultContracts.RequestPermission || contract is ActivityResultContracts.RequestMultiplePermissions

private fun ExternalActivityGuard.begin(dialog: Boolean) = if (dialog) beginDialog() else begin()

private fun ExternalActivityGuard.end(dialog: Boolean) = if (dialog) endDialog() else end()

/**
 * `rememberLauncherForActivityResult` under the app's [ExternalActivityGuard]: use it for the photo
 * picker, the document picker, the camera and permission requests.
 */
@Composable
fun <I, O> rememberGuardedLauncher(contract: ActivityResultContract<I, O>, onResult: (O) -> Unit): GuardedLauncher<I, O> {
    val guard = LocalExternalActivityGuard.current
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(contract) { result ->
        guard.end(isPermissionDialog(contract))
        currentOnResult(result)
    }
    return remember(launcher, guard) { GuardedLauncher(launcher, guard) }
}

/**
 * The system bars' appearance: they follow the theme, except while a screen that is always dark
 * (5.6 viewer, 6.2 scanner, the media viewers) is up — then the icons are light whatever the theme.
 */
@Stable
class U2SystemBars {
    private val forcedDark = mutableStateListOf<Any>()

    /** A screen forces light icons on dark. */
    val forcesDark: Boolean get() = forcedDark.isNotEmpty()

    fun forceDark(owner: Any) {
        if (owner !in forcedDark) forcedDark += owner
    }

    fun release(owner: Any) {
        forcedDark.remove(owner)
    }
}

/** The app's system bars. Provided at the root. */
val LocalSystemBars = staticCompositionLocalOf { U2SystemBars() }

/** While composed, the system bars show light icons, as on an always-dark screen (iOS `PresentationInterfaceStyle(.dark)`). */
@Composable
fun ForceDarkSystemBars() {
    val bars = LocalSystemBars.current
    DisposableEffect(bars) {
        val owner = Any()
        bars.forceDark(owner)
        onDispose { bars.release(owner) }
    }
}

/** Ends editing wherever it is (2.1 → 2.2, a tap outside a field, 5.3 opening): iOS `U2Keyboard.dismiss()`. */
@Stable
class U2Keyboard internal constructor(
    private val keyboard: SoftwareKeyboardController?,
    private val focus: FocusManager,
) {
    fun dismiss() {
        keyboard?.hide()
        focus.clearFocus(force = true)
    }
}

@Composable
fun rememberU2Keyboard(): U2Keyboard {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    return remember(keyboard, focus) { U2Keyboard(keyboard, focus) }
}
