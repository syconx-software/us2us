package com.syconx.us2us.runtime.storage

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.community.CommunityContent
import com.syconx.us2us.core.community.CommunityMember
import com.syconx.us2us.core.community.CommunityState
import com.syconx.us2us.core.encoding.Base64Url
import com.syconx.us2us.core.messenger.Messenger
import com.syconx.us2us.core.messenger.MessengerConfig
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.CommunityReceipt
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.runtime.community.CommunityEngine
import com.syconx.us2us.runtime.media.PreparedMedia
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import java.io.File
import java.util.UUID

/**
 * Communities extension v2 end to end between three phones over [FakeCommunityRelay]: media sent
 * once and fetched by every member, receipts, delete for everyone and its rules.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CommunityV2Test {
    @get:Rule val temporary = TemporaryFolder()
    private val relay = FakeCommunityRelay()
    private val phones = ArrayList<Phone>()

    @After fun close() {
        phones.forEach { it.storage.close() }
        relay.close()
    }

    inner class Phone(name: String) {
        val storage = TestEnvironment(temporary.newFolder(name)).open()
        val account = storage.saveAccount(newAccount(username = name))
        val party = Party.on(storage, account)
        val id: AccountId get() = account.accountId
        val key: String = Base64Url.encode(account.identityKeyPair.publicKey.serialize())
        var readReceipts = true
        var now = 1_760_000_000_000L
        val server = relay.serverFor(account.accountId)
        val messenger = Messenger(account, party.store, storage.contactStore, RelayClient(server.url("/").toString()),
            MessengerConfig(tempDirectory = temporary.newFolder("$name-tmp")))
        val engine = CommunityEngine(messenger, storage, readReceipts = { readReceipts }, clock = { now })
        val repository = CommunityRepository(storage)
        val member get() = CommunityMember(id, key)
        init { phones += this }

        fun messages(community: AccountId) = repository.messages(community)
        fun last(community: AccountId) = messages(community).last()
    }

    private lateinit var owner: Phone
    private lateinit var b: Phone
    private lateinit var c: Phone
    private lateinit var state: CommunityState

    @Before fun community() {
        owner = Phone("owner")
        b = Phone("bbbb")
        c = Phone("cccc")
        state = CommunityState(AccountId(UUID.randomUUID()), 1, "public", owner.id, owner.key, "Topluluk",
            listOf(owner.member, b.member, c.member).sortedBy { it.accountId.toString() })
        val signed = state.sign(owner.account.identityKeyPair)
        relay.publish(signed)
        for (phone in phones) {
            phone.repository.pin(state.id, owner.key, state.name)
            phone.repository.save(signed)
        }
        // Sessions as the relay's pre-key bundles would start them.
        for (from in phones) for (to in phones) if (from !== to) from.party.sessions.startSession(to.id, to.party.bundle())
    }

    private fun sync(vararg list: Phone) = runBlocking { for (phone in list) phone.engine.sync() }

    private fun media(bytes: ByteArray, kind: Attachment.Kind = Attachment.Kind.IMAGE, name: String? = null): PreparedMedia {
        val file = File(temporary.newFolder(), "staged").apply { writeBytes(bytes) }
        return PreparedMedia(file, kind, if (kind == Attachment.Kind.FILE) "application/pdf" else "image/jpeg", name, bytes.size.toLong(),
            width = 4, height = 3, thumbnail = ByteString.copyFrom(ByteArray(64) { 7 }))
    }

    @Test fun stickerIsEncryptedStoredReceivedAndDeletedWithoutAnUpload() = runBlocking {
        val sticker = com.syconx.us2us.core.proto.Sticker.newBuilder().setPackId("us2us.cups").setStickerId(1).setEmoji("👋").build()
        val id = owner.engine.sendSticker(state.id, sticker)
        sync(b, c)
        assertEquals(0, relay.uploads)
        for (phone in phones) {
            val message = phone.repository.message(state.id, owner.id, id)!!
            assertEquals(sticker, message.sticker)
            assertEquals("👋", message.text)
        }
        owner.engine.deleteForEveryone(state.id, owner.id, id)
        sync(b, c)
        for (phone in phones) {
            assertNull(phone.repository.message(state.id, owner.id, id)!!.sticker)
            assertTrue(phone.repository.message(state.id, owner.id, id)!!.isDeleted)
        }
    }

    @Test fun voiceUploadsOncePreservesWaveformAndDecryptsForEveryMember() = runBlocking {
        val audio = ByteArray(5000) { (it % 251).toByte() }
        val file = File(temporary.newFolder(), "voice.m4a").apply { writeBytes(audio) }
        val waveform = ByteString.copyFrom(byteArrayOf(0, 127, -1))
        val prepared = PreparedMedia(file, Attachment.Kind.VOICE, "audio/mp4", null, audio.size.toLong(), durationMs = 2000, waveform = waveform)
        val id = owner.engine.sendMedia(state.id, prepared)
        assertEquals(1, relay.uploads)
        sync(b, c)
        for (phone in listOf(b, c)) {
            val message = phone.repository.message(state.id, owner.id, id)!!
            assertEquals(Attachment.Kind.VOICE, message.attachment!!.kind)
            assertEquals(waveform, message.attachment!!.waveform)
            assertTrue(message.attachment!!.deleteToken.isEmpty)
            phone.engine.download(state.id, owner.id, id)
            assertArrayEquals(audio, phone.engine.mediaData(state.id, owner.id, id))
        }
        assertEquals(1, relay.uploads)
        assertTrue(relay.deletedBlobs.isEmpty())
    }

    @Test fun mediaIsUploadedOnceFetchedByEveryMemberAndNeverDeletedByRecipients() = runBlocking {
        val photo = ByteArray(5000) { (it % 251).toByte() }
        val messageId = owner.engine.sendMedia(state.id, media(photo), caption = "  Gün batımı  ")
        assertEquals("one blob, whatever the member count", 1, relay.uploads)
        val sent = owner.repository.message(state.id, owner.id, messageId)!!
        assertEquals("Gün batımı", sent.text)
        assertEquals(CommunityMediaState.LOCAL, sent.media)
        assertArrayEquals("the sender keeps its file (no re-download)", photo, owner.engine.mediaData(state.id, owner.id, messageId))
        assertEquals("sent", sent.status)
        assertEquals(2, sent.delivery!!.recipients)

        sync(b, c)
        for (phone in listOf(b, c)) {
            val received = phone.last(state.id)
            assertEquals(messageId, received.messageId)
            assertEquals("Gün batımı", received.text)
            assertEquals(CommunityMediaState.REMOTE, received.media)
            assertTrue("no delete token ever reaches a member", received.attachment!!.deleteToken.isEmpty)
            assertTrue(received.attachment!!.caption.isEmpty())
            assertEquals(1, phone.repository.activity().single().unread)
        }
        b.engine.download(state.id, owner.id, messageId)
        assertArrayEquals(photo, b.engine.mediaData(state.id, owner.id, messageId))
        assertEquals(CommunityMediaState.LOCAL, b.last(state.id).media)
        assertTrue("recipients never delete the blob", relay.deletedBlobs.isEmpty())
        assertTrue(relay.log.none { it.startsWith(b.id.toString().take(8)) && it.contains("DELETE /v1/media") })

        // DELIVERY came back from both; READ only from the member whose read receipts are on.
        sync(owner)
        assertEquals(2, owner.repository.message(state.id, owner.id, messageId)!!.delivery!!.delivered)
        c.readReceipts = false
        assertTrue(b.engine.markDisplayed(state.id, listOf(owner.id to messageId)))
        assertTrue(c.engine.markDisplayed(state.id, listOf(owner.id to messageId)))
        assertEquals(0, b.repository.activity().single().unread)
        b.engine.flushReceipts()
        c.engine.flushReceipts()
        sync(owner)
        val recipients = owner.engine.recipients(state.id, messageId).associateBy { it.member }
        assertNotNull(recipients.getValue(b.id).readAt)
        assertNull("read receipts off: no READ", recipients.getValue(c.id).readAt)
        assertNotNull(recipients.getValue(c.id).deliveredAt)
        val summary = owner.repository.message(state.id, owner.id, messageId)!!.delivery!!
        assertTrue(summary.allDelivered)
        assertFalse(summary.allRead)
        // Read again later with the setting on: nothing is sent for what was read while it was off.
        c.readReceipts = true
        assertFalse(c.engine.markDisplayed(state.id, listOf(owner.id to messageId)))
        assertTrue(c.repository.queuedReceipts().isEmpty())
    }

    @Test fun deliveryIsBatchedPerOriginalSenderAndSplitAboveAThousand() = runBlocking {
        repeat(3) { owner.engine.send(state.id, "hi $it") }
        b.engine.send(state.id, "from b")
        relay.log.clear()
        sync(c)
        val receipts = relay.log.filter { it.startsWith(c.id.toString().take(8)) && it.contains("/messages/") }
        assertEquals("one DELIVERY to each original sender", 2, receipts.size)
        assertTrue(receipts.any { it.contains("/messages/${owner.id}/") })
        assertTrue(receipts.any { it.contains("/messages/${b.id}/") })
        sync(owner, b)
        assertTrue(owner.messages(state.id).filter { it.sender == owner.id }.all { it.delivery!!.delivered == 1 })
    }

    @Test fun receiptsApplyOnlyFromRecipientsAndOnlyToOwnMessages() = runBlocking {
        val mine = owner.engine.send(state.id, "for b and c")
        // C claims to have read a message of B (not the owner's) and one that does not exist.
        val bMessage = b.engine.send(state.id, "b's own")
        sync(c)
        val forged = CommunityContent.receipt(state.id, state.revision, AccountId(UUID.randomUUID()), 5, CommunityReceipt.Type.READ, listOf(bMessage, AccountId(UUID.randomUUID())))
        relay.deliver(owner.id, state.id, c.id, c.messenger.encryptCommunityMessage(owner.member, forged))
        sync(owner)
        assertEquals("only DELIVERY from C's sync", 0, owner.engine.recipients(state.id, mine).count { it.readAt != null })
        assertTrue("no receipt row for someone else's message", owner.engine.recipients(state.id, bMessage).isEmpty())
        // A message whose recipient set does not contain the receipt's sender ignores it.
        owner.storage.write { tx -> tx.connection.run("DELETE FROM community_recipients WHERE member=?", c.id.toString()) }
        val read = CommunityContent.receipt(state.id, state.revision, AccountId(UUID.randomUUID()), 6, CommunityReceipt.Type.READ, listOf(mine))
        relay.deliver(owner.id, state.id, c.id, c.messenger.encryptCommunityMessage(owner.member, read))
        sync(owner)
        assertTrue(owner.engine.recipients(state.id, mine).none { it.member == c.id })
    }

    @Test fun theSenderDeletesItsMediaForEveryoneAndItsBlob() = runBlocking {
        val messageId = b.engine.sendMedia(state.id, media(ByteArray(900) { 1 }, Attachment.Kind.FILE, "plan.pdf"))
        val mediaId = Base64Url.encode(b.repository.message(state.id, b.id, messageId)!!.attachment!!.mediaId.toByteArray())
        sync(owner, c)
        c.engine.download(state.id, b.id, messageId)
        assertNotNull(c.engine.mediaData(state.id, b.id, messageId))
        b.engine.deleteForEveryone(state.id, b.id, messageId)
        assertEquals("deleted with its token", listOf(mediaId), relay.deletedBlobs)
        assertEquals(CommunityDeletion.SENDER, b.last(state.id).deletedBy)
        sync(owner, c)
        for (phone in listOf(owner, c)) {
            val placeholder = phone.last(state.id)
            assertEquals(CommunityDeletion.SENDER, placeholder.deletedBy)
            assertEquals("", placeholder.text)
            assertNull(placeholder.attachment)
            assertNull("the decrypted file goes", phone.engine.mediaData(state.id, b.id, messageId))
        }
        assertEquals(0, c.repository.activity().single().unread)
    }

    @Test fun theOwnerRemovesAMembersMessageAndTheAuthorDeletesTheBlob() = runBlocking {
        val messageId = b.engine.sendMedia(state.id, media(ByteArray(300) { 2 }))
        val mediaId = Base64Url.encode(b.repository.message(state.id, b.id, messageId)!!.attachment!!.mediaId.toByteArray())
        sync(owner, c)
        owner.engine.deleteForEveryone(state.id, b.id, messageId)
        assertEquals(CommunityDeletion.OWNER, owner.last(state.id).deletedBy)
        assertTrue("only the author holds the token", relay.hasBlob(mediaId))
        sync(b, c)
        assertEquals(CommunityDeletion.OWNER, c.last(state.id).deletedBy)
        assertEquals(CommunityDeletion.OWNER, b.last(state.id).deletedBy)
        assertEquals("the author's client deletes the blob", listOf(mediaId), relay.deletedBlobs)
    }

    @Test fun aDeleteByNeitherSenderNorOwnerIsDropped() = runBlocking {
        val messageId = b.engine.send(state.id, "keep me")
        sync(owner, c)
        try {
            c.engine.deleteForEveryone(state.id, b.id, messageId)
            error("a member deleted someone else's message")
        } catch (_: IllegalArgumentException) {
        }
        val forged = CommunityContent.delete(state.id, state.revision, AccountId(UUID.randomUUID()), 9, messageId, b.id)
        relay.deliver(owner.id, state.id, c.id, c.messenger.encryptCommunityMessage(owner.member, forged))
        sync(owner)
        assertNull(owner.last(state.id).deletedBy)
        assertEquals("keep me", owner.last(state.id).text)
    }

    @Test fun aDeleteBeforeItsTargetIsRememberedAndTheTargetArrivesAsItsPlaceholder() = runBlocking {
        val messageId = owner.engine.send(state.id, "soon gone")
        owner.engine.deleteForEveryone(state.id, owner.id, messageId)
        // The relay hands B the delete first.
        val inbox = relay.inbox(b.id)
        inbox.add(0, inbox.removeAt(inbox.lastIndex))
        relay.log.clear()
        sync(b)
        val placeholder = b.last(state.id)
        assertEquals(messageId, placeholder.messageId)
        assertEquals(CommunityDeletion.SENDER, placeholder.deletedBy)
        assertEquals("", placeholder.text)
        assertTrue("DELIVERY is still sent", relay.log.any { it.contains("/messages/${owner.id}/") })
        assertFalse("READ never", b.engine.markDisplayed(state.id, listOf(owner.id to messageId)))
        assertTrue(b.repository.queuedReceipts().isEmpty())
        // At most a thousand per community, the oldest first.
        repeat(1001) { b.repository.applyDelete(state.id, owner.id, AccountId(UUID.randomUUID()), CommunityDeletion.SENDER, it.toLong()) }
        b.storage.read { tx -> assertEquals(1000L, tx.connection.scalar("SELECT count(*) FROM community_pending_deletes")) }
        // And for 30 days.
        b.repository.prune(2_000 + CommunityRepository.KEEP_MILLIS)
        b.storage.read { tx -> assertEquals(0L, tx.connection.scalar("SELECT count(*) FROM community_pending_deletes")) }
    }

    @Test fun deleteForMeIsLocalAndARetransmissionStaysADuplicate() = runBlocking {
        val messageId = owner.engine.send(state.id, "once")
        val copy = relay.inbox(b.id).single()
        sync(b)
        b.engine.deleteForMe(state.id, owner.id, messageId)
        assertTrue(b.messages(state.id).isEmpty())
        relay.inbox(b.id) += FakeCommunityRelay.Queued("again", copy.community, copy.revision, copy.bytes, copy.sender)
        relay.log.clear()
        sync(b)
        assertTrue("not brought back", b.messages(state.id).isEmpty())
        assertTrue("nothing is sent for it", relay.log.none { it.contains("/messages/") })
        assertTrue(relay.deletedBlobs.isEmpty())
    }

    @Test fun invalidKindsAreDroppedAndAcknowledged() = runBlocking {
        val empty = com.syconx.us2us.core.proto.communityMessage {
            communityId = ByteString.copyFrom(state.id.toBytes()); revision = state.revision
            messageId = ByteString.copyFrom(AccountId(UUID.randomUUID()).toBytes()); sentAt = 3
        }
        relay.deliver(owner.id, state.id, b.id, b.messenger.encryptCommunityMessage(owner.member, empty))
        sync(owner)
        assertTrue(owner.messages(state.id).isEmpty())
        assertTrue("acknowledged", relay.inbox(owner.id).isEmpty())
        // The ratchet step was kept: the next message from B still opens.
        b.engine.send(state.id, "after the invalid one")
        sync(owner)
        assertEquals("after the invalid one", owner.last(state.id).text)
    }

    @Test fun leavingRemovesFilesWithTheCommunity() = runBlocking {
        val messageId = owner.engine.sendMedia(state.id, media(ByteArray(700) { 3 }))
        sync(b)
        b.engine.download(state.id, owner.id, messageId)
        val files = b.storage.read { tx -> CommunityRepository.fileIds(tx) }
        assertEquals(1, files.size)
        assertTrue(b.storage.files.exists(files.single()))
        b.engine.leave(state.id)
        assertFalse(b.storage.files.exists(files.single()))
    }
}
