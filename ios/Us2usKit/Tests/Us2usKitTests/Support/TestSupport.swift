import Foundation
import LibSignalClient
import Testing

@testable import Us2usKit

/// Paths inside the repository. Simulator test processes can read and write
/// the host file system, which the test vectors rely on.
enum RepositoryPaths {
    static let root: URL = {
        // .../ios/Us2usKit/Tests/Us2usKitTests/Support/TestSupport.swift
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<6 { url.deleteLastPathComponent() }
        return url
    }()

    static var testVectors: URL { root.appendingPathComponent("spec/test-vectors") }
    static var commonSecretCodes: URL { root.appendingPathComponent("spec/common-secret-codes.txt") }
    static var commonBackupPasswords: URL { root.appendingPathComponent("spec/common-backup-passwords.txt") }
}

extension Data {
    /// Hex literal for tests; traps on malformed input.
    init(hex: String) {
        self = Data(hexString: hex)!
    }

    /// 16 bytes all equal to `n` — the `id(n)` of spec/test-vectors/README.md.
    static func id(_ n: UInt8) -> Data { Data(repeating: n, count: 16) }

    /// Deterministic, non-trivial bytes for fixtures.
    static func pattern(_ count: Int, seed: UInt8 = 0) -> Data {
        Data((0..<count).map { UInt8(truncatingIfNeeded: $0 &* 31 &+ Int(seed) &* 7 &+ 3) })
    }

    func flippingBit(at index: Int) -> Data {
        var copy = self
        copy[copy.startIndex + index] ^= 0x01
        return copy
    }
}

/// A key pair derived from a label, so fixtures and vectors are reproducible.
func deterministicIdentity(_ label: String) throws -> IdentityKeyPair {
    let privateKey = try PrivateKey(Data(label.utf8).sha256Digest)
    return IdentityKeyPair(publicKey: privateKey.publicKey, privateKey: privateKey)
}

/// A per-test scratch directory, removed when the value is dropped.
final class TemporaryDirectory {
    let url: URL

    init() throws {
        url = FileManager.default.temporaryDirectory.appendingPathComponent("us2us-tests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    }

    deinit {
        try? FileManager.default.removeItem(at: url)
    }

    func file(_ name: String) -> URL { url.appendingPathComponent(name) }
}

/// One side of a conversation without any relay: an account id, a store with
/// generated pre-keys, and a session cipher.
struct TestParty {
    let account: AccountId
    let store: InMemoryProtocolStore
    let cipher: SessionCipher

    init(identity: IdentityKeyPair = .generate(), account: AccountId = AccountId(uuid: UUID())) {
        self.account = account
        self.store = InMemoryProtocolStore(identity: identity, registrationId: LocalIdentity.generateRegistrationId())
        self.cipher = SessionCipher(localAccount: account, store: store)
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

    /// Seals `content` for `peer`, opens it as `peer` would, and decrypts it.
    func send(_ content: BRPContent, to peer: TestParty, username: NormalizedUsername? = nil) throws -> DecryptedContent
    {
        let envelope = try cipher.seal(content, for: peer.account, senderUsername: username)
        return try peer.receive(envelope)
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
