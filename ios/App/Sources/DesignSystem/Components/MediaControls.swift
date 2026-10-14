import SwiftUI

// The media states of 5.2 (proposed: “downloading — progress ring”) and the
// glass controls of the viewer and the player (5.6 language): one glass
// circle, one ring, one state glyph, shared by the bubbles, the photo viewer
// and the video player.

// MARK: - Activity

/// What the open conversation is doing with a message's media right now,
/// beyond what the messenger reports: a file being decrypted for its
/// preview shows the ring around its tile. In the environment of 5.1 (the
/// bubbles read it; 4.4's inert rows have none).
@Observable
@MainActor
final class ConversationMediaActivity {
    private(set) var opening: MessageID?

    func isOpening(_ id: MessageID) -> Bool { opening == id }

    func begin(_ id: MessageID) { opening = id }

    func end(_ id: MessageID) {
        if opening == id { opening = nil }
    }
}

// MARK: - Glass circle

/// The 5.2 video button: a circle of `#0c0d16@45%` over an 8 pt backdrop
/// blur with a 1 pt `#f5f4ff@35%` inner ring (46 in the bubble, 64 in the
/// player), its content centred in `#f5f4ff`.
struct MediaGlassCircle<Content: View>: View {
    var diameter: CGFloat = 46
    @ViewBuilder let content: Content

    var body: some View {
        content
            .foregroundStyle(Color.u2.onMedia)
            .frame(width: diameter, height: diameter)
            .u2Backdrop(.mediaButton, in: Circle())
            .overlay(Circle().strokeBorder(Color.u2.onMedia.opacity(0.35), lineWidth: 1))
    }
}

// MARK: - Progress ring

/// A transfer in progress: a 2 pt ring — track at 25 %, the done part solid,
/// from 12 o'clock clockwise. Without a fraction a quarter of the ring
/// travels round once per 1.2 s; under Reduce Motion (or a frozen clock) it
/// stands still. A change of fraction glides (0.24 s).
struct MediaProgressRing<S: Shape>: View {
    let fraction: Double?
    let shape: S
    var lineWidth: CGFloat = 2
    var tint: Color = Color.u2.onMedia
    var track: Color = Color.u2.onMedia.opacity(0.25)

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.u2Clock) private var clock

    var body: some View {
        let style = StrokeStyle(lineWidth: lineWidth, lineCap: .round)
        ZStack {
            shape.stroke(track, lineWidth: lineWidth)
            if let fraction {
                shape.trim(from: 0, to: max(0.02, min(1, fraction)))
                    .stroke(tint, style: style)
                    .u2Animation(U2Motion.micro, value: fraction)
            } else if reduceMotion || clock.isFrozen {
                shape.trim(from: 0, to: 0.25).stroke(tint, style: style)
            } else {
                TimelineView(.animation) { context in
                    let phase = (context.date.timeIntervalSinceReferenceDate / 1.2).truncatingRemainder(dividingBy: 1)
                    TravellingArc(shape: shape, phase: phase, length: 0.25, style: style, tint: tint)
                }
            }
        }
        .accessibilityHidden(true)
    }
}

/// `length` of the path starting at `phase` (wrapping past the end).
private struct TravellingArc<S: Shape>: View {
    let shape: S
    let phase: Double
    let length: Double
    let style: StrokeStyle
    let tint: Color

    var body: some View {
        let end = phase + length
        ZStack {
            shape.trim(from: phase, to: min(1, end)).stroke(tint, style: style)
            if end > 1 {
                shape.trim(from: 0, to: end - 1).stroke(tint, style: style)
            }
        }
    }
}

/// A rounded rectangle whose outline starts at the top centre and runs
/// clockwise — so a trimmed ring fills like a clock hand (the file tile's
/// ring; a `Circle` starts at 3 o'clock).
struct TopStartRoundedRectangle: Shape {
    var cornerRadius: CGFloat

    func path(in rect: CGRect) -> Path {
        let r = min(cornerRadius, rect.width / 2, rect.height / 2)
        var path = Path()
        path.move(to: CGPoint(x: rect.midX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX - r, y: rect.minY))
        path.addArc(
            center: CGPoint(x: rect.maxX - r, y: rect.minY + r), radius: r, startAngle: .degrees(-90),
            endAngle: .degrees(0), clockwise: false)
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - r))
        path.addArc(
            center: CGPoint(x: rect.maxX - r, y: rect.maxY - r), radius: r, startAngle: .degrees(0),
            endAngle: .degrees(90), clockwise: false)
        path.addLine(to: CGPoint(x: rect.minX + r, y: rect.maxY))
        path.addArc(
            center: CGPoint(x: rect.minX + r, y: rect.maxY - r), radius: r, startAngle: .degrees(90),
            endAngle: .degrees(180), clockwise: false)
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + r))
        path.addArc(
            center: CGPoint(x: rect.minX + r, y: rect.minY + r), radius: r, startAngle: .degrees(180),
            endAngle: .degrees(270), clockwise: false)
        path.closeSubpath()
        return path
    }
}

/// A circle whose outline starts at 12 o'clock and runs clockwise.
struct TopStartCircle: Shape {
    func path(in rect: CGRect) -> Path {
        let radius = min(rect.width, rect.height) / 2
        var path = Path()
        path.addArc(
            center: CGPoint(x: rect.midX, y: rect.midY), radius: radius, startAngle: .degrees(-90),
            endAngle: .degrees(270), clockwise: false)
        return path
    }
}

// MARK: - State glyph

/// What the glass circle holds for a media state: play (ready), download
/// (still on the relay), the ring (downloading, or a sent video being
/// prepared), the warning (gone, or the video could not be prepared).
struct MediaStateGlyph: View {
    let availability: MediaAvailability
    /// The 46 pt bubble circle (glyph 19) or the 64 pt player circle (26).
    var diameter: CGFloat = 46
    /// What “ready” shows (the photo bubble shows nothing).
    var readyGlyph: ReadyGlyph = .play

    enum ReadyGlyph {
        case play
        case none
    }

    private var glyphSize: CGFloat { diameter >= 60 ? 26 : 19 }

    var body: some View {
        StateCrossFade(value: kind) { kind in
            switch kind {
            case .ready:
                if readyGlyph == .play {
                    PhosphorIcon(.play, weight: .fill, size: glyphSize)
                }
            case .remote:
                MediaGlyphIcon(.download, size: glyphSize)
            case .downloading:
                MediaProgressRing(fraction: fraction, shape: TopStartCircle())
                    .padding(diameter * 0.17)
            case .unavailable:
                PhosphorIcon(.warningCircle, size: glyphSize)
                    .opacity(0.72)
            }
        }
    }

    private enum Kind: Equatable {
        case ready, remote, downloading, unavailable
    }

    private var kind: Kind {
        switch availability {
        case .ready: .ready
        case .remote: .remote
        case .downloading, .preparing: .downloading
        case .held, .unavailable, .preparationFailed: .unavailable
        }
    }

    private var fraction: Double? {
        if case .downloading(let fraction) = availability { return fraction }
        return nil
    }
}
