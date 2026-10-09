import Foundation
import UniformTypeIdentifiers

// App-level models the screens render. They are deliberately UI-shaped
// (what the design shows), independent of Us2usKit's wire types; the live
// MessengerService maps the kit's state into them.

typealias ChatID = String
typealias MessageID = String
typealias RequestID = String

// MARK: - People

/// C12 avatar palettes (fill / letter): A `accent800`/`accent100`,
/// B `accent700`/`accent100`, C `neutral800`/`neutral100`, D (self, outlined)
/// `accent900`/`accent300` + 1 pt `accent700` ring.
enum AvatarPalette: String, CaseIterable, Codable, Sendable, Hashable {
    case a, b, c, d

    /// Deterministic from the username (FNV-1a, stable across launches and
    /// devices). The user's own avatar is always `.d`; unknown senders
    /// (requests) always `.c` — callers decide those cases.
    static func forUsername(_ username: String) -> AvatarPalette {
        var hash: UInt32 = 0x811C_9DC5
        for byte in username.utf8 {
            hash ^= UInt32(byte)
            hash = hash &* 0x0100_0193
        }
        return allCases[Int(hash % UInt32(allCases.count))]
    }
}

/// Activity shown in the chat header (only for accepted contacts that share it).
enum Presence: Hashable, Sendable {
    /// Not shared, or not an accepted contact: no status line.
    case hidden
    /// “Aktif” with a 7 pt online dot.
    case online
    /// “Az önce aktifti”.
    case justNow
    /// “{n} dk önce aktifti” / “Bugün {HH:mm} aktifti” (proposed).
    case lastSeen(Date)
}

struct Peer: Identifiable, Hashable, Sendable {
    var id: String { username }
    /// ASCII lower-case, exact (design 2.1).
    let username: String
    var palette: AvatarPalette
    var isVerified: Bool
    /// Presence and the online dot exist only for accepted contacts (4.1 note).
    var isAcceptedContact: Bool
    var presence: Presence

    var isOnline: Bool { isAcceptedContact && presence == .online }
}

struct OwnAccount: Hashable, Sendable {
    let username: String
    /// Always `.d` (C12).
    var palette: AvatarPalette { .d }
}

// MARK: - Connection

/// The line at the top of the chat list (4.1).
enum ConnectionState: Hashable, Sendable {
    /// “Hat açık · mesajlar geliyor” — packets flow.
    case open
    /// “Hat kuruluyor…” (proposed).
    case connecting
    /// “Hat kapalı · yeniden deneniyor” (proposed) — line static.
    case closed
}

// MARK: - Messages

enum MessageDirection: Hashable, Sendable {
    case incoming
    case outgoing
}

/// C16 — the four delivery states (plus the local “sending”).
enum MessageStatus: Hashable, Sendable, CaseIterable {
    /// Still on this device.
    case sending
    /// Gönderildi — left the device; the relay holds the sealed envelope.
    case sent
    /// İletildi — the recipient opened the app and fetched it.
    case delivered
    /// Okundu — the recipient opened the chat (read receipts on both sides).
    case read
    /// İletilemedi — not fetched within 14 days; deleted from the relay.
    case failed
}

/// Ghost mode periods (5.5): the chat is erased if it stays silent this long.
enum GhostPeriod: String, CaseIterable, Codable, Sendable, Hashable {
    case oneHour, sixHours, oneDay, threeDays, sevenDays

    var duration: TimeInterval {
        switch self {
        case .oneHour: 3_600
        case .sixHours: 6 * 3_600
        case .oneDay: 24 * 3_600
        case .threeDays: 3 * 86_400
        case .sevenDays: 7 * 86_400
        }
    }

    var hours: Int { Int(duration / 3_600) }
}

/// Disappearing-message timers (5.5): counted from when the recipient reads it.
enum DisappearingTimer: String, CaseIterable, Codable, Sendable, Hashable {
    case off, thirtySeconds, fiveMinutes, oneHour, oneDay

