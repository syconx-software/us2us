import Foundation
import Network
import Us2usKit

// The network of a direct transfer (PROTOCOL.md §5.6): plain TCP between two
// phones on the same network. Everything secret is already encrypted by the
// channel in Us2usKit; this file only moves bytes and never touches the relay.

/// What the new phone received: a backup file in protected storage and its password.
public struct DeviceTransferDelivery: Sendable {
    public let file: URL
    public let password: String
    /// The history, until the new vault takes it in.
    public let history: DeviceTransferSpool
}

public enum DeviceTransferNetworkError: Error, Equatable, Sendable {
    /// This phone has no address on a local network (no Wi-Fi, no hotspot).
    case noLocalNetwork
    /// None of the invitation's addresses answered: not on the same network.
    case unreachable
}

/// One TCP connection. A read or write that stays unanswered ends the connection.
final class DeviceTransferNetworkStream: DeviceTransferStream, @unchecked Sendable {
    /// Between two phones side by side nothing takes this long.
    private static let stallLimit: TimeInterval = 20

    private let connection: NWConnection
    private let queue = DispatchQueue(label: "com.syconx.us2us.transfer")

    init(_ connection: NWConnection) {
        self.connection = connection
    }

    func start() {
        connection.start(queue: queue)
    }

    /// Waits until the connection is up; `.waiting` (the local-network question
    /// is on screen, or no route yet) is not an answer. Cancelling ends it.
    func waitUntilReady() async throws {
        let gate = ResumeOnce<Void>()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
                gate.set(continuation)
                connection.stateUpdateHandler = { state in
                    switch state {
                    case .ready: gate.resume(.success(()))
                    case .failed: gate.resume(.failure(DeviceTransferNetworkError.unreachable))
                    // Someone answered that nobody listens there; any other wait may still end well.
                    case .waiting(.posix(.ECONNREFUSED)): gate.resume(.failure(DeviceTransferNetworkError.unreachable))
                    case .cancelled: gate.resume(.failure(DeviceTransferError.closed))
                    default: break
                    }
                }
                start()
            }
        } onCancel: {
            gate.resume(.failure(CancellationError()))
        }
    }

    func read(_ count: Int) async throws -> Data {
        var collected = Data()
        while collected.count < count {
            collected.append(try await receive(atMost: count - collected.count))
        }
        return collected
    }

    private func receive(atMost count: Int) async throws -> Data {
        try await guarded { connection, gate in
            connection.receive(minimumIncompleteLength: 1, maximumLength: count) { data, _, _, _ in
                if let data, !data.isEmpty {
                    gate.resume(.success(data))
                } else {
                    gate.resume(.failure(DeviceTransferError.closed))
                }
            }
        }
    }

    func write(_ data: Data) async throws {
        try await guarded { (connection, gate: ResumeOnce<Void>) in
            connection.send(
                content: data,
                completion: .contentProcessed { error in
                    gate.resume(error == nil ? .success(()) : .failure(DeviceTransferError.closed))
                })
        }
    }

    /// Runs one operation under the stall limit; cancelling the task ends it too.
    private func guarded<T: Sendable>(_ operation: (NWConnection, ResumeOnce<T>) -> Void) async throws -> T {
        let gate = ResumeOnce<T>()
        let connection = connection
        let stall = DispatchWorkItem {
            gate.resume(.failure(DeviceTransferError.timedOut))
            connection.cancel()
        }
        queue.asyncAfter(deadline: .now() + Self.stallLimit, execute: stall)
        defer { stall.cancel() }
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                gate.set(continuation)
                operation(connection, gate)
            }
        } onCancel: {
            gate.resume(.failure(CancellationError()))
            connection.cancel()
        }
    }

    func close() {
        connection.cancel()
    }
}

/// A continuation that the first of several answers resumes, also when the
/// answer comes before the continuation is set.
final class ResumeOnce<T: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<T, any Error>?
    private var early: Result<T, any Error>?
    private var isDone = false

    func set(_ continuation: CheckedContinuation<T, any Error>) {
        lock.lock()
        if let early {
            self.early = nil
            lock.unlock()
            continuation.resume(with: early)
            return
        }
        self.continuation = continuation
        lock.unlock()
    }

    func resume(_ result: Result<T, any Error>) {
        lock.lock()
        guard !isDone else {
            lock.unlock()
            return
        }
        isDone = true
        guard let continuation else {
            early = result
            lock.unlock()
            return
        }
        self.continuation = nil
        lock.unlock()
        continuation.resume(with: result)
    }
}

/// The new phone's listener for one invitation.
public final class DeviceTransferListening: @unchecked Sendable {
    /// How long a connection has to prove it read the QR code.
    private static let handshakeLimit: TimeInterval = 10
    private static let maxConnections = 8

