import Foundation
import Us2usKit
import Us2usStorage

// View models the UI binds to. They are built from Us2usStorage records
// (decrypted while unlocked) and dropped from memory when the app locks.

/// A 16-byte message id (`DataMessage.message_id`).
public typealias MessageId = Data

/// A contact as the UI shows it.
public struct ContactInfo: Sendable, Equatable, Identifiable {
    public var id: AccountId { account }
    public let account: AccountId
    public let requestState: ConversationRequestState
    /// The contact's display name, if it shared one (§8.7).
    public let displayName: String?
    /// The username, and whether the directory confirmed it (§3.3). `nil`
    /// when the directory maps the name to another account (a mismatch is
    /// never shown as this contact's name — e.g. the account was deleted
    /// and someone else registered the name).
    public let username: String?
    public let usernameVerified: Bool
    /// The contact shares "active now" (§8.6).
    public let presenceSharing: Bool
    /// The safety number changed and sending is blocked until accepted (§5).
    public let safetyNumberChanged: Bool
    /// The safety number was verified in person.
    public let verified: Bool
    /// The contact's current identity key (serialized public key), `nil`
    /// before one is known — there is no safety number to compare yet.
    public let identityKey: Data?
    /// The picture from the contact's last `ProfileUpdate` (§8.9): the
    /// initial letter until one arrives, and always for message requests
    /// and blocked contacts (an incoming request's picture is fetched only
    /// when the user taps for it: ``AppRuntime/requestAvatar(of:userConfirmed:)``).
    public let avatar: ProfilePicture

    init(_ contact: Contact) {
        account = contact.accountId
        requestState = contact.requestState
        displayName = contact.profile.displayName
        username = contact.profile.usernameStatus == .mismatch ? nil : contact.profile.username?.value
        usernameVerified = contact.profile.usernameStatus == .verified
        presenceSharing = contact.profile.presenceSharing
        safetyNumberChanged = contact.identityChanged
        verified = contact.isVerified
        identityKey = contact.identityKey?.serialize()
        avatar = contact.requestState == .accepted ? contact.profile.avatar : .initials
    }

    /// The name to show: the display name, else the username (the UI marks
    /// an unverified one).
    public var title: String { displayName ?? username ?? "" }
}

/// Ghost mode of a conversation (§8.2).
public struct GhostSettings: Sendable, Equatable, Codable {
    public var enabled: Bool
    /// 60 … 604800 seconds.
    public var timeoutSeconds: UInt32
    /// "Leave no trace": also delete the contact and session.
    public var wipeContact: Bool

    public init(enabled: Bool, timeoutSeconds: UInt32, wipeContact: Bool = false) {
        self.enabled = enabled
        self.timeoutSeconds = timeoutSeconds
        self.wipeContact = wipeContact
    }

    init(_ mode: BRPGhostMode) {
        self.init(enabled: mode.enabled, timeoutSeconds: mode.timeoutSeconds, wipeContact: mode.wipeContact)
    }

    var proto: BRPGhostMode {
        var mode = BRPGhostMode()
        mode.enabled = enabled
        mode.timeoutSeconds = timeoutSeconds
        mode.wipeContact = wipeContact
        return mode
    }
}

/// A conversation's state for the header and the chat list.
public struct ConversationInfo: Sendable, Equatable {
    public let id: ConversationId
    public let peer: AccountId
    /// Ghost mode, when it is on.
    public let ghost: GhostSettings?
    /// Applied to new outgoing messages; 0 = off (§8.1).
    public let defaultExpireTimerSeconds: UInt32
    /// When ghost mode wipes the conversation (the header countdown).
    public let ghostDeadline: Date?
    public let lastActivity: Date
    public let unreadCount: Int

    init(_ conversation: Conversation, unreadCount: Int) {
        id = conversation.id
        peer = conversation.contact
        ghost = conversation.settings.ghost.enabled ? GhostSettings(conversation.settings.ghost) : nil
        defaultExpireTimerSeconds = conversation.settings.defaultExpireTimerSeconds
        ghostDeadline = conversation.ghostDeadline
        lastActivity = conversation.lastActivityAt ?? conversation.createdAt
        self.unreadCount = unreadCount
    }
}

