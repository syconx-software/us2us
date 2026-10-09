import SwiftUI
import UIKit

/// One colour token: a value for each theme (tokens.json › color).
///
/// The dark value is the design's default and is also used while the trait is
/// unspecified. Views never use literal colours — only `Color.u2.<token>`.
struct U2ThemedColor: Sendable, Hashable {
    let dark: UInt32
    let darkAlpha: Double
    let light: UInt32
    let lightAlpha: Double

    init(dark: UInt32, darkAlpha: Double = 1, light: UInt32, lightAlpha: Double = 1) {
        self.dark = dark
        self.darkAlpha = darkAlpha
        self.light = light
        self.lightAlpha = lightAlpha
    }

    /// A theme-independent colour (tokens.json › color.fixed).
    init(fixed hex: UInt32, alpha: Double = 1) {
        self.init(dark: hex, darkAlpha: alpha, light: hex, lightAlpha: alpha)
    }

    /// The same token at a different opacity (`X·N%` in the inventory).
    func opacity(_ alpha: Double) -> U2ThemedColor {
        U2ThemedColor(dark: dark, darkAlpha: darkAlpha * alpha, light: light, lightAlpha: lightAlpha * alpha)
    }

    /// sRGB components (0…1) for a theme.
    func components(for scheme: ColorScheme) -> (red: Double, green: Double, blue: Double, alpha: Double) {
        let (hex, alpha) = scheme == .light ? (light, lightAlpha) : (dark, darkAlpha)
        return (
            Double((hex >> 16) & 0xff) / 255,
            Double((hex >> 8) & 0xff) / 255,
            Double(hex & 0xff) / 255,
            alpha
        )
    }

    func uiColor(for scheme: ColorScheme) -> UIColor {
        let c = components(for: scheme)
        return UIColor(red: c.red, green: c.green, blue: c.blue, alpha: c.alpha)
    }

    /// A dynamic `UIColor` that follows the trait collection (and therefore the
    /// in-app theme, which is applied as `overrideUserInterfaceStyle`).
    var uiColor: UIColor {
        let dark = uiColor(for: .dark)
        let light = uiColor(for: .light)
        return UIColor { traits in traits.userInterfaceStyle == .light ? light : dark }
    }
}

extension Color {
    /// A dynamic colour for a token. SwiftUI resolves it against the
    /// environment's colour scheme, so `.environment(\.colorScheme, .dark)`
    /// (5.6, 6.2 are always dark) works as well.
    init(_ token: U2ThemedColor) {
        self.init(uiColor: token.uiColor)
    }
}

/// Namespace for the design's colours: `Color.u2.textSecondary`, `Color.u2.accent300`…
/// Base and semantic tokens are generated from tokens.json
/// (U2ColorTokens+Generated.swift); fixed colours and gradients are below.
struct U2Colors: Sendable {}

extension Color {
    /// The us2us design tokens.
    static var u2: U2Colors { U2Colors() }
}

// MARK: - Fixed (theme-independent) colours — tokens.json › color.fixed

enum U2FixedColorTokens {
    static let toggleKnob = U2ThemedColor(fixed: 0xf5f4ff)
    static let qrPaper = U2ThemedColor(fixed: 0xf3f5fe)
    static let qrPaperScan = U2ThemedColor(fixed: 0xf3f5fe, alpha: 0.88)
    static let qrInk = U2ThemedColor(fixed: 0x292b31)
    static let scrimBase = U2ThemedColor(fixed: 0x05060c)
    static let lockOverlay = U2ThemedColor(fixed: 0x06070e, alpha: 0.55)
    static let actionsOverlay = U2ThemedColor(fixed: 0x06070e, alpha: 0.35)
    static let sheetShadow = U2ThemedColor(fixed: 0x000000, alpha: 0.35)
    static let cameraBg = U2ThemedColor(fixed: 0x0c0d14)
    static let mediaViewerBg = U2ThemedColor(fixed: 0x0d0e19)
    static let onMedia = U2ThemedColor(fixed: 0xf5f4ff)
    static let onMediaGlassBase = U2ThemedColor(fixed: 0x0c0d16)
    static let scanCorner = U2ThemedColor(fixed: 0xd2cefd)
    static let scanGlow = U2ThemedColor(fixed: 0x9184d9, alpha: 0.7)
    static let stickerRing = U2ThemedColor(fixed: 0xf5f4ff)
    static let appIconTop = U2ThemedColor(fixed: 0x423a6a)
    static let appIconBottom = U2ThemedColor(fixed: 0x161826)
    static let appIconMark = U2ThemedColor(fixed: 0xb5abfc)
    static let black = U2ThemedColor(fixed: 0x000000)
}

