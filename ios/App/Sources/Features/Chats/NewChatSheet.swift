import SwiftUI

/// 4.2 · Yeni sohbet — the full-height sheet (C18, top at design y 58).
///
/// Exact-username search only: the result appears for the complete username
/// and nothing else (no similar names, no suggestions, no autocomplete;
/// autocorrect and capitalisation off). Below it “QR kodla ekle” (→ 6.2) and
/// “Kodumu göster” (pushed inside the sheet, proposed: the 6.1 QR card
/// without the peer).
struct NewChatSheet: View {
    let presentation: NewChatPresentation
    /// “Mesaj yaz” / “Sohbete git”: the sheet closes, then 5.1 is pushed.
    let onOpenChat: (ChatID) -> Void
    let onCancel: () -> Void

    @Environment(AppModel.self) private var app
    /// Built before the sheet rises, so the search page is there from the
    /// first frame and the whole sheet moves as one piece.
    @State private var model: NewChatModel
    @State private var page: Page = .search
    @State private var isForward = true
    @State private var showsScanner = false
    /// Loaded with the sheet, so the pushed “Kodumu göster” page is complete.
    @State private var myCodePayload: String?

    enum Page {
        case search
        case myCode
        /// “Davet et” after a search found nothing (Features/Invite).
        case invite
    }

    init(
        presentation: NewChatPresentation, messenger: any MessengerService,
        onOpenChat: @escaping (ChatID) -> Void, onCancel: @escaping () -> Void
    ) {
        self.presentation = presentation
        self.onOpenChat = onOpenChat
        self.onCancel = onCancel
        let model = NewChatModel(messenger: messenger)
        var page = Page.search
        #if DEBUG
            model.debug = presentation.debug
            if presentation.debug.startsOnMyCode { page = .myCode }
        #endif
        model.query = presentation.initialQuery
        _model = State(initialValue: model)
        _page = State(initialValue: page)
    }

