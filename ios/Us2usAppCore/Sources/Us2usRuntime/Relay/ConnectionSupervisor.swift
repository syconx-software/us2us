import Foundation
import Network
import Us2usKit

/// The state of the relay line, for the "hat" line on the chat list.
public enum ConnectionStatus: Sendable, Equatable {
    /// Not connected on purpose: locked, in the background, or not set up.
    case offline
    case connecting
    /// Connected; receiving the stored backlog until `queue_empty`.
    case syncing
    /// Connected and live: new messages arrive immediately.
    case open
    /// The device has no usable network path.
    case waitingForNetwork
    /// The connection failed; the next attempt is at the given time.
    case retrying(at: Date)
    /// The relay refused our token (rotated by a restore on another phone,
    /// or the account was deleted). No more attempts are made.
    case revoked

    /// The localized "hat" line.
    public func line(strings: RuntimeStrings = .current) -> String {
        switch self {
        case .open: return strings.string(.lineOpen)
        case .connecting, .retrying: return strings.string(.lineConnecting)
        case .syncing: return strings.string(.lineSyncing)
        case .offline: return strings.string(.lineOffline)
        case .waitingForNetwork: return strings.string(.lineWaitingForNetwork)
        case .revoked: return strings.string(.lineRevoked)
        }
    }

    public var isConnected: Bool { self == .open || self == .syncing }
}

/// Callbacks of a ``ConnectionSupervisor``.
public struct ConnectionEvents: Sendable {
    /// Persist the message; it is acknowledged when this returns. Throwing
    /// leaves it on the relay and reconnects (it arrives again).
    public var deliver: @Sendable (InboundMessage) async throws -> Void
    /// The backlog has been received (`queue_empty`).
    public var queueEmpty: @Sendable () async -> Void

    public init(
        deliver: @escaping @Sendable (InboundMessage) async throws -> Void,
        queueEmpty: @escaping @Sendable () async -> Void
    ) {
        self.deliver = deliver
        self.queueEmpty = queueEmpty
    }
}

/// Keeps the one WebSocket of §4.7 / §5.4 open while it is allowed — the app
/// is active and unlocked, or inside the 30-second background grace period —
/// and closed otherwise. Reconnects with jittered exponential backoff
/// (``Backoff``), waits for the network path, and gives up for good when the
/// relay revokes the token. Keep-alive pings (≤ 30 s) and de-duplication by
/// relay id happen inside Us2usKit's `RelayConnection` / `Messenger.run`.
public actor ConnectionSupervisor {
    private let backend: any MessagingBackend
    private let clock: any RuntimeClock
    private let backoff: Backoff
    private let events: ConnectionEvents
    private let wake = WakeSignal()

    private var allowed = false
    private var networkAvailable = true
    private var loop: Task<Void, Never>?
    private var current: (any RelayStream)?
    private var consecutiveFailures = 0
    /// When the current connection delivered its backlog (`queue_empty`).
    private var liveSince: Date?
    /// A connection counts as healthy (the backoff starts over) only after
    /// it stayed up this long: one that drops right after its backlog (a
    /// relay crash loop, a proxy timeout, a flapping network) keeps backing
    /// off instead of reconnecting every second (§1.1).
    public static let stableConnection: TimeInterval = 60
    public private(set) var status: ConnectionStatus = .offline
    /// Every status change, in order.
    public nonisolated let statusUpdates: AsyncStream<ConnectionStatus>
    private let statusContinuation: AsyncStream<ConnectionStatus>.Continuation

    public init(
        backend: any MessagingBackend, clock: any RuntimeClock = SystemClock(), backoff: Backoff = Backoff(),
        events: ConnectionEvents
    ) {
        self.backend = backend
        self.clock = clock
        self.backoff = backoff
        self.events = events
        (statusUpdates, statusContinuation) = AsyncStream.makeStream(
            of: ConnectionStatus.self, bufferingPolicy: .bufferingNewest(32))
    }

    deinit {
        statusContinuation.finish()
    }

    /// Connects (or keeps connecting) until ``stop()``.
    public func start() {
        guard status != .revoked else { return }
        allowed = true
        guard loop == nil else {
            wake.signal()  // cut a pending backoff short
            return
        }
        loop = Task { [weak self] in await self?.runLoop() }
    }

    /// Closes the socket and stops reconnecting; returns once the loop has ended.
    public func stop() async {
        allowed = false
        current?.close()
        wake.signal()
        let running = loop
        await running?.value
    }

    /// From the network path monitor: reconnect at once when a path appears.
    public func networkChanged(available: Bool) {
        let wasAvailable = networkAvailable
        networkAvailable = available
        if available && !wasAvailable { wake.signal() }
        if !available && allowed && !status.isConnected { setStatus(.waitingForNetwork) }
    }

    private func runLoop() async {
        defer { loop = nil }
        while allowed && !Task.isCancelled {
            if !networkAvailable {
                setStatus(.waitingForNetwork)
                try? await wake.wait(seconds: 60, clock: clock)
                continue
            }
            setStatus(.connecting)
            liveSince = nil
            var failure: Error?
            do {
                let stream = try await backend.openStream()
                guard allowed else {
                    stream.close()
                    break
                }
                current = stream
                setStatus(.syncing)
                let events = self.events
                try await stream.run(
                    deliver: events.deliver,
                    onQueueEmpty: { [weak self] in
                        await self?.reachedQueueEmpty()
                        await events.queueEmpty()
                    })
                current = nil
                guard allowed else { break }
            } catch {
                current = nil
                guard allowed else { break }
                if Self.isRevocation(error) {
                    RuntimeLog.relay.notice("token revoked")
                    allowed = false
                    setStatus(.revoked)
                    return
                }
                failure = error
            }
            if let liveSince, clock.now().timeIntervalSince(liveSince) >= Self.stableConnection {
                consecutiveFailures = 0
            }
            liveSince = nil
            consecutiveFailures += 1
            let delay = backoff.delay(forFailures: consecutiveFailures, after: failure)
            RuntimeLog.relay.info("connection lost: \(failure?.logLabel ?? "closed", privacy: .public)")
            setStatus(.retrying(at: clock.now().addingTimeInterval(delay)))
            try? await wake.wait(seconds: delay, clock: clock)
        }
        if status != .revoked { setStatus(.offline) }
    }

    private func reachedQueueEmpty() {
        if liveSince == nil { liveSince = clock.now() }
        setStatus(.open)
    }

    private func setStatus(_ status: ConnectionStatus) {
        guard status != self.status else { return }
        self.status = status
        statusContinuation.yield(status)
    }

    /// `401` on the upgrade or close code `4401`: the token was rotated (a
    /// restore on another phone, §5.5) or the account deleted.
    static func isRevocation(_ error: Error) -> Bool {
        if case .server(status: 401, _, _)? = error as? RelayError { return true }
        return false
    }
}

/// Watches the device's network path (no traffic of its own).
public final class NetworkPathWatcher: @unchecked Sendable {
    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "com.syconx.us2us.network-path")

    public init() {}

    public func start(_ onChange: @escaping @Sendable (Bool) -> Void) {
        monitor.pathUpdateHandler = { path in onChange(path.status == .satisfied) }
        monitor.start(queue: queue)
    }

    public func stop() {
        monitor.cancel()
    }
}