    var duration: TimeInterval? {
        switch self {
        case .off: nil
        case .thirtySeconds: 30
        case .fiveMinutes: 300
        case .oneHour: 3_600
        case .oneDay: 86_400
        }
    }
}

/// A chat's Ghost state (C17).
struct GhostState: Hashable, Sendable {
    var isEnabled: Bool
    var period: GhostPeriod
    /// When the chat is erased unless a message arrives; `nil` when off.
    var deadline: Date?

    static let off = GhostState(isEnabled: false, period: .oneDay, deadline: nil)

    func remaining(at now: Date) -> TimeInterval? {
        guard isEnabled, let deadline else { return nil }
        return max(0, deadline.timeIntervalSince(now))
    }

    /// Red in its last hour.
    func isUrgent(at now: Date) -> Bool {
        remaining(at: now).map(U2Format.ghostIsUrgent) ?? false
    }
}

/// Where media comes from. Fixtures use the design's own painted stand-ins.
enum MediaSource: Hashable, Sendable {
    case designArtwork(DesignArtwork)
    case file(URL)
    /// Decrypted image bytes held in memory only (the live service: an inline
    /// thumbnail, then the full image) — never written to disk to be shown.
    case memory(Data)
}

/// The placeholder images the design paints with CSS gradients.
enum DesignArtwork: String, Hashable, Sendable {
    /// 5.1 photo “Şu yeni yer, Moda'da” — a night pier with lights.
    case nightPier
    /// 5.2 video poster — warm sunset glow.
    case sunsetVideo
}

struct QuotedMessage: Hashable, Sendable {
    let author: String
    enum Kind: Hashable, Sendable {
        case text(String)
        case photo
        case video
        case voice
        case file(String)
    }
    let kind: Kind
    var thumbnail: MediaSource?
    /// The quoted message (the live service sends its id inside the reply).
    var messageID: MessageID? = nil
}

struct Reaction: Hashable, Sendable {
    let emoji: String
    var count: Int = 1
    var includesMe: Bool = false
}

enum ViewOnceState: Hashable, Sendable {
    case unopened
    case opened
    case expired
}

enum SystemEvent: Hashable, Sendable {
    case ghostEnabled(GhostPeriod)
    case ghostDisabled
    case disappearingChanged(DisappearingTimer)
    case screenshotTaken(by: String)
    case safetyNumberChanged
    case safetyNumberVerified
}

enum MessageContent: Hashable, Sendable {
    case text(String)
    case photo(MediaSource, caption: String?)
    case video(MediaSource, duration: TimeInterval, byteCount: Int64)
    /// Bar heights 0…1 (the design's 31-bar waveform) and how much is played.
    case voice(duration: TimeInterval, waveform: [Double], playedFraction: Double)
    case file(name: String, byteCount: Int64, typeLabel: String, pageCount: Int?)
    case location(name: String, locality: String, latitude: Double, longitude: Double)
    case liveLocation(expiresAt: Date, isActive: Bool)
    /// A Doto text sticker (“tamam!”).
    case textSticker(String)
    case viewOnce(isVideo: Bool, state: ViewOnceState)
    /// “Bu mesaj herkesten silindi”.
    case deleted
    case system(SystemEvent)
}

struct Message: Identifiable, Hashable, Sendable {
    let id: MessageID
    let direction: MessageDirection
    let sentAt: Date
    var content: MessageContent
    /// Outgoing only.
    var status: MessageStatus?
    var replyTo: QuotedMessage?
    var reactions: [Reaction] = []
    /// Set for disappearing messages (copy is disabled for them, 5.3).
    var disappearing: DisappearingTimer = .off
    /// Status history for the info sheet (5.4).
    var deliveredAt: Date?
    var readAt: Date?
    var failedAt: Date?

    var isOutgoing: Bool { direction == .outgoing }
    var isDisappearing: Bool { disappearing != .off }

    /// System lines are not bubbles: no actions, no tail, no status.
    var isSystem: Bool {
        if case .system = content { return true }
        return false
    }

    var isDeleted: Bool {
        if case .deleted = content { return true }
        return false
    }

