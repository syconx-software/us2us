import Foundation
import Observation
import SwiftUI

extension StringTable {
    /// `Features/Suspension/Suspension.xcstrings` (Turkish source + English).
    static let suspension = StringTable(name: "Suspension")
}

extension AppModel {
    /// The relay suspended this account (PROTOCOL §4.3): “Hesabın askıya
    /// alındı” covers the app while it is unlocked. Nothing is retried and
    /// nothing on this phone is deleted — a suspension can be lifted, and the
    /// next unlock asks the relay again.
    var showsAccountSuspended: Bool {
        phase == .unlocked && messenger.connection == .suspended
    }
}

/// The e-mail “Destek ekibine yaz” opens, in the app's language: to the
/// support address, “us2us: askıya alınan hesap (@kullanici)”, with the
/// username, the app's version and build, the time in UTC and a line to
/// write under. Nothing else: no message, key or account id.
enum SuspensionMail {
    static func make(username: String?, version: String, build: String, date: Date, locale: Locale) -> ReportMail {
        let table = StringTable.suspension
        let name = username.flatMap { $0.isEmpty ? nil : $0 }
        let subject =
            name.map { table.string("suspended.mail.subject \($0)", locale: locale) }
            ?? table.string("suspended.mail.subject.unknown", locale: locale)
        let app = "\(version) (\(build))"
        var lines: [String] = []
        if let name { lines.append(table.string("suspended.mail.user \(name)", locale: locale)) }
        lines += [
            table.string("suspended.mail.app \(app)", locale: locale),
            table.string("suspended.mail.time \(ReportMail.utcTime(date))", locale: locale),
            "",
            table.string("suspended.mail.prompt", locale: locale),
            "",
        ]
        return ReportMail(subject: subject, body: lines.joined(separator: "\n"))
    }
}

/// The suspended screen's state: writing to support (Mail in the app,
/// another mail app, or the address to copy where none can write).
@Observable
@MainActor
final class AccountSuspendedModel {
    enum Phase: Equatable {
        case idle
        /// Mail (or another mail app) is up.
        case writing
        /// Nothing on this phone can write an e-mail: the address to copy.
        case address
    }

    private(set) var phase: Phase = .idle
    private(set) var copiedAddress = false
    @ObservationIgnored private let mailer: any ReportMailing
    @ObservationIgnored var version: String = AccountSuspendedModel.bundleValue("CFBundleShortVersionString")
    @ObservationIgnored var build: String = AccountSuspendedModel.bundleValue("CFBundleVersion")

    init(mailer: any ReportMailing = SystemReportMailer()) {
        self.mailer = mailer
    }

    /// “Destek ekibine yaz”.
    func writeToSupport(username: String?, locale: Locale, now: Date = .now) async {
        guard phase != .writing else { return }
        let mail = SuspensionMail.make(username: username, version: version, build: build, date: now, locale: locale)
        phase = .writing
        let delivery = await mailer.deliver(mail)
        withU2Animation(U2Motion.state) { phase = delivery == .addressShown ? .address : .idle }
        if delivery == .addressShown {
            AccessibilityNotification.Announcement(StringTable.suspension.string("suspended.address", locale: locale)).post()
        }
    }

    /// The address on the pasteboard (this device only, two minutes).
    func copyAddress(locale: Locale) {
        ReportPasteboard.copyAddress()
        U2Haptics.success()
        withU2Animation(U2Motion.state) { copiedAddress = true }
        AccessibilityNotification.Announcement(StringTable.report.string("report.address.copied", locale: locale)).post()
    }

    nonisolated private static func bundleValue(_ key: String) -> String {
        Bundle.main.object(forInfoDictionaryKey: key) as? String ?? "—"
    }
}
