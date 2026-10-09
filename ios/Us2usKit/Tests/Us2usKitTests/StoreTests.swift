import Foundation
import LibSignalClient
import Testing

@testable import Us2usKit

@Suite("InMemoryProtocolStore (§5)")
struct StoreTests {
    let store = InMemoryProtocolStore(identity: .generate(), registrationId: 42)
    let contact = AccountId(uuid: UUID())
    let context = NullContext()

    @Test("TOFU: first identity is trusted, a change is flagged and blocks sending")
    func trustOnFirstUse() throws {
        let address = contact.protocolAddress
        let first = IdentityKeyPair.generate().identityKey
        let second = IdentityKeyPair.generate().identityKey

        #expect(try store.identityTrust(for: contact) == .unknown)
        #expect(try store.isTrustedIdentity(first, for: address, direction: .sending, context: context))
        #expect(try store.saveIdentity(first, for: address, context: context) == .newOrUnchanged)
        #expect(try store.identityTrust(for: contact) == .trusted(first, verified: false))
        #expect(try store.saveIdentity(first, for: address, context: context) == .newOrUnchanged)

        #expect(try !store.isTrustedIdentity(second, for: address, direction: .sending, context: context))
        #expect(try store.isTrustedIdentity(second, for: address, direction: .receiving, context: context))
        #expect(try store.saveIdentity(second, for: address, context: context) == .replacedExisting)
        #expect(try store.identityTrust(for: contact) == .changed(previous: first, current: second))
        #expect(try !store.isTrustedIdentity(second, for: address, direction: .sending, context: context))
        #expect(try store.identity(for: address, context: context) == second)

        try store.approveIdentity(second, for: contact)
        #expect(try store.isTrustedIdentity(second, for: address, direction: .sending, context: context))
        #expect(try store.identityTrust(for: contact) == .trusted(second, verified: false))
    }

    @Test("identities restored from a backup keep their trust (§5.5)")
    func restoredIdentities() throws {
        let key = IdentityKeyPair.generate().identityKey
        try store.restoreIdentity(key, for: contact, approved: true, verified: true)
        #expect(try store.identityTrust(for: contact) == .trusted(key, verified: true))
        #expect(try store.saveIdentity(key, for: contact.protocolAddress, context: context) == .newOrUnchanged)

        // An unaccepted change: the previous key is unknown, sending stays blocked, never verified.
        let other = AccountId(uuid: UUID())
        try store.restoreIdentity(key, for: other, approved: false, verified: true)
        #expect(try store.identityTrust(for: other) == .changed(previous: nil, current: key))
        #expect(try !store.isTrustedIdentity(key, for: other.protocolAddress, direction: .sending, context: context))
        #expect(try store.isTrustedIdentity(key, for: other.protocolAddress, direction: .receiving, context: context))
        let newer = IdentityKeyPair.generate().identityKey
        #expect(try store.saveIdentity(newer, for: other.protocolAddress, context: context) == .replacedExisting)
        #expect(try store.identityTrust(for: other) == .changed(previous: nil, current: newer))
        try store.approveIdentity(newer, for: other)
        #expect(try store.identityTrust(for: other) == .trusted(newer, verified: false))
    }

    @Test("repeated changes before approval keep the last approved key as 'previous'")
    func repeatedChanges() throws {
        let address = contact.protocolAddress
        let keys = (0..<3).map { _ in IdentityKeyPair.generate().identityKey }
        for key in keys { _ = try store.saveIdentity(key, for: address, context: context) }
        #expect(try store.identityTrust(for: contact) == .changed(previous: keys[0], current: keys[2]))
    }

    @Test("a change back to the approved key is trusted again instead of asking to approve it")
    func changeBack() throws {
        let address = contact.protocolAddress
        let approved = IdentityKeyPair.generate().identityKey
        let intruder = IdentityKeyPair.generate().identityKey
        _ = try store.saveIdentity(approved, for: address, context: context)
        _ = try store.saveIdentity(intruder, for: address, context: context)
        #expect(try store.saveIdentity(approved, for: address, context: context) == .replacedExisting)
        #expect(try store.identityTrust(for: contact) == .trusted(approved, verified: false))
        #expect(try store.isTrustedIdentity(approved, for: address, direction: .sending, context: context))
    }

    @Test("verifying in person approves the current key; a change clears verification")
    func verification() throws {
        let address = contact.protocolAddress
        let first = IdentityKeyPair.generate().identityKey
        let second = IdentityKeyPair.generate().identityKey
        _ = try store.saveIdentity(first, for: address, context: context)
        try store.setIdentityVerified(true, for: contact)
        #expect(try store.identityTrust(for: contact) == .trusted(first, verified: true))

        _ = try store.saveIdentity(second, for: address, context: context)
        try store.setIdentityVerified(true, for: contact)
        #expect(try store.identityTrust(for: contact) == .trusted(second, verified: true))
        #expect(try store.isTrustedIdentity(second, for: address, direction: .sending, context: context))
    }

