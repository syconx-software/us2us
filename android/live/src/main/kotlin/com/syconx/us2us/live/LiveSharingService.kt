package com.syconx.us2us.live

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.Job

/**
 * The foreground service (type location) that keeps live-location sharing going while the app is
 * in the background (PROTOCOL §8.3): the location updates and the encrypted ticks of the running
 * shares, nothing else. Started from the conversation when a share starts, so while-in-use location
 * access is enough; stopped with the last share, at the latest by the alarm at the shares' end,
 * which also wakes a sleeping phone. The system never restarts it ([START_NOT_STICKY]) and nothing
 * about a share is stored: a share the process loses is stopped at the next start.
 *
 * Its notification says only that the location is being shared and for how long: never with whom,
 * nothing from the chat. Hidden on the lock screen, silent, kept on this phone. Tapping it opens the
 * app, which is locked; “Durdur” stops every share. A share goes on in the background only while
 * the notification can be seen: when the system reports notifications turned off, and every minute,
 * the shares are asked to check ([noticeChanged]).
 */
class LiveSharingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var endsAt = 0L
    private var language: String? = null
    private val minuteChange = Runnable { if (post()) noticeChanged?.invoke() }

    /** The app's notifications or the notice's channel blocked or allowed in the system's settings. */
    private val blockStates = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            noticeChanged?.invoke()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val changes = IntentFilter().apply {
            addAction(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED)
            addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
        }
        // Sent by the system to this package only.
        ContextCompat.registerReceiver(this, blockStates, changes, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                endsAt = intent.getLongExtra(EXTRA_ENDS_AT, 0L)
                language = intent.getStringExtra(EXTRA_LANGUAGE) ?: language
                // Without the foreground service the shares cannot go on in the background: they stop.
                if (!post()) stopEverything(startId)
            }
            ACTION_UPDATE -> {
                endsAt = intent.getLongExtra(EXTRA_ENDS_AT, endsAt)
                if (!post()) finish(startId)
            }
            ACTION_STOP -> stopEverything(startId)
            // The shares' time is up by the wall clock (the alarm, which may have woken the phone).
            ACTION_EXPIRE -> {
                val expire = endDueShares
                if (expire == null) finish(startId) else keepAwakeUntil(expire())
            }
            // The last share ended. Asked for in order, so a show still on its way is handled first.
            ACTION_HIDE -> finish(startId)
            // Never asked for by the app (a restart without a share): nothing to show.
            else -> finish(startId)
        }
        return START_NOT_STICKY
    }

    /** The app was removed from the recents: its shares stop, as they do when iOS closes the app. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopEverything(null)
    }

    override fun onDestroy() {
        handler.removeCallbacks(minuteChange)
        unregisterReceiver(blockStates)
        super.onDestroy()
    }

    /** The notification as it is now, again each time the minutes left change. `false` when the system refused the foreground service. */
    private fun post(): Boolean {
        handler.removeCallbacks(minuteChange)
        val now = System.currentTimeMillis()
        val notification = notification(localized(this, language), endsAt, now)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType())
        } catch (_: RuntimeException) {
            return false
        }
        nextChange(endsAt, now)?.let { handler.postDelayed(minuteChange, it) }
        return true
    }

    /** The alarm's own wake ends with this call: the CPU stays awake until the shares have ended and the key is zeroed (a minute at most). */
    private fun keepAwakeUntil(ending: Job) {
        val power = getSystemService(PowerManager::class.java) ?: return
        val awake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        awake.setReferenceCounted(false)
        awake.acquire(EXPIRY_AWAKE_MILLIS)
        ending.invokeOnCompletion { awake.release() }
    }

    private fun stopEverything(startId: Int?) {
        val stop = stopSharing
        if (stop == null) finish(startId) else stop()
    }

    /** Leaves the foreground and stops, unless [startId] is not the latest request (a new share is on its way). */
    private fun finish(startId: Int?) {
        handler.removeCallbacks(minuteChange)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (startId == null) stopSelf() else stopSelf(startId)
    }

    companion object {
        /** What “Durdur” does while shares run (the live service's shares); `null` otherwise. Main thread. */
        @Volatile
        internal var stopSharing: (() -> Unit)? = null

        /** What the alarm at the shares' end does while shares run: the shares whose time is up end; `null` otherwise. Main thread. */
        @Volatile
        internal var endDueShares: (() -> Job)? = null

        /** Notifications may have been turned off while shares run: in the background they stop ([LiveLocationSharing.noticeChanged]). Main thread. */
        @Volatile
        internal var noticeChanged: (() -> Unit)? = null

        const val CHANNEL_ID = "live-location"
        const val NOTIFICATION_ID = 1
        internal const val ACTION_SHOW = "com.syconx.us2us.live.SHOW_SHARING"
        internal const val ACTION_UPDATE = "com.syconx.us2us.live.UPDATE_SHARING"
        internal const val ACTION_STOP = "com.syconx.us2us.live.STOP_SHARING"
        internal const val ACTION_HIDE = "com.syconx.us2us.live.HIDE_SHARING"
        internal const val ACTION_EXPIRE = "com.syconx.us2us.live.EXPIRE_SHARING"
        internal const val EXTRA_ENDS_AT = "ends_at"
        internal const val EXTRA_LANGUAGE = "language"
        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2
        private const val REQUEST_EXPIRE = 3
        private const val WAKE_LOCK_TAG = "us2us:live-location"
        private const val EXPIRY_AWAKE_MILLIS = 60_000L

        internal fun intent(context: Context): Intent = Intent(context, LiveSharingService::class.java)

        /** Shares run until [endsAt]; the words in [language] (a BCP 47 tag). Nothing about whom it is shared with. */
        internal fun show(context: Context, endsAt: Instant, language: String): Intent =
            intent(context).setAction(ACTION_SHOW).putExtra(EXTRA_ENDS_AT, endsAt.toEpochMilli()).putExtra(EXTRA_LANGUAGE, language)

        internal fun update(context: Context, endsAt: Instant): Intent =
            intent(context).setAction(ACTION_UPDATE).putExtra(EXTRA_ENDS_AT, endsAt.toEpochMilli())

        internal fun hide(context: Context): Intent = intent(context).setAction(ACTION_HIDE)

        /** The alarm at the shares' end: explicit and immutable, nothing but the action. */
        internal fun expire(context: Context): PendingIntent =
            PendingIntent.getService(context, REQUEST_EXPIRE, intent(context).setAction(ACTION_EXPIRE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /** The minutes left, rounded up as the bubble and the map show them. */
        internal fun minutesLeft(endsAt: Long, now: Long): Int = max(0, ceil((endsAt - now) / 60_000.0).toInt())

        /** When the minutes left change next (milliseconds from [now]); `null` once the time is up. */
        internal fun nextChange(endsAt: Long, now: Long): Long? {
            val left = endsAt - now
            if (left <= 0) return null
            val intoMinute = left % 60_000L
            return if (intoMinute == 0L) 60_000L else intoMinute
        }

        /** The silent channel, hidden on the lock screen; its name follows the app's language. */
        internal fun channel(context: Context): NotificationChannel {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.live_sharing_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.live_sharing_channel_description)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
                // Recent systems keep this for the user and ignore it; the notification is secret itself.
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            manager.createNotificationChannel(channel)
            return channel
        }

        /** The notification: that the location is shared and the minutes left, “Durdur”; opens the app (locked), never a chat. */
        internal fun notification(context: Context, endsAt: Long, now: Long): Notification {
            channel(context)
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.live_sharing_notice)
                .setContentTitle(context.getString(R.string.live_sharing_title))
                .setContentText(context.getString(R.string.live_sharing_remaining, minutesLeft(endsAt, now)))
                .setCategory(NotificationCompat.CATEGORY_LOCATION_SHARING)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(0, context.getString(R.string.live_sharing_stop), stopIntent(context))
            openIntent(context)?.let(builder::setContentIntent)
            return builder.build()
        }

        /** The app's launcher entry, as from the home screen: the app opens on its lock. */
        private fun openIntent(context: Context): PendingIntent? {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
            return PendingIntent.getActivity(context, REQUEST_OPEN, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun stopIntent(context: Context): PendingIntent =
            PendingIntent.getService(context, REQUEST_STOP, intent(context).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun foregroundType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0

        /** [context] in [language] when one was given: the app's own language reaches the service only this way before Android 13. */
        @SuppressLint("AppBundleLocaleChanges") // The app bundle keeps every language (app/build.gradle.kts, no language split).
        internal fun localized(context: Context, language: String?): Context {
            if (language.isNullOrEmpty()) return context
            val configuration = Configuration(context.resources.configuration)
            configuration.setLocale(Locale.forLanguageTag(language))
            return context.createConfigurationContext(configuration)
        }
    }
}
