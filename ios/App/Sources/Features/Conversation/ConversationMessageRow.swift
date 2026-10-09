import SwiftUI

/// A row of the message column: the bubble aligned to its side, the status
/// sentence under the newest outgoing one, the long-press that opens 5.3.
///
/// Equatable on its data, so a list update re-renders only the rows whose
/// message (or tail, sentence, lift) changed — never the ones that merely
/// stay on screen. The bubble reports its frame (for the 5.3 lift) only while
/// a finger rests on it or the harness asks: no geometry work per row while
/// the conversation scrolls.
struct ConversationMessageRow: View, Equatable {
    let row: ConversationRow
    /// Hidden while its copy is lifted into the 5.3 menu.
    let isLifted: Bool
    /// The harness wants this bubble's frame without a long-press.
    let capturesFrame: Bool
    /// The message before its kind changed: the bubble morphs from it.
    var replacing: Message? = nil
    /// The conversation the row acts on; `nil` for an inert row (4.4).
    let viewModel: ConversationViewModel?

    @State private var isPressing = false
    /// The status sentence's presence, 0…1, animated: it stays in the
    /// layout while it collapses (a removal transition would drop its height
    /// in one frame and the bottom-anchored column would jump).
    @State private var sentenceProgress: Double
    /// Kept in the hierarchy until its collapse has finished.
    @State private var sentenceInLayout: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    nonisolated static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.row == rhs.row && lhs.isLifted == rhs.isLifted && lhs.capturesFrame == rhs.capturesFrame
            && lhs.replacing == rhs.replacing && lhs.viewModel === rhs.viewModel
    }

    init(row: ConversationRow, isLifted: Bool, capturesFrame: Bool, replacing: Message? = nil, viewModel: ConversationViewModel?) {
        self.row = row
        self.isLifted = isLifted
        self.capturesFrame = capturesFrame
        self.replacing = replacing
        self.viewModel = viewModel
        _sentenceProgress = State(initialValue: row.showsStatusSentence ? 1 : 0)
        _sentenceInLayout = State(initialValue: row.showsStatusSentence)
    }

    /// 4.4 › “Kabul et”: the row exactly as 5.1 will show it, inert.
    init(message: Message, isTail: Bool, showsStatusSentence: Bool, isLifted: Bool, frames _: BubbleFrameStore) {
        self.init(
            row: ConversationRow(message: message, isTail: isTail, showsStatusSentence: showsStatusSentence), isLifted: isLifted,
            capturesFrame: false, viewModel: nil)
    }

    private var message: Message { row.message }

    var body: some View {
        // Spacing 0: the sentence carries its 6 pt gap, so it collapses too.
        VStack(spacing: 0) {
            if message.isSystem {
                MessageBubbleView(message: message)
            } else {
                bubble
                    .frame(maxWidth: .infinity, alignment: message.isOutgoing ? .trailing : .leading)
            }
            if let status = message.status {
                if reduceMotion {
                    if row.showsStatusSentence {
                        statusSentence(status)
                            .padding(.top, U2Spacing.bubbleGap)
                            .transition(.opacity)
                    }
                } else if row.showsStatusSentence || sentenceInLayout {
                    statusSentence(status)
                        .padding(.top, U2Spacing.bubbleGap)
                        .u2CollapseProgress(sentenceProgress, cornerRadius: 0)
                }
            }
        }
        .onChange(of: row.showsStatusSentence) { _, shows in
            if shows { sentenceInLayout = true }
            withAnimation(U2Motion.resolve(U2Motion.state, reduceMotion: reduceMotion), completionCriteria: .removed) {
                sentenceProgress = shows ? 1 : 0
            } completion: {
                if !row.showsStatusSentence, sentenceProgress == 0 { sentenceInLayout = false }
            }
        }
        .u2Animation(U2Motion.state, value: row.showsStatusSentence)
    }

    private var bubble: some View {
        let id = message.id
        let viewModel = viewModel
        return MessageBubbleView(
            message: message, isTail: row.isTail, replacing: replacing,
            onReplaced: { viewModel?.finishKindChange(id) },
            onStopLiveLocation: { viewModel?.stopLiveLocation(id) },
            onOpenViewOnce: { if let viewModel, let message = viewModel.message(id) { viewModel.openViewOnce(message) } },
            onOpenMedia: { if let viewModel, let message = viewModel.message(id) { viewModel.openMedia(message) } }
        )
        .background {
            if let frames = viewModel?.frames, isPressing || capturesFrame {
                BubbleFrameReporter(id: id, store: frames)
            }
        }
        // The bubble rises a touch while the finger rests on it (C15 lift, 5.3).
        .scaleEffect(isPressing && !reduceMotion ? 1.02 : 1, anchor: message.isOutgoing ? .trailing : .leading)
        .u2Animation(U2Motion.soft(0.3), value: isPressing)
        .opacity(isLifted ? 0 : 1)
        .onLongPressGesture(minimumDuration: 0.4, maximumDistance: 12) {
            isPressing = false
            longPress()
        } onPressingChanged: { pressing in
            isPressing = pressing
        }
        .accessibilityAction(named: Text("a11y.message.actions", table: .conversation), longPress)
        .accessibilityIdentifier("message.\(id)")
    }

    /// The sentence under the bubble. A state change cross-fades in place
    /// (`StateCrossFade`). Coming or going (it moves to the newest message),
    /// its height grows or collapses frame by frame while it fades — the
    /// bottom-anchored column glides instead of jumping. Reduce Motion: it
    /// only fades; the column then settles at once.
    private func statusSentence(_ status: MessageStatus) -> some View {
        let id = message.id
        let viewModel = viewModel
        let resend: () -> Void = { viewModel?.resend(id) }
        return StateCrossFade(value: status, alignment: .trailing) { status in
            MessageStatusSentence(status: status, onResend: status == .failed ? resend : nil)
        }
        .allowsHitTesting(row.showsStatusSentence)
    }

    private func longPress() {
        viewModel?.openActions(for: message, isTail: row.isTail)
    }
}

/// Writes the bubble's on-screen frame into the store while it is attached.
private struct BubbleFrameReporter: View {
    let id: MessageID
    let store: BubbleFrameStore

    var body: some View {
        Color.clear
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { store.set($0, for: id) }
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}
