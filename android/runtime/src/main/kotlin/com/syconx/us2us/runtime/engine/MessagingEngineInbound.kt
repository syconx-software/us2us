package com.syconx.us2us.runtime.engine

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.contacts.MessageRequests
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.proto.ControlMessage
import com.syconx.us2us.core.proto.ConversationSettings
import com.syconx.us2us.core.proto.ConversationWipe
import com.syconx.us2us.core.proto.DataMessage
import com.syconx.us2us.core.proto.MessageRequestResponse
import com.syconx.us2us.core.proto.PresenceMessage
import com.syconx.us2us.core.proto.ProfileAvatar
import com.syconx.us2us.core.proto.ReceiptMessage
import com.syconx.us2us.core.proto.content
import com.syconx.us2us.core.proto.controlMessage
import com.syconx.us2us.core.proto.profileUpdate
import com.syconx.us2us.core.proto.receiptMessage
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.runtime.storage.DataMessageOutcome
import com.syconx.us2us.runtime.storage.StorageTransaction
import com.syconx.us2us.runtime.storage.WipeReason
import com.syconx.us2us.runtime.support.RuntimeLog
import com.syconx.us2us.runtime.support.logLabel
import com.syconx.us2us.runtime.support.utf8Prefix
import com.google.protobuf.ByteString
import java.time.Instant

/** What one inbound message changed, applied after its transaction committed. */
internal class InboundOutcome(val peer: AccountId) {
    var listChanged = false
    var messagesChanged = false
    var newMessage = false
    val notices = ArrayList<SystemNotice>()
    var safetyNumberChanged = false
    var typing: Boolean? = null
    var presence: PresenceMessage? = null
    var forgetPresence = false
    var downloads = false
    var wiped = false
    var wakeOutbox = false
}

internal object InboundRules {
    /** Whether the message carries an attachment (or a custom sticker's image). */
    fun hasAttachment(data: DataMessage): Boolean = when (data.payloadCase) {
        DataMessage.PayloadCase.ATTACHMENT -> true
        DataMessage.PayloadCase.STICKER -> data.sticker.hasCustom()
        else -> false
    }

    /** Whether a received content takes part in relay-id de-duplication. */
    fun isRecordedForDedupe(content: Content): Boolean = when (content.bodyCase) {
        Content.BodyCase.TYPING, Content.BodyCase.PRESENCE -> false
        else -> true
    }

    /** A visible message from an unknown account opens a request; a stranger's receipts, typing, presence or control messages are ignored. */
    fun opensRequest(data: DataMessage): Boolean = when (data.payloadCase) {
        DataMessage.PayloadCase.TEXT,
        DataMessage.PayloadCase.ATTACHMENT,
        DataMessage.PayloadCase.LOCATION,
        DataMessage.PayloadCase.STICKER,
        -> true
        else -> false
    }

    /** §8.4: delivery receipts always, for accepted contacts' visible messages. */
    fun wantsDeliveryReceipt(content: Content, state: ContactState): Boolean =
        state == ContactState.ACCEPTED && content.hasData() && opensRequest(content.data)

    fun settingsNotice(before: ConversationSettings, after: ConversationSettings): SystemNotice = SystemNotice.SettingsChanged(
        SystemNotice.SettingsChange(
            ghost = GhostSettings.of(after.ghost),
            expireTimerSeconds = after.defaultExpireTimerSeconds.unsigned(),
            previousGhost = GhostSettings.of(before.ghost),
            previousExpireTimerSeconds = before.defaultExpireTimerSeconds.unsigned(),
        ),
    )

    fun receipt(type: ReceiptMessage.Type, ids: List<ByteString>): Content =
        content { receipt = receiptMessage { this.type = type; messageIds += ids } }

    fun control(build: com.syconx.us2us.core.proto.ControlMessageKt.Dsl.() -> Unit): Content = content { control = controlMessage(build) }
}

internal fun MessagingEngine.receiveMessage(message: InboundMessage) {
    if (!running) engineFailure(EngineError.STOPPED)
    val now = clock.now()
    val outcome = try {
        write { tx -> applyInbound(message, tx, now) }
    } catch (e: Exception) {
        try {
            storage.protocolStorage.discardStagedInboundWrites()
        } catch (_: Exception) {
        }
        RuntimeLog.messaging.error("inbound not stored", e.logLabel)
        throw e
    }
    apply(outcome, now)
}

