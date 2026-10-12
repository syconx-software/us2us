import Foundation
import LibSignalClient
import Testing

@testable import Us2usKit

/// Identity verification against a relay that may substitute keys
/// (PROTOCOL.md §5, §9): the first key of a contact comes from the relay, so
/// only comparing safety numbers — or a contact code scanned side by side,
/// which pins the key — reveals a substitution, and every decision is bound
/// to the number the user saw.
@Suite("Key-bound verification against a fake relay (§5)")
struct KeyBoundVerificationTests {
    let relay = FakeRelay()

    private func register(_ name: String) async throws -> Messenger {
        let store = InMemoryProtocolStore(identity: .generate(), registrationId: LocalIdentity.generateRegistrationId())
        return try await Messenger.register(
            username: try NormalizedUsername(name), store: store, relay: relay.makeClient())
    }

    /// From now on the relay serves `party`'s keys as `account`'s.
    private func serve(_ party: TestParty, as account: AccountId) throws {
        let bundle = try party.bundle()
        relay.update(account) { stored in
            stored.identityKey = bundle.identityKey
            stored.signedPreKey = bundle.signedPreKey
            stored.kyberLastResort = bundle.kyberPreKey
            stored.kyberOneTime = []
            stored.oneTime = bundle.oneTimePreKey.map { [$0] } ?? []
        }
    }

    private func verification(_ method: IdentityVerificationMethod = .qrCode) -> IdentityVerification {
        IdentityVerification(method: method, verifiedAt: Date())
    }

    @Test("a contact code pins its key: a relay serving another one at first contact is caught")
    func contactCodePinsTheKey() async throws {
        let alice = try await register("alice_01")
        let bob = try await register("bob_02")
        let bobId = await bob.account.accountId
        let bobKey = try #require(relay.account(bobId)?.identityKey)
        let pinned = try IdentityKey(bytes: bobKey)

        // Side by side, Alice scans Bob's contact code: the key it commits to is pinned.
        #expect(try await alice.pinVerifiedIdentity(bobKey, for: bobId, verification: verification()))
        #expect(try await alice.identityTrust(for: bobId) == .trusted(pinned, verified: true))

        // The relay now hands out its own keys for Bob: sending is blocked and flagged.
        let mallory = TestParty(account: bobId)
        try serve(mallory, as: bobId)
        await #expect(throws: SessionError.identityChanged(bobId)) {
            try await alice.send(.text("hi"), to: bobId, authorization: .proofOfWork)
        }
        #expect(
            try await alice.identityTrust(for: bobId)
                == .changed(previous: pinned, current: mallory.identity.identityKey))
        // Another pin cannot replace the stored key.
        #expect(
            try await alice.pinVerifiedIdentity(
                mallory.identity.identityKey.serialize(), for: bobId, verification: verification()) == false)
        #expect(try await alice.pinVerifiedIdentity(Data([5, 1, 2]), for: bobId, verification: verification()) == false)
    }

    @Test("a relay that swaps keys at first contact is revealed only by the safety number")
    func keySubstitutionAtFirstContact() async throws {
        let alice = try await register("alice_01")
        let bob = try await register("bob_02")
        let aliceId = await alice.account.accountId
        let bobId = await bob.account.accountId
        let fakeBob = TestParty(account: bobId)
        let fakeAlice = TestParty(account: aliceId)
        try serve(fakeBob, as: bobId)
        try serve(fakeAlice, as: aliceId)
        try await alice.send(.text("hi Bob"), to: bobId, authorization: .proofOfWork)
        try await bob.send(.text("hi Alice"), to: aliceId, authorization: .proofOfWork)

        // Both phones trust the relay's keys without any warning …
        #expect(try await alice.identityTrust(for: bobId) == .trusted(fakeBob.identity.identityKey, verified: false))
        #expect(try await bob.identityTrust(for: aliceId) == .trusted(fakeAlice.identity.identityKey, verified: false))
        // … but their safety numbers differ, and scanning the other's code verifies nothing.
        let seenByAlice = try #require(try await alice.safetyNumber(with: bobId))
        let seenByBob = try #require(try await bob.safetyNumber(with: aliceId))
        #expect(seenByAlice.digits != seenByBob.digits)
        #expect(try !seenByAlice.matches(scanned: seenByBob.scannable))
        #expect(
            try await alice.verifySafetyNumber(contact: bobId, scanned: seenByBob.scannable, verification: verification())
                == false)
        #expect(try await alice.identityTrust(for: bobId) == .trusted(fakeBob.identity.identityKey, verified: false))
    }

    @Test("scanning the other phone's safety-number code verifies the key it matched")
    func scanningVerifies() async throws {
        let alice = try await register("alice_01")
        let bob = try await register("bob_02")
        let aliceId = await alice.account.accountId
        let bobId = await bob.account.accountId
        try await alice.send(.text("hi"), to: bobId, authorization: .proofOfWork)
        _ = try await bob.receiveAll()

        let seenByBob = try #require(try await bob.safetyNumber(with: aliceId))
        #expect(
            try await alice.verifySafetyNumber(contact: bobId, scanned: Data("not a code".utf8), verification: verification())
                == false)
        #expect(try await alice.verifySafetyNumber(contact: bobId, scanned: seenByBob.scannable, verification: verification()))
        let bobKey = try IdentityKey(bytes: try #require(relay.account(bobId)?.identityKey))
        #expect(try await alice.identityTrust(for: bobId) == .trusted(bobKey, verified: true))
        #expect(try await alice.identityVerification(for: bobId)?.method == .qrCode)
        try await alice.clearVerification(contact: bobId)
        #expect(try await alice.identityTrust(for: bobId) == .trusted(bobKey, verified: false))
        #expect(try await alice.identityVerification(for: bobId) == nil)
    }

    @Test("marking or accepting is refused when the key changed after its number was shown")
    func decisionsAreBoundToTheShownNumber() async throws {
        let alice = try await register("alice_01")
        let bob = try await register("bob_02")
        let bobId = await bob.account.accountId
        try await alice.send(.text("hi"), to: bobId, authorization: .proofOfWork)
        let shown = try #require(try await alice.safetyNumber(with: bobId)).scannable

        // A new key arrives while the old number is on screen.
        let newKey = IdentityKeyPair.generate().identityKey
        _ = try alice.store.saveIdentity(newKey, for: bobId.protocolAddress, context: NullContext())
        #expect(try await alice.markVerified(contact: bobId, comparedNumber: shown, verification: verification(.manual)) == false)
        #expect(try await alice.approveIdentityChange(for: bobId, shownNumber: shown) == false)
        guard case .changed(_, let current) = try await alice.identityTrust(for: bobId) else {
            Issue.record("expected a pending change")
            return
        }
        #expect(current == newKey)

        // The number now on screen can be accepted without verifying …
        let now = try #require(try await alice.safetyNumber(with: bobId)).scannable
        #expect(now != shown)
        #expect(try await alice.approveIdentityChange(for: bobId, shownNumber: now))
        #expect(try await alice.identityTrust(for: bobId) == .trusted(newKey, verified: false))
        #expect(try await alice.identityVerification(for: bobId) == nil)
        #expect(try await alice.approveIdentityChange(for: bobId, shownNumber: now) == false, "nothing left to accept")
        // … and verified once compared.
        #expect(try await alice.markVerified(contact: bobId, comparedNumber: now, verification: verification(.manual)))
        #expect(try await alice.identityTrust(for: bobId) == .trusted(newKey, verified: true))
        #expect(try await alice.identityVerification(for: bobId)?.method == .manual)
    }
}
