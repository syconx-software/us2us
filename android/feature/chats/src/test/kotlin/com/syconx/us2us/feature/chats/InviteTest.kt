package com.syconx.us2us.feature.chats

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.syconx.us2us.feature.chats.invite.InviteChannel
import com.syconx.us2us.feature.chats.invite.InviteChannelUrl
import com.syconx.us2us.feature.chats.invite.InviteOutcome
import com.syconx.us2us.feature.chats.invite.InviteSender
import com.syconx.us2us.feature.chats.invite.InviteLink
import com.syconx.us2us.feature.chats.invite.InviteMessage
import com.syconx.us2us.feature.chats.invite.InvitePasteboard
import com.syconx.us2us.ui.env.ExternalActivityGuard
import com.syconx.us2us.ui.l10n.U2Strings
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

// Invites (docs/INVITES.md): links, the message, channel URLs, the clipboard and the link inbox.
// Ported from the iOS InviteTests.

class InviteLinkTest {
    @Test
    fun buildsTheCanonicalLink() {
        assertEquals("https://us2us.app/u/selin_n", InviteLink.url("selin_n"))
        assertEquals("https://us2us.app/u/ayla_", InviteLink.url("ayla_"))
        // Normalized: white space, one “@”, case (without Turkish İ/ı mapping).
        assertEquals("https://us2us.app/u/deniz", InviteLink.url(" @Deniz\n"))
        assertEquals("https://us2us.app/u/kaan2", InviteLink.url("KAAN2"))
        assertEquals("us2us.app/u/selin_n", InviteLink.displayString("selin_n"))
    }

    @Test
    fun refusesWhatIsNotAUsername() {
        val names = listOf(
            "", "ab", "a".repeat(25), "şeyma", "ırmak", "İsmail", "de niz", "de/niz", "de%20niz", "deniz?x",
            "deniz#x", "@@deniz", "de-niz", "de+niz", "...", "_._", "selin.n", "1abc", "_abc", "deniz\u0000",
            "dеniz", // a Cyrillic “е”
        )
        for (name in names) {
            assertNull(name, InviteLink.url(name))
            assertNull(name, InviteLink.normalizedUsername(name))
        }
    }

    @Test
    fun roundTrips() {
        for (name in listOf("deniz", "selin_n", "mert_k", "ayla_", "kaan2", "abc", "a_b_c", "z".repeat(24))) {
            val url = checkNotNull(InviteLink.url(name))
            assertEquals(name, InviteLink.username(url))
            // The website's language switch and a trailing slash are fine.
            assertEquals(name, InviteLink.username("$url?lang=en"))
            assertEquals(name, InviteLink.username("$url/"))
        }
    }

    @Test
    fun refusesEverythingElse() {
        val urls = listOf(
            "http://us2us.app/u/deniz", // not https
            "https://www.us2us.app/u/deniz", // another host
            "https://us2us.app.evil.example/u/deniz",
            "https://evil.example/u/deniz",
            "https://relay.us2us.app/u/deniz",
            "https://us2us.app:8443/u/deniz", // a port
            "https://someone@us2us.app/u/deniz", // user info
            "https://us2us.app/u/Deniz", // not normalized
            "https://us2us.app/u/%64eniz", // percent-encoded
            "https://us2us.app/u/de%20niz",
            "https://us2us.app/u/deniz/x", // more segments
            "https://us2us.app/u//deniz",
            "https://us2us.app/u/",
            "https://us2us.app/u/ab",
            "https://us2us.app/u/selin.n", // a dot is not a username character
            "https://us2us.app/u/1abc",
            "https://us2us.app/U/deniz",
            "https://us2us.app/tr",
            "https://us2us.app/user/deniz",
            "us2us://u/deniz", // no custom scheme
            "us2us:@deniz", // the QR payload is not a link
            "mailto:deniz@us2us.app",
            "https://us2us.app/u/%C5%9Feyma",
            "not a url at all",
        )
        for (url in urls) assertNull(url, InviteLink.username(url))
    }
}

