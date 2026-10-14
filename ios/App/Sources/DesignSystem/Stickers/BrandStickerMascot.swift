import SwiftUI

// The cup mascot and the face every character of the set shares.

// MARK: - Face

/// A face in its own units (centre = 0, 0; eyes 13.6 apart): the cups, the
/// padlock, the ghost and the sun all wear the same features, so the set
/// reads as one family.
struct StickerFace {
    enum Eyes {
        /// Round dots with a highlight; `look` moves them.
        case dots(look: CGVector = .zero)
        /// Closed, smiling (^ ^).
        case happy
        /// Closed, asleep (u u).
        case sleepy
        /// Wide open rings with pupils.
        case wide(look: CGVector = .zero)
        case hearts
        /// Left open, right closed.
        case wink
        case dizzy
        /// Ghost holes.
        case hollow
        /// Squeezed shut (> <).
        case squeezed
        case sunglasses
        case none
    }

    enum Mouth: Equatable {
        case smile, grin, laugh, o, smallO, frown, sob, flat, wavy, tongue, smirk, none
    }

    enum Brows {
        case none, raised, sad, angry, quizzical
    }

    var eyes: Eyes = .dots()
    var mouth: Mouth = .smile
    var brows: Brows = .none
    var blush = true

    static let eyeY: CGFloat = -2.6
    static let eyeX: CGFloat = 6.8

