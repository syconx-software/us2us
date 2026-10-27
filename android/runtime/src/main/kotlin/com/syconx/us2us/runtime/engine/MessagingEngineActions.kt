package com.syconx.us2us.runtime.engine

import com.syconx.us2us.core.AccessKey
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.contacts.MessageRequests
import com.syconx.us2us.core.messenger.SavedMedia
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.proto.ConversationWipe
import com.syconx.us2us.core.proto.DataMessage
import com.syconx.us2us.core.proto.ReceiptMessage
import com.syconx.us2us.core.proto.content
import com.syconx.us2us.core.proto.conversationWipe
import com.syconx.us2us.core.proto.dataMessage
import com.syconx.us2us.core.proto.delete
import com.syconx.us2us.core.proto.location
import com.syconx.us2us.core.proto.quote
import com.syconx.us2us.core.proto.reaction
import com.syconx.us2us.core.proto.screenshotNotice
import com.syconx.us2us.core.proto.sticker
import com.syconx.us2us.core.proto.text
import com.syconx.us2us.core.proto.typingMessage
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.runtime.storage.ConversationId
import com.syconx.us2us.runtime.storage.DataMessageOutcome
import com.syconx.us2us.runtime.storage.IdentityVerification
import com.syconx.us2us.runtime.storage.IdentityVerificationMethod
import com.syconx.us2us.runtime.storage.MessageKind
import com.syconx.us2us.runtime.storage.OutboxState
import com.syconx.us2us.runtime.storage.RandomIds
import com.syconx.us2us.runtime.storage.StorageException
import com.syconx.us2us.runtime.storage.StorageTransaction
import com.syconx.us2us.runtime.storage.StoredAttachment
import com.syconx.us2us.runtime.storage.strippingViewOncePreview
import com.syconx.us2us.runtime.support.RuntimeLog
import com.syconx.us2us.runtime.support.logLabel
import com.syconx.us2us.runtime.vault.VaultFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

// MARK: Requests (§6.4)

/**
 * Someone blocked gets their read-only chat back (ghost mode may have erased it, and with it the
 * only place that offers "Engeli kaldır"); nothing is sent to them until they are unblocked.
 */
internal suspend fun MessagingEngine.startConversationNow(username: Username): AccountId {
    val entry = backend.findAccount(username) ?: engineFailure(EngineError.USERNAME_NOT_FOUND)
    val account = entry.account
    if (account == me) engineFailure(EngineError.OWN_ACCOUNT)
    val now = clock.now()
    write { tx ->
        when (tx.contacts.requestState(account)) {
            null -> tx.contacts.create(account, ContactState.OUTGOING_REQUEST, now)
            ContactState.BLOCKED -> tx.contacts.unblockAsOutgoingRequest(account)
            else -> Unit
        }
        // Our own lookup proves the username belongs to this account (§3.3), and no longer to any
        // other contact still showing it (a deleted account whose name someone else registered).
        tx.contacts.confirmUsername(username, account)
        tx.contacts.noteUsernameOwner(username, account)
        tx.conversations.ensure(account, now)
    }
    emit(EngineChange.Conversations)
    return account
}

/**
 * `ACCEPT` is sent directly, and the state changes only after the relay answered `202`, so a
 * failure leaves the request as it was and accepting can be retried. Then our access key, the
 * profile once both keys are exchanged, the delivery receipts the request's messages did not get,
 * and its held media.
 */
internal suspend fun MessagingEngine.acceptRequestNow(peer: AccountId) {
    val contact = read { it.contacts.get(peer) } ?: engineFailure(EngineError.UNKNOWN_CONTACT)
    if (contact.requestState != ContactState.INCOMING_REQUEST) engineFailure(EngineError.NOT_ALLOWED)
    backend.send(MessageRequests.acceptContent(), peer, ContactState.INCOMING_REQUEST, contact.theirAccessKey, ephemeral = false)
    val now = clock.now()
    val outcome = InboundOutcome(peer)
    write { tx ->
        tx.contacts.acceptIncomingRequest(peer)
        enqueueOurAccessKey(peer, tx, now)
        sendProfileIfExchanged(peer, tx, now)
        tx.conversations.forContact(peer)?.let { conversation ->
            val owed = tx.messages.page(conversation.id, limit = configuration.pageSize, now = now)
                .filter { !it.isOutgoing && !it.isDeleted && wantsReceipt(it.kind) }
                .map { it.messageId }
            for (chunk in owed.chunked(ContentLimits.MAX_RECEIPT_IDS)) {
                tx.outbox.enqueue(InboundRules.receipt(ReceiptMessage.Type.DELIVERY, chunk), peer, now = now)
            }
        }
        shareSettingsAfterAcceptance(peer, tx, now, outcome)
    }
    for (notice in outcome.notices) addNotice(notice, peer, now)
    addNotice(SystemNotice.RequestAccepted, peer, now)
    outboxWake.signal()
    startDownloads()
    emit(EngineChange.Conversations)
    emit(EngineChange.Messages(peer))
}