class InviteChannelUrlTest {
    private fun query(url: String, name: String): String? =
        url.substringAfter('?').split('&').map { it.split('=', limit = 2) }.firstOrNull { it[0] == name }
            ?.let { URLDecoder.decode(it[1].replace("+", "%2B"), "UTF-8") }

    @Test
    fun whatsAppEncodesEverythingButUnreserved() {
        val text = "Merhaba! us2us’ta: @selin_n & #1 + 2?=ş\nhttps://us2us.app/u/selin_n"
        val url = InviteChannelUrl.whatsApp(text)
        assertEquals(
            "https://wa.me/?text=Merhaba%21%20us2us%E2%80%99ta%3A%20%40selin_n%20%26%20%231%20%2B%202%3F%3D%C5%9F%0Ahttps%3A%2F%2Fus2us.app%2Fu%2Fselin_n",
            url,
        )
        // Nothing the query could misread survives unencoded.
        val encoded = URI(url).rawQuery.removePrefix("text=")
        assertFalse(encoded.any { it in " &#+?=/:@\n’ş" })
        // wa.me reads it back exactly.
        assertEquals("wa.me", URI(url).host)
        assertEquals(text, query(url, "text"))
    }

    @Test
    fun mailtoHasSubjectBodyAndCrlf() {
        val url = InviteChannelUrl.mailto("us2us’ta konuşalım mı?", "Satır 1\nSatır 2 & ?\r\nson")
        assertEquals(
            "mailto:?subject=us2us%E2%80%99ta%20konu%C5%9Fal%C4%B1m%20m%C4%B1%3F&body=Sat%C4%B1r%201%0D%0ASat%C4%B1r%202%20%26%20%3F%0D%0Ason",
            url,
        )
        assertEquals("us2us’ta konuşalım mı?", query(url, "subject"))
        assertEquals("Satır 1\r\nSatır 2 & ?\r\nson", query(url, "body"))
    }

    @Test
    fun crlfNormalizesEveryLineBreak() {
        assertEquals("a\r\nb\r\nc\r\nd", InviteChannelUrl.crlf("a\nb\r\nc\rd"))
    }

    @Test
    fun clampsLongEdits() {
        val long = "ğ".repeat(InviteMessage.MAX_LENGTH + 50)
        assertEquals(InviteMessage.MAX_LENGTH, InviteMessage.clamped(long).length)
        assertEquals("kısa", InviteMessage.clamped("kısa"))
    }
}

class InviteInboxTest {
    @Test
    fun keepsTheLatestInviteLinkOnce() {
        val inbox = InviteInbox()
        val now = Instant.parse("2026-10-08T15:52:00Z")
        assertFalse(inbox.receive("https://example.com/u/deniz", now))
        assertFalse(inbox.receive("us2us://u/deniz", now))
        assertNull(inbox.pending)
        assertTrue(inbox.receive("https://us2us.app/u/mert_k", now))
        assertTrue(inbox.receive("https://us2us.app/u/deniz?lang=tr", now))
        assertEquals("deniz", inbox.pending?.username)
        assertEquals("deniz", inbox.take(now.plusSeconds(5)))
        assertNull(inbox.take(now.plusSeconds(6)))
    }

    @Test
    fun dropsStaleLinks() {
        val inbox = InviteInbox()
        val then = Instant.parse("2026-10-08T15:52:00Z")
        inbox.receive("https://us2us.app/u/deniz", then)
        assertNull(inbox.take(then.plusSeconds(InviteInbox.LIFETIME.inWholeSeconds + 1)))
        assertNull(inbox.pending)
    }
}

