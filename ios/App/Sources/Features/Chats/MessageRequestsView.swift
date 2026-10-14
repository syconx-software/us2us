import SwiftUI

/// 4.3 · Mesaj istekleri — first messages from people you haven't accepted.
///
/// Nothing opens until accepted: snippets are text only, media shows as a
/// word plus the “hidden” flag, links are flagged and never tappable. The
/// sender can't tell whether the request was seen. Engelle asks first
/// (proposed alert, with “Bildir” beside it: the report sheet), Sil is
/// silent, Kabul et moves the chat to 4.1 and opens 5.1. Decided cards fade
/// and collapse (0.36 s).
struct MessageRequestsView: View {
    @Environment(AppModel.self) private var model
    @State private var triage = RequestTriageModel()
    @State private var showsMenu = false
    @State private var blockCandidate: MessageRequest?
    @State private var confirmsDeleteAll = false
    /// “Bildir” from the block confirmation: the report sheet.
    @State private var report: ReportModel?
    @State private var isScrolled = false
    /// “Kabul et” on a card: the list as it was, while this screen gives way to
    /// 5.1 (the card is not seen collapsing behind the incoming chat).
    @State private var frozenRequests: [MessageRequest]?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let messenger = model.messenger
        let requests = frozenRequests ?? triage.requests(in: messenger)
        U2Screen {
            ZStack(alignment: .top) {
                ScrollView {
                    VStack(spacing: 0) {
                        InfoBanner(message: Text("requests.banner", table: .chats))
                            .padding(EdgeInsets(top: 16, leading: 16, bottom: 0, trailing: 16))
                        if requests.isEmpty {
                            Text("requests.empty", table: .chats)
                                .u2Font(.callout)
                                .foregroundStyle(Color.u2.textSecondary)
                                .multilineTextAlignment(.center)
                                .frame(maxWidth: .infinity)
                                .padding(.top, 120)
                                .transition(.u2Fade)
                                .accessibilityIdentifier("requests.empty")
                        } else {
                            // Gap 10 lives under each card, so a removed card
                            // collapses together with its gap.
                            VStack(spacing: 0) {
                                ForEach(requests) { request in
                                    RequestCard(
                                        request: request,
                                        onOpen: { model.router.push(.requestPreview(request.id)) },
                                        onBlock: { blockCandidate = request },
                                        onDelete: { Task { await triage.delete(request, in: messenger) } },
                                        onAccept: { accept(request) }
                                    )
                                    .padding(.bottom, 10)
                                    .transition(
                                        reduceMotion ? .opacity : .asymmetric(insertion: .u2Rise, removal: .u2Collapse()))
                                }
                            }
                            // Reduce Motion: nothing glides up — the old list
                            // dissolves into the new one (0.3 s cross-fade).
                            .id(reduceMotion ? requests.map(\.id) : [])
                            .transition(.opacity)
                            .padding(EdgeInsets(top: 14, leading: 16, bottom: 30, trailing: 16))
                        }
                    }
                    .u2Top(100)
                    .u2Animation(U2Motion.state, value: requests.map(\.id))
                    .onGeometryChange(for: Bool.self) { proxy in
                        proxy.frame(in: .scrollView).minY < -2
                    } action: { scrolled in
                        isScrolled = scrolled
                    }
                }
                .scrollIndicators(.hidden)

                ChatsHeaderBackdrop(isVisible: isScrolled)
                CenteredTitleHeader(Text("chats.requests.title")) {
                    U2GlassIconButton(.caretLeft, label: Text("a11y.back"), action: model.router.pop)
                        .accessibilityIdentifier("requests.back")
                } trailing: {
                    // Nothing to act on without requests: an empty slot keeps
                    // the title centred.
                    ZStack {
                        if requests.isEmpty {
                            HeaderSpacer()
                                .transition(.u2Fade)
                        } else {
                            U2GlassIconButton(.dotsThree, label: Text("a11y.more", table: .chats)) { showsMenu.toggle() }
                                .accessibilityIdentifier("requests.more")
                                .transition(.u2Fade)
                        }
                    }
                    .u2Animation(U2Motion.state, value: requests.isEmpty)
                }
            }
            .u2Menu(
                isPresented: $showsMenu,
                items: [
                    FloatingMenuItem(id: "deleteAll", title: Text("requests.menu.deleteAll", table: .chats), glyph: .trash, tone: .destructive) {
                        confirmsDeleteAll = true
                    }
                ])
        }
        .alert(
            blockTitle, isPresented: Binding(get: { blockCandidate != nil }, set: { if !$0 { blockCandidate = nil } }),
            presenting: blockCandidate
        ) { request in
            Button(role: .destructive) {
                Task { await triage.block(request, in: model.messenger) }
            } label: {
                Text("request.block")
            }
            Button {
                openReport(request)
            } label: {
                Text("report.action")
            }
            Button(role: .cancel) {} label: { Text("common.cancel") }
        } message: { _ in
            Text("requests.block.message", table: .chats)
        }
        .u2Sheet(item: $report) { report in
            ReportSheet(model: report)
        }
        .alert(Text("requests.deleteAll.title", table: .chats), isPresented: $confirmsDeleteAll) {
            Button(role: .destructive) {
                Task { await triage.deleteAll(in: model.messenger) }
            } label: {
                Text("requests.deleteAll.confirm", table: .chats)
            }
            Button(role: .cancel) {} label: { Text("common.cancel") }
        } message: {
            Text("requests.deleteAll.message", table: .chats)
        }
        #if DEBUG
            .task {
                let requests = triage.requests(in: model.messenger)
                if ChatsHarness.current == .requestsBlock, let first = requests.first {
                    try? await Task.sleep(for: .milliseconds(600))
                    blockCandidate = first
                } else if await ChatsHarness.waitForDemo(.delete), let media = requests.first(where: { $0.id == "n_aksoy" }) {
                    await triage.delete(media, in: model.messenger)
                } else if await ChatsHarness.waitForDemo(.acceptCard), let first = requests.first {
                    accept(first)
                } else if await ChatsHarness.waitForDemo(.openCard), let first = requests.first {
                    model.router.push(.requestPreview(first.id))
                }
            }
        #endif
    }

    private var blockTitle: Text {
        if let blockCandidate {
            Text("requests.block.title \(blockCandidate.sender)", table: .chats)
        } else {
            Text(verbatim: "")
        }
    }

    /// “Bildir” beside Engelle: the report sheet once the alert has gone,
    /// “Bu kişiyi de engelle” on (the card then collapses like Engelle's).
    private func openReport(_ request: MessageRequest) {
        let messenger = model.messenger
        let triage = triage
        let subject = messenger.reportSubject(for: request) ?? ReportSubject(username: nil, isUsernameVerified: false)
        let sheet = ReportModel(subject: subject, personName: request.sender, offersBlock: true) {
            await triage.block(request, in: messenger)
        }
        sheet.onClose = { _ in report = nil }
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(450))
            report = sheet
        }
    }

    /// Kabul et → the chat moves to 4.1 and opens 5.1 in place of this screen.
    private func accept(_ request: MessageRequest) {
        // This screen leaves as it is: one animation, the push to 5.1.
        frozenRequests = triage.requests(in: model.messenger)
        Task {
            guard let chat = await triage.accept(request, in: model.messenger) else {
                frozenRequests = nil
                return
            }
            guard model.router.top == .requests else {
                frozenRequests = nil
                return
            }
            model.router.replaceTop(with: .conversation(chat))
        }
    }
}

