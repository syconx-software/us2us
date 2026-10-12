package com.syconx.us2us.runtime.support

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.lifecycle.Lifecycle
import java.util.function.Consumer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The app's place on screen, mapped by the app from its lifecycle (see [AppPhase.of]), so the
 * runtime needs no UI.
 */
enum class AppPhase {
    ACTIVE,

    /**
     * Visible but not in front: the recents screen, the notification shade, a system dialog over
     * the app (the biometric prompt itself), a call.
     */
    INACTIVE,
    BACKGROUND,
    ;

    companion object {
        /**
         * The phase of a lifecycle state: resumed is [ACTIVE], started but paused [INACTIVE],
         * anything below [BACKGROUND].
         */
        fun of(state: Lifecycle.State): AppPhase = when {
            state.isAtLeast(Lifecycle.State.RESUMED) -> ACTIVE
            state.isAtLeast(Lifecycle.State.STARTED) -> INACTIVE
            else -> BACKGROUND
        }
    }
}

/** Background time for the 30-second grace period (PROTOCOL.md §5.4). */
interface BackgroundTaskRunning {
    /** Returns a token, or `null` if the system refused. [expiration] runs if the time runs out first. */
    @MainThread
    fun begin(name: String, expiration: () -> Unit): Int?

    @MainThread
    fun end(token: Int)
}

/**
 * Android has no counterpart of iOS's `beginBackgroundTask`: a process whose activity stopped
 * keeps running until the system needs its memory, and then ends without a callback (as an iOS
 * app that is killed does). Every request is therefore granted and never expires.
 *
 * What it does keep is the CPU awake: with [context], each task holds a partial wake lock for at
 * most [awake]. A timer counts only the time the CPU is awake, so without it the 30-second grace
 * period could last minutes on a phone whose screen is off (a live-location share keeps the
 * process running and on the network meanwhile).
 *
 * On Android 12 and later the cached-apps freezer may also pause a process without a foreground
 * service a few seconds after it left the screen, wake lock or not; its timers then run late, on
 * the next return.
 */
class ProcessBackgroundTasks(
    private val context: Context? = null,
    private val awake: Duration = 1.minutes,
) : BackgroundTaskRunning {
    private var next = 0
    private val running = mutableSetOf<Int>()
    private val wakeLocks = HashMap<Int, PowerManager.WakeLock>()

    override fun begin(name: String, expiration: () -> Unit): Int {
        next += 1
        running += next
        wakeLock()?.let { wakeLocks[next] = it }
        return next
    }

    override fun end(token: Int) {
        running -= token
        wakeLocks.remove(token)?.let { if (it.isHeld) it.release() }
    }

    /** Tokens begun and not ended yet. */
    val runningCount: Int get() = running.size

    private fun wakeLock(): PowerManager.WakeLock? {
        val power = context?.getSystemService(PowerManager::class.java) ?: return null
        return try {
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(awake.inWholeMilliseconds)
            }
        } catch (_: SecurityException) {
            null
        }
    }

    private companion object {
        const val WAKE_LOCK_TAG = "us2us:background"
    }
}

/** Background tasks driven by hand (tests). */
class NoBackgroundTasks : BackgroundTaskRunning {
    var begun = 0
        private set
    var ended = 0
        private set

    /** Behave like a system that grants no more background time. */
    var refusesNewTasks = false
    private val expirations = mutableMapOf<Int, () -> Unit>()

    override fun begin(name: String, expiration: () -> Unit): Int? {
        if (refusesNewTasks) return null
        begun += 1
        expirations[begun] = expiration
        return begun
    }

    override fun end(token: Int) {
        if (expirations.remove(token) != null) ended += 1
    }

    /** Simulates the system expiring every running task. */
    fun expireAll() {
        val pending = expirations.values.toList()
        expirations.clear()
        for (expiration in pending) expiration()
    }
}

/** Screenshot and screen-capture events from the system. */
interface CaptureEventSource {
    /** Whether the app's windows are being recorded right now. */
    val isCaptured: Boolean

    @MainThread
    fun start(screenshot: () -> Unit, captureChanged: (Boolean) -> Unit)

    @MainThread
    fun stop()
}

/**
 * The platform's detection, per visible activity:
 *
 * - screenshots: `Activity.registerScreenCaptureCallback` (Android 14, permission
 *   `DETECT_SCREEN_CAPTURE`), called after a screenshot was taken while the activity was visible;
 * - recording: `WindowManager.addScreenRecordingCallback` (Android 15, permission
 *   `DETECT_SCREEN_RECORDING`), whether the app's windows are in a screen recording.
 *
 * The app blocks capture with `FLAG_SECURE` (PROTOCOL.md §9); this only tells the contact when
 * someone tries. Older phones, or a manifest without the permission, report nothing.
 */
