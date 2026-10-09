import SwiftUI

/// 4.4 · İstek önizleme — a request read in context before deciding.
///
/// Looks like a chat but has no composer: the decision bar takes its place.
/// Until accepted no read receipt is sent, links are inert (no tap, no preview
/// fetch, no data detectors) and media is never decoded — a placeholder says
/// what it is. “Kabul et” turns this screen into 5.1: the decision bar slides
/// down (0.4 s) while the composer fades in and the header settles into the
/// chat's, then the conversation takes over. Engelle (after a confirmation)
/// and Sil go back to 4.3, where the card collapses.
struct RequestPreviewView: View {
    let requestID: RequestID

    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var triage = RequestTriageModel()
    /// The request as first shown — kept while the screen leaves after a decision.
    @State private var shown: MessageRequest?
    @State private var acceptedChat: ChatID?
    @State private var showsMenu = false
    @State private var confirmsBlock = false
    @State private var barHeight: CGFloat = 161
    @State private var composerHeight: CGFloat = 94
    @State private var threadHeight: CGFloat = 0
    /// For the accepted rows (5.1's own row view; nothing is lifted here).
    @State private var frames = BubbleFrameStore()

    init(requestID: RequestID) {
        self.requestID = requestID
    }

    private var request: MessageRequest? {
        model.messenger.requests.first { $0.id == requestID } ?? shown
    }

    var body: some View {
        let request = request
        U2Screen {
            ZStack(alignment: .top) {
                if let request {
                    thread(request)
                }
                VStack(spacing: 0) {
                    Spacer(minLength: 0)
                    ZStack(alignment: .bottom) {
                        composer
                        if let request {
                            decisionBar(request)
                        }
                    }
                }
                if let request {
                    header(request)
                }
            }
            .u2Menu(isPresented: $showsMenu, items: menuItems, designTop: 104, trailing: 14)
        }
        .alert(
            Text("requests.block.title \(request?.sender ?? "")", table: .chats), isPresented: $confirmsBlock
        ) {
            Button(role: .destructive) {
                if let request { leave(then: { await model.messenger.block(request) }) }
            } label: {
                Text("request.block")
            }
            Button(role: .cancel) {} label: { Text("common.cancel") }
        } message: {
            Text("requests.block.message", table: .chats)
        }
        .onAppear {
            if shown == nil { shown = model.messenger.requests.first { $0.id == requestID } }
        }
        #if DEBUG
            .task {
                guard let request else { return }
                if await ChatsHarness.waitForDemo(.accept) {
                    accept(request)
                } else if await ChatsHarness.waitForDemo(.deletePreview) {
                    leave(then: { await model.messenger.delete(request) })
                }
            }
        #endif
    }

    // MARK: Header

    private func header(_ request: MessageRequest) -> some View {
        let chat = acceptedChat.flatMap { model.messenger.chat($0) }
        // `onTapPeer` must be set: ChatHeader disables (and so dims) the peer
        // button without it. Nothing to open before the request is accepted.
        return ChatHeader(
            username: request.sender, palette: chat?.peer.palette ?? .c, onBack: model.router.pop, onTapPeer: {}
        ) {
            // Accepted: the status line gives way to the chat's presence line,
            // which a just-accepted contact does not share yet (nothing shown).
            if chat == nil {
                Text("request.title")
                    .transition(.u2Fade)
            }
        } trailing: {
            ZStack {
                if let chat {
                    GhostChip(ghost: chat.ghost) {}
                        .allowsHitTesting(false)
                        .transition(.u2Fade)
                } else {
                    U2GlassIconButton(
                        .dotsThree, diameter: U2Size.iconButtonChat, glyphSize: 20, label: Text("a11y.more", table: .chats)
                    ) { showsMenu.toggle() }
                    .accessibilityIdentifier("preview.more")
                    .transition(.u2Fade)
                }
            }
        }
    }

    private var menuItems: [FloatingMenuItem] {
        [
            FloatingMenuItem(id: "block", title: Text("request.block"), glyph: .prohibit, tone: .destructive) {
                confirmsBlock = true
            },
            FloatingMenuItem(id: "delete", title: Text("request.delete"), glyph: .trash, tone: .destructive) {
                if let request { leave(then: { await model.messenger.delete(request) }) }
            },
        ]
    }

