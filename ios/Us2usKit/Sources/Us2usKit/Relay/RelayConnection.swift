import Foundation

/// A server-to-client WebSocket frame (PROTOCOL.md §4.7).
public enum RelayEvent: Sendable, Equatable {
    case message(RelayEnvelope)
    /// The backlog stored at connect time has been sent.
    case queueEmpty
    case pong
}

/// The foreground WebSocket to the relay (PROTOCOL.md §4.7): stored messages
/// first, then `queue_empty`, then live delivery. Messages stay on the relay
/// until acknowledged and may be re-sent after a reconnect, so consumers MUST
/// de-duplicate by id.
public final class RelayConnection: Sendable {
    /// Ends when the socket closes: normally after ``close()``, otherwise with
    /// a ``RelayError`` (`.connectionReplaced` for close code 4001,
    /// `.server(status: 401, …)` for a rejected token).
    public let events: AsyncThrowingStream<RelayEvent, Error>

    private let task: URLSessionWebSocketTask
    private let continuation: AsyncThrowingStream<RelayEvent, Error>.Continuation
    private let workers = Locked<[Task<Void, Never>]>([])
    private let closedByClient = Locked(false)

    init(task: URLSessionWebSocketTask, keepAliveInterval: TimeInterval) {
        self.task = task
        (events, continuation) = AsyncThrowingStream.makeStream(of: RelayEvent.self)
        continuation.onTermination = { [task, workers] _ in
            task.cancel(with: .goingAway, reason: nil)
            workers.withLock { tasks in
                for worker in tasks { worker.cancel() }
            }
        }
        task.resume()
        let receiver = Task { [weak self] () -> Void in
            await self?.receiveLoop()
        }
        let keepAlive = Task { [weak self] in
            let interval = UInt64(max(1, keepAliveInterval) * 1_000_000_000)
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: interval)
                guard !Task.isCancelled, let self else { return }
                try? await self.ping()
            }
        }
        workers.withLock { $0 = [receiver, keepAlive] }
    }

    /// Acknowledges delivered messages; the relay deletes them (§4.6).
    public func acknowledge(_ ids: [String]) async throws {
        guard !ids.isEmpty else { return }
        for start in stride(from: 0, to: ids.count, by: RelayClient.maxAckBatch) {
            let batch = Array(ids[start..<min(start + RelayClient.maxAckBatch, ids.count)])
            try await send(ClientFrame(type: "ack", ids: batch))
        }
    }

    public func ping() async throws {
        try await send(ClientFrame(type: "ping", ids: nil))
    }

    /// Closes the socket (when the app goes to the background, §5.4).
    public func close() {
        closedByClient.withLock { $0 = true }
        task.cancel(with: .normalClosure, reason: nil)
        continuation.finish()
    }

    private func send(_ frame: ClientFrame) async throws {
        let text = String(decoding: try JSONEncoder().encode(frame), as: UTF8.self)
        do {
            try await task.send(.string(text))
        } catch let error as URLError {
            throw RelayError.network(error.code)
        }
    }

    private func receiveLoop() async {
        do {
            while true {
                let data: Data
                switch try await task.receive() {
                case .string(let text): data = Data(text.utf8)
                case .data(let bytes): data = bytes
                @unknown default: continue
                }
                // Unknown frame types are ignored for forward compatibility.
                if let event = Self.decode(data) { continuation.yield(event) }
            }
        } catch {
            if closedByClient.withLock({ $0 }) {
                continuation.finish()
            } else {
                continuation.finish(throwing: closeError(for: error))
            }
        }
    }

    private func closeError(for error: Error) -> RelayError {
        if let http = task.response as? HTTPURLResponse, http.statusCode == 401 {
            return .server(status: 401, code: .unauthorized, message: nil)
        }
        switch task.closeCode.rawValue {
        case 4001: return .connectionReplaced
        case 4401: return .server(status: 401, code: .unauthorized, message: nil)
        case URLSessionWebSocketTask.CloseCode.invalid.rawValue:
            return .network((error as? URLError)?.code ?? .networkConnectionLost)
        case let code: return .connectionClosed(code: code)
        }
    }

    static func decode(_ data: Data) -> RelayEvent? {
        guard let frame = try? JSONDecoder.relay.decode(ServerFrame.self, from: data) else { return nil }
        switch frame.type {
        case "message":
            guard let id = frame.id, let timestamp = frame.serverTimestamp, let envelope = frame.envelope else {
                return nil
            }
            return .message(RelayEnvelope(id: id, serverTimestamp: timestamp, envelope: envelope))
        case "queue_empty": return .queueEmpty
        case "pong": return .pong
        default: return nil
        }
    }

    private struct ServerFrame: Decodable {
        let type: String
        let id: String?
        let serverTimestamp: Int64?
        let envelope: Data?
    }

    private struct ClientFrame: Encodable {
        let type: String
        let ids: [String]?
    }
}
