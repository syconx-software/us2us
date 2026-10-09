// us2us — AGPL-3.0-only
//
// Us2usKit's InMemoryProtocolStore tests (StoreTests, SessionTests), ported
// so that every case runs against BOTH the in-memory reference and the
// persistent SQLCipher store: same inputs, same expected results.

import Foundation
import LibSignalClient
import Testing
import Us2usKit

@testable import Us2usStorage

enum StoreKind: String, CaseIterable, CustomTestStringConvertible, Sendable {
    case inMemory
    case persistent

    var testDescription: String { rawValue }
}

/// Keeps the storage of a persistent store alive for the test.
final class StoreFixture: @unchecked Sendable {
    let store: any ProtocolStore
    private let environment: TestEnvironment?
    private let storage: LocalStorage?

    init(_ kind: StoreKind, identity: IdentityKeyPair = .generate(), registrationId: UInt32 = 42) throws {
        switch kind {
        case .inMemory:
            store = InMemoryProtocolStore(identity: identity, registrationId: registrationId)
            environment = nil
            storage = nil
        case .persistent:
            let environment = try TestEnvironment()
            let storage = try environment.open()
            store = try storage.createIdentity(identity, registrationId: registrationId)
            self.environment = environment
            self.storage = storage
        }
    }

    func party(account: AccountId = AccountId(uuid: UUID())) -> Party {
        Party(account: account, store: store)
    }
}

@Suite("Protocol store contract (in-memory ≡ persistent, §5)")
struct ProtocolStoreContractTests {
    let contact = AccountId(uuid: UUID())
    let context = NullContext()

