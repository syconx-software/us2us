import SwiftUI

// 5.3 › “Engelle” in a community (App Review 1.2): another member's message
// offers blocking its sender after a confirmation. The member stays in the
// community — the roster, the owner's actions and the keys are untouched —
// but nothing they write is shown here or in any other community, it counts
// as nothing unread, and they get no receipt (PROTOCOL §6.4). 7.1 ›
// Engellenenler lists them; “Engeli kaldır” there shows their messages again.

/// The member a community block confirmation names.
struct CommunityBlockCandidate: Identifiable, Equatable {
    /// The member's account (the messenger blocks it like a chat's).
    let id: String
    /// As the screens name them: the username, else “Üye · 1a2b3c4d”.
    let name: String
}

extension ConversationViewModel {
    /// “Engelle” is on 5.3 for what another community member wrote, unless
    /// they are blocked already.
    func canBlockSender(of message: Message) -> Bool {
        guard community != nil, !message.isOutgoing, !message.isPendingMedia, let sender = message.community?.senderID
        else { return false }
        return !messenger.blockedPeople.contains { $0.id == sender }
    }

    /// The confirmation, once 5.3 has gone.
    func askBlockSender(of id: MessageID) {
        guard let message = message(id), canBlockSender(of: message), let sender = message.community?.senderID else {
            return
        }
        let candidate = CommunityBlockCandidate(id: sender, name: senderName(of: message))
        dismissActions { [weak self] in self?.blockCandidate = candidate }
    }

    /// “Engelle” in the confirmation: blocked; their messages leave the conversation.
    func confirmBlockSender() async {
        guard let candidate = blockCandidate else { return }
        blockCandidate = nil
        guard await messenger.block(chat: candidate.id) else {
            U2Haptics.error()
            return
        }
        U2Haptics.success()
        AccessibilityNotification.Announcement(
            StringTable.contact.string("a11y.blocked \(candidate.name)", locale: presentationLocale)
        ).post()
    }
}

/// The confirmation over the community conversation (C18 floating sheet, as
/// “Herkesten sil”): “{üye} engellensin mi?” · Engelle · Vazgeç.
struct CommunityBlockConfirmation: ViewModifier {
    @Bindable var viewModel: ConversationViewModel
    @Environment(\.locale) private var locale

    func body(content: Content) -> some View {
        content
            .u2Sheet(
                isPresented: Binding(
                    get: { viewModel.blockCandidate != nil },
                    set: { if !$0 { viewModel.blockCandidate = nil } }),
                style: .floating
            ) {
                DestructiveConfirmation(
                    title: StringTable.communities.string(
                        "block.member.title \(viewModel.blockCandidate?.name ?? "")", locale: locale),
                    message: Text("block.member.message", table: .communities),
                    confirmTitle: Text("request.block"),
                    confirmGlyph: .prohibit,
                    identifiers: ("community.block.confirm", "community.block.cancel"),
                    onConfirm: { Task { await viewModel.confirmBlockSender() } },
                    onCancel: { viewModel.blockCandidate = nil }
                )
                .padding(EdgeInsets(top: 18, leading: 18, bottom: 28, trailing: 18))
            }
    }
}
