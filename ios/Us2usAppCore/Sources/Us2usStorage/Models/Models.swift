// us2us — AGPL-3.0-only

import Foundation
import LibSignalClient
import Us2usKit

/// A conversation's local id (16 random bytes; unrelated to its slot id).
public struct ConversationId: Hashable, Sendable, CustomStringConvertible {
    public let rawValue: Data

    public init?(rawValue: Data) {
        guard rawValue.count == RandomId.byteCount else { return nil }
        self.rawValue = rawValue
    }

    static func make() -> ConversationId { ConversationId(rawValue: RandomId.make())! }

    public var description: String { rawValue.hexString }
}

// MARK: Contacts

/// A contact (PROTOCOL.md §6.4, §8.6, §8.7, §3.3). Identity fields come from
/// the protocol store, the single source of truth for keys and trust.
public struct Contact: Sendable, Equatable {
    public let accountId: AccountId
    /// The §6.4 message-request state.
    public var requestState: ConversationRequestState
    /// What the contact last told us (`ProfileUpdate`) plus whether its
    /// username claim was checked against the directory.
    public var profile: ContactProfile
    /// The key the contact gave us (`AccessKeyUpdate`), used to send without PoW.
    public var theirAccessKey: Data?
    /// The access key we last sent this contact; differs from the current
    /// one after a rotation, until the new key is sent.
    public var ourAccessKeySent: Data?
    /// When our last complete `ProfileUpdate` went to this contact.
    public var profileSentAt: Date?
    public let createdAt: Date
    /// Wiped by ghost mode with `wipe_contact`; hidden, deleted once the
    /// runtime has sent `ConversationWipe` (§8.2).
    public var pendingForget: Bool
    public var identity: IdentityTrust

    public var isBlocked: Bool { requestState == .blocked }

    /// The contact shares "active now" (false until its first ProfileUpdate, §8.6).
    public var presenceSharing: Bool { profile.presenceSharing }

    public var identityKey: IdentityKey? {
        switch identity {
        case .unknown: return nil
        case .trusted(let key, _): return key
        case .changed(_, let current): return current
        }
    }

    public var isVerified: Bool {
        if case .trusted(_, verified: true) = identity { return true }
        return false
    }

    /// The safety number changed and sending is blocked until accepted (§5, §6.3).
    public var identityChanged: Bool {
        if case .changed = identity { return true }
        return false
    }
}

// MARK: Conversations

public struct Conversation: Sendable, Equatable {
    public let id: ConversationId
    public let contact: AccountId
    /// The key-slot id of this conversation's `CK` (§5.3).
    public let slotId: Data
    /// Last-writer-wins settings (`ConversationSettings`, brp.proto).
    public var settings: BRPConversationSettings
    /// Who wrote ``settings`` (breaks LWW ties); `nil` for the defaults.
    public var settingsAuthor: AccountId?
    /// Local time of the most recent sent or received `DataMessage` (§8.2).
    public var lastActivityAt: Date?
    /// Local time ghost mode was enabled or its timeout last changed.
    public var ghostAnchorAt: Date?
    /// When ghost mode wipes this conversation, if enabled.
    public var ghostDeadline: Date?
    public let createdAt: Date

    public var isGhostModeEnabled: Bool { settings.ghost.enabled }
    public var defaultExpireTimerSeconds: UInt32 { settings.defaultExpireTimerSeconds }
}

// MARK: Messages

/// What a stored bubble shows (`DataMessage.payload`). Reactions and deletes
/// are applied to their targets and not stored as bubbles.
public enum MessageKind: Int, Sendable, Equatable {
    case unknown = 0
    case text = 1
    case attachment = 2
    case location = 3
    case liveLocation = 4
    case sticker = 5

