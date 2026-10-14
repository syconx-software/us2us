import CoreText
import SwiftUI

// The drawing kit of the built-in stickers: inks, the die-cut pen, shapes and
// the cup mascot. Every sticker is drawn in a 100 × 100 box (one unit = one
// hundredth of the sticker's side), so the art is resolution-independent and
// every line keeps the same weight at every size.

// MARK: - Inks

/// The sticker inks: Nocturne's accent ramp at its dark-theme values, with
/// `danger` as the one warm note (hearts, blush, the morning sun). They are
/// the same in both themes — a sticker is a printed thing, like a photo; only
/// what lies around it changes with the theme (`BrandStickerFrame`).
enum BrandStickerInk {
    /// The die-cut paper around every sticker (`stickerRing`, #f5f4ff).
    static let paper = fixed(U2FixedColorTokens.stickerRing)
    /// Outlines, faces and words (accent900, #2b2741).
    static let ink = fixed(U2ColorTokens.accent900)
    /// The light cup, cards (accent200, #e7e5fe).
    static let lilac100 = fixed(U2ColorTokens.accent200)
    /// Its shade, rims (accent300, #d2cefd).
    static let lilac200 = fixed(U2ColorTokens.accent300)
    /// The mascot cup (accent400, #b5abfc — the line of the app icon).
    static let lilac300 = fixed(U2ColorTokens.accent400)
    /// The mascot's shade (accent500, #968ae0).
    static let lilac400 = fixed(U2ColorTokens.accent500)
    /// The string (accent600, #796cbf).
    static let string = fixed(U2ColorTokens.accent600)
    /// Deep marks: the line mark on cards (accent700, #5d5294).
    static let lilac600 = fixed(U2ColorTokens.accent700)
    /// Night (accent800, #423a6a).
    static let night = fixed(U2ColorTokens.accent800)
    /// Hearts, blush, the sun (danger, #f2716a).
    static let warm = fixed(U2ColorTokens.danger)

    /// A token's dark value as a fixed colour.
    static func fixed(_ token: U2ThemedColor) -> Color {
        let hex = token.dark
        return Color(
            .sRGB, red: Double((hex >> 16) & 0xff) / 255, green: Double((hex >> 8) & 0xff) / 255,
            blue: Double(hex & 0xff) / 255, opacity: token.darkAlpha)
    }
}

/// What the theme changes around a sticker: its drop shadow and, in the
/// light theme, a hairline on the edge of the paper (#f5f4ff on #f3f5fe would
/// otherwise disappear).
struct BrandStickerFrame: Sendable {
    let shadow: Color
    /// Shadow blur radius and offset in units.
    let shadowRadius: CGFloat
    let shadowY: CGFloat
    let hairline: Color?

    static func `for`(_ scheme: ColorScheme) -> BrandStickerFrame {
        switch scheme {
        case .light:
            BrandStickerFrame(
                shadow: BrandStickerInk.fixedLight(U2ColorTokens.text, opacity: 0.18), shadowRadius: 2.2, shadowY: 1.6,
                hairline: BrandStickerInk.fixedLight(U2ColorTokens.text, opacity: 0.13))
        default:
            // The 5.2 sticker's `0 12px 26px #000@35%`, scaled to the art.
            BrandStickerFrame(
                shadow: BrandStickerInk.fixed(U2FixedColorTokens.black.opacity(0.36)), shadowRadius: 2.6, shadowY: 2.2,
                hairline: nil)
        }
    }
}

extension BrandStickerInk {
    /// A token's light value as a fixed colour.
    static func fixedLight(_ token: U2ThemedColor, opacity: Double) -> Color {
        fixed(U2ThemedColor(fixed: token.light, alpha: token.lightAlpha * opacity))
    }
}

// MARK: - Pen

/// Draws a sticker in two passes over the same composition: first the paper
/// (every shape grown by the die-cut margin, under the shadow), then the art
/// (fills, ink outlines, faces, words). A composition calls the same methods
/// in both passes; details inside shapes only draw in the art pass.
struct StickerPen {
    enum Pass {
        /// The die-cut paper: every shape and line grown by `spread`.
        case paper(spread: CGFloat, color: Color)
        case art
    }