    // MARK: Thread

    /// Messages column (pad 122 14 0, gap 6): the sender's profile block, a
    /// date separator per day, the incoming bubbles (only the last of a group
    /// carries the tail and the time).
    ///
    /// Accepted, the column becomes 5.1's: the profile block and the date
    /// rule fade out, every bubble cross-fades into 5.1's row (its time row,
    /// the live link) and the column glides down to sit above the composer
    /// exactly where 5.1 draws it — so the swap to 5.1 that follows is
    /// invisible.
    private func thread(_ request: MessageRequest) -> some View {
        let accepted = acceptedChat != nil
        let messages = request.messages
        return ScrollView {
            VStack(spacing: U2Spacing.bubbleGap) {
                profile(request)
                    .opacity(accepted ? 0 : 1)
                ForEach(Array(messages.enumerated()), id: \.element.id) { index, message in
                    if startsDay(index, in: messages) {
                        RequestDateSeparator(date: message.sentAt)
                            .opacity(accepted ? 0 : 1)
                    }
                    // Both forms stay in place (no insertion / removal), so
                    // they glide together while one dissolves into the other;
                    // the row takes the visible form's height.
                    // Privacy: 5.1's row is built before “Kabul et” only for
                    // text (already shown here, drawn inert); media is never
                    // decoded until accepted, so its row only fades in then.
                    MorphRowLayout(showsSecond: accepted) {
                        RequestMessageView(message: message, endsGroup: endsGroup(index, in: messages))
                            .opacity(accepted ? 0 : 1)
                            .allowsHitTesting(!accepted)
                            .accessibilityHidden(accepted)
                        if accepted || Self.isText(message) {
                            ConversationMessageRow(
                                message: message, isTail: ConversationViewModel.isTail(index, in: messages),
                                showsStatusSentence: false, isLifted: false, frames: frames
                            )
                            .opacity(accepted ? 1 : 0)
                            .allowsHitTesting(false)
                            .accessibilityHidden(!accepted)
                            .transition(.opacity)
                        }
                    }
                }
            }
            .padding(.horizontal, U2Spacing.chatGutter)
            .u2Top(122)
            // Accepted: 5.1's column — 6 pt above the composer, bottom-aligned.
            .padding(.bottom, accepted ? composerHeight + 6 : barHeight + 16)
            .frame(minHeight: accepted ? threadHeight : 0, alignment: .bottom)
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { threadHeight = $0 }
        .scrollIndicators(.hidden)
        .allowsHitTesting(!accepted)
        .accessibilityIdentifier("preview.thread")
    }

    /// Profile block (pad 8 0 16, gap 10, centred): avatar 64, name, explanation.
    private func profile(_ request: MessageRequest) -> some View {
        VStack(spacing: 10) {
            Avatar(username: request.sender, palette: .c, size: .profile)
            Text(verbatim: request.sender)
                .u2Font(.monoUsernameXL)
                .foregroundStyle(Color.u2.text)
                .lineLimit(1)
            Text("preview.intro", table: .chats)
                .u2Font(.callout)
                .foregroundStyle(Color.u2.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 290)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 8)
        .padding(.bottom, 16)
        .accessibilityElement(children: .combine)
    }

    private static func isText(_ message: Message) -> Bool {
        if case .text = message.content { return true }
        return false
    }

    private func startsDay(_ index: Int, in messages: [Message]) -> Bool {
        guard index > 0 else { return true }
        return !Calendar.current.isDate(messages[index].sentAt, inSameDayAs: messages[index - 1].sentAt)
    }

    /// The last message of a group (next one is later than 5 min, another
    /// day, or there is none) carries the tail corner and the time.
    private func endsGroup(_ index: Int, in messages: [Message]) -> Bool {
        guard index + 1 < messages.count else { return true }
        let next = messages[index + 1]
        if case .system = next.content { return true }
        return next.sentAt.timeIntervalSince(messages[index].sentAt) >= 300
            || !Calendar.current.isDate(next.sentAt, inSameDayAs: messages[index].sentAt)
    }

    // MARK: Decision bar

    /// 0,713 402×161: `surface`, top radius 30, pad 18 16 40, gap 14 —
    /// explanation (`callout`, centred) and Engelle · Sil · Kabul et (50 high,
    /// r14, Kabul et at flex 1.4 with the soft glow).
    private func decisionBar(_ request: MessageRequest) -> some View {
        let accepted = acceptedChat != nil
        return VStack(spacing: 14) {
            Text("preview.decision \(request.sender)", table: .chats)
                .u2Font(.callout)
                .foregroundStyle(Color.u2.textSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 10)
            U2FlexRow(spacing: 8) {
                Button { confirmsBlock = true } label: { Text("request.block") }
                    .buttonStyle(U2DecisionButtonStyle(kind: .destructive, size: .bar))
                    .accessibilityIdentifier("preview.block")
                Button {
                    leave(then: { await model.messenger.delete(request) })
                } label: {
                    Text("request.delete")
                }
                .buttonStyle(U2DecisionButtonStyle(kind: .neutral, size: .bar))
                .accessibilityIdentifier("preview.delete")
                Button { accept(request) } label: { Text("request.accept") }
                    .buttonStyle(U2DecisionButtonStyle(kind: .accept, size: .bar))
                    .accessibilityIdentifier("preview.accept")
                    .u2Flex(1.4, border: 2)
            }
        }
        .padding(EdgeInsets(top: 18, leading: 16, bottom: 0, trailing: 16))
        .u2Bottom(40)
        .frame(maxWidth: .infinity)
        .background(
            UnevenRoundedRectangle(topLeadingRadius: U2Radius.sheetBar, topTrailingRadius: U2Radius.sheetBar, style: .continuous)
                .fill(Color.u2.surface)
                .ignoresSafeArea(edges: .bottom)
        )
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { barHeight = $0 }
        .offset(y: accepted && !reduceMotion ? barHeight + 10 : 0)
        .opacity(accepted ? 0 : 1)
        .allowsHitTesting(!accepted)
        .accessibilityHidden(accepted)
    }

    /// The C21 composer the decision bar turns into (inert until 5.1 takes over).
    private var composer: some View {
        Composer(text: .constant(""), onSend: {})
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { composerHeight = $0 }
            .opacity(acceptedChat != nil ? 1 : 0)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }

    // MARK: Decisions

    private func accept(_ request: MessageRequest) {
        Task {
            guard let chat = await triage.accept(request, in: model.messenger) else { return }
            // The whole morph happens here (0.4 s): bar → composer, header →
            // the chat's, thread → 5.1's column. Then 5.1 replaces this screen
            // without any animation: it is drawn identically.
            await withCheckedContinuation { continuation in
                withU2Animation(U2Motion.soft(0.4)) {
                    acceptedChat = chat
                } completion: {
                    continuation.resume()
                }
            }
            // Only if the user is still here (back during the morph keeps 4.3).
            guard model.router.top == .requestPreview(requestID) else { return }
            model.router.replaceTop(with: .conversation(chat), animated: false)
        }
    }

    /// Back to 4.3 first, then the decision — so the card is seen collapsing.
    private func leave(then decision: @escaping @MainActor () async -> Void) {
        model.router.pop()
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(450))
            await decision()
        }
    }
}