    /// Draws the face (art pass only) through `transform` (face units → sticker units).
    func draw(_ pen: StickerPen, _ transform: CGAffineTransform) {
        guard pen.isArt else { return }
        let ink = BrandStickerInk.ink
        let fine = StickerPen.Weight.fine
        let line = StickerPen.Weight.line * 0.92
        let eyeY = Self.eyeY
        let eyeX = Self.eyeX
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x, y: y) }
        func on(_ path: Path) -> Path { path.applying(transform) }
        func closedArc(_ c: CGPoint, up: Bool) -> Path {
            up
                ? StickerShape.quad(p(c.x - 2.7, c.y + 1), p(c.x, c.y - 3.1), p(c.x + 2.7, c.y + 1))
                : StickerShape.quad(p(c.x - 2.7, c.y - 0.4), p(c.x, c.y + 2.6), p(c.x + 2.7, c.y - 0.4))
        }
        func dot(_ c: CGPoint) {
            pen.fill(on(StickerShape.ellipse(c, 2.2, 2.8)), ink)
            pen.fill(on(StickerShape.circle(p(c.x + 0.75, c.y - 1.05), 0.75)), BrandStickerInk.paper)
        }
        let sides: [CGFloat] = [-1, 1]

        if blush {
            for side in sides {
                pen.fill(on(StickerShape.ellipse(p(side * 11.2, 2.4), 2.9, 1.7)), BrandStickerInk.warm.opacity(0.5))
            }
        }

        switch eyes {
        case .dots(let look):
            for side in sides { dot(p(side * eyeX + look.dx, eyeY + look.dy)) }
        case .happy:
            for side in sides { pen.stroke(on(closedArc(p(side * eyeX, eyeY + 0.6), up: true)), ink, width: line) }
        case .sleepy:
            for side in sides { pen.stroke(on(closedArc(p(side * eyeX, eyeY), up: false)), ink, width: line) }
        case .wide(let look):
            for side in sides {
                let c = p(side * eyeX, eyeY - 0.4)
                pen.fill(on(StickerShape.circle(c, 3.6)), BrandStickerInk.paper)
                pen.stroke(on(StickerShape.circle(c, 3.6)), ink, width: fine)
                let pupil = p(c.x + look.dx, c.y + look.dy)
                pen.fill(on(StickerShape.circle(pupil, 1.85)), ink)
                pen.fill(on(StickerShape.circle(p(pupil.x + 0.6, pupil.y - 0.7), 0.6)), BrandStickerInk.paper)
            }
        case .hearts:
            for side in sides {
                let heart = on(StickerShape.heart(p(side * eyeX, eyeY - 0.2), width: 7))
                pen.fill(heart, BrandStickerInk.warm)
                pen.stroke(heart, ink, width: fine)
            }
        case .wink:
            dot(p(-eyeX, eyeY))
            pen.stroke(on(closedArc(p(eyeX, eyeY + 0.6), up: true)), ink, width: line)
        case .dizzy:
            for side in sides {
                let spiral = StickerShape.spiral(
                    p(side * eyeX, eyeY), radius: 3.3, turns: 1.75, start: .degrees(side * 90))
                pen.stroke(on(spiral), ink, width: fine)
            }
        case .hollow:
            for side in sides { pen.fill(on(StickerShape.ellipse(p(side * eyeX, eyeY), 2.7, 3.7)), ink) }
        case .squeezed:
            for side in sides {
                let c = p(side * eyeX, eyeY)
                let chevron = StickerShape.polyline([
                    p(c.x + side * 2, c.y - 2.4), p(c.x - side * 1.8, c.y), p(c.x + side * 2, c.y + 2.4),
                ])
                pen.stroke(on(chevron), ink, width: line)
            }
        case .sunglasses:
            var lenses = Path()
            for side in sides {
                lenses.addPath(
                    StickerShape.rounded(CGRect(x: side * eyeX - 4.9, y: eyeY - 3.2, width: 9.8, height: 6.6), 2.8))
            }
            pen.fill(on(lenses), ink)
            pen.stroke(on(StickerShape.polyline([p(-2.4, eyeY - 1.4), p(2.4, eyeY - 1.4)])), ink, width: fine)
            for side in sides {
                let g = p(side * eyeX - 1.8, eyeY - 0.4)
                pen.stroke(
                    on(StickerShape.polyline([p(g.x, g.y + 1.2), p(g.x + 2.2, g.y - 1.1)])),
                    BrandStickerInk.paper.opacity(0.85), width: 1.1)
            }
        case .none:
            break
        }

        switch brows {
        case .none:
            break
        case .raised:
            for side in sides {
                let c = p(side * eyeX, eyeY - 6.2)
                pen.stroke(
                    on(StickerShape.quad(p(c.x - 2.4, c.y + 0.7), p(c.x, c.y - 1.6), p(c.x + 2.4, c.y + 0.7))), ink,
                    width: fine)
            }
        case .sad:
            for side in sides {
                pen.stroke(
                    on(StickerShape.polyline([p(side * 9.4, eyeY - 4.2), p(side * 4.6, eyeY - 6.2)])), ink, width: fine)
            }
        case .angry:
            for side in sides {
                pen.stroke(
                    on(StickerShape.polyline([p(side * 9.4, eyeY - 6.4), p(side * 4.4, eyeY - 4.4)])), ink, width: fine)
            }
        case .quizzical:
            let left = p(-eyeX, eyeY - 6.6)
            pen.stroke(
                on(
                    StickerShape.quad(
                        p(left.x - 2.4, left.y + 0.8), p(left.x, left.y - 1.8), p(left.x + 2.4, left.y + 0.8))), ink,
                width: fine)
            pen.stroke(
                on(StickerShape.polyline([p(eyeX - 2.4, eyeY - 4.8), p(eyeX + 2.4, eyeY - 5.1)])), ink, width: fine)
        }

        let m = p(0, 4.2)
        let smile = StickerShape.quad(p(m.x - 3.4, m.y - 0.8), p(m.x, m.y + 2.8), p(m.x + 3.4, m.y - 0.8))
        switch mouth {
        case .smile:
            pen.stroke(on(smile), ink, width: line)
        case .grin, .laugh:
            let w: CGFloat = mouth == .laugh ? 5.4 : 4.0
            let d: CGFloat = mouth == .laugh ? 9.6 : 6.6
            var open = Path()
            open.move(to: p(m.x - w, m.y - 1.2))
            open.addLine(to: p(m.x + w, m.y - 1.2))
            open.addQuadCurve(to: p(m.x - w, m.y - 1.2), control: p(m.x, m.y - 1.2 + d))
            open.closeSubpath()
            pen.fill(on(open), ink)
            let tongue = StickerShape.ellipse(p(m.x, m.y - 1.2 + d * 0.5), w * 0.56, d * 0.2)
            pen.clipped(to: on(open)).fill(on(tongue), BrandStickerInk.warm)
            pen.stroke(on(open), ink, width: fine)
        case .o:
            pen.fill(on(StickerShape.ellipse(p(m.x, m.y + 0.6), 1.9, 2.4)), ink)
        case .smallO:
            pen.fill(on(StickerShape.ellipse(p(m.x, m.y + 0.2), 1.25, 1.45)), ink)
        case .frown:
            pen.stroke(
                on(StickerShape.quad(p(m.x - 3.0, m.y + 1.4), p(m.x, m.y - 1.6), p(m.x + 3.0, m.y + 1.4))), ink,
                width: line)
        case .sob:
            var open = Path()
            open.move(to: p(m.x - 4.2, m.y + 2.6))
            open.addQuadCurve(to: p(m.x + 4.2, m.y + 2.6), control: p(m.x, m.y - 4.4))
            open.addQuadCurve(to: p(m.x - 4.2, m.y + 2.6), control: p(m.x, m.y + 0.6))
            open.closeSubpath()
            pen.fill(on(open), ink)
            pen.stroke(on(open), ink, width: fine)
        case .flat:
            pen.stroke(on(StickerShape.polyline([p(m.x - 2.8, m.y + 0.4), p(m.x + 2.8, m.y + 0.4)])), ink, width: line)
        case .wavy:
            let wave = StickerShape.smooth([
                p(m.x - 4, m.y + 0.6), p(m.x - 2, m.y - 0.6), p(m.x, m.y + 0.6), p(m.x + 2, m.y - 0.6),
                p(m.x + 4, m.y + 0.6),
            ])
            pen.stroke(on(wave), ink, width: fine + 0.2)
        case .tongue:
            let tongue = on(StickerShape.ellipse(p(m.x + 1.3, m.y + 2.4), 1.9, 2.2))
            pen.fill(tongue, BrandStickerInk.warm)
            pen.stroke(tongue, ink, width: fine * 0.85)
            pen.stroke(on(smile), ink, width: line)
        case .smirk:
            pen.stroke(
                on(StickerShape.quad(p(m.x - 3.0, m.y + 0.6), p(m.x + 1.2, m.y + 2.6), p(m.x + 3.6, m.y - 1.0))), ink,
                width: line)
        case .none:
            break
        }
    }
}