private fun MessagingEngine.applyInbound(message: InboundMessage, tx: StorageTransaction, now: Instant): InboundOutcome {
    val sender = message.sender
    val content = message.content
    val outcome = InboundOutcome(sender)
    // Re-delivered after a restart (the relay re-sends until acknowledged). Typing and presence are
    // never stored by the relay and harmless to see twice: they leave no entry.
    if (InboundRules.isRecordedForDedupe(content)) {
        if (tx.envelopes.contains(message.envelopeId)) return outcome
        tx.envelopes.record(message.envelopeId, now)
    }

    var previous = tx.contacts.requestState(sender)
    // §6.4: messages from a blocked account are dropped locally.
    if (previous == ContactState.BLOCKED) return outcome
    if (previous == ContactState.OUTGOING_REQUEST && !tx.contacts.requestSent(sender)) {
        // We only opened this handle (search, contact code, invite link); nothing of ours reached
        // it. Its messages count as a stranger's: a visible one is its own request to us, anything
        // else (a bare ACCEPT, receipts, typing, settings) is ignored (audit 2026-10-28 M-9).
        if (!content.hasData() || !InboundRules.opensRequest(content.data)) return outcome
        tx.contacts.turnUnsentRequestIntoIncoming(sender)
        previous = ContactState.INCOMING_REQUEST
        outcome.listChanged = true
    }
    if (previous == null) {
        if (!content.hasData() || !InboundRules.opensRequest(content.data)) return outcome
        outcome.listChanged = true
    }
    var state = tx.contacts.noteVerifiedMessage(content, sender, now)
    val username = message.senderUsername?.let(Username::parse)
    // Verified against the directory by the core (§6.3).
    if (username != null) tx.contacts.confirmUsername(username, sender)
    if (previous == ContactState.OUTGOING_REQUEST && state == ContactState.OUTGOING_REQUEST && username != null &&
        content.hasData() && InboundRules.opensRequest(content.data)
    ) {
        // §6.4 crossing requests: we wrote first (the relay took it), and so did they (only an
        // OUTGOING_REQUEST carries `sender_username`). Neither side could ever accept, so both
        // treat this as mutual acceptance; our ACCEPT makes the other phone agree in any case.
        tx.contacts.acceptCrossingRequest(sender)
        state = ContactState.ACCEPTED
        tx.outbox.enqueue(MessageRequests.acceptContent(), sender, now = now)
        enqueueOurAccessKey(sender, tx, now)
        sendProfileIfExchanged(sender, tx, now)
        shareSettingsAfterAcceptance(sender, tx, now, outcome)
        outcome.notices += SystemNotice.RequestAccepted
        outcome.listChanged = true
        outcome.downloads = true
        outcome.wakeOutbox = true
    } else if (previous == ContactState.ACCEPTED && username != null &&
        content.hasData() && InboundRules.opensRequest(content.data)
    ) {
        // §6.4 a request from an accepted contact: only an OUTGOING_REQUEST carries
        // `sender_username`, so the contact deleted us on its phone and wrote again as a stranger.
        // Our ACCEPT ends its request at once (nothing here changes); our access key and profile
        // go again, since it no longer holds them.
        tx.contacts.setOurAccessKeySent(null, sender)
        tx.contacts.setProfileSent(null, sender)
        tx.outbox.enqueue(MessageRequests.acceptContent(), sender, now = now)
        enqueueOurAccessKey(sender, tx, now)
        sendProfileIfExchanged(sender, tx, now)
        outcome.wakeOutbox = true
    }
    if (message.identityChanged) {
        outcome.notices += SystemNotice.SafetyNumberChanged
        outcome.safetyNumberChanged = true
    }

    when (content.bodyCase) {
        Content.BodyCase.DATA -> receiveData(content.data, message, state, tx, now, outcome)
        Content.BodyCase.RECEIPT -> receiveReceipt(content.receipt, sender, state, tx, now, outcome)
        Content.BodyCase.TYPING ->
            if (state == ContactState.ACCEPTED && settings.typingIndicators) outcome.typing = content.typing.started
        Content.BodyCase.PRESENCE -> outcome.presence = content.presence
        Content.BodyCase.CONTROL -> receiveControl(content.control, previous, state, sender, tx, now, outcome)
        else -> Unit
    }
    return outcome
}

