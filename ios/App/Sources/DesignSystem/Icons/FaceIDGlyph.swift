import SwiftUI

/// The design's own Face ID glyph (icons/custom/face-id.svg, 24 × 24 stroke
/// path, round caps and joins) — preferred over the SF Symbol.
///
/// The stroke width is given in the SVG's 24-unit space and scales with the
/// glyph: 56 pt @ 1.3 (3.1 disc), 30 pt @ 1.6 (keypad key), 22 pt @ 1.7
/// (2.3 card), 18 pt @ 1.8 (settings tile).
struct FaceIDGlyph: View {
    let size: CGFloat
    /// Stroke width in the glyph's 24-unit coordinate space.
    let lineWidth: CGFloat

    var body: some View {
        FaceIDShape()
            .stroke(style: StrokeStyle(lineWidth: lineWidth * size / 24, lineCap: .round, lineJoin: .round))
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

/// `M2 7V5a3 3 0 0 1 3-3h2 M17 2h2a3 3 0 0 1 3 3v2 M22 17v2a3 3 0 0 1-3 3h-2
/// M7 22H5a3 3 0 0 1-3-3v-2 M8.5 9v1.5 M15.5 9v1.5 M12 9v4.5h-1
/// M9 16.5c1.7 1.3 4.3 1.3 6 0`
struct FaceIDShape: Shape {
    func path(in rect: CGRect) -> Path {
        let s = min(rect.width, rect.height) / 24
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
            CGPoint(x: rect.minX + x * s, y: rect.minY + y * s)
        }
        var path = Path()
        // Corners: quarter circles of radius 3 (the SVG's `a3 3 0 0 1`).
        path.move(to: p(2, 7))
        path.addLine(to: p(2, 5))
        path.addArc(tangent1End: p(2, 2), tangent2End: p(5, 2), radius: 3 * s)
        path.addLine(to: p(7, 2))

        path.move(to: p(17, 2))
        path.addLine(to: p(19, 2))
        path.addArc(tangent1End: p(22, 2), tangent2End: p(22, 5), radius: 3 * s)
        path.addLine(to: p(22, 7))

        path.move(to: p(22, 17))
        path.addLine(to: p(22, 19))
        path.addArc(tangent1End: p(22, 22), tangent2End: p(19, 22), radius: 3 * s)
        path.addLine(to: p(17, 22))

        path.move(to: p(7, 22))
        path.addLine(to: p(5, 22))
        path.addArc(tangent1End: p(2, 22), tangent2End: p(2, 19), radius: 3 * s)
        path.addLine(to: p(2, 17))

        // Eyes.
        path.move(to: p(8.5, 9))
        path.addLine(to: p(8.5, 10.5))
        path.move(to: p(15.5, 9))
        path.addLine(to: p(15.5, 10.5))

        // Nose.
        path.move(to: p(12, 9))
        path.addLine(to: p(12, 13.5))
        path.addLine(to: p(11, 13.5))

        // Smile.
        path.move(to: p(9, 16.5))
        path.addCurve(to: p(15, 16.5), control1: p(10.7, 17.8), control2: p(13.3, 17.8))
        return path
    }
}

#Preview("Face ID glyph") {
    HStack(spacing: 24) {
        FaceIDGlyph(size: 56, lineWidth: 1.3).foregroundStyle(Color.u2.accent300)
        FaceIDGlyph(size: 30, lineWidth: 1.6).foregroundStyle(Color.u2.text)
        FaceIDGlyph(size: 22, lineWidth: 1.7).foregroundStyle(Color.u2.accent300)
        FaceIDGlyph(size: 18, lineWidth: 1.8).foregroundStyle(Color.u2.accent300)
    }
    .padding(40)
    .background(Color.u2.bg)
}