    private let receiver = DeviceTransferReceiver()
    private let listener: NWListener
    private let queue = DispatchQueue(label: "com.syconx.us2us.transfer.listener")
    private let directory: URL
    private let lock = NSLock()
    private var open: [ObjectIdentifier: DeviceTransferNetworkStream] = [:]
    private let outcome = ResumeOnce<DeviceTransferDelivery>()
    private var isStopped = false

    public private(set) var invitation = ""
    /// A phone that read the QR code connected.
    public var onStarted: (@Sendable () -> Void)?
    public var onProgress: (@Sendable (Double) -> Void)?
    /// The connection of a phone that read the QR code broke: it may try again.
    public var onInterrupted: (@Sendable () -> Void)?
    /// What was offered does not fit on this phone, and the old phone was told.
    public var onNoSpace: (@Sendable () -> Void)?

    /// Starts listening on a port of the system's choice.
    /// - Parameter directory: where the file goes (created, complete protection).
    public init(directory: URL) throws {
        self.directory = directory
        listener = try NWListener(using: .tcp, on: .any)
    }

    /// Up and listening: `invitation` is ready to show.
    public func start() async throws {
        let addresses = DeviceTransferAddresses.local()
        guard !addresses.isEmpty else { throw DeviceTransferNetworkError.noLocalNetwork }
        // Whatever a killed app left behind goes: it cannot be opened without its password anyway.
        try? FileManager.default.removeItem(at: directory)
        try FileManager.default.createDirectory(
            at: directory, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.complete])
        let ready = ResumeOnce<UInt16>()
        listener.newConnectionHandler = { [weak self] connection in self?.serve(connection) }
        listener.stateUpdateHandler = { [weak listener] state in
            switch state {
            case .ready:
                if let port = listener?.port?.rawValue { ready.resume(.success(port)) }
            case .failed(let error):
                ready.resume(.failure(error))
            case .cancelled:
                ready.resume(.failure(DeviceTransferError.closed))
            default:
                break
            }
        }
        listener.start(queue: queue)
        let port = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<UInt16, any Error>) in
            ready.set(continuation)
        }
        invitation = receiver.invite(
            endpoints: addresses.prefix(DeviceTransferInvite.maxEndpoints).map { .init(host: $0, port: port) }
        ).text
    }

    /// The first complete file. Connections that fail do not end the wait.
    public func waitForDelivery() async throws -> DeviceTransferDelivery {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { outcome.set($0) }
        } onCancel: {
            outcome.resume(.failure(CancellationError()))
        }
    }

    /// Ends the invitation: no further connection is taken, and what is underway ends
    /// too, so the old phone never hears “received” for a file nobody will take.
    public func stop() {
        let underway = lock.withLock { () -> [DeviceTransferNetworkStream] in
            isStopped = true
            return Array(open.values)
        }
        listener.cancel()
        underway.forEach { $0.close() }
        outcome.resume(.failure(CancellationError()))
    }

    /// The directory and what is in it (after the file was handed on, or given up).
    public func removeFiles() {
        try? FileManager.default.removeItem(at: directory)
    }

    private func serve(_ connection: NWConnection) {
        let stream = DeviceTransferNetworkStream(connection)
        let accepted = lock.withLock { () -> Bool in
            guard !isStopped, open.count < Self.maxConnections else { return false }
            open[ObjectIdentifier(stream)] = stream
            return true
        }
        guard accepted else {
            connection.cancel()
            return
        }
        stream.start()
        let limit = Self.handshakeLimit
        let handshake = Task {
            try await Task.sleep(nanoseconds: UInt64(limit * 1_000_000_000))
            stream.close()
        }
        // A folder per connection: the file keeps a name that can be shown.
        let folder = directory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let destination = folder.appendingPathComponent("transfer.u2u")
        let receiver = receiver
        let started = StartedFlag()
        Task { [self] in
            var spool: DeviceTransferSpool?
            do {
                try FileManager.default.createDirectory(
                    at: folder, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
                let history = try DeviceTransferSpool(
                    directory: folder.appendingPathComponent("history", isDirectory: true),
                    attributes: [.protectionKey: FileProtectionType.complete])
                spool = history
                let password = try await receiver.receive(
                    over: stream, into: destination, attributes: [.protectionKey: FileProtectionType.complete],
                    history: history,
                    authenticated: { [self] in
                        handshake.cancel()
                        started.set()
                        onStarted?()
                    },
                    progress: { [self] in onProgress?($0) })
                outcome.resume(.success(DeviceTransferDelivery(file: destination, password: password, history: history)))
            } catch {
                spool?.remove()
                try? FileManager.default.removeItem(at: folder)
                if (error as? DeviceTransferError) == .noSpace {
                    onNoSpace?()
                } else if started.isSet, !isStoppedNow {
                    onInterrupted?()
                }
            }
            handshake.cancel()
            stream.close()
            lock.withLock { open[ObjectIdentifier(stream)] = nil }
        }
    }

    private var isStoppedNow: Bool { lock.withLock { isStopped } }

    private final class StartedFlag: @unchecked Sendable {
        private let lock = NSLock()
        private var value = false
        func set() { lock.withLock { value = true } }
        var isSet: Bool { lock.withLock { value } }
    }
}

