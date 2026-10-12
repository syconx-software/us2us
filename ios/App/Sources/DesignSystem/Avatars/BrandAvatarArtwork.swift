import SwiftUI

// How a preset avatar is drawn: the stickers' cup mascot, at a sticker's own
// scale, seen through a round window. The art lives in the stickers' 100 × 100
// unit box, so the cup, its face and every line weight are exactly the
// stickers'; the disc is a circle in that box (`BrandAvatarFrame`) that the
// view maps onto its frame.

// MARK: - Frame

/// The avatar's window on the sticker box and where the cup stands in it.
enum BrandAvatarFrame {
    /// The disc's centre and radius in sticker units.
    static let center = CGPoint(x: 50, y: 50)
    static let radius: CGFloat = 42
    /// The cup: a sticker's scale (the cups of the set stand at 1.38–1.48),
    /// its base a little above the bottom of the disc.
    static let cupScale: CGFloat = 1.4
    static let cupBase = CGPoint(x: 50, y: 86.5)

    /// The disc in sticker units.
    static var disc: Path { StickerShape.circle(center, radius) }

    /// Sticker units → points, for a disc `diameter` points wide whose top-left is `origin`.
    static func transform(diameter: CGFloat, origin: CGPoint = .zero) -> CGAffineTransform {
        let unit = diameter / (radius * 2)
        return CGAffineTransform(translationX: origin.x + diameter / 2, y: origin.y + diameter / 2)
            .scaledBy(x: unit, y: unit)
            .translatedBy(x: -center.x, y: -center.y)
    }
}

/// How much is drawn: small avatars (chat header, request cards) keep the
/// cup, its face and what it wears; from 40 pt on the little extras around
/// it (sparkles, steam, whiskers) are drawn too.
enum BrandAvatarDetail: Sendable, Hashable {
    case compact
    case full

    static let fullFromDiameter: CGFloat = 40

    init(diameter: CGFloat) {
        self = diameter >= Self.fullFromDiameter ? .full : .compact
    }
}

/// Colours of the avatar that are not sticker inks.
enum BrandAvatarInk {
    static func color(hex: UInt32, alpha: Double = 1) -> Color {
        Color(
            .sRGB, red: Double((hex >> 16) & 0xff) / 255, green: Double((hex >> 8) & 0xff) / 255,
            blue: Double(hex & 0xff) / 255, opacity: alpha)
    }

    /// The light theme's hairline round a pale disc (`text·10%`, as on cards).
    static let lightHairline = color(hex: U2ColorTokens.strokeCard.light, alpha: U2ColorTokens.strokeCard.lightAlpha)
}

// MARK: - Renderer

/// Draws a preset avatar with `Canvas`: the disc, then the cup on it, clipped
/// to the circle. Vector all the way down, so it is crisp at any size. No
/// accessibility of its own — `BrandAvatarView` adds the label.
struct BrandAvatarCanvas: View {
    let preset: BrandAvatarPreset
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let scheme = colorScheme
        Canvas { context, size in
            Self.render(preset, scheme: scheme, in: context, size: size)
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityHidden(true)
    }

    /// The disc centred in `size`, as large as fits.
    static func render(_ preset: BrandAvatarPreset, scheme: ColorScheme, in context: GraphicsContext, size: CGSize) {
        let diameter = min(size.width, size.height)
        guard diameter > 0 else { return }
        let origin = CGPoint(x: (size.width - diameter) / 2, y: (size.height - diameter) / 2)
        let disc = Path(ellipseIn: CGRect(origin: origin, size: CGSize(width: diameter, height: diameter)))

        var clipped = context
        clipped.clip(to: disc)
        clipped.fill(disc, with: .color(preset.backdrop.color(for: scheme)))

        var art = clipped
        art.concatenate(BrandAvatarFrame.transform(diameter: diameter, origin: origin))
        BrandAvatarArt.draw(
            preset, pen: StickerPen(context: art, pass: .art), detail: BrandAvatarDetail(diameter: diameter),
            scheme: scheme)

        if scheme == .light, preset.backdrop.needsLightHairline {
            let inset = Path(
                ellipseIn: CGRect(origin: origin, size: CGSize(width: diameter, height: diameter)).insetBy(
                    dx: 0.25, dy: 0.25))
            context.stroke(inset, with: .color(BrandAvatarInk.lightHairline), lineWidth: 0.5)
        }
    }
}

