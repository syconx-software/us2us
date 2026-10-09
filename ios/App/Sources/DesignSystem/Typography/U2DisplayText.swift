import SwiftUI
import UIKit

/// A Doto display heading, set exactly like the design:
///
/// * Doto with ROND = 100 at the style's weight;
/// * every “.” in Inter 700 at the same size (`displayPunctuation` — 1.1, 1.2,
///   1.3, 2.3, 3.3, 6.3);
/// * CSS line boxes: the line height (often *tighter* than the font's own,
///   e.g. 40/40.8) with Blink's rounding, and a line that contains an Inter
///   “.” grows to fit it like an inline box does in CSS (`CSSLineMetrics` —
///   “İki uç. Bir hat.” has 47 pt lines and its baseline 40 pt down).
///
/// SwiftUI `Text` cannot set a line height below the font's natural one, so
/// this view lays the text out with TextKit 1 (`CSSTextView`) and reports its
/// exact size to SwiftUI. Single-line Doto (counters, “Sohbetler”) can simply
/// use `Text(…).u2Font(style)`.
struct U2DisplayText: View {
    private enum Source {
        case resource(LocalizedStringResource)
        case verbatim(String)
    }

    private let source: Source
    private let style: U2TextStyle
    private let alignment: NSTextAlignment
    private let color: Color
    private let isHeader: Bool

    @Environment(\.locale) private var locale
    @Environment(\.self) private var environment

    /// A localized heading (the key is looked up in the environment's language).
    init(
        _ resource: LocalizedStringResource, style: U2TextStyle, alignment: TextAlignment = .leading,
        color: Color = Color.u2.text, isHeader: Bool = true
    ) {
        self.source = .resource(resource)
        self.style = style
        self.alignment = Self.nsAlignment(alignment)
        self.color = color
        self.isHeader = isHeader
    }

    /// Text that is not localized (user content, sticker artwork, numbers).
    init(
        verbatim text: String, style: U2TextStyle, alignment: TextAlignment = .leading,
        color: Color = Color.u2.text, isHeader: Bool = false
    ) {
        self.source = .verbatim(text)
        self.style = style
        self.alignment = Self.nsAlignment(alignment)
        self.color = color
        self.isHeader = isHeader
    }

    private static func nsAlignment(_ alignment: TextAlignment) -> NSTextAlignment {
        switch alignment {
        case .leading: .natural
        case .center: .center
        case .trailing: .right
        }
    }

    private var string: String {
        switch source {
        case .verbatim(let text):
            return text
        case .resource(var resource):
            resource.locale = locale
            return String(localized: resource)
        }
    }

    var body: some View {
        let text = string
        let resolved = color.resolve(in: environment)
        let uiColor = UIColor(
            red: CGFloat(resolved.red), green: CGFloat(resolved.green), blue: CGFloat(resolved.blue),
            alpha: CGFloat(resolved.opacity))
        DisplayLabel(
            attributed: Self.attributed(text, style: style, alignment: alignment, color: uiColor),
            strut: CSSLineMetrics(font: style.uiFont(), lineHeight: style.lineHeight))
            .accessibilityElement()
            .accessibilityLabel(Text(verbatim: text))
            .accessibilityAddTraits(isHeader ? .isHeader : [])
    }

    /// The attributed string: Doto runs, Inter 700 full stops. Line boxes are
    /// applied by `CSSLineBoxTypesetting`, not by the paragraph style.
    static func attributed(_ text: String, style: U2TextStyle, alignment: NSTextAlignment, color: UIColor) -> NSAttributedString {
        let doto = style.uiFont()
        let punctuation = U2Fonts.font(.inter, weight: 700, size: style.size)

        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = alignment
        paragraph.lineBreakMode = .byWordWrapping
        // CSS wraps greedily; the system's “push out” widow avoidance would
        // move “Bir” down in “İki uç. Bir / hat.”. Balanced headings (6.3,
        // `text-wrap: balance`) keep it.
        paragraph.lineBreakStrategy = style.name.hasSuffix("Balanced") ? .pushOut : []

        let result = NSMutableAttributedString()
        for character in text {
            let isStop = character == "."
            var attributes: [NSAttributedString.Key: Any] = [
                .font: isStop ? punctuation : doto,
                .foregroundColor: color,
                .paragraphStyle: paragraph,
            ]
            // A kern of 0 would switch the font's own kerning off.
            if style.tracking != 0 { attributes[.kern] = style.tracking }
            result.append(NSAttributedString(string: String(character), attributes: attributes))
        }
        return result
    }
}

