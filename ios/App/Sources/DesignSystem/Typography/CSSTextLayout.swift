import SwiftUI
import UIKit

// Text layouts that reproduce how the browser that drew the design sets
// text, where SwiftUI's `Text` differs.

// MARK: - CSS block width

/// Sizes text like a CSS block inside a shrink-to-fit box: as wide as its
/// longest line when it fits on one line, the full available width once it
/// wraps (a wrapped 4.4 bubble is exactly 290 wide in the design, while
/// SwiftUI would hug the longest wrapped line).
struct CSSBlockWidth: Layout {
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) -> CGSize {
        guard let text = subviews.first else { return .zero }
        let ideal = text.sizeThatFits(.unspecified)
        guard let width = proposal.width, ideal.width > width else { return ideal }
        let wrapped = text.sizeThatFits(ProposedViewSize(width: width, height: nil))
        return CGSize(width: width, height: wrapped.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) {
        subviews.first?.place(
            at: bounds.origin, anchor: .topLeading, proposal: ProposedViewSize(width: bounds.width, height: bounds.height))
    }
}

// MARK: - CSS line clamp

/// `-webkit-line-clamp`: the text wraps by words; when it needs more than
/// `lines` lines, the last visible line ends with “…” right after its last
/// word (4.3 “… slaytları paylaşabilir…”), where SwiftUI's tail truncation
/// would fill the line with characters first (“… paylaşabilir mis…”).
struct CSSLineClampText: View {
    let text: String
    let style: U2TextStyle
    var lines = 2

    @State private var width: CGFloat = 0
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        Text(verbatim: clamped)
            .u2Font(style)
            .lineLimit(lines)
            .truncationMode(.tail)
            .frame(maxWidth: .infinity, alignment: .leading)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
            .accessibilityLabel(Text(verbatim: text))
    }

    private var clamped: String {
        CSSLineClamp.clamp(text, font: style.uiFont(scale: style.scale(for: dynamicTypeSize)), width: width, lines: lines)
    }
}

enum CSSLineClamp {
    static func clamp(_ text: String, font: UIFont, width: CGFloat, lines: Int) -> String {
        guard width > 1, lines > 0, !text.isEmpty else { return text }
        let attributes: [NSAttributedString.Key: Any] = [.font: font]
        let storage = NSTextStorage(string: text, attributes: attributes)
        let layout = NSLayoutManager()
        storage.addLayoutManager(layout)
        let container = NSTextContainer(size: CGSize(width: width, height: .greatestFiniteMagnitude))
        container.lineFragmentPadding = 0
        container.lineBreakMode = .byWordWrapping
        layout.addTextContainer(container)
        layout.ensureLayout(for: container)

        var lineRanges: [NSRange] = []
        var glyph = 0
        while glyph < layout.numberOfGlyphs {
            var range = NSRange()
            layout.lineFragmentRect(forGlyphAt: glyph, effectiveRange: &range)
            lineRanges.append(range)
            glyph = NSMaxRange(range)
        }
        guard lineRanges.count > lines else { return text }

        let string = text as NSString
        let last = layout.characterRange(forGlyphRange: lineRanges[lines - 1], actualGlyphRange: nil)
        var head = string.substring(to: NSMaxRange(last)).trimmingCharacters(in: .whitespacesAndNewlines)
        func lastLineWidth(_ head: String) -> CGFloat {
            let line = (head as NSString).substring(from: min(last.location, (head as NSString).length)) + "…"
            return ceil((line as NSString).size(withAttributes: attributes).width)
        }
        while !head.isEmpty, lastLineWidth(head) > width {
            head.removeLast()
            head = head.trimmingCharacters(in: .whitespaces)
        }
        return head + "…"
    }
}

// MARK: - Greedy paragraph

/// A multi-line paragraph that wraps like the browser that drew the design:
/// greedily. SwiftUI `Text` uses the system's “push out” widow avoidance,
/// which moves a word down to keep the last line from being a single word
/// (7.3: “…sohbetler / geri gelmez.” instead of the drawn “…sohbetler geri /
/// gelmez.”). Laid out by the design system's `CSSTextView` (TextKit 1 with
/// CSS line boxes) with `lineBreakStrategy = []`.
struct GreedyParagraph: View {
    let text: String
    let style: U2TextStyle
    var color: Color = Color.u2.textSecondary
    var alignment: NSTextAlignment = .natural

    @Environment(\.self) private var environment

