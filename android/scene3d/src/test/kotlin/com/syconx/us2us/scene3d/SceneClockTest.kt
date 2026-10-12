package com.syconx.us2us.scene3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneClockTest {
    /** Frames drawn in one second of vsyncs at [hz] (with ±[jitterNanos] of jitter). */
    private fun framesPerSecond(hz: Int, constrained: Boolean = false, jitterNanos: Long = 0): Int {
        val pacer = SceneFramePacer().apply { this.constrained = constrained }
        val period = 1_000_000_000L / hz
        var frames = 0
        // Skip the first half second so the count is the steady rate.
        for (i in 0 until hz * 3 / 2) {
            val jitter = if (i % 2 == 0) jitterNanos else -jitterNanos
            val t = 5_000_000_000L + i * period + jitter
            if (pacer.frame(t) != null && i >= hz / 2) frames++
        }
        return frames
    }

    @Test
    fun atMostSixtyFramesOnAnyDisplay() {
        assertEquals(60, framesPerSecond(60))
        assertEquals(60, framesPerSecond(60, jitterNanos = 1_500_000))
        assertEquals(60, framesPerSecond(90), 1)
        assertEquals(60, framesPerSecond(120))
        assertEquals(48, framesPerSecond(48))
    }

    @Test
    fun thirtyInBatterySaver() {
        assertEquals(30, framesPerSecond(60, constrained = true))
        assertEquals(30, framesPerSecond(120, constrained = true))
    }

    @Test
    fun firstFrameAdvancesOneSixtiethAndLongGapsAreCapped() {
        val pacer = SceneFramePacer()
        assertEquals(1.0 / 60, pacer.frame(1_000_000_000L)!!, 1e-12)
        assertNull(pacer.frame(1_008_000_000L))
        assertEquals(0.0166, pacer.frame(1_016_600_000L)!!, 1e-9)

        val clock = SceneClock()
        clock.advance(1.0 / 60)
        clock.advance(3.0) // paused for three seconds
        clock.advance(-1.0)
        assertEquals(1.0 / 60 + 0.05, clock.time, 1e-12)
        assertEquals(clock.time, clock.dustTime, 0.0)
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) {
        assertTrue("expected $expected ± $tolerance, was $actual", kotlin.math.abs(expected - actual) <= tolerance)
    }
}