    @Test("one-time Kyber keys are deleted when used; last-resort keys track reuse")
    func kyberUsage() throws {
        let generator = PreKeyGenerator(store: store)
        let oneTime = try generator.generateOneTimeKyberPreKeys(count: 2)
        let lastResort = try generator.generateLastResortKyberPreKey()
        let baseKey = PrivateKey.generate().publicKey

        try store.markKyberPreKeyUsed(id: oneTime[0].keyId, signedPreKeyId: 1, baseKey: baseKey, context: context)
        #expect(try store.oneTimeKyberPreKeyCount() == 1)
        #expect(throws: SignalError.self) { try store.loadKyberPreKey(id: oneTime[0].keyId, context: context) }

        try store.markKyberPreKeyUsed(id: lastResort.keyId, signedPreKeyId: 1, baseKey: baseKey, context: context)
        #expect(throws: SignalError.self) {
            try store.markKyberPreKeyUsed(id: lastResort.keyId, signedPreKeyId: 1, baseKey: baseKey, context: context)
        }
        // Same key with another signed pre-key or base key is a different handshake.
        try store.markKyberPreKeyUsed(id: lastResort.keyId, signedPreKeyId: 2, baseKey: baseKey, context: context)
        try store.markKyberPreKeyUsed(
            id: lastResort.keyId, signedPreKeyId: 1, baseKey: PrivateKey.generate().publicKey, context: context)
        #expect(try store.lastResortKyberPreKeys().map(\.id) == [lastResort.keyId])

        // Plain storeKyberPreKey keeps the last-resort flag of an existing entry.
        let record = try store.loadKyberPreKey(id: lastResort.keyId, context: context)
        try store.storeKyberPreKey(record, id: lastResort.keyId, context: context)
        #expect(try store.lastResortKyberPreKeys().count == 1)

        try store.removeKyberPreKey(id: lastResort.keyId)
        #expect(try store.lastResortKyberPreKeys().isEmpty)
    }

    @Test("key ids stay in 1…16777215 and wrap")
    func keyIdReservation() throws {
        #expect(try store.reserveKeyIds(.oneTime, count: 3) == [1, 2, 3])
        #expect(try store.reserveKeyIds(.kyber, count: 1) == [1])
        #expect(try store.reserveKeyIds(.oneTime, count: 1) == [4])
        #expect(KeyIds.next(after: 0xFF_FFFF) == 1)
        #expect(KeyIds.next(after: 7) == 8)
    }

    @Test func generatedPreKeysAreSignedByTheIdentity() throws {
        let generator = PreKeyGenerator(store: store)
        let identity = try store.identityKeyPair(context: context).publicKey
        let signed = try generator.generateSignedPreKey()
        #expect(signed.publicKey.count == 33)
        #expect(try identity.verifySignature(message: signed.publicKey, signature: signed.signature))
        let kyber = try generator.generateLastResortKyberPreKey()
        #expect(kyber.publicKey.first == 0x08)  // Kyber1024 type byte
        #expect(try identity.verifySignature(message: kyber.publicKey, signature: kyber.signature))
        let oneTime = try generator.generateOneTimePreKeys(count: 100)
        #expect(Set(oneTime.map(\.keyId)).count == 100)
        #expect(try store.oneTimePreKeyCount() == 100)
    }

    @Test func forgettingAContactRemovesSessionAndIdentity() throws {
        let alice = TestParty()
        let bob = TestParty()
        try alice.cipher.processBundle(try bob.bundle())
        #expect(try alice.store.loadSession(for: bob.account.protocolAddress, context: context) != nil)
        try alice.store.deleteContact(bob.account)
        #expect(try alice.store.loadSession(for: bob.account.protocolAddress, context: context) == nil)
        #expect(try alice.store.identityTrust(for: bob.account) == .unknown)
    }

    @Test("addresses not built from an account id are refused")
    func foreignAddress() throws {
        let address = try ProtocolAddress(name: "+14151111111", deviceId: 1)
        #expect(throws: ProtocolStoreError.self) { try store.loadSession(for: address, context: context) }
    }
}

@Suite("Pre-key maintenance rules (§5)")
struct PreKeyMaintenanceTests {
    let day: TimeInterval = 24 * 3600
    let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func key(_ id: UInt32, daysAgo: Double) -> PreKeyMaintenance.Key {
        .init(id: id, timestamp: now.addingTimeInterval(-daysAgo * day).millisecondsSince1970)
    }

    @Test func rotation() {
        #expect(PreKeyMaintenance.needsRotation(keys: [], relayCurrentId: nil, now: now))
        #expect(!PreKeyMaintenance.needsRotation(keys: [key(1, daysAgo: 29)], relayCurrentId: 1, now: now))
        #expect(PreKeyMaintenance.needsRotation(keys: [key(1, daysAgo: 30)], relayCurrentId: 1, now: now))
        #expect(PreKeyMaintenance.needsRotation(keys: [key(1, daysAgo: 1)], relayCurrentId: 7, now: now))
    }

    @Test("replaced keys are kept for 30 days after their replacement")
    func expiry() {
        let keys = [key(3, daysAgo: 5), key(2, daysAgo: 40), key(1, daysAgo: 75)]
        // Key 2 was replaced 5 days ago (keep); key 1 was replaced 40 days ago (drop).
        #expect(PreKeyMaintenance.expired(keys: keys, now: now) == [1])
        #expect(PreKeyMaintenance.expired(keys: [key(9, daysAgo: 400)], now: now).isEmpty)
    }
}
