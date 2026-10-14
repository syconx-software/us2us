import Foundation
import LibSignalClient

public enum CommunityError: Error, Equatable, Sendable {
    case invalidState, invalidSignature, identityMismatch, staleState, notMember, unsupported, invalidInvite, partialDelivery
    /// A member's key differs from the signed roster (or silently changed between revisions).
    case memberKeyMismatch(AccountId)
    case invalidName
}

/// The shared community-name rule (docs/COMMUNITIES.md, Names) for public
/// names, names carried in invitations and public search queries: trim the
/// Unicode `White_Space` code points that are not controls, then 1...120
/// UTF-8 bytes with no C0/C1 control and no bidirectional formatting mark.
/// Everything else, ZWJ emoji sequences and variation selectors included,
/// is allowed; nothing is normalized.
public enum CommunityName {
    public static let maxBytes = 120

    /// The canonical (trimmed) name, or `nil` when it breaks the rule.
    public static func normalized(_ raw: String) -> String? {
        let name = trimmed(raw)
        return isValid(name) ? name : nil
    }

    /// Whether `name` is already canonical.
    public static func isValid(_ name: String) -> Bool {
        (1...maxBytes).contains(name.utf8.count)
            && trimmed(name) == name
            && !name.unicodeScalars.contains(where: isForbidden)
    }

    /// A search query under the same rule; empty is allowed (browse).
    public static func searchQuery(_ raw: String) -> String? {
        let query = trimmed(raw)
        return query.isEmpty ? "" : normalized(query)
    }

    /// Removes leading and trailing ``isTrimmed`` code points only (no
    /// platform trim: TAB, LF, U+001C...U+001F, U+0085 and U+FEFF stay).
    static func trimmed(_ text: String) -> String {
        var scalars = Substring(text).unicodeScalars
        while let first = scalars.first, isTrimmed(first) { scalars.removeFirst() }
        while let last = scalars.last, isTrimmed(last) { scalars.removeLast() }
        return String(scalars)
    }

    /// `White_Space` without its controls.
    static func isTrimmed(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x0020, 0x00A0, 0x1680, 0x2000...0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000: true
        default: false
        }
    }

    /// C0, DEL + C1, ALM, LRM/RLM, LRE...RLO and LRI...PDI.
    static func isForbidden(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x0000...0x001F, 0x007F...0x009F, 0x061C, 0x200E, 0x200F, 0x202A...0x202E, 0x2066...0x2069: true
        default: false
        }
    }
}

public struct CommunityMember: Codable, Equatable, Sendable {
    public let accountId: AccountId
    public let identityKey: Data
    public init(accountId: AccountId, identityKey: Data) { self.accountId = accountId; self.identityKey = identityKey }
}

/// Canonical, owner-signed membership. See docs/COMMUNITIES.md.
public struct CommunityState: Equatable, Sendable {
    public let id: AccountId
    public let revision: UInt64
    public let isPublic: Bool
    public let owner: CommunityMember
    public let name: String
    public let members: [CommunityMember]
    public static let maxMembers = 100
    private static let domain = Data("BRP/CommunityState/v1\0".utf8)

    public init(id: AccountId, revision: UInt64, isPublic: Bool, owner: CommunityMember, name: String, members: [CommunityMember]) throws {
        self.id = id; self.revision = revision; self.isPublic = isPublic; self.owner = owner
        self.name = name; self.members = members.sorted { $0.accountId < $1.accountId }
        try validate()
    }

