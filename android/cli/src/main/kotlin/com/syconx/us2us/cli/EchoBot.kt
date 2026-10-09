package com.syconx.us2us.cli

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.attachment.AttachmentCipher
import com.syconx.us2us.core.attachment.AttachmentIntegrityException
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.encoding.toHex
import com.syconx.us2us.core.messenger.IdentityChangedException
import com.syconx.us2us.core.messenger.IncomingMessage
import com.syconx.us2us.core.messenger.Messenger
import com.syconx.us2us.core.messenger.SendNotAllowedException
import com.syconx.us2us.core.proto.ControlMessage
import com.syconx.us2us.core.proto.ConversationSettings
import com.syconx.us2us.core.proto.ConversationWipe
import com.syconx.us2us.core.proto.DataMessage
import com.syconx.us2us.core.proto.Location
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale

/**
 * A minimal real client for interop tests. It accepts every message request and answers each
 * message with one text (see [Echo]): texts, attachments (downloaded, verified, decrypted and
 * deleted first), locations, conversation settings and conversation wipes. The bot keeps no
 * history, so a wipe has nothing to erase; it is only answered. Receipts, access keys, profiles
 * and presence are handled by [Messenger] itself (an eligible contact's ONLINE is answered once).
 *
 * Message contents are never logged, only who sent what kind of message.
 */
internal class EchoBot(private val messenger: Messenger, private val log: (String) -> Unit = {}) {

    /** Runs until cancelled, or until the relay hands the account's connection to another client. */
    suspend fun run() = messenger.runReceiveLoop(::onMessage)

    /** Never throws for a single bad message or network hiccup: that would stop the receive loop. */
    private suspend fun onMessage(message: IncomingMessage) {
        val sender = message.sender
        try {
            if (message.contactState == ContactState.INCOMING_REQUEST) {
                // If this fails, the request stays open and the sender's next message retries it.
                log("accepting message request from $sender")
                messenger.acceptMessageRequest(sender)
            }
            val content = message.content
            when {
                content.hasData() -> onData(sender, content.data)
                content.hasControl() -> onControl(sender, content.control)
            }
        } catch (e: IOException) {
            log("could not answer $sender: ${e.message}")
        } catch (e: AttachmentIntegrityException) {
            log("rejected a tampered attachment from $sender: ${e.message}")
        } catch (e: IdentityChangedException) {
            log("not answering $sender: ${e.message}")
        } catch (e: SendNotAllowedException) {
            log("not answering $sender: ${e.message}")
        }
    }

    private suspend fun onData(sender: AccountId, data: DataMessage) {
        val reply = when (data.payloadCase) {
            DataMessage.PayloadCase.TEXT -> Echo.text(data.text.body)
            DataMessage.PayloadCase.ATTACHMENT -> {
                val plaintext = ByteArrayOutputStream()
                messenger.downloadAttachment(data.attachment, plaintext)
                Echo.attachment(AttachmentCipher.sha256(plaintext.toByteArray()).toHex())
            }
            DataMessage.PayloadCase.LOCATION -> Echo.location(data.location)
            else -> null
        }
        answer(sender, data.payloadCase.name, reply)
    }

    private suspend fun onControl(sender: AccountId, control: ControlMessage) {
        val reply = when (control.actionCase) {
            ControlMessage.ActionCase.SETTINGS -> Echo.settings(control.settings)
            ControlMessage.ActionCase.WIPE -> Echo.wipe(control.wipe)
            // Access keys, profiles and request responses are the messenger's business.
            else -> return
        }
        answer(sender, control.actionCase.name, reply)
    }

    private suspend fun answer(sender: AccountId, kind: String, reply: String?) {
        if (reply == null) {
            log("ignoring $kind from $sender")
            return
        }
        log("${kind.lowercase(Locale.ROOT)} from $sender")
        messenger.sendText(sender, reply)
    }
}

/**
 * The bot's answers, a small contract for live interop tests. Every client-side echo responder
 * answers exactly like this (the iOS one is `LiveEchoResponderTests` in ios/Us2usKit), so the same
 * tests drive either.
 */
internal object Echo {
    fun text(body: String) = "echo: $body"

    fun attachment(plaintextSha256Hex: String) = "echo-attachment: $plaintextSha256Hex"

    /** Coordinates with six decimals (about 0.1 m), then the place name if there is one. */
    fun location(location: Location): String {
        val coordinates = String.format(Locale.ROOT, "%.6f,%.6f", location.latitude, location.longitude)
        return if (location.name.isEmpty()) "echo-location: $coordinates" else "echo-location: $coordinates ${location.name}"
    }

    fun settings(settings: ConversationSettings): String {
        val ghost = settings.ghost
        return "echo-settings: ghost=${if (ghost.enabled) "on" else "off"},timeout=${ghost.timeoutSeconds}," +
            "wipe_contact=${ghost.wipeContact},expire=${settings.defaultExpireTimerSeconds}"
    }

    /** The reason as named in brp.proto, e.g. `USER_REQUEST`. */
    fun wipe(wipe: ConversationWipe) = "echo-wipe: ${wipe.reason.name}"
}