class SystemCaptureEvents(private val application: Application) : CaptureEventSource {
    private var onScreenshot: (() -> Unit)? = null
    private var onCaptureChanged: ((Boolean) -> Unit)? = null
    private val attached = mutableMapOf<Activity, Attachment>()
    private val recordingActivities = mutableSetOf<Activity>()

    override val isCaptured: Boolean get() = recordingActivities.isNotEmpty()

    override fun start(screenshot: () -> Unit, captureChanged: (Boolean) -> Unit) {
        stop()
        onScreenshot = screenshot
        onCaptureChanged = captureChanged
        application.registerActivityLifecycleCallbacks(callbacks)
    }

    override fun stop() {
        application.unregisterActivityLifecycleCallbacks(callbacks)
        for (activity in attached.keys.toList()) detach(activity)
        recordingActivities.clear()
        onScreenshot = null
        onCaptureChanged = null
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) = attach(activity)

        override fun onActivityStopped(activity: Activity) = detach(activity)

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = detach(activity)
    }

    private class Attachment(val screenshot: Any?, val recording: Any?)

    private fun attach(activity: Activity) {
        if (activity in attached) return
        val screenshot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) registerScreenshots(activity) else null
        val recording = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) registerRecording(activity) else null
        attached[activity] = Attachment(screenshot, recording)
    }

    // The capture permissions are install-time ones the app manifest declares; every call below
    // is skipped when the manifest does not (checked at run time, which lint does not follow).
    @SuppressLint("MissingPermission")
    private fun detach(activity: Activity) {
        val attachment = attached.remove(activity) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            application.checkSelfPermission(Manifest.permission.DETECT_SCREEN_CAPTURE) == PackageManager.PERMISSION_GRANTED
        ) {
            (attachment.screenshot as? Activity.ScreenCaptureCallback)?.let { activity.unregisterScreenCaptureCallback(it) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            application.checkSelfPermission(Manifest.permission.DETECT_SCREEN_RECORDING) == PackageManager.PERMISSION_GRANTED
        ) {
            @Suppress("UNCHECKED_CAST")
            (attachment.recording as? Consumer<Int>)?.let { activity.windowManager.removeScreenRecordingCallback(it) }
        }
        setRecording(activity, false)
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun registerScreenshots(activity: Activity): Activity.ScreenCaptureCallback? {
        if (application.checkSelfPermission(Manifest.permission.DETECT_SCREEN_CAPTURE) != PackageManager.PERMISSION_GRANTED) return null
        val callback = Activity.ScreenCaptureCallback {
            RuntimeLog.privacy.info("screenshot detected")
            onScreenshot?.invoke()
        }
        activity.registerScreenCaptureCallback(activity.mainExecutor, callback)
        return callback
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun registerRecording(activity: Activity): Consumer<Int>? {
        if (application.checkSelfPermission(Manifest.permission.DETECT_SCREEN_RECORDING) != PackageManager.PERMISSION_GRANTED) return null
        val callback = Consumer<Int> { state -> setRecording(activity, state == WindowManager.SCREEN_RECORDING_STATE_VISIBLE) }
        val initial = activity.windowManager.addScreenRecordingCallback(activity.mainExecutor, callback)
        setRecording(activity, initial == WindowManager.SCREEN_RECORDING_STATE_VISIBLE)
        return callback
    }

    private fun setRecording(activity: Activity, recording: Boolean) {
        val before = isCaptured
        if (recording) recordingActivities += activity else recordingActivities -= activity
        if (isCaptured != before) {
            if (isCaptured) {
                RuntimeLog.privacy.info("screen recording started")
            } else {
                RuntimeLog.privacy.info("screen recording ended")
            }
            onCaptureChanged?.invoke(isCaptured)
        }
    }
}

/** Capture events driven by hand (tests). */
class ManualCaptureEvents : CaptureEventSource {
    override var isCaptured = false
        private set
    private var screenshot: (() -> Unit)? = null
    private var captureChanged: ((Boolean) -> Unit)? = null

    override fun start(screenshot: () -> Unit, captureChanged: (Boolean) -> Unit) {
        this.screenshot = screenshot
        this.captureChanged = captureChanged
    }

    override fun stop() {
        screenshot = null
        captureChanged = null
    }

    fun takeScreenshot() {
        screenshot?.invoke()
    }

    fun setCaptured(captured: Boolean) {
        isCaptured = captured
        captureChanged?.invoke(captured)
    }
}