/** "Sil": deletes the request (crypto-shredded) and the contact. Nothing is sent, so the requester cannot tell whether it was seen. */
internal fun MessagingEngine.declineRequestNow(peer: AccountId) {
    write { tx ->
        if (tx.contacts.requestState(peer) != ContactState.INCOMING_REQUEST) engineFailure(EngineError.NOT_ALLOWED)
        tx.conversations.forContact(peer)?.let { forgetNotices(it.id) }
        tx.contacts.forget(peer)
    }
    typing.remove(peer)
    emit(EngineChange.Wiped(peer))
    emit(EngineChange.Conversations)
}

/**
 * Blocks a contact (§6.4): its messages are dropped locally from now on, pending sends to it are
 * cancelled (their messages become *Failed*), and our access key is rotated — the new one goes to
 * every other accepted contact, so the blocked account cannot use the old one.
 */
internal suspend fun MessagingEngine.blockNow(peer: AccountId) {
    val now = clock.now()
    write { tx ->
        tx.contacts.block(peer, now)
        for (entry in tx.outbox.all()) {
            if (entry.recipient != peer) continue
            val messageId = entry.messageId
            val conversationId = entry.conversationId
            if (messageId != null && conversationId != null) tx.messages.markFailed(messageId, conversationId, now)
            tx.outbox.remove(entry.id)
        }
    }
    presence.forget(peer)
    typing.remove(peer)
    held.remove(peer)
    // Nothing may reach someone blocked, so no share with them keeps running (the app stops it first).
    endLiveSessions(peer)
    runtime = runtime.copy(pendingAccessKeyRotation = true)
    saveRuntime()
    emit(EngineChange.Conversations)
    emit(EngineChange.Messages(peer))
    emit(EngineChange.Presence)
    rotateAccessKeyIfPending()
}

/**
 * Unblocks: the contact returns to its state before the block. An accepted contact gets our
 * *current* access key and our profile again (§6.4). Nothing happens for a contact that is not
 * blocked (a second tap sends nothing twice). One with nothing to go back to (no conversation, and
 * no contact before the block: 7.1 › Engellenenler lists bare blocks "leave no trace" kept) is
 * forgotten: a stranger again, not an empty request.
 */
internal fun MessagingEngine.unblockNow(peer: AccountId) {
    val now = clock.now()
    val unblocked = write { tx ->
        if (tx.contacts.unblockLeavingNothing(peer)) return@write true
        if (!tx.contacts.unblock(peer)) return@write false
        val contact = tx.contacts.get(peer)
        if (contact == null || contact.requestState != ContactState.ACCEPTED) return@write true
        enqueueOurAccessKey(peer, tx, now)
        if (contact.profileSentAt != null) {
            tx.outbox.enqueue(ownProfile(tx, now), peer, now = now)
            tx.contacts.setProfileSent(now, peer)
        } else {
            sendProfileIfExchanged(peer, tx, now)
        }
        true
    }
    if (!unblocked) return
    outboxWake.signal()
    emit(EngineChange.Conversations)
}

/** Finishes the access-key rotation a block asked for; retried after every connection until it succeeds. */
internal suspend fun MessagingEngine.rotateAccessKeyIfPending() {
    if (!runtime.pendingAccessKeyRotation) return
    try {
        val key = backend.rotateAccessKey()
        runtime = runtime.copy(pendingAccessKeyRotation = false)
        saveRuntime()
        val now = clock.now()
        write { tx ->
            for (contact in tx.contacts.needingAccessKey(key)) {
                tx.outbox.enqueue(MessageRequests.accessKeyUpdateContent(AccessKey(key.toByteArray())), contact.accountId, now = now)
                tx.contacts.setOurAccessKeySent(key, contact.accountId)
            }
        }
        outboxWake.signal()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RuntimeLog.messaging.info("access key rotation postponed", e.logLabel)
    }
}

/** After a restore (§5.5 step 5): the restored phone has no sessions, so it writes first, to every accepted contact. */
internal suspend fun MessagingEngine.finishRestoreAnnouncementIfNeeded() {
    if (!backend.needsProfileAnnouncement()) return
    val now = clock.now()
    val (profile, contacts) = try {
        read { tx -> ownProfile(tx, now) to tx.contacts.accepted().map { Recipient(it.accountId, it.theirAccessKey) } }
    } catch (_: Exception) {
        return
    }
    try {
        backend.announceProfile(profile, contacts)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return
    }
    try {
        write { tx -> for (contact in contacts) tx.contacts.setProfileSent(now, contact.account) }
    } catch (_: Exception) {
    }
}

// MARK: Composing (§8)

