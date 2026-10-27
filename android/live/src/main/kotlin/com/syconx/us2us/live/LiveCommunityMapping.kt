package com.syconx.us2us.live

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.ChatSummary
import com.syconx.us2us.domain.CommunityChat
import com.syconx.us2us.domain.CommunityChats
import com.syconx.us2us.domain.CommunityMessageInfo
import com.syconx.us2us.domain.CommunitySender
import com.syconx.us2us.domain.DisappearingTimer
import com.syconx.us2us.domain.GhostState
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.domain.MediaDimensions
import com.syconx.us2us.domain.MediaSource
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.domain.MessagePreview
import com.syconx.us2us.domain.MessagePreviewKind
import com.syconx.us2us.domain.MessageRecipient
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.domain.Peer
import com.syconx.us2us.domain.Presence
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.runtime.storage.CommunityDeletion
import com.syconx.us2us.runtime.storage.CommunityMediaState
import com.syconx.us2us.runtime.storage.CommunityRecipient
import com.syconx.us2us.runtime.storage.StoredCommunityMessage
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

/** A community as the mapping needs it: what the roster and this phone know about it. */
data class LiveCommunityRecord(
    val id: AccountId,
    val name: String,
    val isPublic: Boolean,
    val members: List<AccountId>,
    val owner: AccountId?,
    val active: Boolean,
    /** Verified contacts' usernames by account (the very key the roster lists). */
    val names: Map<AccountId, String> = emptyMap(),
)

/**
 * Communities onto the screens' models (Communities extension v2): chat-list rows mixed with the
 * one-to-one chats, the conversation's messages with the 1:1 bubbles, and the 5.4 recipient list.
 * Pure functions, so the rules are tested without a runtime.
 */
object LiveCommunityMapping {
    /** A message's id on screen: own messages by their id alone (as the bubble picked in “+” has it), others' with their sender. */
    fun messageId(own: AccountId, sender: AccountId, messageId: AccountId): MessageId =
        if (sender == own) MessageId(hex(messageId)) else MessageId(hex(sender) + hex(messageId))