// MARK: - Compositions

/// One composition for every preset: what floats behind, the accessory's
/// back, the cup, the accessory's front. Points are in the cup's own units
/// (base centre = origin, up = −y; the rim's top is at −39, the face's
/// centre at −16) unless they say otherwise.
enum BrandAvatarArt {
    /// `scheme` only colours what lies on the disc itself (sparkles, marks,
    /// steam); the cup and what it wears are printed in the sticker inks.
    static func draw(_ preset: BrandAvatarPreset, pen: StickerPen, detail: BrandAvatarDetail, scheme: ColorScheme) {
        var cup = Cup(
            BrandAvatarFrame.cupBase.x, BrandAvatarFrame.cupBase.y, scale: BrandAvatarFrame.cupScale,
            style: preset.cup.style)
        cup.face = preset.expression.face
        if preset.accessory == .coffee { cup.faceOffset = -4 }
        let composer = Composer(pen: pen, cup: cup, detail: detail, scheme: scheme)
        composer.ornaments(preset)
        composer.behind(preset.accessory)
        if preset.accessory == .ghostSheet {
            composer.ghost()
        } else {
            cup.draw(pen)
        }
        composer.front(preset.accessory)
    }

    private struct Composer {
        let pen: StickerPen
        let cup: Cup
        let detail: BrandAvatarDetail
        let scheme: ColorScheme

        private var ink: BrandStickerInk.Type { BrandStickerInk.self }
        private var isFull: Bool { detail == .full }
        /// Marks drawn straight on the disc: light on the deep discs, deeper on the pale ones.
        private var onDisc: Color { scheme == .light ? ink.string : ink.lilac200 }

