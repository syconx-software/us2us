import Foundation
import UniformTypeIdentifiers

#if canImport(UIKit)
    import UIKit
#endif

/// The parts of `UIPasteboard` the hygiene helper uses, so it can be tested.
@MainActor
public protocol PasteboardWriting: AnyObject {
    var changeCount: Int { get }
    func setItems(_ items: [[String: Any]], localOnly: Bool, expiresAt: Date?)
    func clear()
}

#if canImport(UIKit)
    extension UIPasteboard: PasteboardWriting {
        public func setItems(_ items: [[String: Any]], localOnly: Bool, expiresAt: Date?) {
            var options: [UIPasteboard.OptionsKey: Any] = [.localOnly: localOnly]
            if let expiresAt { options[.expirationDate] = expiresAt }
            setItems(items, options: options)
        }

        public func clear() {
            items = []
        }
    }
#endif

/// Pasteboard hygiene (owner decision):
///
/// * Every copy is **local only**, so Universal Clipboard never carries it to
///   the user's other devices.
/// * Sensitive values (safety numbers, codes, usernames shown for sharing)
///   expire after ``sensitiveLifetime`` and are also cleared when the app
///   locks, but only if the pasteboard still holds what we put there.
/// * A value copied to be pasted into another app (`.handOff`: the
///   generated backup password, for a password manager) expires after
///   ``sensitiveLifetime`` too, but is left in place when the app leaves the
///   foreground — which is exactly when it is pasted.
/// * Text from disappearing or view-once messages is never copied (the UI
///   disables the action; ``copy(_:sensitivity:)`` refuses `.forbidden`).
@MainActor
public final class SecurePasteboard {
    public enum Sensitivity: Sendable {
        /// Ordinary message text: local only, expires after ``ordinaryLifetime``.
        case ordinary
        /// Codes and numbers: local only, expires after ``sensitiveLifetime``.
        case sensitive
        /// Copied to be pasted into another app (the generated backup
        /// password): local only, expires after ``sensitiveLifetime``, and
        /// not cleared by ``clearIfOwned()`` (leaving the app, locking).
        case handOff
        /// Never copied (disappearing / view-once content).
        case forbidden
    }

    public static let sensitiveLifetime: TimeInterval = 60
    public static let ordinaryLifetime: TimeInterval = 10 * 60

    private let pasteboard: any PasteboardWriting
    private let now: @Sendable () -> Date
    /// The pasteboard change count right after our last write.
    private var ownedChangeCount: Int?

    public init(pasteboard: any PasteboardWriting, now: @escaping @Sendable () -> Date = { Date() }) {
        self.pasteboard = pasteboard
        self.now = now
    }

    #if canImport(UIKit)
        public static let shared = SecurePasteboard(pasteboard: UIPasteboard.general)
    #endif

    /// Copies `text`; returns `false` if the sensitivity forbids it.
    @discardableResult
    public func copy(_ text: String, sensitivity: Sensitivity) -> Bool {
        let lifetime: TimeInterval
        switch sensitivity {
        case .forbidden: return false
        case .ordinary: lifetime = Self.ordinaryLifetime
        case .sensitive, .handOff: lifetime = Self.sensitiveLifetime
        }
        pasteboard.setItems(
            [[UTType.utf8PlainText.identifier: text]], localOnly: true, expiresAt: now().addingTimeInterval(lifetime))
        // A hand-off is meant to outlive this app's foreground: only its
        // expiry removes it.
        ownedChangeCount = sensitivity == .handOff ? nil : pasteboard.changeCount
        return true
    }

    /// Clears the pasteboard if it still holds our last copy (on lock and
    /// when the app goes to the background). Something the user copied in
    /// another app since then is left alone.
    public func clearIfOwned() {
        guard let owned = ownedChangeCount, owned == pasteboard.changeCount else {
            ownedChangeCount = nil
            return
        }
        pasteboard.clear()
        ownedChangeCount = nil
    }
}
