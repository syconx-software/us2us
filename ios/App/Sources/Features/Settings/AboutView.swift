import SwiftUI

/// 7.1 › Hakkında (not drawn; composed from the design's parts): the line
/// mark and wordmark, the version, what the app does and does not do (the
/// design's own promises: 1.2, 2.3, 3.3, 4.4), the privacy policy and
/// support pages on us2us.app (App Review 5.1.1(i): the policy is linked in
/// the app), and the licences shipped in the bundle.
struct AboutView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.locale) private var locale
    @Environment(\.openURL) private var openURL
    @State private var licence: Licence?

    /// “0.1.0” (and the build in the header line).
    static var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—"
    }

    private static var build: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "—"
    }

    var body: some View {
        U2Screen {
            VStack(spacing: 0) {
                CenteredTitleHeader(Text("about.title", table: .settings)) {
                    U2GlassIconButton(.caretLeft, label: Text("a11y.back"), action: model.router.pop)
                }
                ScrollView {
                    VStack(alignment: .leading, spacing: U2Spacing.sectionGap) {
                        hero
                        GroupedSection(Text("about.principles", table: .settings)) {
                            principle(.cloudSlash, "about.principle.code", "about.principle.code.detail")
                            GroupedSeparator()
                            principle(.shieldCheck, "about.principle.e2e", "about.principle.e2e.detail")
                            GroupedSeparator()
                            principle(.prohibit, "about.principle.noNotifications", "about.principle.noNotifications.detail")
                            GroupedSeparator()
                            principle(.eyeSlash, "about.principle.noTracking", "about.principle.noTracking.detail")
                        }
                        GroupedSection(Text("about.website", table: .settings)) {
                            website(.privacy, title: Text("about.privacyPolicy", table: .settings))
                                .accessibilityIdentifier("about.privacy")
                            GroupedSeparator(inset: 14)
                            website(.support, title: Text("about.support", table: .settings))
                                .accessibilityIdentifier("about.support")
                        }
                        GroupedSection(Text("about.licences", table: .settings)) {
                            ForEach(Array(Licence.all.enumerated()), id: \.element.id) { index, item in
                                if index > 0 { GroupedSeparator(inset: 14) }
                                GroupedNavigationRow(
                                    tile: nil, title: Text(verbatim: item.component), detail: Text(verbatim: item.kind)
                                ) { licence = item }
                                .accessibilityIdentifier("about.licence.\(item.id)")
                            }
                        }
                    }
                    .padding(EdgeInsets(top: 18, leading: 16, bottom: 0, trailing: 16))
                    .u2Bottom(U2Spacing.ctaBottom)
                }
                .scrollIndicators(.hidden)
            }
        }
        .u2Sheet(item: $licence) { item in
            LicenceSheet(licence: item) { licence = nil }
        }
    }

    private var hero: some View {
        VStack(spacing: 14) {
            LineMark(metrics: .lock)
            U2Wordmark(style: .brandWordmarkL)
            Text("about.version \(Self.version) \(Self.build)", table: .settings)
                .u2Font(.monoMeta)
                .foregroundStyle(Color.u2.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 22)
        .background(RoundedRectangle(cornerRadius: U2Radius.cardL, style: .continuous).fill(Color.u2.surface))
        .accessibilityElement(children: .combine)
    }

    /// A page of the website, in the app's language, opened in the browser
    /// (the site has no analytics, cookies or access logs).
    private func website(_ page: WebsitePage, title: Text) -> some View {
        GroupedNavigationRow(tile: nil, title: title, detail: Text(verbatim: WebsitePage.host)) {
            guard let url = page.url(for: locale) else { return }
            openURL(url)
        }
    }

    private func principle(_ glyph: PhosphorGlyph, _ title: LocalizedStringKey, _ detail: LocalizedStringKey) -> some View {
        GroupedRow(tile: IconTile(content: .glyph(glyph)), title: Text(title, table: .settings), subtitle: Text(detail, table: .settings)) {
            EmptyView()
        }
        .fixedSize(horizontal: false, vertical: true)
        .accessibilityElement(children: .combine)
    }
}

/// The website's pages the app links to, in Turkish or English
/// (relay/app/content/site.ts › `paths`).
enum WebsitePage: CaseIterable, Sendable {
    case privacy
    case support

    static let host = "us2us.app"

    /// `https://us2us.app/tr/gizlilik` in Turkish, `/en/privacy` otherwise.
    func url(for locale: Locale) -> URL? {
        let turkish = locale.language.languageCode?.identifier == "tr"
        let path: String
        switch self {
        case .privacy: path = turkish ? "/tr/gizlilik" : "/en/privacy"
        case .support: path = turkish ? "/tr/destek" : "/en/support"
        }
        return URL(string: "https://" + Self.host + path)
    }
}

/// A licence shipped in the bundle (`Resources/Licenses`, libsignal's copied at build time).
struct Licence: Identifiable, Equatable, Sendable {
    let id: String
    let component: String
    let kind: String
    let resource: String

    static let all: [Licence] = [
        Licence(id: "doto", component: "Doto", kind: "SIL OFL 1.1", resource: "OFL-Doto"),
        Licence(id: "martian", component: "Martian Mono", kind: "SIL OFL 1.1", resource: "OFL-MartianMono"),
        Licence(id: "inter", component: "Inter", kind: "SIL OFL 1.1", resource: "OFL-Inter"),
        Licence(id: "phosphor", component: "Phosphor Icons", kind: "MIT", resource: "Phosphor-LICENSE"),
        Licence(id: "libsignal", component: "libsignal", kind: "AGPL-3.0", resource: "LICENSE.libsignal"),
    ]

    var text: String {
        guard let url = Bundle.main.url(forResource: resource, withExtension: "txt", subdirectory: "Licenses"),
            let text = try? String(contentsOf: url, encoding: .utf8)
        else { return "—" }
        return text
    }
}

/// The licence text in a full-height sheet (C18), Mono 11 for the legal text.
private struct LicenceSheet: View {
    let licence: Licence
    let onClose: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            SheetCloseHeader(title: Text(verbatim: licence.component), onClose: onClose)
                .padding(.horizontal, 14)
                .padding(.top, 6)
            ScrollView {
                Text(verbatim: licence.text)
                    .u2Font(.monoMeta)
                    .foregroundStyle(Color.u2.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
                    .padding(EdgeInsets(top: 12, leading: 20, bottom: 0, trailing: 20))
                    .u2Bottom(40)
            }
            .scrollIndicators(.hidden)
        }
    }
}

#Preview("Hakkında") {
    let model = AppModel.preview()
    return AboutView()
        .environment(model)
        .environment(\.locale, model.locale)
}
