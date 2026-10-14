package com.syconx.us2us.domain

import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClockAndFlowsTest {
    @Test
    fun `a frozen clock never moves, a running one starts where it is told`() {
        val moment = Instant.parse("2026-10-08T15:52:00Z")
        val frozen = DesignClock.frozen(moment)
        assertTrue(frozen.isFrozen)
        assertEquals(moment, frozen.now)
        assertEquals(moment, frozen.dateForTick(Instant.now()))

        var wall = Instant.parse("2030-01-01T00:00:00Z")
        val running = DesignClock.running(moment) { wall }
        assertFalse(running.isFrozen)
        assertEquals(moment, running.now)
        wall = wall.plusSeconds(90)
        assertEquals(moment.plusSeconds(90), running.now)
        assertFalse(DesignClock.Live.isFrozen)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `mapped and combined flows follow their sources without repeats`() = runTest(UnconfinedTestDispatcher()) {
        val numbers = MutableStateFlow(1)
        val words = MutableStateFlow("a")
        val parity = numbers.mapState { it % 2 }
        val both = combineStates(numbers, words) { n, w -> "$w$n" }
        assertEquals(1, parity.value)
        assertEquals("a1", both.value)

        val seen = mutableListOf<Int>()
        val seenBoth = mutableListOf<String>()
        val jobs = listOf(
            launch { parity.collect { seen += it } },
            launch { both.collect { seenBoth += it } },
        )
        numbers.value = 3
        numbers.value = 4
        words.value = "b"
        jobs.forEach { it.cancel() }
        assertEquals(listOf(1, 0), seen)
        assertEquals(listOf("a1", "a3", "a4", "b4"), seenBoth)
        assertEquals(listOf(0), parity.replayCache)
        assertEquals(7, constantStateFlow(7).value)
    }
}
