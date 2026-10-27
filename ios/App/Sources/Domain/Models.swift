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
    /// The peer's safety number changed and the change waits for the user
    /// (compare, or continue without verifying): sending is held (5.1 banner).
    var safetyNumberChanged = false
    /// Fingerprint of the peer's current identity key — `nil` while none is
    /// known (no safety number to compare yet). Changes with the key, so 6.1
    /// reloads when it does.
    var identityFingerprint: String? = nil
    /// The profile picture to draw (PROTOCOL §8.9): an accepted contact's
    /// last `ProfileUpdate`, the search copy for the user's own message
    /// request, otherwise (incoming requests, blocked) the initial.
    var avatar: AvatarPicture = .initials
    /// The user blocked this person (PROTOCOL §6.4): what they send is
    /// dropped on this phone, nothing can be sent to them, and the chat
    /// stays, read only, until “Engeli kaldır”. They are not told.
    var isBlocked = false

    var isOnline: Bool { isAcceptedContact && presence == .online }

    /// 5.1 first-contact prompt: a safety number exists but was never
    /// verified, and no change waits for a decision (the banner covers that).
    var offersVerification: Bool { !isVerified && !safetyNumberChanged && identityFingerprint != nil }
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
    /// “Hat kapandı · oturum sona erdi” — the server refused this
    /// phone's token (the backup was restored on another phone, or the
    /// account was deleted); nothing is retried, so the line never says so.
    case revoked
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

/// Where the file of a photo, video or file message is (5.2 › States): what
/// its bubble shows and what a tap does.
enum MediaAvailability: Hashable, Sendable {
    /// On this device (received and decrypted, or sent from it): opens at once.
    case ready
    /// Still on the relay: a tap downloads it.
    case remote
    /// On its way; the fraction (0…1) when it is known.
    case downloading(Double?)
    /// From a message request: downloaded once the request is accepted (§6.4).
    case held
    /// Expired on the relay (14 days) or failed its integrity check.
    case unavailable
    /// A photo or video picked in “+”, still being prepared on this phone
    /// (re-encoded at ≤ 2048 px / ≤ 720p without metadata, one item at a
    /// time) before it is sent: “Hazırlanıyor”.
    case preparing
    /// Preparing that photo or video failed: nothing left the phone;
    /// “Tekrar gönder” prepares it again.
    case preparationFailed

    var isReady: Bool { self == .ready }

    /// The photo or video exists only on this phone so far (preparing, or failed to).
    var isPending: Bool { self == .preparing || self == .preparationFailed }

    var isDownloading: Bool {
        if case .downloading = self { return true }
        return false
    }
}

/// The pixel size an attachment was sent with (0 when the sender did not say).
struct MediaDimensions: Hashable, Sendable {
    let width: Int
    let height: Int

    var aspectRatio: CGFloat? {
        guard width > 0, height > 0 else { return nil }
        return CGFloat(width) / CGFloat(height)
    }
}

