package com.syconx.us2us.core.messenger

import com.google.protobuf.kotlin.toByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.proto.DataMessage
import com.syconx.us2us.core.proto.ReceiptMessage
import com.syconx.us2us.core.proto.attachment
import com.syconx.us2us.core.proto.content
import com.syconx.us2us.core.proto.dataMessage
import com.syconx.us2us.core.proto.location
import com.syconx.us2us.core.proto.receiptMessage
import com.syconx.us2us.core.proto.text
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.testing.FakeRelay
import com.syconx.us2us.core.testing.TestClient
import com.syconx.us2us.core.username.Username
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.util.UUID

/** `ReceiptMessage{SAVED}` (PROTOCOL.md §8.4): who sends it, for what, and what the sender applies. */
class SavedMediaTest {
    private val contact = AccountId(UUID.randomUUID())
    private val someoneElse = AccountId(UUID.randomUUID())

    private fun media(kind: Attachment.Kind, viewOnce: Boolean = false, timer: Int = 0, id: Int = 1): DataMessage {
        val file = attachment {
            this.kind = kind
            contentType = "application/octet-stream"
        }
        return dataMessage {
            messageId = ByteArray(16) { id.toByte() }.toByteString()
            sentAt = 1_760_000_000_000
            attachment = file
            this.viewOnce = viewOnce
            expireTimerSeconds = timer
        }
    }

    private fun receipt(type: ReceiptMessage.Type, vararg messages: DataMessage): Content = content {
        receipt = receiptMessage {
            this.type = type
            messageIds += messages.map { it.messageId }
        }
    }

    private fun incoming(content: Content, from: AccountId = contact, state: ContactState = ContactState.ACCEPTED) =
        IncomingMessage(
            envelopeId = "envelope",
            serverTimestamp = 0,
            sender = from,
            senderUsername = null,
            content = content,
            contactState = state,
            identityChanged = false,
        )

    @Test
    fun `only permanent photos, videos and files can be saved`() {
        for (kind in listOf(Attachment.Kind.IMAGE, Attachment.Kind.VIDEO, Attachment.Kind.FILE)) {
            assertTrue(SavedMedia.isSavable(media(kind)), kind.name)
            // View-once and disappearing items cannot be saved, so they never get SAVED.
            assertFalse(SavedMedia.isSavable(media(kind, viewOnce = true)), kind.name)
            assertFalse(SavedMedia.isSavable(media(kind, timer = 3600)), kind.name)
        }
        for (kind in listOf(Attachment.Kind.VOICE, Attachment.Kind.STICKER_IMAGE, Attachment.Kind.KIND_UNSPECIFIED)) {
            assertFalse(SavedMedia.isSavable(media(kind)), kind.name)
        }
        assertFalse(SavedMedia.isSavable(dataMessage { text = text { body = "hi" } }))
        assertFalse(SavedMedia.isSavable(dataMessage { location = location { latitude = 41.0 } }))
    }

    @Test
    fun `SAVED goes to accepted contacts only, whatever the read-receipt setting`() {
        assertTrue(SavedMedia.maySend(ContactState.ACCEPTED))
        ContactState.entries.filter { it != ContactState.ACCEPTED }.forEach { assertFalse(SavedMedia.maySend(it), it.name) }
    }

    @Test
    fun `SAVED is type 4, and a type a build does not know is not SAVED`() {
        assertEquals(4, ReceiptMessage.Type.SAVED.number)
        val bytes = receipt(ReceiptMessage.Type.SAVED, media(Attachment.Kind.IMAGE)).toByteArray()
        assertEquals(ReceiptMessage.Type.SAVED, Content.parseFrom(bytes).receipt.type)
        // Type 9, from some later build: field 1 of the receipt (field 2 of Content).
        val future = Content.parseFrom(byteArrayOf(0x12, 0x02, 0x08, 0x09))
        assertEquals(ReceiptMessage.Type.UNRECOGNIZED, future.receipt.type)
        assertFalse(SavedMedia.appliesTo(incoming(future), media(Attachment.Kind.IMAGE), contact))
    }

