import SwiftUI

// Helpers only 01–02 use; nothing outside Features/Onboarding depends on them.

// MARK: - Dashed outlines (1.2 notification card, 1.3 view-once badge)

/// A CSS `border: 1.5px dashed` drawn inside `shape` *as the reference
/// shows it*: Chrome at 3x snapped the border to 3 device px (1 pt, and laid
/// the content out with a 1 pt border), dashes ≈ 3 pt on, 2 pt off.
struct DashedBorder<S: InsettableShape>: View {
    let shape: S
    var color: Color = Color.u2.dashedStroke
    var lineWidth: CGFloat = 1
    var dash: [CGFloat] = [3, 2]
    /// Where along the outline the first dash starts (to match Chrome's).
    var phase: CGFloat = 0

    var body: some View {
        shape
            .inset(by: lineWidth / 2)
            .stroke(color, style: StrokeStyle(lineWidth: lineWidth, dash: dash, dashPhase: phase))
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

// MARK: - Bullets, legends, feature rows

/// 2.1 rules: a 4 × 4 `accent400` dot, gap 10, `bullet` (Inter 400 13.5/18.9).
/// The text tints `danger` while the rule is broken (0.36 s cross-fade);
/// VoiceOver hears “karşılanmıyor” / “not met” after it.
struct OnboardingRuleRow: View {
    let text: Text
    var isBroken = false
    @Environment(\.locale) private var locale

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            Circle()
                .fill(Color.u2.accent400)
                .frame(width: 4, height: 4)
                .accessibilityHidden(true)
            text
                .u2Font(.bullet)
                .foregroundStyle(isBroken ? Color.u2.danger : Color.u2.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityValue(
                    isBroken
                        ? StringTable.onboarding.text("a11y.onboarding.username.rule.broken", locale: locale)
                        : Text(verbatim: ""))
        }
        .u2Animation(U2Motion.state, value: isBroken)
    }
}

/// 1.3 feature row: a 20 pt icon slot (18 pt `accent300` glyph), gap 10,
/// `legend` (Inter 400 14/18.9) in `text`.
struct OnboardingFeatureRow<Icon: View>: View {
    let text: Text
    @ViewBuilder let icon: Icon

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            // The glyph sits at the slot's leading edge (CSS inline svg in a
            // 20 pt slot); the view-once badge brings its own 1 pt margins.
            icon
                .foregroundStyle(Color.u2.accent300)
                .frame(width: 20, height: 18, alignment: .leading)
                .accessibilityHidden(true)
            text
                .u2Font(.legend)
                .foregroundStyle(Color.u2.text)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .combine)
    }
}

/// The 1.3 view-once mark: an 18 pt circle with a dashed `accent300` ring
/// around “1” (`monoMicro`, Mono 400 9). As drawn: 1 pt, 12 thin dashes on
/// the 53.4 pt centre line (2.67 on / 1.78 off), one centred at the top.
struct ViewOnceDashedBadge: View {
    var body: some View {
        ZStack {
            // The circle path starts at 3 o'clock; 12 dashes put one centred
            // there and therefore one centred at 12 o'clock.
            DashedBorder(shape: Circle(), color: Color.u2.accent300, lineWidth: 1, dash: [2.67, 1.78], phase: 1.335)
            Text(verbatim: "1")
                .u2Font(.monoMicro, matchLineBox: false)
                .foregroundStyle(Color.u2.accent300)
        }
        .frame(width: 18, height: 18)
        .padding(.horizontal, 1)
    }
}

// MARK: - Soft pager (1.1 → 1.3)

/// One page of the welcome pager at `distance` pages from the current
/// position: cross-fade + 16 pt drift (flows.md: “cross-fade + 16 pt drift,
/// 0.5 s curveSoft”). Animatable, so a button tap animates it on the design
/// curve and a swipe drives it with the finger. Reduce Motion: fade only.
struct WelcomePageLayer: ViewModifier, Animatable {
    var distance: Double
    var reduceMotion: Bool

    nonisolated var animatableData: Double {
        get { distance }
        set { distance = newValue }
    }

    func body(content: Content) -> some View {
        let d = min(max(distance, -1), 1)
        content
            .opacity(1 - abs(d))
            .offset(x: reduceMotion ? 0 : 16 * d)
    }
}

// MARK: - Harness support

extension OnboardingRoute {
    /// The design id shown in the setup header (“n / 3”) for each step.
    var setupStep: Int? {
        switch self {
        case .username: 1
        case .secretCode: 2
        case .noWayBack: 3
        case .restore: nil
        }
    }
}

// MARK: - Large text

/// The design's layout as long as it fits the screen; with very large
/// Dynamic Type (body text grows, Doto headings do not) the same content
/// scrolls instead of running off the bottom — its spacers then collapse.
struct OnboardingFitOrScroll<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        ViewThatFits(in: .vertical) {
            content
            ScrollView(.vertical) { content }
                .scrollBounceBehavior(.basedOnSize)
        }
    }
}
