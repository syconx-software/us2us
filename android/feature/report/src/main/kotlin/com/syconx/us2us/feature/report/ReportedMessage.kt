package com.syconx.us2us.feature.report

import androidx.annotation.StringRes
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import java.text.BreakIterator
import java.util.Locale
import com.syconx.us2us.ui.R as DesignR

/**
 * The message a report started from (5.3 › “Bildir”, App Review 1.2, as on iOS): a text message's text
 * may go into the e-mail, anything else only by its kind. Nothing else of the message is ever added.
 */
data class ReportedMessage(val content: Content) {
    sealed interface Content {
        /** A text message: its text may go into the e-mail. */
        data class Text(val text: String) : Content

        /** Anything else: only its kind. */
        data class Of(val kind: Kind) : Content
    }

    enum class Kind(@StringRes val nameRes: Int) {
        Photo(DesignR.string.report_kind_photo),
        Video(DesignR.string.report_kind_video),
        Voice(DesignR.string.report_kind_voice),
        File(DesignR.string.report_kind_file),
        Location(DesignR.string.report_kind_location),
        LiveLocation(DesignR.string.report_kind_livelocation),
        Sticker(DesignR.string.report_kind_sticker),
        ViewOnce(DesignR.string.report_kind_viewonce),
    }

    val isText: Boolean get() = content is Content.Text

    companion object {
        /** The longest text the e-mail quotes (longer ones end in “…”), in characters as the user sees them. */
        const val MAX_TEXT_LENGTH = 1_000

        /** `null` for what nobody wrote: a deleted message, a line of the chat itself. */
        fun of(message: Message): ReportedMessage? = when (val content = message.content) {
            is MessageContent.Text -> ReportedMessage(Content.Text(content.text))
            is MessageContent.Photo -> ReportedMessage(Content.Of(Kind.Photo))
            is MessageContent.Video -> ReportedMessage(Content.Of(Kind.Video))
            is MessageContent.Voice -> ReportedMessage(Content.Of(Kind.Voice))
            is MessageContent.File -> ReportedMessage(Content.Of(Kind.File))
            is MessageContent.Location -> ReportedMessage(Content.Of(Kind.Location))
            is MessageContent.LiveLocation -> ReportedMessage(Content.Of(Kind.LiveLocation))
            is MessageContent.TextSticker, is MessageContent.Sticker -> ReportedMessage(Content.Of(Kind.Sticker))
            is MessageContent.ViewOnce -> ReportedMessage(Content.Of(Kind.ViewOnce))
            MessageContent.Deleted, is MessageContent.System -> null
        }

        /** What someone else wrote may be reported: not a deleted message, nor a picked photo still being prepared. */
        fun canReport(message: Message): Boolean = !message.isOutgoing && !message.isPendingMedia && of(message) != null

        /** The text as the e-mail quotes it: trimmed, at most [MAX_TEXT_LENGTH] characters. */
        fun quoted(text: String): String {
            val trimmed = text.trim()
            val breaks = BreakIterator.getCharacterInstance(Locale.ROOT)
            breaks.setText(trimmed)
            var count = 0
            var end = breaks.first()
            while (true) {
                val next = breaks.next()
                if (next == BreakIterator.DONE) return trimmed
                count++
                if (count > MAX_TEXT_LENGTH) return trimmed.substring(0, end) + "…"
                end = next
            }
        }
    }
}
