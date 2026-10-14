package com.syconx.us2us.runtime.engine

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.runtime.support.eventually
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Blocking and unblocking (§6.4) beyond the drop and the key rotation (the iOS `BlockingTests`):
 * what a block leaves of messages still on their way, a ghost wipe of a blocked chat, an unblock
 * tapped twice, and the list behind 7.1 › Engellenenler.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BlockingTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: EngineFixture

    @Before
    fun setUp() {
        fixture = EngineFixture(temporary.newFolder())
    }

    @After
    fun tearDown() = runBlocking {
        fixture.engine.stop()
        fixture.close()
    }

    private val engine get() = fixture.engine

    private suspend fun enableGhost(peer: AccountId, leaveNoTrace: Boolean) =
        engine.updateConversationSettings(peer, GhostSettings(enabled = true, timeoutSeconds = 3600, wipeContact = leaveNoTrace), 0)

    /** The ghost deadline passes (nothing can arrive from someone blocked). */
    private suspend fun runGhostOut(peer: AccountId) = eventually(message = "wiped") {
        fixture.clock.advance(600.seconds)
        engine.serialized { engine.runDueWipes() }
        engine.conversationInfo(peer) == null
    }

    private suspend fun assertRefused(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            return
        }
        throw AssertionError("expected an error")
    }

    @Test
    fun messagesStillOnTheirWayWhenThePersonIsBlockedFail() = runBlocking {
        val peer = fixture.acceptedContact()
        // Offline: nothing leaves the outbox.
        val id = engine.send(OutgoingContent.Text("yarın görüşelim"), peer)
        assertEquals(OutgoingStatus.SENDING, fixture.messages(peer).first { it.messageId == id }.status)
        engine.block(peer)
        assertEquals(OutgoingStatus.FAILED, fixture.messages(peer).first { it.messageId == id }.status)
        assertTrue("nothing is sent to them", fixture.outbox().none { it.recipient == peer })
        assertRefused { engine.resend(id, peer) }
        // Unblocked: "Tekrar gönder" queues it again.
        engine.unblock(peer)
        engine.resend(id, peer)
        assertTrue(fixture.outbox().any { it.recipient == peer && it.messageId != null })
    }

    @Test
    fun aBlockedChatErasedByGhostComesBackFrom42StillBlocked() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.backend.register("deniz", peer)
        enableGhost(peer, leaveNoTrace = false)
        engine.block(peer)
        runGhostOut(peer)
        assertFalse("the chat went", engine.chatSummaries().any { it.id == peer })
        assertEquals("the block stays", ContactState.BLOCKED, fixture.contact(peer)?.requestState)

        assertEquals(peer, engine.startConversation(Username("deniz")))
        val chat = engine.chatSummaries().first { it.id == peer }
        assertEquals("read only, with Engeli kaldır", ContactState.BLOCKED, chat.contact.requestState)
        assertFalse(chat.isRequest)
        assertRefused { engine.send(OutgoingContent.Text("selam"), peer) }

        engine.unblock(peer)
        assertEquals(ContactState.ACCEPTED, fixture.contact(peer)?.requestState)
    }

    @Test
    fun afterLeaveNoTraceKeptOnlyTheBlockUnblockingFrom42MakesANewRequest() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.backend.register("deniz", peer)
        enableGhost(peer, leaveNoTrace = true)
        engine.block(peer)
        runGhostOut(peer)
        val stripped = fixture.contact(peer)!!
        assertTrue(stripped.isBlocked)
        assertNull("nothing but the block was kept", stripped.profile.username)

        engine.startConversation(Username("deniz"))
        val chat = engine.chatSummaries().first { it.id == peer }
        assertEquals(ContactState.BLOCKED, chat.contact.requestState)
        assertEquals("deniz", chat.contact.username)

        engine.unblock(peer)
        assertEquals("a new chat, not an empty request", ContactState.OUTGOING_REQUEST, fixture.contact(peer)?.requestState)
        assertTrue(engine.chatSummaries().any { it.id == peer && !it.isRequest })
    }

    @Test
    fun startingAChatKeepsTheStateBeforeTheBlock() = runBlocking {
        fixture.start()
        val stranger = AccountId(UUID.randomUUID())
        fixture.backend.register("kuzey_dev", stranger)
        fixture.receive(textContent("merhaba"), stranger, username = "kuzey_dev")
        engine.block(stranger)
        engine.startConversation(Username("kuzey_dev"))
        engine.unblock(stranger)
        assertEquals(ContactState.INCOMING_REQUEST, fixture.contact(stranger)?.requestState)
    }

    @Test
    fun aSecondUnblockSendsNeitherTheAccessKeyNorTheProfileAgain() = runBlocking {
        val peer = fixture.acceptedContact()
        engine.block(peer)
        engine.unblock(peer)
        val queued = fixture.outbox().filter { it.recipient == peer }
        assertEquals("the current access key and the profile", 2, queued.size)
        engine.unblock(peer)
        assertEquals(queued, fixture.outbox().filter { it.recipient == peer })
        assertEquals(ContactState.ACCEPTED, fixture.contact(peer)?.requestState)
    }

    // MARK: Engellenenler

    @Test
    fun everyoneBlockedIsListedAlsoOnceTheConversationIsGone() = runBlocking {
        val deniz = fixture.acceptedContact()
        val mert = fixture.acceptedContact()
        val other = fixture.acceptedContact()
        engine.block(deniz)
        engine.block(mert)
        engine.deleteConversationLocally(mert)
        assertFalse("mert's chat went", engine.chatSummaries().any { it.id == mert })
        val blocked = engine.blockedContacts()
        assertEquals(setOf(deniz, mert), blocked.map { it.account }.toSet())
        assertTrue(blocked.all { it.requestState == ContactState.BLOCKED && it.avatar == null })
        assertFalse(blocked.any { it.account == other })

        // Without a conversation: back as before the block, still without one.
        engine.unblock(mert)
        assertEquals(listOf(deniz), engine.blockedContacts().map { it.account })
        assertEquals(ContactState.ACCEPTED, fixture.contact(mert)?.requestState)
        assertEquals("the current access key and the profile", 2, fixture.outbox().count { it.recipient == mert })
        assertFalse(engine.chatSummaries().any { it.id == mert })
    }

    @Test
    fun aBlockLeaveNoTraceKeptAloneIsListedWithoutANameAndLeavesNoChatOnceUnblocked() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.backend.register("deniz", peer)
        enableGhost(peer, leaveNoTrace = true)
        engine.block(peer)
        runGhostOut(peer)
        val listed = engine.blockedContacts().firstOrNull()
        assertNotNull(listed)
        assertEquals(peer, listed!!.account)
        assertTrue("nothing but the block was kept", listed.username == null && listed.displayName == null)

        engine.unblock(peer)
        assertTrue(engine.blockedContacts().isEmpty())
        assertTrue(engine.chatSummaries().isEmpty())
    }

    /** Unblocked from 7.1 › Engellenenler without finding them in 4.2 first: nothing was left but the block, so nothing is left after it. */
    @Test
    fun aBareBlockUnblockedFromTheListIsAStrangerAgain() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.backend.register("deniz", peer)
        enableGhost(peer, leaveNoTrace = true)
        engine.block(peer)
        runGhostOut(peer)

        engine.unblock(peer)
        assertNull("forgotten, not a hidden request", fixture.contact(peer))
        engine.startConversation(Username("deniz"))
        val chat = engine.chatSummaries().first { it.id == peer }
        assertFalse("a new chat, not an empty request", chat.isRequest)
        assertEquals(ContactState.OUTGOING_REQUEST, fixture.contact(peer)?.requestState)
    }

    @Test
    fun aBlockedRequestWhoseConversationIsGoneIsForgottenWhenUnblocked() = runBlocking {
        fixture.start()
        val stranger = AccountId(UUID.randomUUID())
        fixture.backend.register("kuzey_dev", stranger)
        fixture.receive(textContent("merhaba"), stranger, username = "kuzey_dev")
        engine.block(stranger)
        engine.deleteConversationLocally(stranger)
        assertEquals(listOf(stranger), engine.blockedContacts().map { it.account })

        engine.unblock(stranger)
        assertNull(fixture.contact(stranger))
        assertTrue("no empty request", engine.chatSummaries().isEmpty())
        fixture.receive(textContent("tekrar merhaba"), stranger, username = "kuzey_dev")
        assertEquals(listOf(stranger), engine.chatSummaries().map { it.id })
        assertEquals(ContactState.INCOMING_REQUEST, fixture.contact(stranger)?.requestState)
    }
}
