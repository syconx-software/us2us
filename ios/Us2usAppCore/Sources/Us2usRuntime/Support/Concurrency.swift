import Foundation

/// A value guarded by a lock, for state shared by `Sendable` reference types
/// that cannot be actors (callbacks from system frameworks, synchronous APIs).
final class LockedState<Value>: @unchecked Sendable {
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

/// Wall-clock time and sleeping, injectable so timers (grace period, ghost
/// deadlines, presence refresh, backoff) can be tested without waiting.
public protocol RuntimeClock: Sendable {
    func now() -> Date
    /// Sleeps for `seconds`; throws `CancellationError` when the task is cancelled.
    func sleep(seconds: TimeInterval) async throws
}

/// The real clock.
public struct SystemClock: RuntimeClock {
    public init() {}

    public func now() -> Date { Date() }

    public func sleep(seconds: TimeInterval) async throws {
        guard seconds > 0 else {
            try Task.checkCancellation()
            return
        }
        try await Task.sleep(nanoseconds: UInt64(min(seconds, 365 * 24 * 3600) * 1_000_000_000))
    }
}

/// A signal that wakes a waiting loop early ("new work in the outbox", "the
/// network is back, stop backing off"). Waiting returns when signalled or when
/// the timeout passes. A signal while nobody waits is remembered, so the next
/// wait returns at once: work queued while the loop was busy is never missed.
final class WakeSignal: Sendable {
    private struct State {
        var pending = false
        var waiters: [Int: CheckedContinuation<Void, Never>] = [:]
        var nextId = 0
    }

    private let state = LockedState(State())

    /// Wakes everybody waiting, or the next wait if nobody is.
    func signal() {
        let waiters = state.withLock { state -> [CheckedContinuation<Void, Never>] in
            guard !state.waiters.isEmpty else {
                state.pending = true
                return []
            }
            defer { state.waiters = [:] }
            return Array(state.waiters.values)
        }
        for waiter in waiters { waiter.resume() }
    }

    /// Consumes a remembered signal.
    private func takePending() -> Bool {
        state.withLock { state in
            defer { state.pending = false }
            return state.pending
        }
    }

    /// Waits until ``signal()`` or until `clock` has slept `seconds`, whichever
    /// comes first. Throws `CancellationError` if the task is cancelled.
    func wait(seconds: TimeInterval, clock: any RuntimeClock) async throws {
        if takePending() { return }
        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask { try await clock.sleep(seconds: seconds) }
            group.addTask { [self] in await self.waitForSignal() }
            _ = try await group.next()
            group.cancelAll()
        }
    }

    private func waitForSignal() async {
        let id = state.withLock { state -> Int in
            state.nextId += 1
            return state.nextId
        }
        await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                let resumeNow = state.withLock { state -> Bool in
                    if Task.isCancelled { return true }
                    if state.pending {
                        state.pending = false
                        return true
                    }
                    state.waiters[id] = continuation
                    return false
                }
                if resumeNow { continuation.resume() }
            }
        } onCancel: {
            let waiter = state.withLock { $0.waiters.removeValue(forKey: id) }
            waiter?.resume()
        }
    }
}