/// Two forms of one row on top of each other: sized by the first, or by
/// the second when `showsSecond` (4.4 → 5.1 morph).
private struct MorphRowLayout: Layout {
    var showsSecond: Bool

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) -> CGSize {
        guard subviews.count == 2 else { return subviews.first?.sizeThatFits(proposal) ?? .zero }
        return subviews[showsSecond ? 1 : 0].sizeThatFits(ProposedViewSize(width: proposal.width, height: nil))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) {
        for subview in subviews {
            let size = subview.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil))
            subview.place(at: bounds.origin, anchor: .topLeading, proposal: ProposedViewSize(width: bounds.width, height: size.height))
        }
    }
}

/// The 4.4 date separator (pad 6 2, gap 12): dotted rules (`text·28%`,
/// r .8) either side of “BUGÜN 16:12” (`monoSectionLabel`).
struct RequestDateSeparator: View {
    let date: Date
    @Environment(\.locale) private var locale
    @Environment(\.u2Clock) private var clock

    var body: some View {
        HStack(spacing: 12) {
            DottedRule(color: Color.u2.dotRuleDate, pattern: .dateRule)
            Text(verbatim: ChatsFormat.separator(date, now: clock.now, locale: locale))
                .u2Font(.monoSectionLabel)
                .foregroundStyle(Color.u2.textSecondary)
                .lineLimit(1)
                .fixedSize()
            DottedRule(color: Color.u2.dotRuleDate, pattern: .dateRule)
        }
        .padding(.vertical, 6)
        .padding(.horizontal, 2)
        .accessibilityElement(children: .combine)
    }
}

