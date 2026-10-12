package com.syconx.us2us.runtime.support

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaDuration

/**
 * Wall-clock time and sleeping, injectable so timers (grace period, ghost deadlines, presence
 * refresh, backoff) can be tested without waiting.
 */
interface RuntimeClock {
    fun now(): Instant

    /** Sleeps for [duration]; throws `CancellationException` when the coroutine is cancelled. */
    suspend fun sleep(duration: Duration)
}

/** The real clock. */
object SystemRuntimeClock : RuntimeClock {
    override fun now(): Instant = Instant.now()

    override suspend fun sleep(duration: Duration) {
        if (!duration.isPositive()) {
            currentCoroutineContext().ensureActive()
            return
        }
        delay(duration.coerceAtMost(365.days))
    }
}

/** [this] moved forward by [duration]. */
internal fun Instant.after(duration: Duration): Instant = plus(duration.toJavaDuration())

/**
 * A signal that wakes a waiting loop early ("new work in the outbox", "the network is back, stop
 * backing off"). Waiting returns when signalled or when the timeout passes. A signal while nobody
 * waits is remembered, so the next wait returns at once: work queued while the loop was busy is
 * never missed.
 */
class WakeSignal {
    private val lock = Any()
    private var pending = false
    private val waiters = mutableListOf<CompletableDeferred<Unit>>()

    /** Wakes everybody waiting, or the next wait if nobody is. */
    fun signal() {
        val woken = synchronized(lock) {
            if (waiters.isEmpty()) {
                pending = true
                return
            }
            waiters.toList().also { waiters.clear() }
        }
        for (waiter in woken) waiter.complete(Unit)
    }

    /**
     * Waits until [signal] or until [clock] has slept [duration], whichever comes first. Throws
     * `CancellationException` if the coroutine is cancelled.
     */
    suspend fun wait(duration: Duration, clock: RuntimeClock) {
        val waiter = synchronized(lock) {
            if (pending) {
                pending = false
                return
            }
            CompletableDeferred<Unit>().also { waiters += it }
        }
        try {
            coroutineScope {
                val sleeper = launch {
                    clock.sleep(duration)
                    waiter.complete(Unit)
                }
                waiter.await()
                sleeper.cancel()
            }
        } finally {
            synchronized(lock) { waiters.remove(waiter) }
        }
    }
}