public enum MessageDirection: String, Sendable, Equatable {
    case incoming, outgoing, system
}

/// What a bubble shows.
public enum MessageBody: Sendable, Equatable {
    case text(String)
    case attachment(AttachmentItem)
    case location(LocationInfo)
    case liveLocation(LiveLocationInfo)
    case sticker(StickerInfo)
    /// Deleted for everyone (§8.5): the tombstone.
    case deleted
    /// A view-once item that was opened (on either side): nothing is left.
    case viewOnceOpened
    /// A local system line (screenshot, safety number, settings …).
    case notice(SystemNotice)
    /// A payload this version does not know.
    case unsupported
}

/// An attachment and where its bytes are.
public struct AttachmentItem: Sendable, Equatable {
    public enum Kind: String, Sendable, Equatable {
        case image, video, voice, file, stickerImage
    }

    public enum Availability: Sendable, Equatable {
        /// Stored here, encrypted under the conversation key.
        case local
        /// On its way (upload or download).
        case transferring
        /// On the relay, not downloaded yet.
        case remote
        /// From a message request: hidden until the request is accepted (§6.4).
        case heldUntilAccepted
        /// Expired on the relay, opened (view-once) or failed its integrity check.
        case unavailable
    }

    /// The local attachment id.
    public let id: Data
    public let kind: Kind
    public let contentType: String
    public let fileName: String?
    public let size: UInt64
    public let width: UInt32
    public let height: UInt32
    public let durationMs: UInt32
    /// Inline JPEG ≤ 10 KiB, end-to-end encrypted with the message.
    public let thumbnail: Data?
    public let caption: String?
    public let waveform: Data?
    public let availability: Availability
}

public struct LocationInfo: Sendable, Equatable {
    public var latitude: Double
    public var longitude: Double
    public var accuracyMeters: Float
    public var name: String?
    public var address: String?

    public init(latitude: Double, longitude: Double, accuracyMeters: Float, name: String? = nil, address: String? = nil) {
        self.latitude = latitude
        self.longitude = longitude
        self.accuracyMeters = accuracyMeters
        self.name = name
        self.address = address
    }
}

public struct LiveLocationInfo: Sendable, Equatable {
    public let sessionId: Data
    public let latitude: Double
    public let longitude: Double
    public let accuracyMeters: Float
    public let headingDegrees: Float
    public let speedMps: Float
    public let expiresAt: Date
    public let sequence: UInt32
    public let stopped: Bool

    public func isActive(at now: Date) -> Bool { !stopped && now < expiresAt }

    init(_ live: BRPLiveLocation) {
        sessionId = live.sessionID
        latitude = live.latitude
        longitude = live.longitude
        accuracyMeters = live.accuracyMeters
        headingDegrees = live.headingDegrees
        speedMps = live.speedMps
        expiresAt = Date(timeIntervalSince1970: TimeInterval(live.expiresAt) / 1000)
        sequence = live.sequence
        stopped = live.stopped
    }
}

public struct StickerInfo: Sendable, Equatable {
    public let packId: String
    public let stickerId: UInt32
    public let emoji: String
    /// A built-in sticker with words: the language they were shown in on the
    /// sender's side (“tr”, “en”, PROTOCOL.md §8.8), as received. Empty for a
    /// wordless sticker and from older senders.
    public let lang: String
    /// A custom sticker travels as an attachment.
    public let custom: AttachmentItem?
}

/// Local system lines. Stored as events inside the conversation's own
/// crypto-shreddable notice item, and localized when shown.
public enum SystemNotice: Sendable, Equatable, Codable {
    case screenshotByMe
    case screenRecordingByMe
    case screenshotByContact
    case safetyNumberChanged
    /// Written by TestFlight builds 4–6 when the user verified a safety
    /// number (their only way to accept a change was to verify it), so it
    /// reads as a verification. Never written any more.
    case safetyNumberAccepted
    /// A changed safety number was accepted without verifying it.
    case safetyNumberAcceptedUnverified
    /// The safety number was verified (compared, scanned or pinned by a contact code).
    case safetyNumberVerified
    case requestAccepted
    /// Ghost / disappearing settings changed.
    case settingsChanged(SettingsChange)

