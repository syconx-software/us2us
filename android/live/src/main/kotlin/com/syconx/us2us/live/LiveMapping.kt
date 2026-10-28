package com.syconx.us2us.live

import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.BlockedPerson
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.ChatSummary
import com.syconx.us2us.domain.ConnectionState
import com.syconx.us2us.domain.DisappearingTimer
import com.syconx.us2us.domain.GhostPeriod
import com.syconx.us2us.domain.GhostState
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.domain.MediaDimensions
import com.syconx.us2us.domain.MediaSource
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.domain.MessagePreview
import com.syconx.us2us.domain.MessagePreviewKind
import com.syconx.us2us.domain.MessageRequest
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.domain.Peer
import com.syconx.us2us.domain.Presence
import com.syconx.us2us.domain.QuotedMessage
import com.syconx.us2us.domain.Reaction
import com.syconx.us2us.domain.ReportSubject
import com.syconx.us2us.domain.SafetyQRCodec
import com.syconx.us2us.domain.SystemEvent
import com.syconx.us2us.domain.ViewOnceState
import com.syconx.us2us.domain.VoiceWaveform
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.domain.nav.RequestId
import com.syconx.us2us.domain.sticker.StickerCatalog
import com.syconx.us2us.domain.sticker.StickerLanguage
import com.syconx.us2us.domain.sticker.StickerResolution
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

// The mapping layer of the live service: plain records (`Live*`, built from the runtime's view
// models in RuntimeSnapshots.kt) → the UI models the screens render (domain Models.kt). Pure
// functions, no runtime and no I/O, so unit tests drive them with hand-made records.

// MARK: - Records

enum class LiveRequestState {
    OutgoingRequest,
    IncomingRequest,
    Accepted,
    Blocked,
    ;

    companion object
}

sealed interface LivePresence {
    data object Active : LivePresence

    data object JustNow : LivePresence

    data class MinutesAgo(val minutes: Int) : LivePresence

    data object None : LivePresence

    companion object
}

enum class LiveOutgoingStatus {
    Sending,
    Sent,
    Delivered,
    Read,
    NotDelivered,
    Failed,
    ;

    companion object
}

data class LiveAttachment(
    val kind: Kind,
    val contentType: String,
    val fileName: String?,
    val size: Long,
    val durationMs: Long,
    val thumbnail: Bytes?,
    val caption: String?,
    val waveform: Bytes?,
    val availability: Availability,
    /** Pixel size as sent (0: not given). */
    val width: Int = 0,
    val height: Int = 0,
) {
    enum class Kind { Image, Video, Voice, File, StickerImage }

    enum class Availability { Local, Transferring, Remote, HeldUntilAccepted, Unavailable }

    companion object
}

/** An attachment being fetched: asked for by the user, or reported by the runtime with its progress. */
data class LiveDownload(
    /** 0…1, `null` until the size is known. */
    val fraction: Double? = null,
)

sealed interface LiveNotice {
    data object ScreenshotByMe : LiveNotice

    data object ScreenRecordingByMe : LiveNotice

    data object ScreenshotByContact : LiveNotice

    data object SafetyNumberChanged : LiveNotice

    data object SafetyNumberAccepted : LiveNotice

    data object SafetyNumberVerified : LiveNotice

    data object RequestAccepted : LiveNotice

    data class SettingsChanged(
        val ghostEnabled: Boolean,
        val ghostTimeout: Long,
        val expireTimer: Long,
        val previousGhostEnabled: Boolean,
        val previousGhostTimeout: Long,
        val previousExpireTimer: Long,
    ) : LiveNotice

    companion object
}

sealed interface LiveBody {
    data class Text(val text: String) : LiveBody

    data class Attachment(val attachment: LiveAttachment) : LiveBody

    data class Location(val name: String?, val address: String?, val latitude: Double, val longitude: Double) : LiveBody

    data class LiveLocation(val sessionId: Bytes, val expiresAt: Instant, val stopped: Boolean) : LiveBody

    /** A sticker (PROTOCOL §8.8) as it travelled; `lang` as received. */
    data class Sticker(val packId: String, val stickerId: Int, val emoji: String, val lang: String) : LiveBody

    data object Deleted : LiveBody

    data object ViewOnceOpened : LiveBody

