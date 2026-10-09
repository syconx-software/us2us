import Foundation
import LibSignalClient
import Testing
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime

/// The whole runtime against a real relay: Us2usKit's `Messenger`, the
/// WebSocket with keep-alive, Us2usStorage's SQLCipher store with atomic
/// receive, the engine and the connection supervisor. Skipped unless
/// `RELAY_URL` is set (xcodebuild: `TEST_RUNNER_RELAY_URL`).
enum LiveRelay {
    static var url: URL? {
        ProcessInfo.processInfo.environment["RELAY_URL"].flatMap(URL.init(string:))
    }
}

@Suite("Live relay", .serialized, .enabled(if: LiveRelay.url != nil))
struct LiveRuntimeTests {
    static var relayURL: URL? { LiveRelay.url }

    final class Party: @unchecked Sendable {
        let username: String
        let directory: TemporaryDirectory
        let storage: LocalStorage
        let backend: any MessagingBackend
        let engine: MessagingEngine
        let supervisor: ConnectionSupervisor
        let key: SecretKeyMaterial

        init(username: String, relay: URL) async throws {
            self.username = username
            directory = try TemporaryDirectory()
            let directories = directory.directories
            try directories.prepare()
            let dek = randomKey()
            key = SecretKeyMaterial(copying: dek)
            storage = try LocalStorage.open(layout: directories.storage, dek: dek).storage
            _ = try storage.createIdentity(.generate(), registrationId: LocalIdentity.generateRegistrationId())
            let backend = try await RelayBackendFactory(relayURL: relay).register(
                username: try NormalizedUsername(username), storage: storage)
            self.backend = backend
            let engine = try MessagingEngine(
                storage: storage, backend: backend, stateDirectory: directories.runtime, key: key)
            self.engine = engine
            supervisor = ConnectionSupervisor(
                backend: backend,
                events: ConnectionEvents(
                    deliver: { try await engine.receive($0) },
                    queueEmpty: { await engine.connectionReachedQueueEmpty() }))
            try await engine.updateUserSettings { $0.displayName = username.uppercased() }
            await engine.start()
        }

        func connect() async throws {
            await supervisor.start()
            try await eventually(timeout: 20, "\(username) online") { await self.supervisor.status == .open }
        }

        func reconnect() async throws {
            await engine.goingOffline()
            await supervisor.stop()
            await engine.connectionClosed()
            try await connect()
        }

        func shutdown() async {
            try? await backend.deleteAccount()
            await supervisor.stop()
            await engine.stop()
            storage.close()
        }

        func messages(with peer: AccountId) async throws -> [MessageItem] { try await engine.messages(of: peer) }
    }

    /// Runs `body` with two registered parties and always cleans up
    /// (accounts deleted on the relay, storage closed).
    static func withParties(_ body: (Party, Party) async throws -> Void) async throws {
        let relay = try #require(relayURL)
        let alice = try await Party(username: username("rta"), relay: relay)
        let bob = try await Party(username: username("rtb"), relay: relay)
        do {
            try await body(alice, bob)
        } catch {
            await alice.shutdown()
            await bob.shutdown()
            throw error
        }
        await alice.shutdown()
        await bob.shutdown()
    }

    static func username(_ prefix: String) -> String {
        "\(prefix)_\(UUID().uuidString.lowercased().prefix(8).filter { $0.isLetter || $0.isNumber })"
    }

    @Test func requestAcceptanceMessagingPresenceAndMedia() async throws {
        try await Self.withParties { alice, bob in try await Self.fullFlow(alice: alice, bob: bob) }
    }

