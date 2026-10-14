package com.syconx.us2us.live

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.activity.ComponentActivity
import com.syconx.us2us.domain.NoticePermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Owner decision 0b: whether the live-location notice can be seen, and the share sheet's
 * “Bildirimlere izin ver”: the system's question while Android still asks it, the settings
 * otherwise. Never required to share.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationAccessTest {
    private val context: Application = org.robolectric.RuntimeEnvironment.getApplication()
    private val notifications: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private val activity: ComponentActivity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val access = NotificationAccess(LivePlatform(context, { activity }))

    private fun asked() = context.getSharedPreferences(LIVE_PREFERENCES, Context.MODE_PRIVATE).getBoolean("notifications.asked", false)

    @Test
    fun allowedNotificationsNeedNoQuestion() = runBlocking {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(access.canBeSeen())
        assertEquals(NoticePermission.Allowed, access.request())
        assertNull(shadowOf(activity).lastRequestedPermission)
    }

    @Test
    fun aChannelTurnedOffIsAllowedOnlyInTheSettings() = runBlocking {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.createNotificationChannel(NotificationChannel(LiveSharingService.CHANNEL_ID, "x", NotificationManager.IMPORTANCE_NONE))
        assertFalse(access.canBeSeen())
        assertEquals(NoticePermission.InSettings, access.request())
        assertNull("no question the system would not show", shadowOf(activity).lastRequestedPermission)
    }

    @Test
    fun theSystemsQuestionWhileItStillAsks() {
        shadowOf(notifications).setNotificationsEnabled(false)
        assertFalse(access.canBeSeen())
        var answer: NoticePermission? = null
        val asking = CoroutineScope(Dispatchers.Unconfined).launch { answer = access.request() }
        val question = shadowOf(activity).lastRequestedPermission
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), question.requestedPermissions.toList())
        assertTrue(asked())
        assertNull("waits for the answer", answer)
        asking.cancel()
    }

    @Test
    fun refusedForGoodOnlyTheSettingsCanAllowIt() = runBlocking {
        shadowOf(notifications).setNotificationsEnabled(false)
        context.getSharedPreferences(LIVE_PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean("notifications.asked", true).commit()
        shadowOf(context.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS, false)
        assertEquals(NoticePermission.InSettings, access.request())
        assertNull(shadowOf(activity).lastRequestedPermission)

        // Refused once: Android asks a second time.
        shadowOf(context.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS, true)
        val asking = CoroutineScope(Dispatchers.Unconfined).launch { access.request() }
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), shadowOf(activity).lastRequestedPermission.requestedPermissions.toList())
        asking.cancel()
    }

    @Test
    fun withoutAnActivityInFrontNothingIsAsked() = runBlocking {
        shadowOf(notifications).setNotificationsEnabled(false)
        val away = NotificationAccess(LivePlatform(context, { null }))
        assertEquals(NoticePermission.Refused, away.request())
        assertFalse(asked())
    }
}
