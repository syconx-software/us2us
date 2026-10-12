package com.syconx.us2us.runtime.storage

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.contacts.UsernameStatus
import com.syconx.us2us.core.encoding.Hex
import com.syconx.us2us.core.profile.ProfilePicture
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.proto.ConversationSettings
import com.syconx.us2us.core.proto.DataMessage
import com.syconx.us2us.core.proto.ProfileUpdate
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.runtime.vault.SlotId
import org.signal.libsignal.protocol.IdentityKey
import java.security.SecureRandom
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/** 16 random bytes: conversation ids, message ids, item ids, payload ids. */
internal object RandomIds {
    const val LENGTH = 16
    private val random = SecureRandom()

    fun make(): ByteString = ByteString.copyFrom(ByteArray(LENGTH).also(random::nextBytes))
}

/** A conversation's local id (16 random bytes; unrelated to its slot id). */
@JvmInline
value class ConversationId(val raw: ByteString) {
    init {
        require(raw.size() == RandomIds.LENGTH) { "a conversation id is ${RandomIds.LENGTH} bytes" }
    }

    override fun toString(): String = Hex.encode(raw.toByteArray())

    companion object {
        fun of(bytes: ByteString?): ConversationId? = bytes?.takeIf { it.size() == RandomIds.LENGTH }?.let(::ConversationId)

        internal fun make(): ConversationId = ConversationId(RandomIds.make())
    }
}

// MARK: Contacts

/** The raw value of a §6.4 state in the database, the same text as the iOS app's. */
internal val ContactState.raw: String
    get() = when (this) {
        ContactState.OUTGOING_REQUEST -> "outgoingRequest"
        ContactState.INCOMING_REQUEST -> "incomingRequest"
        ContactState.ACCEPTED -> "accepted"
        ContactState.BLOCKED -> "blocked"
    }

internal fun contactState(raw: String?): ContactState? = ContactState.entries.firstOrNull { it.raw == raw }

internal val UsernameStatus.raw: String
    get() = when (this) {
        UsernameStatus.UNCHECKED -> "unchecked"
        UsernameStatus.VERIFIED -> "verified"
        UsernameStatus.MISMATCH -> "mismatch"
    }

internal fun usernameStatus(raw: String?): UsernameStatus? = UsernameStatus.entries.firstOrNull { it.raw == raw }

/**
 * What a contact last told us (`ProfileUpdate`, §8.7) plus whether its username claim was checked
 * against the directory (§3.3).
 */
data class ContactProfile(
    val username: Username? = null,
    val usernameStatus: UsernameStatus = UsernameStatus.UNCHECKED,
    val displayName: String? = null,
    /** The contact shares "active now" status; false until its first `ProfileUpdate` (§8.6). */
    val presenceSharing: Boolean = false,
    /** The contact's last `ProfileUpdate.profile_avatar` (§8.9), checked when it arrived; `null` is the initial letter. */
    val picture: ProfilePicture? = null,
    /** `ProfileUpdate.updated_at` of the profile shown (ms since the epoch, unsigned). */
    val updatedAt: Long = 0,
) {
    /**
     * The profile after receiving [update]. Every `ProfileUpdate` is complete and replaces the
     * stored profile; an empty or malformed field means "none" (§8.7). Its username is only a
     * claim: a new one is unchecked, while repeating the current one keeps the earlier result.
     */
    fun applying(update: ProfileUpdate): ContactProfile {
        val claimed = Username.parse(update.username)?.takeIf { it.value == update.username }
        val displayName = update.displayName.takeIf {
            it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= StorageRules.MAX_DISPLAY_NAME_BYTES
        }
        return ContactProfile(
            username = claimed,
            usernameStatus = if (claimed == username) usernameStatus else UsernameStatus.UNCHECKED,
            displayName = displayName,
            presenceSharing = update.presenceSharing,
            picture = if (update.hasProfileAvatar()) ProfilePicture.fromProto(update.profileAvatar) else null,
            updatedAt = update.updatedAt,
        )
    }

    /** Whether the username claim still needs a directory check. */
    val needsUsernameCheck: Boolean get() = username != null && usernameStatus == UsernameStatus.UNCHECKED

    /**
     * Records a directory answer for [checked]: verified if [owner] is [contact], else a mismatch.
     * An answer for a username the contact no longer claims changes nothing.
     */
    fun recordingUsernameCheck(checked: Username, owner: AccountId?, contact: AccountId): ContactProfile {
        if (checked != username) return this
        return copy(usernameStatus = if (owner == contact) UsernameStatus.VERIFIED else UsernameStatus.MISMATCH)
    }

    /** A username already shown to point at this contact: our own lookup, or a verified `sender_username` (§6.3). */
    fun confirming(confirmed: Username): ContactProfile = copy(username = confirmed, usernameStatus = UsernameStatus.VERIFIED)
}