        /// A point of the cup, in sticker units.
        func c(_ x: CGFloat, _ y: CGFloat) -> CGPoint { cup.point(x, y) }
        /// A path in the cup's units, in sticker units.
        func on(_ path: Path) -> Path { path.applying(cup.transform) }
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x, y: y) }

        // MARK: Around the cup

        /// The extras on the disc behind the cup (full detail only).
        func ornaments(_ preset: BrandAvatarPreset) {
            guard isFull else { return }
            switch (preset.accessory, preset.expression) {
            case (.none, .smile):
                pen.dotSparkle(p(80, 30), 3.4, onDisc)
                pen.dotSparkle(p(21, 44), 2.6, onDisc)
            case (.none, .cool):
                pen.sparkle(p(80, 27), 5.6)
                pen.sparkle(p(87, 41), 3)
            case (.none, .wink):
                pen.sparkle(p(80, 28), 5.2)
                pen.dotSparkle(p(70, 17), 2.6, onDisc)
            case (.none, .curious):
                marks(around: c(0, -39), from: 6, to: 11, angles: [-140, -90, -40])
            case (.none, .laugh):
                marks(around: c(0, -30), from: 31, to: 35.5, angles: [-125, -145, -35, -55])
            case (.none, _) where preset.cup == .twin:
                pen.heart(p(81, 31), 7.5)
                pen.dotSparkle(p(20, 40), 2.8, onDisc)
            case (.nightcap, _):
                pen.sparkle(p(22, 30), 3.6, ink.lilac200)
                pen.sparkle(p(80, 18), 2.6, ink.lilac200)
                pen.dotSparkle(p(17, 52), 2.2, onDisc)
            case (.partyHat, _):
                let confetti: [(CGPoint, Color, Double)] = [
                    (p(20, 36), ink.warm, 30), (p(30, 21), onDisc, -20), (p(81, 34), onDisc, 50),
                    (p(84, 50), ink.warm, -40), (p(16, 54), ink.lilac400, 10),
                ]
                for (center, color, angle) in confetti {
                    let piece = StickerShape.rounded(CGRect(x: -2.6, y: -1.2, width: 5.2, height: 2.4), 1)
                        .applying(
                            CGAffineTransform(translationX: center.x, y: center.y).rotated(
                                by: Angle.degrees(angle).radians))
                    pen.shape(piece, color, outline: false)
                }
            case (.crown, _):
                pen.sparkle(p(23, 30), 3.6, ink.lilac100)
                pen.sparkle(p(79, 27), 2.8, ink.lilac100)
            case (.ghostSheet, _):
                pen.line(StickerShape.polyline([p(14, 52), p(18, 52)]), onDisc, width: 2)
                pen.line(StickerShape.polyline([p(13, 61), p(17.5, 61)]), onDisc, width: 2)
            case (.antennae, _):
                pen.dotSparkle(p(80, 33), 2.6, onDisc)
                pen.dotSparkle(p(19, 38), 2.2, onDisc)
            default:
                break
            }
        }

        /// The stickers' action marks, in the disc's colour.
        private func marks(around center: CGPoint, from inner: CGFloat, to outer: CGFloat, angles: [Double]) {
            for angle in angles {
                let a = Angle.degrees(angle).radians
                let dx = CGFloat(cos(a))
                let dy = CGFloat(sin(a))
                let path = StickerShape.polyline([
                    p(center.x + dx * inner, center.y + dy * inner), p(center.x + dx * outer, center.y + dy * outer),
                ])
                pen.line(path, onDisc, width: 2.2)
            }
        }

        // MARK: Accessories

        /// What goes behind the cup.
        func behind(_ accessory: BrandAvatarAccessory) {
            switch accessory {
            case .headphones:
                let band = StickerShape.curve(p(-21, -21), p(-22.5, -51), p(22.5, -51), p(21, -21))
                pen.tube(on(band), ink.night, width: 3.6)
            case .catEars:
                for side in [-1.0, 1.0] as [CGFloat] {
                    var ear = Path()
                    ear.move(to: p(side * 19.5, -36.5))
                    ear.addQuadCurve(to: p(side * 13.6, -48.5), control: p(side * 18.5, -44))
                    ear.addQuadCurve(to: p(side * 6.5, -38.5), control: p(side * 9.5, -45))
                    ear.closeSubpath()
                    pen.shape(on(ear), cup.style.body)
                    var inner = Path()
                    inner.move(to: p(side * 16.8, -38))
                    inner.addQuadCurve(to: p(side * 13.9, -45.2), control: p(side * 16.4, -42.6))
                    inner.addQuadCurve(to: p(side * 9.8, -38.6), control: p(side * 11.6, -42.6))
                    inner.closeSubpath()
                    pen.fill(on(inner), ink.warm.opacity(0.7))
                }
            case .antennae:
                for side in [-1.0, 1.0] as [CGFloat] {
                    let stalk = StickerShape.quad(p(side * 5.5, -38), p(side * 5.4, -44), p(side * 8.6, -47.6))
                    pen.line(on(stalk), ink.ink, width: StickerPen.Weight.line)
                    pen.shape(StickerShape.circle(c(side * 8.9, -48), 2.2 * cup.scale), ink.warm)
                }
            case .stringHeart:
                stringHeart()
            default:
                break
            }
        }

        /// What goes over the cup.
        func front(_ accessory: BrandAvatarAccessory) {
            switch accessory {
            case .none, .ghostSheet, .stringHeart, .antennae:
                break
            case .catEars:
                if isFull { whiskers() }
            case .headphones:
                for side in [-1.0, 1.0] as [CGFloat] {
                    let cupShell = StickerShape.rounded(
                        CGRect(x: side * 20.6 - 4.4, y: -27.2, width: 8.8, height: 14.4), 4)
                    pen.shape(on(cupShell), ink.night)
                    let cushion = StickerShape.rounded(
                        CGRect(x: side * 20.6 - (side < 0 ? -1.6 : 4.0), y: -24.6, width: 2.4, height: 9.2), 1.2)
                    pen.fill(on(cushion), ink.warm)
                }
            case .beanie:
                var dome = Path()
                dome.move(to: p(-20, -37))
                dome.addCurve(to: p(0, -48.6), control1: p(-20, -45.4), control2: p(-11, -48.6))
                dome.addCurve(to: p(20, -37), control1: p(11, -48.6), control2: p(20, -45.4))
                dome.closeSubpath()
                pen.shape(on(dome), ink.warm)
                let cuff = StickerShape.rounded(CGRect(x: -22.4, y: -41.2, width: 44.8, height: 9.2), 3.4)
                pen.shape(on(cuff), ink.warm)
                if pen.isArt {
                    let ribs = pen.clipped(to: on(cuff))
                    for x in stride(from: -18.0, through: 18.0, by: 4.5) as StrideThrough<CGFloat> {
                        ribs.stroke(
                            on(StickerShape.polyline([p(x, -39.4), p(x, -33.8)])), ink.ink.opacity(0.32), width: 1.3)
                    }
                    pen.stroke(on(cuff))
                }
                pen.shape(StickerShape.circle(c(0, -49.4), 3.3 * cup.scale), ink.paper)
            case .glasses:
                glasses()
            case .flower:
                flower(at: p(13.5, -39.5), radius: 8.6)
            case .cap:
                // A cap worn sideways: a round crown and the brim out to the right.
                var crown = Path()
                crown.move(to: p(-20.6, -36.4))
                crown.addCurve(to: p(-2, -52.4), control1: p(-21.6, -47.6), control2: p(-12.6, -52.4))
                crown.addCurve(to: p(19.4, -37.4), control1: p(9.6, -52.4), control2: p(19.4, -46.6))
                crown.closeSubpath()
                var brim = Path()
                brim.move(to: p(12.6, -41.6))
                brim.addCurve(to: p(27.6, -38.8), control1: p(19.2, -43.8), control2: p(26.6, -42.8))
                brim.addCurve(to: p(12.4, -35.2), control1: p(28.4, -36), control2: p(19.4, -34.6))
                brim.closeSubpath()
                pen.shape(on(brim), ink.lilac600)
                pen.shape(on(crown), ink.night)
                if pen.isArt {
                    let panels = pen.clipped(to: on(crown))
                    for (from, control, to) in [
                        (p(-2, -52), p(-9.6, -46), p(-10.6, -37)), (p(-2, -52), p(4.4, -46), p(6.6, -37)),
                    ] as [(CGPoint, CGPoint, CGPoint)] {
                        panels.stroke(on(StickerShape.quad(from, control, to)), ink.lilac400.opacity(0.85), width: 1.3)
                    }
                    pen.stroke(on(crown))
                }
                let band = StickerShape.rounded(CGRect(x: -21.4, y: -40.4, width: 41.8, height: 5.2), 2.6)
                pen.shape(on(band), ink.lilac600)
                pen.shape(StickerShape.circle(c(-2, -52.6), 1.8 * cup.scale), ink.lilac300)
            case .scarf:
                scarf()
            case .partyHat:
                var hat = Path()
                hat.move(to: p(-10.6, -38.4))
                hat.addLine(to: p(4.8, -53.4))
                hat.addLine(to: p(10.2, -38.4))
                hat.closeSubpath()
                pen.shape(on(hat), ink.lilac100)
                if pen.isArt {
                    let stripes = pen.clipped(to: on(hat))
                    for y in [-42.6, -47.8] as [CGFloat] {
                        stripes.fill(
                            on(
                                StickerShape.polyline([
                                    p(-14, y + 1.4), p(14, y - 1.6), p(14, y + 1.4), p(-14, y + 4.4),
                                ])),
                            ink.warm)
                    }
                    pen.stroke(on(hat))
                }
                pen.shape(StickerShape.circle(c(5, -53.8), 2.6 * cup.scale), ink.warm)
            case .nightcap:
                // The stickers' 305 nightcap, its tip flopped over the right of the rim.
                var cap = Path()
                cap.move(to: p(-19.4, -40))
                cap.addCurve(to: p(0, -53), control1: p(-18.6, -49), control2: p(-9.4, -53))
                cap.addCurve(to: p(24.4, -30.6), control1: p(13.6, -53), control2: p(23.8, -44.2))
                cap.addCurve(to: p(17.2, -39.6), control1: p(20.6, -35), control2: p(19.4, -38.2))
                cap.closeSubpath()
                pen.shape(on(cap), ink.night)
                let band = StickerShape.rounded(CGRect(x: -20.8, y: -42.6, width: 41.6, height: 5.6), 2.8)
                pen.shape(on(band), ink.lilac100)
                pen.shape(StickerShape.circle(c(24, -29.4), 3.2 * cup.scale), ink.paper)
            case .crown:
                var crown = Path()
                crown.move(to: p(-11, -37.4))
                crown.addLine(to: p(-12.2, -48))
                crown.addLine(to: p(-5.6, -42.6))
                crown.addLine(to: p(0, -50))
                crown.addLine(to: p(5.6, -42.6))
                crown.addLine(to: p(12.2, -48))
                crown.addLine(to: p(11, -37.4))
                crown.closeSubpath()
                pen.shape(on(crown), ink.lilac100)
                pen.fill(on(StickerShape.circle(p(0, -40.4), 1.9)), ink.warm)
                for x in [-12.2, 0, 12.2] as [CGFloat] {
                    let y: CGFloat = x == 0 ? -50 : -48
                    pen.shape(StickerShape.circle(c(x, y), 1.5 * cup.scale), ink.warm)
                }
            case .bow:
                let bow = StickerShape.bow(p(-11.5, -40.2), size: 15.5)
                for tail in bow.tails { pen.line(on(tail), ink.warm, width: 2.6) }
                for loop in bow.loops { pen.shape(on(loop), ink.warm) }
                pen.shape(on(bow.knot), ink.warm)
            case .coffee:
                coffee()
            case .chefHat:
                chefHat()
            }
        }

        // MARK: Pieces

        /// Two rings and a bar over the eyes: the app icon's line mark.
        private func glasses() {
            let face = cup.faceCenter
            let eyeY = face.y + StickerFace.eyeY
            let x = StickerFace.eyeX
            let r: CGFloat = 5.2
            if pen.isArt {
                for side in [-1.0, 1.0] as [CGFloat] {
                    pen.fill(on(StickerShape.circle(p(side * x, eyeY), r)), ink.paper.opacity(0.28))
                }
            }
            for side in [-1.0, 1.0] as [CGFloat] {
                pen.stroke(on(StickerShape.circle(p(side * x, eyeY), r)), ink.ink, width: 2.2)
                pen.stroke(
                    on(StickerShape.polyline([p(side * (x + r), eyeY - 0.6), p(side * 17.6, eyeY - 2.2)])), ink.ink,
                    width: 1.8)
            }
            pen.stroke(
                on(StickerShape.quad(p(-x + r, eyeY - 0.4), p(0, eyeY - 2), p(x - r, eyeY - 0.4))), ink.ink, width: 2)
        }

        private func flower(at center: CGPoint, radius: CGFloat) {
            var petals = Path()
            for index in 0..<5 {
                let a = Angle.degrees(Double(index) * 72 - 90).radians
                let petalCenter = p(
                    center.x + radius * 0.52 * CGFloat(cos(a)), center.y + radius * 0.52 * CGFloat(sin(a)))
                let petal = StickerShape.ellipse(.zero, radius * 0.5, radius * 0.36)
                    .applying(
                        CGAffineTransform(translationX: petalCenter.x, y: petalCenter.y).rotated(by: a))
                petals = petals.isEmpty ? petal : petals.union(petal)
            }
            pen.shape(on(petals), ink.paper)
            pen.shape(on(StickerShape.circle(center, radius * 0.3)), ink.warm)
        }

        /// The string from under the cup, up its right side into a heart.
        private func stringHeart() {
            // Sticker units: the loop sits between the cup's right side and the disc's edge.
            let tip = p(81, 76)
            var string = Path()
            string.move(to: cup.stringStart)
            string.addCurve(to: tip, control1: p(62, 89), control2: p(76, 85))
            string.addCurve(to: p(73.4, 59.6), control1: p(77, 70.4), control2: p(73.4, 66))
            string.addCurve(to: p(81, 56.4), control1: p(73.4, 54.6), control2: p(79.4, 53.4))
            string.addCurve(to: p(88.6, 59.6), control1: p(82.6, 53.4), control2: p(88.6, 54.6))
            string.addCurve(to: tip, control1: p(88.6, 66), control2: p(85, 70.4))
            pen.string(string)
            pen.heart(p(81, 63.4), 7.6)
        }

        private func whiskers() {
            for side in [-1.0, 1.0] as [CGFloat] {
                for (dy, slope) in [(-1.2, -1.4), (1.4, 1.2)] as [(CGFloat, CGFloat)] {
                    let from = p(side * 14.6, -12.6 + dy)
                    let to = p(side * 21.4, -12.6 + dy + slope)
                    pen.line(on(StickerShape.polyline([from, to])), ink.ink, width: 1.4, onPaper: false)
                }
            }
        }

        private func scarf() {
            var band = Path()
            band.move(to: p(-15.6, -8.4))
            band.addQuadCurve(to: p(15.6, -8.4), control: p(0, -6.2))
            band.addLine(to: p(14.8, -2.2))
            band.addQuadCurve(to: p(-14.8, -2.2), control: p(0, 0.2))
            band.closeSubpath()
            var tail = Path()
            tail.move(to: p(5.2, -5.4))
            tail.addLine(to: p(11.6, -5.8))
            tail.addLine(to: p(12.8, 3.2))
            tail.addLine(to: p(6.6, 3.6))
            tail.closeSubpath()
            pen.shape(on(band), ink.warm)
            pen.shape(on(tail), ink.warm)
            if pen.isArt {
                let stripes = pen.clipped(to: on(band))
                for x in [-9.0, -3.0, 3.0, 9.0] as [CGFloat] {
                    stripes.stroke(
                        on(StickerShape.polyline([p(x - 1, -10), p(x + 1.4, 1)])), ink.paper.opacity(0.8), width: 1.6)
                }
                let tailStripes = pen.clipped(to: on(tail))
                tailStripes.stroke(
                    on(StickerShape.polyline([p(4, -1), p(14, -1.6)])), ink.paper.opacity(0.8), width: 1.6)
                pen.stroke(on(band))
                pen.stroke(on(tail))
            }
            if isFull {
                for x in [7.8, 9.8, 11.8] as [CGFloat] {
                    pen.line(
                        on(StickerShape.polyline([p(x, 3.6), p(x + 0.2, 5.6)])), ink.ink, width: 1.3, onPaper: false)
                }
            }
        }

        private func coffee() {
            let sleeve = StickerShape.polyline([c(-16.1, -12.5), c(16.1, -12.5), c(14.6, -3.5), c(-14.6, -3.5)])
            var closed = sleeve
            closed.closeSubpath()
            pen.shape(closed, ink.lilac600)
            pen.heart(c(0, -8), 5.6, ink.lilac100)
            guard isFull else { return }
            let rim = cup.rimTop
            for (dx, phase) in [(-7.0, 1.0), (7.0, -1.0)] as [(CGFloat, CGFloat)] {
                let base = p(rim.x + dx, rim.y - 3.5)
                let steam = StickerShape.smooth([
                    base, p(base.x + phase * 2.4, base.y - 4.4), p(base.x - phase * 1.4, base.y - 9),
                    p(base.x + phase * 1.6, base.y - 13.4),
                ])
                pen.line(steam, onDisc, width: 2.6, dotted: 4.2)
            }
        }

        private func chefHat() {
            let band = StickerShape.rounded(CGRect(x: -15.6, y: -44.2, width: 31.2, height: 7.4), 2.4)
            var puff = StickerShape.circle(p(-9.6, -46.2), 5.4)
            puff = puff.union(StickerShape.circle(p(0, -48.2), 5.6))
            puff = puff.union(StickerShape.circle(p(9.6, -46.2), 5.4))
            puff = puff.union(StickerShape.rounded(CGRect(x: -13.6, y: -47, width: 27.2, height: 6), 2))
            pen.shape(on(puff), ink.paper)
            pen.shape(on(band), ink.paper)
            if pen.isArt {
                for x in [-6.0, 0, 6.0] as [CGFloat] {
                    pen.stroke(
                        on(StickerShape.polyline([p(x, -43.6), p(x, -38.2)])), ink.lilac300, width: 1.3)
                }
            }
        }

        /// The cup under a sheet: a cup-shaped ghost with a wavy hem, its rim
        /// showing on top (the stickers' 202).
        func ghost() {
            var body = Path()
            body.move(to: p(-19.6, -36))
            body.addCurve(to: p(-21.6, -1.6), control1: p(-20.6, -24), control2: p(-22.6, -12))
            let hem: [CGFloat] = [-21.6, -10.8, 0, 10.8, 21.6]
            for index in 0..<(hem.count - 1) {
                let x0 = hem[index]
                let x1 = hem[index + 1]
                body.addQuadCurve(to: p(x1, -1.6), control: p((x0 + x1) / 2, index % 2 == 0 ? 4.2 : -6))
            }
            body.addCurve(to: p(19.6, -36), control1: p(22.6, -12), control2: p(20.6, -24))
            body.closeSubpath()
            pen.shape(on(body), ink.lilac100)
            pen.shape(cup.lipPath, ink.paper)
            cup.face.draw(pen, cup.transform.translatedBy(x: 0, y: -18))
        }
    }
}