/** The message in both languages and the clipboard (they need the app's resources and services). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InviteMessageTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun strings(tag: String): U2Strings = U2Strings.of(context, Locale.forLanguageTag(tag))

    @Test
    fun turkishTemplate() {
        val message = checkNotNull(InviteMessage.make("selin_n", strings("tr-TR")))
        assertEquals("https://us2us.app/u/selin_n", message.link)
        assertTrue(message.text.startsWith("Merhaba! Seninle us2us’ta konuşmak istiyorum"))
        assertTrue(message.text.contains("Kullanıcı adım: @selin_n"))
        assertTrue(message.text.endsWith("\nhttps://us2us.app/u/selin_n"))
        assertEquals("us2us’ta konuşalım mı?", message.subject)
        assertFalse(message.text.contains("%"))
    }

    @Test
    fun englishTemplate() {
        val message = checkNotNull(InviteMessage.make("deniz", strings("en-US")))
        assertTrue(message.text, message.text.startsWith("Hi! I’d like to talk with you on us2us"))
        assertTrue(message.text.contains("My username: @deniz"))
        assertTrue(message.text.endsWith("\nhttps://us2us.app/u/deniz"))
        assertEquals("Shall we talk on us2us?", message.subject)
    }

    @Test
    fun normalizesAndRefuses() {
        assertEquals("deniz", InviteMessage.make("@Deniz", strings("tr-TR"))?.username)
        assertNull(InviteMessage.make("şeyma", strings("tr-TR")))
        assertNull(InviteMessage.make("", strings("en-US")))
    }

    @Test
    fun whatsAppCarriesTheWholeTurkishTemplate() {
        val message = checkNotNull(InviteMessage.make("selin_n", strings("tr-TR")))
        val url = InviteChannelUrl.whatsApp(message.text)
        assertTrue(url.contains("Kullan%C4%B1c%C4%B1%20ad%C4%B1m%3A%20%40selin_n%0Ahttps%3A%2F%2Fus2us.app%2Fu%2Fselin_n"))
    }

    @Test
    fun channelsOpenTheSystemsOwnUi() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val shadow = Shadows.shadowOf(app)
        val sender = InviteSender(app, ExternalActivityGuard())
        val message = checkNotNull(InviteMessage.make("selin_n", strings("tr-TR")))
        val outcomes = mutableListOf<InviteOutcome>()

        sender.send(InviteChannel.Sms, message) { outcomes += it }
        val sms = checkNotNull(shadow.nextStartedActivity)
        assertEquals(Intent.ACTION_SENDTO, sms.action)
        assertEquals("smsto:", sms.dataString)
        assertEquals(message.text, sms.getStringExtra("sms_body"))

        sender.send(InviteChannel.Email, message) { outcomes += it }
        val mail = checkNotNull(shadow.nextStartedActivity)
        assertEquals(Intent.ACTION_SENDTO, mail.action)
        assertEquals(InviteChannelUrl.mailto(message.subject, message.text), mail.dataString)

        sender.send(InviteChannel.WhatsApp, message) { outcomes += it }
        val whatsApp = checkNotNull(shadow.nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, whatsApp.action)
        assertEquals(InviteChannelUrl.whatsApp(message.text), whatsApp.dataString)

        sender.send(InviteChannel.More, message) { outcomes += it }
        val chooser = checkNotNull(shadow.nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val shared = checkNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(message.text, shared.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(message.subject, shared.getStringExtra(Intent.EXTRA_SUBJECT))

        sender.send(InviteChannel.CopyLink, message) { outcomes += it }
        assertEquals(listOf(InviteOutcome.HandedOff, InviteOutcome.HandedOff, InviteOutcome.HandedOff, InviteOutcome.Copied), outcomes)
    }

    @Test
    fun copiesOnlyTheLink() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        InvitePasteboard.copy("https://us2us.app/u/selin_n", clipboard)
        val clip = checkNotNull(clipboard.primaryClip)
        assertEquals(1, clip.itemCount)
        assertEquals("https://us2us.app/u/selin_n", clip.getItemAt(0).text.toString())
        assertEquals(120L, InvitePasteboard.LIFETIME_SECONDS)
    }
}