// MARK: - CSS line boxes

/// The vertical metrics of one font in a CSS line box, computed the way the
/// design's renderer (Chrome/Blink) does: the font's ascent and descent are
/// rounded to whole CSS pixels, the half-leading `(line-height − content) / 2`
/// is floored onto the ascent, and the descent takes the rest.
///
/// A line box is the union of the block's *strut* (its own font at the line
/// height) and every inline font on the line. That is why “İki uç. Bir hat.”
/// (Doto 46/46 with Inter 700 full stops) has 47 pt lines, not 46, and its
/// baseline sits 40 pt below the line's top.
struct CSSLineMetrics: Equatable, Sendable {
    /// Line top → baseline.
    var ascent: CGFloat
    /// Baseline → line bottom.
    var descent: CGFloat

    var height: CGFloat { ascent + descent }

    init(ascent: CGFloat, descent: CGFloat) {
        self.ascent = ascent
        self.descent = descent
    }

    init(font: UIFont, lineHeight: CGFloat) {
        let ascent = font.ascender.rounded()
        let descent = (-font.descender).rounded()
        let halfLeading = ((lineHeight - (ascent + descent)) / 2).rounded(.down)
        self.ascent = ascent + halfLeading
        self.descent = lineHeight - self.ascent
    }

    func union(_ other: CSSLineMetrics) -> CSSLineMetrics {
        CSSLineMetrics(ascent: max(ascent, other.ascent), descent: max(descent, other.descent))
    }

    /// The line box of a line set in `fonts` (plus the strut).
    func lineBox(for fonts: some Sequence<UIFont>, lineHeight: CGFloat) -> CSSLineMetrics {
        fonts.reduce(self) { $0.union(CSSLineMetrics(font: $1, lineHeight: lineHeight)) }
    }
}

/// TextKit 1 delegate that gives every line its CSS line box (see
/// `CSSLineMetrics`) instead of TextKit's own line height.
private final class CSSLineBoxTypesetting: NSObject, NSLayoutManagerDelegate {
    let strut: CSSLineMetrics
    let lineHeight: CGFloat

    init(strut: CSSLineMetrics, lineHeight: CGFloat) {
        self.strut = strut
        self.lineHeight = lineHeight
    }

    func layoutManager(
        _ layoutManager: NSLayoutManager, shouldSetLineFragmentRect lineFragmentRect: UnsafeMutablePointer<CGRect>,
        lineFragmentUsedRect: UnsafeMutablePointer<CGRect>, baselineOffset: UnsafeMutablePointer<CGFloat>,
        in textContainer: NSTextContainer, forGlyphRange glyphRange: NSRange
    ) -> Bool {
        let characters = layoutManager.characterRange(forGlyphRange: glyphRange, actualGlyphRange: nil)
        var fonts: [UIFont] = []
        layoutManager.textStorage?.enumerateAttribute(.font, in: characters) { value, _, _ in
            if let font = value as? UIFont { fonts.append(font) }
        }
        let box = strut.lineBox(for: fonts, lineHeight: lineHeight)
        lineFragmentRect.pointee.size.height = box.height
        lineFragmentUsedRect.pointee.size.height = box.height
        baselineOffset.pointee = box.ascent
        return true
    }
}

/// Lays out and draws attributed text with CSS line boxes. The text is drawn
/// by a subview that extends past the bounds, because glyphs overflow a line
/// box tighter than the font (Doto at line-height 1), exactly as in CSS.
final class CSSTextView: UIView {
    private static let overflow: CGFloat = 32

