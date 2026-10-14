package com.syconx.us2us.feature.report

import androidx.annotation.StringRes
import com.syconx.us2us.domain.ReportReason
import com.syconx.us2us.domain.ReportSubject
import com.syconx.us2us.ui.l10n.U2Strings
import java.text.BreakIterator
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.syconx.us2us.ui.R as DesignR

/** The row title of a reason, also the reason as the e-mail names it. */
@get:StringRes
val ReportReason.titleRes: Int
    get() = when (this) {
        ReportReason.Spam -> DesignR.string.report_reason_spam
        ReportReason.Harassment -> DesignR.string.report_reason_harassment
        ReportReason.Inappropriate -> DesignR.string.report_reason_inappropriate
        ReportReason.Impersonation -> DesignR.string.report_reason_impersonation
        ReportReason.Other -> DesignR.string.report_reason_other
    }

/**
 * The e-mail a report becomes, in the app's language: to the support address, “us2us bildirimi:
 * @kullanici”, and a body with the username, the reason, the user's note, the app's version and
 * build, the time in UTC and the sentence that no message content is in it. Nothing else is added:
 * no message, no key, no account id; the user may attach screenshots themselves.
 */
data class ReportMail(val subject: String, val body: String) {
    /**
     * `mailto:` (RFC 6068) to the support address with the subject and body. Everything outside
     * RFC 3986's unreserved characters is percent-encoded (UTF-8); line breaks become CRLF.
     */
    val mailtoUri: String
        get() = "mailto:$ADDRESS?subject=${percentEncoded(subject)}&body=${percentEncoded(crlf(body))}"

    companion object {
        /** Where reports go (also shown, with a copy button, when nothing on this phone can write an e-mail). */
        const val ADDRESS = "support@us2us.app"

        /** The longest note the sheet keeps, in characters as the user sees them. */
        const val MAX_NOTE_LENGTH = 500

        /** The body's placeholder for an empty note. */
        const val NO_NOTE = "—"

        /**
         * The e-mail about [about] in [strings]' language. [version] and [build] are the app's
         * `versionName` and `versionCode`; [date] is written in UTC.
         */
        fun make(
            about: ReportSubject,
            reason: ReportReason,
            note: String,
            version: String,
            build: String,
            date: Instant,
            strings: U2Strings,
        ): ReportMail {
            val username = about.username?.takeIf { it.isNotEmpty() }
            val subject: String
            val user: String
            if (username != null) {
                subject = strings.get(DesignR.string.report_mail_subject, username)
                user = if (about.isUsernameVerified) {
                    strings.get(DesignR.string.report_mail_user, username)
                } else {
                    strings.get(DesignR.string.report_mail_user_unverified, username)
                }
            } else {
                subject = strings[DesignR.string.report_mail_subject_unknown]
                user = strings[DesignR.string.report_mail_user_unknown]
            }
            val trimmed = clamped(note).trim()
            val lines = listOf(
                user,
                strings.get(DesignR.string.report_mail_reason, strings[reason.titleRes]),
                strings.get(DesignR.string.report_mail_note, trimmed.ifEmpty { NO_NOTE }),
                "",
                strings.get(R.string.report_android_mail_app, "$version ($build)"),
                strings.get(DesignR.string.report_mail_time, utcTime(date)),
                "",
                strings[DesignR.string.report_mail_encrypted],
            )
            return ReportMail(subject, lines.joinToString("\n"))
        }

        /** The note as the sheet keeps it: at most [MAX_NOTE_LENGTH] characters (grapheme clusters, as iOS counts them). */
        fun clamped(note: String): String {
            val breaks = BreakIterator.getCharacterInstance(Locale.ROOT)
            breaks.setText(note)
            var count = 0
            var end = breaks.first()
            while (true) {
                val next = breaks.next()
                if (next == BreakIterator.DONE) return note
                count++
                if (count > MAX_NOTE_LENGTH) return note.substring(0, end)
                end = next
            }
        }

        /** Characters as the user sees them (grapheme clusters). */
        fun length(text: String): Int {
            val breaks = BreakIterator.getCharacterInstance(Locale.ROOT)
            breaks.setText(text)
            var count = 0
            breaks.first()
            while (breaks.next() != BreakIterator.DONE) count++
            return count
        }

        private val utcFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC)

        /** “2026-10-12 14:03 UTC”: the same in every language and region. */
        fun utcTime(date: Instant): String = utcFormat.format(date)

        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        private const val HEX = "0123456789ABCDEF"

        /** RFC 3986 `unreserved` kept, every other UTF-8 byte as `%XX`. */
        internal fun percentEncoded(text: String): String = buildString {
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                val value = byte.toInt() and 0xFF
                val char = value.toChar()
                if (value < 0x80 && char in UNRESERVED) {
                    append(char)
                } else {
                    append('%').append(HEX[value shr 4]).append(HEX[value and 0x0F])
                }
            }
        }

        /** LF / CR / CRLF → CRLF. */
        internal fun crlf(text: String): String = text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n")
    }
}

/** How far a report got once the user tapped “E-postayla gönder”. */
enum class ReportDelivery {
    /** A mail app sent it (the system cannot tell this on Android; kept for parity and for other mailers). */
    Sent,

    /** A mail app took it over (`mailto:`); whether it goes is up to the user there. */
    HandedOff,

    /** Nothing on this phone can write an e-mail: the sheet shows the address to copy, and the report is in the user's hands. */
    AddressShown,

    /** The mail app closed without sending (cancelled, or kept as a draft), or no mail app was picked in the system's “Open with”. */
    NotSent,

    /** The mail app could not send it. */
    Failed,
    ;

    /** The report left the sheet: sent, handed to a mail app, or the address given to the user to write to. */
    val leftTheApp: Boolean get() = this == Sent || this == HandedOff || this == AddressShown

    companion object {
        /**
         * “Bu kişiyi de engelle” blocks only once the report left the sheet: never when the mail app
         * was cancelled, kept a draft or failed.
         */
        fun blocks(after: ReportDelivery, alsoBlock: Boolean): Boolean = alsoBlock && after.leftTheApp
    }
}
