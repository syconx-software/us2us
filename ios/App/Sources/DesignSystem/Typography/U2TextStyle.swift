import SwiftUI
import UIKit

/// A type style of the design (tokens.json › typography.styles). The 91
/// instances are generated into U2TextStyle+Generated.swift.
///
/// Sizes and line heights are CSS pixels = points on the 402 × 874 frame.
/// `lineHeight` is the CSS line height: the block height of one line. SwiftUI
/// draws a line `naturalLineHeight` high, so `.u2Font(_:)` adds the difference
/// as line spacing and splits it above the first / below the last line — the
/// text then occupies exactly what it does in the design (tokens.md ›
/// Line-height in SwiftUI).
struct U2TextStyle: Sendable, Hashable, Identifiable {
    let name: String
    let family: U2FontFamily
    /// CSS weight (100…900).
    let weight: Int
    let size: CGFloat
    let lineHeight: CGFloat
    /// Letter spacing in points (`.tracking`).
    let tracking: CGFloat
    /// Upper-cased with the environment locale (Turkish İ/ı, inventory 9.3).
    let uppercase: Bool
    /// Tabular figures (ticking counters, times).
    let monospacedDigits: Bool
    let italic: Bool
    /// Whether the style follows Dynamic Type. Display (Doto) moments and
    /// glyphs inside fixed-size shapes stay at the design size.
    let scalesWithDynamicType: Bool

    var id: String { name }

    /// The same style at another weight (e.g. the emphasised word of a sentence).
    func weight(_ weight: Int) -> U2TextStyle {
        U2TextStyle(
            name: "\(name)@\(weight)", family: family, weight: weight, size: size, lineHeight: lineHeight,
            tracking: tracking, uppercase: uppercase, monospacedDigits: monospacedDigits, italic: italic,
            scalesWithDynamicType: scalesWithDynamicType)
    }

    /// The same style scaled by `factor` (size, line height and tracking) —
    /// e.g. the 3.4 cover, drawn in the design at 0.70 scale.
    func scaled(_ factor: CGFloat) -> U2TextStyle {
        U2TextStyle(
            name: "\(name)@x\(factor)", family: family, weight: weight, size: size * factor,
            lineHeight: lineHeight * factor, tracking: tracking * factor, uppercase: uppercase,
            monospacedDigits: monospacedDigits, italic: italic, scalesWithDynamicType: false)
    }

    /// The same style with tabular figures.
    var tabular: U2TextStyle {
        U2TextStyle(
            name: "\(name)@tnum", family: family, weight: weight, size: size, lineHeight: lineHeight,
            tracking: tracking, uppercase: uppercase, monospacedDigits: true, italic: italic,
            scalesWithDynamicType: scalesWithDynamicType)
    }

    /// Scale factor for a Dynamic Type size: exactly 1 at the default
    /// `.large` (the design's size), the body text style's growth otherwise.
    func scale(for dynamicTypeSize: DynamicTypeSize) -> CGFloat {
        guard scalesWithDynamicType, dynamicTypeSize != .large else { return 1 }
        return Self.bodyScale(dynamicTypeSize)
    }

    /// `UIFontMetrics(.body)` growth relative to `.large` (its absolute
    /// values are rounded, so compare a large reference value).
    private static func bodyScale(_ size: DynamicTypeSize) -> CGFloat {
        let metrics = UIFontMetrics(forTextStyle: .body)
        let reference: CGFloat = 1_000
        let base = metrics.scaledValue(
            for: reference, compatibleWith: UITraitCollection(preferredContentSizeCategory: .large))
        let scaled = metrics.scaledValue(
            for: reference, compatibleWith: UITraitCollection(preferredContentSizeCategory: UIContentSizeCategory(u2: size)))
        return base > 0 ? scaled / base : 1
    }

    func uiFont(scale: CGFloat = 1) -> UIFont {
        U2Fonts.font(family, weight: weight, size: size * scale, italic: italic, monospacedDigits: monospacedDigits)
    }

    /// The height SwiftUI gives one line of this style (hhea metrics).
    func naturalLineHeight(scale: CGFloat = 1) -> CGFloat {
        uiFont(scale: scale).lineHeight
    }
}

extension UIContentSizeCategory {
    init(u2 size: DynamicTypeSize) {
        switch size {
        case .xSmall: self = .extraSmall
        case .small: self = .small
        case .medium: self = .medium
        case .large: self = .large
        case .xLarge: self = .extraLarge
        case .xxLarge: self = .extraExtraLarge
        case .xxxLarge: self = .extraExtraExtraLarge
        case .accessibility1: self = .accessibilityMedium
        case .accessibility2: self = .accessibilityLarge
        case .accessibility3: self = .accessibilityExtraLarge
        case .accessibility4: self = .accessibilityExtraExtraLarge
        case .accessibility5: self = .accessibilityExtraExtraExtraLarge
        @unknown default: self = .large
        }
    }
}

// MARK: - Applying a style

