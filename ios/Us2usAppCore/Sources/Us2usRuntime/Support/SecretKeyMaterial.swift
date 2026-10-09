import Foundation

/// Key material (the vault DEK) held in memory only while the app is unlocked,
/// in a buffer this type owns and overwrites with zeros on ``wipe()`` and on
/// deallocation.
///
/// Swift and Foundation do not promise to zero memory, so this is best effort:
/// ``withData(_:)`` hands out a short-lived copy and zeroes it afterwards, but
/// callees (HKDF, CommonCrypto, SQLCipher) may hold their own copies for as
/// long as they need them. What this type guarantees is that *its* copy is
/// gone the moment the vault locks.
public final class SecretKeyMaterial: @unchecked Sendable {
    private let lock = NSLock()
    private let buffer: UnsafeMutableRawBufferPointer
    private var wiped = false

    /// Copies `bytes`. Zero your own copy afterwards (``zeroize(_:)``).
    public init(copying bytes: Data) {
        let buffer = UnsafeMutableRawBufferPointer.allocate(byteCount: max(bytes.count, 1), alignment: 16)
        buffer.initializeMemory(as: UInt8.self, repeating: 0)
        bytes.withUnsafeBytes { source in
            if let base = source.baseAddress, source.count > 0 {
                buffer.baseAddress!.copyMemory(from: base, byteCount: source.count)
            }
        }
        self.buffer = buffer
        count = bytes.count
    }

    deinit {
        zeroBuffer()
        buffer.deallocate()
    }

    public let count: Int

    public var isWiped: Bool {
        lock.lock()
        defer { lock.unlock() }
        return wiped
    }

    /// Runs `body` with a temporary copy, zeroed when `body` returns. Throws
    /// ``SecretKeyMaterialError/wiped`` after ``wipe()``. Do not let the copy
    /// escape; APIs that keep a key must make their own copy.
    public func withData<Result>(_ body: (Data) throws -> Result) throws -> Result {
        var copy: Data = try {
            lock.lock()
            defer { lock.unlock() }
            guard !wiped else { throw SecretKeyMaterialError.wiped }
            return Data(bytes: buffer.baseAddress!, count: count)
        }()
        defer { Self.zeroize(&copy) }
        return try body(copy)
    }

    /// Overwrites the key with zeros; every later use throws.
    public func wipe() {
        lock.lock()
        defer { lock.unlock() }
        guard !wiped else { return }
        wiped = true
        zeroBuffer()
    }

    private func zeroBuffer() {
        memset_s(buffer.baseAddress!, buffer.count, 0, buffer.count)
    }

    /// Overwrites `data` with zeros in place (when uniquely referenced; a
    /// shared buffer is copied by `Data` first, so only this copy is zeroed).
    public static func zeroize(_ data: inout Data) {
        let count = data.count
        guard count > 0 else { return }
        data.withUnsafeMutableBytes { raw in
            if let base = raw.baseAddress { memset_s(base, count, 0, count) }
        }
    }

    /// Constant-time comparison with `other`.
    func matches(_ other: SecretKeyMaterial) -> Bool {
        let result = try? withData { mine in
            try other.withData { theirs -> Bool in
                guard mine.count == theirs.count else { return false }
                var difference: UInt8 = 0
                for (a, b) in zip(mine, theirs) { difference |= a ^ b }
                return difference == 0
            }
        }
        return result ?? false
    }
}

public enum SecretKeyMaterialError: Error, Equatable, Sendable {
    /// The vault was locked; the key no longer exists in memory.
    case wiped
}
