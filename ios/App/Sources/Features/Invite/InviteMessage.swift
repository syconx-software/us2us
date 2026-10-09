import Foundation
import UIKit
import UniformTypeIdentifiers

extension StringTable {
    /// `Features/Invite/Invite.xcstrings` (Turkish source + English).
    static let invite = StringTable(name: "Invite")
}

/// The ready-made invitation (Invite.xcstrings), in the app's language:
/// a short, warm text with the user's username and link, and an e-mail
/// subject. The user sees it — and may edit it — before choosing a channel.
struct InviteMessage: Equatable, Sendable {
    let username: String
    let link: URL
    /// What is sent (SMS, WhatsApp, e-mail body, share sheet).
    var text: String
    /// The e-mail subject (not editable).
    let subject: String

    /// The longest text the editor keeps (a long SMS, a comfortable wa.me URL).
    static let maxLength = 1_000

    /// The message for the user `username` in `locale`'s language, or nil
    /// when `username` cannot make a link (`InviteLink`).
    static func make(username: String, locale: Locale) -> InviteMessage? {
        guard let name = InviteLink.normalizedUsername(username), let link = InviteLink.url(for: name) else { return nil }
        return InviteMessage(
            username: name, link: link, text: defaultText(username: name, link: link, locale: locale),
            subject: StringTable.invite.string("invite.subject", locale: locale))
    }

    /// The template: “Merhaba! Seninle us2us’ta konuşmak istiyorum: … Kullanıcı adım: @selin.n” and the link on its own line.
    static func defaultText(username: String, link: URL, locale: Locale) -> String {
        StringTable.invite.string("invite.message \(username) \(link.absoluteString)", locale: locale)
    }

    /// Keeps an edited text within `maxLength`.
    static func clamped(_ text: String) -> String {
        text.count > maxLength ? String(text.prefix(maxLength)) : text
    }
}

// MARK: - Channel URLs

/// The URLs the channels open. Everything outside RFC 3986's unreserved
/// characters is percent-encoded (UTF-8), so spaces, line breaks, “&”, “#”,
/// “+”, “@”, “’” and Turkish letters reach the other app exactly as written.
enum InviteChannelURL {
    /// WhatsApp's documented click-to-chat link without a number: WhatsApp
    /// opens with the text and the user picks the chat (no SDK, no
    /// `LSApplicationQueriesSchemes`; it is a universal link, so without
    /// WhatsApp it opens wa.me in the browser).
    static func whatsApp(text: String) -> URL? {
        URL(string: "https://wa.me/?text=" + percentEncoded(text))
    }

    /// `mailto:` (RFC 6068) with subject and body and no recipient — the
    /// fallback when Mail cannot compose here. Line breaks become CRLF.
    static func mailto(subject: String, body: String) -> URL? {
        URL(string: "mailto:?subject=\(percentEncoded(subject))&body=\(percentEncoded(crlf(body)))")
    }

    /// RFC 3986 `unreserved`: ALPHA / DIGIT / "-" / "." / "_" / "~".
    static let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")

    static func percentEncoded(_ text: String) -> String {
        // Never nil for valid Unicode strings (the set is ASCII-only).
        text.addingPercentEncoding(withAllowedCharacters: unreserved) ?? ""
    }

    /// LF / CR / CRLF → CRLF.
    static func crlf(_ text: String) -> String {
        text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
            .replacingOccurrences(of: "\n", with: "\r\n")
    }
}

// MARK: - Pasteboard

/// “Bağlantıyı kopyala”: the link only, kept on this device (no Universal
/// Clipboard) and removed from the pasteboard after two minutes.
enum InvitePasteboard {
    static let lifetime: TimeInterval = 120

    static func items(for link: URL) -> [[String: Any]] {
        [[UTType.url.identifier: link, UTType.plainText.identifier: link.absoluteString]]
    }

    static func options(now: Date = .now) -> [UIPasteboard.OptionsKey: Any] {
        [.localOnly: true, .expirationDate: now.addingTimeInterval(lifetime)]
    }

    @MainActor
    static func copy(_ link: URL, to pasteboard: UIPasteboard = .general, now: Date = .now) {
        pasteboard.setItems(items(for: link), options: options(now: now))
    }
}
