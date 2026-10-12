import Foundation
import LibSignalClient

/// Trust state of a contact's identity key (TOFU with change detection, PROTOCOL.md §5).
public enum IdentityTrust: Equatable, Sendable {
    /// No identity stored yet; the first one seen is trusted.
    case unknown
    case trusted(IdentityKey, verified: Bool)
    /// The contact's identity changed. Sending is blocked until the user
    /// accepts the new key with ``ProtocolStore/approveIdentity(_:for:)``.
    /// `previous` is the last approved key, or `nil` when it is unknown: a
    /// change restored from a backup (§5.5) carries only the current key.
    case changed(previous: IdentityKey?, current: IdentityKey)
}

/// How the user verified a contact's identity key (PROTOCOL.md §5).
public enum IdentityVerificationMethod: Int, Sendable, CaseIterable {
    /// The safety number was compared another way (read out, another
    /// channel) and marked as verified by hand.
    case manual = 1
    /// A QR code was scanned side by side: the contact's safety-number code,
    /// or their contact code, whose key fingerprint pinned the key.
    case qrCode = 2
}

/// When and how the contact's current identity key was verified. It belongs
/// to that key: a change of the key drops it together with the verification.
public struct IdentityVerification: Equatable, Sendable {
    public let method: IdentityVerificationMethod
    public let verifiedAt: Date

    public init(method: IdentityVerificationMethod, verifiedAt: Date) {
        self.method = method
        self.verifiedAt = verifiedAt
    }
}

/// The three independent key-id spaces (key ids are 1…16777215, §4.3).
public enum PreKeyIdSpace: Sendable, CaseIterable {
    case oneTime
    case signed
    /// Shared by Kyber one-time and Kyber last-resort keys (one libsignal store).
    case kyber
}

/// Everything the protocol layer persists. Conforming types back libsignal's
/// store protocols and add what BRP needs on top. ``InMemoryProtocolStore`` is
/// the reference implementation; a persistent store (SQLCipher, §5.3) must
/// keep exactly the same semantics.
///
/// Implementations must be safe to call from any thread. The kit performs
/// every libsignal operation on one actor, so a store is never used by two
/// sessions operations concurrently through the kit.
public protocol ProtocolStore: IdentityKeyStore, PreKeyStore, SignedPreKeyStore, KyberPreKeyStore, SessionStore,
    Sendable
{
    /// Stores a Kyber key, recording whether it is the last-resort key. Only
    /// last-resort keys survive ``markKyberPreKeyUsed(id:signedPreKeyId:baseKey:context:)``.
    func storeKyberPreKey(_ record: KyberPreKeyRecord, id: UInt32, lastResort: Bool) throws

    func signedPreKeys() throws -> [SignedPreKeyRecord]
    func removeSignedPreKey(id: UInt32) throws
    func lastResortKyberPreKeys() throws -> [KyberPreKeyRecord]
    func removeKyberPreKey(id: UInt32) throws
    func oneTimePreKeyCount() throws -> Int
    func oneTimeKyberPreKeyCount() throws -> Int

    /// Reserves `count` consecutive ids (wrapping after 16777215 to 1).
    func reserveKeyIds(_ space: PreKeyIdSpace, count: Int) throws -> [UInt32]

    func identityTrust(for account: AccountId) throws -> IdentityTrust
    /// Accepts `identity` for `account` (after a safety-number change, or after
    /// verifying it in person), which unblocks sending.
    func approveIdentity(_ identity: IdentityKey, for account: AccountId) throws
    /// Sets the verified flag of whatever key is stored (verifying also
    /// accepts it). Clearing is always safe; to verify, prefer
    /// ``verifyIdentity(_:for:verification:)``, which is bound to the key the
    /// user compared.
    func setIdentityVerified(_ verified: Bool, for account: AccountId) throws

    /// Accepts the stored key only if it is `expected` — the key whose safety
    /// number the user was shown: a pending change is approved (unverified),
    /// which unblocks sending. Returns `false` and changes nothing when
    /// another key, or none, is stored.
    func acceptIdentity(_ expected: IdentityKey, for account: AccountId) throws -> Bool
    /// Verifies the stored key only if it is `expected` — the key the user
    /// compared — and accepts it (a pending change is approved). Returns
    /// `false` and changes nothing when another key, or none, is stored.
    func verifyIdentity(_ expected: IdentityKey, for account: AccountId, verification: IdentityVerification) throws
        -> Bool
    /// Pins `identity` for `account` after it was checked side by side (a
    /// scanned contact code carries the key's fingerprint, §5): with nothing
    /// stored it is stored verified and approved, so a later bundle or
    /// message under another key is a change (sending blocked, warning shown);
    /// a stored `identity` is verified and approved. Another stored key
    /// changes nothing: returns `false` — also when `identity` is a pending
    /// change of the approved key (the client holds that other key; the
    /// change is accepted by comparing safety numbers).
    func pinVerifiedIdentity(_ identity: IdentityKey, for account: AccountId, verification: IdentityVerification)
        throws -> Bool
    /// When and how the current key was verified; `nil` when it is not
    /// verified, or was verified without a record (restored from a backup,
    /// verified by an older app version).
    func identityVerification(for account: AccountId) throws -> IdentityVerification?
    /// Records a contact's identity from an account backup as it was on the
    /// old phone (§5.5), replacing whatever is stored: approved, or — for a
    /// change the user had not accepted — ``IdentityTrust/changed(previous:current:)``
    /// with an unknown previous key, so sending stays blocked. An unapproved
    /// key is never verified.
    func restoreIdentity(_ identity: IdentityKey, for account: AccountId, approved: Bool, verified: Bool) throws

    func deleteSession(for account: AccountId) throws
    /// Forgets the contact entirely: session and identity (ghost mode with
    /// `wipe_contact`, §8.2).
    func deleteContact(_ account: AccountId) throws
}

public enum ProtocolStoreError: Error, Equatable, Sendable {
    /// libsignal passed an address that was not built by `AccountId.protocolAddress`.
    case unsupportedAddress(String)
    case corruptRecord
}

enum KeyIds {
    static let range: ClosedRange<UInt32> = 1...0xFF_FFFF

    static func next(after id: UInt32) -> UInt32 {
        id >= range.upperBound ? range.lowerBound : id + 1
    }
}
