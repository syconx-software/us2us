import SwiftUI

/// 5.4 for a community message (proposed): the bubble, the state it adds up
/// to (Gönderildi → İletildi → Okundu, Okundu only while read receipts are
/// on, as in a one-to-one chat) and every recipient of the message — the
/// members it was sent to — with when it reached their phone and when they
/// read it, “Bekliyor” until then, sends that could not be made, and
/// members who have left with the last state they reached. Receipts are a
/// courtesy, not proof: the note says so.
struct CommunityMessageInfoSheet: View {
    let viewModel: ConversationViewModel
    let community: any CommunityConversationContext
    let messageID: MessageID
    @State private var recipients: [CommunityRecipientInfo]?

    var body: some View {
        ViewThatFits(in: .vertical) {
            content
            ScrollView(showsIndicators: false) { content }
        }
        // Read again whenever the message's state moves (a receipt arrived).
        .task(id: viewModel.message(messageID)?.status) {
            recipients = await community.recipients(of: messageID)
        }
    }

    private var showsRead: Bool { viewModel.messenger.readReceiptsEnabled }

    private var content: some View {
        VStack(spacing: 16) {
            SheetCloseHeader(title: Text("info.title", table: .conversation)) { viewModel.info = nil }
            if let message = viewModel.message(messageID) {
                bubble(message)
            }
            if let recipients {
                if recipients.isEmpty {
                    Text("info.recipients.none", table: .communities)
                        .u2Font(.callout)
                        .foregroundStyle(Color.u2.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 4)
                } else {
                    summary(recipients)
                    GroupedCard(fill: Color.u2.bg) {
                        ForEach(Array(CommunityRecipientRow.ordered(recipients, showsRead: showsRead).enumerated()), id: \.element.id) {
                            index, recipient in
                            if index > 0 { GroupedSeparator() }
                            CommunityRecipientRow(recipient: recipient, showsRead: showsRead)
                        }
                    }
                    .accessibilityIdentifier("community.info.recipients")
                }
            }
            NoteCard(title: Text("info.note.title", table: .communities), message: Text("info.note.body", table: .communities))
        }
        .padding(EdgeInsets(top: 16, leading: 16, bottom: 24, trailing: 16))
        .frame(maxWidth: .infinity, alignment: .top)
        .u2Animation(U2Motion.state, value: recipients)
    }

    private func bubble(_ message: Message) -> some View {
        MessageBubbleView(message: message, isTail: false)
            .allowsHitTesting(false)
            .frame(maxWidth: .infinity, alignment: .trailing)
            .padding(.top, 4)
            .padding(.bottom, 2)
    }

    /// “3 / 4 iletildi · 2 / 4 okundu” (read only while read receipts are on).
    private func summary(_ recipients: [CommunityRecipientInfo]) -> some View {
        let total = recipients.count
        let delivered = recipients.filter { $0.deliveredAt != nil }.count
        let read = recipients.filter { $0.readAt != nil }.count
        return HStack(spacing: 14) {
            Label {
                LocalizedText("info.summary.delivered \(delivered) \(total)", table: .communities)
            } icon: {
                PhosphorIcon(.checks, size: 16).foregroundStyle(Color.u2.textSecondary)
            }
            if showsRead {
                Label {
                    LocalizedText("info.summary.read \(read) \(total)", table: .communities)
                } icon: {
                    PhosphorIcon(.checks, weight: .fill, size: 16).foregroundStyle(Color.u2.accent300)
                }
            }
            Spacer(minLength: 0)
        }
        .u2Font(.captionCompact)
        .foregroundStyle(Color.u2.textSecondary)
        .padding(.horizontal, 4)
        .accessibilityElement(children: .combine)
    }
}

/// One recipient: the name (“Üye · 1a2b3c4d” without a username) and
/// where the message stands for them.
struct CommunityRecipientRow: View {
    let recipient: CommunityRecipientInfo
    let showsRead: Bool
    @Environment(\.locale) private var locale

    var body: some View {
        GroupedRow(tile: nil, title: Text(verbatim: name), subtitle: subtitle) {
            icon
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("community.info.recipient.\(recipient.id)")
    }

    /// Who read it first, then whom it reached, then who is still waiting,
    /// then the sends that could not be made — so “kimin okuduğu” reads from
    /// the top. The order within each group stays the roster's.
    static func ordered(_ recipients: [CommunityRecipientInfo], showsRead: Bool) -> [CommunityRecipientInfo] {
        func rank(_ recipient: CommunityRecipientInfo) -> Int {
            if showsRead, recipient.readAt != nil { return 0 }
            if recipient.deliveredAt != nil { return 1 }
            switch recipient.state {
            case .accepted, .pending: return 2
            case .skipped, .failed: return 3
            }
        }
        return recipients.enumerated()
            .sorted { (rank($0.element), $0.offset) < (rank($1.element), $1.offset) }
            .map(\.element)
    }

    private var name: String {
        CommunityNames.member(username: recipient.username, shortID: recipient.shortID, locale: locale)
    }

    @ViewBuilder private var icon: some View {
        if showsRead, recipient.readAt != nil {
            PhosphorIcon(.checks, weight: .fill, size: 17).foregroundStyle(Color.u2.accent300)
        } else if recipient.deliveredAt != nil {
            PhosphorIcon(.checks, size: 17).foregroundStyle(Color.u2.textSecondary)
        } else {
            switch recipient.state {
            case .accepted: PhosphorIcon(.check, size: 17).foregroundStyle(Color.u2.textSecondary)
            case .pending: PhosphorIcon(.clock, size: 17).foregroundStyle(Color.u2.textPending)
            case .skipped, .failed: PhosphorIcon(.warningCircle, size: 17).foregroundStyle(Color.u2.danger)
            }
        }
    }

    private var subtitle: Text {
        Text(verbatim: Self.detail(for: recipient, showsRead: showsRead, locale: locale))
    }

    /// “İletildi 14:02 · Okundu 14:05”, “Bekliyor”, “Ayrıldı · …”: Okundu
    /// only while read receipts are on.
    static func detail(for recipient: CommunityRecipientInfo, showsRead: Bool, locale: Locale) -> String {
        var parts: [String] = []
        let table = StringTable.communities
        if let delivered = recipient.deliveredAt {
            parts.append(table.string("info.recipient.delivered \(U2Format.clockTime(delivered, locale: locale))", locale: locale))
            if showsRead, let read = recipient.readAt {
                parts.append(table.string("info.recipient.read \(U2Format.clockTime(read, locale: locale))", locale: locale))
            }
        } else {
            switch recipient.state {
            case .pending: parts.append(table.string("info.recipient.pending", locale: locale))
            case .accepted: parts.append(table.string("info.recipient.waiting", locale: locale))
            case .skipped: parts.append(table.string("info.recipient.skipped", locale: locale))
            case .failed: parts.append(table.string("info.recipient.failed", locale: locale))
            }
        }
        if recipient.hasLeft { parts.insert(table.string("info.recipient.left", locale: locale), at: 0) }
        return parts.joined(separator: " · ")
    }
}
