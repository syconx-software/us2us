import Foundation
import Testing
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime

/// Blocking and unblocking (§6.4) beyond the drop and the key rotation
/// (`InboundTests`, `RequestTests`): what a block leaves of messages still on
/// their way, a Ghost wipe of a blocked chat, and an unblock tapped twice.
@Suite("Blocking (§6.4)")
struct BlockingTests {
    private func enableGhost(_ fixture: EngineFixture, _ peer: AccountId, leaveNoTrace: Bool) async throws {
        try await fixture.engine.updateConversationSettings(
            peer, ghost: GhostSettings(enabled: true, timeoutSeconds: 3600, wipeContact: leaveNoTrace),
            defaultExpireTimerSeconds: 0)
    }

    /// Ghost's deadline passes (nothing can arrive from someone blocked).
    private func runGhostOut(_ fixture: EngineFixture, _ peer: AccountId) async throws {
        try await eventually("wiped") {
            fixture.clock.advance(by: 600)
            await fixture.engine.runDueWipes()
            return try await fixture.engine.conversationInfo(peer) == nil
        }
    }

    @Test("messages still on their way when the person is blocked fail; they are not left “sending”")
    func pendingMessagesFail() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        // Offline: nothing leaves the outbox.
        let id = try await fixture.engine.send(.text("yarın görüşelim"), to: peer)
        #expect(try await fixture.messages(peer).first { $0.messageId == id }?.status == .sending)
        try await fixture.engine.block(peer)
        #expect(try await fixture.messages(peer).first { $0.messageId == id }?.status == .failed)
        #expect(try fixture.outbox().allSatisfy { $0.recipient != peer }, "nothing is sent to them")
        await #expect(throws: (any Error).self) { _ = try await fixture.engine.resend(id, peer: peer) }
        // Unblocked: “Tekrar gönder” queues it again.
        try await fixture.engine.unblock(peer)
        _ = try await fixture.engine.resend(id, peer: peer)
        #expect(try fixture.outbox().contains { $0.recipient == peer && $0.messageId != nil })
    }

    @Test("a blocked chat erased by Ghost comes back from 4.2, still blocked; unblocking restores the contact")
    func ghostWipedBlockedChatComesBack() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        fixture.backend.register("deniz", as: peer)
        try await enableGhost(fixture, peer, leaveNoTrace: false)
        try await fixture.engine.block(peer)
        try await runGhostOut(fixture, peer)
        #expect(try await !fixture.engine.chatSummaries().contains { $0.id == peer }, "the chat went")
        #expect(try fixture.contact(peer)?.requestState == .blocked, "the block stays")

        let found = try await fixture.engine.startConversation(username: try NormalizedUsername("deniz"))
        #expect(found == peer)
        let chat = try #require(try await fixture.engine.chatSummaries().first { $0.id == peer })
        #expect(chat.contact.requestState == .blocked, "read only, with “Engeli kaldır”")
        #expect(!chat.isRequest)
        await #expect(throws: (any Error).self) { _ = try await fixture.engine.send(.text("selam"), to: peer) }

        try await fixture.engine.unblock(peer)
        #expect(try fixture.contact(peer)?.requestState == .accepted)
    }

    @Test("after “leave no trace” kept only the block, 4.2 brings the chat back and unblocking makes it a new request")
    func strippedBlockComesBackAsANewChat() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        fixture.backend.register("deniz", as: peer)
        try await enableGhost(fixture, peer, leaveNoTrace: true)
        try await fixture.engine.block(peer)
        try await runGhostOut(fixture, peer)
        let stripped = try #require(try fixture.contact(peer))
        #expect(stripped.isBlocked)
        #expect(stripped.profile.username == nil, "nothing but the block was kept")

        _ = try await fixture.engine.startConversation(username: try NormalizedUsername("deniz"))
        let chat = try #require(try await fixture.engine.chatSummaries().first { $0.id == peer })
        #expect(chat.contact.requestState == .blocked)
        #expect(chat.contact.username == "deniz")

        try await fixture.engine.unblock(peer)
        #expect(try fixture.contact(peer)?.requestState == .outgoingRequest, "a new chat, not an empty request")
        #expect(try await fixture.engine.chatSummaries().contains { $0.id == peer && !$0.isRequest })
    }

    @Test("4.2 keeps what a block remembers: a blocked request that comes back is a request again once unblocked")
    func startingAChatKeepsTheStateBeforeTheBlock() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        let stranger = AccountId(uuid: UUID())
        fixture.backend.register("kuzey_dev", as: stranger)
        try await fixture.receive(.textMessage("merhaba"), from: stranger, username: "kuzey_dev")
        try await fixture.engine.block(stranger)
        _ = try await fixture.engine.startConversation(username: try NormalizedUsername("kuzey_dev"))
        try await fixture.engine.unblock(stranger)
        #expect(try fixture.contact(stranger)?.requestState == .incomingRequest)
        await fixture.engine.stop()
    }

    @Test("a second “Engeli kaldır” sends neither the access key nor the profile again")
    func unblockingTwiceSendsOnce() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        try await fixture.engine.block(peer)
        try await fixture.engine.unblock(peer)
        let queued = try fixture.outbox().filter { $0.recipient == peer }
        #expect(queued.count == 2, "the current access key and the profile")
        try await fixture.engine.unblock(peer)
        #expect(try fixture.outbox().filter { $0.recipient == peer } == queued)
        #expect(try fixture.contact(peer)?.requestState == .accepted)
    }

    // MARK: Engellenenler

    @Test("everyone blocked is listed, also once the conversation is gone; unblocking takes them off the list")
    func blockedContactsIncludeThoseWithoutAConversation() async throws {
        let fixture = try EngineFixture()
        let deniz = try fixture.acceptedContact()
        let mert = try fixture.acceptedContact()
        let other = try fixture.acceptedContact()
        try await fixture.engine.block(deniz)
        try await fixture.engine.block(mert)
        try await fixture.engine.deleteConversationLocally(mert)
        #expect(try await !fixture.engine.chatSummaries().contains { $0.id == mert }, "mert's chat went")
        let blocked = try await fixture.engine.blockedContacts()
        #expect(Set(blocked.map(\.account)) == [deniz, mert])
        #expect(blocked.allSatisfy { $0.requestState == .blocked && $0.avatar == .initials })
        #expect(!blocked.contains { $0.account == other })

        // Without a conversation: back as before the block, still without one.
        try await fixture.engine.unblock(mert)
        #expect(try await fixture.engine.blockedContacts().map(\.account) == [deniz])
        #expect(try fixture.contact(mert)?.requestState == .accepted)
        #expect(try fixture.outbox().filter { $0.recipient == mert }.count == 2, "the current access key and the profile")
        #expect(try await !fixture.engine.chatSummaries().contains { $0.id == mert })
    }

    @Test("a block that “leave no trace” kept alone is listed without a name; unblocked, it leaves no chat")
    func strippedBlockIsListed() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        fixture.backend.register("deniz", as: peer)
        try await enableGhost(fixture, peer, leaveNoTrace: true)
        try await fixture.engine.block(peer)
        try await runGhostOut(fixture, peer)
        let listed = try #require(try await fixture.engine.blockedContacts().first)
        #expect(listed.account == peer)
        #expect(listed.username == nil && listed.displayName == nil, "nothing but the block was kept")

        try await fixture.engine.unblock(peer)
        #expect(try await fixture.engine.blockedContacts().isEmpty)
        #expect(try await fixture.engine.chatSummaries().isEmpty)
    }

    /// Unblocked from 7.1 › Engellenenler without finding them in 4.2 first:
    /// nothing was left but the block, so nothing is left after it.
    @Test("a bare block unblocked from the list is a stranger again: writing to them starts a new chat")
    func bareBlockUnblockedFromTheListLeavesNothing() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        fixture.backend.register("deniz", as: peer)
        try await enableGhost(fixture, peer, leaveNoTrace: true)
        try await fixture.engine.block(peer)
        try await runGhostOut(fixture, peer)

        try await fixture.engine.unblock(peer)
        #expect(try fixture.contact(peer) == nil, "forgotten, not a hidden request")
        _ = try await fixture.engine.startConversation(username: try NormalizedUsername("deniz"))
        let chat = try #require(try await fixture.engine.chatSummaries().first { $0.id == peer })
        #expect(!chat.isRequest, "a new chat, not an empty request")
        #expect(try fixture.contact(peer)?.requestState == .outgoingRequest)
    }

    @Test("a blocked request whose conversation is gone is forgotten when unblocked; a new message is a new request")
    func blockedRequestWithoutAConversationLeavesNothing() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        let stranger = AccountId(uuid: UUID())
        fixture.backend.register("kuzey_dev", as: stranger)
        try await fixture.receive(.textMessage("merhaba"), from: stranger, username: "kuzey_dev")
        try await fixture.engine.block(stranger)
        try await fixture.engine.deleteConversationLocally(stranger)
        #expect(try await fixture.engine.blockedContacts().map(\.account) == [stranger])

        try await fixture.engine.unblock(stranger)
        #expect(try fixture.contact(stranger) == nil)
        #expect(try await fixture.engine.chatSummaries().isEmpty, "no empty request")
        try await fixture.receive(.textMessage("tekrar merhaba"), from: stranger, username: "kuzey_dev")
        #expect(try await fixture.engine.chatSummaries().map(\.id) == [stranger])
        #expect(try fixture.contact(stranger)?.requestState == .incomingRequest)
        await fixture.engine.stop()
    }
}

