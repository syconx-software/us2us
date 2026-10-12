package com.syconx.us2us.feature.verification

import com.syconx.us2us.domain.SafetyNumberInfo
import com.syconx.us2us.domain.VerificationMethod
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.l10n.U2Format
import com.syconx.us2us.ui.l10n.U2Strings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Texts only 06 builds (dates, the spoken digits, the shared number). */
object VerificationText {
    /** “8 Ekim 2026” / “8 October 2026” (the design writes the day first in both languages). */
    fun longDate(date: Instant, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("d MMMM y", locale).format(date.atZone(zone))

    /** “8 Ekim 2026 · 18:52 · yüz yüze” (6.3 pill); `null` without a date (verified before dates were kept). */
    fun verifiedLine(info: SafetyNumberInfo, strings: U2Strings, zone: ZoneId = ZoneId.systemDefault()): String? {
        val date = info.verifiedAt ?: return null
        val method = if (info.method == VerificationMethod.Manual) R.string.verification_verified_method_manual else R.string.verification_verified_method_facetoface
        return listOf(longDate(date, strings.locale, zone), U2Format.clockTime(date, strings.locale, zone), strings[method]).joinToString(" · ")
    }

    /** “Doğrulandı · 8 Ekim 2026 · 18:52” (6.1 verified pill); “Doğrulandı” alone without a date. */
    fun verifiedSince(info: SafetyNumberInfo, strings: U2Strings, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = info.verifiedAt ?: return strings[R.string.verification_safety_verified]
        val time = longDate(date, strings.locale, zone) + " · " + U2Format.clockTime(date, strings.locale, zone)
        return strings.get(R.string.verification_safety_verifiedsince, time)
    }

    /** The digits read group by group (“0 8 2 2 3, 7 8 6 2 6, …”). */
    fun spokenDigits(groups: List<String>): String = groups.joinToString(", ") { group -> group.toList().joinToString(" ") }

    /** The text shared from 6.1 › share: the header, an empty line and the 3 × 4 groups. */
    fun shareText(own: String, peer: String, groups: List<String>, strings: U2Strings): String {
        val header = strings.get(R.string.verification_safety_share_header, own, peer)
        val rows = groups.chunked(4).map { it.joinToString(" ") }
        return (listOf(header, "") + rows).joinToString("\n")
    }
}