// MARK: - Cup mascot

/// The colours of a cup: the mascot (`mid`) and its lighter twin (`light`).
struct CupStyle: Sendable {
    let body: Color
    let shade: Color
    let lip: Color

    /// The mascot: the app icon's line colour.
    static let mid = CupStyle(
        body: BrandStickerInk.lilac300, shade: BrandStickerInk.lilac400, lip: BrandStickerInk.lilac200)
    /// The other end of the line.
    static let light = CupStyle(
        body: BrandStickerInk.lilac100, shade: BrandStickerInk.lilac200, lip: BrandStickerInk.paper)
}

/// The cup mascot: a paper cup of the string telephone, standing on its base
/// with the string leaving from the base's centre. The same proportions in
/// every sticker (a 33-unit body narrowing from 36 to 26, a 41 × 7.5 rim),
/// scaled as a whole; line weights never scale.
struct Cup {
    enum Side {
        case left, right
        var sign: CGFloat { self == .left ? -1 : 1 }
    }

    /// A rubber-hose arm from the shoulder to a round hand.
    struct Arm {
        var side: Side
        /// The hand, in the cup's own units (base centre = origin, up = −y).
        var hand: CGPoint
        /// Sideways bow of the arm (units; positive bows away from the body).
        var bend: CGFloat = 0
        /// Drawn over the body (hands on the face, hugging).
        var inFront = false
        /// The hand's radius (bigger to cover the eyes).
        var handRadius: CGFloat = 2.9
        /// Finger lines on the hand (an open hand seen from the front).
        var fingers = false