    data class Notice(val notice: LiveNotice) : LiveBody

    data object Unsupported : LiveBody

    companion object
}

/** One reacting account's emoji; [byMe] tells the user's own. */
data class LiveReaction(val byMe: Boolean, val emoji: String)

data class LiveMessageRecord(
    val id: Bytes,
    val direction: Direction,
    val body: LiveBody,
    val sentAt: Instant,
    val quoteTarget: Bytes? = null,
    val quoteText: String? = null,
    val expireTimerSeconds: Long = 0,
    val viewOnce: Boolean = false,
    val reactions: List<LiveReaction> = emptyList(),
    val status: LiveOutgoingStatus? = null,
    val deliveredAt: Instant? = null,
    val readAt: Instant? = null,
    /** A voice note played: outgoing, by the contact; incoming, here. */
    val viewedAt: Instant? = null,
    /** Outgoing photo, video or file: the contact saved it (their SAVED, §8.4). */
    val savedByPeerAt: Instant? = null,
    /** Saved outside the app on this phone. */
    val savedByMeAt: Instant? = null,
) {
    enum class Direction { Incoming, Outgoing, System }

    companion object
}

data class LiveChatRecord(
    /** The contact's account id (canonical string): the app's chat id. */
    val peer: String,
    val username: String?,
    /** The directory confirmed the username (§3.3 / §6.3). */
    val usernameVerified: Boolean = true,
    val displayName: String?,
    val requestState: LiveRequestState,
    val verified: Boolean,
    val safetyNumberChanged: Boolean,
    val presenceSharing: Boolean,
    val ghostEnabled: Boolean,
    val ghostTimeoutSeconds: Long,
    val ghostDeadline: Instant?,
    val expireTimerSeconds: Long,
    val unreadCount: Int,
    val lastActivity: Instant,
    val lastMessage: LiveMessageRecord?,
    /** The contact's current identity key (serialized), `null` before one is known. */
    val identityKey: Bytes? = null,
    /**
     * The contact's picture as the runtime has it (accepted contacts: their last profile update);
     * for the user's own request, the search copy found this session (LiveMappingAvatars.kt decides
     * what is drawn).
     */
    val avatar: LiveAvatar = LiveAvatar.Initials,
) {
    companion object
}

/** A blocked contact, with or without a conversation (7.1 › Engellenenler). */
data class LiveBlockedRecord(
    /** The contact's account id (canonical string): the app's chat id. */
    val peer: String,
    val username: String?,
    val displayName: String?,
) {
    companion object
}

/** The relay line as the runtime reports it. */
enum class LiveConnection {
    Offline,
    Connecting,
    Syncing,
    Open,
    WaitingForNetwork,
    Retrying,
    Revoked,
    ;

    companion object
}

// MARK: - Mapping

object LiveMapping {
    /** The runtime's reading of the vault's hardware protection, for 7.1 › Güvenlik (M-6). */
    fun deviceProtection(level: com.syconx.us2us.runtime.vault.DeviceProtection): com.syconx.us2us.domain.DeviceProtection = when (level) {
        com.syconx.us2us.runtime.vault.DeviceProtection.FULL -> com.syconx.us2us.domain.DeviceProtection.Full
        com.syconx.us2us.runtime.vault.DeviceProtection.LIMITED -> com.syconx.us2us.domain.DeviceProtection.Limited
        com.syconx.us2us.runtime.vault.DeviceProtection.BASIC -> com.syconx.us2us.domain.DeviceProtection.Basic
    }

    // MARK: Ids

    /** Message ids are 16 random bytes; the UI keys them as lower-case hex. */
    fun messageId(data: Bytes): MessageId = MessageId(data.toHex())

    fun messageData(id: MessageId): Bytes? {
        val base = id.value.substringBefore('#')
        if (base.isEmpty() || base.length % 2 != 0) return null
        val values = ArrayList<Int>(base.length / 2)
        var index = 0
        while (index < base.length) {
            val high = Character.digit(base[index], 16)
            val low = Character.digit(base[index + 1], 16)
            if (high < 0 || low < 0) return null
            values += high * 16 + low
            index += 2
        }
        return Bytes.ofUnsigned(values)
    }

    // MARK: Settings values

