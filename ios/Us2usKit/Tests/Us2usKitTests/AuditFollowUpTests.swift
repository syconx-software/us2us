import Foundation
import LibSignalClient
import Testing

@testable import Us2usKit

/// Client fixes for the 2026-10-28 security audit (reports/security-audit-2026-10-28):
/// the proof-of-work ceiling (L-10), the download ceiling (L-20), refused
/// redirects (L-7), blocked senders dropped before decryption (L-18), a
/// failing `deliver` that no longer blocks the mailbox (H-1) and the bulk
/// "reject all" route (M-14).
@Suite("Security audit 2026-10-28: client follow-ups")
struct AuditFollowUpTests {
    let relay = FakeRelay()

    private struct Refused: Error {}

    private func register(_ name: String) async throws -> Messenger {
        let store = InMemoryProtocolStore(identity: .generate(), registrationId: LocalIdentity.generateRegistrationId())
        return try await Messenger.register(
            username: try NormalizedUsername(name), store: store, relay: relay.makeClient())
    }

    // MARK: L-10

    @Test("a relay challenge above 28 bits is refused without solving")
    func powAboveCeilingIsRefused() async {
        let clock = ContinuousClock()
        let start = clock.now
        for difficulty in [29, 64, 256, -1] {
            await #expect(throws: ProofOfWorkError.abandoned) {
                _ = try await ProofOfWork.solveRelayChallenge(PowChallenge(token: "t", difficulty: difficulty, expiresAt: 0))
            }
        }
        #expect(clock.now - start < .seconds(1))
    }

    @Test("a relay challenge that takes too long is given up", .timeLimit(.minutes(1)))
    func powTimesOut() async {
        await #expect(throws: ProofOfWorkError.abandoned) {
            _ = try await ProofOfWork.solveRelayChallenge(
                PowChallenge(token: "slow", difficulty: ProofOfWork.maxRelayDifficulty, expiresAt: 0), timeout: 0.01)
        }
    }

    @Test("a challenge within the ceiling is still solved", .timeLimit(.minutes(1)))
    func powWithinCeiling() async throws {
        let solution = try await ProofOfWork.solveRelayChallenge(PowChallenge(token: "ok", difficulty: 12, expiresAt: 0))
        let nonce = try #require(UInt64(solution.nonce))
        #expect(ProofOfWork.isValid(token: "ok", nonce: nonce, difficulty: 12))
    }

    // MARK: L-20

    @Test("a download whose blob would exceed 1 GiB is refused before anything is fetched")
    func downloadCeiling() async throws {
        let bob = try await register("bob_02")
        let directory = try TemporaryDirectory()
        var attachment = BRPAttachment()
        attachment.mediaID = RandomId.make()
        attachment.deleteToken = Data(count: 32)
        attachment.key = Data(count: 64)
        attachment.digest = Data(count: 32)
        attachment.size = 2 << 30
        #expect(AttachmentCipher.blobSize(forPlaintextSize: attachment.size) > Messenger.maxDownloadBlobBytes)
        await #expect(throws: AttachmentError.sizeMismatch) {
            try await bob.downloadAttachment(attachment, to: directory.file("big"))
        }
        #expect(!relay.requests.contains { $0.method == "GET" && $0.path.hasPrefix("/v1/media/") })
        // Just under the ceiling the relay is asked (and here has no such blob).
        attachment.size = 900 << 20
        #expect(AttachmentCipher.blobSize(forPlaintextSize: attachment.size) <= Messenger.maxDownloadBlobBytes)
        await #expect(throws: (any Error).self) {
            try await bob.downloadAttachment(attachment, to: directory.file("big"))
        }
        #expect(relay.requests.contains { $0.method == "GET" && $0.path.hasPrefix("/v1/media/") })
    }

    // MARK: L-7

    @Test("both relay sessions refuse every HTTP redirect")
    func redirectsAreRefused() async throws {
        let sessions = RelayClient.makeSessions(configuration: RelayClient.defaultConfiguration())
        for session in [sessions.authenticated, sessions.anonymous] {
            let delegate = try #require(session.delegate as? RedirectRefusingDelegate)
            let task = session.dataTask(with: URL(string: "https://relay.example.test/v1/messages")!)
            let response = HTTPURLResponse(
                url: URL(string: "https://relay.example.test/v1/messages")!, statusCode: 302, httpVersion: "HTTP/1.1",
                headerFields: ["Location": "https://elsewhere.example.test/collect"])!
            var forwarded = URLRequest(url: URL(string: "https://elsewhere.example.test/collect")!)
            forwarded.setValue("secret", forHTTPHeaderField: "X-Auth-Token")
            let followed = Locked<URLRequest??>(nil)
            delegate.urlSession(session, task: task, willPerformHTTPRedirection: response, newRequest: forwarded) {
                request in followed.withLock { $0 = .some(request) }
            }
            #expect(followed.withLock { $0 } == .some(nil))
            task.cancel()
            session.invalidateAndCancel()
        }
    }

    @Test("a 3xx answer is a relay error, never followed")
    func redirectAnswerIsAnError() async throws {
        let (baseURL, configuration) = StubURLProtocol.register { _ in
            (302, ["Location": "https://elsewhere.example.test/"], Data())
        }
        let client = RelayClient(
            baseURL: baseURL,
            credentials: RelayCredentials(accountId: AccountId(uuid: UUID()), authToken: Data(repeating: 1, count: 32)),
            configuration: configuration)
        await #expect(throws: RelayError.server(status: 302, code: nil, message: nil)) {
            _ = try await client.keyStatus()
        }
    }

    // MARK: L-18

    @Test("a blocked sender's PreKey message is dropped before lookup and decryption, and acknowledged")
    func blockedSenderDroppedBeforeDecryption() async throws {
        let aliceStore = InMemoryProtocolStore(identity: .generate(), registrationId: 11)
        let alice = try await Messenger.register(
            username: try NormalizedUsername("alice_01"), store: aliceStore, relay: relay.makeClient())
        let bobStore = InMemoryProtocolStore(identity: .generate(), registrationId: 12)
        let bob = try await Messenger.register(
            username: try NormalizedUsername("bob_02"), store: bobStore, relay: relay.makeClient())
        let aliceId = await alice.account.accountId
        let bobId = await bob.account.accountId
        bob.dropMessages { $0 == aliceId }

        try await alice.send(.text("engellendin mi?"), to: bobId, requestState: .outgoingRequest, theirAccessKey: nil)
        let envelope = try #require(relay.account(bobId)?.mailbox.first)
        let lookupsBefore = relay.requests.filter { $0.path == "/v1/accounts/\(aliceId.string)/identity" }.count
        guard case .dropped(.blocked) = await bob.process(envelope) else {
            Issue.record("not dropped as blocked")
            return
        }
        #expect(try await bob.receiveAll().isEmpty)
        #expect(relay.account(bobId)?.mailbox.isEmpty == true)
        #expect(relay.requests.filter { $0.path == "/v1/accounts/\(aliceId.string)/identity" }.count == lookupsBefore)
        // No session and no identity row were (re)created for the blocked account.
        #expect(try bobStore.loadSession(for: aliceId.protocolAddress, context: NullContext()) == nil)
        #expect(try bobStore.identity(for: aliceId.protocolAddress, context: NullContext()) == nil)

        // Unblocked, the next message is received again.
        bob.dropMessages { _ in false }
        try await alice.send(.text("şimdi?"), to: bobId, requestState: .outgoingRequest, theirAccessKey: nil)
        #expect(try await bob.receiveAll().map(\.content.textBody) == ["şimdi?"])
    }

    // MARK: H-1

    @Test("a message the app cannot take stays at the relay while the next one is received")
    func failingDeliveryDoesNotBlockTheMailbox() async throws {
        let alice = try await register("alice_01")
        let bob = try await register("bob_02")
        let bobId = await bob.account.accountId
        try await alice.send(.text("zehir"), to: bobId, requestState: .outgoingRequest, theirAccessKey: nil)
        try await alice.send(.text("sağlam"), to: bobId, requestState: .outgoingRequest, theirAccessKey: nil)
        let envelopes = try #require(relay.account(bobId)?.mailbox)
        #expect(envelopes.count == 2)

        let delivered = Inbox()
        let acknowledged = Locked<[String]>([])
        for envelope in envelopes {
            try await bob.handle(
                .message(envelope),
                deliver: { message in
                    if message.content.textBody == "zehir" { throw Refused() }
                    try await delivered.deliver(message)
                },
                onQueueEmpty: {}, acknowledge: { ids in acknowledged.withLock { $0 += ids } })
        }
        #expect(delivered.all.map(\.content.textBody) == ["sağlam"])
        #expect(acknowledged.withLock { $0 } == [envelopes[1].id])
    }

    @Test("a store that is unavailable stops the loop and never counts against the message")
    func deferredDeliveryStopsTheLoop() async throws {
        let alice = try await register("alice_01")
        let bob = try await register("bob_02")
        let bobId = await bob.account.accountId
        try await alice.send(.text("bekle"), to: bobId, requestState: .outgoingRequest, theirAccessKey: nil)
        let envelope = try #require(relay.account(bobId)?.mailbox.first)
        let acknowledged = Locked<[String]>([])
        await #expect(throws: DeliveryDeferral.storeUnavailable) {
            try await bob.handle(
                .message(envelope), deliver: { _ in throw DeliveryDeferral.storeUnavailable },
                onQueueEmpty: {}, acknowledge: { ids in acknowledged.withLock { $0 += ids } })
        }
        #expect(acknowledged.withLock { $0 }.isEmpty)
    }

    @Test("failed attempts are counted per envelope, bounded, and forgotten on success")
    func attemptCounter() {
        var counter = AttemptCounter(capacity: 3)
        #expect(counter.record("a") == 1)
        #expect(counter.record("a") == 2)
        #expect(counter.record("b") == 1)
        counter.forget("a")
        #expect(counter.count("a") == 0)
        #expect(counter.record("a") == 1)
        _ = counter.record("c")
        _ = counter.record("d")  // over capacity: the oldest ("b") goes
        #expect(counter.count("b") == 0)
        #expect(counter.count("d") == 1)
        #expect(Messenger.maxDeliveryAttempts == 3)
    }

    // MARK: M-14

    @Test("reject all is one DELETE on the community's requests")
    func rejectAllRoute() async throws {
        let seen = Locked<[RecordedRequest]>([])
        let (baseURL, configuration) = StubURLProtocol.register { request in
            seen.withLock { $0.append(request) }
            return (204, [:], Data())
        }
        let client = RelayClient(
            baseURL: baseURL,
            credentials: RelayCredentials(accountId: AccountId(uuid: UUID()), authToken: Data(repeating: 1, count: 32)),
            configuration: configuration)
        let community = AccountId(uuid: UUID())
        try await client.rejectAllCommunityRequests(community)
        let request = try #require(seen.withLock { $0 }.first)
        #expect(request.method == "DELETE")
        #expect(request.path == "/v1/communities/\(community.string)/requests")
        #expect(request.header("X-Auth-Token") != nil)
    }
}
