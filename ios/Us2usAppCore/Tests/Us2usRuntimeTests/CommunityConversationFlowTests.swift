import Foundation
import Testing
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime

/// The open community conversation as the screen drives it: a text, then a
/// photo under the id its bubble already has (`sendMedia(_:caption:messageId:to:)`).
/// Both stay in `messages`, the conversation stays selected, and the photo's
/// upload and fanout in the background change nothing about that.
@Suite("Communities v2: the open conversation across a text and a photo")
struct CommunityConversationFlowTests {
    @Test @MainActor func pagesSurvivePollingAndCloseClearsTheWindow() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer()
        fixture.relay.register(alice.account, identityKey: alice.member.identityKey, bundle: try alice.bundle())
        let state = try fixture.community(owner: alice, members: [fixture.me])
        try fixture.storage.write { tx in
            for index in 0..<350 {
                try tx.communities.store(BRPCommunityMessage.text("history-\(index)", community: state.id, revision: 1),
                    sender: alice.account, outgoing: false, now: Date().addingTimeInterval(Double(index)/1000))
            }
        }
        let service = CommunityService(backend: fixture.backend, storage: fixture.storage, pollInterval: .seconds(3600))
        defer { service.stop() }
        await service.refresh()
        try await service.open(state.id)
        #expect(service.messages.count == 100)
        #expect(try await service.loadOlderMessages(state.id))
        let oldest = service.messages.first?.id
        #expect(service.messages.count == 200)
        await service.refresh()
        #expect(service.messages.count == 200 && service.messages.first?.id == oldest)
        service.closeConversation()
        #expect(service.messages.isEmpty)
        #expect(try await !service.loadOlderMessages(state.id))
        try await service.open(state.id)
        #expect(service.messages.count == 100)
    }

    static let photo = Data((0..<3000).map { UInt8($0 % 239) })

    @Test("a text and a photo sent in the open conversation stay visible, before and after the upload")
    @MainActor
    func textThenPhotoStayVisible() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer(), carol = CommunityPeer()
        for peer in [alice, carol] { fixture.relay.register(peer.account, identityKey: peer.member.identityKey, bundle: try peer.bundle()) }
        let state = try fixture.community(owner: alice, members: [fixture.me, carol.member])
        let service = CommunityService(backend: fixture.backend, storage: fixture.storage, pollInterval: .seconds(3600))
        defer { service.stop() }
        await service.refresh()
        try await service.open(state.id)
        #expect(service.selected == state.id && service.messages.isEmpty)

        try await service.send("Selam", to: state.id)
        #expect(service.selected == state.id)
        #expect(service.messages.map(\.text) == ["Selam"])

        // The bubble's id, as `OutgoingMedia` hands it over.
        let bubble = RandomId.make()
        try await service.sendMedia(try fixture.prepared(Self.photo), caption: "Gün batımı", messageId: bubble, to: state.id)
        // Right away: the text and the photo, under the bubble's id, still selected.
        #expect(service.selected == state.id)
        #expect(service.messages.map(\.text) == ["Selam", "Gün batımı"])
        #expect(service.messages.last?.messageId.bytes == bubble)
        #expect(service.messages.last?.attachment?.kind == .image)

        // The upload and the fanout finish in the background: the same two messages, now sent.
        try await eventually("the photo is sent") {
            service.messages.last?.status == .sent && service.messages.last?.attachment?.state == .uploaded
        }
        #expect(service.selected == state.id)
        #expect(service.messages.map(\.text) == ["Selam", "Gün batımı"])
        #expect(service.messages.allSatisfy { $0.outgoing })
        #expect(fixture.relay.uploads == 1)

        // A later refresh (the poller) keeps them too.
        await service.refresh()
        #expect(service.messages.map(\.text) == ["Selam", "Gün batımı"])
    }
}
