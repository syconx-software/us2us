import SwiftUI

/// A community's conversation: the one-to-one conversation screen (05) —
/// its bubbles, composer and “+” (photos, videos, files), photo viewer,
/// player, file preview, long-press menu and “Bilgi” — on the community
/// (`CommunityChatMessenger`). Incoming bubbles carry their sender's name;
/// the header is 4.3's centred title (back, the name, “…” for members and
/// management). The order is the order this phone received the messages;
/// the sender's clock only labels them.
struct CommunityConversationView: View {
    let communityID: CommunityID
    @Environment(AppModel.self) private var model

    var body: some View {
        ConversationScreen(
            chatID: communityID, messenger: CommunityChatMessenger(base: model.messenger, communityID: communityID),
            saveNotice: model.preferences)
        .privacySensitive()
    }
}

/// The community conversation's header: back, the community's name
/// (centred), “…” → members and management. The name stays while the
/// screen pops after leaving or deleting (the row goes first).
struct CommunityConversationHeader: View {
    let context: any CommunityConversationContext
    let onBack: () -> Void
    let onManage: () -> Void
    @Environment(\.locale) private var locale
    /// The last name shown, kept while the screen pops after a leave or delete.
    @State private var knownName = ""

    var body: some View {
        let row = context.row
        ZStack(alignment: .top) {
            ChatsHeaderBackdrop(isVisible: true)
            CenteredTitleHeader(Text(verbatim: CommunityNames.title(row?.name, known: knownName, locale: locale))) {
                U2GlassIconButton(.caretLeft, label: Text("a11y.back"), action: onBack)
                    .accessibilityIdentifier("community.back")
            } trailing: {
                U2GlassIconButton(.dotsThree, label: Text("a11y.manage", table: .communities), action: onManage)
                    .accessibilityIdentifier("community.manage")
                    .disabled(row?.isOpenable != true)
            }
        }
        .onChange(of: row?.name, initial: true) { _, name in knownName = CommunityNames.remembered(name, known: knownName) }
    }
}
