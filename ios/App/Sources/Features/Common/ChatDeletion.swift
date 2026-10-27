import SwiftUI

/// “Sohbeti sil” / “Kişiyi sil” (4.1 › a chat's long press, the contact
/// screen's “…”; proposed): what is about to go, for its confirmation —
/// “{kişi} ile sohbet silinsin mi?” / “{kişi} silinsin mi?” · Sil · Vazgeç,
/// with a text that says it happens on this phone only (PROTOCOL §5.3:
/// crypto-shredded; nothing is sent).
///
/// Deleting a contact does not block it: 4.2 then finds the username as a
/// stranger, and if it writes again its message opens a new request (its
/// session and identity stay, PROTOCOL §6.4). Someone blocked stays
/// blocked; the text says where the block is lifted.
struct ChatDeletion: Identifiable, Equatable {
    enum Kind: Equatable {
        /// The chat's messages and media; the person stays a contact.
        case chat
        /// The chat and the contact itself.
        case contact
    }

    let chat: ChatID
    /// The name the chat shows.
    let name: String
    let kind: Kind
    let isBlocked: Bool

    var id: String { "\(kind).\(chat)" }

    init(_ kind: Kind, chat: ChatID, peer: Peer?) {
        self.kind = kind
        self.chat = chat
        name = peer?.username ?? chat
        isBlocked = peer?.isBlocked ?? false
    }

    var title: Text {
        switch kind {
        case .chat: Text("delete.chat.title \(name)")
        case .contact: Text("delete.contact.title \(name)")
        }
    }

    var message: Text {
        switch kind {
        case .chat: Text("delete.chat.message")
        case .contact: isBlocked ? Text("delete.contact.message.blocked") : Text("delete.contact.message")
        }
    }

    /// After the confirmation: deleted on this phone, with the haptic and
    /// the announcement. `false` when nothing was deleted (locked meanwhile).
    @MainActor
    func perform(in messenger: any MessengerService, locale: Locale) async -> Bool {
        let deleted =
            switch kind {
            case .chat: await messenger.deleteChat(chat)
            case .contact: await messenger.deleteContact(chat)
            }
        guard deleted else { return false }
        U2Haptics.success()
        let announcement: String.LocalizationValue = kind == .chat ? "a11y.chatDeleted" : "a11y.contactDeleted"
        AccessibilityNotification.Announcement(L10n.string(announcement, locale: locale)).post()
        return true
    }
}

extension View {
    /// The confirmation of a `ChatDeletion` (the system alert, as the block's).
    func chatDeletionAlert(
        _ deletion: Binding<ChatDeletion?>, messenger: any MessengerService, locale: Locale,
        onDeleted: @escaping @MainActor () -> Void = {}
    ) -> some View {
        modifier(ChatDeletionAlert(deletion: deletion, messenger: messenger, locale: locale, onDeleted: onDeleted))
    }
}

private struct ChatDeletionAlert: ViewModifier {
    @Binding var deletion: ChatDeletion?
    let messenger: any MessengerService
    let locale: Locale
    let onDeleted: @MainActor () -> Void
    /// The one on screen; kept while the alert fades out.
    @State private var shown: ChatDeletion?

    func body(content: Content) -> some View {
        content
            .onChange(of: deletion, initial: true) { _, pending in
                if let pending { shown = pending }
            }
            .alert(
                (deletion ?? shown)?.title ?? Text(verbatim: ""),
                isPresented: Binding(get: { deletion != nil }, set: { if !$0 { deletion = nil } })
            ) {
                Button(role: .destructive) {
                    confirm()
                } label: {
                    Text("request.delete")
                }
                Button(role: .cancel) {} label: { Text("common.cancel") }
            } message: {
                (deletion ?? shown)?.message ?? Text(verbatim: "")
            }
    }

    private func confirm() {
        guard let pending = deletion ?? shown else { return }
        let messenger = messenger
        let locale = locale
        let onDeleted = onDeleted
        Task { @MainActor in
            if await pending.perform(in: messenger, locale: locale) { onDeleted() }
        }
    }
}
