import SwiftUI
import UIKit

/// The time source for everything time-dependent on screen (countdowns,
/// relative times). Live in the app; frozen for previews, design comparisons
/// and UI tests, so screens render exactly like the reference PNGs.
struct U2Clock: Sendable {
    private let base: Date?
    private let offset: TimeInterval

    /// Wall-clock time.
    static let live = U2Clock(base: nil, offset: 0)

    /// Always `date` — countdowns do not tick.
    static func frozen(at date: Date) -> U2Clock { U2Clock(base: date, offset: 0) }

    /// Starts at `date` and runs with real time from the moment it is created
    /// (the design's demo counters: 1.3 starts at 23:41:07 and counts down).
    static func running(from date: Date) -> U2Clock { U2Clock(base: nil, offset: date.timeIntervalSinceNow) }

    var isFrozen: Bool { base != nil }

    var now: Date { base ?? Date().addingTimeInterval(offset) }

    /// The clock's time for a `TimelineView` tick date.
    func date(forTimelineDate timelineDate: Date) -> Date {
        base ?? timelineDate.addingTimeInterval(offset)
    }
}

private struct U2ClockKey: EnvironmentKey {
    static let defaultValue = U2Clock.live
}

extension EnvironmentValues {
    var u2Clock: U2Clock {
        get { self[U2ClockKey.self] }
        set { self[U2ClockKey.self] = newValue }
    }
}

/// Re-renders `content` with the clock's time every `interval` seconds (or
/// once when the clock is frozen). Countdowns keep ticking under Reduce
/// Motion — they are information, not decoration.
struct U2TimeView<Content: View>: View {
    var interval: TimeInterval = 1
    @ViewBuilder let content: (Date) -> Content
    @Environment(\.u2Clock) private var clock

    var body: some View {
        if clock.isFrozen {
            content(clock.now)
        } else {
            TimelineView(.periodic(from: .now, by: interval)) { context in
                content(clock.date(forTimelineDate: context.date))
            }
        }
    }
}

/// Haptics used by the design (proposed in the inventory: light impact per
/// keypad digit, medium on long-press, error notification on a wrong code).
@MainActor
enum U2Haptics {
    static func keypadTap() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred(intensity: 0.7)
    }

    static func longPress() {
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    }

    static func selection() {
        UISelectionFeedbackGenerator().selectionChanged()
    }

    static func error() {
        UINotificationFeedbackGenerator().notificationOccurred(.error)
    }

    static func success() {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }
}