/// The decrypted bytes of a photo, video or file message, held in memory
/// only (players, viewers and previews take them from here).
struct MediaPayload: Sendable {
    let data: Data
    /// MIME type as sent (`image/jpeg`, `video/mp4`, `application/pdf` …).
    let contentType: String
    /// The name as sent (files), sender-controlled: sanitise before use.
    let fileName: String?
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
    /// A changed safety number was accepted without verifying it (no seal).
    case safetyNumberAccepted
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
    /// A Doto text sticker (“tamam!”): the plain text stickers of builds
    /// before the built-in catalog (`pack_id` “us2us.text”), kept for history.
    case textSticker(String)
    /// A built-in sticker (PROTOCOL §8.8) exactly as it travelled: its pack
    /// and id, its `emoji` and the language its words were shown in on the
    /// sender's side (“tr”, “en”; empty for a wordless sticker or an older
    /// sender). The bubble resolves it against `BrandStickerCatalog` — an
    /// unknown sticker falls back to its emoji.
    case sticker(packID: String, stickerID: UInt32, emoji: String, lang: String)
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
    /// Photo, video and file messages: where the file is (5.2 › States).
    var mediaAvailability: MediaAvailability = .ready
    /// Photo and video messages: the size they were sent with, if known.
    var mediaSize: MediaDimensions? = nil
    /// Voice messages: incoming — played on this phone; outgoing — the other
    /// side played it (their VIEWED receipt, shown while read receipts are on).
    var isVoicePlayed = false
    /// Photo, video and file messages I sent: the other side saved it to
    /// their phone (their SAVED receipt, PROTOCOL §8.4) — the “Kaydetti”
    /// chip. Always told, independent of read receipts.
    var savedByPeer = false
    /// When their SAVED arrived (5.4 › “Kaydedildi”), if known.
    var savedByPeerAt: Date? = nil
    /// Photo, video and file messages: I saved it or handed it on from one of
    /// the app's viewers (the viewer's “Kaydet” shows the check).
    var savedByMe = false
    /// A message of a community conversation (Extension v2): who sent it
    /// and how it was deleted; `nil` in a one-to-one chat.
    var community: CommunityMessageMarks? = nil

    var isOutgoing: Bool { direction == .outgoing }
    var isDisappearing: Bool { disappearing != .off }
    /// A picked photo or video still being prepared, or whose preparation
    /// failed: it is not a message yet (no reactions, replies or info — it
    /// can only be sent again or removed).
    var isPendingMedia: Bool { mediaAvailability.isPending }

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
        case .textSticker, .sticker: "sticker"
        case .viewOnce(_, let state): "viewOnce.\(state)"
        case .deleted: "deleted"
        case .system: "system"
        }
    }

    /// The plain text a message carries (text, a photo's caption, a
    /// community video's or file's caption), for copying.
    var copyableText: String? {
        switch content {
        case .text(let text): text
        case .photo(_, let caption): caption
        case .video, .file: community?.caption
        default: nil
        }
    }
}

/// Who wrote a community message: the username this phone knows (a
/// directory-verified contact), else the short account id — shown as
/// “Üye · 1a2b3c4d” (``CommunityNames/member(username:shortID:locale:)``).
struct CommunitySenderName: Hashable, Sendable {
    let username: String?
    let shortID: String
}

/// What a community message adds to a ``Message``.
struct CommunityMessageMarks: Hashable, Sendable {
    /// The sender's account id: a run of bubbles ends where it changes.
    let senderID: String
    /// Incoming: the name above the bubble; `nil` for own messages.
    var sender: CommunitySenderName?
    /// A placeholder removed by the community's owner (not by its sender).
    var removedByOwner = false
    /// A video's or file's caption, shown as a text row under it (a photo
    /// carries its own).
    var caption: String? = nil
}

/// The contact screen's media tabs (6.1 › Fotoğraflar / Belgeler).
enum ChatMediaKind: Hashable, Sendable, CaseIterable {
    /// Photos and videos.
    case visual
    /// Files.
    case documents
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
    /// A community message deleted by its sender, or removed by the owner.
    case communityDeleted(byOwner: Bool)
}

struct MessagePreview: Hashable, Sendable {
    var kind: MessagePreviewKind
    var direction: MessageDirection
    var status: MessageStatus?
    var sentAt: Date
    /// Communities: who wrote an incoming message (“deniz: …”).
    var sender: CommunitySenderName? = nil
}