    init(_ payload: BRPDataMessage.OneOf_Payload?) {
        switch payload {
        case .text: self = .text
        case .attachment: self = .attachment
        case .location: self = .location
        case .liveLocation: self = .liveLocation
        case .sticker: self = .sticker
        case .reaction, .delete, .none: self = .unknown
        }
    }
}

/// Delivery status (§5.4): outgoing Sending → Sent (relay accepted) →
/// Delivered (receipt) → Read (optional); Not delivered after 14 days without
/// a delivery receipt. Incoming messages are `received`.
public enum MessageStatus: Int, Sendable, Equatable, Comparable {
    case sending = 0
    case sent = 1
    case delivered = 2
    case read = 3
    case notDelivered = 4
    /// The relay rejected it for good (e.g. blocked, invalid); not retried.
    case failed = 5
    case received = 10

    public static func < (lhs: MessageStatus, rhs: MessageStatus) -> Bool { lhs.rawValue < rhs.rawValue }
}

public struct MessageTimestamps: Sendable, Equatable {
    /// Relay answered 202 (Sent).
    public var acceptedAt: Date?
    public var deliveredAt: Date?
    public var readAt: Date?
    /// View-once or voice note opened (by them for outgoing, by us for incoming).
    public var viewedAt: Date?
    public var notDeliveredAt: Date?
    public var failedAt: Date?
    /// Incoming: first displayed on this phone (starts the disappearing timer, §8.1).
    public var displayedAt: Date?
    /// Outgoing photo, video or file: the contact saved it outside the app
    /// (their `ReceiptMessage{SAVED}`, §8.4).
    public var savedByPeerAt: Date?
    /// First saved outside the app on this phone.
    public var savedByMeAt: Date?
}

public struct StoredMessage: Sendable, Equatable {
    /// Local row id; orders messages within a conversation.
    public let localId: Int64
    public let conversationId: ConversationId
    /// `DataMessage.message_id` (16 bytes, sender-generated).
    public let messageId: Data
    public let isOutgoing: Bool
    public let sender: AccountId
    public let kind: MessageKind
    /// The decrypted `DataMessage`; `nil` once deleted for everyone or once
    /// a view-once message was viewed.
    public let content: BRPDataMessage?
    /// Sender clock (`DataMessage.sent_at`).
    public let sentAt: Date
    /// Local time the message was stored.
    public let createdAt: Date
    public let serverTimestamp: Date?
    public let status: MessageStatus
    public let timestamps: MessageTimestamps
    public let expireTimerSeconds: UInt32
    /// When the disappearing timer deletes it (§8.1).
    public let expiresAt: Date?
    public let isViewOnce: Bool
    public let quoteTarget: Data?
    public let liveSessionId: Data?
    public let deletedAt: Date?

    public var isDeleted: Bool { deletedAt != nil }
}

public struct StoredReaction: Sendable, Equatable {
    public let targetMessageId: Data
    public let reactor: AccountId
    public let emoji: String
    public let sentAt: Date
}

/// What storing an incoming or outgoing `DataMessage` did.
public enum DataMessageOutcome: Sendable, Equatable {
    /// A new bubble.
    case stored(StoredMessage)
    /// Already stored (same `message_id`): nothing changed.
    case duplicate
    /// A reaction was applied to (or removed from) its target.
    case reaction(target: Data)
    /// A delete-for-everyone removed its target.
    case deleted(target: Data)
    /// A live-location update changed its bubble in place.
    case liveLocationUpdated(StoredMessage)
    /// Not applicable (unknown target, delete by someone else, stale update…).
    case ignored
}

// MARK: Attachments

public enum AttachmentState: Int, Sendable, Equatable {
    case pendingUpload = 0
    case uploading = 1
    case uploaded = 2
    case pendingDownload = 10
    case downloading = 11
    case downloaded = 12
    case failed = 20
    /// The relay no longer has the blob (14-day TTL) or view-once was used up.
    case unavailable = 21
}

