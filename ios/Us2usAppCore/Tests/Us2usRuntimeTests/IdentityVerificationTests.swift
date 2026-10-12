import Foundation
import LibSignalClient
import Testing
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime

/// The engine's side of identity verification (PROTOCOL.md §5): a key change
/// found while sending warns like one found on receive, and every decision
/// is bound to the number the user saw (``FakeBackend`` stands in for the
/// messenger's key-bound calls).
@Suite("Identity verification (§5)")
struct IdentityVerificationTests {
    private static func count(_ items: [MessageItem], _ notice: SystemNotice) -> Int {
        items.filter { $0.body == .notice(notice) }.count
    }

    @Test func aKeyChangeFoundWhileSendingWarnsOnce() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        let seen = LockedState<[EngineChange]>([])
        let changes = fixture.engine.changes
        let listener = Task {
            for await change in changes { seen.withLock { $0.append(change) } }
        }
        defer { listener.cancel() }
        await fixture.start()
        // The bundle the relay serves carries another key than the trusted one.
        fixture.backend.failNextSend(to: peer, with: SessionError.identityChanged(peer))
        try await fixture.engine.send(.text("first"), to: peer)
        try await eventually("the warning line") {
            Self.count(try await fixture.messages(peer), .safetyNumberChanged) == 1
        }
        try await eventually("the change event") { seen.withLock { $0.contains(.safetyNumberChanged(peer)) } }
        // What is queued behind it waits too, without another warning.
        try await fixture.engine.send(.text("second"), to: peer)
        await settle()
        #expect(Self.count(try await fixture.messages(peer), .safetyNumberChanged) == 1)
        #expect(seen.withLock { $0.filter { $0 == .safetyNumberChanged(peer) }.count } == 1)
        #expect(fixture.backend.sent(to: peer).isEmpty)
        await fixture.engine.stop()
    }

    @Test func markingVerifiedIsBoundToTheComparedNumber() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let key = IdentityKeyPair.generate().identityKey
        let shown = Data("number of the stored key".utf8)
        fixture.backend.setIdentity(.trusted(key, verified: false), number: shown, for: peer)

        #expect(try await fixture.engine.markVerified(peer, comparedNumber: Data("another number".utf8)) == false)
        #expect(try await fixture.engine.identityTrust(for: peer) == .trusted(key, verified: false))
        #expect(Self.count(try await fixture.messages(peer), .safetyNumberVerified) == 0)

        #expect(try await fixture.engine.markVerified(peer, comparedNumber: shown))
        #expect(try await fixture.engine.identityTrust(for: peer) == .trusted(key, verified: true))
        let record = try #require(try await fixture.engine.identityVerification(for: peer))
        #expect(record.method == .manual)
        #expect(record.verifiedAt == fixture.clock.now())
        #expect(Self.count(try await fixture.messages(peer), .safetyNumberVerified) == 1)

        // Scanning the same number again verifies again, without a second line.
        #expect(try await fixture.engine.verifySafetyNumber(peer, scanned: shown))
        #expect(try await fixture.engine.identityVerification(for: peer)?.method == .qrCode)
        #expect(Self.count(try await fixture.messages(peer), .safetyNumberVerified) == 1)

        try await fixture.engine.clearVerification(peer)
        #expect(try await fixture.engine.identityTrust(for: peer) == .trusted(key, verified: false))
        #expect(try await fixture.engine.identityVerification(for: peer) == nil)
        await fixture.engine.stop()
    }

    @Test func aKeyThatChangedWhileItWasShownIsNeitherVerifiedNorAccepted() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let old = IdentityKeyPair.generate().identityKey
        let new = IdentityKeyPair.generate().identityKey
        let oldNumber = Data("old number".utf8)
        let newNumber = Data("new number".utf8)
        fixture.backend.setIdentity(.trusted(old, verified: false), number: oldNumber, for: peer)
        // 6.1 shows the old number; meanwhile a message arrives under a new key.
        fixture.backend.setIdentity(.changed(previous: old, current: new), number: newNumber, for: peer)
        try await fixture.receive(.textMessage("new phone"), from: peer, identityChanged: true)
        try await fixture.engine.send(.text("held"), to: peer)

        // Decisions about the old number are refused: nothing is sealed or accepted.
        #expect(try await fixture.engine.markVerified(peer, comparedNumber: oldNumber) == false)
        #expect(try await fixture.engine.acceptSafetyNumberChange(peer, shownNumber: oldNumber) == false)
        #expect(try await fixture.engine.identityTrust(for: peer) == .changed(previous: old, current: new))
        await settle()
        #expect(!fixture.backend.sent(to: peer).contains { $0.data?.text.body == "held" })

        // Going on with the number now on screen — without verifying — sends.
        #expect(try await fixture.engine.acceptSafetyNumberChange(peer, shownNumber: newNumber))
        #expect(try await fixture.engine.identityTrust(for: peer) == .trusted(new, verified: false))
        try await eventually("sent after accepting") {
            fixture.backend.sent(to: peer).contains { $0.data?.text.body == "held" }
        }
        let items = try await fixture.messages(peer)
        #expect(Self.count(items, .safetyNumberAcceptedUnverified) == 1)
        #expect(Self.count(items, .safetyNumberAccepted) == 0, "the legacy case is never written")
        #expect(Self.count(items, .safetyNumberVerified) == 0, "accepting is not verifying")
        await fixture.engine.stop()
    }

    @Test func aContactCodePinsTheKeyItCommittedTo() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let scanned = IdentityKeyPair.generate().identityKey
        #expect(try await fixture.engine.verifyContactCode(peer, identityKey: scanned.serialize()))
        #expect(try await fixture.engine.identityTrust(for: peer) == .trusted(scanned, verified: true))
        #expect(try await fixture.engine.identityVerification(for: peer)?.method == .qrCode)
        #expect(Self.count(try await fixture.messages(peer), .safetyNumberVerified) == 1)

        // A contact for whom this phone holds another key: nothing is verified.
        let other = try fixture.acceptedContact()
        let stored = IdentityKeyPair.generate().identityKey
        fixture.backend.setIdentity(.trusted(stored, verified: false), number: Data("stored".utf8), for: other)
        #expect(try await fixture.engine.verifyContactCode(other, identityKey: scanned.serialize()) == false)
        #expect(try await fixture.engine.identityTrust(for: other) == .trusted(stored, verified: false))
        #expect(Self.count(try await fixture.messages(other), .safetyNumberVerified) == 0)
        await fixture.engine.stop()
    }
}
