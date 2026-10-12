package com.syconx.us2us.live

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.presence.PresenceStatus
import com.syconx.us2us.core.profile.ProfilePicture
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.ChatMediaKind
import com.syconx.us2us.runtime.engine.AttachmentItem
import com.syconx.us2us.runtime.engine.AvatarLookup
import com.syconx.us2us.runtime.engine.AvatarRecordStatus
import com.syconx.us2us.runtime.engine.ChatSummary
import com.syconx.us2us.runtime.engine.ContactInfo
import com.syconx.us2us.runtime.engine.MediaKind
import com.syconx.us2us.runtime.engine.MessageBody
import com.syconx.us2us.runtime.engine.MessageDirection
import com.syconx.us2us.runtime.engine.MessageItem
import com.syconx.us2us.runtime.engine.OutgoingStatus
import com.syconx.us2us.runtime.engine.OwnProfilePicture
import com.syconx.us2us.runtime.engine.SystemNotice
import com.syconx.us2us.runtime.net.ConnectionStatus

// The runtime's view models → the mapping layer's plain records. Field by field, nothing decided
// here (LiveMapping decides).

internal fun ByteString.toBytes(): Bytes = Bytes.wrap(toByteArray())

internal fun Bytes.toByteString(): ByteString = ByteString.copyFrom(toByteArray())

internal fun LiveChatRecord.Companion.of(summary: ChatSummary, ownAccount: AccountId?): LiveChatRecord {
    val contact = summary.contact
    val conversation = summary.conversation
    return LiveChatRecord(
        peer = contact.account.toString(),
        username = contact.username,
        usernameVerified = contact.usernameVerified,
        displayName = contact.displayName,
        requestState = LiveRequestState.of(contact.requestState),
        verified = contact.verified,
        safetyNumberChanged = contact.safetyNumberChanged,
        presenceSharing = contact.presenceSharing,
        ghostEnabled = conversation.ghost?.enabled ?: false,
        ghostTimeoutSeconds = conversation.ghost?.timeoutSeconds ?: 0,
        ghostDeadline = conversation.ghostDeadline,
        expireTimerSeconds = conversation.defaultExpireTimerSeconds,
        unreadCount = conversation.unreadCount,
        lastActivity = conversation.lastActivity,
        lastMessage = summary.lastMessage?.let { LiveMessageRecord.of(it, ownAccount) },
        identityKey = contact.identityKey?.toBytes(),
        avatar = LiveAvatar.of(contact.avatar),
    )
}

internal fun LiveBlockedRecord.Companion.of(contact: ContactInfo): LiveBlockedRecord =
    LiveBlockedRecord(peer = contact.account.toString(), username = contact.username, displayName = contact.displayName)

internal fun LiveRequestState.Companion.of(state: ContactState): LiveRequestState = when (state) {
    ContactState.OUTGOING_REQUEST -> LiveRequestState.OutgoingRequest
    ContactState.INCOMING_REQUEST -> LiveRequestState.IncomingRequest
    ContactState.ACCEPTED -> LiveRequestState.Accepted
    ContactState.BLOCKED -> LiveRequestState.Blocked
}

internal fun LivePresence.Companion.of(status: PresenceStatus): LivePresence = when (status) {
    PresenceStatus.Active -> LivePresence.Active
    PresenceStatus.ActiveJustNow -> LivePresence.JustNow
    is PresenceStatus.ActiveMinutesAgo -> LivePresence.MinutesAgo(status.minutes)
    PresenceStatus.None -> LivePresence.None
}