// MARK: Data messages (§8.1–§8.5)

private fun MessagingEngine.receiveData(
    data: DataMessage,
    message: InboundMessage,
    state: ContactState,
    tx: StorageTransaction,
    now: Instant,
    outcome: InboundOutcome,
) {
    val conversation = tx.conversations.ensure(message.sender, now)
    when (val stored = tx.messages.storeIncoming(data, conversation.id, message.serverTimestamp, now)) {
        is DataMessageOutcome.Stored -> {
            outcome.messagesChanged = true
            outcome.listChanged = true
            outcome.newMessage = true
            if (state == ContactState.ACCEPTED && InboundRules.hasAttachment(data)) outcome.downloads = true
            if (InboundRules.wantsDeliveryReceipt(message.content, state)) {
                tx.outbox.enqueue(InboundRules.receipt(ReceiptMessage.Type.DELIVERY, listOf(stored.message.messageId)), message.sender, now = now)
                outcome.wakeOutbox = true
            }
        }
        is DataMessageOutcome.Reaction, is DataMessageOutcome.Deleted, is DataMessageOutcome.LiveLocationUpdated -> {
            outcome.messagesChanged = true
            outcome.listChanged = true
        }
        DataMessageOutcome.Duplicate, DataMessageOutcome.Ignored -> Unit
    }
}

// MARK: Receipts

private fun receiveReceipt(
    receipt: ReceiptMessage,
    sender: AccountId,
    state: ContactState,
    tx: StorageTransaction,
    now: Instant,
    outcome: InboundOutcome,
) {
    // SAVED comes only from accepted contacts (§8.4); our own photos, videos and files are checked by the repository.
    if (receipt.type == ReceiptMessage.Type.SAVED && state != ContactState.ACCEPTED) return
    val conversation = tx.conversations.forContact(sender) ?: return
    val changed = tx.messages.applyReceipt(receipt, conversation.id, now)
    if (receipt.type == ReceiptMessage.Type.VIEWED) {
        // A view-once item is gone from both phones once opened.
        for (id in changed) {
            val message = tx.messages.get(id, conversation.id, now, includeExpired = true) ?: continue
            if (message.isOutgoing && message.isViewOnce) tx.messages.consumeViewOnce(id, conversation.id, now)
        }
    }
    outcome.messagesChanged = changed.isNotEmpty()
}

// MARK: Control messages

private fun MessagingEngine.receiveControl(
    control: ControlMessage,
    previous: ContactState?,
    state: ContactState,
    sender: AccountId,
    tx: StorageTransaction,
    now: Instant,
    outcome: InboundOutcome,
) {
    when (control.actionCase) {
        ControlMessage.ActionCase.REQUEST_RESPONSE -> {
            if (previous == ContactState.ACCEPTED && control.requestResponse.type == MessageRequestResponse.Type.ACCEPT) {
                resendOwnStateAfterReacceptance(sender, tx, now, outcome)
                return
            }
            // ACCEPT ended our outgoing request (the storage applied §6.4).
            if (previous != ContactState.OUTGOING_REQUEST || state != ContactState.ACCEPTED) return
            outcome.notices += SystemNotice.RequestAccepted
            outcome.listChanged = true
            outcome.downloads = true
            // The requester sends its access key at once (§6.4); the profile follows when both keys are exchanged (§8.7).
            enqueueOurAccessKey(sender, tx, now)
            sendProfileIfExchanged(sender, tx, now)
            shareSettingsAfterAcceptance(sender, tx, now, outcome)
            outcome.wakeOutbox = true
        }
        ControlMessage.ActionCase.ACCESS_KEY -> {
            if (control.accessKey.accessKey.size() != com.syconx.us2us.core.AccessKey.LENGTH) return
            tx.contacts.setTheirAccessKey(control.accessKey.accessKey, sender)
            if (state == ContactState.ACCEPTED) sendProfileIfExchanged(sender, tx, now)
            outcome.wakeOutbox = true
        }
        ControlMessage.ActionCase.PROFILE -> {
            // Profiles are shared between accepted contacts only (§8.7).
            if (state != ContactState.ACCEPTED) return
            val profile = tx.contacts.applyProfileUpdate(control.profile, sender, now)
            outcome.forgetPresence = !profile.presenceSharing
            outcome.listChanged = true
            outcome.messagesChanged = true
        }
        ControlMessage.ActionCase.SETTINGS -> {
            // Only between accepted contacts: a stranger's request cannot set ghost mode or timers
            // (nor lock them with a far-future `updated_at`, which the storage also clamps).
            if (state != ContactState.ACCEPTED) return
            val conversation = tx.conversations.ensure(sender, now)
            val before = conversation.settings
            val after = tx.conversations.applySettings(control.settings, sender, conversation.id, now)
            if (after == before) return
            outcome.notices += InboundRules.settingsNotice(before, after)
            outcome.listChanged = true
            outcome.messagesChanged = true
        }
        ControlMessage.ActionCase.WIPE -> {
            // "Delete this conversation on both phones now"; never answered.
            val conversation = tx.conversations.forContact(sender) ?: return
            val reason = if (control.wipe.reason == ConversationWipe.Reason.GHOST_TIMEOUT) WipeReason.GHOST_TIMEOUT else WipeReason.REQUESTED
            storage.wipeConversation(conversation.id, reason, now)
            forgetNotices(conversation.id)
            outcome.wiped = true
            outcome.listChanged = true
        }
        ControlMessage.ActionCase.SCREENSHOT -> {
            outcome.notices += SystemNotice.ScreenshotByContact
            outcome.messagesChanged = true
        }
        else -> Unit
    }
}