    @Test
    fun `the sender applies SAVED only to its own savable message to that contact`() {
        val photo = media(Attachment.Kind.IMAGE, id = 1)
        val file = media(Attachment.Kind.FILE, id = 2)
        val saved = incoming(receipt(ReceiptMessage.Type.SAVED, photo, file))
        assertTrue(SavedMedia.appliesTo(saved, photo, sentTo = contact))
        assertTrue(SavedMedia.appliesTo(saved, file, sentTo = contact))

        // A message to someone else, or one the receipt does not name.
        assertFalse(SavedMedia.appliesTo(saved, photo, sentTo = someoneElse))
        assertFalse(SavedMedia.appliesTo(saved, media(Attachment.Kind.IMAGE, id = 3), sentTo = contact))
        // Kinds that cannot be saved, even when named.
        for (unsavable in listOf(
            media(Attachment.Kind.IMAGE, viewOnce = true, id = 4),
            media(Attachment.Kind.VIDEO, timer = 60, id = 5),
            media(Attachment.Kind.VOICE, id = 6),
        )) {
            assertFalse(SavedMedia.appliesTo(incoming(receipt(ReceiptMessage.Type.SAVED, unsavable)), unsavable, sentTo = contact))
        }
        // Only from an accepted contact, and only SAVED.
        ContactState.entries.filter { it != ContactState.ACCEPTED }.forEach { state ->
            assertFalse(SavedMedia.appliesTo(incoming(receipt(ReceiptMessage.Type.SAVED, photo), state = state), photo, sentTo = contact), state.name)
        }
        for (type in listOf(ReceiptMessage.Type.DELIVERY, ReceiptMessage.Type.READ, ReceiptMessage.Type.VIEWED)) {
            assertFalse(SavedMedia.appliesTo(incoming(receipt(type, photo)), photo, sentTo = contact), type.name)
        }
        assertFalse(SavedMedia.appliesTo(incoming(content { data = photo }), photo, sentTo = contact))
    }

    @Test
    fun `saving a received photo tells its sender, once the request was accepted`() = runBlocking {
        FakeRelay().start().use { relay ->
            val alice = Messenger.register(Username("alice"), RelayClient(relay.url), config = TestClient.testConfig)
            val bob = Messenger.register(Username("bob"), RelayClient(relay.url), config = TestClient.testConfig)
            alice.findByUsername("bob")
            val image = OutgoingAttachment(Attachment.Kind.IMAGE, "image/jpeg", source = { ByteArrayInputStream(ByteArray(64)) })
            val sent = alice.sendAttachment(bob.accountId, image)
            val once = alice.sendAttachment(bob.accountId, image, viewOnce = true)

            val received = mutableListOf<IncomingMessage>()
            bob.fetchPending { received += it }
            val photo = received.single { it.content.data.messageId.toByteArray().contentEquals(sent.messageId) }.content.data
            val viewOnce = received.single { it.content.data.messageId.toByteArray().contentEquals(once.messageId) }.content.data
            assertEquals(ContactState.INCOMING_REQUEST, bob.contact(alice.accountId)?.state)

            // Never for a message request.
            assertNull(bob.sendSavedReceipt(alice.accountId, photo, receivedFrom = alice.accountId))
            bob.acceptMessageRequest(alice.accountId)
            alice.fetchPending { }
            assertEquals(ContactState.ACCEPTED, alice.contact(bob.accountId)?.state)

            // A view-once item cannot be saved, and SAVED never goes out as a plain receipt.
            assertThrows<IllegalArgumentException> {
                runBlocking { bob.sendSavedReceipt(alice.accountId, viewOnce, receivedFrom = alice.accountId) }
            }
            // Only to the account the item came from.
            assertThrows<IllegalArgumentException> {
                runBlocking { bob.sendSavedReceipt(alice.accountId, photo, receivedFrom = bob.accountId) }
            }
            assertThrows<IllegalArgumentException> {
                runBlocking { bob.sendReceipt(alice.accountId, ReceiptMessage.Type.SAVED, listOf(sent.messageId)) }
            }
            assertNotNull(bob.sendSavedReceipt(alice.accountId, photo, receivedFrom = alice.accountId))

            val atAlice = mutableListOf<IncomingMessage>()
            alice.fetchPending { atAlice += it }
            val saved = atAlice.single { it.content.hasReceipt() && it.content.receipt.type == ReceiptMessage.Type.SAVED }
            assertEquals(1, saved.content.receipt.messageIdsCount)
            assertArrayEquals(sent.messageId, saved.content.receipt.getMessageIds(0).toByteArray())
            // Alice holds the photo she sent (the same DataMessage Bob received).
            assertTrue(SavedMedia.appliesTo(saved, photo, sentTo = bob.accountId))
            assertFalse(SavedMedia.appliesTo(saved, viewOnce, sentTo = bob.accountId))
        }
    }
}
