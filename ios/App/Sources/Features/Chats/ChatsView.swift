import SwiftUI

/// 4.1 · Sohbet listesi — the root of the main stack.
///
/// Top bar (own avatar → 7.1, QR menu, compose → 4.2), the “Sohbetler” title
/// with the connection line that flows while the line is open, the requests
/// card (only when there are requests), and the chat rows with their status,
/// presence dot (accepted contacts only) and Ghost pill / unread badge.
///
/// The top bar stays put and the list scrolls under it (inventory 4.1); at rest
/// the bar is exactly as drawn, and once rows pass beneath it the chat
/// header's translucent band fades in (proposed). New messages move a row to
/// the top with the soft 0.5 s reorder.
struct ChatsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// The own profile picture on the top bar (proposed), set at the root.
    @Environment(\.u2OwnAvatar) private var ownAvatar

    @State private var newChat: NewChatPresentation?
    @State private var showsQRMenu = false
    @State private var myCode: MyCodePresentation?
    @State private var showsScanner = false
    @State private var isScrolled = false
    /// One clock for every Ghost pill in the list (instead of a timeline per
    /// row), ticking only while 4.1 can be seen.
    @State private var ticker: U2Ticker?
    /// The own contact code, loaded up front so “Kodumu göster” rises complete
    /// (nothing is inserted into the sheet while it moves).
    @State private var myCodePayload: String?
    #if DEBUG
        @State private var harness = ChatsHarness.State()
    #endif

    var body: some View {
        U2Screen {
            ZStack(alignment: .top) {
                list
                ChatsHeaderBackdrop(isVisible: isScrolled)
                topBar
            }
            .u2Menu(isPresented: $showsQRMenu, items: qrMenuItems, designTop: 106, trailing: 70)
        }
        .u2Sheet(item: $newChat, onDismiss: openPendingChat) { presentation in
            NewChatSheet(presentation: presentation, messenger: model.messenger) { result in
                pendingChat = result
                newChat = nil
            } onCancel: {
                newChat = nil
            }
        }
        .u2Sheet(item: $myCode) { presentation in
            MyCodeSheet(username: model.messenger.ownUsername, payload: presentation.payload) { myCode = nil }
        }
        .u2FadeCover(isPresented: $showsScanner, onEscape: { showsScanner = false }) {
            // 6.2 from 4.1: a match adds + verifies; “Sohbete dön” pushes 5.1 under the fading cover.
            ContactQRScanView(onClose: { showsScanner = false })
        }
        .task { myCodePayload = await model.messenger.preparedOwnContactCode() }
        .onAppear {
            if ticker == nil { ticker = U2Ticker(clock: model.clock) }
        }
        .background { TickerVisibility(ticker: ticker) }
        // An invite link (https://us2us.app/u/<username>), once unlocked.
        .u2OnInviteLink(isReady: model.phase == .unlocked && model.overlay == .none) { username in
            openInvite(username)
        }
        #if DEBUG
            .task { applyHarness() }
        #endif
    }

    // MARK: List

    @State private var pendingChat: ChatID?

    private var chats: [ChatSummary] {
        #if DEBUG
            if harness.hidesChats { return [] }
            if let feed = harness.stressFeed { return feed.chats }
        #endif
        return model.messenger.chats
    }

    private var list: some View {
        let messenger = model.messenger
        let chats = chats
        return ScrollView {
            VStack(spacing: 0) {
                titleBlock(connection: messenger.connection)
                    .u2Top(100)

                if let communities = messenger.communities {
                    // Communities (proposed): one card in the requests card's shape.
                    CommunitiesCard(count: communities.rows.count) { model.router.push(.communities) }
                        .accessibilityIdentifier("chats.communities")
                        .padding(EdgeInsets(top: 18, leading: 16, bottom: 4, trailing: 16))
                }

                if messenger.restoreAccessPending {
                    // After a restore whose token rotation is unanswered: the
                    // old phone may still have access; 7.1 explains and checks.
                    RestoreAccessCard { model.router.push(.settings) }
                        .accessibilityIdentifier("chats.access")
                        .padding(EdgeInsets(top: messenger.communities != nil ? 6 : 18, leading: 16, bottom: 4, trailing: 16))
                        .transition(.u2Fade)
                }

                if !messenger.requests.isEmpty {
                    RequestsCard(count: messenger.requests.count) { model.router.push(.requests) }
                        .accessibilityIdentifier("chats.requests")
                        // Under the access card: the 10 pt gap of stacked option cards.
                        .padding(
                            EdgeInsets(
                                top: messenger.restoreAccessPending || messenger.communities != nil ? 6 : 18, leading: 16,
                                bottom: 4, trailing: 16))
                        .transition(.u2Fade)
                }

                if chats.isEmpty {
                    emptyState
                        .transition(.u2Rise)
                } else {
                    LazyVStack(spacing: 0) {
                        ForEach(Array(chats.enumerated()), id: \.element.id) { index, chat in
                            VStack(spacing: 0) {
                                if index > 0 {
                                    // The rule belongs to the row's place, not the
                                    // row: when a row moves to the top it simply
                                    // has none there (no fading rule mid-move).
                                    ChatListSeparator()
                                        .padding(.leading, U2Spacing.listSeparatorInset)
                                        .transition(.identity)
                                }
                                Button {
                                    model.router.push(.conversation(chat.id))
                                } label: {
                                    ChatListRow(chat: chat)
                                }
                                .buttonStyle(GroupedRowButtonStyle())
                                .accessibilityIdentifier("chat.\(chat.id)")
                            }
                            .transition(.u2Rise)
                        }
                    }
                    // Reduce Motion: rows never slide into a new order — the
                    // old list dissolves into the new one (0.3 s cross-fade).
                    .id(reduceMotion ? chats.map(\.id) : [])
                    .transition(.opacity)
                }
            }
            .padding(.bottom, 40)
            .u2Animation(U2Motion.screen, value: chats.map(\.id))
            .u2Animation(U2Motion.state, value: messenger.requests.isEmpty)
            .u2Animation(U2Motion.state, value: messenger.restoreAccessPending)
            .onGeometryChange(for: Bool.self) { proxy in
                proxy.frame(in: .scrollView).minY < -2
            } action: { scrolled in
                isScrolled = scrolled
            }
        }
        .scrollIndicators(.hidden)
        .environment(\.u2Ticker, ticker)
        .accessibilityIdentifier("chats.list")
    }

    /// Title block (pad 14 20 0, gap 12): “Sohbetler” (Doto 900 38) and the C20
    /// connection line with its caption.
    private func titleBlock(connection: ConnectionState) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("chats.title")
                .u2Font(.displayTitle)
                .foregroundStyle(Color.u2.text)
                .accessibilityAddTraits(.isHeader)
            ConnectionStatusLine(state: connection)
                .accessibilityIdentifier("chats.connection")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 14)
        .padding(.horizontal, U2Spacing.screenGutter)
    }

    /// No chats yet (proposed copy): a short instruction and the way to start.
    private var emptyState: some View {
        VStack(spacing: 14) {
            Text("list.empty", table: .chats)
                .u2Font(.callout)
                .foregroundStyle(Color.u2.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 290)
                .fixedSize(horizontal: false, vertical: true)
            Button {
                newChat = NewChatPresentation()
            } label: {
                HStack(spacing: 8) {
                    PhosphorIcon(.notePencil, size: 20)
                    Text("list.empty.action", table: .chats)
                }
            }
            .buttonStyle(U2TextButtonStyle(tone: .accent, fillsWidth: false))
            .accessibilityIdentifier("chats.empty.new")
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 120)
        .padding(.horizontal, U2Spacing.screenGutterL)
    }

    // MARK: Top bar

    /// Row at design y 56, pad 0 16: own avatar 40 (palette D) · QR + compose (gap 10).
    private var topBar: some View {
        HStack {
            Button {
                model.router.push(.settings)
            } label: {
                Avatar(username: model.messenger.ownUsername, palette: .d, size: .chatsSelf, picture: ownAvatar)
            }
            .buttonStyle(CardButtonStyle())
            .accessibilityLabel(Text("a11y.settings"))
            .accessibilityIdentifier("chats.settings")
            Spacer(minLength: 0)
            HStack(spacing: 10) {
                U2GlassIconButton(.qrCode, label: Text("a11y.qr")) { showsQRMenu.toggle() }
                    .accessibilityIdentifier("chats.qr")
                U2GlassIconButton(.notePencil, tint: Color.u2.accent300, label: Text("a11y.newChat")) {
                    newChat = NewChatPresentation()
                }
                .accessibilityIdentifier("chats.new")
            }
        }
        .padding(.horizontal, U2Spacing.screenGutterS)
        .frame(height: U2Size.iconButton)
        .u2Top(U2Spacing.headerTopButtons)
    }

    /// 4.1 QR → “QR kodla ekle” (6.2) / “Kodumu göster” (proposed, matching 4.2).
    private var qrMenuItems: [FloatingMenuItem] {
        [
            FloatingMenuItem(id: "scan", title: Text("qr.add", table: .chats), glyph: .scan) { showsScanner = true },
            FloatingMenuItem(id: "myCode", title: Text("qr.mine", table: .chats), glyph: .qrCode) { showMyCode() },
        ]
    }

    // MARK: Actions

    /// “Kodumu göster”: the code loaded up front rises with the sheet.
    private func showMyCode() {
        Task {
            if myCodePayload == nil { myCodePayload = await model.messenger.preparedOwnContactCode() }
            myCode = MyCodePresentation(payload: myCodePayload)
        }
    }

    /// An invite link: 4.2 over 4.1 with the name searched — the same exact
    /// lookup as typing it; nothing is sent until “Mesaj yaz”. Whatever is
    /// open goes first (the stack pops softly, sheets go down), so the new
    /// sheet never meets another presentation.
    private func openInvite(_ username: String) {
        let isBusy = !model.router.isAtRoot || newChat != nil || myCode != nil || showsScanner || showsQRMenu
        showsQRMenu = false
        pendingChat = nil
        newChat = nil
        myCode = nil
        showsScanner = false
        model.router.popToRoot()
        Task { @MainActor in
            if isBusy { try? await Task.sleep(for: .seconds(U2Motion.Duration.sheet + 0.25)) }
            newChat = NewChatPresentation(initialQuery: username)
        }
    }

    /// 4.2 “Mesaj yaz”: the sheet goes down first (0.4 s), then 5.1 is pushed.
    private func openPendingChat() {
        guard let chat = pendingChat else { return }
        pendingChat = nil
        model.router.push(.conversation(chat))
    }

    #if DEBUG
        private func applyHarness() {
            guard !harness.isApplied else { return }
            harness.isApplied = true
            ChatsHarness.apply(to: model, state: &harness)
            if let query = harness.newChatQuery {
                newChat = NewChatPresentation(initialQuery: query, debug: harness.newChatDebug)
            }
            if harness.showsQRMenu { showsQRMenu = true }
            if harness.showsMyCode { showMyCode() }
            Task {
                if await ChatsHarness.waitForDemo(.qrMyCode) {
                    showsQRMenu = true
                    try? await Task.sleep(for: .milliseconds(1200))
                    qrMenuItems.last?.action()
                    showsQRMenu = false
                } else if await ChatsHarness.waitForDemo(.compose) {
                    newChat = NewChatPresentation()
                } else if await ChatsHarness.waitForDemo(.openChat), let first = chats.first {
                    model.router.push(.conversation(first.id))
                } else if await ChatsHarness.waitForDemo(.pushPop) {
                    // 4.1 → 5.1 (deniz) → 4.1, five times (frame-probe runs).
                    for _ in 0..<5 {
                        model.router.push(.conversation("deniz"))
                        try? await Task.sleep(for: .milliseconds(1400))
                        model.router.pop()
                        try? await Task.sleep(for: .milliseconds(1400))
                    }
                } else if await ChatsHarness.waitForDemo(.sheetCycle) {
                    // The 4.2 sheet up and down, five times (frame-probe runs).
                    for _ in 0..<5 {
                        newChat = NewChatPresentation()
                        try? await Task.sleep(for: .milliseconds(1800))
                        newChat = nil
                        try? await Task.sleep(for: .milliseconds(1400))
                    }
                }
            }
        }
    #endif
}