    fun ghostPeriod(seconds: Long): GhostPeriod =
        GhostPeriod.entries.minByOrNull { abs(it.duration.inWholeSeconds - seconds) } ?: GhostPeriod.OneDay

    fun seconds(period: GhostPeriod): Long = period.duration.inWholeSeconds

    fun disappearing(seconds: Long): DisappearingTimer {
        if (seconds <= 0) return DisappearingTimer.Off
        return DisappearingTimer.entries
            .filter { it.duration != null }
            .minByOrNull { abs((it.duration?.inWholeSeconds ?: 0) - seconds) }
            ?: DisappearingTimer.Off
    }

    fun seconds(timer: DisappearingTimer): Long = timer.duration?.inWholeSeconds ?: 0

    fun status(status: LiveOutgoingStatus): MessageStatus = when (status) {
        LiveOutgoingStatus.Sending -> MessageStatus.Sending
        LiveOutgoingStatus.Sent -> MessageStatus.Sent
        LiveOutgoingStatus.Delivered -> MessageStatus.Delivered
        LiveOutgoingStatus.Read -> MessageStatus.Read
        LiveOutgoingStatus.NotDelivered, LiveOutgoingStatus.Failed -> MessageStatus.Failed
    }

    /** Reciprocal (§8.6): with presence off on this device nobody's shows. */
    fun presence(presence: LivePresence?, now: Instant, ownSharing: Boolean): Presence {
        if (!ownSharing || presence == null) return Presence.Hidden
        return when (presence) {
            LivePresence.Active -> Presence.Online
            LivePresence.JustNow -> Presence.JustNow
            is LivePresence.MinutesAgo -> Presence.LastSeen(now.minusSeconds(presence.minutes * 60L))
            LivePresence.None -> Presence.Hidden
        }
    }

    // MARK: Chats

    /** The name the UI shows: the username (the design's only name), else the display name, else a short form of the account id. */
    fun username(chat: LiveChatRecord): String = shownName(chat.username, chat.displayName, chat.peer)

    fun shownName(username: String?, displayName: String?, peer: String): String {
        if (!username.isNullOrEmpty()) return username
        if (!displayName.isNullOrEmpty()) return displayName
        return peer.take(8)
    }

    fun peer(chat: LiveChatRecord, presence: LivePresence?, now: Instant, ownSharing: Boolean): Peer {
        val name = username(chat)
        val accepted = chat.requestState == LiveRequestState.Accepted
        return Peer(
            username = name,
            palette = if (chat.requestState == LiveRequestState.IncomingRequest) AvatarPalette.C else AvatarPalette.forUsername(name),
            isVerified = chat.verified && !chat.safetyNumberChanged,
            isAcceptedContact = accepted,
            presence = if (accepted && chat.presenceSharing) presence(presence, now, ownSharing) else Presence.Hidden,
            safetyNumberChanged = chat.safetyNumberChanged,
            identityFingerprint = chat.identityKey?.let(::identityFingerprint),
            avatar = avatar(chat),
            isBlocked = chat.requestState == LiveRequestState.Blocked,
        )
    }

    /** Who a report names: the username alone, never the display name or the account id [username] falls back to for showing. */
    fun reportSubject(chat: LiveChatRecord): ReportSubject {
        val username = chat.username
        if (username.isNullOrEmpty()) return ReportSubject(username = null, isUsernameVerified = false)
        return ReportSubject(username = username, isUsernameVerified = chat.usernameVerified)
    }

    // MARK: Blocked people (7.1 › Engellenenler)

    /** Someone blocked, as 4.1 would name them (a chat may be gone); the initial in the palette of that name, never a picture. */
    fun blockedPerson(record: LiveBlockedRecord): BlockedPerson {
        val name = shownName(record.username, record.displayName, record.peer)
        return BlockedPerson(
            id = ChatId(record.peer),
            name = name,
            isUsernameKnown = !record.username.isNullOrEmpty(),
            palette = AvatarPalette.forUsername(name),
        )
    }

    fun blockedPeople(records: List<LiveBlockedRecord>): List<BlockedPerson> = BlockedPerson.listed(records.map(::blockedPerson))

