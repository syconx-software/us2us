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

enum CommunityMessageStatus: Hashable, Sendable {
    case received
    case sending
    /// Accepted for every member.
    case sent
    /// Some members could not be reached; the others got it.
    case partial
    /// Stopped for everyone (the member list changed); some may have it.
    case failed
}

struct CommunityMessage: Identifiable, Hashable, Sendable {
    let id: String
    let text: String
    let senderID: String
    let senderName: String?
    let senderShortID: String
    let isOutgoing: Bool
    let sentAt: Date
    let status: CommunityMessageStatus
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
    /// The open community's latest messages, oldest first.
    var openMessages: [CommunityMessage] { get }
    /// The open community's join requests (owner only).
    var openRequests: [CommunityJoinRequestRow] { get }

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

    func open(_ id: CommunityID) async throws(CommunityProblem)
    func close(_ id: CommunityID)
    /// Returns once the message is queued for every member.
    func send(_ text: String, to id: CommunityID) async throws(CommunityProblem)
    func approve(_ request: CommunityID, in id: CommunityID) async throws(CommunityProblem)
    func reject(_ request: CommunityID, in id: CommunityID) async throws(CommunityProblem)
    func removeMember(_ member: String, from id: CommunityID) async throws(CommunityProblem)
    func invite(_ id: CommunityID) async throws(CommunityProblem) -> String
    func revokeInvites(_ id: CommunityID) async throws(CommunityProblem)
    func delete(_ id: CommunityID) async throws(CommunityProblem)
}
