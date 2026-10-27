import SwiftUI

/// 5.1 · the message column: bottom-aligned bubbles (gap 6, pad 0 14 100)
/// that scroll under the translucent header and the composer.
///
/// * **Any length.** The history (everything there when the conversation
///   opened, and older messages loading later) is a lazy stack — it costs
///   only the rows on screen; the live end (what arrived since) is laid out
///   exactly, so a new row never passes through an estimated height. Rows are
///   equatable: an update re-renders only the rows that changed.
/// * **Anchored at the bottom.** It opens at the newest message with no
///   visible jump; at the bottom it follows arrivals; scrolled up, what is on
///   screen stays put — when rows above are measured, when messages arrive
///   below, and when older messages load above (the scroll position keeps the
///   row in the middle of the screen in place).
/// * **Motion.** A new row's height grows in frame by frame while it rises
///   from behind the composer (sent: on the critically damped spring, from
///   94 % at its tail corner; received: the design's 0.36 s ease-out), and
///   the bottom anchor carries the column with it in the same motion; a
///   removed row, a moving status sentence and the typing bubble collapse the
///   same way; reactions grow in softly; status changes cross-fade; a change
///   of kind (“herkesten silindi”) morphs. Reduce Motion: short cross-fades,
///   the column settles at once.
struct ConversationMessageList<Footer: View>: View {
    let viewModel: ConversationViewModel
    let footer: (Bool, @escaping () -> Void) -> Footer

    init(viewModel: ConversationViewModel, @ViewBuilder footer: @escaping (Bool, @escaping () -> Void) -> Footer) {
        self.viewModel = viewModel
        self.footer = footer
    }

    /// The history row in the middle of the screen while the user reads
    /// further up (nil near the bottom). Kept in this outer view: it changes
    /// while scrolling and must not re-render the column.
    @State private var historyPosition: MessageID?
    @State private var isNearBottom = true
    /// iOS 17 only (no scroll-anchor roles): the viewport, so a short chat
    /// still sits at the bottom.
    @State private var viewportHeight: CGFloat = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let last = viewModel.rows.last
        return ScrollViewReader { proxy in
            ScrollView {
                ConversationColumn(viewModel: viewModel, viewportHeight: viewportHeight) {
                    guard !isNearBottom else { return }
                    Task { await viewModel.loadOlderMessages() }
                }
                .background { ConversationKeyboardDismissal() }
            }
            .modifier(BottomAnchoring(isNearBottom: $isNearBottom, viewportHeight: $viewportHeight))
            .coordinateSpace(name: ConversationListAnchor.viewport)
            .scrollPosition(id: $historyPosition, anchor: .center)
            .scrollDismissesKeyboard(.interactively)
            .accessibilityIdentifier("conversation.list")
            .safeAreaInset(edge: .bottom, spacing: 0) {
                footer(!isNearBottom && !viewModel.rows.isEmpty) {
                    historyPosition = nil
                    withAnimation(reduceMotion ? nil : U2Motion.out(0.2)) {
                        proxy.scrollTo(ConversationListAnchor.bottom, anchor: .bottom)
                    }
                }
            }
            .onChange(of: historyPosition) { _, id in
                guard !isNearBottom, let id, viewModel.rows.prefix(10).contains(where: { $0.id == id }) else { return }
                Task { await viewModel.loadOlderMessages() }
            }
            .onChange(of: isNearBottom) { _, near in
                // At the bottom the column follows arrivals (bottom anchor),
                // not a row in the history.
                if near { historyPosition = nil }
            }
            // Near the bottom an arrival needs no scrolling: the new row's
            // height grows in and the bottom anchor carries the column up
            // with it, frame by frame. What the user sends (or changes, like
            // a Ghost setting) also comes into view from far up; incoming
            // messages never yank someone reading further up.
            .onChange(of: last?.id) { old, new in
                guard old != nil, new != nil, let last, last.message.isOutgoing || last.message.isSystem else { return }
                guard !(BottomAnchoring.tracksPosition && isNearBottom) else { return }
                withAnimation(reduceMotion ? U2Motion.reducedCrossFade : ConversationMotion.send) {
                    proxy.scrollTo(ConversationListAnchor.bottom, anchor: .bottom)
                }
            }
            #if DEBUG
                .task {
                    guard let target = ConversationHarness.scrollTarget else { return }
                    try? await Task.sleep(for: .milliseconds(400))
                    historyPosition = target
                }
            #endif
        }
    }
}