    fun ghost(chat: LiveChatRecord): GhostState {
        if (!chat.ghostEnabled) return GhostState.Off
        return GhostState(isEnabled = true, period = ghostPeriod(chat.ghostTimeoutSeconds), deadline = chat.ghostDeadline)
    }

    /**
     * Someone blocked has no unread count: a blocked request's messages were never displayed, and a
     * badge would only invite the user back into the chat of a person they just blocked.
     */
    fun chatSummary(chat: LiveChatRecord, presence: LivePresence?, now: Instant, ownSharing: Boolean): ChatSummary = ChatSummary(
        id = ChatId(chat.peer),
        peer = peer(chat, presence, now, ownSharing),
        lastMessage = chat.lastMessage?.let { preview(it, now) },
        unreadCount = if (chat.requestState == LiveRequestState.Blocked) 0 else chat.unreadCount,
        ghost = ghost(chat),
        disappearing = disappearing(chat.expireTimerSeconds),
    )

    fun previewKind(record: LiveMessageRecord, now: Instant): MessagePreviewKind? {
        if (record.expireTimerSeconds > 0 && record.direction == LiveMessageRecord.Direction.Incoming) return MessagePreviewKind.Disappearing
        return when (val body = record.body) {
            is LiveBody.Text -> MessagePreviewKind.Text(body.text)
            is LiveBody.Attachment -> {
                if (record.viewOnce) return MessagePreviewKind.PhotoViewOnce
                when (body.attachment.kind) {
                    LiveAttachment.Kind.Image -> MessagePreviewKind.Photo
                    LiveAttachment.Kind.Video -> MessagePreviewKind.Video
                    LiveAttachment.Kind.Voice -> MessagePreviewKind.Voice
                    LiveAttachment.Kind.File -> MessagePreviewKind.File(body.attachment.fileName.orEmpty())
                    LiveAttachment.Kind.StickerImage -> MessagePreviewKind.Sticker
                }
            }
            is LiveBody.Location -> MessagePreviewKind.Location
            is LiveBody.LiveLocation -> if (!body.stopped && now < body.expiresAt) MessagePreviewKind.LiveLocation else MessagePreviewKind.LiveLocationEnded
            is LiveBody.Sticker -> MessagePreviewKind.Sticker
            LiveBody.Deleted -> MessagePreviewKind.Deleted
            LiveBody.ViewOnceOpened -> MessagePreviewKind.PhotoViewOnce
            is LiveBody.Notice, LiveBody.Unsupported -> null
        }
    }

    fun preview(record: LiveMessageRecord, now: Instant): MessagePreview? {
        if (record.direction == LiveMessageRecord.Direction.System) return null
        val kind = previewKind(record, now) ?: return null
        return MessagePreview(
            kind = kind,
            direction = if (record.direction == LiveMessageRecord.Direction.Outgoing) MessageDirection.Outgoing else MessageDirection.Incoming,
            status = record.status?.let(::status),
            sentAt = record.sentAt,
        )
    }

    // MARK: Requests (4.3 / 4.4)

    fun request(chat: LiveChatRecord, now: Instant): MessageRequest {
        val flags = LinkedHashSet<MessageRequest.Flag>()
        var messages = emptyList<Message>()
        val sender = username(chat)
        val last = chat.lastMessage
        if (last != null) {
            val body = last.body
            if (body is LiveBody.Attachment) flags += MessageRequest.Flag.HasMedia
            if (body is LiveBody.Text && containsLink(body.text)) flags += MessageRequest.Flag.HasLink
            messages = message(last, ownUsername = "", peerUsername = sender, lookup = emptyMap(), now = now, media = emptyMap())
        }
        // The directory did not confirm the claimed name (§3.3).
        if (chat.username == null || !chat.usernameVerified) flags += MessageRequest.Flag.Suspicious
        return MessageRequest(
            id = RequestId(chat.peer),
            sender = sender,
            receivedAt = last?.sentAt ?: chat.lastActivity,
            preview = last?.let { previewKind(it, now) } ?: MessagePreviewKind.Text(""),
            flags = flags,
            messages = messages,
            offersAvatar = offersAvatar(chat),
        )
    }

    /** A local pattern only (nothing is fetched or resolved): an optional scheme, a dotted host with a top-level label of two letters or more. */
    private val linkPattern = Regex("""(?i)(?:https?://)?(?:[a-z0-9-]+\.)+[a-z]{2,}(?:/\S*)?""")

