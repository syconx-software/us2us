package com.syconx.us2us.ui.bubbles

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.DisappearingTimer
import com.syconx.us2us.domain.GhostPeriod
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.l10n.U2Format
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * The time row inside a bubble: an optional disappearing badge (`ph:timer` 13 + “5 dk”,
 * `accent300`, gap 3) · gap 8 · the time (`bubbleTime` 11.5, tabular, `text·68%`) and, for
 * outgoing messages, the 15 dp status glyph (gap 4), which cross-fades on a change.
 */
@Composable
fun BubbleMeta(
    time: Instant,
    modifier: Modifier = Modifier,
    status: MessageStatus? = null,
    disappearing: DisappearingTimer = DisappearingTimer.Off,
) {
    val colors = U2Theme.colors
    val locale = currentAppLocale()
    Row(
        modifier.heightIn(min = if (status == null) 11.5.dp else 15.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (disappearing != DisappearingTimer.Off) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                PhosphorIcon(PhosphorGlyph.Timer, 13.dp, tint = colors.accent300)
                U2Text(stringResource(disappearing.shortLabelRes), U2TextStyles.monoTimerInBubble, color = colors.accent300, maxLines = 1, softWrap = false)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            U2Text(
                BubbleTimeFormat.string(time, locale),
                U2TextStyles.bubbleTime.tabular,
                color = colors.textSecondary,
                maxLines = 1,
                softWrap = false,
            )
            // C16: a state change cross-fades in place (0.36 s).
            if (status != null) MessageStatusIcon(status, size = 15.dp)
        }
    }
}

/**
 * `HH:mm` for the bubbles' time rows: `U2Format.clockTime`, whose formatters are cached per
 * locale, so a bubble never builds a formatter while a long conversation scrolls.
 */
object BubbleTimeFormat {
    fun string(date: Instant, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String = U2Format.clockTime(date, locale, zone)
}

/** “30 sn”, “5 dk”, “1 sa”, “1 gün”, “Kapalı”. */
@get:StringRes
val DisappearingTimer.shortLabelRes: Int
    get() = when (this) {
        DisappearingTimer.Off -> R.string.common_disappearing_off
        DisappearingTimer.ThirtySeconds -> R.string.common_disappearing_30s
        DisappearingTimer.FiveMinutes -> R.string.common_disappearing_5m
        DisappearingTimer.OneHour -> R.string.common_disappearing_1h
        DisappearingTimer.OneDay -> R.string.common_disappearing_1d
    }

/** Chip labels (5.5): “1 sa”, “6 sa”, “24 sa”, “3 gün”, “7 gün”. */
@get:StringRes
val GhostPeriod.chipLabelRes: Int
    get() = when (this) {
        GhostPeriod.OneHour -> R.string.common_ghost_period_chip_1h
        GhostPeriod.SixHours -> R.string.common_ghost_period_chip_6h
        GhostPeriod.OneDay -> R.string.common_ghost_period_chip_24h
        GhostPeriod.ThreeDays -> R.string.common_ghost_period_chip_3d
        GhostPeriod.SevenDays -> R.string.common_ghost_period_chip_7d
    }

/** Long labels (7.1, system lines): “1 saat” … “7 gün”. */
@get:StringRes
val GhostPeriod.longLabelRes: Int
    get() = when (this) {
        GhostPeriod.OneHour -> R.string.common_ghost_period_long_1h
        GhostPeriod.SixHours -> R.string.common_ghost_period_long_6h
        GhostPeriod.OneDay -> R.string.common_ghost_period_long_24h
        GhostPeriod.ThreeDays -> R.string.common_ghost_period_long_3d
        GhostPeriod.SevenDays -> R.string.common_ghost_period_long_7d
    }
