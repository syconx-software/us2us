import Foundation
import LibSignalClient
import Testing

@testable import Us2usKit

@Suite("Safety numbers (§5)")
struct SafetyNumberTests {
    let alice = AccountId(uuid: UUID()), bob = AccountId(uuid: UUID())
    let aliceKey = IdentityKeyPair.generate().identityKey, bobKey = IdentityKeyPair.generate().identityKey

    @Test("both sides see the same 60 digits and each other's QR matches")
    func symmetric() throws {
        let mine = try SafetyNumber(local: alice, localIdentity: aliceKey, remote: bob, remoteIdentity: bobKey)
        let theirs = try SafetyNumber(local: bob, localIdentity: bobKey, remote: alice, remoteIdentity: aliceKey)
        #expect(mine.digits == theirs.digits)
        #expect(mine.digits.count == 60)
        #expect(mine.digits.allSatisfy { $0.isNumber })
        #expect(mine.groups.count == 12)
        #expect(mine.groups.joined() == mine.digits)
        #expect(try mine.matches(scanned: theirs.scannable))
        #expect(try theirs.matches(scanned: mine.scannable))
    }

    @Test("libsignal NumericFingerprintGenerator(5200), version 2, account id bytes as identifiers")
    func parameters() throws {
        let number = try SafetyNumber(local: alice, localIdentity: aliceKey, remote: bob, remoteIdentity: bobKey)
        let reference = try NumericFingerprintGenerator(iterations: 5200).create(
            version: 2, localIdentifier: alice.bytes, localKey: aliceKey.publicKey, remoteIdentifier: bob.bytes,
            remoteKey: bobKey.publicKey)
        #expect(number.digits == reference.displayable.formatted)
        #expect(number.scannable == reference.scannable.encoding)
    }

    @Test("a changed key changes the number")
    func changedKey() throws {
        let before = try SafetyNumber(local: alice, localIdentity: aliceKey, remote: bob, remoteIdentity: bobKey)
        let newBob = IdentityKeyPair.generate().identityKey
        let after = try SafetyNumber(local: alice, localIdentity: aliceKey, remote: bob, remoteIdentity: newBob)
        let theirs = try SafetyNumber(local: bob, localIdentity: newBob, remote: alice, remoteIdentity: aliceKey)
        #expect(before.digits != after.digits)
        #expect(try !before.matches(scanned: theirs.scannable))
    }

    @Test("a fingerprint of another version is an error, not a mismatch")
    func versionMismatch() throws {
        let number = try SafetyNumber(local: alice, localIdentity: aliceKey, remote: bob, remoteIdentity: bobKey)
        let v1 = try NumericFingerprintGenerator(iterations: 5200).create(
            version: 1, localIdentifier: bob.bytes, localKey: bobKey.publicKey, remoteIdentifier: alice.bytes,
            remoteKey: aliceKey.publicKey)
        #expect(throws: SignalError.self) { try number.matches(scanned: v1.scannable.encoding) }
    }
}