    /// Line weights in units — the same across the whole set.
    enum Weight {
        /// Every outline, arm and mouth.
        static let line: CGFloat = 2.4
        /// The string.
        static let string: CGFloat = 2.2
        /// Fine inner marks (brows, pupils' rings).
        static let fine: CGFloat = 1.5
        /// The die-cut paper beyond the outline.
        static let cut: CGFloat = 3.6
        /// The light theme's hairline beyond the paper.
        static let hairline: CGFloat = 0.6
    }

    var context: GraphicsContext
    let pass: Pass

    var isArt: Bool {
        if case .art = pass { return true }
        return false
    }

    private static func style(_ width: CGFloat, dash: [CGFloat] = []) -> StrokeStyle {
        StrokeStyle(lineWidth: width, lineCap: .round, lineJoin: .round, dash: dash)
    }

    /// A filled shape with an ink outline.
    func shape(_ path: Path, _ fill: Color, outline: Bool = true) {
        switch pass {
        case .paper(let spread, let color):
            context.fill(path, with: .color(color))
            context.stroke(path, with: .color(color), style: Self.style((outline ? Weight.line : 0) + spread * 2))
        case .art:
            context.fill(path, with: .color(fill))
            if outline { context.stroke(path, with: .color(BrandStickerInk.ink), style: Self.style(Weight.line)) }
        }
    }

    /// A thick outlined stroke — a ring, a shackle, the key's bow.
    func tube(_ path: Path, _ fill: Color, width: CGFloat) {
        switch pass {
        case .paper(let spread, let color):
            context.stroke(path, with: .color(color), style: Self.style(width + Weight.line + spread * 2))
        case .art:
            context.stroke(path, with: .color(BrandStickerInk.ink), style: Self.style(width + Weight.line))
            context.stroke(path, with: .color(fill), style: Self.style(width - Weight.line))
        }
    }

    /// A line on the paper (the string, arms, motion marks). `dotted` draws
    /// round dots `gap` apart — the dot-matrix of the brand.
    func line(
        _ path: Path, _ color: Color = BrandStickerInk.ink, width: CGFloat = Weight.line, dotted gap: CGFloat? = nil,
        onPaper: Bool = true
    ) {
        switch pass {
        case .paper(let spread, let paper):
            guard onPaper else { return }
            context.stroke(path, with: .color(paper), style: Self.style(width + spread * 2))
        case .art:
            context.stroke(path, with: .color(color), style: Self.style(width, dash: gap.map { [0.001, $0] } ?? []))
        }
    }

    /// A fill inside the art (faces, shading) — nothing on the paper.
    func fill(_ path: Path, _ color: Color) {
        guard isArt else { return }
        context.fill(path, with: .color(color))
    }

    /// A stroke inside the art — nothing on the paper.
    func stroke(
        _ path: Path, _ color: Color = BrandStickerInk.ink, width: CGFloat = Weight.line, dotted gap: CGFloat? = nil
    ) {
        guard isArt else { return }
        context.stroke(path, with: .color(color), style: Self.style(width, dash: gap.map { [0.001, $0] } ?? []))
    }

    /// The pen clipped to a shape (shading inside a cup).
    func clipped(to path: Path) -> StickerPen {
        var copy = self
        copy.context.clip(to: path)
        return copy
    }

