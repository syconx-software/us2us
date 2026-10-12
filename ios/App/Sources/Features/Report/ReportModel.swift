import SwiftUI

/// The report sheet's state (“Bildir”, App Review 1.2): the reason, an
/// optional note and “Bu kişiyi de engelle” (on by default), then the
/// e-mail to the support address (`ReportMail`) through Mail, another mail
/// app or — when this phone has neither — the address to copy.
///
/// The block follows the report only once the report left the sheet
/// (`ReportDelivery.blocks(after:alsoBlock:)`): sent by Mail, handed to a
/// mail app, or the address given to the user. Cancelling Mail, keeping a
/// draft or a failure blocks nobody; the form stays as it was.
@Observable
@MainActor
final class ReportModel: Identifiable, Equatable {
    enum Phase: Equatable {
        /// Reason, note, the block toggle and “E-postayla gönder”.
        case form
        /// Mail (or another mail app) is up.
        case sending
        /// Nothing on this phone can write an e-mail: the address to copy.
        case address
    }

    let id = UUID()
    let subject: ReportSubject
    /// The name the sheet says (the username, or what the screen shows for the person).
    let personName: String
    /// “Bu kişiyi de engelle” is offered: the person is not blocked yet.
    let offersBlock: Bool

    var reason: ReportReason?
    var note = "" {
        didSet {
            let clamped = ReportMail.clamped(note)
            if clamped != note { note = clamped }
        }
    }
    var alsoBlock = true
    private(set) var phase: Phase = .form
    /// Mail could not send the last attempt (the form says so).
    private(set) var failed = false
    /// This report blocked the person.
    private(set) var didBlock = false
    /// “Kopyala” on the address was tapped.
    private(set) var copiedAddress = false
    @ObservationIgnored private var isClosed = false
    /// The block this report started (it may still be waiting for the network).
    @ObservationIgnored private(set) var blocking: Task<Void, Never>?

    @ObservationIgnored private let mailer: any ReportMailing
    @ObservationIgnored private let block: @MainActor () async -> Void
    /// Closes the sheet; `blocked`: this report blocked the person.
    @ObservationIgnored var onClose: (@MainActor (_ blocked: Bool) -> Void)?
    @ObservationIgnored var version: String = ReportModel.bundleValue("CFBundleShortVersionString")
    @ObservationIgnored var build: String = ReportModel.bundleValue("CFBundleVersion")

    /// - Parameters:
    ///   - subject: who the report names (`MessengerService.reportSubject`).
    ///   - personName: how the screens show the person.
    ///   - offersBlock: `false` when the person is blocked already.
    ///   - block: blocks the person (the host's own way: a chat, a request).
    init(
        subject: ReportSubject, personName: String, offersBlock: Bool, mailer: any ReportMailing = SystemReportMailer(),
        block: @escaping @MainActor () async -> Void
    ) {
        self.subject = subject
        self.personName = personName
        self.offersBlock = offersBlock
        self.mailer = mailer
        self.block = block
    }

    nonisolated static func == (lhs: ReportModel, rhs: ReportModel) -> Bool { lhs.id == rhs.id }

    var canSend: Bool { reason != nil && phase == .form }

    /// The person is blocked if the report leaves the sheet.
    var blocksOnSend: Bool { offersBlock && alsoBlock }

    // MARK: Actions

    /// “E-postayla gönder”: the e-mail in `locale`'s language, then what
    /// came of it.
    func send(locale: Locale, now: Date = .now) async {
        guard let reason, phase == .form else { return }
        failed = false
        let mail = ReportMail.make(
            about: subject, reason: reason, note: note, version: version, build: build, date: now, locale: locale)
        phase = .sending
        let delivery = await mailer.deliver(mail)
        if ReportDelivery.blocks(after: delivery, alsoBlock: blocksOnSend) {
            didBlock = true
            // Stored at once; the new access key the block asks the relay
            // for can take a while on a poor connection — the sheet does
            // not wait for it.
            let block = block
            blocking = Task { await block() }
        }
        switch delivery {
        case .sent, .handedOff:
            announce(delivery == .sent ? "report.sent" : "report.handedOff", locale: locale)
            close()
        case .addressShown:
            withU2Animation(U2Motion.state) { phase = .address }
            announce("report.address.title", locale: locale)
        case .notSent:
            phase = .form
        case .failed:
            withU2Animation(U2Motion.state) {
                phase = .form
                failed = true
            }
            announce("report.failed", locale: locale)
        }
    }

    /// The address on the pasteboard (this device only, two minutes).
    func copyAddress(locale: Locale) {
        ReportPasteboard.copyAddress()
        U2Haptics.success()
        withU2Animation(U2Motion.state) { copiedAddress = true }
        announce("report.address.copied", locale: locale)
    }

    /// “Vazgeç”, “Tamam”, or the sheet pulled down (once).
    func close() {
        guard !isClosed else { return }
        isClosed = true
        onClose?(didBlock)
    }

    /// The sheet left the screen without “Vazgeç” or “Tamam” (pulled down).
    /// Not while Mail is up over it: the report is still on its way.
    func sheetDidDisappear() {
        guard phase != .sending else { return }
        close()
    }

    private func announce(_ key: String.LocalizationValue, locale: Locale) {
        var text = StringTable.report.string(key, locale: locale)
        if didBlock {
            text += " " + StringTable.report.string("report.blocked \(personName)", locale: locale)
        }
        AccessibilityNotification.Announcement(text).post()
    }

    nonisolated private static func bundleValue(_ key: String) -> String {
        Bundle.main.object(forInfoDictionaryKey: key) as? String ?? "—"
    }
}