    /// A key that changes when the bubble kind changes (text → tombstone…), so
    /// the row cross-fades between the two instead of morphing.
    var contentKind: String {
        switch content {
        case .text: "text"
        case .photo: "photo"
        case .video: "video"
        case .voice: "voice"
        case .file: "file"
        case .location: "location"
        case .liveLocation(_, let isActive): isActive ? "live" : "liveEnded"
        case .textSticker: "sticker"
        case .viewOnce(_, let state): "viewOnce.\(state)"
        case .deleted: "deleted"
        case .system: "system"
        }
    }

    /// The plain text a message carries (text, photo caption), for copying.
    var copyableText: String? {
        switch content {
        case .text(let text): text
        case .photo(_, let caption): caption
        default: nil
        }
    }
}

// MARK: - Chat list

/// What a chat-list row previews (4.1 line 2).
enum MessagePreviewKind: Hashable, Sendable {
    case text(String)
    case disappearing
    case sticker
    case photo
    case photoViewOnce
    case video
    case voice
    case file(String)
    case location
    case liveLocation
    case liveLocationEnded
    case deleted
}

struct MessagePreview: Hashable, Sendable {
    var kind: MessagePreviewKind
    var direction: MessageDirection
    var status: MessageStatus?
    var sentAt: Date
}

struct ChatSummary: Identifiable, Hashable, Sendable {
    let id: ChatID
    var peer: Peer
    var lastMessage: MessagePreview?
    var unreadCount: Int
    var ghost: GhostState
    var disappearing: DisappearingTimer
}

// MARK: - Requests (4.3, 4.4)

struct MessageRequest: Identifiable, Hashable, Sendable {
    struct Flags: OptionSet, Hashable, Sendable {
        let rawValue: Int
        static let hasMedia = Flags(rawValue: 1 << 0)
        static let hasLink = Flags(rawValue: 1 << 1)
        static let suspicious = Flags(rawValue: 1 << 2)
    }

    let id: RequestID
    let sender: String
    let receivedAt: Date
    /// The first message's text, or a media placeholder.
    let preview: MessagePreviewKind
    let flags: Flags
    /// The request thread, decrypted locally (4.4).
    let messages: [Message]
}

// MARK: - Lookup (4.2)

enum UserLookup: Hashable, Sendable {
    case idle
    case searching
    case found(Peer, isExistingChat: Bool)
    case notFound
}

/// 2.1 availability while typing.
enum UsernameAvailability: Hashable, Sendable {
    case empty
    case checking
    case available
    case taken
    case invalid(UsernameRules.Violation)
}

// MARK: - Verification (06)

enum VerificationMethod: String, Hashable, Sendable, Codable {
    /// “yüz yüze” — QR scanned side by side.
    case faceToFace
    /// “elle işaretlendi”.
    case manual
}

struct SafetyNumberInfo: Hashable, Sendable {
    /// 12 groups of 5 digits (60 digits), identical on both sides.
    let groups: [String]
    /// The scannable fingerprint payload (QR content, see `SafetyQRCodec`).
    let scannablePayload: Data
    var verifiedAt: Date?
    var method: VerificationMethod?
    /// When the peer's number last changed (new device, reinstall, restore)
    /// and the change has not been verified since — 6.1 shows the danger
    /// banner and the seal is gone (flows.md › 4.6).
    var changedAt: Date? = nil

    var isVerified: Bool { verifiedAt != nil }
    var hasUnverifiedChange: Bool { changedAt != nil && !isVerified }
}

/// Result of comparing a scanned QR code with a chat's safety number (6.2).
enum SafetyCodeComparison: Equatable, Sendable {
    /// Both sides see the same safety number → 6.3.
    case match
    /// A us2us safety-number code, but not this chat's → “Numaralar uyuşmuyor”.
    case mismatch
    /// Not a us2us safety-number code at all (a URL, a Wi-Fi code …): ignored.
    case notASafetyCode
}

