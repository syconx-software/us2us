import Foundation

/// One inbound message at a time, from decryption until it is stored.
///
/// A decryption advances libsignal state that the app commits together with
/// the stored message (atomic receive). The one-to-one mailbox and the
/// community mailbox share the pairwise sessions and the app's staging of
/// those writes, so their receives take turns here: one path can never
/// commit the other's ratchet state before that message is stored. Waiters
/// are served in arrival order.
public final class InboundTurn: Sendable {
    private struct State {
        var busy = false
        var waiters: [CheckedContinuation<Void, Never>] = []
    }

    private let state = Locked(State())

    public init() {}

    /// Runs `body` once every earlier turn has finished.
    public func run<T>(
        isolation: isolated (any Actor)? = #isolation, _ body: () async throws -> T
    ) async rethrows -> T {
        await acquire()
        defer { release() }
        return try await body()
    }

    private func acquire() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let granted = state.withLock { state -> Bool in
                guard state.busy else {
                    state.busy = true
                    return true
                }
                state.waiters.append(continuation)
                return false
            }
            if granted { continuation.resume() }
        }
    }

    private func release() {
        let next = state.withLock { state -> CheckedContinuation<Void, Never>? in
            guard !state.waiters.isEmpty else {
                state.busy = false
                return nil
            }
            // The turn passes directly to the next waiter; `busy` stays set.
            return state.waiters.removeFirst()
        }
        next?.resume()
    }
}