/** How the user verified a contact's identity key (PROTOCOL.md §5). */
enum class IdentityVerificationMethod(val raw: Int) {
    /** The safety number was compared another way and marked as verified by hand. */
    MANUAL(1),

    /** A QR code was scanned side by side, which pinned the key. */
    QR_CODE(2),
    ;

    companion object {
        fun of(raw: Long?): IdentityVerificationMethod? = entries.firstOrNull { it.raw.toLong() == raw }
    }
}

/** When and how the contact's current identity key was verified. It belongs to that key: a change drops it. */
data class IdentityVerification(val method: IdentityVerificationMethod, val verifiedAt: Instant)

/** A contact's identity key as the protocol storage holds it. */
sealed interface ContactIdentity {
    /** No identity stored yet; the first one seen is trusted. */
    data object Unknown : ContactIdentity

    data class Trusted(val key: IdentityKey, val verified: Boolean) : ContactIdentity

    /**
     * The contact's identity changed; sending is blocked until the user accepts the new key.
     * [previous] is the last approved key, or `null` when it is unknown (a change restored from a backup, §5.5).
     */
    data class Changed(val previous: IdentityKey?, val current: IdentityKey) : ContactIdentity
}

/** A contact (PROTOCOL.md §6.4, §8.6, §8.7, §3.3). Identity fields come from the protocol storage. */
data class StoredContact(
    val accountId: AccountId,
    /** The §6.4 message-request state. */
    val requestState: ContactState,
    val profile: ContactProfile,
    /** The key the contact gave us (`AccessKeyUpdate`), used to send without proof-of-work. */
    val theirAccessKey: ByteString?,
    /** The access key we last sent this contact. */
    val ourAccessKeySent: ByteString?,
    /** When our last complete `ProfileUpdate` went to this contact. */
    val profileSentAt: Instant?,
    val createdAt: Instant,
    /** Wiped by ghost mode with `wipe_contact`; hidden, deleted once `ConversationWipe` went out (§8.2). */
    val pendingForget: Boolean,
    val identity: ContactIdentity,
) {
    val isBlocked: Boolean get() = requestState == ContactState.BLOCKED

    val presenceSharing: Boolean get() = profile.presenceSharing

    val identityKey: IdentityKey?
        get() = when (identity) {
            ContactIdentity.Unknown -> null
            is ContactIdentity.Trusted -> identity.key
            is ContactIdentity.Changed -> identity.current
        }

    val isVerified: Boolean get() = identity is ContactIdentity.Trusted && identity.verified

    /** The safety number changed and sending is blocked until accepted (§5, §6.3). */
    val identityChanged: Boolean get() = identity is ContactIdentity.Changed
}

// MARK: Conversations

data class Conversation(
    val id: ConversationId,
    val contact: AccountId,
    /** The key-slot id of this conversation's `CK` (§5.3). */
    val slotId: SlotId,
    /** Last-writer-wins settings (`ConversationSettings`, brp.proto). */
    val settings: ConversationSettings,
    /** Who wrote [settings] (breaks LWW ties); `null` for the defaults. */
    val settingsAuthor: AccountId?,
    /** Local time of the most recent sent or received `DataMessage` (§8.2). */
    val lastActivityAt: Instant?,
    /** Local time ghost mode was enabled or its timeout last changed. */
    val ghostAnchorAt: Instant?,
    /** When ghost mode wipes this conversation, if enabled. */
    val ghostDeadline: Instant?,
    val createdAt: Instant,
) {
    val isGhostModeEnabled: Boolean get() = settings.ghost.enabled

    val defaultExpireTimerSeconds: Long get() = settings.defaultExpireTimerSeconds.unsigned()
}

// MARK: Messages

/** What a stored bubble shows (`DataMessage.payload`). Reactions and deletes are applied to their targets. */
enum class MessageKind(val raw: Int) {
    UNKNOWN(0),
    TEXT(1),
    ATTACHMENT(2),
    LOCATION(3),
    LIVE_LOCATION(4),
    STICKER(5),
    ;