    @Test("TOFU: first identity is trusted, a change is flagged and blocks sending", arguments: StoreKind.allCases)
    func trustOnFirstUse(kind: StoreKind) throws {
        let store = try StoreFixture(kind).store
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

    @Test("identities restored from a backup keep their trust (§5.5)", arguments: StoreKind.allCases)
    func restoredIdentities(kind: StoreKind) throws {
        let store = try StoreFixture(kind).store
        let key = IdentityKeyPair.generate().identityKey
        try store.restoreIdentity(key, for: contact, approved: true, verified: true)
        #expect(try store.identityTrust(for: contact) == .trusted(key, verified: true))
        #expect(try store.saveIdentity(key, for: contact.protocolAddress, context: context) == .newOrUnchanged)

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

    @Test("repeated changes before approval keep the last approved key as 'previous'", arguments: StoreKind.allCases)
    func repeatedChanges(kind: StoreKind) throws {
        let store = try StoreFixture(kind).store
        let keys = (0..<3).map { _ in IdentityKeyPair.generate().identityKey }
        for key in keys { _ = try store.saveIdentity(key, for: contact.protocolAddress, context: context) }
        #expect(try store.identityTrust(for: contact) == .changed(previous: keys[0], current: keys[2]))
    }

    @Test("a change back to the approved key is trusted again", arguments: StoreKind.allCases)
    func changeBack(kind: StoreKind) throws {
        let store = try StoreFixture(kind).store
        let address = contact.protocolAddress
        let approved = IdentityKeyPair.generate().identityKey
        let intruder = IdentityKeyPair.generate().identityKey
        _ = try store.saveIdentity(approved, for: address, context: context)
        _ = try store.saveIdentity(intruder, for: address, context: context)
        #expect(try store.saveIdentity(approved, for: address, context: context) == .replacedExisting)
        #expect(try store.identityTrust(for: contact) == .trusted(approved, verified: false))
        #expect(try store.isTrustedIdentity(approved, for: address, direction: .sending, context: context))
    }

    @Test("verifying in person approves the current key; a change clears verification", arguments: StoreKind.allCases)
    func verification(kind: StoreKind) throws {
        let store = try StoreFixture(kind).store
        let address = contact.protocolAddress
        let first = IdentityKeyPair.generate().identityKey
        let second = IdentityKeyPair.generate().identityKey
        _ = try store.saveIdentity(first, for: address, context: context)
        try store.setIdentityVerified(true, for: contact)
        #expect(try store.identityTrust(for: contact) == .trusted(first, verified: true))

        _ = try store.saveIdentity(second, for: address, context: context)
        #expect(try store.identityTrust(for: contact) == .changed(previous: first, current: second))
        try store.setIdentityVerified(true, for: contact)
        #expect(try store.identityTrust(for: contact) == .trusted(second, verified: true))
        #expect(try store.isTrustedIdentity(second, for: address, direction: .sending, context: context))
    }

    @Test("one-time Kyber keys are deleted when used; last-resort keys track reuse", arguments: StoreKind.allCases)
    func kyberUsage(kind: StoreKind) throws {
        let store = try StoreFixture(kind).store
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
        try store.markKyberPreKeyUsed(id: lastResort.keyId, signedPreKeyId: 2, baseKey: baseKey, context: context)
        try store.markKyberPreKeyUsed(
            id: lastResort.keyId, signedPreKeyId: 1, baseKey: PrivateKey.generate().publicKey, context: context)
        #expect(try store.lastResortKyberPreKeys().map(\.id) == [lastResort.keyId])

        let record = try store.loadKyberPreKey(id: lastResort.keyId, context: context)
        try store.storeKyberPreKey(record, id: lastResort.keyId, context: context)
        #expect(try store.lastResortKyberPreKeys().count == 1)

        try store.removeKyberPreKey(id: lastResort.keyId)
        #expect(try store.lastResortKyberPreKeys().isEmpty)
    }

    @Test("key ids are reserved consecutively per space", arguments: StoreKind.allCases)
    func keyIdReservation(kind: StoreKind) throws {
        let store = try StoreFixture(kind).store
        #expect(try store.reserveKeyIds(.oneTime, count: 3) == [1, 2, 3])
        #expect(try store.reserveKeyIds(.kyber, count: 1) == [1])
        #expect(try store.reserveKeyIds(.oneTime, count: 1) == [4])
        #expect(try store.reserveKeyIds(.signed, count: 2) == [1, 2])
    }

    @Test func persistentKeyIdsWrapAfter16777215() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        let store = try storage.createIdentity(.generate(), registrationId: 1)
        try storage.database.write { connection in
            try connection.run("INSERT INTO key_id_counters (space, next_id) VALUES ('oneTime', ?)", [0xFF_FFFE])
        }
        #expect(try store.reserveKeyIds(.oneTime, count: 3) == [0xFF_FFFE, 0xFF_FFFF, 1])
    }

    @Test("generated pre-keys are signed by the identity", arguments: StoreKind.allCases)
    func generatedPreKeys(kind: StoreKind) throws {
        let store = try StoreFixture(kind).store
        let generator = PreKeyGenerator(store: store)
        let identity = try store.identityKeyPair(context: context).publicKey
        let signed = try generator.generateSignedPreKey()
        #expect(try identity.verifySignature(message: signed.publicKey, signature: signed.signature))
        let kyber = try generator.generateLastResortKyberPreKey()
        #expect(kyber.publicKey.first == 0x08)
        #expect(try identity.verifySignature(message: kyber.publicKey, signature: kyber.signature))
        let oneTime = try generator.generateOneTimePreKeys(count: 100)
        #expect(Set(oneTime.map(\.keyId)).count == 100)
        #expect(try store.oneTimePreKeyCount() == 100)
        #expect(try store.signedPreKeys().map(\.id) == [signed.keyId])
        try store.removeSignedPreKey(id: signed.keyId)
        #expect(try store.signedPreKeys().isEmpty)
    }

    @Test("forgetting a contact removes session and identity", arguments: StoreKind.allCases)
    func forgettingAContact(kind: StoreKind) throws {
        let aliceFixture = try StoreFixture(kind)
        let alice = aliceFixture.party()
        let bob = Party.inMemory()
        try alice.cipher.processBundle(try bob.bundle())
        #expect(try alice.store.loadSession(for: bob.account.protocolAddress, context: context) != nil)
        try alice.store.deleteContact(bob.account)
        #expect(try alice.store.loadSession(for: bob.account.protocolAddress, context: context) == nil)
        #expect(try alice.store.identityTrust(for: bob.account) == .unknown)
    }

    @Test("addresses not built from an account id are refused", arguments: StoreKind.allCases)
    func foreignAddress(kind: StoreKind) throws {
        let store = try StoreFixture(kind).store
        let address = try ProtocolAddress(name: "+14151111111", deviceId: 1)
        #expect(throws: ProtocolStoreError.self) { try store.loadSession(for: address, context: context) }
        let wrongDevice = ProtocolAddress(Aci(fromUUID: UUID()), deviceId: 2)
        #expect(throws: ProtocolStoreError.self) { try store.loadSession(for: wrongDevice, context: context) }
    }

    @Test("everything survives closing and reopening the database")
    func persistence() throws {
        let environment = try TestEnvironment()
        let identity = IdentityKeyPair.generate()
        let bob = Party.inMemory()
        let aliceAccount = AccountId(uuid: UUID())
        do {
            let storage = try environment.open()
            let store = try storage.createIdentity(identity, registrationId: 77)
            let alice = Party(account: aliceAccount, store: store)
            try alice.cipher.processBundle(try bob.bundle())
            _ = try alice.send(.text("one"), to: bob)
            _ = try bob.send(.text("two"), to: alice)
            _ = try PreKeyGenerator(store: store).generateOneTimePreKeys(count: 5)
            try store.setIdentityVerified(true, for: bob.account)
            storage.close()
        }
        let storage = try environment.open()
        let store = try #require(storage.protocolStore)
        #expect(try store.identityKeyPair(context: context).serialize() == identity.serialize())
        #expect(try store.localRegistrationId(context: context) == 77)
        #expect(try store.oneTimePreKeyCount() == 5)
        #expect(try store.identityTrust(for: bob.account) == .trusted(bob.identity.identityKey, verified: true))
        let alice = Party(account: aliceAccount, store: store)
        #expect(try alice.send(.text("three"), to: bob).content.textBody == "three")
        #expect(try bob.send(.text("four"), to: alice).content.textBody == "four")
    }
}

/// Us2usKit's SessionTests on persistent stores (both parties persistent).
@Suite("Sessions on the persistent store (§5, §6)")
struct PersistentSessionTests {
    private func pair() throws -> (StoreFixture, StoreFixture, Party, Party) {
        let a = try StoreFixture(.persistent)
        let b = try StoreFixture(.persistent)
        return (a, b, a.party(), b.party())
    }

