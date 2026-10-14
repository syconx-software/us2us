package com.syconx.us2us.runtime.engine

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.contacts.UsernameStatus
import com.syconx.us2us.core.profile.ProfilePicture
import com.syconx.us2us.core.proto.GhostMode
import com.syconx.us2us.core.proto.LiveLocation
import com.syconx.us2us.runtime.media.PreparedMedia
import com.syconx.us2us.runtime.storage.Conversation
import com.syconx.us2us.runtime.storage.ConversationId
import com.syconx.us2us.runtime.storage.MessageStatus
import com.syconx.us2us.runtime.storage.StoredContact
import com.syconx.us2us.runtime.support.RuntimeString
import com.syconx.us2us.runtime.support.RuntimeStrings
import org.json.JSONObject
import java.time.Instant

// View models the UI binds to. They are built from storage records (decrypted while unlocked)
// and dropped from memory when the app locks.

/** A 16-byte message id (`DataMessage.message_id`). */
typealias MessageId = ByteString

/** The limits of brp.proto the engine checks before anything is stored. */
object ContentLimits {
    const val MAX_TEXT_BYTES = 8192
    const val MAX_CAPTION_BYTES = 2048
    const val MAX_QUOTE_PREVIEW_CHARACTERS = 200
    const val MAX_THUMBNAIL_BYTES = 10 * 1024
    const val MAX_DISPLAY_NAME_BYTES = 64
    const val MAX_WAVEFORM_SAMPLES = 100
    const val MAX_RECEIPT_IDS = 1000
    val GHOST_TIMEOUT_RANGE = 60L..604_800L
}

/** A contact as the UI shows it. */
data class ContactInfo(
    val account: AccountId,
    val requestState: ContactState,
    /** The contact's display name, if it shared one (§8.7). */
    val displayName: String?,
    /** The username, and whether the directory confirmed it (§3.3). */
    val username: String?,
    val usernameVerified: Boolean,
    /** The contact shares "active now" (§8.6). */
    val presenceSharing: Boolean,
    /** The safety number changed and sending is blocked until accepted (§5). */
    val safetyNumberChanged: Boolean,
    /** The safety number was verified in person. */
    val verified: Boolean,
    /** The contact's current identity key (serialized public key), `null` before one is known. */
    val identityKey: ByteString?,
    /**
     * The picture from the contact's last `ProfileUpdate` (§8.9); `null` is the initial letter,
     * always for message requests and blocked contacts.
     */
    val avatar: ProfilePicture?,
) {
    /** The name to show: the display name, else the username (the UI marks an unverified one). */
    val title: String get() = displayName ?: username ?: ""

    companion object {
        internal fun of(contact: StoredContact) = ContactInfo(
            account = contact.accountId,
            requestState = contact.requestState,
            displayName = contact.profile.displayName,
            username = contact.profile.username?.value,
            usernameVerified = contact.profile.usernameStatus == UsernameStatus.VERIFIED,
            presenceSharing = contact.profile.presenceSharing,
            safetyNumberChanged = contact.identityChanged,
            verified = contact.isVerified,
            identityKey = contact.identityKey?.let { ByteString.copyFrom(it.serialize()) },
            avatar = if (contact.requestState == ContactState.ACCEPTED) contact.profile.picture else null,
        )
    }
}

/** Ghost mode of a conversation (§8.2). */
data class GhostSettings(
    val enabled: Boolean,
    /** 60 … 604800 seconds. */
    val timeoutSeconds: Long,
    /** "Leave no trace": also delete the contact and session. */
    val wipeContact: Boolean = false,
) {
    internal val proto: GhostMode
        get() = GhostMode.newBuilder()
            .setEnabled(enabled)
            .setTimeoutSeconds(timeoutSeconds.toInt())
            .setWipeContact(wipeContact)
            .build()

    internal fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("timeoutSeconds", timeoutSeconds)
        .put("wipeContact", wipeContact)

    companion object {
        internal fun of(mode: GhostMode) = GhostSettings(mode.enabled, mode.timeoutSeconds.unsigned(), mode.wipeContact)

        internal fun fromJson(json: JSONObject?): GhostSettings? {
            json ?: return null
            if (!json.has("enabled") || !json.has("timeoutSeconds")) return null
            return GhostSettings(json.optBoolean("enabled"), json.optLong("timeoutSeconds"), json.optBoolean("wipeContact"))
        }
    }
}

