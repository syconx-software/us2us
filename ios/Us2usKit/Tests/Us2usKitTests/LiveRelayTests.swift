import Foundation
import LibSignalClient
import Testing

@testable import Us2usKit

/// End to end against a real relay. Skipped unless `RELAY_URL` is set; with
/// xcodebuild pass it as `TEST_RUNNER_RELAY_URL=http://127.0.0.1:3000` (the
/// prefix is stripped for the test process).
@Suite(
    "Live relay end to end",
    .enabled(if: LiveRelay.url != nil, "set RELAY_URL (TEST_RUNNER_RELAY_URL with xcodebuild)"),
    .timeLimit(.minutes(5)))
struct LiveRelayTests {
    private func register() async throws -> Messenger {
        let store = InMemoryProtocolStore(identity: .generate(), registrationId: LocalIdentity.generateRegistrationId())
        let name = "t" + String((0..<15).map { _ in "abcdefghijklmnopqrstuvwxyz0123456789".randomElement()! })
        return try await Messenger.register(
            username: try NormalizedUsername(name), store: store, relay: RelayClient(baseURL: LiveRelay.url!))
    }

    @Test("register, request, accept, chat over HTTP and WebSocket, attachments, delete")
    func fullConversation() async throws {
        let health = try await RelayClient(baseURL: LiveRelay.url!).health()
        #expect(health.ok)

        let alice = try await register()
        let bob = try await register()
        let aliceId = await alice.account.accountId
        let bobId = await bob.account.accountId

        let found = try #require(try await alice.findAccount(username: await bob.account.username))
        #expect(found.accountId == bobId)

        // Message request with PoW and username, received over HTTP.
        try await alice.send(.text("Merhaba"), to: bobId, requestState: .outgoingRequest, theirAccessKey: nil)
        let request = try await bob.receiveAll()
        #expect(request.map(\.content.textBody) == ["Merhaba"])
        #expect(request.first?.senderUsername == (await alice.account.username))

        // Acceptance with Bob's access key.
        for content in MessageRequestPolicy.acceptanceMessages(ourAccessKey: await bob.account.accessKey) {
            try await bob.send(content, to: aliceId, requestState: .accepted, theirAccessKey: nil)
        }
        let acceptance = try await alice.receiveAll()
        let bobKey = try #require(acceptance.last?.content.control.accessKey.accessKey)

        // Live delivery to Bob's WebSocket, sent with the access key.
        let connection = try bob.relay.connect()
        let inbox = Inbox()
        let queueDrained = Locked(false)
        let runner = Task {
            try await bob.run(connection, deliver: inbox.deliver, onQueueEmpty: { queueDrained.withLock { $0 = true } })
        }
        try await eventually { queueDrained.withLock { $0 } }
        try await alice.send(.text("canlı"), to: bobId, requestState: .accepted, theirAccessKey: bobKey)
        try await eventually { inbox.all.contains { $0.content.textBody == "canlı" } }

        // Typing indicators are ephemeral and reach an online recipient.
        try await alice.send(
            BRPContent(typingStarted: true), to: bobId, requestState: .accepted, theirAccessKey: bobKey, ephemeral: true
        )
        try await eventually { inbox.all.contains { $0.content.typing.started } }

        // Presence fan-out through the anonymous batch endpoint (§8.6).
        let addressed = try await alice.sendPresence(PresencePolicy.online(), to: [(bobId, bobKey)])
        #expect(addressed == [bobId])
        try await eventually { inbox.all.contains { $0.content.presence.state == .online } }
        connection.close()
        _ = await runner.result

        // Attachment through the relay's media store.
        let directory = try TemporaryDirectory()
        try Data.pattern(100_000).write(to: directory.file("in"))
        let attachment = try await alice.uploadAttachment(
            fileAt: directory.file("in"), kind: .file, contentType: "application/octet-stream", fileName: "in.bin")
        try await alice.send(
            BRPContent(data: BRPDataMessage(payload: .attachment(attachment))), to: bobId, requestState: .accepted,
            theirAccessKey: bobKey)
        let received = try #require(try await bob.receiveAll().first)
        try await bob.downloadAttachment(received.content.data.attachment, to: directory.file("out"))
        #expect(try Data(contentsOf: directory.file("out")) == Data.pattern(100_000))

        try await bob.performMaintenance()
        let a = try #require(try await alice.safetyNumber(with: bobId))
        let b = try #require(try await bob.safetyNumber(with: aliceId))
        #expect(a.digits == b.digits)

        try await alice.deleteAccount()
        try await bob.deleteAccount()
    }

