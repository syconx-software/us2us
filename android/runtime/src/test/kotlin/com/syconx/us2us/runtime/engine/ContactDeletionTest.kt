package com.syconx.us2us.runtime.engine

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.contacts.UsernameStatus
import com.syconx.us2us.core.keys.Keys
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.runtime.storage.ContactIdentity
import com.syconx.us2us.runtime.storage.ContactProfile
import com.syconx.us2us.runtime.support.eventually
import com.syconx.us2us.runtime.support.settle
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
import kotlin.time.Duration.Companion.hours

/**
 * Deleting on this phone only (4.1 / the contact screen › “Sohbeti sil”, “Kişiyi sil”), a deleted
 * contact that writes again (a new request under its old name, §6.4), the request an accepted
 * contact sends after it deleted us and our ACCEPT back, and a username taken over by another
 * account after the first one was deleted (§3.3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContactDeletionTest {
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

    /** [peer] has a verified identity key here (the 6.1 seal). */
    private fun verify(peer: AccountId) {
        fixture.backend.setIdentity(peer, Keys.generateIdentityKeyPair().publicKey, ByteArray(30), changed = false)
        fixture.storage.protocolStorage.setIdentityVerified(true, peer)
        assertTrue(fixture.contact(peer)!!.isVerified)
    }

    @Test
    fun deletingAChatKeepsTheContactAndItsSession() = runBlocking {
        val peer = fixture.acceptedContact()
        verify(peer)
        engine.send(OutgoingContent.Text("yarın görüşelim"), peer)
        engine.deleteConversationLocally(peer)
        assertNull("the chat went", engine.conversationInfo(peer))
        assertTrue(engine.messages(peer).isEmpty())
        assertTrue("its pending send went with it", fixture.outbox().none { it.recipient == peer && it.messageId != null })
        val contact = fixture.contact(peer)!!
        assertEquals(ContactState.ACCEPTED, contact.requestState)
        assertTrue("the identity and its verification stay", contact.isVerified)
    }

    @Test
    fun deletingAChatStillStopsOurLiveLocationOnTheOtherPhone() = runBlocking {
        val peer = fixture.acceptedContact()
        val session = engine.startLiveLocation(peer, 41.0, 29.0, 5f, 1.hours)
        assertEquals(listOf(session), engine.activeLiveLocationSessions())

        engine.deleteConversationLocally(peer)
        val queued = fixture.outbox().filter { it.recipient == peer }
        assertEquals("only the stop is left for the contact", 1, queued.size)
        val stop = queued.single()
        assertNull("it goes without the shredded conversation", stop.conversationId)
        assertNull("not stored as a message", stop.messageId)
        val live = stop.content.data.liveLocation
        assertTrue(live.stopped)
        assertEquals(session, live.sessionId)
        assertTrue(engine.activeLiveLocationSessions().isEmpty())
    }

    @Test
    fun deletingTheChatOfABlockedContactOrAContactSendsNoStop() = runBlocking {
        val blocked = fixture.acceptedContact()
        engine.startLiveLocation(blocked, 41.0, 29.0, 5f, 1.hours)
        engine.block(blocked)
        engine.deleteConversationLocally(blocked)
        assertTrue(fixture.outbox().none { it.recipient == blocked })

        val deleted = fixture.acceptedContact()
        engine.startLiveLocation(deleted, 41.0, 29.0, 5f, 1.hours)
        engine.deleteContact(deleted)
        assertTrue(fixture.outbox().none { it.recipient == deleted })
        assertTrue(engine.activeLiveLocationSessions().isEmpty())
    }

    @Test
    fun deletingAContactKeepsOnlyItsSessionAndTheUsernameStartsAFreshRequest() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.backend.register("deniz", peer)
        verify(peer)
        engine.send(OutgoingContent.Text("yarın görüşelim"), peer)

        engine.deleteContact(peer)
        assertNull("the contact went", fixture.contact(peer))
        assertNull("the chat went", engine.conversationInfo(peer))
        assertTrue(fixture.outbox().none { it.recipient == peer })
        assertTrue(
            "session and identity stay (not blocked, §6.4)",
            fixture.storage.protocolStorage.identityTrust(peer) is ContactIdentity.Trusted,
        )
        assertFalse(engine.chatSummaries().any { it.id == peer })

        assertEquals(peer, engine.startConversation(Username("deniz")))
        val fresh = fixture.contact(peer)!!
        assertEquals("a new request, not the old contact", ContactState.OUTGOING_REQUEST, fresh.requestState)
        assertNull("no key exchanged yet", fresh.theirAccessKey)
        assertNull(fresh.ourAccessKeySent)
        assertNull(fresh.profileSentAt)
        assertNull(fresh.profile.displayName)
        assertTrue("no old message", engine.messages(peer).isEmpty())
    }

    /** An accepted contact shown as “deniz”, deleted with “Kişiyi sil”. */
    private suspend fun deletedContact(): AccountId {
        val peer = fixture.acceptedContact()
        fixture.backend.register("deniz", peer)
        fixture.storage.write { tx ->
            tx.contacts.setProfile(ContactProfile(username = Username("deniz"), usernameStatus = UsernameStatus.VERIFIED, displayName = "Deniz"), peer)
        }
        fixture.start()
        fixture.receive(textContent("eski mesaj"), peer)
        engine.deleteContact(peer)
        assertNull(fixture.contact(peer))
        return peer
    }

    @Test
    fun aDeletedContactWritingAgainAppearsAsARequestUnderItsNameAndAcceptingContinues() = runBlocking {
        val peer = deletedContact()
        // It still holds our access key and its session and is accepted on its phone: no `sender_username`.
        fixture.receive(textContent("yine ben"), peer)
        val contact = fixture.contact(peer)!!
        assertEquals(ContactState.INCOMING_REQUEST, contact.requestState)
        val summary = engine.chatSummaries().single { it.id == peer }
        assertTrue("a new request in 4.1", summary.isRequest)
        assertEquals("under the name it was shown under", "deniz", summary.contact.username)
        assertFalse("only a claim until checked", summary.contact.usernameVerified)
        assertNull("its old profile is gone", summary.contact.displayName)
        assertEquals("only the new message", listOf("yine ben"), engine.messages(peer).mapNotNull { (it.body as? MessageBody.Text)?.text })
        assertTrue("a request is never answered", fixture.backend.sent(peer).isEmpty())

        // Opening it checks the kept name against the directory (§3.3).
        engine.verifyUsernameIfNeeded(peer)
        assertEquals(UsernameStatus.VERIFIED, fixture.contact(peer)!!.profile.usernameStatus)

        engine.acceptRequest(peer)
        assertEquals(ContactState.ACCEPTED, fixture.contact(peer)!!.requestState)
        eventually(message = "ACCEPT and our access key") {
            val sent = fixture.backend.sent(peer)
            sent.any { it.isAccept } && sent.any { it.isAccessKeyUpdate }
        }
        engine.send(OutgoingContent.Text("hoş geldin"), peer)
        eventually(message = "the conversation continues") {
            fixture.backend.sent(peer).any { it.data?.text?.body == "hoş geldin" }
        }
    }

    @Test
    fun acceptFromAnAcceptedContactSendsOurKeyAndProfileAgainOnce() = runBlocking {
        // We are the side that was deleted: it accepted our message as a new request.
        val peer = fixture.acceptedContact()
        val incomplete = fixture.acceptedContact()
        fixture.storage.write { it.contacts.setProfileSent(null, incomplete) }
        fixture.start()
        fixture.receive(acceptContent(), peer)
        eventually(message = "our access key and profile again") {
            val sent = fixture.backend.sent(peer)
            sent.any { it.isAccessKeyUpdate } && sent.any { it.isProfile }
        }
        settle()
        val sent = fixture.backend.sent(peer)
        assertEquals(1, sent.count { it.isAccessKeyUpdate })
        assertEquals(1, sent.count { it.isProfile })
        assertTrue("the key first", sent.indexOfFirst { it.isAccessKeyUpdate } < sent.indexOfFirst { it.isProfile })
        assertFalse("no ACCEPT back", sent.any { it.isAccept })
        assertEquals("nothing changes here", ContactState.ACCEPTED, fixture.contact(peer)?.requestState)
        assertTrue("no notice", engine.messages(peer).none { it.body is MessageBody.Notice })

        // While our own exchange is still under way, an ACCEPT adds nothing.
        fixture.receive(acceptContent(), incomplete)
        settle()
        assertTrue(fixture.backend.sent(incomplete).none { it.isAccessKeyUpdate || it.isProfile })
    }

    @Test
    fun aDeletedBlockedContactWritingStaysBlocked() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        engine.block(peer)
        engine.deleteContact(peer)
        fixture.receive(textContent("beni engelleme"), peer)
        fixture.receive(textContent("lütfen"), peer, username = "deniz")
        assertEquals(ContactState.BLOCKED, fixture.contact(peer)?.requestState)
        assertNull("nothing stored", engine.conversationInfo(peer))
        assertFalse(engine.chatSummaries().any { it.id == peer })
        assertEquals(listOf(peer), engine.blockedContacts().map { it.account })
        assertTrue(fixture.backend.sent(peer).none { it.isAccept })
    }

    @Test
    fun aDeletedContactReturningUnderANewIdentityIsFlagged() = runBlocking {
        val peer = deletedContact()
        // The kept identity makes a new key a change, not a first contact (§6.3 step 5).
        fixture.backend.setIdentity(peer, Keys.generateIdentityKeyPair().publicKey, ByteArray(30), changed = true)
        fixture.receive(textContent("yeni telefonum"), peer, identityChanged = true)
        assertEquals(ContactState.INCOMING_REQUEST, fixture.contact(peer)?.requestState)
        assertTrue(fixture.contact(peer)!!.identity is ContactIdentity.Changed)
        assertTrue(engine.chatSummaries().single { it.id == peer }.contact.safetyNumberChanged)
        assertTrue(engine.messages(peer).any { it.body == MessageBody.Notice(SystemNotice.SafetyNumberChanged) })
    }

    @Test
    fun deletingABlockedContactKeepsTheBlockAndNothingElse() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.storage.write { tx ->
            tx.contacts.setProfile(ContactProfile(username = Username("deniz"), usernameStatus = UsernameStatus.VERIFIED, displayName = "Deniz"), peer)
        }
        engine.block(peer)
        engine.deleteContact(peer)
        val kept = fixture.contact(peer)!!
        assertEquals("deleting is not unblocking", ContactState.BLOCKED, kept.requestState)
        assertNull("nothing but the block is kept", kept.profile.username)
        assertNull(kept.profile.displayName)
        assertNull(engine.conversationInfo(peer))
        assertEquals(ContactIdentity.Unknown, fixture.storage.protocolStorage.identityTrust(peer))
        assertEquals("still in Engellenenler", listOf(peer), engine.blockedContacts().map { it.account })
    }

    @Test
    fun deletingAnUnknownContactIsRefused() = runBlocking {
        try {
            engine.deleteContact(AccountId(UUID.randomUUID()))
            throw AssertionError("expected an error")
        } catch (e: EngineException) {
            assertEquals(EngineError.UNKNOWN_CONTACT, e.error)
        }
    }

    @Test
    fun anAcceptedContactWritingAsANewRequestGetsOurAcceptAgain() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.backend.register("deniz", peer)
        fixture.start()
        // An ordinary message from the contact: nothing to answer.
        fixture.receive(textContent("selam"), peer)
        settle()
        assertFalse(fixture.backend.sent(peer).any { it.isAccept })

        // It deleted us and wrote again: a request carries `sender_username`.
        fixture.receive(textContent("yeniden selam"), peer, username = "deniz")
        assertEquals("nothing changes here", ContactState.ACCEPTED, fixture.contact(peer)?.requestState)
        eventually(message = "ACCEPT, the access key and the profile") {
            val sent = fixture.backend.sent(peer)
            sent.any { it.isAccept } && sent.any { it.isAccessKeyUpdate } && sent.any { it.isProfile }
        }
        val sent = fixture.backend.sent(peer)
        assertTrue("ACCEPT goes first", sent.indexOfFirst { it.isAccept } < sent.indexOfFirst { it.isAccessKeyUpdate })
        assertEquals("the message is shown", listOf("selam", "yeniden selam"), engine.messages(peer).mapNotNull { (it.body as? MessageBody.Text)?.text })
    }

    @Test
    fun aUsernameTakenOverByAnotherAccountOpensTheNewAccountAndStopsNamingTheOldOne() = runBlocking {
        val old = fixture.acceptedContact()
        fixture.storage.write { tx ->
            tx.contacts.setProfile(ContactProfile(username = Username("deniz"), usernameStatus = UsernameStatus.VERIFIED), old)
        }
        assertEquals("deniz", engine.contactInfo(old)?.username)
        // The old account was deleted and someone else registered the name.
        val new = AccountId(UUID.randomUUID())
        fixture.backend.register("deniz", new)

        assertEquals("the account the directory answered with", new, engine.startConversation(Username("deniz")))
        assertEquals(ContactState.OUTGOING_REQUEST, fixture.contact(new)?.requestState)
        assertEquals(UsernameStatus.MISMATCH, fixture.contact(old)!!.profile.usernameStatus)
        val chats = engine.chatSummaries()
        assertNull("the old chat no longer shows the name", chats.first { it.id == old }.contact.username)
        assertEquals("deniz", chats.first { it.id == new }.contact.username)
        assertEquals("the old chat stays", ContactState.ACCEPTED, fixture.contact(old)?.requestState)
    }
}