/** A conversation's state for the header and the chat list. */
data class ConversationInfo(
    val id: ConversationId,
    val peer: AccountId,
    /** Ghost mode, when it is on. */
    val ghost: GhostSettings?,
    /** Applied to new outgoing messages; 0 = off (§8.1). */
    val defaultExpireTimerSeconds: Long,
    /** When ghost mode wipes the conversation (the header countdown). */
    val ghostDeadline: Instant?,
    val lastActivity: Instant,
    val unreadCount: Int,
) {
    companion object {
        internal fun of(conversation: Conversation, unreadCount: Int) = ConversationInfo(
            id = conversation.id,
            peer = conversation.contact,
            ghost = if (conversation.settings.ghost.enabled) GhostSettings.of(conversation.settings.ghost) else null,
            defaultExpireTimerSeconds = conversation.defaultExpireTimerSeconds,
            ghostDeadline = conversation.ghostDeadline,
            lastActivity = conversation.lastActivityAt ?: conversation.createdAt,
            unreadCount = unreadCount,
        )
    }
}

enum class MessageDirection { INCOMING, OUTGOING, SYSTEM }

/** What a bubble shows. */
sealed interface MessageBody {
    data class Text(val text: String) : MessageBody

    data class Attachment(val attachment: AttachmentItem) : MessageBody

    data class Location(val location: LocationInfo) : MessageBody

    data class LiveLocation(val location: LiveLocationInfo) : MessageBody

    data class Sticker(val sticker: StickerInfo) : MessageBody

    /** Deleted for everyone (§8.5): the tombstone. */
    data object Deleted : MessageBody

    /** A view-once item that was opened (on either side): nothing is left. */
    data object ViewOnceOpened : MessageBody

    /** A local system line (screenshot, safety number, settings …). */
    data class Notice(val notice: SystemNotice) : MessageBody

    /** A payload this version does not know. */
    data object Unsupported : MessageBody
}

/** An attachment and where its bytes are. */
data class AttachmentItem(
    /** The local attachment id. */
    val id: ByteString,
    val kind: Kind,
    val contentType: String,
    val fileName: String?,
    val size: Long,
    val width: Int,
    val height: Int,
    val durationMs: Int,
    /** Inline JPEG ≤ 10 KiB, end-to-end encrypted with the message. */
    val thumbnail: ByteString?,
    val caption: String?,
    val waveform: ByteString?,
    val availability: Availability,
) {
    enum class Kind { IMAGE, VIDEO, VOICE, FILE, STICKER_IMAGE }

    enum class Availability {
        /** Stored here, encrypted under the conversation key. */
        LOCAL,

        /** On its way (upload or download). */
        TRANSFERRING,

        /** On the relay, not downloaded yet. */
        REMOTE,

        /** From a message request: hidden until the request is accepted (§6.4). */
        HELD_UNTIL_ACCEPTED,

        /** Expired on the relay, opened (view-once) or failed its integrity check. */
        UNAVAILABLE,
    }
}

data class LocationInfo(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val name: String? = null,
    val address: String? = null,
)

data class LiveLocationInfo(
    val sessionId: ByteString,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val headingDegrees: Float,
    val speedMps: Float,
    val expiresAt: Instant,
    val sequence: Long,
    val stopped: Boolean,
) {
    fun isActive(now: Instant): Boolean = !stopped && now.isBefore(expiresAt)

    companion object {
        internal fun of(live: LiveLocation) = LiveLocationInfo(
            sessionId = live.sessionId,
            latitude = live.latitude,
            longitude = live.longitude,
            accuracyMeters = live.accuracyMeters,
            headingDegrees = live.headingDegrees,
            speedMps = live.speedMps,
            expiresAt = Instant.ofEpochMilli(if (live.expiresAt < 0) Long.MAX_VALUE else live.expiresAt),
            sequence = live.sequence.unsigned(),
            stopped = live.stopped,
        )
    }
}

