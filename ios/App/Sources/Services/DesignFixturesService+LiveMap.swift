import Foundation

// 5.2 › Canlı konum haritası in the fixtures: the live shares' positions
// (memory only), a simulated position for this phone, and the contact's
// demo share for the harness (`5.2-map`). Two places in Moda, a few hundred
// metres apart; with a running clock both move a little.

extension DesignFixturesService {
    /// selin_n — her share (m4) and her phone: Moda Caddesi.
    nonisolated static let ownFixturePosition = LivePosition(latitude: 40.9872, longitude: 29.0262, accuracy: 12)
    /// mert_k's demo share starts at Moda İskelesi (the m3 place).
    nonisolated static let peerFixturePosition = LivePosition(latitude: 40.9846, longitude: 29.0249, accuracy: 18)
    /// The demo share's message.
    nonisolated static let demoLiveShareID: MessageID = "m8"

    func liveLocations(in chat: ChatID) -> LiveLocationSnapshot {
        var shares: [LiveShare] = []
        for message in messages(in: chat) {
            guard case .liveLocation(let expiresAt, let isActive) = message.content,
                let position = livePositions[message.id]
            else { continue }
            shares.append(
                LiveShare(
                    id: message.id, direction: message.direction, startedAt: message.sentAt, position: position,
                    expiresAt: expiresAt, isStopped: !isActive))
        }
        return LiveLocationSnapshot(shares: shares)
    }

    /// selin_n's phone: at once, then (running clock) a few metres either
    /// way every 4 s. No system prompt ever: the harness sets the other states.
    func ownPositionUpdates() -> AsyncStream<OwnPositionState> {
        let initial = ownPositionOverride ?? .position(Self.ownFixturePosition)
        let (stream, continuation) = AsyncStream<OwnPositionState>.makeStream(bufferingPolicy: .bufferingNewest(1))
        continuation.yield(initial)
        guard case .position(let start) = initial, !clock.isFrozen else { return stream }
        let task = Task { @MainActor in
            var step = 0
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(4))
                step += 1
                continuation.yield(.position(DesignFixturesService.drifted(start, step: step)))
            }
        }
        continuation.onTermination = { _ in task.cancel() }
        return stream
    }

    /// “İzin ver” is granted at once; a refusal stays refused.
    func requestOwnPositionAccess() async -> Bool {
        if ownPositionOverride == .needsPermission { ownPositionOverride = nil }
        return ownPositionOverride == nil
    }

    /// The demo share walks from the pier towards selin_n, about 15 m every
    /// 3 s, and stops 30 m short of her; it ends with its share.
    func walkDemoShare(_ id: MessageID, until end: Date) {
        liveDemoTasks[id]?.cancel()
        let start = Self.peerFixturePosition
        let target = Self.ownFixturePosition
        liveDemoTasks[id] = Task { @MainActor [weak self] in
            var step = 0
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(3))
                guard let self, !Task.isCancelled, self.clock.now < end else { return }
                step += 1
                let fraction = min(0.9, Double(step) * 0.05)
                self.livePositions[id] = LivePosition(
                    latitude: start.latitude + (target.latitude - start.latitude) * fraction,
                    longitude: start.longitude + (target.longitude - start.longitude) * fraction,
                    accuracy: start.accuracy, updatedAt: self.clock.now)
            }
        }
    }

    /// A few metres around `start` (a slow circle).
    nonisolated static func drifted(_ start: LivePosition, step: Int) -> LivePosition {
        let angle = Double(step) * 0.5
        return LivePosition(
            latitude: start.latitude + 0.00004 * sin(angle), longitude: start.longitude + 0.00005 * cos(angle),
            accuracy: start.accuracy)
    }
}