        init(
            _ side: Side, _ x: CGFloat, _ y: CGFloat, bend: CGFloat = 0, inFront: Bool = false,
            handRadius: CGFloat = 2.9,
            fingers: Bool = false
        ) {
            self.side = side
            self.hand = CGPoint(x: x, y: y)
            self.bend = bend
            self.inFront = inFront
            self.handRadius = handRadius
            self.fingers = fingers
        }

        init(_ side: Side, _ hand: CGPoint, bend: CGFloat = 0, inFront: Bool = false) {
            self.init(side, hand.x, hand.y, bend: bend, inFront: inFront)
        }
    }

    // Proportions (the cup's own units).
    static let height: CGFloat = 33
    static let topWidth: CGFloat = 36
    static let baseWidth: CGFloat = 26
    static let lipWidth: CGFloat = 41
    static let lipHeight: CGFloat = 7.5
    static let corner: CGFloat = 4.5
    /// From the base to the top of the rim.
    static let totalHeight: CGFloat = height + 6

    /// The base centre in sticker units.
    var origin: CGPoint
    var scale: CGFloat = 1
    /// Leaning, around the base centre.
    var tilt: Angle = .zero
    var style: CupStyle = .mid
    var face = StickerFace()
    var arms: [Arm] = []
    /// Running legs: the feet, in the cup's units (below the base, +y).
    var feet: [CGPoint] = []
    /// Moves the face up (−) or down (+) on the body.
    var faceOffset: CGFloat = 0
    /// Draws the face upright even when the cup lies on its side.
    var uprightFace = false

    init(
        _ x: CGFloat, _ y: CGFloat, scale: CGFloat = 1, tilt: Double = 0, style: CupStyle = .mid,
        eyes: StickerFace.Eyes = .dots(), mouth: StickerFace.Mouth = .smile, brows: StickerFace.Brows = .none,
        blush: Bool = true, arms: [Arm] = []
    ) {
        origin = CGPoint(x: x, y: y)
        self.scale = scale
        self.tilt = .degrees(tilt)
        self.style = style
        face = StickerFace(eyes: eyes, mouth: mouth, brows: brows, blush: blush)
        self.arms = arms
    }

    var transform: CGAffineTransform {
        CGAffineTransform(translationX: origin.x, y: origin.y).rotated(by: tilt.radians).scaledBy(x: scale, y: scale)
    }