    private func sealedType(_ envelope: Data, for party: Party) throws -> UInt32 {
        try SealedEnvelope.open(envelope, with: party.identity).type
    }

    @Test("bundle → PreKeySignalMessage → replies on the ratchet")
    func preKeyThenReplies() throws {
        let (_, _, alice, bob) = try pair()
        #expect(try !alice.cipher.hasUsableSession(with: bob.account))
        try alice.cipher.processBundle(try bob.bundle())
        let first = try alice.seal(.text("Merhaba"), to: bob)
        #expect(try sealedType(first, for: bob) == 3)
        let received = try bob.receive(first)
        #expect(received.sender == alice.account)
        #expect(received.content.textBody == "Merhaba")
        let reply = try bob.seal(.text("Selam"), to: alice)
        #expect(try sealedType(reply, for: alice) == 2)
        #expect(try alice.receive(reply).content.textBody == "Selam")
        let second = try alice.seal(.text("Nasılsın?"), to: bob)
        #expect(try sealedType(second, for: bob) == 2)
        #expect(try bob.receive(second).content.textBody == "Nasılsın?")
    }

    @Test("out-of-order delivery decrypts; replays are rejected")
    func outOfOrder() throws {
        let (_, _, alice, bob) = try pair()
        try alice.cipher.processBundle(try bob.bundle())
        _ = try alice.send(.text("hello"), to: bob)
        _ = try bob.send(.text("hi"), to: alice)
        let envelopes = try (1...3).map { try alice.seal(.text("m\($0)"), to: bob) }
        #expect(try bob.receive(envelopes[2]).content.textBody == "m3")
        #expect(try bob.receive(envelopes[0]).content.textBody == "m1")
        #expect(try bob.receive(envelopes[1]).content.textBody == "m2")
        #expect(throws: SessionError.self) { try bob.receive(envelopes[1]) }
    }

