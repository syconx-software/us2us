import Foundation
import LibSignalClient
import Testing

@testable import Us2usKit

/// Collects delivered messages across the actor boundary.
final class Inbox: Sendable {
    private let messages = Locked<[IncomingMessage]>([])
    var all: [IncomingMessage] { messages.withLock { $0 } }
    @Sendable func deliver(_ message: IncomingMessage) async throws { messages.withLock { $0.append(message) } }
}

extension Messenger {
    func receiveAll() async throws -> [IncomingMessage] {
        let inbox = Inbox()
        try await receivePending(deliver: inbox.deliver)
        return inbox.all
    }
}

@Suite("Messenger end to end against a fake relay")
struct MessengerTests {
    let relay = FakeRelay()

    private func register(_ name: String) async throws -> Messenger {
        let store = InMemoryProtocolStore(identity: .generate(), registrationId: LocalIdentity.generateRegistrationId())
        return try await Messenger.register(
            username: try NormalizedUsername(name), store: store, relay: relay.makeClient())
    }

    @Test("registration uploads keys, solves PoW and sets an access key (§4.3)")
    func registration() async throws {
        let alice = try await register("alice_01")
        let account = await alice.account
        let stored = try #require(relay.account(account.accountId))
        #expect(stored.usernameHash == (try NormalizedUsername("alice_01").hash()))
        #expect(stored.oneTime.count == 100)
        #expect(stored.kyberOneTime.count == 100)
        #expect(stored.accessKeyHash == AccessKey.hash(account.accessKey))
        #expect(account.authToken.count == 32)

        let status = try await alice.relay.keyStatus()
        #expect(status.signedPreKeyId == stored.signedPreKey.keyId)

        await #expect(throws: RelayError.server(status: 409, code: .usernameTaken, message: "error")) {
            _ = try await register("ALICE_01")
        }
    }

    @Test("a message request, its acceptance, and access-key delivery afterwards (§6.4)")
    func messageRequestFlow() async throws {
        let alice = try await register("alice_01")
        let bob = try await register("bob_02")
        let aliceId = await alice.account.accountId
        let bobId = await bob.account.accountId

        let found = try #require(try await alice.findAccount(username: try NormalizedUsername("Bob_02")))
        #expect(found.accountId == bobId)
        #expect(try await alice.findAccount(username: try NormalizedUsername("nobody")) == nil)

        // Alice writes first: PoW plus her username.
        try await alice.send(.text("Merhaba Bob"), to: bobId, requestState: .outgoingRequest, theirAccessKey: nil)
        let send = try #require(relay.requests.last { $0.method == "PUT" && $0.path.hasPrefix("/v1/messages/") })
        #expect(send.header("X-PoW") != nil)
        #expect(send.header("X-Auth-Token") == nil)

        let request = try #require(try await bob.receiveAll().first)
        #expect(request.sender == aliceId)
        #expect(request.senderUsername?.value == "alice_01")
        #expect(request.content.textBody == "Merhaba Bob")
        #expect(MessageRequestPolicy.state(afterReceiving: request.content, current: nil) == .incomingRequest)
        #expect(relay.account(bobId)?.mailbox.isEmpty == true)  // acknowledged

        // Bob accepts: ACCEPT + his access key, by PoW since he has no key of hers yet.
        let bobKey = await bob.account.accessKey
        for content in MessageRequestPolicy.acceptanceMessages(ourAccessKey: bobKey) {
            try await bob.send(content, to: aliceId, requestState: .accepted, theirAccessKey: nil)
        }
        let acceptance = try await alice.receiveAll()
        #expect(acceptance.count == 2)
        #expect(
            MessageRequestPolicy.state(afterReceiving: acceptance[0].content, current: .outgoingRequest) == .accepted)
        let receivedKey = acceptance[1].content.control.accessKey.accessKey
        #expect(receivedKey == bobKey)
        #expect(acceptance.allSatisfy { $0.senderUsername == nil })

        // Alice answers with her own key; from now on both sides use access keys.
        let aliceKey = await alice.account.accessKey
        for content in MessageRequestPolicy.messagesAfterAcceptance(ourAccessKey: aliceKey) {
            try await alice.send(content, to: bobId, requestState: .accepted, theirAccessKey: receivedKey)
        }
        let keyMessage = try #require(try await bob.receiveAll().first)
        let aliceKeyAtBob = keyMessage.content.control.accessKey.accessKey
        #expect(aliceKeyAtBob == aliceKey)

        try await bob.send(.text("Hoş geldin"), to: aliceId, requestState: .accepted, theirAccessKey: aliceKeyAtBob)
        let reply = try #require(relay.requests.last { $0.method == "PUT" && $0.path.hasPrefix("/v1/messages/") })
        #expect(reply.header("X-Access-Key") == aliceKey.base64URLEncodedString())
        #expect(try await alice.receiveAll().map(\.content.textBody) == ["Hoş geldin"])

        await #expect(throws: MessengerError.blocked) {
            try await alice.send(.text("x"), to: bobId, requestState: .blocked, theirAccessKey: receivedKey)
        }
    }

    @Test("a wrong access key is refused by the relay")
    func wrongAccessKey() async throws {
        let alice = try await register("alice_01")
        let bob = try await register("bob_02")
        await #expect(throws: RelayError.server(status: 403, code: .accessDenied, message: "error")) {
            try await alice.send(
                .text("hi"), to: await bob.account.accountId, authorization: .accessKey(AccessKey.generate()))
        }
    }

    @Test("every content type survives the full pipeline")
    func allContentTypes() async throws {
        let alice = try await register("alice_01")
        let bob = try await register("bob_02")
        let bobId = await bob.account.accountId
        let contents = ContentCases.all.map(\.content)
        for content in contents {
            try await alice.send(content, to: bobId, authorization: .proofOfWork)
        }
        let received = try await bob.receiveAll()
        #expect(received.map(\.content) == contents)
    }

    @Test("attachments: encrypt, upload, send, download, verify, delete (§7, §4.9)")
    func attachments() async throws {
        let alice = try await register("alice_01")
        let bob = try await register("bob_02")
        let bobId = await bob.account.accountId
        let directory = try TemporaryDirectory()
        let photo = Data.pattern(150_000, seed: 3)
        try photo.write(to: directory.file("photo.jpg"))

        var attachment = try await alice.uploadAttachment(
            fileAt: directory.file("photo.jpg"), kind: .image, contentType: "image/jpeg")
        attachment.width = 640
        attachment.height = 480
        #expect(relay.mediaCount() == 1)
        try await alice.send(
            BRPContent(data: BRPDataMessage(payload: .attachment(attachment))), to: bobId, authorization: .proofOfWork)

        let message = try #require(try await bob.receiveAll().first)
        let received = message.content.data.attachment
        #expect(received.size == 150_000)
        try await bob.downloadAttachment(received, to: directory.file("received.jpg"))
        #expect(try Data(contentsOf: directory.file("received.jpg")) == photo)
        #expect(relay.mediaCount() == 0)

        // A tampered digest is caught before anything is written.
        var forged = received
        forged.digest = Data(count: 32)
        let blob = try await alice.uploadAttachment(
            fileAt: directory.file("photo.jpg"), kind: .image, contentType: "image/jpeg")
        forged.mediaID = blob.mediaID
        forged.deleteToken = blob.deleteToken
        await #expect(throws: AttachmentError.integrityCheckFailed) {
            try await bob.downloadAttachment(forged, to: directory.file("forged.jpg"))
        }
        #expect(!FileManager.default.fileExists(atPath: directory.file("forged.jpg").path))

        // A declared size too small for the blob stops the download (§7); absurd sizes are refused.
        var lying = forged
        lying.digest = received.digest
        lying.size = 10
        await #expect(throws: AttachmentError.sizeMismatch) {
            try await bob.downloadAttachment(lying, to: directory.file("lying.jpg"))
        }
        lying.size = (1 << 40) + 1
        await #expect(throws: AttachmentError.sizeMismatch) {
            try await bob.downloadAttachment(lying, to: directory.file("lying.jpg"))
        }
        #expect(!FileManager.default.fileExists(atPath: directory.file("lying.jpg").path))
    }

    @Test("ephemeral sends need the access key, are never stored and reveal nothing (§4.6)")
    func ephemeral() async throws {
        let alice = try await register("alice_01")
        let bob = try await register("bob_02")
        let bobId = await bob.account.accountId
        let bobKey = await bob.account.accessKey
        let typing = BRPContent(typingStarted: true)
        let before = relay.requests.count
        await #expect(throws: MessengerError.ephemeralRequiresAccessKey) {
            try await alice.send(typing, to: bobId, authorization: .proofOfWork, ephemeral: true)
        }
        #expect(relay.requests.count == before)

        let offline = try await alice.send(typing, to: bobId, authorization: .accessKey(bobKey), ephemeral: true)
        let wrongKey = try await alice.send(
            typing, to: bobId, authorization: .accessKey(AccessKey.generate()), ephemeral: true)
        #expect(offline.id.count == wrongKey.id.count)  // same shape either way
        #expect(relay.requests.last { $0.method == "PUT" }?.url.query == "ephemeral=1")
        #expect(try await bob.receiveAll().isEmpty)

        relay.setOnline(bobId)
        try await alice.send(typing, to: bobId, authorization: .accessKey(bobKey), ephemeral: true)
        let live = relay.liveDeliveries(for: bobId)
        #expect(live.count == 1)
        guard case .message(let message) = await bob.process(try #require(live.first)) else {
            Issue.record("live typing indicator did not decrypt")
            return
        }
        #expect(message.content.typing.started)
    }

    @Test("presence goes out in one anonymous batch, only to contacts with a session (§8.6)")
    func presence() async throws {
        let alice = try await register("alice_01")
        let bob = try await register("bob_02")
        let carol = try await register("carol_03")
        let dave = try await register("dave_04")
        let aliceId = await alice.account.accountId
        let bobId = await bob.account.accountId
        let carolId = await carol.account.accountId
        let daveId = await dave.account.accountId
        let contacts = [
            (bobId, await bob.account.accessKey), (carolId, await carol.account.accessKey),
            (daveId, await dave.account.accessKey),
        ]
        for (contact, key) in contacts.prefix(2) {
            try await alice.send(.text("hi"), to: contact, authorization: .accessKey(key))
        }
        relay.setOnline(bobId)

        let before = relay.requests.count
        let addressed = try await alice.sendPresence(PresencePolicy.online(), to: contacts.map { ($0.0, $0.1) })
        #expect(Set(addressed) == [bobId, carolId])  // no session with Dave: no bundle fetched for him
        let sent = relay.requests[before...]
        #expect(sent.count == 1)
        #expect(sent.first?.path == "/v1/messages/ephemeral")
        #expect(sent.first?.header("X-Auth-Token") == nil)

        #expect(try await bob.receiveAll().map(\.content.textBody) == ["hi"])
        let live = try #require(relay.liveDeliveries(for: bobId).first)
        guard case .message(let message) = await bob.process(live) else {
            Issue.record("presence did not decrypt")
            return
        }
        let presence = message.content.presence
        #expect(presence.state == .online && presence.validForSeconds == 300 && !presence.reply)
        #expect(PresencePolicy.shouldReply(to: presence, eligible: true))
        var tracker = PresenceTracker()
        tracker.record(presence, from: aliceId)
        #expect(tracker.status(of: aliceId) == .active)

        // Carol's app is closed: nothing reaches her and nothing is stored.
        #expect(relay.liveDeliveries(for: carolId).isEmpty)
        #expect(try await carol.receiveAll().map(\.content.textBody) == ["hi"])
        await #expect(throws: MessengerError.self) { try await alice.sendPresence(.text("not presence"), to: []) }
    }

    @Test("undecryptable envelopes are dropped and acknowledged (§6.2)")
    func garbageIsAcknowledged() async throws {
        let bob = try await register("bob_02")
        let bobId = await bob.account.accountId
        let anonymous = relay.makeClient()
        for garbage in [Data([0x01, 0x02, 0x03]), Data([0x02]) + Data(count: 100), Data()] {
            let challenge = try await anonymous.powChallenge(for: .message(to: bobId))
            _ = try await anonymous.sendMessage(
                garbage, to: bobId, authorization: .proofOfWork(try await ProofOfWork.solve(challenge)))
        }
        #expect(relay.account(bobId)?.mailbox.count == 3)
        #expect(try await bob.receiveAll().isEmpty)
        #expect(relay.account(bobId)?.mailbox.isEmpty == true)
    }

    @Test("transient verification failures leave the envelope on the relay")
    func retryLater() async throws {
        let alice = try await register("alice_01")
        let bobStore = InMemoryProtocolStore(identity: .generate(), registrationId: 7)
        let failing = FakeDirectory()
        await failing.setFailure(RelayError.network(.notConnectedToInternet))
        let bob = try await Messenger.register(
            username: try NormalizedUsername("bob_02"), store: bobStore, relay: relay.makeClient(), directory: failing)
        let bobId = await bob.account.accountId
        try await alice.send(.text("hi"), to: bobId, authorization: .proofOfWork)

        #expect(try await bob.receiveAll().isEmpty)
        #expect(relay.account(bobId)?.mailbox.count == 1)
    }

    @Test("a forged sender is dropped: the PreKey identity must match the relay (§6.3)")
    func forgedSender() async throws {
        let alice = try await register("alice_01")
        let bob = try await register("bob_02")
        let aliceId = await alice.account.accountId
        let bobId = await bob.account.accountId
        // Mallory claims to be Alice, with her own identity key.
        let mallory = TestParty(account: aliceId)
        try mallory.cipher.processBundle(try await alice.relay.preKeyBundle(for: bobId))
        let envelope = try mallory.cipher.seal(.text("I am Alice"), for: bobId)
        let result = await bob.process(RelayEnvelope(id: "x", serverTimestamp: 0, envelope: envelope))
        guard case .dropped(.sender(.identityMismatch)) = result else {
            Issue.record("expected identityMismatch, got \(result)")
            return
        }
    }

    @Test("safety numbers agree on both phones")
    func safetyNumbers() async throws {
        let alice = try await register("alice_01")
        let bob = try await register("bob_02")
        let aliceId = await alice.account.accountId
        let bobId = await bob.account.accountId
        #expect(try await alice.safetyNumber(with: bobId) == nil)
        try await alice.send(.text("hi"), to: bobId, authorization: .proofOfWork)
        _ = try await bob.receiveAll()
        let a = try #require(try await alice.safetyNumber(with: bobId))
        let b = try #require(try await bob.safetyNumber(with: aliceId))
        #expect(a.digits == b.digits)
        #expect(try a.matches(scanned: b.scannable))
    }

    @Test("pre-key upkeep: refill below 20, rotate after 30 days, drop replaced keys 30 days later (§5)")
    func preKeyMaintenance() async throws {
        let bob = try await register("bob_02")
        let bobId = await bob.account.accountId
        relay.update(bobId) {
            $0.oneTime.removeLast(85)
            $0.kyberOneTime.removeLast(90)
        }

        let now = Date()
        try await bob.performMaintenance(now: now)
        #expect(relay.account(bobId)?.oneTime.count == 100)
        #expect(relay.account(bobId)?.kyberOneTime.count == 100)
        let firstSigned = try #require(relay.account(bobId)?.signedPreKey.keyId)

        try await bob.performMaintenance(now: now.addingTimeInterval(31 * 86400))
        let secondSigned = try #require(relay.account(bobId)?.signedPreKey.keyId)
        #expect(secondSigned != firstSigned)
        #expect(relay.account(bobId)?.kyberLastResort.keyId != nil)
        #expect(try bob.store.signedPreKeys().count == 2)  // the old one stays for late messages
        #expect(try bob.store.lastResortKyberPreKeys().count == 2)

        try await bob.performMaintenance(now: now.addingTimeInterval(62 * 86400))
        #expect(try bob.store.signedPreKeys().map(\.id).contains(firstSigned) == false)
        // Rotated again at day 62; the key replaced less than 30 days ago stays.
        #expect(try bob.store.lastResortKyberPreKeys().count == 2)
    }

    @Test("backup and restore keep the identity; the old phone loses access (§5.5)")
    func restore() async throws {
        let alice = try await register("alice_01")
        let bob = try await register("bob_02")
        let aliceId = await alice.account.accountId
        let bobId = await bob.account.accountId
        try await alice.send(.text("hi"), to: bobId, authorization: .proofOfWork)
        _ = try await bob.receiveAll()

        // The app supplies its contact fields; the identity fields come from the store.
        try await bob.markVerified(true, contact: aliceId)
        var contact = BRPBackupContact()
        contact.accountID = aliceId.bytes
        contact.username = "alice_01"
        contact.presenceSharing = true
        let backup = try await bob.makeBackup(displayName: "Bob", contacts: [contact])
        let aliceKey = try #require(try bob.store.identity(for: aliceId.protocolAddress, context: NullContext()))
        #expect(backup.contacts.first?.identityKey == aliceKey.serialize())
        #expect(backup.contacts.first?.verified == true)
        #expect(backup.contacts.first?.identityUnaccepted == false)
        #expect(backup.contacts.first?.presenceSharing == true)
        let file = try AccountBackupFile.export(
            backup, password: "yedek parolam 2026", salt: .pattern(16), iterations: 1000, nonce: .pattern(12))

        let restored = try await Messenger.restore(
            from: try AccountBackupFile.import(file, password: "yedek parolam 2026"), relay: relay.makeClient(),
            makeStore: { InMemoryProtocolStore(identity: $0, registrationId: $1) })
        #expect(await restored.account.accountId == bobId)
        #expect(try restored.store.identityTrust(for: aliceId) == .trusted(aliceKey, verified: true))
        await #expect(throws: RelayError.self) { try await bob.relay.keyStatus() }  // old token revoked
        #expect(relay.account(bobId)?.oneTime.count == 100)
        #expect(await restored.account.pendingSetup == [.announceProfile])

        // The restored phone re-establishes sessions by writing first: its profile, to every contact.
        let unreached = try await restored.announceProfile(
            .profileUpdate(displayName: "Bob", username: try NormalizedUsername("bob_02"), presenceSharing: true),
            to: [(aliceId, nil)])
        #expect(unreached.isEmpty)
        #expect(await restored.account.pendingSetup.isEmpty)
        let update = try #require(try await alice.receiveAll().first)
        #expect(!update.identityChanged)  // same identity key: no safety-number change
        try await alice.send(.text("welcome back"), to: bobId, authorization: .proofOfWork)
        #expect(try await restored.receiveAll().map(\.content.textBody) == ["welcome back"])
    }

    @Test("a failure after registration keeps the credentials; maintenance finishes the setup")
    func pendingSetupAfterRegistration() async throws {
        relay.failNext("PUT", "/v1/accounts/me/access-key")
        let alice = try await register("alice_01")
        let account = await alice.account
        #expect(account.pendingSetup == [.publishAccessKey])
        #expect(relay.account(account.accountId)?.accessKeyHash == nil)

        try await alice.performMaintenance()
        #expect(await alice.account.pendingSetup.isEmpty)
        #expect(relay.account(account.accountId)?.accessKeyHash == AccessKey.hash(account.accessKey))
    }

    @Test("a failed key upload after a restore is retried by maintenance")
    func pendingSetupAfterRestore() async throws {
        let bob = try await register("bob_02")
        let bobId = await bob.account.accountId
        let backup = try await bob.makeBackup(displayName: "Bob", contacts: [])
        relay.update(bobId) { $0.oneTime.removeAll() }
        relay.failNext("PUT", "/v1/keys")

        let restored = try await Messenger.restore(
            from: backup, relay: relay.makeClient(),
            makeStore: { InMemoryProtocolStore(identity: $0, registrationId: $1) })
        #expect(await restored.account.pendingSetup == [.replacePreKeys, .announceProfile])
        #expect(relay.account(bobId)?.oneTime.isEmpty == true)

        try await restored.performMaintenance()
        #expect(await restored.account.pendingSetup == [.announceProfile])  // needs the app's contacts
        #expect(relay.account(bobId)?.oneTime.count == 100)
        #expect(try await restored.relay.keyStatus().signedPreKeyId != nil)
    }

    @Test("a connected client retries a failed key upload with every pong (§5.5)")
    func pendingSetupRetriedWhileConnected() async throws {
        let bob = try await register("bob_02")
        let bobId = await bob.account.accountId
        let backup = try await bob.makeBackup(displayName: "Bob", contacts: [])
        relay.failNext("PUT", "/v1/keys")
        let restored = try await Messenger.restore(
            from: backup, relay: relay.makeClient(),
            makeStore: { InMemoryProtocolStore(identity: $0, registrationId: $1) })
        relay.failNext("PUT", "/v1/keys")
        try await restored.handle(.queueEmpty, deliver: { _ in }, onQueueEmpty: {}, acknowledge: { _ in })
        #expect(await restored.account.pendingSetup.contains(.replacePreKeys))
        try await restored.handle(.pong, deliver: { _ in }, onQueueEmpty: {}, acknowledge: { _ in })
        #expect(await restored.account.pendingSetup == [.announceProfile])
        #expect(relay.account(bobId)?.oneTime.count == 100)
        let uploads = relay.requests.filter { $0.method == "PUT" && $0.path == "/v1/keys" }.count
        try await restored.handle(.pong, deliver: { _ in }, onQueueEmpty: {}, acknowledge: { _ in })
        #expect(relay.requests.filter { $0.method == "PUT" && $0.path == "/v1/keys" }.count == uploads)
    }

    @Test("the rotated token is saved before the key upload (§5.5 step 3)")
    func restoreSavesTokenFirst() async throws {
        let bob = try await register("bob_02")
        let backup = try await bob.makeBackup(displayName: "Bob", contacts: [])
        let saves = Locked<[(account: LocalAccount, uploadsBefore: Int)]>([])
        let relay = self.relay
        let restored = try await Messenger.restore(
            from: backup, relay: relay.makeClient(),
            makeStore: { InMemoryProtocolStore(identity: $0, registrationId: $1) },
            saveAccount: { account in
                let uploads = relay.requests.filter { $0.method == "PUT" && $0.path == "/v1/keys" }.count
                saves.withLock { $0.append((account, uploads)) }
            })
        let first = try #require(saves.withLock { $0.first })
        #expect(first.uploadsBefore == 0)
        #expect(relay.requests.filter { $0.method == "PUT" && $0.path == "/v1/keys" }.count == 1)
        #expect(first.account.authToken != backup.authToken)
        #expect(first.account.pendingSetup == [.replacePreKeys, .announceProfile])
        #expect(saves.withLock { $0.last?.account } == (await restored.account))
    }

    @Test("an unaccepted safety-number change survives backup and restore (§5.5)")
    func unacceptedIdentityChange() async throws {
        let alice = try await register("alice_01")
        let bob = try await register("bob_02")
        let aliceId = await alice.account.accountId
        try await alice.send(.text("hi"), to: await bob.account.accountId, authorization: .proofOfWork)
        _ = try await bob.receiveAll()
        // Alice's key changes on Bob's phone; Bob has not accepted it.
        let changed = IdentityKeyPair.generate().identityKey
        _ = try bob.store.saveIdentity(changed, for: aliceId.protocolAddress, context: NullContext())

        var contact = BRPBackupContact()
        contact.accountID = aliceId.bytes
        let backup = try await bob.makeBackup(displayName: "Bob", contacts: [contact])
        #expect(backup.contacts.first?.identityUnaccepted == true)
        #expect(backup.contacts.first?.identityKey == changed.serialize())
        #expect(backup.contacts.first?.verified == false)

        let restored = try await Messenger.restore(
            from: backup, relay: relay.makeClient(),
            makeStore: { InMemoryProtocolStore(identity: $0, registrationId: $1) })
        #expect(try restored.store.identityTrust(for: aliceId) == .changed(previous: nil, current: changed))
        await #expect(throws: SessionError.identityChanged(aliceId)) {
            try await restored.send(.text("blocked"), to: aliceId, authorization: .proofOfWork)
        }
    }

    @Test(
        "a malformed backup is refused before the relay is contacted (§5.5 step 1)",
        arguments: [
            { (b: inout BRPAccountBackup) in b.contacts[0].theirAccessKey = Data(count: 15) },
            { (b: inout BRPAccountBackup) in b.contacts[0].username = "Alice_01" },
            { (b: inout BRPAccountBackup) in b.contacts[0].identityKey = Data(b.contacts[0].identityKey.prefix(32)) },
            { (b: inout BRPAccountBackup) in b.contacts[0].identityKey = b.contacts[0].identityKey + [0] },
            { (b: inout BRPAccountBackup) in
                b.contacts[0].identityKey = Data()
                b.contacts[0].identityUnaccepted = true
            },
            { (b: inout BRPAccountBackup) in b.contacts[0].accountID = Data(count: 15) },
            { (b: inout BRPAccountBackup) in b.contacts[0].displayName = String(repeating: "x", count: 65) },
            { (b: inout BRPAccountBackup) in b.contacts.append(b.contacts[0]) },
            { (b: inout BRPAccountBackup) in b.contacts[0].accountID = b.accountID },
            { (b: inout BRPAccountBackup) in b.displayName = String(repeating: "ğ", count: 33) },
            { (b: inout BRPAccountBackup) in b.version = 2 },
        ] as [@Sendable (inout BRPAccountBackup) -> Void])
    func malformedBackup(_ breakIt: @Sendable (inout BRPAccountBackup) -> Void) async throws {
        let alice = try await register("alice_01")
        let bob = try await register("bob_02")
        try await alice.send(.text("hi"), to: await bob.account.accountId, authorization: .proofOfWork)
        _ = try await bob.receiveAll()
        var contact = BRPBackupContact()
        contact.accountID = (await alice.account.accountId).bytes
        contact.username = "alice_01"
        var backup = try await bob.makeBackup(displayName: "Bob", contacts: [contact])
        try Messenger.validate(backup)
        breakIt(&backup)
        let before = relay.requests.count
        await #expect(throws: MessengerError.invalidBackup) {
            _ = try await Messenger.restore(
                from: backup, relay: relay.makeClient(),
                makeStore: { InMemoryProtocolStore(identity: $0, registrationId: $1) })
        }
        #expect(relay.requests.count == before)
        _ = try await bob.relay.keyStatus()  // the old phone keeps its token
    }

    @Test func usernameChange() async throws {
        let alice = try await register("alice_01")
        try await alice.changeUsername(to: try NormalizedUsername("alice_new"))
        #expect(await alice.account.username.value == "alice_new")
        let bob = try await register("bob_02")
        #expect(
            try await bob.findAccount(username: try NormalizedUsername("alice_new"))?.accountId
                == (await alice.account.accountId))
    }

    @Test("an attachment above the media limit is refused from its file size, before encryption")
    func attachmentLimit() async throws {
        var configuration = Messenger.Configuration()
        configuration.maxMediaBytes = 4096
        // Staging a blob here would fail with an I/O error, so the expected
        // error proves that nothing was encrypted.
        configuration.temporaryDirectory = URL(fileURLWithPath: "/nonexistent-us2us-staging")
        let store = InMemoryProtocolStore(identity: .generate(), registrationId: LocalIdentity.generateRegistrationId())
        let alice = try await Messenger.register(
            username: try NormalizedUsername("alice_01"), store: store, relay: relay.makeClient(),
            configuration: configuration)
        let directory = try TemporaryDirectory()
        try Data.pattern(4000).write(to: directory.file("big.bin"))  // pads and grows past 4096
        let before = relay.requests.count
        await #expect(throws: MessengerError.attachmentTooLarge) {
            try await alice.uploadAttachment(
                fileAt: directory.file("big.bin"), kind: .file, contentType: "application/octet-stream")
        }
        #expect(relay.requests.count == before)
    }

    @Test("oversized content is rejected before anything is sent")
    func limits() async throws {
        let alice = try await register("alice_01")
        let bob = try await register("bob_02")
        let before = relay.requests.count
        await #expect(throws: MessengerError.invalidContent(.tooLarge("text.body"))) {
            try await alice.send(
                .text(String(repeating: "x", count: 9000)), to: await bob.account.accountId, authorization: .proofOfWork
            )
        }
        #expect(relay.requests.count == before)
    }
}
