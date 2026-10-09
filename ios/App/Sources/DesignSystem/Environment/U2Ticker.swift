import SwiftUI

/// One shared, slow clock for a screen with many small countdowns (the 4.1
/// Ghost pills): a single timer instead of a `TimelineView` per row, and
/// only the views that read `now` re-render when it ticks. Rows that scroll
/// in no longer set up a timeline of their own (which made a Ghost row cost
/// three times a plain one).
///
/// Follows the design clock (`U2Clock`: frozen in references, running from
/// the design's moment otherwise) and ticks only while `isRunning` (the
/// screen is visible) — when it resumes, it catches up at once.
@Observable
@MainActor
final class U2Ticker {
    private(set) var now: Date
    private let clock: U2Clock
    private let interval: Duration
    private var task: Task<Void, Never>?

    init(clock: U2Clock, interval: Duration = .seconds(30)) {
        self.clock = clock
        self.interval = interval
        now = clock.now
    }

    /// Starts or stops ticking.
    func setRunning(_ running: Bool) {
        guard running != (task != nil) else { return }
        if running {
            update()
            guard !clock.isFrozen else { return }
            task = Task { [weak self, interval] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: interval)
                    guard !Task.isCancelled else { return }
                    self?.update()
                }
            }
        } else {
            task?.cancel()
            task = nil
        }
    }

    private func update() {
        let date = clock.now
        if date != now { now = date }
    }
}

extension EnvironmentValues {
    /// A shared `U2Ticker` for the countdowns below (nil: each keeps its own timeline).
    @Entry var u2Ticker: U2Ticker? = nil
}
