import SwiftUI

// MARK: - C1 · Primary button (outline + glow)

/// C1 — the primary CTA: transparent, 1 pt `accent` outline, `accent300`
/// label (Inter 500 17), outer glow `0 0 32px -8px accent·70%`. Press fills it
/// with `accent·12%`; disabled draws the whole control at 45 %.
struct U2PrimaryButtonStyle: ButtonStyle {
    enum Size: Sendable {
        /// 54 high, r16 — screens.
        case regular
        /// 48 high, r16 — inside cards (4.2 “Mesaj yaz”).
        case inCard
        /// 50 high, r14, smaller glow — the 4.4 decision bar.
        case bar
    }

    var size: Size = .regular

    func makeBody(configuration: Configuration) -> some View {
        PrimaryButtonBody(configuration: configuration, size: size)
    }
}

private struct PrimaryButtonBody: View {
    let configuration: ButtonStyleConfiguration
    let size: U2PrimaryButtonStyle.Size
    @Environment(\.isEnabled) private var isEnabled

    private var height: CGFloat {
        switch size {
        case .regular: U2Size.buttonPrimary
        case .inCard: U2Size.buttonInCard
        case .bar: U2Size.buttonBar
        }
    }

    private var radius: CGFloat { size == .bar ? U2Radius.buttonSheet : U2Radius.button }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        configuration.label
            .u2Font(.button, matchLineBox: false)
            .foregroundStyle(Color.u2.accent300)
            .frame(maxWidth: .infinity, minHeight: height, maxHeight: height)
            .background(shape.fill(Color.u2.accent.opacity(configuration.isPressed ? 0.12 : 0)))
            .overlay(shape.strokeBorder(Color.u2.accent, lineWidth: 1))
            .u2BoxShadow(size == .bar ? .glowButtonAccept : .glowButton, in: shape)
            .contentShape(shape)
            .opacity(isEnabled ? 1 : U2Opacity.disabled)
            .modifier(U2PressEffect(isPressed: configuration.isPressed))
            .u2Animation(U2Motion.state, value: isEnabled)
    }
}

// MARK: - C2 · Secondary button

/// C2 — same geometry, no border, an inner 1 pt `divider` stroke, label in
/// `text` (“Kodla aç”, “Tekrar dene”, “İptal”). Press fill `text·7%`.
struct U2SecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        SecondaryButtonBody(configuration: configuration)
    }
}

private struct SecondaryButtonBody: View {
    let configuration: ButtonStyleConfiguration
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: U2Radius.button, style: .continuous)
        configuration.label
            .u2Font(.button, matchLineBox: false)
            .foregroundStyle(Color.u2.text)
            .frame(maxWidth: .infinity, minHeight: U2Size.buttonPrimary, maxHeight: U2Size.buttonPrimary)
            .background(shape.fill(Color.u2.text.opacity(configuration.isPressed ? 0.07 : 0)))
            .u2InnerStroke(Color.u2.divider, width: 1, in: shape)
            .contentShape(shape)
            .opacity(isEnabled ? 1 : U2Opacity.disabled)
            .modifier(U2PressEffect(isPressed: configuration.isPressed))
    }
}

// MARK: - C3 · Text button

/// C3 — a centred `rowTitle` (Inter 400 16) label, 44–46 high:
/// `.quiet` = `text·80%` (“Yedekten geri yükle”), `.accent` = `accent300`
/// (“Kodu unuttum”, sheet “Vazgeç”).
struct U2TextButtonStyle: ButtonStyle {
    enum Tone: Sendable {
        case quiet
        case accent
        case danger
    }

    var tone: Tone = .quiet
    var height: CGFloat = U2Size.textButton
    var fillsWidth = true

    func makeBody(configuration: Configuration) -> some View {
        let color: Color =
            switch tone {
            case .quiet: Color.u2.textQuiet
            case .accent: Color.u2.accent300
            case .danger: Color.u2.danger
            }
        return configuration.label
            .u2Font(.rowTitle, matchLineBox: false)
            .foregroundStyle(color)
            .frame(maxWidth: fillsWidth ? .infinity : nil, minHeight: height)
            .contentShape(Rectangle())
            .opacity(configuration.isPressed ? 0.55 : 1)
            .animation(configuration.isPressed ? U2Motion.press : U2Motion.release, value: configuration.isPressed)
    }
}

