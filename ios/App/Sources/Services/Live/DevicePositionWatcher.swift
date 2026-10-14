import CoreLocation
import Foundation

/// This device's own position for the live-location map (5.2 › harita).
///
/// * Shown on this phone only: never sent, never stored, never logged.
/// * Its own `CLLocationManager`, separate from `LocationProvider` (which
///   sends): closing the map never touches a running share, and this
///   manager never runs in the background (`allowsBackgroundLocationUpdates`
///   stays off; the map stops it when the app leaves the foreground).
/// * Never asks for permission by itself — `requestAccess()` does, when the
///   user taps “İzin ver” on the map.
@MainActor
final class DevicePositionWatcher: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var continuation: AsyncStream<OwnPositionState>.Continuation?
    private var authorizationWaiters: [CheckedContinuation<CLAuthorizationStatus, Never>] = []
    private var isUpdating = false
    private var last: LivePosition?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        manager.distanceFilter = 10
    }

    /// The device's position while the stream is consumed; updates stop
    /// (and the watcher goes) when it ends.
    static func updates() -> AsyncStream<OwnPositionState> {
        let (stream, continuation) = AsyncStream<OwnPositionState>.makeStream(bufferingPolicy: .bufferingNewest(1))
        let watcher = DevicePositionWatcher()
        watcher.start(continuation)
        return stream
    }

    /// Asks for when-in-use permission once (only while it is undecided).
    /// `true` when location is allowed.
    static func requestAccess() async -> Bool {
        let watcher = DevicePositionWatcher()
        return await watcher.authorize()
    }

    private func start(_ continuation: AsyncStream<OwnPositionState>.Continuation) {
        self.continuation = continuation
        // The stream keeps the watcher until it ends.
        continuation.onTermination = { [self] _ in
            Task { @MainActor in self.stop() }
        }
        evaluate()
    }

    private func stop() {
        pause()
        continuation = nil
        last = nil
    }

    private func pause() {
        guard isUpdating else { return }
        isUpdating = false
        manager.stopUpdatingLocation()
    }

    /// Reports what the map can show, and runs the updates only while allowed.
    private func evaluate() {
        guard let continuation else { return }
        switch manager.authorizationStatus {
        case .notDetermined:
            pause()
            continuation.yield(.needsPermission)
        case .denied, .restricted:
            pause()
            continuation.yield(.denied)
        case .authorizedWhenInUse, .authorizedAlways:
            continuation.yield(last.map(OwnPositionState.position) ?? .locating)
            if !isUpdating {
                isUpdating = true
                manager.startUpdatingLocation()
            }
        @unknown default:
            pause()
            continuation.yield(.unavailable)
        }
    }

    private func authorize() async -> Bool {
        var status = manager.authorizationStatus
        if status == .notDetermined {
            status = await withCheckedContinuation { waiter in
                authorizationWaiters.append(waiter)
                manager.requestWhenInUseAuthorization()
            }
        }
        return status == .authorizedWhenInUse || status == .authorizedAlways
    }

    private func authorizationChanged(_ status: CLAuthorizationStatus) {
        if status != .notDetermined {
            let waiters = authorizationWaiters
            authorizationWaiters = []
            for waiter in waiters { waiter.resume(returning: status) }
        }
        evaluate()
    }

    private func deliver(_ position: LivePosition) {
        guard isUpdating, let continuation else { return }
        last = position
        continuation.yield(.position(position))
    }

    // MARK: CLLocationManagerDelegate

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in self.authorizationChanged(status) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let fix = locations.last, fix.horizontalAccuracy >= 0 else { return }
        let position = LivePosition(
            latitude: fix.coordinate.latitude, longitude: fix.coordinate.longitude, accuracy: fix.horizontalAccuracy,
            heading: fix.course >= 0 ? fix.course : nil, updatedAt: fix.timestamp)
        Task { @MainActor in self.deliver(position) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: any Error) {
        // No fix right now (no signal): the map keeps what it shows. A
        // refusal arrives as an authorization change.
    }
}