public struct StoredAttachment: Sendable, Equatable {
    /// Random item id of the local file (binds the item, §5.3).
    public let id: Data
    public let conversationId: ConversationId
    public let messageId: Data
    /// Pointer and keys for the relay blob (`Attachment`); `nil` before upload.
    public let descriptor: BRPAttachment?
    public let state: AttachmentState
    /// The encrypted local file exists.
    public let hasFile: Bool
    /// Item id of a locally generated thumbnail file, if any.
    public let thumbnailId: Data?
    public let byteCount: UInt64?
    public let transferredBytes: UInt64
    public let attempts: Int
    public let updatedAt: Date
}

// MARK: Outbox

public enum OutboxState: Int, Sendable, Equatable {
    case ready = 0
    /// Waits for an attachment upload before it can be sent.
    case waitingForUpload = 1
    /// Given up (permanent relay error or too old); kept until handled.
    case failed = 2
}

public struct OutboxEntry: Sendable, Equatable {
    public let id: Int64
    public let recipient: AccountId
    public let conversationId: ConversationId?
    /// The `DataMessage` this send carries, to update its status.
    public let messageId: Data?
    public let content: BRPContent
    public let ephemeral: Bool
    public let state: OutboxState
    public let attempts: Int
    public let nextAttemptAt: Date
    public let createdAt: Date
    /// A short error code of the last failure (never content).
    public let lastError: String?
}

/// Exponential backoff for outbox retries: `base · 2^(attempts−1)`, capped,
/// with ±`jitter` randomization so many phones do not retry in lockstep.
public struct RetryPolicy: Sendable, Equatable {
    public var base: TimeInterval
    public var maximum: TimeInterval
    public var jitter: Double
    /// Entries older than this are marked failed (the relay keeps messages
    /// 14 days, so later delivery would be pointless).
    public var maximumAge: TimeInterval

    public init(base: TimeInterval = 2, maximum: TimeInterval = 900, jitter: Double = 0.2,
                maximumAge: TimeInterval = 14 * 24 * 3600) {
        self.base = base
        self.maximum = maximum
        self.jitter = jitter
        self.maximumAge = maximumAge
    }

    public static let standard = RetryPolicy()

    public func delay(afterAttempts attempts: Int, random: Double = Double.random(in: -1...1)) -> TimeInterval {
        guard attempts > 0 else { return 0 }
        let exponent = min(attempts - 1, 30)
        let raw = min(maximum, base * pow(2, Double(exponent)))
        return max(0, raw * (1 + jitter * max(-1, min(1, random))))
    }
}

// MARK: Wipes

public enum WipeReason: Sendable, Equatable {
    /// Ghost-mode inactivity timeout (§8.2).
    case ghostTimeout
    /// `ConversationWipe` received, or the user deleted the conversation.
    case requested
    /// The slot was found missing (crypto-shredded while locked, or a crash
    /// mid-wipe); the rows were garbage-collected.
    case shreddedEarlier
}

public struct WipedConversation: Sendable, Equatable {
    public let conversationId: ConversationId
    public let contact: AccountId
    public let reason: WipeReason
    /// Ghost mode had `wipe_contact`; the contact is hidden and flagged
    /// ``Contact/pendingForget``.
    public let wipeContact: Bool
    /// Ghost mode was enabled: the runtime should send
    /// `ConversationWipe{GHOST_TIMEOUT}` to the contact (§8.2).
    public let ghost: Bool
}

/// What one maintenance pass removed.
public struct WipeReport: Sendable, Equatable {
    public var conversations: [WipedConversation] = []
    /// Disappearing messages deleted (§8.1), by conversation.
    public var expiredMessages: [ConversationId: [Data]] = [:]
    public var orphanFilesRemoved = 0
    public var orphanSlotsRemoved = 0

    public var isEmpty: Bool {
        conversations.isEmpty && expiredMessages.isEmpty && orphanFilesRemoved == 0 && orphanSlotsRemoved == 0
    }
}
