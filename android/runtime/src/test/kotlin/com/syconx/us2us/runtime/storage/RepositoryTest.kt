package com.syconx.us2us.runtime.storage

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.AuthToken
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.contacts.MessageRequests
import com.syconx.us2us.core.contacts.UsernameStatus
import com.syconx.us2us.core.keys.Keys
import com.syconx.us2us.core.messenger.PendingSetup
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.proto.ReceiptMessage
import com.syconx.us2us.core.proto.content
import com.syconx.us2us.core.proto.delete
import com.syconx.us2us.core.proto.liveLocation
import com.syconx.us2us.core.proto.location
import com.syconx.us2us.core.proto.profileUpdate
import com.syconx.us2us.core.proto.quote
import com.syconx.us2us.core.proto.reaction
import com.syconx.us2us.core.proto.receiptMessage
import com.syconx.us2us.core.proto.sticker
import com.syconx.us2us.core.proto.text
import com.syconx.us2us.core.proto.typingMessage
import com.syconx.us2us.core.store.ProtocolStore
import com.syconx.us2us.core.username.Username
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import java.security.SecureRandom
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/** Contacts (§6.4, §8.7), messages (§8), attachments, outbox, envelope log and account (the iOS RepositoryTests). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var environment: TestEnvironment
    private lateinit var storage: LocalStorage

    @Before
    fun setUp() {
        environment = TestEnvironment(temporary.newFolder())
        storage = environment.open(t0)
        storage.saveAccount()
    }

    @After
    fun tearDown() {
        storage.close()
    }

    private fun expectStorageError(block: () -> Unit): StorageError {
        try {
            block()
        } catch (e: StorageException) {
            return e.error
        }
        fail("expected a StorageException")
        error("unreachable")
    }

    private fun accept(): Content = MessageRequests.acceptContent()

    // MARK: Contacts and message requests

    @Test
    fun sendingFirstOpensAnOutgoingRequestOnlyAcceptEndsIt() {
        val bob = newAccountId()
        storage.write { tx ->
            assertEquals(ContactState.OUTGOING_REQUEST, tx.contacts.prepareToSend(bob, t0))
            assertEquals(ContactState.OUTGOING_REQUEST, tx.contacts.noteVerifiedMessage(textContent("hi"), bob))
            assertEquals(
                ContactState.OUTGOING_REQUEST,
                tx.contacts.noteVerifiedMessage(MessageRequests.accessKeyUpdateContent(com.syconx.us2us.core.AccessKey(ByteArray(16) { 1 })), bob),
            )
            // M-9: an ACCEPT ends only a request the relay took (a visible message answered 202).
            assertEquals(ContactState.OUTGOING_REQUEST, tx.contacts.noteVerifiedMessage(accept(), bob))
            tx.contacts.markRequestSent(bob, t0)
            assertEquals(ContactState.ACCEPTED, tx.contacts.noteVerifiedMessage(accept(), bob))
        }
    }

    @Test
    fun aFirstMessageOpensAnIncomingRequestAcceptingNeedsTheRelaysAnswer() {
        val carol = newAccountId()
        storage.write { tx ->
            assertEquals(ContactState.INCOMING_REQUEST, tx.contacts.noteVerifiedMessage(textContent("merhaba"), carol))
            assertEquals(ContactState.INCOMING_REQUEST, tx.contacts.noteVerifiedMessage(accept(), carol))
            tx.contacts.acceptIncomingRequest(carol)
            assertEquals(ContactState.ACCEPTED, tx.contacts.requestState(carol))
            expectStorageError { tx.contacts.acceptIncomingRequest(newAccountId()) }
        }
    }

    @Test
    fun blockFromAnyStateUnblockToThePreviousOne() {
        val dave = newAccountId()
        storage.write { tx ->
            tx.contacts.create(dave, ContactState.ACCEPTED)
            tx.contacts.block(dave)
            assertTrue(tx.contacts.get(dave)!!.isBlocked)
            assertEquals(StorageError.InvalidState("blocked"), expectStorageError { tx.contacts.prepareToSend(dave) })
            assertEquals(ContactState.BLOCKED, tx.contacts.noteVerifiedMessage(textContent("x"), dave))
            tx.contacts.unblock(dave)
            assertEquals(ContactState.ACCEPTED, tx.contacts.requestState(dave))
            val stranger = newAccountId()
            tx.contacts.block(stranger)
            assertEquals(ContactState.BLOCKED, tx.contacts.requestState(stranger))
            tx.contacts.unblock(stranger)
            assertEquals(ContactState.INCOMING_REQUEST, tx.contacts.requestState(stranger))
            assertFalse("not blocked: nothing to undo", tx.contacts.unblock(stranger))
        }
    }

    @Test
    fun theBlockedListHoldsEveryBlockedAccountWithAConversationOrWithoutOne() {
        val erin = newAccountId()
        val frank = newAccountId()
        val gina = newAccountId()
        storage.write { tx ->
            tx.contacts.create(erin, ContactState.ACCEPTED)
            tx.conversations.ensure(erin)
            tx.contacts.create(frank, ContactState.INCOMING_REQUEST)
            tx.contacts.create(gina, ContactState.ACCEPTED)
            assertTrue(tx.contacts.blocked().isEmpty())
            tx.contacts.block(erin)
            tx.contacts.block(frank)
            assertEquals(setOf(erin, frank), tx.contacts.blocked().map { it.accountId }.toSet())
            assertTrue(tx.contacts.unblock(frank))
            assertEquals(listOf(erin), tx.contacts.blocked().map { it.accountId })
        }
    }

    @Test
    fun unblockingWithNothingToGoBackToForgetsTheAccountAnythingElseKeepsIt() {
        val stranger = newAccountId()
        val request = newAccountId()
        val friend = newAccountId()
        val kept = newAccountId()
        storage.write { tx ->
            tx.contacts.block(stranger)
            tx.contacts.create(request, ContactState.INCOMING_REQUEST)
            tx.contacts.block(request)
            tx.contacts.create(friend, ContactState.ACCEPTED)
            tx.contacts.block(friend)
            tx.contacts.block(kept)
            tx.conversations.ensure(kept)

            assertTrue(tx.contacts.unblockLeavingNothing(stranger))
            assertNull(tx.contacts.get(stranger))
            assertTrue(tx.contacts.unblockLeavingNothing(request))
            assertNull(tx.contacts.get(request))
            assertFalse("an accepted contact goes back to accepted", tx.contacts.unblockLeavingNothing(friend))
            assertEquals(ContactState.BLOCKED, tx.contacts.requestState(friend))
            assertFalse("a conversation is something to go back to", tx.contacts.unblockLeavingNothing(kept))
            assertEquals(ContactState.BLOCKED, tx.contacts.requestState(kept))
            tx.contacts.unblock(friend)
            assertFalse("not blocked", tx.contacts.unblockLeavingNothing(friend))
            assertEquals(ContactState.ACCEPTED, tx.contacts.requestState(friend))
        }
    }

    @Test
    fun crossingRequestsBecomeAccepted() {
        val eve = newAccountId()
        storage.write { tx ->
            assertFalse(tx.contacts.acceptCrossingRequest(eve))
            tx.contacts.prepareToSend(eve)
            assertTrue(tx.contacts.acceptCrossingRequest(eve))
            assertEquals(ContactState.ACCEPTED, tx.contacts.requestState(eve))
            assertFalse(tx.contacts.acceptCrossingRequest(eve))
        }
    }

    @Test
    fun accessKeysBothWaysAndRotationBookkeeping() {
        val erin = newAccountId()
        val ours = ByteString.copyFrom(ByteArray(16) { 8 })
        storage.write { tx ->
            tx.contacts.create(erin, ContactState.ACCEPTED)
            tx.contacts.setTheirAccessKey(ByteString.copyFrom(ByteArray(16) { 2 }), erin)
            expectStorageError { tx.contacts.setTheirAccessKey(ByteString.copyFrom(ByteArray(3)), erin) }
            assertEquals(listOf(erin), tx.contacts.needingAccessKey(ours).map { it.accountId })
            tx.contacts.setOurAccessKeySent(ours, erin)
            assertTrue(tx.contacts.needingAccessKey(ours).isEmpty())
            val contact = tx.contacts.get(erin)!!
            assertEquals(ByteString.copyFrom(ByteArray(16) { 2 }), contact.theirAccessKey)
            assertEquals(ours, contact.ourAccessKeySent)
        }
    }

    @Test
    fun completeProfileUpdatesReplaceTheStoredProfileOlderOnesAreIgnored() {
        val fay = newAccountId()
        val update = profileUpdate {
            displayName = "Fay"
            username = "fay_99"
            presenceSharing = true
            updatedAt = 200
        }
        storage.write { tx ->
            tx.contacts.create(fay, ContactState.ACCEPTED)
            // False until the first update (§8.6).
            assertFalse(tx.contacts.get(fay)!!.presenceSharing)
            val profile = tx.contacts.applyProfileUpdate(update, fay)
            assertEquals("Fay", profile.displayName)
            assertEquals(UsernameStatus.UNCHECKED, profile.usernameStatus)
            val older = update.toBuilder().setDisplayName("Old").setUpdatedAt(100).build()
            assertEquals("Fay", tx.contacts.applyProfileUpdate(older, fay).displayName)
            tx.contacts.recordUsernameCheck(Username("fay_99"), fay, fay)
            val contact = tx.contacts.get(fay)!!
            assertEquals(UsernameStatus.VERIFIED, contact.profile.usernameStatus)
            assertTrue(contact.presenceSharing)
            // A far-future stamp is clamped to a day ahead, so later updates still win.
            val future = update.toBuilder().setDisplayName("Future").setUpdatedAt(-1L).build()
            assertEquals("Future", tx.contacts.applyProfileUpdate(future, fay, t0).displayName)
            assertEquals(t0.toEpochMilli() + 1.days.inWholeMilliseconds, tx.contacts.get(fay)!!.profile.updatedAt)
        }
    }

    @Test
    fun identityFieldsComeFromTheProtocolStorage() {
        val gus = newAccountId()
        val own = storage.read { it.account.load()!! }
        val store = ProtocolStore(own.identityKeyPair, own.registrationId, storage.protocolStorage)
        val key = IdentityKeyPair.generate().publicKey
        storage.write { tx -> tx.contacts.create(gus, ContactState.ACCEPTED) }
        store.saveIdentity(SignalProtocolAddress(gus.toString(), 1), key)
        store.setVerified(gus, true)
        var contact = storage.read { it.contacts.get(gus)!! }
        assertEquals(key, contact.identityKey)
        assertTrue(contact.isVerified)
        store.saveIdentity(SignalProtocolAddress(gus.toString(), 1), IdentityKeyPair.generate().publicKey)
        contact = storage.read { it.contacts.get(gus)!! }
        assertTrue(contact.identityChanged)
        assertFalse(contact.isVerified)
        assertEquals(key, (contact.identity as ContactIdentity.Changed).previous)
    }

    // MARK: Messages

    @Test
    fun everyDataMessagePayloadRoundTripsThroughTheEncryptedBody() {
        val (_, conversation) = storage.contactWithConversation()
        val target = dataMessageOf { text = text { body = "alıntı" } }
        storage.write { tx -> tx.messages.storeIncoming(target, conversation.id, receivedAt = t0) }
        val payloads = listOf(
            dataMessageOf {
                text = text { body = "Gizli mesaj ✓" }
                // The reply quotes a message that is here and permanent, so it keeps its excerpt.
                quote = quote {
                    targetMessageId = target.messageId
                    previewText = "alıntı"
                }
            },
            dataMessageOf { attachment = sampleAttachment(caption = "foto") },
            dataMessageOf {
                location = location {
                    latitude = 41.0082
                    longitude = 28.9784
                    name = "İstanbul"
                }
            },
            // A built-in sticker with words keeps the language it was shown in (§8.8).
            dataMessageOf {
                sticker = sticker {
                    packId = "us2us.words"
                    stickerId = 103
                    emoji = "🫂"
                    lang = "en"
                }
            },
            dataMessageOf { sticker = sticker { custom = sampleAttachment() } },
            dataMessageOf {
                liveLocation = liveLocation {
                    sessionId = randomId()
                    latitude = 1.0
                    sequence = 1
                }
            },
        )
        storage.write { tx ->
            for (message in payloads) {
                assertTrue(tx.messages.storeIncoming(message, conversation.id, receivedAt = t0) is DataMessageOutcome.Stored)
            }
        }
        val page = storage.read { it.messages.page(conversation.id, now = t0) }.drop(1)
        assertEquals(payloads, page.map { it.content })
        assertEquals(
            listOf(MessageKind.TEXT, MessageKind.ATTACHMENT, MessageKind.LOCATION, MessageKind.STICKER, MessageKind.STICKER, MessageKind.LIVE_LOCATION),
            page.map { it.kind },
        )
        assertEquals(target.messageId, page[0].quoteTarget)
        assertTrue(page.all { it.status == MessageStatus.RECEIVED && !it.isOutgoing })
        val attachments = storage.read { tx -> page.flatMap { tx.attachments.forMessage(it.messageId, conversation.id) } }
        assertEquals(2, attachments.size)
        assertTrue(attachments.all { it.state == AttachmentState.PENDING_DOWNLOAD && it.descriptor != null })
    }

    @Test
    fun aReplyLosesTheExcerptOfAMissingOrExpiringTarget() {
        val (_, conversation) = storage.contactWithConversation()
        val expiring = textMessage("kaybolur", timer = 60)
        val reply = dataMessageOf {
            text = text { body = "cevap" }
            quote = quote {
                targetMessageId = expiring.messageId
                previewText = "kaybolur"
            }
        }
        storage.write { tx ->
            tx.messages.storeIncoming(expiring, conversation.id, receivedAt = t0)
            tx.messages.storeIncoming(reply, conversation.id, receivedAt = t0)
        }
        val stored = storage.read { it.messages.get(reply.messageId, conversation.id, t0)!! }
        assertEquals("", stored.content!!.quote.previewText)
        assertEquals(expiring.messageId, stored.quoteTarget)
    }

    @Test
    fun dedupeByMessageIdPagesAreChronologicalAndPaginate() {
        val (_, conversation) = storage.contactWithConversation()
        val messages = (0 until 10).map { textMessage("m$it") }
        storage.write { tx ->
            for (message in messages) tx.messages.storeIncoming(message, conversation.id)
            assertEquals(DataMessageOutcome.Duplicate, tx.messages.storeIncoming(messages[3], conversation.id))
        }
        storage.read { tx ->
            val last = tx.messages.page(conversation.id, limit = 4)
            assertEquals(messages.drop(6).map { it.messageId }, last.map { it.messageId })
            val before = tx.messages.page(conversation.id, before = last.first().localId, limit = 4)
            assertEquals(messages.subList(2, 6).map { it.messageId }, before.map { it.messageId })
            assertEquals(10, tx.conversations.unreadCount(conversation.id))
            assertEquals(messages[9].messageId, tx.messages.latest(conversation.id)?.messageId)
        }
    }

    @Test
    fun statusMovesForwardOnlyAndSentBecomesNotDeliveredAfter14Days() {
        val (_, conversation) = storage.contactWithConversation()
        val a = textMessage("a")
        val b = textMessage("b")
        storage.write { tx ->
            tx.messages.storeOutgoing(a, conversation.id, t0)
            tx.messages.storeOutgoing(b, conversation.id, t0)
            assertEquals(MessageStatus.SENDING, tx.messages.get(a.messageId, conversation.id, t0)?.status)
            tx.messages.markAccepted(a.messageId, conversation.id, t0.plusSeconds(1))
            tx.messages.markAccepted(b.messageId, conversation.id, t0.plusSeconds(1))
            val read = receiptMessage {
                type = ReceiptMessage.Type.READ
                messageIds += a.messageId
            }
            assertEquals(listOf(a.messageId), tx.messages.applyReceipt(read, conversation.id, t0.plusSeconds(3)))
            val delivery = receiptMessage {
                type = ReceiptMessage.Type.DELIVERY
                messageIds += a.messageId
            }
            assertTrue(tx.messages.applyReceipt(delivery, conversation.id, t0.plusSeconds(4)).isEmpty())
            val message = tx.messages.get(a.messageId, conversation.id, t0)!!
            assertEquals(MessageStatus.READ, message.status)
            assertEquals(t0.plusSeconds(3), message.timestamps.readAt)
            assertEquals(t0.plusSeconds(3), message.timestamps.deliveredAt)
            assertEquals(t0.plusSeconds(1), message.timestamps.acceptedAt)

            // Accepted at t0 + 1.
            assertEquals(0, tx.messages.markUndelivered(t0.plusSeconds(14 * 24 * 3600)))
            assertEquals(1, tx.messages.markUndelivered(t0.plusSeconds(14 * 24 * 3600 + 1)))
            assertEquals(MessageStatus.NOT_DELIVERED, tx.messages.get(b.messageId, conversation.id, t0)?.status)
            // A late delivery receipt still upgrades it.
            val late = delivery.toBuilder().clearMessageIds().addMessageIds(b.messageId).build()
            assertEquals(listOf(b.messageId), tx.messages.applyReceipt(late, conversation.id))
            assertEquals(MessageStatus.DELIVERED, tx.messages.get(b.messageId, conversation.id, t0)?.status)
        }
    }

    @Test
    fun reactionsOnePerReactorLastWriterWinsRemovableEncrypted() {
        val (contact, conversation) = storage.contactWithConversation()
        val target = textMessage("güzel")
        fun reaction(emoji: String, remove: Boolean = false, sentAt: Int) = dataMessageOf(sentAt = t0.plusSeconds(sentAt)) {
            reaction = reaction {
                targetMessageId = target.messageId
                this.emoji = emoji
                this.remove = remove
            }
        }
        storage.write { tx ->
            tx.messages.storeOutgoing(target, conversation.id)
            tx.messages.storeIncoming(reaction("❤️", sentAt = 10), conversation.id)
            // Older: ignored.
            tx.messages.storeIncoming(reaction("😂", sentAt = 5), conversation.id)
            tx.messages.storeOutgoing(reaction("👍", sentAt = 6), conversation.id)
        }
        var reactions = storage.read { it.messages.reactions(listOf(target.messageId), conversation.id) }
        assertEquals(setOf("❤️", "👍"), reactions[target.messageId]!!.map { it.emoji }.toSet())
        assertEquals("❤️", reactions[target.messageId]!!.first { it.reactor == contact }.emoji)
        assertFalse(environment.databaseBytes().contains("❤️"))
        storage.write { tx -> tx.messages.storeIncoming(reaction("❤️", remove = true, sentAt = 20), conversation.id) }
        reactions = storage.read { it.messages.reactions(listOf(target.messageId), conversation.id) }
        assertEquals(listOf("👍"), reactions[target.messageId]!!.map { it.emoji })
    }

    @Test
    fun deleteForEveryoneOnlyByTheOriginalSenderAlsoWhenItArrivesFirst() {
        val (_, conversation) = storage.contactWithConversation()
        val theirs = textMessage("sil beni")
        val mine = textMessage("benim")
        val early = textMessage("sonra gelen")
        fun delete(target: com.syconx.us2us.core.proto.DataMessage) = dataMessageOf { delete = delete { targetMessageId = target.messageId } }
        storage.write { tx ->
            tx.messages.storeIncoming(theirs, conversation.id)
            tx.messages.storeOutgoing(mine, conversation.id)
            assertEquals(DataMessageOutcome.Ignored, tx.messages.storeIncoming(delete(mine), conversation.id))
            assertEquals(DataMessageOutcome.Deleted(theirs.messageId), tx.messages.storeIncoming(delete(theirs), conversation.id))
            // The delete overtakes its message.
            assertEquals(DataMessageOutcome.Ignored, tx.messages.storeIncoming(delete(early), conversation.id))
            tx.messages.storeIncoming(early, conversation.id)
            tx.messages.deleteForEveryone(mine.messageId, conversation.id)
            expectStorageError { tx.messages.deleteForEveryone(theirs.messageId, conversation.id) }
        }
        storage.read { tx ->
            for (id in listOf(theirs.messageId, early.messageId, mine.messageId)) {
                val message = tx.messages.get(id, conversation.id)!!
                assertTrue(message.isDeleted)
                assertNull(message.content)
            }
        }
        assertFalse(environment.databaseBytes().contains("sil beni"))
    }

    @Test
    fun viewOnceOpeningDestroysTheAttachmentFileAndTheBody() {
        val (_, conversation) = storage.contactWithConversation()
        val data = dataMessageOf {
            viewOnce = true
            attachment = sampleAttachment(caption = "secret caption")
        }
        val attachmentId = storage.write { tx ->
            tx.messages.storeIncoming(data, conversation.id)
            val id = tx.attachments.forMessage(data.messageId, conversation.id).first().id
            tx.attachments.storeFile("once".toByteArray(), id)
            id
        }
        // Nothing of it can be seen before opening.
        val before = storage.read { it.messages.get(data.messageId, conversation.id)!! }
        assertEquals("", before.content!!.attachment.caption)
        assertTrue(before.content!!.attachment.thumbnail.isEmpty)
        storage.write { tx -> tx.messages.consumeViewOnce(data.messageId, conversation.id, t0) }
        val message = storage.read { it.messages.get(data.messageId, conversation.id)!! }
        assertNull(message.content)
        assertEquals(t0, message.timestamps.viewedAt)
        assertFalse(storage.files.exists(attachmentId))
        assertNull(storage.read { it.attachments.get(attachmentId) })
    }

    @Test
    fun liveLocationUpdatesOneBubbleInPlaceStaleOrStoppedSessionsAreNotChanged() {
        val (_, conversation) = storage.contactWithConversation()
        val session = randomId()
        fun live(sequence: Int, latitude: Double, stopped: Boolean = false) = dataMessageOf {
            liveLocation = liveLocation {
                sessionId = session
                this.sequence = sequence
                this.latitude = latitude
                this.stopped = stopped
            }
        }
        val start = live(1, 10.0)
        storage.write { tx ->
            tx.messages.storeIncoming(start, conversation.id)
            val updated = tx.messages.storeIncoming(live(3, 30.0), conversation.id)
            assertEquals(start.messageId, (updated as DataMessageOutcome.LiveLocationUpdated).message.messageId)
            assertEquals(DataMessageOutcome.Ignored, tx.messages.storeIncoming(live(2, 20.0), conversation.id))
            tx.messages.storeIncoming(live(4, 40.0, stopped = true), conversation.id)
            assertEquals(DataMessageOutcome.Ignored, tx.messages.storeIncoming(live(5, 50.0), conversation.id))
            assertEquals(1, tx.messages.count(conversation.id))
        }
        val bubble = storage.read { it.messages.get(start.messageId, conversation.id)!! }
        assertTrue(bubble.content!!.liveLocation.stopped)
        assertEquals(4, bubble.content!!.liveLocation.sequence)
    }

    @Test
    fun mediaPagesListPermanentPhotosNewestFirst() {
        val (_, conversation) = storage.contactWithConversation()
        val photos = (0 until 3).map { attachmentMessage(Attachment.Kind.IMAGE) }
        val file = attachmentMessage(Attachment.Kind.FILE)
        val disappearing = attachmentMessage(Attachment.Kind.IMAGE, timer = 60)
        val viewOnce = attachmentMessage(Attachment.Kind.IMAGE, viewOnce = true)
        storage.write { tx ->
            for (message in photos + file + disappearing + viewOnce) tx.messages.storeIncoming(message, conversation.id, receivedAt = t0)
        }
        storage.read { tx ->
            val media = tx.messages.mediaPage(conversation.id, setOf(Attachment.Kind.IMAGE, Attachment.Kind.VIDEO), now = t0)
            assertEquals(photos.reversed().map { it.messageId }, media.map { it.messageId })
            val files = tx.messages.mediaPage(conversation.id, setOf(Attachment.Kind.FILE), now = t0)
            assertEquals(listOf(file.messageId), files.map { it.messageId })
            val older = tx.messages.mediaPage(conversation.id, setOf(Attachment.Kind.IMAGE), before = media[0].localId, limit = 1, now = t0)
            assertEquals(listOf(photos[1].messageId), older.map { it.messageId })
        }
    }

    @Test
    fun savedReceiptsAndSavesAreRecordedOnceForSavableMediaOnly() {
        val (_, conversation) = storage.contactWithConversation()
        val photo = attachmentMessage(Attachment.Kind.IMAGE)
        val voice = attachmentMessage(Attachment.Kind.VOICE)
        val incoming = attachmentMessage(Attachment.Kind.FILE)
        storage.write { tx ->
            tx.messages.storeOutgoing(photo, conversation.id, t0)
            tx.messages.storeOutgoing(voice, conversation.id, t0)
            tx.messages.storeIncoming(incoming, conversation.id, receivedAt = t0)
            val saved = receiptMessage {
                type = ReceiptMessage.Type.SAVED
                messageIds += photo.messageId
                messageIds += voice.messageId
            }
            assertEquals(listOf(photo.messageId), tx.messages.applyReceipt(saved, conversation.id, t0.plusSeconds(5)))
            assertTrue(tx.messages.applyReceipt(saved, conversation.id, t0.plusSeconds(6)).isEmpty())
            assertEquals(t0.plusSeconds(5), tx.messages.get(photo.messageId, conversation.id, t0)!!.timestamps.savedByPeerAt)
            assertTrue(tx.messages.markSavedByMe(incoming.messageId, conversation.id, t0.plusSeconds(7)))
            assertFalse(tx.messages.markSavedByMe(incoming.messageId, conversation.id, t0.plusSeconds(8)))
            expectStorageError { tx.messages.markSavedByMe(voice.messageId, conversation.id, t0) }
        }
    }

    // MARK: Attachments, outbox, envelope log, account

    @Test
    fun attachmentUploadAndDownloadStateFilesThumbnailsStagingImport() {
        val (_, conversation) = storage.contactWithConversation()
        val messageId = randomId()
        val staging = storage.makeStagingFile()
        staging.writeBytes("downloaded plaintext".toByteArray())
        val attachment = storage.write { tx ->
            val created = tx.attachments.create(messageId, conversation.id, null, AttachmentState.PENDING_UPLOAD)
            tx.attachments.storeFile("to upload".toByteArray(), created.id)
            tx.attachments.setState(AttachmentState.UPLOADING, created.id, transferredBytes = 4, countsAsAttempt = true)
            tx.attachments.setDescriptor(sampleAttachment(size = 9), created.id)
            tx.attachments.storeThumbnail("thumb".toByteArray(), created.id)
            val incoming = tx.attachments.create(randomId(), conversation.id, sampleAttachment(), AttachmentState.PENDING_DOWNLOAD)
            assertEquals(listOf(incoming.id), tx.attachments.inStates(listOf(AttachmentState.PENDING_DOWNLOAD)).map { it.id })
            tx.attachments.importFile(staging, incoming.id)
            assertEquals(AttachmentState.DOWNLOADED, tx.attachments.get(incoming.id)?.state)
            tx.attachments.get(created.id)!!
        }
        assertFalse(staging.exists())
        assertEquals(AttachmentState.UPLOADED, attachment.state)
        assertEquals(1, attachment.attempts)
        assertEquals(9L, attachment.descriptor?.size)
        storage.read { tx ->
            assertArrayEquals("to upload".toByteArray(), tx.attachments.readFile(attachment.id))
            assertArrayEquals("thumb".toByteArray(), tx.attachments.readThumbnail(attachment.id))
            val exported = tx.attachments.exportToStaging(attachment.id)
            assertArrayEquals("to upload".toByteArray(), exported.readBytes())
        }
    }

    @Test
    fun outgoingAttachmentBubbleFirstFileKeptEncryptedUploadThenTheFinalContentIsSent() {
        val (contact, conversation) = storage.contactWithConversation()
        val draft = Attachment.newBuilder().setKind(Attachment.Kind.IMAGE).setContentType("image/jpeg").setSize(5).build()
        var data = dataMessageOf { attachment = draft }
        val (attachmentId, outboxId) = storage.write { tx ->
            tx.messages.storeOutgoing(data, conversation.id)
            val attachment = tx.attachments.forMessage(data.messageId, conversation.id).first()
            assertEquals(AttachmentState.PENDING_UPLOAD, attachment.state)
            tx.attachments.storeFile("photo".toByteArray(), attachment.id)
            val outboxId = tx.outbox.enqueue(
                content { this.data = data },
                contact,
                conversation.id,
                data.messageId,
                waitingForUpload = true,
            )
            attachment.id to outboxId
        }
        assertTrue(storage.read { it.outbox.due() }.isEmpty())
        val plaintext = storage.read { it.attachments.exportToStaging(attachmentId) }
        assertArrayEquals("photo".toByteArray(), plaintext.readBytes())
        plaintext.delete()

        // The upload returned the pointer and keys.
        val uploaded = sampleAttachment(size = 5)
        data = data.toBuilder().setAttachment(uploaded).build()
        storage.write { tx ->
            tx.messages.updateOutgoingContent(data, conversation.id)
            tx.outbox.markReady(outboxId, content { this.data = data })
            assertEquals(1, tx.attachments.forMessage(data.messageId, conversation.id).size)
            val attachment = tx.attachments.get(attachmentId)!!
            assertEquals(AttachmentState.UPLOADED, attachment.state)
            assertTrue(attachment.hasFile)
            assertEquals(uploaded.mediaId, attachment.descriptor?.mediaId)
            assertEquals(content { this.data = data }, tx.outbox.due().first().content)
            assertEquals(data, tx.messages.get(data.messageId, conversation.id)?.content)
        }
    }

    @Test
    fun outboxDueOrderBackoffPermanentFailureRetryAndSentOnSuccess() {
        val (contact, conversation) = storage.contactWithConversation()
        val message = textMessage("gönder")
        val policy = RetryPolicy(base = 2.seconds, maximum = 60.seconds, jitter = 0.0, maximumAge = 3600.seconds)
        val typing = content { this.typing = typingMessage { started = true } }
        val (first, second) = storage.write { tx ->
            tx.messages.storeOutgoing(message, conversation.id, t0)
            val first = tx.outbox.enqueue(content { data = message }, contact, conversation.id, message.messageId, now = t0)
            val second = tx.outbox.enqueue(typing, contact, ephemeral = true, now = t0)
            first to second
        }
        storage.write { tx ->
            assertEquals(listOf(first, second), tx.outbox.due(t0).map { it.id })
            assertEquals(content { data = message }, tx.outbox.get(first)?.content)
            tx.outbox.markFailed(first, "network", retryable = true, policy = policy, now = t0)
            assertEquals(t0.plusSeconds(2), tx.outbox.get(first)?.nextAttemptAt)
            tx.outbox.markFailed(first, "network", retryable = true, policy = policy, now = t0.plusSeconds(2))
            assertEquals(t0.plusSeconds(6), tx.outbox.get(first)?.nextAttemptAt)
            assertEquals(listOf(second), tx.outbox.due(t0.plusSeconds(3)).map { it.id })
            tx.outbox.markFailed(second, "403", retryable = false, policy = policy, now = t0)
            assertEquals(OutboxState.FAILED, tx.outbox.get(second)?.state)
            tx.outbox.markSent(first, t0.plusSeconds(7))
            assertNull(tx.outbox.get(first))
            assertEquals(MessageStatus.SENT, tx.messages.get(message.messageId, conversation.id, t0)?.status)
            // Too old: given up, and the message is Failed.
            val late = textMessage("late")
            tx.messages.storeOutgoing(late, conversation.id, t0)
            val lateId = tx.outbox.enqueue(content { data = late }, contact, conversation.id, late.messageId, now = t0)
            tx.outbox.markFailed(lateId, "network", retryable = true, policy = policy, now = t0.plusSeconds(3600))
            assertEquals(OutboxState.FAILED, tx.outbox.get(lateId)?.state)
            assertEquals(MessageStatus.FAILED, tx.messages.get(late.messageId, conversation.id, t0)?.status)
            tx.outbox.retry(lateId, t0.plusSeconds(4000))
            assertEquals(OutboxState.READY, tx.outbox.get(lateId)?.state)
            assertEquals(MessageStatus.SENDING, tx.messages.get(late.messageId, conversation.id, t0)?.status)
        }
        assertEquals(900.seconds, RetryPolicy(base = 2.seconds, maximum = 900.seconds, jitter = 0.0).delay(20))
        val jittered = RetryPolicy(base = 10.seconds, maximum = 900.seconds, jitter = 0.2)
        repeat(20) { assertTrue(jittered.delay(1) in 8.seconds..12.seconds) }
    }

    @Test
    fun pendingTextOfAConversationIsAnItemConversationlessSendsAreNot() {
        val (contact, conversation) = storage.contactWithConversation()
        storage.write { tx ->
            tx.outbox.enqueue(content { data = textMessage("bekleyen metin") }, contact, conversation.id)
            tx.conversations.setDraft("taslak metin", conversation.id)
        }
        val raw = environment.databaseBytes()
        assertFalse(raw.contains("bekleyen metin"))
        assertFalse(raw.contains("taslak metin"))
        assertEquals("taslak metin", storage.read { it.conversations.draft(conversation.id) })
        storage.write { tx -> tx.conversations.setDraft(null, conversation.id) }
        assertNull(storage.read { it.conversations.draft(conversation.id) })
    }

    @Test
    fun theEnvelopeLogSurvivesRestartsAndIsPrunedAfter15Days() {
        storage.write { tx ->
            tx.envelopes.record("relay-1", t0)
            tx.envelopes.record("relay-1", t0)
        }
        storage.close()
        storage = environment.open(t0)
        assertTrue(storage.read { it.envelopes.contains("relay-1") })
        storage.write { tx -> assertEquals(1, tx.envelopes.prune(t0.plusSeconds(15 * 24 * 3600 + 1))) }
        assertFalse(storage.read { it.envelopes.contains("relay-1") })
    }

    @Test
    fun theLocalAccountIsSavedThroughTheAccountStoreAndSurvivesRestarts() {
        storage.close()
        storage = environment.open()
        val account = storage.read { it.account.load()!! }.copy(pendingSetup = setOf(PendingSetup.REPLACE_PRE_KEYS), displayName = "Ayşe")
        storage.accountStore.save(account)
        assertTrue(storage.hasSavedAccount)
        storage.close()
        // A durable fact: still known once the database can no longer be read.
        assertTrue(storage.hasSavedAccount)
        storage = environment.open()
        assertFalse("only saves through this instance count", storage.hasSavedAccount)
        val loaded = storage.read { it.account.load()!! }
        // IdentityKeyPair has no value equality: compare its serialization.
        assertArrayEquals(account.identityKeyPair.serialize(), loaded.identityKeyPair.serialize())
        assertEquals(account, loaded.copy(identityKeyPair = account.identityKeyPair))
        val other = newAccount(username = "other_1")
        try {
            storage.accountStore.save(other)
            fail("expected a refusal")
        } catch (e: StorageException) {
            assertTrue(e.error is StorageError.InvalidState)
        }
        // The same account id with another identity is refused too.
        try {
            storage.accountStore.save(account.copy(identityKeyPair = Keys.generateIdentityKeyPair()))
            fail("expected a refusal")
        } catch (e: StorageException) {
            assertTrue(e.error is StorageError.InvalidState)
        }
        assertNotNull(storage.read { it.account.load() })
    }

    /** §5.5 step 3.1: the new token is stored with the backup's before the relay hears of it, and survives a restart until settled. */
    @Test
    fun aPendingAuthTokenIsKeptWithTheAccountUntilItIsSettled() {
        val pending = AuthToken(ByteArray(AuthToken.LENGTH).also(SecureRandom()::nextBytes))
        val account = storage.read { it.account.load()!! }
        assertNull(account.pendingAuthToken)
        storage.accountStore.save(account.copy(pendingAuthToken = pending))
        storage.close()
        storage = environment.open()
        val reopened = storage.read { it.account.load()!! }
        assertEquals(pending, reopened.pendingAuthToken)
        assertEquals("the backup's token stays until the relay says which one it holds", account.authToken, reopened.authToken)

        // Settled: the relay's token is the account's, the other one is forgotten.
        storage.accountStore.save(reopened.copy(authToken = pending, pendingAuthToken = null))
        storage.close()
        storage = environment.open()
        val settled = storage.read { it.account.load()!! }
        assertEquals(pending, settled.authToken)
        assertNull(settled.pendingAuthToken)
        assertFalse("absent once settled", String(LocalAccountCodec.encode(settled), Charsets.UTF_8).contains("pendingAuthToken"))
    }

    @Test
    fun accountIdsSortLikeTheirBytes() {
        val small = AccountId.fromBytes(ByteArray(16) { 0x01 })
        val large = AccountId.fromBytes(ByteArray(16) { 0xF0.toByte() })
        assertTrue(StorageRules.compare(small, large) < 0)
        assertTrue(StorageRules.compare(large, small) > 0)
        assertEquals(0, StorageRules.compare(small, small))
    }
}