internal fun LiveMessageRecord.Companion.of(item: MessageItem, ownAccount: AccountId?): LiveMessageRecord = LiveMessageRecord(
    id = item.messageId.toBytes(),
    direction = when (item.direction) {
        MessageDirection.INCOMING -> LiveMessageRecord.Direction.Incoming
        MessageDirection.OUTGOING -> LiveMessageRecord.Direction.Outgoing
        MessageDirection.SYSTEM -> LiveMessageRecord.Direction.System
    },
    body = LiveBody.of(item.body),
    sentAt = item.sentAt,
    quoteTarget = item.quote?.targetMessageId?.toBytes(),
    quoteText = item.quote?.previewText,
    expireTimerSeconds = item.expireTimerSeconds,
    viewOnce = item.viewOnce,
    reactions = item.reactions.entries
        .sortedBy { it.key.toString() }
        .map { LiveReaction(byMe = it.key == ownAccount, emoji = it.value) },
    status = item.status?.let(LiveOutgoingStatus::of),
    deliveredAt = item.deliveredAt,
    readAt = item.readAt,
    viewedAt = item.viewedAt,
    savedByPeerAt = item.savedByPeerAt,
    savedByMeAt = item.savedByMeAt,
)

/** The contact screen's tab → the runtime's media list. */
internal fun mediaKind(kind: ChatMediaKind): MediaKind = when (kind) {
    ChatMediaKind.Visual -> MediaKind.VISUAL
    ChatMediaKind.Documents -> MediaKind.DOCUMENTS
}

internal fun LiveOutgoingStatus.Companion.of(status: OutgoingStatus): LiveOutgoingStatus = when (status) {
    OutgoingStatus.SENDING -> LiveOutgoingStatus.Sending
    OutgoingStatus.SENT -> LiveOutgoingStatus.Sent
    OutgoingStatus.DELIVERED -> LiveOutgoingStatus.Delivered
    OutgoingStatus.READ -> LiveOutgoingStatus.Read
    OutgoingStatus.NOT_DELIVERED -> LiveOutgoingStatus.NotDelivered
    OutgoingStatus.FAILED -> LiveOutgoingStatus.Failed
}

internal fun LiveBody.Companion.of(body: MessageBody): LiveBody = when (body) {
    is MessageBody.Text -> LiveBody.Text(body.text)
    is MessageBody.Attachment -> LiveBody.Attachment(LiveAttachment.of(body.attachment))
    is MessageBody.Location -> LiveBody.Location(body.location.name, body.location.address, body.location.latitude, body.location.longitude)
    is MessageBody.LiveLocation -> LiveBody.LiveLocation(body.location.sessionId.toBytes(), body.location.expiresAt, body.location.stopped)
    is MessageBody.Sticker -> LiveBody.Sticker(body.sticker.packId, body.sticker.stickerId, body.sticker.emoji, body.sticker.lang)
    MessageBody.Deleted -> LiveBody.Deleted
    MessageBody.ViewOnceOpened -> LiveBody.ViewOnceOpened
    is MessageBody.Notice -> LiveBody.Notice(LiveNotice.of(body.notice))
    MessageBody.Unsupported -> LiveBody.Unsupported
}

internal fun LiveAttachment.Companion.of(item: AttachmentItem): LiveAttachment = LiveAttachment(
    kind = when (item.kind) {
        AttachmentItem.Kind.IMAGE -> LiveAttachment.Kind.Image
        AttachmentItem.Kind.VIDEO -> LiveAttachment.Kind.Video
        AttachmentItem.Kind.VOICE -> LiveAttachment.Kind.Voice
        AttachmentItem.Kind.FILE -> LiveAttachment.Kind.File
        AttachmentItem.Kind.STICKER_IMAGE -> LiveAttachment.Kind.StickerImage
    },
    contentType = item.contentType,
    fileName = item.fileName,
    size = item.size,
    durationMs = item.durationMs.toLong() and 0xFFFF_FFFFL,
    thumbnail = item.thumbnail?.toBytes(),
    caption = item.caption,
    waveform = item.waveform?.toBytes(),
    availability = when (item.availability) {
        AttachmentItem.Availability.LOCAL -> LiveAttachment.Availability.Local
        AttachmentItem.Availability.TRANSFERRING -> LiveAttachment.Availability.Transferring
        AttachmentItem.Availability.REMOTE -> LiveAttachment.Availability.Remote
        AttachmentItem.Availability.HELD_UNTIL_ACCEPTED -> LiveAttachment.Availability.HeldUntilAccepted
        AttachmentItem.Availability.UNAVAILABLE -> LiveAttachment.Availability.Unavailable
    },
    width = item.width,
    height = item.height,
)

