import SwiftUI

// MARK: - C5 · Onboarding header (1.1–1.3)

/// C5 — wordmark “us2us” (Doto 900 20) left, pager “n / 3” (Mono 11,
/// `text·68%`) right; row at design y 66, side padding 24.
struct OnboardingHeader: View {
    let page: Int
    var pageCount: Int = 3

    var body: some View {
        HStack(alignment: .center) {
            U2Wordmark(style: .brandWordmark)
            Spacer(minLength: 0)
            Text(verbatim: "\(page) / \(pageCount)")
                .u2Font(.monoMeta)
                .foregroundStyle(Color.u2.textSecondary)
                .accessibilityLabel(Text("a11y.pager \(page) \(pageCount)"))
        }
        .padding(.horizontal, U2Spacing.screenGutterL)
        .u2Top(U2Spacing.onboardingHeaderTop)
    }
}

/// The “us2us” wordmark in Doto (never localized).
struct U2Wordmark: View {
    var style: U2TextStyle = .brandWordmark
    var color: Color = Color.u2.text

    var body: some View {
        Text(verbatim: "us2us")
            .u2Font(style)
            .foregroundStyle(color)
            .accessibilityLabel(Text(verbatim: "us2us"))
    }
}

// MARK: - C6 · Setup header (2.1–2.3)

/// C6 — back button (44, caret-left 22) left, “n / 3” right with 8 pt trailing
/// padding; row at design y 56, side padding 16.
struct SetupHeader: View {
    let step: Int
    var stepCount: Int = 3
    let onBack: () -> Void

    var body: some View {
        HStack(alignment: .center) {
            U2GlassIconButton(.caretLeft, glyphSize: 22, label: Text("a11y.back"), action: onBack)
            Spacer(minLength: 0)
            Text(verbatim: "\(step) / \(stepCount)")
                .u2Font(.monoMeta)
                .foregroundStyle(Color.u2.textSecondary)
                .padding(.trailing, 8)
                .accessibilityLabel(Text("a11y.step \(step) \(stepCount)"))
        }
        .padding(.horizontal, U2Spacing.screenGutterS)
        .frame(height: U2Size.iconButton)
        .u2Top(U2Spacing.headerTopButtons)
    }
}

// MARK: - C7 · Centred-title header (4.3, 6.1, 7.2, 7.3)

/// C7 — 44 high row at design y 56: leading button, centred `navTitle`
/// (Inter 600 17) in the band between the buttons, trailing button or an
/// empty 44 × 44 spacer.
struct CenteredTitleHeader<Leading: View, Trailing: View>: View {
    let title: Text
    @ViewBuilder let leading: Leading
    @ViewBuilder let trailing: Trailing

    init(_ title: Text, @ViewBuilder leading: () -> Leading, @ViewBuilder trailing: () -> Trailing) {
        self.title = title
        self.leading = leading()
        self.trailing = trailing()
    }

    var body: some View {
        ZStack {
            title
                .u2Font(.navTitle, matchLineBox: false)
                .foregroundStyle(Color.u2.text)
                .lineLimit(1)
                .frame(maxWidth: 250)
                .accessibilityAddTraits(.isHeader)
            HStack {
                leading
                Spacer(minLength: 0)
                trailing
            }
        }
        .padding(.horizontal, U2Spacing.screenGutterS)
        .frame(height: U2Size.iconButton)
        .u2Top(U2Spacing.headerTopButtons)
    }
}

extension CenteredTitleHeader where Trailing == Color {
    /// A header with no trailing button (an empty 44 × 44 slot keeps the title centred).
    init(_ title: Text, @ViewBuilder leading: () -> Leading) {
        self.init(title, leading: leading) { Color.clear }
    }
}

/// An empty 44 × 44 header slot.
struct HeaderSpacer: View {
    var body: some View {
        Color.clear.frame(width: U2Size.iconButton, height: U2Size.iconButton).accessibilityHidden(true)
    }
}

// MARK: - C8 · Chat header (4.4, 5.1, 5.2, 8.2, 9.2)

/// C8 — the translucent chat header that floats over the messages:
/// `bg·80%` + backdrop blur, row (pad 54 14 10 12, gap 10) of back button
/// (40, caret-left 20) · avatar 38 · name + status · trailing control, and a
/// dotted rule (`text·20%`) at design y 104 on top of the scrolling content.
struct ChatHeader<Status: View, Trailing: View>: View {
    let username: String
    let palette: AvatarPalette
    var isVerified = false
    var showsOnlineDot = false
    let onBack: () -> Void
    var onTapPeer: (() -> Void)? = nil
    @ViewBuilder let status: Status
    @ViewBuilder let trailing: Trailing

