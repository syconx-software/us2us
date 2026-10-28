import Foundation

/// A community's id (its UUID string).
typealias CommunityID = String

/// Where this account stands in a community (the row's actions follow it).
enum CommunityPhase: Hashable, Sendable {
    /// A current member; may leave.
    case member
    /// The owner; may manage and delete.
    case owner
    /// A join request is waiting (or was declined: the relay does not say);
    /// may be withdrawn.
    case pending
    /// Created on this phone, but the relay never confirmed it; may be removed.
    case unfinished
    /// Removed, left elsewhere or deleted by its owner; may be removed here.
    case ended
}

/// A row of “Topluluklar”.
struct CommunityRow: Identifiable, Hashable, Sendable {
    let id: CommunityID
    /// The public name, or the name a private invitation carried; empty when unknown.
    let name: String
    let isPublic: Bool
    /// Members of the last verified roster; `nil` before the first one.
    let memberCount: Int?
    let phase: CommunityPhase

    var isOpenable: Bool { phase == .member || phase == .owner }
}

/// A member, named by the username this phone already knows (a personal
/// contact the directory confirmed), else by a short form of the account id.
struct CommunityMemberRow: Identifiable, Hashable, Sendable {
    let id: String
    let username: String?
    /// ``CommunityIdentifiers/shortID(_:)`` of the account id.
    let shortID: String
    let isOwner: Bool
    let isSelf: Bool
}

/// A pending join request (owner only): who, and the identity key to review.
struct CommunityJoinRequestRow: Identifiable, Hashable, Sendable {
    let id: String
    let username: String?
    let shortID: String
    /// The requester's identity key, ``CommunityIdentifiers/keyFingerprint(_:)``.
    let keyFingerprint: String
    let requestedAt: Date
}

/// A public community found by name.
struct CommunityDirectoryEntry: Identifiable, Hashable, Sendable {
    let id: CommunityID
    let name: String
    let memberCount: Int
    /// Already a member, or a request is pending.
    let isKnown: Bool

    var isFull: Bool { memberCount >= CommunityLimits.maxMembers }
}

/// What went wrong, for the screens to explain in their words.
enum CommunityProblem: Error, Hashable, Sendable {
    case offline
    case unsupported
    case verification
    /// This member's identity key differs from the owner-signed list.
    case memberKey(name: String?, shortID: String)
    case stale
    /// The community already has 100 members.
    case full
    case notFound
    case rateLimited
    case invalidName
    case invalidInvite
    case ownerCannotLeave
    case failed
}

/// A community with owner approvals waiting at the top of the chats list.
struct CommunityRequestInboxRow: Identifiable, Hashable, Sendable {
    let id: CommunityID
    let name: String
    let count: Int
}

/// A community among the chats of 4.1 (``ChatSummary/community``).
struct CommunityChatInfo: Hashable, Sendable {
    let id: CommunityID
    let isPublic: Bool
}

/// A community in the chats list, mixed with the one-to-one chats by its
/// last text or media activity (receipts never move it).
struct CommunityChatRow: Identifiable, Hashable, Sendable {
    let id: CommunityID
    /// The public name, or the name a private invitation carried; empty when unknown.
    let name: String
    let isPublic: Bool
    let lastMessage: MessagePreview?
    /// When it last had text or media; `nil` before the first message.
    let lastActivity: Date?
    let unreadCount: Int

    /// The row as 4.1 draws a chat: the name in the peer's place, the
    /// community marker for the avatar and the route.
    func chatSummary() -> ChatSummary {
        ChatSummary(
            id: Self.listID(id),
            peer: Peer(
                username: name, palette: AvatarPalette.forUsername(id), isVerified: false, isAcceptedContact: false,
                presence: .hidden),
            lastMessage: lastMessage, unreadCount: unreadCount, ghost: .off, disappearing: .off,
            community: CommunityChatInfo(id: id, isPublic: isPublic), lastMessageActivity: lastActivity)
    }

    /// The list's id of a community (never the same as a chat's).
    static func listID(_ id: CommunityID) -> ChatID { "community." + id }
}