data class StickerInfo(
    val packId: String,
    val stickerId: Int,
    val emoji: String,
    /**
     * A built-in sticker with words: the language they were shown in on the sender's side
     * ("tr", "en", PROTOCOL.md §8.8), as received. Empty for a wordless sticker and from older senders.
     */
    val lang: String,
    /** A custom sticker travels as an attachment. */
    val custom: AttachmentItem?,
)

/**
 * Local system lines. Stored as events inside the conversation's own crypto-shreddable notice
 * item, and localized when shown.
 */
sealed interface SystemNotice {
    data object ScreenshotByMe : SystemNotice

    data object ScreenRecordingByMe : SystemNotice

    data object ScreenshotByContact : SystemNotice

    data object SafetyNumberChanged : SystemNotice

    /** Written by early builds when the user verified a safety number; reads as a verification. Never written any more. */
    data object SafetyNumberAccepted : SystemNotice

    /** A changed safety number was accepted without verifying it. */
    data object SafetyNumberAcceptedUnverified : SystemNotice

    /** The safety number was verified (compared, scanned or pinned by a contact code). */
    data object SafetyNumberVerified : SystemNotice

    data object RequestAccepted : SystemNotice

    /** Ghost / disappearing settings changed. */
    data class SettingsChanged(val change: SettingsChange) : SystemNotice

    data class SettingsChange(
        val ghost: GhostSettings,
        val expireTimerSeconds: Long,
        val previousGhost: GhostSettings,
        val previousExpireTimerSeconds: Long,
    )

    /**
     * The text of the lines the runtime words itself, in the language of [strings]; `null` for
     * the lines the app's own catalogue words (they name the contact or describe settings).
     */
    fun runtimeText(strings: RuntimeStrings): String? = when (this) {
        ScreenshotByMe -> strings.string(RuntimeString.SCREENSHOT_TAKEN_BY_ME)
        ScreenRecordingByMe -> strings.string(RuntimeString.SCREEN_RECORDING_BY_ME)
        SafetyNumberAccepted, SafetyNumberVerified -> strings.string(RuntimeString.SAFETY_NUMBER_VERIFIED_LINE)
        SafetyNumberAcceptedUnverified -> strings.string(RuntimeString.SAFETY_NUMBER_ACCEPTED_LINE)
        RequestAccepted -> strings.string(RuntimeString.REQUEST_ACCEPTED_LINE)
        ScreenshotByContact, SafetyNumberChanged, is SettingsChanged -> null
    }

    companion object {
        internal fun toJson(notice: SystemNotice): JSONObject = when (notice) {
            ScreenshotByMe -> JSONObject().put("type", "screenshotByMe")
            ScreenRecordingByMe -> JSONObject().put("type", "screenRecordingByMe")
            ScreenshotByContact -> JSONObject().put("type", "screenshotByContact")
            SafetyNumberChanged -> JSONObject().put("type", "safetyNumberChanged")
            SafetyNumberAccepted -> JSONObject().put("type", "safetyNumberAccepted")
            SafetyNumberAcceptedUnverified -> JSONObject().put("type", "safetyNumberAcceptedUnverified")
            SafetyNumberVerified -> JSONObject().put("type", "safetyNumberVerified")
            RequestAccepted -> JSONObject().put("type", "requestAccepted")
            is SettingsChanged -> JSONObject()
                .put("type", "settingsChanged")
                .put("ghost", notice.change.ghost.toJson())
                .put("expireTimerSeconds", notice.change.expireTimerSeconds)
                .put("previousGhost", notice.change.previousGhost.toJson())
                .put("previousExpireTimerSeconds", notice.change.previousExpireTimerSeconds)
        }

        internal fun fromJson(json: JSONObject): SystemNotice? = when (json.optString("type")) {
            "screenshotByMe" -> ScreenshotByMe
            "screenRecordingByMe" -> ScreenRecordingByMe
            "screenshotByContact" -> ScreenshotByContact
            "safetyNumberChanged" -> SafetyNumberChanged
            "safetyNumberAccepted" -> SafetyNumberAccepted
            "safetyNumberAcceptedUnverified" -> SafetyNumberAcceptedUnverified
            "safetyNumberVerified" -> SafetyNumberVerified
            "requestAccepted" -> RequestAccepted
            "settingsChanged" -> {
                val ghost = GhostSettings.fromJson(json.optJSONObject("ghost"))
                val previous = GhostSettings.fromJson(json.optJSONObject("previousGhost"))
                if (ghost == null || previous == null) {
                    null
                } else {
                    SettingsChanged(
                        SettingsChange(ghost, json.optLong("expireTimerSeconds"), previous, json.optLong("previousExpireTimerSeconds")),
                    )
                }
            }
            else -> null
        }
    }
}

