import Foundation
import LibSignalClient
import Testing

@testable import Us2usKit

/// Full Swift↔Swift sessions without a network (§5, §6).
@Suite("Sessions")
struct SessionTests {
    private func sealedType(_ envelope: Data, for party: TestParty) throws -> UInt32 {
        try SealedEnvelope.open(envelope, with: party.identity).type
    }

    @Test("bundle → PreKeySignalMessage → replies on the ratchet")
    func preKeyThenReplies() throws {
        let alice = TestParty()
        let bob = TestParty()
        #expect(try !alice.cipher.hasUsableSession(with: bob.account))
        try alice.cipher.processBundle(try bob.bundle())
        #expect(try alice.cipher.hasUsableSession(with: bob.account))

        let first = try alice.cipher.seal(.text("Merhaba"), for: bob.account)
        #expect(try sealedType(first, for: bob) == 3)
        let received = try bob.receive(first)
        #expect(received.sender == alice.account)
        #expect(received.content.textBody == "Merhaba")
        #expect(!received.identityChanged)

        let reply = try bob.cipher.seal(.text("Selam"), for: alice.account)
        #expect(try sealedType(reply, for: alice) == 2)
        #expect(try alice.receive(reply).content.textBody == "Selam")

        // Once Bob has answered, Alice's session is acknowledged: no more pre-key messages.
        let second = try alice.cipher.seal(.text("Nasılsın?"), for: bob.account)
        #expect(try sealedType(second, for: bob) == 2)
        #expect(try bob.receive(second).content.textBody == "Nasılsın?")
    }

    @Test("out-of-order delivery decrypts; replays are rejected")
    func outOfOrder() throws {
        let alice = TestParty()
        let bob = TestParty()
        try alice.cipher.processBundle(try bob.bundle())
        _ = try alice.send(.text("hello"), to: bob)
        _ = try bob.send(.text("hi"), to: alice)

        let envelopes = try (1...3).map { try alice.cipher.seal(.text("m\($0)"), for: bob.account) }
        #expect(try bob.receive(envelopes[2]).content.textBody == "m3")
        #expect(try bob.receive(envelopes[0]).content.textBody == "m1")
        #expect(try bob.receive(envelopes[1]).content.textBody == "m2")
        #expect(throws: SessionError.self) { try bob.receive(envelopes[1]) }
    }

    @Test("works without an EC one-time pre-key")
    func withoutOneTimePreKey() throws {
        let alice = TestParty()
        let bob = TestParty()
        try alice.cipher.processBundle(try bob.bundle(withOneTimeKeys: false))
        #expect(try alice.send(.text("x"), to: bob).content.textBody == "x")
    }

    @Test("a used one-time Kyber key and EC pre-key are deleted (§5)")
    func oneTimeKeysAreDeleted() throws {
        let alice = TestParty()
        let bob = TestParty()
        let bundle = try bob.bundle()
        let kyberId = bundle.kyberPreKey.keyId
        let ecId = try #require(bundle.oneTimePreKey?.keyId)
        #expect(try bob.store.oneTimeKyberPreKeyCount() == 1)

        try alice.cipher.processBundle(bundle)
        let first = try alice.cipher.seal(.text("one"), for: bob.account)
        _ = try bob.receive(first)

        #expect(throws: SignalError.self) { try bob.store.loadKyberPreKey(id: kyberId, context: NullContext()) }
        #expect(throws: SignalError.self) { try bob.store.loadPreKey(id: ecId, context: NullContext()) }
        #expect(try bob.store.oneTimeKyberPreKeyCount() == 0)

        // Replaying the first message after the session is gone cannot reuse the deleted key.
        try bob.store.deleteSession(for: alice.account)
        #expect(throws: SessionError.self) { try bob.receive(first) }
    }

