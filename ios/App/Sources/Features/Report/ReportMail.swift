import Foundation
import UIKit
import UniformTypeIdentifiers

extension StringTable {
    /// `Features/Report/Report.xcstrings` (Turkish source + English).
    static let report = StringTable(name: "Report")
}

/// Why the user reports someone (“Bildir”), in the order the sheet lists them.
enum ReportReason: String, CaseIterable, Identifiable, Sendable {
    case spam
    case harassment
    case inappropriate
    case impersonation
    case other

    var id: String { rawValue }

    /// The row title, also the reason as the e-mail names it.
    var titleKey: String.LocalizationValue {
        switch self {
        case .spam: "report.reason.spam"
        case .harassment: "report.reason.harassment"
        case .inappropriate: "report.reason.inappropriate"
        case .impersonation: "report.reason.impersonation"
        case .other: "report.reason.other"
        }
    }
}

/// The message a report starts from (5.3 › “Bildir”), as the e-mail may
/// carry it: a text message's text, or only what kind of message it is
/// (“fotoğraf”). Never the media itself, a caption, a file's name, a place,
/// a key or an id.
struct ReportedMessage: Equatable, Sendable {
    enum Kind: String, CaseIterable, Sendable {
        case photo, video, voice, file, location, liveLocation, sticker, viewOnce

        /// How the e-mail and the sheet name it.
        var nameKey: String.LocalizationValue {
            switch self {
            case .photo: "report.kind.photo"
            case .video: "report.kind.video"
            case .voice: "report.kind.voice"
            case .file: "report.kind.file"
            case .location: "report.kind.location"
            case .liveLocation: "report.kind.liveLocation"
            case .sticker: "report.kind.sticker"
            case .viewOnce: "report.kind.viewOnce"
            }
        }
    }

    enum Content: Equatable, Sendable {
        /// A text message: its text may go into the e-mail.
        case text(String)
        /// Anything else: only its kind.
        case kind(Kind)
    }

    /// The longest text the e-mail quotes (longer ones end in “…”).
    static let maxTextLength = 1_000

    let content: Content

    init(content: Content) {
        self.content = content
    }

    /// `nil` for what nobody wrote: a deleted message, a line of the chat itself.
    init?(_ message: Message) {
        switch message.content {
        case .text(let text): content = .text(text)
        case .photo: content = .kind(.photo)
        case .video: content = .kind(.video)
        case .voice: content = .kind(.voice)
        case .file: content = .kind(.file)
        case .location: content = .kind(.location)
        case .liveLocation: content = .kind(.liveLocation)
        case .textSticker, .sticker: content = .kind(.sticker)
        case .viewOnce: content = .kind(.viewOnce)
        case .deleted, .system: return nil
        }
    }

    var isText: Bool {
        if case .text = content { return true }
        return false
    }

    /// The kind, for anything but text.
    var kind: Kind? {
        if case .kind(let kind) = content { return kind }
        return nil
    }

    /// The text as the e-mail quotes it: trimmed, at most `maxTextLength` characters.
    static func quoted(_ text: String) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count > maxTextLength else { return trimmed }
        return String(trimmed.prefix(maxTextLength)) + "…"
    }
}

/// The e-mail a report becomes, in the app's language: to the support
/// address, “us2us bildirimi: @kullanici”, and a body with the username,
/// the reason, the user's note, the app's version and build and the time in
/// UTC. Started from a message (5.3 › “Bildir”) with “Bu mesajın metnini
/// bildirime ekle” on, it also quotes that message — a text message's text,
/// anything else only by its kind — and its last sentence says that only
/// this text was added; otherwise the last sentence says that no message
/// content is in it. Nothing else is ever added: no other message, no
/// media, no key, no account id — the user may attach screenshots themselves.
struct ReportMail: Equatable, Sendable {
    /// Where reports go (also shown, with a copy button, when this phone cannot write an e-mail).
    static let address = "support@us2us.app"
    /// The longest note the sheet keeps.
    static let maxNoteLength = 500

    let subject: String
    let body: String

