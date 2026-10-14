import SwiftUI

/// The art of one built-in sticker: a composition drawn twice by
/// `BrandStickerCanvas` (paper, then art) in the 100 × 100 sticker box.
struct BrandStickerArtwork {
    /// How far the whole sticker leans, in degrees (a sticker is never quite straight).
    let tilt: Double
    /// Draws the sticker; `lines` are its words in the language shown (empty for wordless art).
    let compose: (StickerPen, [String]) -> Void

    /// The art for a catalog id; nil for an id this build cannot draw.
    static func artwork(for id: UInt32) -> BrandStickerArtwork? {
        switch id {
        case 1: BrandStickerArtwork(tilt: -3, compose: StickerArt.hello)
        case 2: BrandStickerArtwork(tilt: 0, compose: StickerArt.together)
        case 3: BrandStickerArtwork(tilt: -6, compose: StickerArt.laugh)
        case 4: BrandStickerArtwork(tilt: 0, compose: StickerArt.cry)
        case 5: BrandStickerArtwork(tilt: 2, compose: StickerArt.wow)
        case 6: BrandStickerArtwork(tilt: 3, compose: StickerArt.love)
        case 7: BrandStickerArtwork(tilt: 5, compose: StickerArt.wink)
        case 8: BrandStickerArtwork(tilt: -2, compose: StickerArt.think)
        case 9: BrandStickerArtwork(tilt: 0, compose: StickerArt.hug)
        case 10: BrandStickerArtwork(tilt: 4, compose: StickerArt.tangled)
        case 11: BrandStickerArtwork(tilt: 0, compose: StickerArt.listen)
        case 101: BrandStickerArtwork(tilt: -4, compose: StickerArt.wordsHello)
        case 102: BrandStickerArtwork(tilt: 3, compose: StickerArt.wordsLineOpen)
        case 103: BrandStickerArtwork(tilt: -3, compose: StickerArt.wordsHearYou)
        case 104: BrandStickerArtwork(tilt: 2, compose: StickerArt.wordsBetweenUs)
        case 105: BrandStickerArtwork(tilt: -3, compose: StickerArt.wordsTalkLater)
        case 106: BrandStickerArtwork(tilt: 3, compose: StickerArt.wordsMissYou)
        case 107: BrandStickerArtwork(tilt: -2, compose: StickerArt.wordsGoodMorning)
        case 108: BrandStickerArtwork(tilt: 3, compose: StickerArt.wordsGoodNight)
        case 109: BrandStickerArtwork(tilt: -6, compose: StickerArt.wordsOK)
        case 110: BrandStickerArtwork(tilt: -2, compose: StickerArt.wordsThanks)
        case 111: BrandStickerArtwork(tilt: 4, compose: StickerArt.wordsHaha)
        case 112: BrandStickerArtwork(tilt: -2, compose: StickerArt.wordsPhone)
        case 201: BrandStickerArtwork(tilt: -3, compose: StickerArt.lock)
        case 202: BrandStickerArtwork(tilt: 5, compose: StickerArt.ghost)
        case 203: BrandStickerArtwork(tilt: 0, compose: StickerArt.key)
        case 204: BrandStickerArtwork(tilt: -3, compose: StickerArt.notLooking)
        case 205: BrandStickerArtwork(tilt: -3, compose: StickerArt.noTrace)
        case 206: BrandStickerArtwork(tilt: 2, compose: StickerArt.shh)
        case 207: BrandStickerArtwork(tilt: -2, compose: StickerArt.endToEnd)
        case 208: BrandStickerArtwork(tilt: 4, compose: StickerArt.verified)
        case 301: BrandStickerArtwork(tilt: -3, compose: StickerArt.coffee)
        case 302: BrandStickerArtwork(tilt: -2, compose: StickerArt.onMyWay)
        case 303: BrandStickerArtwork(tilt: 2, compose: StickerArt.busy)
        case 304: BrandStickerArtwork(tilt: -3, compose: StickerArt.party)
        case 305: BrandStickerArtwork(tilt: 3, compose: StickerArt.sleepy)
        case 306: BrandStickerArtwork(tilt: -4, compose: StickerArt.chill)
        case 307: BrandStickerArtwork(tilt: 0, compose: StickerArt.cheers)
        default: nil
        }
    }
}

// MARK: - Renderer

/// Draws a built-in sticker with `Canvas`: the die-cut paper under its
/// shadow (and, in the light theme, a hairline on its edge), then the art.
/// Vector all the way down, so it is crisp at any size. No accessibility of
/// its own — `BrandStickerView` adds the label.
struct BrandStickerCanvas: View {
    let sticker: BrandSticker
    let language: BrandStickerLanguage
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let artwork = BrandStickerArtwork.artwork(for: sticker.id)
        let lines = sticker.lines?(language) ?? []
        let frame = BrandStickerFrame.for(colorScheme)
        Canvas { context, size in
            guard let artwork else { return }
            Self.render(artwork, lines: lines, frame: frame, in: context, size: size)
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityHidden(true)
    }

    /// The 100-unit box centred in `size`, leaning by the artwork's tilt.
    static func render(
        _ artwork: BrandStickerArtwork, lines: [String], frame: BrandStickerFrame, in context: GraphicsContext,
        size: CGSize
    ) {
        let unit = min(size.width, size.height) / 100
        guard unit > 0 else { return }
        var box = CGAffineTransform(translationX: (size.width - unit * 100) / 2, y: (size.height - unit * 100) / 2)
        box = box.scaledBy(x: unit, y: unit)
        box = box.translatedBy(x: 50, y: 50).rotated(by: Angle.degrees(artwork.tilt).radians).translatedBy(
            x: -50, y: -50)

        // The paper, its hairline and its shadow, in one layer. The shadow
        // filter is set before the transform, in points.
        context.drawLayer { layer in
            layer.addFilter(
                .shadow(color: frame.shadow, radius: frame.shadowRadius * unit, x: 0, y: frame.shadowY * unit))
            var paper = layer
            paper.concatenate(box)
            // The whole hairline first, then the whole paper over it, so no
            // hairline shows where two shapes' papers overlap.
            if let hairline = frame.hairline {
                let spread = StickerPen.Weight.cut + StickerPen.Weight.hairline
                artwork.compose(StickerPen(context: paper, pass: .paper(spread: spread, color: hairline)), lines)
            }
            artwork.compose(
                StickerPen(context: paper, pass: .paper(spread: StickerPen.Weight.cut, color: BrandStickerInk.paper)),
                lines)
        }
        var art = context
        art.concatenate(box)
        artwork.compose(StickerPen(context: art, pass: .art), lines)
    }
}

