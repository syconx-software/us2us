package com.syconx.us2us.live

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.syconx.us2us.domain.NoticePermission
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.Job

/**
 * What keeps live-location shares going while the app is in the background: on Android a foreground
 * service of type location with its notification ([LiveSharingService]), the counterpart of the iOS
 * background location mode and its indicator (PROTOCOL §8.3).
 */
interface SharingNotice {
    /**
     * A share started (from the conversation, so the app is in front): shares run until [endsAt];
     * [stopAll] is what the notice's “Durdur” does, [endDue] what it does once [endsAt] has passed
     * on the wall clock, [changed] what it does when notifications may have been turned off.
     * `false` when the system refused it.
     */
    fun show(endsAt: Instant, stopAll: () -> Unit, endDue: () -> Job, changed: () -> Unit): Boolean

    /** A share ended and others run on, until [endsAt]. */
    fun update(endsAt: Instant)

    /** No share runs any more. */
    fun hide()

    /** Whether the user can see the notice (notifications allowed and its channel not turned off). */
    fun canBeSeen(): Boolean = true

    /** The share sheet's “Bildirimlere izin ver” ([NotificationAccess.request]). */
    suspend fun allow(): NoticePermission = NoticePermission.Allowed
}

/** [SharingNotice] as [LiveSharingService]. */
class ForegroundSharingNotice(private val platform: LivePlatform) : SharingNotice {
    private val context: Context get() = platform.context
    private val access = NotificationAccess(platform)

    /** The service was asked to show the notice and not yet to go. */
    private var shown = false

    override fun show(endsAt: Instant, stopAll: () -> Unit, endDue: () -> Job, changed: () -> Unit): Boolean {
        LiveSharingService.stopSharing = stopAll
        LiveSharingService.endDueShares = endDue
        LiveSharingService.noticeChanged = changed
        try {
            // In the app's own language, which the activity in front carries (per-app language).
            val language = (platform.resumedActivity() ?: context).resources.configuration.locales[0] ?: Locale.getDefault()
            ContextCompat.startForegroundService(context, LiveSharingService.show(context, endsAt, language.toLanguageTag()))
        } catch (_: IllegalStateException) {
            // Android 12+: not in front any more (ForegroundServiceStartNotAllowedException).
            return refused()
        } catch (_: SecurityException) {
            return refused()
        }
        shown = true
        expireAt(endsAt)
        return true
    }

    /** The service was not started for a new share; one already showing for earlier shares keeps its “Durdur”. */
    private fun refused(): Boolean {
        if (!shown) {
            LiveSharingService.stopSharing = null
            LiveSharingService.endDueShares = null
            LiveSharingService.noticeChanged = null
        }
        return false
    }

    override fun update(endsAt: Instant) {
        if (!shown) return
        expireAt(endsAt)
        try {
            // An app with a foreground service may start its services from the background.
            context.startService(LiveSharingService.update(context, endsAt))
        } catch (_: IllegalStateException) {
        } catch (_: SecurityException) {
        }
    }

    override fun hide() {
        LiveSharingService.stopSharing = null
        LiveSharingService.endDueShares = null
        LiveSharingService.noticeChanged = null
        if (!shown) return
        shown = false
        context.getSystemService(AlarmManager::class.java)?.cancel(LiveSharingService.expire(context))
        try {
            // A request after the show, not a stop: a service still on its way reaches the
            // foreground first, as Android requires of a service started for the foreground.
            context.startService(LiveSharingService.hide(context))
        } catch (_: IllegalStateException) {
            context.stopService(LiveSharingService.intent(context))
        } catch (_: SecurityException) {
            context.stopService(LiveSharingService.intent(context))
        }
    }

    override fun canBeSeen(): Boolean = access.canBeSeen()

    override suspend fun allow(): NoticePermission = access.request()

    /**
     * Wakes the phone when the shares' time is up by the wall clock, and the service ends them:
     * their timers count only the time the phone is awake, so a share without fixes (indoors,
     * location off) would keep the service and the key long past its end on a sleeping phone.
     * Exact where the app may set exact alarms, otherwise as close as the system allows.
     */
    @SuppressLint("MissingPermission") // Exact only after canScheduleExactAlarms() on Android 12+, which lint cannot follow.
    private fun expireAt(endsAt: Instant) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val operation = LiveSharingService.expire(context)
        val time = endsAt.toEpochMilli()
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, operation)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, operation)
            }
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, operation)
        }
    }
}

/**
 * Whether the share's notice can be seen, and the share sheet's request for it. Without it a share
 * runs while the app is in front and stops when the app leaves ([LiveLocationSharing.appLeft]).
 */
class NotificationAccess(private val platform: LivePlatform) {
    private val context: Context get() = platform.context
    private val preferences = context.getSharedPreferences(LIVE_PREFERENCES, Context.MODE_PRIVATE)

    fun isGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Notifications allowed (on Android 13+ also the permission) and the notice's channel not turned off. */
    fun canBeSeen(): Boolean {
        val notifications = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!notifications.areNotificationsEnabled()) return false
        return notifications.getNotificationChannel(LiveSharingService.CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * “Bildirimlere izin ver”: the system's question over the activity in front (Android 13+), as
     * long as Android still asks it; refused for good, or turned off in the settings (the app's
     * notifications, the notice's channel), only the settings can allow it. Main thread.
     */
    suspend fun request(): NoticePermission {
        if (canBeSeen()) return NoticePermission.Allowed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isGranted()) {
            val activity = platform.resumedActivity() as? ComponentActivity ?: return NoticePermission.Refused
            val asks = !preferences.getBoolean(ASKED_KEY, false) ||
                activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            if (asks) {
                preferences.edit { putBoolean(ASKED_KEY, true) }
                requestPermissions(platform, activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                return when {
                    canBeSeen() -> NoticePermission.Allowed
                    isGranted() -> NoticePermission.InSettings
                    else -> NoticePermission.Refused
                }
            }
        }
        return NoticePermission.InSettings
    }

    private companion object {
        const val ASKED_KEY = "notifications.asked"
    }
}
