import Foundation
import LibSignalClient

/// The safety number of a conversation (PROTOCOL.md §5): libsignal's numeric
/// fingerprint, 5200 iterations, version 2, stable identifiers = account id
/// bytes, keys = identity public keys. Both sides compute the same digits.
public struct SafetyNumber: Sendable {
    static let iterations = 5200
    static let version = 2

    private let fingerprint: Fingerprint

    public init(local: AccountId, localIdentity: IdentityKey, remote: AccountId, remoteIdentity: IdentityKey) throws {
        fingerprint = try NumericFingerprintGenerator(iterations: Self.iterations).create(
            version: Self.version,
            localIdentifier: local.bytes, localKey: localIdentity.publicKey,
            remoteIdentifier: remote.bytes, remoteKey: remoteIdentity.publicKey)
    }

    /// 60 digits.
    public var digits: String { fingerprint.displayable.formatted }

    /// Payload for the QR code; compare a scanned one with ``matches(scanned:)``.
    public var scannable: Data { fingerprint.scannable.encoding }

    /// Twelve groups of five digits, the usual display layout.
    public var groups: [String] {
        let digits = Array(self.digits)
        return stride(from: 0, to: digits.count, by: 5).map { String(digits[$0..<min($0 + 5, digits.count)]) }
    }

    /// Whether a QR payload scanned from the contact's phone matches. Throws if
    /// the payload is not a fingerprint or uses another version.
    public func matches(scanned: Data) throws -> Bool {
        try fingerprint.scannable.compare(againstEncoding: scanned)
    }
}
