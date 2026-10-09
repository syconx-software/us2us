// Adversarial security / stability review (2026-10-08).
//
// Every test in this file reproduces a concrete defect and FAILS against the
// current production code; production code is untouched.

import Foundation
import LibSignalClient
import Testing
import Us2usKit

@testable import Us2usStorage

/// A relay stand-in for an interrupted restore: the token rotation works once
/// (afterwards the backup's token is revoked, as on the real relay, §4.3),
/// and the key upload of §5.5 step 4 runs a hook — here: the process dies.
final class InterruptedRestoreRelayStub: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var onKeyUpload: (@Sendable () -> Void)?
    nonisolated(unsafe) static var rotations = 0
    static let lock = NSLock()
    static let rotatedToken = Data(repeating: 0xCD, count: 32)

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let method = request.httpMethod ?? "GET"
        let path = request.url?.path ?? ""
        var status = 500
        var body = Data()
        switch (method, path) {
        case ("POST", "/v1/accounts/me/token"):
            let first = Self.lock.withLock { () -> Bool in
                Self.rotations += 1
                return Self.rotations == 1
            }
            if first {
                status = 200
                body = Data(#"{"authToken":"\#(Self.rotatedToken.base64URLEncodedString())"}"#.utf8)
            } else {
                status = 401
                body = Data(#"{"code":"unauthorized","message":"token revoked"}"#.utf8)
            }
        case ("PUT", "/v1/keys"):
            Self.lock.withLock { Self.onKeyUpload }?()
            status = 204
        case ("PUT", "/v1/accounts/me/access-key"):
            status = 204
        default:
            break
        }
        let response = HTTPURLResponse(
            url: request.url!, statusCode: status, httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    static func relay() -> RelayClient {
        let configuration = RelayClient.defaultConfiguration()
        configuration.protocolClasses = [InterruptedRestoreRelayStub.self]
        return RelayClient(baseURL: URL(string: "https://relay.invalid")!, configuration: configuration)
    }
}

@Suite("Security review: storage", .serialized)
struct SecurityReviewStorageTests {
    /// `ConversationRepository.updateSettings` stamps
    /// `max(now, current.updatedAt + 1)`. `applySettings` stores whatever
    /// `updated_at` a contact sent (any contact, also an unaccepted request:
    /// the engine applies `ConversationSettings` in every non-blocked state,
    /// and Us2usKit validates only the ghost timeout). With
    /// `updated_at = UInt64.max` the `+ 1` traps: the app crashes every
    /// time the user tries to change ghost mode or the disappearing timer of
    /// that conversation, and the sender's settings win LWW forever.
    ///
    /// The default run stops before the trap so the rest of the suite runs;
    /// `TEST_RUNNER_US2US_REVIEW_CRASH=1` executes the crashing call.
    @Test func aContactsFarFutureSettingsTimestampCannotMakeOurNextChangeCrash() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        defer { storage.close() }
        _ = try TestAccount(storage: storage)
        let (contact, conversation) = try makeContactWithConversation(storage)
        var hostile = BRPConversationSettings()
        hostile.updatedAt = UInt64.max
        try storage.write { tx in _ = try tx.conversations.applySettings(hostile, author: contact, to: conversation.id) }
        let stored = try #require(try storage.read { try $0.conversations.get(conversation.id) })
        #expect(
            stored.settings.updatedAt < UInt64.max,
            "updated_at = UInt64.max was stored; ConversationRepository.updateSettings computes updatedAt + 1 and traps")
        let reproduceCrash = ProcessInfo.processInfo.environment["US2US_REVIEW_CRASH"] == "1"
        guard stored.settings.updatedAt < UInt64.max || reproduceCrash else { return }
        try storage.write { tx in
            _ = try tx.conversations.updateSettings(of: conversation.id) { $0.defaultExpireTimerSeconds = 30 }
        }
    }

    /// `AccountBackupService.restore` commits in two steps with an
    /// irreversible relay call in between: `Messenger.restore` rotates the
    /// token and saves the account, uploads ~200 pre-keys, and only then are
    /// the contacts written in a separate transaction. If the process dies
    /// in that window (the key upload takes seconds on a mobile network), the
    /// phone keeps a working account with no contacts — accepted contacts
    /// and their access keys are gone and **blocked contacts are no longer
    /// blocked** — and nothing can redo the step: the backup's token is
    /// revoked, so restoring the same file again fails.
    @Test func anInterruptedRestoreKeepsContactsAndBlocks() async throws {
        // Old phone: an accepted and a blocked contact.
        let oldEnvironment = try TestEnvironment()
        let oldStorage = try oldEnvironment.open()
        defer { oldStorage.close() }
        let old = try TestAccount(storage: oldStorage)
        let localAccount = try #require(try oldStorage.read { try $0.account.load() })
        let friend = AccountId(uuid: UUID())
        let stalker = AccountId(uuid: UUID())
        try oldStorage.write { tx throws in
            try tx.contacts.create(friend, state: .accepted)
            try tx.contacts.setTheirAccessKey(Data(repeating: 1, count: 16), for: friend)
            try tx.contacts.create(stalker, state: .accepted)
            try tx.contacts.block(stalker)
        }
        _ = try old.store.saveIdentity(
            IdentityKeyPair.generate().identityKey, for: friend.protocolAddress, context: NullContext())
        let messenger = Messenger(account: localAccount, store: old.store, relay: InterruptedRestoreRelayStub.relay())
        let contacts = try oldStorage.read { try AccountBackupService.backupContacts($0) }
        let backup = try await messenger.makeBackup(displayName: "Ben", contacts: contacts)

        // New phone: the process dies right after the key upload.
        let newEnvironment = try TestEnvironment()
        let newStorage = try newEnvironment.open()
        InterruptedRestoreRelayStub.lock.withLock {
            InterruptedRestoreRelayStub.rotations = 0
            InterruptedRestoreRelayStub.onKeyUpload = { newStorage.close() }
        }
        defer { InterruptedRestoreRelayStub.lock.withLock { InterruptedRestoreRelayStub.onKeyUpload = nil } }
        await #expect(throws: (any Error).self) {
            _ = try await AccountBackupService.restore(backup, storage: newStorage, relay: InterruptedRestoreRelayStub.relay())
        }
        InterruptedRestoreRelayStub.lock.withLock { InterruptedRestoreRelayStub.onKeyUpload = nil }

        // Next launch.
        let reopened = try newEnvironment.open()
        defer { reopened.close() }
        let saved = try #require(try reopened.read { try $0.account.load() })
        #expect(saved.authToken == InterruptedRestoreRelayStub.rotatedToken)  // the account itself survived
        try reopened.read { tx throws in
            #expect(try tx.contacts.get(friend)?.requestState == .accepted, "the restored contacts are gone")
            #expect(try tx.contacts.get(stalker)?.isBlocked == true, "the blocked contact is no longer blocked")
        }

        // And it cannot be repaired by restoring the file again: the backup's token is dead.
        let retryEnvironment = try TestEnvironment()
        let retryStorage = try retryEnvironment.open()
        defer { retryStorage.close() }
        await #expect(throws: (any Error).self) {
            _ = try await AccountBackupService.restore(backup, storage: retryStorage, relay: InterruptedRestoreRelayStub.relay())
        }
    }
}