    public init(bytes: Data) throws {
        var reader = CommunityReader(data: bytes)
        guard bytes.count <= 8192, try reader.take(Self.domain.count) == Self.domain else { throw CommunityError.invalidState }
        guard let id = AccountId(bytes: try reader.take(16)) else { throw CommunityError.invalidState }
        self.id = id; revision = try reader.integer(8)
        let visibility = try reader.integer(1)
        guard visibility <= 1 else { throw CommunityError.invalidState }
        isPublic = visibility == 1
        guard let ownerId = AccountId(bytes: try reader.take(16)) else { throw CommunityError.invalidState }
        owner = CommunityMember(accountId: ownerId, identityKey: try reader.take(33))
        let nameLength = Int(try reader.integer(2))
        guard let name = String(data: try reader.take(nameLength), encoding: .utf8) else { throw CommunityError.invalidState }
        self.name = name
        let count = try reader.integer(2)
        guard count >= 1, count <= Self.maxMembers else { throw CommunityError.invalidState }
        var members: [CommunityMember] = []
        for _ in 0..<count {
            guard let account = AccountId(bytes: try reader.take(16)) else { throw CommunityError.invalidState }
            members.append(CommunityMember(accountId: account, identityKey: try reader.take(33)))
        }
        self.members = members
        guard reader.offset == bytes.count else { throw CommunityError.invalidState }
        try validate()
    }

    private func validate() throws {
        guard revision > 0, revision <= 9_007_199_254_740_991, (1...Self.maxMembers).contains(members.count),
              owner.identityKey.count == 33, owner.identityKey.first == 5,
              members.allSatisfy({ $0.identityKey.count == 33 && $0.identityKey.first == 5 }),
              members.contains(owner), zip(members, members.dropFirst()).allSatisfy({ $0.accountId < $1.accountId }),
              isPublic ? CommunityName.isValid(name) : name.isEmpty
        else { throw CommunityError.invalidState }
    }

    public var bytes: Data {
        var result = Self.domain
        result.append(id.bytes); result.communityAppend(revision, width: 8)
        result.append(isPublic ? 1 : 0); result.append(owner.accountId.bytes); result.append(owner.identityKey)
        result.communityAppend(UInt64(name.utf8.count), width: 2); result.append(Data(name.utf8))
        result.communityAppend(UInt64(members.count), width: 2)
        for member in members { result.append(member.accountId.bytes); result.append(member.identityKey) }
        return result
    }

    public func replacingMembers(_ members: [CommunityMember]) throws -> CommunityState {
        try CommunityState(id: id, revision: revision + 1, isPublic: isPublic, owner: owner, name: name, members: members)
    }

    public func member(_ account: AccountId) -> CommunityMember? { members.first { $0.accountId == account } }
}

public struct SignedCommunityState: Codable, Equatable, Sendable {
    public let state: Data
    public let signature: Data
    public init(state: Data, signature: Data) { self.state = state; self.signature = signature }

    /// Rejects rollback, same-revision forks, changed owners and silent key replacement.
    public func verified(ownerKey: Data? = nil, previous: SignedCommunityState? = nil) throws -> CommunityState {
        let parsed = try CommunityState(bytes: state)
        guard signature.count == 64,
              try PublicKey(parsed.owner.identityKey).verifySignature(message: state, signature: signature)
        else { throw CommunityError.invalidSignature }
        if let ownerKey, ownerKey != parsed.owner.identityKey { throw CommunityError.identityMismatch }
        if let previous {
            let old = try CommunityState(bytes: previous.state)
            guard old.id == parsed.id, old.owner == parsed.owner, old.isPublic == parsed.isPublic, old.name == parsed.name else { throw CommunityError.identityMismatch }
            guard parsed.revision >= old.revision, parsed.revision != old.revision || previous.state == state else { throw CommunityError.staleState }
            for member in old.members {
                if let current = parsed.member(member.accountId), current.identityKey != member.identityKey {
                    throw CommunityError.memberKeyMismatch(member.accountId)
                }
            }
        }
        return parsed
    }
}