    var body: some View {
        ZStack(alignment: .top) {
            switch page {
            case .search:
                NewChatSearchPage(
                    model: model, presentation: presentation, onOpenChat: onOpenChat, onCancel: onCancel,
                    onScan: { showsScanner = true }, onMyCode: { go(to: .myCode) }, onInvite: { go(to: .invite) }
                )
                .transition(.chatsPage(forward: isForward))
            case .myCode:
                NewChatMyCodePage(payload: myCodePayload) { go(to: .search) }
                    .transition(.chatsPage(forward: isForward))
            case .invite:
                InviteView(username: app.messenger.ownUsername, closeTitle: Text("common.back"), onClose: { go(to: .search) })
                    .transition(.chatsPage(forward: isForward))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .u2FadeCover(isPresented: $showsScanner, onEscape: { showsScanner = false }) {
            // 6.2 from 4.2: a match adds + verifies; “Sohbete dön” fades the
            // cover out, then the sheet goes down with the new chat, which 4.1
            // pushes (like “Mesaj yaz”).
            ContactQRScanView(onClose: { showsScanner = false }) { chat in
                Task { @MainActor in
                    try? await Task.sleep(for: .seconds(U2Motion.Duration.state))
                    onOpenChat(chat)
                }
            }
        }
        .task { myCodePayload = await app.messenger.preparedOwnContactCode() }
        .onDisappear { model.cancel() }
    }

    private func go(to page: Page) {
        isForward = page != .search
        withU2Animation(U2Motion.screen) { self.page = page }
    }
}

// MARK: - Search page

private struct NewChatSearchPage: View {
    @Bindable var model: NewChatModel
    let presentation: NewChatPresentation
    let onOpenChat: (ChatID) -> Void
    let onCancel: () -> Void
    let onScan: () -> Void
    let onMyCode: () -> Void
    let onInvite: () -> Void

    @Environment(\.locale) private var locale

    private var forcesFocusChrome: Bool {
        #if DEBUG
            return model.debug.forcesFocusChrome
        #else
            return false
        #endif
    }

    var body: some View {
        VStack(spacing: 0) {
            SheetBar(title: Text("newChat.title"), cancelTitle: Text("common.cancel"), onCancel: onCancel)
                .accessibilityIdentifier("newChat.bar")
            searchBlock
            // Plain when everything fits (swipe-down works anywhere on the
            // sheet); scrollable when the keyboard leaves too little room.
            ViewThatFits(in: .vertical) {
                results
                ScrollView {
                    results
                }
                .scrollIndicators(.hidden)
                .scrollDismissesKeyboard(.interactively)
            }
        }
        #if DEBUG
            .task {
                guard await ChatsHarness.waitForDemo(.write, delay: .milliseconds(2200)) else { return }
                if case .found(let peer, _) = model.phase { openChat(with: peer) }
            }
        #endif
    }

    /// Search block (pad 10 16 0, gap 8): the C23 field and the hint.
    private var searchBlock: some View {
        VStack(alignment: .leading, spacing: 8) {
            #if DEBUG
                UsernameSearchField(
                    text: $model.query, isSearching: model.phase == .searching, focusesOnAppear: !forcesFocusChrome,
                    onSubmit: { model.submit() }, forcesFocusChrome: forcesFocusChrome)
            #else
                UsernameSearchField(text: $model.query, isSearching: model.phase == .searching, focusesOnAppear: true, onSubmit: { model.submit() })
            #endif
            Text("newChat.hint")
                .u2Font(.monoMeta)
                .foregroundStyle(Color.u2.textSecondary)
                .padding(.horizontal, 4)
        }
        .padding(EdgeInsets(top: 10, leading: 16, bottom: 0, trailing: 16))
    }

    private var results: some View {
        VStack(spacing: 0) {
            if let card = resultCard {
                card
                    .padding(EdgeInsets(top: 18, leading: 16, bottom: 0, trailing: 16))
                    .transition(.u2Rise)
                    .id(model.resultName + String(describing: cardKind))
            }
            if case .found(let peer, false) = model.phase {
                infoRow(username: peer.username)
                    .padding(EdgeInsets(top: 16, leading: 24, bottom: 0, trailing: 24))
                    .transition(.asymmetric(insertion: .u2Fade.animation(U2Motion.state.delay(U2Motion.Duration.state)), removal: .u2Fade))
            }
            options
                .padding(EdgeInsets(top: 22, leading: 16, bottom: 24, trailing: 16))
        }
        .frame(maxWidth: .infinity, alignment: .top)
    }

    // MARK: Result card

    private enum CardKind: Hashable {
        case found
        case existing
        case notFound
        case invalid(UsernameRules.Violation)
        case own
    }

    private var cardKind: CardKind? {
        switch model.phase {
        case .found(_, let existing): existing ? .existing : .found
        case .notFound: .notFound
        case .invalid(let violation): .invalid(violation)
        case .ownName: .own
        case .idle, .pending, .searching: nil
        }
    }

    /// “Kullanıcı bulunamadı”, or for a name nobody can have, the rule it breaks.
    private var notFoundSubtitle: LocalizedStringKey {
        guard case .invalid(let violation) = model.phase else { return "newChat.notFound" }
        switch violation {
        case .turkishCharacters: return "newChat.invalid.turkish"
        case .characters: return "newChat.invalid.characters"
        case .firstLetter: return "newChat.invalid.firstLetter"
        // A name too short is not searched (the hint only): this one is too long.
        case .length: return "newChat.invalid.tooLong"
        }
    }

    private var resultCard: AnyView? {
        switch model.phase {
        case .found(let peer, let existing):
            AnyView(
                ResultCard(
                    avatar: Avatar(
                        username: peer.username, palette: peer.palette, size: .listRow, picture: model.resultAvatar.picture),
                    isLoadingAvatar: model.resultAvatar == .loading,
                    name: Text(verbatim: peer.username), nameColor: Color.u2.text,
                    subtitle: Text(existing ? "newChat.existing" : "newChat.found", table: .chats),
                    status: .found
                ) {
                    Button {
                        openChat(with: peer)
                    } label: {
                        HStack(spacing: 8) {
                            PhosphorIcon(existing ? .chatCircleDots : .paperPlaneTilt, size: 20)
                            Text(existing ? "newChat.openChat" : "newChat.write", table: .chats)
                        }
                    }
                    .buttonStyle(.u2PrimaryInCard)
                    .disabled(model.isStarting)
                    .accessibilityIdentifier("newChat.write")
                })
        case .notFound, .invalid:
            AnyView(
                ResultCard(
                    avatar: Avatar(username: "?", palette: .c, size: .listRow),
                    name: Text(verbatim: model.resultName), nameColor: Color.u2.textSecondary,
                    subtitle: Text(notFoundSubtitle, table: .chats), status: .notFound
                ) { InviteNotFoundButton(action: onInvite) })
        case .ownName:
            AnyView(
                ResultCard(
                    avatar: Avatar(
                        username: model.resultName, palette: .d, size: .listRow, picture: model.resultAvatar.picture),
                    name: Text(verbatim: model.resultName), nameColor: Color.u2.text,
                    subtitle: Text("newChat.own", table: .chats), status: .none
                ) { EmptyView() })
        case .idle, .pending, .searching:
            nil
        }
    }

    private func openChat(with peer: Peer) {
        Task {
            if let chat = await model.startChat(with: peer) { onOpenChat(chat) }
        }
    }

    /// Info row (pad 16 24 0, gap 12): `ph:tray` 18 + `callout` with
    /// “Mesaj istekleri” emphasised in `text`.
    private func infoRow(username: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            PhosphorIcon(.tray, size: 18)
                .foregroundStyle(Color.u2.accent300)
                .padding(.top, 1)
            infoText(username: username)
                .u2Font(.callout)
                .foregroundStyle(Color.u2.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }

    private func infoText(username: String) -> Text {
        let emphasis = StringTable.chats.string("newChat.info.requests", locale: locale)
        let sentence = StringTable.chats.string("newChat.info \(username) \(emphasis)", locale: locale)
        var attributed = AttributedString(sentence)
        if let range = attributed.range(of: emphasis) {
            attributed[range].foregroundColor = Color.u2.text
        }
        return Text(attributed)
    }

    // MARK: Options

    /// Options group (pad 22 16 0): C13 card on `bg`, 62 pt rows.
    private var options: some View {
        GroupedCard(fill: Color.u2.bg) {
            GroupedNavigationRow(
                tile: IconTile(content: .glyph(.scan)), title: Text("qr.add", table: .chats),
                subtitle: Text("qr.add.subtitle", table: .chats), action: onScan
            )
            .accessibilityIdentifier("newChat.scan")
            GroupedSeparator()
            GroupedNavigationRow(
                tile: IconTile(content: .glyph(.qrCode)), title: Text("qr.mine", table: .chats),
                subtitle: Text(verbatim: "@\(model.ownUsername)"), action: onMyCode
            )
            .accessibilityIdentifier("newChat.myCode")
        }
    }
}

/// The 4.2 result card: `bg` r20, pad 16, gap 14 — avatar 52 · name
/// (`monoUsernameL`) + subtitle (`caption`, gap 3) · status glyph 22; the C1
/// in-card button below when there is something to do. While the result's
/// picture is on its way (proposed) a ring travels round the avatar; the
/// picture cross-fades in over the initial.
private struct ResultCard<Action: View>: View {
    enum Status {
        case none
        case found
        case notFound
    }

    let avatar: Avatar
    var isLoadingAvatar = false
    let name: Text
    let nameColor: Color
    let subtitle: Text
    let status: Status
    @ViewBuilder let action: Action

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 13) {
                avatar
                    .overlay {
                        if isLoadingAvatar {
                            AvatarLoadingRing(diameter: Avatar.Size.listRow.diameter)
                                .transition(.u2Fade)
                        }
                    }
                    .u2Animation(U2Motion.state, value: isLoadingAvatar)
                VStack(alignment: .leading, spacing: 3) {
                    name
                        .u2Font(.monoUsernameL)
                        .foregroundStyle(nameColor)
                        .lineLimit(1)
                    subtitle
                        .u2Font(.caption)
                        .foregroundStyle(Color.u2.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                switch status {
                case .none:
                    EmptyView()
                case .found:
                    PhosphorIcon(.checkCircle, weight: .fill, size: 22)
                        .foregroundStyle(Color.u2.online)
                case .notFound:
                    PhosphorIcon(.xCircle, weight: .fill, size: 22)
                        .foregroundStyle(Color.u2.danger)
                }
            }
            .accessibilityElement(children: .combine)
            action
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: U2Radius.cardL, style: .continuous).fill(Color.u2.bg))
        // A container of its own, so the identifier does not replace the button's.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("newChat.result")
    }
}

// MARK: - My code page (pushed inside the sheet)

private struct NewChatMyCodePage: View {
    let payload: String?
    let onBack: () -> Void
    @Environment(AppModel.self) private var app

    var body: some View {
        MyCodeSheet(username: app.messenger.ownUsername, payload: payload, closeTitle: Text("common.back"), onClose: onBack)
    }
}

#Preview("4.2 Yeni sohbet") {
    let model = AppModel.preview()
    return Color.u2.bg
        .u2Sheet(isPresented: .constant(true)) {
            NewChatSheet(
                presentation: NewChatPresentation(initialQuery: "deniz"), messenger: model.messenger,
                onOpenChat: { _ in }, onCancel: {})
        }
        .environment(model)
        .environment(\.locale, model.locale)
        .environment(\.u2Clock, model.clock)
}
