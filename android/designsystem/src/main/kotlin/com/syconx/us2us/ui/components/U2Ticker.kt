package com.syconx.us2us.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.ui.env.LocalU2Clock
import com.syconx.us2us.ui.nav.LocalIsLayerVisible
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * One shared, slow clock for a screen with many small countdowns (the 4.1 Ghost pills): a single
 * timer instead of one per row, and only the composables that read [now] recompose when it ticks.
 * Rows that scroll in do not start a timer of their own.
 *
 * Follows the design clock (frozen in references, running from the design's moment otherwise) and
 * ticks only while [run] is running (the screen is visible); when it resumes, it catches up at once.
 */
@Stable
class U2Ticker(private val clock: DesignClock, private val interval: Duration = 30.seconds) {
    /** The clock's time at the last tick. */
    var now: Instant by mutableStateOf(clock.now)
        private set

    /**
     * Ticks every [interval] until cancelled; catches up first. Returns at once on a frozen clock,
     * which never moves.
     */
    suspend fun run() {
        update()
        if (clock.isFrozen) return
        while (true) {
            delay(interval)
            update()
        }
    }

    private fun update() {
        val date = clock.now
        if (date != now) now = date
    }
}

/** A shared [U2Ticker] for the countdowns below it (null: each keeps its own timer). */
val LocalU2Ticker = staticCompositionLocalOf<U2Ticker?> { null }

/**
 * A ticker on the current design clock that runs while [running] (by default while its screen can
 * be seen). Provide it with `CompositionLocalProvider(LocalU2Ticker provides ticker)`.
 */
@Composable
fun rememberU2Ticker(interval: Duration = 30.seconds, running: Boolean = LocalIsLayerVisible.current): U2Ticker {
    val clock = LocalU2Clock.current
    val ticker = remember(clock, interval) { U2Ticker(clock, interval) }
    if (running) {
        LaunchedEffect(ticker) { ticker.run() }
    }
    return ticker
}
