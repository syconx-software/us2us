import Foundation
import LibSignalClient
import Testing

@testable import Us2usKit

/// Answers token rotations from a script, in order, and records which token
/// each one was authenticated with and which new token it carried.
final class ScriptedRotationRelay: Sendable {
    enum Answer: Sendable, Equatable {
        case status(Int)
        /// The connection drops: no answer at all.
        case noAnswer
    }

    enum Token: Sendable, Equatable {
        case old, new, other
    }

    /// `T`, the backup's token.
    static let old = Data(repeating: 0x0A, count: 32)
    /// `N`, the token the client chose.
    static let new = Data((0..<32).map { UInt8(255 - $0) })

    private let log: Locked<[Token]>
    private let bodies: Locked<[String?]>
    let client: RelayClient

    init(_ answers: [Answer]) {
        let script = Locked(answers)
        let log = Locked<[Token]>([])
        let bodies = Locked<[String?]>([])
        let endpoint = StubURLProtocol.register { request in
            let token = request.header("X-Auth-Token").flatMap(Data.init(base64URLEncoded:))
            let who: Token =
                token == ScriptedRotationRelay.old ? .old : token == ScriptedRotationRelay.new ? .new : .other
            log.withLock { $0.append(who) }
            bodies.withLock { $0.append((try? request.json())?["newAuthToken"] as? String) }
            let answer = script.withLock { $0.isEmpty ? Answer.noAnswer : $0.removeFirst() }
            switch answer {
            case .noAnswer:
                return StubURLProtocol.noAnswer
            case .status(204):
                return (204, [:], Data())
            case .status(let status):
                return (
                    status, ["Content-Type": "application/json"],
                    Data(#"{"error":{"code":"refused","message":"refused"}}"#.utf8)
                )
            }
        }
        self.log = log
        self.bodies = bodies
        client = RelayClient(baseURL: endpoint.baseURL, configuration: endpoint.configuration)
    }

    var asked: [Token] { log.withLock { $0 } }
    var sentTokens: [String?] { bodies.withLock { $0 } }
}

@Suite("Token rotation: every lost answer still leaves a token that works (§4.3, §5.5 step 3)")
struct TokenRotationTests {
    enum Expected: Sendable, Equatable {
        case committed, unchanged, noLongerValid, unknown
    }

    struct Case: Sendable {
        /// Step 3.4 (a start with a pending token) instead of step 3.2.
        let resuming: Bool
        let answers: [ScriptedRotationRelay.Answer]
        let expected: Expected
        let asked: [ScriptedRotationRelay.Token]
    }

    static let cases: [Case] = [
        // Step 3.2, then 3.4 at once when no answer arrives.
        Case(resuming: false, answers: [.status(204)], expected: .committed, asked: [.old]),
        // The 204 was lost: asking with N finds it out.
        Case(resuming: false, answers: [.noAnswer, .status(204)], expected: .committed, asked: [.old, .new]),
        // 401 for T: an earlier attempt completed in between, N works.
        Case(resuming: false, answers: [.status(401), .status(204)], expected: .committed, asked: [.old, .new]),
        Case(
            resuming: false, answers: [.status(401), .status(401)], expected: .noLongerValid, asked: [.old, .new]),
        Case(
            resuming: false, answers: [.noAnswer, .status(401), .status(204)], expected: .committed,
            asked: [.old, .new, .old]),
        Case(
            resuming: false, answers: [.noAnswer, .status(401), .status(401), .status(204)], expected: .committed,
            asked: [.old, .new, .old, .new]),
        Case(
            resuming: false, answers: [.noAnswer, .status(401), .status(401), .status(401)],
            expected: .noLongerValid, asked: [.old, .new, .old, .new]),
        Case(resuming: false, answers: [.noAnswer, .noAnswer], expected: .unknown, asked: [.old, .new]),
        Case(resuming: false, answers: [.status(500), .status(429)], expected: .unknown, asked: [.old, .new]),
        Case(resuming: false, answers: [.status(401), .noAnswer], expected: .unknown, asked: [.old, .new]),
        Case(
            resuming: false, answers: [.noAnswer, .status(401), .noAnswer], expected: .unknown,
            asked: [.old, .new, .old]),
        // 400: nothing changed.
        Case(resuming: false, answers: [.status(400)], expected: .unchanged, asked: [.old]),
        Case(
            resuming: false, answers: [.noAnswer, .status(401), .status(400)], expected: .unchanged,
            asked: [.old, .new, .old]),
        // Step 3.4 on its own: N, then T, then N once more.
        Case(resuming: true, answers: [.status(204)], expected: .committed, asked: [.new]),
        Case(resuming: true, answers: [.status(401), .status(204)], expected: .committed, asked: [.new, .old]),
        Case(
            resuming: true, answers: [.status(401), .status(401), .status(204)], expected: .committed,
            asked: [.new, .old, .new]),
        Case(
            resuming: true, answers: [.status(401), .status(401), .status(401)], expected: .noLongerValid,
            asked: [.new, .old, .new]),
        Case(resuming: true, answers: [.noAnswer], expected: .unknown, asked: [.new]),
        Case(resuming: true, answers: [.status(429)], expected: .unknown, asked: [.new]),
        Case(resuming: true, answers: [.status(503)], expected: .unknown, asked: [.new]),
        Case(resuming: true, answers: [.status(401), .noAnswer], expected: .unknown, asked: [.new, .old]),
    ]

