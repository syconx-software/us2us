import SwiftUI

// 5.3 › “Bildir” (App Review 1.2): a message someone else sent — in a chat
// or a community — opens the report sheet about its sender, with “Bu
// mesajın metnini bildirime ekle” (on by default) and, unless the sender is
// blocked already, “Bu kişiyi de engelle”.

extension ConversationViewModel {
    /// “Bildir” is on 5.3 for what someone else wrote (not a deleted
    /// message, nor a picked photo still being prepared).
    func canReport(_ message: Message) -> Bool {
        !message.isOutgoing && !message.isPendingMedia && ReportedMessage(message) != nil
    }

    /// The report sheet about the message's sender, once 5.3 has gone.
    func openReport(for id: MessageID) {
        guard let message = message(id), canReport(message), let report = makeReport(for: message) else { return }
        dismissActions { [weak self] in self?.report = report }
    }

    /// Who the report names and whom “Bu kişiyi de engelle” blocks: the
    /// chat's person, or in a community the member who wrote the message
    /// (by the username this phone knows — never the account id).
    func makeReport(for message: Message) -> ReportModel? {
        let reported = ReportedMessage(message)
        let messenger = messenger
        let model: ReportModel
        if community != nil {
            guard let marks = message.community else { return nil }
            let sender = marks.senderID
            let username = marks.sender?.username
            let subject = ReportSubject(username: username, isUsernameVerified: username != nil)
            let blocked = messenger.blockedPeople.contains { $0.id == sender }
            model = ReportModel(
                subject: subject, personName: senderName(of: message), offersBlock: !blocked, message: reported,
                mailer: reportMailer
            ) {
                await messenger.block(chat: sender)
            }
        } else {
            let chat = chatID
            let subject = messenger.reportSubject(for: chat) ?? ReportSubject(username: nil, isUsernameVerified: false)
            model = ReportModel(
                subject: subject, personName: peerUsername, offersBlock: !isBlocked, message: reported,
                mailer: reportMailer
            ) {
                await messenger.block(chat: chat)
            }
        }
        model.onClose = { [weak self] _ in self?.report = nil }
        return model
    }
}

/// The report sheet over the conversation.
struct ConversationReportSheet: ViewModifier {
    @Bindable var viewModel: ConversationViewModel
    @Environment(\.locale) private var locale

    func body(content: Content) -> some View {
        content
            .u2Sheet(item: $viewModel.report) { report in
                ReportSheet(model: report)
                    .environment(\.locale, locale)
            }
    }
}
