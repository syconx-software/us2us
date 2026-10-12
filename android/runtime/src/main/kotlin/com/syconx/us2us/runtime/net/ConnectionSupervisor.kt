package com.syconx.us2us.runtime.net

import com.syconx.us2us.core.messenger.IncomingMessage
import com.syconx.us2us.runtime.support.RuntimeClock
import com.syconx.us2us.runtime.support.RuntimeLog
import com.syconx.us2us.runtime.support.SystemRuntimeClock
import com.syconx.us2us.runtime.support.WakeSignal
import com.syconx.us2us.runtime.support.after
import com.syconx.us2us.runtime.support.logLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/**
 * One open WebSocket (PROTOCOL.md §4.7): the backlog, `queue_empty`, then live messages.
 */
interface RelayStream {
    /**
     * Runs until the socket closes. Each message is handed to `deliver` and acknowledged after
     * `deliver` returns; re-sent frames are de-duplicated by relay id. Returns normally after
     * [close]; throws the close error otherwise (a [RelayStreamException] with the close code or
     * HTTP status, or the core's `ConnectionRejectedException` / `RelayException`).
     */
    suspend fun run(deliver: suspend (IncomingMessage) -> Unit, onQueueEmpty: suspend () -> Unit)

    fun close()
}

/** Opens a [RelayStream] (the messaging backend's `openStream`). */
fun interface RelayStreamOpener {
    suspend fun openStream(): RelayStream
}

/** Callbacks of a [ConnectionSupervisor]. */
class ConnectionEvents(
    /**
     * Persist the message; it is acknowledged when this returns. Throwing leaves it on the relay
     * and reconnects (it arrives again).
     */
    val deliver: suspend (IncomingMessage) -> Unit,
    /** The backlog has been received (`queue_empty`). */
    val queueEmpty: suspend () -> Unit,
)

/**
 * Keeps the one WebSocket of §4.7 / §5.4 open while it is allowed (the app is active and
 * unlocked, or inside the 30-second background grace period) and closed otherwise. Reconnects
 * with jittered exponential backoff ([Backoff]), waits for the network, and gives up for good when
 * the relay revokes the token. Keep-alive pings (≤ 30 s) and de-duplication by relay id happen
 * inside the stream (the core's `RelaySocket` / `Messenger`).
 *
 * The loop runs in [scope]; every method may be called from any thread.
 */
