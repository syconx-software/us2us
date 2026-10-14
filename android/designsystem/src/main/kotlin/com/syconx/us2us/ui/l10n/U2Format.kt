package com.syconx.us2us.ui.l10n

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.text.format.DateFormat
import com.syconx.us2us.ui.R
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Formatting rules of the design (iOS `U2Format`). Every function takes the language explicitly
 * (the in-app language: a [Locale] or the [U2Strings] of that language), never `Locale.getDefault()`.
 * Times are wall-clock times in [ZoneId] (the device's by default).
 */
object U2Format {
    private const val DAY = 86_400.0
    private const val HOUR = 3_600.0

    // Chat list times (4.1)

    /**
     * Today `HH:mm`; yesterday “Dün” / “Yesterday”; this year `d MMM` (“24 Eyl” / “Sep 24”);
     * older `d.MM.yy`.
     */
    fun chatListTime(date: Instant, now: Instant, strings: U2Strings, zone: ZoneId = ZoneId.systemDefault()): String {
        val day = date.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when {
            day == today -> clockTime(date, strings.locale, zone)
            day == today.minusDays(1) -> strings[R.string.common_time_yesterday]
            day.year == today.year -> dayMonth(date, strings.locale, zone)
            else -> Formatters.fixed("d.MM.yy", strings.locale).format(date.atZone(zone))
        }
    }

    /** `d MMM`: “24 Eyl” / “Sep 24”, “8 Eki” / “Oct 8”. */
    fun dayMonth(date: Instant, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
        // Turkish abbreviations come with a trailing dot in some ICU versions (“Eyl.”); the design writes “24 Eyl”.
        Formatters.template("dMMM", locale).format(date.atZone(zone)).replace(".", "")

    /** `HH:mm` (the design uses a 24-hour clock in both languages). */
    fun clockTime(date: Instant, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
        Formatters.fixed("HH:mm", locale).format(date.atZone(zone))

    // Ghost countdowns (C17)

    /**
     * The list-pill form: ≥ 1 day “N gün / N days”, ≥ 1 hour “N sa / N h”, otherwise
     * “N dk / N min”.
     *
     * Days and hours are floored, minutes rounded up with a minimum of 1: this reproduces every
     * pair the design shows: 23:41:07 → “23 sa”, 00:42:10 → “43 dk”.
     */
    fun ghostShort(remaining: Duration, strings: U2Strings): String {
        val seconds = seconds(remaining)
        if (seconds >= DAY) {
            val days = (seconds / DAY).toInt()
            return strings.plural(R.plurals.common_ghost_short_days, days, days)
        }
        if (seconds >= HOUR) {
            return strings.get(R.string.common_ghost_short_hours, (seconds / HOUR).toInt())
        }
        val minutes = max(1, ceil(seconds / 60).toInt())
        return strings.get(R.string.common_ghost_short_minutes, minutes)
    }

    /** The live form `HH:MM:SS` (hours are not wrapped at 24). */
    fun ghostLong(remaining: Duration): String {
        val total = floor(seconds(remaining)).toLong()
        return String.format(Locale.ROOT, "%02d:%02d:%02d", total / 3_600, (total % 3_600) / 60, total % 60)
    }

    /** Ghost turns urgent in its last hour (C17). */
    fun ghostIsUrgent(remaining: Duration): Boolean = remaining.toDouble(DurationUnit.SECONDS) < HOUR

    // Countdowns and sizes

    /** `mm:ss` for lock-out waits (3.2). */
    fun minutesSeconds(remaining: Duration): String {
        val total = ceil(seconds(remaining)).toLong()
        return String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60)
    }

    /** Voice-message duration `m:ss` (“0:42”). */
    fun voiceDuration(duration: Duration): String {
        val total = seconds(duration).roundToLong()
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
    }

    /**
     * File sizes with the language's decimal separator: TR “2,1 MB”, EN “2.1 MB”. Decimal units
     * (1 kB = 1 000 bytes, as the iOS file style), 0 fraction digits for kB, 1 for MB, 2 above.
     */
    fun byteCount(bytes: Long, locale: Locale): String {
        val numberLocale = numberLocale(locale)
        var unit = 0
        var value = BigDecimal.valueOf(bytes).abs()
        while (unit < BYTE_UNITS.size - 1 && value >= THOUSAND) {
            value = value.divide(THOUSAND)
            unit++
        }
        var rounded = value.setScale(fractionDigits(unit), RoundingMode.HALF_EVEN)
        // 999 950 bytes is 1 000.0 kB at kB precision: show it as 1 MB, as the system does.
        if (unit in 1 until BYTE_UNITS.size - 1 && rounded >= THOUSAND) {
            unit++
            rounded = value.divide(THOUSAND).setScale(fractionDigits(unit), RoundingMode.HALF_EVEN)
        }
        val signed = if (bytes < 0) rounded.negate() else rounded
        val number = android.icu.text.NumberFormat.getInstance(numberLocale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = fractionDigits(unit)
        }
        val format = MeasureFormat.getInstance(numberLocale, MeasureFormat.FormatWidth.SHORT, number)
        return format.format(Measure(signed.stripTrailingZeros(), BYTE_UNITS[unit]))
    }

    /** Grouped integers: TR “3.920”, EN “3,920”. */
    fun integer(value: Long, locale: Locale): String =
        NumberFormat.getIntegerInstance(numberLocale(locale)).format(value)

    /** [integer] for an `Int`. */
    fun integer(value: Int, locale: Locale): String = integer(value.toLong(), locale)

    /**
     * Sizes and counts follow the UI language, as the design's English writes them (“3,920”,
     * “12.4 MB”), not the device region (an English UI on a Turkish-region phone would otherwise
     * read “3.920”). Times are formatted elsewhere and keep the 24-hour clock.
     */
    fun numberLocale(locale: Locale): Locale = if (locale.language == "en") Locale.US else locale

    private val THOUSAND = BigDecimal.valueOf(1_000)
    private val BYTE_UNITS = listOf(MeasureUnit.BYTE, MeasureUnit.KILOBYTE, MeasureUnit.MEGABYTE, MeasureUnit.GIGABYTE, MeasureUnit.TERABYTE)

    private fun fractionDigits(unit: Int): Int = when {
        unit <= 1 -> 0
        unit == 2 -> 1
        else -> 2
    }

    /** Whole seconds and fractions, never negative (an elapsed deadline reads zero). */
    private fun seconds(duration: Duration): Double =
        if (duration.isInfinite()) (if (duration.isPositive()) Double.MAX_VALUE else 0.0) else max(0.0, duration.toDouble(DurationUnit.SECONDS))
}

/**
 * The date formatters behind [U2Format], made once per pattern and language. `DateTimeFormatter`
 * is immutable and thread-safe; the chat list formats a time for every row that scrolls in.
 */
private object Formatters {
    private val fixed = ConcurrentHashMap<Pair<String, Locale>, DateTimeFormatter>()
    private val templates = ConcurrentHashMap<Pair<String, Locale>, DateTimeFormatter>()

    /** A fixed pattern (“HH:mm”). */
    fun fixed(pattern: String, locale: Locale): DateTimeFormatter =
        fixed.getOrPut(pattern to locale) { DateTimeFormatter.ofPattern(pattern, locale) }

    /** A localized template (“dMMM” → “d MMM” / “MMM d”), resolved with ICU as on iOS. */
    fun template(skeleton: String, locale: Locale): DateTimeFormatter =
        templates.getOrPut(skeleton to locale) {
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
        }
}
