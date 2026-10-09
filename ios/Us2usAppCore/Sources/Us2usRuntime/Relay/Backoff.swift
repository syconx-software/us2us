import Foundation
import Us2usKit

/// Reconnect and retry delays: exponential with "full jitter" (a uniformly
/// random delay up to the exponential bound), so phones that lost the relay
/// at the same moment (relay restart, close code `1001`) do not come back in
/// lock-step, and a relay outage costs it little.
public struct Backoff: Sendable {
    public var base: TimeInterval
    public var cap: TimeInterval
    /// Never retry faster than this.
    public var floor: TimeInterval
    private let random: @Sendable (ClosedRange<Double>) -> Double

    public init(
        base: TimeInterval = 1, cap: TimeInterval = 60, floor: TimeInterval = 0.5,
        random: @escaping @Sendable (ClosedRange<Double>) -> Double = { Double.random(in: $0) }
    ) {
        self.base = base
        self.cap = cap
        self.floor = floor
        self.random = random
    }

    /// The exponential bound after `failures` consecutive failures (1, 2, …).
    public func bound(forFailures failures: Int) -> TimeInterval {
        guard failures > 0 else { return 0 }
        let exponent = Double(min(failures - 1, 30))
        return min(cap, base * pow(2, exponent))
    }

    /// The delay before the next attempt.
    public func delay(forFailures failures: Int) -> TimeInterval {
        guard failures > 0 else { return 0 }
        let bound = bound(forFailures: failures)
        return max(floor, random(0...bound))
    }

    /// The delay before retrying after `error`: the relay's `Retry-After`
    /// wins for `429`; a replaced connection (another session of this
    /// account, close code `4001`) waits at least 30 s so two installations
    /// cannot keep replacing each other in a tight loop.
    public func delay(forFailures failures: Int, after error: Error?) -> TimeInterval {
        let delay = delay(forFailures: failures)
        switch error as? RelayError {
        case .rateLimited(let retryAfter?)?: return max(delay, min(retryAfter, 3600))
        case .connectionReplaced?: return max(delay, 30)
        default: return delay
        }
    }
}
