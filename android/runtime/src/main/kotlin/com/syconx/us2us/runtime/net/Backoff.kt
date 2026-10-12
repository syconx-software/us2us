package com.syconx.us2us.runtime.net

import com.syconx.us2us.core.messenger.ConnectionRejectedException
import com.syconx.us2us.core.relay.RelayException
import com.syconx.us2us.core.relay.RelaySocket
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Reconnect and retry delays: exponential with "full jitter" (a uniformly random delay up to the
 * exponential bound), so phones that lost the relay at the same moment (relay restart, close code
 * `1001`) do not come back in lock-step, and a relay outage costs it little.
 */
class Backoff(
    val base: Duration = 1.seconds,
    val cap: Duration = 60.seconds,
    /** Never retry faster than this. */
    val floor: Duration = 500.milliseconds,
    /** A value in the range, in seconds. */
    private val random: (ClosedFloatingPointRange<Double>) -> Double = ::uniform,
) {
    /** The exponential bound after [failures] consecutive failures (1, 2, …). */
    fun bound(failures: Int): Duration {
        if (failures <= 0) return Duration.ZERO
        val exponent = min(failures - 1, 30)
        return minOf(cap, base * 2.0.pow(exponent))
    }

    /** The delay before the next attempt. */
    fun delay(failures: Int): Duration {
        if (failures <= 0) return Duration.ZERO
        val bound = bound(failures)
        return maxOf(floor, random(0.0..bound.toDouble(kotlin.time.DurationUnit.SECONDS)).seconds)
    }

    /**
     * The delay before retrying after [error]: the relay's `Retry-After` wins for `429`; a replaced
     * connection (another session of this account, close code `4001`) waits at least 30 s so two
     * installations cannot keep replacing each other in a tight loop.
     */
    fun delay(failures: Int, after: Throwable?): Duration {
        val delay = delay(failures)
        return when (val failure = RelayFailure.of(after)) {
            is RelayFailure.RateLimited -> failure.retryAfter?.let { maxOf(delay, minOf(it, 1.hours)) } ?: delay
            RelayFailure.Replaced -> maxOf(delay, REPLACED_MINIMUM)
            else -> delay
        }
    }

    private companion object {
        val REPLACED_MINIMUM = 30.seconds

        fun uniform(range: ClosedFloatingPointRange<Double>): Double =
            if (range.endInclusive <= range.start) range.start else Random.nextDouble(range.start, range.endInclusive)
    }
}

/**
 * Why a relay connection or request failed, as far as reconnecting is concerned. Read from the
 * core's errors ([RelayException], [ConnectionRejectedException]) and from [RelayStreamException].
 */
sealed interface RelayFailure {
    /** `401` on the upgrade or close code `4401`: the token was rotated (§5.5) or the account deleted. */
    data object Revoked : RelayFailure

    /** Close code `4001`: another connection of this account took over. */
    data object Replaced : RelayFailure

    /** `429`, with the relay's `Retry-After` if it sent one. */
    data class RateLimited(val retryAfter: Duration?) : RelayFailure

    /** Anything else: the network, a timeout, a relay restart. */
    data object Transient : RelayFailure

    companion object {
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_TOO_MANY_REQUESTS = 429

        fun of(error: Throwable?): RelayFailure {
            var current = error
            var depth = 0
            while (current != null && depth < 4) {
                classify(current)?.let { return it }
                current = current.cause
                depth += 1
            }
            return Transient
        }

        private fun classify(error: Throwable): RelayFailure? = when (error) {
            is RelayStreamException -> from(error.closeCode, error.httpStatus, error.retryAfter)
            is ConnectionRejectedException -> from(error.code, error.httpStatus, null)
            is RelayException -> from(null, error.httpStatus, error.retryAfterSeconds?.seconds)
            else -> null
        }

        private fun from(closeCode: Int?, httpStatus: Int?, retryAfter: Duration?): RelayFailure? = when {
            closeCode == RelaySocket.CLOSE_UNAUTHORIZED || httpStatus == HTTP_UNAUTHORIZED -> Revoked
            closeCode == RelaySocket.CLOSE_REPLACED -> Replaced
            httpStatus == HTTP_TOO_MANY_REQUESTS -> RateLimited(retryAfter)
            else -> null
        }
    }
}

/**
 * How a [RelayStream] reports the way its socket ended: the close code or the refused upgrade's
 * HTTP status, and the relay's `Retry-After`.
 */
class RelayStreamException(
    val closeCode: Int? = null,
    val httpStatus: Int? = null,
    val retryAfter: Duration? = null,
    cause: Throwable? = null,
) : java.io.IOException("relay connection ended (close code $closeCode, HTTP $httpStatus)", cause)
