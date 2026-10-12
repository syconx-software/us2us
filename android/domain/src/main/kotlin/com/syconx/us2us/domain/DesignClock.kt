package com.syconx.us2us.domain

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The time source for everything time-dependent on screen (countdowns, relative times). Live in
 * the app; frozen for previews, design comparisons and screenshot tests, so screens render exactly
 * like the reference PNGs (iOS `U2Clock`).
 */
class DesignClock private constructor(
    /** The fixed time of a frozen clock. */
    private val base: Instant?,
    /** Added to the wall clock by a running clock, in milliseconds. */
    private val offsetMillis: Long,
    private val wall: () -> Instant,
) {
    val isFrozen: Boolean get() = base != null

    /** The clock's time now. */
    val now: Instant get() = base ?: wall().plusMillis(offsetMillis)

    /** The clock's time for a tick of a periodic redraw taken at wall time [tick]. */
    fun dateForTick(tick: Instant): Instant = base ?: tick.plusMillis(offsetMillis)

    override fun toString(): String = if (base != null) "DesignClock(frozen at $base)" else "DesignClock(offset ${offsetMillis}ms)"

    companion object {
        private val systemWall: () -> Instant = { Instant.now() }

        /** Wall-clock time. */
        val Live = DesignClock(base = null, offsetMillis = 0, wall = systemWall)

        /** Always [at]: countdowns do not tick. */
        fun frozen(at: Instant): DesignClock = DesignClock(base = at, offsetMillis = 0, wall = systemWall)

        /**
         * Starts at [from] and runs with real time from the moment it is created (the design's demo
         * counters: 1.3 starts at 23:41:07 and counts down).
         */
        fun running(from: Instant, wall: () -> Instant = systemWall): DesignClock =
            DesignClock(base = null, offsetMillis = from.toEpochMilli() - wall().toEpochMilli(), wall = wall)

        /** The design's moment: Thursday 8 October 2026, 18:52 in the device's time zone (the 6.3 verification time). */
        val referenceDate: Instant get() = designDate(day = 8, hour = 18, minute = 52)

        /**
         * A wall-clock time in 2026 on the design's calendar, in the device's time zone, so “18:10”
         * always renders as 18:10.
         */
        fun designDate(month: Int = 10, day: Int, hour: Int, minute: Int, second: Int = 0, zone: ZoneId = ZoneId.systemDefault()): Instant =
            LocalDateTime.of(2026, month, day, hour, minute, second).atZone(zone).toInstant()
    }
}
