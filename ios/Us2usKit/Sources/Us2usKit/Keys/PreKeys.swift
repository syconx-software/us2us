import Foundation
import LibSignalClient

/// `{ "keyId", "publicKey", "signature" }` (PROTOCOL.md §4.3): a signed EC
/// pre-key or a Kyber pre-key (one-time or last-resort).
public struct SignedPreKeyEntity: Codable, Sendable, Equatable {
    public let keyId: UInt32
    public let publicKey: Data
    public let signature: Data

    public init(keyId: UInt32, publicKey: Data, signature: Data) {
        self.keyId = keyId
        self.publicKey = publicKey
        self.signature = signature
    }

    init(_ record: SignedPreKeyRecord) throws {
        self.init(keyId: record.id, publicKey: try record.publicKey().serialize(), signature: record.signature)
    }

    init(_ record: KyberPreKeyRecord) throws {
        self.init(keyId: record.id, publicKey: try record.publicKey().serialize(), signature: record.signature)
    }
}

/// `{ "keyId", "publicKey" }`: a one-time EC pre-key.
public struct PreKeyEntity: Codable, Sendable, Equatable {
    public let keyId: UInt32
    public let publicKey: Data

    public init(keyId: UInt32, publicKey: Data) {
        self.keyId = keyId
        self.publicKey = publicKey
    }
}

/// Long-term identity material created once per account (PROTOCOL.md §5).
public enum LocalIdentity {
    public static let registrationIdRange: ClosedRange<UInt32> = 1...16380

    public static func generateKeyPair() -> IdentityKeyPair { .generate() }

    public static func generateRegistrationId() -> UInt32 {
        UInt32.random(in: registrationIdRange)  // SystemRandomNumberGenerator is a CSPRNG
    }
}

/// Creates pre-keys, stores their private parts and returns the public parts
/// for upload (PROTOCOL.md §5). Signatures are the identity key's XEdDSA over
/// the serialized public key, type byte included.
public struct PreKeyGenerator: Sendable {
    /// One-time keys of each type kept on the relay.
    public static let oneTimeBatchSize = 100

    private let store: any ProtocolStore

    public init(store: any ProtocolStore) {
        self.store = store
    }

    private func identity() throws -> IdentityKeyPair {
        try store.identityKeyPair(context: NullContext())
    }

    public func generateSignedPreKey(now: Date = Date()) throws -> SignedPreKeyEntity {
        let id = try reserveIds(.signed, 1)[0]
        let privateKey = PrivateKey.generate()
        let signature = try identity().privateKey.generateSignature(message: privateKey.publicKey.serialize())
        let record = try SignedPreKeyRecord(
            id: id, timestamp: now.millisecondsSince1970, privateKey: privateKey, signature: signature)
        try store.storeSignedPreKey(record, id: id, context: NullContext())
        return try SignedPreKeyEntity(record)
    }

    public func generateLastResortKyberPreKey(now: Date = Date()) throws -> SignedPreKeyEntity {
        let record = try makeKyberPreKey(id: try reserveIds(.kyber, 1)[0], now: now)
        try store.storeKyberPreKey(record, id: record.id, lastResort: true)
        return try SignedPreKeyEntity(record)
    }

    public func generateOneTimePreKeys(count: Int = oneTimeBatchSize) throws -> [PreKeyEntity] {
        try reserveIds(.oneTime, count).map { id in
            let privateKey = PrivateKey.generate()
            let record = try PreKeyRecord(id: id, privateKey: privateKey)
            try store.storePreKey(record, id: id, context: NullContext())
            return PreKeyEntity(keyId: id, publicKey: privateKey.publicKey.serialize())
        }
    }

    public func generateOneTimeKyberPreKeys(
        count: Int = oneTimeBatchSize, now: Date = Date()
    ) throws
        -> [SignedPreKeyEntity]
    {
        try reserveIds(.kyber, count).map { id in
            let record = try makeKyberPreKey(id: id, now: now)
            try store.storeKyberPreKey(record, id: id, lastResort: false)
            return try SignedPreKeyEntity(record)
        }
    }

    private func makeKyberPreKey(id: UInt32, now: Date) throws -> KyberPreKeyRecord {
        let keyPair = KEMKeyPair.generate()  // Kyber1024 in libsignal v0.105
        let signature = try identity().privateKey.generateSignature(message: keyPair.publicKey.serialize())
        return try KyberPreKeyRecord(
            id: id, timestamp: now.millisecondsSince1970, keyPair: keyPair, signature: signature)
    }

    private func reserveIds(_ space: PreKeyIdSpace, _ count: Int) throws -> [UInt32] {
        guard count > 0 else { return [] }
        return try store.reserveKeyIds(space, count: count)
    }
}