/// One presentation of the 4.2 sheet.
struct NewChatPresentation: Identifiable, Equatable {
    let id = UUID()
    var initialQuery = ""
    #if DEBUG
        var debug = NewChatModel.DebugOptions()
    #endif

    #if DEBUG
        init(initialQuery: String = "", debug: NewChatModel.DebugOptions = .init()) {
            self.initialQuery = initialQuery
            self.debug = debug
        }
    #else
        init(initialQuery: String = "") {
            self.initialQuery = initialQuery
        }
    #endif
}

#Preview("4.1 Sohbetler") {
    let model = AppModel.preview()
    return ChatsView()
        .environment(model)
        .environment(\.locale, model.locale)
        .environment(\.u2Clock, model.clock)
}

/// Runs the 4.1 ticker only while the list can be seen. A separate view, so
/// a push or pop (which flips the layer's visibility) does not re-evaluate
/// the whole list.
private struct TickerVisibility: View {
    let ticker: U2Ticker?
    @Environment(\.u2IsLayerVisible) private var isLayerVisible

    var body: some View {
        Color.clear
            .onChange(of: isLayerVisible, initial: true) { _, visible in ticker?.setRunning(visible) }
            .onChange(of: ticker == nil) { _, _ in ticker?.setRunning(isLayerVisible) }
            .accessibilityHidden(true)
    }
}