    @Test("the Kyber last-resort key is kept but never accepted twice with the same base key (§5)")
    func lastResortReuse() throws {
        let bob = TestParty()
        let bundle = try bob.bundle(withOneTimeKeys: false, kyberOneTime: false)
        let lastResortId = bundle.kyberPreKey.keyId

        let alice = TestParty()
        try alice.cipher.processBundle(bundle)
        let first = try alice.cipher.seal(.text("one"), for: bob.account)
        _ = try bob.receive(first)
        #expect(try bob.store.loadKyberPreKey(id: lastResortId, context: NullContext()).id == lastResortId)

        try bob.store.deleteSession(for: alice.account)
        #expect(throws: SessionError.self) { try bob.receive(first) }

        // A different sender (fresh base key) may still use the last-resort key.
        let carol = TestParty()
        try carol.cipher.processBundle(bundle)
        #expect(try carol.send(.text("from carol"), to: bob).content.textBody == "from carol")
    }

    @Test("a changed identity in a bundle blocks sending until approved (§5)")
    func identityChangeOnSend() throws {
        let alice = TestParty()
        let bob = TestParty()
        try alice.cipher.processBundle(try bob.bundle())
        _ = try alice.send(.text("before"), to: bob)

        let reinstalledBob = TestParty(account: bob.account)  // same account, new identity
        #expect(throws: SessionError.identityChanged(bob.account)) {
            try alice.cipher.processBundle(try reinstalledBob.bundle())
        }
        guard case .changed(let previous, let current) = try alice.store.identityTrust(for: bob.account) else {
            Issue.record("expected a changed identity")
            return
        }
        #expect(previous == bob.identity.identityKey)
        #expect(current == reinstalledBob.identity.identityKey)
        #expect(throws: SessionError.identityChanged(bob.account)) {
            try alice.cipher.seal(.text("blocked"), for: bob.account)
        }

        try alice.store.approveIdentity(current, for: bob.account)
        try alice.cipher.processBundle(try reinstalledBob.bundle())
        #expect(try alice.send(.text("after"), to: reinstalledBob).content.textBody == "after")
    }

    @Test("a bundle with the previously approved identity after a change needs no approval")
    func revertedIdentityChange() throws {
        let alice = TestParty()
        let bob = TestParty()
        try alice.cipher.processBundle(try bob.bundle())
        _ = try alice.send(.text("before"), to: bob)

        let impostor = TestParty(account: bob.account)
        #expect(throws: SessionError.identityChanged(bob.account)) {
            try alice.cipher.processBundle(try impostor.bundle())
        }
        try alice.cipher.processBundle(try bob.bundle())
        #expect(try alice.store.identityTrust(for: bob.account) == .trusted(bob.identity.identityKey, verified: false))
        #expect(try alice.send(.text("after"), to: bob).content.textBody == "after")
    }

    @Test("a message from a new identity is accepted, flagged, and blocks replies until approved")
    func identityChangeOnReceive() throws {
        let alice = TestParty()
        let bob = TestParty()
        try alice.cipher.processBundle(try bob.bundle())
        _ = try alice.send(.text("hello"), to: bob)
        _ = try bob.send(.text("hi"), to: alice)

        let reinstalledBob = TestParty(account: bob.account)
        try reinstalledBob.cipher.processBundle(try alice.bundle())
        let received = try reinstalledBob.send(.text("new phone"), to: alice)
        #expect(received.content.textBody == "new phone")
        #expect(received.identityChanged)

        #expect(throws: SessionError.identityChanged(bob.account)) {
            try alice.cipher.seal(.text("reply"), for: bob.account)
        }
        // Further messages from the same new identity are not flagged again.
        #expect(try !reinstalledBob.send(.text("again"), to: alice).identityChanged)

        try alice.store.approveIdentity(reinstalledBob.identity.identityKey, for: bob.account)
        #expect(try alice.send(.text("reply"), to: reinstalledBob).content.textBody == "reply")
    }

    @Test func sealingRequiresAKnownRecipient() {
        let alice = TestParty()
        let bob = TestParty()
        #expect(throws: SessionError.noSession(bob.account)) { try alice.cipher.seal(.text("x"), for: bob.account) }
    }

    @Test("bundles are checked before libsignal sees them")
    func invalidBundles() throws {
        let alice = TestParty()
        let bob = TestParty()
        let good = try bob.bundle()

        let wrongDevice = PreKeyBundleResponse(
            accountId: good.accountId, deviceId: 2, registrationId: good.registrationId,
            identityKey: good.identityKey, signedPreKey: good.signedPreKey, kyberPreKey: good.kyberPreKey,
            oneTimePreKey: good.oneTimePreKey)
        #expect(throws: SessionError.invalidBundle("deviceId")) { try alice.cipher.processBundle(wrongDevice) }

        let badSignature = PreKeyBundleResponse(
            accountId: good.accountId, deviceId: 1, registrationId: good.registrationId,
            identityKey: good.identityKey,
            signedPreKey: SignedPreKeyEntity(
                keyId: good.signedPreKey.keyId, publicKey: good.signedPreKey.publicKey,
                signature: good.signedPreKey.signature.flippingBit(at: 3)),
            kyberPreKey: good.kyberPreKey, oneTimePreKey: good.oneTimePreKey)
        #expect(throws: SessionError.self) { try alice.cipher.processBundle(badSignature) }

        let trailingBytes = PreKeyBundleResponse(
            accountId: good.accountId, deviceId: 1, registrationId: good.registrationId,
            identityKey: good.identityKey + Data([0]), signedPreKey: good.signedPreKey,
            kyberPreKey: good.kyberPreKey, oneTimePreKey: good.oneTimePreKey)
        #expect(throws: SessionError.invalidBundle("key length")) { try alice.cipher.processBundle(trailingBytes) }

        let ownBundle = try alice.bundle()
        #expect(throws: SessionError.invalidBundle("own account")) { try alice.cipher.processBundle(ownBundle) }
    }

    @Test("sealed content carries the sender and, only on request, the username (§6.1, §6.4)")
    func sealedContentFields() throws {
        let alice = TestParty()
        let bob = TestParty()
        try alice.cipher.processBundle(try bob.bundle())
        let username = try NormalizedUsername("alice_01")
        let withName = try SealedEnvelope.open(
            try alice.cipher.seal(.text("a"), for: bob.account, senderUsername: username), with: bob.identity)
        #expect(withName.senderAccountID == alice.account.bytes)
        #expect(withName.senderDeviceID == 1)
        #expect(withName.senderUsername == "alice_01")
        let withoutName = try SealedEnvelope.open(
            try alice.cipher.seal(.text("b"), for: bob.account), with: bob.identity)
        #expect(withoutName.senderUsername.isEmpty)
    }

    @Test("presence envelopes fit the 4 KiB ephemeral limit, also as PreKey messages and across ratchet steps (§4.6)")
    func presenceEnvelopeSize() throws {
        let alice = TestParty()
        let bob = TestParty()
        try alice.cipher.processBundle(try bob.bundle())
        let presence = PresencePolicy.online(reply: true)
        // Unacknowledged session: every envelope is a PreKeySignalMessage with the Kyber ciphertext.
        var largest = try alice.cipher.seal(presence, for: bob.account).count
        _ = try alice.send(.text("hello"), to: bob)
        // Alternate directions so both ratchets (and the post-quantum one) keep stepping.
        for round in 0..<40 {
            let (sender, receiver) = round.isMultiple(of: 2) ? (bob, alice) : (alice, bob)
            let envelope = try sender.cipher.seal(presence, for: receiver.account)
            largest = max(largest, envelope.count)
            _ = try receiver.receive(envelope)
        }
        #expect(largest <= RelayClient.maxEphemeralEnvelopeBytes)
    }

    @Test("structurally invalid content is rejected after decryption")
    func invalidContentDropped() throws {
        let alice = TestParty()
        let bob = TestParty()
        try alice.cipher.processBundle(try bob.bundle())
        var reaction = BRPReaction()
        reaction.targetMessageID = Data([1, 2, 3])
        let bad = BRPContent(data: BRPDataMessage(payload: .reaction(reaction)))
        #expect(throws: SessionError.invalidContent) { try alice.send(bad, to: bob) }
    }
}