/// All conversations ordered by their latest message, newest first.
/// Equal times retain source order; conversations without messages follow active ones.
/// Request cards are rendered separately above these rows.
enum ChatListOrder {
    static func merged(chats: [ChatSummary], communities: [CommunityChatRow]) -> [ChatSummary] {
        let rows = chats + communities.map { $0.chatSummary() }
        return rows.enumerated().sorted { lhs, rhs in
            let left = lhs.element.lastMessageActivity ?? lhs.element.lastMessage?.sentAt
            let right = rhs.element.lastMessageActivity ?? rhs.element.lastMessage?.sentAt
            switch (left, right) {
            case let (l?, r?) where l != r: return l > r
            case (.some, nil): return true
            case (nil, .some): return false
            default: return lhs.offset < rhs.offset
            }
        }.map(\.element)
    }
}

/// One recipient of an own community message (5.4 › Bilgi).
struct CommunityRecipientInfo: Identifiable, Hashable, Sendable {
    enum State: Hashable, Sendable {
        /// Not accepted by the relay yet.
        case pending
        case accepted
        /// Not a member any more when it was sent.
        case skipped
        /// Could not be sent to this member.
        case failed
    }

    let id: String
    let username: String?
    let shortID: String
    let state: State
    let deliveredAt: Date?
    let readAt: Date?
    /// Left or removed since: shown with the last state it reached.
    let hasLeft: Bool
}

enum CommunityLimits {
    static let maxMembers = 100
    /// Public searches reach the relay: only whole words of at least this length.
    static let minimumQueryLength = 2
    static let searchDebounce: Duration = .milliseconds(600)
    static let maxTextBytes = 8192
}

/// The one place community screens turn raw ids and keys into text
/// (the Android app mirrors it): an account by the first
/// eight hex digits of its id (`Üye · 1a2b3c4d`), an identity key as
/// lowercase hex in groups of four (`05ab cdef …`).
enum CommunityIdentifiers {
    static let shortIDLength = 8

    /// `1a2b3c4d`: the first eight hex digits of an account id (its UUID string).
    static func shortID(_ accountID: String) -> String {
        String(accountID.lowercased().filter(\.isHexDigit).prefix(shortIDLength))
    }

    /// `05ab cdef …`: the serialized key, lowercase hex in groups of four.
    static func keyFingerprint(_ key: Data) -> String {
        let digits = Array("0123456789abcdef")
        var groups: [String] = []
        var group = ""
        for byte in key {
            group.append(digits[Int(byte >> 4)])
            group.append(digits[Int(byte & 0x0F)])
            if group.count == 4 {
                groups.append(group)
                group = ""
            }
        }
        if !group.isEmpty { groups.append(group) }
        return groups.joined(separator: " ")
    }
}

/// Communities: text groups of up to 100 members, each message encrypted to
/// every member separately. Optional (fixtures and older relays have none).
@MainActor
protocol CommunitiesService: AnyObject, Observable {
    var rows: [CommunityRow] { get }
    /// `false` when the relay has no community routes.
    var isSupported: Bool { get }
    /// The last background refresh's problem.
    var problem: CommunityProblem? { get }
    var directory: [CommunityDirectoryEntry] { get }
    var hasMoreResults: Bool { get }
    /// The open community's join requests (owner only).
    var openRequests: [CommunityJoinRequestRow] { get }
    var requestInbox: [CommunityRequestInboxRow] { get }
    func requests(in id: CommunityID) -> [CommunityJoinRequestRow]
    /// Communities for the chats list (current and ended memberships).
    var chatRows: [CommunityChatRow] { get }

    func row(_ id: CommunityID) -> CommunityRow?
    func members(of id: CommunityID) -> [CommunityMemberRow]

    /// Polling follows the screens that show communities.
    func screenAppeared()
    func screenDisappeared()
    func refresh() async
    func dismissProblem()

