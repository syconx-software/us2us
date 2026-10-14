import SwiftUI

/// The media controls' glyphs the design file does not bundle (player,
/// downloads, generic files, the voice recorder — proposed states of 5.1 /
/// 5.2 / 5.6), drawn in code on
/// Phosphor's own 256-unit grid so they sit with the bundled set: regular =
/// 16-unit strokes with round caps and joins, fill = solid shapes. Sizing as
/// `PhosphorIcon`: “N pt” is an N × N frame; colour from the foreground style.
enum MediaGlyph: Sendable, Hashable, CaseIterable {
    /// `pause` (fill): two rounded bars.
    case pause
    /// `speaker-high` (regular).
    case speakerHigh
    /// `speaker-x` (regular): the speaker with a cross — muted.
    case speakerX
    /// `download-simple` (regular): arrow into a tray.
    case download
    /// `file` (regular): a page with a folded corner.
    case file
    /// `stop` (fill): a rounded square — stops a voice recording.
    case stop
    /// `caret-up` (regular): the voice recorder's “slide up to lock”.
    case caretUp
    /// `arrow-down` (regular): the “Kaydetti” chip under a saved photo.
    case arrowDown

    var isFilled: Bool { self == .pause || self == .stop }
}

struct MediaGlyphIcon: View {
    let glyph: MediaGlyph
    let size: CGFloat

    init(_ glyph: MediaGlyph, size: CGFloat) {
        self.glyph = glyph
        self.size = size
    }

    var body: some View {
        let shape = MediaGlyphShape(glyph: glyph)
        Group {
            if glyph.isFilled {
                shape.fill()
            } else {
                shape.stroke(style: StrokeStyle(lineWidth: size * 16 / 256, lineCap: .round, lineJoin: .round))
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// The glyph's outline in a square, from the 256-unit grid.
struct MediaGlyphShape: Shape {
    let glyph: MediaGlyph

    func path(in rect: CGRect) -> Path {
        let unit = min(rect.width, rect.height) / 256
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: rect.minX + x * unit, y: rect.minY + y * unit) }
        var path = Path()
        switch glyph {
        case .pause:
            for x in [40.0, 144.0] {
                path.addRoundedRect(
                    in: CGRect(origin: p(x, 32), size: CGSize(width: 72 * unit, height: 192 * unit)),
                    cornerSize: CGSize(width: 16 * unit, height: 16 * unit), style: .circular)
            }
        case .speakerHigh, .speakerX:
            // Body: the cone and the magnet.
            path.move(to: p(80, 168))
            path.addLine(to: p(32, 168))
            path.addQuadCurve(to: p(24, 160), control: p(24, 168))
            path.addLine(to: p(24, 96))
            path.addQuadCurve(to: p(32, 88), control: p(24, 88))
            path.addLine(to: p(80, 88))
            path.addLine(to: p(152, 32))
            path.addLine(to: p(152, 224))
            path.closeSubpath()
            path.move(to: p(80, 88))
            path.addLine(to: p(80, 168))
            if glyph == .speakerHigh {
                path.move(to: p(192, 106.85))
                path.addArc(
                    center: p(168, 128), radius: 32 * unit, startAngle: .degrees(-41), endAngle: .degrees(41),
                    clockwise: false)
                path.move(to: p(221.7, 80))
                path.addArc(
                    center: p(168, 128), radius: 72 * unit, startAngle: .degrees(-41.8), endAngle: .degrees(41.8),
                    clockwise: false)
            } else {
                path.move(to: p(200, 104))
                path.addLine(to: p(248, 152))
                path.move(to: p(248, 104))
                path.addLine(to: p(200, 152))
            }
        case .download:
            path.move(to: p(86, 110))
            path.addLine(to: p(128, 152))
            path.addLine(to: p(170, 110))
            path.move(to: p(128, 40))
            path.addLine(to: p(128, 152))
            path.move(to: p(216, 152))
            path.addLine(to: p(216, 208))
            path.addQuadCurve(to: p(208, 216), control: p(216, 216))
            path.addLine(to: p(48, 216))
            path.addQuadCurve(to: p(40, 208), control: p(40, 216))
            path.addLine(to: p(40, 152))
        case .file:
            path.move(to: p(200, 224))
            path.addLine(to: p(56, 224))
            path.addQuadCurve(to: p(48, 216), control: p(48, 224))
            path.addLine(to: p(48, 40))
            path.addQuadCurve(to: p(56, 32), control: p(48, 32))
            path.addLine(to: p(152, 32))
            path.addLine(to: p(208, 88))
            path.addLine(to: p(208, 216))
            path.addQuadCurve(to: p(200, 224), control: p(208, 224))
            path.closeSubpath()
            path.move(to: p(152, 32))
            path.addLine(to: p(152, 88))
            path.addLine(to: p(208, 88))
        case .stop:
            path.addRoundedRect(
                in: CGRect(origin: p(40, 40), size: CGSize(width: 176 * unit, height: 176 * unit)),
                cornerSize: CGSize(width: 16 * unit, height: 16 * unit), style: .circular)
        case .caretUp:
            path.move(to: p(48, 160))
            path.addLine(to: p(128, 80))
            path.addLine(to: p(208, 160))
        case .arrowDown:
            path.move(to: p(128, 40))
            path.addLine(to: p(128, 216))
            path.move(to: p(56, 144))
            path.addLine(to: p(128, 216))
            path.addLine(to: p(200, 144))
        }
        return path
    }
}

#Preview("Media glyphs") {
    HStack(spacing: 18) {
        ForEach(MediaGlyph.allCases, id: \.self) { glyph in
            MediaGlyphIcon(glyph, size: 24)
        }
    }
    .foregroundStyle(Color.u2.text)
    .padding(30)
    .background(Color.u2.bg)
}