    @Test("the recovery matrix", arguments: TokenRotationTests.cases)
    func recovery(_ test: Case) async throws {
        let relay = ScriptedRotationRelay(test.answers)
        let old = ScriptedRotationRelay.old
        let new = ScriptedRotationRelay.new
        let outcome: Expected
        do {
            let result: TokenRotationOutcome
            if test.resuming {
                result = try await TokenRotation.resolve(old: old, new: new, relay: relay.client)
            } else {
                result = try await TokenRotation.rotate(from: old, to: new, relay: relay.client)
            }
            switch result {
            case .committed(let token):
                #expect(token == new)
                outcome = .committed
            case .unchanged:
                outcome = .unchanged
            case .backupNoLongerValid:
                outcome = .noLongerValid
            }
        } catch {
            outcome = .unknown
        }
        #expect(outcome == test.expected)
        #expect(relay.asked == test.asked)
        // Every request carries the same new token: it is never replaced while the outcome is unknown.
        #expect(relay.sentTokens.allSatisfy { $0 == new.base64URLEncodedString() })
        // Finding out costs at most three 401s (§5.5 step 3.4).
        let refusals = test.answers.prefix(relay.asked.count).filter { $0 == .status(401) }
        #expect(refusals.count <= 3)
    }

    private func pendingMessenger(
        _ relay: ScriptedRotationRelay, saves: Locked<[LocalAccount]>
    ) throws -> Messenger {
        let account = LocalAccount(
            accountId: try #require(AccountId(bytes: .id(1))), authToken: ScriptedRotationRelay.old,
            username: try NormalizedUsername("bob_02"), accessKey: .id(13),
            pendingAuthToken: ScriptedRotationRelay.new)
        let store = InMemoryProtocolStore(identity: .generate(), registrationId: LocalIdentity.generateRegistrationId())
        return Messenger(
            account: account, store: store, relay: relay.client,
            saveAccount: { account in saves.withLock { $0.append(account) } })
    }

    @Test("400 while settling: the old token still works, so a fresh token is pending and the next call rotates to it")
    func refusedNewTokenStaysPending() async throws {
        // N unknown (401), T refuses the new token (400); then N′ unknown, T → N′ accepted.
        let relay = ScriptedRotationRelay([.status(401), .status(400), .status(401), .status(204)])
        let saves = Locked<[LocalAccount]>([])
        let messenger = try pendingMessenger(relay, saves: saves)

        await #expect(throws: BackupRestoreError.tokenRefused) { try await messenger.finishPendingTokenRotation() }
        let retried = await messenger.account
        let fresh = try #require(retried.pendingAuthToken, "never reported as settled: the old phone keeps T")
        #expect(fresh != ScriptedRotationRelay.new)
        #expect(fresh.count == 32)
        #expect(retried.authToken == ScriptedRotationRelay.old)
        #expect(saves.withLock { $0.last } == retried)
        #expect(messenger.relay.credentials == nil)

        #expect(try await messenger.finishPendingTokenRotation() == .ready)
        let settled = await messenger.account
        #expect(settled.authToken == fresh)
        #expect(settled.pendingAuthToken == nil)
        #expect(relay.asked == [.new, .old, .other, .old])
        let freshBody = fresh.base64URLEncodedString()
        let firstBody = ScriptedRotationRelay.new.base64URLEncodedString()
        #expect(relay.sentTokens == [firstBody, firstBody, freshBody, freshBody])
    }

