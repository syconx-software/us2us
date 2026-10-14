// us2us — the welcome scene's clock and frame pacing (the display link's
// preferred frame rate and SceneRenderer.advance(by:) on iOS).

package com.syconx.us2us.scene3d

/** Scene time, advanced frame by frame. */
internal class SceneClock {
    var time = 0.0
        private set
    var dustTime = 0.0
        private set

    /**
     * Advances the clock; like the web version a long gap (the scene was paused) counts as at most
     * 50 ms, so nothing jumps on resume.
     */
    fun advance(dt: Double) {
        val step = minOf(0.05, maxOf(0.0, dt))
        time += step
        dustTime += step
    }
}

/**
 * Caps the rate of a vsync-driven loop: at most 60 frames a second ([constrained]: 30) on any
 * display rate, by drawing on the first vsync at (or just before) the next due time.
 */
internal class SceneFramePacer {
    /** Battery saver or a hot device: 30 fps. */
    var constrained = false

    private var nextDueNanos = 0L
    private var lastFrameNanos = -1L

    private val interval: Long get() = if (constrained) FRAME_30 else FRAME_60

    /** Starts over (the loop was stopped): the next vsync draws, and advances by one 60 Hz frame. */
    fun reset() {
        nextDueNanos = 0
        lastFrameNanos = -1
    }

    /** The seconds to advance the scene by if a frame should be drawn at [frameTimeNanos], else null. */
    fun frame(frameTimeNanos: Long): Double? {
        if (frameTimeNanos + VSYNC_TOLERANCE < nextDueNanos) return null
        nextDueNanos += interval
        if (nextDueNanos <= frameTimeNanos) nextDueNanos = frameTimeNanos + interval
        val dt = if (lastFrameNanos < 0) 1.0 / 60 else (frameTimeNanos - lastFrameNanos) / 1e9
        lastFrameNanos = frameTimeNanos
        return dt
    }

    private companion object {
        const val FRAME_60 = 16_666_667L
        const val FRAME_30 = 33_333_333L

        /** Less than half a 120 Hz vsync, more than a vsync's jitter. */
        const val VSYNC_TOLERANCE = 4_000_000L
    }
}