/// A 4.3 request card: `surface` r20, pad 14 14 14 16, gap 13 — avatar 44
/// (palette C: strangers are always neutral) · username + age · snippet
/// (`requestSnippet`, `text·84%`, max 2 lines) · optional flag; the decision
/// row (40 high, r12; Kabul et at flex 1.3).
struct RequestCard: View {
    let request: MessageRequest
    let onOpen: () -> Void
    let onBlock: () -> Void
    let onDelete: () -> Void
    let onAccept: () -> Void

    @Environment(\.locale) private var locale
    @Environment(\.u2Clock) private var clock

    var body: some View {
        VStack(alignment: .leading, spacing: 13) {
            Button(action: onOpen) {
                summary
                    .contentShape(Rectangle())
            }
            .buttonStyle(CardButtonStyle())
            .accessibilityElement(children: .combine)
            .accessibilityHint(Text("a11y.request.open", table: .chats))
            .accessibilityIdentifier("request.\(request.id)")

            U2FlexRow(spacing: 8) {
                Button(action: onBlock) { Text("request.block") }
                    .buttonStyle(U2DecisionButtonStyle(kind: .destructive))
                    .accessibilityIdentifier("request.\(request.id).block")
                Button(action: onDelete) { Text("request.delete") }
                    .buttonStyle(U2DecisionButtonStyle(kind: .neutral))
                    .accessibilityIdentifier("request.\(request.id).delete")
                Button(action: onAccept) { Text("request.accept") }
                    .buttonStyle(U2DecisionButtonStyle(kind: .accept))
                    .accessibilityIdentifier("request.\(request.id).accept")
                    .u2Flex(1.3, border: 2)
            }
        }
        .padding(EdgeInsets(top: 14, leading: 16, bottom: 14, trailing: 14))
        .background(RoundedRectangle(cornerRadius: U2Radius.cardL, style: .continuous).fill(Color.u2.surface))
    }