    /// Words in Doto, centred in `box` and set as large as fits (at most
    /// `maxSize` units). Doto is monospaced (0.6 em), so the size is computed,
    /// not measured; lines are centred on their cap height.
    func words(
        _ lines: [String], in box: CGRect, color: Color = BrandStickerInk.ink, maxSize: CGFloat,
        tracking: CGFloat = -0.02, leading: CGFloat = 1.0, rotation: Angle = .zero
    ) {
        guard isArt, !lines.isEmpty else { return }
        let size = BrandStickerType.fittedSize(
            lines, in: box.size, maxSize: maxSize, tracking: tracking, leading: leading)
        var block = context
        block.translateBy(x: box.midX, y: box.midY)
        block.rotate(by: rotation)
        let height = (BrandStickerType.capHeight + CGFloat(lines.count - 1) * leading) * size
        for (index, line) in lines.enumerated() {
            let baseline = -height / 2 + (BrandStickerType.capHeight + CGFloat(index) * leading) * size
            glyphs(line, in: block, baseline: CGPoint(x: 0, y: baseline), size: size, color: color, tracking: tracking)
        }
    }

    /// One run of Doto, horizontally centred on `baseline`.
    ///
    /// Set with Core Text rather than a resolved `Text`: the run keeps the
    /// exact variable instance `U2Fonts` makes (wght 900, ROND 100), and the
    /// glyphs stay vectors at any scale.
    func glyphs(
        _ text: String, in context: GraphicsContext, baseline: CGPoint, size: CGFloat, color: Color,
        tracking: CGFloat = -0.02, rotation: Angle = .zero
    ) {
        guard isArt, !text.isEmpty else { return }
        var run = context
        run.translateBy(x: baseline.x, y: baseline.y)
        run.rotate(by: rotation)
        let scale = size / BrandStickerType.referenceSize
        run.scaleBy(x: scale, y: scale)
        let line = BrandStickerType.line(text, tracking: tracking, color: color.resolve(in: run.environment).cgColor)
        // Tracking also follows the last glyph: centre the ink, not the advance.
        let width = CGFloat(CTLineGetTypographicBounds(line, nil, nil, nil)) - tracking * BrandStickerType.referenceSize
        run.withCGContext { cg in
            cg.translateBy(x: -width / 2, y: 0)
            cg.scaleBy(x: 1, y: -1)
            cg.textMatrix = .identity
            cg.textPosition = .zero
            CTLineDraw(line, cg)
        }
    }
}

/// Doto 900 (ROND 100) for the words on stickers. It is set at one
/// reference size and scaled, so one font instance serves every size.
enum BrandStickerType {
    static let referenceSize: CGFloat = 20
    /// Every Doto glyph advances 600 / 1000 em.
    static let advance: CGFloat = 0.6
    /// Cap height 700 / 1000 em.
    static let capHeight: CGFloat = 0.7
    /// Room kept above the caps (İ, Ö, Ü) and below the baseline (ş, g, y) when fitting.
    static let accentRoom: CGFloat = 0.26

    static var font: CTFont { U2Fonts.font(.doto, weight: 900, size: referenceSize) as CTFont }

    /// A Core Text line of `string` at the reference size.
    static func line(_ string: String, tracking: CGFloat, color: CGColor) -> CTLine {
        let attributes: [NSAttributedString.Key: Any] = [
            NSAttributedString.Key(kCTFontAttributeName as String): font,
            NSAttributedString.Key(kCTKernAttributeName as String): tracking * referenceSize,
            NSAttributedString.Key(kCTForegroundColorAttributeName as String): color,
        ]
        return CTLineCreateWithAttributedString(NSAttributedString(string: string, attributes: attributes))
    }

    /// The size (units) at which `lines` fit `box`: the longest line fills the
    /// width, the block the height, never above `maxSize`.
    static func fittedSize(
        _ lines: [String], in box: CGSize, maxSize: CGFloat, tracking: CGFloat = -0.02, leading: CGFloat = 1.0
    ) -> CGFloat {
        let longest = CGFloat(lines.map(\.count).max() ?? 1)
        let byWidth = box.width / max(1, longest * (advance + tracking) - tracking)
        let block = capHeight + CGFloat(max(0, lines.count - 1)) * leading + accentRoom
        let byHeight = box.height / block
        return min(maxSize, byWidth, byHeight)
    }
}

// MARK: - Shapes

