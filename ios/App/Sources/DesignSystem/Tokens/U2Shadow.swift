import SwiftUI

/// One CSS `box-shadow` layer (tokens.md › Shadows & glows).
///
/// CSS draws an outer box-shadow from the box's outline grown by `spread`,
/// blurred with σ = blur / 2, and **only outside the box** — under a
/// transparent outline button the glow does not show through. SwiftUI's
/// `.shadow` follows the view's alpha instead, so the design's glows are
/// rebuilt here with exactly the CSS semantics.
struct U2BoxShadow: Sendable {
    var color: Color
    var x: CGFloat = 0
    var y: CGFloat = 0
    var blur: CGFloat
    var spread: CGFloat = 0
}

extension U2BoxShadow {
    /// `0 0 32px -8px accent·70%` — primary outline CTA.
    static var glowButton: U2BoxShadow { .init(color: Color.u2.accentGlow, blur: 32, spread: -8) }
    /// `0 0 26px -8px accent·70%` — 4.4 “Kabul et”.
    static var glowButtonAccept: U2BoxShadow { .init(color: Color.u2.accentGlow, blur: 26, spread: -8) }
    /// `0 0 20px -6px accent·70%` — composer mic button.
    static var glowMic: U2BoxShadow { .init(color: Color.u2.accentGlow, blur: 20, spread: -6) }
    /// `0 0 26px -10px accent` — focused text field (outer part of `glowField`).
    static var glowField: U2BoxShadow { .init(color: Color.u2.accent, blur: 26, spread: -10) }
    /// `0 0 12px accent·70%` — filled code dot.
    static var glowCodeDot: U2BoxShadow { .init(color: Color.u2.accentGlow, blur: 12) }
    /// `0 0 70px -12px accent·60%` — 3.1 Face ID disc.
    static var glowFaceID: U2BoxShadow { .init(color: Color.u2.accentGlowSoft, blur: 70, spread: -12) }
    /// `0 20px 50px -20px accent·60%` — 6.1 QR card.
    /// `0 0 32px -8px danger·45%` — the danger button's glow (C1's geometry in `dangerStroke`).
    static var glowDanger: U2BoxShadow { .init(color: Color.u2.dangerStroke, blur: 32, spread: -8) }
    static var glowQRCard: U2BoxShadow { .init(color: Color.u2.accentGlowSoft, y: 20, blur: 50, spread: -20) }
    /// `0 0 24px accent·55%` — 6.3 verified seal.
    static var glowSeal: U2BoxShadow { .init(color: Color.u2.accentGlowSeal, blur: 24) }
    /// `0 0 12px 2px accent·50%` — light packet on the line.
    static var glowPacket: U2BoxShadow { .init(color: Color.u2.accentGlowPacket, blur: 12, spread: 2) }
    /// `0 0 18px accent·80%` — live-location dot.
    static var glowLiveDot: U2BoxShadow { .init(color: Color.u2.accentGlowLive, blur: 18) }
    /// `0 0 18px 3px #9184d9@70%` — 6.2 scan line.
    static var glowScanLine: U2BoxShadow { .init(color: Color.u2.scanGlow, blur: 18, spread: 3) }
    /// `0 -12px 40px #000@35%` — 3.3 floating sheet.
    static var sheetFloating: U2BoxShadow { .init(color: Color.u2.shadowBlack(0.35), y: -12, blur: 40) }
    /// `0 16px 40px #000@40%` — 5.3 lifted bubble.
    static var bubbleLifted: U2BoxShadow { .init(color: Color.u2.shadowBlack(0.4), y: 16, blur: 40) }
    /// `0 10px 30px #000@30%` — 5.1 urgent banner.
    static var floatingBanner: U2BoxShadow { .init(color: Color.u2.shadowBlack(0.3), y: 10, blur: 30) }
    /// `0 14px 40px #000@45%` — 5.6 screenshot warning.
    static var floatingWarning: U2BoxShadow { .init(color: Color.u2.shadowBlack(0.45), y: 14, blur: 40) }
    /// `0 12px 26px #000@35%` — 5.2 sticker.
    static var sticker: U2BoxShadow { .init(color: Color.u2.shadowBlack(0.35), y: 12, blur: 26) }
    /// `0 20px 40px #000@45%` — 6.2 phone QR mock.
    static var qrScanCard: U2BoxShadow { .init(color: Color.u2.shadowBlack(0.45), y: 20, blur: 40) }
    /// `0 34px 70px #000@55%` — 3.4 app-switcher card.
    static var switcherCard: U2BoxShadow { .init(color: Color.u2.shadowBlack(0.55), y: 34, blur: 70) }
    /// Drop part of `shadowMd` — dark `0 6px 18px #000@55%`, light `0 6px 18px #292b31@10%`.
    static var mdDrop: U2BoxShadow { .init(color: Color(U2ShadowTokens.mdDrop), y: 6, blur: 18) }
}