/** What the sender sees under a bubble (§5.4). */
enum class OutgoingStatus {
    /** Waiting in the outbox (no network, locked, retrying, uploading). */
    SENDING,

    /** Accepted by the relay; waiting for the recipient to open the app. */
    SENT,
    DELIVERED,
    READ,

    /** 14 days without a delivery receipt: the relay has deleted it. */
    NOT_DELIVERED,

    /** Refused for good (blocked, gone, invalid); "Tekrar gönder" sends it anew. */
    FAILED,
    ;

    companion object {
        fun of(status: MessageStatus): OutgoingStatus = when (status) {
            MessageStatus.SENDING -> SENDING
            MessageStatus.SENT -> SENT
            MessageStatus.DELIVERED -> DELIVERED
            MessageStatus.READ -> READ
            MessageStatus.NOT_DELIVERED -> NOT_DELIVERED
            MessageStatus.FAILED -> FAILED
            MessageStatus.RECEIVED -> DELIVERED
        }
    }
}

/** One message (bubble or system line), in chronological order. */
data class MessageItem(
    val messageId: MessageId,
    val direction: MessageDirection,
    val body: MessageBody,
    /** Sender clock (`sent_at`); local time for system lines. */
    val sentAt: Instant,
    /** Local time it was stored; the sort key. */
    val storedAt: Instant,
    val quote: QuoteInfo?,
    val expireTimerSeconds: Long,
    /** When the disappearing timer deletes it (§8.1). */
    val expiresAt: Instant?,
    val viewOnce: Boolean,
    /** One emoji per reacting account. */
    val reactions: Map<AccountId, String>,
    /** Outgoing only. */
    val status: OutgoingStatus?,
    val deliveredAt: Instant?,
    val readAt: Instant?,
    /** Incoming: first displayed (starts the disappearing timer). */
    val displayedAt: Instant?,
    /**
     * A voice note played — outgoing: by the contact (their VIEWED); incoming: on this phone. A
     * view-once item: when it was opened.
     */
    val viewedAt: Instant?,
    /** Outgoing photo, video or file: when the contact said it saved it outside the app (§8.4). */
    val savedByPeerAt: Instant?,
    /** When it was first saved outside the app on this phone. */
    val savedByMeAt: Instant?,
) {
    val id: ByteString get() = messageId

    /** Text that may be copied: never from disappearing or view-once messages. */
    val isCopyable: Boolean get() = expireTimerSeconds == 0L && !viewOnce && body is MessageBody.Text

    companion object {
        internal fun notice(record: NoticeRecord) = MessageItem(
            messageId = record.id,
            direction = MessageDirection.SYSTEM,
            body = MessageBody.Notice(record.notice),
            sentAt = record.at,
            storedAt = record.at,
            quote = null,
            expireTimerSeconds = 0,
            expiresAt = null,
            viewOnce = false,
            reactions = emptyMap(),
            status = null,
            deliveredAt = null,
            readAt = null,
            displayedAt = null,
            viewedAt = null,
            savedByPeerAt = null,
            savedByMeAt = null,
        )
    }
}

/** Which media of a conversation to list ([MessagingEngine.mediaMessages]). */
enum class MediaKind {
    /** Photos and videos. */
    VISUAL,

    /** Files. */
    DOCUMENTS,
}

data class QuoteInfo(val targetMessageId: MessageId, val previewText: String)

