import SwiftUI

/// “Kullanım Koşulları” (App Review 1.2; in the report sheet's language): a
/// full-height C18 sheet — the title (with “Vazgeç” before a way in), one
/// line on why it is asked, the zero-tolerance rule in a C13 card on `bg`
/// with a `danger` tile, the key rules as C13 rows (what is not allowed,
/// blocking, reporting, review within 24 hours and removal), the whole text
/// on us2us.app and, pinned under the scrolling text, “Kabul ediyorum” (C1).
///
/// Before a way in (1.1–1.3) “Vazgeç” or pulling the sheet down goes back
/// to the welcome pages and nothing goes on. With an account already here
/// (`.required`) there is no way past it but “Kabul ediyorum”: the sheet
/// cannot be pulled down and has no “Vazgeç”.
struct TermsSheet: View {
    enum Mode: Equatable {
        /// Before “Hesabımı oluştur”, “Yedekten geri yükle” or “Eski telefondan taşı”.
        case beforeStart
        /// The app with an account on this phone: accepted once, or not used.
        case required
    }

    let mode: Mode
    let onAgree: () -> Void
    var onDecline: () -> Void = {}

    @Environment(\.locale) private var locale
    @Environment(\.openURL) private var openURL
    @Environment(\.u2SafeArea) private var safeArea

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    intro
                        .padding(.horizontal, 8)
                    zeroTolerance
                        .padding(.top, 20)
                    rules
                        .padding(.top, 22)
                    fullTerms
                        .padding(.top, 22)
                }
                .padding(EdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 16))
            }
            .scrollIndicators(.hidden)
            .scrollBounceBehavior(.basedOnSize)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                agreeBar
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(mode == .required ? "terms.sheet.required" : "terms.sheet")
    }

    // MARK: Header

    /// As C18's sheet bar: the title centred, “Vazgeç” at the left before a way in.
    private var header: some View {
        ZStack {
            Text("terms.title", table: .terms)
                .u2Font(.navTitle, matchLineBox: false)
                .foregroundStyle(Color.u2.text)
                .accessibilityAddTraits(.isHeader)
            if mode == .beforeStart {
                HStack {
                    Button(action: onDecline) { Text("common.cancel") }
                        .buttonStyle(U2TextButtonStyle(tone: .accent, height: 48, fillsWidth: false))
                        .accessibilityHint(Text("terms.decline.hint", table: .terms))
                        .accessibilityIdentifier("terms.decline")
                    Spacer()
                }
            }
        }
        .padding(.horizontal, 20)
        .frame(height: 48)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("terms.bar")
    }

    // MARK: Content

    private var intro: some View {
        Group {
            if mode == .required {
                Text("terms.intro.required", table: .terms)
            } else {
                Text("terms.intro", table: .terms)
            }
        }
        .u2Font(.callout)
        .foregroundStyle(Color.u2.textSecondary)
        .fixedSize(horizontal: false, vertical: true)
    }

    /// The rule App Review asks to be stated, on its own.
    private var zeroTolerance: some View {
        GroupedCard(fill: Color.u2.bg) {
            HStack(alignment: .top, spacing: U2Spacing.rowGap) {
                IconTile(content: .glyph(.prohibit), tone: .danger)
                Text("terms.zeroTolerance", table: .terms)
                    .u2Font(.rowTitleEmphasis)
                    .foregroundStyle(Color.u2.text)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(14)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("terms.zeroTolerance")
    }

    /// What is not allowed, and what anyone can do about it.
    private var rules: some View {
        VStack(alignment: .leading, spacing: U2Spacing.sectionLabelGap) {
            SectionLabel(Text("terms.rules.label", table: .terms))
            GroupedCard(fill: Color.u2.bg) {
                rule(.warningCircle, "terms.rule.content", "terms.rule.content.detail", id: "content")
                GroupedSeparator()
                rule(.prohibit, "terms.rule.block", "terms.rule.block.detail", id: "block")
                GroupedSeparator()
                rule(.warning, "terms.rule.report", "terms.rule.report.detail", id: "report")
                GroupedSeparator()
                rule(.clock, "terms.rule.review", "terms.rule.review.detail", id: "review")
            }
        }
    }

    private func rule(
        _ glyph: PhosphorGlyph, _ title: LocalizedStringKey, _ detail: LocalizedStringKey, id: String
    ) -> some View {
        GroupedRow(
            tile: IconTile(content: .glyph(glyph)), title: Text(title, table: .terms),
            subtitle: Text(detail, table: .terms)
        ) {
            EmptyView()
        }
        .fixedSize(horizontal: false, vertical: true)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("terms.rule.\(id)")
    }

    /// The whole text, in the app's language, in the browser.
    private var fullTerms: some View {
        GroupedCard(fill: Color.u2.bg) {
            GroupedNavigationRow(
                tile: IconTile(content: .glyph(.fileLock)), title: Text("terms.full", table: .terms),
                detail: Text(verbatim: WebsitePage.host)
            ) {
                guard let url = WebsitePage.terms.url(for: locale) else { return }
                openURL(url)
            }
            .accessibilityHint(Text("terms.full.hint", table: .terms))
            .accessibilityIdentifier("terms.full")
        }
    }

    // MARK: Agree

    /// The line on what the tap means, then “Kabul ediyorum” (pad 12 16, on
    /// the sheet's `surface`; the text fades out above it).
    private var agreeBar: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("terms.agree.footnote", table: .terms)
                .u2Font(.caption)
                .foregroundStyle(Color.u2.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 8)
            Button(action: agree) {
                U2ButtonLabel(Text("terms.agree", table: .terms), glyph: .check)
            }
            .buttonStyle(.u2Primary)
            .accessibilityIdentifier("terms.agree")
        }
        .padding(EdgeInsets(top: 12, leading: 16, bottom: safeArea.bottom(40), trailing: 16))
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.u2.surface)
        .background(alignment: .top) {
            LinearGradient(colors: [Color.u2.surface.opacity(0), Color.u2.surface], startPoint: .top, endPoint: .bottom)
                .frame(height: 16)
                .offset(y: -16)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
    }

    private func agree() {
        U2Haptics.success()
        AccessibilityNotification.Announcement(StringTable.terms.string("terms.accepted", locale: locale)).post()
        onAgree()
    }
}

#Preview("Kullanım Koşulları") {
    TermsSheet(mode: .beforeStart, onAgree: {})
        .padding(.top, 40)
        .background(Color.u2.surface)
        .environment(\.locale, Locale(identifier: "tr"))
}