    func search(_ query: String, more: Bool) async throws(CommunityProblem)
    func create(name: String, isPublic: Bool) async throws(CommunityProblem)
    func requestJoin(_ id: CommunityID) async throws(CommunityProblem)
    func redeem(_ code: String) async throws(CommunityProblem)
    func leave(_ id: CommunityID) async throws(CommunityProblem)
    func withdraw(_ id: CommunityID) async throws(CommunityProblem)
    func forget(_ id: CommunityID) async throws(CommunityProblem)

    func loadOlderMessages(in id: CommunityID) async throws -> Bool
    func open(_ id: CommunityID) async throws(CommunityProblem)
    /// Temporarily hidden by a full-screen picker: retain history, pause marking incoming messages as read.
    func cover(_ id: CommunityID)
    func close(_ id: CommunityID)
    /// Returns once the message is queued for every member.
    func send(_ text: String, to id: CommunityID) async throws(CommunityProblem)
    func approve(_ request: CommunityID, in id: CommunityID) async throws(CommunityProblem)
    func reject(_ request: CommunityID, in id: CommunityID) async throws(CommunityProblem)
    /// Owner: rejects every pending join request at once (confirmed first).
    func rejectAll(in id: CommunityID) async throws(CommunityProblem)
    func removeMember(_ member: String, from id: CommunityID) async throws(CommunityProblem)
    func invite(_ id: CommunityID) async throws(CommunityProblem) -> String
    func revokeInvites(_ id: CommunityID) async throws(CommunityProblem)
    func delete(_ id: CommunityID) async throws(CommunityProblem)

    func send(sticker: BrandStickerSelection, to id: CommunityID) async
    func send(voice: VoiceRecording, to id: CommunityID) async -> Bool

    // MARK: Extension v2 (photos, videos, files, receipts, delete for everyone)

    /// The open community's conversation in the one-to-one conversation's
    /// model (oldest first): sender names, media and its availability,
    /// placeholders and, for own messages, the aggregate state.
    func conversation(_ id: CommunityID) -> [Message]
    /// This account owns the community (it may remove anyone's message).
    func isOwner(of id: CommunityID) -> Bool
    /// “+” › a document or a single photo or video.
    func send(attachment: ConversationAttachment, to id: CommunityID) async
    /// “+” › Fotoğraf ve video: every item as a message of its own.
    func send(pickedMedia messages: [PickedMediaMessage], to id: CommunityID) async
    /// The decrypted bytes of a photo, video or file message (downloaded first if needed).
    func mediaPayload(_ message: MessageID, in id: CommunityID) async -> MediaPayload?
    /// Starts the download of a photo, video or file still on the relay.
    func downloadMedia(_ message: MessageID, in id: CommunityID) async
    /// Own messages; for the owner, every message.
    func deleteForEveryone(_ message: MessageID, in id: CommunityID) async throws(CommunityProblem)
    func deleteForMe(_ message: MessageID, in id: CommunityID) async throws(CommunityProblem)
    /// “Tekrar gönder”: a picked photo or video that could not be prepared is
    /// prepared again; a stored message that failed goes again only to the
    /// members that did not get it (an uploaded blob is reused).
    func resend(_ message: MessageID, in id: CommunityID) async throws(CommunityProblem)
    /// Every recipient of an own message with its delivered and read times.
    func recipients(of message: MessageID, in id: CommunityID) async -> [CommunityRecipientInfo]
}

extension CommunitiesService {
    func rejectAll(in id: CommunityID) async throws(CommunityProblem) {
        for request in requests(in: id) { try await reject(request.id, in: id) }
    }
    func loadOlderMessages(in id: CommunityID) async throws -> Bool { false }
    func send(sticker: BrandStickerSelection, to id: CommunityID) async {}
    func send(voice: VoiceRecording, to id: CommunityID) async -> Bool { false }
    var requestInbox: [CommunityRequestInboxRow] { [] }
    func requests(in id: CommunityID) -> [CommunityJoinRequestRow] { openRequests }
    /// Inert fixtures/implementations without read receipts need no visibility bookkeeping.
    func cover(_ id: CommunityID) {}
}
