package com.syconx.us2us.runtime.engine

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.keys.Keys
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.runtime.storage.ContactIdentity
import com.syconx.us2us.runtime.storage.IdentityVerification
import com.syconx.us2us.runtime.storage.IdentityVerificationMethod
import com.syconx.us2us.runtime.support.eventually
import com.syconx.us2us.runtime.support.settle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

/**
 * Audit 2026-10-28: message-request preconditions (M-9), contact-code add without a second lookup
 * (M-10) and the access-key rotation on "Kişiyi sil" (L-2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RequestAndContactCodeTest {
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
    private val backend get() = fixture.backend

    private fun stranger() = AccountId(UUID.randomUUID())

    private fun requestSent(peer: AccountId): Boolean = fixture.storage.read { it.contacts.requestSent(peer) }

    // MARK: M-9

    @Test
    fun openingAHandleAloneCannotBeAcceptedByItsOwner() = runBlocking {
        fixture.start()
        val owner = stranger()
        backend.register("handle", owner)
        engine.startConversation(Username("handle"))
        assertEquals(ContactState.OUTGOING_REQUEST, fixture.contact(owner)?.requestState)
        assertFalse(requestSent(owner))

        // The handle's owner answers a request we never sent: nothing changes, nothing goes out.
        fixture.receive(acceptContent(), owner)
        settle()
        assertEquals(ContactState.OUTGOING_REQUEST, fixture.contact(owner)?.requestState)
        assertTrue("no ACCEPT, key, profile, receipt or settings", backend.sent(owner).isEmpty())
        assertTrue(fixture.outbox().none { it.recipient == owner })
    }

    @Test
    fun aMessageToAnOpenedButUnsentHandleIsItsOwnRequestNotACrossingOne() = runBlocking {
        fixture.start()
        val owner = stranger()
        backend.register("handle", owner)
        engine.startConversation(Username("handle"))

        fixture.receive(textContent("merhaba yabancı"), owner, username = "handle")
        assertEquals(ContactState.INCOMING_REQUEST, fixture.contact(owner)?.requestState)
        settle()
        assertTrue("our phone answers nothing until the user accepts", backend.sent(owner).isEmpty())
        assertTrue(engine.chatSummaries().single { it.id == owner }.isRequest)
        // Invisible content from it is ignored as a stranger's would be.
        val other = stranger()
        backend.register("other", other)
        engine.startConversation(Username("other"))
        fixture.receive(typingContent(true), other)
        fixture.receive(receiptContent(com.syconx.us2us.core.proto.ReceiptMessage.Type.READ, listOf(com.syconx.us2us.runtime.storage.RandomIds.make())), other)
        assertEquals(ContactState.OUTGOING_REQUEST, fixture.contact(other)?.requestState)
    }

    @Test
    fun theRequestCountsOnceTheRelayTookAVisibleMessage() = runBlocking {
        fixture.start()
        val bob = stranger()
        backend.register("bob_99", bob)
        engine.startConversation(Username("bob_99"))
        engine.send(OutgoingContent.Text("hey bob"), bob)
        eventually(message = "sent and recorded") { requestSent(bob) }
        fixture.receive(acceptContent(), bob)
        assertEquals(ContactState.ACCEPTED, fixture.contact(bob)?.requestState)
    }

    @Test
    fun aCrossingMessageWithoutSenderUsernameIsNoAcceptance() = runBlocking {
        fixture.start()
        val bob = stranger()
        backend.register("bob_99", bob)
        engine.startConversation(Username("bob_99"))
        engine.send(OutgoingContent.Text("hey bob"), bob)
        eventually { requestSent(bob) }
        // Only an OUTGOING_REQUEST carries `sender_username` (§6.4): without it this is not a crossing request.
        fixture.receive(textContent("hey you"), bob)
        assertEquals(ContactState.OUTGOING_REQUEST, fixture.contact(bob)?.requestState)
        settle()
        assertFalse(backend.sent(bob).any { it.isAccept })
    }

    @Test
    fun theReacceptRuleFiresEveryTime() = runBlocking {
        val peer = fixture.acceptedContact()
        backend.register("deniz", peer)
        fixture.start()
        repeat(2) { round ->
            fixture.receive(textContent("yeniden $round"), peer, username = "deniz")
            eventually(message = "ACCEPT number ${round + 1}") { backend.sent(peer).count { it.isAccept } == round + 1 }
        }
        assertEquals(ContactState.ACCEPTED, fixture.contact(peer)?.requestState)
    }

    // MARK: M-10

    private fun entry(account: AccountId, key: ByteArray) = DirectoryEntry(account, key)

    @Test
    fun aContactCodeCreatesTheCheckedAccountWithoutASecondLookupAndPinsItsKey() = runBlocking {
        fixture.start()
        val peer = stranger()
        val key = Keys.generateIdentityKeyPair().publicKey
        // Not in the fake directory at all: any second lookup would fail.
        assertTrue(engine.addContactFromCode(Username("deniz"), entry(peer, key.serialize()), pinKey = true))
        val contact = fixture.contact(peer)!!
        assertEquals(ContactState.OUTGOING_REQUEST, contact.requestState)
        assertEquals(ContactIdentity.Trusted(key, true), fixture.storage.protocolStorage.identityTrust(peer))
        assertEquals(IdentityVerificationMethod.QR_CODE, fixture.storage.protocolStorage.identityVerification(peer)?.method)
        assertEquals("deniz", engine.contactInfo(peer)?.username)
    }

    @Test
    fun aContactCodeForAnAccountHoldingAnotherKeyAddsNothing() = runBlocking {
        fixture.start()
        val peer = stranger()
        val stored = Keys.generateIdentityKeyPair().publicKey
        fixture.storage.protocolStorage.pinVerifiedIdentity(stored, peer, IdentityVerification(IdentityVerificationMethod.MANUAL, Instant.now()))
        val other = Keys.generateIdentityKeyPair().publicKey
        assertFalse(engine.addContactFromCode(Username("deniz"), entry(peer, other.serialize()), pinKey = true))
        assertNull("§5: a mismatch must not add the contact", fixture.contact(peer))
        assertTrue(engine.chatSummaries().none { it.id == peer })
        assertEquals(ContactIdentity.Trusted(stored, true), fixture.storage.protocolStorage.identityTrust(peer))
    }

    @Test
    fun aContactCodeWithoutAFingerprintAddsUnverified() = runBlocking {
        fixture.start()
        val peer = stranger()
        assertTrue(engine.addContactFromCode(Username("deniz"), entry(peer, ByteArray(33)), pinKey = false))
        assertEquals(ContactState.OUTGOING_REQUEST, fixture.contact(peer)?.requestState)
        assertEquals(ContactIdentity.Unknown, fixture.storage.protocolStorage.identityTrust(peer))
    }

    // MARK: L-2

    @Test
    fun deletingAContactThatHeldOurKeyRotatesItForTheOthers() = runBlocking {
        val friend = fixture.acceptedContact()
        val deleted = fixture.acceptedContact()
        fixture.start()
        engine.deleteContact(deleted)
        eventually(message = "the friend gets the new key") { backend.sent(friend).any { it.isAccessKeyUpdate } }
        assertEquals(1, backend.rotations)
        assertEquals(backend.currentAccessKey(), backend.sent(friend).first { it.isAccessKeyUpdate }.control!!.accessKey.accessKey)
        assertTrue("nothing goes to the deleted contact", backend.sent(deleted).isEmpty())
        // It can still write: as a new request (with proof-of-work on the relay).
        fixture.receive(textContent("yine ben"), deleted)
        assertEquals(ContactState.INCOMING_REQUEST, fixture.contact(deleted)?.requestState)
    }

    @Test
    fun deletingARequestThatNeverGotOurKeyRotatesNothing() = runBlocking {
        fixture.start()
        val requester = stranger()
        fixture.receive(textContent("selam"), requester)
        engine.deleteContact(requester)
        settle()
        assertEquals(0, backend.rotations)
    }
}