    fun containsLink(text: String): Boolean = linkPattern.containsMatchIn(text)

    // MARK: Messages

    /**
     * A whole conversation, oldest first. [media] holds decrypted full images (by message id)
     * loaded so far; until then a bubble shows the inline thumbnail. [downloads] are the
     * attachments being fetched right now, with their progress when it is known.
     * [showsPeerPlayback]: whether an outgoing voice message may show that the other side played it
     * (read receipts on: they are mutual).
     */
    fun messages(
        records: List<LiveMessageRecord>,
        ownUsername: String,
        peerUsername: String,
        now: Instant,
        media: Map<MessageId, Bytes>,
        downloads: Map<MessageId, LiveDownload> = emptyMap(),
        showsPeerPlayback: Boolean = true,
    ): List<Message> {
        val lookup = HashMap<Bytes, LiveMessageRecord>(records.size)
        for (record in records) lookup[record.id] = record
        return records.flatMap { message(it, ownUsername, peerUsername, lookup, now, media, downloads, showsPeerPlayback) }
    }

    /** One record → zero (unsupported), one, or (a settings notice that changed both Ghost and the timer) two rows. */
    fun message(
        record: LiveMessageRecord,
        ownUsername: String,
        peerUsername: String,
        lookup: Map<Bytes, LiveMessageRecord>,
        now: Instant,
        media: Map<MessageId, Bytes>,
        downloads: Map<MessageId, LiveDownload> = emptyMap(),
        showsPeerPlayback: Boolean = true,
    ): List<Message> {
        val id = messageId(record.id)
        val direction = if (record.direction == LiveMessageRecord.Direction.Outgoing) MessageDirection.Outgoing else MessageDirection.Incoming
        val body = record.body
        if (body is LiveBody.Notice) {
            return events(body.notice, ownUsername, peerUsername).mapIndexed { index, event ->
                Message(
                    id = if (index == 0) id else MessageId("${id.value}#$index"),
                    direction = MessageDirection.Incoming,
                    sentAt = record.sentAt,
                    content = MessageContent.System(event),
                )
            }
        }
        val content = content(record, id, now, media) ?: return emptyList()
        var message = Message(
            id = id,
            direction = direction,
            sentAt = record.sentAt,
            content = content,
            status = if (direction == MessageDirection.Outgoing) record.status?.let(::status) ?: MessageStatus.Sending else null,
        )
        val quoteText = record.quoteText
        if (quoteText != null) {
            val target = record.quoteTarget?.let { lookup[it] }
            val author = if (target?.direction == LiveMessageRecord.Direction.Outgoing) ownUsername else peerUsername
            message = message.copy(
                replyTo = QuotedMessage(author = author, kind = QuotedMessage.Kind.Text(quoteText), thumbnail = null, messageId = record.quoteTarget?.let(::messageId)),
            )
        }
        if (body is LiveBody.Attachment && !record.viewOnce) {
            val attachment = body.attachment
            message = message.copy(mediaAvailability = availability(attachment, direction == MessageDirection.Outgoing, downloads[id]))
            if (attachment.width > 0 && attachment.height > 0) message = message.copy(mediaSize = MediaDimensions(attachment.width, attachment.height))
            if (attachment.kind == LiveAttachment.Kind.Voice) {
                message = message.copy(isVoicePlayed = record.viewedAt != null && (direction == MessageDirection.Incoming || showsPeerPlayback))
            }
            // Saved outside the app (§8.4): told whatever the read-receipt setting.
            val savable = attachment.kind in SAVABLE_KINDS && record.expireTimerSeconds == 0L
            val savedByPeer = savable && direction == MessageDirection.Outgoing && record.savedByPeerAt != null
            message = message.copy(
                savedByPeer = savedByPeer,
                savedByPeerAt = if (savedByPeer) record.savedByPeerAt else null,
                savedByMe = savable && record.savedByMeAt != null,
            )
        }
        return listOf(
            message.copy(
                reactions = reactions(record.reactions),
                disappearing = disappearing(record.expireTimerSeconds),
                deliveredAt = record.deliveredAt,
                readAt = record.readAt,
                failedAt = if (record.status == LiveOutgoingStatus.NotDelivered || record.status == LiveOutgoingStatus.Failed) record.sentAt else null,
            ),
        )
    }