/// The facade's list behind 7.1 › Engellenenler.
@Suite("Blocked contacts in the runtime")
@MainActor
struct BlockedContactsRuntimeTests {
    @Test func theListFollowsBlocksAndDeletedChatsAndGoesWithTheLock() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        let deniz = try fixture.acceptedContact()
        #expect(fixture.runtime.blockedContacts.isEmpty)
        try await fixture.runtime.block(deniz)
        try await eventually("deniz listed") { fixture.runtime.blockedContacts.map(\.account) == [deniz] }
        try await fixture.runtime.deleteConversation(deniz, forBoth: false)
        try await eventually("the chat went") { !fixture.runtime.chats.contains { $0.id == deniz } }
        #expect(fixture.runtime.blockedContacts.map(\.account) == [deniz], "listed without a conversation")

        try await fixture.runtime.unblock(deniz)
        try await eventually("deniz unlisted") { fixture.runtime.blockedContacts.isEmpty }
        #expect(!fixture.runtime.chats.contains { $0.id == deniz })

        try await fixture.runtime.block(deniz)
        try await eventually("deniz listed again") { !fixture.runtime.blockedContacts.isEmpty }
        await fixture.runtime.lockNow()
        #expect(fixture.runtime.blockedContacts.isEmpty, "nothing decrypted stays while locked")
    }
}