class ConnectionSupervisor(
    private val opener: RelayStreamOpener,
    private val events: ConnectionEvents,
    private val scope: CoroutineScope,
    private val clock: RuntimeClock = SystemRuntimeClock,
    private val backoff: Backoff = Backoff(),
) {
    private val lock = Any()
    private val wake = WakeSignal()

    private var allowed = false
    private var networkAvailable = true
    private var loop: Job? = null
    private var current: RelayStream? = null
    private var consecutiveFailures = 0

    /** When the current connection delivered its backlog (`queue_empty`). */
    private var liveSince: Instant? = null

    private val statusState = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Offline)

    /** The current status. Conflated: use [statusUpdates] to see every change. */
    val status: StateFlow<ConnectionStatus> = statusState.asStateFlow()

    private val updates = Channel<ConnectionStatus>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every status change, in order (for one collector; the newest 32 are kept until collected). */
    val statusUpdates: Flow<ConnectionStatus> = updates.receiveAsFlow()

    /** Connects (or keeps connecting) until [stop]. */
    fun start() {
        val job = synchronized(lock) {
            if (statusState.value == ConnectionStatus.Revoked) return
            allowed = true
            if (loop != null) {
                null
            } else {
                scope.launch(start = CoroutineStart.LAZY) { runLoop() }.also { loop = it }
            }
        }
        if (job == null) wake.signal() else job.start() // a running loop: cut a pending backoff short
    }

    /** Closes the socket and stops reconnecting; returns once the loop has ended. */
    suspend fun stop() {
        val running = synchronized(lock) {
            allowed = false
            current?.close()
            loop
        }
        wake.signal()
        running?.join()
    }

    /** From the network watcher: reconnect at once when a network appears. */
    fun networkChanged(available: Boolean) {
        val wakeUp = synchronized(lock) {
            val wasAvailable = networkAvailable
            networkAvailable = available
            if (!available && allowed && !statusState.value.isConnected) setStatusLocked(ConnectionStatus.WaitingForNetwork)
            available && !wasAvailable
        }
        if (wakeUp) wake.signal()
    }

    private suspend fun runLoop() {
        val self = currentCoroutineContext()[Job]
        try {
            while (isAllowed() && currentCoroutineContext().isActive) {
                if (!synchronized(lock) { networkAvailable }) {
                    setStatus(ConnectionStatus.WaitingForNetwork)
                    wake.wait(NETWORK_RECHECK, clock)
                    continue
                }
                setStatus(ConnectionStatus.Connecting)
                synchronized(lock) { liveSince = null }
                var failure: Throwable? = null
                try {
                    val stream = opener.openStream()
                    val kept = synchronized(lock) { allowed.also { if (it) current = stream } }
                    if (!kept) {
                        stream.close()
                        break
                    }
                    setStatus(ConnectionStatus.Syncing)
                    val events = events
                    stream.run(
                        deliver = events.deliver,
                        onQueueEmpty = {
                            reachedQueueEmpty()
                            events.queueEmpty()
                        },
                    )
                    clearCurrent()
                    if (!isAllowed()) break
                } catch (e: CancellationException) {
                    clearCurrent()
                    throw e
                } catch (e: Exception) {
                    clearCurrent()
                    if (!isAllowed()) break
                    if (isRevocation(e)) {
                        RuntimeLog.relay.notice("token revoked")
                        synchronized(lock) {
                            allowed = false
                            setStatusLocked(ConnectionStatus.Revoked)
                        }
                        return
                    }
                    failure = e
                }
                val delay = synchronized(lock) {
                    val since = liveSince
                    if (since != null && java.time.Duration.between(since, clock.now()).toKotlinDuration() >= STABLE_CONNECTION) {
                        consecutiveFailures = 0
                    }
                    liveSince = null
                    consecutiveFailures += 1
                    backoff.delay(consecutiveFailures, failure)
                }
                RuntimeLog.relay.info("connection lost", failure?.logLabel ?: "closed")
                setStatus(ConnectionStatus.Retrying(clock.now().after(delay)))
                wake.wait(delay, clock)
            }
        } finally {
            val restart = synchronized(lock) {
                if (loop === self) loop = null
                when {
                    statusState.value == ConnectionStatus.Revoked -> null
                    // start() came in while this loop was leaving: it found the loop still set and
                    // only signalled it, so carry on in a new one.
                    allowed && loop == null && scope.isActive -> scope.launch(start = CoroutineStart.LAZY) { runLoop() }.also { loop = it }
                    else -> {
                        setStatusLocked(ConnectionStatus.Offline)
                        null
                    }
                }
            }
            restart?.start()
        }
    }

    private fun isAllowed(): Boolean = synchronized(lock) { allowed }

    private fun clearCurrent() {
        synchronized(lock) { current = null }
    }

    private fun reachedQueueEmpty() {
        synchronized(lock) {
            if (liveSince == null) liveSince = clock.now()
            setStatusLocked(ConnectionStatus.Open)
        }
    }

    private fun setStatus(status: ConnectionStatus) {
        synchronized(lock) { setStatusLocked(status) }
    }

    private fun setStatusLocked(status: ConnectionStatus) {
        if (status == statusState.value) return
        statusState.value = status
        updates.trySend(status)
    }

    companion object {
        /**
         * A connection counts as healthy (the backoff starts over) only after it stayed up this
         * long: one that drops right after its backlog (a relay crash loop, a proxy timeout, a
         * flapping network) keeps backing off instead of reconnecting every second (§1.1).
         */
        val STABLE_CONNECTION: Duration = 60.seconds

        /** How long to wait for a network before looking again without being told. */
        internal val NETWORK_RECHECK: Duration = 60.seconds

        /** `401` on the upgrade or close code `4401`: the token was rotated (a restore on another phone, §5.5) or the account deleted. */
        fun isRevocation(error: Throwable): Boolean = RelayFailure.of(error) == RelayFailure.Revoked
    }
}
