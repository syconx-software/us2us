package com.syconx.us2us.core.messenger

import com.syconx.us2us.core.attachment.AttachmentCipher
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.contacts.InMemoryContactStore
import com.syconx.us2us.core.presence.PresenceStatus
import com.syconx.us2us.core.encoding.toHex
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.MessageRequestResponse
import com.syconx.us2us.core.proto.PresenceMessage
import com.syconx.us2us.core.proto.ReceiptMessage
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.relay.RelayErrorCode
import com.syconx.us2us.core.relay.RelayException
import com.syconx.us2us.core.store.InMemoryProtocolStorage
import com.syconx.us2us.core.testing.TestClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * The end-to-end conversations every relay must support: two fresh accounts register with
 * proof-of-work, find each other by username, go through a message request, exchange access keys,
 * text both ways, send an attachment, and acknowledge everything; and presence plus a restore of
 * one account on a new phone.
 *
 * Subclasses provide the relay; relay-specific checks go into the hooks.
 */
abstract class ConversationScenario {
    protected abstract val relayUrl: String

    /** Fresh, unique usernames for this run. */
    protected open fun usernames(): Pair<String, String> {
        val suffix = (1..8).map { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }.joinToString("")
        return "ka_$suffix" to "kb_$suffix"
    }

    /** Called after each send to [recipient] whose delivery method is known: `"pow"` or `"access-key"`. */
    protected open fun expectDelivery(recipient: TestClient, method: String) = Unit

    /** Called at the end, with both receive loops still running. */
    protected open suspend fun finalChecks(alice: TestClient, bob: TestClient) = Unit

    @Test
    fun `message request, acceptance, texts both ways, attachment and acks`() = runBlocking {
        coroutineScope { runScenario(this) }
    }

    @Test
    fun `presence after reopening the app, then a restore on a new phone`() = runBlocking {
        coroutineScope {
            val (aliceName, bobName) = usernames()
            val alice = TestClient.register(relayUrl, aliceName, this)
            val bob = TestClient.register(relayUrl, bobName, this)
            alice.awaitUpToDate()
            bob.awaitUpToDate()
            alice.messenger.findByUsername(bobName)
            alice.messenger.sendText(bob.accountId, "selam")
            bob.nextData()
            bob.messenger.acceptMessageRequest(alice.accountId)
            // Access keys, then profiles (with presence_sharing) are exchanged by themselves.
            withTimeout(60.seconds) {
                while (alice.messenger.contact(bob.accountId)?.presenceSharing != true || bob.messenger.contact(alice.accountId)?.presenceSharing != true) {
                    delay(50)
                }
            }

            // Bob reopens the app: ONLINE goes out in one ephemeral batch, Alice answers once.
            bob.job.cancelAndJoin()
            val bobAgain = TestClient(bob.messenger, this)
            val online = alice.next { it.content.hasPresence() }.content.presence
            assertEquals(PresenceMessage.State.ONLINE, online.state)
            assertTrue(!online.reply)
            assertTrue(bobAgain.next { it.content.hasPresence() }.content.presence.reply)
            assertEquals(PresenceStatus.Active, alice.messenger.presence(bob.accountId))
            assertEquals(PresenceStatus.Active, bob.messenger.presence(alice.accountId))
            bob.messenger.announceOffline()
            assertEquals(PresenceMessage.State.OFFLINE, alice.next { it.content.hasPresence() }.content.presence.state)

            // Alice moves to a new phone with her backup file.
            val backup = alice.messenger.exportBackup("yeni telefon 42", iterations = 1000)
            alice.job.cancelAndJoin()
            val restored = Messenger.restore(backup, "yeni telefon 42", RelayClient(relayUrl), InMemoryProtocolStorage(), InMemoryContactStore(), TestClient.testConfig)
            // Keys are replaced at once; the profile announcement waits for the receive loop (§5.5).
            assertEquals(setOf(PendingSetup.ANNOUNCE_PROFILE), restored.account.pendingSetup)
            val oldPhone = assertThrows<RelayException> { runBlocking { alice.messenger.maintainPreKeys() } }
            assertEquals(RelayErrorCode.UNAUTHORIZED, oldPhone.code)

            val newPhone = TestClient(restored, this)
            newPhone.awaitUpToDate()
            val announced = bobAgain.next { it.content.hasControl() && it.content.control.hasProfile() }.content.control.profile
            assertEquals(aliceName, announced.username)
            newPhone.messenger.sendText(bob.accountId, "yeni telefon, aynı ben")
            val fromNewPhone = bobAgain.nextData()
            assertEquals("yeni telefon, aynı ben", fromNewPhone.content.data.text.body)
            assertTrue(!fromNewPhone.identityChanged, "same identity key, so the same safety number")
            bob.messenger.sendText(alice.accountId, "hoş geldin")
            assertEquals("hoş geldin", newPhone.nextData().content.data.text.body)

            finalChecks(newPhone, bobAgain)
            newPhone.job.cancelAndJoin()
            bobAgain.job.cancelAndJoin()
        }
    }