    @Test("a refusal is remembered: later calls say so instead of ready, without asking again")
    func refusalIsRemembered() async throws {
        let relay = ScriptedRotationRelay([.status(401), .status(401), .status(401)])
        let saves = Locked<[LocalAccount]>([])
        let messenger = try pendingMessenger(relay, saves: saves)

        #expect(try await messenger.finishPendingTokenRotation() == .backupNoLongerValid)
        #expect(await messenger.account.pendingAuthToken == nil)
        #expect(try await messenger.finishPendingTokenRotation() == .backupNoLongerValid)
        #expect(relay.asked.count == 3)
    }

    @Test("a new token is 32 random bytes the relay accepts as random")
    func newTokens() {
        var seen: Set<Data> = []
        for _ in 0..<64 {
            let token = TokenRotation.newToken()
            #expect(token.count == 32)
            #expect(Set(token).count >= TokenRotation.minimumDistinctBytes)
            seen.insert(token)
        }
        #expect(seen.count == 64)
    }
}

@Suite("Restore with a token the client chose (§5.5 step 3)")
struct RestoreTokenTests {
    let relay = FakeRelay()

    private func register(_ name: String) async throws -> Messenger {
        let store = InMemoryProtocolStore(identity: .generate(), registrationId: LocalIdentity.generateRegistrationId())
        return try await Messenger.register(
            username: try NormalizedUsername(name), store: store, relay: relay.makeClient())
    }

    private func restore(_ backup: BRPAccountBackup, saves: Locked<[LocalAccount]>? = nil) async throws -> Messenger {
        try await Messenger.restore(
            from: backup, relay: relay.makeClient(),
            makeStore: { InMemoryProtocolStore(identity: $0, registrationId: $1) },
            saveAccount: { account in saves?.withLock { $0.append(account) } })
    }

    @Test("a lost 204 is found out with the new token, and the old phone is locked out")
    func lostAnswer() async throws {
        let bob = try await register("bob_02")
        let bobId = await bob.account.accountId
        let backup = try await bob.makeBackup(displayName: "Bob", contacts: [])
        relay.loseNextAnswer("POST", "/v1/accounts/me/token")

        let restored = try await restore(backup)
        let account = await restored.account
        #expect(account.pendingAuthToken == nil)
        #expect(account.authToken != backup.authToken)
        #expect(relay.account(bobId)?.tokenHash == account.authToken.sha256Digest)
        #expect(relay.tokenRotations == [backup.authToken, account.authToken])
        #expect(account.pendingSetup == [.announceProfile])
        await #expect(throws: RelayError.self) { _ = try await bob.relay.keyStatus() }
        _ = try await restored.relay.keyStatus()
    }

    @Test("without any answer both tokens are kept, neither is used, and the next start settles it")
    func noAnswer() async throws {
        let bob = try await register("bob_02")
        let bobId = await bob.account.accountId
        let backup = try await bob.makeBackup(displayName: "Bob", contacts: [])
        // Carried out, answer lost; then the question with the new token gets no answer either.
        relay.loseNextAnswer("POST", "/v1/accounts/me/token")
        relay.dropNext("POST", "/v1/accounts/me/token")
        let saves = Locked<[LocalAccount]>([])

        let restored = try await restore(backup, saves: saves)
        let pending = await restored.account
        let chosen = try #require(pending.pendingAuthToken)
        #expect(pending.authToken == backup.authToken)
        #expect(saves.withLock { $0.last } == pending)
        #expect(relay.account(bobId)?.tokenHash == chosen.sha256Digest)
        // Nothing else uses either token meanwhile: no key upload, and a
        // call fails before anything is sent.
        #expect(!relay.requests.contains { $0.path == "/v1/keys" })
        #expect(restored.relay.credentials == nil)
        let sent = relay.requests.count
        await #expect(throws: RelayError.notAuthenticated) { _ = try await restored.relay.keyStatus() }
        #expect(relay.requests.count == sent)

        // The next start, from what was saved.
        let resumed = Messenger(
            account: pending, store: restored.store, relay: relay.makeClient(),
            saveAccount: { account in saves.withLock { $0.append(account) } })
        #expect(resumed.relay.credentials == nil)
        try await resumed.performMaintenance()
        let account = await resumed.account
        #expect(account.authToken == chosen)
        #expect(account.pendingAuthToken == nil)
        #expect(account.pendingSetup == [.announceProfile])
        #expect(relay.tokenRotations == [backup.authToken, chosen, chosen])
        #expect(saves.withLock { $0.last } == account)
        #expect(resumed.relay.credentials?.authToken == chosen)
    }

