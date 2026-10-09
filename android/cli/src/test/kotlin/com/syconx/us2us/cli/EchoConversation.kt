package com.syconx.us2us.cli

import com.google.protobuf.kotlin.toByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.attachment.AttachmentCipher
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.contacts.InMemoryContactStore
import com.syconx.us2us.core.contacts.UsernameStatus
import com.syconx.us2us.core.encoding.toHex
import com.syconx.us2us.core.messenger.ConnectionState
import com.syconx.us2us.core.messenger.IncomingMessage
import com.syconx.us2us.core.messenger.Messenger
import com.syconx.us2us.core.messenger.OutgoingAttachment
import com.syconx.us2us.core.presence.PresenceStatus
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.ConversationWipe
import com.syconx.us2us.core.proto.MessageRequestResponse
import com.syconx.us2us.core.proto.PresenceMessage
import com.syconx.us2us.core.proto.ReceiptMessage
import com.syconx.us2us.core.proto.content
import com.syconx.us2us.core.proto.controlMessage
import com.syconx.us2us.core.proto.conversationSettings
import com.syconx.us2us.core.proto.conversationWipe
import com.syconx.us2us.core.proto.ghostMode
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.store.InMemoryProtocolStorage
import com.syconx.us2us.core.testing.TestClient
import com.syconx.us2us.core.username.Username
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * One conversation with an echo responder ([EchoBot], or the iOS `LiveEchoResponderTests`), as the
 * echo contract in [Echo] describes it: a message request in Turkish answered with ACCEPT and an
 * access key, profiles both ways, an attachment and a location with their delivery receipts,
 * "active now" presence, ghost-mode settings and a conversation wipe. Afterwards the tester's
 * account is deleted. The expected answers are literals on purpose: they are the contract the
 * responders on both platforms implement.
 */
internal suspend fun talkToEchoResponder(scope: CoroutineScope, relayUrl: String, responder: AccountId, responderUsername: String) {
    val tester = RecordingClient(
        Messenger.register(
            Username("tester_" + Random.nextInt(1_000_000, 9_999_999)),
            RelayClient(relayUrl),
            InMemoryProtocolStorage(),
            InMemoryContactStore(),
            TestClient.testConfig,
        ),
        scope,
    )
    val messenger = tester.messenger
    tester.awaitUpToDate()
    assertEquals(responder, messenger.findByUsername(responderUsername)?.accountId)

    // A message request (proof-of-work, sender username) is accepted, then echoed.
    messenger.sendText(responder, "Merhaba 👋 dünya")
    val accept = tester.await("ACCEPT") { it.sender == responder && it.content.control.requestResponse.type == MessageRequestResponse.Type.ACCEPT }
    assertFalse(accept.identityChanged)
    tester.awaitText(responder, "echo: Merhaba 👋 dünya")
    val contact = checkNotNull(messenger.contact(responder))
    assertEquals(ContactState.ACCEPTED, contact.state)
    assertNotNull(contact.theirAccessKey, "the responder's access key arrives with its acceptance")

    // Our access key and profile go out by themselves; the responder's profile comes back.
    val profile = tester.await("the responder's profile") { it.sender == responder && it.content.control.hasProfile() }.content.control.profile
    assertEquals(responderUsername, profile.username)
    assertTrue(profile.presenceSharing)
    assertEquals(UsernameStatus.VERIFIED, messenger.contact(responder)?.usernameStatus)

    val file = Random.nextBytes(150_000)
    val attachment = messenger.sendAttachment(
        responder,
        OutgoingAttachment(Attachment.Kind.FILE, "application/octet-stream", source = { file.inputStream() }, fileName = "veri.bin"),
    )
    tester.awaitText(responder, "echo-attachment: ${AttachmentCipher.sha256(file).toHex()}")

    val location = messenger.sendLocation(responder, 41.0086, 28.9802, accuracyMeters = 12f, name = "Ayasofya, İstanbul")
    tester.awaitText(responder, "echo-location: 41.008600,28.980200 Ayasofya, İstanbul")
    for ((what, sent) in listOf("attachment" to attachment, "location" to location)) {
        tester.await("a delivery receipt for the $what") {
            it.sender == responder && it.content.hasReceipt() && it.content.receipt.type == ReceiptMessage.Type.DELIVERY &&
                it.content.receipt.messageIdsList.any { id -> id == sent.messageId.toByteString() }
        }
    }

    // Reopening the app announces ONLINE to eligible contacts; the responder answers once (§8.6).
    tester.reconnect()
    tester.awaitUpToDate()
    tester.await("the responder's presence reply") {
        it.sender == responder && it.content.hasPresence() && it.content.presence.state == PresenceMessage.State.ONLINE && it.content.presence.reply
    }
    tester.eventually("the responder shown as active") { messenger.presence(responder) == PresenceStatus.Active }
    messenger.announceOffline()

    val now = System.currentTimeMillis()
    messenger.send(
        responder,
        content {
            control = controlMessage {
                settings = conversationSettings {
                    updatedAt = now
                    ghost = ghostMode { enabled = true; timeoutSeconds = 3600; wipeContact = false }
                }
            }
        },
    )
    tester.awaitText(responder, "echo-settings: ghost=on,timeout=3600,wipe_contact=false,expire=0")
    messenger.send(
        responder,
        content { control = controlMessage { wipe = conversationWipe { requestedAt = now; reason = ConversationWipe.Reason.USER_REQUEST } } },
    )
    tester.awaitText(responder, "echo-wipe: USER_REQUEST")

    // Deleting the account closes our socket with 4401, so the receive loop stops first.
    tester.job.cancelAndJoin()
    messenger.deleteAccount()
}

/** A messenger whose receive loop keeps every message, so a test can wait for one in any order. */
internal class RecordingClient(val messenger: Messenger, private val scope: CoroutineScope) {
    private val received = MutableStateFlow<List<IncomingMessage>>(emptyList())

    var job: Job = start()
        private set

    private fun start(): Job = scope.launch { messenger.runReceiveLoop { message -> received.update { it + message } } }

    /** Like reopening the app: a new connection, after which presence is announced again. */
    suspend fun reconnect() {
        job.cancelAndJoin()
        job = start()
    }

    suspend fun awaitUpToDate() = withTimeout(TIMEOUT) { messenger.connection.first { it == ConnectionState.UP_TO_DATE } }

    /** The first message so far (or to come) that matches [predicate]. */
    suspend fun await(what: String, predicate: (IncomingMessage) -> Boolean): IncomingMessage = try {
        withTimeout(TIMEOUT) { received.mapNotNull { messages -> messages.firstOrNull(predicate) }.first() }
    } catch (_: TimeoutCancellationException) {
        throw AssertionError("no $what within $TIMEOUT; received: ${received.value.map { it.content.bodyCase }}")
    }

    suspend fun eventually(what: String, condition: () -> Boolean) = try {
        withTimeout(TIMEOUT) { while (!condition()) delay(20) }
    } catch (_: TimeoutCancellationException) {
        throw AssertionError("$what: not within $TIMEOUT")
    }

    suspend fun awaitText(from: AccountId, text: String) =
        await("\"$text\"") { it.sender == from && it.content.hasData() && it.content.data.hasText() && it.content.data.text.body == text }

    private companion object {
        /** Generous: it only turns a hang into a failure, also on a loaded machine. */
        val TIMEOUT = 60.seconds
    }
}