    /** The (sender, message id) of a bubble's id (a caption row's too); `null` for anything else. */
    fun key(own: AccountId, id: MessageId): Pair<AccountId, AccountId>? {
        val raw = id.value.substringBefore(CAPTION_SUFFIX)
        return try {
            when (raw.length) {
                32 -> own to AccountId.fromBytes(unhex(raw))
                64 -> AccountId.fromBytes(unhex(raw.substring(0, 32))) to AccountId.fromBytes(unhex(raw.substring(32)))
                else -> null
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * The aggregate state of an own message (the 1:1 icons): Sending until the relay accepted it
     * for every recipient, then Sent; Delivered once every recipient got it; Read once every one
     * read it, only while read receipts are on (they are mutual). A send that failed or went stale
     * is Failed (“Tekrar gönder” sends it to those who did not get it).
     */
    fun status(message: StoredCommunityMessage, showsRead: Boolean): MessageStatus = when (message.status) {
        "preparing", "pending", "partial" -> MessageStatus.Sending
        "failed", "stale" -> MessageStatus.Failed
        else -> {
            val delivery = message.delivery
            when {
                delivery == null -> MessageStatus.Sent
                showsRead && delivery.allRead -> MessageStatus.Read
                delivery.allDelivered -> MessageStatus.Delivered
                else -> MessageStatus.Sent
            }
        }
    }

    /** The conversation, oldest first: a video's or file's caption follows it as a text row of its own. */
    fun messages(
        community: LiveCommunityRecord,
        stored: List<StoredCommunityMessage>,
        own: AccountId,
        showsRead: Boolean,
        media: Map<MessageId, Bytes> = emptyMap(),
        downloads: Map<MessageId, Double?> = emptyMap(),
    ): List<Message> = stored.flatMap { message(community, it, own, showsRead, media, downloads) }

    fun message(
        community: LiveCommunityRecord,
        stored: StoredCommunityMessage,
        own: AccountId,
        showsRead: Boolean,
        media: Map<MessageId, Bytes> = emptyMap(),
        downloads: Map<MessageId, Double?> = emptyMap(),
    ): List<Message> {
        val outgoing = stored.sender == own
        val id = messageId(own, stored.sender, stored.messageId)
        val direction = if (outgoing) MessageDirection.Outgoing else MessageDirection.Incoming
        val info = CommunityMessageInfo(
            sender = CommunitySender(stored.sender.toString(), community.names[stored.sender]),
            removedByOwner = stored.deletedBy == CommunityDeletion.OWNER,
            canDeleteForEveryone = !stored.isDeleted && community.active && (outgoing || community.owner == own),
        )
        val sentAt = Instant.ofEpochMilli(stored.receivedAt)
        val status = if (outgoing) status(stored, showsRead) else null
        val base = Message(id = id, direction = direction, sentAt = sentAt, content = MessageContent.Deleted, status = status, community = info,
            failedAt = if (status == MessageStatus.Failed) sentAt else null)
        if (stored.isDeleted) return listOf(base)
        stored.sticker?.let { return listOf(base.copy(content = MessageContent.Sticker(it.packId, it.stickerId, it.emoji, it.lang))) }
        val attachment = stored.attachment ?: return listOf(base.copy(content = MessageContent.Text(stored.text)))
        val source = MediaSource.Memory(media[id] ?: attachment.thumbnail.takeIf { !it.isEmpty }?.let { Bytes.wrap(it.toByteArray()) } ?: Bytes.Empty)
        val caption = stored.text.takeIf { it.isNotEmpty() }
        val content = when (attachment.kind) {
            Attachment.Kind.IMAGE -> MessageContent.Photo(source, caption)
            Attachment.Kind.VOICE -> MessageContent.Voice(attachment.durationMs.toLong().milliseconds, LiveMapping.waveform(Bytes.wrap(attachment.waveform.toByteArray())), 0.0)
            Attachment.Kind.VIDEO -> MessageContent.Video(source, attachment.durationMs.toLong().milliseconds, attachment.size)
            else -> {
                val name = attachment.fileName
                MessageContent.File(name = name, byteCount = attachment.size, typeLabel = LiveMapping.typeLabel(name), pageCount = null)
            }
        }
        val availability = when (stored.media) {
            CommunityMediaState.LOCAL -> MediaAvailability.Ready
            CommunityMediaState.UNAVAILABLE -> MediaAvailability.Unavailable
            else -> if (id in downloads) MediaAvailability.Downloading(downloads[id]) else MediaAvailability.Remote
        }
        val size = if (attachment.width > 0 && attachment.height > 0) MediaDimensions(attachment.width, attachment.height) else null
        val bubble = base.copy(content = content, mediaAvailability = availability, mediaSize = size)
        // A photo carries its caption; a video's or a file's follows as a text of its own.
        if (caption == null || attachment.kind == Attachment.Kind.IMAGE) return listOf(bubble)
        return listOf(bubble, base.copy(id = MessageId(id.value + CAPTION_SUFFIX), content = MessageContent.Text(caption)))
    }

    /** The chat-list row of a community: its newest text or media message (or the placeholder) and its unread count. */
    fun summary(community: LiveCommunityRecord, last: StoredCommunityMessage?, unread: Int, own: AccountId, showsRead: Boolean): ChatSummary = ChatSummary(
        id = CommunityChats.chatId(community.id.toString()),
        peer = Peer(
            username = community.name,
            palette = AvatarPalette.C,
            isVerified = false,
            isAcceptedContact = false,
            presence = Presence.Hidden,
        ),
        lastMessage = last?.let { preview(community, it, own, showsRead) },
        unreadCount = unread,
        ghost = GhostState.Off,
        disappearing = DisappearingTimer.Off,
        community = CommunityChat(
            id = community.id.toString(),
            name = community.name,
            isPublic = community.isPublic,
            memberCount = community.members.size,
            isOwner = community.owner == own,
            isActive = community.active,
        ),
        lastMessageActivity = last?.let { Instant.ofEpochMilli(it.receivedAt) },
    )

    fun preview(community: LiveCommunityRecord, message: StoredCommunityMessage, own: AccountId, showsRead: Boolean): MessagePreview {
        val outgoing = message.sender == own
        val attachment = message.attachment
        val kind = when {
            message.isDeleted -> MessagePreviewKind.Deleted
            message.sticker != null -> MessagePreviewKind.Sticker
            attachment == null -> MessagePreviewKind.Text(message.text)
            attachment.kind == Attachment.Kind.VOICE -> MessagePreviewKind.Voice
            attachment.kind == Attachment.Kind.IMAGE -> MessagePreviewKind.Photo
            attachment.kind == Attachment.Kind.VIDEO -> MessagePreviewKind.Video
            else -> MessagePreviewKind.File(attachment.fileName)
        }
        return MessagePreview(
            kind = kind,
            direction = if (outgoing) MessageDirection.Outgoing else MessageDirection.Incoming,
            status = if (outgoing && !message.isDeleted) status(message, showsRead) else null,
            sentAt = Instant.ofEpochMilli(message.receivedAt),
            sender = if (outgoing) null else CommunitySender(message.sender.toString(), community.names[message.sender]),
            caption = message.text.takeIf { attachment != null && !message.isDeleted && it.isNotEmpty() },
            removedByOwner = message.deletedBy == CommunityDeletion.OWNER,
        )
    }

    /** All conversations by latest local message activity. Equal times keep source order; empty chats are last. */
    fun mixed(chats: List<ChatSummary>, communities: List<ChatSummary>): List<ChatSummary> =
        (chats + communities).sortedWith(compareByDescending { it.lastMessageActivity ?: it.lastMessage?.sentAt })

    /**
     * 5.4 › Bilgi of an own message: every recipient fixed at send time, current members first
     * (by name), then those who have left with their last state. Read times only while read
     * receipts are on.
     */
    fun recipients(community: LiveCommunityRecord, recipients: List<CommunityRecipient>, showsRead: Boolean): List<MessageRecipient> =
        recipients.map { recipient ->
            val left = recipient.member !in community.members || recipient.transport == CommunityRecipient.SKIPPED
            MessageRecipient(
                member = CommunitySender(recipient.member.toString(), community.names[recipient.member]),
                state = when (recipient.transport) {
                    CommunityRecipient.ACCEPTED, CommunityRecipient.SKIPPED -> MessageRecipient.State.Sent
                    CommunityRecipient.FAILED, CommunityRecipient.STALE -> MessageRecipient.State.Failed
                    else -> MessageRecipient.State.Pending
                },
                deliveredAt = recipient.deliveredAt?.let(Instant::ofEpochMilli),
                readAt = if (showsRead) recipient.readAt?.let(Instant::ofEpochMilli) else null,
                hasLeft = left,
            )
        }.sortedWith(compareBy<MessageRecipient> { it.hasLeft }.thenBy { it.member.username == null }.thenBy { it.member.username ?: it.member.account })

    const val CAPTION_SUFFIX = "#caption"

    private fun hex(id: AccountId): String = Bytes.wrap(id.toBytes()).toHex()

    private fun unhex(hex: String): ByteArray {
        require(hex.length % 2 == 0 && hex.all { it in '0'..'9' || it in 'a'..'f' })
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** The community of a chat id, or `null` for a one-to-one chat. */
    fun community(chat: ChatId): AccountId? = CommunityChats.community(chat)?.let {
        try {
            AccountId.parse(it)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
