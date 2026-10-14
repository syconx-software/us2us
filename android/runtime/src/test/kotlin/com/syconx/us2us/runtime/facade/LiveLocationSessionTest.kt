package com.syconx.us2us.runtime.facade

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.runtime.engine.EngineException
import com.syconx.us2us.runtime.engine.FakeStream
import com.syconx.us2us.runtime.engine.GhostSettings
import com.syconx.us2us.runtime.engine.MessageBody
import com.syconx.us2us.runtime.engine.attachmentContent
import com.syconx.us2us.runtime.storage.AttachmentState
import com.syconx.us2us.runtime.storage.RandomIds
import com.syconx.us2us.runtime.net.ConnectionStatus
import com.syconx.us2us.runtime.support.AppPhase
import com.syconx.us2us.runtime.support.eventually
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Live location in the background (PROTOCOL §8.3, §5.4 step 4): after the grace period the session
 * keeps only what the running shares need. The UI stays locked, the socket closes and nothing is
 * received; the store and the DEK stay open for the location ticks only while a share runs, and
 * the key is zeroed the moment the last one ends, runs out, is wiped with its conversation or can
 * no longer reach its contact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveLocationSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: RuntimeFixture

    private fun newFixture(): RuntimeFixture = RuntimeFixture(temporary.newFolder()).also { fixture = it }

    @After
    fun tearDown() = runBlocking {
        if (::fixture.isInitialized) {
            try {
                fixture.runtime.lockNow()
            } catch (_: Exception) {
            }
            fixture.main.close()
        }
    }

    private suspend inline fun <reified E : Throwable> assertThrows(block: suspend () -> Unit): E {
        try {
            block()
        } catch (e: Throwable) {
            if (e is E) return e
            throw AssertionError("expected ${E::class.java.simpleName}, got $e", e)
        }
        throw AssertionError("expected ${E::class.java.simpleName}")
    }

    private suspend fun RuntimeFixture.share(peer: AccountId, duration: Duration = 15.minutes): ByteString =
        runtime.startLiveLocation(peer, 41.0, 29.0, 5f, duration)

    private suspend fun RuntimeFixture.tick(session: ByteString, peer: AccountId) =
        runtime.updateLiveLocation(session, peer, 41.001, 29.001, 5f)

    /** Leaves the app (locked at once) and lets the grace period run out: the socket closes. */
    private suspend fun RuntimeFixture.leaveUntilTheGraceIsOver(stream: FakeStream) {
        phase(AppPhase.INACTIVE)
        phase(AppPhase.BACKGROUND)
        assertEquals(LockState.LOCKED, runtime.lockState.value)
        eventually(message = "grace over") {
            clock.advance(1.seconds)
            stream.isClosed
        }
    }

    private suspend fun RuntimeFixture.ready(): AccountId {
        onboard()
        connect()
        return acceptedContact()
    }

    @Test
    fun withoutAShareTheGraceEndsTheSession() = runBlocking {
        val fixture = newFixture()
        fixture.ready()
        val session = fixture.runtime.session!!
        fixture.leaveUntilTheGraceIsOver(fixture.backend.latestStream!!)
        eventually(message = "key zeroed") {
            session.key.isWiped && fixture.runtime.session == null && fixture.runtime.connection.value == ConnectionStatus.Offline
        }
        assertFalse(session.liveLocationOnly)
        assertEquals(LockState.LOCKED, fixture.runtime.lockState.value)
    }

    @Test
    fun withAShareOnlyItsTicksGoOnAfterTheGrace() = runBlocking {
        val fixture = newFixture()
        val peer = fixture.ready()
        val sharing = fixture.share(peer)
        eventually(message = "start sent") { fixture.backend.sent(peer).any { it.data?.hasLiveLocation() == true && !it.ephemeral } }
        val session = fixture.runtime.session!!
        fixture.leaveUntilTheGraceIsOver(fixture.backend.latestStream!!)

        assertTrue("live location only", session.liveLocationOnly)
        assertSame("the store and the key stay for the share", session, fixture.runtime.session)
        assertFalse(session.key.isWiped)
        assertEquals(LockState.LOCKED, fixture.runtime.lockState.value)
        assertTrue("nothing decrypted is observable", fixture.runtime.chats.value.isEmpty())

        // Nothing is received: the socket stays closed, also on the lock screen.
        fixture.phase(AppPhase.ACTIVE)
        delay(200.milliseconds)
        assertEquals(1, fixture.backend.streamCount)
        fixture.phase(AppPhase.BACKGROUND)

        // Only the share's ticks are encrypted and sent, ephemeral.
        fixture.tick(sharing, peer)
        eventually(message = "tick sent") { fixture.backend.sent(peer).any { it.ephemeral && it.data?.liveLocation?.sequence == 1 } }
        assertEquals(1, fixture.backend.streamCount)
        assertSame(session, fixture.runtime.session)
    }

    /** iOS `stoppingLiveLocationInTheBackgroundZeroesTheKey`, with two shares. */
    @Test
    fun theLastShareEndingInTheBackgroundZeroesTheKey() = runBlocking {
        val fixture = newFixture()
        val deniz = fixture.ready()
        val ece = fixture.acceptedContact()
        val toDeniz = fixture.share(deniz)
        val toEce = fixture.share(ece, 1.hours)
        val session = fixture.runtime.session!!
        fixture.leaveUntilTheGraceIsOver(fixture.backend.latestStream!!)
        assertTrue(session.liveLocationOnly)

        // Stopped from the notification, still locked: the other share keeps the store open.
        fixture.runtime.stopLiveLocation(toDeniz, deniz)
        assertSame(session, fixture.runtime.session)
        assertFalse(session.key.isWiped)
        fixture.tick(toEce, ece)

        // The last one: it returns only once the key is zeroed.
        fixture.runtime.stopLiveLocation(toEce, ece)
        assertTrue("the DEK outlives live-location sharing in the background", session.key.isWiped)
        assertNull(fixture.runtime.session)
        assertThrows<RuntimeException> { fixture.tick(toEce, ece) }
        assertEquals(LockState.LOCKED, fixture.runtime.lockState.value)
    }

    @Test
    fun aShareRunningOutZeroesTheKeyByItself() = runBlocking {
        val fixture = newFixture()
        val peer = fixture.ready()
        fixture.share(peer, 2.minutes)
        val session = fixture.runtime.session!!
        fixture.leaveUntilTheGraceIsOver(fixture.backend.latestStream!!)
        assertTrue(session.liveLocationOnly)
        eventually(message = "share over, key zeroed") {
            fixture.clock.advance(5.seconds)
            session.key.isWiped
        }
        assertNull(fixture.runtime.session)
    }

    @Test
    fun aGhostWipeInTheBackgroundEndsTheShareAndZeroesTheKey() = runBlocking<Unit> {
        val fixture = newFixture()
        val peer = fixture.ready()
        fixture.runtime.setConversationSettings(peer, GhostSettings(enabled = true, timeoutSeconds = 60), 0)
        val sharing = fixture.share(peer, 1.hours)
        val session = fixture.runtime.session!!
        fixture.leaveUntilTheGraceIsOver(fixture.backend.latestStream!!)
        assertTrue(session.liveLocationOnly)
        // A minute of silence wipes the conversation, and its share goes with it, long before the hour is up.
        eventually(message = "wiped, key zeroed") {
            fixture.clock.advance(1.seconds)
            session.key.isWiped
        }
        assertNull(fixture.runtime.session)
        assertThrows<RuntimeException> { fixture.tick(sharing, peer) }
    }

    /** The wake for an overdue Ghost wipe (an alarm) runs on the open store while only the ticks run: the engine hears of it. */
    @Test
    fun aGhostWipeByTheWipeAlarmEndsTheShareAndZeroesTheKey() = runBlocking<Unit> {
        val fixture = newFixture()
        val peer = fixture.ready()
        fixture.runtime.setConversationSettings(peer, GhostSettings(enabled = true, timeoutSeconds = 60), 0)
        val sharing = fixture.share(peer, 1.hours)
        val session = fixture.runtime.session!!
        fixture.leaveUntilTheGraceIsOver(fixture.backend.latestStream!!)
        assertTrue(session.liveLocationOnly)
        // The phone slept past the Ghost deadline: the alarm wakes it, the engine's own timer has not run.
        val wiped = AppRuntime.runBackgroundWipes(fixture.environment.directories, fixture.clock.now().plusSeconds(120))
        assertEquals(1, wiped)
        eventually(message = "share ended, key zeroed") { session.key.isWiped }
        assertNull(fixture.runtime.session)
        assertThrows<RuntimeException> { fixture.tick(sharing, peer) }
    }

    /** A tick after the grace period's end on the wall clock ends it, although its timer slept with the phone. */
    @Test
    fun aTickPastTheGraceOnTheWallClockEndsIt() = runBlocking {
        val fixture = newFixture()
        val peer = fixture.ready()
        val sharing = fixture.share(peer)
        val session = fixture.runtime.session!!
        val stream = fixture.backend.latestStream!!
        fixture.phase(AppPhase.INACTIVE)
        fixture.phase(AppPhase.BACKGROUND)
        fixture.clock.jump(31.seconds)
        delay(200.milliseconds)
        assertFalse("the timer has not run", stream.isClosed)

        fixture.tick(sharing, peer)
        eventually(message = "grace over") { stream.isClosed && session.liveLocationOnly }
        assertEquals(LockState.LOCKED, fixture.runtime.lockState.value)
        assertSame(session, fixture.runtime.session)
    }

    /** Nothing is fetched or decrypted while only the ticks run, a download already running included; the unlock resumes them. */
    @Test
    fun nothingIsDownloadedWhileOnlyTheTicksRun() = runBlocking {
        val fixture = newFixture()
        val peer = fixture.ready()
        fixture.share(peer)
        val session = fixture.runtime.session!!
        val gate = CompletableDeferred<Unit>()
        fixture.backend.downloadGate = gate
        fixture.push(attachmentContent(RandomIds.make()), peer)
        fixture.push(attachmentContent(RandomIds.make()), peer)
        eventually(message = "a download runs") { fixture.backend.downloadsStarted == 1 }

        fixture.leaveUntilTheGraceIsOver(fixture.backend.latestStream!!)
        assertTrue(session.liveLocationOnly)
        gate.complete(Unit)
        delay(300.milliseconds)
        assertEquals("the running download stopped, no other began", 1, fixture.backend.downloadsStarted)
        assertEquals(2, session.storage.read { it.attachments.inStates(listOf(AttachmentState.PENDING_DOWNLOAD), limit = 10) }.size)

        fixture.phase(AppPhase.ACTIVE)
        fixture.runtime.unlock(RuntimeFixture.CODE)
        assertSame(session, fixture.runtime.session)
        eventually(message = "both downloaded") {
            session.storage.read { it.attachments.inStates(listOf(AttachmentState.PENDING_DOWNLOAD, AttachmentState.DOWNLOADING), limit = 10) }.isEmpty()
        }
        assertEquals(3, fixture.backend.downloadsStarted)
    }

    @Test
    fun aShareWhoseBubbleIsGoneEndsEvenThoughItsStopCannotBeStored() = runBlocking {
        val fixture = newFixture()
        val peer = fixture.ready()
        val sharing = fixture.share(peer)
        val session = fixture.runtime.session!!
        val bubble = session.engine.messages(peer).first { it.body is MessageBody.LiveLocation }
        fixture.runtime.deleteForMe(bubble.messageId, peer)
        assertThrows<EngineException> { fixture.tick(sharing, peer) }
        fixture.leaveUntilTheGraceIsOver(fixture.backend.latestStream!!)
        assertTrue(session.liveLocationOnly)

        assertThrows<EngineException> { fixture.runtime.stopLiveLocation(sharing, peer) }
        assertTrue("a stop that could not be stored still ends the share", session.key.isWiped)
        assertNull(fixture.runtime.session)
    }

    @Test
    fun blockingEndsTheShareSoTheStoreDoesNotStayOpenForIt() = runBlocking {
        val fixture = newFixture()
        val peer = fixture.ready()
        val sharing = fixture.share(peer)
        fixture.runtime.block(peer)
        assertTrue(fixture.runtime.session!!.engine.activeLiveLocationSessions().isEmpty())
        assertThrows<EngineException> { fixture.tick(sharing, peer) }

        val session = fixture.runtime.session!!
        fixture.leaveUntilTheGraceIsOver(fixture.backend.latestStream!!)
        eventually(message = "closed after the grace") { session.key.isWiped && fixture.runtime.session == null }
        assertFalse(session.liveLocationOnly)
    }

    @Test
    fun unlockingWhileOnlyTheTicksRunResumesTheSameSession() = runBlocking {
        val fixture = newFixture()
        val peer = fixture.ready()
        val sharing = fixture.share(peer)
        val session = fixture.runtime.session!!
        fixture.leaveUntilTheGraceIsOver(fixture.backend.latestStream!!)
        assertTrue(session.liveLocationOnly)

        fixture.phase(AppPhase.ACTIVE)
        fixture.runtime.unlock(RuntimeFixture.CODE)
        assertEquals(LockState.UNLOCKED, fixture.runtime.lockState.value)
        assertSame("the same store and key", session, fixture.runtime.session)
        assertFalse(session.liveLocationOnly)
        assertFalse(session.key.isWiped)
        // Receiving starts again on a new socket; the share runs on.
        fixture.connect()
        assertEquals(2, fixture.backend.streamCount)
        assertEquals(listOf(sharing), session.engine.activeLiveLocationSessions())
    }

    /** A process that ended while sharing (killed in the background) never resumes it; the next unlock stops it (§8.3). */
    @Test
    fun aLostShareIsNotResumedAndIsStoppedAtTheNextUnlock() = runBlocking {
        val fixture = newFixture()
        val peer = fixture.ready()
        fixture.share(peer)
        eventually(message = "start sent") { fixture.backend.sent(peer).any { it.data?.hasLiveLocation() == true } }
        fixture.runtime.lockNow()
        fixture.relaunch()
        assertNull(fixture.runtime.session)
        assertEquals(LockState.LOCKED, fixture.runtime.lockState.value)

        fixture.runtime.unlock(RuntimeFixture.CODE)
        eventually(message = "stop sent") { fixture.backend.sent(peer).any { it.data?.liveLocation?.stopped == true && !it.ephemeral } }
        assertTrue(fixture.runtime.session!!.engine.activeLiveLocationSessions().isEmpty())
    }
}
