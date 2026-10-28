import Foundation
import LibSignalClient
import Testing
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime

/// Client fixes for the 2026-10-28 security audit, the same behaviour as
/// Android's (reports/security-audit-2026-10-28/messenger-android-fixes.md):
/// message-request preconditions (M-9), contact-code add without a second
/// lookup (M-10), key rotation on "delete contact" (L-2), poison and blocked
/// envelopes on the real receive path (H-1, L-18), and the community
/// re-admission, leaver drop and "reject all" (M-11, M-14).
@Suite("Security audit 2026-10-28: runtime follow-ups")
struct AuditFollowUpRuntimeTests {
    private struct Refused: Error {}

    private func text(_ body: String) -> BRPContent { .textMessage(body) }

    // MARK: M-9

    @Test("opening a handle alone cannot be accepted by its owner")
    func openedHandleIsNoRequest() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        let bob = AccountId(uuid: UUID())
        fixture.backend.register("bob_99", as: bob)
        _ = try await fixture.engine.startConversation(username: try NormalizedUsername("bob_99"))
        try await fixture.receive(.messageRequestResponse(.accept), from: bob)
        try await fixture.receive(BRPContent(receipt: .delivery, messageIds: [RandomId.make()]), from: bob)
        try await fixture.receive(.accessKeyUpdate(Data(repeating: 6, count: 16)), from: bob)
        await settle()
        let contact = try #require(try fixture.contact(bob))
        #expect(contact.requestState == .outgoingRequest)
        #expect(contact.theirAccessKey == nil)
        #expect(try fixture.outbox().isEmpty)
        #expect(fixture.backend.sent(to: bob).isEmpty)
        await fixture.engine.stop()
    }

    @Test("a message to an opened but unsent handle is its own request")
    func openedHandleMessageIsAnIncomingRequest() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        let bob = AccountId(uuid: UUID())
        fixture.backend.register("bob_99", as: bob)
        _ = try await fixture.engine.startConversation(username: try NormalizedUsername("bob_99"))
        // Even with `sender_username` (bob's own outgoing request): not a crossing request.
        try await fixture.receive(text("selam, ben bob"), from: bob, username: "bob_99")
        await settle()
        #expect(try fixture.contact(bob)?.requestState == .incomingRequest)
        #expect(try await fixture.messages(bob).count == 1)
        #expect(try fixture.outbox().isEmpty)
        #expect(fixture.backend.sent(to: bob).isEmpty, "no ACCEPT, no key, no profile, no receipt")
        // The user accepts it like any request.
        try await fixture.engine.acceptRequest(bob)
        #expect(try fixture.contact(bob)?.requestState == .accepted)
        await fixture.engine.stop()
    }

    @Test("the request counts once the relay took a visible message")
    func sentRequestCanBeAccepted() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        let bob = AccountId(uuid: UUID())
        fixture.backend.register("bob_99", as: bob)
        _ = try await fixture.engine.startConversation(username: try NormalizedUsername("bob_99"))
        try await fixture.engine.send(.text("hey bob"), to: bob)
        try await eventually("marked sent") { (try? fixture.storage.read { try $0.contacts.requestSent(to: bob) }) == true }
        try await fixture.receive(.messageRequestResponse(.accept), from: bob)
        #expect(try fixture.contact(bob)?.requestState == .accepted)
        await fixture.engine.stop()
    }

    @Test("a send the relay refused does not make the request answerable")
    func refusedSendIsNotARequest() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        let bob = AccountId(uuid: UUID())
        fixture.backend.register("bob_99", as: bob)
        _ = try await fixture.engine.startConversation(username: try NormalizedUsername("bob_99"))
        fixture.backend.failNextSend(to: bob, with: RelayError.server(status: 400, code: nil, message: nil))
        try await fixture.engine.send(.text("olmadı"), to: bob)
        try await eventually("given up") {
            let entries = (try? fixture.outbox()) ?? []
            return !entries.isEmpty && entries.allSatisfy { $0.state == .failed }
        }
        #expect(try fixture.storage.read { try $0.contacts.requestSent(to: bob) } == false)
        try await fixture.receive(.messageRequestResponse(.accept), from: bob)
        #expect(try fixture.contact(bob)?.requestState == .outgoingRequest)
        await fixture.engine.stop()
    }

    @Test("a crossing message without sender_username is no acceptance")
    func crossingNeedsSenderUsername() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        let bob = AccountId(uuid: UUID())
        fixture.backend.register("bob_99", as: bob)
        _ = try await fixture.engine.startConversation(username: try NormalizedUsername("bob_99"))
        try await fixture.engine.send(.text("selam bob"), to: bob)
        try await eventually("marked sent") { (try? fixture.storage.read { try $0.contacts.requestSent(to: bob) }) == true }
        try await fixture.receive(text("isimsiz"), from: bob)
        await settle()
        #expect(try fixture.contact(bob)?.requestState == .outgoingRequest)
        #expect(!fixture.backend.sent(to: bob).contains { $0.isAccept })
        try await fixture.receive(text("selam selin"), from: bob, username: "bob_99")
        #expect(try fixture.contact(bob)?.requestState == .accepted)
        try await eventually("ACCEPT") { fixture.backend.sent(to: bob).contains { $0.isAccept } }
        await fixture.engine.stop()
    }

    // MARK: M-10

    @Test("a contact code adds the checked account without a second lookup and pins its key first")
    func contactCodeUsesTheCheckedLookup() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        // Not in the fake directory: a second lookup would fail.
        let peer = AccountId(uuid: UUID())
        let key = IdentityKeyPair.generate().identityKey
        let added = try await fixture.engine.addContactFromCode(
            username: try NormalizedUsername("lale"), account: peer, identityKey: key.serialize(), pinKey: true)
        #expect(added)
        let contact = try #require(try fixture.contact(peer))
        #expect(contact.requestState == .outgoingRequest)
        #expect(contact.profile.username?.value == "lale")
        #expect(contact.profile.usernameStatus == .verified)
        #expect(fixture.backend.state.withLock { $0.identity[peer] } == .trusted(key, verified: true))
        #expect(fixture.backend.state.withLock { $0.verifications[peer]?.method } == .qrCode)
        #expect(try fixture.storage.read { try $0.conversations.forContact(peer) } != nil)
        await fixture.engine.stop()
    }

    @Test("a contact code for an account holding another key adds nothing")
    func contactCodeMismatchAddsNothing() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        let peer = AccountId(uuid: UUID())
        let held = IdentityKeyPair.generate().identityKey
        fixture.backend.setIdentity(.trusted(held, verified: false), number: Data([1]), for: peer)
        let added = try await fixture.engine.addContactFromCode(
            username: try NormalizedUsername("lale"), account: peer,
            identityKey: IdentityKeyPair.generate().identityKey.serialize(), pinKey: true)
        #expect(!added)
        #expect(try fixture.contact(peer) == nil)
        #expect(try fixture.storage.read { try $0.conversations.forContact(peer) } == nil)
        #expect(fixture.backend.state.withLock { $0.identity[peer] } == .trusted(held, verified: false))
        await fixture.engine.stop()
    }

    @Test("a contact code without a fingerprint adds the account unverified")
    func contactCodeWithoutFingerprint() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        let peer = AccountId(uuid: UUID())
        let added = try await fixture.engine.addContactFromCode(
            username: try NormalizedUsername("lale"), account: peer,
            identityKey: IdentityKeyPair.generate().identityKey.serialize(), pinKey: false)
        #expect(added)
        #expect(try fixture.contact(peer)?.requestState == .outgoingRequest)
        #expect(fixture.backend.state.withLock { $0.identity[peer] } == nil)
        await #expect(throws: EngineError.ownAccount) {
            _ = try await fixture.engine.addContactFromCode(
                username: try NormalizedUsername("tester"), account: fixture.account.accountId,
                identityKey: Data(), pinKey: false)
        }
        await fixture.engine.stop()
    }

    // MARK: L-2

    @Test("deleting a contact that held our key rotates it for the others")
    func deletingAContactRotatesTheAccessKey() async throws {
        let fixture = try EngineFixture()
        let friend = try fixture.acceptedContact()
        let gone = try fixture.acceptedContact()
        await fixture.start()
        try await fixture.engine.deleteContact(gone)
        try await eventually("new key to the friend") { fixture.backend.sent(to: friend).contains { $0.isAccessKeyUpdate } }
        #expect(fixture.backend.state.withLock { $0.rotations } == 1)
        let update = try #require(fixture.backend.sent(to: friend).first { $0.isAccessKeyUpdate })
        guard case .accessKey(let key)? = update.control else { return }
        #expect(key.accessKey == fixture.backend.state.withLock { $0.accessKey })
        #expect(fixture.backend.sent(to: gone).isEmpty)
        // It can still write, as a stranger: a new request.
        try await fixture.receive(text("hâlâ buradayım"), from: gone, username: "eski")
        #expect(try fixture.contact(gone)?.requestState == .incomingRequest)
        await fixture.engine.stop()
    }

    @Test("deleting a request that never got our key rotates nothing")
    func deletingARequestRotatesNothing() async throws {
        let fixture = try EngineFixture()
        _ = try fixture.acceptedContact()
        await fixture.start()
        let stranger = AccountId(uuid: UUID())
        try await fixture.receive(text("merhaba"), from: stranger, username: "yabanci")
        #expect(try fixture.contact(stranger)?.requestState == .incomingRequest)
        try await fixture.engine.deleteContact(stranger)
        await settle()
        #expect(fixture.backend.state.withLock { $0.rotations } == 0)
        await fixture.engine.stop()
    }

    // MARK: H-1 and L-18 on the real receive path (Messenger + SQLCipher store)

    private func directText(_ body: String, from peer: CommunityPeer, to fixture: CommunityFixture, id: String) throws {
        if try !peer.cipher.hasUsableSession(with: fixture.account.accountId) { try peer.cipher.processBundle(try fixture.bundle()) }
        var content = BRPContent()
        content.body = .data(BRPDataMessage(payload: .text({ var text = BRPText(); text.body = body; return text }()), messageId: RandomId.make()))
        fixture.relay.deliverDirect(.init(id: id, serverTimestamp: 1, envelope: try peer.cipher.seal(content, for: fixture.account.accountId)))
    }

    /// One connection: every pending envelope, `deliver` storing like the engine
    /// (a failure discards the staged decryption, so a redelivery decrypts again).
    private func connect(
        _ fixture: CommunityFixture, refuse: @escaping @Sendable (String) -> (any Error)?, seen: LockedState<[String]>
    ) async throws {
        let storage = fixture.storage, store = fixture.store
        try await store.withStagedInboundWrites {
            try await fixture.messenger.receivePending { message in
                let body = message.content.plainText ?? ""
                seen.withLock { $0.append(body) }
                if let error = refuse(body) {
                    _ = try? store.discardStagedInboundWrites()
                    throw error
                }
                try storage.write { try $0.envelopes.record(message.relayId, at: Date()) }
            }
        }
    }

    private func pendingDirect(_ fixture: CommunityFixture) async throws -> Int {
        try await fixture.relay.client.fetchMessages(limit: 100).messages.count
    }

    @Test("a message the app keeps refusing is acknowledged after three connections; the next one is not held up")
    func poisonEnvelopeIsDroppedAfterThreeAttempts() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer()
        fixture.relay.register(alice.account, identityKey: alice.member.identityKey)
        try directText("zehir", from: alice, to: fixture, id: "bad")
        try directText("sağlam", from: alice, to: fixture, id: "good")
        let seen = LockedState<[String]>([])
        for attempt in 1...3 {
            try await connect(fixture, refuse: { $0 == "zehir" ? Refused() : nil }, seen: seen)
            #expect(try await pendingDirect(fixture) == (attempt < 3 ? 1 : 0), "after attempt \(attempt)")
        }
        // Decrypted again at every connection (the staged ratchet was discarded), delivered once.
        #expect(seen.withLock { $0 } == ["zehir", "sağlam", "zehir", "zehir"])
        #expect(try fixture.storage.read { try $0.envelopes.contains("good") })
    }

    @Test("a locked store never counts against a message: it is delivered once the store is back")
    func unavailableStoreIsNeverCounted() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer()
        fixture.relay.register(alice.account, identityKey: alice.member.identityKey)
        try directText("sabır", from: alice, to: fixture, id: "wait")
        let seen = LockedState<[String]>([])
        for _ in 1...5 {
            await #expect(throws: DeliveryDeferral.storeUnavailable) {
                try await connect(fixture, refuse: { _ in DeliveryDeferral.storeUnavailable }, seen: seen)
            }
            #expect(try await pendingDirect(fixture) == 1)
        }
        try await connect(fixture, refuse: { _ in nil }, seen: seen)
        #expect(try await pendingDirect(fixture) == 0)
        #expect(try fixture.storage.read { try $0.envelopes.contains("wait") })
    }

    @Test("the engine's 'not running' and a closed store are store failures; content errors are not")
    func storeUnavailableClassification() {
        #expect(MessengerStream.isStoreUnavailable(EngineError.stopped))
        #expect(MessengerStream.isStoreUnavailable(RuntimeError.locked))
        #expect(MessengerStream.isStoreUnavailable(StorageError.closed))
        #expect(MessengerStream.isStoreUnavailable(StorageError.io("disk")))
        #expect(!MessengerStream.isStoreUnavailable(StorageError.invalidState("message id")))
        #expect(!MessengerStream.isStoreUnavailable(StorageError.integrity))
        #expect(!MessengerStream.isStoreUnavailable(Refused()))
    }

    @Test("a blocked contact's PreKey message is dropped before lookup and decryption, and acknowledged")
    func blockedSenderDroppedOnTheRealPath() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer()
        fixture.relay.register(alice.account, identityKey: alice.member.identityKey)
        try fixture.storage.write { try $0.contacts.block(alice.account) }
        RelayBackendFactory.dropBlockedSenders(of: fixture.messenger, storage: fixture.storage)
        try directText("engelli", from: alice, to: fixture, id: "blocked-1")
        let seen = LockedState<[String]>([])
        try await connect(fixture, refuse: { _ in nil }, seen: seen)
        #expect(seen.withLock { $0 }.isEmpty)
        #expect(try await pendingDirect(fixture) == 0)
        #expect(fixture.relay.count("GET /v1/accounts/\(alice.account.string)/identity") == 0)
        #expect(try fixture.store.loadSession(for: alice.account.protocolAddress, context: NullContext()) == nil)
        #expect(try fixture.store.identity(for: alice.account.protocolAddress, context: NullContext()) == nil)
        // Unblocked: the next message is received.
        try fixture.storage.write { _ = try $0.contacts.unblock(alice.account) }
        try directText("açıldı", from: alice, to: fixture, id: "open-1")
        try await connect(fixture, refuse: { _ in nil }, seen: seen)
        #expect(seen.withLock { $0 } == ["açıldı"])
    }

    // MARK: M-11, M-14

    private func application(_ peer: CommunityPeer) throws -> CommunityJoinRequest {
        struct Payload: Encodable { let accountId: AccountId; let identityKey: Data; let createdAt: Int64 }
        let json = try FakeCommunityRelay.Server.encoder.encode(
            Payload(accountId: peer.account, identityKey: peer.member.identityKey, createdAt: 1))
        return try FakeCommunityRelay.Server.decoder.decode(CommunityJoinRequest.self, from: json)
    }

    @Test("a member who left is re-admitted with two signed states")
    func leaverIsReadmittedInTwoSteps() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer()
        let state = try fixture.ownCommunity(members: [alice.member])
        let request = try application(alice)
        fixture.relay.setApplications([request], in: state.id)
        try await fixture.engine.approve(request, in: state.id)
        #expect(fixture.relay.count("PUT /v1/communities/\(state.id.string)/state") == 2)
        let final = try #require(fixture.relay.state(of: state.id))
        #expect(final.revision == state.revision + 2)
        #expect(final.member(alice.account) != nil)
        #expect(fixture.relay.applications(in: state.id).isEmpty)
    }

    @Test("a new member is still admitted with one signed state")
    func newMemberIsAdmittedOnce() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer()
        let state = try fixture.ownCommunity(members: [])
        let request = try application(alice)
        fixture.relay.setApplications([request], in: state.id)
        try await fixture.engine.approve(request, in: state.id)
        #expect(fixture.relay.count("PUT /v1/communities/\(state.id.string)/state") == 1)
        #expect(fixture.relay.state(of: state.id)?.revision == state.revision + 1)
        #expect(fixture.relay.state(of: state.id)?.member(alice.account) != nil)
    }

    @Test("the owner drops a listed member whose delivery is refused as gone, after its queued messages left")
    func ownerDropsDepartedMember() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer(), bob = CommunityPeer()
        for peer in [alice, bob] { fixture.relay.register(peer.account, identityKey: peer.member.identityKey, bundle: try peer.bundle()) }
        let state = try fixture.ownCommunity(members: [alice.member, bob.member])
        fixture.relay.setSendStatus(404, for: alice.account)
        #expect(try await fixture.engine.send("herkese", to: state.id).isEmpty)
        #expect(await fixture.engine.flushNow() == nil)
        let next = try #require(fixture.relay.state(of: state.id))
        #expect(next.revision == state.revision + 1)
        #expect(next.member(alice.account) == nil)
        #expect(next.member(bob.account) != nil)
        #expect(fixture.relay.sent.map(\.recipient) == [bob.account])
    }

    @Test("a member's phone never signs a roster for someone else's community")
    func memberDoesNotDropDeparted() async throws {
        let fixture = try CommunityFixture()
        let owner = CommunityPeer(), alice = CommunityPeer()
        for peer in [owner, alice] { fixture.relay.register(peer.account, identityKey: peer.member.identityKey, bundle: try peer.bundle()) }
        let state = try fixture.community(owner: owner, members: [fixture.me, alice.member])
        _ = try await fixture.engine.sync()
        fixture.relay.setSendStatus(404, for: alice.account)
        _ = try await fixture.engine.send("selam", to: state.id)
        _ = await fixture.engine.flushNow()
        #expect(fixture.relay.count("PUT /v1/communities/\(state.id.string)/state") == 0)
        #expect(fixture.relay.state(of: state.id)?.revision == state.revision)
    }

    @Test("reject all uses the bulk route and falls back one by one on an older relay")
    func rejectAll() async throws {
        let fixture = try CommunityFixture()
        let state = try fixture.ownCommunity(members: [])
        let requests = try (0..<3).map { _ in try application(CommunityPeer()) }

        fixture.relay.setApplications(requests, in: state.id)
        fixture.relay.setBulkReject(true)
        try await fixture.engine.rejectAll(in: state.id)
        #expect(fixture.relay.count("DELETE /v1/communities/\(state.id.string)/requests") == 1)
        #expect(fixture.relay.applications(in: state.id).isEmpty)

        fixture.relay.setApplications(requests, in: state.id)
        fixture.relay.setBulkReject(false)
        try await fixture.engine.rejectAll(in: state.id)
        #expect(fixture.relay.applications(in: state.id).isEmpty)
        for request in requests {
            #expect(fixture.relay.count("DELETE /v1/communities/\(state.id.string)/requests/\(request.accountId.string)") == 1)
        }

        // Not the owner: refused before anything is sent.
        let other = CommunityPeer()
        let foreign = try fixture.community(owner: other, members: [fixture.me])
        await #expect(throws: (any Error).self) { try await fixture.engine.rejectAll(in: foreign.id) }
        #expect(fixture.relay.count("DELETE /v1/communities/\(foreign.id.string)/requests") == 0)
    }
}

private extension BRPContent {
    var plainText: String? {
        guard case .data(let data) = body, case .text(let text) = data.payload else { return nil }
        return text.body
    }
}