// MARK: - C4 · Glass icon button

/// C4 — a round glass button: fill `text·9%`, inner hairline 0.5 pt
/// `text·18%`, a centred Phosphor glyph. 44 pt (40 in chat headers, 36 in
/// sheets). Over media the fill is `#0c0d16@40%`, over the camera
/// `#f5f4ff@12%`, with `#f5f4ff` glyphs.
struct U2GlassIconButton: View {
    enum Surface: Sendable {
        case standard
        case media
        case camera
    }

    let glyph: PhosphorGlyph
    var weight: PhosphorWeight = .regular
    var diameter: CGFloat = U2Size.iconButton
    var glyphSize: CGFloat = 21
    var tint: Color? = nil
    var surface: Surface = .standard
    let label: Text
    let action: () -> Void

    init(
        _ glyph: PhosphorGlyph, weight: PhosphorWeight = .regular, diameter: CGFloat = U2Size.iconButton,
        glyphSize: CGFloat = 21, tint: Color? = nil, surface: Surface = .standard, label: Text,
        action: @escaping () -> Void
    ) {
        self.glyph = glyph
        self.weight = weight
        self.diameter = diameter
        self.glyphSize = glyphSize
        self.tint = tint
        self.surface = surface
        self.label = label
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            PhosphorIcon(glyph, weight: weight, size: glyphSize)
                .offset(y: surface == .standard ? Self.glyphBaselineNudge(diameter: diameter, glyphSize: glyphSize) : 0)
        }
        .buttonStyle(GlassIconButtonStyle(diameter: diameter, tint: tint, surface: surface))
        .accessibilityLabel(label)
    }
}

extension U2GlassIconButton {
    /// The design's icon-font glyphs sit lower in a standard 44 pt circle
    /// than a centred vector: 2/3 pt for 21 pt glyphs (7.1–7.3 headers, the
    /// 4.1 / 9.1 header buttons), 1/3 pt for the 22 pt composer “+” (measured
    /// centroids). The camera / media buttons (6.2, 5.6) already match.
    static func glyphBaselineNudge(diameter: CGFloat, glyphSize: CGFloat) -> CGFloat {
        guard diameter == U2Size.iconButton else { return 0 }
        switch glyphSize {
        case 21: return 2.0 / 3.0
        case 22: return 1.0 / 3.0
        default: return 0
        }
    }
}

private struct GlassIconButtonStyle: ButtonStyle {
    let diameter: CGFloat
    let tint: Color?
    let surface: U2GlassIconButton.Surface

    func makeBody(configuration: Configuration) -> some View {
        let fill: Color
        let ink: Color
        switch surface {
        case .standard:
            fill = Color.u2.text.opacity(configuration.isPressed ? 0.14 : 0.09)
            ink = tint ?? Color.u2.text
        case .media:
            fill = Color.u2.onMediaGlass(configuration.isPressed ? 0.55 : 0.4)
            ink = tint ?? Color.u2.onMedia
        case .camera:
            fill = Color.u2.onMedia.opacity(configuration.isPressed ? 0.2 : 0.12)
            ink = tint ?? Color.u2.onMedia
        }
        return configuration.label
            .foregroundStyle(ink)
            .frame(width: diameter, height: diameter)
            .background(Circle().fill(fill))
            .u2InnerStroke(Color.u2.strokeGlass, width: 0.5, in: Circle())
            .contentShape(Circle())
            .modifier(U2PressEffect(isPressed: configuration.isPressed))
    }
}

// MARK: - Decision buttons (4.3 cards, 4.4 bar)

/// The three request actions: “Engelle” (danger), “Sil” (neutral) — inner
/// 1 pt `divider` — and “Kabul et” (1 pt `accent` outline, `accent300`).
/// `.card`: 40 high r12 Inter 14.5 (4.3); `.bar`: 50 high r14 Inter 16 with the
/// accept glow (4.4).
struct U2DecisionButtonStyle: ButtonStyle {
    enum Kind: Sendable {
        case destructive
        case neutral
        case accept
    }

    enum Size: Sendable {
        case card
        case bar
    }

    let kind: Kind
    var size: Size = .card