internal fun LiveNotice.Companion.of(notice: SystemNotice): LiveNotice = when (notice) {
    SystemNotice.ScreenshotByMe -> LiveNotice.ScreenshotByMe
    SystemNotice.ScreenRecordingByMe -> LiveNotice.ScreenRecordingByMe
    SystemNotice.ScreenshotByContact -> LiveNotice.ScreenshotByContact
    SystemNotice.SafetyNumberChanged -> LiveNotice.SafetyNumberChanged
    // Early builds wrote `SafetyNumberAccepted` only when verifying.
    SystemNotice.SafetyNumberAccepted, SystemNotice.SafetyNumberVerified -> LiveNotice.SafetyNumberVerified
    SystemNotice.SafetyNumberAcceptedUnverified -> LiveNotice.SafetyNumberAccepted
    SystemNotice.RequestAccepted -> LiveNotice.RequestAccepted
    is SystemNotice.SettingsChanged -> LiveNotice.SettingsChanged(
        ghostEnabled = notice.change.ghost.enabled,
        ghostTimeout = notice.change.ghost.timeoutSeconds,
        expireTimer = notice.change.expireTimerSeconds,
        previousGhostEnabled = notice.change.previousGhost.enabled,
        previousGhostTimeout = notice.change.previousGhost.timeoutSeconds,
        previousExpireTimer = notice.change.previousExpireTimerSeconds,
    )
}

internal fun LiveConnection.Companion.of(status: ConnectionStatus): LiveConnection = when (status) {
    ConnectionStatus.Offline -> LiveConnection.Offline
    ConnectionStatus.Connecting -> LiveConnection.Connecting
    ConnectionStatus.Syncing -> LiveConnection.Syncing
    ConnectionStatus.Open -> LiveConnection.Open
    ConnectionStatus.WaitingForNetwork -> LiveConnection.WaitingForNetwork
    is ConnectionStatus.Retrying -> LiveConnection.Retrying
    ConnectionStatus.Revoked -> LiveConnection.Revoked
}

internal fun LiveAvatar.Companion.of(picture: ProfilePicture?): LiveAvatar = when (picture) {
    null -> LiveAvatar.Initials
    is ProfilePicture.Preset -> LiveAvatar.Preset(picture.id)
    is ProfilePicture.Photo -> LiveAvatar.Photo(Bytes.wrap(picture.data), picture.contentType)
}

/**
 * What a 4.2 result's picture leaves for the user's own request to that account (§8.9), this
 * session only: the search copy, or the initial letter when the record holds none; `null` when no
 * search was made (the own picture, an accepted contact's).
 */
internal fun LiveAvatar.Companion.searchCopy(lookup: AvatarLookup): LiveAvatar? = when (lookup.source) {
    AvatarLookup.Source.SEARCH, AvatarLookup.Source.INITIAL_LETTER -> of(lookup.picture)
    AvatarLookup.Source.OWN, AvatarLookup.Source.CONTACT -> null
}

internal fun LiveOwnAvatar.Companion.of(own: OwnProfilePicture): LiveOwnAvatar = LiveOwnAvatar(
    avatar = LiveAvatar.of(own.picture),
    showInSearch = own.showInSearch,
    status = when (own.searchRecord) {
        AvatarRecordStatus.IN_SYNC -> LiveAvatarSearchStatus.InSync
        AvatarRecordStatus.PENDING -> LiveAvatarSearchStatus.Pending
        AvatarRecordStatus.UNAVAILABLE -> LiveAvatarSearchStatus.Unavailable
    },
)