    companion object {
        fun of(raw: Long?): MessageKind = entries.firstOrNull { it.raw.toLong() == raw } ?: UNKNOWN

        fun of(payload: DataMessage.PayloadCase): MessageKind = when (payload) {
            DataMessage.PayloadCase.TEXT -> TEXT
            DataMessage.PayloadCase.ATTACHMENT -> ATTACHMENT
            DataMessage.PayloadCase.LOCATION -> LOCATION
            DataMessage.PayloadCase.LIVE_LOCATION -> LIVE_LOCATION
            DataMessage.PayloadCase.STICKER -> STICKER
            else -> UNKNOWN
        }
    }
}

/**
 * Delivery status (§5.4): outgoing Sending → Sent (relay accepted) → Delivered (receipt) → Read
 * (optional); Not delivered after 14 days without a delivery receipt. Incoming messages are [RECEIVED].
 */
enum class MessageStatus(val raw: Int) {
    SENDING(0),
    SENT(1),
    DELIVERED(2),
    READ(3),
    NOT_DELIVERED(4),

    /** The relay rejected it for good (e.g. blocked, invalid); not retried. */
    FAILED(5),
    RECEIVED(10),
    ;

    companion object {
        fun of(raw: Long?): MessageStatus? = entries.firstOrNull { it.raw.toLong() == raw }
    }
}

data class MessageTimestamps(
    /** Relay answered 202 (Sent). */
    val acceptedAt: Instant? = null,
    val deliveredAt: Instant? = null,
    val readAt: Instant? = null,
    /** View-once or voice note opened (by them for outgoing, by us for incoming). */
    val viewedAt: Instant? = null,
    val notDeliveredAt: Instant? = null,
    val failedAt: Instant? = null,
    /** Incoming: first displayed on this phone (starts the disappearing timer, §8.1). */
    val displayedAt: Instant? = null,
    /** Outgoing photo, video or file: the contact saved it outside the app (`ReceiptMessage{SAVED}`, §8.4). */
    val savedByPeerAt: Instant? = null,
    /** First saved outside the app on this phone. */
    val savedByMeAt: Instant? = null,
)

data class StoredMessage(
    /** Local row id; orders messages within a conversation. */
    val localId: Long,
    val conversationId: ConversationId,
    /** `DataMessage.message_id` (16 bytes, sender-generated). */
    val messageId: ByteString,
    val isOutgoing: Boolean,
    val sender: AccountId,
    val kind: MessageKind,
    /** The decrypted `DataMessage`; `null` once deleted for everyone or once a view-once message was viewed. */
    val content: DataMessage?,
    /** Sender clock (`DataMessage.sent_at`). */
    val sentAt: Instant,
    /** Local time the message was stored. */
    val createdAt: Instant,
    val serverTimestamp: Instant?,
    val status: MessageStatus,
    val timestamps: MessageTimestamps,
    val expireTimerSeconds: Long,
    /** When the disappearing timer deletes it (§8.1). */
    val expiresAt: Instant?,
    val isViewOnce: Boolean,
    val quoteTarget: ByteString?,
    val liveSessionId: ByteString?,
    val deletedAt: Instant?,
) {
    val isDeleted: Boolean get() = deletedAt != null
}

data class StoredReaction(val targetMessageId: ByteString, val reactor: AccountId, val emoji: String, val sentAt: Instant)

/** What storing an incoming or outgoing `DataMessage` did. */
sealed interface DataMessageOutcome {
    /** A new bubble. */
    data class Stored(val message: StoredMessage) : DataMessageOutcome

    /** Already stored (same `message_id`): nothing changed. */
    data object Duplicate : DataMessageOutcome

    /** A reaction was applied to (or removed from) its target. */
    data class Reaction(val target: ByteString) : DataMessageOutcome

    /** A delete-for-everyone removed its target. */
    data class Deleted(val target: ByteString) : DataMessageOutcome

    /** A live-location update changed its bubble in place. */
    data class LiveLocationUpdated(val message: StoredMessage) : DataMessageOutcome

    /** Not applicable (unknown target, delete by someone else, stale update…). */
    data object Ignored : DataMessageOutcome
}

// MARK: Attachments

enum class AttachmentState(val raw: Int) {
    PENDING_UPLOAD(0),
    UPLOADING(1),
    UPLOADED(2),
    PENDING_DOWNLOAD(10),
    DOWNLOADING(11),
    DOWNLOADED(12),
    FAILED(20),

    /** The relay no longer has the blob (14-day TTL) or view-once was used up. */
    UNAVAILABLE(21),
    ;