    private val SAVABLE_KINDS = setOf(LiveAttachment.Kind.Image, LiveAttachment.Kind.Video, LiveAttachment.Kind.File)

    fun content(record: LiveMessageRecord, id: MessageId, now: Instant, media: Map<MessageId, Bytes>): MessageContent? = when (val body = record.body) {
        is LiveBody.Text -> MessageContent.Text(body.text)
        is LiveBody.Attachment -> {
            val attachment = body.attachment
            if (record.viewOnce) {
                MessageContent.ViewOnce(isVideo = attachment.kind == LiveAttachment.Kind.Video, state = ViewOnceState.Unopened)
            } else {
                val source = MediaSource.Memory(media[id] ?: attachment.thumbnail ?: Bytes.Empty)
                when (attachment.kind) {
                    LiveAttachment.Kind.Image -> MessageContent.Photo(source, attachment.caption)
                    // A video sent from here this session keeps the poster its bubble showed while it
                    // was prepared (sharper than the inline thumbnail).
                    LiveAttachment.Kind.Video -> MessageContent.Video(source, attachment.durationMs.milliseconds, attachment.size)
                    LiveAttachment.Kind.Voice -> MessageContent.Voice(
                        duration = attachment.durationMs.milliseconds,
                        waveform = waveform(attachment.waveform),
                        playedFraction = 0.0,
                    )
                    LiveAttachment.Kind.File -> {
                        val name = attachment.fileName.orEmpty()
                        MessageContent.File(name = name, byteCount = attachment.size, typeLabel = typeLabel(name), pageCount = null)
                    }
                    LiveAttachment.Kind.StickerImage -> MessageContent.Photo(source, null)
                }
            }
        }
        is LiveBody.Location -> MessageContent.Location(body.name.orEmpty(), body.address.orEmpty(), body.latitude, body.longitude)
        is LiveBody.LiveLocation -> MessageContent.LiveLocation(expiresAt = body.expiresAt, isActive = !body.stopped && now < body.expiresAt)
        is LiveBody.Sticker -> {
            // The plain text stickers of earlier builds keep their card; every other sticker is
            // resolved by the bubble, in the reader's language only where the sender's is missing.
            val legacy = if (body.packId == StickerCatalog.LEGACY_TEXT_PACK_ID) {
                StickerCatalog.resolve(body.packId, body.stickerId, body.emoji, body.lang, StickerLanguage.Turkish) as? StickerResolution.LegacyText
            } else {
                null
            }
            if (legacy != null) MessageContent.TextSticker(legacy.text) else MessageContent.Sticker(body.packId, body.stickerId, body.emoji, body.lang)
        }
        LiveBody.Deleted -> MessageContent.Deleted
        LiveBody.ViewOnceOpened -> MessageContent.ViewOnce(isVideo = false, state = ViewOnceState.Opened)
        is LiveBody.Notice, LiveBody.Unsupported -> null
    }

    /**
     * What is downloading, by the bubble's id: the attachments the user asked for (no fraction yet)
     * and those the runtime transfers, with the fraction of the blob received so far ([progress],
     * by the runtime's message id).
     */
    fun downloads(requested: Map<MessageId, LiveDownload>, progress: Map<Bytes, Double>): Map<MessageId, LiveDownload> {
        val result = HashMap(requested)
        for ((id, fraction) in progress) result[messageId(id)] = LiveDownload(fraction)
        return result
    }

    /**
     * Where the attachment's file is, as its bubble shows it. What this phone sent is here (also
     * while it uploads); what it received is downloading while the runtime transfers it or the
     * user asked for it ([download]: the fraction when known).
     */
    fun availability(attachment: LiveAttachment, outgoing: Boolean, download: LiveDownload? = null): MediaAvailability =
        when (attachment.availability) {
            LiveAttachment.Availability.Local -> MediaAvailability.Ready
            LiveAttachment.Availability.Transferring -> if (outgoing) MediaAvailability.Ready else MediaAvailability.Downloading(download?.fraction)
            LiveAttachment.Availability.Remote -> download?.let { MediaAvailability.Downloading(it.fraction) } ?: MediaAvailability.Remote
            LiveAttachment.Availability.HeldUntilAccepted -> MediaAvailability.Held
            LiveAttachment.Availability.Unavailable -> MediaAvailability.Unavailable
        }

