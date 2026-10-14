package com.syconx.us2us.live

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Live location on Android (PROTOCOL §8.3): a share keeps its location updates and its notice
// (the foreground service) only while it runs, and every way a share ends — the user's stop from the
// map or the notification, its time running out, blocking the contact, a tick that can no longer go
// out (the conversation wiped, the vault closed), erasing the phone — ends the updates and the notice
// with the last one.

private val FIX = LocationFix(latitude = 40.9846, longitude = 29.0249, accuracy = 8f, course = -1f, speed = -1f)

/** The runtime's live-location calls, recorded. */
private class RuntimeCalls : LiveLocationSharing.Runtime {
    val started = ArrayList<Pair<AccountId, Duration>>()
    val ticks = ArrayList<Pair<ByteString, LocationFix>>()
    val stopped = ArrayList<ByteString>()
    var failsStart = false
    var refusesTicksFor = emptySet<ByteString>()
    var stopGate: CompletableDeferred<Unit>? = null
    var startGate: CompletableDeferred<Unit>? = null

    override suspend fun start(peer: AccountId, fix: LocationFix, duration: Duration): ByteString {
        if (failsStart) throw IOException("not accepted")
        // Recorded by the runtime first; the answer comes back after (the engine's own dispatcher).
        started += peer to duration
        startGate?.await()
        return ByteString.copyFrom(ByteArray(16) { started.size.toByte() })
    }

    override suspend fun update(sessionId: ByteString, peer: AccountId, fix: LocationFix) {
        if (sessionId in refusesTicksFor) throw IllegalStateException("its conversation is gone")
        ticks += sessionId to fix
    }

    override suspend fun stop(sessionId: ByteString, peer: AccountId) {
        stopGate?.await()
        stopped += sessionId
    }
}

/** The location provider, driven by the test. */
private class Positions : LiveLocationSharing.Updates {
    var allowed = true
    var handler: ((LocationFix) -> Unit)? = null
    var unavailable: (() -> Unit)? = null
    var stops = 0

    val running: Boolean get() = handler != null

    override suspend fun currentLocation(): LocationFix? = if (allowed) FIX else null

    override suspend fun startLiveUpdates(handler: (LocationFix) -> Unit, unavailable: () -> Unit): Boolean {
        if (!allowed) return false
        this.handler = handler
        this.unavailable = unavailable
        return true
    }

    override fun stopLiveUpdates() {
        handler = null
        unavailable = null
        stops += 1
    }

    fun fix(fix: LocationFix = FIX) = handler?.invoke(fix)

    /** Location switched off in the system's settings. */
    fun switchOff() = unavailable?.invoke()
}

/** The foreground service's notice, recorded. */
private class Notice : SharingNotice {
    var refuses = false
    var visible = true
    var shownUntil: Instant? = null
    var stopAll: (() -> Unit)? = null
    var endDue: (() -> Job)? = null
    var changed: (() -> Unit)? = null
    val calls = ArrayList<String>()

    val isShown: Boolean get() = shownUntil != null

    override fun show(endsAt: Instant, stopAll: () -> Unit, endDue: () -> Job, changed: () -> Unit): Boolean {
        calls += "show"
        if (refuses) return false
        shownUntil = endsAt
        this.stopAll = stopAll
        this.endDue = endDue
        this.changed = changed
        return true
    }

    /** Notifications turned off (or on again) in the system's settings: the system reports it. */
    fun turn(visible: Boolean) {
        this.visible = visible
        changed?.invoke()
    }

    override fun canBeSeen(): Boolean = visible

    override fun update(endsAt: Instant) {
        calls += "update"
        shownUntil = endsAt
    }

