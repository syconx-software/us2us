import SwiftUI

/// 4.1 chat row: min height 76, pad 0 18 0 16, gap 13 — avatar 52 (with the
/// online dot for accepted contacts) · line 1: username (Mono 500 15, one
/// line) + verified seal 14 + time (`caption`, tabular; `accent300` when
/// unread) · line 2 (min 22, gap 6): status glyph, type glyph, preview
/// (`preview` 14.5, one line; `text` when unread, `danger` when failed) and
/// the Ghost pill or the unread badge. Someone blocked: “Engellendi” with
/// `ph:prohibit` in the preview's place (proposed).
///
/// Changes animate in place, never by rebuilding the row: the preview
/// cross-fades, the time and the counters roll (`numericText`), the status
/// glyph cross-fades — all on `U2Motion.state` (0.36 s), short cross-fades
/// under Reduce Motion.
struct ChatListRow: View {
    let chat: ChatSummary
    @Environment(\.locale) private var locale
    @Environment(\.u2Clock) private var clock
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var isUnread: Bool { chat.unreadCount > 0 }
    private var isFailed: Bool { chat.lastMessage?.status == .failed }

    var body: some View {
        HStack(spacing: U2Spacing.rowGap) {
            if chat.community != nil {
                CommunityAvatar(diameter: Avatar.Size.listRow.diameter)
            } else {
                Avatar(
                    username: chat.peer.username, palette: chat.peer.palette, size: .listRow,
                    showsOnlineDot: chat.peer.isOnline, picture: chat.peer.avatar)
            }
            VStack(alignment: .leading, spacing: 4) {
                firstLine
                secondLine
            }
        }
        .padding(.leading, U2Spacing.listRowInsetLeading)
        .padding(.trailing, U2Spacing.listRowInsetTrailing)
        .frame(minHeight: 76)
        .contentShape(Rectangle())
        // A new message, a counter or a status changes inside the row: the
        // changes animate where they are (the list moves the row itself).
        .u2Animation(U2Motion.state, value: chat.lastMessage)
        .u2Animation(U2Motion.state, value: chat.unreadCount)
        .u2Animation(U2Motion.state, value: chat.peer.isBlocked)
        .accessibilityElement(children: .combine)
    }

