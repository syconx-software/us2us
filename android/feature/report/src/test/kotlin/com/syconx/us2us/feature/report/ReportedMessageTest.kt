package com.syconx.us2us.feature.report

import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.domain.ReportReason
import com.syconx.us2us.domain.ReportSubject
import com.syconx.us2us.domain.nav.MessageId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 5.3 › “Bildir” on a message (App Review 1.2), as on iOS: what the report may quote and how the e-mail says it. */
@RunWith(RobolectricTestRunner::class)
class ReportedMessageTest {
    private val deniz = ReportSubject("deniz", isUsernameVerified = true)

    private fun message(content: MessageContent, direction: MessageDirection = MessageDirection.Incoming, media: MediaAvailability = MediaAvailability.Ready) =
        Message(MessageId("m1"), direction, reportNow, content, mediaAvailability = media)

    @Test fun aTextIsQuotedAndAnythingElseOnlyByItsKind() {
        assertEquals(ReportedMessage(ReportedMessage.Content.Text("Merhaba")), ReportedMessage.of(message(MessageContent.Text("Merhaba"))))
        assertEquals(
            ReportedMessage(ReportedMessage.Content.Of(ReportedMessage.Kind.Voice)),
            ReportedMessage.of(message(MessageContent.Voice(kotlin.time.Duration.ZERO, emptyList(), 0.0))),
        )
        assertNull("nobody wrote a deleted message", ReportedMessage.of(message(MessageContent.Deleted)))
    }

    @Test fun onlyWhatSomeoneElseWroteAndIsHereCanBeReported() {
        assertTrue(ReportedMessage.canReport(message(MessageContent.Text("selam"))))
        assertFalse("own message", ReportedMessage.canReport(message(MessageContent.Text("selam"), MessageDirection.Outgoing)))
        assertFalse("deleted", ReportedMessage.canReport(message(MessageContent.Deleted)))
        val preparing = message(MessageContent.Text("x"), media = MediaAvailability.Preparing)
        assertFalse("a picked photo still being prepared", ReportedMessage.canReport(preparing))
    }

    @Test fun aLongTextIsCutAtAThousandCharactersAsTheUserSeesThem() {
        assertEquals("selam", ReportedMessage.quoted("  selam \n"))
        val flags = "🇹🇷".repeat(1_001)
        val quoted = ReportedMessage.quoted(flags)
        assertEquals("🇹🇷".repeat(1_000) + "…", quoted)
        assertEquals("exactly the limit stays whole", "a".repeat(1_000), ReportedMessage.quoted("a".repeat(1_000)))
    }

    @Test fun theMailQuotesATextAndSaysOnlyItWasAdded() {
        val strings = reportStrings("en")
        val mail = ReportMail.make(deniz, ReportReason.Spam, "", "1.2", "3", reportNow, strings, ReportedMessage(ReportedMessage.Content.Text("Buy now!")))
        val lines = mail.body.lines()
        assertTrue(mail.body, lines.contains("Reported message: Buy now!"))
        assertEquals(lines.indexOf("Reported message: Buy now!"), lines.indexOfFirst { it.startsWith("Note:") } + 1)
        assertTrue(lines.last().startsWith("Only the text of the message above was added"))
    }

    @Test fun theMailNamesOtherKindsWithoutTheirContentAndAReportWithoutAMessageIsUnchanged() {
        val strings = reportStrings("en")
        val photo = ReportMail.make(deniz, ReportReason.Spam, "", "1.2", "3", reportNow, strings, ReportedMessage(ReportedMessage.Content.Of(ReportedMessage.Kind.Photo)))
        assertTrue(photo.body, photo.body.lines().contains("Reported message: photo (content not added)"))
        val plain = ReportMail.make(deniz, ReportReason.Spam, "", "1.2", "3", reportNow, strings)
        assertEquals("a photo adds nothing but its kind", plain.body.lines().last(), photo.body.lines().last())
        assertFalse(plain.body.contains("Reported message"))
        val turkish = ReportMail.make(deniz, ReportReason.Spam, "", "1.2", "3", reportNow, reportStrings("tr"), ReportedMessage(ReportedMessage.Content.Text("Hemen al!")))
        assertTrue(turkish.body, turkish.body.contains("Hemen al!"))
    }

    @Test fun turningTheToggleOffLeavesTheMessageOut() = runBlocking {
        var sent: ReportMail? = null
        val model = ReportModel(deniz, "deniz", offersBlock = false, mailer = { sent = it; ReportDelivery.Sent }, scope = CoroutineScope(Dispatchers.Unconfined),
            message = ReportedMessage(ReportedMessage.Content.Text("gizli"))) {}
        assertTrue("on by default", model.includeMessage)
        model.reason = ReportReason.Harassment
        model.includeMessage = false
        model.send(reportStrings("en"), reportNow)
        assertFalse(sent!!.body, sent!!.body.contains("gizli"))
    }
}
