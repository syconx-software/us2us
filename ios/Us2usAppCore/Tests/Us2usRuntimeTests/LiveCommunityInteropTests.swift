import Foundation
import LibSignalClient
import Testing
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime

/// The communities extension (docs/COMMUNITIES.md) between this runtime and
/// the Android runtime over a real relay. The other side is the Kotlin
/// `LiveCommunityInteropTest` (android/runtime): both play the script below
/// and meet in `COMMUNITY_DIR`, where each writes one small JSON file per
/// step (`ios-<step>.json`, `kotlin-<step>.json`) and waits for the other's.
/// Skipped unless `RELAY_URL` and `COMMUNITY_DIR` are set (`TEST_RUNNER_…`
/// with xcodebuild).
///
/// This phone is "S"; Kotlin plays the owner "K1" and a third account "K3".
///
/// 1. K1 creates the public community "Aile 👨‍👩‍👧"; S finds it by search
///    and asks to join; K1 approves (revision 2); S checks the signed roster;
///    S and K1 write to each other.
/// 2. S creates a private community and two invitations; K3 and K1 redeem
///    them, S approves both (revision 3); K3 also joins the public one.
///    Messages go both ways; nobody became anybody's personal contact.
/// 3. K1 and S start a one-to-one conversation (request, acceptance, reply).
/// 4. S removes K3 from its private community: K3 can no longer send or
///    fetch there (its queued copy is gone), S and K1 go on writing.
/// 5. K3 leaves the public community (`DELETE …/members/me`, 204); S's next
///    message still reaches K1; K1 publishes revision 4 without K3; S and K1
///    go on, the one-to-one conversation too.
@Suite(
    "Live communities with another platform",
    .serialized,
    .enabled(
        if: LiveRelay.url != nil && CommunityMeeting.directory != nil,
        "set RELAY_URL and COMMUNITY_DIR (TEST_RUNNER_… with xcodebuild)"),
    .timeLimit(.minutes(15)))
struct LiveCommunityInteropTests {
    typealias Party = LiveRuntimeTests.Party

    static let publicName = "Aile 👨‍👩‍👧"
    static let privateName = "Gizli Çay ☕"

