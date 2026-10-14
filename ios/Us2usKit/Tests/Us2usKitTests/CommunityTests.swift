import Foundation
import LibSignalClient
import SwiftProtobuf
import Testing
@testable import Us2usKit

@Suite("Communities: canonical signed roster and isolated transport")
struct CommunityTests {
    let identity = IdentityKeyPair.generate()
    var owner: CommunityMember { CommunityMember(accountId: AccountId(string: "00000000-0000-0000-0000-000000000001")!, identityKey: identity.identityKey.serialize()) }
    func state(revision: UInt64 = 1, members: [CommunityMember]? = nil) throws -> CommunityState {
        try CommunityState(id: AccountId(string: "11111111-1111-1111-1111-111111111111")!, revision: revision, isPublic: true, owner: owner, name: "Deneme", members: members ?? [owner])
    }
    func sign(_ state: CommunityState) throws -> SignedCommunityState {
        SignedCommunityState(state: state.bytes, signature: identity.privateKey.generateSignature(message: state.bytes))
    }

    @Test func signatureTamperAndPin() throws {
        let original = try sign(state())
        #expect(try original.verified(ownerKey: owner.identityKey) == state())
        var damaged = original.state; damaged[damaged.count - 1] ^= 1
        #expect(throws: CommunityError.self) { try SignedCommunityState(state: damaged, signature: original.signature).verified() }
        #expect(throws: CommunityError.identityMismatch) { try original.verified(ownerKey: IdentityKeyPair.generate().identityKey.serialize()) }
    }
    @Test func rejectsRollbackAndSameRevisionFork() throws {
        let old = try sign(state())
        let other = CommunityMember(accountId: AccountId(uuid: UUID()), identityKey: IdentityKeyPair.generate().identityKey.serialize())
        let latest = try sign(state(revision: 2, members: [owner, other]))
        #expect(try latest.verified(previous: old).members.count == 2)
        #expect(throws: CommunityError.staleState) { try old.verified(previous: latest) }
        let fork = try sign(state(revision: 2))
        #expect(throws: CommunityError.staleState) { try fork.verified(previous: latest) }
    }
    @Test func hundredMembersCanonicalAndBounded() throws {
        let members = [owner] + (0..<99).map { _ in CommunityMember(accountId: AccountId(uuid: UUID()), identityKey: IdentityKeyPair.generate().identityKey.serialize()) }
        let full = try state(members: members.reversed())
        #expect(full.bytes.count < 8192)
        #expect(try CommunityState(bytes: full.bytes) == full)
        #expect(full.members.count - 1 == 99)
        #expect(throws: CommunityError.invalidState) { try state(members: members + [CommunityMember(accountId: AccountId(uuid: UUID()), identityKey: owner.identityKey)]) }
        #expect(throws: CommunityError.invalidState) { try state(members: [owner, owner]) }
        #expect(throws: CommunityError.invalidState) { try CommunityState(bytes: full.bytes + Data([0])) }
    }
    @Test func invitationRoundTripAndShape() throws {
        let value = CommunityInvitation(communityId: owner.accountId, token: Data(repeating: 8, count: 32), ownerIdentityKey: owner.identityKey, name: "Özel")
        let decoded = try CommunityInvitation(code: value.code)
        #expect(decoded.name == "Özel")
        #expect(decoded.ownerIdentityKey == value.ownerIdentityKey)
        #expect(throws: CommunityError.invalidInvite) { try CommunityInvitation(code: "https://example.com/invite") }
    }
    @Test func communityTransportNeverCarriesPersonalAccessKey() async throws {
        let server = RelayClientTests.Server { _ in RelayClientTests.Server.json(202, #"{"id":"AAAAAAAAAAAAAAAAAAAAAA","serverTimestamp":1760000000000}"#) }
        let id = owner.accountId
        _ = try await server.client.sendCommunityEnvelope(Data([1,2,3]), community: id, recipient: id, messageId: id, revision: 7)
        #expect(server.last.path == "/v1/communities/\(id.string)/messages/\(id.string)/\(id.string)")
        #expect(server.last.header("X-Auth-Token") != nil)
        #expect(server.last.header("X-Access-Key") == nil)
        #expect(server.last.header("X-Community-Revision") == "7")
    }
    @Test("100-member group uses 99 independent libsignal ciphertexts")
    func hundredMemberPairwiseDelivery() throws {
        let sender = TestParty()
        var community = BRPCommunityMessage()
        community.communityID = RandomId.make(); community.revision = 1
        community.messageID = RandomId.make(); community.sentAt = 123; community.text = "100 members"
        var content = BRPContent(); content.body = .community(community)
        var ciphertexts = Set<Data>()
        for _ in 0..<99 {
            let recipient = TestParty()
            try sender.cipher.processBundle(recipient.bundle())
            let envelope = try sender.cipher.seal(content, for: recipient.account)
            ciphertexts.insert(envelope)
            let sealed = try SealedEnvelope.open(envelope, with: recipient.identity)
            let decrypted = try recipient.cipher.decrypt(sealed)
            #expect(decrypted.content.community == community)
            #expect(decrypted.sender == sender.account)
        }
        #expect(ciphertexts.count == 99)
    }
    @Test("Swift verifies the Kotlin libsignal signature and identical protobuf bytes")
    func sharedKotlinVector() throws {
        struct Vector: Decodable {
            let communityId: AccountId; let ownerAccountId: AccountId; let ownerIdentityKey: Data
            let state: Data; let stateHex: String; let signature: Data; let contentHex: String; let text: String
        }
        let vector = try JSONDecoder.relay.decode(Vector.self, from: Data(contentsOf: RepositoryPaths.testVectors.appendingPathComponent("communities.json")))
        let signed = SignedCommunityState(state: vector.state, signature: vector.signature)
        let state = try signed.verified(ownerKey: vector.ownerIdentityKey)
        #expect(state.id == vector.communityId)
        #expect(state.owner.accountId == vector.ownerAccountId)
        #expect(state.bytes.hexString == vector.stateHex)
        let content = try BRPContent(serializedBytes: Data(hex: vector.contentHex))
        try content.validateStructure()
        #expect(content.community.communityID == state.id.bytes)
        #expect(content.community.revision == state.revision)
        #expect(content.community.text == vector.text)
        let encoded: Data = try content.serializedBytes()
        #expect(encoded.hexString == vector.contentHex)
    }
    // MARK: Shared name rule

    @Test("names: trimmed, 1...120 bytes, no controls or bidi marks; ZWJ and variation selectors allowed")
    func nameRule() throws {
        let valid = ["Aile 👨‍👩‍👧", "Kahve ☕️", "Deneme", "Ö", String(repeating: "a", count: 120), String(repeating: "ş", count: 60)]
        for name in valid { #expect(CommunityName.isValid(name), "\(name)") }
        let invalid = [
            "", " ", "  Deneme", "Deneme\n", String(repeating: "a", count: 121), String(repeating: "ş", count: 61),
            "a\u{0}b", "a\u{7}b", "a\u{1F}b", "a\u{7F}b", "a\u{85}b", "a\u{9F}b", "a\u{61C}b", "a\u{200E}b",
            "a\u{200F}b", "a\u{202A}b", "a\u{202E}b", "a\u{2066}b", "a\u{2069}b",
        ]
        for name in invalid { #expect(!CommunityName.isValid(name), "\(name.unicodeScalars.map { String($0.value, radix: 16) })") }
        #expect(CommunityName.normalized(" \u{3000}Aile 👨‍👩‍👧\u{A0} ") == "Aile 👨‍👩‍👧")
        // Only White_Space without controls is trimmed: TAB, LF, U+0085 and U+FEFF stay.
        #expect(CommunityName.normalized("\tAile") == nil)
        #expect(CommunityName.normalized("Aile\n") == nil)
        #expect(CommunityName.normalized("Aile\u{85}") == nil)
        #expect(CommunityName.normalized("\u{FEFF}Aile") == "\u{FEFF}Aile")
        #expect(CommunityName.searchQuery("  ") == "")
        #expect(CommunityName.searchQuery(" Kitap ") == "Kitap")
        #expect(CommunityName.searchQuery("a\u{202E}") == nil)
        #expect(CommunityName.normalized(" \u{202E}evil ") == nil)
        #expect(CommunityName.normalized("   ") == nil)
        // Cf characters other than the bidi controls (ZWJ, ZWNJ, word joiner) are allowed.
        #expect(CommunityName.isValid("a\u{200D}b") && CommunityName.isValid("a\u{200C}b") && CommunityName.isValid("a\u{2060}b"))
    }

    @Test func publicStateAndInvitationFollowTheNameRule() throws {
        let id = AccountId(string: "11111111-1111-1111-1111-111111111111")!
        let family = try CommunityState(id: id, revision: 1, isPublic: true, owner: owner, name: "Aile 👨‍👩‍👧", members: [owner])
        #expect(try CommunityState(bytes: family.bytes).name == "Aile 👨‍👩‍👧")
        #expect(throws: CommunityError.invalidState) {
            try CommunityState(id: id, revision: 1, isPublic: true, owner: owner, name: "evil\u{202E}gpj", members: [owner])
        }
        #expect(throws: CommunityError.invalidState) {
            try CommunityState(id: id, revision: 1, isPublic: false, owner: owner, name: "Gizli", members: [owner])
        }
        let invite = CommunityInvitation(communityId: id, token: Data(repeating: 1, count: 32), ownerIdentityKey: owner.identityKey, name: "Aile 👨‍👩‍👧")
        #expect(try CommunityInvitation(code: invite.code).name == "Aile 👨‍👩‍👧")
        // A private invitation may carry no name at all.
        let unnamed = CommunityInvitation(communityId: id, token: Data(repeating: 1, count: 32), ownerIdentityKey: owner.identityKey, name: "")
        #expect(!(try unnamed.code).isEmpty)
        #expect(try CommunityInvitation(code: unnamed.code).name == "")
        for name in [" Aile", "a\u{200F}b", "Aile\t", String(repeating: "a", count: 121)] {
            let bad = CommunityInvitation(communityId: id, token: Data(repeating: 1, count: 32), ownerIdentityKey: owner.identityKey, name: name)
            #expect(throws: CommunityError.invalidInvite) { try CommunityInvitation(code: bad.code) }
        }
    }

    @Test func silentMemberKeyChangeNamesTheMember() throws {
        let member = CommunityMember(accountId: AccountId(uuid: UUID()), identityKey: IdentityKeyPair.generate().identityKey.serialize())
        let old = try sign(state(members: [owner, member]))
        let swapped = CommunityMember(accountId: member.accountId, identityKey: IdentityKeyPair.generate().identityKey.serialize())
        let next = try sign(state(revision: 2, members: [owner, swapped]))
        #expect(throws: CommunityError.memberKeyMismatch(member.accountId)) { try next.verified(previous: old) }
    }

    @Test func leaveAndWithdrawUseTheirOwnRoutes() async throws {
        let server = RelayClientTests.Server { _ in (204, [:], Data()) }
        let id = owner.accountId
        try await server.client.leaveCommunity(id)
        #expect(server.last.method == "DELETE")
        #expect(server.last.path == "/v1/communities/\(id.string)/members/me")
        try await server.client.withdrawCommunityRequest(id)
        #expect(server.last.method == "DELETE")
        #expect(server.last.path == "/v1/communities/\(id.string)/requests/me")
        #expect(server.last.header("X-Access-Key") == nil)
        let owner = RelayClientTests.Server { _ in RelayClientTests.Server.json(409, #"{"error":{"code":"stale_version","message":"owner"}}"#) }
        await #expect(throws: RelayError.self) { try await owner.client.leaveCommunity(id) }
        // A full community inbox is one recipient's problem, not a global rate limit.
        let full = RelayClientTests.Server { _ in
            RelayClientTests.Server.json(429, #"{"error":{"code":"community_inbox_full","message":"full"}}"#, headers: ["Retry-After": "600"])
        }
        await #expect(throws: RelayError.communityInboxFull(retryAfter: 600)) {
            _ = try await full.client.sendCommunityEnvelope(Data([1]), community: id, recipient: id, messageId: id, revision: 1)
        }
        let limited = RelayClientTests.Server { _ in RelayClientTests.Server.json(429, #"{"error":{"code":"rate_limited","message":"slow"}}"#) }
        await #expect(throws: RelayError.rateLimited(retryAfter: nil)) {
            _ = try await limited.client.sendCommunityEnvelope(Data([1]), community: id, recipient: id, messageId: id, revision: 1)
        }
    }

    // MARK: Shared vectors (spec/test-vectors), verified once the relay suite publishes them

    static let namesVector = RepositoryPaths.testVectors.appendingPathComponent("communities-names.json")
    static let invalidStatesVector = RepositoryPaths.testVectors.appendingPathComponent("communities-invalid.json")

    @Test("shared name vectors", .enabled(if: FileManager.default.fileExists(atPath: CommunityTests.namesVector.path)))
    func sharedNameVectors() throws {
        struct Case: Decodable { let name: String; let valid: Bool; let canonical: String? }
        let cases = try CommunityVectors.cases([Case].self, from: Self.namesVector, keys: ["vectors", "names", "cases"])
        #expect(cases.count >= 32)
        for item in cases {
            let label = item.name.unicodeScalars.map { String($0.value, radix: 16) }
            #expect((CommunityName.normalized(item.name) != nil) == item.valid, "\(label)")
            #expect(CommunityName.normalized(item.name) == item.canonical, "\(label)")
        }
    }

    @Test("shared signed-state decoding vectors", .enabled(if: FileManager.default.fileExists(atPath: CommunityTests.invalidStatesVector.path)))
    func sharedStateVectors() throws {
        struct Case: Decodable { let reason: String; let stateHex: String; let valid: Bool }
        let cases = try CommunityVectors.cases([Case].self, from: Self.invalidStatesVector, keys: ["vectors", "cases", "invalid"])
        #expect(cases.count >= 20)
        for item in cases {
            let decoded = try? CommunityState(bytes: Data(hex: item.stateHex))
            #expect((decoded != nil) == item.valid, "\(item.reason)")
            if let decoded { #expect(decoded.bytes.hexString == item.stateHex, "\(item.reason)") }
        }
    }
}

/// Reads the shared community vector files: a bare list or `{ <key>: [...] }`,
/// binary values as lowercase hex.
enum CommunityVectors {
    struct Missing: Error {}

    static func cases<T: Decodable>(_ type: T.Type, from url: URL, keys: [String]) throws -> T {
        let data = try Data(contentsOf: url)
        if let list = try? JSONDecoder().decode(T.self, from: data) { return list }
        let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
        for key in keys {
            if let value = object[key] {
                return try JSONDecoder().decode(T.self, from: JSONSerialization.data(withJSONObject: value))
            }
        }
        throw Missing()
    }
}
