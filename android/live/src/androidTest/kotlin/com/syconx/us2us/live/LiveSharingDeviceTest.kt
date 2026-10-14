package com.syconx.us2us.live

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.domain.ConversationAttachment
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.domain.MessengerScenePhase
import com.syconx.us2us.domain.UnlockAttemptResult
import com.syconx.us2us.domain.UserLookup
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.runtime.facade.LockState
import com.syconx.us2us.runtime.net.ConnectionStatus
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live location in the background on a phone (PROTOCOL §8.3): the foreground service of type
 * location started with a share, its notification (generic words, hidden on the lock screen,
 * silent), the share going on after the app left, and the notification's “Durdur” ending it.
 *
 * The first test needs nothing but the phone. The others run the live service against the local
 * relay of `scripts/e2e-relay.sh` with the echo bot, as `scripts/e2e-live.sh --class
 * com.syconx.us2us.live.LiveSharingDeviceTest` runs it (instrumentation arguments `relayUrl`,
 * `botUsername`, `location=1`); without them they are skipped.
 *
 * A share goes on in the background only while its notification can be seen.
 * With `notifications=0` (`e2e-live.sh --no-notifications`, the permission refused) only the test of
 * that runs: the share stops when the app leaves and nothing is sent afterwards. `backgroundMinutes`
 * keeps the 15-minute share ticking in the background that long (default 1).
 */
