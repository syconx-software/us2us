import SwiftUI

// Helpers only 03 · Kilit uses.

// MARK: - Wait times

enum LockFormat {
    /// “1 dakika”, “5 dakika”, “1 saat” / “1 minute”, “5 minutes”, “1 hour”.
    static func waitDuration(_ seconds: TimeInterval, locale: Locale) -> String {
        let formatter = DateComponentsFormatter()
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = locale
        formatter.calendar = calendar
        formatter.unitsStyle = .full
        formatter.allowedUnits = seconds >= 3_600 ? [.hour] : [.minute]
        formatter.maximumUnitCount = 1
        return formatter.string(from: max(seconds, 60)) ?? ""
    }
}

// MARK: - Biometry

extension Biometry {
    /// The prompt reason (shown by Touch ID; Face ID shows the Info.plist text).
    func reason(locale: Locale) -> String {
        switch self {
        case .touchID: StringTable.lock.string("lock.touchID.reason", locale: locale)
        case .faceID, .none: StringTable.lock.string("lock.faceID.reason", locale: locale)
        }
    }
}
