import SwiftUI
import UIKit

/// Motion tokens (tokens.json › motion).
///
/// Design rule: every animation is *very soft* — no bounce, no
/// overshoot, responsive gentle ease-in-out, nothing snaps. All curves here are the
/// design's own `cubic-bezier(0.45, 0, 0.55, 1)`, its ease-out, or a
/// critically damped spring (damping fraction 1.0, never below).
///
/// Reduce Motion (`accessibilityReduceMotion`): ambient motion stops,
/// movement becomes a short cross-fade, shakes are removed; countdowns keep
/// ticking because they are information. Use `U2Motion.resolve(_:reduceMotion:)`,
/// `.u2Animation(_:value:)`, `withU2Animation` and the `.u2*` transitions —
/// they all apply that rule.
enum U2Motion {
    /// Durations in seconds.
    enum Duration {
        /// Press feedback in.
        static let press: Double = 0.06
        /// Dots, icons, chips: small state changes.
        static let micro: Double = 0.1
        /// Text/colour changes, messages appearing.
        static let state: Double = 0.12
        /// Screen changes (push, page, root swap).
        static let screen: Double = 0.16
        /// Sheet backdrops.
        static let sheet: Double = 0.18
        /// Short cross-fade replacing movement under Reduce Motion.
        static let reducedCrossFade: Double = 0.08
        /// Ambient colour changes (Ghost calm → urgent).
        static let ambient: Double = 0.8
    }

    /// The design's curve, `cubic-bezier(0.45, 0, 0.55, 1)` — the default for every transition.
    static func soft(_ duration: Double) -> Animation {
        .timingCurve(0.45, 0, 0.55, 1, duration: duration)
    }

    /// `ease-out` = `cubic-bezier(0, 0, 0.58, 1)` — entrances and ring pulses.
    static func out(_ duration: Double) -> Animation {
        .timingCurve(0, 0, 0.58, 1, duration: duration)
    }

    /// Critically damped spring for interactive moves (sheets following the
    /// finger, toggles, the reaction bar): zero bounce.
    static let springResponse: Double = 0.2
    static let springQuickResponse: Double = 0.14
    static let spring = Animation.spring(response: springResponse, dampingFraction: 1.0)

    /// Same spring, a touch quicker — for small interactive elements (toggle knob).
    static let springQuick = Animation.spring(response: springQuickResponse, dampingFraction: 1.0)

    // Named uses.
    static var press: Animation { soft(Duration.press) }
    static var release: Animation { soft(Duration.micro) }
    static var micro: Animation { soft(Duration.micro) }
    static var state: Animation { soft(Duration.state) }
    static var screen: Animation { out(Duration.screen) }
    static var sheet: Animation { out(Duration.sheet) }
    static var ambient: Animation { soft(Duration.ambient) }
    static var appear: Animation { out(Duration.state) }

    /// The cross-fade that replaces movement when Reduce Motion is on.
    static var reducedCrossFade: Animation { .easeInOut(duration: Duration.reducedCrossFade) }

    /// `animation`, or a short cross-fade when Reduce Motion is on.
    static func resolve(_ animation: Animation, reduceMotion: Bool) -> Animation {
        reduceMotion ? reducedCrossFade : animation
    }

    /// The system Reduce Motion flag (for code outside a view's environment).
    @MainActor static var isReduceMotionEnabled: Bool { UIAccessibility.isReduceMotionEnabled }
}

/// `withAnimation` that honours Reduce Motion (movement → short cross-fade).
@MainActor
func withU2Animation<Result>(_ animation: Animation = U2Motion.state, _ body: () throws -> Result) rethrows -> Result {
    try withAnimation(U2Motion.resolve(animation, reduceMotion: U2Motion.isReduceMotionEnabled), body)
}

/// `withAnimation(_:completionCriteria:_:completion:)` that honours Reduce Motion.
@MainActor
func withU2Animation(
    _ animation: Animation = U2Motion.state, _ body: () -> Void, completion: @escaping () -> Void
) {
    withAnimation(
        U2Motion.resolve(animation, reduceMotion: U2Motion.isReduceMotionEnabled),
        completionCriteria: .logicallyComplete, body, completion: completion)
}

private struct U2AnimationModifier<V: Equatable>: ViewModifier {
    let animation: Animation
    let value: V
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content.animation(U2Motion.resolve(animation, reduceMotion: reduceMotion), value: value)
    }
}

extension View {
    /// `.animation(_:value:)` that honours Reduce Motion.
    func u2Animation<V: Equatable>(_ animation: Animation = U2Motion.state, value: V) -> some View {
        modifier(U2AnimationModifier(animation: animation, value: value))
    }
}

// MARK: - Transitions

