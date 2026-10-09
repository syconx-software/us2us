import Foundation
import Security

/// Cryptographically secure random bytes from the system CSPRNG.
enum SecureRandom {
    static func bytes(_ count: Int) -> Data {
        var data = Data(count: count)
        let status = data.withUnsafeMutableBytes { buffer in
            SecRandomCopyBytes(kSecRandomDefault, count, buffer.baseAddress!)
        }
        // SecRandomCopyBytes only fails if the kernel RNG is unavailable; there
        // is no safe way to continue without randomness.
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed: \(status)")
        return data
    }
}

/// Comparisons of secret values (MACs, digests, keys) that do not leak the
/// position of the first difference through timing.
enum ConstantTime {
    static func equals<A: DataProtocol, B: DataProtocol>(_ lhs: A, _ rhs: B) -> Bool {
        // Lengths are public in every use (fixed-size MACs and digests).
        guard lhs.count == rhs.count else { return false }
        var difference: UInt8 = 0
        for (a, b) in zip(lhs, rhs) {
            difference |= a ^ b
        }
        return difference == 0
    }
}

/// A value guarded by a lock, for state shared by `Sendable` reference types.
final class Locked<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Value

    init(_ value: Value) {
        self.value = value
    }

    func withLock<Result, Failure: Error>(_ body: (inout Value) throws(Failure) -> Result) throws(Failure) -> Result {
        lock.lock()
        defer { lock.unlock() }
        return try body(&value)
    }
}
