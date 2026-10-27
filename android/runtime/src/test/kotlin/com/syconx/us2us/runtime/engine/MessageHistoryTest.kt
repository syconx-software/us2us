package com.syconx.us2us.runtime.engine

import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageHistoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: EngineFixture
    @Before fun open() { fixture = EngineFixture(temporary.newFolder()) }
    @After fun close() = runBlocking { fixture.engine.stop(); fixture.close() }

    private fun seed(count: Int): com.syconx.us2us.core.AccountId {
        val peer = fixture.acceptedContact()
        fixture.storage.write { tx ->
            val conversation = tx.conversations.forContact(peer)!!
            repeat(count) { index ->
                tx.messages.storeIncoming(textContent("history-$index").data.toBuilder().setSentAt((count-index).toLong()).build(),
                    conversation.id, receivedAt = fixture.clock.now())
            }
        }
        return peer
    }

    @Test fun pagesReachBeforeFiveThousandWithoutGapsOrDuplicates() = runBlocking {
        val peer = seed(5107)
        val other = seed(2)
        val seen = mutableListOf<String>()
        var page = fixture.engine.history(peer)
        assertEquals(100, page.items.size)
        assertTrue(page.hasOlder)
        while (true) {
            seen.addAll(0, page.items.map { (it.body as MessageBody.Text).text })
            if (!page.hasOlder) break
            page = fixture.engine.history(peer, before = page.oldest)
        }
        assertEquals((0..<5107).map { "history-$it" }, seen)
        assertEquals(5107, seen.toSet().size)
        assertEquals(2, fixture.engine.history(other).items.size)
        assertEquals(500, fixture.engine.messages(peer).size) // existing API remains compatible
    }

    @Test fun refreshPreservesLoadedWindowAndDeletedCursorWhileNewMessagesArrive() = runBlocking {
        val peer = seed(350)
        val first = fixture.engine.history(peer)
        val older = fixture.engine.history(peer, before = first.oldest)
        val floor = older.oldest!!
        fixture.storage.write { tx ->
            val conversation = tx.conversations.forContact(peer)!!
            tx.messages.deleteLocally(listOf(older.items.first().messageId, older.items[10].messageId), conversation.id, fixture.clock.now())
            tx.messages.storeIncoming(textContent("new arrival").data, conversation.id, receivedAt = fixture.clock.now())
        }
        val window = fixture.engine.history(peer, through = floor)
        assertEquals(199, window.items.size)
        assertEquals("new arrival", (window.items.last().body as MessageBody.Text).text)
        assertFalse(window.items.any { it.messageId == older.items.first().messageId })
        val next = fixture.engine.history(peer, before = floor)
        assertEquals("history-149", (next.items.last().body as MessageBody.Text).text)
    }

    @Test fun deletingEntireHistoryCannotHideNewMessagesWhenSQLiteReusesIds() = runBlocking {
        val peer = seed(350)
        val floor = fixture.engine.history(peer).oldest!!
        fixture.storage.write { tx ->
            val conversation = tx.conversations.forContact(peer)!!
            val ids = tx.messages.page(conversation.id, limit = 1000, now = fixture.clock.now()).map { it.messageId }
            tx.messages.deleteLocally(ids, conversation.id, fixture.clock.now())
            tx.messages.storeIncoming(textContent("after deletion").data, conversation.id, receivedAt = fixture.clock.now())
        }
        val window = fixture.engine.history(peer, through = floor)
        assertEquals(listOf(MessageBody.Text("after deletion")), window.items.map { it.body })
        assertFalse(window.hasOlder)
    }

    @Test fun expiredMessagesAreNeverReturnedByPagesOrWindowRefresh() = runBlocking {
        val peer = seed(120)
        val first = fixture.engine.history(peer)
        fixture.storage.write { tx ->
            val conversation = tx.conversations.forContact(peer)!!
            val message = textContent("expired").data.toBuilder().setExpireTimerSeconds(60).build()
            tx.messages.storeOutgoing(message, conversation.id, fixture.clock.now())
        }
        fixture.clock.advance(600.seconds)
        assertFalse(fixture.engine.history(peer, through = first.oldest).items.any { (it.body as? MessageBody.Text)?.text == "expired" })
        assertEquals(20, fixture.engine.history(peer, before = first.oldest).items.size)
    }
}
