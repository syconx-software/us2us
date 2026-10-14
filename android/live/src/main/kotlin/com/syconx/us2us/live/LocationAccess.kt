package com.syconx.us2us.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Where location access stands for this app (while in use; never in the background). */
enum class LocationAuthorization {
    /** Not asked yet (or asked and dismissed, so it may be asked again): the map offers “İzin ver”. */
    NotDetermined,

    /** Refused for good (or restricted): only the system settings can allow it. */
    Denied,

    /** Allowed while in use (precise or approximate). */
    Allowed,
}

/**
 * Location permission (“while in use” only): what it is now, and asking for it once through the
 * system dialog over the activity in front. Asking puts the app behind the system's permission
 * activity, which is one of our own system activities, so the launch is guarded and the app does
 * not lock or cover itself.
 */
class LocationAccess(
    private val context: Context,
    private val platform: LivePlatform,
) {
    private val preferences = context.getSharedPreferences(LIVE_PREFERENCES, Context.MODE_PRIVATE)

    val authorization: LocationAuthorization
        get() {
            if (isGranted()) return LocationAuthorization.Allowed
            // Never asked, or the system would show the dialog again: still undecided.
            val asked = preferences.getBoolean(ASKED_KEY, false)
            val activity = platform.resumedActivity()
            val rationale = activity != null && activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
            return if (!asked || rationale) LocationAuthorization.NotDetermined else LocationAuthorization.Denied
        }

    fun isGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Asks for location while in use, only while it is undecided. `true` when it is allowed (also
     * when it already was). Main thread.
     */
    suspend fun authorize(): Boolean {
        if (isGranted()) return true
        if (authorization == LocationAuthorization.Denied) return false
        val activity = platform.resumedActivity() as? ComponentActivity ?: return false
        preferences.edit { putBoolean(ASKED_KEY, true) }
        val granted = requestPermissions(platform, activity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        return granted || isGranted()
    }

    private companion object {
        const val ASKED_KEY = "location.asked"
    }
}

/** The live service's own preferences: only whether a permission was asked already. */
internal const val LIVE_PREFERENCES = "us2us.live"

/**
 * Asks for [permissions] through the system dialog over [activity]; `true` when any of them was
 * granted. The dialog is one of our own system activities, so the launch is guarded and the app
 * does not lock or cover itself. Main thread.
 */
internal suspend fun requestPermissions(platform: LivePlatform, activity: ComponentActivity, permissions: Array<String>): Boolean =
    suspendCancellableCoroutine { continuation ->
        val key = "us2us.permission.${UUID.randomUUID()}"
        val registration = arrayOfNulls<ActivityResultLauncher<Array<String>>>(1)
        val launcher = activity.activityResultRegistry.register(key, ActivityResultContracts.RequestMultiplePermissions()) { result ->
            registration[0]?.unregister()
            platform.externalActivityEnded()
            if (continuation.isActive) continuation.resume(result.values.any { it })
        }
        registration[0] = launcher
        continuation.invokeOnCancellation { launcher.unregister() }
        platform.externalActivityStarts()
        try {
            launcher.launch(permissions)
        } catch (_: Exception) {
            launcher.unregister()
            platform.externalActivityEnded()
            if (continuation.isActive) continuation.resume(false)
        }
    }