    @Test("401 for both tokens: the backup is no longer valid and nothing else is sent")
    func anotherPhoneRotatedFirst() async throws {
        let bob = try await register("bob_02")
        let bobId = await bob.account.accountId
        let backup = try await bob.makeBackup(displayName: "Bob", contacts: [])
        // Another phone restored the same backup and rotated first.
        relay.update(bobId) { $0.tokenHash = TokenRotation.newToken().sha256Digest }
        let saves = Locked<[LocalAccount]>([])

        await #expect(throws: BackupRestoreError.backupNoLongerValid) {
            _ = try await restore(backup, saves: saves)
        }
        #expect(relay.tokenRotations.count == 2)
        #expect(saves.withLock { $0.last }?.pendingAuthToken == nil)
        #expect(!relay.requests.contains { $0.path == "/v1/keys" })
    }

    @Test("a start with a pending token that never went out: N is refused, T rotates now")
    func neverSent() async throws {
        let bob = try await register("bob_02")
        let original = await bob.account
        var pending = original
        let chosen = TokenRotation.newToken()
        pending.pendingAuthToken = chosen

        let resumed = Messenger(account: pending, store: bob.store, relay: relay.makeClient())
        #expect(try await resumed.finishPendingTokenRotation() == .ready)
        #expect(await resumed.account.authToken == chosen)
        #expect(await resumed.account.pendingAuthToken == nil)
        #expect(relay.tokenRotations == [chosen, original.authToken])
        await #expect(throws: RelayError.self) { _ = try await bob.relay.keyStatus() }
        // Nothing pending any more: no further requests.
        #expect(try await resumed.finishPendingTokenRotation() == .ready)
        #expect(relay.tokenRotations.count == 2)
    }

    @Test("while the outcome is unknown the pending token is never replaced; concurrent callers share one attempt")
    func pendingTokenIsKept() async throws {
        let bob = try await register("bob_02")
        let original = await bob.account
        var pending = original
        let chosen = TokenRotation.newToken()
        pending.pendingAuthToken = chosen
        let saves = Locked<[LocalAccount]>([])
        let resumed = Messenger(
            account: pending, store: bob.store, relay: relay.makeClient(),
            saveAccount: { account in saves.withLock { $0.append(account) } })

        for _ in 0..<3 {
            relay.dropNext("POST", "/v1/accounts/me/token")
            await #expect(throws: RelayError.self) { try await resumed.finishPendingTokenRotation() }
            #expect(await resumed.account.pendingAuthToken == chosen)
            #expect(await resumed.account.authToken == original.authToken)
        }
        #expect(saves.withLock { $0.isEmpty })
        #expect(relay.tokenRotations == [chosen, chosen, chosen])

        async let first = resumed.finishPendingTokenRotation()
        async let second = resumed.finishPendingTokenRotation()
        let results = try await [first, second]
        #expect(results == [.ready, .ready])
        #expect(relay.tokenRotations.count == 5)  // N refused, then T rotated: one attempt
        #expect(saves.withLock { $0.count } == 1)
        #expect(await resumed.account.authToken == chosen)
    }

    @Test("account deletion settles a pending rotation first")
    func deletionSettlesFirst() async throws {
        let bob = try await register("bob_02")
        let bobId = await bob.account.accountId
        var pending = await bob.account
        pending.pendingAuthToken = TokenRotation.newToken()
        let resumed = Messenger(account: pending, store: bob.store, relay: relay.makeClient())
        try await resumed.deleteAccount()
        #expect(relay.account(bobId) == nil)
    }