    var body: some View {
        let resolved = color.resolve(in: environment)
        let uiColor = UIColor(
            red: CGFloat(resolved.red), green: CGFloat(resolved.green), blue: CGFloat(resolved.blue),
            alpha: CGFloat(resolved.opacity))
        GreedyLabel(
            attributed: attributed(uiColor),
            strut: CSSLineMetrics(font: style.uiFont(), lineHeight: style.lineHeight))
            .accessibilityElement()
            .accessibilityLabel(Text(verbatim: text))
    }

    private func attributed(_ color: UIColor) -> NSAttributedString {
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = alignment
        paragraph.lineBreakMode = .byWordWrapping
        paragraph.lineBreakStrategy = []
        var attributes: [NSAttributedString.Key: Any] = [
            .font: style.uiFont(), .foregroundColor: color, .paragraphStyle: paragraph,
        ]
        if style.tracking != 0 { attributes[.kern] = style.tracking }
        return NSAttributedString(string: text, attributes: attributes)
    }
}

private struct GreedyLabel: UIViewRepresentable {
    let attributed: NSAttributedString
    let strut: CSSLineMetrics

    func makeUIView(context: Context) -> CSSTextView {
        let view = CSSTextView()
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        view.setContentHuggingPriority(.defaultLow, for: .horizontal)
        return view
    }

    func updateUIView(_ view: CSSTextView, context: Context) {
        view.configure(attributed: attributed, strut: strut, lineHeight: strut.height)
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView view: CSSTextView, context: Context) -> CGSize? {
        view.configure(attributed: attributed, strut: strut, lineHeight: strut.height)
        let size = view.layoutSize(width: proposal.width ?? .greatestFiniteMagnitude)
        return CGSize(width: proposal.width ?? ceil(size.width), height: size.height)
    }
}

// MARK: - Setup titles (2.1–2.3, 7.1 › code change)

/// A setup-step heading (`displayTitleM` 36/36.72, `displayTitleS`
/// 34/35.36): `U2DisplayText` with its lines placed where the design's
/// renderer painted them.
///
/// Chrome lays these lines out 36.72 / 35.36 pt apart but paints each line
/// on a whole point (the 2nd line of “Gizli kodunu / belirle” is 36 pt below
/// the 1st), while TextKit rounds a fractional line fragment *up* (37) and
/// reports a block ~0.6 pt too tall. Laying the text out at the whole-point
/// line height and adding the fractional remainder back under the block
/// reproduces both: lines at Chrome's positions, and the block exactly as
/// tall as in CSS (the paragraph stays at y 203.44).
struct SetupTitle: View {
    let resource: LocalizedStringResource
    let style: U2TextStyle

    init(_ resource: LocalizedStringResource, style: U2TextStyle) {
        self.resource = resource
        self.style = style
    }

    var body: some View {
        let painted = style.lineHeight.rounded(.down)
        CSSFractionalLineBlock(paintedLineHeight: painted, remainder: style.lineHeight - painted) {
            U2DisplayText(resource, style: Self.style(style, lineHeight: painted))
        }
    }

    private static func style(_ style: U2TextStyle, lineHeight: CGFloat) -> U2TextStyle {
        U2TextStyle(
            // Keeps a “…Balanced” style balanced (`U2DisplayText` reads the suffix).
            name: style.name.hasSuffix("Balanced") ? "\(style.name)@paintedBalanced" : "\(style.name)@painted", family: style.family, weight: style.weight, size: style.size,
            lineHeight: lineHeight, tracking: style.tracking, uppercase: style.uppercase,
            monospacedDigits: style.monospacedDigits, italic: style.italic,
            scalesWithDynamicType: style.scalesWithDynamicType)
    }
}

/// Reports the CSS block height of text laid out at a whole-point line
/// height: `height + lines × remainder` (a line holding an Inter “.” is at
/// most a point taller than the line height, so `floor(height / lineHeight)`
/// counts the lines).
private struct CSSFractionalLineBlock: Layout {
    let paintedLineHeight: CGFloat
    let remainder: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) -> CGSize {
        guard let text = subviews.first else { return .zero }
        let size = text.sizeThatFits(proposal)
        let lines = max(1, (size.height / paintedLineHeight).rounded(.down))
        // A line grown by an Inter “.” already holds the fraction: the
        // design's renderer drew “Geri dönüş / yok.” (2.3) 71.0 pt tall —
        // the grown text itself — not 71 + 2 × 0.36.
        let grown = size.height - lines * paintedLineHeight
        return CGSize(width: size.width, height: size.height + max(0, lines * remainder - grown))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) {
        guard let text = subviews.first else { return }
        let size = text.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil))
        text.place(
            at: bounds.origin, anchor: .topLeading, proposal: ProposedViewSize(width: bounds.width, height: size.height)
        )
    }
}

