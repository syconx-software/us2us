package com.syconx.us2us.feature.conversation

import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.domain.SystemEvent
import com.syconx.us2us.domain.GhostPeriod
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.feature.conversation.request.RequestContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The column's rules (tails, status sentences, arrivals and departures), 5.4's steps and 4.4's link rule. */
class ConversationLogicTest {
    private val deniz = DesignFixtures.denizMessages(english = false)

    private fun text(id: String, direction: MessageDirection, status: MessageStatus? = null, at: Long = 0) =
        Message(MessageId(id), direction, Instant.ofEpochSecond(1_000 + at), MessageContent.Text(id), status = status)

    @Test
    fun tailsEndEveryRunOfOneDirection() {
        val rows = ConversationRows.rows(deniz)
        val tails = rows.filter { it.isTail }.map { it.id.value }
        // d0 is a system line (never a tail); d1+d2 incoming, d3 outgoing, d4 incoming, d5+d6 outgoing.
        assertEquals(listOf("d2", "d3", "d4", "d6"), tails)
    }

    @Test
    fun onlyTheNewestOutgoingMessageSpellsOutItsState() {
        val rows = ConversationRows.rows(deniz)
        assertEquals(listOf("d6"), rows.filter { it.showsStatusSentence }.map { it.id.value })
        // Once the other side has written after it, its state is evident: no sentence.
        val answered = ConversationRows.statusSentenceIds(deniz + text("in", MessageDirection.Incoming, at = 9_999))
        assertTrue(answered.isEmpty())
        // A system line after it changes nothing.
        val system = deniz + Message(MessageId("sys"), MessageDirection.Outgoing, Instant.now(), MessageContent.System(SystemEvent.GhostEnabled(GhostPeriod.OneHour)))
        assertEquals(setOf(MessageId("d6")), ConversationRows.statusSentenceIds(system))
    }

    @Test
    fun everyFailedMessageSaysSo() {
        val messages = listOf(
            text("a", MessageDirection.Outgoing, MessageStatus.Failed),
            text("b", MessageDirection.Incoming),
            text("c", MessageDirection.Outgoing, MessageStatus.Sent),
        )
        assertEquals(setOf(MessageId("a"), MessageId("c")), ConversationRows.statusSentenceIds(messages))
    }

    @Test
    fun changesFindArrivalsDeparturesAndMorphs() {
        val old = ConversationRows.rows(deniz)
        val withoutD1 = deniz.filter { it.id.value != "d1" }
        val traced = withoutD1.map { if (it.id.value == "d5") it.copy(content = MessageContent.Deleted) else it }
        val new = ConversationRows.rows(traced + text("n1", MessageDirection.Outgoing, MessageStatus.Sending, at = 99_999))
        val changes = ConversationRows.changes(old, new)
        assertEquals(listOf(MessageId("d1")), changes.leaving.map { it.row.id })
        assertEquals(MessageId("d0"), changes.leaving.single().after)
        assertEquals(setOf(MessageId("d5")), changes.kindChanges.keys)
        assertEquals(listOf(MessageId("n1")), changes.arriving)
        // The leaving row keeps its place while it collapses.
        val displayed = ConversationRows.displayed(new, changes.leaving)
        assertEquals(listOf("d0", "d1", "d2"), displayed.take(3).map { it.id.value })
    }

    @Test
    fun historyLoadingAboveIsNotAnArrival() {
        val rows = ConversationRows.rows(deniz)
        assertEquals(rows.map { it.id }, ConversationRows.changes(emptyList(), rows).arriving)
        val older = ConversationRows.rows(listOf(text("old", MessageDirection.Incoming, at = -500)) + deniz)
        assertTrue("history loading above is not an arrival", ConversationRows.changes(rows, older).arriving.isEmpty())
    }

    @Test
    fun infoStepsFollowTheMessagesJourney() {
        val read = deniz.first { it.id.value == "d3" }
        val steps = InfoStep.steps(read, showsRead = true)
        assertEquals(listOf(InfoStep.Kind.Sent, InfoStep.Kind.Delivered, InfoStep.Kind.Read), steps.map { it.kind })
        assertTrue(steps.all { it.isReached })
        assertEquals(read.readAt, steps.last().time)
        assertEquals("read receipts off: no “Okundu”", 2, InfoStep.steps(read, showsRead = false).size)
        val sent = deniz.first { it.id.value == "d6" }
        val pending = InfoStep.steps(sent, showsRead = true)
        assertEquals(listOf(true, false, false), pending.map { it.isReached })
        val failed = InfoStep.steps(sent.copy(status = MessageStatus.Failed), showsRead = true)
        assertEquals(listOf(InfoStep.Kind.Sent, InfoStep.Kind.Failed), failed.map { it.kind })
        val saved = InfoStep.steps(
            Message(MessageId("f"), MessageDirection.Outgoing, Instant.EPOCH, MessageContent.File("a.pdf", 1, "PDF", 1), status = MessageStatus.Read, savedByPeer = true),
            showsRead = true,
        )
        assertEquals(InfoStep.Kind.Saved, saved.last().kind)
    }

    @Test
    fun requestLinksAreRecognisedLocally() {
        assertEquals(RequestContent.TextKind.Link, RequestContent.classify("slides.kuzey.dev/meetup"))
        assertEquals(RequestContent.TextKind.Link, RequestContent.classify("  https://example.com/a?b=c "))
        assertEquals(RequestContent.TextKind.ContainsLink, RequestContent.classify("Ödülünü almak için tıkla: bit.ly/3xQ"))
        assertEquals(RequestContent.TextKind.Plain, RequestContent.classify("Merhaba! Dünkü meetup’taki sunumunu çok beğendim."))
        assertFalse(RequestContent.classify("8.5 saat") == RequestContent.TextKind.Link)
    }
}
