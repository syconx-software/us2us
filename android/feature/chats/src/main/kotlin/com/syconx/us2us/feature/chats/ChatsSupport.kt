package com.syconx.us2us.feature.chats

import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.l10n.U2Format
import com.syconx.us2us.ui.l10n.U2Strings
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max

// Helpers only 04 · Sohbetler uses. Shared pieces (menus, fade covers, the QR card, flex rows,
// CSS text layouts, the collapse transition) live in the design system.

/**
 * How a stranger's message is shown before it is accepted: links never become tappable and media
 * is never decoded (inventory 4.3 / 4.4 notes).
 */
object RequestContent {
    enum class TextKind {
        Plain,

        /** The whole message is one address (“slides.kuzey.dev/meetup”). */
        Link,

        /** Text with an address inside (“… tıkla: bit.ly/3xQ”). */
        ContainsLink,
    }

    /**
     * Local pattern only: nothing is fetched or resolved. Scheme optional, a dotted host with a
     * top-level label of at least two letters, optional path.
     */
    private val linkPattern = Regex("""(?i)(?:https?://)?(?:[a-z0-9-]+\.)+[a-z]{2,}(?:/\S*)?""")

    fun classify(text: String): TextKind {
        val trimmed = text.trim()
        val match = linkPattern.find(trimmed) ?: return TextKind.Plain
        return if (match.range.first == 0 && match.range.last == trimmed.length - 1) TextKind.Link else TextKind.ContainsLink
    }

    /** Whether a request's thread carries media (shown as a placeholder only). */
    fun isMedia(content: MessageContent): Boolean = when (content) {
        is MessageContent.Text, MessageContent.Deleted, is MessageContent.System -> false
        else -> true
    }
}

/** Times of 4.3 (request ages) and 4.4 (the date separator). */
object ChatsFormat {
    /** 4.3 request age: “2 sa” · “Dün” · “3 gün” (under an hour “N dk”; from a week on `d MMM`). */
    fun requestAge(date: Instant, now: Instant, strings: U2Strings, zone: ZoneId = ZoneId.systemDefault()): String {
        val elapsed = max(0L, now.epochSecond - date.epochSecond)
        val hours = { strings.get(R.string.chats_requests_age_hours, (elapsed / 3_600).toInt()) }
        if (elapsed < 3_600) return strings.get(R.string.chats_requests_age_minutes, max(1L, elapsed / 60).toInt())
        val day = date.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        if (elapsed < 86_400 && day == today) return hours()
        val days = ChronoUnit.DAYS.between(day, today).toInt()
        if (days <= 1) return if (elapsed < 86_400) hours() else strings[R.string.common_time_yesterday]
        if (days < 7) return strings.plural(R.plurals.chats_requests_age_days, days, days)
        return U2Format.dayMonth(date, strings.locale, zone)
    }

    /**
     * 4.4 date separator: “Bugün 16:12” / “Dün 16:12” / “24 Eyl 16:12” (upper-cased by
     * `monoSectionLabel` in the current language).
     */
    fun separator(date: Instant, now: Instant, strings: U2Strings, zone: ZoneId = ZoneId.systemDefault()): String {
        val time = U2Format.clockTime(date, strings.locale, zone)
        val day = date.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when (day) {
            today -> strings.get(R.string.chats_preview_date_today, time)
            today.minusDays(1) -> strings.get(R.string.chats_preview_date_yesterday, time)
            else -> "${U2Format.dayMonth(date, strings.locale, zone)} $time"
        }
    }
}