// MARK: - Pen helpers

extension StickerPen {
    /// The string of the string telephone.
    func string(_ path: Path) { line(path, BrandStickerInk.string, width: Weight.string) }

    /// A ring of the line mark (the app icon's “o”).
    func ring(_ center: CGPoint, _ radius: CGFloat, fill: Color = BrandStickerInk.lilac300, width: CGFloat = 4.2) {
        tube(StickerShape.circle(center, radius), fill, width: width)
    }

    func heart(_ center: CGPoint, _ width: CGFloat, _ fill: Color = BrandStickerInk.warm) {
        shape(StickerShape.heart(center, width: width), fill)
    }

    func sparkle(_ center: CGPoint, _ radius: CGFloat, _ fill: Color = BrandStickerInk.lilac100) {
        shape(StickerShape.sparkle(center, radius), fill)
    }

    /// A dot-matrix sparkle: a centre dot and four smaller ones around it.
    func dotSparkle(_ center: CGPoint, _ radius: CGFloat, _ color: Color = BrandStickerInk.string) {
        shape(StickerShape.circle(center, radius * 0.36), color, outline: false)
        for angle in stride(from: 0.0, to: 360.0, by: 90.0) {
            let a = Angle.degrees(angle).radians
            let p = CGPoint(x: center.x + radius * CGFloat(cos(a)), y: center.y + radius * CGFloat(sin(a)))
            shape(StickerShape.circle(p, radius * 0.22), color, outline: false)
        }
    }

    /// Short action lines radiating around `center` (degrees clockwise from 3 o'clock).
    func marks(around center: CGPoint, from inner: CGFloat, to outer: CGFloat, angles: [Double], width: CGFloat = 2.2) {
        for angle in angles {
            let a = Angle.degrees(angle).radians
            let dx = CGFloat(cos(a))
            let dy = CGFloat(sin(a))
            let path = StickerShape.polyline([
                CGPoint(x: center.x + dx * inner, y: center.y + dy * inner),
                CGPoint(x: center.x + dx * outer, y: center.y + dy * outer),
            ])
            line(path, BrandStickerInk.ink, width: width)
        }
    }

    /// A card for words.
    func card(_ rect: CGRect, radius: CGFloat = 14, _ fill: Color = BrandStickerInk.lilac100) {
        shape(StickerShape.rounded(rect, radius), fill)
    }

    /// Paper under something that has none of its own (letters floating free).
    func paperPatch(_ path: Path) { shape(path, BrandStickerInk.paper, outline: false) }

    /// The pen with its drawing rotated by `angle` around `center`.
    func rotated(_ angle: Angle, around center: CGPoint) -> StickerPen {
        var copy = self
        copy.context.translateBy(x: center.x, y: center.y)
        copy.context.rotate(by: angle)
        copy.context.translateBy(x: -center.x, y: -center.y)
        return copy
    }
}

// MARK: - Compositions

/// One function per sticker. Order inside each: the string first (it leaves
/// from under the cups), then the characters, then what floats in front.
enum StickerArt {
    private static let ink = BrandStickerInk.self