extension U2Colors {
    /// Switch knob, both themes (#f5f4ff).
    var toggleKnob: Color { Color(U2FixedColorTokens.toggleKnob) }
    /// QR card paper (#f3f5fe) in both themes.
    var qrPaper: Color { Color(U2FixedColorTokens.qrPaper) }
    /// QR card paper on the scanner mock (#f3f5fe@88%).
    var qrPaperScan: Color { Color(U2FixedColorTokens.qrPaperScan) }
    /// QR modules (#292b31).
    var qrInk: Color { Color(U2FixedColorTokens.qrInk) }
    /// Mixed into `bg` for sheet backdrops (#05060c).
    var scrimBase: Color { Color(U2FixedColorTokens.scrimBase) }
    /// Over the dimmed lock screen behind the 3.3 sheet (#06070e@55%).
    var lockOverlay: Color { Color(U2FixedColorTokens.lockOverlay) }
    /// Over the blurred chat behind the 5.3 menu (#06070e@35%).
    var actionsOverlay: Color { Color(U2FixedColorTokens.actionsOverlay) }
    /// 3.3 floating sheet shadow (#000@35%).
    var sheetShadow: Color { Color(U2FixedColorTokens.sheetShadow) }
    /// 6.2 scanner background (#0c0d14).
    var cameraBg: Color { Color(U2FixedColorTokens.cameraBg) }
    /// 5.6 viewer background (#0d0e19).
    var mediaViewerBg: Color { Color(U2FixedColorTokens.mediaViewerBg) }
    /// Text and icons over photos and the camera (#f5f4ff).
    var onMedia: Color { Color(U2FixedColorTokens.onMedia) }
    /// Round buttons and pills over media: #0c0d16 at 40 / 45 / 55 %.
    func onMediaGlass(_ alpha: Double) -> Color { Color(U2FixedColorTokens.onMediaGlassBase.opacity(alpha)) }
    /// Round buttons and pills over the camera (#f5f4ff@12%).
    var onCameraGlass: Color { Color(U2FixedColorTokens.onMedia.opacity(0.12)) }
    /// QR viewfinder corners and scan line (#d2cefd).
    var scanCorner: Color { Color(U2FixedColorTokens.scanCorner) }
    /// Scan-line glow (#9184d9@70%).
    var scanGlow: Color { Color(U2FixedColorTokens.scanGlow) }
    /// 4 pt ring around stickers (#f5f4ff).
    var stickerRing: Color { Color(U2FixedColorTokens.stickerRing) }
    /// Pure black at an opacity — only for the design's drop shadows (`0 2px 6px #000@25%` …).
    func shadowBlack(_ alpha: Double) -> Color { Color(U2FixedColorTokens.black.opacity(alpha)) }
}

// MARK: - Gradients (tokens.md › Gradients & patterns)

extension U2Colors {
    /// `lineGradient`: accent400 → accent·30 % at 50 % → accent400 (the bar of the line mark).
    var lineGradient: LinearGradient {
        LinearGradient(
            stops: [
                .init(color: accent400, location: 0),
                .init(color: accentLineMid, location: 0.5),
                .init(color: accent400, location: 1),
            ],
            startPoint: .leading, endPoint: .trailing)
    }

    /// `linePacket`: transparent → accent200 → transparent.
    var linePacketGradient: LinearGradient {
        LinearGradient(colors: [accent200.opacity(0), accent200, accent200.opacity(0)], startPoint: .leading, endPoint: .trailing)
    }

    /// `composerFade`: transparent → bg at 30 % (and below).
    var composerFade: LinearGradient {
        LinearGradient(stops: [.init(color: bg.opacity(0), location: 0), .init(color: bg, location: 0.3)], startPoint: .top, endPoint: .bottom)
    }

    /// 5.6 top shade: #05060c@70 % → transparent.
    var mediaTopShade: LinearGradient {
        LinearGradient(colors: [Color(U2FixedColorTokens.scrimBase.opacity(0.7)), Color(U2FixedColorTokens.scrimBase.opacity(0))], startPoint: .top, endPoint: .bottom)
    }

    /// 5.6 bottom shade: transparent → #05060c@80 %.
    var mediaBottomShade: LinearGradient {
        LinearGradient(colors: [Color(U2FixedColorTokens.scrimBase.opacity(0)), Color(U2FixedColorTokens.scrimBase.opacity(0.8))], startPoint: .top, endPoint: .bottom)
    }
}
