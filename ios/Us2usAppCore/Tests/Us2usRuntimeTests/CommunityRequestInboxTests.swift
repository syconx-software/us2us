import Foundation
import Testing
import Us2usKit
@testable import Us2usRuntime

@Suite("Owner join-request inbox")
struct CommunityRequestInboxTests {
    private func application(_ peer: CommunityPeer) throws -> CommunityJoinRequest {
        struct Payload: Encodable { let accountId: AccountId; let identityKey: Data; let createdAt: Int64 }
        let json = try FakeCommunityRelay.Server.encoder.encode(Payload(accountId: peer.account, identityKey: peer.member.identityKey, createdAt: 1))
        return try FakeCommunityRelay.Server.decoder.decode(CommunityJoinRequest.self, from: json)
    }

    @Test @MainActor
    func loadsEveryOwnedGroupWithoutOpeningChatsAndClearsOnLock() async throws {
        let fixture = try CommunityFixture()
        let first = try fixture.ownCommunity(members: [])
        let second = try fixture.ownCommunity(members: [])
        let other = CommunityPeer()
        let memberGroup = try fixture.community(owner: other, members: [fixture.me])
        let request = try application(CommunityPeer())
        fixture.relay.setApplications([request], in: first.id)
        fixture.relay.setApplications([request], in: second.id)
        let service = CommunityService(backend: fixture.backend, storage: fixture.storage)
        defer { service.stop() }
        await service.refresh()
        #expect(Set(service.ownerRequests.keys) == [first.id, second.id])
        #expect(service.ownerRequests[first.id] == [request])
        #expect(service.selected == nil && service.messages.isEmpty)
        #expect(!fixture.relay.requests.contains("GET /v1/communities/\(memberGroup.id.string)/requests"))
        // Refreshing the inbox must also preserve an already selected conversation.
        try await service.open(memberGroup.id)
        await service.refresh()
        #expect(service.selected == memberGroup.id)
        service.stop()
        #expect(service.ownerRequests.isEmpty)
        await service.refresh()
        #expect(service.ownerRequests.isEmpty)
    }

    @Test @MainActor
    func outageRetainsCountButSuccessfulEmptyResponseAndLostOwnershipRemoveIt() async throws {
        let fixture = try CommunityFixture()
        let group = try fixture.ownCommunity(members: [])
        let request = try application(CommunityPeer())
        fixture.relay.setApplications([request], in: group.id)
        let service = CommunityService(backend: fixture.backend, storage: fixture.storage)
        defer { service.stop() }
        await service.refresh()
        fixture.relay.setApplicationStatus(503, in: group.id)
        await service.refresh()
        #expect(service.ownerRequests[group.id] == [request])
        #expect(service.issue == .offline)
        fixture.relay.setApplicationStatus(200, in: group.id)
        fixture.relay.setApplications([], in: group.id)
        await service.refresh()
        #expect(service.ownerRequests[group.id]?.isEmpty == true)
        fixture.relay.setApplications([request], in: group.id)
        await service.refresh()
        fixture.relay.setState(nil, for: group.id)
        await service.refresh()
        #expect(service.ownerRequests[group.id] == nil)
    }

    @Test @MainActor
    func approvingAndRejectingUpdateTheRightGroupWithoutOpeningIt() async throws {
        let fixture = try CommunityFixture()
        let group = try fixture.ownCommunity(members: [])
        let alice = try application(CommunityPeer())
        let bob = try application(CommunityPeer())
        fixture.relay.setApplications([alice, bob], in: group.id)
        let service = CommunityService(backend: fixture.backend, storage: fixture.storage)
        defer { service.stop() }
        await service.refresh()
        try await service.reject(alice, in: group.id)
        #expect(service.ownerRequests[group.id] == [bob])
        try await service.approve(bob, in: group.id)
        #expect(service.ownerRequests[group.id]?.isEmpty == true)
        #expect(service.joined.first { $0.id == group.id }?.state?.member(bob.accountId) != nil)
        #expect(service.selected == nil && service.messages.isEmpty)
    }
}