    static func fullFlow(alice: Party, bob: Party) async throws {
        try await alice.connect()
        try await bob.connect()

        // Alice finds Bob by his exact username and writes first (PoW, §6.4).
        let bobId = try await alice.engine.startConversation(username: try NormalizedUsername(bob.username))
        #expect(bobId == bob.backend.accountId)
        try await alice.engine.send(.text("merhaba bob"), to: bobId)
        let aliceId = alice.backend.accountId

        // Bob gets a request, with Alice's verified username.
        try await eventually(timeout: 30, "request at bob") {
            try await bob.engine.chatSummaries().contains { $0.id == aliceId && $0.isRequest }
        }
        let request = try #require(try await bob.engine.contactInfo(aliceId))
        #expect(request.username == alice.username)
        #expect(request.usernameVerified)
        #expect(try await bob.messages(with: aliceId).first?.body == .text("merhaba bob"))

        // Bob accepts; keys and profiles are exchanged both ways.
        try await bob.engine.acceptRequest(aliceId)
        try await eventually(timeout: 30, "alice sees acceptance") {
            try await alice.engine.contactInfo(bobId)?.requestState == .accepted
        }
        try await eventually(timeout: 30, "profiles exchanged") {
            let atAlice = try await alice.engine.contactInfo(bobId)
            let atBob = try await bob.engine.contactInfo(aliceId)
            return atAlice?.displayName == bob.username.uppercased() && atBob?.displayName == alice.username.uppercased()
        }
        // Bob's delivery receipt for the request's message reached Alice.
        try await eventually(timeout: 30, "first message delivered") {
            try await alice.messages(with: bobId).first { $0.direction == .outgoing }?.status == .delivered
        }

        // Normal traffic with access keys; delivery and read receipts.
        let second = try await alice.engine.send(.text("ikinci mesaj"), to: bobId)
        try await eventually(timeout: 30, "bob has it") {
            try await bob.messages(with: aliceId).contains { $0.messageId == second }
        }
        try await bob.engine.markDisplayed(aliceId)
        try await eventually(timeout: 30, "read") {
            try await alice.messages(with: bobId).first { $0.messageId == second }?.status == .read
        }

        // Presence: Alice reconnects, announces ONLINE, Bob answers.
        try await alice.reconnect()
        try await eventually(timeout: 30, "bob sees alice active") {
            await bob.engine.presenceStatus(at: Date())[aliceId] == .active
        }
        try await eventually(timeout: 30, "alice sees bob active") {
            await alice.engine.presenceStatus(at: Date())[bobId] == .active
        }

        // Media: encrypted, uploaded, downloaded, verified, deleted from the relay.
        let file = alice.directory.directories.stagingFile(extension: "jpg")
        let bytes = Data((0..<5000).map { UInt8(truncatingIfNeeded: $0 &* 7) })
        try bytes.write(to: file)
        let photo = try await alice.engine.send(
            .media(PreparedMedia(file: file, kind: .image, contentType: "image/jpeg", size: UInt64(bytes.count))),
            to: bobId)
        try await eventually(timeout: 30, "photo downloaded") {
            guard case .attachment(let item)? = try await bob.messages(with: aliceId).first(where: { $0.messageId == photo })?.body
            else { return false }
            return item.availability == .local
        }
        let received = try await bob.engine.exportMedia(photo, peer: aliceId)
        #expect(try Data(contentsOf: received) == bytes)

        // Reactions and delete for everyone.
        try await bob.engine.react(to: second, peer: aliceId, emoji: "👍")
        try await eventually(timeout: 30, "reaction") {
            try await alice.messages(with: bobId).first { $0.messageId == second }?.reactions[bobId] == "👍"
        }
        try await alice.engine.deleteForEveryone(second, peer: bobId)
        try await eventually(timeout: 30, "deleted at bob") {
            try await bob.messages(with: aliceId).first { $0.messageId == second }?.body == .deleted
        }

        // Ghost settings sync (LWW) with a system line on both phones.
        try await alice.engine.updateConversationSettings(
            bobId, ghost: GhostSettings(enabled: true, timeoutSeconds: 3600), defaultExpireTimerSeconds: 0)
        try await eventually(timeout: 30, "ghost at bob") {
            try await bob.engine.conversationInfo(aliceId)?.ghost?.timeoutSeconds == 3600
        }

        // Blocking rotates Bob's access key on the relay.
        try await bob.engine.block(aliceId)
        #expect(try await bob.engine.contactInfo(aliceId)?.requestState == .blocked)
        try await alice.engine.send(.text("hâlâ orada mısın?"), to: bobId)
        try await Task.sleep(nanoseconds: 2_000_000_000)
        #expect(try await !bob.messages(with: aliceId).contains { $0.body == .text("hâlâ orada mısın?") })
    }

    @Test func deliveryWhileTheRecipientIsOffline() async throws {
        try await Self.withParties { alice, bob in try await Self.offlineFlow(alice: alice, bob: bob) }
    }

    static func offlineFlow(alice: Party, bob: Party) async throws {
        try await alice.connect()
        let bobId = try await alice.engine.startConversation(username: try NormalizedUsername(bob.username))
        for index in 0..<5 { try await alice.engine.send(.text("çevrimdışı \(index)"), to: bobId) }
        try await eventually(timeout: 30, "all sent") {
            try await alice.messages(with: bobId).filter { $0.status == .sent }.count == 5
        }
        // Bob opens the app later: the backlog arrives in order before queue_empty.
        try await bob.connect()
        let aliceId = alice.backend.accountId
        try await eventually(timeout: 30, "backlog") { try await bob.messages(with: aliceId).count == 5 }
        let texts = try await bob.messages(with: aliceId).map(\.body)
        #expect(texts == (0..<5).map { .text("çevrimdışı \($0)") })
    }
}
