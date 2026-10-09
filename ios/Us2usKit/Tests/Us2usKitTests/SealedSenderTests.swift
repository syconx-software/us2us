import Foundation
import LibSignalClient
import Testing

@testable import Us2usKit

@Suite("Sealed envelope (§6.1, §6.2)")
struct SealedEnvelopeTests {
    let recipient = IdentityKeyPair.generate()

    private func sample() -> BRPSealedContent {
        var content = BRPSealedContent()
        content.senderAccountID = .id(14)
        content.senderDeviceID = 1
        content.type = 3
        content.ciphertext = Data([1, 2, 3, 4, 5])
        content.senderUsername = "alice_01"
        return content
    }

    @Test func roundTrip() throws {
        let envelope = try SealedEnvelope.seal(sample(), to: recipient.identityKey)
        #expect(envelope.first == 0x01)
        #expect(try SealedEnvelope.open(envelope, with: recipient) == sample())
    }

    @Test("every envelope uses a fresh encapsulation")
    func unlinkable() throws {
        let a = try SealedEnvelope.seal(sample(), to: recipient.identityKey)
        let b = try SealedEnvelope.seal(sample(), to: recipient.identityKey)
        #expect(a != b)
    }

    @Test("HPKE parameters are info \"BRP/sealed/v1\" and aad 0x01")
    func parameters() throws {
        let plaintext: Data = try sample().serializedBytes()
        let envelope =
            Data([0x01]) + recipient.publicKey.seal(plaintext, info: "BRP/sealed/v1", associatedData: Data([0x01]))
        #expect(try SealedEnvelope.open(envelope, with: recipient) == sample())

        let wrongInfo =
            Data([0x01]) + recipient.publicKey.seal(plaintext, info: "BRP/sealed/v2", associatedData: Data([0x01]))
        #expect(throws: SealedEnvelopeError.decryptionFailed) { try SealedEnvelope.open(wrongInfo, with: recipient) }
        let wrongAad =
            Data([0x01]) + recipient.publicKey.seal(plaintext, info: "BRP/sealed/v1", associatedData: Data([0x02]))
        #expect(throws: SealedEnvelopeError.decryptionFailed) { try SealedEnvelope.open(wrongAad, with: recipient) }
    }

    @Test("tampering, wrong recipient and unknown versions fail")
    func tamper() throws {
        let envelope = try SealedEnvelope.seal(sample(), to: recipient.identityKey)
        for index in [1, 2, 20, envelope.count / 2, envelope.count - 1] {
            #expect(throws: SealedEnvelopeError.decryptionFailed) {
                try SealedEnvelope.open(envelope.flippingBit(at: index), with: recipient)
            }
        }
        #expect(throws: SealedEnvelopeError.decryptionFailed) { try SealedEnvelope.open(envelope, with: .generate()) }
        var versioned = envelope
        versioned[0] = 0x02
        #expect(throws: SealedEnvelopeError.unsupportedVersion) { try SealedEnvelope.open(versioned, with: recipient) }
        #expect(throws: SealedEnvelopeError.unsupportedVersion) { try SealedEnvelope.open(Data(), with: recipient) }
        #expect(throws: SealedEnvelopeError.decryptionFailed) {
            try SealedEnvelope.open(envelope.prefix(10), with: recipient)
        }
    }

    @Test("garbage that decrypts but is not protobuf is malformed")
    func malformedPlaintext() throws {
        let envelope =
            Data([0x01])
            + recipient.publicKey.seal(Data([0xFF, 0xFF, 0xFF]), info: "BRP/sealed/v1", associatedData: Data([0x01]))
        #expect(throws: SealedEnvelopeError.malformedContent) { try SealedEnvelope.open(envelope, with: recipient) }
    }
}

/// An in-memory directory that counts lookups.
actor FakeDirectory: IdentityDirectory {
    var identities: [AccountId: IdentityKey] = [:]
    var usernames: [NormalizedUsername: AccountId] = [:]
    var cachedIdentities: [AccountId: IdentityKey] = [:]
    private(set) var lookups = 0
    var failure: Error?

    func set(identity: IdentityKey?, for account: AccountId) { identities[account] = identity }
    func set(username: NormalizedUsername, to account: AccountId) { usernames[username] = account }
    func setCached(identity: IdentityKey, for account: AccountId) { cachedIdentities[account] = identity }
    func setFailure(_ error: Error?) { failure = error }

    func identityKey(of account: AccountId, refresh: Bool) async throws -> IdentityKey? {
        if let failure { throw failure }
        lookups += 1
        if !refresh, let cached = cachedIdentities[account] { return cached }
        return identities[account]
    }

    func account(for username: NormalizedUsername, refresh: Bool) async throws -> AccountId? {
        if let failure { throw failure }
        lookups += 1
        return usernames[username]
    }
}

@Suite("Sender verification (§6.3)")
struct SenderVerifierTests {
    let alice = TestParty(), bob = TestParty()
    let directory = FakeDirectory()

    private func firstMessage(username: NormalizedUsername? = nil) throws -> BRPSealedContent {
        try alice.cipher.processBundle(try bob.bundle())
        return try SealedEnvelope.open(
            try alice.cipher.seal(.text("hi"), for: bob.account, senderUsername: username), with: bob.identity)
    }

    @Test func preKeyMessageMatchingTheRelayIdentityPasses() async throws {
        await directory.set(identity: alice.identity.identityKey, for: alice.account)
        let verified = try await SenderVerifier(directory: directory).verify(try firstMessage())
        #expect(verified == VerifiedSender(account: alice.account, username: nil))
    }