    func makeBody(configuration: Configuration) -> some View {
        let radius = size == .card ? U2Radius.buttonS : U2Radius.buttonSheet
        let height = size == .card ? U2Size.buttonSmall : U2Size.buttonBar
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        let style: U2TextStyle =
            switch (kind, size) {
            case (.accept, .card): .buttonS
            case (_, .card): .preview
            case (.accept, .bar): .rowTitleEmphasis
            case (_, .bar): .rowTitle
            }
        let ink: Color =
            switch kind {
            case .destructive: Color.u2.danger
            case .neutral: Color.u2.text
            case .accept: Color.u2.accent300
            }
        return configuration.label
            .u2Font(style, matchLineBox: false)
            .foregroundStyle(ink)
            .frame(maxWidth: .infinity, minHeight: height, maxHeight: height)
            .background(
                shape.fill(
                    kind == .accept
                        ? Color.u2.accent.opacity(configuration.isPressed ? 0.12 : 0)
                        : Color.u2.text.opacity(configuration.isPressed ? 0.07 : 0))
            )
            .overlay {
                if kind == .accept {
                    shape.strokeBorder(Color.u2.accent, lineWidth: 1)
                } else {
                    shape.strokeBorder(Color.u2.divider, lineWidth: 1)
                }
            }
            .background {
                if kind == .accept && size == .bar {
                    Color.clear.u2BoxShadow(.glowButtonAccept, in: shape)
                }
            }
            .contentShape(shape)
            .modifier(U2PressEffect(isPressed: configuration.isPressed))
    }
}

// MARK: - Small pill buttons

/// “Durdur” inside the live-location bubble (5.2): 32 high, pad 0 13, r16,
/// fill `bg·55%`, Inter 500 13.5 `danger`.
struct U2InlinePillButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .u2Font(.inlineButton, matchLineBox: false)
            .foregroundStyle(Color.u2.danger)
            .padding(.horizontal, 13)
            .frame(height: 32)
            .background(Capsule().fill(Color.u2.inlineButtonFill))
            .contentShape(Capsule())
            .opacity(configuration.isPressed ? 0.7 : 1)
            .modifier(U2PressEffect(isPressed: configuration.isPressed))
    }
}

/// The pill over the camera (6.2 “Kendi kodumu göster”): 44 high, pad 0 18,
/// r22, `#f5f4ff@12%`, Inter 400 15 `#f5f4ff`, 18 pt glyph, gap 8.
struct U2CameraPillButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .u2Font(.label15, matchLineBox: false)
            .foregroundStyle(Color.u2.onMedia)
            .padding(.horizontal, 18)
            .frame(height: 44)
            .background(Capsule().fill(Color.u2.onMedia.opacity(configuration.isPressed ? 0.2 : 0.12)))
            .contentShape(Capsule())
            .modifier(U2PressEffect(isPressed: configuration.isPressed))
    }
}

// MARK: - Labels

/// A button label: optional 20 pt leading glyph, gap 8, text.
struct U2ButtonLabel: View {
    let title: Text
    var glyph: PhosphorGlyph? = nil
    var glyphSize: CGFloat = 20
    /// A soft spinner in the glyph's place while the action runs (7.2 export,
    /// erase); the title cross-fades when it changes.
    var isWorking = false

    init(_ title: LocalizedStringKey, glyph: PhosphorGlyph? = nil, glyphSize: CGFloat = 20, isWorking: Bool = false) {
        self.init(Text(title), glyph: glyph, glyphSize: glyphSize, isWorking: isWorking)
    }

    init(verbatim title: String, glyph: PhosphorGlyph? = nil, glyphSize: CGFloat = 20, isWorking: Bool = false) {
        self.init(Text(verbatim: title), glyph: glyph, glyphSize: glyphSize, isWorking: isWorking)
    }

    /// Any `Text` — e.g. from a feature's string table.
    init(_ title: Text, glyph: PhosphorGlyph? = nil, glyphSize: CGFloat = 20, isWorking: Bool = false) {
        self.title = title
        self.glyph = glyph
        self.glyphSize = glyphSize
        self.isWorking = isWorking
    }

    var body: some View {
        HStack(spacing: 8) {
            if isWorking {
                SpinningGlyph(size: glyphSize)
                    .transition(.u2Fade)
            } else if let glyph {
                PhosphorIcon(glyph, size: glyphSize)
                    .transition(.u2Fade)
            }
            title
                .contentTransition(.opacity)
        }
        .u2Animation(U2Motion.state, value: isWorking)
    }
}

