import CryptoKit
import Foundation

/// A relay-issued challenge (PROTOCOL.md §4.2). The token is opaque.
public struct PowChallenge: Codable, Sendable, Equatable {
    public let token: String
    public let difficulty: Int
    /// Milliseconds since the Unix epoch.
    public let expiresAt: Int64

    public init(token: String, difficulty: Int, expiresAt: Int64) {
        self.token = token
        self.difficulty = difficulty
        self.expiresAt = expiresAt
    }
}

/// A solved challenge as submitted to the relay (`nonce` is a decimal string, §2).
public struct PowSolution: Codable, Sendable, Equatable {
    public let token: String
    public let nonce: String

    public init(token: String, nonce: UInt64) {
        self.token = token
        self.nonce = String(nonce)
    }

    /// The `X-PoW: <token>:<nonce>` header value.
    public var headerValue: String { "\(token):\(nonce)" }
}

public enum ProofOfWorkError: Error, Equatable, Sendable {
    case invalidDifficulty(Int)
    /// The nonce space was exhausted (never happens for sane difficulties).
    case unsolvable
    /// A relay challenge was not solved: harder than any relay issues, or it
    /// took longer than the time allowed. Like a network failure, the caller
    /// retries later with a fresh challenge.
    case abandoned
}

/// Anti-spam proof-of-work (PROTOCOL.md §4.2): find a 64-bit nonce such that
/// `SHA-256(UTF-8(token) ‖ uint64_be(nonce))` has at least `difficulty`
/// leading zero bits. Solving runs on the phone; the relay verifies with one hash.
public enum ProofOfWork {
    public static let maxDifficulty = 256
    /// The highest difficulty a relay ever issues (28 bits; registration
    /// rises to at most 26 under load). A harder challenge is refused instead
    /// of burning the battery (audit 2026-10-28 L-10).
    public static let maxRelayDifficulty = 28
    /// How long one relay challenge may take before it is given up.
    public static let relayTimeout: TimeInterval = 180

    public static func isValid(token: String, nonce: UInt64, difficulty: Int) -> Bool {
        guard (0...maxDifficulty).contains(difficulty) else { return false }
        var hasher = SHA256()
        hasher.update(data: Data(token.utf8))
        withUnsafeBytes(of: nonce.bigEndian) { hasher.update(bufferPointer: $0) }
        return hasher.finalize().withUnsafeBytes { leadingZeroBits(in: $0) } >= difficulty
    }

    /// Solves `challenge` using all CPU cores. Cancelling the calling task
    /// stops every worker promptly and throws `CancellationError`.
    public static func solve(_ challenge: PowChallenge) async throws -> PowSolution {
        let nonce = try await solve(token: challenge.token, difficulty: challenge.difficulty)
        return PowSolution(token: challenge.token, nonce: nonce)
    }

    /// Solves a challenge the relay issued: refused above
    /// ``maxRelayDifficulty`` and given up after `timeout` seconds, both as
    /// ``ProofOfWorkError/abandoned``. Cancelling the calling task still
    /// throws `CancellationError`.
    public static func solveRelayChallenge(
        _ challenge: PowChallenge, timeout: TimeInterval = relayTimeout
    ) async throws -> PowSolution {
        guard (0...maxRelayDifficulty).contains(challenge.difficulty) else { throw ProofOfWorkError.abandoned }
        let nonce = try await withThrowingTaskGroup(of: UInt64?.self) { group in
            group.addTask { try await solve(token: challenge.token, difficulty: challenge.difficulty) }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(max(0, timeout) * 1_000_000_000))
                return nil
            }
            defer { group.cancelAll() }
            // The first to finish decides: a nonce, or the timer.
            guard let first = try await group.next(), let nonce = first else {
                try Task.checkCancellation()
                throw ProofOfWorkError.abandoned
            }
            return nonce
        }
        return PowSolution(token: challenge.token, nonce: nonce)
    }

    public static func solve(
        token: String,
        difficulty: Int,
        workers: Int = ProcessInfo.processInfo.activeProcessorCount
    ) async throws -> UInt64 {
        guard (0...maxDifficulty).contains(difficulty) else { throw ProofOfWorkError.invalidDifficulty(difficulty) }
        let workers = max(1, workers)
        let progress = SearchState()
        // The hashing loops block their threads for seconds, so they run on
        // GCD rather than on Swift's cooperative pool, which they would starve.
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<UInt64, Error>) in
                DispatchQueue.global(qos: .userInitiated).async {
                    // Worker i tries nonces i, i + workers, i + 2·workers, …
                    DispatchQueue.concurrentPerform(iterations: workers) { worker in
                        let nonce = search(
                            token: token, difficulty: difficulty, start: UInt64(worker), stride: UInt64(workers),
                            shouldStop: { progress.isFinished })
                        if let nonce { progress.found(nonce) }
                    }
                    switch progress.outcome {
                    case .found(let nonce): continuation.resume(returning: nonce)
                    case .cancelled: continuation.resume(throwing: CancellationError())
                    case .running: continuation.resume(throwing: ProofOfWorkError.unsolvable)
                    }
                }
            }
        } onCancel: {
            progress.cancel()
        }
    }

    /// Sequential search from `start` in steps of `stride`; returns the first
    /// solution, or `nil` when the nonce space is exhausted or `shouldStop`
    /// returns true. Deterministic, which test-vector generation relies on.
    static func search(
        token: String, difficulty: Int, start: UInt64, stride: UInt64, shouldStop: () -> Bool = { false }
    ) -> UInt64? {
        // Hash the token once and copy the midstate for every nonce.
        var prefix = SHA256()
        prefix.update(data: Data(token.utf8))
        var nonceBytes = [UInt8](repeating: 0, count: 8)
        var nonce = start
        var iterations: UInt32 = 0
        while true {
            withUnsafeBytes(of: nonce.bigEndian) { source in
                nonceBytes.withUnsafeMutableBytes { $0.copyMemory(from: source) }
            }
            var hasher = prefix
            nonceBytes.withUnsafeBytes { hasher.update(bufferPointer: $0) }
            let digest = hasher.finalize()
            if digest.withUnsafeBytes({ leadingZeroBits(in: $0) }) >= difficulty { return nonce }

            iterations &+= 1
            if iterations & 0x3FFF == 0, shouldStop() { return nil }
            let (next, overflow) = nonce.addingReportingOverflow(stride)
            if overflow { return nil }
            nonce = next
        }
    }

    /// Shared by the workers of one solve: the first solution or a
    /// cancellation stops everyone.
    private final class SearchState: Sendable {
        enum Outcome {
            case running
            case found(UInt64)
            case cancelled
        }

        private let state = Locked(Outcome.running)

        var outcome: Outcome { state.withLock { $0 } }

        var isFinished: Bool {
            if case .running = outcome { return false }
            return true
        }

        func found(_ nonce: UInt64) {
            state.withLock { if case .running = $0 { $0 = .found(nonce) } }
        }

        func cancel() {
            state.withLock { if case .running = $0 { $0 = .cancelled } }
        }
    }

    static func leadingZeroBits(in bytes: UnsafeRawBufferPointer) -> Int {
        var bits = 0
        for byte in bytes {
            if byte == 0 {
                bits += 8
            } else {
                return bits + byte.leadingZeroBitCount
            }
        }
        return bits
    }
}
