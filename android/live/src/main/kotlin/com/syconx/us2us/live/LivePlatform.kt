package com.syconx.us2us.live

import android.app.Activity
import android.content.Context

/**
 * Our own system dialogs in flight (a permission request): the app's guard, so the app neither
 * covers nor locks itself while one is up. A dialog only pauses the app, so a stop behind it is
 * still the user leaving. The app supplies it ([LiveMessenger.create]).
 */
interface ExternalActivityHooks {
    /** A system dialog with a result is about to show over the app. */
    fun begin()

    /** Its result arrived (or it failed to show). */
    fun end()

    companion object {
        /** No guard (tests). */
        val None: ExternalActivityHooks = object : ExternalActivityHooks {
            override fun begin() = Unit

            override fun end() = Unit
        }
    }
}

/** What the live service needs from the platform besides the runtime: the app's context, the activity in front and the guard. */
class LivePlatform(
    /** The application context. */
    val context: Context,
    private val activities: () -> Activity?,
    private val hooks: ExternalActivityHooks = ExternalActivityHooks.None,
) {
    /** The app's resumed activity, or `null` when none is in the foreground. */
    fun resumedActivity(): Activity? = activities()

    fun externalActivityStarts() = hooks.begin()

    fun externalActivityEnded() = hooks.end()
}
