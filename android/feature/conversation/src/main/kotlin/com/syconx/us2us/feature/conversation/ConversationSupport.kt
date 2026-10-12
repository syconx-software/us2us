package com.syconx.us2us.feature.conversation

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.ui.geometry.Rect
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.QuotedMessage
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.domain.sticker.StickerCatalog
import com.syconx.us2us.domain.sticker.StickerLanguage
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.l10n.U2Strings
import com.syconx.us2us.ui.motion.U2Motion

// Helpers only 05 · Sohbet uses (the reply quote, the 5.3 lift's frames). Bubbles, menus, the
// fade cover and the presentation probes are in the design system.

/**
 * The quote shown when replying to this message (C15 reply block), with the author and kind texts
 * in the UI language. View-once media never yields a thumbnail: it has no preview anywhere (5.2 note).
 */
fun Message.quote(ownUsername: String, peerUsername: String, strings: U2Strings): QuotedMessage? {
    val author = if (isOutgoing) ownUsername else peerUsername
    val quote = when (val content = content) {
        is MessageContent.Text -> QuotedMessage(author, QuotedMessage.Kind.Text(content.text))
        is MessageContent.Photo -> QuotedMessage(author, QuotedMessage.Kind.Photo, thumbnail = content.source)
        is MessageContent.Video -> QuotedMessage(author, QuotedMessage.Kind.Video, thumbnail = content.source)
        is MessageContent.Voice -> QuotedMessage(author, QuotedMessage.Kind.Voice)
        is MessageContent.File -> QuotedMessage(author, QuotedMessage.Kind.File(content.name))
        is MessageContent.Location -> QuotedMessage(author, QuotedMessage.Kind.Text(content.name))
        is MessageContent.LiveLocation -> QuotedMessage(author, QuotedMessage.Kind.Text(strings[R.string.common_location_live]))
        is MessageContent.TextSticker -> QuotedMessage(author, QuotedMessage.Kind.Text(content.text))
        is MessageContent.Sticker -> {
            // The words as drawn, else the emoji and what it shows (“👋 El sallayan bardak”); an unknown sticker its emoji.
            val ui = StickerLanguage.forLocale(strings.locale)
            val summary = StickerCatalog.resolution(content, ui)?.summary(ui)
            QuotedMessage(author, QuotedMessage.Kind.Text(summary ?: strings[R.string.common_chats_preview_sticker]))
        }
        is MessageContent.ViewOnce -> QuotedMessage(author, if (content.isVideo) QuotedMessage.Kind.Video else QuotedMessage.Kind.Photo)
        MessageContent.Deleted, is MessageContent.System -> null
    }
    return quote?.copy(messageId = id)
}

/**
 * Where bubbles are on screen, in window coordinates (the 5.3 lift). A plain map, written from
 * layout callbacks without invalidating anything and read once when a long press opens the menu.
 * Rows report only while a finger rests on them (or the harness asks), never while the column scrolls.
 */
class BubbleFrameStore {
    private val frames = HashMap<MessageId, Rect>()

    operator fun set(id: MessageId, frame: Rect) {
        frames[id] = frame
    }

    operator fun get(id: MessageId): Rect? = frames[id]

    fun remove(id: MessageId) {
        frames.remove(id)
    }
}

/** Motion tokens of the conversation. */
object ConversationMotion {
    /** The send spring: critically damped (no overshoot), a touch quicker than the sheet spring; the bubble and the column settle together. */
    fun <T> send(): AnimationSpec<T> = U2Motion.springQuick()
}