    fun events(notice: LiveNotice, ownUsername: String, peerUsername: String): List<SystemEvent> = when (notice) {
        LiveNotice.ScreenshotByMe, LiveNotice.ScreenRecordingByMe -> listOf(SystemEvent.ScreenshotTaken(ownUsername))
        LiveNotice.ScreenshotByContact -> listOf(SystemEvent.ScreenshotTaken(peerUsername))
        LiveNotice.SafetyNumberChanged -> listOf(SystemEvent.SafetyNumberChanged)
        LiveNotice.SafetyNumberAccepted -> listOf(SystemEvent.SafetyNumberAccepted)
        LiveNotice.SafetyNumberVerified -> listOf(SystemEvent.SafetyNumberVerified)
        LiveNotice.RequestAccepted -> emptyList()
        is LiveNotice.SettingsChanged -> buildList {
            if (notice.ghostEnabled != notice.previousGhostEnabled || (notice.ghostEnabled && notice.ghostTimeout != notice.previousGhostTimeout)) {
                add(if (notice.ghostEnabled) SystemEvent.GhostEnabled(ghostPeriod(notice.ghostTimeout)) else SystemEvent.GhostDisabled)
            }
            if (notice.expireTimer != notice.previousExpireTimer) add(SystemEvent.DisappearingChanged(disappearing(notice.expireTimer)))
        }
    }

    /** One reaction pill per emoji, in first-seen order. */
    fun reactions(entries: List<LiveReaction>): List<Reaction> {
        val byEmoji = LinkedHashMap<String, Reaction>()
        for (entry in entries) {
            val existing = byEmoji[entry.emoji]
            byEmoji[entry.emoji] = if (existing == null) {
                Reaction(entry.emoji, count = 1, includesMe = entry.byMe)
            } else {
                existing.copy(count = existing.count + 1, includesMe = existing.includesMe || entry.byMe)
            }
        }
        return byEmoji.values.toList()
    }

    /** The voice note's waveform bytes (up to 100, 0…255) → the design's 30 bars (0…1), each the mean of an equal stretch. */
    fun waveform(data: Bytes?, bars: Int = 30): List<Double> = VoiceWaveform.bars(data, bars)

    fun typeLabel(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        val ext = if (dot <= 0 || dot == fileName.length - 1) "" else fileName.substring(dot + 1)
        return if (ext.isEmpty()) "FILE" else ext.uppercase(Locale.ROOT)
    }

    // MARK: Verification

    /** The contact code's fingerprint: SHA-256 of the identity key the relay serves, first 16 bytes, lower-case hex. */
    fun identityFingerprint(identityKey: Bytes): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(identityKey.toByteArray())
        return Bytes.wrap(digest.copyOf(16)).toHex()
    }

    /** Constant-time comparison of a scanned fingerprint with the served key's. */
    fun fingerprintMatches(scanned: String, identityKey: Bytes): Boolean {
        val expected = Bytes.utf8(identityFingerprint(identityKey))
        val given = Bytes.utf8(scanned.lowercase(Locale.ROOT))
        return SafetyQRCodec.constantTimeEquals(expected, given)
    }

    // MARK: Backup

    /** The backup file header (`"BRPB"` ‖ version 1, the core's `BackupFile`). */
    fun looksLikeBackup(header: ByteArray): Boolean =
        header.size >= 5 && header[0] == 'B'.code.toByte() && header[1] == 'R'.code.toByte() &&
            header[2] == 'P'.code.toByte() && header[3] == 'B'.code.toByte() && header[4] == 1.toByte()

    // MARK: Connection

    fun connection(status: LiveConnection): ConnectionState = when (status) {
        LiveConnection.Open, LiveConnection.Syncing -> ConnectionState.Open
        LiveConnection.Connecting, LiveConnection.Retrying -> ConnectionState.Connecting
        LiveConnection.Offline, LiveConnection.WaitingForNetwork -> ConnectionState.Closed
        LiveConnection.Revoked -> ConnectionState.Revoked
    }
}
