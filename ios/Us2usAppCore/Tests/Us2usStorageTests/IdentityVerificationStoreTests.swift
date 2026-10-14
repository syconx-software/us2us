// us2us — AGPL-3.0-only
//
// Key-bound identity decisions (PROTOCOL.md §5) on BOTH stores: a contact
// code pins the scanned key, a verification or an acceptance only applies
// to the key the user compared, and when / how a key was verified is kept
// with that key.

import Foundation
import LibSignalClient
import Testing
import Us2usKit

@testable import Us2usStorage

@Suite("Identity verification bound to keys (in-memory ≡ persistent, §5)")
struct IdentityVerificationStoreTests {
    let contact = AccountId(uuid: UUID())
    let context = NullContext()
    let verifiedAt = Date(timeIntervalSince1970: 1_791_000_123.456)

    private var byQR: IdentityVerification { IdentityVerification(method: .qrCode, verifiedAt: verifiedAt) }
    private var byHand: IdentityVerification { IdentityVerification(method: .manual, verifiedAt: verifiedAt) }

    @Test("a contact code pins its key when none is stored: verified, approved, with its record", arguments: StoreKind.allCases)
    func pinOnFirstContact(kind: StoreKind) throws {
        let store = try StoreFixture(kind).store
        let scanned = IdentityKeyPair.generate().identityKey
        #expect(try store.pinVerifiedIdentity(scanned, for: contact, verification: byQR))
        #expect(try store.identityTrust(for: contact) == .trusted(scanned, verified: true))
        #expect(try store.identityVerification(for: contact) == byQR)
        let address = contact.protocolAddress
        #expect(try store.isTrustedIdentity(scanned, for: address, direction: .sending, context: context))
        // The relay's first bundle with another key is a change, not a first use.
        let substituted = IdentityKeyPair.generate().identityKey
        #expect(try !store.isTrustedIdentity(substituted, for: address, direction: .sending, context: context))
        #expect(try store.saveIdentity(substituted, for: address, context: context) == .replacedExisting)
        #expect(try store.identityTrust(for: contact) == .changed(previous: scanned, current: substituted))
        #expect(try store.identityVerification(for: contact) == nil)
    }

    @Test("pinning the stored key verifies and approves it", arguments: StoreKind.allCases)
    func pinTheStoredKey(kind: StoreKind) throws {
        let store = try StoreFixture(kind).store
        let key = IdentityKeyPair.generate().identityKey
        _ = try store.saveIdentity(key, for: contact.protocolAddress, context: context)
        #expect(try store.identityTrust(for: contact) == .trusted(key, verified: false))
        #expect(try store.pinVerifiedIdentity(key, for: contact, verification: byQR))
        #expect(try store.identityTrust(for: contact) == .trusted(key, verified: true))
        #expect(try store.identityVerification(for: contact)?.method == .qrCode)
    }

    @Test("pinning over another stored key changes nothing", arguments: StoreKind.allCases)
    func pinOverAnotherKey(kind: StoreKind) throws {
        let store = try StoreFixture(kind).store
        let address = contact.protocolAddress
        let first = IdentityKeyPair.generate().identityKey
        let stored = IdentityKeyPair.generate().identityKey
        let scanned = IdentityKeyPair.generate().identityKey
        // Trusted, unverified.
        _ = try store.saveIdentity(stored, for: address, context: context)
        #expect(try store.pinVerifiedIdentity(scanned, for: contact, verification: byQR) == false)
        #expect(try store.identityTrust(for: contact) == .trusted(stored, verified: false))
        // A pending change: the scanned key is neither the new nor the old one.
        let other = AccountId(uuid: UUID())
        _ = try store.saveIdentity(first, for: other.protocolAddress, context: context)
        _ = try store.saveIdentity(stored, for: other.protocolAddress, context: context)
        #expect(try store.pinVerifiedIdentity(scanned, for: other, verification: byQR) == false)
        #expect(try store.pinVerifiedIdentity(first, for: other, verification: byQR) == false)
        // Nor the pending key itself: the approved one is still held (§5).
        #expect(try store.pinVerifiedIdentity(stored, for: other, verification: byQR) == false)
        #expect(try store.identityTrust(for: other) == .changed(previous: first, current: stored))
        #expect(try store.identityVerification(for: other) == nil)
        #expect(try !store.isTrustedIdentity(stored, for: other.protocolAddress, direction: .sending, context: context))
    }

