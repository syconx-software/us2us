package com.syconx.us2us.runtime.engine

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.contacts.UsernameStatus
import com.syconx.us2us.core.keys.Keys
import com.syconx.us2us.core.presence.PresenceStatus
import com.syconx.us2us.core.proto.ConversationWipe
import com.syconx.us2us.core.proto.PresenceMessage
import com.syconx.us2us.core.proto.ReceiptMessage
import com.syconx.us2us.core.proto.conversationSettings
import com.syconx.us2us.core.proto.conversationWipe
import com.syconx.us2us.core.proto.delete
import com.syconx.us2us.core.proto.reaction
import com.syconx.us2us.core.proto.screenshotNotice
import com.syconx.us2us.core.relay.RelayErrorCode
import com.syconx.us2us.core.relay.RelayException
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.runtime.media.PreparedMedia
import com.syconx.us2us.runtime.storage.RandomIds
import com.syconx.us2us.runtime.support.eventually
import com.syconx.us2us.runtime.support.settle
import com.syconx.us2us.runtime.vault.SecretKeyMaterial
import com.syconx.us2us.runtime.vault.SecretKeyWipedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** The iOS `MessagingEngineTests`, case by case, on the storage and a fake backend. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessagingEngineTest {
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

    private suspend inline fun <reified E : Throwable> assertThrows(block: suspend () -> Unit): E {
        try {
            block()
        } catch (e: Throwable) {
            if (e is E) return e
            throw AssertionError("expected ${E::class.java.simpleName}, got $e", e)
        }
        throw AssertionError("expected ${E::class.java.simpleName}")
    }

    private suspend fun assertEngineError(error: EngineError, block: suspend () -> Unit) {
        val thrown = assertThrows<EngineException>(block)
        assertEquals(error, thrown.error)
    }

    // MARK: Inbound

    @Test
    fun aStrangersFirstMessageOpensARequest() = runBlocking {
        fixture.start()
        val stranger = stranger()
        fixture.receive(textContent("merhaba"), stranger, username = "kuzey_dev")
        val contact = fixture.contact(stranger)!!
        assertEquals(ContactState.INCOMING_REQUEST, contact.requestState)
        assertEquals("kuzey_dev", contact.profile.username?.value)
        assertEquals(UsernameStatus.VERIFIED, contact.profile.usernameStatus)
        val messages = fixture.messages(stranger)
        assertEquals(1, messages.size)
        assertEquals(MessageBody.Text("merhaba"), messages[0].body)
        // No delivery receipt for a request (§8.4).
        assertTrue(fixture.outbox().isEmpty())
        assertTrue(engine.chatSummaries().first().isRequest)
    }

    @Test
    fun strangersCannotOpenRequestsWithInvisibleContent() = runBlocking {
        fixture.start()
        val stranger = stranger()
        fixture.receive(receiptContent(ReceiptMessage.Type.DELIVERY, listOf(RandomIds.make())), stranger)
        fixture.receive(presenceContent(PresenceMessage.State.ONLINE), stranger)
        fixture.receive(typingContent(true), stranger)
        fixture.receive(accessKeyContent(ByteString.copyFrom(ByteArray(16) { 1 })), stranger)
        assertNull(fixture.contact(stranger))
        assertTrue(engine.typingContacts(fixture.clock.now()).isEmpty())
    }

    @Test
    fun blockedContactsAreDroppedLocally() = runBlocking {
        fixture.start()
        val peer = fixture.acceptedContact()
        engine.block(peer)
        fixture.receive(textContent("still there?"), peer)
        assertTrue(fixture.messages(peer).isEmpty())
    }

    @Test
    fun deliveryReceiptsAreAlwaysSentAndMerged() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        val ids = List(3) { RandomIds.make() }
        for (id in ids) fixture.receive(textContent("hi", id), peer)
        eventually(message = "receipt sent") { backend.sent(peer).any { it.receipt != null } }
        eventually(message = "outbox drained") { fixture.outbox().isEmpty() }
        val receipts = backend.sent(peer).mapNotNull { it.receipt }
        assertTrue(receipts.all { it.type == ReceiptMessage.Type.DELIVERY })
        assertEquals(ids, receipts.flatMap { it.messageIdsList })
        // One ReceiptMessage for the backlog, not one per message (§1.1, §8.4).
        assertTrue(receipts.size <= 2)
    }

    @Test
    fun duplicateRelayIdsAndMessageIdsAreIgnored() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        val id = RandomIds.make()
        fixture.receive(textContent("once", id), peer, relayId = "r1")
        fixture.receive(textContent("once", id), peer, relayId = "r1")
        fixture.receive(textContent("once", id), peer, relayId = "r2")
        assertEquals(1, fixture.messages(peer).size)
    }

    @Test
    fun reactionsDeletesAndQuotes() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        val target = RandomIds.make()
        fixture.receive(textContent("photo?", target), peer)
        fixture.receive(dataContent { reaction = reaction { targetMessageId = target; emoji = "❤️" } }, peer)
        assertEquals("❤️", fixture.messages(peer).first().reactions[peer])
        val reply = engine.send(OutgoingContent.Text("evet"), peer, QuoteInfo(target, "ignored"))
        val replied = fixture.messages(peer).first { it.messageId == reply }
        assertEquals("photo?", replied.quote?.previewText)
        fixture.receive(dataContent { delete = delete { targetMessageId = target } }, peer)
        assertEquals(MessageBody.Deleted, fixture.messages(peer).first().body)
        // The quoted text goes with its target.
        assertEquals("", fixture.messages(peer).first { it.messageId == reply }.quote?.previewText)
    }

    @Test
    fun settingsAreLastWriterWinsWithASystemLine() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        val stamp = fixture.clock.now().toEpochMilli()
        fixture.receive(
            controlContent { settings = conversationSettings { updatedAt = stamp; ghost = GhostSettings(true, 3600).proto } },
            peer,
        )
        val conversation = engine.conversationInfo(peer)!!
        assertEquals(GhostSettings(true, 3600), conversation.ghost)
        assertNotNull(conversation.ghostDeadline)
        val notice = (fixture.messages(peer).last().body as MessageBody.Notice).notice as SystemNotice.SettingsChanged
        assertTrue(notice.change.ghost.enabled)
        // An older write loses.
        fixture.receive(controlContent { settings = conversationSettings { updatedAt = stamp - 10 } }, peer)
        assertEquals(true, engine.conversationInfo(peer)?.ghost?.enabled)
    }

    @Test
    fun aRequestCannotSetSettings() = runBlocking {
        fixture.start()
        val stranger = stranger()
        fixture.receive(textContent("selam"), stranger)
        fixture.receive(
            controlContent { settings = conversationSettings { updatedAt = Long.MAX_VALUE; ghost = GhostSettings(true, 60).proto } },
            stranger,
        )
        assertNull(engine.conversationInfo(stranger)?.ghost)
    }

    @Test
    fun screenshotNoticesFromTheContact() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        fixture.receive(controlContent { screenshot = screenshotNotice { takenAt = 1 } }, peer)
        val last = fixture.messages(peer).last()
        assertEquals(MessageBody.Notice(SystemNotice.ScreenshotByContact), last.body)
        assertEquals(MessageDirection.SYSTEM, last.direction)
    }

    @Test
    fun aReceivedWipeDeletesTheConversation() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        fixture.receive(textContent("secret"), peer)
        fixture.receive(controlContent { wipe = conversationWipe { reason = ConversationWipe.Reason.USER_REQUEST } }, peer)
        assertTrue(fixture.messages(peer).isEmpty())
        assertNull(engine.conversationInfo(peer))
        // A received wipe is never answered.
        eventually { fixture.outbox().isEmpty() }
        assertFalse(backend.sent(peer).any { it.control?.hasWipe() == true })
    }

    @Test
    fun safetyNumberChangeHoldsSendsAndAddsALine() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        val shown = "the new number".toByteArray()
        backend.setIdentity(peer, Keys.generateIdentityKeyPair().publicKey, shown, changed = true)
        fixture.receive(textContent("new phone"), peer, identityChanged = true)
        assertTrue(fixture.messages(peer).any { it.body == MessageBody.Notice(SystemNotice.SafetyNumberChanged) })
        engine.send(OutgoingContent.Text("are you sure?"), peer)
        settle()
        // The receipt and the message wait until the user accepts the new number.
        assertTrue(backend.sent(peer).isEmpty())
        assertFalse(engine.acceptSafetyNumberChange(peer, "another number".toByteArray()))
        assertTrue(engine.acceptSafetyNumberChange(peer, shown))
        eventually(message = "sent after accepting") { backend.sent(peer).any { it.data?.text?.body == "are you sure?" } }
        assertTrue(fixture.messages(peer).any { it.body == MessageBody.Notice(SystemNotice.SafetyNumberAcceptedUnverified) })
    }

    // MARK: Message requests (§6.4)

    @Test
    fun acceptingSendsAcceptThenKeyThenProfile() = runBlocking {
        fixture.start()
        val requester = stranger()
        fixture.receive(textContent("selam"), requester, username = "mert_k")
        engine.acceptRequest(requester)
        assertEquals(ContactState.ACCEPTED, fixture.contact(requester)?.requestState)
        eventually { backend.sent(requester).size >= 3 }
        val sent = backend.sent(requester)
        assertTrue(sent[0].isAccept)
        assertEquals(ContactState.INCOMING_REQUEST, sent[0].requestState)
        assertTrue(sent.any { it.isAccessKeyUpdate })
        // The request's message gets its delivery receipt now.
        assertTrue(sent.any { it.receipt?.type == ReceiptMessage.Type.DELIVERY })
        // No profile until their key arrives.
        assertFalse(sent.any { it.isProfile })
        val theirKey = ByteString.copyFrom(ByteArray(16) { 4 })
        fixture.receive(accessKeyContent(theirKey), requester)
        eventually(message = "profile after the exchange") { backend.sent(requester).any { it.isProfile } }
        // From now on with their access key, not proof-of-work.
        engine.send(OutgoingContent.Text("hoş geldin"), requester)
        eventually { backend.sent(requester).any { it.data?.text?.body == "hoş geldin" } }
        assertEquals(theirKey, backend.sent(requester).last { it.data != null }.accessKey)
        assertTrue(fixture.messages(requester).any { it.body == MessageBody.Notice(SystemNotice.RequestAccepted) })
    }

    @Test
    fun aFailedAcceptChangesNothing() = runBlocking {
        fixture.start()
        val requester = stranger()
        fixture.receive(textContent("selam"), requester)
        backend.failNextSend(requester, IOException("offline"))
        assertThrows<IOException> { engine.acceptRequest(requester) }
        assertEquals(ContactState.INCOMING_REQUEST, fixture.contact(requester)?.requestState)
        engine.acceptRequest(requester)
        assertEquals(ContactState.ACCEPTED, fixture.contact(requester)?.requestState)
    }

    @Test
    fun answeringBeforeAcceptingIsRefused() = runBlocking {
        fixture.start()
        val requester = stranger()
        fixture.receive(textContent("selam"), requester)
        assertEngineError(EngineError.NOT_ALLOWED) { engine.send(OutgoingContent.Text("hi"), requester) }
    }

    @Test
    fun decliningDeletesSilently() = runBlocking {
        fixture.start()
        val requester = stranger()
        fixture.receive(textContent("tebrikler!"), requester)
        engine.declineRequest(requester)
        assertNull(fixture.contact(requester))
        assertTrue(engine.chatSummaries().isEmpty())
        settle()
        assertTrue(backend.sent.isEmpty())
    }

    @Test
    fun outgoingRequestFlow() = runBlocking {
        fixture.start()
        val bob = stranger()
        backend.register("bob_99", bob)
        assertEquals(bob, engine.startConversation(Username("bob_99")))
        assertEquals(ContactState.OUTGOING_REQUEST, fixture.contact(bob)?.requestState)
        engine.send(OutgoingContent.Text("hey bob"), bob)
        eventually { backend.sent(bob).isNotEmpty() }
        assertEquals(ContactState.OUTGOING_REQUEST, backend.sent(bob)[0].requestState)
        // Only a request the relay took can be accepted (M-9).
        eventually { fixture.storage.read { it.contacts.requestSent(bob) } }
        // Bob accepts and sends his key.
        fixture.receive(acceptContent(), bob)
        assertEquals(ContactState.ACCEPTED, fixture.contact(bob)?.requestState)
        fixture.receive(accessKeyContent(ByteString.copyFrom(ByteArray(16) { 6 })), bob)
        eventually(message = "our key and profile") {
            val sent = backend.sent(bob)
            sent.any { it.isAccessKeyUpdate } && sent.any { it.isProfile }
        }
        assertTrue(fixture.messages(bob).any { it.body == MessageBody.Notice(SystemNotice.RequestAccepted) })
        assertEngineError(EngineError.USERNAME_NOT_FOUND) { engine.startConversation(Username("nobody")) }
    }

    @Test
    fun crossingRequestsBecomeAccepted() = runBlocking {
        fixture.start()
        val bob = stranger()
        backend.register("bob_99", bob)
        engine.startConversation(Username("bob_99"))
        engine.send(OutgoingContent.Text("hey bob"), bob)
        eventually { fixture.storage.read { it.contacts.requestSent(bob) } }
        // Bob wrote first too: his request carries his username.
        fixture.receive(textContent("hey you"), bob, username = "bob_99")
        assertEquals(ContactState.ACCEPTED, fixture.contact(bob)?.requestState)
        eventually { backend.sent(bob).any { it.isAccept } && backend.sent(bob).any { it.isAccessKeyUpdate } }
    }

    @Test
    fun blockingRotatesTheAccessKeyForEveryoneElse() = runBlocking {
        val friend = fixture.acceptedContact()
        val pest = fixture.acceptedContact()
        fixture.start()
        engine.block(pest)
        assertEquals(ContactState.BLOCKED, fixture.contact(pest)?.requestState)
        eventually { backend.sent(friend).any { it.isAccessKeyUpdate } }
        assertEquals(1, backend.rotations)
        val update = backend.sent(friend).first { it.isAccessKeyUpdate }
        assertEquals(backend.currentAccessKey(), update.control!!.accessKey.accessKey)
        assertFalse(backend.sent(pest).any { it.isAccessKeyUpdate })
    }

    @Test
    fun startingAChatWithABlockedAccountKeepsItBlocked() = runBlocking {
        fixture.start()
        val pest = stranger()
        backend.register("pest", pest)
        engine.block(pest)
        engine.startConversation(Username("pest"))
        assertEquals(ContactState.BLOCKED, fixture.contact(pest)?.requestState)
        engine.unblock(pest)
        assertEquals(ContactState.OUTGOING_REQUEST, fixture.contact(pest)?.requestState)
    }

    // MARK: Outbox

    @Test
    fun messagesGoOutInOrderPerContactDespiteRetries() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        backend.failNextSend(peer, IOException("timed out"))
        val first = engine.send(OutgoingContent.Text("1"), peer)
        val second = engine.send(OutgoingContent.Text("2"), peer)
        settle()
        assertTrue(backend.sent(peer).isEmpty())
        assertEquals(listOf(OutgoingStatus.SENDING, OutgoingStatus.SENDING), fixture.messages(peer).map { it.status })
        eventually {
            fixture.clock.advance(1.seconds)
            backend.sent(peer).size == 2
        }
        assertEquals(listOf(first, second), backend.sent(peer).mapNotNull { it.data?.messageId })
        eventually { fixture.messages(peer).map { it.status } == listOf(OutgoingStatus.SENT, OutgoingStatus.SENT) }
    }

    @Test
    fun permanentFailuresAreMarkedAndCanBeRetried() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        backend.failNextSend(peer, RelayException(404, RelayErrorCode.NOT_FOUND, "gone"))
        val id = engine.send(OutgoingContent.Text("gone?"), peer)
        eventually { fixture.messages(peer).first().status == OutgoingStatus.FAILED }
        engine.resend(id, peer)
        eventually { fixture.messages(peer).first().status == OutgoingStatus.SENT }
    }

    @Test
    fun aRotatedAccessKeyFallsBackToProofOfWork() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        backend.failNextSend(peer, RelayException(403, RelayErrorCode.ACCESS_DENIED, "denied"))
        engine.send(OutgoingContent.Text("knock"), peer)
        eventually {
            fixture.clock.advance(1.seconds)
            backend.sent(peer).isNotEmpty()
        }
        assertNull(backend.sent(peer)[0].accessKey)
        assertNull(fixture.contact(peer)?.theirAccessKey)
    }

    @Test
    fun receiptsReadAndDisappearing() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        val id = RandomIds.make()
        fixture.receive(textContent("kapı kodu 4471", id, expire = 300), peer)
        var item = fixture.messages(peer).first()
        assertNull(item.expiresAt)
        assertFalse(item.isCopyable)
        engine.markDisplayed(peer)
        item = fixture.messages(peer).first()
        // §8.1: T seconds after first display.
        assertEquals(fixture.clock.now().plusSeconds(300), item.expiresAt)
        eventually(message = "read receipt") { backend.sent(peer).any { it.receipt?.type == ReceiptMessage.Type.READ } }
        fixture.clock.advance(300.seconds)
        eventually(message = "expired") {
            fixture.clock.advance(1.seconds)
            fixture.messages(peer).isEmpty()
        }
    }

    @Test
    fun readReceiptsCanBeTurnedOff() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        engine.updateUserSettings { it.copy(readReceipts = false) }
        fixture.receive(textContent("x"), peer)
        engine.markDisplayed(peer)
        eventually { backend.sent(peer).any { it.receipt?.type == ReceiptMessage.Type.DELIVERY } }
        settle()
        assertFalse(backend.sent(peer).any { it.receipt?.type == ReceiptMessage.Type.READ })
    }

    @Test
    fun deliveryAndReadUpdateOurBubbles() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        val id = engine.send(OutgoingContent.Text("çok güzelmiş"), peer)
        eventually { fixture.messages(peer).first().status == OutgoingStatus.SENT }
        fixture.receive(receiptContent(ReceiptMessage.Type.DELIVERY, listOf(id)), peer)
        assertEquals(OutgoingStatus.DELIVERED, fixture.messages(peer).first().status)
        fixture.receive(receiptContent(ReceiptMessage.Type.READ, listOf(id)), peer)
        assertEquals(OutgoingStatus.READ, fixture.messages(peer).first().status)
    }

    @Test
    fun outgoingDisappearingMessagesExpireAfterSending() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        engine.updateConversationSettings(peer, null, 30)
        engine.send(OutgoingContent.Text("30 sn"), peer)
        val sent = fixture.messages(peer).first { it.direction == MessageDirection.OUTGOING }
        assertEquals(30L, sent.expireTimerSeconds)
        eventually {
            fixture.clock.advance(1.seconds)
            fixture.messages(peer).none { it.direction == MessageDirection.OUTGOING }
        }
    }

    // MARK: Ghost mode (§8.2)

    @Test
    fun silenceWipesBothSidesAndTheNoticeGoesOut() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        engine.updateConversationSettings(peer, GhostSettings(true, 3600), 0)
        engine.send(OutgoingContent.Text("bu akşam?"), peer)
        eventually { backend.sent(peer).any { it.data != null } }
        fixture.clock.advance(1800.seconds)
        fixture.receive(textContent("evet"), peer) // resets the timer
        fixture.clock.advance(1800.seconds)
        settle()
        assertNotNull(engine.conversationInfo(peer))
        fixture.clock.advance(1790.seconds)
        settle()
        assertNotNull(engine.conversationInfo(peer))
        eventually(message = "wiped") {
            fixture.clock.advance(1.seconds)
            engine.conversationInfo(peer) == null
        }
        eventually(message = "ConversationWipe sent") {
            backend.sent(peer).any { it.control?.hasWipe() == true && it.control?.wipe?.reason == ConversationWipe.Reason.GHOST_TIMEOUT }
        }
        // Its system lines are gone with it (own crypto-shredded slot).
        assertTrue(fixture.messages(peer).isEmpty())
    }

    // MARK: Presence (§8.6)

    @Test
    fun onlineFanOutRepliesAndOffline() = runBlocking {
        val alice = fixture.acceptedContact()
        val bob = fixture.acceptedContact()
        val hidden = fixture.acceptedContact(presenceSharing = false)
        fixture.start()
        engine.connectionReachedQueueEmpty()
        // One request to every eligible contact.
        assertEquals(setOf(alice, bob), backend.presenceSends.first().second.toSet())
        // Alice comes online: recorded and answered once.
        fixture.receive(presenceContent(PresenceMessage.State.ONLINE), alice)
        eventually { backend.presenceSends.size == 2 }
        val reply = backend.presenceSends[1]
        assertEquals(listOf(alice), reply.second)
        assertTrue(reply.first.presence.reply)
        assertEquals(PresenceStatus.Active, engine.presenceStatus(fixture.clock.now())[alice])
        // A reply is never answered.
        fixture.receive(presenceContent(PresenceMessage.State.ONLINE, reply = true), bob)
        settle()
        assertEquals(2, backend.presenceSends.size)
        // Reciprocity: a contact who hides its status is neither shown nor answered.
        fixture.receive(presenceContent(PresenceMessage.State.ONLINE), hidden)
        settle()
        assertNull(engine.presenceStatus(fixture.clock.now())[hidden])
        // Leaving: OFFLINE only to those seen online.
        engine.goingOffline()
        assertEquals(setOf(alice, bob), backend.presenceSends.last().second.toSet())
        // Turning sharing off hides everyone.
        engine.updateUserSettings { it.copy(presenceSharing = false) }
        assertTrue(engine.presenceStatus(fixture.clock.now()).isEmpty())
    }

    @Test
    fun aBackendThatRunsPresenceIsLeftToIt() = runBlocking {
        backend.presenceRunsInside = true
        val alice = fixture.acceptedContact()
        fixture.start()
        engine.connectionReachedQueueEmpty()
        fixture.receive(presenceContent(PresenceMessage.State.ONLINE), alice)
        settle()
        assertTrue(backend.presenceSends.isEmpty())
        // What arrives is still shown.
        assertEquals(PresenceStatus.Active, engine.presenceStatus(fixture.clock.now())[alice])
        engine.goingOffline()
        assertEquals(1, backend.offlineAnnouncements)
    }

    @Test
    fun typingIndicators() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        fixture.receive(typingContent(true), peer)
        assertEquals(setOf(peer), engine.typingContacts(fixture.clock.now()))
        assertTrue(engine.typingContacts(fixture.clock.now().plusSeconds(20)).isEmpty())
        engine.setTyping(true, peer)
        engine.setTyping(true, peer) // throttled
        val typing = backend.sent(peer).filter { it.content.hasTyping() }
        assertEquals(1, typing.size)
        assertTrue(typing[0].ephemeral)
    }

    // MARK: Media

    @Test
    fun outgoingMediaIsUploadedThenSent() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        val file = fixture.directories.stagingFile("jpg")
        file.writeBytes("jpeg bytes".toByteArray())
        val media = PreparedMedia(file, com.syconx.us2us.core.proto.Attachment.Kind.IMAGE, "image/jpeg", size = 10, width = 4, height = 3)
        val id = engine.send(OutgoingContent.Media(media), peer)
        // The plaintext was moved into encrypted storage.
        assertFalse(file.exists())
        eventually { backend.sent(peer).any { it.data?.messageId == id } }
        val attachment = backend.sent(peer).first { it.data?.messageId == id }.data!!.attachment
        assertEquals(16, attachment.mediaId.size())
        assertEquals(4, attachment.width)
        assertEquals(3, attachment.height)
        assertEquals("jpeg bytes", String(backend.uploads[attachment.mediaId]!!))
        val shown = (fixture.messages(peer).first().body as MessageBody.Attachment).attachment
        assertEquals(AttachmentItem.Availability.LOCAL, shown.availability)
        val exported = engine.exportMedia(id, peer)
        assertEquals("jpeg bytes", exported.readText())
        // Unsending deletes the blob from the relay too.
        engine.deleteForEveryone(id, peer)
        assertEquals(listOf(attachment.mediaId), backend.deletedMedia)
        assertEquals(MessageBody.Deleted, fixture.messages(peer).first().body)
        // The decrypted copy went with its message.
        assertFalse(exported.exists())
    }

    @Test
    fun requestMediaWaitsForAcceptance() = runBlocking {
        fixture.start()
        val stranger = stranger()
        val mediaId = RandomIds.make()
        backend.setDownload("photo".toByteArray(), mediaId)
        fixture.receive(attachmentContent(mediaId), stranger)
        settle()
        val held = (fixture.messages(stranger).first().body as MessageBody.Attachment).attachment
        assertEquals(AttachmentItem.Availability.HELD_UNTIL_ACCEPTED, held.availability)
        engine.acceptRequest(stranger)
        eventually(message = "downloaded") {
            val body = fixture.messages(stranger).first().body
            body is MessageBody.Attachment && body.attachment.availability == AttachmentItem.Availability.LOCAL
        }
        val item = fixture.messages(stranger).first()
        assertEquals("photo", String(engine.mediaData(item.messageId, stranger)))
    }

    @Test
    fun viewOnceIsDestroyedOnBothSides() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        val mediaId = RandomIds.make()
        val id = RandomIds.make()
        fixture.receive(attachmentContent(mediaId, id, viewOnce = true), peer)
        eventually {
            val body = fixture.messages(peer).first().body
            body is MessageBody.Attachment && body.attachment.availability == AttachmentItem.Availability.LOCAL
        }
        // Nothing of it shows before it is opened.
        val shown = (fixture.messages(peer).first().body as MessageBody.Attachment).attachment
        assertNull(shown.thumbnail)
        assertNull(shown.caption)
        assertEngineError(EngineError.INVALID_MESSAGE) { engine.exportMedia(id, peer) }
        val file = engine.openViewOnce(id, peer)
        assertTrue(file.exists())
        engine.closeViewOnce(id, peer, file)
        assertFalse(file.exists())
        assertEquals(MessageBody.ViewOnceOpened, fixture.messages(peer).first().body)
        eventually(message = "VIEWED") { backend.sent(peer).any { it.receipt?.type == ReceiptMessage.Type.VIEWED } }
        assertEngineError(EngineError.INVALID_MESSAGE) { engine.openViewOnce(id, peer) }
    }

    @Test
    fun expiredBlobsBecomeUnavailable() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        val mediaId = RandomIds.make()
        backend.failDownload(mediaId, RelayException(404, RelayErrorCode.NOT_FOUND, "gone"))
        fixture.receive(attachmentContent(mediaId), peer)
        eventually {
            val body = fixture.messages(peer).first().body
            body is MessageBody.Attachment && body.attachment.availability == AttachmentItem.Availability.UNAVAILABLE
        }
    }

    @Test
    fun aDownloadReportsItsProgress() = runBlocking {
        val peer = fixture.acceptedContact()
        val changes = java.util.concurrent.CopyOnWriteArrayList<EngineChange>()
        val collector = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            engine.changes.collect { changes += it }
        }
        fixture.start()
        val mediaId = RandomIds.make()
        val id = RandomIds.make()
        fixture.receive(attachmentContent(mediaId, id), peer)
        eventually { changes.any { it is EngineChange.DownloadProgress && it.fraction == 1.0 } }
        val fractions = changes.filterIsInstance<EngineChange.DownloadProgress>().map { it.fraction }
        assertEquals(listOf(0.5, 1.0), fractions)
        assertTrue(changes.filterIsInstance<EngineChange.DownloadProgress>().all { it.messageId == id && it.peer == peer })
        collector.cancel()
    }

    // MARK: Local actions

    @Test
    fun screenshotsAddALineAndTellAcceptedContacts() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        engine.screenCaptured(peer, recording = false)
        engine.screenCaptured(peer, recording = true)
        assertEquals(
            listOf(MessageBody.Notice(SystemNotice.ScreenshotByMe), MessageBody.Notice(SystemNotice.ScreenRecordingByMe)),
            fixture.messages(peer).map { it.body },
        )
        eventually { backend.sent(peer).count { it.control?.hasScreenshot() == true } == 2 }
    }

    @Test
    fun liveLocationTicksAreEphemeral() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        val session = engine.startLiveLocation(peer, 41.0, 29.0, 5f, 1.hours)
        eventually { backend.sent(peer).any { it.data != null } }
        engine.updateLiveLocation(session, peer, 41.001, 29.001, 5f)
        assertTrue(backend.sent(peer).last().ephemeral)
        val shown = (fixture.messages(peer).first().body as MessageBody.LiveLocation).location
        assertEquals(41.001, shown.latitude, 0.0)
        assertEquals(1L, shown.sequence)
        assertEquals(listOf(session), engine.activeLiveLocationSessions())
        engine.stopLiveLocation(session, peer)
        assertTrue(engine.activeLiveLocationSessions().isEmpty())
        eventually { backend.sent(peer).any { it.data?.liveLocation?.stopped == true && !it.ephemeral } }
    }

    @Test
    fun anOrphanedLiveLocationIsStoppedAtTheNextStart() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        engine.startLiveLocation(peer, 41.0, 29.0, 5f, 1.hours)
        eventually { backend.sent(peer).any { it.data?.hasLiveLocation() == true } }
        // The process ends while sharing; a new engine on the same vault starts.
        engine.stop()
        val restarted = MessagingEngine(fixture.storage, backend, fixture.directories.runtime, fixture.key, fixture.clock)
        restarted.start()
        eventually { backend.sent(peer).any { it.data?.liveLocation?.stopped == true } }
        assertTrue(restarted.activeLiveLocationSessions().isEmpty())
        restarted.stop()
    }

    @Test
    fun profileChangesGoToContactsThatHaveOne() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        engine.updateUserSettings { it.copy(displayName = "Selin") }
        eventually { backend.sent(peer).any { it.isProfile } }
        val update = backend.sent(peer).last { it.isProfile }.control!!.profile
        assertEquals("Selin", update.displayName)
        assertEquals("tester", update.username)
        assertTrue(update.presenceSharing)
    }

    @Test
    fun aBackendThatAnnouncesProfileChangesIsToldInstead() = runBlocking {
        backend.announcesChanges = true
        val peer = fixture.acceptedContact()
        fixture.start()
        eventually { backend.profileUpdates.isNotEmpty() }
        engine.updateUserSettings { it.copy(displayName = "Selin") }
        eventually { backend.profileUpdates.lastOrNull() == ("Selin" to true) }
        settle()
        assertFalse(backend.sent(peer).any { it.isProfile })
    }

    @Test
    fun runtimeStateIsEncryptedAndSurvivesARestart() = runBlocking {
        fixture.start()
        engine.updateUserSettings { it.copy(displayName = "Gizli Ad") }
        engine.stop()
        val file = File(fixture.directories.runtime, RuntimeStateFile.FILE_NAME)
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("Gizli Ad"))
        val reopened = RuntimeStateFile(fixture.directories.runtime, fixture.key)
        assertEquals("Gizli Ad", reopened.load().settings.displayName)
        val wrong = RuntimeStateFile(fixture.directories.runtime, SecretKeyMaterial(ByteArray(32).also(SecureRandom()::nextBytes)))
        assertEquals("", wrong.load().settings.displayName)
        // Once the engine stopped, its zeroed keys are never used to write.
        assertThrows<SecretKeyWipedException> { engine.updateUserSettings { it.copy(displayName = "after lock") } }
        assertEquals("Gizli Ad", reopened.load().settings.displayName)
    }

    // MARK: Stickers

    @Test
    fun aStickerKeepsItsLanguageAndAResendKeepsIt() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        val id = engine.send(OutgoingContent.Sticker("us2us.words", 101, "👋", "tr"), peer)
        eventually { backend.sent(peer).any { it.data?.messageId == id } }
        assertEquals("tr", backend.sent(peer).first { it.data?.messageId == id }.data!!.sticker.lang)
        assertEngineError(EngineError.TOO_LARGE) { engine.send(OutgoingContent.Sticker("us2us.words", 101, "x", "a".repeat(36)), peer) }
    }

    @Test
    fun resendingAnOldTextStickerSendsItsCatalogSuccessor() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        val id = engine.send(OutgoingContent.Sticker(OutgoingContent.LEGACY_TEXT_STICKER_PACK, 7, "x"), peer)
        eventually { fixture.messages(peer).first().status == OutgoingStatus.SENT }
        // 14 days later without a delivery receipt: sent again as new.
        fixture.storage.write { it.messages.markUndelivered(fixture.clock.now().plusSeconds(15L * 86_400)) }
        val again = engine.resend(id, peer)
        eventually { backend.sent(peer).any { it.data?.messageId == again } }
        val sticker = backend.sent(peer).first { it.data?.messageId == again }.data!!.sticker
        assertEquals("us2us.words", sticker.packId)
        assertEquals(109, sticker.stickerId)
        assertEquals("", sticker.lang)
    }

    @Test
    fun recentsAreKeptEncryptedAndBounded() = runBlocking {
        fixture.start()
        engine.setRecentStickers(List(20) { "$it.tr" } + listOf("", "1.tr", "x".repeat(33)))
        assertEquals(List(12) { "$it.tr" }, engine.recentStickers())
        engine.noteRecentSticker("5.tr")
        assertEquals("5.tr", engine.recentStickers().first())
        assertEquals(12, engine.recentStickers().size)
        assertEquals(1, engine.recentStickers().count { it == "5.tr" })
    }

    @Test
    fun aViewOnceCaptionIsRefused() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        val file = fixture.directories.stagingFile("jpg").also { it.writeBytes(byteArrayOf(1)) }
        val media = PreparedMedia(file, com.syconx.us2us.core.proto.Attachment.Kind.IMAGE, "image/jpeg", size = 1, caption = "look")
        assertEngineError(EngineError.NOT_ALLOWED) { engine.send(OutgoingContent.Media(media), peer, viewOnce = true) }
    }

    @Test
    fun aChosenIdIsUsedOnceOnly() = runBlocking {
        val peer = fixture.acceptedContact()
        fixture.start()
        val chosen = RandomIds.make()
        assertEquals(chosen, engine.send(OutgoingContent.Text("first"), peer, messageId = chosen))
        assertEngineError(EngineError.INVALID_MESSAGE) { engine.send(OutgoingContent.Text("again"), peer, messageId = chosen) }
        assertEngineError(EngineError.INVALID_MESSAGE) {
            engine.send(OutgoingContent.Text("short"), peer, messageId = ByteString.copyFrom(ByteArray(4)))
        }
        if (fixture.messages(peer).count { it.direction == MessageDirection.OUTGOING } != 1) fail("stored twice")
    }
}