    private var summary: some View {
        HStack(alignment: .top, spacing: 12) {
            Avatar(username: request.sender, palette: .c, size: .request)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    Text(verbatim: request.sender)
                        .u2Font(.monoUsername)
                        .foregroundStyle(Color.u2.text)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    Spacer(minLength: 0)
                    Text(verbatim: ChatsFormat.requestAge(request.receivedAt, now: clock.now, locale: locale))
                        .u2Font(.meta)
                        .foregroundStyle(Color.u2.textSecondary)
                        .lineLimit(1)
                        .fixedSize()
                }
                Group {
                    if case .text(let text) = request.preview {
                        CSSLineClampText(text: text, style: .requestSnippet, lines: 2)
                    } else {
                        snippet
                            .u2Font(.requestSnippet)
                            .lineLimit(2)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .foregroundStyle(Color.u2.textStrong)
                if let flag {
                    flag
                }
            }
        }
    }

    /// The first message as plain, inert text — or a word for media.
    private var snippet: Text {
        switch request.preview {
        case .text(let text): Text(verbatim: text)
        case .photo, .photoViewOnce: Text("media.photo")
        case .video: Text("media.video")
        case .voice: Text("media.voice")
        case .file: Text("media.file", table: .chats)
        case .location: Text("media.location")
        case .liveLocation, .liveLocationEnded: Text("location.live")
        case .sticker: Text("chats.preview.sticker")
        case .disappearing: Text("chats.preview.disappearing")
        case .deleted: Text("message.deleted")
        }
    }

    private var flag: FlagTag? {
        if request.flags.contains(.hasLink) {
            return FlagTag(glyph: .warning, text: Text("requests.flag.link", table: .chats), color: Color.u2.danger)
        }
        if request.flags.contains(.suspicious) {
            return FlagTag(glyph: .warning, text: Text("requests.flag.suspicious", table: .chats), color: Color.u2.danger)
        }
        if request.flags.contains(.hasMedia) {
            return FlagTag(glyph: .eyeSlash, text: Text("requests.flag.media", table: .chats))
        }
        return nil
    }
}

#Preview("4.3 Mesaj istekleri") {
    let model = AppModel.preview()
    return MessageRequestsView()
        .environment(model)
        .environment(\.locale, model.locale)
        .environment(\.u2Clock, model.clock)
}