/// C1 in `danger` — destructive confirmations (3.3 “Her şeyi sil”, 7.1
/// erase, 7.3 cancel): 54 high, r16, 1 pt `danger` outline, `danger` label
/// (Inter 500 17), a soft `danger` glow; press fills `danger·12%`.
struct U2DangerButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        DangerButtonBody(configuration: configuration)
    }
}

private struct DangerButtonBody: View {
    let configuration: ButtonStyleConfiguration
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: U2Radius.button, style: .continuous)
        configuration.label
            .u2Font(.button, matchLineBox: false)
            .foregroundStyle(Color.u2.danger)
            .frame(maxWidth: .infinity, minHeight: U2Size.buttonPrimary, maxHeight: U2Size.buttonPrimary)
            .background(shape.fill(Color.u2.danger.opacity(configuration.isPressed ? 0.12 : 0)))
            .overlay(shape.strokeBorder(Color.u2.danger, lineWidth: 1))
            .u2BoxShadow(.glowDanger, in: shape)
            .contentShape(shape)
            .opacity(isEnabled ? 1 : U2Opacity.disabled)
            .modifier(U2PressEffect(isPressed: configuration.isPressed))
            .u2Animation(U2Motion.state, value: isEnabled)
    }
}

extension ButtonStyle where Self == U2PrimaryButtonStyle {
    /// C1 primary outline button (54 high).
    static var u2Primary: U2PrimaryButtonStyle { U2PrimaryButtonStyle() }
    /// C1 at 48 high, inside cards.
    static var u2PrimaryInCard: U2PrimaryButtonStyle { U2PrimaryButtonStyle(size: .inCard) }
}

extension ButtonStyle where Self == U2DangerButtonStyle {
    /// C1 in `danger`.
    static var u2Danger: U2DangerButtonStyle { U2DangerButtonStyle() }
}

extension ButtonStyle where Self == U2SecondaryButtonStyle {
    /// C2 secondary button.
    static var u2Secondary: U2SecondaryButtonStyle { U2SecondaryButtonStyle() }
}

extension ButtonStyle where Self == U2TextButtonStyle {
    /// C3 text button, `text·80%`.
    static var u2Text: U2TextButtonStyle { U2TextButtonStyle() }
    /// C3 text button, `accent300`.
    static var u2TextAccent: U2TextButtonStyle { U2TextButtonStyle(tone: .accent) }
}

#Preview("Buttons") {
    VStack(spacing: 16) {
        Button {} label: { U2ButtonLabel("preview.button.primary") }
            .buttonStyle(.u2Primary)
        Button {} label: { U2ButtonLabel("preview.button.scan", glyph: .scan) }
            .buttonStyle(.u2Primary)
        Button {} label: { U2ButtonLabel("preview.button.primary") }
            .buttonStyle(.u2Primary)
            .disabled(true)
        Button {} label: { U2ButtonLabel("preview.button.secondary", glyph: .password) }
            .buttonStyle(.u2Secondary)
        Button {} label: { U2ButtonLabel("preview.button.text") }
            .buttonStyle(.u2Text)
        Button {} label: { U2ButtonLabel("preview.button.forgot") }
            .buttonStyle(.u2TextAccent)
        HStack(spacing: 10) {
            U2GlassIconButton(.qrCode, label: Text(verbatim: "QR")) {}
            U2GlassIconButton(.notePencil, tint: Color.u2.accent300, label: Text(verbatim: "New")) {}
            U2GlassIconButton(.caretLeft, diameter: 40, glyphSize: 20, label: Text(verbatim: "Back")) {}
            U2GlassIconButton(.x, diameter: 36, glyphSize: 17, label: Text(verbatim: "Close")) {}
        }
        U2FlexRow(spacing: 8) {
            Button {} label: { Text(verbatim: "Engelle") }.buttonStyle(U2DecisionButtonStyle(kind: .destructive))
            Button {} label: { Text(verbatim: "Sil") }.buttonStyle(U2DecisionButtonStyle(kind: .neutral))
            Button {} label: { Text(verbatim: "Kabul et") }.buttonStyle(U2DecisionButtonStyle(kind: .accept))
                .u2Flex(1.3, border: 2)
        }
    }
    .padding(24)
    .frame(maxHeight: .infinity)
    .background(Color.u2.bg)
}
