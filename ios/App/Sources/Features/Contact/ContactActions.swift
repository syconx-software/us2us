import SwiftUI

/// The contact screen's “…” menu (proposed, App Review 1.2): the safety
/// number to share (on Doğrulama), “Bildir” (the report sheet) and
/// “Engelle” after the request block's own confirmation (“{kişi}
/// engellensin mi?” · Engelle · Bildir · Vazgeç) — “Engeli kaldır” once
/// blocked, in the same place — and, in a group of their own, “Sohbeti sil”
/// and “Kişiyi sil” after their confirmation (`ChatDeletion`; on this phone
/// only). The blocked state itself comes from the messenger
/// (`Peer.isBlocked`), so 4.1 and 5.1 follow on their own; a deleted chat
/// takes this screen and 5.1 with it (back to 4.1).
@Observable
@MainActor
final class ContactActionsModel {
    let chatID: ChatID
    let messenger: any MessengerService

    var showsMenu = false
    /// “{kişi} engellensin mi?”
    var confirmsBlock = false
    /// “Sohbeti sil” / “Kişiyi sil”, waiting for its confirmation.
    var deletion: ChatDeletion?
    /// The report sheet.
    var report: ReportModel?
    /// The UI language for announcements; the screen keeps it current.
    @ObservationIgnored var locale: Locale = .current
    /// Where reports are sent from (the harness swaps it).
    @ObservationIgnored var mailer: any ReportMailing = SystemReportMailer()
    /// The name of a block the user confirmed, until it shows (`blockDidShow`).
    @ObservationIgnored private var blockAwaitingFeedback: String?

    init(chatID: ChatID, messenger: any MessengerService) {
        self.chatID = chatID
        self.messenger = messenger
    }

    var peer: Peer? { messenger.peer(for: chatID) }
    var peerName: String { peer?.username ?? chatID }
    var isBlocked: Bool { peer?.isBlocked ?? false }

    // MARK: Blocking

    func requestBlock() {
        confirmsBlock = true
    }

    /// After the confirmation: blocked (the menu, the note, 5.1 and 4.1 follow).
    func block() async {
        blockAwaitingFeedback = peerName
        if await messenger.block(chat: chatID) {
            blockDidShow()
        } else {
            blockAwaitingFeedback = nil
        }
    }

    /// The block is on screen (`isBlocked` turned true): the haptic and the
    /// announcement, once. The block is stored at once, but the call also
    /// waits for the new access key it asks the relay for (up to its
    /// timeout on a poor connection) — the feedback does not.
    func blockDidShow() {
        guard let name = blockAwaitingFeedback else { return }
        blockAwaitingFeedback = nil
        U2Haptics.success()
        announce(StringTable.contact.string("a11y.blocked \(name)", locale: locale))
    }

    /// “Engeli kaldır”: no confirmation — it can be undone at once. The
    /// name is taken first: a blocked request leaves the chats (it is a
    /// request again), and with it the peer.
    func unblock() async {
        let name = peerName
        guard await messenger.unblock(chat: chatID) else { return }
        announce(StringTable.contact.string("a11y.unblocked \(name)", locale: locale))
    }

    // MARK: Deleting

    /// “Sohbeti sil” / “Kişiyi sil”: the confirmation first.
    func requestDeletion(_ kind: ChatDeletion.Kind) {
        deletion = ChatDeletion(kind, chat: chatID, peer: peer)
    }

    // MARK: Report

    /// “Bildir” (the menu, or the block confirmation): the sheet, with
    /// “Bu kişiyi de engelle” unless the person is blocked already. From the
    /// confirmation it rises once the alert has gone (one presentation at
    /// a time).
    func openReport(afterAlert: Bool = false) {
        confirmsBlock = false
        let subject = messenger.reportSubject(for: chatID) ?? ReportSubject(username: nil, isUsernameVerified: false)
        let messenger = messenger
        let chat = chatID
        let report = ReportModel(subject: subject, personName: peerName, offersBlock: !isBlocked, mailer: mailer) {
            await messenger.block(chat: chat)
        }
        report.onClose = { [weak self] _ in self?.report = nil }
        guard afterAlert else {
            self.report = report
            return
        }
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(450))
            self?.report = report
        }
    }

    private func announce(_ text: String) {
        AccessibilityNotification.Announcement(text).post()
    }
}

/// The contact screen's note while the person is blocked (Doğrulama, above
/// the line; proposed): the 5.4 note card's look — inner 1 pt `danger·40%`,
/// r20, pad 16, gap 14, `ph:prohibit` 22 `danger`, `noteTitle` + `captionTight`
/// — with “Engeli kaldır” (`rowTitle` `accent300`) under the text.
struct ContactBlockedNote: View {
    let name: String
    let onUnblock: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            PhosphorIcon(.prohibit, size: 22)
                .foregroundStyle(Color.u2.danger)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("blocked.title \(name)", table: .contact)
                        .u2Font(.noteTitle)
                        .foregroundStyle(Color.u2.text)
                    Text("blocked.message", table: .contact)
                        .u2Font(.captionTight)
                        .foregroundStyle(Color.u2.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .accessibilityElement(children: .combine)
                Button(action: onUnblock) {
                    Text("block.unblock")
                }
                .buttonStyle(U2TextButtonStyle(tone: .accent, height: 36, fillsWidth: false))
                .accessibilityHint(Text("a11y.unblock.hint", table: .contact))
                .accessibilityIdentifier("contact.unblock")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(EdgeInsets(top: 16, leading: 16, bottom: 6, trailing: 16))
        .u2InnerStroke(Color.u2.dangerStrokeSoft, width: 1, cornerRadius: U2Radius.cardL)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("contact.blocked")
    }
}
