import SwiftUI

/// 5.4 · Mesaj bilgisi — the message's journey, step by step (there are no
/// notifications, so this is where “with you / on its way / with them” is
/// spelled out). Full-height sheet (C18, from design y 58), pad 8 16 0, gap 16:
/// centred title + close (36), the message bubble right-aligned, the timeline
/// card (`bg` r20, pad 18 16) and the “İletilemedi ne demek?” note.
struct MessageInfoSheet: View {
    let viewModel: ConversationViewModel
    let messageID: MessageID

    var body: some View {
        ViewThatFits(in: .vertical) {
            content
            ScrollView(showsIndicators: false) { content }
        }
    }

    private var content: some View {
        VStack(spacing: 16) {
            SheetCloseHeader(title: Text("info.title", table: .conversation)) { viewModel.info = nil }
            if let message = viewModel.message(messageID) {
                bubble(message)
                InfoTimeline(
                    steps: InfoStep.steps(for: message, showsRead: viewModel.messenger.readReceiptsEnabled),
                    peer: viewModel.peerUsername)
            }
            NoteCard(title: Text("info.note.title", table: .conversation), message: Text("info.note.body", table: .conversation))
        }
        .padding(EdgeInsets(top: 16, leading: 16, bottom: 24, trailing: 16))
        .frame(maxWidth: .infinity, alignment: .top)
    }

    /// The bubble as a plain, round bubble (the design drops the reply quote and reactions here).
    private func bubble(_ message: Message) -> some View {
        var plain = message
        plain.replyTo = nil
        plain.reactions = []
        return MessageBubbleView(message: plain, isTail: false)
            .allowsHitTesting(false)
            .frame(maxWidth: .infinity, alignment: message.isOutgoing ? .trailing : .leading)
            .padding(.top, 4)
            .padding(.bottom, 2)
    }
}

/// One step of the journey.
struct InfoStep: Identifiable, Equatable {
    enum Kind: Equatable {
        case sent, delivered, read, failed
    }

    let kind: Kind
    let isReached: Bool
    let time: Date?

    var id: Kind { kind }

    /// Gönderildi → İletildi → Okundu; a failed message ends in İletilemedi.
    /// “Okundu” is hidden when read receipts are off (they are mutual).
    static func steps(for message: Message, showsRead: Bool) -> [InfoStep] {
        let status = message.status ?? .sending
        let sent = InfoStep(kind: .sent, isReached: status != .sending, time: status == .sending ? nil : message.sentAt)
        if status == .failed {
            return [sent, InfoStep(kind: .failed, isReached: true, time: message.failedAt)]
        }
        let delivered = status == .delivered || status == .read
        var steps = [sent, InfoStep(kind: .delivered, isReached: delivered, time: delivered ? message.deliveredAt : nil)]
        if showsRead {
            steps.append(InfoStep(kind: .read, isReached: status == .read, time: status == .read ? message.readAt : nil))
        }
        return steps
    }
}

/// The timeline card: rows on a [34 | 1fr | auto] grid (gap 14) — node,
/// title + description, time — joined by a vertical dotted connector.
private struct InfoTimeline: View {
    let steps: [InfoStep]
    let peer: String

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(steps.enumerated()), id: \.element.id) { index, step in
                let isLast = index == steps.count - 1
                InfoTimelineRow(
                    step: step, peer: peer, isLast: isLast,
                    nextIsReached: isLast ? false : steps[index + 1].isReached)
            }
        }
        .padding(EdgeInsets(top: 18, leading: 16, bottom: 18, trailing: 16))
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: U2Radius.cardL, style: .continuous).fill(Color.u2.bg))
    }
}

private struct InfoTimelineRow: View {
    let step: InfoStep
    let peer: String
    let isLast: Bool
    let nextIsReached: Bool
    @Environment(\.locale) private var locale

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            node
            VStack(alignment: .leading, spacing: 2) {
                title
                    .u2Font(.rowTitleEmphasis)
                    .foregroundStyle(step.isReached ? Color.u2.text : Color.u2.textPending)
                detail
                    .u2Font(.captionTighter)
                    .foregroundStyle(step.isReached ? Color.u2.textSecondary : Color.u2.textPending)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.top, 6)
            .padding(.bottom, isLast ? 0 : 16)
            .frame(maxWidth: .infinity, alignment: .leading)
            Group {
                if let time = step.time, step.isReached {
                    Text(verbatim: U2Format.clockTime(time, locale: locale))
                        .u2Font(.monoMeta)
                        .foregroundStyle(Color.u2.textSecondary)
                        .transition(.u2Fade)
                }
            }
            .padding(.top, 8)
        }
        .frame(minHeight: isLast ? 34 : 56, alignment: .top)
        .overlay(alignment: .topLeading) {
            if !isLast { connector }
        }
        .u2Animation(U2Motion.soft(0.5), value: step.isReached)
        .u2Animation(U2Motion.soft(0.5), value: nextIsReached)
        .accessibilityElement(children: .combine)
    }

    /// 34 pt `surface` circle with an inner 1 pt `divider` and the C16 glyph.
    private var node: some View {
        ZStack {
            Circle().fill(Color.u2.surface)
            Circle().strokeBorder(Color.u2.divider, lineWidth: 1)
            glyph
                .id(step.isReached)
                .transition(.opacity)
        }
        .frame(width: 34, height: 34)
    }

    @ViewBuilder private var glyph: some View {
        let pending = Color.u2.textPending
        switch step.kind {
        case .sent:
            PhosphorIcon(.check, size: 17).foregroundStyle(step.isReached ? Color.u2.textSecondary : pending)
        case .delivered:
            PhosphorIcon(.checks, size: 17).foregroundStyle(step.isReached ? Color.u2.textSecondary : pending)
        case .read:
            PhosphorIcon(.checks, weight: .fill, size: 17).foregroundStyle(step.isReached ? Color.u2.accent300 : pending)
        case .failed:
            PhosphorIcon(.warningCircle, size: 17).foregroundStyle(Color.u2.danger)
        }
    }

    /// The dotted connector under the node to the next row (`text·32%`); it
    /// fills from the top when the next step is reached (0.5 s).
    private var connector: some View {
        ZStack(alignment: .top) {
            DottedRule(color: Color.u2.dotRuleTimeline, pattern: .timeline, axis: .vertical)
                .opacity(0.4)
            DottedRule(color: Color.u2.dotRuleTimeline, pattern: .timeline, axis: .vertical)
                .mask(alignment: .top) {
                    Rectangle().scaleEffect(x: 1, y: nextIsReached ? 1 : 0, anchor: .top)
                }
        }
        .frame(width: 34)
        .padding(.top, 34)
        .accessibilityHidden(true)
    }

    private var title: Text {
        switch step.kind {
        case .sent: Text(MessageStatus.sent.titleKey)
        case .delivered: Text(MessageStatus.delivered.titleKey)
        case .read: Text(MessageStatus.read.titleKey)
        case .failed: Text(MessageStatus.failed.titleKey)
        }
    }

    private var detail: Text {
        switch step.kind {
        case .sent: Text("info.sent.detail", table: .conversation)
        case .delivered: Text("info.delivered.detail \(peer)", table: .conversation)
        case .read: Text("info.read.detail \(peer)", table: .conversation)
        case .failed: Text("info.failed.detail", table: .conversation)
        }
    }
}