@RunWith(AndroidJUnit4::class)
class LiveSharingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val context = instrumentation.targetContext
    private val notifications: NotificationManager = context.getSystemService(NotificationManager::class.java)

    /** The run with notifications refused (revoked before the run: revoking a permission here could end this process). */
    private val noticeRefused = arguments.getString("notifications") == "0"

    @Before
    fun allowLocationAndTheNotice() {
        val automation = instrumentation.uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !noticeRefused) {
            automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** The runtime's calls, recorded (no relay). */
    private class Calls : LiveLocationSharing.Runtime {
        @Volatile var ticks = 0
        @Volatile var stops = 0
        private val session = ByteString.copyFrom(ByteArray(16) { 7 })

        override suspend fun start(peer: AccountId, fix: LocationFix, duration: Duration): ByteString = session

        override suspend fun update(sessionId: ByteString, peer: AccountId, fix: LocationFix) {
            ticks += 1
        }

        override suspend fun stop(sessionId: ByteString, peer: AccountId) {
            stops += 1
        }
    }

    /** Fixes handed in by the test. */
    private class Positions : LiveLocationSharing.Updates {
        var handler: ((LocationFix) -> Unit)? = null

        override suspend fun currentLocation(): LocationFix = FIX

        override suspend fun startLiveUpdates(handler: (LocationFix) -> Unit, unavailable: () -> Unit): Boolean {
            this.handler = handler
            return true
        }

        override fun stopLiveUpdates() {
            handler = null
        }
    }

    @Test
    fun theServiceKeepsAShareGoingInTheBackgroundAndItsStopEndsIt(): Unit = runBlocking {
        assumeFalse("needs the notice", noticeRefused)
        val screen = ActivityScenario.launch(ComponentActivity::class.java)
        var activity: Activity? = null
        screen.onActivity { activity = it }
        val calls = Calls()
        val positions = Positions()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val sharing = onMain { LiveLocationSharing(calls, positions, ForegroundSharingNotice(LivePlatform(context, { activity })), scope) }
        try {
            step("share")
            onMain { sharing.start(AccountId(UUID.randomUUID()), 15.minutes) }
            val posted = awaitNotification("the notice is posted")
            val notification = posted.notification
            val words = activity!!
            assertEquals(words.getString(R.string.live_sharing_title), notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals(words.getString(R.string.live_sharing_remaining, 15), notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals(words.getString(R.string.live_sharing_stop), notification.actions.single().title.toString())
            assertTrue("a foreground service's notice", notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
            assertEquals("hidden on the lock screen", Notification.VISIBILITY_SECRET, notification.visibility)
            val channel = notifications.getNotificationChannel(LiveSharingService.CHANNEL_ID)
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
            // Android keeps a channel's lock-screen setting for the user (an app's choice is not
            // stored): the notification's own secret visibility applies, unless the user overrides it.
            assertTrue(channel.lockscreenVisibility in setOf(Notification.VISIBILITY_SECRET, NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE))
            assertTrue("the service runs in the foreground", serviceRunsInForeground())

            step("background")
            // The app leaves the screen; the cached-apps freezer would stop a process without the service.
            screen.moveToState(Lifecycle.State.CREATED)
            delay(15.seconds)
            assertTrue(serviceRunsInForeground())
            onMain { positions.handler!!.invoke(FIX) }
            awaitTrue("the tick goes out in the background") { calls.ticks == 1 }

            step("stop from the notification")
            notification.actions.single().actionIntent.send()
            awaitTrue("every share stopped") { calls.stops == 1 && !sharing.isSharing }
            awaitTrue("the notice is gone") { current() == null }
            awaitTrue("the service stopped") { !serviceRunsInForeground() }
        } finally {
            onMain { sharing.forget() }
            scope.cancel()
            screen.close()
        }
    }

    @Test
    fun aLiveShareKeepsTickingWhileLockedAndStopsFromTheNotification(): Unit = withTheBot(noticeRefused = false) { service, screen, bot, chat ->
        step("share")
        onMain { service.sendAttachment(ConversationAttachment.LiveLocation(15.minutes), chat) }
        awaitTrue("the share is on the map") { service.liveLocations(chat).value.activeShare(MessageDirection.Outgoing, Instant.now()) != null }
        val share = service.liveLocations(chat).value.activeShare(MessageDirection.Outgoing, Instant.now())!!
        val notification = awaitNotification("the notice is posted").notification
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString() + notification.extras.getCharSequence(Notification.EXTRA_TITLE)
        assertFalse("the notice never names the contact", text.contains(bot, ignoreCase = true))
        assertTrue(service.liveSharingContinuesInBackground())

        val graces = logged(LIVE_LOCATION_ONLY)
        val keys = logged(KEY_ZEROED)
        leave(service, screen, chat)
        // The grace period (30 s) ends: the socket closes, only the share's ticks go on.
        delay(35.seconds)
        assertNotNull("the notice stays while the share runs", current())
        assertTrue(service.sharing.isSharing)
        assertEquals("grace over, live location only", graces + 1, logged(LIVE_LOCATION_ONLY))
        assertEquals("the key stays while the share runs", keys, logged(KEY_ZEROED))
        val minutes = arguments.getString("backgroundMinutes")?.toIntOrNull() ?: 1
        for (minute in 1..minutes) {
            val before = service.sharing.ticks
            awaitTrue("ticks go out after the grace, locked and in the background (minute $minute)", 90.seconds) { service.sharing.ticks > before }
            assertEquals("the socket stays closed: nothing is received in the background", ConnectionStatus.Offline, service.runtime.connection.value)
            step("background minute $minute: ${service.sharing.ticks} ticks")
            if (minute < minutes) delay(60.seconds - 200.milliseconds)
        }
        assertTrue(serviceRunsInForeground())

        step("stop from the notification")
        notification.actions.single().actionIntent.send()
        awaitTrue("the share stopped") { !service.sharing.isSharing }
        awaitTrue("the notice is gone") { current() == null }
        awaitTrue("the service stopped") { !serviceRunsInForeground() }
        awaitTrue("the data key is zeroed with the last share") { logged(KEY_ZEROED) == keys + 1 }
        val stoppedAt = service.sharing.ticks
        delay(45.seconds)
        assertEquals("nothing is sent after the stop", stoppedAt, service.sharing.ticks)

        back(service, screen)
        onMain { service.conversationAppeared(chat) }
        awaitTrue("the bubble says it stopped") { service.liveLocations(chat).value.share(share.id)?.isStopped == true }
        onMain { service.conversationDisappeared(chat) }
    }

    /** The shortest share there is (the runtime makes every share at least a minute) runs out in the background by itself. */
    @Test
    fun aShortShareRunsOutInTheBackgroundAndTheServiceGoes(): Unit = withTheBot(noticeRefused = false) { service, screen, _, chat ->
        step("share for a minute")
        onMain { service.sendAttachment(ConversationAttachment.LiveLocation(1.minutes), chat) }
        awaitTrue("the share is on the map") { service.liveLocations(chat).value.activeShare(MessageDirection.Outgoing, Instant.now()) != null }
        val share = service.liveLocations(chat).value.activeShare(MessageDirection.Outgoing, Instant.now())!!
        awaitNotification("the notice is posted")

        val graces = logged(LIVE_LOCATION_ONLY)
        val keys = logged(KEY_ZEROED)
        leave(service, screen, chat)
        delay(35.seconds)
        assertTrue("still sharing after the grace", service.sharing.isSharing)
        assertEquals("grace over, live location only", graces + 1, logged(LIVE_LOCATION_ONLY))
        awaitTrue("the share runs out by itself", 60.seconds) { !service.sharing.isSharing }
        step("expired: ${service.sharing.ticks} ticks")
        awaitTrue("the notice is gone") { current() == null }
        awaitTrue("the service stopped") { !serviceRunsInForeground() }
        awaitTrue("the data key is zeroed with the last share") { logged(KEY_ZEROED) == keys + 1 }
        val endedAt = service.sharing.ticks
        delay(45.seconds)
        assertEquals("nothing is sent after the end", endedAt, service.sharing.ticks)

        back(service, screen)
        onMain { service.conversationAppeared(chat) }
        awaitTrue("the bubble says it ended") { service.liveLocations(chat).value.activeShare(MessageDirection.Outgoing, Instant.now())?.id != share.id }
        onMain { service.conversationDisappeared(chat) }
    }

    /** With notifications refused a share runs while the app is in front and stops when it leaves. */
    @Test
    fun withNotificationsRefusedTheShareStopsWhenTheAppLeaves(): Unit = withTheBot(noticeRefused = true) { service, screen, _, chat ->
        assertFalse(notifications.areNotificationsEnabled())
        assertFalse("the share sheet says so first", service.liveSharingContinuesInBackground())

        step("share in front")
        onMain { service.sendAttachment(ConversationAttachment.LiveLocation(15.minutes), chat) }
        awaitTrue("the share is on the map") { service.liveLocations(chat).value.activeShare(MessageDirection.Outgoing, Instant.now()) != null }
        val share = service.liveLocations(chat).value.activeShare(MessageDirection.Outgoing, Instant.now())!!
        val before = service.sharing.ticks
        awaitTrue("in front it ticks", 90.seconds) { service.sharing.ticks > before }
        assertTrue(service.sharing.isSharing)
        assertNull("no notification can be seen", current())

        val graces = logged(LIVE_LOCATION_ONLY)
        val locks = logged(GRACE_LOCKED)
        leave(service, screen, chat)
        awaitTrue("the share stops as the app leaves", 5.seconds) { !service.sharing.isSharing }
        val stoppedAt = service.sharing.ticks
        awaitTrue("the service stopped") { !serviceRunsInForeground() }
        delay(35.seconds)
        assertEquals("grace over, locked: the key is gone", locks + 1, logged(GRACE_LOCKED))
        assertEquals("no live-location-only session", graces, logged(LIVE_LOCATION_ONLY))
        delay(30.seconds)
        assertEquals("nothing is sent afterwards", stoppedAt, service.sharing.ticks)

        back(service, screen)
        onMain { service.conversationAppeared(chat) }
        awaitTrue("the bubble says it stopped") { service.liveLocations(chat).value.share(share.id)?.isStopped == true }
        onMain { service.conversationDisappeared(chat) }
    }

    /**
     * A fresh account on the local relay whose message request the echo bot accepted, the chat open
     * on a screen in front; the account is deleted on the relay and the phone erased afterwards.
     * Runs only in the run whose notifications are refused or not, as [noticeRefused] says.
     */
    private fun withTheBot(
        noticeRefused: Boolean,
        test: suspend (RealMessengerService, ActivityScenario<ComponentActivity>, String, ChatId) -> Unit,
    ): Unit = runBlocking {
        assumeTrue("the run with notifications ${if (noticeRefused) "refused (--no-notifications)" else "allowed"}", noticeRefused == this@LiveSharingDeviceTest.noticeRefused)
        val relay = arguments.getString("relayUrl")
        val bot = arguments.getString("botUsername")
        assumeTrue(
            "needs relayUrl, botUsername and location=1 (scripts/e2e-live.sh)",
            relay != null && bot != null && arguments.getString("location") == "1",
        )
        relay!!
        bot!!
        assertEquals("a loopback relay only", relay, RelayEndpoint.debugOverride(relay))
        val screen = ActivityScenario.launch(ComponentActivity::class.java)
        var activity: Activity? = null
        screen.onActivity { activity = it }
        val service = onMain {
            LiveMessenger.create(context, relay, activities = { activity }, hooks = ExternalActivityHooks.None).also {
                it.scenePhaseChanged(MessengerScenePhase.Active)
            }
        }
        try {
            if (service.hasAccount.value) onMain { service.eraseEverything() }
            step("register")
            onMain { service.createAccount("g2live_" + hex(4), CODE, faceIdEnabled = false) }
            val lookup = onMain { service.lookup(bot) }
            assertTrue("the directory finds the bot: $lookup", lookup is UserLookup.Found)
            val chat = onMain { service.startChat(bot) } ?: throw AssertionError("no chat with $bot")
            onMain { service.conversationAppeared(chat) }
            onMain { service.sendText("merhaba", null, chat) }
            awaitTrue("the request is accepted", 90.seconds) { service.chat(chat).value?.peer?.isAcceptedContact == true }
            test(service, screen, bot, chat)
        } finally {
            onMain {
                if (service.runtime.lockState.value == LockState.UNLOCKED) {
                    try {
                        service.deleteAccountOnRelay()
                    } catch (_: Exception) {
                    }
                }
                service.eraseEverything()
                service.close()
            }
            screen.close()
        }
    }

    /** The app leaves the screen: it locks at once, nothing decrypted stays. */
    private suspend fun leave(service: RealMessengerService, screen: ActivityScenario<ComponentActivity>, chat: ChatId) {
        step("background")
        onMain {
            service.conversationDisappeared(chat)
            service.scenePhaseChanged(MessengerScenePhase.Inactive)
            service.scenePhaseChanged(MessengerScenePhase.Background)
        }
        screen.moveToState(Lifecycle.State.CREATED)
        assertEquals(LockState.LOCKED, service.runtime.lockState.value)
        assertTrue("nothing decrypted while locked", service.chats.value.isEmpty())
    }

    private suspend fun back(service: RealMessengerService, screen: ActivityScenario<ComponentActivity>) {
        step("back")
        screen.moveToState(Lifecycle.State.RESUMED)
        onMain { service.scenePhaseChanged(MessengerScenePhase.Active) }
        assertEquals(UnlockAttemptResult.Unlocked, onMain { service.unlock(CODE) })
    }

    /** How often the runtime logged [event] in this run (`e2e-live.sh` clears the log first): when the grace ends and when the key goes. */
    private fun logged(event: String): Int {
        val output = instrumentation.uiAutomation.executeShellCommand("logcat -d -s us2us.lifecycle:I")
        val lines = ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readLines() }
        return lines.count { it.contains(event) }
    }

    private fun current(): StatusBarNotification? = notifications.activeNotifications.firstOrNull { it.id == LiveSharingService.NOTIFICATION_ID }

    private suspend fun awaitNotification(what: String): StatusBarNotification {
        awaitTrue(what) { current() != null }
        return current()!!
    }

    @Suppress("DEPRECATION")
    private fun serviceRunsInForeground(): Boolean =
        context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == LiveSharingService::class.java.name && it.foreground }

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main) { block() }

    private suspend fun awaitTrue(what: String, timeout: Duration = 30.seconds, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out: $what")
            delay(200)
        }
    }

    private fun step(name: String) = Log.i("us2us.e2e", "step: $name")

    private companion object {
        const val CODE = "482916"

        /** The runtime's lifecycle events (`AppRuntime`). */
        const val LIVE_LOCATION_ONLY = "grace over, live location continues"
        const val GRACE_LOCKED = "grace over, locked"
        const val KEY_ZEROED = "live location over, locked"
        val FIX = LocationFix(latitude = 40.9846, longitude = 29.0249, accuracy = 8f, course = -1f, speed = -1f)

        fun hex(bytes: Int): String = ByteArray(bytes).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