    private suspend fun runScenario(scope: CoroutineScope) {
        val (aliceName, bobName) = usernames()
        val alice = TestClient.register(relayUrl, aliceName, scope)
        val bob = TestClient.register(relayUrl, bobName, scope)
        alice.awaitUpToDate()
        bob.awaitUpToDate()

        // Directory lookup is exact-match on the normalized name.
        val found = checkNotNull(alice.messenger.findByUsername("  ${bobName.uppercase()} "))
        assertEquals(bob.accountId, found.accountId)
        assertEquals(ContactState.OUTGOING_REQUEST, found.state)

        // First contact: a message request, delivered with proof-of-work and carrying the username.
        alice.messenger.sendText(bob.accountId, "Merhaba Bob 👋")
        expectDelivery(bob, "pow")
        val request = bob.nextData()
        assertEquals("Merhaba Bob 👋", request.content.data.text.body)
        assertEquals(alice.accountId, request.sender)
        assertEquals(aliceName, request.senderUsername)
        assertEquals(ContactState.INCOMING_REQUEST, request.contactState)
        assertTrue(!request.identityChanged)

        // While the request is pending, Bob shares nothing back.
        assertThrows<SendNotAllowedException> { runBlocking { bob.messenger.sendText(alice.accountId, "too early") } }
        assertNull(bob.messenger.sendReceipt(alice.accountId, ReceiptMessage.Type.DELIVERY, listOf(request.content.data.messageId.toByteArray())))
        assertTrue(!bob.messenger.sendTyping(alice.accountId, true))

        // Bob accepts: ACCEPT plus his access key.
        bob.messenger.acceptMessageRequest(alice.accountId)
        val accept = alice.next { it.content.hasControl() && it.content.control.hasRequestResponse() }
        assertEquals(MessageRequestResponse.Type.ACCEPT, accept.content.control.requestResponse.type)
        assertEquals(ContactState.ACCEPTED, accept.contactState)
        alice.next { it.content.hasControl() && it.content.control.hasAccessKey() }
        assertNotNull(alice.messenger.contact(bob.accountId)?.theirAccessKey)

        // Alice answers with her own key, so both directions stop paying proof-of-work.
        bob.next { it.content.hasControl() && it.content.control.hasAccessKey() }
        assertNotNull(bob.messenger.contact(alice.accountId)?.theirAccessKey)
        assertEquals(ContactState.ACCEPTED, bob.messenger.contact(alice.accountId)?.state)

        bob.messenger.sendText(alice.accountId, "Hoş geldin Alice")
        expectDelivery(alice, "access-key")
        val reply = alice.nextData()
        assertEquals("Hoş geldin Alice", reply.content.data.text.body)
        assertEquals(bobName, reply.senderUsername)

        val sent = alice.messenger.sendText(bob.accountId, "İkinci mesaj", expireTimerSeconds = 3600)
        expectDelivery(bob, "access-key")
        val second = bob.nextData()
        assertEquals("İkinci mesaj", second.content.data.text.body)
        assertEquals(3600, second.content.data.expireTimerSeconds)
        assertArrayEquals(sent.messageId, second.content.data.messageId.toByteArray())

        // Bob's phone sends a delivery receipt by itself once the message is stored; read
        // receipts are the app's choice.
        val delivered = alice.next { it.content.hasReceipt() }.content.receipt
        assertEquals(ReceiptMessage.Type.DELIVERY, delivered.type)
        assertArrayEquals(sent.messageId, delivered.getMessageIds(0).toByteArray())
        assertNotNull(bob.messenger.sendReceipt(alice.accountId, ReceiptMessage.Type.READ, listOf(sent.messageId)))
        val read = alice.next { it.content.hasReceipt() && it.content.receipt.type == ReceiptMessage.Type.READ }
        assertArrayEquals(sent.messageId, read.content.receipt.getMessageIds(0).toByteArray())

        // An attachment: encrypted on the phone, uploaded, downloaded, verified and deleted.
        val photo = Random.nextBytes(300_000)
        alice.messenger.sendAttachment(
            bob.accountId,
            OutgoingAttachment(Attachment.Kind.IMAGE, "image/jpeg", source = { ByteArrayInputStream(photo) }, width = 640, height = 480, caption = "Foto"),
        )
        val withPhoto = bob.nextData()
        val attachment = withPhoto.content.data.attachment
        assertEquals(photo.size.toLong(), attachment.size)
        assertEquals("Foto", attachment.caption)
        val downloaded = ByteArrayOutputStream()
        bob.messenger.downloadAttachment(attachment, downloaded)
        assertEquals(AttachmentCipher.sha256(photo).toHex(), AttachmentCipher.sha256(downloaded.toByteArray()).toHex())

        // Live-only messages: typing indicators are ephemeral.
        assertTrue(alice.messenger.sendTyping(bob.accountId, true))
        assertTrue(bob.next { it.content.hasTyping() }.content.typing.started)

        // Everything was handled; anything still at the relay is only an acknowledgement in flight.
        bob.messenger.fetchPending { error("unexpected message from ${it.sender}") }
        alice.messenger.fetchPending { error("unexpected message from ${it.sender}") }

        finalChecks(alice, bob)
        alice.job.cancelAndJoin()
        bob.job.cancelAndJoin()
    }
}
