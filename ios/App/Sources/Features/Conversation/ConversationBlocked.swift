import SwiftUI

// 5.1 · the chat of someone the user blocked (proposed, in 4.4's language):
// the history stays, read only. The composer's place goes to a bar like
// 4.4's decision bar with “Engeli kaldır”; the header says “Engellendi”
// where the presence line would be, the Ghost chip is shown but cannot be
// changed, and 5.3 offers only what stays on this phone (no reaction, no
// reply, no “Herkesten sil”): nothing reaches a blocked person (PROTOCOL §6.4).

extension ConversationViewModel {
    /// The person is blocked: nothing can be sent, reacted or changed here.
    var isBlocked: Bool { chat?.peer.isBlocked ?? false }

    /// “Engeli kaldır” in the bar. A blocked request becomes a request
    /// again and leaves 4.1 (the screen then pops, as for any chat that goes).
    func unblock() {
        U2Haptics.selection()
        Task { await messenger.unblock(chat: chatID) }
    }

    /// The composer went (the person was blocked while the chat was open):
    /// the keyboard, the “+” menu, the sticker picker, a reply quote and a
    /// recording in progress go with it. A voice message that plays goes on.
    func leaveComposer() {
        U2Keyboard.dismiss()
        dismissAttachMenu()
        showsStickerPicker = false
        replyTo = nil
        voice.discardAll()
    }
}

/// The bar in the composer's place (4.4's decision bar: `surface`, top
/// radius 30, pad 18 16 40, gap 14): the sentence in `callout` `text·68%`,
/// centred, over “Engeli kaldır” (50 high, r14, inner 1 pt `divider`).
struct BlockedChatBar: View {
    let username: String
    let onUnblock: () -> Void
    /// From the physical bottom (the design's 40, as 4.4's bar).
    let bottomPadding: CGFloat

    var body: some View {
        VStack(spacing: 14) {
            Text("blocked.bar \(username)", table: .conversation)
                .u2Font(.callout)
                .foregroundStyle(Color.u2.textSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 10)
            Button(action: onUnblock) {
                Text("block.unblock")
            }
            .buttonStyle(U2DecisionButtonStyle(kind: .neutral, size: .bar))
            .accessibilityHint(Text("blocked.unblock.hint", table: .conversation))
            .accessibilityIdentifier("conversation.unblock")
        }
        .padding(EdgeInsets(top: 18, leading: 16, bottom: bottomPadding, trailing: 16))
        .frame(maxWidth: .infinity)
        .background(
            UnevenRoundedRectangle(topLeadingRadius: U2Radius.sheetBar, topTrailingRadius: U2Radius.sheetBar, style: .continuous)
                .fill(Color.u2.surface)
                .ignoresSafeArea(edges: .bottom)
        )
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("conversation.blocked")
    }
}

#Preview("5.1 Engellendi") {
    VStack {
        Spacer()
        BlockedChatBar(username: "deniz", onUnblock: {}, bottomPadding: 40)
    }
    .background(Color.u2.bg)
    .environment(\.locale, Locale(identifier: "tr"))
}