    override fun hide() {
        calls += "hide"
        shownUntil = null
        stopAll = null
        endDue = null
        changed = null
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LiveLocationSharingTest {
    private val start: Instant = Instant.parse("2026-10-12T09:00:00Z")
    private val deniz = AccountId(UUID.randomUUID())
    private val ece = AccountId(UUID.randomUUID())
    private val runtime = RuntimeCalls()
    private val updates = Positions()
    private val notice = Notice()

    /** The wall clock beyond the coroutine scheduler's time: how long the phone slept meanwhile. */
    private var slept = 0L

    private fun TestScope.sharing() = LiveLocationSharing(runtime, updates, notice, backgroundScope) {
        start.plusMillis(testScheduler.currentTime + slept)
    }

    @Test
    fun aShareRunsWithItsNoticeAndEveryFixIsATick() = runTest {
        val sharing = sharing()
        sharing.start(deniz, 15.minutes)
        assertEquals(listOf(deniz to 15.minutes), runtime.started)
        assertEquals("nothing is asked on the way: the share sheet offers it", listOf("show"), notice.calls)
        assertEquals(start.plusSeconds(15 * 60), notice.shownUntil)
        assertTrue(updates.running)
        assertTrue(sharing.isSharing)

        updates.fix()
        runCurrent()
        updates.fix(FIX.copy(latitude = 40.99))
        runCurrent()
        assertEquals(listOf(40.9846, 40.99), runtime.ticks.map { it.second.latitude })
        assertEquals(2, sharing.ticks)
        assertTrue(runtime.stopped.isEmpty())
    }

    @Test
    fun theNotificationsStopEndsEveryShareAndTheNoticeGoes() = runTest {
        val sharing = sharing()
        sharing.start(deniz, 15.minutes)
        sharing.start(ece, 1.hours)
        assertEquals("the notice runs until the last share ends", start.plusSeconds(3600), notice.shownUntil)
        updates.fix()
        runCurrent()
        assertEquals("one fix, a tick for every share", 2, runtime.ticks.size)

        notice.stopAll!!.invoke()
        runCurrent()
        assertEquals(2, runtime.stopped.size)
        assertFalse(sharing.isSharing)
        assertFalse("no more location updates", updates.running)
        assertFalse(notice.isShown)
    }

    @Test
    fun aShareEndsWhenItsTimeIsUpEvenWithoutAFix() = runTest {
        val sharing = sharing()
        sharing.start(deniz, 15.minutes)
        advanceTimeBy(15.minutes - 1.seconds)
        runCurrent()
        assertTrue(sharing.isSharing)
        assertTrue(notice.isShown)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(1, runtime.stopped.size)
        assertFalse(sharing.isSharing)
        assertFalse(updates.running)
        assertFalse(notice.isShown)
    }

    /** A sleeping phone: the wall clock passes the end, the timers (awake time only) do not; the notice's alarm ends it. */
    @Test
    fun theAlarmAtTheEndEndsTheSharesWhoseTimeIsUpOnASleepingPhone() = runTest {
        val sharing = sharing()
        sharing.start(deniz, 15.minutes)
        sharing.start(ece, 1.hours)
        val alarm = notice.endDue!!
        slept = 20.minutes.inWholeMilliseconds
        alarm().join()
        assertEquals("only the share whose time is up", listOf(ByteString.copyFrom(ByteArray(16) { 1 })), runtime.stopped)
        assertEquals(start.plusSeconds(3600), notice.shownUntil)
        assertTrue(updates.running)

        slept = 2.hours.inWholeMilliseconds
        alarm().join()
        assertEquals(2, runtime.stopped.size)
        assertFalse(sharing.isSharing)
        assertFalse(updates.running)
        assertFalse(notice.isShown)
    }

    @Test
    fun switchingLocationOffEndsEveryShare() = runTest {
        val sharing = sharing()
        sharing.start(deniz, 15.minutes)
        sharing.start(ece, 1.hours)
        updates.switchOff()
        runCurrent()
        assertEquals("the contacts see a stop, not a frozen position", 2, runtime.stopped.size)
        assertFalse(updates.running)
        assertFalse(notice.isShown)
    }

    @Test
    fun aShareWhoseNoticeCannotBeSeenStopsWhenTheAppLeaves() = runTest {
        val sharing = sharing()
        sharing.start(deniz, 15.minutes)
        sharing.appLeft()
        runCurrent()
        assertTrue("seen: it goes on in the background", sharing.isSharing)

        notice.visible = false
        sharing.appLeft()
        runCurrent()
        assertEquals(1, runtime.stopped.size)
        assertFalse(sharing.isSharing)
        assertFalse(notice.isShown)
    }

    /** Owner decision 0b: in front a share runs whether or not its notice can be seen; in the background only while it can. */
    @Test
    fun withoutItsNoticeAShareRunsInFrontOnly() = runTest {
        notice.visible = false
        val sharing = sharing()
        sharing.start(deniz, 15.minutes)
        updates.fix()
        runCurrent()
        notice.turn(visible = false)
        runCurrent()
        assertTrue("in front it goes on", sharing.isSharing)
        assertEquals(1, runtime.ticks.size)

        sharing.appLeft()
        runCurrent()
        assertEquals(1, runtime.stopped.size)
        assertFalse(sharing.isSharing)
        assertFalse(updates.running)
        assertFalse(notice.isShown)
        updates.fix()
        runCurrent()
        assertEquals("nothing is sent afterwards", 1, runtime.ticks.size)
    }

    @Test
    fun turningNotificationsOffDuringABackgroundShareStopsIt() = runTest {
        val sharing = sharing()
        sharing.start(deniz, 15.minutes)
        sharing.start(ece, 1.hours)
        sharing.appLeft()
        updates.fix()
        runCurrent()
        assertEquals("seen: both go on in the background", 2, runtime.ticks.size)

        notice.turn(visible = false)
        runCurrent()
        assertEquals(2, runtime.stopped.size)
        assertFalse(sharing.isSharing)
        assertFalse(updates.running)
        assertFalse(notice.isShown)
    }

    /** No report from the system (a permission revoked from the shell, a missed broadcast): the next fix stops them instead of a tick. */
    @Test
    fun aFixInTheBackgroundWithTheNoticeUnseenStopsInsteadOfATick() = runTest {
        val sharing = sharing()
        sharing.start(deniz, 15.minutes)
        sharing.appLeft()
        notice.visible = false
        updates.fix()
        runCurrent()
        assertTrue("no tick goes out", runtime.ticks.isEmpty())
        assertEquals(1, runtime.stopped.size)
        assertFalse(notice.isShown)
    }

    @Test
    fun backInFrontAShareGoesOnWithTheNoticeOff() = runTest {
        val sharing = sharing()
        sharing.start(deniz, 15.minutes)
        sharing.appLeft()
        sharing.appReturned()
        notice.turn(visible = false)
        updates.fix()
        runCurrent()
        assertTrue(sharing.isSharing)
        assertEquals(1, runtime.ticks.size)
        assertTrue(runtime.stopped.isEmpty())

        notice.turn(visible = true)
        sharing.appLeft()
        updates.fix()
        runCurrent()
        assertTrue("allowed again before leaving: it goes on", sharing.isSharing)
        assertEquals(2, runtime.ticks.size)
    }

    /** The screen goes away (back, or the lock on leaving) while the runtime records the start: the share is still driven and ended. */
    @Test
    fun aStartWhoseScreenGoesAwayIsStillTrackedAndEnds() = runTest {
        val sharing = sharing()
        val gate = CompletableDeferred<Unit>()
        runtime.startGate = gate
        val screen = launch { sharing.start(deniz, 15.minutes) }
        runCurrent()
        assertEquals(1, runtime.started.size)
        screen.cancel()
        gate.complete(Unit)
        runCurrent()
        assertTrue(screen.isCancelled)
        assertTrue("the share runs", sharing.isSharing)
        assertTrue("with its updates", updates.running)
        assertTrue("and its notice", notice.isShown)
        updates.fix()
        runCurrent()
        assertEquals(1, runtime.ticks.size)

        advanceTimeBy(15.minutes + 1.seconds)
        runCurrent()
        assertEquals("and it ends on time", 1, runtime.stopped.size)
        assertFalse(notice.isShown)
    }

    @Test
    fun aFixAfterTheEndStopsTheShareInsteadOfATick() = runTest {
        val sharing = sharing()
        sharing.start(deniz, 15.minutes)
        // The time is up while the expiry has not run yet: the fix stops the share (as on iOS).
        testScheduler.advanceTimeBy(15.minutes.inWholeMilliseconds)
        updates.fix()
        runCurrent()
        assertTrue(runtime.ticks.isEmpty())
        assertEquals(1, runtime.stopped.size)
        assertFalse(notice.isShown)
    }

    @Test
    fun blockingSomeoneStopsOnlyTheirShare() = runTest {
        val sharing = sharing()
        sharing.start(deniz, 1.hours)
        sharing.start(ece, 15.minutes)
        sharing.stopSharingWith(deniz)
        assertEquals(listOf(ByteString.copyFrom(ByteArray(16) { 1 })), runtime.stopped)
        assertEquals("the notice follows the share left", start.plusSeconds(15 * 60), notice.shownUntil)
        assertTrue(updates.running)

        updates.fix()
        runCurrent()
        assertEquals("nothing keeps tracking for someone blocked", listOf(ByteString.copyFrom(ByteArray(16) { 2 })), runtime.ticks.map { it.first })
        sharing.stopSharingWith(ece)
        assertFalse(notice.isShown)
        assertFalse(updates.running)
    }

    @Test
    fun aTickThatCannotGoOutEndsTheShare() = runTest {
        val sharing = sharing()
        sharing.start(deniz, 1.hours)
        // Its conversation was wiped (Ghost, “Sil”), the contact blocked or the vault closed.
        runtime.refusesTicksFor = setOf(ByteString.copyFrom(ByteArray(16) { 1 }))
        updates.fix()
        runCurrent()
        assertFalse(sharing.isSharing)
        assertEquals("its stop is still asked for", 1, runtime.stopped.size)
        assertFalse(updates.running)
        assertFalse(notice.isShown)
    }

    @Test
    fun withoutItsNoticeAShareStopsAtOnce() = runTest {
        notice.refuses = true
        val sharing = sharing()
        sharing.start(deniz, 15.minutes)
        assertEquals(1, runtime.started.size)
        assertEquals("the contact's bubble does not stay live with a frozen position", 1, runtime.stopped.size)
        assertFalse(sharing.isSharing)
        assertFalse(updates.running)
        assertEquals(listOf("show", "hide"), notice.calls)
    }

    @Test
    fun withoutLocationNothingStarts() = runTest {
        updates.allowed = false
        val sharing = sharing()
        sharing.start(deniz, 15.minutes)
        assertTrue(runtime.started.isEmpty())
        assertTrue(notice.calls.isEmpty())
        assertFalse(sharing.isSharing)
    }

    @Test
    fun aShareTheRuntimeRefusesShowsNoNotice() = runTest {
        runtime.failsStart = true
        val sharing = sharing()
        sharing.start(deniz, 15.minutes)
        assertTrue(notice.calls.isEmpty())
        assertFalse(updates.running)
        assertFalse(sharing.isSharing)
    }

    @Test
    fun theMapStopsItsShareAndLeavesOthersAlone() = runTest {
        val sharing = sharing()
        sharing.start(deniz, 15.minutes)
        sharing.start(ece, 15.minutes)
        val first = ByteString.copyFrom(ByteArray(16) { 1 })
        assertTrue(sharing.isRunning(first))
        assertTrue(sharing.stop(first))
        assertFalse(sharing.isRunning(first))
        assertFalse("not run by this phone", sharing.stop(first))
        assertTrue(notice.isShown)
        assertEquals(listOf("show", "show", "update"), notice.calls)
    }

    @Test
    fun erasingThePhoneForgetsTheSharesWithoutTheRuntime() = runTest {
        val sharing = sharing()
        sharing.start(deniz, 15.minutes)
        sharing.forget()
        assertFalse(sharing.isSharing)
        assertFalse(updates.running)
        assertFalse(notice.isShown)
        advanceUntilIdle()
        assertTrue("nothing is asked of an erased vault, not even at the share's end", runtime.stopped.isEmpty())
    }

    @Test
    fun aStopAskedByAScreenThatGoesAwayStillEndsTheShare() = runTest {
        val sharing = sharing()
        sharing.start(deniz, 15.minutes)
        val gate = CompletableDeferred<Unit>()
        runtime.stopGate = gate
        val screen = launch { sharing.stop(ByteString.copyFrom(ByteArray(16) { 1 })) }
        runCurrent()
        screen.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, runtime.stopped.size)
        assertFalse(updates.running)
        assertFalse(notice.isShown)
        assertNull(notice.stopAll)
    }
}
