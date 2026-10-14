package com.syconx.us2us.ui.l10n

import com.syconx.us2us.domain.DesignClock
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** iOS `FormattingTests`: the design's formats in both languages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FormattingTest {
    private val tr = Locale.forLanguageTag("tr-TR")
    private val en = Locale.forLanguageTag("en-US")
    private val trStrings get() = U2Strings.of(RuntimeEnvironment.getApplication(), tr)
    private val enStrings get() = U2Strings.of(RuntimeEnvironment.getApplication(), en)

    @Test
    fun ghostShortFormatReproducesTheDesign() {
        assertEquals("23 sa", U2Format.ghostShort(23.hours + 41.minutes + 7.seconds, trStrings))
        assertEquals("43 dk", U2Format.ghostShort(42.minutes + 10.seconds, trStrings))
        assertEquals("38 dk", U2Format.ghostShort(37.minutes + 20.seconds, trStrings))
        assertEquals("6 gün", U2Format.ghostShort(6.days + 4.hours, trStrings))
        assertEquals("23 h", U2Format.ghostShort(23.hours + 41.minutes + 7.seconds, enStrings))
        assertEquals("43 min", U2Format.ghostShort(42.minutes + 10.seconds, enStrings))
        assertEquals("6 days", U2Format.ghostShort(6.days, enStrings))
        assertEquals("1 day", U2Format.ghostShort(1.days, enStrings))
        assertEquals("1 dk", U2Format.ghostShort(5.seconds, trStrings))
        assertEquals("1 gün", U2Format.ghostShort(1.days, trStrings))
        assertEquals("1 dk", U2Format.ghostShort((-3).seconds, trStrings))
    }

    @Test
    fun ghostLongFormat() {
        assertEquals("23:41:07", U2Format.ghostLong(23.hours + 41.minutes + 7.seconds))
        assertEquals("00:42:10", U2Format.ghostLong(42.minutes + 10.seconds))
        assertEquals("00:00:00", U2Format.ghostLong((-3).seconds))
        assertEquals("167:59:59", U2Format.ghostLong(7.days - 1.seconds))
        assertTrue(U2Format.ghostIsUrgent(42.minutes))
        assertFalse(U2Format.ghostIsUrgent(1.hours))
    }

    @Test
    fun chatListTimes() {
        val zone = ZoneId.systemDefault()
        val now = DesignClock.referenceDate
        fun date(month: Int = 10, day: Int, hour: Int, minute: Int) = DesignClock.designDate(month = month, day = day, hour = hour, minute = minute)
        assertEquals("18:10", U2Format.chatListTime(date(day = 8, hour = 18, minute = 10), now, trStrings, zone))
        assertEquals("Dün", U2Format.chatListTime(date(day = 7, hour = 21, minute = 34), now, trStrings, zone))
        assertEquals("Yesterday", U2Format.chatListTime(date(day = 7, hour = 21, minute = 34), now, enStrings, zone))
        assertEquals("24 Eyl", U2Format.chatListTime(date(month = 9, day = 24, hour = 11, minute = 5), now, trStrings, zone))
        assertEquals("Sep 24", U2Format.chatListTime(date(month = 9, day = 24, hour = 11, minute = 5), now, enStrings, zone))
        val lastYear = ZonedDateTime.ofInstant(now, zone).minusYears(1).toInstant()
        assertTrue(U2Format.chatListTime(lastYear, now, trStrings, zone).endsWith(".25"))
        assertEquals("8.10.25", U2Format.chatListTime(lastYear, now, trStrings, zone))
    }

    @Test
    fun dayMonthAndClock() {
        val zone = ZoneId.systemDefault()
        val date = DesignClock.designDate(day = 8, hour = 9, minute = 5)
        assertEquals("8 Eki", U2Format.dayMonth(date, tr, zone))
        assertEquals("Oct 8", U2Format.dayMonth(date, en, zone))
        assertEquals("09:05", U2Format.clockTime(date, tr, zone))
        assertEquals("09:05", U2Format.clockTime(date, en, zone))
    }

    @Test
    fun numbersFollowTheLanguage() {
        assertEquals("3.920", U2Format.integer(3920, tr))
        assertEquals("3,920", U2Format.integer(3920, en))
        assertEquals("2,1 MB", U2Format.byteCount(2_100_000, tr))
        assertEquals("2.1 MB", U2Format.byteCount(2_100_000, en))
        // An English UI on a Turkish-region device writes the design's English.
        val englishOnTurkishRegion = Locale.forLanguageTag("en-TR")
        assertEquals("3,920", U2Format.integer(3920, englishOnTurkishRegion))
        assertEquals("12.4 MB", U2Format.byteCount(12_400_000, englishOnTurkishRegion))
        assertEquals("0:42", U2Format.voiceDuration(42.seconds))
        assertEquals("01:00", U2Format.minutesSeconds(59.seconds + 200.milliseconds))
    }

    @Test
    fun byteCountUnits() {
        assertEquals("340 kB", U2Format.byteCount(340_000, en))
        assertEquals("2 MB", U2Format.byteCount(2_000_000, en))
        assertEquals("1 MB", U2Format.byteCount(999_999, en))
        assertEquals("1.25 GB", U2Format.byteCount(1_250_000_000, en))
        assertEquals("1,25 GB", U2Format.byteCount(1_250_000_000, tr))
    }

    @Test
    fun voiceDurations() {
        assertEquals("0:00", U2Format.voiceDuration(0.seconds))
        assertEquals("0:07", U2Format.voiceDuration(6.5.seconds))
        assertEquals("1:05", U2Format.voiceDuration(65.seconds))
        assertEquals("0:00", U2Format.voiceDuration((-1).seconds))
    }
}
