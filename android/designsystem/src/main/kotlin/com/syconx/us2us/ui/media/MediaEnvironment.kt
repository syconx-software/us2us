package com.syconx.us2us.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.ui.env.LocalU2Clock
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * The clock the bubbles and the media controls read (iOS `@Environment(\.u2Clock)`): the app's
 * [LocalU2Clock]. A frozen clock stops the travelling ring, the typing dots and the pulse rings
 * where the references caught them, and stands the live-location countdown still.
 */
val LocalMediaClock: ProvidableCompositionLocal<DesignClock> get() = LocalU2Clock

/**
 * The clock's time, read again every [intervalMillis] (iOS `U2TimeView`); once when the clock is
 * frozen. Countdowns keep ticking under reduce motion: they are information, not decoration.
 */
@Composable
fun rememberClockTime(intervalMillis: Long = 1_000, clock: DesignClock = LocalMediaClock.current): Instant {
    if (clock.isFrozen) return clock.now
    var now by remember(clock) { mutableStateOf(clock.now) }
    LaunchedEffect(clock, intervalMillis) {
        while (true) {
            now = clock.now
            delay(intervalMillis)
        }
    }
    return now
}

/**
 * What the open conversation is doing with a message's media right now, beyond what the
 * messenger reports: a file being decrypted for its preview shows the ring around its tile.
 * Provided by 5.1 ([LocalConversationMediaActivity]); 4.4's inert rows have none.
 */
@Stable
class ConversationMediaActivity {
    /** The message whose file is being opened. */
    var opening: MessageId? by mutableStateOf(null)
        private set

    fun isOpening(id: MessageId): Boolean = opening == id

    fun begin(id: MessageId) {
        opening = id
    }

    fun end(id: MessageId) {
        if (opening == id) opening = null
    }
}

/** The open conversation's [ConversationMediaActivity], `null` outside 5.1. */
val LocalConversationMediaActivity = staticCompositionLocalOf<ConversationMediaActivity?> { null }
