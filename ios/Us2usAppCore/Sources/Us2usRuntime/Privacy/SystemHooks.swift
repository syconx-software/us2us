import Foundation

#if canImport(UIKit)
    import UIKit
#endif

/// The scene phase, mapped by the app from SwiftUI's `ScenePhase`
/// (`.active` → `.active`, …), so this module needs no SwiftUI.
public enum AppPhase: Sendable, Equatable {
    case active
    /// Visible but not in front: app switcher, Control Center, a system
    /// prompt (Face ID itself), an incoming call banner.
    case inactive
    case background
}

/// `beginBackgroundTask` for the 30-second grace period (§5.4).
@MainActor
public protocol BackgroundTaskRunning: AnyObject {
    /// Returns a token, or `nil` if the system refused.
    func begin(name: String, expiration: @escaping @MainActor () -> Void) -> Int?
    func end(_ token: Int)
}

/// Screenshot and screen-capture events from the system.
@MainActor
public protocol CaptureEventSource: AnyObject {
    /// Whether the screen is being recorded, mirrored or AirPlayed right now.
    var isCaptured: Bool { get }
    func start(screenshot: @escaping @MainActor () -> Void, captureChanged: @escaping @MainActor (Bool) -> Void)
    func stop()
}

#if canImport(UIKit)
    /// `UIApplication.beginBackgroundTask` / `endBackgroundTask`.
    @MainActor
    public final class SystemBackgroundTasks: BackgroundTaskRunning {
        private var identifiers: [Int: UIBackgroundTaskIdentifier] = [:]
        private var next = 0

        public init() {}

        public func begin(name: String, expiration: @escaping @MainActor () -> Void) -> Int? {
            next += 1
            let token = next
            let identifier = UIApplication.shared.beginBackgroundTask(withName: name) {
                MainActor.assumeIsolated { expiration() }
            }
            guard identifier != .invalid else { return nil }
            identifiers[token] = identifier
            return token
        }

        public func end(_ token: Int) {
            guard let identifier = identifiers.removeValue(forKey: token) else { return }
            UIApplication.shared.endBackgroundTask(identifier)
        }
    }

    /// iOS cannot block screenshots (PROTOCOL.md §9), only detect them:
    /// `userDidTakeScreenshotNotification` after the fact, and the capture
    /// state of the screen (recording, mirroring, AirPlay) while it lasts.
    @MainActor
    public final class SystemCaptureEvents: CaptureEventSource {
        private var observers: [NSObjectProtocol] = []

        public init() {}

        public var isCaptured: Bool {
            UIApplication.shared.connectedScenes.contains { scene in
                guard let windowScene = scene as? UIWindowScene else { return false }
                return windowScene.traitCollection.sceneCaptureState == .active
            }
        }

        public func start(screenshot: @escaping @MainActor () -> Void, captureChanged: @escaping @MainActor (Bool) -> Void) {
            stop()
            let center = NotificationCenter.default
            observers.append(
                center.addObserver(forName: UIApplication.userDidTakeScreenshotNotification, object: nil, queue: .main) {
                    _ in MainActor.assumeIsolated { screenshot() }
                })
            observers.append(
                center.addObserver(forName: UIScreen.capturedDidChangeNotification, object: nil, queue: .main) {
                    [weak self] _ in
                    MainActor.assumeIsolated {
                        guard let self else { return }
                        captureChanged(self.isCaptured)
                    }
                })
        }

        public func stop() {
            for observer in observers { NotificationCenter.default.removeObserver(observer) }
            observers = []
        }
    }
#endif

/// Background tasks that do nothing (tests, previews).
@MainActor
public final class NoBackgroundTasks: BackgroundTaskRunning {
    public private(set) var begun = 0
    public private(set) var ended = 0
    /// Behave like a system that grants no more background time
    /// (`beginBackgroundTask` returning `.invalid`).
    public var refusesNewTasks = false
    private var expirations: [Int: @MainActor () -> Void] = [:]

    public init() {}

    public func begin(name: String, expiration: @escaping @MainActor () -> Void) -> Int? {
        guard !refusesNewTasks else { return nil }
        begun += 1
        expirations[begun] = expiration
        return begun
    }

    public func end(_ token: Int) {
        if expirations.removeValue(forKey: token) != nil { ended += 1 }
    }

    /// Simulates the system expiring every running task.
    public func expireAll() {
        let pending = expirations
        expirations = [:]
        for (_, expiration) in pending { expiration() }
    }
}

/// Capture events driven by hand (tests, previews).
@MainActor
public final class ManualCaptureEvents: CaptureEventSource {
    public private(set) var isCaptured = false
    private var screenshot: (@MainActor () -> Void)?
    private var captureChanged: (@MainActor (Bool) -> Void)?

    public init() {}

    public func start(screenshot: @escaping @MainActor () -> Void, captureChanged: @escaping @MainActor (Bool) -> Void) {
        self.screenshot = screenshot
        self.captureChanged = captureChanged
    }

    public func stop() {
        screenshot = nil
        captureChanged = nil
    }

    public func takeScreenshot() { screenshot?() }

    public func setCaptured(_ captured: Bool) {
        isCaptured = captured
        captureChanged?(captured)
    }
}