private enum ConversationListAnchor {
    static let bottom = "conversation.bottom"
    static let typing = "conversation.typing"
    static let viewport = "conversation.viewport"
}

private struct ConversationBottomPosition: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

/// Backport the bottom-distance observation for iOS 17 without measuring message rows.
private struct LegacyBottomPosition: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content
        } else {
            content.background {
                GeometryReader { geometry in
                    Color.clear.preference(key: ConversationBottomPosition.self,
                        value: geometry.frame(in: .named(ConversationListAnchor.viewport)).maxY)
                }
            }
        }
    }
}

/// The rows of the column and their motion state.
private struct ConversationColumn: View {
    let viewModel: ConversationViewModel
    let viewportHeight: CGFloat
    let loadOlder: () -> Void

    @State private var collapsing: Set<MessageID> = []
    /// Arriving rows whose growth has started (the others of
    /// `viewModel.arrivingRows` are still at height 0 for their first frame).
    @State private var growing: Set<MessageID> = []
    /// The typing bubble's presence, 0…1: it grows in and collapses out in
    /// the layout, frame by frame.
    @State private var typingProgress: Double = 0
    /// Kept in the hierarchy until its collapse has finished.
    @State private var typingInLayout = false
    @State private var typingLeaves = false
    /// The newest row when the column first appeared: everything after it
    /// is the live end.
    @State private var liveAfter: Live?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    struct Live: Equatable {
        let id: MessageID
        let sentAt: Date
    }

