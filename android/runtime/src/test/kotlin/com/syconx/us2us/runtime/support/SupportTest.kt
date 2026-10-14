package com.syconx.us2us.runtime.support

import androidx.lifecycle.Lifecycle
import com.syconx.us2us.runtime.vault.AppDirectories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SupportTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aSignalWhileNobodyWaitsIsRemembered() = runBlocking {
        val signal = WakeSignal()
        val clock = ManualClock()
        signal.signal()
        withTimeout(5.seconds) { signal.wait(60.seconds, clock) } // returns at once
        // Consumed: the next wait sleeps until the clock moves.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val waiting = scope.async { signal.wait(60.seconds, clock) }
        eventually { clock.sleeperCount == 1 }
        assertFalse(waiting.isCompleted)
        clock.advance(60.seconds)
        withTimeout(5.seconds) { waiting.await() }
        scope.cancel()
    }

    @Test
    fun aSignalWakesEveryWaiter() = runBlocking {
        val signal = WakeSignal()
        val clock = ManualClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = scope.async { signal.wait(60.seconds, clock) }
        val second = scope.async { signal.wait(60.seconds, clock) }
        eventually { clock.sleeperCount == 2 }
        signal.signal()
        withTimeout(5.seconds) {
            first.await()
            second.await()
        }
        // The sleepers were cancelled with their waits.
        eventually { clock.sleeperCount == 0 }
        scope.cancel()
    }

    @Test
    fun theSystemClockSleeps() = runBlocking {
        val before = SystemRuntimeClock.now()
        SystemRuntimeClock.sleep(kotlin.time.Duration.ZERO)
        SystemRuntimeClock.sleep((-1).seconds)
        assertFalse(SystemRuntimeClock.now().isBefore(before))
    }

    @Test
    fun phasesFollowTheLifecycle() {
        assertEquals(AppPhase.ACTIVE, AppPhase.of(Lifecycle.State.RESUMED))
        assertEquals(AppPhase.INACTIVE, AppPhase.of(Lifecycle.State.STARTED))
        assertEquals(AppPhase.BACKGROUND, AppPhase.of(Lifecycle.State.CREATED))
        assertEquals(AppPhase.BACKGROUND, AppPhase.of(Lifecycle.State.DESTROYED))
    }

    @Test
    fun backgroundTasks() {
        val tasks = NoBackgroundTasks()
        var expired = 0
        val token = tasks.begin("us2us.grace") { expired += 1 }
        assertEquals(1, token)
        tasks.expireAll()
        assertEquals(1, expired)
        tasks.end(token!!)
        assertEquals(0, tasks.ended)
        tasks.refusesNewTasks = true
        assertNull(tasks.begin("us2us.grace") {})

        val process = ProcessBackgroundTasks()
        val granted = process.begin("us2us.grace") { error("never expires") }
        assertEquals(1, process.runningCount)
        process.end(granted)
        assertEquals(0, process.runningCount)
    }

    /** The grace period and the teardown run on time with the screen off: a partial wake lock while a task runs. */
    @Test
    fun aBackgroundTaskKeepsTheCpuAwakeUntilItEnds() {
        val tasks = ProcessBackgroundTasks(org.robolectric.RuntimeEnvironment.getApplication(), 60.seconds)
        val token = tasks.begin("us2us.grace") {}
        val awake = ShadowPowerManager.getLatestWakeLock()
        assertTrue(awake.isHeld)
        tasks.end(token)
        assertFalse(awake.isHeld)
        assertEquals(0, tasks.runningCount)
    }

    @Test
    fun captureEventsByHand() {
        val events = ManualCaptureEvents()
        var screenshots = 0
        val changes = mutableListOf<Boolean>()
        events.start(screenshot = { screenshots += 1 }, captureChanged = { changes += it })
        events.takeScreenshot()
        events.setCaptured(true)
        assertTrue(events.isCaptured)
        events.stop()
        events.takeScreenshot()
        events.setCaptured(false)
        assertEquals(1, screenshots)
        assertEquals(listOf(true), changes)
    }

    /** Without the manifest permissions the platform hooks stay silent instead of throwing. */
    @Test
    fun systemCaptureEventsWithoutPermission() {
        val application = org.robolectric.RuntimeEnvironment.getApplication()
        val events = SystemCaptureEvents(application)
        events.start(screenshot = {}, captureChanged = {})
        val controller = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        assertFalse(events.isCaptured)
        controller.pause().stop().destroy()
        events.stop()
    }

    @Test
    fun filesAreWrittenAtomicallyAndStagingIsEmptied() {
        val directories = AppDirectories(folder.newFolder("us2us"))
        directories.prepare()
        val file = directories.stagingFile("bin")
        ProtectedFiles.write(byteArrayOf(1, 2, 3), file)
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        assertEquals(3L, ProtectedFiles.size(file))
        assertTrue(directories.staging.listFiles()!!.none { it.name.endsWith(".pending") })
        directories.purgeStaging()
        assertFalse(file.exists())
        assertEquals(0L, ProtectedFiles.size(file))
    }

    @Test
    fun logLabelsNameTheKindOnly() {
        assertEquals("IOException", IOException("secret path /data/x").logLabel)
        assertEquals("cancelled", kotlinx.coroutines.CancellationException("x").logLabel)
        val labelled = object : Exception("x"), LogLabel {
            override val logLabel = "network"
        }
        assertEquals("network", (labelled as Throwable).logLabel)
        RuntimeLog.relay.info("connected")
        RuntimeLog.relay.debug("detail", "network")
    }
}