    public struct SettingsChange: Sendable, Equatable, Codable {
        public var ghost: GhostSettings
        public var expireTimerSeconds: UInt32
        public var previousGhost: GhostSettings
        public var previousExpireTimerSeconds: UInt32
    }

    /// The text to show, in the device language.
    public func text(contactName: String, strings: RuntimeStrings = .current, localizer: Localizer = .current) -> String {
        switch self {
        case .screenshotByMe: return strings.string(.screenshotTakenByMe)
        case .screenRecordingByMe: return strings.string(.screenRecordingByMe)
        case .screenshotByContact: return localizer.string(.screenshotTaken, contactName)
        case .safetyNumberChanged: return localizer.string(.safetyNumberChanged, contactName)
        case .safetyNumberAccepted, .safetyNumberVerified: return strings.string(.safetyNumberVerifiedLine)
        case .safetyNumberAcceptedUnverified: return strings.string(.safetyNumberAcceptedLine)
        case .requestAccepted: return strings.string(.requestAcceptedLine)
        case .settingsChanged(let change):
            var current = BRPConversationSettings()
            current.defaultExpireTimerSeconds = change.expireTimerSeconds
            current.ghost = change.ghost.proto
            var previous = BRPConversationSettings()
            previous.defaultExpireTimerSeconds = change.previousExpireTimerSeconds
            previous.ghost = change.previousGhost.proto
            return localizer.notices(for: current, previous: previous).joined(separator: "\n")
        }
    }
}

/// What the sender sees under a bubble (§5.4).
public enum OutgoingStatus: Sendable, Equatable {
    /// Waiting in the outbox (no network, locked, retrying, uploading).
    case sending
    /// Accepted by the relay; waiting for the recipient to open the app.
    case sent
    case delivered
    case read
    /// 14 days without a delivery receipt: the relay has deleted it.
    case notDelivered
    /// Refused for good (blocked, gone, invalid); "Tekrar gönder" sends it anew.
    case failed

    init(_ status: MessageStatus) {
        switch status {
        case .sending: self = .sending
        case .sent: self = .sent
        case .delivered: self = .delivered
        case .read: self = .read
        case .notDelivered: self = .notDelivered
        case .failed: self = .failed
        case .received: self = .delivered
        }
    }
}

/// One message (bubble or system line), in chronological order.
public struct MessageHistory: Sendable {
    public let items: [MessageItem]
    public let oldest: Int64?
    public let hasOlder: Bool
}

public struct MessageItem: Sendable, Equatable, Identifiable {
    public var id: Data { messageId }
    public let messageId: MessageId
    public let direction: MessageDirection
    public let body: MessageBody
    /// Sender clock (`sent_at`); local time for system lines.
    public let sentAt: Date
    /// Local time it was stored; the sort key.
    public let storedAt: Date
    public let quote: QuoteInfo?
    public let expireTimerSeconds: UInt32
    /// When the disappearing timer deletes it (§8.1).
    public let expiresAt: Date?
    public let viewOnce: Bool
    /// One emoji per reacting account.
    public let reactions: [AccountId: String]
    /// Outgoing only.
    public let status: OutgoingStatus?
    public let deliveredAt: Date?
    public let readAt: Date?
    /// Incoming: first displayed (starts the disappearing timer).
    public let displayedAt: Date?
    /// A voice note played — outgoing: by the contact (their VIEWED);
    /// incoming: on this phone. A view-once item: when it was opened.
    public let viewedAt: Date?
    /// Outgoing photo, video or file: when the contact said it saved it
    /// outside the app (their SAVED receipt, §8.4).
    public let savedByPeerAt: Date?
    /// When it was first saved outside the app on this phone
    /// (``AppRuntime/markMediaSaved(_:peer:)``).
    public let savedByMeAt: Date?

    /// Text that may be copied: never from disappearing or view-once messages.
    public var isCopyable: Bool {
        guard expireTimerSeconds == 0, !viewOnce, case .text = body else { return false }
        return true
    }

