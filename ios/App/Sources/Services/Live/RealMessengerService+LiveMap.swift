import Foundation
import Us2usKit
import Us2usRuntime

// 5.2 › Canlı konum haritası in the live service: the open conversation's
// live shares with their latest positions, read from the runtime's open
// messages (memory only — the app adds no copy of its own), and this
// device's position for the map (`DevicePositionWatcher`, never sent).
//
// The runtime itself keeps only each share's latest position, sealed inside
// the bubble in SQLCipher and replaced in place (PROTOCOL §8.3); the relay
// never stores ticks.

/// One live-location message of the open conversation, field by field
/// (LiveMapping decides what it means).
struct LiveLocationRecord: Equatable, Sendable {
    let id: Data
    let outgoing: Bool
    let sentAt: Date
    let latitude: Double
    let longitude: Double
    let accuracy: Float
    /// Degrees; negative when unknown.
    let heading: Float
    let expiresAt: Date
    let sequence: UInt32
    let stopped: Bool
}

extension LiveLocationRecord {
    init?(_ item: MessageItem) {
        guard item.direction != .system, case .liveLocation(let live) = item.body else { return nil }
        self.init(
            id: item.messageId, outgoing: item.direction == .outgoing, sentAt: item.sentAt, latitude: live.latitude,
            longitude: live.longitude, accuracy: live.accuracyMeters, heading: live.headingDegrees,
            expiresAt: live.expiresAt, sequence: live.sequence, stopped: live.stopped)
    }
}

extension LiveMapping {
    /// When a share's position last changed, as this app saw it
    /// (`LiveLocationInfo` carries no time of its own).
    struct LiveTick: Equatable, Sendable {
        let sequence: UInt32
        let at: Date?
    }

    /// Keeps, per live share, the sequence last seen and when it changed: a
    /// share still at sequence 0 is its start (`sentAt`); a later sequence
    /// seen for the first time has no known time; a new sequence is `now`.
    /// Shares that are gone are dropped.
    static func liveTicks(_ records: [LiveLocationRecord], previous: [Data: LiveTick], now: Date) -> [Data: LiveTick] {
        var ticks: [Data: LiveTick] = [:]
        for record in records {
            if let old = previous[record.id] {
                ticks[record.id] = old.sequence == record.sequence ? old : LiveTick(sequence: record.sequence, at: now)
            } else {
                ticks[record.id] = LiveTick(sequence: record.sequence, at: record.sequence == 0 ? record.sentAt : nil)
            }
        }
        return ticks
    }

    /// The conversation's live shares, oldest first.
    static func liveLocationSnapshot(_ records: [LiveLocationRecord], ticks: [Data: LiveTick]) -> LiveLocationSnapshot {
        LiveLocationSnapshot(
            shares: records.map { record in
                LiveShare(
                    id: messageID(record.id), direction: record.outgoing ? .outgoing : .incoming, startedAt: record.sentAt,
                    position: LivePosition(
                        latitude: record.latitude, longitude: record.longitude,
                        accuracy: record.accuracy.isFinite ? max(0, Double(record.accuracy)) : 0,
                        heading: record.heading >= 0 && record.heading.isFinite ? Double(record.heading) : nil,
                        updatedAt: ticks[record.id]?.at),
                    expiresAt: record.expiresAt, isStopped: record.stopped)
            })
    }
}

/// The live map's derived state (memory only; dropped with the other caches
/// on lock, in the background and when the conversation closes).
struct LiveMapCache {
    var ticks: [Data: LiveMapping.LiveTick] = [:]
    var snapshot: (peer: AccountId, key: [MessageItem], value: LiveLocationSnapshot)?
}

extension RealMessengerService {
    func liveLocations(in chat: ChatID) -> LiveLocationSnapshot {
        guard let peer = runtime.openPeer, peer.string == chat else { return .empty }
        let source = runtime.openMessages
        if let cached = liveMapCache.snapshot, cached.peer == peer, cached.key == source { return cached.value }
        let records = source.compactMap { LiveLocationRecord($0) }
        noteLiveTicks(records)
        let value = LiveMapping.liveLocationSnapshot(records, ticks: liveMapCache.ticks)
        liveMapCache.snapshot = (peer, source, value)
        return value
    }

    /// Notes when the open conversation's live shares moved. `messages(in:)`
    /// calls it on every change of the conversation, so the times are known
    /// while the chat is open, map or not.
    func noteLiveTicks(in source: [MessageItem]) {
        noteLiveTicks(source.compactMap { LiveLocationRecord($0) })
    }

    private func noteLiveTicks(_ records: [LiveLocationRecord]) {
        liveMapCache.ticks = LiveMapping.liveTicks(records, previous: liveMapCache.ticks, now: Date())
    }

    func ownPositionUpdates() -> AsyncStream<OwnPositionState> {
        // A watcher of its own per map: the sharing manager is never touched.
        DevicePositionWatcher.updates()
    }

    func requestOwnPositionAccess() async -> Bool {
        await DevicePositionWatcher.requestAccess()
    }
}