    var body: some View {
        let rows = viewModel.rows
        let leaving = viewModel.leavingRows
        let sourceRows = ConversationViewModel.displayedRows(rows, leaving: leaving)
        let removedPhotos = ConversationPhotos.albumRemovals(sourceRows, leaving: Set(leaving.map(\.row.id)))
        let displayed = ConversationPhotos.albums(sourceRows.filter { !removedPhotos.contains($0.id) },
            separate: Set(leaving.map(\.row.id)).union(viewModel.kindChanges.keys))
        let liftedID = viewModel.actions?.messageID
        let frameRequest = viewModel.frameRequest
        let typing = viewModel.isPeerTyping
        let kindChanges = viewModel.kindChanges
        let arriving = viewModel.arrivingRows
        let firstID = displayed.first?.id
        let split = Self.split(displayed, liveAfter: liveAfter)
        let newest = NewestRow(id: rows.last?.id, isOutgoing: rows.last?.message.isOutgoing ?? false, typing: typing)
        let makeRow = { (row: ConversationRow, isLive: Bool) in
            RowSlot(
                row: row, isFirst: row.id == firstID, isLive: isLive, isLifted: row.id == liftedID,
                capturesFrame: row.id == frameRequest, replacing: kindChanges[row.id],
                isLeaving: leaving.contains { $0.row.id == row.id }, isCollapsed: collapsing.contains(row.id),
                isArriving: arriving.contains(row.id), isGrowing: growing.contains(row.id), reduceMotion: reduceMotion,
                viewModel: viewModel)
        }
        let verifyPrompt = viewModel.showsVerifyPrompt
        // Spacing 0 everywhere: each row carries the 6 pt gap above it, so a
        // row that grows in or collapses takes its gap along.
        return VStack(spacing: 0) {
            // An unverified chat starts with the prompt to compare the
            // safety number; it scrolls away with the history.
            if verifyPrompt {
                ConversationVerifyPrompt(chatID: viewModel.chatID)
                    .padding(.bottom, displayed.isEmpty ? 0 : 2 * U2Spacing.bubbleGap)
                    .transition(.opacity)
            } else if viewModel.community != nil {
                // A community says how it is encrypted (each member separately).
                SystemPill(glyph: .shieldCheck, text: Text("conversation.intro", table: .communities))
                    .frame(maxWidth: .infinity)
                    .padding(.bottom, displayed.isEmpty ? 0 : 2 * U2Spacing.bubbleGap)
            }
            // The history: lazy — any length costs only what is on screen.
            LazyVStack(spacing: 0) {
                ForEach(displayed[..<split]) { row in
                    makeRow(row, false).onAppear { if row.id == firstID { loadOlder() } }
                }
            }
            .scrollTargetLayout()
            // The live end — what arrived since the column appeared — laid
            // out exactly: a lazy stack lays a new row out at an estimated
            // height first, and the bottom anchor would jump to that before
            // the row's real (growing) height.
            VStack(spacing: 0) {
                ForEach(displayed[split...]) { makeRow($0, true) }
                if reduceMotion ? typing : (typing || typingInLayout) {
                    typingRow
                }
                // The end of the column: 6 pt — the design's 100 pt bottom
                // padding, of which the composer inset covers 94. Scrolling
                // “to the newest” aligns this, so the column rests exactly as
                // drawn.
                Color.clear
                    .frame(height: U2Spacing.bubbleGap)
                    .id(ConversationListAnchor.bottom)
                    .modifier(LegacyBottomPosition())
                    .accessibilityHidden(true)
            }
        }
        .padding(.horizontal, U2Spacing.chatGutter)
        .padding(.top, 114)
        .modifier(ShortChatAlignment(viewportHeight: viewportHeight))
        // Reduce Motion: rows simply fade in and out.
        .animation(reduceMotion ? U2Motion.reducedCrossFade : nil, value: newest)
        .onAppear {
            if liveAfter == nil, let last = displayed.last { liveAfter = Live(id: last.id, sentAt: last.message.sentAt) }
        }
        .onChange(of: typing) { _, typing in
            if typing { typingInLayout = true }
            typingLeaves = !typing
            withAnimation(insertAnimation(newest), completionCriteria: .removed) {
                typingProgress = typing ? 1 : 0
            } completion: {
                if !viewModel.isPeerTyping, typingProgress == 0 { typingInLayout = false }
            }
        }
        .onChange(of: arriving) { _, ids in
            grow(ids.subtracting(growing), animation: insertAnimation(newest))
        }
        .onChange(of: leaving.map(\.row.id)) { _, ids in
            for id in removedPhotos { viewModel.finishLeaving(id) }
            collapse(ids.filter { !removedPhotos.contains($0) && !collapsing.contains($0) })
        }
    }

