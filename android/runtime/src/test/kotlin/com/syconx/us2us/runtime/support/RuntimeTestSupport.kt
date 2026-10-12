package com.syconx.us2us.runtime.support

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A clock that only moves when told to. Sleepers wake when the time passes their deadline (or
 * when cancelled). It starts at the real time (in milliseconds, as the storage keeps it), since the
 * storage still reads the system clock in a few places (a stored bubble is read back before its
 * deadline).
 */
class ManualClock(start: Instant = Instant.ofEpochMilli(System.currentTimeMillis())) : RuntimeClock {
    private class Sleeper(val deadline: Instant, val continuation: CancellableContinuation<Unit>)

    private val lock = Any()
    private var current = start
    private val sleepers = mutableMapOf<Any, Sleeper>()

    override fun now(): Instant = synchronized(lock) { current }

    val sleeperCount: Int get() = synchronized(lock) { sleepers.size }

    override suspend fun sleep(duration: Duration) = suspendCancellableCoroutine { continuation ->
        val id = Any()
        val immediate = synchronized(lock) {
            val deadline = current.after(duration)
            if (deadline <= current) {
                true
            } else {
                sleepers[id] = Sleeper(deadline, continuation)
                false
            }
        }
        if (immediate) {
            continuation.resume(Unit)
        } else {
            continuation.invokeOnCancellation { synchronized(lock) { sleepers.remove(id) } }
        }
    }

    /**
     * Moves the wall clock forward without waking anyone: a phone that slept, whose timers count
     * only the time it was awake.
     */
    fun jump(by: Duration) {
        synchronized(lock) { current = current.after(by) }
    }

    /** Moves time forward and wakes every sleeper whose deadline passed. */
    fun advance(by: Duration) {
        val due = synchronized(lock) {
            current = current.after(by)
            val due = sleepers.filterValues { it.deadline <= current }
            due.keys.forEach { sleepers.remove(it) }
            due.values.toList()
        }
        for (sleeper in due) sleeper.continuation.resume(Unit)
    }
}

/** Thrown by [eventually] when its condition never held. */
class TestTimeout(message: String) : AssertionError(message)

/**
 * Polls [condition] until it holds (real time; the work runs on other threads). The default
 * timeout is generous: a whole-module run shares the machine with PBKDF2 and Robolectric forks.
 */
suspend fun eventually(timeout: Duration = 30.seconds, message: String = "condition", condition: suspend () -> Boolean) {
    val start = TimeSource.Monotonic.markNow()
    while (start.elapsedNow() < timeout) {
        if (condition()) return
        delay(10.milliseconds)
    }
    throw TestTimeout("timed out waiting for $message")
}

/** Whether [condition] ever holds within [timeout] (real time), for negative checks. */
suspend fun happens(timeout: Duration = 300.milliseconds, condition: suspend () -> Boolean): Boolean {
    val start = TimeSource.Monotonic.markNow()
    while (start.elapsedNow() < timeout) {
        if (condition()) return true
        delay(10.milliseconds)
    }
    return false
}

/** Lets other threads run for a moment. */
suspend fun settle(rounds: Int = 20) {
    repeat(rounds) { delay(5.milliseconds) }
}
