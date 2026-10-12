package com.syconx.us2us.live

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import androidx.core.app.NotificationCompat
import java.time.Instant
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

/**
 * The foreground service that keeps a live-location share going in the background, and its
 * notification: generic words only (never with whom, nothing from the chat), silent, hidden on the
 * lock screen, “Durdur” for every share, a tap opens the app on its lock; never restarted by the
 * system.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveSharingServiceTest {
    private val context: Application = org.robolectric.RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        LiveSharingService.stopSharing = null
        LiveSharingService.endDueShares = null
        LiveSharingService.noticeChanged = null
    }

    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))

    /** The app's context, refusing a foreground service once [refuses] is set (Android 12+ in the background). */
    private class Front(base: Context) : ContextWrapper(base) {
        var refuses = false

        override fun startForegroundService(service: Intent): ComponentName? {
            if (refuses) throw IllegalStateException("not allowed in the background")
            return super.startForegroundService(service)
        }
    }

    private fun started(language: String, minutes: Long = 15): Pair<LiveSharingService, Notification> {
        val endsAt = Instant.ofEpochMilli(System.currentTimeMillis()).plusSeconds(minutes * 60)
        val controller = Robolectric.buildService(LiveSharingService::class.java, LiveSharingService.show(context, endsAt, language)).create()
        val service = controller.get()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(LiveSharingService.show(context, endsAt, language), 0, 1))
        val shadow = shadowOf(service)
        assertEquals(LiveSharingService.NOTIFICATION_ID, shadow.lastForegroundNotificationId)
        return service to shadow.lastForegroundNotification
    }

    @Test
    fun theNotificationSaysOnlyThatTheLocationIsSharedAndForHowLong() {
        val (_, english) = started("en")
        assertEquals("Sharing your location", english.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("15 min left", english.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("Stop", english.actions.single().title.toString())

        val (_, turkish) = started("tr", minutes = 60)
        assertEquals("Konumun paylaşılıyor", turkish.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("60 dk kaldı", turkish.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("Durdur", turkish.actions.single().title.toString())

        for (notification in listOf(english, turkish)) {
            assertEquals("hidden on the lock screen", Notification.VISIBILITY_SECRET, notification.visibility)
            assertNull("no public version for the lock screen", notification.publicVersion)
            assertEquals(LiveSharingService.CHANNEL_ID, notification.channelId)
            assertEquals(NotificationCompat.CATEGORY_LOCATION_SHARING, notification.category)
            assertTrue("ongoing", notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertTrue("kept on this phone", notification.flags and Notification.FLAG_LOCAL_ONLY != 0)
        }
        // What the service is told: the end and the language, nothing about the contact or the chat.
        val intent = LiveSharingService.show(context, Instant.now(), "tr")
        assertEquals(setOf(LiveSharingService.EXTRA_ENDS_AT, LiveSharingService.EXTRA_LANGUAGE), intent.extras!!.keySet())
    }

    @Test
    fun itsChannelIsSilentAndHiddenOnTheLockScreen() {
        started("en")
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(LiveSharingService.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertFalse(channel.canShowBadge())
        assertEquals(Notification.VISIBILITY_SECRET, channel.lockscreenVisibility)
        assertEquals("Live location", channel.name.toString())
        started("tr")
        assertEquals("the channel follows the app's language", "Canlı konum", context.getSystemService(NotificationManager::class.java).getNotificationChannel(LiveSharingService.CHANNEL_ID).name.toString())
    }

    @Test
    fun aTapOpensTheAppAsFromTheHomeScreenNeverAChat() {
        val launcher = ComponentName(context, "com.syconx.us2us.app.MainActivity")
        val packages = shadowOf(context.packageManager)
        packages.addActivityIfNotPresent(launcher)
        packages.addIntentFilterForActivity(launcher, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        val (_, notification) = started("en")
        assertTrue(notification.contentIntent.isActivity)
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertEquals(launcher, intent.component)
        assertNull("no chat, no data", intent.data)
        assertTrue(intent.extras?.isEmpty ?: true)
    }

    @Test
    fun itsStopEndsEveryShare() {
        var stops = 0
        LiveSharingService.stopSharing = { stops += 1 }
        val (service, notification) = started("en")
        val action = notification.actions.single().actionIntent
        assertTrue(action.isService)
        val stop = shadowOf(action).savedIntent
        assertEquals(ComponentName(context, LiveSharingService::class.java), stop.component)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(stop, 0, 2))
        assertEquals(1, stops)
        assertFalse("the shares end, then the live service stops it", shadowOf(service).isStoppedBySelf)

        // The app removed from the recents: the shares stop, as when iOS closes the app.
        service.onTaskRemoved(Intent())
        assertEquals(2, stops)
    }

    @Test
    fun withoutASharePendingItStopsAndIsNeverRestarted() {
        val (service, _) = started("en")
        // A stop after the process lost its shares: nothing to stop, the service goes.
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(context, LiveSharingService::class.java).setAction(LiveSharingService.ACTION_STOP), 0, 2))
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)

        val restarted = Robolectric.buildService(LiveSharingService::class.java).create().get()
        assertEquals("a restart without a share shows nothing", Service.START_NOT_STICKY, restarted.onStartCommand(null, Service.START_FLAG_REDELIVERY, 1))
        assertTrue(shadowOf(restarted).isStoppedBySelf)
        assertNull(shadowOf(restarted).lastForegroundNotification)
    }

    @Test
    fun theMinutesLeftChangeOnceAMinute() {
        val now = 1_000_000L
        assertEquals(15, LiveSharingService.minutesLeft(now + 14 * 60_000L + 30_000L, now))
        assertEquals(30_000L, LiveSharingService.nextChange(now + 14 * 60_000L + 30_000L, now))
        assertEquals(15, LiveSharingService.minutesLeft(now + 15 * 60_000L, now))
        assertEquals(60_000L, LiveSharingService.nextChange(now + 15 * 60_000L, now))
        assertEquals(1, LiveSharingService.minutesLeft(now + 1, now))
        assertEquals(1L, LiveSharingService.nextChange(now + 1, now))
        assertEquals(0, LiveSharingService.minutesLeft(now, now))
        assertNull(LiveSharingService.nextChange(now, now))
        assertNull(LiveSharingService.nextChange(now - 5, now))
    }

    @Test
    fun theNoticeStartsTheServiceWithAShareAndStopsItWithTheLast() {
        val notice = ForegroundSharingNotice(LivePlatform(context, { null }))
        val application = shadowOf(context)
        val endsAt = Instant.now().plusSeconds(900)
        var stops = 0
        assertTrue(notice.show(endsAt, { stops += 1 }, { Job() }, {}))
        val shown = application.nextStartedService
        assertEquals(ComponentName(context, LiveSharingService::class.java), shown.component)
        assertEquals(LiveSharingService.ACTION_SHOW, shown.action)
        assertEquals(endsAt.toEpochMilli(), shown.getLongExtra(LiveSharingService.EXTRA_ENDS_AT, 0))
        LiveSharingService.stopSharing!!.invoke()
        assertEquals(1, stops)
        // The wall clock's alarm at the end, which wakes a sleeping phone.
        val alarm = alarms.scheduledAlarms.single()
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.getType())
        assertEquals(endsAt.toEpochMilli(), alarm.triggerAtMs)
        assertTrue(alarm.isAllowWhileIdle)
        alarms.fireAlarm(alarm)
        val expire = application.nextStartedService
        assertEquals(ComponentName(context, LiveSharingService::class.java), expire.component)
        assertEquals(LiveSharingService.ACTION_EXPIRE, expire.action)
        assertTrue("nothing but the action", expire.extras?.isEmpty ?: true)

        notice.update(endsAt.minusSeconds(600))
        val updated = application.nextStartedService
        assertEquals(LiveSharingService.ACTION_UPDATE, updated.action)
        assertEquals(endsAt.minusSeconds(600).toEpochMilli(), updated.getLongExtra(LiveSharingService.EXTRA_ENDS_AT, 0))
        assertEquals("the alarm follows the latest end", endsAt.minusSeconds(600).toEpochMilli(), alarms.scheduledAlarms.single().triggerAtMs)

        notice.hide()
        val hidden = application.nextStartedService
        assertEquals(ComponentName(context, LiveSharingService::class.java), hidden.component)
        assertEquals("in order after the show", LiveSharingService.ACTION_HIDE, hidden.action)
        assertNull("“Durdur” reaches nothing once every share ended", LiveSharingService.stopSharing)
        assertNull(LiveSharingService.endDueShares)
        assertNull(LiveSharingService.noticeChanged)
        assertTrue("no alarm without a share", alarms.scheduledAlarms.isEmpty())

        // Erasing the phone without a share asks nothing of the service.
        val idle = ForegroundSharingNotice(LivePlatform(context, { null }))
        idle.update(endsAt)
        idle.hide()
        assertNull(application.nextStartedService)
    }

    /** The alarm at the end: the shares whose time is up end, with the CPU kept awake until they have. */
    @Test
    fun theAlarmEndsTheDueSharesAwake() {
        var dues = 0
        val ending = Job()
        LiveSharingService.endDueShares = {
            dues += 1
            ending
        }
        val (service, _) = started("en")
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(context, LiveSharingService::class.java).setAction(LiveSharingService.ACTION_EXPIRE), 0, 2))
        assertEquals(1, dues)
        val awake = ShadowPowerManager.getLatestWakeLock()
        assertTrue("awake until the shares ended and the key is zeroed", awake.isHeld)
        assertFalse(shadowOf(service).isStoppedBySelf)
        ending.complete()
        assertFalse(awake.isHeld)

        // A late alarm after the last share: nothing to end, the service goes.
        LiveSharingService.endDueShares = null
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(context, LiveSharingService::class.java).setAction(LiveSharingService.ACTION_EXPIRE), 0, 3))
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    /** A second share whose notice is refused (the app just left): the first one's “Durdur” and alarm still work. */
    @Test
    fun aRefusedNoticeForAnotherShareKeepsTheRunningOnesStop() {
        val front = Front(context)
        val notice = ForegroundSharingNotice(LivePlatform(front, { null }))
        val endsAt = Instant.now().plusSeconds(900)
        var stops = 0
        assertTrue(notice.show(endsAt, { stops += 1 }, { Job() }, {}))
        front.refuses = true
        assertFalse(notice.show(endsAt.plusSeconds(600), { stops += 1 }, { Job() }, {}))
        LiveSharingService.stopSharing!!.invoke()
        assertEquals(1, stops)
        assertNotNull(LiveSharingService.endDueShares)
        assertEquals(endsAt.toEpochMilli(), alarms.scheduledAlarms.single().triggerAtMs)

        // Refused for the first share: nothing is left behind.
        notice.hide()
        val refused = ForegroundSharingNotice(LivePlatform(front, { null }))
        assertFalse(refused.show(endsAt, { stops += 1 }, { Job() }, {}))
        assertNull(LiveSharingService.stopSharing)
        assertNull(LiveSharingService.endDueShares)
        assertNull(LiveSharingService.noticeChanged)
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun theNoticeCannotBeSeenWithNotificationsOffOrItsChannelOff() {
        val notice = ForegroundSharingNotice(LivePlatform(context, { null }))
        val notifications = context.getSystemService(NotificationManager::class.java)
        assertTrue(notice.canBeSeen())
        shadowOf(notifications).setNotificationsEnabled(false)
        assertFalse(notice.canBeSeen())
        shadowOf(notifications).setNotificationsEnabled(true)
        // The user turned the channel off.
        notifications.createNotificationChannel(NotificationChannel(LiveSharingService.CHANNEL_ID, "x", NotificationManager.IMPORTANCE_NONE))
        assertFalse(notice.canBeSeen())
    }

    /** Notifications turned off in the system's settings while shares run: the shares are asked to check at once. */
    @Test
    fun turningNotificationsOffReachesTheShares() {
        val notice = ForegroundSharingNotice(LivePlatform(context, { null }))
        var changes = 0
        assertTrue(notice.show(Instant.now().plusSeconds(900), {}, { Job() }, { changes += 1 }))
        started("en")
        context.sendBroadcast(Intent(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED).setPackage(context.packageName))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("the app's notifications", 1, changes)
        context.sendBroadcast(Intent(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED).setPackage(context.packageName))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("the notice's channel", 2, changes)
    }

    /** No fix and no report from the system (a permission revoked from the shell): every minute's notice asks too. */
    @Test
    fun everyMinuteTheSharesCheckTheNoticeCanBeSeen() {
        var changes = 0
        LiveSharingService.noticeChanged = { changes += 1 }
        val (service, _) = started("en")
        assertEquals("not on the share's own start", 0, changes)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(1))
        assertEquals(1, changes)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(1))
        assertEquals(2, changes)
        service.onDestroy()
        context.sendBroadcast(Intent(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED).setPackage(context.packageName))
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(1))
        assertEquals("nothing once the service is gone", 2, changes)
    }

    @Test
    fun theLastShareEndingTakesTheServiceOutOfTheForeground() {
        val (service, _) = started("en")
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(LiveSharingService.hide(context), 0, 2))
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
}