/// One message of a request thread, always inert: text never becomes a link;
/// an address is shown broken (`ph:link-break`) with “Bağlantı, kabul edene
/// kadar açılmaz”; media is never loaded — a placeholder names it.
struct RequestMessageView: View {
    let message: Message
    /// Last of its group: tail corner + time row.
    let endsGroup: Bool

    var body: some View {
        content
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private var content: some View {
        switch message.content {
        case .text(let text):
            let kind = RequestContent.classify(text)
            MessageBubble(direction: .incoming, isTail: endsGroup) {
                if kind == .link {
                    HStack(spacing: 8) {
                        PhosphorIcon(.linkBreak, size: 16)
                            .foregroundStyle(Color.u2.textSecondary)
                        Text(verbatim: text)
                            .u2Font(.monoLink)
                            .foregroundStyle(Color.u2.textStrong)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                    inertNote(Text("preview.link.inert", table: .chats))
                } else {
                    CSSBlockWidth {
                        Text(verbatim: text)
                            .u2Font(.bubble)
                            .foregroundStyle(Color.u2.text)
                    }
                    if kind == .containsLink {
                        inertNote(Text("preview.link.inert", table: .chats), glyph: .linkBreak)
                    }
                }
                if endsGroup {
                    BubbleMeta(time: message.sentAt).u2BubbleTrailing()
                }
            }
            .accessibilityElement(children: .combine)
        case .deleted:
            TombstoneBubble(message: message, isTail: endsGroup)
        case .system(let event):
            SystemEventPill(event: event)
                .frame(maxWidth: .infinity)
        default:
            MessageBubble(direction: .incoming, isTail: endsGroup) {
                HStack(spacing: 8) {
                    PhosphorIcon(.eyeSlash, size: 16)
                        .foregroundStyle(Color.u2.textSecondary)
                    mediaName
                        .u2Font(.bubble)
                        .foregroundStyle(Color.u2.text)
                }
                inertNote(Text("requests.flag.media", table: .chats))
                if endsGroup {
                    BubbleMeta(time: message.sentAt).u2BubbleTrailing()
                }
            }
            .accessibilityElement(children: .combine)
        }
    }

    private func inertNote(_ text: Text, glyph: PhosphorGlyph? = nil) -> some View {
        HStack(spacing: 6) {
            if let glyph {
                PhosphorIcon(glyph, size: 14)
            }
            text.u2Font(.metaInBubble)
        }
        .foregroundStyle(Color.u2.textSecondary)
    }

    private var mediaName: Text {
        switch message.content {
        case .photo: Text("media.photo")
        case .video: Text("media.video")
        case .voice: Text("media.voice")
        case .file: Text("media.file", table: .chats)
        case .location: Text("media.location")
        case .liveLocation: Text("location.live")
        case .viewOnce(let isVideo, _): isVideo ? Text("media.video") : Text("media.photo")
        case .textSticker: Text("chats.preview.sticker")
        case .text, .deleted, .system: Text(verbatim: "")
        }
    }
}

#Preview("4.4 İstek önizleme") {
    let model = AppModel.preview()
    return RequestPreviewView(requestID: "kuzey.dev")
        .environment(model)
        .environment(\.locale, model.locale)
        .environment(\.u2Clock, model.clock)
}