    @Test("a username change and a restore on a new phone keep the conversation and the safety number (§3.3, §5.5)")
    func usernameChangeAndRestore() async throws {
        let alice = try await register()
        let bob = try await register()
        let aliceId = await alice.account.accountId
        let bobId = await bob.account.accountId
        let aliceKey = await alice.account.accessKey
        let bobKey = await bob.account.accessKey
        try await alice.send(.text("ilk"), to: bobId, authorization: .accessKey(bobKey))
        #expect(try await bob.receiveAll().map(\.content.textBody) == ["ilk"])
        let safetyNumber = try #require(try await bob.safetyNumber(with: aliceId)).digits

        // The username is only a pointer to the account id: changing it leaves sessions alone.
        let newName = try NormalizedUsername("n" + String(UUID().uuidString.lowercased().prefix(8)))
        try await alice.changeUsername(to: newName)
        #expect(try await bob.findAccount(username: newName)?.accountId == aliceId)
        try await bob.send(.text("yeni adın güzel"), to: aliceId, authorization: .accessKey(aliceKey))
        #expect(try await alice.receiveAll().map(\.content.textBody) == ["yeni adın güzel"])

        // Backup file → new phone: the old token is revoked, the identity is unchanged.
        var contact = BRPBackupContact()  // identity fields are filled in from the store
        contact.accountID = bobId.bytes
        contact.theirAccessKey = bobKey
        let backup = try await alice.makeBackup(displayName: "Alice", contacts: [contact])
        let file = try AccountBackupFile.export(backup, password: "yedek için uzun parola")
        let restored = try await Messenger.restore(
            from: try AccountBackupFile.import(file, password: "yedek için uzun parola"),
            relay: RelayClient(baseURL: LiveRelay.url!),
            makeStore: { InMemoryProtocolStore(identity: $0, registrationId: $1) })
        #expect(await restored.account.username == newName)
        #expect(await restored.account.pendingSetup == [.announceProfile])
        do {
            _ = try await alice.relay.accountInfo()
            Issue.record("the old phone's token still works")
        } catch let error as RelayError {
            #expect(error.code == .unauthorized)
        }

        // The restored phone writes first: its complete profile to every contact (§5.5 step 5).
        let unreached = try await restored.announceProfile(
            .profileUpdate(displayName: "Alice", username: newName, presenceSharing: true),
            to: [(bobId, bobKey)])
        #expect(unreached.isEmpty)
        #expect(await restored.account.pendingSetup.isEmpty)
        try await restored.send(.text("yeni telefon"), to: bobId, authorization: .accessKey(bobKey))
        let fromRestored = try await bob.receiveAll()
        #expect(fromRestored.count == 2)
        guard case .control(let control) = fromRestored.first?.content.body, case .profile(let profile) = control.action
        else {
            Issue.record("expected the profile first")
            return
        }
        #expect(profile.username == newName.value)
        #expect(fromRestored.last?.content.textBody == "yeni telefon")
        #expect(fromRestored.allSatisfy { !$0.identityChanged })
        #expect(try await bob.safetyNumber(with: aliceId)?.digits == safetyNumber)
        try await bob.send(.text("hoş geldin"), to: aliceId, authorization: .accessKey(aliceKey))
        #expect(try await restored.receiveAll().map(\.content.textBody) == ["hoş geldin"])

        try await restored.deleteAccount()
        try await bob.deleteAccount()
    }

    private func eventually(timeout: TimeInterval = 20, _ condition: @Sendable () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            guard Date() < deadline else {
                Issue.record("condition not met within \(timeout) s")
                return
            }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
    }
}

enum LiveRelay {
    static let url: URL? = ProcessInfo.processInfo.environment["RELAY_URL"].flatMap(URL.init(string:))
}
