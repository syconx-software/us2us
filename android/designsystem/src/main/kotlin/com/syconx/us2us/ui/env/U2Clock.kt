package com.syconx.us2us.ui.env

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.syconx.us2us.domain.DesignClock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * The time source for everything time-dependent on screen (countdowns, relative times): live in
 * the app, frozen for previews, design comparisons and screenshot tests, so screens render exactly
 * like the reference PNGs (iOS `\.u2Clock`).
 */
val LocalU2Clock = staticCompositionLocalOf { DesignClock.Live }

/**
 * The clock's time, read again every [interval] (or once when the clock is frozen). Countdowns
 * keep ticking under reduce motion: they are information, not decoration.
 */
@Composable
fun rememberU2Now(interval: Duration = 1.seconds): Instant {
    val clock = LocalU2Clock.current
    var now by remember(clock) { mutableStateOf(clock.now) }
    if (!clock.isFrozen) {
        LaunchedEffect(clock, interval) {
            while (true) {
                now = clock.now
                // Wake at the next whole interval of the clock's own time, as a periodic timeline does.
                val step = interval.inWholeMilliseconds.coerceAtLeast(1)
                val wait = step - Math.floorMod(clock.now.toEpochMilli(), step)
                delay(wait.coerceAtLeast(1))
            }
        }
    }
    return now
}

/**
 * Re-renders [content] with the clock's time every [interval] (or once when the clock is frozen):
 * the iOS `U2TimeView`.
 */
@Composable
fun U2TimeView(interval: Duration = 1.seconds, content: @Composable (Instant) -> Unit) {
    content(rememberU2Now(interval))
}
