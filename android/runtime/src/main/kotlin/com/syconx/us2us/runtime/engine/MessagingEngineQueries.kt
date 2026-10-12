package com.syconx.us2us.runtime.engine

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.contacts.UsernameStatus
import com.syconx.us2us.core.profile.ProfilePicture
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.DataMessage
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.runtime.storage.AttachmentState
import com.syconx.us2us.runtime.storage.StorageTransaction
import com.syconx.us2us.runtime.storage.StoredAttachment
import com.syconx.us2us.runtime.storage.StoredMessage
import com.syconx.us2us.runtime.storage.StoredReaction
import kotlinx.coroutines.CancellationException
import java.time.Instant

/** The chat list: every visible contact with a conversation, most recent first. */
internal fun MessagingEngine.chatSummariesNow(): List<ChatSummary> {
    val now = clock.now()
    return read { tx ->
        val contacts = tx.contacts.all().associateBy { it.accountId }
        tx.conversations.all().mapNotNull { conversation ->
            val contact = contacts[conversation.contact] ?: return@mapNotNull null
            val accepted = contact.requestState == ContactState.ACCEPTED
            val last = tx.messages.latest(conversation.id, now)?.let { item(it, tx, accepted, emptyList()) }
            val lastNotice = noticesOf(conversation.id).lastOrNull()?.let(MessageItem::notice)
            val latest = listOfNotNull(last, lastNotice).maxByOrNull { it.storedAt }
            ChatSummary(
                contact = ContactInfo.of(contact),
                conversation = ConversationInfo.of(conversation, tx.conversations.unreadCount(conversation.id)),
                lastMessage = latest,
            )
        }.sortedByDescending { summary ->
            maxOf(summary.conversation.lastActivity, summary.lastMessage?.storedAt ?: Instant.MIN)
        }
    }
}

/** The newest messages of a conversation and its system lines, oldest first. */
internal fun MessagingEngine.messagesNow(peer: AccountId): List<MessageItem> {
    val now = clock.now()
    val (items, conversationId) = read { tx ->
        val conversation = tx.conversations.forContact(peer) ?: return@read emptyList<MessageItem>() to null
        val accepted = tx.contacts.requestState(peer) == ContactState.ACCEPTED
        val page = tx.messages.page(conversation.id, limit = configuration.pageSize, now = now)
        val reactions = tx.messages.reactions(page.map { it.messageId }, conversation.id)
        page.map { item(it, tx, accepted, reactions[it.messageId].orEmpty()) } to conversation.id
    }
    conversationId ?: return emptyList()
    val lines = noticesOf(conversationId).map(MessageItem::notice)
    if (lines.isEmpty()) return items
    val cutoff = items.firstOrNull()?.storedAt ?: Instant.MIN
    return (items + lines.filter { !it.storedAt.isBefore(cutoff) || items.size < configuration.pageSize })
        .sortedBy { it.storedAt }
}

/**
 * The conversation's photos and videos ([MediaKind.VISUAL]) or files ([MediaKind.DOCUMENTS]),
 * **newest first**: up to [limit] (at most a page) older than the message [before]. Never
 * view-once, disappearing, deleted or expired items. An empty page when [before] is no longer here.
 */
internal fun MessagingEngine.mediaMessagesNow(peer: AccountId, kind: MediaKind, before: MessageId?, limit: Int): List<MessageItem> {
    val now = clock.now()
    return read { tx ->
        if (limit <= 0) return@read emptyList()
        val conversation = tx.conversations.forContact(peer) ?: return@read emptyList()
        val cursor = if (before != null) tx.messages.localId(before, conversation.id) ?: return@read emptyList() else null
        val accepted = tx.contacts.requestState(peer) == ContactState.ACCEPTED
        val page = tx.messages.mediaPage(conversation.id, attachmentKinds(kind), cursor, minOf(limit, configuration.pageSize), now)
        val reactions = tx.messages.reactions(page.map { it.messageId }, conversation.id)
        page.map { item(it, tx, accepted, reactions[it.messageId].orEmpty()) }
    }
}

/** One message of the conversation as it is now (e.g. an item of the media list while its download runs); `null` once it is gone or expired. */
internal fun MessagingEngine.messageItemNow(messageId: MessageId, peer: AccountId): MessageItem? {
    val now = clock.now()
    return read { tx ->
        val conversation = tx.conversations.forContact(peer) ?: return@read null
        val message = tx.messages.get(messageId, conversation.id, now) ?: return@read null
        val accepted = tx.contacts.requestState(peer) == ContactState.ACCEPTED
        val reactions = tx.messages.reactions(listOf(messageId), conversation.id)
        item(message, tx, accepted, reactions[messageId].orEmpty())
    }
}

// MARK: Mapping

