import CryptoKit
import Foundation

/// The per-guess hardware step of vault format v2 (PROTOCOL.md §5.2): a
/// pseudorandom function `hw = HWPRF(codeKey)` under a key that is created in
/// the phone's security hardware and cannot be exported. Every unlock attempt,
/// right or wrong, needs one evaluation by that hardware on this phone, so the
/// vault file and the device secret copied out of the phone are not enough to
/// guess the code anywhere else.
///
/// The app uses `SecureEnclaveVaultKey` (Us2usRuntime), which implements
/// ``VaultCurvePRF`` with a Secure Enclave key. Software stand-ins exist only
/// in tests: a vault sealed with one is no stronger than v1, so production
/// code never writes v2 with anything but the hardware.
public protocol VaultHardwareKey: Sendable {
    /// Whether a key can be created and used here (iOS:
    /// `SecureEnclave.isAvailable`). New vaults are v2 only where this is
    /// true; elsewhere (e.g. a Simulator without one) they stay v1.
    var isAvailable: Bool { get }
    /// Creates a new key, replacing any previous one (e.g. the leftover of an
    /// interrupted migration).
    func createKey() throws
    /// `hw` for `codeKey` under the current key: 32 bytes, always the same for
    /// the same key and input. Throws ``VaultHardwareKeyError/keyMissing``
    /// when there is no key.
    func evaluate(codeKey: Data) throws -> Data
    /// Deletes the key; deleting a missing key is not an error.
    func deleteKey() throws
}

public enum VaultHardwareKeyError: Error, Equatable, Sendable {
    /// There is no key: never created, deleted, or its Keychain item is gone.
    /// A v2 vault can then never be opened on this phone again.
    case keyMissing
    /// This device has no hardware for the key (e.g. some Simulators).
    case unavailable
    /// No curve point within ``VaultCurvePRF/maxCounter`` tries (probability
    /// 2⁻⁶⁴ per code key; fails closed).
    case noCurvePoint
}

/// The vault format version, recorded in byte 4 of the header (§5.2).
public enum VaultFormatVersion: UInt8, Sendable, CaseIterable {
    /// `KEK` from the code key and the device secret.
    case v1 = 0x01
    /// `KEK` also from the hardware step (``VaultHardwareKey``).
    case v2 = 0x02
}

/// The iOS hardware PRF of vault format v2 (§5.2), a 2HashDH construction:
/// the code key is hashed to a P-256 point whose discrete logarithm nobody
/// knows, the hardware key `k` multiplies it (ECDH), and the x-coordinate of
/// the result is hashed:
///
///     P  = H2C(codeKey): the first ctr = 0, 1, …, 63 (one byte) for which
///          x = SHA-256("BRP/vault/h2c/v2" ‖ ctr ‖ codeKey) is below the field
///          prime p and 0x02 ‖ x is a valid compressed point
///     hw = SHA-256(x-coordinate of k·P)
///
/// The point must come from hashing. A point `s·G` with `s` derived from the
/// code would let anyone compute `k·(s·G) = s·(k·G)` from the public key,
/// without the hardware.
@available(iOS 16.0, macOS 13.0, *)
public enum VaultCurvePRF {
    /// Counters tried before giving up (§5.2).
    public static let maxCounter = 64
    static let label = Data("BRP/vault/h2c/v2".utf8)
    /// The P-256 field prime p = 2²⁵⁶ − 2²²⁴ + 2¹⁹² + 2⁹⁶ − 1, big-endian.
    static let fieldPrime = Data([
        0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
    ])

    /// `hw` for `codeKey`; `agree` is the key's ECDH with the hashed point
    /// (in the app, the Secure Enclave's `sharedSecretFromKeyAgreement`).
    public static func evaluate(
        codeKey: Data, agree: (P256.KeyAgreement.PublicKey) throws -> SharedSecret
    ) throws -> Data {
        let point = try hashToCurve(codeKey).point
        let shared = try agree(point)
        return shared.withUnsafeBytes { bytes in
            var hash = SHA256()
            hash.update(bufferPointer: bytes)
            return Data(hash.finalize())
        }
    }

    /// H2C: the point for `codeKey` and the counter that produced it. The
    /// point lives in a CryptoKit key that cannot be wiped, and it is as
    /// sensitive as `codeKey` (it confirms a guessed code offline); the
    /// buffers it was made from are zeroed.
    public static func hashToCurve(
        _ codeKey: Data
    ) throws(VaultHardwareKeyError) -> (point: P256.KeyAgreement.PublicKey, counter: Int) {
        try hashToCurve(codeKey, maxCounter: maxCounter)
    }

    static func hashToCurve(
        _ codeKey: Data, maxCounter: Int
    ) throws(VaultHardwareKeyError) -> (point: P256.KeyAgreement.PublicKey, counter: Int) {
        precondition((0...256).contains(maxCounter), "the counter is one byte")
        for counter in 0..<maxCounter {
            var input = Data(capacity: label.count + 1 + codeKey.count)
            input.append(label)
            input.append(UInt8(counter))
            input.append(codeKey)
            var x = Data(SHA256.hash(data: input))
            input.zeroize()
            defer { x.zeroize() }
            // Checked here so that the rule does not depend on how a library
            // treats an x-coordinate of p or more.
            guard x.lexicographicallyPrecedes(fieldPrime) else { continue }
            var compressed = Data([0x02])
            compressed.append(x)
            defer { compressed.zeroize() }
            if let point = try? P256.KeyAgreement.PublicKey(compressedRepresentation: compressed) {
                return (point, counter)
            }
        }
        throw .noCurvePoint
    }
}
