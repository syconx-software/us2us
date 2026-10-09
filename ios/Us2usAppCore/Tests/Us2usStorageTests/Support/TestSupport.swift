// us2us — AGPL-3.0-only

import Foundation
import LibSignalClient
import Testing
import Us2usKit

@testable import Us2usStorage

/// A per-test storage root with its own random DEK, removed afterwards.
final class TestEnvironment: @unchecked Sendable {
    let directory: URL
    let layout: StorageLayout
    let dek: Data

    init(dek: Data = TestEnvironment.randomKey()) throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(
            "us2us-storage-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        layout = StorageLayout(root: directory.appendingPathComponent("storage", isDirectory: true))
        self.dek = dek
    }

    deinit {
        try? FileManager.default.removeItem(at: directory)
    }

    static func randomKey() -> Data {
        var generator = SystemRandomNumberGenerator()
        return Data((0..<32).map { _ in UInt8.random(in: 0...255, using: &generator) })
    }

    func open(now: Date = Date()) throws -> LocalStorage {
        try LocalStorage.open(layout: layout, dek: dek, now: now).storage
    }

    func openReport(now: Date = Date()) throws -> LocalStorage.Opened {
        try LocalStorage.open(layout: layout, dek: dek, now: now)
    }

    /// All bytes of the database and its WAL (what an attacker copying the
    /// container would get).
    func databaseBytes() -> Data {
        layout.databaseFiles.reduce(into: Data()) { result, url in
            if let data = try? Data(contentsOf: url) { result.append(data) }
        }
    }

    func copyDatabase(to directory: URL) throws -> URL {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        for url in layout.databaseFiles where FileManager.default.fileExists(atPath: url.path) {
            try FileManager.default.copyItem(at: url, to: directory.appendingPathComponent(url.lastPathComponent))
        }
        return directory.appendingPathComponent(layout.databaseURL.lastPathComponent)
    }
}

/// A local account with identity, as after registration.
struct TestAccount {
    let storage: LocalStorage
    let accountId: AccountId
    let store: PersistentProtocolStore

    init(storage: LocalStorage, accountId: AccountId = AccountId(uuid: UUID()), identity: IdentityKeyPair = .generate())
        throws
    {
        self.storage = storage
        self.accountId = accountId
        self.store = try storage.protocolStore
            ?? storage.createIdentity(identity, registrationId: LocalIdentity.generateRegistrationId())
        if try storage.read({ try $0.account.load() }) == nil {
            let account = LocalAccount(
                accountId: accountId, authToken: Data(repeating: 7, count: 32),
                username: try NormalizedUsername("tester_\(Int.random(in: 100...999))"),
                accessKey: Data(repeating: 9, count: 16))
            try storage.accountSaver(account)
        }
    }

    var cipher: SessionCipher { SessionCipher(localAccount: accountId, store: store) }
}

/// One side of a conversation without a relay, on a given store.
struct Party {
    let account: AccountId
    let store: any ProtocolStore
    let cipher: SessionCipher

    init(account: AccountId = AccountId(uuid: UUID()), store: any ProtocolStore) {
        self.account = account
        self.store = store
        self.cipher = SessionCipher(localAccount: account, store: store)
    }

    static func inMemory(account: AccountId = AccountId(uuid: UUID()), identity: IdentityKeyPair = .generate()) -> Party {
        Party(
            account: account,
            store: InMemoryProtocolStore(identity: identity, registrationId: LocalIdentity.generateRegistrationId()))
    }

    var identity: IdentityKeyPair { try! store.identityKeyPair(context: NullContext()) }

    /// What the relay would serve for this party (§4.5), freshly generated.
    func bundle(withOneTimeKeys: Bool = true, kyberOneTime: Bool = true) throws -> PreKeyBundleResponse {
        let generator = PreKeyGenerator(store: store)
        let signed = try generator.generateSignedPreKey()
        let kyber =
            kyberOneTime
            ? try generator.generateOneTimeKyberPreKeys(count: 1)[0] : try generator.generateLastResortKyberPreKey()
        let oneTime = withOneTimeKeys ? try generator.generateOneTimePreKeys(count: 1)[0] : nil
        return PreKeyBundleResponse(
            accountId: account, deviceId: 1, registrationId: try store.localRegistrationId(context: NullContext()),
            identityKey: identity.identityKey.serialize(), signedPreKey: signed, kyberPreKey: kyber,
            oneTimePreKey: oneTime)
    }

    func seal(_ content: BRPContent, to peer: Party) throws -> Data {
        try cipher.seal(content, for: peer.account)
    }

    func send(_ content: BRPContent, to peer: Party) throws -> DecryptedContent {
        try peer.receive(try seal(content, to: peer))
    }

    func receive(_ envelope: Data) throws -> DecryptedContent {
        let sealed = try SealedEnvelope.open(envelope, with: identity)
        return try cipher.decrypt(sealed)
    }
}

extension BRPContent {
    var textBody: String? {
        if case .data(let data) = body, case .text(let text) = data.payload { return text.body }
        return nil
    }
}

extension Data {
    func contains(_ needle: Data) -> Bool {
        range(of: needle) != nil
    }

    static func marker(_ label: String) -> Data { Data(label.utf8) }
}

func textMessage(_ body: String, id: Data = RandomId.make(), sentAt: Date = Date(), timer: UInt32 = 0)
    -> BRPDataMessage
{
    var text = BRPText()
    text.body = body
    return BRPDataMessage(payload: .text(text), messageId: id, sentAt: sentAt, expireTimerSeconds: timer)
}

func sampleAttachment(size: UInt64 = 1234, caption: String = "") -> BRPAttachment {
    var attachment = BRPAttachment()
    attachment.kind = .image
    attachment.mediaID = RandomId.make()
    attachment.deleteToken = Data(repeating: 3, count: 32)
    attachment.key = Data(repeating: 4, count: 64)
    attachment.digest = Data(repeating: 5, count: 32)
    attachment.size = size
    attachment.contentType = "image/jpeg"
    attachment.caption = caption
    attachment.width = 640
    attachment.height = 480
    return attachment
}

/// Creates an accepted contact with a conversation.
func makeContactWithConversation(_ storage: LocalStorage, contact: AccountId = AccountId(uuid: UUID()), now: Date = Date())
    throws -> (AccountId, Conversation)
{
    try storage.write { tx in
        try tx.contacts.create(contact, state: .accepted, now: now)
        return (contact, try tx.conversations.ensure(for: contact, now: now))
    }
}

let t0 = Date(timeIntervalSince1970: 1_800_000_000)