struct ChatSummary: Identifiable, Hashable, Sendable {
    let id: ChatID
    var peer: Peer
    var lastMessage: MessagePreview?
    var unreadCount: Int
    var ghost: GhostState
    var disappearing: DisappearingTimer
    /// A community shown among the chats (4.1); `nil` for a one-to-one chat.
    var community: CommunityChatInfo? = nil
    /// Local send/receive time of the latest message, independent of the peer's clock.
    /// Fixtures/older adapters fall back to the preview time. No messages: nil.
    var lastMessageActivity: Date? = nil
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
    /// What 4.3 and 4.4 draw: always the initial. A stranger's picture is
    /// neither fetched nor decoded before the user asks for it on the opened
    /// request (“Profil resmini göster”) or accepts (PROTOCOL §8.9).
    var avatar: AvatarPicture = .initials
    /// 4.4 may offer “Profil resmini göster”: the sender's username is verified,
    /// so its search copy can be looked up under it — once, on a tap.
    var offersAvatar = false
}

// MARK: - Reports (App Review 1.2)

/// Who a report (“Bildir”) is about, and nothing else: the username as this
/// phone knows it. No message, key or account id ever goes into a report.
struct ReportSubject: Hashable, Sendable {
    /// The username; `nil` when this phone has none for the person (only a
    /// display name or the account id, which a report never carries).
    let username: String?
    /// The directory confirmed the username (PROTOCOL §3.3); an unconfirmed
    /// one is only what the sender claimed, and the report says so.
    let isUsernameVerified: Bool
}

// MARK: - Blocked people (7.1 › Engellenenler)

/// Someone the user blocked, as 7.1 › Engellenenler lists them — also when
/// their chat is gone (Ghost erased it, or it was deleted): the block stays.
struct BlockedPerson: Identifiable, Hashable, Sendable {
    /// The person's chat id (the account in the live service), also when
    /// there is no chat: what `unblock(_:)` takes.
    let id: ChatID
    /// What the list shows: the username, else the name 4.1 would show.
    let name: String
    /// `false` when this phone keeps no username for the person (a Ghost
    /// wipe with `wipe_contact`, PROTOCOL §8.2, kept only the block).
    var isUsernameKnown = true
    var palette: AvatarPalette
    /// Always the initial: no picture is shown for someone blocked (§8.9).
    var avatar: AvatarPicture = .initials

    /// The list's order: by username, people without one last.
    static func listed(_ people: [BlockedPerson]) -> [BlockedPerson] {
        people.sorted { lhs, rhs in
            if lhs.isUsernameKnown != rhs.isUsernameKnown { return lhs.isUsernameKnown }
            return lhs.name.localizedStandardCompare(rhs.name) == .orderedAscending
        }
    }
}

// MARK: - Lookup (4.2)

enum UserLookup: Hashable, Sendable {
    case idle
    case searching
    /// `existingChat`: the chat with the account the directory returned —
    /// never one that only shows the same name (an account deleted since,
    /// whose name someone else registered, PROTOCOL §3.3).
    case found(Peer, existingChat: ChatID?)
    case notFound
}

/// 2.1 availability while typing.
enum UsernameAvailability: Hashable, Sendable {
    case empty
    case checking
    /// Valid, and known to be free.
    case available
    /// Valid; whether it is free is known only when setup registers it
    /// (the directory answers only accounts, PROTOCOL §4.4).
    case meetsRules
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
    /// Verified, but when and how is not known (restored from a backup, or
    /// verified by an earlier version): no date is shown, never a made-up one.
    var isVerifiedWithoutRecord = false

    var isVerified: Bool { verifiedAt != nil || isVerifiedWithoutRecord }
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
    /// The name could not be looked up (no connection, the server busy):
    /// not “nobody found”; scanning again later can work.
    case lookupFailed
    /// The code's key fingerprint is not the key the server serves for that
    /// name, or this phone holds another key for them (“Numaralar
    /// uyuşmuyor”) — what a server swapping keys looks like (PROTOCOL §5):
    /// nothing is added or verified.
    case keyMismatch
    /// Not a us2us contact code (a URL, a safety-number code …): ignored.
    case notAContactCode
}

