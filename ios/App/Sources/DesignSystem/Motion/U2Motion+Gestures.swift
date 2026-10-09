import SwiftUI

// Gesture hand-offs: when a finger lets go of something it was dragging (a
// sheet, the edge swipe back, a welcome page), the settle carries on at the
// finger's own speed instead of starting from rest — no stall at release, no
// overshoot (the same critically damped spring as `U2Motion.spring`).

extension U2Motion {
    /// `U2Motion.spring` (response 0.55, no bounce) starting at
    /// `initialVelocity`, given in units of the remaining distance per second
    /// (SwiftUI's convention: 1 = the whole remaining distance in one second).
    /// Under Reduce Motion: the short cross-fade, like every movement.
    static func spring(initialVelocity: Double) -> Animation {
        .interpolatingSpring(duration: 0.55, bounce: 0, initialVelocity: initialVelocity)
    }

    /// The finger's speed (`points` per second, positive towards the target)
    /// relative to the `remaining` distance — the `initialVelocity` of
    /// `spring(initialVelocity:)`. Clamped, so a flick over the last points
    /// never becomes a jump. A finger still moving away from the target
    /// gives a negative value: the content eases on a little in that
    /// direction and turns round — critically damped, it never passes the
    /// target.
    static func relativeVelocity(_ points: CGFloat, remaining: CGFloat) -> Double {
        guard abs(remaining) > 0.5 else { return 0 }
        return min(max(Double(points / remaining), -12), 12)
    }
}