    @Test("verifying is refused for a key other than the compared one", arguments: StoreKind.allCases)
    func verifyExpectedKey(kind: StoreKind) throws {
        let store = try StoreFixture(kind).store
        let address = contact.protocolAddress
        let compared = IdentityKeyPair.generate().identityKey
        let newer = IdentityKeyPair.generate().identityKey
        #expect(try store.verifyIdentity(compared, for: contact, verification: byHand) == false, "nothing stored")
        _ = try store.saveIdentity(compared, for: address, context: context)
        // The key changes while the user compares the old number.
        _ = try store.saveIdentity(newer, for: address, context: context)
        #expect(try store.verifyIdentity(compared, for: contact, verification: byHand) == false)
        #expect(try store.acceptIdentity(compared, for: contact) == false)
        #expect(try store.identityTrust(for: contact) == .changed(previous: compared, current: newer))
        #expect(try !store.isTrustedIdentity(newer, for: address, direction: .sending, context: context))
        #expect(try store.identityVerification(for: contact) == nil)

        // The number of the current key: verified, which also accepts it.
        #expect(try store.verifyIdentity(newer, for: contact, verification: byHand))
        #expect(try store.identityTrust(for: contact) == .trusted(newer, verified: true))
        #expect(try store.identityVerification(for: contact) == byHand)
        #expect(try store.isTrustedIdentity(newer, for: address, direction: .sending, context: context))
    }

    @Test("accepting a change without verifying approves only the shown key, unverified", arguments: StoreKind.allCases)
    func acceptWithoutVerifying(kind: StoreKind) throws {
        let store = try StoreFixture(kind).store
        let address = contact.protocolAddress
        let old = IdentityKeyPair.generate().identityKey
        let new = IdentityKeyPair.generate().identityKey
        _ = try store.saveIdentity(old, for: address, context: context)
        #expect(try store.verifyIdentity(old, for: contact, verification: byQR))
        _ = try store.saveIdentity(new, for: address, context: context)
        #expect(try store.acceptIdentity(new, for: contact))
        #expect(try store.identityTrust(for: contact) == .trusted(new, verified: false))
        #expect(try store.identityVerification(for: contact) == nil)
        #expect(try store.isTrustedIdentity(new, for: address, direction: .sending, context: context))
        // Nothing pending any more: accepting again changes nothing and stays unverified.
        #expect(try store.acceptIdentity(new, for: contact))
        #expect(try store.identityTrust(for: contact) == .trusted(new, verified: false))
    }

    @Test("the record belongs to the verified key: cleared with the seal, kept on re-approval", arguments: StoreKind.allCases)
    func recordLifecycle(kind: StoreKind) throws {
        let store = try StoreFixture(kind).store
        let key = IdentityKeyPair.generate().identityKey
        _ = try store.saveIdentity(key, for: contact.protocolAddress, context: context)
        #expect(try store.verifyIdentity(key, for: contact, verification: byHand))
        try store.approveIdentity(key, for: contact)
        #expect(try store.identityVerification(for: contact) == byHand)
        try store.setIdentityVerified(false, for: contact)
        #expect(try store.identityTrust(for: contact) == .trusted(key, verified: false))
        #expect(try store.identityVerification(for: contact) == nil)
        // Verified without a record (as by an earlier version): no made-up date.
        try store.setIdentityVerified(true, for: contact)
        #expect(try store.identityTrust(for: contact) == .trusted(key, verified: true))
        #expect(try store.identityVerification(for: contact) == nil)
        // Restored from a backup: verified, no record.
        let restored = AccountId(uuid: UUID())
        try store.restoreIdentity(key, for: restored, approved: true, verified: true)
        #expect(try store.identityTrust(for: restored) == .trusted(key, verified: true))
        #expect(try store.identityVerification(for: restored) == nil)
    }

