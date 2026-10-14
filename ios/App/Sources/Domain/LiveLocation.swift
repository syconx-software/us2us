import Foundation

// 5.2 › Konum: what the “Haritada aç” chooser and the live-location map
// need. Plain values (no CoreLocation here), held in memory only: nothing
// in this file is stored, logged or sent.

/// A point on the map: a live share's latest position, or this device's own.
struct LivePosition: Hashable, Sendable {
    var latitude: Double
    var longitude: Double
    /// Horizontal accuracy in metres; 0 when unknown.
    var accuracy: Double = 0
    /// Course in degrees from true north; `nil` when unknown.
    var heading: Double? = nil
    /// When this phone saw the position change; `nil` when that is not known.
    var updatedAt: Date? = nil

    /// A real place on Earth (a sender's position is whatever its app sent).
    var isValidCoordinate: Bool {
        latitude.isFinite && longitude.isFinite && abs(latitude) <= 90 && abs(longitude) <= 180
    }
}

/// One live-location share of the open chat, with its latest position.
struct LiveShare: Identifiable, Hashable, Sendable {
    /// The live-location message.
    let id: MessageID
    let direction: MessageDirection
    let startedAt: Date
    var position: LivePosition
    var expiresAt: Date
    /// Stopped by its sender (“Durdur”).
    var isStopped: Bool

    var isOutgoing: Bool { direction == .outgoing }

    /// Still running at `now` — after that, receivers stop showing it (PROTOCOL §8.3).
    func isActive(at now: Date) -> Bool { !isStopped && now < expiresAt }
}

/// The live shares of the open chat, oldest first, with their latest
/// positions. Memory only: empty while the chat is not open and while the
/// app is locked.
struct LiveLocationSnapshot: Hashable, Sendable {
    var shares: [LiveShare] = []

    static let empty = LiveLocationSnapshot()

    func share(_ id: MessageID) -> LiveShare? {
        shares.last { $0.id == id }
    }

    /// The newest share in `direction` that is still running at `now`.
    func activeShare(_ direction: MessageDirection, at now: Date) -> LiveShare? {
        shares.last { $0.direction == direction && $0.isActive(at: now) }
    }
}

/// This device's own position for the live-location map: shown on this
/// phone only, never sent.
enum OwnPositionState: Hashable, Sendable {
    /// Location access has not been asked yet: the map offers “İzin ver”.
    case needsPermission
    /// Refused or restricted: the map offers “Ayarları aç”.
    case denied
    /// Allowed; waiting for the first fix.
    case locating
    case position(LivePosition)
    /// This messenger shows no own position.
    case unavailable
}