/// Opacity + a slight blur + an offset: how content enters and leaves.
/// With Reduce Motion only the opacity remains.
private struct U2SoftTransitionModifier: ViewModifier {
    let progress: Double  // 0 = hidden, 1 = shown
    let offset: CGSize
    let blur: CGFloat
    let scale: CGFloat
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        let hidden = 1 - progress
        content
            .opacity(progress)
            .blur(radius: reduceMotion ? 0 : blur * hidden)
            .scaleEffect(reduceMotion ? 1 : 1 - (1 - scale) * hidden)
            .offset(
                x: reduceMotion ? 0 : offset.width * hidden,
                y: reduceMotion ? 0 : offset.height * hidden)
    }
}

extension AnyTransition {
    /// Fade + 8 pt rise + slight blur — new content (messages, cards, captions).
    static var u2Rise: AnyTransition { u2Soft(offset: CGSize(width: 0, height: 8), blur: 4) }

    /// Fade + 8 pt drop + slight blur — warnings sliding in from above.
    static var u2Drop: AnyTransition { u2Soft(offset: CGSize(width: 0, height: -8), blur: 4) }

    /// Fade + slight blur, no movement.
    static var u2Fade: AnyTransition { u2Soft(offset: .zero, blur: 3) }

    /// Fade + scale from 0.96 (menus, the reaction bar).
    static var u2Pop: AnyTransition { u2Soft(offset: .zero, blur: 2, scale: 0.96) }

    /// A configurable soft transition (opacity always; offset/blur/scale unless Reduce Motion).
    static func u2Soft(offset: CGSize, blur: CGFloat, scale: CGFloat = 1) -> AnyTransition {
        .modifier(
            active: U2SoftTransitionModifier(progress: 0, offset: offset, blur: blur, scale: scale),
            identity: U2SoftTransitionModifier(progress: 1, offset: offset, blur: blur, scale: scale))
    }

    /// `screenPush`: cross-fade + 16 pt horizontal drift (forward: in from the
    /// trailing side, out towards the leading side).
    static var u2Push: AnyTransition {
        .asymmetric(
            insertion: u2Soft(offset: CGSize(width: 16, height: 0), blur: 0),
            removal: u2Soft(offset: CGSize(width: 16, height: 0), blur: 0))
    }
}

// MARK: - Press feedback

/// `pressFeedback`: scale 0.97 + fill brighten on press and release timings.
struct U2PressEffect: ViewModifier {
    let isPressed: Bool
    var scale: CGFloat = 0.97
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .scaleEffect(isPressed && !reduceMotion ? scale : 1)
            .animation(isPressed ? U2Motion.press : U2Motion.release, value: isPressed)
    }
}

extension Animation {
    /// The software keyboard's own curve: a critically damped spring (no
    /// overshoot) — for content that follows the keyboard (5.1 composer).
    static var u2Keyboard: Animation { .interpolatingSpring(mass: 3, stiffness: 1000, damping: 500) }
}

// MARK: - Fade + collapse (4.3 card removal, 5.3 “Benden sil”)

extension AnyTransition {
    /// 4.3 “Engelle / Sil”, 5.3 “Benden sil”: the item fades while its height
    /// collapses to nothing (clipped with `cornerRadius`, so the shrinking edge
    /// stays a rounded bottom instead of a straight cut), and what follows
    /// glides up into its place instead of sliding over it. Driven by the
    /// removal's state animation.
    static func u2Collapse(cornerRadius: CGFloat = U2Radius.cardL) -> AnyTransition {
        .modifier(
            active: CollapseModifier(progress: 0, cornerRadius: cornerRadius),
            identity: CollapseModifier(progress: 1, cornerRadius: cornerRadius))
    }
}

extension View {
    /// The `u2Collapse` look at a given progress (1 = full height, 0 =
    /// collapsed and transparent) — for a row that collapses *before* it is
    /// removed (5.1 “Benden sil”), so its height leaves the layout gradually.
    func u2CollapseProgress(_ progress: Double, cornerRadius: CGFloat = U2Radius.cardL) -> some View {
        modifier(CollapseModifier(progress: progress, cornerRadius: cornerRadius))
    }
}

private struct CollapseModifier: ViewModifier, Animatable {
    var progress: Double
    let cornerRadius: CGFloat

    nonisolated var animatableData: Double {
        get { progress }
        set { progress = newValue }
    }

    func body(content: Content) -> some View {
        // Clipped only while collapsing: at rest nothing that overhangs the
        // item (a reaction chip, a shadow) is cut.
        CollapseLayout(progress: progress) { content }
            .clipShape(progress < 1 ? AnyShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)) : AnyShape(Rectangle().inset(by: -10_000)))
            .opacity(progress)
    }
}

/// Reports `progress ×` the child's height and pins the child to the top.
private struct CollapseLayout: Layout {
    var progress: Double

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) -> CGSize {
        guard let child = subviews.first else { return .zero }
        let size = child.sizeThatFits(ProposedViewSize(width: proposal.width, height: nil))
        return CGSize(width: size.width, height: size.height * max(0, min(1, progress)))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) {
        guard let child = subviews.first else { return }
        let size = child.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil))
        child.place(
            at: bounds.origin, anchor: .topLeading, proposal: ProposedViewSize(width: bounds.width, height: size.height))
    }
}