/** One row of the chat list. */
data class ChatSummary(
    val contact: ContactInfo,
    val conversation: ConversationInfo,
    val lastMessage: MessageItem?,
) {
    val id: AccountId get() = contact.account
    val isRequest: Boolean get() = contact.requestState == ContactState.INCOMING_REQUEST
    val unreadCount: Int get() = conversation.unreadCount
    val ghostDeadline: Instant? get() = conversation.ghostDeadline
}

/** The user's own preferences, inside the vault (the runtime state file). */
data class UserSettings(
    val displayName: String = "",
    /** "Show when I'm active" (§8.6), default on; reciprocal. */
    val presenceSharing: Boolean = true,
    /** Optional read receipts (§8.4), default on. */
    val readReceipts: Boolean = true,
    /** Optional typing indicators (§8.4), default on. */
    val typingIndicators: Boolean = true,
    /** Defaults for new conversations. */
    val defaultGhost: GhostSettings? = null,
    val defaultExpireTimerSeconds: Long = 0,
    /** Download media from accepted contacts automatically. */
    val autoDownloadMedia: Boolean = true,
) {
    internal fun toJson(): JSONObject = JSONObject()
        .put("displayName", displayName)
        .put("presenceSharing", presenceSharing)
        .put("readReceipts", readReceipts)
        .put("typingIndicators", typingIndicators)
        .put("defaultGhost", defaultGhost?.toJson() ?: JSONObject.NULL)
        .put("defaultExpireTimerSeconds", defaultExpireTimerSeconds)
        .put("autoDownloadMedia", autoDownloadMedia)

    companion object {
        /**
         * Tolerant decoding: a missing or unreadable field takes its default instead of failing the
         * whole runtime state file, which would reset every setting at once.
         */
        internal fun fromJson(json: JSONObject?): UserSettings {
            val defaults = UserSettings()
            json ?: return defaults
            return UserSettings(
                displayName = json.stringOr("displayName", defaults.displayName),
                presenceSharing = json.booleanOr("presenceSharing", defaults.presenceSharing),
                readReceipts = json.booleanOr("readReceipts", defaults.readReceipts),
                typingIndicators = json.booleanOr("typingIndicators", defaults.typingIndicators),
                defaultGhost = GhostSettings.fromJson(json.optJSONObject("defaultGhost")) ?: defaults.defaultGhost,
                defaultExpireTimerSeconds = json.longOr("defaultExpireTimerSeconds", defaults.defaultExpireTimerSeconds),
                autoDownloadMedia = json.booleanOr("autoDownloadMedia", defaults.autoDownloadMedia),
            )
        }
    }
}

/** What changed, for the observable facade. */
sealed interface EngineChange {
    /** The chat list (conversations, requests, contacts) changed. */
    data object Conversations : EngineChange

    /** The messages of one conversation changed. */
    data class Messages(val peer: AccountId) : EngineChange

    data object Presence : EngineChange

    data object Typing : EngineChange

    /** A new message arrived (for an in-app banner when it is not on screen). */
    data class NewMessage(val peer: AccountId) : EngineChange

    /** A conversation was wiped (ghost mode, `ConversationWipe`, or locally). */
    data class Wiped(val peer: AccountId) : EngineChange

    /** A safety number changed; sending to this contact is blocked until accepted. */
    data class SafetyNumberChanged(val peer: AccountId) : EngineChange

    /** An attachment of the message is downloading: the fraction received (0…1), at most every 2 %. */
    data class DownloadProgress(val messageId: MessageId, val peer: AccountId, val fraction: Double) : EngineChange
}

enum class EngineError {
    /** No such contact or conversation. */
    UNKNOWN_CONTACT,

    /**
     * Not allowed in the contact's request state (accepting what is not a request, answering a
     * request before accepting it, sending to a blocked contact …).
     */
    NOT_ALLOWED,

    /** The message cannot be acted on (not ours, deleted, not view-once …). */
    INVALID_MESSAGE,

    /** The media of the message is not available on this phone. */
    MEDIA_UNAVAILABLE,

    /** Text, caption or another field over its protocol limit. */
    TOO_LARGE,

    /** The username is not registered (exact match only, §4.4). */
    USERNAME_NOT_FOUND,