    @Test("a forged sender claim is dropped")
    func identityMismatch() async throws {
        await directory.set(identity: IdentityKeyPair.generate().identityKey, for: alice.account)
        await #expect(throws: SenderVerificationError.identityMismatch) {
            try await SenderVerifier(directory: directory).verify(try firstMessage())
        }
    }

    @Test func unknownSender() async throws {
        await #expect(throws: SenderVerificationError.unknownSender) {
            try await SenderVerifier(directory: directory).verify(try firstMessage())
        }
    }

    @Test("a stale cached identity is refreshed once before failing")
    func staleCache() async throws {
        await directory.set(identity: alice.identity.identityKey, for: alice.account)
        await directory.setCached(identity: IdentityKeyPair.generate().identityKey, for: alice.account)
        let verified = try await SenderVerifier(directory: directory).verify(try firstMessage())
        #expect(verified.account == alice.account)
        #expect(await directory.lookups == 2)
    }

    @Test("sender_username must point to the sender")
    func username() async throws {
        let name = try NormalizedUsername("alice_01")
        await directory.set(identity: alice.identity.identityKey, for: alice.account)
        let sealed = try firstMessage(username: name)

        await #expect(throws: SenderVerificationError.usernameMismatch) {
            try await SenderVerifier(directory: directory).verify(sealed)
        }
        await directory.set(username: name, to: bob.account)
        await #expect(throws: SenderVerificationError.usernameMismatch) {
            try await SenderVerifier(directory: directory).verify(sealed)
        }
        await directory.set(username: name, to: alice.account)
        let verified = try await SenderVerifier(directory: directory).verify(sealed)
        #expect(verified.username == name)
    }

    @Test("a non-normalized username is rejected, not normalized")
    func lookAlikeUsername() async throws {
        await directory.set(identity: alice.identity.identityKey, for: alice.account)
        var sealed = try firstMessage()
        sealed.senderUsername = "Alice_01"
        await #expect(throws: SenderVerificationError.invalidUsername) {
            try await SenderVerifier(directory: directory).verify(sealed)
        }
    }

    @Test("messages on an existing session need no lookup")
    func whisperSkipsLookup() async throws {
        try alice.cipher.processBundle(try bob.bundle())
        _ = try alice.send(.text("a"), to: bob)
        _ = try bob.send(.text("b"), to: alice)
        let sealed = try SealedEnvelope.open(try alice.cipher.seal(.text("c"), for: bob.account), with: bob.identity)
        #expect(sealed.type == 2)
        _ = try await SenderVerifier(directory: directory).verify(sealed)
        #expect(await directory.lookups == 0)
    }

    @Test("a PreKeySignalMessage carrying the stored identity key needs no lookup")
    func storedIdentitySkipsLookup() async throws {
        let stored = alice.identity.identityKey
        let verified = try await SenderVerifier(directory: directory, storedIdentity: { _ in stored })
            .verify(try firstMessage())
        #expect(verified.account == alice.account)
        #expect(await directory.lookups == 0)
    }

    @Test("a stored key that differs is checked against the relay")
    func differentStoredIdentityIsLookedUp() async throws {
        let other = IdentityKeyPair.generate().identityKey
        await directory.set(identity: alice.identity.identityKey, for: alice.account)
        _ = try await SenderVerifier(directory: directory, storedIdentity: { _ in other }).verify(try firstMessage())
        #expect(await directory.lookups == 1)
        await directory.set(identity: other, for: alice.account)
        await #expect(throws: SenderVerificationError.identityMismatch) {
            try await SenderVerifier(directory: directory, storedIdentity: { _ in other }).verify(try firstMessage())
        }
    }

    @Test("sender_username is checked on an existing session too (§6.3 step 3)")
    func usernameOnWhisperMessage() async throws {
        let name = try NormalizedUsername("alice_01")
        try alice.cipher.processBundle(try bob.bundle())
        _ = try alice.send(.text("a"), to: bob)
        _ = try bob.send(.text("b"), to: alice)
        let sealed = try SealedEnvelope.open(
            try alice.cipher.seal(.text("c"), for: bob.account, senderUsername: name), with: bob.identity)
        #expect(sealed.type == 2)
        await directory.set(username: name, to: bob.account)
        await #expect(throws: SenderVerificationError.usernameMismatch) {
            try await SenderVerifier(directory: directory).verify(sealed)
        }
        await directory.set(username: name, to: alice.account)
        #expect(try await SenderVerifier(directory: directory).verify(sealed).username == name)
        await directory.setFailure(RelayError.rateLimited(retryAfter: 60))
        await #expect(throws: RelayError.rateLimited(retryAfter: 60)) {
            try await SenderVerifier(directory: directory).verify(sealed)
        }
    }

    @Test(
        "malformed headers are dropped",
        arguments: [
            { (s: inout BRPSealedContent) in s.senderAccountID = Data(count: 15) },
            { (s: inout BRPSealedContent) in s.senderDeviceID = 2 },
            { (s: inout BRPSealedContent) in s.type = 7 },
            { (s: inout BRPSealedContent) in s.ciphertext = Data([1, 2, 3]) },
        ] as [@Sendable (inout BRPSealedContent) -> Void])
    func malformed(_ mutate: @Sendable (inout BRPSealedContent) -> Void) async throws {
        await directory.set(identity: alice.identity.identityKey, for: alice.account)
        var sealed = try firstMessage()
        mutate(&sealed)
        await #expect(throws: SenderVerificationError.self) {
            try await SenderVerifier(directory: directory).verify(sealed)
        }
    }

    @Test("lookup failures are not verification failures (retry later)")
    func transientFailure() async throws {
        await directory.setFailure(RelayError.network(.notConnectedToInternet))
        let sealed = try firstMessage()
        await #expect(throws: RelayError.network(.notConnectedToInternet)) {
            try await SenderVerifier(directory: directory).verify(sealed)
        }
    }
}