internal fun MessagingEngine.sendNow(
    outgoing: OutgoingContent,
    peer: AccountId,
    reply: QuoteInfo?,
    viewOnce: Boolean,
    chosenId: MessageId?,
): MessageId {
    val now = clock.now()
    if (chosenId != null && chosenId.size() != RandomIds.LENGTH) engineFailure(EngineError.INVALID_MESSAGE)
    val id = chosenId ?: RandomIds.make()
    var media: com.syconx.us2us.runtime.media.PreparedMedia? = null
    val template = DataMessage.newBuilder()
    when (outgoing) {
        is OutgoingContent.Text -> {
            if (outgoing.text.isEmpty() || outgoing.text.toByteArray(Charsets.UTF_8).size > ContentLimits.MAX_TEXT_BYTES) {
                engineFailure(EngineError.TOO_LARGE)
            }
            val words = outgoing.text
            template.text = text { body = words }
        }
        is OutgoingContent.Location -> {
            val place = outgoing.location
            template.location = location {
                latitude = place.latitude
                longitude = place.longitude
                accuracyMeters = place.accuracyMeters
                name = place.name.orEmpty()
                address = place.address.orEmpty()
            }
        }
        is OutgoingContent.Sticker -> {
            if (outgoing.lang.length > OutgoingContent.MAX_STICKER_LANG_LENGTH) engineFailure(EngineError.TOO_LARGE)
            val chosen = outgoing
            template.sticker = sticker {
                packId = chosen.packId
                stickerId = chosen.stickerId
                emoji = chosen.emoji
                lang = chosen.lang
            }
        }
        is OutgoingContent.Media -> {
            val file = outgoing.prepared
            if ((file.caption?.toByteArray(Charsets.UTF_8)?.size ?: 0) > ContentLimits.MAX_CAPTION_BYTES ||
                (file.thumbnail?.size() ?: 0) > ContentLimits.MAX_THUMBNAIL_BYTES
            ) {
                engineFailure(EngineError.TOO_LARGE)
            }
            // View-once means nothing of it is visible before it is opened: a caption would be.
            if (viewOnce && !file.caption.isNullOrEmpty()) engineFailure(EngineError.NOT_ALLOWED)
            media = file
            template.attachment = file.pendingAttachment
        }
    }
    val prepared = media
    write { tx ->
        // §6.4: sending to an account without state makes it an outgoing request; answering a
        // request means accepting it first.
        val state = try {
            tx.contacts.prepareToSend(peer, now)
        } catch (_: StorageException) {
            engineFailure(EngineError.NOT_ALLOWED)
        }
        if (state == ContactState.INCOMING_REQUEST) engineFailure(EngineError.NOT_ALLOWED)
        val conversation = tx.conversations.ensure(peer, now)
        val builder = template.clone()
            .setMessageId(id)
            .setSentAt(now.toEpochMilli())
            .setExpireTimerSeconds(conversation.settings.defaultExpireTimerSeconds)
            .setViewOnce(viewOnce && prepared != null)
        if (reply != null) {
            // The excerpt comes from the stored target, never from the caller, and only from one that stays (§9).
            val preview = quotePreview(reply.targetMessageId, conversation.id, tx, now).orEmpty()
            val target = reply.targetMessageId
            builder.quote = quote {
                targetMessageId = target
                previewText = preview
            }
        }
        val data = builder.build().strippingViewOncePreview()
        // A chosen id that is already taken stores nothing: refused, not queued twice.
        if (tx.messages.storeOutgoing(data, conversation.id, now) == DataMessageOutcome.Duplicate) {
            engineFailure(EngineError.INVALID_MESSAGE)
        }
        if (prepared != null) {
            for (attachment in tx.attachments.forMessage(id, conversation.id)) {
                tx.attachments.setState(com.syconx.us2us.runtime.storage.AttachmentState.PENDING_UPLOAD, attachment.id, now = now)
                tx.attachments.importFile(prepared.file, attachment.id, now)
            }
        }
        tx.outbox.enqueue(
            content { this.data = data },
            peer,
            conversation.id,
            messageId = id,
            waitingForUpload = prepared != null,
            now = now,
        )
    }
    afterLocalChange(peer)
    return id
}