    private static func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x, y: y) }
    private static func rect(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat) -> CGRect {
        CGRect(x: x, y: y, width: w, height: h)
    }

    // MARK: Cups

    /// 1 — waving hello, the string trailing off to a ring.
    static func hello(_ pen: StickerPen, _ lines: [String]) {
        let cup = Cup(
            44, 84, scale: 1.48, eyes: .dots(), mouth: .grin,
            arms: [Cup.Arm(.right, 25, -38, bend: -4), Cup.Arm(.left, -22, -5, bend: 3)])
        pen.string(StickerShape.smooth([cup.stringStart, p(52, 90), p(68, 92), p(80, 86), p(85, 79)]))
        pen.ring(p(88, 75.5), 3.6)
        cup.draw(pen)
        let hand = cup.hand(cup.arms[0])
        pen.marks(around: hand, from: 6.5, to: 10.5, angles: [-125, -80, -35])
    }

    /// 2 — two cups, their string tied into a heart between them.
    static func together(_ pen: StickerPen, _ lines: [String]) {
        let a = Cup(23, 88, scale: 0.9, tilt: 6, style: .light, eyes: .happy, mouth: .smile)
        let b = Cup(77, 88, scale: 0.9, tilt: -6, eyes: .happy, mouth: .smile)
        var string = Path()
        string.move(to: a.stringStart)
        string.addCurve(to: p(50, 57), control1: p(36, 90), control2: p(46, 66))
        string.addCurve(to: p(34.5, 31), control1: p(43, 50), control2: p(34.5, 42))
        string.addCurve(to: p(50, 28.5), control1: p(34.5, 19), control2: p(48, 18))
        string.addCurve(to: p(65.5, 31), control1: p(52, 18), control2: p(65.5, 19))
        string.addCurve(to: p(50, 57), control1: p(65.5, 42), control2: p(57, 50))
        string.addCurve(to: b.stringStart, control1: p(54, 66), control2: p(64, 90))
        pen.string(string)
        a.draw(pen)
        b.draw(pen)
        pen.heart(p(50, 39), 9)
        pen.dotSparkle(p(16, 26), 4.6)
        pen.dotSparkle(p(86, 20), 3.8)
    }

    /// 3 — laughing so hard the tears fly.
    static func laugh(_ pen: StickerPen, _ lines: [String]) {
        let cup = Cup(
            50, 84, scale: 1.46, eyes: .squeezed, mouth: .laugh,
            arms: [Cup.Arm(.left, -22, -2, bend: 4), Cup.Arm(.right, 22, -2, bend: 4)])
        pen.string(StickerShape.smooth([cup.stringStart, p(58, 91), p(66, 86), p(74, 92), p(82, 86), p(90, 90)]))
        cup.draw(pen)
        let left = cup.point(-13.5, -21)
        let right = cup.point(13.5, -21)
        pen.shape(StickerShape.drop(p(left.x - 9, left.y - 4), size: 8, angle: .degrees(70)), ink.lilac100)
        pen.shape(StickerShape.drop(p(right.x + 9, right.y - 4), size: 8, angle: .degrees(-70)), ink.lilac100)
        pen.marks(around: cup.point(0, -27), from: 31, to: 36, angles: [-150, -165, -15, -30])
    }

    /// 4 — crying its eyes out into a puddle.
    static func cry(_ pen: StickerPen, _ lines: [String]) {
        let puddle = StickerShape.ellipse(p(50, 88.5), 36, 5.5)
        pen.shape(puddle, ink.lilac100)
        let cup = Cup(50, 86, scale: 1.44, eyes: .sleepy, mouth: .sob, brows: .sad)
        pen.string(StickerShape.smooth([cup.stringStart, p(34, 92), p(18, 92), p(9, 86)]))
        cup.draw(pen)
        // Two streams down the cheeks, over the base, into the puddle.
        for side in [-1.0, 1.0] as [CGFloat] {
            var stream = Path()
            stream.move(to: cup.point(side * 6.8, -15.4))
            stream.addCurve(
                to: cup.point(side * 10.5, 1.5), control1: cup.point(side * 7.4, -8),
                control2: cup.point(side * 9.5, -4))
            pen.tube(stream, ink.lilac100, width: 4.4)
        }
        pen.shape(StickerShape.drop(p(17, 33), size: 7.5, angle: .degrees(-25)), ink.lilac100)
        pen.shape(StickerShape.drop(p(84, 29), size: 6.5, angle: .degrees(25)), ink.lilac100)
        pen.shape(StickerShape.drop(p(10, 50), size: 5, angle: .degrees(-35)), ink.lilac100)
    }

    /// 5 — astonished, hands up.
    static func wow(_ pen: StickerPen, _ lines: [String]) {
        let cup = Cup(
            50, 86, scale: 1.42, eyes: .wide(), mouth: .o, brows: .raised,
            arms: [Cup.Arm(.left, -25, -31, bend: -3), Cup.Arm(.right, 25, -31, bend: -3)])
        pen.string(
            StickerShape.smooth([
                cup.stringStart, p(60, 92), p(70, 88), p(68, 82), p(63, 86), p(70, 93), p(82, 91), p(88, 84),
            ]))
        cup.draw(pen)
        pen.marks(around: cup.rimTop, from: 7, to: 13, angles: [-135, -90, -45])
    }

    /// 6 — heart eyes, hearts rising.
    static func love(_ pen: StickerPen, _ lines: [String]) {
        let cup = Cup(47, 87, scale: 1.46, eyes: .hearts, mouth: .grin)
        pen.string(StickerShape.smooth([cup.stringStart, p(60, 92), p(74, 92), p(82, 87)]))
        pen.heart(p(86.5, 83), 8.5)
        cup.draw(pen)
        pen.heart(p(80, 22), 13)
        pen.heart(p(90.5, 39), 7.5)
        pen.heart(p(70, 9.5), 6.5)
    }

    /// 7 — a wink and a sparkle.
    static func wink(_ pen: StickerPen, _ lines: [String]) {
        let cup = Cup(
            48, 87, scale: 1.46, eyes: .wink, mouth: .tongue,
            arms: [Cup.Arm(.right, 25, -30, bend: -5), Cup.Arm(.left, -21, -5, bend: 3)])
        pen.string(StickerShape.smooth([cup.stringStart, p(36, 92), p(22, 91), p(13, 84), p(15, 77), p(22, 79)]))
        cup.draw(pen)
        pen.sparkle(p(86, 22), 8.5)
        pen.dotSparkle(p(73, 12), 3.6)
    }

    /// 8 — deep in thought, the string curled into a question mark.
    static func think(_ pen: StickerPen, _ lines: [String]) {
        let cup = Cup(
            40, 88, scale: 1.38, tilt: -2, eyes: .dots(look: CGVector(dx: 1.3, dy: -1.5)), mouth: .flat,
            brows: .quizzical,
            arms: [Cup.Arm(.right, 6.4, -8.4, bend: 6, inFront: true), Cup.Arm(.left, -21, -6, bend: 6)])
        pen.string(StickerShape.smooth([cup.stringStart, p(28, 92), p(14, 90), p(9, 82)]))
        cup.draw(pen)
        var question = Path()
        question.move(to: p(75.5, 16.5))
        question.addCurve(to: p(84, 7.5), control1: p(75.5, 10.5), control2: p(79.5, 7.5))
        question.addCurve(to: p(92.5, 15.5), control1: p(89, 7.5), control2: p(92.5, 10.5))
        question.addCurve(to: p(84, 24.5), control1: p(92.5, 20.5), control2: p(84, 20))
        question.addLine(to: p(84, 28))
        pen.line(question, ink.string, width: 3.2)
        pen.shape(StickerShape.circle(p(84, 35.5), 2.4), ink.string, outline: false)
        pen.shape(StickerShape.circle(p(71, 31), 1.6), ink.lilac100)
        pen.shape(StickerShape.circle(p(76, 38.5), 1.1), ink.lilac100)
    }

    /// 9 — two cups hugging, their strings tied in a bow.
    static func hug(_ pen: StickerPen, _ lines: [String]) {
        var a = Cup(31, 86, scale: 0.98, tilt: 6, style: .light, eyes: .happy, mouth: .smile)
        var b = Cup(69, 86, scale: 0.98, tilt: -6, eyes: .happy, mouth: .smile)
        // Each reaches round the other: the hand peeks out past the far side.
        a.arms = [Cup.Arm(.right, a.local(p(86, 70)), bend: -5)]
        b.arms = [Cup.Arm(.left, b.local(p(14, 70)), bend: -5)]
        let knot = p(50, 91)
        pen.string(StickerShape.quad(a.stringStart, p(40, 92), knot))
        pen.string(StickerShape.quad(b.stringStart, p(60, 92), knot))
        b.drawBehind(pen)
        a.drawBehind(pen)
        a.drawBody(pen)
        b.drawBody(pen)
        let bow = StickerShape.bow(knot, size: 17)
        for tail in bow.tails { pen.string(tail) }
        for loop in bow.loops { pen.shape(loop, ink.string) }
        pen.shape(bow.knot, ink.string)
        pen.heart(p(50, 25), 12)
        pen.dotSparkle(p(27, 20), 3.8)
        pen.dotSparkle(p(74, 15), 3.2)
    }

    /// 10 — dizzy, wound up in its own string.
    static func tangled(_ pen: StickerPen, _ lines: [String]) {
        let cup = Cup(50, 87, scale: 1.4, tilt: 4, eyes: .dizzy, mouth: .wavy)
        // Behind: the loops round the back.
        pen.string(StickerShape.smooth([p(27, 72), p(20, 66), p(22, 58), p(29, 57)]))
        pen.string(StickerShape.smooth([p(73, 80), p(82, 76), p(83, 68), p(75, 64)]))
        pen.string(StickerShape.smooth([cup.stringStart, p(64, 92), p(80, 92), p(91, 85), p(88, 78), p(82, 82)]))
        cup.draw(pen)
        // In front: the wraps across the body.
        pen.string(StickerShape.smooth([p(26, 70), p(50, 76), p(73, 79)]))
        pen.string(StickerShape.smooth([p(29, 57), p(46, 66), p(75, 64)]))
        pen.string(StickerShape.smooth([p(28, 82), p(48, 79), p(72, 86)]))
        let orbit = StickerShape.ellipse(p(51, 22), 23, 6)
        pen.stroke(orbit, ink.ink, width: 1.6, dotted: 3.6)
        pen.sparkle(p(29, 22), 4.6)
        pen.sparkle(p(66, 16.5), 5.4)
        pen.sparkle(p(74, 27), 3.6)
    }

    /// 11 — all ears: leaning in towards the sound.
    static func listen(_ pen: StickerPen, _ lines: [String]) {
        let cup = Cup(
            60, 89, scale: 1.34, tilt: -22, eyes: .wide(look: CGVector(dx: -1.2, dy: -1.2)), mouth: .smallO,
            brows: .raised,
            arms: [Cup.Arm(.left, -25, -34, bend: 3)])
        pen.string(StickerShape.smooth([cup.stringStart, p(72, 92), p(84, 88), p(88, 80)]))
        pen.ring(p(89.5, 75), 3.4)
        cup.draw(pen)
        // Sound arriving: “(((” closing in on the rim.
        let rim = cup.rimTop
        for radius in [12.0, 19.5, 27.0] {
            pen.line(StickerShape.arc(rim, radius, from: .degrees(198), to: .degrees(252)), ink.ink, width: 2.4)
        }
    }

    // MARK: Words

    /// 101 — “Merhaba!”: a bubble from a small waving cup.
    static func wordsHello(_ pen: StickerPen, _ lines: [String]) {
        let cup = Cup(
            19, 94, scale: 0.6, eyes: .dots(), mouth: .grin, blush: true,
            arms: [Cup.Arm(.left, -24, -32, bend: 4)])
        let bubble = StickerShape.bubble(rect(9, 9, 84, 52), radius: 18, tailX: 36, tailWidth: 12, tip: p(29, 71))
        cup.draw(pen)
        pen.shape(bubble, ink.lilac100)
        pen.words(lines, in: rect(15, 15, 72, 40), maxSize: 30)
    }

    /// 102 — “Hat açık”: the card over the line mark.
    static func wordsLineOpen(_ pen: StickerPen, _ lines: [String]) {
        var bar = Path()
        bar.move(to: p(29, 79))
        bar.addLine(to: p(71, 79))
        pen.tube(bar, ink.lilac300, width: 4.2)
        pen.ring(p(22, 79), 6.2)
        pen.ring(p(78, 79), 6.2)
        pen.shape(StickerShape.rounded(rect(39, 77.2, 9, 3.6), 1.8), ink.paper, outline: false)
        pen.card(rect(8, 13, 84, 50), radius: 16, ink.lilac300)
        pen.words(lines, in: rect(14, 18, 72, 40), maxSize: 26)
    }

    /// 103 — “Seni duyuyorum”: a cup below listening up to the words.
    static func wordsHearYou(_ pen: StickerPen, _ lines: [String]) {
        let cup = Cup(
            24, 94, scale: 0.64, eyes: .dots(look: CGVector(dx: 0.8, dy: -1.4)), mouth: .smile,
            arms: [Cup.Arm(.right, 22, -30, bend: -3)])
        pen.string(StickerShape.smooth([cup.stringStart, p(46, 93), p(68, 92), p(80, 87)]))
        pen.ring(p(84, 84), 3.6)
        cup.draw(pen)
        pen.card(rect(6, 6, 88, 50))
        pen.words(lines, in: rect(12, 11, 76, 40), maxSize: 24)
        // The words reaching the cup: “)))” from the card down to its rim.
        let rim = cup.rimTop
        for radius in [5.0, 10.0] {
            pen.line(
                StickerShape.arc(p(rim.x + 9, rim.y - 2), radius, from: .degrees(-60), to: .degrees(-10)), ink.ink,
                width: 2.2)
        }
    }

    /// 104 — “Aramızda kalsın”: a padlock on the line under the words.
    static func wordsBetweenUs(_ pen: StickerPen, _ lines: [String]) {
        pen.string(StickerShape.quad(p(20, 80), p(50, 88), p(80, 80)))
        pen.ring(p(15, 79), 4.6)
        pen.ring(p(85, 79), 4.6)
        padlock(pen, center: p(50, 87), width: 15, face: nil)
        pen.card(rect(6, 9, 88, 50))
        pen.words(lines, in: rect(12, 14, 76, 40), maxSize: 24)
    }

    /// 105 — “Sonra konuşuruz”: a bow tied in the string, to remember.
    static func wordsTalkLater(_ pen: StickerPen, _ lines: [String]) {
        let knot = p(50, 80)
        pen.string(StickerShape.quad(p(19, 80), p(34, 84), knot))
        pen.string(StickerShape.quad(p(81, 80), p(66, 84), knot))
        pen.ring(p(14, 79), 4.6)
        pen.ring(p(86, 79), 4.6)
        let bow = StickerShape.bow(knot, size: 19)
        for tail in bow.tails { pen.string(tail) }
        for loop in bow.loops { pen.shape(loop, ink.string) }
        pen.shape(bow.knot, ink.string)
        pen.card(rect(6, 9, 88, 50), ink.lilac200)
        pen.words(lines, in: rect(12, 14, 76, 40), maxSize: 24)
    }

    /// 106 — “Özledim”: the string drawn into a heart above the words.
    static func wordsMissYou(_ pen: StickerPen, _ lines: [String]) {
        var string = Path()
        string.move(to: p(50, 52))
        string.addLine(to: p(50, 41))
        string.addCurve(to: p(34, 20.5), control1: p(43, 35), control2: p(34, 29))
        string.addCurve(to: p(50, 16.5), control1: p(34, 9.5), control2: p(48, 8.5))
        string.addCurve(to: p(66, 20.5), control1: p(52, 8.5), control2: p(66, 9.5))
        string.addCurve(to: p(50, 41), control1: p(66, 29), control2: p(57, 35))
        pen.string(string)
        pen.heart(p(50, 24), 10.5)
        pen.card(rect(6, 47, 88, 40))
        pen.words(lines, in: rect(12, 51, 76, 32), maxSize: 26)
        pen.dotSparkle(p(18, 26), 4)
        pen.dotSparkle(p(83, 34), 3.4)
    }

    /// 107 — “Günaydın”: a sun rising behind the card.
    static func wordsGoodMorning(_ pen: StickerPen, _ lines: [String]) {
        let sun = p(50, 44)
        for angle in stride(from: -165.0, through: -15.0, by: 25.0) {
            let a = Angle.degrees(angle).radians
            let from = p(sun.x + 25 * CGFloat(cos(a)), sun.y + 25 * CGFloat(sin(a)))
            let to = p(sun.x + 35 * CGFloat(cos(a)), sun.y + 35 * CGFloat(sin(a)))
            pen.line(StickerShape.polyline([from, to]), ink.warm, width: 3.4, dotted: 5)
        }
        pen.shape(StickerShape.circle(sun, 19), ink.warm)
        StickerFace(eyes: .happy, mouth: .none, blush: false).draw(
            pen, CGAffineTransform(translationX: sun.x, y: sun.y - 6.5))
        pen.card(rect(6, 43, 88, 44))
        pen.words(lines, in: rect(12, 47, 76, 36), maxSize: 26)
    }

    /// 108 — “İyi geceler”: a night card under the moon.
    static func wordsGoodNight(_ pen: StickerPen, _ lines: [String]) {
        pen.card(rect(6, 28, 88, 60), ink.night)
        let moon = StickerShape.circle(p(79, 23), 14).subtracting(StickerShape.circle(p(86.5, 16.5), 12))
        pen.shape(moon, ink.lilac100)
        pen.words(lines, in: rect(12, 34, 76, 48), color: ink.paper, maxSize: 26)
        pen.sparkle(p(17, 18), 5.5)
        pen.sparkle(p(40, 11), 3.4)
        pen.dotSparkle(p(57, 19), 2.6, ink.lilac200)
    }

    /// 109 — “Tamam!”: the 5.2 sticker — a card leaning −6° over the mini line mark.
    static func wordsOK(_ pen: StickerPen, _ lines: [String]) {
        pen.card(rect(8, 21, 84, 58), radius: 20)
        pen.words(lines, in: rect(14, 26, 72, 32), maxSize: 30)
        let mark = ink.lilac600
        pen.stroke(StickerShape.circle(p(39.5, 67.5), 3.2), mark, width: 2.2)
        pen.stroke(StickerShape.circle(p(60.5, 67.5), 3.2), mark, width: 2.2)
        pen.stroke(StickerShape.polyline([p(42.7, 67.5), p(57.3, 67.5)]), mark, width: 2.2)
    }

    /// 110 — “Teşekkürler”: a ribbon banner and a heart.
    static func wordsThanks(_ pen: StickerPen, _ lines: [String]) {
        for side in [-1.0, 1.0] as [CGFloat] {
            let x0 = 50 + side * 33
            let x1 = 50 + side * 46
            let tail = StickerShape.polyline([p(x0, 56), p(x1, 56), p(x1, 79), p((x0 + x1) / 2, 72.5), p(x0, 79)])
            var closed = tail
            closed.closeSubpath()
            pen.shape(closed, ink.lilac400)
        }
        pen.card(rect(4, 33, 92, 31), radius: 6, ink.lilac300)
        pen.words(lines, in: rect(8, 36, 84, 25), maxSize: 22, tracking: -0.04)
        pen.heart(p(50, 18), 15)
        pen.sparkle(p(26, 18), 4.6)
        pen.sparkle(p(75, 14), 3.6)
    }

    /// 111 — “Haha”: bouncing letters on a burst.
    static func wordsHaha(_ pen: StickerPen, _ lines: [String]) {
        pen.shape(StickerShape.scallop(p(50, 50), radius: 42, bumps: 9, depth: 6), ink.lilac300)
        let word = lines.first ?? ""
        let letters = Array(word)
        guard !letters.isEmpty else { return }
        // Each letter in its own cell, 0.74 em apart, so the leaning
        // letters never touch.
        let size = min(25, 72 / (CGFloat(letters.count) * 0.74))
        let step = size * 0.74
        let start = 50 - step * CGFloat(letters.count - 1) / 2
        for (index, letter) in letters.enumerated() {
            let up = index % 2 == 0
            let baseline = p(start + step * CGFloat(index), 50 + size * 0.35 + (up ? -3.5 : 3.5))
            pen.glyphs(
                String(letter), in: pen.context, baseline: baseline, size: size, color: ink.ink, tracking: 0,
                rotation: .degrees(up ? -9 : 8))
        }
        pen.shape(StickerShape.drop(p(16, 22), size: 7, angle: .degrees(60)), ink.lilac100)
        pen.shape(StickerShape.drop(p(86, 76), size: 6, angle: .degrees(-120)), ink.lilac100)
    }

    /// 112 — “Alo?”: a ringing cup and its bubble.
    static func wordsPhone(_ pen: StickerPen, _ lines: [String]) {
        let cup = Cup(
            25, 91, scale: 0.72, tilt: -12, eyes: .dots(look: CGVector(dx: 1, dy: -1)), mouth: .grin,
            arms: [Cup.Arm(.left, -22, -20, bend: 3)])
        pen.string(StickerShape.smooth([cup.stringStart, p(46, 93), p(70, 92), p(84, 87)]))
        pen.ring(p(88, 84), 3.6)
        cup.draw(pen)
        pen.marks(around: cup.point(0, -22), from: 22, to: 27, angles: [-160, -175, 190])
        pen.marks(around: cup.point(0, -22), from: 22, to: 27, angles: [-20, -5, 10])
        let bubble = StickerShape.bubble(rect(26, 7, 68, 44), radius: 15, tailX: 40, tailWidth: 11, tip: p(31, 58))
        pen.shape(bubble, ink.lilac100)
        pen.words(lines, in: rect(32, 12, 56, 34), maxSize: 26)
    }

    // MARK: Privacy

    /// A padlock (optionally with a face) hanging from its shackle.
    private static func padlock(_ pen: StickerPen, center: CGPoint, width: CGFloat, face: StickerFace?) {
        let height = width * 0.84
        let body = rect(center.x - width / 2, center.y - height / 2 + width * 0.12, width, height)
        let parts = StickerShape.padlock(body, shackleHeight: width * 0.62)
        pen.tube(parts.shackle, ink.lilac200, width: max(3.2, width * 0.13))
        pen.shape(parts.body, ink.lilac300)
        if let face {
            let scale = width / 34
            face.draw(
                pen,
                CGAffineTransform(translationX: body.midX, y: body.midY - height * 0.1).scaledBy(x: scale, y: scale))
            pen.fill(StickerShape.keyhole(p(body.midX, body.maxY - height * 0.2), size: height * 0.2), ink.ink)
        } else {
            pen.fill(StickerShape.keyhole(p(body.midX, body.midY + 0.5), size: height * 0.44), ink.ink)
        }
    }

    /// 201 — a smiling padlock hanging on the line.
    static func lock(_ pen: StickerPen, _ lines: [String]) {
        padlock(pen, center: p(50, 63), width: 50, face: StickerFace(eyes: .happy, mouth: .smile))
        pen.string(StickerShape.quad(p(16, 25), p(50, 31), p(84, 25)))
        pen.ring(p(11, 24), 5)
        pen.ring(p(89, 24), 5)
        pen.dotSparkle(p(14, 76), 4)
        pen.dotSparkle(p(87, 82), 3.4)
    }

    /// 202 — a ghost shaped like a cup, fading into dots.
    static func ghost(_ pen: StickerPen, _ lines: [String]) {
        var body = Path()
        body.move(to: p(26, 24))
        body.addCurve(to: p(19, 70), control1: p(23, 40), control2: p(18, 58))
        let hem: [CGFloat] = [19, 34.5, 50, 65.5, 81]
        for index in 0..<(hem.count - 1) {
            let x0 = hem[index]
            let x1 = hem[index + 1]
            body.addQuadCurve(to: p(x1, 70), control: p((x0 + x1) / 2, index % 2 == 0 ? 78 : 63))
        }
        body.addCurve(to: p(74, 24), control1: p(82, 58), control2: p(77, 40))
        body.closeSubpath()
        pen.shape(body, ink.lilac100)
        pen.shape(StickerShape.rounded(rect(21, 17, 58, 10), 5), ink.paper)
        StickerFace(eyes: .hollow, mouth: .o, blush: true).draw(
            pen, CGAffineTransform(translationX: 50, y: 45).scaledBy(x: 1.3, y: 1.3))
        let rows: [(y: CGFloat, xs: [CGFloat], r: CGFloat)] = [
            (79, [27, 39, 50.5, 62, 73.5], 2.3), (86.5, [33, 45, 57, 68], 1.7), (93, [39.5, 51.5, 63], 1.15),
        ]
        for row in rows {
            for x in row.xs { pen.shape(StickerShape.circle(p(x, row.y), row.r), ink.lilac300, outline: false) }
        }
        pen.line(StickerShape.polyline([p(9, 40), p(13, 40)]), ink.ink, width: 2.2)
        pen.line(StickerShape.polyline([p(7, 50), p(12, 50)]), ink.ink, width: 2.2)
    }

    /// 203 — the key: its bow is the line's ring, a heart tag tied on.
    static func key(_ pen: StickerPen, _ lines: [String]) {
        let tagAnchor = p(27, 55)
        pen.string(StickerShape.smooth([tagAnchor, p(23, 64), p(22, 72)]))
        pen.heart(p(22, 78), 14)
        StickerFace(eyes: .happy, mouth: .smile, blush: false).draw(
            pen, CGAffineTransform(translationX: 22, y: 76.5).scaledBy(x: 0.55, y: 0.55))
        let key = pen.rotated(.degrees(-28), around: p(50, 42))
        key.shape(StickerShape.rounded(rect(70, 42, 6, 12), 1.6), ink.lilac300)
        key.shape(StickerShape.rounded(rect(79.5, 42, 6, 8.5), 1.6), ink.lilac300)
        var shaft = Path()
        shaft.move(to: p(41, 42))
        shaft.addLine(to: p(88, 42))
        key.tube(shaft, ink.lilac300, width: 7.5)
        key.ring(p(30, 42), 11.5, width: 7.5)
        pen.sparkle(p(86, 70), 6.5)
        pen.dotSparkle(p(70, 84), 3.4)
    }

    /// 204 — covering its eyes.
    static func notLooking(_ pen: StickerPen, _ lines: [String]) {
        let cup = Cup(
            50, 86, scale: 1.46, eyes: .happy, mouth: .grin,
            arms: [
                Cup.Arm(.left, -6.6, -18.4, bend: 7, inFront: true, handRadius: 4.3, fingers: true),
                Cup.Arm(.right, 6.6, -18.4, bend: 7, inFront: true, handRadius: 4.3, fingers: true),
            ])
        pen.string(StickerShape.smooth([cup.stringStart, p(38, 91), p(22, 91), p(12, 85)]))
        cup.draw(pen)
        pen.marks(around: cup.rimTop, from: 7, to: 12, angles: [-150, -120, -60, -30], width: 2)
    }

    /// 205 — “İz yok”: a card that dissolves into dots.
    static func noTrace(_ pen: StickerPen, _ lines: [String]) {
        let columns: [(x: CGFloat, r: CGFloat, rows: [CGFloat])] = [
            (72.5, 2.5, [27, 34, 41, 48, 55, 62, 69]), (79.5, 2.0, [30.5, 37.5, 44.5, 51.5, 58.5, 65.5]),
            (86.0, 1.5, [27, 41, 48, 62]), (92.0, 1.0, [37.5, 51.5, 58.5]),
        ]
        for column in columns {
            for y in column.rows {
                pen.shape(StickerShape.circle(p(column.x, y), column.r), ink.lilac300, outline: false)
            }
        }
        pen.card(rect(6, 22, 62, 52))
        pen.words(lines, in: rect(11, 27, 52, 42), maxSize: 24)
    }

    /// 206 — “Şşş”: finger to the lips.
    static func shh(_ pen: StickerPen, _ lines: [String]) {
        let cup = Cup(
            33, 90, scale: 1.24, tilt: 3, eyes: .dots(look: CGVector(dx: 1.6, dy: 0)), mouth: .smallO, brows: .raised,
            arms: [Cup.Arm(.right, 1.2, -6.5, bend: 7, inFront: true, handRadius: 3.4)])
        pen.string(StickerShape.smooth([cup.stringStart, p(48, 93), p(64, 92), p(76, 87)]))
        cup.draw(pen)
        // The finger, up over the lips.
        var finger = Path()
        finger.move(to: cup.point(1.2, -7))
        finger.addLine(to: cup.point(1.2, -14.6))
        pen.tube(finger, CupStyle.mid.body, width: 3.6)
        pen.shape(StickerShape.circle(cup.point(1.2, -6.5), 3.4 * 1.24), CupStyle.mid.body)
        let bubble = StickerShape.bubble(rect(52, 9, 42, 31), radius: 13, tailX: 62, tailWidth: 9, tip: p(58, 49))
        pen.shape(bubble, ink.lilac100)
        pen.words(lines, in: rect(56, 13, 34, 23), maxSize: 22)
    }

    /// 207 — end to end: two cups lying back to back, the line locked between them.
    static func endToEnd(_ pen: StickerPen, _ lines: [String]) {
        var a = Cup(36, 56, scale: 0.72, tilt: -90, style: .light, eyes: .happy, mouth: .smile)
        var b = Cup(64, 56, scale: 0.72, tilt: 90, eyes: .happy, mouth: .smile)
        a.uprightFace = true
        b.uprightFace = true
        let tunnel = StickerShape.arc(p(50, 56), 26, from: .degrees(198), to: .degrees(342))
        pen.line(tunnel, ink.string, width: 2.6, dotted: 5)
        pen.string(StickerShape.polyline([a.stringStart, b.stringStart]))
        a.draw(pen)
        b.draw(pen)
        padlock(pen, center: p(50, 66), width: 14, face: nil)
        pen.dotSparkle(p(50, 20), 4)
    }

    /// 208 — the verified seal.
    static func verified(_ pen: StickerPen, _ lines: [String]) {
        for side in [-1.0, 1.0] as [CGFloat] {
            let x0 = 50 + side * 6
            let x1 = 50 + side * 19
            var tail = StickerShape.polyline([
                p(x0, 62), p(x1, 62), p(x1 + side * 3, 92), p((x0 + x1) / 2 + side * 2, 85), p(x0 + side * 3, 91),
            ])
            tail.closeSubpath()
            pen.shape(tail, ink.string)
        }
        pen.shape(StickerShape.scallop(p(50, 44), radius: 35, bumps: 12, depth: 5), ink.lilac300)
        pen.shape(StickerShape.circle(p(50, 44), 24), ink.lilac100)
        pen.stroke(StickerShape.circle(p(50, 44), 19.5), ink.string, width: 1.8, dotted: 3.6)
        pen.stroke(StickerShape.polyline([p(39.5, 44.5), p(47, 52), p(61.5, 36.5)]), ink.ink, width: 5)
    }

    // MARK: Mood

    /// 301 — a coffee break: sleeve on, steam rising into a heart.
    static func coffee(_ pen: StickerPen, _ lines: [String]) {
        var cup = Cup(50, 89, scale: 1.46, eyes: .happy, mouth: .smile)
        cup.faceOffset = -4
        cup.drawBody(pen)
        let sleeve = StickerShape.polyline([
            cup.point(-16.1, -12.5), cup.point(16.1, -12.5), cup.point(14.6, -3.5), cup.point(-14.6, -3.5),
        ])
        var closed = sleeve
        closed.closeSubpath()
        pen.shape(closed, ink.lilac600)
        pen.heart(cup.point(0, -8), 6, ink.lilac100)
        let rim = cup.rimTop
        for (dx, phase) in [(-9.0, 1.0), (9.0, -1.0)] as [(CGFloat, CGFloat)] {
            let base = p(rim.x + dx, rim.y - 4)
            let steam = StickerShape.smooth([
                base, p(base.x + phase * 3, base.y - 5), p(base.x - phase * 1.5, base.y - 11),
                p(base.x + phase * 2, base.y - 17),
            ])
            pen.line(steam, ink.string, width: 2.8, dotted: 4.6)
        }
        pen.heart(p(rim.x, rim.y - 16), 8)
    }

    /// 302 — “Yoldayım!”: running, the string flying behind.
    static func onMyWay(_ pen: StickerPen, _ lines: [String]) {
        pen.card(rect(7, 74, 86, 20), radius: 10)
        var cup = Cup(
            54, 64, scale: 1.12, tilt: 12, eyes: .dots(look: CGVector(dx: 1.2, dy: 0)), mouth: .grin,
            arms: [Cup.Arm(.right, 23, -24, bend: -4), Cup.Arm(.left, -24, -9, bend: 4)])
        cup.feet = [CGPoint(x: 11, y: 7), CGPoint(x: -12, y: 4)]
        pen.string(StickerShape.smooth([cup.stringStart, p(38, 70), p(26, 62), p(16, 64), p(8, 58)]))
        for (y, x0, length) in [(34.0, 8.0, 13.0), (48.0, 5.0, 10.0)] as [(CGFloat, CGFloat, CGFloat)] {
            pen.line(StickerShape.polyline([p(x0, y), p(x0 + length, y)]), ink.ink, width: 2.4)
        }
        cup.draw(pen)
        pen.words(lines, in: rect(13, 76.5, 74, 15), maxSize: 16)
    }

    /// 303 — “Meşgul”: frazzled, holding up a sign.
    static func busy(_ pen: StickerPen, _ lines: [String]) {
        let cup = Cup(
            30, 90, scale: 1.2, tilt: -3, eyes: .squeezed, mouth: .wavy, brows: .sad,
            arms: [Cup.Arm(.right, 25, -27, bend: -3), Cup.Arm(.left, -21, -5, bend: 3)])
        let hand = cup.hand(cup.arms[0])
        pen.line(StickerShape.polyline([p(hand.x, hand.y + 2), p(hand.x + 3, 34)]), ink.ink, width: 3)
        pen.string(StickerShape.smooth([cup.stringStart, p(44, 93), p(60, 92), p(70, 86)]))
        cup.draw(pen)
        pen.card(rect(38, 6, 56, 32), radius: 8)
        pen.words(lines, in: rect(42, 10, 48, 24), maxSize: 22)
        pen.shape(StickerShape.drop(cup.point(-22, -34), size: 7.5, angle: .degrees(-20)), ink.lilac100)
    }

    /// 304 — a party: hat on, arms up, the string a streamer.
    static func party(_ pen: StickerPen, _ lines: [String]) {
        let cup = Cup(
            50, 87, scale: 1.4, eyes: .happy, mouth: .laugh,
            arms: [Cup.Arm(.left, -25, -37, bend: -3), Cup.Arm(.right, 25, -37, bend: -3)])
        pen.string(
            StickerShape.smooth([
                cup.stringStart, p(64, 93), p(74, 86), p(68, 80), p(64, 86), p(74, 93), p(86, 90), p(90, 82), p(84, 78),
                p(81, 84),
            ]))
        cup.draw(pen)
        var hat = Path()
        hat.move(to: cup.point(-9, -39.5))
        hat.addLine(to: cup.point(3, -58))
        hat.addLine(to: cup.point(10, -39.5))
        hat.closeSubpath()
        pen.shape(hat, ink.lilac100)
        if pen.isArt {
            let stripes = pen.clipped(to: hat)
            for y in [-44.0, -50.5] as [CGFloat] {
                stripes.fill(
                    StickerShape.polyline([
                        cup.point(-12, y + 1.6), cup.point(12, y - 1.6), cup.point(12, y + 1.6),
                        cup.point(-12, y + 4.8),
                    ]), ink.warm)
            }
            pen.stroke(hat)
        }
        pen.shape(StickerShape.circle(cup.point(3, -58.5), 3.2), ink.warm)
        let confetti: [(CGPoint, Color, Double)] = [
            (p(12, 30), ink.warm, 30), (p(20, 14), ink.string, -20), (p(86, 30), ink.lilac400, 50),
            (p(78, 12), ink.warm, -40), (p(91, 50), ink.string, 10), (p(9, 50), ink.lilac400, -60),
        ]
        for (center, color, angle) in confetti {
            let piece = StickerShape.rounded(rect(-3, -1.4, 6, 2.8), 1.2)
                .applying(
                    CGAffineTransform(translationX: center.x, y: center.y).rotated(by: Angle.degrees(angle).radians))
            pen.shape(piece, color, outline: false)
        }
        pen.dotSparkle(p(30, 9), 3.2, ink.warm)
    }

    /// 305 — sleepy, nightcap on, “z z Z”.
    static func sleepy(_ pen: StickerPen, _ lines: [String]) {
        let cup = Cup(44, 88, scale: 1.42, tilt: -4, eyes: .sleepy, mouth: .smallO)
        pen.string(StickerShape.smooth([cup.stringStart, p(30, 92), p(16, 90), p(10, 83)]))
        cup.draw(pen)
        var cap = Path()
        cap.move(to: cup.point(-19, -38))
        cap.addCurve(to: cup.point(6, -58), control1: cup.point(-17, -50), control2: cup.point(-6, -58))
        cap.addCurve(to: cup.point(27, -44), control1: cup.point(17, -58), control2: cup.point(25, -52))
        cap.addCurve(to: cup.point(19, -38), control1: cup.point(23, -42), control2: cup.point(20, -40))
        cap.closeSubpath()
        pen.shape(cap, ink.night)
        pen.shape(
            StickerShape.rounded(CGRect(x: -20.5, y: -41.5, width: 41, height: 6.5), 3.25).applying(cup.transform),
            ink.lilac100)
        pen.shape(StickerShape.circle(cup.point(28, -42), 3.8), ink.paper)
        let zs: [(CGPoint, CGFloat)] = [(p(76, 40), 10), (p(84, 27), 13), (p(91, 12), 16)]
        for (center, size) in zs {
            pen.paperPatch(StickerShape.circle(center, size * 0.42))
        }
        for (center, size) in zs {
            pen.glyphs(
                "z", in: pen.context, baseline: p(center.x, center.y + size * 0.25), size: size, color: ink.ink,
                tracking: 0, rotation: .degrees(-10))
        }
    }

    /// 306 — cool, sunglasses on.
    static func chill(_ pen: StickerPen, _ lines: [String]) {
        let cup = Cup(
            49, 87, scale: 1.46, tilt: -5, eyes: .sunglasses, mouth: .smirk, blush: false,
            arms: [Cup.Arm(.right, 21, -6, bend: 7), Cup.Arm(.left, -25, -33, bend: -3)])
        pen.string(StickerShape.smooth([cup.stringStart, p(62, 92), p(76, 91), p(86, 85)]))
        pen.ring(p(89.5, 81.5), 3.4)
        cup.draw(pen)
        pen.sparkle(p(84, 22), 8)
        pen.sparkle(p(92, 40), 4)
    }

    /// 307 — cheers: two cups clinking.
    static func cheers(_ pen: StickerPen, _ lines: [String]) {
        let a = Cup(
            25, 89, scale: 0.96, tilt: 11, style: .light, eyes: .happy, mouth: .grin,
            arms: [Cup.Arm(.left, -24, -32, bend: -3)])
        let b = Cup(
            75, 89, scale: 0.96, tilt: -11, eyes: .happy, mouth: .grin,
            arms: [Cup.Arm(.right, 24, -32, bend: -3)])
        let knot = p(50, 92.5)
        pen.string(StickerShape.quad(a.stringStart, p(38, 94), knot))
        pen.string(StickerShape.quad(b.stringStart, p(62, 94), knot))
        pen.shape(StickerShape.circle(knot, 2.4), ink.string)
        a.draw(pen)
        b.draw(pen)
        pen.marks(around: p(50, 47), from: 7, to: 13, angles: [-140, -90, -40], width: 2.4)
        pen.dotSparkle(p(50, 22), 4)
    }
}
