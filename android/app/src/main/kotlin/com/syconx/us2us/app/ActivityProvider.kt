package com.syconx.us2us.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * The app's activity in front, for the system prompts the live service shows over it (the biometric
 * prompt of the lock, the location permission dialog). Weakly held: a destroyed activity is never
 * kept alive, and none is returned while the app is in the background.
 */
class ActivityProvider private constructor() : Application.ActivityLifecycleCallbacks {
    private var reference: WeakReference<Activity>? = null

    /** The resumed activity, or `null` when none is in the foreground. */
    val current: Activity? get() = reference?.get()

    override fun onActivityResumed(activity: Activity) {
        reference = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        // The system prompts are shown over a paused activity too (a dialog in front of it): it
        // stays the one in front until another resumes or it stops.
    }

    override fun onActivityStopped(activity: Activity) {
        if (reference?.get() === activity) reference = null
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (reference?.get() === activity) reference = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    companion object {
        @Volatile
        private var installed: ActivityProvider? = null

        /** The provider of [application], registered once per process. */
        fun of(application: Application): ActivityProvider = installed ?: synchronized(this) {
            installed ?: ActivityProvider().also { provider ->
                application.registerActivityLifecycleCallbacks(provider)
                installed = provider
            }
        }
    }
}
