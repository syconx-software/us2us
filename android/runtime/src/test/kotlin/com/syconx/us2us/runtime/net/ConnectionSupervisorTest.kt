package com.syconx.us2us.runtime.net

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.messenger.ConnectionRejectedException
import com.syconx.us2us.core.messenger.IncomingMessage
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.relay.RelayErrorCode
import com.syconx.us2us.core.relay.RelayException
import com.syconx.us2us.core.relay.RelaySocket
import com.syconx.us2us.runtime.support.ManualClock
import com.syconx.us2us.runtime.support.eventually
import com.syconx.us2us.runtime.support.settle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Duration as JavaDuration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

/** A relay stream driven by the test: messages, `queue_empty` and failures are pushed in. */
class FakeStream : RelayStream {
    sealed interface Event {
        class Message(val message: IncomingMessage) : Event

        data object QueueEmpty : Event

        class Fail(val error: Exception) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    @Volatile var isRunning = false
        private set

    @Volatile var isClosed = false
        private set

    fun push(event: Event) {
        events.trySend(event)
    }

    override suspend fun run(deliver: suspend (IncomingMessage) -> Unit, onQueueEmpty: suspend () -> Unit) {
        isRunning = true
        try {
            for (event in events) {
                when (event) {
                    is Event.Message -> deliver(event.message)
                    Event.QueueEmpty -> onQueueEmpty()
                    is Event.Fail -> throw event.error
                }
            }
        } finally {
            isRunning = false
        }
    }

    override fun close() {
        isClosed = true
        events.close()
    }
}

/** Opens [FakeStream]s, or fails the next opens as told. */
class FakeOpener : RelayStreamOpener {
    val streams = CopyOnWriteArrayList<FakeStream>()
    private val failures = ArrayDeque<Exception>()

    val latestStream: FakeStream? get() = streams.lastOrNull()
    val streamCount: Int get() = streams.size

    @Synchronized
    fun failNextOpen(error: Exception) {
        failures.addLast(error)
    }