    static func notice(_ record: NoticeRecord) -> MessageItem {
        MessageItem(
            messageId: record.id, direction: .system, body: .notice(record.notice), sentAt: record.at,
            storedAt: record.at, quote: nil, expireTimerSeconds: 0, expiresAt: nil, viewOnce: false, reactions: [:],
            status: nil, deliveredAt: nil, readAt: nil, displayedAt: nil, viewedAt: nil, savedByPeerAt: nil,
            savedByMeAt: nil)
    }
}

/// Which media of a conversation to list
/// (``AppRuntime/mediaMessages(with:kind:before:limit:)``).
public enum MediaKind: Sendable, Equatable, CaseIterable {
    /// Photos and videos.
    case visual
    /// Files.
    case documents
}

public struct QuoteInfo: Sendable, Equatable {
    public var targetMessageId: MessageId
    public var previewText: String

    public init(targetMessageId: MessageId, previewText: String) {
        self.targetMessageId = targetMessageId
        self.previewText = previewText
    }
}

/// One row of the chat list.
public struct ChatSummary: Sendable, Equatable, Identifiable {
    public var id: AccountId { contact.account }
    public let contact: ContactInfo
    public let conversation: ConversationInfo
    public let lastMessage: MessageItem?

    public var isRequest: Bool { contact.requestState == .incomingRequest }
    public var unreadCount: Int { conversation.unreadCount }
    public var ghostDeadline: Date? { conversation.ghostDeadline }
}

/// The user's own preferences, inside the vault (the runtime state file).
public struct UserSettings: Sendable, Equatable, Codable {
    public var displayName: String
    /// "Show when I'm active" (§8.6), default on; reciprocal.
    public var presenceSharing: Bool
    /// Optional read receipts (§8.4), default on.
    public var readReceipts: Bool
    /// Optional typing indicators (§8.4), default on.
    public var typingIndicators: Bool
    /// Defaults for new conversations.
    public var defaultGhost: GhostSettings?
    public var defaultExpireTimerSeconds: UInt32
    /// Download media from accepted contacts automatically.
    public var autoDownloadMedia: Bool

    public init(
        displayName: String = "", presenceSharing: Bool = true, readReceipts: Bool = true,
        typingIndicators: Bool = true, defaultGhost: GhostSettings? = nil, defaultExpireTimerSeconds: UInt32 = 0,
        autoDownloadMedia: Bool = true
    ) {
        self.displayName = displayName
        self.presenceSharing = presenceSharing
        self.readReceipts = readReceipts
        self.typingIndicators = typingIndicators
        self.defaultGhost = defaultGhost
        self.defaultExpireTimerSeconds = defaultExpireTimerSeconds
        self.autoDownloadMedia = autoDownloadMedia
    }

    private enum CodingKeys: String, CodingKey {
        case displayName, presenceSharing, readReceipts, typingIndicators, defaultGhost, defaultExpireTimerSeconds
        case autoDownloadMedia
    }
}

// Tolerant decoding, like `RuntimeStateData`: a missing or unreadable field
// takes its default instead of failing the whole runtime state file, which
// would reset every setting at once.
extension UserSettings {
    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let defaults = UserSettings()
        displayName = (try? container.decodeIfPresent(String.self, forKey: .displayName)) ?? defaults.displayName
        presenceSharing =
            (try? container.decodeIfPresent(Bool.self, forKey: .presenceSharing)) ?? defaults.presenceSharing
        readReceipts = (try? container.decodeIfPresent(Bool.self, forKey: .readReceipts)) ?? defaults.readReceipts
        typingIndicators =
            (try? container.decodeIfPresent(Bool.self, forKey: .typingIndicators)) ?? defaults.typingIndicators
        defaultGhost = (try? container.decodeIfPresent(GhostSettings.self, forKey: .defaultGhost)) ?? defaults.defaultGhost
        defaultExpireTimerSeconds =
            (try? container.decodeIfPresent(UInt32.self, forKey: .defaultExpireTimerSeconds))
            ?? defaults.defaultExpireTimerSeconds
        autoDownloadMedia =
            (try? container.decodeIfPresent(Bool.self, forKey: .autoDownloadMedia)) ?? defaults.autoDownloadMedia
    }
}