    companion object {
        fun of(raw: Long?): AttachmentState? = entries.firstOrNull { it.raw.toLong() == raw }
    }
}

data class StoredAttachment(
    /** Random item id of the local file (binds the item, §5.3). */
    val id: ByteString,
    val conversationId: ConversationId,
    val messageId: ByteString,
    /** Pointer and keys for the relay blob (`Attachment`); `null` before upload. */
    val descriptor: Attachment?,
    val state: AttachmentState,
    /** The encrypted local file exists. */
    val hasFile: Boolean,
    /** Item id of a locally generated thumbnail file, if any. */
    val thumbnailId: ByteString?,
    val byteCount: Long?,
    val transferredBytes: Long,
    val attempts: Int,
    val updatedAt: Instant,
)

// MARK: Outbox

enum class OutboxState(val raw: Int) {
    READY(0),

    /** Waits for an attachment upload before it can be sent. */
    WAITING_FOR_UPLOAD(1),

    /** Given up (permanent relay error or too old); kept until handled. */
    FAILED(2),
    ;

    companion object {
        fun of(raw: Long?): OutboxState? = entries.firstOrNull { it.raw.toLong() == raw }
    }
}

data class OutboxEntry(
    val id: Long,
    val recipient: AccountId,
    val conversationId: ConversationId?,
    /** The `DataMessage` this send carries, to update its status. */
    val messageId: ByteString?,
    val content: Content,
    val ephemeral: Boolean,
    val state: OutboxState,
    val attempts: Int,
    val nextAttemptAt: Instant,
    val createdAt: Instant,
    /** A short error code of the last failure (never content). */
    val lastError: String?,
)

/**
 * Exponential backoff for outbox retries: `base · 2^(attempts−1)`, capped, with ±[jitter]
 * randomization so many phones do not retry in lockstep.
 */
data class RetryPolicy(
    val base: Duration = 2.seconds,
    val maximum: Duration = 900.seconds,
    val jitter: Double = 0.2,
    /** Entries older than this are marked failed (the relay keeps messages 14 days). */
    val maximumAge: Duration = 14.days,
) {
    fun delay(afterAttempts: Int, random: Double = Random.nextDouble(-1.0, 1.0)): Duration {
        if (afterAttempts <= 0) return Duration.ZERO
        val exponent = min(afterAttempts - 1, 30)
        val raw = min(maximum.inWholeMilliseconds / 1000.0, base.inWholeMilliseconds / 1000.0 * 2.0.pow(exponent))
        val factor = 1 + jitter * max(-1.0, min(1.0, random))
        return max(0.0, raw * factor).seconds
    }

    companion object {
        val STANDARD = RetryPolicy()
    }
}

// MARK: Wipes

enum class WipeReason {
    /** Ghost-mode inactivity timeout (§8.2). */
    GHOST_TIMEOUT,

    /** `ConversationWipe` received, or the user deleted the conversation. */
    REQUESTED,

    /** The slot was found missing (crypto-shredded while locked, or a crash mid-wipe); the rows were collected. */
    SHREDDED_EARLIER,
}

data class WipedConversation(
    val conversationId: ConversationId,
    val contact: AccountId,
    val reason: WipeReason,
    /** Ghost mode had `wipe_contact`; the contact is hidden and flagged [StoredContact.pendingForget]. */
    val wipeContact: Boolean,
    /** Ghost mode was enabled: the runtime should send `ConversationWipe{GHOST_TIMEOUT}` (§8.2). */
    val ghost: Boolean,
)

/** What one maintenance pass removed. */
data class WipeReport(
    val conversations: List<WipedConversation> = emptyList(),
    /** Disappearing messages deleted (§8.1), by conversation. */
    val expiredMessages: Map<ConversationId, List<ByteString>> = emptyMap(),
    val orphanFilesRemoved: Int = 0,
    val orphanSlotsRemoved: Int = 0,
) {
    val isEmpty: Boolean
        get() = conversations.isEmpty() && expiredMessages.isEmpty() && orphanFilesRemoved == 0 && orphanSlotsRemoved == 0
}

/** A protobuf uint32 read through Java's signed `int`. */
internal fun Int.unsigned(): Long = toLong() and 0xFFFF_FFFFL

/** A protobuf uint64 read through Java's signed `long`, clamped like Swift's `Int64(clamping:)`. */
internal fun Long.clampedUnsigned(): Long = if (this < 0) Long.MAX_VALUE else this