    private var typingRow: some View {
        TypingIndicatorBubble()
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, U2Spacing.bubbleGap)
            .modifier(ArrivalModifier(progress: reduceMotion ? 1 : typingProgress, sent: false, clips: typingLeaves))
            .accessibilityElement()
            .accessibilityLabel(Text("a11y.typing \(viewModel.peerUsername)", table: .conversation))
            .id(ConversationListAnchor.typing)
            .transition(.opacity)
    }

    /// The first row of the live end: the rows after `liveAfter` (before it
    /// is set: none). Walks from the end only; if that row is gone, the rows
    /// newer than it.
    static func split(_ rows: [ConversationRow], liveAfter: Live?) -> Int {
        guard let liveAfter else { return rows.endIndex }
        var index = rows.endIndex
        while index > rows.startIndex {
            let row = rows[index - 1]
            if row.id == liveAfter.id || row.message.sentAt < liveAfter.sentAt { break }
            index -= 1
        }
        return index
    }

    // MARK: Motion

    /// The newest row and the typing state: what decides whether a change
    /// is an arrival at the bottom.
    private struct NewestRow: Equatable {
        let id: MessageID?
        let isOutgoing: Bool
        let typing: Bool
    }

    /// Sent: the critically damped spring (design rule: no bounce) — the bubble
    /// travels from the composer. Received: the design's 0.36 s ease-out.
    private func insertAnimation(_ newest: NewestRow) -> Animation {
        if reduceMotion { return U2Motion.reducedCrossFade }
        return newest.isOutgoing ? ConversationMotion.send : U2Motion.appear
    }

    /// New rows grow in from height 0 (they were laid out at 0 for one
    /// frame), then become plain rows.
    private func grow(_ ids: Set<MessageID>, animation: Animation) {
        guard !ids.isEmpty else { return }
        withAnimation(animation, completionCriteria: .removed) {
            growing.formUnion(ids)
        } completion: {
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) {
                for id in ids { viewModel.finishArrival(id) }
                growing.subtract(ids)
            }
        }
    }

    /// Removed rows collapse (0.36 s; Reduce Motion: fade in place), then
    /// leave without animation — they are already invisible.
    private func collapse(_ ids: [MessageID]) {
        for id in ids {
            withAnimation(reduceMotion ? U2Motion.reducedCrossFade : U2Motion.state, completionCriteria: .removed) {
                _ = collapsing.insert(id)
            } completion: {
                var transaction = Transaction()
                transaction.disablesAnimations = true
                withTransaction(transaction) {
                    viewModel.finishLeaving(id)
                    collapsing.remove(id)
                }
            }
        }
    }
}

/// One row of the column with its gap and its state there (leaving,
/// arriving, at rest).
private struct RowSlot: View {
    let row: ConversationRow
    let isFirst: Bool
    /// In the live end: the row keeps its arrival modifier after arriving
    /// (at rest it changes nothing), so finishing the arrival does not
    /// re-create the row.
    let isLive: Bool
    let isLifted: Bool
    let capturesFrame: Bool
    let replacing: Message?
    let isLeaving: Bool
    let isCollapsed: Bool
    let isArriving: Bool
    let isGrowing: Bool
    let reduceMotion: Bool
    let viewModel: ConversationViewModel

    var body: some View {
        let content = ConversationMessageRow(
            row: row, isLifted: isLifted, capturesFrame: capturesFrame, replacing: replacing, isNew: isLive,
            viewModel: viewModel
        )
        .equatable()
        .padding(.top, isFirst ? 0 : U2Spacing.bubbleGap)
        Group {
            if isLeaving {
                content.modifier(CollapsingRow(isCollapsed: isCollapsed, reduceMotion: reduceMotion))
            } else if isLive || isArriving, !reduceMotion {
                content.modifier(
                    ArrivalModifier(progress: isArriving && !isGrowing ? 0 : 1, sent: row.message.isOutgoing && !row.message.isSystem))
            } else {
                content
            }
        }
        .transition(reduceMotion ? .opacity : .identity)
    }
}

// MARK: - Motion tokens of the conversation

enum ConversationMotion {
    /// The send spring: critically damped (no overshoot), a touch quicker
    /// than the sheet spring — the bubble and the column settle together.
    static let send = U2Motion.springQuick
}

/// A new row at the bottom of the column: its height (with the 6 pt gap
/// above it) grows from 0 to its own, frame by frame, with the row pinned to
/// the top of that growing space — so it rises from behind the composer by
/// its height while the bottom anchor carries the rows above up with it, in
/// one motion (no scrolling, nothing jumps). It fades in on the way; a sent
/// bubble also grows from 94 % at its tail corner, like the text leaving the
/// field. (The design's incoming “fade + 8 pt rise” becomes this rise from
/// the bottom edge — the column has to make room anyway.)
///
/// Driven by state, not by an insertion transition: a transition is laid
/// out at its final size first, and the bottom anchor would jump to that.
private struct ArrivalModifier: ViewModifier, Animatable {
    var progress: Double
    let sent: Bool
    /// Leaving instead (the typing bubble): the shrinking space clips it,
    /// so it fades away in place rather than sinking behind the composer.
    var clips = false

