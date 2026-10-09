import SwiftUI

/// The single place where the welcome screen (1.1) mounts the 3D scene
/// (Sources/Scene3D, `Us2usScene`): a transparent 402 × 330 canvas at design
/// y 84 over the `bg` + dot grid. Swap the implementation here if the
/// scene's API changes — nothing else references it.
///
/// * The scene pauses (no GPU work) while the welcome screen is covered by a
///   pushed onboarding step, as well as off screen and in the background.
/// * It also holds its last frame while its page is moving — a page change,
///   a swipe under the finger, a push or pop of the stack it is on
///   (`\.u2LayerMotion`) — so the transition has every frame to itself;
///   it carries on from that frame once the page has settled.
/// * With the frozen design clock (`-U2FrozenClock YES`) the scene is pinned
///   to the moment the reference PNGs captured, so screenshots compare 1:1.
struct WelcomeSceneHook: View {
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.u2Clock) private var clock
    @Environment(\.u2IsLayerVisible) private var isVisible
    @Environment(\.u2LayerMotion) private var motion

    var body: some View {
        Us2usScene(
            theme: colorScheme, state: .welcome,
            pinnedTime: clock.isFrozen ? Self.referenceTime(colorScheme) : nil,
            isActive: isVisible && (motion?.isSettled ?? true)
        )
        .frame(height: 330)
        .frame(maxWidth: .infinity)
    }

    /// Scene time of `ios/design/reference/1.1-karsilama.png` (dark) and
    /// `reference/light/1.1-karsilama.png`: the canvas was captured while the
    /// web scene was running; these are the times whose frames match the
    /// references best (sway, packets, lattice).
    static func referenceTime(_ scheme: ColorScheme) -> Double {
        scheme == .light ? 39.95 : 4.5
    }
}
