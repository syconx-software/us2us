// us2us — AGPL-3.0-only

import Foundation
import LibSignalClient
import Testing
import Us2usKit

@testable import Us2usStorage

/// A relay stand-in for the two calls a restore makes (§5.5 steps 3–4).
final class RestoreRelayStub: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var log: [String] = []
    static let lock = NSLock()
    static let rotatedToken = Data(repeating: 0xAB, count: 32)

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let method = request.httpMethod ?? "GET"
        let path = request.url?.path ?? ""
        Self.lock.withLock { Self.log.append("\(method) \(path)") }
        let (status, body): (Int, Data) =
            switch (method, path) {
            case ("POST", "/v1/accounts/me/token"):
                (200, Data(#"{"authToken":"\#(Self.rotatedToken.base64URLEncodedString())"}"#.utf8))
            case ("PUT", "/v1/keys"), ("PUT", "/v1/accounts/me/access-key"):
                (204, Data())
            default:
                (500, Data())
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
        configuration.protocolClasses = [RestoreRelayStub.self]
        return RelayClient(baseURL: URL(string: "https://relay.invalid")!, configuration: configuration)
    }
}

@Suite("Account backup with the separate backup password (§5.5)", .serialized)
struct BackupTests {
    let password = "Kırmızı-Bisiklet-42"

    @Test("export → decrypt → restore on a new phone keeps identity, contacts and trust")
    func roundTrip() async throws {
        // Old phone.
        let oldEnvironment = try TestEnvironment()
        let oldStorage = try oldEnvironment.open()
        let old = try TestAccount(storage: oldStorage)
        let localAccount = try #require(try oldStorage.read { try $0.account.load() })
        let accepted = AccountId(uuid: UUID())
        let blocked = AccountId(uuid: UUID())
        let request = AccountId(uuid: UUID())
        let changed = AccountId(uuid: UUID())
        let acceptedKey = IdentityKeyPair.generate().identityKey
        let changedKey = IdentityKeyPair.generate().identityKey
        try oldStorage.write { tx throws in
            try tx.contacts.create(accepted, state: .accepted)
            try tx.contacts.create(blocked, state: .accepted)
            try tx.contacts.block(blocked)
            try tx.contacts.create(request, state: .incomingRequest)
            try tx.contacts.create(changed, state: .accepted)
            try tx.contacts.setTheirAccessKey(Data(repeating: 1, count: 16), for: accepted)
            var profile = ContactProfile(
                username: try NormalizedUsername("ayse_k"), usernameStatus: .verified, displayName: "Ayşe",
                presenceSharing: true)
            profile.updatedAt = 10
            try tx.contacts.setProfile(profile, for: accepted)
        }
        _ = try old.store.saveIdentity(acceptedKey, for: accepted.protocolAddress, context: NullContext())
        try old.store.setIdentityVerified(true, for: accepted)
        _ = try old.store.saveIdentity(IdentityKeyPair.generate().identityKey, for: changed.protocolAddress, context: NullContext())
        _ = try old.store.saveIdentity(changedKey, for: changed.protocolAddress, context: NullContext())

        let messenger = Messenger(account: localAccount, store: old.store, relay: RestoreRelayStub.relay())

        // The backup password is checked before anything slow happens.
        await #expect(throws: AccountBackupService.Failure.invalidPassword(.tooShort)) {
            _ = try await AccountBackupService.export(
                messenger: messenger, storage: oldStorage, displayName: "Ben", password: "123456",
                confirmation: "123456")
        }
        await #expect(throws: AccountBackupService.Failure.invalidPassword(.mismatch)) {
            _ = try await AccountBackupService.export(
                messenger: messenger, storage: oldStorage, displayName: "Ben", password: password,
                confirmation: password + "x")
        }
        let file = try await AccountBackupService.export(
            messenger: messenger, storage: oldStorage, displayName: "Ben", password: password, confirmation: password)
        #expect(file.prefix(5) == Data("BRPB".utf8) + [0x01])
        let staged = try AccountBackupService.writeToStaging(file, storage: oldStorage)
        #expect(try Data(contentsOf: staged) == file)
        #expect(staged.lastPathComponent.hasPrefix("us2us-backup-"))

        await #expect(throws: AccountBackupError.wrongPassword) {
            _ = try await AccountBackupService.decrypt(file, password: "Yanlis-Parola-99")
        }
        let backup = try await AccountBackupService.decrypt(file, password: password)
        #expect(backup.accountID == old.accountId.bytes)
        #expect(Set(backup.contacts.compactMap { AccountId(bytes: $0.accountID) }) == [accepted, blocked, changed])
        let exportedAccepted = try #require(backup.contacts.first { $0.accountID == accepted.bytes })
        #expect(exportedAccepted.verified)
        #expect(exportedAccepted.identityKey == acceptedKey.serialize())
        #expect(exportedAccepted.presenceSharing)
        #expect(exportedAccepted.username == "ayse_k")
        #expect(backup.contacts.first { $0.accountID == changed.bytes }?.identityUnaccepted == true)

        // New phone.
        let newEnvironment = try TestEnvironment()
        let newStorage = try newEnvironment.open()
        RestoreRelayStub.lock.withLock { RestoreRelayStub.log.removeAll() }
        let restored = try await AccountBackupService.restore(backup, storage: newStorage, relay: RestoreRelayStub.relay())
        let calls = RestoreRelayStub.lock.withLock { RestoreRelayStub.log }
        #expect(calls.first == "POST /v1/accounts/me/token")
        #expect(calls.contains("PUT /v1/keys"))

        let saved = try #require(try newStorage.read { try $0.account.load() })
        #expect(saved.accountId == old.accountId)
        #expect(saved.authToken == RestoreRelayStub.rotatedToken)
        #expect(saved.pendingSetup.contains(.announceProfile))
        #expect(!saved.pendingSetup.contains(.replacePreKeys))
        let store = try #require(newStorage.protocolStore)
        #expect(try store.identityKeyPair(context: NullContext()).serialize()
            == old.store.identityKeyPair(context: NullContext()).serialize())
        #expect(await restored.account.accountId == old.accountId)
        #expect(try store.oneTimePreKeyCount() == 100)

        try newStorage.read { tx throws in
            let acceptedContact = try #require(try tx.contacts.get(accepted))
            #expect(acceptedContact.requestState == .accepted)
            #expect(acceptedContact.identity == .trusted(acceptedKey, verified: true))
            #expect(acceptedContact.theirAccessKey == Data(repeating: 1, count: 16))
            #expect(acceptedContact.ourAccessKeySent == localAccount.accessKey)
            #expect(acceptedContact.profile.displayName == "Ayşe")
            #expect(acceptedContact.profile.usernameStatus == .unchecked)
            #expect(acceptedContact.presenceSharing)
            #expect(try tx.contacts.get(blocked)?.isBlocked == true)
            #expect(try tx.contacts.get(changed)?.identity == .changed(previous: nil, current: changedKey))
            #expect(try tx.contacts.get(request) == nil)
        }
    }
}