/// Path builders in sticker units.
enum StickerShape {
    static func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x, y: y) }

    static func circle(_ center: CGPoint, _ radius: CGFloat) -> Path {
        Path(ellipseIn: CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2))
    }

    static func ellipse(_ center: CGPoint, _ rx: CGFloat, _ ry: CGFloat) -> Path {
        Path(ellipseIn: CGRect(x: center.x - rx, y: center.y - ry, width: rx * 2, height: ry * 2))
    }

    static func rounded(_ rect: CGRect, _ radius: CGFloat) -> Path {
        Path(roundedRect: rect, cornerRadius: radius, style: .continuous)
    }

    static func polyline(_ points: [CGPoint]) -> Path {
        var path = Path()
        path.addLines(points)
        return path
    }

    static func quad(_ from: CGPoint, _ control: CGPoint, _ to: CGPoint) -> Path {
        var path = Path()
        path.move(to: from)
        path.addQuadCurve(to: to, control: control)
        return path
    }

    static func curve(_ from: CGPoint, _ control1: CGPoint, _ control2: CGPoint, _ to: CGPoint) -> Path {
        var path = Path()
        path.move(to: from)
        path.addCurve(to: to, control1: control1, control2: control2)
        return path
    }

    /// A smooth line through `points` (Catmull–Rom as cubic Béziers).
    static func smooth(_ points: [CGPoint], tension: CGFloat = 1) -> Path {
        var path = Path()
        guard let first = points.first else { return path }
        path.move(to: first)
        guard points.count > 2 else {
            if points.count == 2 { path.addLine(to: points[1]) }
            return path
        }
        for index in 0..<(points.count - 1) {
            let p0 = points[max(0, index - 1)]
            let p1 = points[index]
            let p2 = points[index + 1]
            let p3 = points[min(points.count - 1, index + 2)]
            let c1 = CGPoint(x: p1.x + (p2.x - p0.x) / 6 * tension, y: p1.y + (p2.y - p0.y) / 6 * tension)
            let c2 = CGPoint(x: p2.x - (p3.x - p1.x) / 6 * tension, y: p2.y - (p3.y - p1.y) / 6 * tension)
            path.addCurve(to: p2, control1: c1, control2: c2)
        }
        return path
    }

    /// A heart `width` wide (its height ≈ 0.92 × width), centred on its box.
    static func heart(_ center: CGPoint, width: CGFloat) -> Path {
        let w = width / 2
        let h = width * 0.92
        let top = center.y - h * 0.5
        let bottom = CGPoint(x: center.x, y: center.y + h * 0.5)
        let cusp = CGPoint(x: center.x, y: top + h * 0.24)
        var path = Path()
        path.move(to: bottom)
        path.addCurve(
            to: CGPoint(x: center.x - w, y: top + h * 0.3),
            control1: CGPoint(x: center.x - w * 0.24, y: bottom.y - h * 0.2),
            control2: CGPoint(x: center.x - w, y: top + h * 0.6))
        path.addCurve(
            to: cusp, control1: CGPoint(x: center.x - w, y: top - h * 0.06),
            control2: CGPoint(x: center.x - w * 0.08, y: top - h * 0.06))
        path.addCurve(
            to: CGPoint(x: center.x + w, y: top + h * 0.3),
            control1: CGPoint(x: center.x + w * 0.08, y: top - h * 0.06),
            control2: CGPoint(x: center.x + w, y: top - h * 0.06))
        path.addCurve(
            to: bottom, control1: CGPoint(x: center.x + w, y: top + h * 0.6),
            control2: CGPoint(x: center.x + w * 0.24, y: bottom.y - h * 0.2))
        path.closeSubpath()
        return path
    }

    /// A four-point sparkle with concave sides.
    static func sparkle(_ center: CGPoint, _ radius: CGFloat, waist: CGFloat = 0.18) -> Path {
        let r = radius
        let k = radius * waist
        var path = Path()
        path.move(to: CGPoint(x: center.x, y: center.y - r))
        path.addQuadCurve(to: CGPoint(x: center.x + r, y: center.y), control: CGPoint(x: center.x + k, y: center.y - k))
        path.addQuadCurve(to: CGPoint(x: center.x, y: center.y + r), control: CGPoint(x: center.x + k, y: center.y + k))
        path.addQuadCurve(to: CGPoint(x: center.x - r, y: center.y), control: CGPoint(x: center.x - k, y: center.y + k))
        path.addQuadCurve(to: CGPoint(x: center.x, y: center.y - r), control: CGPoint(x: center.x - k, y: center.y - k))
        path.closeSubpath()
        return path
    }

    /// A falling drop pointing up, `size` high.
    static func drop(_ center: CGPoint, size: CGFloat, angle: Angle = .zero) -> Path {
        let r = size * 0.36
        var path = Path()
        let tip = CGPoint(x: 0, y: -size * 0.58)
        path.move(to: tip)
        path.addCurve(
            to: CGPoint(x: r, y: size * 0.1), control1: CGPoint(x: r * 0.3, y: -size * 0.3),
            control2: CGPoint(x: r, y: -size * 0.1))
        path.addArc(
            center: CGPoint(x: 0, y: size * 0.1), radius: r, startAngle: .degrees(0), endAngle: .degrees(180),
            clockwise: false)
        path.addCurve(to: tip, control1: CGPoint(x: -r, y: -size * 0.1), control2: CGPoint(x: -r * 0.3, y: -size * 0.3))
        path.closeSubpath()
        return path.applying(CGAffineTransform(translationX: center.x, y: center.y).rotated(by: angle.radians))
    }

    /// A speech bubble: a rounded rect with a tail from its bottom edge
    /// (`tailX` the tail's centre on that edge) to `tip`.
    static func bubble(_ rect: CGRect, radius: CGFloat, tailX: CGFloat, tailWidth: CGFloat, tip: CGPoint) -> Path {
        var path = Path()
        let r = min(radius, rect.height / 2, rect.width / 2)
        let left = tailX - tailWidth / 2
        let right = tailX + tailWidth / 2
        path.move(to: CGPoint(x: rect.minX + r, y: rect.minY))
        path.addArc(
            tangent1End: CGPoint(x: rect.maxX, y: rect.minY), tangent2End: CGPoint(x: rect.maxX, y: rect.maxY),
            radius: r)
        path.addArc(
            tangent1End: CGPoint(x: rect.maxX, y: rect.maxY), tangent2End: CGPoint(x: rect.minX, y: rect.maxY),
            radius: r)
        path.addLine(to: CGPoint(x: right, y: rect.maxY))
        path.addQuadCurve(to: tip, control: CGPoint(x: right - tailWidth * 0.2, y: (rect.maxY + tip.y) / 2))
        path.addQuadCurve(
            to: CGPoint(x: left, y: rect.maxY),
            control: CGPoint(x: left + tailWidth * 0.1, y: rect.maxY + (tip.y - rect.maxY) * 0.3))
        path.addArc(
            tangent1End: CGPoint(x: rect.minX, y: rect.maxY), tangent2End: CGPoint(x: rect.minX, y: rect.minY),
            radius: r)
        path.addArc(
            tangent1End: CGPoint(x: rect.minX, y: rect.minY), tangent2End: CGPoint(x: rect.maxX, y: rect.minY),
            radius: r)
        path.closeSubpath()
        return path
    }

    /// A round seal with `bumps` scallops.
    static func scallop(_ center: CGPoint, radius: CGFloat, bumps: Int, depth: CGFloat) -> Path {
        var path = Path()
        let steps = bumps * 12
        for step in 0...steps {
            let t = CGFloat(step) / CGFloat(steps) * .pi * 2
            let r = radius - depth * 0.5 + depth * 0.5 * cos(CGFloat(bumps) * t)
            let p = CGPoint(x: center.x + r * sin(t), y: center.y - r * cos(t))
            if step == 0 { path.move(to: p) } else { path.addLine(to: p) }
        }
        path.closeSubpath()
        return path
    }

    /// A small open spiral (dizzy eyes, the tangle).
    static func spiral(_ center: CGPoint, radius: CGFloat, turns: CGFloat = 1.6, start: Angle = .zero) -> Path {
        var path = Path()
        let steps = Int(turns * 36)
        for step in 0...steps {
            let f = CGFloat(step) / CGFloat(steps)
            let a = start.radians + Double(f * turns * 2 * .pi)
            let r = radius * (0.12 + 0.88 * f)
            let p = CGPoint(x: center.x + r * CGFloat(cos(a)), y: center.y + r * CGFloat(sin(a)))
            if step == 0 { path.move(to: p) } else { path.addLine(to: p) }
        }
        return path
    }

    /// An arc of a circle (angles clockwise from 3 o'clock, as on screen).
    static func arc(_ center: CGPoint, _ radius: CGFloat, from start: Angle, to end: Angle) -> Path {
        var path = Path()
        path.addArc(center: center, radius: radius, startAngle: start, endAngle: end, clockwise: false)
        return path
    }

    /// A padlock body (`rect`) with its shackle above; returns (body, shackle centre line).
    static func padlock(_ rect: CGRect, shackleHeight: CGFloat) -> (body: Path, shackle: Path) {
        let body = rounded(rect, rect.width * 0.2)
        let inset = rect.width * 0.22
        let left = rect.minX + inset
        let right = rect.maxX - inset
        let radius = (right - left) / 2
        var shackle = Path()
        shackle.move(to: CGPoint(x: left, y: rect.minY + 1))
        shackle.addLine(to: CGPoint(x: left, y: rect.minY - shackleHeight + radius))
        shackle.addArc(
            center: CGPoint(x: (left + right) / 2, y: rect.minY - shackleHeight + radius), radius: radius,
            startAngle: .degrees(180), endAngle: .degrees(0), clockwise: false)
        shackle.addLine(to: CGPoint(x: right, y: rect.minY + 1))
        return (body, shackle)
    }

    /// A keyhole centred at `center`, `size` high.
    static func keyhole(_ center: CGPoint, size: CGFloat) -> Path {
        var path = circle(CGPoint(x: center.x, y: center.y - size * 0.18), size * 0.26)
        var foot = Path()
        foot.move(to: CGPoint(x: center.x - size * 0.1, y: center.y - size * 0.1))
        foot.addLine(to: CGPoint(x: center.x + size * 0.1, y: center.y - size * 0.1))
        foot.addLine(to: CGPoint(x: center.x + size * 0.17, y: center.y + size * 0.42))
        foot.addLine(to: CGPoint(x: center.x - size * 0.17, y: center.y + size * 0.42))
        foot.closeSubpath()
        path.addPath(foot)
        return path
    }

    /// A bow knot centred at `center`: two loops and two tails.
    static func bow(_ center: CGPoint, size: CGFloat) -> (loops: [Path], tails: [Path], knot: Path) {
        let s = size
        func loop(_ side: CGFloat) -> Path {
            var path = Path()
            path.move(to: center)
            path.addCurve(
                to: CGPoint(x: center.x + side * s * 0.5, y: center.y - s * 0.02),
                control1: CGPoint(x: center.x + side * s * 0.12, y: center.y - s * 0.42),
                control2: CGPoint(x: center.x + side * s * 0.52, y: center.y - s * 0.4))
            path.addCurve(
                to: center, control1: CGPoint(x: center.x + side * s * 0.48, y: center.y + s * 0.3),
                control2: CGPoint(x: center.x + side * s * 0.12, y: center.y + s * 0.2))
            path.closeSubpath()
            return path
        }
        func tail(_ side: CGFloat) -> Path {
            curve(
                center, CGPoint(x: center.x + side * s * 0.1, y: center.y + s * 0.2),
                CGPoint(x: center.x + side * s * 0.28, y: center.y + s * 0.3),
                CGPoint(x: center.x + side * s * 0.36, y: center.y + s * 0.5))
        }
        return ([loop(-1), loop(1)], [tail(-1), tail(1)], ellipse(center, s * 0.09, s * 0.11))
    }
}