internal fun attachmentKinds(kind: MediaKind): Set<Attachment.Kind> = when (kind) {
    MediaKind.VISUAL -> setOf(Attachment.Kind.IMAGE, Attachment.Kind.VIDEO)
    MediaKind.DOCUMENTS -> setOf(Attachment.Kind.FILE)
}

internal fun MessagingEngine.item(message: StoredMessage, tx: StorageTransaction, accepted: Boolean, reactions: List<StoredReaction>): MessageItem {
    val attachments = tx.attachments.forMessage(message.messageId, message.conversationId)
    val data = message.content
    val body = when {
        message.isDeleted -> MessageBody.Deleted
        data != null -> body(data, attachments, incoming = !message.isOutgoing, accepted = accepted, viewOnce = message.isViewOnce)
        message.isViewOnce -> MessageBody.ViewOnceOpened
        else -> MessageBody.Unsupported
    }
    // The excerpt is shown only while the quoted message itself is here and stays (§9); the reply's
    // own copy of the text is never trusted for that.
    val quote = data?.takeIf { it.hasQuote() }?.let {
        QuoteInfo(it.quote.targetMessageId, quotePreview(it.quote.targetMessageId, message.conversationId, tx, clock.now()).orEmpty())
    }
    return MessageItem(
        messageId = message.messageId,
        direction = if (message.isOutgoing) MessageDirection.OUTGOING else MessageDirection.INCOMING,
        body = body,
        sentAt = message.sentAt,
        storedAt = message.createdAt,
        quote = quote,
        expireTimerSeconds = message.expireTimerSeconds,
        expiresAt = message.expiresAt,
        viewOnce = message.isViewOnce,
        reactions = reactions.associate { it.reactor to it.emoji },
        status = if (message.isOutgoing) OutgoingStatus.of(message.status) else null,
        deliveredAt = message.timestamps.deliveredAt,
        readAt = message.timestamps.readAt,
        displayedAt = message.timestamps.displayedAt,
        viewedAt = message.timestamps.viewedAt,
        savedByPeerAt = if (message.isOutgoing) message.timestamps.savedByPeerAt else null,
        savedByMeAt = message.timestamps.savedByMeAt,
    )
}

internal fun body(data: DataMessage, attachments: List<StoredAttachment>, incoming: Boolean, accepted: Boolean, viewOnce: Boolean = false): MessageBody =
    when (data.payloadCase) {
        DataMessage.PayloadCase.TEXT -> MessageBody.Text(data.text.body)
        DataMessage.PayloadCase.ATTACHMENT ->
            MessageBody.Attachment(attachmentItem(data.attachment, attachments.firstOrNull(), incoming, accepted, viewOnce))
        DataMessage.PayloadCase.LOCATION -> MessageBody.Location(
            LocationInfo(
                latitude = data.location.latitude,
                longitude = data.location.longitude,
                accuracyMeters = data.location.accuracyMeters,
                name = data.location.name.ifEmpty { null },
                address = data.location.address.ifEmpty { null },
            ),
        )
        DataMessage.PayloadCase.LIVE_LOCATION -> MessageBody.LiveLocation(LiveLocationInfo.of(data.liveLocation))
        DataMessage.PayloadCase.STICKER -> MessageBody.Sticker(
            StickerInfo(
                packId = data.sticker.packId,
                stickerId = data.sticker.stickerId,
                emoji = data.sticker.emoji,
                lang = data.sticker.lang,
                custom = if (data.sticker.hasCustom()) attachmentItem(data.sticker.custom, attachments.firstOrNull(), incoming, accepted) else null,
            ),
        )
        else -> MessageBody.Unsupported
    }

/** A view-once item never shows a preview (thumbnail, caption, waveform) before it is opened, whatever the sender put in. */
internal fun attachmentItem(attachment: Attachment, stored: StoredAttachment?, incoming: Boolean, accepted: Boolean, viewOnce: Boolean = false): AttachmentItem {
    val kind = when (attachment.kind) {
        Attachment.Kind.IMAGE -> AttachmentItem.Kind.IMAGE
        Attachment.Kind.VIDEO -> AttachmentItem.Kind.VIDEO
        Attachment.Kind.VOICE -> AttachmentItem.Kind.VOICE
        Attachment.Kind.STICKER_IMAGE -> AttachmentItem.Kind.STICKER_IMAGE
        else -> AttachmentItem.Kind.FILE
    }
    val availability = when (stored?.state) {
        null -> AttachmentItem.Availability.UNAVAILABLE
        AttachmentState.DOWNLOADED, AttachmentState.UPLOADED, AttachmentState.PENDING_UPLOAD ->
            if (stored.hasFile) AttachmentItem.Availability.LOCAL else AttachmentItem.Availability.UNAVAILABLE
        AttachmentState.UPLOADING, AttachmentState.DOWNLOADING -> AttachmentItem.Availability.TRANSFERRING
        AttachmentState.PENDING_DOWNLOAD ->
            if (incoming && !accepted) AttachmentItem.Availability.HELD_UNTIL_ACCEPTED else AttachmentItem.Availability.REMOTE
        AttachmentState.FAILED, AttachmentState.UNAVAILABLE -> AttachmentItem.Availability.UNAVAILABLE
    }
    var source = stored?.descriptor ?: attachment
    if (viewOnce) {
        source = source.toBuilder()
            .setThumbnail(com.google.protobuf.ByteString.EMPTY)
            .setCaption("")
            .setWaveform(com.google.protobuf.ByteString.EMPTY)
            .build()
    }
    return AttachmentItem(
        id = stored?.id ?: com.google.protobuf.ByteString.EMPTY,
        kind = kind,
        contentType = source.contentType,
        fileName = source.fileName.ifEmpty { null },
        size = if (source.size < 0) Long.MAX_VALUE else source.size,
        width = source.width,
        height = source.height,
        durationMs = source.durationMs,
        thumbnail = source.thumbnail.takeIf { !it.isEmpty },
        caption = source.caption.ifEmpty { null },
        waveform = source.waveform.takeIf { !it.isEmpty },
        availability = availability,
    )
}