/// The old phone's side: the first of the invitation's addresses to answer gets the file.
public enum DeviceTransferSending {
    /// How long the phones get to find each other, the local-network question included.
    private static let connectLimit: Duration = .seconds(20)

    public static func send(
        file: URL, password: String, invitation: String, history: (any DeviceTransferHistorySource)? = nil,
        progress: @escaping @Sendable (Double) -> Void
    ) async throws {
        guard let invite = DeviceTransferInvite(text: invitation) else { throw DeviceTransferError.badMessage }
        // Before connecting: over a long history this takes a moment, and the new phone
        // gives a connection only seconds to reach `OFFER`.
        var estimated: EstimatedHistory?
        if let history { estimated = EstimatedHistory(source: history, bytes: try await history.estimatedBytes()) }
        let stream = try await connect(invite)
        defer { stream.close() }
        try await DeviceTransferSender.send(
            file: file, password: password, invite: invite, over: stream, history: estimated, progress: progress)
    }

    private struct EstimatedHistory: DeviceTransferHistorySource {
        let source: any DeviceTransferHistorySource
        let bytes: UInt64

        func estimatedBytes() async throws -> UInt64 { bytes }

        func export(_ emit: @Sendable (Data) async throws -> Void) async throws {
            try await source.export(emit)
        }
    }

    private enum Attempt: Sendable {
        case connected(DeviceTransferNetworkStream)
        case failed
        case outOfTime
    }

    /// Tries every address at once; the first to answer is used, the others are closed.
    private static func connect(_ invite: DeviceTransferInvite) async throws -> DeviceTransferNetworkStream {
        // A code comes from a phone side by side: one sending this account to the
        // internet is a trap, whoever made it.
        let endpoints = invite.endpoints.filter { DeviceTransferAddresses.isPrivate($0.host) }
        guard !endpoints.isEmpty else { throw DeviceTransferNetworkError.unreachable }
        let winner = await withTaskGroup(of: Attempt.self) { group -> DeviceTransferNetworkStream? in
            for endpoint in endpoints {
                group.addTask {
                    guard let port = NWEndpoint.Port(rawValue: endpoint.port) else { return .failed }
                    let connection = NWConnection(host: NWEndpoint.Host(endpoint.host), port: port, using: .tcp)
                    let stream = DeviceTransferNetworkStream(connection)
                    do {
                        try await stream.waitUntilReady()
                        return .connected(stream)
                    } catch {
                        stream.close()
                        return .failed
                    }
                }
            }
            group.addTask {
                try? await Task.sleep(for: connectLimit)
                return .outOfTime
            }
            var winner: DeviceTransferNetworkStream?
            var unanswered = endpoints.count
            for await attempt in group {
                switch attempt {
                case .connected(let stream):
                    if winner == nil {
                        winner = stream
                        group.cancelAll()
                    } else {
                        stream.close()
                    }
                case .failed:
                    unanswered -= 1
                    if unanswered == 0 { group.cancelAll() }
                case .outOfTime:
                    group.cancelAll()
                }
            }
            return winner
        }
        if Task.isCancelled {
            winner?.close()
            throw CancellationError()
        }
        guard let winner else { throw DeviceTransferNetworkError.unreachable }
        return winner
    }
}

/// This phone's IPv4 addresses on local networks (Wi-Fi, hotspot), Wi-Fi first.
enum DeviceTransferAddresses {
    static func local() -> [String] {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return [] }
        defer { freeifaddrs(head) }
        var found: [(name: String, address: String)] = []
        for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let entry = pointer.pointee
            let flags = Int32(entry.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_RUNNING != 0, flags & IFF_LOOPBACK == 0,
                let address = entry.ifa_addr, address.pointee.sa_family == UInt8(AF_INET)
            else { continue }
            let name = String(cString: entry.ifa_name)
            guard name.hasPrefix("en") || name.hasPrefix("bridge") || name.hasPrefix("ap") else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard
                getnameinfo(
                    address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0
            else { continue }
            let text = String(cString: host)
            if isPrivate(text), !found.contains(where: { $0.address == text }) { found.append((name, text)) }
        }
        return found.sorted { rank($0.name) < rank($1.name) }.map(\.address)
    }

    private static func rank(_ name: String) -> Int { name == "en0" ? 0 : name.hasPrefix("en") ? 1 : 2 }

    /// RFC 1918 and link-local addresses: nothing routed over the internet.
    static func isPrivate(_ address: String) -> Bool {
        let octets = address.split(separator: ".").compactMap { UInt8($0) }
        guard octets.count == 4 else { return false }
        switch (octets[0], octets[1]) {
        case (10, _), (192, 168), (169, 254): return true
        case (172, 16...31): return true
        default: return false
        }
    }
}