    /// A point of the cup in sticker units.
    func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x, y: y).applying(transform) }

    /// A sticker point in the cup's own units (to reach for something).
    func local(_ point: CGPoint) -> CGPoint { point.applying(transform.inverted()) }

    /// The top centre of the rim, in sticker units.
    var rimTop: CGPoint { point(0, -Self.totalHeight) }

    /// Where the string leaves the cup: a little inside the base, so the
    /// string (drawn first) comes out from under it.
    var stringStart: CGPoint { point(0, -3) }

    /// The face centre in the cup's units.
    var faceCenter: CGPoint { CGPoint(x: 0, y: -16 + faceOffset) }

    var bodyPath: Path {
        let h = Self.height
        let top = Self.topWidth / 2
        let base = Self.baseWidth / 2
        var path = Path()
        path.move(to: CGPoint(x: -top, y: -h))
        path.addLine(to: CGPoint(x: top, y: -h))
        path.addArc(tangent1End: CGPoint(x: base, y: 0), tangent2End: CGPoint(x: 0, y: 0), radius: Self.corner)
        path.addArc(tangent1End: CGPoint(x: -base, y: 0), tangent2End: CGPoint(x: -top, y: -h), radius: Self.corner)
        path.closeSubpath()
        return path.applying(transform)
    }

    var lipPath: Path {
        let rect = CGRect(x: -Self.lipWidth / 2, y: -Self.totalHeight, width: Self.lipWidth, height: Self.lipHeight)
        return Path(roundedRect: rect, cornerRadius: Self.lipHeight / 2, style: .continuous).applying(transform)
    }

    /// Everything: arms behind, legs, body, rim, face, arms in front.
    func draw(_ pen: StickerPen) {
        drawBehind(pen)
        drawBody(pen)
        drawFront(pen)
    }

    func drawBehind(_ pen: StickerPen) {
        for arm in arms where !arm.inFront { drawArm(arm, pen) }
        for foot in feet { drawLeg(to: foot, pen) }
    }

    func drawBody(_ pen: StickerPen) {
        pen.shape(bodyPath, style.body)
        if pen.isArt {
            let inside = pen.clipped(to: bodyPath)
            let h = Self.height
            let shade = StickerShape.polyline([
                CGPoint(x: Self.topWidth / 2 - 9.5, y: -h - 1), CGPoint(x: Self.topWidth / 2 + 1, y: -h - 1),
                CGPoint(x: Self.baseWidth / 2 + 1, y: 1), CGPoint(x: Self.baseWidth / 2 - 6.5, y: 1),
            ])
            inside.fill(shade.applying(transform), style.shade)
            // A paper glint down the left side.
            let glint = StickerShape.polyline([CGPoint(x: -12.9, y: -26.5), CGPoint(x: -11.6, y: -17.5)])
            inside.stroke(glint.applying(transform), BrandStickerInk.paper.opacity(0.75), width: 2.1 * scale)
            inside.fill(StickerShape.circle(point(-11.0, -13.2), 1.05 * scale), BrandStickerInk.paper.opacity(0.75))
            // The outline again, over the shading.
            pen.stroke(bodyPath)
        }
        pen.shape(lipPath, style.lip)
        let center = faceCenter
        let faceTransform: CGAffineTransform
        if uprightFace {
            let c = point(center.x, center.y)
            faceTransform = CGAffineTransform(translationX: c.x, y: c.y).scaledBy(x: scale, y: scale)
        } else {
            faceTransform = transform.translatedBy(x: center.x, y: center.y)
        }
        face.draw(pen, faceTransform)
    }

    func drawFront(_ pen: StickerPen) {
        for arm in arms where arm.inFront { drawArm(arm, pen) }
    }

    /// The hand's centre in sticker units.
    func hand(_ arm: Arm) -> CGPoint { point(arm.hand.x, arm.hand.y) }

    private func drawArm(_ arm: Arm, _ pen: StickerPen) {
        let shoulder = CGPoint(x: arm.side.sign * 14.4, y: -15)
        let mid = CGPoint(x: (shoulder.x + arm.hand.x) / 2, y: (shoulder.y + arm.hand.y) / 2)
        let dx = arm.hand.x - shoulder.x
        let dy = arm.hand.y - shoulder.y
        let length = max(0.001, hypot(dx, dy))
        var normal = CGVector(dx: -dy / length, dy: dx / length)
        if normal.dx * arm.side.sign < 0 { normal = CGVector(dx: -normal.dx, dy: -normal.dy) }
        let control = CGPoint(x: mid.x + normal.dx * arm.bend, y: mid.y + normal.dy * arm.bend)
        pen.line(StickerShape.quad(shoulder, control, arm.hand).applying(transform))
        pen.shape(StickerShape.circle(arm.hand, arm.handRadius).applying(transform), style.body)
        if arm.fingers {
            let r = arm.handRadius
            for dx in [-r * 0.32, r * 0.32] {
                let finger = StickerShape.polyline([
                    CGPoint(x: arm.hand.x + dx, y: arm.hand.y - r * 0.95),
                    CGPoint(x: arm.hand.x + dx, y: arm.hand.y - r * 0.25),
                ])
                pen.stroke(finger.applying(transform), BrandStickerInk.ink, width: StickerPen.Weight.fine)
            }
        }
    }

    private func drawLeg(to foot: CGPoint, _ pen: StickerPen) {
        let hip = CGPoint(x: foot.x < 0 ? -4.5 : 4.5, y: -2)
        let knee = CGPoint(x: (hip.x + foot.x) / 2 + (foot.x < 0 ? -1.5 : 1.5), y: (hip.y + foot.y) / 2)
        pen.line(StickerShape.quad(hip, knee, foot).applying(transform))
        let shoe = StickerShape.ellipse(CGPoint(x: foot.x + (foot.x < 0 ? -1.4 : 1.4), y: foot.y + 0.4), 3.4, 2.1)
        pen.shape(shoe.applying(transform), BrandStickerInk.ink)
    }
}