    private var firstLine: some View {
        HStack(spacing: 6) {
            if chat.community != nil {
                // A community's name is free text, not a username.
                Text(verbatim: CommunityNames.title(chat.peer.username, locale: locale))
                    .u2Font(.rowTitleEmphasis)
                    .foregroundStyle(Color.u2.text)
                    .lineLimit(1)
                    .truncationMode(.tail)
            } else {
                Text(verbatim: chat.peer.username)
                    .u2Font(.monoUsername)
                    .foregroundStyle(Color.u2.text)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            if chat.peer.isVerified {
                PhosphorIcon(.sealCheck, weight: .fill, size: 14, label: Text("a11y.verified"))
                    .foregroundStyle(Color.u2.accent)
            }
            Spacer(minLength: 0)
            if let last = chat.lastMessage {
                Text(verbatim: U2Format.chatListTime(last.sentAt, now: clock.now, locale: locale))
                    .u2Font(.caption)
                    .foregroundStyle(isUnread ? Color.u2.accent300 : Color.u2.textSecondary)
                    .lineLimit(1)
                    .fixedSize()
                    .contentTransition(reduceMotion ? .opacity : .numericText())
            }
        }
    }

    private var secondLine: some View {
        HStack(spacing: 6) {
            if chat.peer.isBlocked {
                // Blocked (proposed): the state in the preview's place, as a
                // deleted message's line (`ph:prohibit` + `preview`, `text·68%`).
                PhosphorIcon(.prohibit, size: 15).foregroundStyle(Color.u2.textSecondary)
                Text("block.blocked")
                    .u2Font(.preview)
                    .foregroundStyle(Color.u2.textSecondary)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .transition(.opacity)
            } else if let last = chat.lastMessage {
                if last.direction == .outgoing, let status = last.status {
                    MessageStatusIcon(status: status, size: 15)
                }
                if let glyph = typeGlyph(last.kind), !isFailed {
                    PhosphorIcon(glyph, size: 15).foregroundStyle(Color.u2.textSecondary)
                }
                previewText(last)
                    .u2Font(.preview)
                    .foregroundStyle(isFailed ? Color.u2.danger : isUnread ? Color.u2.text : Color.u2.textSecondary)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .contentTransition(.opacity)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                Spacer(minLength: 0)
            }
            if isUnread {
                CountBadge(count: chat.unreadCount)
                    .transition(.opacity)
            } else if chat.ghost.isEnabled {
                GhostPill(ghost: chat.ghost)
                    .transition(.opacity)
            }
        }
        .frame(minHeight: 22)
    }

    private func typeGlyph(_ kind: MessagePreviewKind) -> PhosphorGlyph? {
        switch kind {
        case .disappearing: .timer
        case .sticker: .sticker
        case .liveLocation, .liveLocationEnded, .location: .navigationArrow
        case .deleted, .communityDeleted: .prohibit
        case .text, .photo, .photoViewOnce, .video, .voice, .file: nil
        }
    }

    private func previewText(_ last: MessagePreview) -> Text {
        if isFailed { return Text("chats.preview.failed") }
        let body = previewBody(last)
        // A community names who wrote it (“deniz: Merhaba”).
        guard let sender = last.sender else { return body }
        let name = CommunityNames.member(username: sender.username, shortID: sender.shortID, locale: locale)
        return Text(verbatim: name + ": ") + body
    }

    private func previewBody(_ last: MessagePreview) -> Text {
        switch last.kind {
        case .text(let text): return Text(verbatim: text)
        case .disappearing: return Text("chats.preview.disappearing")
        case .sticker: return Text("chats.preview.sticker")
        case .photo: return Text("media.photo")
        case .photoViewOnce: return Text("chats.preview.photoViewOnce")
        case .video: return Text("media.video")
        case .voice: return Text("media.voice")
        case .file(let name): return Text(verbatim: name)
        case .location: return Text("media.location")
        case .liveLocation: return Text("location.live")
        case .liveLocationEnded: return Text("chats.preview.liveLocationEnded")
        case .deleted: return Text("message.deleted")
        case .communityDeleted(let byOwner):
            return byOwner ? Text("message.removedByOwner", table: .communities) : Text("message.deleted", table: .communities)
        }
    }
}

/// 4.1 requests card: `surface` r18, pad 13 14, gap 13 — tray tile 42
/// (circle `accent900`, ring `accent800`, `ph:tray` 21) · “Mesaj istekleri” +
/// “{n} kişi seninle yazışmak istiyor” · count badge · chevron.
struct RequestsCard: View {
    let count: Int
    var communityName: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 13) {
                PhosphorIcon(.tray, size: 21)
                    .foregroundStyle(Color.u2.accent300)
                    .frame(width: 42, height: 42)
                    .background(Circle().fill(Color.u2.accent900))
                    .overlay(Circle().strokeBorder(Color.u2.accent800, lineWidth: 1))
                VStack(alignment: .leading, spacing: 2) {
                    Group {
                        if let communityName { Text(verbatim: communityName) }
                        else { Text("chats.requests.title") }
                    }
                        .u2Font(.rowTitleEmphasis)
                        .foregroundStyle(Color.u2.text)
                    Group {
                        if communityName != nil { Text("chats.requests.count \(count)", table: .communities) }
                        else { Text("chats.requests.subtitle \(count)") }
                    }
                        .u2Font(.caption)
                        .foregroundStyle(Color.u2.textSecondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                CountBadge(count: count)
                PhosphorIcon(.caretRight, size: 15)
                    .foregroundStyle(Color.u2.textTertiary)
            }
            .padding(EdgeInsets(top: 13, leading: 14, bottom: 13, trailing: 14))
            .background(RoundedRectangle(cornerRadius: U2Radius.card, style: .continuous).fill(Color.u2.surface))
            .contentShape(RoundedRectangle(cornerRadius: U2Radius.card, style: .continuous))
        }
        .buttonStyle(CardButtonStyle())
        .accessibilityElement(children: .combine)
    }
}

/// The connection line + caption under the chat-list title (4.1): the C20
/// mark (60 × 9) with flowing packets while the line is open, gap 10,
/// “Hat açık · mesajlar geliyor” (`monoMeta`, `text·68%`).
struct ConnectionStatusLine: View {
    let state: ConnectionState

    var body: some View {
        HStack(spacing: 10) {
            LineMark(metrics: .connection, isFlowing: state == .open)
            Group {
                switch state {
                case .open: Text("chats.line.open")
                case .connecting: Text("chats.line.connecting")
                case .closed: Text("chats.line.closed")
                case .revoked: Text("chats.line.revoked")
                }
            }
            .u2Font(.monoMeta)
            .foregroundStyle(Color.u2.textSecondary)
            .lineLimit(1)
            .contentTransition(.opacity)
        }
        .u2Animation(U2Motion.state, value: state)
        .accessibilityElement(children: .combine)
    }
}

#Preview("Chat list rows — 4.1") {
    let chats = DesignFixtures.chats(now: DesignFixtures.referenceDate, ghostUrgent: false, english: false)
    return ScrollView {
        VStack(alignment: .leading, spacing: 0) {
            ConnectionStatusLine(state: .open).padding(20)
            RequestsCard(count: 2) {}.padding(.horizontal, 16)
            ForEach(Array(chats.enumerated()), id: \.element.id) { index, chat in
                if index > 0 { DottedRule().padding(.leading, U2Spacing.listSeparatorInset) }
                ChatListRow(chat: chat)
            }
        }
    }
    .background(Color.u2.bg)
    .environment(\.locale, Locale(identifier: "tr"))
    .environment(\.u2Clock, .frozen(at: DesignFixtures.referenceDate))
}
