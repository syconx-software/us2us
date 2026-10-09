import SwiftUI

/// C19 — every rule in this design is dotted, never solid: anti-aliased discs
/// (solid to `radius`, fading to transparent at `edgeRadius`) repeated at a
/// fixed pitch, the first centre at pitch / 2 — exactly the CSS
/// `radial-gradient(circle, C r, transparent edge) 0 50% / pitch h repeat-x`.
struct DotPattern: Sendable, Hashable {
    var radius: CGFloat
    var edgeRadius: CGFloat
    var pitch: CGFloat
    var thickness: CGFloat

    /// List separators, chat-header rule, menu separators: r .75 / 1.1, pitch 5, 2 high.
    static let rule = DotPattern(radius: 0.75, edgeRadius: 1.1, pitch: 5, thickness: 2)
    /// Date separator (4.4): r .8 / 1.2, pitch 5.
    static let dateRule = DotPattern(radius: 0.8, edgeRadius: 1.2, pitch: 5, thickness: 2)
    /// Message-info timeline (5.4, vertical): r .9 / 1.3, pitch 5, 2 wide.
    static let timeline = DotPattern(radius: 0.9, edgeRadius: 1.3, pitch: 5, thickness: 2)
    /// Progress meters (1.3 hours, 7.3 restore): r 2.5 / 3, pitch 12, 8 high.
    static let progress = DotPattern(radius: 2.5, edgeRadius: 3, pitch: 12, thickness: 8)
    /// Password strength (7.2): r 2 / 2.5, pitch 8, 6 high.
    static let strength = DotPattern(radius: 2, edgeRadius: 2.5, pitch: 8, thickness: 6)
    /// Skeleton lines in the 1.2 illustration: r 2 / 2.5, pitch 8, 6 high.
    static let skeleton = DotPattern(radius: 2, edgeRadius: 2.5, pitch: 8, thickness: 6)
}

/// Draws a row (or column) of pattern dots in `rect`.
private func drawDots(
    in context: inout GraphicsContext, rect: CGRect, pattern: DotPattern, color: Color, vertical: Bool,
    count: Int? = nil
) {
    let length = vertical ? rect.height : rect.width
    // CSS repeat-x paints partial tiles at the end as well (clipped by the box).
    let tiles = count ?? Int((length / pattern.pitch).rounded(.up))
    guard tiles > 0 else { return }
    let gradient = Gradient(stops: [
        .init(color: color, location: 0),
        .init(color: color, location: pattern.radius / pattern.edgeRadius),
        .init(color: color.opacity(0), location: 1),
    ])
    for i in 0..<tiles {
        let along = pattern.pitch * (CGFloat(i) + 0.5)
        let center =
            vertical
            ? CGPoint(x: rect.midX, y: rect.minY + along)
            : CGPoint(x: rect.minX + along, y: rect.midY)
        let disc = CGRect(
            x: center.x - pattern.edgeRadius, y: center.y - pattern.edgeRadius,
            width: pattern.edgeRadius * 2, height: pattern.edgeRadius * 2)
        context.fill(
            Path(ellipseIn: disc),
            with: .radialGradient(gradient, center: center, startRadius: 0, endRadius: pattern.edgeRadius))
    }
}

/// A dotted rule, horizontal by default (C19). Fills the proposed length.
struct DottedRule: View {
    var color: Color = Color.u2.dotRule
    var pattern: DotPattern = .rule
    var axis: Axis = .horizontal

    var body: some View {
        Canvas { context, size in
            drawDots(
                in: &context, rect: CGRect(origin: .zero, size: size), pattern: pattern, color: color,
                vertical: axis == .vertical)
        }
        .frame(
            width: axis == .vertical ? pattern.thickness : nil,
            height: axis == .horizontal ? pattern.thickness : nil
        )
        .frame(maxWidth: axis == .horizontal ? .infinity : nil, maxHeight: axis == .vertical ? .infinity : nil)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// A dotted meter: a track of unlit dots with the first `lit` dots in
/// `litColor` (1.3 Ghost hours, 7.3 restore progress, 7.2 password strength).
/// Animate `progress` (`.u2Animation`) and the lit part sweeps left → right.
struct DottedMeter: View, Animatable {
    /// Fraction of the track that is lit (0…1), as the CSS overlay width.
    var progress: Double
    var pattern: DotPattern = .progress
    var trackColor: Color = Color.u2.dotTrack
    var litColor: Color = Color.u2.accent400

    nonisolated var animatableData: Double {
        get { progress }
        set { progress = newValue }
    }

    var body: some View {
        Canvas { context, size in
            let rect = CGRect(origin: .zero, size: size)
            drawDots(in: &context, rect: rect, pattern: pattern, color: trackColor, vertical: false)
            let litWidth = size.width * CGFloat(min(max(progress, 0), 1))
            guard litWidth > 0 else { return }
            var lit = context
            lit.clip(to: Path(CGRect(x: 0, y: 0, width: litWidth, height: size.height)))
            drawDots(in: &lit, rect: CGRect(x: 0, y: 0, width: litWidth, height: size.height), pattern: pattern, color: litColor, vertical: false)
        }
        .frame(height: pattern.thickness)
        .frame(maxWidth: .infinity)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// The 1.3 hour meter: one dot per hour of the Ghost period, lit for the hours
/// still remaining; the right-most lit dot fades to the track over 0.8 s when
/// an hour elapses.
struct DottedHourMeter: View {
    let totalHours: Int
    let litHours: Int

    var body: some View {
        HStack(spacing: 0) {
            ForEach(0..<totalHours, id: \.self) { index in
                Canvas { context, size in
                    drawDots(
                        in: &context, rect: CGRect(origin: .zero, size: size), pattern: .progress,
                        color: index < litHours ? Color.u2.accent400 : Color.u2.dotTrack, vertical: false, count: 1)
                }
                .frame(width: DotPattern.progress.pitch, height: DotPattern.progress.thickness)
                .u2Animation(U2Motion.ambient, value: index < litHours)
            }
        }
        .accessibilityHidden(true)
    }
}

#Preview("Dotted rules & meters") {
    VStack(alignment: .leading, spacing: 20) {
        DottedRule()
        DottedRule(color: Color.u2.dotRuleDate, pattern: .dateRule)
        HStack {
            DottedRule(color: Color.u2.dotRuleTimeline, pattern: .timeline, axis: .vertical).frame(height: 50)
        }
        DottedMeter(progress: 0.62)
        DottedMeter(progress: 0.82, pattern: .strength, trackColor: Color.u2.dotTrackStrength, litColor: Color.u2.online)
        DottedHourMeter(totalHours: 24, litHours: 23)
    }
    .padding(24)
    .background(Color.u2.bg)
}