    override suspend fun openStream(): RelayStream {
        synchronized(this) { failures.removeFirstOrNull() }?.let { throw it }
        return FakeStream().also { streams += it }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConnectionSupervisorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val delivered = CopyOnWriteArrayList<String>()
    private val queueEmpty = AtomicInteger()

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun make(opener: FakeOpener, clock: ManualClock) = ConnectionSupervisor(
        opener = opener,
        events = ConnectionEvents(deliver = { delivered += it.envelopeId }, queueEmpty = { queueEmpty.incrementAndGet() }),
        scope = scope,
        clock = clock,
        backoff = Backoff(base = 1.seconds, cap = 8.seconds, floor = 1.seconds, random = { it.endInclusive }),
    )

    private fun inbound(id: String) = IncomingMessage(
        envelopeId = id,
        serverTimestamp = 0,
        sender = AccountId(UUID.randomUUID()),
        senderUsername = null,
        content = Content.getDefaultInstance(),
        contactState = ContactState.ACCEPTED,
        identityChanged = false,
    )

    private fun retryingIn(supervisor: ConnectionSupervisor, clock: ManualClock): Double? {
        val status = supervisor.status.value as? ConnectionStatus.Retrying ?: return null
        return JavaDuration.between(clock.now(), status.at).toMillis() / 1000.0
    }

    @Test
    fun backlogThenLive() = runBlocking {
        val opener = FakeOpener()
        val clock = ManualClock()
        val supervisor = make(opener, clock)
        supervisor.start()
        eventually { opener.latestStream?.isRunning == true }
        assertEquals(ConnectionStatus.Syncing, supervisor.status.value)
        opener.latestStream?.push(FakeStream.Event.Message(inbound("a")))
        opener.latestStream?.push(FakeStream.Event.QueueEmpty)
        eventually { supervisor.status.value == ConnectionStatus.Open }
        assertEquals(listOf("a"), delivered.toList())
        assertEquals(1, queueEmpty.get())
        supervisor.stop()
        assertTrue(opener.latestStream?.isClosed == true)
        assertEquals(ConnectionStatus.Offline, supervisor.status.value)
    }

    @Test
    fun reconnectsWithBackoffAndResetsAfterAStableConnection() = runBlocking {
        val opener = FakeOpener()
        val clock = ManualClock()
        val supervisor = make(opener, clock)
        opener.failNextOpen(IOException("no route"))
        opener.failNextOpen(IOException("no route"))
        supervisor.start()
        eventually { retryingIn(supervisor, clock)?.let { abs(it - 1) < 0.001 } == true }
        assertEquals(0, opener.streamCount)
        // First failure: 1 s, second: 2 s, then the third attempt connects.
        eventually {
            if (retryingIn(supervisor, clock)?.let { abs(it - 2) < 0.001 } == true) return@eventually true
            if (clock.sleeperCount > 0) clock.advance(1.seconds)
            false
        }
        eventually {
            if (clock.sleeperCount > 0) clock.advance(1.seconds)
            opener.latestStream?.isRunning == true
        }
        opener.latestStream?.push(FakeStream.Event.QueueEmpty)
        eventually { supervisor.status.value == ConnectionStatus.Open }
        // A dropped connection after a good one (it stayed up a while, not only until its
        // backlog) starts over at the shortest delay.
        clock.advance(ConnectionSupervisor.STABLE_CONNECTION)
        opener.latestStream?.push(FakeStream.Event.Fail(RelayStreamException(closeCode = 1011)))
        eventually { retryingIn(supervisor, clock)?.let { it <= 1.01 } == true }
        eventually {
            if (clock.sleeperCount > 0) clock.advance(1.seconds)
            opener.streamCount == 2
        }
        supervisor.stop()
    }

    @Test
    fun aConnectionThatDropsRightAfterItsBacklogKeepsBackingOff() = runBlocking {
        val opener = FakeOpener()
        val clock = ManualClock()
        val supervisor = make(opener, clock)
        supervisor.start()
        eventually { opener.latestStream?.isRunning == true }
        opener.latestStream?.push(FakeStream.Event.QueueEmpty)
        eventually { supervisor.status.value == ConnectionStatus.Open }
        opener.latestStream?.push(FakeStream.Event.Fail(RelayStreamException(closeCode = 1011)))
        eventually { retryingIn(supervisor, clock)?.let { abs(it - 1) < 0.001 } == true }
        eventually { clock.sleeperCount > 0 } // the backoff sleeps from here
        clock.advance(1.seconds)
        eventually { opener.streamCount == 2 && opener.latestStream?.isRunning == true }
        opener.latestStream?.push(FakeStream.Event.QueueEmpty)
        eventually { supervisor.status.value == ConnectionStatus.Open }
        opener.latestStream?.push(FakeStream.Event.Fail(RelayStreamException(closeCode = 1011)))
        eventually { retryingIn(supervisor, clock)?.let { abs(it - 2) < 0.001 } == true }
        supervisor.stop()
    }

    @Test
    fun revokedTokenStopsForGood() = runBlocking {
        val opener = FakeOpener()
        val clock = ManualClock()
        val supervisor = make(opener, clock)
        supervisor.start()
        eventually { opener.latestStream?.isRunning == true }
        opener.latestStream?.push(FakeStream.Event.Fail(RelayException(401, RelayErrorCode.UNAUTHORIZED, "unauthorized")))
        eventually { supervisor.status.value == ConnectionStatus.Revoked }
        clock.advance(120.seconds)
        settle()
        assertEquals(1, opener.streamCount)
        supervisor.start()
        settle()
        assertEquals(1, opener.streamCount)
        assertEquals(ConnectionStatus.Revoked, supervisor.status.value)
    }

    @Test
    fun closeCode4401AndTheCoreRejectionAreRevocationsToo() {
        assertTrue(ConnectionSupervisor.isRevocation(RelayStreamException(closeCode = RelaySocket.CLOSE_UNAUTHORIZED)))
        assertTrue(ConnectionSupervisor.isRevocation(ConnectionRejectedException(RelaySocket.CLOSE_UNAUTHORIZED, null)))
        assertTrue(ConnectionSupervisor.isRevocation(ConnectionRejectedException(null, 401)))
        assertTrue(ConnectionSupervisor.isRevocation(IOException("wrapped", RelayException(401, RelayErrorCode.UNAUTHORIZED, "x"))))
        assertTrue(!ConnectionSupervisor.isRevocation(ConnectionRejectedException(RelaySocket.CLOSE_REPLACED, null)))
        assertTrue(!ConnectionSupervisor.isRevocation(RelayException(500, RelayErrorCode.INTERNAL, "x")))
    }

    @Test
    fun waitsForTheNetworkAndReconnectsAtOnce() = runBlocking {
        val opener = FakeOpener()
        val clock = ManualClock()
        val supervisor = make(opener, clock)
        supervisor.networkChanged(available = false)
        supervisor.start()
        eventually { supervisor.status.value == ConnectionStatus.WaitingForNetwork }
        assertEquals(0, opener.streamCount)
        supervisor.networkChanged(available = true)
        eventually { opener.latestStream?.isRunning == true }
        supervisor.stop()
    }

    @Test
    fun startCutsAPendingBackoffShort() = runBlocking {
        val opener = FakeOpener()
        val clock = ManualClock()
        val supervisor = make(opener, clock)
        opener.failNextOpen(IOException("no route"))
        supervisor.start()
        eventually { supervisor.status.value is ConnectionStatus.Retrying }
        supervisor.start()
        eventually { opener.latestStream?.isRunning == true }
        supervisor.stop()
    }

    @Test
    fun statusUpdatesArriveInOrder() = runBlocking {
        val opener = FakeOpener()
        val clock = ManualClock()
        val supervisor = make(opener, clock)
        val collected = CopyOnWriteArrayList<ConnectionStatus>()
        val listener = scope.launch {
            supervisor.statusUpdates.collect { status ->
                collected += status
                if (status == ConnectionStatus.Offline) cancel()
            }
        }
        supervisor.start()
        eventually { opener.latestStream?.isRunning == true }
        opener.latestStream?.push(FakeStream.Event.QueueEmpty)
        eventually { supervisor.status.value == ConnectionStatus.Open }
        supervisor.stop()
        listener.join()
        assertEquals(
            listOf(ConnectionStatus.Connecting, ConnectionStatus.Syncing, ConnectionStatus.Open, ConnectionStatus.Offline),
            collected.toList(),
        )
    }

    @Test
    fun aFailedDeliveryLeavesTheMessageAndReconnects() = runBlocking {
        val opener = FakeOpener()
        val clock = ManualClock()
        var failures = 1
        val supervisor = ConnectionSupervisor(
            opener = opener,
            events = ConnectionEvents(
                deliver = {
                    if (failures-- > 0) throw IOException("store failed")
                    delivered += it.envelopeId
                },
                queueEmpty = {},
            ),
            scope = scope,
            clock = clock,
            backoff = Backoff(base = 1.seconds, cap = 8.seconds, floor = 1.seconds, random = { it.endInclusive }),
        )
        supervisor.start()
        eventually { opener.latestStream?.isRunning == true }
        opener.latestStream?.push(FakeStream.Event.Message(inbound("x")))
        eventually { supervisor.status.value is ConnectionStatus.Retrying }
        eventually { clock.sleeperCount > 0 }
        clock.advance(1.seconds)
        eventually { opener.streamCount == 2 && opener.latestStream?.isRunning == true }
        opener.latestStream?.push(FakeStream.Event.Message(inbound("x")))
        eventually { delivered.toList() == listOf("x") }
        supervisor.stop()
    }
}