    /// - Parameter message: the message the report quotes (the user left
    ///   it in), else `nil`.
    static func make(
        about person: ReportSubject, reason: ReportReason, note: String, message: ReportedMessage? = nil,
        version: String, build: String, date: Date, locale: Locale
    ) -> ReportMail {
        let table = StringTable.report
        let subject: String
        let user: String
        if let username = person.username {
            subject = table.string("report.mail.subject \(username)", locale: locale)
            user =
                person.isUsernameVerified
                ? table.string("report.mail.user \(username)", locale: locale)
                : table.string("report.mail.user.unverified \(username)", locale: locale)
        } else {
            subject = table.string("report.mail.subject.unknown", locale: locale)
            user = table.string("report.mail.user.unknown", locale: locale)
        }
        let trimmed = clamped(note).trimmingCharacters(in: .whitespacesAndNewlines)
        let reasonTitle = table.string(reason.titleKey, locale: locale)
        let noteText = trimmed.isEmpty ? "—" : trimmed
        let app = "\(version) (\(build))"
        var lines = [
            user,
            table.string("report.mail.reason \(reasonTitle)", locale: locale),
            table.string("report.mail.note \(noteText)", locale: locale),
        ]
        switch message?.content {
        case .text(let text):
            let quoted = ReportedMessage.quoted(text)
            lines.append(table.string("report.mail.message \(quoted.isEmpty ? "—" : quoted)", locale: locale))
        case .kind(let kind):
            let name = table.string(kind.nameKey, locale: locale)
            lines.append(table.string("report.mail.message.kind \(name)", locale: locale))
        case nil:
            break
        }
        lines += [
            "",
            table.string("report.mail.app \(app)", locale: locale),
            table.string("report.mail.time \(utcTime(date))", locale: locale),
            "",
            message?.isText == true
                ? table.string("report.mail.messageIncluded", locale: locale)
                : table.string("report.mail.encrypted", locale: locale),
        ]
        return ReportMail(subject: subject, body: lines.joined(separator: "\n"))
    }

    /// The note as the sheet keeps it: at most `maxNoteLength` characters.
    static func clamped(_ note: String) -> String {
        note.count > maxNoteLength ? String(note.prefix(maxNoteLength)) : note
    }

    /// “2026-10-12 14:03 UTC” — the same in every language and region.
    static func utcTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyy-MM-dd HH:mm 'UTC'"
        return formatter.string(from: date)
    }

    /// `mailto:` (RFC 6068) to the support address with the subject and
    /// body — for a mail app other than Mail. Line breaks become CRLF.
    var mailtoURL: URL? {
        URL(
            string: "mailto:\(Self.address)?subject=\(InviteChannelURL.percentEncoded(subject))"
                + "&body=\(InviteChannelURL.percentEncoded(InviteChannelURL.crlf(body)))")
    }
}

/// How far a report got once the user tapped “E-postayla gönder”.
enum ReportDelivery: Equatable, Sendable {
    /// Mail sent it.
    case sent
    /// Another mail app took it over (`mailto:`); whether it goes is up to the user there.
    case handedOff
    /// Nothing on this phone can write an e-mail: the sheet shows the
    /// address to copy, and the report is in the user's hands.
    case addressShown
    /// Mail closed without sending (cancelled, or kept as a draft).
    case notSent
    /// Mail could not send it.
    case failed

    /// The report left the sheet: sent, handed to a mail app, or the
    /// address given to the user to write to.
    var leftTheApp: Bool {
        switch self {
        case .sent, .handedOff, .addressShown: true
        case .notSent, .failed: false
        }
    }

    /// “Bu kişiyi de engelle” blocks only once the report left the sheet —
    /// never when Mail was cancelled, kept as a draft or failed.
    static func blocks(after delivery: ReportDelivery, alsoBlock: Bool) -> Bool {
        alsoBlock && delivery.leftTheApp
    }
}

/// The fallback's “Kopyala”: the address only, kept on this device (no
/// Universal Clipboard) and removed from the pasteboard after two minutes.
enum ReportPasteboard {
    static let lifetime: TimeInterval = 120

    @MainActor
    static func copyAddress(to pasteboard: UIPasteboard = .general, now: Date = .now) {
        pasteboard.setItems(
            [[UTType.plainText.identifier: ReportMail.address]],
            options: [.localOnly: true, .expirationDate: now.addingTimeInterval(lifetime)])
    }
}
