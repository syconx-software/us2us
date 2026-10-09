import SwiftUI

// The ways into the invitation, ready for the screens that offer them
// (docs/INVITES.md › Entry points): 4.2 after a search found nothing, 7.1,
// and the “Kodum” sheet. Their texts live in Invite.xcstrings.

/// 4.2 · the not-found result card's action (C1 in-card, like “Mesaj yaz”):
/// “Davet et” opens the invitation inside the 4.2 sheet.
struct InviteNotFoundButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                PhosphorIcon(.paperPlaneTilt, size: 20)
                Text("invite.entry.notFound", table: .invite)
            }
        }
        .buttonStyle(.u2PrimaryInCard)
        .accessibilityHint(Text("a11y.invite.entry", table: .invite))
        .accessibilityIdentifier("newChat.invite")
    }
}

/// 7.1 · “Arkadaşını davet et” (a C13 navigation row with `ph:paper-plane-tilt`).
struct InviteSettingsRow: View {
    let action: () -> Void

    var body: some View {
        GroupedNavigationRow(
            tile: IconTile(content: .glyph(.paperPlaneTilt)), title: Text("invite.entry.settings", table: .invite), action: action
        )
        .accessibilityHint(Text("a11y.invite.entry", table: .invite))
        .accessibilityIdentifier("settings.invite")
    }
}

/// “Kodum” sheet · “Davet bağlantını paylaş”: the invitation (text with the
/// link, the subject for Mail) straight to the system share sheet — for
/// someone who is not next to you to scan the code.
struct InviteShareButton: View {
    let username: String
    @Environment(\.locale) private var locale

    var body: some View {
        Button {
            guard let message = InviteMessage.make(username: username, locale: locale) else { return }
            InviteSender.share(message) { _ in }
        } label: {
            HStack(spacing: 8) {
                PhosphorIcon(.export, size: 20)
                Text("invite.entry.share", table: .invite)
            }
        }
        .buttonStyle(U2TextButtonStyle(tone: .accent, fillsWidth: false))
        .disabled(InviteLink.url(for: username) == nil)
        .accessibilityHint(Text("a11y.invite.more", table: .invite))
        .accessibilityIdentifier("myCode.share")
    }
}