    @Test("the read-only check before a restore replaces a vault changes nothing")
    func checkBackup() async throws {
        let bob = try await register("bob_02")
        let bobId = await bob.account.accountId
        let backup = try await bob.makeBackup(displayName: "Bob", contacts: [])
        let client = relay.makeClient()

        try await Messenger.checkBackup(backup, relay: client)
        #expect(client.credentials == nil)
        #expect(relay.requests.last?.path == "/v1/keys/status")
        #expect(relay.requests.last?.method == "GET")
        _ = try await bob.relay.keyStatus()  // still the account's token

        var broken = backup
        broken.authToken = Data(count: 31)
        let before = relay.requests.count
        await #expect(throws: MessengerError.invalidBackup) { try await Messenger.checkBackup(broken, relay: client) }
        #expect(relay.requests.count == before)

        relay.dropNext("GET", "/v1/keys/status")
        await #expect(throws: RelayError.network(.networkConnectionLost)) {
            try await Messenger.checkBackup(backup, relay: client)
        }

        relay.update(bobId) { $0.tokenHash = TokenRotation.newToken().sha256Digest }
        await #expect(throws: BackupRestoreError.backupNoLongerValid) {
            try await Messenger.checkBackup(backup, relay: client)
        }
        #expect(client.credentials == nil)
    }
}

@Suite("A backup that opens is one a restore accepts (§5.5 step 1)")
struct BackupImportValidationTests {
    static let password = "yedek parolam 2026"

    static func sample() throws -> BRPAccountBackup {
        var backup = BRPAccountBackup()
        backup.version = 1
        backup.accountID = .id(1)
        backup.authToken = .pattern(32)
        backup.identityKeyPair = try deterministicIdentity("backup").serialize()
        backup.registrationID = 1234
        backup.username = "alice_01"
        backup.displayName = "Alice"
        backup.accessKey = .id(13)
        var contact = BRPBackupContact()
        contact.accountID = .id(2)
        contact.username = "bob_02"
        contact.identityKey = try deterministicIdentity("bob").identityKey.serialize()
        contact.verified = true
        contact.theirAccessKey = .id(3)
        backup.contacts = [contact]
        backup.createdAt = 1_760_000_000_000
        return backup
    }

    static func file(_ backup: BRPAccountBackup) throws -> Data {
        try AccountBackupFile.export(backup, password: password, salt: .pattern(16), iterations: 1000, nonce: .pattern(12))
    }

    @Test func aValidBackupOpens() throws {
        let backup = try Self.sample()
        #expect(try AccountBackupFile.import(try Self.file(backup), password: Self.password) == backup)
    }

    @Test(
        "an encrypted, readable but malformed backup does not open",
        arguments: [
            { (b: inout BRPAccountBackup) in b.authToken = Data([1]) },
            { (b: inout BRPAccountBackup) in b.authToken = Data(count: 31) },
            { (b: inout BRPAccountBackup) in b.identityKeyPair = Data([1]) },
            { (b: inout BRPAccountBackup) in b.registrationID = 0 },
            { (b: inout BRPAccountBackup) in b.registrationID = 16381 },
            { (b: inout BRPAccountBackup) in b.accessKey = Data(count: 15) },
            { (b: inout BRPAccountBackup) in b.username = "Alice_01" },
            { (b: inout BRPAccountBackup) in b.username = "" },
            { (b: inout BRPAccountBackup) in b.displayName = String(repeating: "x", count: 65) },
            { (b: inout BRPAccountBackup) in b.accountID = Data(count: 15) },
            { (b: inout BRPAccountBackup) in b.version = 2 },
            { (b: inout BRPAccountBackup) in b.contacts.append(b.contacts[0]) },
            { (b: inout BRPAccountBackup) in b.contacts[0].accountID = b.accountID },
            { (b: inout BRPAccountBackup) in b.contacts[0].identityKey = Data(b.contacts[0].identityKey.prefix(32)) },
            { (b: inout BRPAccountBackup) in b.contacts[0].theirAccessKey = Data(count: 15) },
        ] as [@Sendable (inout BRPAccountBackup) -> Void])
    func malformed(_ breakIt: @Sendable (inout BRPAccountBackup) -> Void) throws {
        var backup = try Self.sample()
        breakIt(&backup)
        let file = try Self.file(backup)
        #expect(throws: AccountBackupError.invalidFile) {
            try AccountBackupFile.import(file, password: Self.password)
        }
        // The password was right: a damaged backup is never reported as a wrong password.
        #expect(throws: AccountBackupError.wrongPassword) {
            try AccountBackupFile.import(file, password: Self.password + "x")
        }
    }
}