// MARK: After the commit

private fun MessagingEngine.apply(outcome: InboundOutcome, now: Instant) {
    val peer = outcome.peer
    for (notice in outcome.notices) addNotice(notice, peer, now)
    if (outcome.safetyNumberChanged) {
        held += peer
        emit(EngineChange.SafetyNumberChanged(peer))
    }
    outcome.typing?.let { started ->
        if (started) {
            typing[peer] = now.plusMillis(configuration.typingTimeout.inWholeMilliseconds)
        } else {
            typing.remove(peer)
        }
        emit(EngineChange.Typing)
    }
    outcome.presence?.let { receivePresence(it, peer, now) }
    if (outcome.forgetPresence) {
        presence.forget(peer)
        emit(EngineChange.Presence)
    }
    if (outcome.wiped) {
        typing.remove(peer)
        emit(EngineChange.Wiped(peer))
    }
    if (outcome.messagesChanged || outcome.notices.isNotEmpty()) emit(EngineChange.Messages(peer))
    if (outcome.listChanged) emit(EngineChange.Conversations)
    if (outcome.newMessage) emit(EngineChange.NewMessage(peer))
    if (outcome.downloads) startDownloads()
    if (outcome.wakeOutbox) outboxWake.signal()
    mirrorNoticeDeadline(peer)
    schedulerWake.signal()
}

private fun MessagingEngine.receivePresence(message: PresenceMessage, peer: AccountId, now: Instant) {
    // Reciprocal (§8.6): only eligible contacts are recorded or answered.
    val contact = try {
        read { it.contacts.get(peer) }
    } catch (_: Exception) {
        null
    } ?: return
    if (!isPresenceEligible(contact)) return
    presence.record(message, peer, now)
    emit(EngineChange.Presence)
    if (message.state == PresenceMessage.State.ONLINE && !message.reply) queuePresenceReply(peer)
}

// MARK: Access keys and profiles (§6.4, §8.7)

/** Our current access key to an accepted contact, once per key. */
internal fun MessagingEngine.enqueueOurAccessKey(account: AccountId, tx: StorageTransaction, now: Instant) {
    val key = ourAccessKey(tx)
    val contact = tx.contacts.get(account) ?: return
    if (contact.requestState != ContactState.ACCEPTED || contact.ourAccessKeySent == key) return
    tx.outbox.enqueue(MessageRequests.accessKeyUpdateContent(com.syconx.us2us.core.AccessKey(key.toByteArray())), account, now = now)
    tx.contacts.setOurAccessKeySent(key, account)
}