    @Test("a used one-time Kyber key and EC pre-key are deleted (§5)")
    func oneTimeKeysAreDeleted() throws {
        let (_, _, alice, bob) = try pair()
        let bundle = try bob.bundle()
        let kyberId = bundle.kyberPreKey.keyId
        let ecId = try #require(bundle.oneTimePreKey?.keyId)
        try alice.cipher.processBundle(bundle)
        let first = try alice.seal(.text("one"), to: bob)
        _ = try bob.receive(first)
        #expect(throws: SignalError.self) { try bob.store.loadKyberPreKey(id: kyberId, context: NullContext()) }
        #expect(throws: SignalError.self) { try bob.store.loadPreKey(id: ecId, context: NullContext()) }
        #expect(try bob.store.oneTimeKyberPreKeyCount() == 0)
        try bob.store.deleteSession(for: alice.account)
        #expect(throws: SessionError.self) { try bob.receive(first) }
    }

    @Test("the Kyber last-resort key is kept but never accepted twice with the same base key (§5)")
    func lastResortReuse() throws {
        let (_, _, alice, bob) = try pair()
        let bundle = try bob.bundle(withOneTimeKeys: false, kyberOneTime: false)
        try alice.cipher.processBundle(bundle)
        let first = try alice.seal(.text("one"), to: bob)
        _ = try bob.receive(first)
        #expect(try bob.store.loadKyberPreKey(id: bundle.kyberPreKey.keyId, context: NullContext()).id
            == bundle.kyberPreKey.keyId)
        try bob.store.deleteSession(for: alice.account)
        #expect(throws: SessionError.self) { try bob.receive(first) }
        let carol = Party.inMemory()
        try carol.cipher.processBundle(bundle)
        #expect(try carol.send(.text("from carol"), to: bob).content.textBody == "from carol")
    }

    @Test("a changed identity blocks sending until approved; receiving flags it (§5, §6.3)")
    func identityChanges() throws {
        let (_, _, alice, bob) = try pair()
        try alice.cipher.processBundle(try bob.bundle())
        _ = try alice.send(.text("hello"), to: bob)
        _ = try bob.send(.text("hi"), to: alice)

        let reinstalledBob = Party.inMemory(account: bob.account)
        try reinstalledBob.cipher.processBundle(try alice.bundle())
        let received = try reinstalledBob.send(.text("new phone"), to: alice)
        #expect(received.identityChanged)
        #expect(throws: SessionError.identityChanged(bob.account)) { try alice.seal(.text("reply"), to: bob) }
        #expect(try !reinstalledBob.send(.text("again"), to: alice).identityChanged)
        try alice.store.approveIdentity(reinstalledBob.identity.identityKey, for: bob.account)
        #expect(try alice.send(.text("reply"), to: reinstalledBob).content.textBody == "reply")
    }

    @Test("persistent and in-memory peers interoperate")
    func mixedStores() throws {
        let persistent = try StoreFixture(.persistent)
        let alice = persistent.party()
        let bob = Party.inMemory()
        try bob.cipher.processBundle(try alice.bundle())
        for round in 0..<20 {
            let (sender, receiver) = round.isMultiple(of: 2) ? (bob, alice) : (alice, bob)
            #expect(try sender.send(.text("r\(round)"), to: receiver).content.textBody == "r\(round)")
        }
    }
}
