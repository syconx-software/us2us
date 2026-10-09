import CoreLocation
import Foundation

/// CoreLocation for “+” › Konum and live location (PROTOCOL §8.3).
///
/// * When-in-use only, asked the first time the user shares a place.
/// * Updates continue in the background **only** while live location is
///   being shared, with the system's location indicator shown
///   (`showsBackgroundLocationIndicator`); they stop the moment sharing ends.
/// * Nothing is stored; fixes go straight to the runtime, encrypted to the chat.
@MainActor
final class LocationProvider: NSObject, CLLocationManagerDelegate {
    enum LocationError: Error {
        case denied
        case unavailable
    }

    private let manager = CLLocationManager()
    private var authorizationWaiters: [CheckedContinuation<CLAuthorizationStatus, Never>] = []
    private var fixWaiters: [CheckedContinuation<CLLocation?, Never>] = []
    private var liveHandler: ((CLLocation) -> Void)?
    private var lastLiveTick: Date?
    /// The runtime asks for a tick every 15–30 s.
    private let liveInterval: TimeInterval = 20

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
    }

    var isSharingLive: Bool { liveHandler != nil }

    /// One fix, asking for when-in-use permission first if needed.
    func currentLocation() async throws(LocationError) -> CLLocation {
        guard await authorize() else { throw .denied }
        if let recent = manager.location, recent.timestamp.timeIntervalSinceNow > -30 { return recent }
        let fix: CLLocation? = await withCheckedContinuation { continuation in
            fixWaiters.append(continuation)
            manager.requestLocation()
        }
        guard let fix else { throw .unavailable }
        return fix
    }

    /// Starts continuous updates for live location (background allowed while it runs).
    func startLiveUpdates(_ handler: @escaping (CLLocation) -> Void) async throws(LocationError) {
        guard await authorize() else { throw .denied }
        liveHandler = handler
        lastLiveTick = nil
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        manager.pausesLocationUpdatesAutomatically = false
        manager.distanceFilter = kCLDistanceFilterNone
        manager.startUpdatingLocation()
    }

    func stopLiveUpdates() {
        liveHandler = nil
        manager.stopUpdatingLocation()
        manager.allowsBackgroundLocationUpdates = false
    }

    private func authorize() async -> Bool {
        var status = manager.authorizationStatus
        if status == .notDetermined {
            status = await withCheckedContinuation { continuation in
                authorizationWaiters.append(continuation)
                manager.requestWhenInUseAuthorization()
            }
        }
        return status == .authorizedWhenInUse || status == .authorizedAlways
    }

    // MARK: CLLocationManagerDelegate

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            guard status != .notDetermined else { return }
            let waiters = self.authorizationWaiters
            self.authorizationWaiters = []
            for waiter in waiters { waiter.resume(returning: status) }
            if status == .denied || status == .restricted { self.stopLiveUpdates() }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let last = locations.last else { return }
        Task { @MainActor in self.deliver(last) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: any Error) {
        Task { @MainActor in
            let waiters = self.fixWaiters
            self.fixWaiters = []
            for waiter in waiters { waiter.resume(returning: nil) }
        }
    }

    private func deliver(_ location: CLLocation) {
        let waiters = fixWaiters
        fixWaiters = []
        for waiter in waiters { waiter.resume(returning: location) }
        guard let liveHandler else { return }
        if let lastLiveTick, Date().timeIntervalSince(lastLiveTick) < liveInterval { return }
        lastLiveTick = Date()
        liveHandler(location)
    }
}
