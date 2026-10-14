package com.syconx.us2us.feature.chats.invite

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.l10n.U2Strings

/**
 * The ready-made invitation (Invite catalogue), in the app's language: a short, warm text with the
 * user's username and link, and an e-mail subject. The user sees it, and may edit it, before
 * choosing a channel.
 */
data class InviteMessage(
    val username: String,
    val link: String,
    /** What is sent (SMS, WhatsApp, e-mail body, share sheet). */
    val text: String,
    /** The e-mail subject (not editable). */
    val subject: String,
) {
    companion object {
        /** The longest text the editor keeps (a long SMS, a comfortable wa.me URL). */
        const val MAX_LENGTH = 1_000

        /** The message for the user [username] in [strings]' language, or `null` when [username] cannot make a link. */
        fun make(username: String, strings: U2Strings): InviteMessage? {
            val name = InviteLink.normalizedUsername(username) ?: return null
            val link = InviteLink.url(name) ?: return null
            return InviteMessage(name, link, defaultText(name, link, strings), strings[R.string.invite_subject])
        }

        /** The template: “Merhaba! Seninle us2us’ta konuşmak istiyorum: … Kullanıcı adım: @selin_n” and the link on its own line. */
        fun defaultText(username: String, link: String, strings: U2Strings): String =
            strings.get(R.string.invite_message, username, link)

        /** Keeps an edited text within [MAX_LENGTH] characters. */
        fun clamped(text: String): String {
            val count = text.codePointCount(0, text.length)
            if (count <= MAX_LENGTH) return text
            return text.substring(0, text.offsetByCodePoints(0, MAX_LENGTH))
        }
    }
}

/**
 * The URLs the channels open. Everything outside RFC 3986's unreserved characters is
 * percent-encoded (UTF-8), so spaces, line breaks, “&”, “#”, “+”, “@”, “’” and Turkish letters
 * reach the other app exactly as written.
 */
object InviteChannelUrl {
    /**
     * WhatsApp's documented click-to-chat link without a number: WhatsApp opens with the text and
     * the user picks the chat (no SDK). It is an App Link of WhatsApp's, so without WhatsApp it
     * opens wa.me in the browser.
     */
    fun whatsApp(text: String): String = "https://wa.me/?text=" + percentEncoded(text)

    /** `mailto:` (RFC 6068) with subject and body and no recipient. Line breaks become CRLF. */
    fun mailto(subject: String, body: String): String =
        "mailto:?subject=${percentEncoded(subject)}&body=${percentEncoded(crlf(body))}"

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private const val HEX = "0123456789ABCDEF"

    /** RFC 3986 `unreserved` (ALPHA / DIGIT / "-" / "." / "_" / "~") kept, every other UTF-8 byte as `%XX`. */
    fun percentEncoded(text: String): String = buildString {
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
    fun crlf(text: String): String = text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n")
}

/**
 * “Bağlantıyı kopyala”: the link only, as plain text, removed from the clipboard after two minutes
 * if it is still the link there. Android has no expiring or device-only clipboard items, so the
 * removal is done by the app while its process lives.
 */
object InvitePasteboard {
    /** Seconds the link stays on the clipboard. */
    const val LIFETIME_SECONDS = 120L

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var pendingClear: Runnable? = null

    /** The clip for [link]: plain text, the label being the link itself. */
    fun clip(link: String): ClipData = ClipData.newPlainText(link, link).apply {
        // Nothing secret: the link is meant to be shared. Marked so the system's paste preview shows it.
        if (Build.VERSION.SDK_INT >= 33) {
            description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) }
        }
    }

    fun copy(link: String, clipboard: ClipboardManager) {
        clipboard.setPrimaryClip(clip(link))
        pendingClear?.let(handler::removeCallbacks)
        val clear = Runnable {
            pendingClear = null
            val current = runCatching { clipboard.primaryClip }.getOrNull()
            val text = current?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            if (text == link) runCatching { clipboard.clearPrimaryClip() }
        }
        pendingClear = clear
        handler.postDelayed(clear, LIFETIME_SECONDS * 1_000)
    }
}