/// What a QR text read while adding a contact (4.1 / 4.2 → 6.2) stands for.
enum ContactCodeResolution: Equatable, Sendable {
    /// Someone's us2us contact code.
    case contact(username: String)
    /// The user's own code.
    case ownCode
    /// A us2us contact code, but nobody with that name.
    case unknown
    /// Not a us2us contact code (a URL, a safety-number code …): ignored.
    case notAContactCode
}

// MARK: - Settings (07)

enum PresenceVisibility: String, CaseIterable, Codable, Sendable {
    /// “Kabul ettiklerim” (default).
    case acceptedContacts
    /// “Kimse” — and then you don't see anyone else's either.
    case nobody
}

enum ThemePreference: String, CaseIterable, Codable, Sendable, Identifiable {
    case system, dark, light
    var id: String { rawValue }
}

/// Settings kept in the vault (7.1).
struct AppSettings: Hashable, Sendable {
    var presenceVisibility: PresenceVisibility = .acceptedContacts
    var readReceipts = true
    var faceIDEnabled = true
    var defaultGhost: GhostPeriod? = .oneDay
    var defaultDisappearing: DisappearingTimer = .off
    var lastBackupAt: Date?
    // Theme and language are device preferences (AppPreferences), readable
    // before unlocking; they are not part of the vault-backed settings.
}

// MARK: - Attachments (5.1 “+”, sticker key)

/// What “+” (and the sticker key) can send.
enum ConversationAttachment: Hashable, Sendable {
    case photo(MediaSource, caption: String?)
    case file(name: String, byteCount: Int64, typeLabel: String, pageCount: Int?)
    case location(name: String, locality: String, latitude: Double, longitude: Double)
    case liveLocation(duration: TimeInterval)
    case textSticker(String)
    /// A file picked in the document picker (live service; a copy in the app's container).
    case document(URL)
}

// MARK: - Backup (7.2, 7.3)

/// What goes into a backup (7.2 › Yedeğe girecekler). View-once media and
/// disappearing messages are never part of a backup, whatever is chosen.
struct BackupContents: Hashable, Sendable {
    var messages = true
    /// Photos and videos of the included chats — the user's choice; it only
    /// takes effect together with `messages` (see `includesMedia`).
    var media = true
    var settingsAndContacts = true

    /// Whether media is actually written: never without the messages.
    var includesMedia: Bool { messages && media }

    /// Nothing would be written (media alone is never exported).
    var isEmpty: Bool { !messages && !settingsAndContacts }
}

extension UTType {
    /// The `.u2u` backup document (Info.plist › UTExportedTypeDeclarations).
    static let us2usBackup = UTType("com.syconx.us2us.backup") ?? .data
}

/// A backup file the user picked (7.3 file card).
struct BackupFileInfo: Hashable, Sendable {
    /// A copy inside the app's protected container (never the picked original).
    let url: URL
    let fileName: String
    let byteCount: Int64
    let createdAt: Date?
}

/// Why a picked file can't be restored.
enum BackupFileError: Error, Equatable, Sendable {
    /// The file could not be read (permissions, iCloud placeholder, I/O).
    case unreadable
    /// Not a us2us backup (wrong type or a damaged header).
    case notABackup
}

/// The backup password check (7.3).
enum BackupOpenResult: Equatable, Sendable {
    case opened
    case wrongPassword
    case damaged
}

/// Changing the secret code (7.1 › Gizli kodu değiştir) failed.
enum ChangeSecretCodeError: Error, Equatable, Sendable {
    /// The current code was wrong (it counted as a wrong unlock attempt).
    case wrongCurrentCode
    /// Re-keying the vault failed; the old code still works.
    case failed
}

struct BackupEstimate: Hashable, Sendable {
    let fileName: String
    let chatCount: Int
    let messageCount: Int
    let mediaBytes: Int64
    let totalBytes: Int64
}

struct RestoreProgress: Hashable, Sendable {
    enum Stage: Int, Hashable, Sendable, CaseIterable {
        case keys, messages, media
    }

    var fraction: Double
    var stage: Stage
    var messagesDone: Int
    var messagesTotal: Int
    var mediaBytes: Int64
    var estimatedRemaining: TimeInterval
}