    @Environment(\.u2SafeArea) private var safeArea

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .center, spacing: 10) {
                U2GlassIconButton(.caretLeft, diameter: U2Size.iconButtonChat, glyphSize: 20, label: Text("a11y.back"), action: onBack)
                    .accessibilityIdentifier("chatHeader.back")
                peer
                trailing
            }
            .padding(.leading, 12)
            .padding(.trailing, 14)
            .padding(.top, safeArea.top(U2Spacing.headerTopChat))
            .padding(.bottom, 10)
        }
        .frame(maxWidth: .infinity)
        .background(U2Backdrop(style: .header).ignoresSafeArea(edges: .top))
        .overlay(alignment: .bottom) {
            // The rule sits at design y 104 — 4.63 pt above the header's bottom.
            DottedRule(color: Color.u2.dotRuleHeader)
                .offset(y: -(108.63 - 104 - 2))
        }
    }

    private var peer: some View {
        Button {
            onTapPeer?()
        } label: {
            HStack(alignment: .center, spacing: 10) {
                Avatar(username: username, palette: palette, size: .header, showsOnlineDot: showsOnlineDot)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 5) {
                        Text(verbatim: username)
                            .u2Font(.monoUsername)
                            .foregroundStyle(Color.u2.text)
                            .lineLimit(1)
                        if isVerified {
                            PhosphorIcon(.sealCheck, weight: .fill, size: 15, label: Text("a11y.verified"))
                                .foregroundStyle(Color.u2.accent)
                        }
                    }
                    status
                        .u2Font(.meta)
                        .foregroundStyle(Color.u2.textSecondary)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(onTapPeer == nil)
        .accessibilityIdentifier("chatHeader.peer")
    }
}

// MARK: - Sheet headers

/// 4.2 sheet bar: “Vazgeç” (`rowTitle`, `accent300`) left, `navTitle` centred
/// over the full width; 48 high, side padding 20.
struct SheetBar: View {
    let title: Text
    let cancelTitle: Text
    let onCancel: () -> Void

    var body: some View {
        ZStack {
            title
                .u2Font(.navTitle, matchLineBox: false)
                .foregroundStyle(Color.u2.text)
                .accessibilityAddTraits(.isHeader)
            HStack {
                Button(action: onCancel) { cancelTitle }
                    .buttonStyle(U2TextButtonStyle(tone: .accent, height: 48, fillsWidth: false))
                Spacer()
            }
        }
        .padding(.horizontal, 20)
        .frame(height: 48)
    }
}

/// 5.4 sheet header: centred `navTitle`, close button (36, `ph:x` 17) at the right; 40 high.
struct SheetCloseHeader: View {
    let title: Text
    let onClose: () -> Void

    var body: some View {
        ZStack {
            title
                .u2Font(.navTitle, matchLineBox: false)
                .foregroundStyle(Color.u2.text)
                .accessibilityAddTraits(.isHeader)
            HStack {
                Spacer()
                U2GlassIconButton(.x, diameter: U2Size.iconButtonSheet, glyphSize: 17, label: Text("a11y.close"), action: onClose)
                    .accessibilityIdentifier("sheet.close")
                    .padding(.trailing, 2)
            }
        }
        .frame(height: 40)
    }
}

// MARK: - Section labels and kickers

/// `monoSectionLabel` — Mono 400 10.5, tracking 0.1 em, UPPERCASE in the
/// current language (“GİZLİLİK”), `text·68%`; 16 pt side padding (C13).
struct SectionLabel: View {
    let title: Text
    var horizontalPadding: CGFloat = 16

    init(_ title: Text, horizontalPadding: CGFloat = 16) {
        self.title = title
        self.horizontalPadding = horizontalPadding
    }

    var body: some View {
        title
            .u2Font(.monoSectionLabel)
            .foregroundStyle(Color.u2.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, horizontalPadding)
            .accessibilityAddTraits(.isHeader)
    }
}

/// `monoKicker` — Mono 400 11, tracking 0.08 em, UPPERCASE, `accent300`, with
/// an optional glyph (gap 8): “GHOST MOD · 24 SAAT”, “TAM EŞLEŞME”.
struct MonoKicker: View {
    let title: Text
    var glyph: PhosphorGlyph? = nil
    var glyphSize: CGFloat = 16
    var color: Color = Color.u2.accent300

    var body: some View {
        HStack(spacing: 8) {
            if let glyph {
                PhosphorIcon(glyph, size: glyphSize)
            }
            title.u2Font(.monoKicker)
        }
        .foregroundStyle(color)
    }
}

#Preview("Headers") {
    U2Screen {
        VStack(spacing: 24) {
            OnboardingHeader(page: 1)
            SetupHeader(step: 2) {}
            CenteredTitleHeader(Text(verbatim: "Mesaj istekleri")) {
                U2GlassIconButton(.caretLeft, label: Text(verbatim: "Back")) {}
            } trailing: {
                U2GlassIconButton(.dotsThree, label: Text(verbatim: "More")) {}
            }
            SectionLabel(Text(verbatim: "Gizlilik"))
            MonoKicker(title: Text(verbatim: "Ghost mod · 24 saat"), glyph: .ghost)
        }
    }
    .environment(\.locale, Locale(identifier: "tr"))
}
