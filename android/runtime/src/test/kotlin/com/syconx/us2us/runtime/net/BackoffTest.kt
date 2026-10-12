package com.syconx.us2us.runtime.net

import com.syconx.us2us.core.messenger.ConnectionRejectedException
import com.syconx.us2us.core.relay.RelayErrorCode
import com.syconx.us2us.core.relay.RelayException
import com.syconx.us2us.core.relay.RelaySocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BackoffTest {
    @Test
    fun exponentialBoundsWithFullJitter() {
        val backoff = Backoff(base = 1.seconds, cap = 60.seconds, floor = 500.milliseconds, random = { it.endInclusive })
        assertEquals(0.seconds, backoff.delay(0))
        assertEquals(1.seconds, backoff.delay(1))
        assertEquals(2.seconds, backoff.delay(2))
        assertEquals(16.seconds, backoff.delay(5))
        assertEquals(60.seconds, backoff.delay(30))
        assertEquals(60.seconds, backoff.delay(Int.MAX_VALUE))
        val low = Backoff(base = 1.seconds, cap = 60.seconds, floor = 500.milliseconds, random = { it.start })
        assertEquals(500.milliseconds, low.delay(6))
        // Real jitter stays inside the bound.
        val real = Backoff()
        for (failures in 1..10) {
            val delay = real.delay(failures)
            assertTrue(delay >= 500.milliseconds && delay <= real.bound(failures) + 500.milliseconds)
        }
    }

    @Test
    fun relayHintsWin() {
        val backoff = Backoff(random = { it.start })
        assertEquals(42.seconds, backoff.delay(1, after = RelayException(429, RelayErrorCode.RATE_LIMITED, "slow down", retryAfterSeconds = 42)))
        assertEquals(1.hours, backoff.delay(1, after = RelayException(429, RelayErrorCode.RATE_LIMITED, "slow down", retryAfterSeconds = 90_000)))
        assertEquals(500.milliseconds, backoff.delay(1, after = RelayException(429, RelayErrorCode.RATE_LIMITED, "slow down")))
        assertEquals(30.seconds, backoff.delay(1, after = ConnectionRejectedException(RelaySocket.CLOSE_REPLACED, null)))
        assertEquals(30.seconds, backoff.delay(1, after = RelayStreamException(closeCode = RelaySocket.CLOSE_REPLACED)))
        assertEquals(42.seconds, backoff.delay(1, after = RelayStreamException(httpStatus = 429, retryAfter = 42.seconds)))
        assertEquals(500.milliseconds, backoff.delay(1, after = IOException("timeout")))
        assertEquals(500.milliseconds, backoff.delay(1, after = null))
    }

    @Test
    fun failuresAreClassified() {
        assertEquals(RelayFailure.Revoked, RelayFailure.of(RelayException(401, RelayErrorCode.UNAUTHORIZED, "x")))
        assertEquals(RelayFailure.Replaced, RelayFailure.of(RelayStreamException(closeCode = 4001)))
        assertEquals(RelayFailure.RateLimited(7.seconds), RelayFailure.of(IOException("outer", RelayException(429, RelayErrorCode.RATE_LIMITED, "x", 7))))
        assertEquals(RelayFailure.Transient, RelayFailure.of(RelayStreamException(closeCode = 1001)))
        assertEquals(RelayFailure.Transient, RelayFailure.of(IllegalStateException()))
    }
}
