package com.syconx.us2us.runtime.storage

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.community.CommunityMember
import com.syconx.us2us.core.community.CommunityState
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.encoding.Base64Url
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.TransferRecord
import com.syconx.us2us.core.proto.TransferSettings
import com.syconx.us2us.core.proto.attachment
import com.syconx.us2us.core.proto.dataMessage
import com.syconx.us2us.core.proto.reaction
import com.syconx.us2us.core.proto.text
import com.syconx.us2us.core.proto.transferFilePart
import com.syconx.us2us.core.proto.transferRecord
import com.syconx.us2us.core.transfer.DeviceTransferException
import com.syconx.us2us.core.transfer.DeviceTransferSpool
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.libsignal.protocol.IdentityKeyPair

/** PROTOCOL.md §5.6: what the old phone's history becomes on the new one, and what stays behind. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceTransferHistoryTest {
    @get:Rule val temporary = TemporaryFolder()

    private val peer = AccountId(UUID(0x2121, 0x2121))

    private fun id(value: Int): ByteString = ByteString.copyFrom(ByteArray(16) { value.toByte() })

    private fun text(body: String, value: Int, timer: Int = 0, once: Boolean = false) = dataMessage {
        messageId = id(value)
        sentAt = 1_700_000_000_000 + value
        this.text = text { this.body = body }
        expireTimerSeconds = timer
        viewOnce = once
    }

    private fun photoMessage(value: Int) = dataMessage {
        messageId = id(value)
        sentAt = 1_700_000_000_000 + value
        attachment = attachment {
            kind = Attachment.Kind.IMAGE
            mediaId = id(0x55)
            key = ByteString.copyFrom(ByteArray(64) { 0x56 })
            digest = ByteString.copyFrom(ByteArray(32) { 0x57 })
            size = 3
            contentType = "image/jpeg"
        }
    }

    private fun phone(): LocalStorage = TestEnvironment(temporary.newFolder()).open().also { it.saveAccount() }

    private fun oldPhone(): Pair<LocalStorage, ConversationId> {
        val storage = phone()
        val conversation = storage.write { tx ->
            tx.contacts.create(peer, ContactState.ACCEPTED)
            tx.conversations.ensure(peer).id
        }
        return storage to conversation
    }

    private fun newPhone(): LocalStorage = phone().also { storage ->
        storage.write { tx -> tx.contacts.create(peer, ContactState.ACCEPTED) }
    }

    private fun export(storage: LocalStorage): DeviceTransferSpool {
        val spool = DeviceTransferSpool(temporary.newFolder())
        runBlocking {
            DeviceTransferHistoryExporter(storage, TransferSettings.getDefaultInstance()).export { spool.append(it) }
        }
        spool.finish()
        return spool
    }

    private fun take(spool: DeviceTransferSpool, into: LocalStorage, now: Instant = Instant.now()) {
        DeviceTransferHistoryImporter(into, into.read { it.ownAccountId() }, spool.reader(), applySettings = {}, now = now).run()
    }

    @Test fun messagesFilesReactionsStatesAndTheOrderArriveWhatMustNotMoveDoesNot() {
        val (old, conversation) = oldPhone()
        val keep1 = text("merhaba", 1)
        val keep2 = text("nasılsın", 2)
        val photo = photoMessage(3)
        val timed = text("kaybolur", 4, timer = 60)
        val once = text("tek görüntülük", 5, once = true)
        old.write { tx ->
            tx.messages.storeOutgoing(keep1, conversation)
            tx.messages.markAccepted(keep1.messageId, conversation)
            tx.messages.storeIncoming(keep2, conversation)
            tx.messages.storeIncoming(photo, conversation)
            val attachment = tx.attachments.forMessage(photo.messageId, conversation).first()
            tx.attachments.storeFile(byteArrayOf(9, 8, 7), attachment.id)
            tx.attachments.storeThumbnail(byteArrayOf(1, 2), attachment.id)
            tx.messages.storeIncoming(timed, conversation)
            tx.messages.storeOutgoing(once, conversation)
            tx.messages.storeOutgoing(
                dataMessage {
                    messageId = id(6)
                    sentAt = 1_700_000_000_100
                    reaction = reaction {
                        targetMessageId = keep2.messageId
                        emoji = "👍"
                    }
                },
                conversation,
            )
            tx.conversations.setDraft("taslak", conversation)
            tx.chatBackgrounds.setForContact(ChatBackgroundChoice.Preset("dusk"), peer)
        }
        val lastActivity = old.read { it.conversations.get(conversation)?.lastActivityAt }

        val spool = export(old)
        val new = newPhone()
        take(spool, new)

        new.read { tx ->
            val moved = assertNotNullValue(tx.conversations.forContact(peer))
            val messages = tx.messages.page(moved.id, limit = 100)
            assertEquals(listOf(keep1.messageId, keep2.messageId, photo.messageId), messages.map { it.messageId })
            assertEquals("merhaba", messages[0].content?.text?.body)
            assertEquals(MessageStatus.SENT, messages[0].status)
            assertTrue(messages[0].isOutgoing)
            assertEquals(tx.ownAccountId(), messages[0].sender)
            assertEquals(peer, messages[1].sender)
            assertEquals(MessageStatus.RECEIVED, messages[1].status)
            assertEquals(2, tx.conversations.unreadCount(moved.id))
            assertEquals("👍", tx.messages.reactions(listOf(keep2.messageId), moved.id)[keep2.messageId]?.first()?.emoji)
            val attachment = assertNotNullValue(tx.attachments.forMessage(photo.messageId, moved.id).firstOrNull())
            assertTrue(attachment.hasFile)
            assertEquals(AttachmentState.DOWNLOADED, attachment.state)
            assertArrayEquals(byteArrayOf(9, 8, 7), tx.attachments.readFile(attachment.id))
            assertArrayEquals(byteArrayOf(1, 2), tx.attachments.readThumbnail(attachment.id))
            assertEquals("taslak", tx.conversations.draft(moved.id))
            assertEquals(ChatBackgroundChoice.Preset("dusk"), tx.chatBackgrounds.forContact(peer))
            assertEquals(lastActivity, moved.lastActivityAt)
            // Neither the timed message nor the view-once one, and a retransmission stays out too.
            assertTrue(tx.messages.wasRemoved(timed.messageId, moved.id))
            assertTrue(tx.messages.wasRemoved(once.messageId, moved.id))
            assertEquals(3, tx.messages.count(moved.id))
        }
        // Under this phone's own keys: the rows are not the old phone's.
        assertNotEquals(old.read { it.conversations.get(conversation)?.slotId }, new.read { it.conversations.forContact(peer)?.slotId })
    }

    @Test fun aGhostChatKeepsItsSettingsButNoneOfItsMessagesWithAFreshTimeout() {
        val (old, conversation) = oldPhone()
        val secret = text("ghost", 7)
        old.write { tx ->
            tx.messages.storeIncoming(secret, conversation)
            tx.conversations.setDraft("yazılan", conversation)
            tx.conversations.updateSettings(conversation) { settings ->
                settings.ghost = settings.ghost.toBuilder().setEnabled(true).setTimeoutSeconds(3600).build()
            }
        }
        val spool = export(old)
        val new = newPhone()
        val moment = Instant.now()
        take(spool, new, moment)
        new.read { tx ->
            val moved = assertNotNullValue(tx.conversations.forContact(peer))
            assertTrue(moved.settings.ghost.enabled)
            assertEquals("what was typed goes the way of the messages", null, tx.conversations.draft(moved.id))
            assertEquals(0, tx.messages.count(moved.id))
            assertTrue(tx.messages.wasRemoved(secret.messageId, moved.id))
            val deadline = assertNotNullValue(moved.ghostDeadline)
            assertTrue(deadline.epochSecond - moment.epochSecond > 3590)
        }
    }

    @Test fun whatWasStillBeingSentArrivesAsFailed() {
        val (old, conversation) = oldPhone()
        val pending = text("yolda", 8)
        old.write { tx -> tx.messages.storeOutgoing(pending, conversation) }
        val spool = export(old)
        val new = newPhone()
        take(spool, new)
        new.read { tx ->
            val moved = assertNotNullValue(tx.conversations.forContact(peer))
            val message = assertNotNullValue(tx.messages.get(pending.messageId, moved.id))
            assertEquals(MessageStatus.FAILED, message.status)
            assertNotNull(message.timestamps.failedAt)
        }
    }

    @Test fun aMessageRequestArrivesWithItsContactThoughNoBackupHoldsIt() {
        val stranger = AccountId(UUID(0x4141, 0x4141))
        val old = phone()
        old.write { tx ->
            tx.contacts.create(stranger, ContactState.INCOMING_REQUEST)
            tx.messages.storeIncoming(text("selam", 9), tx.conversations.ensure(stranger).id)
        }
        val spool = export(old)
        val new = newPhone()
        take(spool, new)
        new.read { tx ->
            assertEquals(ContactState.INCOMING_REQUEST, tx.contacts.requestState(stranger))
            assertEquals(1, tx.messages.count(assertNotNullValue(tx.conversations.forContact(stranger)).id))
        }
    }

    /** Under its own id, when asked to, and not before. */
    @Test fun whatHadNotGoneOutCanGoOutFromTheNewPhone() {
        val (old, conversation) = oldPhone()
        val waiting = text("yolda", 8)
        val notUploaded = photoMessage(9).toBuilder().setAttachment(photoMessage(9).attachment.toBuilder().clearMediaId()).build()
        val uploaded = photoMessage(10).toBuilder().setAttachment(photoMessage(10).attachment.toBuilder().clearMediaId()).build()
        val pointer = photoMessage(10).attachment
        old.write { tx ->
            tx.messages.storeOutgoing(waiting, conversation)
            for (message in listOf(notUploaded, uploaded)) {
                tx.messages.storeOutgoing(message, conversation)
                val item = tx.attachments.forMessage(message.messageId, conversation).first()
                tx.attachments.storeFile(byteArrayOf(9, 8, 7), item.id)
                tx.attachments.setState(AttachmentState.UPLOADING, item.id)
            }
            val item = tx.attachments.forMessage(uploaded.messageId, conversation).first()
            tx.attachments.setDescriptor(pointer, item.id)
            tx.attachments.setState(AttachmentState.UPLOADED, item.id)
        }
        val spool = export(old)
        val new = newPhone()
        take(spool, new)
        val moved = assertNotNullValue(new.read { it.conversations.forContact(peer) }).id
        new.read { tx ->
            assertTrue("nothing goes out by itself", tx.outbox.due(Instant.now().plusSeconds(60)).isEmpty())
            val failed = tx.outbox.all(OutboxState.FAILED)
            assertEquals(listOf(waiting.messageId, notUploaded.messageId, uploaded.messageId), failed.map { it.messageId })
            val item = tx.attachments.forMessage(notUploaded.messageId, moved).first()
            assertEquals(AttachmentState.PENDING_UPLOAD, item.state)
            assertArrayEquals(byteArrayOf(9, 8, 7), tx.attachments.readFile(item.id))
            val entry = failed.first { it.messageId == uploaded.messageId }
            assertEquals("the pointer it got on the old phone goes with it", pointer.mediaId, entry.content.data.attachment.mediaId)
        }
        new.write { tx -> for (entry in tx.outbox.all(OutboxState.FAILED)) tx.outbox.retry(entry.id) }
        new.read { tx ->
            assertEquals(listOf(waiting.messageId, uploaded.messageId), tx.outbox.all(OutboxState.READY).map { it.messageId })
            assertEquals(listOf(notUploaded.messageId), tx.outbox.all(OutboxState.WAITING_FOR_UPLOAD).map { it.messageId })
            assertEquals(MessageStatus.SENDING, tx.messages.get(waiting.messageId, moved)?.status)
        }
    }

    @Test fun aBlockedContactKeepsWhatItWasBeforeTheBlock() {
        val (old, conversation) = oldPhone()
        old.write { tx ->
            tx.messages.storeIncoming(text("önceki", 1), conversation)
            tx.contacts.block(peer)
        }
        val spool = export(old)
        // As a backup restores it.
        val new = phone()
        new.write { tx -> tx.contacts.create(peer, ContactState.BLOCKED) }
        take(spool, new)
        new.write { tx ->
            assertTrue(tx.contacts.unblock(peer))
            assertEquals(ContactState.ACCEPTED, tx.contacts.requestState(peer))
        }
    }

    @Test fun theNewPhoneTakesNoGhostChatsMessagesOrDraftEvenIfTheyAreSent() {
        val spool = DeviceTransferSpool(temporary.newFolder())
        val secret = text("ghost", 7)
        spool.append(
            transferRecord {
                conversation = com.syconx.us2us.core.proto.transferConversation {
                    contact = ByteString.copyFrom(peer.toBytes())
                    requestState = ContactState.ACCEPTED.raw
                    settings = com.syconx.us2us.core.proto.ConversationSettings.newBuilder()
                        .setGhost(com.syconx.us2us.core.proto.GhostMode.newBuilder().setEnabled(true).setTimeoutSeconds(3600))
                        .build()
                    draft = "yazılan"
                }
            }.toByteArray(),
        )
        spool.append(
            transferRecord {
                message = com.syconx.us2us.core.proto.transferMessage {
                    messageId = secret.messageId
                    content = secret.toByteString()
                    kind = 1
                    status = 10
                }
            }.toByteArray(),
        )
        spool.finish()
        val new = newPhone()
        take(spool, new)
        new.read { tx ->
            val moved = assertNotNullValue(tx.conversations.forContact(peer))
            assertEquals(0, tx.messages.count(moved.id))
            assertEquals(null, tx.conversations.draft(moved.id))
        }
    }

    private fun ownCommunity(old: LocalStorage, others: List<AccountId>): CommunityState {
        val own = old.read { it.ownAccountId() }
        val identity = IdentityKeyPair.generate()
        val key = Base64Url.encode(identity.publicKey.serialize())
        val state = CommunityState(
            AccountId(UUID.randomUUID()), 1, "private", own, key, "",
            (others + own).map { CommunityMember(it, key) }.sortedBy { it.accountId.toString() },
        )
        val repository = CommunityRepository(old)
        repository.pin(state.id, key, "topluluk")
        repository.save(state.sign(identity))
        return state
    }

    /** "partial" is "under way" here and final on iOS: what arrives says which it was. */
    @Test fun anOwnCommunityMessageNobodyWaitsForIsSentOneSomeoneWaitsForCanBeSentAgain() {
        val old = phone()
        val own = old.read { it.ownAccountId() }
        val alice = AccountId(UUID.randomUUID())
        val bob = AccountId(UUID.randomUUID())
        val state = ownCommunity(old, listOf(alice, bob))
        val repository = CommunityRepository(old)
        val settled = AccountId(UUID.randomUUID())
        val waiting = AccountId(UUID.randomUUID())
        for ((message, last) in listOf(settled to CommunityRecipient.SKIPPED, waiting to CommunityRecipient.PENDING)) {
            val body = if (message == settled) "tamam" else "yolda"
            repository.add(StoredCommunityMessage(state.id, own, message, 1000, body, "partial", receivedAt = 1000))
            repository.setRecipients(state.id, message, listOf(alice, bob))
            old.write { tx ->
                for ((member, transport) in listOf(alice to CommunityRecipient.ACCEPTED, bob to last)) {
                    tx.connection.run(
                        "UPDATE community_recipients SET transport=? WHERE community_id=? AND message_id=? AND member=?",
                        transport, state.id.toString(), message.toString(), member.toString(),
                    )
                }
            }
        }
        val spool = export(old)
        val new = TestEnvironment(temporary.newFolder()).open()
        DeviceTransferHistoryImporter(new, own, spool.reader(), applySettings = {}).run()
        new.saveAccount(newAccount(own))
        val moved = CommunityRepository(new)
        assertEquals(mapOf("tamam" to "sent", "yolda" to "failed"), moved.messages(state.id).associate { it.text to it.status })
        assertEquals(CommunityRecipient.FAILED, moved.recipients(state.id, waiting).first { it.member == bob }.transport)
    }

    @Test fun whatCannotBeReadStaysBehindAndTheMoveGoesOn() {
        val (old, conversation) = oldPhone()
        val own = old.read { it.ownAccountId() }
        val state = ownCommunity(old, listOf(AccountId(UUID.randomUUID())))
        val repository = CommunityRepository(old)
        val damaged = AccountId(UUID.randomUUID())
        repository.add(StoredCommunityMessage(state.id, own, AccountId(UUID.randomUUID()), 1000, "kalır", "sent", receivedAt = 1000))
        repository.add(StoredCommunityMessage(state.id, own, damaged, 1001, "bozuk", "sent", receivedAt = 1001))
        old.write { tx ->
            tx.messages.storeIncoming(text("iyi", 1), conversation)
            tx.messages.storeIncoming(photoMessage(2), conversation)
            tx.conversations.setDraft("taslak", conversation)
            tx.connection.run("UPDATE attachments SET descriptor = ?", byteArrayOf(0))
            tx.connection.run("UPDATE conversations SET draft = ?", byteArrayOf(0))
            tx.connection.run("UPDATE community_messages SET body = ? WHERE message_id = ?", byteArrayOf(0), damaged.toString())
        }
        val spool = export(old)
        val new = TestEnvironment(temporary.newFolder()).open()
        new.write { tx -> tx.contacts.create(peer, ContactState.ACCEPTED) }
        DeviceTransferHistoryImporter(new, own, spool.reader(), applySettings = {}).run()
        new.saveAccount(newAccount(own))
        new.read { tx ->
            val moved = assertNotNullValue(tx.conversations.forContact(peer))
            assertEquals(listOf("iyi"), tx.messages.page(moved.id, limit = 10).map { it.content?.text?.body })
            assertEquals(null, tx.conversations.draft(moved.id))
        }
        assertEquals(listOf("kalır"), CommunityRepository(new).messages(state.id).map { it.text })
    }

    /** §6.4: an answer counts only to a request the other phone really got. */
    @Test fun anOutgoingRequestArrivesKnowingWhetherItWentOut() {
        val old = phone()
        val sentTo = AccountId(UUID(0x6161, 0x6161))
        val openedOnly = AccountId(UUID(0x6262, 0x6262))
        val sentAt = Instant.ofEpochMilli(1_700_000_000_000)
        old.write { tx ->
            for (contact in listOf(sentTo, openedOnly)) {
                tx.contacts.create(contact, ContactState.OUTGOING_REQUEST)
                tx.conversations.ensure(contact)
            }
            tx.contacts.markRequestSent(sentTo, sentAt)
        }
        val spool = export(old)
        val new = phone()
        take(spool, new)
        new.read { tx ->
            assertEquals(ContactState.OUTGOING_REQUEST, tx.contacts.get(sentTo)?.requestState)
            assertEquals(sentAt, tx.contacts.requestSentAt(sentTo))
            assertEquals(ContactState.OUTGOING_REQUEST, tx.contacts.get(openedOnly)?.requestState)
            assertFalse(tx.contacts.requestSent(openedOnly))
        }
    }

    @Test fun aCommunityArrivesWithItsMessagesAndItsPlaceOnTheRoster() {
        val old = phone()
        val identity = IdentityKeyPair.generate()
        val owner = old.read { it.ownAccountId() }
        val key = Base64Url.encode(identity.publicKey.serialize())
        val community = AccountId(UUID(0x7171, 0x7171))
        val member = AccountId(UUID(0x8181, 0x8181))
        val repository = CommunityRepository(old)
        repository.pin(community, key, "topluluk")
        repository.save(
            CommunityState(
                community, 1, "private", owner, key, "",
                listOf(CommunityMember(owner, key), CommunityMember(member, key)).sortedBy { it.accountId.toString() },
            ).sign(identity),
        )
        val messageId = AccountId(UUID(0x9191, 0x9191))
        repository.add(StoredCommunityMessage(community, member, messageId, 1000, "herkese selam", "received", receivedAt = 1000))

        val spool = export(old)
        val new = phone()
        take(spool, new)

        val moved = CommunityRepository(new)
        val stored = assertNotNullValue(moved.get(community))
        assertEquals("topluluk", stored.name)
        assertTrue(stored.active)
        assertEquals(1L, stored.state?.revision)
        assertEquals(listOf("herkese selam"), moved.messages(community).map { it.text })
        assertEquals(member, moved.messages(community).first().sender)
    }

    /** Everything goes in before the account is saved: a vault without one takes it all. */
    @Test fun aVaultWithoutAnAccountTakesItAll() {
        val old = phone()
        val own = old.read { it.ownAccountId() }
        val identity = IdentityKeyPair.generate()
        val key = Base64Url.encode(identity.publicKey.serialize())
        val community = AccountId(UUID(0x7171, 0x7171))
        val member = AccountId(UUID(0x8181, 0x8181))
        old.write { tx ->
            tx.contacts.create(peer, ContactState.ACCEPTED)
            val id = tx.conversations.ensure(peer).id
            tx.messages.storeOutgoing(text("giden", 1), id)
            tx.messages.storeIncoming(text("gelen", 2), id)
        }
        val repository = CommunityRepository(old)
        repository.pin(community, key, "topluluk")
        repository.save(
            CommunityState(
                community, 1, "private", own, key, "",
                listOf(CommunityMember(own, key), CommunityMember(member, key)).sortedBy { it.accountId.toString() },
            ).sign(identity),
        )
        repository.add(StoredCommunityMessage(community, own, AccountId(UUID(0x9191, 0x9191)), 1000, "benden", "sent", receivedAt = 1000))
        repository.add(StoredCommunityMessage(community, member, AccountId(UUID(0x9292, 0x9292)), 1001, "ondan", "received", receivedAt = 1001))

        val spool = export(old)
        val new = TestEnvironment(temporary.newFolder()).open()
        new.write { tx -> tx.contacts.create(peer, ContactState.ACCEPTED) }
        assertNull(new.read { it.account.load() })
        DeviceTransferHistoryImporter(new, own, spool.reader(), applySettings = {}).run()

        // The moment the account is saved it is all there, and the messages are its own.
        new.saveAccount(newAccount(own))
        new.read { tx ->
            val moved = assertNotNullValue(tx.conversations.forContact(peer))
            val messages = tx.messages.page(moved.id, limit = 10)
            assertEquals(setOf("giden", "gelen"), messages.map { it.content?.text?.body }.toSet())
            assertEquals(own, messages.first { it.content?.text?.body == "giden" }.sender)
            assertEquals(peer, messages.first { it.content?.text?.body == "gelen" }.sender)
        }
        val movedMessages = CommunityRepository(new).messages(community)
        assertEquals(setOf("benden", "ondan"), movedMessages.map { it.text }.toSet())
        assertEquals(own, movedMessages.first { it.text == "benden" }.sender)
    }

    @Test fun aRecordOutOfPlaceEndsTheImport() {
        val spool = DeviceTransferSpool(temporary.newFolder())
        spool.append(transferRecord { filePart = transferFilePart { data = ByteString.copyFrom(byteArrayOf(1)) } }.toByteArray())
        spool.finish()
        val new = newPhone()
        assertThrows(DeviceTransferException::class.java) { take(spool, new) }
    }

    @Test fun theEstimateLeavesOutWhatDoesNotMove() = runBlocking {
        val (old, conversation) = oldPhone()
        old.write { tx -> tx.messages.storeIncoming(text("kalır", 1), conversation) }
        val exporter = DeviceTransferHistoryExporter(old, TransferSettings.getDefaultInstance())
        val small = exporter.estimatedBytes()
        assertTrue(small > 0)
        old.write { tx -> tx.messages.storeIncoming(text("x".repeat(50_000), 2, timer = 60), conversation) }
        assertEquals(small, exporter.estimatedBytes())
    }

    private fun <T> assertNotNullValue(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