    private let storage = NSTextStorage()
    private let layoutManager = NSLayoutManager()
    private let container = NSTextContainer(size: .zero)
    private let canvas = CSSTextCanvas()
    private var typesetting: CSSLineBoxTypesetting?

    override init(frame: CGRect) {
        super.init(frame: frame)
        container.lineFragmentPadding = 0
        container.lineBreakMode = .byWordWrapping
        container.maximumNumberOfLines = 0
        layoutManager.usesFontLeading = false
        layoutManager.addTextContainer(container)
        storage.addLayoutManager(layoutManager)
        backgroundColor = .clear
        clipsToBounds = false
        isAccessibilityElement = false
        canvas.draws = { [weak self] in self?.drawText() }
        addSubview(canvas)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    func configure(attributed: NSAttributedString, strut: CSSLineMetrics, lineHeight: CGFloat) {
        if typesetting?.strut != strut || typesetting?.lineHeight != lineHeight {
            let typesetting = CSSLineBoxTypesetting(strut: strut, lineHeight: lineHeight)
            self.typesetting = typesetting
            layoutManager.delegate = typesetting
            layoutManager.invalidateLayout(forCharacterRange: NSRange(location: 0, length: storage.length), actualCharacterRange: nil)
        }
        if !storage.isEqual(to: attributed) {
            storage.setAttributedString(attributed)
        }
        canvas.setNeedsDisplay()
    }

    /// The size of the laid-out text at `width` (exact CSS heights, unrounded).
    func layoutSize(width: CGFloat) -> CGSize {
        setContainerWidth(width)
        layoutManager.ensureLayout(for: container)
        return layoutManager.usedRect(for: container).size
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        canvas.frame = bounds.insetBy(dx: -Self.overflow, dy: -Self.overflow)
        if setContainerWidth(bounds.width) { canvas.setNeedsDisplay() }
    }

    @discardableResult
    private func setContainerWidth(_ width: CGFloat) -> Bool {
        let size = CGSize(width: max(width, 1), height: .greatestFiniteMagnitude)
        guard container.size != size else { return false }
        container.size = size
        return true
    }

    private func drawText() {
        let glyphs = layoutManager.glyphRange(for: container)
        let origin = CGPoint(x: Self.overflow, y: Self.overflow)
        layoutManager.drawBackground(forGlyphRange: glyphs, at: origin)
        layoutManager.drawGlyphs(forGlyphRange: glyphs, at: origin)
    }
}

private final class CSSTextCanvas: UIView {
    var draws: (() -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false
        backgroundColor = .clear
        contentMode = .redraw
        isUserInteractionEnabled = false
        isAccessibilityElement = false
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func draw(_ rect: CGRect) {
        draws?()
    }
}

private struct DisplayLabel: UIViewRepresentable {
    let attributed: NSAttributedString
    let strut: CSSLineMetrics

    private var lineHeight: CGFloat { strut.height }

    func makeUIView(context: Context) -> CSSTextView {
        let view = CSSTextView()
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        view.setContentHuggingPriority(.defaultLow, for: .horizontal)
        return view
    }

    func updateUIView(_ view: CSSTextView, context: Context) {
        view.configure(attributed: attributed, strut: strut, lineHeight: lineHeight)
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView view: CSSTextView, context: Context) -> CGSize? {
        view.configure(attributed: attributed, strut: strut, lineHeight: lineHeight)
        let size = view.layoutSize(width: proposal.width ?? .greatestFiniteMagnitude)
        // Take the full proposed width so alignment inside the view applies.
        return CGSize(width: proposal.width ?? ceil(size.width), height: size.height)
    }
}

#Preview("Display headings") {
    VStack(alignment: .leading, spacing: 24) {
        U2DisplayText("preview.display.hero", style: .displayHero)
        U2DisplayText("preview.display.titleL", style: .displayTitleL)
        U2DisplayText("preview.display.verified", style: .displayTitleLBalanced, alignment: .center)
    }
    .padding(24)
    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    .background(Color.u2.bg)
}