/** "Tekrar gönder": a failed message goes back into the queue; a message the relay dropped after 14 days is sent again as new. */
internal fun MessagingEngine.resendNow(messageId: MessageId, peer: AccountId): MessageId {
    val now = clock.now()
    val retried = write { tx ->
        val conversation = tx.conversations.forContact(peer) ?: engineFailure(EngineError.INVALID_MESSAGE)
        val message = tx.messages.get(messageId, conversation.id, now)
        if (message == null || !message.isOutgoing) engineFailure(EngineError.INVALID_MESSAGE)
        val entry = tx.outbox.all(OutboxState.FAILED).firstOrNull { it.messageId == messageId }
        if (entry != null) {
            tx.outbox.retry(entry.id, now)
            true
        } else {
            false
        }
    }
    if (retried) {
        afterLocalChange(peer)
        return messageId
    }
    val data = read { tx ->
        tx.conversations.forContact(peer)?.let { tx.messages.get(messageId, it.id, now) }?.content
    } ?: engineFailure(EngineError.INVALID_MESSAGE)
    return when (data.payloadCase) {
        DataMessage.PayloadCase.TEXT -> sendNow(OutgoingContent.Text(data.text.body), peer, null, false, null)
        DataMessage.PayloadCase.LOCATION -> sendNow(
            OutgoingContent.Location(LocationInfo(data.location.latitude, data.location.longitude, data.location.accuracyMeters)),
            peer,
            null,
            false,
            null,
        )
        DataMessage.PayloadCase.STICKER -> when {
            data.sticker.hasCustom() -> engineFailure(EngineError.INVALID_MESSAGE)
            // The plain text sticker of builds up to 1.0.5 is not sent any more (§8.8): its catalog successor goes instead.
            data.sticker.packId == OutgoingContent.LEGACY_TEXT_STICKER_PACK ->
                sendNow(OutgoingContent.LEGACY_TEXT_STICKER_SUCCESSOR, peer, null, false, null)
            else -> sendNow(
                OutgoingContent.Sticker(data.sticker.packId, data.sticker.stickerId, data.sticker.emoji, data.sticker.lang),
                peer,
                null,
                false,
                null,
            )
        }
        else -> engineFailure(EngineError.INVALID_MESSAGE)
    }
}

/** Adds ([emoji]) or removes (`null`) our reaction (§8.5). Not to an unaccepted request: nothing goes back before the user accepts. */
internal fun MessagingEngine.reactNow(target: MessageId, peer: AccountId, emoji: String?) {
    val now = clock.now()
    write { tx ->
        val state = tx.contacts.requestState(peer) ?: engineFailure(EngineError.UNKNOWN_CONTACT)
        if (state != ContactState.ACCEPTED && state != ContactState.OUTGOING_REQUEST) engineFailure(EngineError.NOT_ALLOWED)
        val conversation = tx.conversations.forContact(peer) ?: engineFailure(EngineError.UNKNOWN_CONTACT)
        if (tx.messages.get(target, conversation.id, now) == null) engineFailure(EngineError.INVALID_MESSAGE)
        val chosen = emoji
        val data = dataMessage {
            messageId = RandomIds.make()
            sentAt = now.toEpochMilli()
            reaction = reaction {
                targetMessageId = target
                this.emoji = chosen.orEmpty()
                remove = chosen == null
            }
        }
        tx.messages.storeOutgoing(data, conversation.id, now)
        tx.outbox.enqueue(content { this.data = data }, peer, conversation.id, now = now)
    }
    afterLocalChange(peer)
}

/**
 * Delete for everyone (§8.5), our own messages only: leaves a tombstone, deletes the local media and
 * unsends the uploaded blob. A message the relay has not accepted yet and that is not being handed
 * over right now never left this phone: its queue entry is removed and no `Delete` is sent.
 */