    nonisolated var animatableData: Double {
        get { progress }
        set { progress = newValue }
    }

    func body(content: Content) -> some View {
        let layout = ArrivalLayout(progress: progress) {
            content
                .scaleEffect(sent ? 0.94 + 0.06 * progress : 1, anchor: .bottomTrailing)
        }
        if clips {
            layout.clipShape(Rectangle()).opacity(progress)
        } else {
            layout.opacity(progress)
        }
    }
}

/// Reports `progress ×` the child's height; the child keeps its full height
/// and hangs from the top (it overflows below until it has arrived).
private struct ArrivalLayout: Layout {
    var progress: Double

    nonisolated func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) -> CGSize {
        guard let child = subviews.first else { return .zero }
        let size = child.sizeThatFits(ProposedViewSize(width: proposal.width, height: nil))
        return CGSize(width: size.width, height: size.height * CGFloat(max(0, min(1, progress))))
    }

    nonisolated func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) {
        guard let child = subviews.first else { return }
        let size = child.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil))
        child.place(at: bounds.origin, anchor: .topLeading, proposal: ProposedViewSize(width: bounds.width, height: size.height))
    }
}

/// A leaving row: `u2CollapseProgress` (height × progress, rounded clip,
/// fade) — under Reduce Motion it only fades in place.
private struct CollapsingRow: ViewModifier {
    let isCollapsed: Bool
    let reduceMotion: Bool

    func body(content: Content) -> some View {
        content
            .u2CollapseProgress(isCollapsed && !reduceMotion ? 0 : 1, cornerRadius: U2Radius.bubble)
            .opacity(isCollapsed && reduceMotion ? 0 : 1)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

// MARK: - Anchoring

/// iOS 18+: the bottom anchor for the first position, for size changes
/// (content measured above, keyboard, older messages) and for short content;
/// whether the user is near the bottom. iOS 17: the single bottom anchor and
/// the viewport height for `ShortChatAlignment`.
private struct BottomAnchoring: ViewModifier {
    @Binding var isNearBottom: Bool
    @Binding var viewportHeight: CGFloat
    @State private var legacyBottom: CGFloat = 0

    static var tracksPosition: Bool {
        if #available(iOS 18.0, *) { true } else { false }
    }

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content
                .defaultScrollAnchor(.bottom, for: .initialOffset)
                .defaultScrollAnchor(.bottom, for: .sizeChanges)
                .defaultScrollAnchor(.bottom, for: .alignment)
                .onScrollGeometryChange(for: Bool.self) { geometry in
                    // `bounds` is the visible content above the composer inset.
                    geometry.contentSize.height - geometry.bounds.maxY < 80
                } action: { _, near in
                    isNearBottom = near
                }
        } else {
            content
                .defaultScrollAnchor(.bottom)
                .onGeometryChange(for: CGFloat.self) { $0.size.height - $0.safeAreaInsets.bottom } action: { viewportHeight = $0 }
                .onPreferenceChange(ConversationBottomPosition.self) { bottom in
                    legacyBottom = bottom
                    if viewportHeight > 0 { isNearBottom = bottom - viewportHeight < 80 }
                }
                .onChange(of: viewportHeight) { _, height in
                    if height > 0 { isNearBottom = legacyBottom - height < 80 }
                }
        }
    }
}

/// iOS 17: short chats sit at the bottom, like the design's column
/// (`jc: flex-end`). iOS 18+ does it with the alignment anchor.
private struct ShortChatAlignment: ViewModifier {
    let viewportHeight: CGFloat

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content
        } else {
            content.frame(minHeight: viewportHeight, alignment: .bottom)
        }
    }
}