public struct CommunitySummary: Codable, Equatable, Sendable, Identifiable {
    public let id: AccountId
    public let name: String
    public let visibility: String
    public let ownerAccountId: AccountId
    public let ownerIdentityKey: Data
    public let memberCount: Int
    public let revision: UInt64
}
public struct CommunityDirectory: Codable, Sendable { public let communities: [CommunitySummary]; public let nextCursor: String? }
public struct CommunityMemberships: Codable, Sendable { public let communities: [SignedCommunityState] }
public struct CommunityJoinRequest: Codable, Equatable, Sendable, Identifiable {
    public let accountId: AccountId; public let identityKey: Data; public let createdAt: Int64
    public var id: AccountId { accountId }
    public var member: CommunityMember { CommunityMember(accountId: accountId, identityKey: identityKey) }
}
public struct CommunityRequests: Codable, Sendable { public let requests: [CommunityJoinRequest] }
public struct CommunityInviteToken: Codable, Sendable { public let token: Data; public let expiresAt: Int64 }
public struct CommunityEnvelope: Codable, Sendable {
    public let id: String; public let communityId: AccountId; public let revision: UInt64
    public let serverTimestamp: Int64; public let envelope: Data
    public var relayEnvelope: RelayEnvelope { RelayEnvelope(id: id, serverTimestamp: serverTimestamp, envelope: envelope) }
}
public struct CommunityInbox: Codable, Sendable { public let envelopes: [CommunityEnvelope]; public let more: Bool }

public struct CommunityInvitation: Codable, Sendable {
    /// The canonical name, or empty when the invitation carries none.
    public let communityId: AccountId; public let token: Data; public let ownerIdentityKey: Data; public let name: String
    public init(communityId: AccountId, token: Data, ownerIdentityKey: Data, name: String) {
        self.communityId = communityId; self.token = token; self.ownerIdentityKey = ownerIdentityKey; self.name = name
    }
    private enum CodingKeys: String, CodingKey { case communityId, token, ownerIdentityKey, name }
    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        communityId = try container.decode(AccountId.self, forKey: .communityId)
        token = try container.decode(Data.self, forKey: .token)
        ownerIdentityKey = try container.decode(Data.self, forKey: .ownerIdentityKey)
        // The name may be absent; a present one must be canonical.
        guard let name = try container.decodeIfPresent(String.self, forKey: .name) else { self.name = ""; return }
        guard CommunityName.isValid(name) else { throw CommunityError.invalidInvite }
        self.name = name
    }
    public func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(communityId, forKey: .communityId)
        try container.encode(token, forKey: .token)
        try container.encode(ownerIdentityKey, forKey: .ownerIdentityKey)
        if !name.isEmpty { try container.encode(name, forKey: .name) }
    }
    public var code: String { get throws { "us2us-community:v1:" + (try JSONEncoder.relay.encode(self)).base64URLEncodedString() } }
    public init(code: String) throws {
        let prefix = "us2us-community:v1:"
        guard code.hasPrefix(prefix), code.utf8.count < 2048,
              let data = Data(base64URLEncoded: String(code.dropFirst(prefix.count))) else { throw CommunityError.invalidInvite }
        do { self = try JSONDecoder.relay.decode(Self.self, from: data) } catch { throw CommunityError.invalidInvite }
        guard token.count == 32, ownerIdentityKey.count == 33, ownerIdentityKey.first == 5,
              name.isEmpty || CommunityName.isValid(name) else { throw CommunityError.invalidInvite }
    }
}

private struct CommunityReader {
    let data: Data; var offset = 0
    mutating func take(_ size: Int) throws -> Data {
        guard size >= 0, offset + size <= data.count else { throw CommunityError.invalidState }
        defer { offset += size }; return data.subdata(in: offset..<(offset + size))
    }
    mutating func integer(_ width: Int) throws -> UInt64 { try take(width).reduce(0) { ($0 << 8) | UInt64($1) } }
}
private extension Data {
    mutating func communityAppend(_ value: UInt64, width: Int) {
        for shift in (0..<width).reversed() { append(UInt8(truncatingIfNeeded: value >> (shift * 8))) }
    }
}