internal suspend fun MessagingEngine.deleteForEveryoneNow(messageId: MessageId, peer: AccountId) {
    val now = clock.now()
    val uploaded = write { tx ->
        val conversation = tx.conversations.forContact(peer) ?: engineFailure(EngineError.UNKNOWN_CONTACT)
        val blobs = tx.attachments.forMessage(messageId, conversation.id).mapNotNull { attachment ->
            attachment.descriptor?.takeIf { !it.mediaId.isEmpty }
        }
        val queued = tx.outbox.entries(messageId, conversation.id)
        val neverLeft = queued.isNotEmpty() && queued.none { it.id in inFlightOutbox }
        try {
            tx.messages.deleteForEveryone(messageId, conversation.id, now)
        } catch (_: StorageException) {
            engineFailure(EngineError.INVALID_MESSAGE)
        }
        if (neverLeft) {
            for (entry in queued) tx.outbox.remove(entry.id)
        } else {
            val target = messageId
            val data = dataMessage {
                this.messageId = RandomIds.make()
                sentAt = now.toEpochMilli()
                delete = delete { targetMessageId = target }
            }
            tx.outbox.enqueue(content { this.data = data }, peer, conversation.id, now = now)
        }
        blobs
    }
    afterLocalChange(peer)
    for (blob in uploaded) {
        try {
            backend.deleteUploadedMedia(blob)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}

/** Delete for me: removes the message on this phone only (an outgoing one not handed to the relay yet is not sent any more). */
internal fun MessagingEngine.deleteForMeNow(messageId: MessageId, peer: AccountId) {
    val now = clock.now()
    write { tx ->
        val conversation = tx.conversations.forContact(peer) ?: engineFailure(EngineError.UNKNOWN_CONTACT)
        tx.messages.deleteLocally(listOf(messageId), conversation.id, now)
    }
    emit(EngineChange.Messages(peer))
    emit(EngineChange.Conversations)
}

/**
 * The excerpt a reply may carry: only of a target that is here and stays — no timer, not
 * view-once, not deleted (§9). `null` otherwise.
 */
internal fun quotePreview(target: MessageId, conversationId: ConversationId, tx: StorageTransaction, now: Instant): String? {
    val message = tx.messages.get(target, conversationId, now) ?: return null
    if (message.isDeleted || message.isViewOnce || message.expireTimerSeconds != 0L) return null
    val data = message.content ?: return null
    val text = when (data.payloadCase) {
        DataMessage.PayloadCase.TEXT -> data.text.body
        DataMessage.PayloadCase.ATTACHMENT -> data.attachment.caption
        DataMessage.PayloadCase.LOCATION -> data.location.name
        else -> ""
    }
    if (text.isEmpty()) return null
    val end = text.offsetByCodePoints(0, minOf(text.codePointCount(0, text.length), ContentLimits.MAX_QUOTE_PREVIEW_CHARACTERS))
    return text.substring(0, end)
}

internal fun MessagingEngine.afterLocalChange(peer: AccountId) {
    outboxWake.signal()
    schedulerWake.signal()
    mirrorNoticeDeadline(peer)
    emit(EngineChange.Messages(peer))
    emit(EngineChange.Conversations)
}

internal fun wantsReceipt(kind: MessageKind): Boolean = when (kind) {
    MessageKind.TEXT, MessageKind.ATTACHMENT, MessageKind.LOCATION, MessageKind.STICKER -> true
    MessageKind.LIVE_LOCATION, MessageKind.UNKNOWN -> false
}

// MARK: Reading (§8.1, §8.4)

/**
 * Received messages are displayed now, which starts their disappearing timers, and read receipts
 * go out if enabled (never for requests, never for view-once).
 */
internal fun MessagingEngine.markDisplayedNow(peer: AccountId) {
    val now = clock.now()
    val changed = write { tx ->
        val conversation = tx.conversations.forContact(peer) ?: return@write false
        val displayed = tx.messages.markAllDisplayed(conversation.id, now)
        if (displayed.isEmpty()) return@write false
        val state = tx.contacts.requestState(peer)
        if (state == ContactState.ACCEPTED && settings.readReceipts) {
            val ids = displayed.filter { id ->
                val message = tx.messages.get(id, conversation.id, now) ?: return@filter false
                !message.isViewOnce && wantsReceipt(message.kind)
            }
            for (chunk in ids.chunked(ContentLimits.MAX_RECEIPT_IDS)) {
                tx.outbox.enqueue(InboundRules.receipt(ReceiptMessage.Type.READ, chunk), peer, now = now)
            }
        }
        true
    }
    if (!changed) return
    outboxWake.signal()
    schedulerWake.signal()
    emit(EngineChange.Messages(peer))
    emit(EngineChange.Conversations)
}

// MARK: Media

/**
 * A received voice note was played for the first time (§8.4): its `viewedAt` is set once, and
 * VIEWED goes to the sender only where a read receipt would.
 */
internal fun MessagingEngine.markVoiceNotePlayedNow(messageId: MessageId, peer: AccountId) {
    val now = clock.now()
    val changed = write { tx ->
        val conversation = tx.conversations.forContact(peer) ?: return@write false
        val message = tx.messages.get(messageId, conversation.id, now) ?: return@write false
        val data = message.content
        if (message.isOutgoing || message.isViewOnce || message.isDeleted || data == null || !data.hasAttachment() ||
            data.attachment.kind != Attachment.Kind.VOICE
        ) {
            return@write false
        }
        if (!tx.messages.markPlayed(messageId, conversation.id, now)) return@write false
        if (tx.contacts.requestState(peer) == ContactState.ACCEPTED && settings.readReceipts) {
            tx.outbox.enqueue(InboundRules.receipt(ReceiptMessage.Type.VIEWED, listOf(messageId)), peer, now = now)
        }
        true
    }
    if (!changed) return
    outboxWake.signal()
    emit(EngineChange.Messages(peer))
}

/**
 * A photo, video or file was saved outside the app (§8.4): `savedByMeAt` is set once and, for a
 * received one from an accepted contact, SAVED goes to the sender in the same transaction —
 * whatever the read-receipt setting. View-once, disappearing and deleted items cannot be saved.
 */
internal fun MessagingEngine.markMediaSavedNow(messageId: MessageId, peer: AccountId) {
    val now = clock.now()
    val changed = write { tx ->
        val conversation = tx.conversations.forContact(peer) ?: engineFailure(EngineError.INVALID_MESSAGE)
        val message = tx.messages.get(messageId, conversation.id, now) ?: engineFailure(EngineError.INVALID_MESSAGE)
        val data = message.content
        if (message.isDeleted || message.isViewOnce || message.expireTimerSeconds != 0L || data == null || !SavedMedia.isSavable(data)) {
            engineFailure(EngineError.INVALID_MESSAGE)
        }
        if (!tx.messages.markSavedByMe(messageId, conversation.id, now)) return@write false
        if (!message.isOutgoing && tx.contacts.requestState(peer) == ContactState.ACCEPTED) {
            tx.outbox.enqueue(InboundRules.receipt(ReceiptMessage.Type.SAVED, listOf(messageId)), peer, now = now)
        }
        true
    }
    if (!changed) return
    outboxWake.signal()
    emit(EngineChange.Messages(peer))
}

internal fun MessagingEngine.openViewOnceDataNow(messageId: MessageId, peer: AccountId): ByteArray {
    val now = clock.now()
    val plaintext = write { tx -> consumeViewOnceForViewing(messageId, peer, tx, now) }
    outboxWake.signal()
    emit(EngineChange.Messages(peer))
    return plaintext
}

internal fun MessagingEngine.openViewOnceNow(messageId: MessageId, peer: AccountId): File {
    val now = clock.now()
    val file = storage.makeStagingFile()
    try {
        write { tx ->
            val plaintext = consumeViewOnceForViewing(messageId, peer, tx, now)
            try {
                VaultFiles.write(file, plaintext)
            } finally {
                plaintext.fill(0)
            }
        }
    } catch (e: Exception) {
        file.delete()
        throw e
    }
    outboxWake.signal()
    emit(EngineChange.Messages(peer))
    return file
}

private fun MessagingEngine.consumeViewOnceForViewing(messageId: MessageId, peer: AccountId, tx: StorageTransaction, now: Instant): ByteArray {
    val conversation = tx.conversations.forContact(peer) ?: engineFailure(EngineError.INVALID_MESSAGE)
    val message = tx.messages.get(messageId, conversation.id, now) ?: engineFailure(EngineError.INVALID_MESSAGE)
    if (!message.isViewOnce || message.isOutgoing || message.content == null) engineFailure(EngineError.INVALID_MESSAGE)
    val attachment = tx.attachments.forMessage(messageId, conversation.id).firstOrNull()
    if (attachment == null || !attachment.hasFile) engineFailure(EngineError.MEDIA_UNAVAILABLE)
    val plaintext = tx.attachments.readFile(attachment.id)
    tx.messages.consumeViewOnce(messageId, conversation.id, now)
    if (tx.contacts.requestState(peer) == ContactState.ACCEPTED) {
        tx.outbox.enqueue(InboundRules.receipt(ReceiptMessage.Type.VIEWED, listOf(messageId)), peer, now = now)
    }
    return plaintext
}

/** The viewer closed: deletes its file. An item still intact (opened some other way) is consumed now. */
internal fun MessagingEngine.closeViewOnceNow(messageId: MessageId, peer: AccountId, displayedFile: File?) {
    displayedFile?.delete()
    val now = clock.now()
    val consumed = write { tx ->
        val conversation = tx.conversations.forContact(peer) ?: engineFailure(EngineError.UNKNOWN_CONTACT)
        val message = tx.messages.get(messageId, conversation.id, now, includeExpired = true) ?: return@write false
        if (!message.isViewOnce || message.isOutgoing || message.content == null) return@write false
        tx.messages.consumeViewOnce(messageId, conversation.id, now)
        if (tx.contacts.requestState(peer) == ContactState.ACCEPTED) {
            tx.outbox.enqueue(InboundRules.receipt(ReceiptMessage.Type.VIEWED, listOf(messageId)), peer, now = now)
        }
        true
    }
    if (!consumed) return
    outboxWake.signal()
    emit(EngineChange.Messages(peer))
}

internal fun MessagingEngine.mediaDataNow(messageId: MessageId, peer: AccountId): ByteArray = read { tx ->
    tx.attachments.readFile(displayableAttachment(messageId, peer, tx).id)
}

internal fun MessagingEngine.exportMediaNow(messageId: MessageId, peer: AccountId): File {
    val (file, conversationId) = read { tx ->
        val attachment = displayableAttachment(messageId, peer, tx)
        tx.attachments.exportToStaging(attachment.id) to attachment.conversationId
    }
    exportedMedia[file] = MessagingEngine.ExportedMedia(conversationId, messageId)
    return file
}

private fun MessagingEngine.displayableAttachment(messageId: MessageId, peer: AccountId, tx: StorageTransaction): StoredAttachment {
    val conversation = tx.conversations.forContact(peer) ?: engineFailure(EngineError.INVALID_MESSAGE)
    val message = tx.messages.get(messageId, conversation.id, clock.now())
    if (message == null || message.isViewOnce || message.isDeleted) engineFailure(EngineError.INVALID_MESSAGE)
    val attachment = tx.attachments.forMessage(messageId, conversation.id).firstOrNull()
    if (attachment == null || !attachment.hasFile) engineFailure(EngineError.MEDIA_UNAVAILABLE)
    return attachment
}

// MARK: Typing (§8.4)

/** Our typing indicator, if enabled: ephemeral, accepted contacts with a key only, "started" at most every few seconds. */
internal suspend fun MessagingEngine.setTypingNow(started: Boolean, peer: AccountId) {
    if (!settings.typingIndicators) return
    val contact = try {
        read { it.contacts.get(peer) }
    } catch (_: Exception) {
        null
    } ?: return
    val key = contact.theirAccessKey ?: return
    if (contact.requestState != ContactState.ACCEPTED || contact.identityChanged) return
    val now = clock.now()
    val last = lastTypingSent[peer]
    if (started && last != null && now.isBefore(last.plusMillis(configuration.typingResendInterval.inWholeMilliseconds))) return
    if (started) lastTypingSent[peer] = now else lastTypingSent.remove(peer)
    try {
        backend.send(content { typing = typingMessage { this.started = started } }, peer, contact.requestState, key, ephemeral = true)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
}

// MARK: Conversation settings (§8.1, §8.2)

/** Sets ghost mode and the default disappearing timer for both sides (LWW; a system line on each phone). */
internal fun MessagingEngine.updateConversationSettingsNow(peer: AccountId, ghost: GhostSettings?, defaultExpireTimerSeconds: Long) {
    if (ghost != null && ghost.enabled && ghost.timeoutSeconds !in ContentLimits.GHOST_TIMEOUT_RANGE) engineFailure(EngineError.TOO_LARGE)
    val now = clock.now()
    val notice = write { tx ->
        val state = tx.contacts.requestState(peer)
        if (state == null || state == ContactState.BLOCKED) engineFailure(EngineError.UNKNOWN_CONTACT)
        // Nothing goes to an unaccepted requester (§6.4): accept first.
        if (state != ContactState.ACCEPTED && state != ContactState.OUTGOING_REQUEST) engineFailure(EngineError.NOT_ALLOWED)
        val conversation = tx.conversations.ensure(peer, now)
        val before = conversation.settings
        val after = tx.conversations.updateSettings(conversation.id, now) { builder ->
            builder.defaultExpireTimerSeconds = defaultExpireTimerSeconds.toInt()
            builder.ghost = (ghost ?: GhostSettings(enabled = false, timeoutSeconds = 0)).proto
        }
        tx.outbox.enqueue(InboundRules.control { settings = after }, peer, conversation.id, now = now)
        InboundRules.settingsNotice(before, after)
    }
    addNotice(notice, peer, now)
    afterLocalChange(peer)
}

// MARK: Screenshots (§9)

/** A screenshot or screen recording while [peer]'s conversation was on screen: a local line, and a `ScreenshotNotice` to accepted contacts. */
internal fun MessagingEngine.screenCapturedNow(peer: AccountId, recording: Boolean) {
    val now = clock.now()
    val accepted = write { tx ->
        val state = tx.contacts.requestState(peer)
        if (state == null || state == ContactState.BLOCKED) return@write false
        val conversation = tx.conversations.forContact(peer)
        if (state != ContactState.ACCEPTED || conversation == null) return@write false
        val notice = screenshotNotice { takenAt = now.toEpochMilli() }
        tx.outbox.enqueue(InboundRules.control { screenshot = notice }, peer, conversation.id, now = now)
        true
    }
    addNotice(if (recording) SystemNotice.ScreenRecordingByMe else SystemNotice.ScreenshotByMe, peer, now)
    if (accepted) outboxWake.signal()
}

// MARK: Safety numbers (§5)

/**
 * Accepts a changed safety number without verifying it: sending resumes, the contact stays
 * unverified. Bound to the key whose number the user was shown; `false`, and nothing changes, when
 * the key changed since or no change was pending.
 */
internal suspend fun MessagingEngine.acceptSafetyNumberChangeNow(peer: AccountId, shownNumber: ByteArray): Boolean {
    if (!backend.approveIdentityChange(peer, shownNumber)) return false
    held.remove(peer)
    addNotice(SystemNotice.SafetyNumberAcceptedUnverified, peer, clock.now())
    outboxWake.signal()
    emit(EngineChange.Conversations)
    return true
}

/** "Doğrulandı olarak işaretle": verifies the contact's key if its safety number is still [comparedNumber]. */
internal suspend fun MessagingEngine.markVerifiedNow(peer: AccountId, comparedNumber: ByteArray): Boolean {
    val verification = IdentityVerification(IdentityVerificationMethod.MANUAL, clock.now())
    val wasVerified = isIdentityVerified(peer)
    if (!backend.markVerified(peer, comparedNumber, verification)) return false
    didVerify(peer, wasVerified)
    return true
}

/** 6.2: compares a safety-number QR payload scanned from the contact's phone and, on a match, verifies the key — one step. */
internal suspend fun MessagingEngine.verifySafetyNumberNow(peer: AccountId, scanned: ByteArray): Boolean {
    val verification = IdentityVerification(IdentityVerificationMethod.QR_CODE, clock.now())
    val wasVerified = isIdentityVerified(peer)
    if (!backend.verifySafetyNumber(peer, scanned, verification)) return false
    didVerify(peer, wasVerified)
    return true
}

/** "QR kodla ekle": pins the key a contact code scanned side by side committed to. */
internal suspend fun MessagingEngine.verifyContactCodeNow(peer: AccountId, identityKey: ByteArray): Boolean {
    val verification = IdentityVerification(IdentityVerificationMethod.QR_CODE, clock.now())
    val wasVerified = isIdentityVerified(peer)
    if (!backend.pinVerifiedIdentity(identityKey, peer, verification)) return false
    didVerify(peer, wasVerified)
    return true
}

private suspend fun MessagingEngine.isIdentityVerified(peer: AccountId): Boolean = try {
    (backend.identityTrust(peer) as? com.syconx.us2us.runtime.storage.ContactIdentity.Trusted)?.verified == true
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    false
}

/** A verification was stored: a held queue goes out, and a line says so (once). */
private fun MessagingEngine.didVerify(peer: AccountId, wasVerified: Boolean) {
    held.remove(peer)
    if (!wasVerified) addNotice(SystemNotice.SafetyNumberVerified, peer, clock.now())
    outboxWake.signal()
    emit(EngineChange.Conversations)
}

// MARK: Username claims (§3.3)

/**
 * Checks a contact's username claim against the directory, once, when the conversation is opened:
 * an accepted contact's, or an incoming request's whose name was kept from a deleted contact (a
 * stranger's request carries a verified `sender_username`, so it never needs one).
 */
internal suspend fun MessagingEngine.verifyUsernameIfNeededNow(peer: AccountId) {
    val contact = try {
        read { it.contacts.get(peer) }
    } catch (_: Exception) {
        null
    } ?: return
    val username = contact.profile.username ?: return
    if (contact.requestState !in setOf(ContactState.ACCEPTED, ContactState.INCOMING_REQUEST) || !contact.profile.needsUsernameCheck) return
    val owner = try {
        backend.usernameOwner(username)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return
    }
    try {
        write { it.contacts.recordUsernameCheck(username, owner, peer) }
    } catch (_: Exception) {
    }
    emit(EngineChange.Conversations)
}

// MARK: User settings

internal suspend fun MessagingEngine.updateUserSettingsNow(change: (UserSettings) -> UserSettings) {
    val before = settings
    val updated = runtime.copy(settings = change(before))
    stateFile.save(updated)
    runtime = updated
    if (before.presenceSharing != settings.presenceSharing) {
        if (!settings.presenceSharing) presence.clear()
        emit(EngineChange.Presence)
    }
    if (!settings.typingIndicators && typing.isNotEmpty()) {
        typing.clear()
        emit(EngineChange.Typing)
    }
    if (before.autoDownloadMedia != settings.autoDownloadMedia) startDownloads()
    if (before.displayName != settings.displayName || before.presenceSharing != settings.presenceSharing) {
        if (backend.announcesProfileChanges) {
            // The backend tells its contacts itself, over the network: the setting is saved now,
            // the account follows without holding the caller up (the newest settings when it runs).
            scope.launch { reconcileProfile() }
        } else {
            announceProfileChange()
        }
    }
}

/** The complete profile again to every contact that has one (§8.7). */
internal fun MessagingEngine.announceProfileChange() {
    val now = clock.now()
    write { tx ->
        val profile = ownProfile(tx, now)
        for (contact in tx.contacts.accepted()) {
            if (contact.profileSentAt == null) continue
            tx.outbox.enqueue(profile, contact.accountId, now = now)
            tx.contacts.setProfileSent(now, contact.accountId)
        }
    }
    outboxWake.signal()
}

/** Changes our username on the relay (§3.3) and tells the contacts (§8.7). */
internal suspend fun MessagingEngine.changeUsernameNow(username: Username) {
    backend.changeUsername(username)
    if (!backend.announcesProfileChanges) announceProfileChange()
}

// MARK: Backup and stickers

/**
 * Replaces the recents in the encrypted runtime state: empty and over-long ids are dropped,
 * repeats kept once, at most [MessagingEngine.MAX_RECENT_STICKERS]. When the state cannot be
 * written nothing changes.
 */
internal fun MessagingEngine.setRecentStickersNow(ids: List<String>) {
    val seen = HashSet<String>()
    val kept = ids.filter { it.isNotEmpty() && it.length <= MessagingEngine.MAX_RECENT_STICKER_ID_LENGTH && seen.add(it) }
        .take(MessagingEngine.MAX_RECENT_STICKERS)
    if (kept == runtime.recentStickers) return
    val updated = runtime.copy(recentStickers = kept)
    stateFile.save(updated)
    runtime = updated
}

/** Records that an exported backup was saved. When the state cannot be written nothing changes. */
internal fun MessagingEngine.recordBackupSavedNow(at: Instant) {
    val updated = runtime.copy(lastBackupAt = at)
    stateFile.save(updated)
    runtime = updated
}

/** The `ConversationWipe` a "delete for both" sends. */
internal fun userWipe(now: Instant): Content = InboundRules.control {
    wipe = conversationWipe {
        requestedAt = now.toEpochMilli()
        reason = ConversationWipe.Reason.USER_REQUEST
    }
}
