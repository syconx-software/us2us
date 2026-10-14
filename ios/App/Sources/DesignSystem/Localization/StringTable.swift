import SwiftUI

/// A feature's String Catalog table (`<Name>.xcstrings` next to the
/// feature's code; Turkish source + English). Shared texts live in the
/// default table (`Resources/Localizable.xcstrings`).
///
/// * `Text("key", table: .chats)` — looked up in the environment's language
///   (the root view sets `\.locale` from 7.1 › Dil);
/// * `.string(_:locale:)` / `.text(_:locale:)` — resolved explicitly in a
///   language: view models, announcements, plural variants, UIKit;
/// * `.resource(_:)` — for `U2DisplayText` headings.
struct StringTable: Hashable, Sendable {
    let name: String

    static let onboarding = StringTable(name: "Onboarding")
    static let lock = StringTable(name: "Lock")
    static let chats = StringTable(name: "Chats")
    static let conversation = StringTable(name: "Conversation")
    static let verification = StringTable(name: "Verification")
    /// The contact screen's tabs, grid and list (Features/Contact).
    static let contact = StringTable(name: "Contact")
    static let settings = StringTable(name: "Settings")
    /// Topluluklar and a community's conversation (Features/Communities).
    static let communities = StringTable(name: "Communities")

    func string(_ key: String.LocalizationValue, locale: Locale) -> String {
        L10n.string(key, locale: locale, table: name)
    }

    /// A `Text` resolved in `locale`'s language (never the process language).
    func text(_ key: String.LocalizationValue, locale: Locale) -> Text {
        Text(verbatim: string(key, locale: locale))
    }

    func resource(_ key: String.LocalizationValue) -> LocalizedStringResource {
        LocalizedStringResource(key, table: name)
    }
}

extension Text {
    /// A string from a feature's table, looked up in the environment's language.
    init(_ key: LocalizedStringKey, table: StringTable) {
        self.init(key, tableName: table.name)
    }
}

/// A `Text` from a table, resolved explicitly in the environment's language
/// (for keys with plural variants, and anywhere the in-app language must win).
struct LocalizedText: View {
    let key: String.LocalizationValue
    let table: StringTable
    @Environment(\.locale) private var locale

    init(_ key: String.LocalizationValue, table: StringTable) {
        self.key = key
        self.table = table
    }

    var body: some View {
        table.text(key, locale: locale)
    }
}