/**
 * §6.4: `ACCEPT` from a contact we were already accepted with. It had deleted us on its phone (which
 * keeps the session), we wrote again without `sender_username`, and it accepted that as a new
 * request: it no longer holds our access key or profile, so both go again. Only after a completed
 * exchange; while ours is still under way (crossing requests, a repeated `ACCEPT`) it finishes by
 * itself. The state stays accepted and nothing is shown.
 */
private fun MessagingEngine.resendOwnStateAfterReacceptance(account: AccountId, tx: StorageTransaction, now: Instant, outcome: InboundOutcome) {
    val contact = tx.contacts.get(account) ?: return
    if (contact.requestState != ContactState.ACCEPTED || contact.ourAccessKeySent != ourAccessKey(tx) || contact.profileSentAt == null) return
    tx.contacts.setOurAccessKeySent(null, account)
    tx.contacts.setProfileSent(null, account)
    enqueueOurAccessKey(account, tx, now)
    sendProfileIfExchanged(account, tx, now)
    outcome.wakeOutbox = true
}

/** The complete `ProfileUpdate`, once the access keys are exchanged. */
internal fun MessagingEngine.sendProfileIfExchanged(account: AccountId, tx: StorageTransaction, now: Instant) {
    val key = ourAccessKey(tx)
    val contact = tx.contacts.get(account) ?: return
    if (contact.requestState != ContactState.ACCEPTED || contact.theirAccessKey == null ||
        contact.ourAccessKeySent != key || contact.profileSentAt != null
    ) {
        return
    }
    tx.outbox.enqueue(ownProfile(tx, now), account, now = now)
    tx.contacts.setProfileSent(now, account)
}

/**
 * The complete `ProfileUpdate` (§8.7): the display name and presence setting from the runtime
 * state, the username and the profile picture (§8.9) from the account in the vault. Every field
 * is set; the deprecated `avatar` (field 2) never is.
 */
internal fun MessagingEngine.ownProfile(tx: StorageTransaction, now: Instant): Content {
    val account = tx.account.load() ?: engineFailure(EngineError.STOPPED)
    val picture: ProfileAvatar? = account.profilePicture?.toProto()
    val user = settings
    val update = profileUpdate {
        displayName = user.displayName.utf8Prefix(ContentLimits.MAX_DISPLAY_NAME_BYTES)
        username = account.username.value
        presenceSharing = user.presenceSharing
        updatedAt = now.toEpochMilli()
        picture?.let { profileAvatar = it }
    }
    return InboundRules.control { profile = update }
}

/**
 * A conversation just became accepted. Settings we wrote while it was our outgoing request go out
 * again (the contact dropped them; settings count only between accepted contacts); a conversation
 * that never had settings gets the user's defaults.
 */
internal fun MessagingEngine.shareSettingsAfterAcceptance(peer: AccountId, tx: StorageTransaction, now: Instant, outcome: InboundOutcome) {
    val conversation = tx.conversations.ensure(peer, now)
    val author = conversation.settingsAuthor
    if (author != null) {
        if (author != me) return
        tx.outbox.enqueue(InboundRules.control { settings = conversation.settings }, peer, conversation.id, now = now)
        outcome.wakeOutbox = true
        return
    }
    applyDefaultSettingsIfUnset(peer, tx, now, outcome)
}

/** The user's default ghost / disappearing settings for a conversation that just became accepted and never had settings written. */
internal fun MessagingEngine.applyDefaultSettingsIfUnset(peer: AccountId, tx: StorageTransaction, now: Instant, outcome: InboundOutcome) {
    val ghost = settings.defaultGhost?.takeIf { it.enabled }
    val timer = settings.defaultExpireTimerSeconds
    if (ghost == null && timer <= 0) return
    val conversation = tx.conversations.ensure(peer, now)
    if (conversation.settingsAuthor != null) return
    val before = conversation.settings
    val after = tx.conversations.updateSettings(conversation.id, now) { builder ->
        builder.defaultExpireTimerSeconds = timer.toInt()
        if (ghost != null) builder.ghost = ghost.proto
    }
    tx.outbox.enqueue(InboundRules.control { settings = after }, peer, conversation.id, now = now)
    outcome.notices += InboundRules.settingsNotice(before, after)
    outcome.wakeOutbox = true
}