/// Theme-dependent shadow colours (the light theme uses ink-tinted shadows).
enum U2ShadowTokens {
    /// `shadowMd` ring: dark #595d6c, light #b2b6ca.
    static let mdRing = U2ThemedColor(dark: 0x595d6c, light: 0xb2b6ca)
    /// `shadowMd` drop: dark #000@55%, light #292b31@10%.
    static let mdDrop = U2ThemedColor(dark: 0x000000, darkAlpha: 0.55, light: 0x292b31, lightAlpha: 0.10)
    /// `shadowSm` ring: dark #3f424d, light #cfd3e5.
    static let smRing = U2ThemedColor(dark: 0x3f424d, light: 0xcfd3e5)
}

private struct BoxShadowLayer<S: InsettableShape>: View {
    let shape: S
    let shadow: U2BoxShadow

    var body: some View {
        // How far the blurred glow can reach outside the box.
        let reach = shadow.blur * 1.5 + max(shadow.spread, 0) + abs(shadow.x) + abs(shadow.y) + 2
        shape
            .inset(by: -shadow.spread)
            .fill(shadow.color)
            .offset(x: shadow.x, y: shadow.y)
            .blur(radius: shadow.blur / 2)
            .mask {
                // Outer shadows are clipped to the outside of the box.
                ZStack {
                    Rectangle().padding(-reach)
                    shape.fill(.black).blendMode(.destinationOut)
                }
                .compositingGroup()
            }
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

extension View {
    /// Draws CSS box-shadows behind the view, outside `shape` only.
    func u2BoxShadow<S: InsettableShape>(_ shadows: U2BoxShadow..., in shape: S) -> some View {
        background {
            ZStack {
                ForEach(Array(shadows.enumerated()), id: \.offset) { _, shadow in
                    BoxShadowLayer(shape: shape, shadow: shadow)
                }
            }
        }
    }

    /// CSS box-shadows for a rounded rectangle (continuous corners).
    func u2BoxShadow(_ shadows: U2BoxShadow..., cornerRadius: CGFloat) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        return background {
            ZStack {
                ForEach(Array(shadows.enumerated()), id: \.offset) { _, shadow in
                    BoxShadowLayer(shape: shape, shadow: shadow)
                }
            }
        }
    }

    /// `inset 0 0 0 Npx C` — an inner stroke of `width` points.
    func u2InnerStroke<S: InsettableShape>(_ color: Color, width: CGFloat, in shape: S) -> some View {
        overlay(shape.strokeBorder(color, lineWidth: width).allowsHitTesting(false))
    }

    /// `inset 0 0 0 Npx C` on a rounded rectangle.
    func u2InnerStroke(_ color: Color, width: CGFloat, cornerRadius: CGFloat) -> some View {
        u2InnerStroke(color, width: width, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
    }

    /// `0 0 0 Npx C` — an outer ring (the online dot's cut-out ring in `bg`).
    func u2OuterRing<S: InsettableShape>(_ color: Color, width: CGFloat, in shape: S) -> some View {
        background(shape.inset(by: -width).fill(color).allowsHitTesting(false))
    }

    /// `shadowMd` — 1 pt ring + soft drop (5.3 reaction bar and action menu).
    func u2ShadowMd(cornerRadius: CGFloat) -> some View {
        // CSS paints the first listed shadow (the ring) on top of the drop.
        self
            .u2OuterRing(
                Color(U2ShadowTokens.mdRing), width: 1,
                in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .u2BoxShadow(.mdDrop, cornerRadius: cornerRadius)
    }

    /// `0 2px 6px #000@25%` — the switch knob's shadow (on an opaque circle).
    func u2KnobShadow() -> some View {
        shadow(color: Color.u2.shadowBlack(0.25), radius: 3, x: 0, y: 2)
    }
}