    @Test("a pinned key blocks a session with a bundle under another key (§5)", arguments: StoreKind.allCases)
    func pinnedKeyBlocksASubstitutedBundle(kind: StoreKind) throws {
        let aliceFixture = try StoreFixture(kind)
        let alice = aliceFixture.party()
        let bob = Party.inMemory()
        #expect(try alice.store.pinVerifiedIdentity(bob.identity.identityKey, for: bob.account, verification: byQR))
        // The relay hands out its own keys for Bob.
        let attacker = Party.inMemory(account: bob.account)
        #expect(throws: SessionError.identityChanged(bob.account)) { try alice.cipher.processBundle(try attacker.bundle()) }
        #expect(
            try alice.store.identityTrust(for: bob.account)
                == .changed(previous: bob.identity.identityKey, current: attacker.identity.identityKey))
        #expect(throws: SessionError.self) { try alice.seal(.text("hi"), to: attacker) }
        // The real bundle still works once it comes back, verified as before.
        try alice.cipher.processBundle(try bob.bundle())
        #expect(try alice.store.identityTrust(for: bob.account) == .trusted(bob.identity.identityKey, verified: true))
        #expect(try alice.store.identityVerification(for: bob.account) == byQR)
        #expect(try alice.send(.text("hi"), to: bob).content.textBody == "hi")
    }

    @Test(
        "a return to the verified key keeps its verification: a key injected once cannot strip the seal (§5)",
        arguments: StoreKind.allCases)
    func returnToTheVerifiedKey(kind: StoreKind) throws {
        let store = try StoreFixture(kind).store
        let address = contact.protocolAddress
        let verified = IdentityKeyPair.generate().identityKey
        let injected = IdentityKeyPair.generate().identityKey
        let another = IdentityKeyPair.generate().identityKey
        #expect(try store.pinVerifiedIdentity(verified, for: contact, verification: byQR))
        #expect(try store.saveIdentity(injected, for: address, context: context) == .replacedExisting)
        #expect(try store.identityTrust(for: contact) == .changed(previous: verified, current: injected))
        #expect(try store.identityVerification(for: contact) == nil)
        // A further key on top still remembers that the approved key was verified.
        _ = try store.saveIdentity(another, for: address, context: context)
        #expect(try store.saveIdentity(verified, for: address, context: context) == .replacedExisting)
        #expect(try store.identityTrust(for: contact) == .trusted(verified, verified: true))
        #expect(try store.identityVerification(for: contact) == byQR)
        // A key accepted without verifying comes back unverified.
        _ = try store.saveIdentity(injected, for: address, context: context)
        #expect(try store.acceptIdentity(injected, for: contact))
        _ = try store.saveIdentity(another, for: address, context: context)
        _ = try store.saveIdentity(injected, for: address, context: context)
        #expect(try store.identityTrust(for: contact) == .trusted(injected, verified: false))
        #expect(try store.identityVerification(for: contact) == nil)
    }

    @Test("the previous key's verification survives a relaunch while a change awaits approval")
    func previousVerificationSurvivesReopening() throws {
        let environment = try TestEnvironment()
        let verified = IdentityKeyPair.generate().identityKey
        let injected = IdentityKeyPair.generate().identityKey
        do {
            let storage = try environment.open()
            let store = try storage.createIdentity(.generate(), registrationId: 7)
            #expect(try store.pinVerifiedIdentity(verified, for: contact, verification: byQR))
            _ = try store.saveIdentity(injected, for: contact.protocolAddress, context: context)
            storage.close()
        }
        let storage = try environment.open()
        let store = try #require(storage.protocolStore)
        #expect(try store.identityTrust(for: contact) == .changed(previous: verified, current: injected))
        _ = try store.saveIdentity(verified, for: contact.protocolAddress, context: context)
        #expect(try store.identityTrust(for: contact) == .trusted(verified, verified: true))
        #expect(try store.identityVerification(for: contact)?.method == .qrCode)
    }

    @Test("when and how a key was verified survives a relaunch")
    func recordSurvivesReopening() throws {
        let environment = try TestEnvironment()
        let key = IdentityKeyPair.generate().identityKey
        do {
            let storage = try environment.open()
            let store = try storage.createIdentity(.generate(), registrationId: 7)
            #expect(try store.pinVerifiedIdentity(key, for: contact, verification: byQR))
            storage.close()
        }
        let storage = try environment.open()
        let store = try #require(storage.protocolStore)
        #expect(try store.identityTrust(for: contact) == .trusted(key, verified: true))
        let record = try #require(try store.identityVerification(for: contact))
        #expect(record.method == .qrCode)
        #expect(abs(record.verifiedAt.timeIntervalSince(verifiedAt)) < 0.001)
    }

    @Test("identities verified before v4 keep their verification, without a record")
    func upgradeKeepsVerifiedIdentities() throws {
        let environment = try TestEnvironment()
        try environment.layout.prepare()
        let dbKey = LocalKeyDerivation.databaseKey(dek: environment.dek)
        let key = IdentityKeyPair.generate().identityKey
        let pending = AccountId(uuid: UUID())
        let first = IdentityKeyPair.generate().identityKey
        do {
            // A v3 database, as TestFlight builds stored it.
            let v3 = try EncryptedDatabase(
                url: environment.layout.databaseURL, key: dbKey, migrations: [Schema.v1, Schema.v2, Schema.v3])
            _ = try PersistentProtocolStore.create(database: v3, identity: .generate(), registrationId: 9)
            try v3.write { connection in
                try connection.run(
                    """
                    INSERT INTO identities (account, identity_key, previous_key, approved, verified)
                    VALUES (?, ?, NULL, 1, 1)
                    """, [contact.bytes, key.serialize()])
                try connection.run(
                    """
                    INSERT INTO identities (account, identity_key, previous_key, approved, verified)
                    VALUES (?, ?, ?, 0, 0)
                    """, [pending.bytes, key.serialize(), first.serialize()])
            }
            v3.close()
        }
        let storage = try environment.open()
        let store = try #require(storage.protocolStore)
        #expect(try store.identityTrust(for: contact) == .trusted(key, verified: true))
        #expect(try store.identityVerification(for: contact) == nil)
        #expect(try store.identityTrust(for: pending) == .changed(previous: first, current: key))
        // A new verification of the same key now keeps its record.
        #expect(try store.verifyIdentity(key, for: contact, verification: byHand))
        #expect(try store.identityVerification(for: contact) == byHand)
    }
}
