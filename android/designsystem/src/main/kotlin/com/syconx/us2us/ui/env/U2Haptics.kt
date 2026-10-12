package com.syconx.us2us.ui.env

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/** The haptics the design uses (iOS `U2Haptics` and `ConversationHaptics`). */
enum class U2Haptic {
    /** A keypad digit: a light tap. */
    KeypadTap,

    /** A long press (message actions). */
    LongPress,

    /** A selection changed (segments, pickers, toggles). */
    Selection,

    /** A wrong code, a refused action. */
    Error,

    /** Done (verified, saved, unlocked). */
    Success,

    /** A message leaves (send, attachment, resend). */
    Send,

    /** The voice recorder: recording starts, locks, stops at the maximum, is cancelled. */
    Voice,
    ;

    /** The `HapticFeedbackConstants` value for [sdk]. Haptics are not motion: reduce motion keeps them. */
    fun feedbackConstant(sdk: Int = Build.VERSION.SDK_INT): Int = when (this) {
        KeypadTap -> HapticFeedbackConstants.KEYBOARD_TAP
        LongPress -> HapticFeedbackConstants.LONG_PRESS
        Selection -> if (sdk >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        Error -> if (sdk >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        Success -> if (sdk >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
        Send, Voice -> HapticFeedbackConstants.CONTEXT_CLICK
    }
}

/**
 * Plays [U2Haptic]s. The app's instance performs them on its window's view (so the system's touch
 * feedback setting applies); tests record them.
 */
fun interface U2Haptics {
    fun perform(haptic: U2Haptic)

    fun keypadTap() = perform(U2Haptic.KeypadTap)

    fun longPress() = perform(U2Haptic.LongPress)

    fun selection() = perform(U2Haptic.Selection)

    fun error() = perform(U2Haptic.Error)

    fun success() = perform(U2Haptic.Success)

    fun send() = perform(U2Haptic.Send)

    fun voice() = perform(U2Haptic.Voice)

    companion object {
        /** No haptics (previews). */
        val None = U2Haptics { }

        /** Haptics performed on [view]. */
        fun of(view: View): U2Haptics = U2Haptics { haptic -> view.performHapticFeedback(haptic.feedbackConstant()) }
    }
}

/** The haptics of the current window. Provided at the root; [U2Haptics.None] outside the app. */
val LocalHaptics = staticCompositionLocalOf { U2Haptics.None }

/** Haptics on the current view (the app root provides these as [LocalHaptics]). */
@Composable
fun rememberViewHaptics(): U2Haptics {
    val view = LocalView.current
    return remember(view) { U2Haptics.of(view) }
}