/// What “QR kodla ekle” did with a scanned contact code.
enum ContactCodeAddition: Equatable, Sendable {
    /// Added (or found) and verified face to face: the code's key
    /// fingerprint matched and that key is pinned → 6.3.
    case verified(ChatID)
    /// Added (or found) but not verified: the code carried no key
    /// fingerprint (older app versions) → the chat opens unverified.
    case added(ChatID)
    /// The code's key is not the one the relay serves, or this phone holds
    /// another key for the contact: nothing verified.
    case mismatch
    /// Nobody could be added (no network, not found).
    case failed
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

// MARK: - Attachments (5.1 “+”)

/// What “+” can send. Stickers go through `MessengerService.send(sticker:replyTo:in:)`.
enum ConversationAttachment: Hashable, Sendable {
    case photo(MediaSource, caption: String?)
    case file(name: String, byteCount: Int64, typeLabel: String, pageCount: Int?)
    case location(name: String, locality: String, latitude: Double, longitude: Double)
    case liveLocation(duration: TimeInterval)
    /// A file picked in the document picker (live service; a copy in the app's container).
    case document(URL)
    /// A video picked in the photo picker (live service; a copy in the app's
    /// container, re-exported at ≤ 720p without metadata before it is sent).
    /// A pick of several photos and videos goes through
    /// `MessengerService.send(pickedMedia:in:)` instead.
    case video(URL)
}

// MARK: - Backup (7.2, 7.3)

/// What a backup should hold. The account backup (PROTOCOL.md §5.5) is
/// always the account, its identity key and the contacts — never messages
/// or media — so 7.2 offers no choice and asks for
/// `BackupExportModel.contents` (settings and contacts only). The message
/// switches are kept for a later format that carries history.
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
    /// It opened with its password, but its contents are damaged or
    /// malformed: no restore accepts it.
    case damaged
    /// The server refuses its token: its account was deleted, or it was
    /// restored on another phone since.
    case noLongerValid
}

/// The backup password check (7.3).
enum BackupOpenResult: Equatable, Sendable {
    /// The password opened it, every field is valid and the relay still
    /// accepts its token: restoring can go ahead.
    case opened
    case wrongPassword
    /// The file decrypts but is damaged or malformed: no restore accepts it.
    case damaged
    /// The relay refuses the backup's token: its account was deleted, or it
    /// was restored on another phone since.
    case noLongerValid
    /// The relay could not be asked (no connection, relay busy): nothing was
    /// changed, try again.
    case unverified
}

/// Finishing a restore (2.3) failed in a way trying again cannot fix.
enum RestoreFailure: Error, Equatable, Sendable {
    /// The relay refuses the backup's token: its account was deleted, or the
    /// backup was restored on another phone since (the token was rotated
    /// there).
    case backupNoLongerValid
    /// The file can't be read, or it decrypts but is damaged or malformed.
    case backupDamaged
}

/// Creating the account (2.3) was refused before anything was kept.
enum AccountCreationFailure: Error, Equatable, Sendable {
    /// Someone registered the name first (the relay's 409 `username_taken`):
    /// another name can be chosen and setup finished with the same code.
    case usernameTaken
}

/// 2.3 could not finish, yet a vault is on this phone, locked: onboarding
/// cannot go on (trying again would find that vault and fail), the lock
/// opens it.
enum SetupInterruption: Error, Equatable, Sendable {
    /// The new account — created or restored here — was saved before the
    /// failure: it is kept under the new code, and its first unlock
    /// finishes what is missing (PROTOCOL §5.5).
    case accountKept
    /// Restoring inside the lock (3.3 › 7.3) was refused before anything
    /// was erased (the backup is no longer valid, or damaged): the vault
    /// this phone held is untouched.
    case previousVaultKept
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
    /// The backup carries message history and media (the design's planned
    /// format). An account backup never does (PROTOCOL §5.5): then 7.3 shows
    /// only the keys step and says that messages don't come back.
    var includesHistory = true

    /// The steps 7.3 lists.
    var stages: [Stage] { includesHistory ? Stage.allCases : [.keys] }
}