    /** Starting a conversation with ourselves. */
    OWN_ACCOUNT,

    /** The engine is stopped (the vault locked). */
    STOPPED,
}

class EngineException(val error: EngineError) : Exception("engine: $error")

internal fun engineFailure(error: EngineError): Nothing = throw EngineException(error)

/** What to send as a bubble. */
sealed interface OutgoingContent {
    data class Text(val text: String) : OutgoingContent

    data class Location(val location: LocationInfo) : OutgoingContent

    /** A built-in sticker (PROTOCOL.md §8.8). [lang] is the language its words were shown in ("tr", "en"); empty for a wordless sticker. */
    data class Sticker(val packId: String, val stickerId: Int, val emoji: String, val lang: String = "") : OutgoingContent

    /** A prepared (compressed, metadata-stripped) file from the media preparation; it is moved into encrypted storage. */
    data class Media(val prepared: PreparedMedia) : OutgoingContent

    companion object {
        /** The longest `Sticker.lang` sent: a BCP-47 tag is at most 35 characters (§8.8). */
        const val MAX_STICKER_LANG_LENGTH = 35

        /** The pack of the plain text stickers of builds up to 1.0.5 (§8.8): still read, never sent. */
        const val LEGACY_TEXT_STICKER_PACK = "us2us.text"

        /** What a resend of such a sticker sends: `words.ok` of the catalog, without a language. */
        val LEGACY_TEXT_STICKER_SUCCESSOR = Sticker(packId = "us2us.words", stickerId = 109, emoji = "👌")
    }
}

/**
 * A picture to draw for one account, and where it came from (PROTOCOL.md §8.9, "Which picture is
 * shown"). [picture] `null` is the initial letter.
 */
data class AvatarLookup(
    val picture: ProfilePicture?,
    val source: Source,
    /**
     * An incoming message request whose sender's username is verified: nothing was fetched or
     * decoded (§8.9). Draw the initial letter and offer "Show picture"; on that tap call
     * [MessagingEngine.requestAvatar] with `userConfirmed`.
     */
    val availableOnRequest: Boolean = false,
) {
    enum class Source {
        /** The user's own choice. */
        OWN,

        /** An accepted contact's last `ProfileUpdate`. */
        CONTACT,

        /** The account's avatar record for search (§8.10). */
        SEARCH,

        /** Nothing to show but the initial letter. */
        INITIAL_LETTER,
    }

    companion object {
        val INITIALS = AvatarLookup(null, Source.INITIAL_LETTER)
    }
}

/** Where the account's avatar record for search stands (PROTOCOL.md §8.10, "Publishing"). */
enum class AvatarRecordStatus {
    /** The relay holds what the picture, the setting and the username ask for: the record, or none. */
    IN_SYNC,

    /** A write is still owed; it is made again at the next connection or the next change. */
    PENDING,

    /** The relay has no avatar records (`404` or `405`); tried again after the next app start. */
    UNAVAILABLE,
}

/** The user's own picture and its search setting (§8.9, §8.10); [picture] `null` is the initial letter. */
data class OwnProfilePicture(
    val picture: ProfilePicture?,
    /** "Aramada göster" / "Show in search"; on by default. */
    val showInSearch: Boolean,
    /** The state of the search record (none is published for the initial letter or with the setting off). */
    val searchRecord: AvatarRecordStatus,
) {
    /** Whether a record is published for search: a preset or a photo with the setting on. */
    val isShownInSearch: Boolean get() = showInSearch && picture != null
}

internal fun Int.unsigned(): Long = toLong() and 0xFFFF_FFFFL

private fun JSONObject.stringOr(name: String, fallback: String): String =
    if (has(name) && !isNull(name)) (opt(name) as? String) ?: fallback else fallback

private fun JSONObject.booleanOr(name: String, fallback: Boolean): Boolean =
    if (has(name) && !isNull(name)) (opt(name) as? Boolean) ?: fallback else fallback

private fun JSONObject.longOr(name: String, fallback: Long): Long =
    if (has(name) && !isNull(name)) (opt(name) as? Number)?.toLong()?.takeIf { it >= 0 } ?: fallback else fallback