private struct U2FontModifier: ViewModifier {
    let style: U2TextStyle
    /// When false, no line box is applied (the view keeps the font's own height).
    let matchLineBox: Bool
    /// Place the text by its measured first baseline (runs in another font
    /// change where SwiftUI puts it).
    var measuresBaseline = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    func body(content: Content) -> some View {
        let scale = style.scale(for: dynamicTypeSize)
        let font = style.uiFont(scale: scale)
        let lineHeight = style.lineHeight * scale
        let natural = font.lineHeight
        let styled = content
            .font(Font(font as CTFont))
            .modifier(TrackingModifier(tracking: style.tracking * scale))
            .lineSpacing(max(0, lineHeight - natural))
            .textCase(style.uppercase ? .uppercase : nil)
        if matchLineBox {
            let cssAscent = CSSLineMetrics(font: font, lineHeight: lineHeight).ascent
            CSSLineBox(
                lineHeight: lineHeight, naturalLineHeight: natural,
                firstBaselineShift: cssAscent - font.ascender,
                measuredBaselineAscent: measuresBaseline ? cssAscent : nil
            ) { styled }
        } else {
            styled
        }
    }
}

/// `.tracking` only when the style has letter spacing (`.tracking(0)` still
/// changes how SwiftUI measures the run).
private struct TrackingModifier: ViewModifier {
    let tracking: CGFloat
    func body(content: Content) -> some View {
        if tracking != 0 {
            content.tracking(tracking)
        } else {
            content
        }
    }
}

/// Sizes text like a CSS line box: `lines × lineHeight` exactly, instead of
/// SwiftUI's pixel-rounded natural height, with each baseline where the
/// design's renderer puts it (`CSSLineMetrics`: half-leading on whole-pixel
/// font metrics, floored). For a line height below the font's natural one the
/// glyphs overflow the box, as in CSS.
private struct CSSLineBox: Layout {
    let lineHeight: CGFloat
    let naturalLineHeight: CGFloat
    /// CSS baseline − SwiftUI baseline, both measured from the box's top.
    let firstBaselineShift: CGFloat
    /// Set: the CSS baseline (from the box's top); the text is placed by its
    /// *measured* first baseline instead of assuming the style font's ascender.
    var measuredBaselineAscent: CGFloat? = nil

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) -> CGSize {
        guard let text = subviews.first else { return .zero }
        let size = text.sizeThatFits(proposal)
        return CGSize(width: size.width, height: CGFloat(lines(in: size.height)) * lineHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) {
        guard let text = subviews.first else { return }
        let proposal = ProposedViewSize(width: bounds.width, height: nil)
        let size = text.sizeThatFits(proposal)
        var shift = firstBaselineShift
        if let measuredBaselineAscent {
            shift = measuredBaselineAscent - text.dimensions(in: proposal)[VerticalAlignment.firstTextBaseline]
        }
        text.place(
            at: CGPoint(x: bounds.minX, y: bounds.minY + shift), anchor: .topLeading,
            proposal: ProposedViewSize(width: bounds.width, height: size.height))
    }

    /// Lines in a text block of `height` (n lines are n × natural + (n − 1) × spacing high).
    private func lines(in height: CGFloat) -> Int {
        let spacing = max(0, lineHeight - naturalLineHeight)
        return max(1, Int(((height + spacing) / (naturalLineHeight + spacing)).rounded()))
    }
}

extension View {
    /// Applies a design type style: font (with the exact variable-font axes),
    /// tracking, CSS line height, upper-casing and Dynamic Type scaling.
    ///
    /// The view's height becomes `lineHeight × lines`, exactly like the CSS line
    /// box. For a line height *below* the font's natural one (Doto at 1.0,
    /// keypad digits, avatar initials) the frame shrinks and the glyphs
    /// overflow it evenly, as in CSS. Multi-line Doto headings need tighter
    /// leading than SwiftUI allows: use `U2DisplayText` for those.
    func u2Font(_ style: U2TextStyle, matchLineBox: Bool = true) -> some View {
        modifier(U2FontModifier(style: style, matchLineBox: matchLineBox))
    }

    /// `u2Font` for a sentence with a run in another font (the 5.1 Ghost
    /// banner's Mono “43 dk”): the run changes where SwiftUI puts the first
    /// baseline, so the text is placed by its measured baseline.
    func u2FontMixedRuns(_ style: U2TextStyle) -> some View {
        modifier(U2FontModifier(style: style, matchLineBox: true, measuresBaseline: true))
    }
}

// MARK: - Variants

extension U2TextStyle {
    /// The same Doto style, but a heading that has to wrap pushes a word down
    /// instead of leaving one alone on the last line (`U2DisplayText` applies
    /// `.pushOut` to styles named “…Balanced”): “Her şey / silinsin mi?”,
    /// “Enter your / secret code”. One-line headings are unchanged.
    var balanced: U2TextStyle {
        guard !name.hasSuffix("Balanced") else { return self }
        return U2TextStyle(
            name: "\(name)Balanced", family: family, weight: weight, size: size, lineHeight: lineHeight,
            tracking: tracking, uppercase: uppercase, monospacedDigits: monospacedDigits, italic: italic,
            scalesWithDynamicType: scalesWithDynamicType)
    }

    /// The same style with proportional figures. Some generated styles are
    /// tabular (`caption`, for the 2.1 counter), but the 7.2 / 7.3 card
    /// captions are drawn with proportional digits (“184 MB” is 3.3 pt narrower).
    var proportional: U2TextStyle {
        U2TextStyle(
            name: "\(name)@pnum", family: family, weight: weight, size: size, lineHeight: lineHeight,
            tracking: tracking, uppercase: uppercase, monospacedDigits: false, italic: italic,
            scalesWithDynamicType: scalesWithDynamicType)
    }
}