// MARK: Profile pictures (§8.9, §8.10)

/**
 * Sets the own picture and "Show in search". A new picture goes to every accepted contact that has
 * our profile (§8.7); turning the setting on or off alone sends nothing to contacts. Then the
 * avatar record on the relay is brought in line (§8.10).
 */
internal suspend fun MessagingEngine.setOwnAvatarNow(picture: ProfilePicture?, showInSearch: Boolean): OwnProfilePicture {
    val before = backend.ownAvatar()
    backend.setOwnAvatar(picture, showInSearch)
    if (picture != before.picture && !backend.announcesProfileChanges) announceProfileChange()
    backend.syncAvatarRecord()
    return backend.ownAvatar()
}

/**
 * The picture of a search result (§8.10 "Search"): [account] is what the directory returned for
 * exactly [username]. Our own account shows our own choice; an accepted contact whose
 * `ProfileUpdate` arrived shows the picture from it (no request to the relay); a blocked one the
 * initial letter; anyone else the avatar record, fetched now.
 */
internal suspend fun MessagingEngine.avatarForUsername(username: Username, account: AccountId): AvatarLookup {
    if (account == me) return AvatarLookup(backend.ownAvatar().picture, AvatarLookup.Source.OWN)
    val contact = try {
        read { it.contacts.get(account) }
    } catch (_: Exception) {
        null
    }
    if (contact != null) {
        if (contact.requestState == ContactState.BLOCKED) return AvatarLookup.INITIALS
        if (contact.requestState == ContactState.ACCEPTED && contact.profile.updatedAt > 0) {
            return AvatarLookup(contact.profile.picture, AvatarLookup.Source.CONTACT)
        }
    }
    val picture = searchQuietly(username, account)
    return AvatarLookup(picture, if (picture == null) AvatarLookup.Source.INITIAL_LETTER else AvatarLookup.Source.SEARCH)
}

/**
 * The picture of a conversation's contact (§8.9): an accepted contact's last `ProfileUpdate`; the
 * account of our own message request its avatar record; anyone else the initial letter. An incoming
 * request is the initial letter too, with nothing fetched — marked available on request when its
 * sender's username is verified, unless [fetchRequest] (the user tapped "Show picture").
 */
internal suspend fun MessagingEngine.avatarOf(peer: AccountId, fetchRequest: Boolean): AvatarLookup {
    if (peer == me) return AvatarLookup(backend.ownAvatar().picture, AvatarLookup.Source.OWN)
    val contact = try {
        read { it.contacts.get(peer) }
    } catch (_: Exception) {
        null
    }
    if (contact == null || contact.pendingForget) return AvatarLookup.INITIALS
    return when (contact.requestState) {
        ContactState.ACCEPTED ->
            if (contact.profile.updatedAt > 0) AvatarLookup(contact.profile.picture, AvatarLookup.Source.CONTACT) else AvatarLookup.INITIALS
        ContactState.OUTGOING_REQUEST, ContactState.INCOMING_REQUEST -> {
            // Only a name the directory confirmed for this account.
            val username = contact.profile.username
            if (username == null || contact.profile.usernameStatus != UsernameStatus.VERIFIED) return AvatarLookup.INITIALS
            if (contact.requestState == ContactState.INCOMING_REQUEST && !fetchRequest) {
                return AvatarLookup(null, AvatarLookup.Source.INITIAL_LETTER, availableOnRequest = true)
            }
            val picture = searchQuietly(username, peer) ?: return AvatarLookup.INITIALS
            AvatarLookup(picture, AvatarLookup.Source.SEARCH)
        }
        ContactState.BLOCKED -> AvatarLookup.INITIALS
    }
}

private suspend fun MessagingEngine.searchQuietly(username: Username, account: AccountId): ProfilePicture? = try {
    backend.searchAvatar(username, account)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
