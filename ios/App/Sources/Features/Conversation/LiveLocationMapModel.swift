import CoreLocation
import Foundation
import MapKit
import Observation

/// The live-location map's state (5.2 › harita): the open chat's live shares
/// from the messenger (observable, memory only) and this device's own
/// position (shown on this phone only, never sent). Nothing outlives the
/// map: it all goes when the cover closes.
@Observable
@MainActor
final class LiveLocationMapModel {
    /// What the bottom card says about the user.
    enum OwnRow: Equatable {
        /// The user's own share is running: “Konumunu paylaşıyorsun” + “Paylaşımı durdur”.
        case sharing(LiveShare)
        /// This device's position, shown here only.
        case device
        /// Allowed, no fix yet.
        case locating
        /// The gentle hint with “İzin ver”.
        case askPermission
        /// The gentle hint with “Ayarları aç”.
        case denied
        /// Nothing to say (the messenger shows no own position).
        case hidden

        /// Which row it is, whatever the share's latest values (the card
        /// cross-fades only when this changes).
        var kind: Int {
            switch self {
            case .sharing: 0
            case .device: 1
            case .locating: 2
            case .askPermission: 3
            case .denied: 4
            case .hidden: 5
            }
        }
    }

    let chatID: ChatID
    /// The share the map was opened on: the map closes when it is gone
    /// (deleted, the chat closed, the app locked).
    let focus: MessageID
    let messenger: any MessengerService

    private(set) var own: OwnPositionState = .unavailable
    /// Bumped after a permission request: the position stream starts again.
    private(set) var streamGeneration = 0
    /// The user asked to see themselves (“Konumuma git”, “İkinizi de
    /// göster”): only then does the camera take in this device's position
    /// while it is not shared, so the map loads no tiles around the user
    /// unasked. Their marker is drawn either way.
    var framesDevicePosition = false

    init(chatID: ChatID, focus: MessageID, messenger: any MessengerService) {
        self.chatID = chatID
        self.focus = focus
        self.messenger = messenger
    }

    var snapshot: LiveLocationSnapshot { messenger.liveLocations(in: chatID) }

    var isGone: Bool { snapshot.share(focus) == nil }

    var peerUsername: String { messenger.peer(for: chatID)?.username ?? chatID }

    var peerPalette: AvatarPalette { messenger.peer(for: chatID)?.palette ?? .forUsername(peerUsername) }

    /// The contact's profile picture (proposed), on their marker and row.
    var peerAvatar: AvatarPicture { messenger.peer(for: chatID)?.avatar ?? .initials }

    var ownUsername: String { messenger.ownUsername }

    /// The contact's running share (its marker goes when it ends — PROTOCOL §8.3).
    func peerShare(at now: Date) -> LiveShare? {
        snapshot.activeShare(.incoming, at: now)
    }

    func ownShare(at now: Date) -> LiveShare? {
        snapshot.activeShare(.outgoing, at: now)
    }

    /// The contact's marker: their share's latest position, if it is a real place.
    func peerPosition(at now: Date) -> LivePosition? {
        peerShare(at: now).map(\.position).flatMap { $0.isValidCoordinate ? $0 : nil }
    }

    /// Where the user is: this device's position when it is known, else the
    /// position last shared.
    func ownPosition(at now: Date) -> LivePosition? {
        let position: LivePosition?
        if case .position(let device) = own {
            position = device
        } else {
            position = ownShare(at: now)?.position
        }
        return position.flatMap { $0.isValidCoordinate ? $0 : nil }
    }

    /// Everything the camera keeps in view: the contact, and the user while
    /// they share their position or once they asked to see it.
    func points(at now: Date) -> [LivePosition] {
        let framesOwn = ownShare(at: now) != nil || framesDevicePosition
        return [peerPosition(at: now), framesOwn ? ownPosition(at: now) : nil].compactMap { $0 }
    }

    /// Whether the share's message disappears: the maps chooser then says
    /// that the chosen app may keep the place in its own history.
    func isDisappearing(_ share: LiveShare) -> Bool {
        messenger.messages(in: chatID).first { $0.id == share.id }?.isDisappearing ?? false
    }

    /// Whether anything on the map is still live.
    func isLive(at now: Date) -> Bool {
        peerShare(at: now) != nil || ownShare(at: now) != nil
    }

    func ownRow(at now: Date) -> OwnRow {
        if let share = ownShare(at: now) { return .sharing(share) }
        switch own {
        case .position: return .device
        case .locating: return .locating
        case .needsPermission: return .askPermission
        case .denied: return .denied
        case .unavailable: return .hidden
        }
    }

    func receive(_ state: OwnPositionState) {
        own = state
    }

    /// Follows this device's position until the calling task is cancelled
    /// (the map closed, or the app left the foreground).
    func run() async {
        for await state in messenger.ownPositionUpdates() {
            receive(state)
        }
    }

    /// “İzin ver”: the system asks once; the stream then starts again.
    func requestAccess() async {
        _ = await messenger.requestOwnPositionAccess()
        streamGeneration += 1
    }

    /// “Paylaşımı durdur”.
    func stop(_ share: LiveShare) async {
        await messenger.stopLiveLocation(share.id, in: chatID)
    }
}

/// The camera of the live-location map: both people in view, never closer
/// than a few streets.
enum LiveMapFraming {
    /// The smallest span shown (metres): a single point shows its street.
    static let minimumSpanMeters: Double = 400
    /// Room around the points: the header and the bottom card cover the
    /// map's top and bottom, so the height gets more of it.
    static let latitudeMargin: Double = 2.2
    static let longitudeMargin: Double = 1.5
    /// An accuracy circle is drawn only when it means something.
    static let accuracyRange: ClosedRange<Double> = 1...500
    private static let metersPerDegree: Double = 111_320

    static func showsAccuracy(_ accuracy: Double) -> Bool {
        accuracyRange.contains(accuracy)
    }

    /// The region that shows every point with its margin; `nil` without points.
    static func region(for points: [LivePosition]) -> MKCoordinateRegion? {
        let points = points.filter(\.isValidCoordinate)
        guard let first = points.first else { return nil }
        var minLatitude = first.latitude
        var maxLatitude = first.latitude
        var minLongitude = first.longitude
        var maxLongitude = first.longitude
        for point in points {
            minLatitude = min(minLatitude, point.latitude)
            maxLatitude = max(maxLatitude, point.latitude)
            // Unwrapped around the first point: two points either side of
            // ±180° stay neighbours instead of spanning the globe.
            var longitude = point.longitude
            while longitude - first.longitude > 180 { longitude -= 360 }
            while longitude - first.longitude < -180 { longitude += 360 }
            minLongitude = min(minLongitude, longitude)
            maxLongitude = max(maxLongitude, longitude)
        }
        let centerLatitude = (minLatitude + maxLatitude) / 2
        var centerLongitude = (minLongitude + maxLongitude) / 2
        if centerLongitude > 180 { centerLongitude -= 360 }
        if centerLongitude < -180 { centerLongitude += 360 }
        let cosine = max(0.01, cos(centerLatitude * .pi / 180))
        let latitudeDelta = min(170, max((maxLatitude - minLatitude) * latitudeMargin, minimumSpanMeters / metersPerDegree))
        let longitudeDelta = min(
            360, max((maxLongitude - minLongitude) * longitudeMargin, minimumSpanMeters / (metersPerDegree * cosine)))
        return MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: centerLatitude, longitude: centerLongitude),
            span: MKCoordinateSpan(latitudeDelta: latitudeDelta, longitudeDelta: longitudeDelta))
    }
}