    @Test func communitiesWithTheAndroidRuntime() async throws {
        let meeting = CommunityMeeting(directory: try #require(CommunityMeeting.directory))
        let me = try await Party(username: LiveRuntimeTests.username("ios_c"), relay: try #require(LiveRelay.url))
        do {
            try await Self.script(Side(party: me, meeting: meeting))
        } catch {
            try? meeting.post("ios-failed", ["error": "\(error)"])
            await me.shutdown()
            throw error
        }
        await me.shutdown()
    }

    static func script(_ s: Side) async throws {
        try await s.party.connect()
        let own = s.backend.accountId
        let ownKey = try s.backend.protocolStore.identityKeyPair(context: NullContext()).identityKey.serialize()
        let me = CommunityMember(accountId: own, identityKey: ownKey)
        try s.meeting.post(
            "ios-ready",
            ["accountId": own.string, "username": s.party.username, "identityKey": ownKey.base64URLEncodedString()])

        // 1. The Kotlin owner's public community: search, request, approval.
        let created = try await s.meeting.wait("kotlin-public")
        let publicId = try #require(created["communityId"].flatMap(AccountId.init(string:)))
        let owner = try #require(created.member("owner"))
        let third = try #require(created.member("third"))
        #expect(created["name"] == publicName)
        let summary = try await s.find(publicId, query: "Aile")
        #expect(summary.name == publicName)
        #expect(summary.visibility == "public")
        #expect(summary.ownerAccountId == owner.accountId && summary.ownerIdentityKey == owner.identityKey)
        #expect(summary.memberCount == 1 && summary.revision == 1)
        // Prefix search ignores case.
        #expect(try await s.find(publicId, query: "aile").id == publicId)
        try await s.communities.requestJoin(summary)
        try s.meeting.post("ios-requested")

        _ = try await s.meeting.wait("kotlin-approved")
        let approved = try await s.state(publicId, revision: 2)
        #expect(approved.isPublic && approved.name == publicName)
        #expect(approved.owner == owner)
        #expect(Set(approved.members.map(\.accountId)) == [owner.accountId, own])
        #expect(approved.member(own) == me && approved.member(owner.accountId) == owner)
        // The relay's copy verifies against the owner key pinned from the directory.
        let fetched = try await s.backend.messenger.relay.community(publicId).verified(ownerKey: owner.identityKey)
        #expect(fetched == approved)

        let hello = "Merhaba aile 👋 iOS'tan"
        try await s.send(hello, to: publicId)
        try s.meeting.post("ios-public-sent", ["text": hello])
        let reply = try #require(try await s.meeting.wait("kotlin-public-sent")["text"])
        try await s.awaitText(reply, from: owner.accountId, in: publicId)

        // 2. A private community owned here, joined by invitation.
        try await s.communities.create(name: privateName, isPublic: false)
        let privateGroup = try #require(try await s.communities.groups().first { $0.name == privateName })
        let privateId = privateGroup.id
        let privateState = try #require(privateGroup.state)
        #expect(!privateState.isPublic && privateState.name.isEmpty, "a private name never reaches the relay")
        #expect(privateState.owner == me && privateState.revision == 1)
        let inviteThird = try await s.communities.invite(privateId)
        let inviteOwner = try await s.communities.invite(privateId)
        try s.meeting.post(
            "ios-private",
            ["communityId": privateId.string, "inviteThird": inviteThird, "inviteOwner": inviteOwner])

        _ = try await s.meeting.wait("kotlin-redeemed")
        let requests = try await s.communities.requests(privateId)
        let thirdRequest = try #require(requests.first { $0.accountId == third.accountId })
        let ownerRequest = try #require(requests.first { $0.accountId == owner.accountId })
        #expect(thirdRequest.member == third && ownerRequest.member == owner)
        try await s.communities.approve(thirdRequest, in: privateId)
        try await s.communities.approve(ownerRequest, in: privateId)
        let joined = try await s.state(privateId, revision: 3)
        #expect(Set(joined.members.map(\.accountId)) == [own, owner.accountId, third.accountId])
        try s.meeting.post("ios-private-approved", ["revision": "3"])

        let fromThird = try #require(try await s.meeting.wait("kotlin-private-sent")["text"])
        try await s.awaitText(fromThird, from: third.accountId, in: privateId)
        // K1 admitted K3 to the public community meanwhile.
        let withThird = try await s.state(publicId, revision: 3)
        #expect(Set(withThird.members.map(\.accountId)) == [owner.accountId, own, third.accountId])
        // Community traffic made nobody a personal contact.
        #expect(try await s.party.engine.chatSummaries().isEmpty)
        #expect(try await s.party.engine.contactInfo(owner.accountId) == nil)
        #expect(try await s.party.engine.contactInfo(third.accountId) == nil)
        let welcome = "Hoş geldin, gizli çaya ☕"
        try await s.send(welcome, to: privateId)
        try s.meeting.post("ios-private-sent", ["text": welcome])

        // 3. One-to-one with K1, on the ratchet the communities already use.
        let direct = try #require(try await s.meeting.wait("kotlin-direct")["text"])
        try await eventually(timeout: 60, "K1's message request") {
            try await s.party.engine.chatSummaries().contains { $0.id == owner.accountId && $0.isRequest }
        }
        #expect(try await s.party.messages(with: owner.accountId).map(\.body) == [.text(direct)])
        try await s.party.engine.acceptRequest(owner.accountId)
        let directReply = "Birebir: selam, burası iOS"
        try await s.party.engine.send(.text(directReply), to: owner.accountId)
        try s.meeting.post("ios-direct", ["text": directReply])

        // 4. Removal: K3 leaves the private roster by the owner's signed state.
        let before = try await s.meeting.wait("kotlin-before-remove")
        try await s.awaitText(try #require(before["public"]), from: owner.accountId, in: publicId)
        try await s.awaitText(try #require(before["private"]), from: owner.accountId, in: privateId)
        try await s.communities.remove(third, from: privateId)
        let removed = try await s.state(privateId, revision: 4)
        #expect(Set(removed.members.map(\.accountId)) == [own, owner.accountId])
        try s.meeting.post("ios-removed", ["revision": "4"])
        let afterRemoval = try #require(try await s.meeting.wait("kotlin-after-remove")["text"])
        try await s.awaitText(afterRemoval, from: owner.accountId, in: privateId)
        let stillHere = "Kaldık iki kişi, devam"
        try await s.send(stillHere, to: privateId)
        try s.meeting.post("ios-after-remove", ["text": stillHere])

        // 5. K3 left the public community; its roster still lists K3 until K1 publishes.
        _ = try await s.meeting.wait("kotlin-left")
        let afterLeave = "K3 ayrıldı, mesajlar sürüyor"
        // The relay refuses the leaver (404): this phone reports the message partial, K1 still gets it.
        try await s.send(afterLeave, to: publicId, settled: [.sent, .partial])
        try s.meeting.post("ios-after-leave", ["text": afterLeave])
        let updated = try await s.meeting.wait("kotlin-owner-updated")
        try await s.awaitText(try #require(updated["text"]), from: owner.accountId, in: publicId)
        let withoutThird = try await s.state(publicId, revision: 4)
        #expect(Set(withoutThird.members.map(\.accountId)) == [owner.accountId, own])

        let lastDirect = "Birebir: hâlâ buradayım"
        try await s.party.engine.send(.text(lastDirect), to: owner.accountId)
        try s.meeting.post("ios-final", ["text": lastDirect])
        _ = try await s.meeting.wait("kotlin-done")

        // Neither path leaked into the other.
        #expect(try await s.party.engine.chatSummaries().map(\.id) == [owner.accountId])
        #expect(try await s.party.engine.contactInfo(third.accountId) == nil)
        let directTexts = Set(try await s.party.messages(with: owner.accountId).compactMap { item -> String? in
            if case .text(let text) = item.body { return text }
            return nil
        })
        #expect(directTexts == [direct, directReply, lastDirect])
        var communityTexts = Set<String>()
        for id in [publicId, privateId] { for item in try await s.communities.messages(id) { communityTexts.insert(item.text) } }
        #expect(communityTexts.isDisjoint(with: directTexts))
        #expect(communityTexts.isSuperset(of: [hello, reply, welcome, fromThird, stillHere, afterLeave]))
        try await s.communities.delete(privateId)
    }

    /// One account of the script with its community engine and the meeting place.
    struct Side: Sendable {
        let party: Party
        let meeting: CommunityMeeting
        let backend: MessengerBackend
        let communities: CommunityEngine

        init(party: Party, meeting: CommunityMeeting) throws {
            self.party = party
            self.meeting = meeting
            backend = try #require(party.backend as? MessengerBackend)
            communities = CommunityEngine(backend: backend, storage: party.storage)
        }

        /// Pages through the public directory until `id` turns up.
        func find(_ id: AccountId, query: String) async throws -> CommunitySummary {
            var after: String?
            repeat {
                let page = try await communities.search(query, after: after)
                if let found = page.communities.first(where: { $0.id == id }) { return found }
                after = page.nextCursor
            } while after != nil
            throw CommunityError.invalidState
        }

        /// Syncs until the community is active at `revision`; its verified state.
        func state(_ id: AccountId, revision: UInt64) async throws -> CommunityState {
            try await eventually(timeout: 60, "revision \(revision) of \(id)") {
                _ = try await communities.sync()
                let group = try await communities.groups().first { $0.id == id }
                return group?.isActive == true && group?.state?.revision == revision
            }
            return try #require(try await communities.groups().first { $0.id == id }?.state)
        }

        /// Sends `text` and waits until the outbox has settled it.
        func send(_ text: String, to id: AccountId, settled: Set<CommunityDeliveryStatus> = [.sent]) async throws {
            #expect(try await communities.send(text, to: id).isEmpty)
            try await eventually(timeout: 60, "\"\(text)\" delivered") {
                if let issue = await communities.flushNow() { Issue.record("flush: \(issue)") }
                let status = try await communities.messages(id).first { $0.outgoing && $0.text == text }?.status
                return status.map(settled.contains) ?? false
            }
        }

        /// Syncs until `text` from `sender` is stored in the community.
        func awaitText(_ text: String, from sender: AccountId, in id: AccountId) async throws {
            try await eventually(timeout: 90, "\"\(text)\" in \(id)") {
                _ = try await communities.sync()
                if try await communities.messages(id).contains(where: { !$0.outgoing && $0.sender == sender && $0.text == text }) {
                    return true
                }
                try await Task.sleep(for: .milliseconds(300))
                return false
            }
        }
    }
}

/// The directory both platforms meet in: one JSON object of strings per step.
struct CommunityMeeting: Sendable {
    static var directory: URL? {
        ProcessInfo.processInfo.environment["COMMUNITY_DIR"].map { URL(fileURLWithPath: $0, isDirectory: true) }
    }

    enum Failure: Error { case peerFailed(String), timedOut(String) }

    let directory: URL

    func post(_ step: String, _ values: [String: String] = [:]) throws {
        try JSONSerialization.data(withJSONObject: values).write(to: file(step), options: .atomic)
    }

    func wait(_ step: String, timeout: TimeInterval = 300) async throws -> [String: String] {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let data = try? Data(contentsOf: file(step)) {
                return try #require(try JSONSerialization.jsonObject(with: data) as? [String: String])
            }
            if FileManager.default.fileExists(atPath: file("kotlin-failed").path) { throw Failure.peerFailed(step) }
            try await Task.sleep(for: .milliseconds(200))
        }
        throw Failure.timedOut(step)
    }

    private func file(_ step: String) -> URL { directory.appendingPathComponent(step + ".json") }
}

extension [String: String] {
    /// `<role>Id` and `<role>Key` (base64url) as a roster member.
    func member(_ role: String) -> CommunityMember? {
        guard let id = self[role + "Id"].flatMap(AccountId.init(string:)),
              let key = self[role + "Key"].flatMap(Data.init(base64URLEncoded:)) else { return nil }
        return CommunityMember(accountId: id, identityKey: key)
    }
}
