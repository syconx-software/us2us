import SwiftUI
import UIKit

/// 05 · Sohbet — the conversation (5.1, and 5.2 for the content catalogue).
///
/// C8 header (back · avatar · name + seal · presence · live Ghost chip → 5.5)
/// over the bottom-aligned bubble column (gap 6, pad 0 14 100) and the C21
/// composer, which follows the keyboard. Only the newest outgoing message says
/// its state as a sentence; earlier ones show the icon. Long-press a bubble →
/// 5.3; Bilgi → 5.4; a view-once bubble → 5.6; the urgent banner floats above
/// the composer in Ghost's last hour. New bubbles fade in with an 8 pt rise
/// (0.36 s ease-out); every change cross-fades — nothing bounces.
struct ConversationView: View {
    let chatID: ChatID
    @Environment(AppModel.self) private var model

    var body: some View {
        ConversationScreen(chatID: chatID, messenger: model.messenger)
    }
}

private struct ConversationScreen: View {
    @State private var viewModel: ConversationViewModel
    @State private var isKeyboardVisible = false
    @State private var composerFrame: CGRect = .zero
    @Environment(AppModel.self) private var model
    @Environment(\.u2SafeArea) private var safeArea
    @Environment(\.locale) private var locale
    @Environment(\.colorScheme) private var colorScheme

    init(chatID: ChatID, messenger: any MessengerService) {
        ConversationSignposts.beginOpen(chatID)
        _viewModel = State(initialValue: ConversationViewModel(chatID: chatID, messenger: messenger))
    }

    var body: some View {
        U2Screen {
            ZStack(alignment: .top) {
                chatLayer
                    .blur(radius: viewModel.actionsVisible ? 14 : 0)
                    .opacity(viewModel.actionsVisible ? U2Opacity.contextBackdropContent : 1)
                    .allowsHitTesting(viewModel.actions == nil)
                    .accessibilityHidden(viewModel.actions != nil)

                if viewModel.actions != nil {
                    Color.u2.actionsOverlay
                        .ignoresSafeArea()
                        .opacity(viewModel.actionsVisible ? 1 : 0)
                        .allowsHitTesting(false)
                }

                if let context = viewModel.actions, let message = viewModel.message(context.messageID) {
                    MessageActionsOverlay(viewModel: viewModel, context: context, message: message)
                }

                if viewModel.showsAttachMenu {
                    attachMenuLayer
                }
            }
        }
        .u2Sheet(isPresented: $viewModel.showsGhostSettings, style: .content, onDismiss: viewModel.commitGhostChange) {
            if let chat = viewModel.chat {
                GhostSettingsSheet(chat: chat) { enabled, period, disappearing in
                    viewModel.saveGhost(enabled: enabled, period: period, disappearing: disappearing)
                } onClose: {
                    viewModel.showsGhostSettings = false
                }
            }
        }
        .u2Sheet(item: $viewModel.info, style: .fullHeight) { item in
            MessageInfoSheet(viewModel: viewModel, messageID: item.id)
        }
        .u2FadeCover(item: $viewModel.viewer) { item in
            ViewOnceViewer(item: item, themeScheme: colorScheme) {
                viewModel.viewerDidDetectScreenshot(item)
            } onClose: {
                viewModel.closeViewer()
            }
            .environment(\.locale, locale)
            .environment(\.u2Clock, model.clock)
        }
        // “Herkesten sil” is irreversible: the app's soft floating
        // confirmation (as 3.3, 7.1, 7.3), not a system alert.
        .u2Sheet(
            isPresented: Binding(
                get: { viewModel.deleteForEveryoneCandidate != nil },
                set: { if !$0 { viewModel.deleteForEveryoneCandidate = nil } }),
            style: .floating
        ) {
            DestructiveConfirmation(
                title: StringTable.conversation.string("confirm.deleteEveryone.title", locale: locale),
                message: Text("confirm.deleteEveryone.message", table: .conversation),
                confirmTitle: Text("actions.deleteEveryone", table: .conversation),
                confirmGlyph: .trash,
                identifiers: ("conversation.deleteEveryone.confirm", "conversation.deleteEveryone.cancel"),
                onConfirm: viewModel.confirmDeleteForEveryone,
                onCancel: { viewModel.deleteForEveryoneCandidate = nil }
            )
            .padding(EdgeInsets(top: 18, leading: 18, bottom: 28, trailing: 18))
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in
            isKeyboardVisible = true
            viewModel.dismissAttachMenu()
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
            isKeyboardVisible = false
        }
        // The chat ended underneath (Ghost erased it on both ends, or it was
        // removed elsewhere): leave softly instead of showing an empty shell.
        .onChange(of: viewModel.chat == nil) { _, isGone in
            if isGone, model.router.top == .conversation(viewModel.chatID) { model.router.pop() }
        }
        #if DEBUG
            .modifier(ConversationHarnessModifier(viewModel: viewModel))
        #endif
    }

    // MARK: Chat

    private var chatLayer: some View {
        ZStack(alignment: .top) {
            // The column and the composer avoid the keyboard (SwiftUI moves
            // them with the keyboard's own animation and, frame by frame, with
            // its interactive dismissal). The keyboard's height is read in
            // that same layout pass, so the composer's bottom padding is
            // continuous — 36 pt above the bottom at rest, 8 pt above the
            // keyboard — instead of switching on keyboard notifications: no
            // second animation, and no dip below its rest at the end of a drag.
            GeometryReader { proxy in
                // The reader fills the screen; its children avoid the keyboard,
                // which it reports as its bottom safe-area inset (U2Screen
                // ignores the container insets, so that is the keyboard alone).
                let keyboard = proxy.safeAreaInsets.bottom
                ConversationMessageList(viewModel: viewModel)
                    .safeAreaInset(edge: .bottom, spacing: 0) {
                        composer(keyboard: keyboard)
                    }
            }
            .onAppear { ConversationSignposts.didAppear(viewModel.chatID) }
            .onDisappear {
                ConversationSignposts.didDisappear(viewModel.chatID)
                viewModel.messenger.conversationDisappeared(viewModel.chatID)
            }
            .task(id: viewModel.chatID) { await viewModel.messenger.conversationAppeared(viewModel.chatID) }
            header
        }
    }

    @ViewBuilder private var header: some View {
        if let chat = viewModel.chat {
            ChatHeader(
                username: chat.peer.username, palette: chat.peer.palette, isVerified: chat.peer.isVerified,
                showsOnlineDot: chat.peer.isOnline, onBack: model.router.pop,
                onTapPeer: { model.router.push(.safetyNumber(viewModel.chatID)) }
            ) {
                PresenceLine(presence: chat.peer.isAcceptedContact ? chat.peer.presence : .hidden)
            } trailing: {
                GhostChip(ghost: chat.ghost, action: viewModel.openGhostSettings)
                    .accessibilityIdentifier("conversation.ghost")
            }
        }
    }

    // MARK: Composer

    private func composer(keyboard: CGFloat) -> some View {
        ConversationComposerBar(viewModel: viewModel, bottomPadding: max(8, safeArea.bottom(36) - keyboard))
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { composerFrame = $0 }
        .overlay(alignment: .top) {
            urgentBanner
        }
    }

    /// 5.1 urgent banner (Ghost's last hour): 6 pt above the composer, 12 pt
    /// gutters; cross-fades in over 0.8 s with an 8 pt rise when the hour starts.
    private var urgentBanner: some View {
        U2TimeView(interval: 1) { now in
            Group {
                if let chat = viewModel.chat, chat.ghost.isUrgent(at: now), let deadline = chat.ghost.deadline {
                    GhostUrgentBanner(deadline: deadline)
                        .padding(.horizontal, 12)
                        .transition(.u2Rise)
                }
            }
            .u2Animation(U2Motion.ambient, value: viewModel.chat?.ghost.isUrgent(at: now) ?? false)
        }
        .alignmentGuide(.top) { $0[.bottom] + 6 }
    }

    // MARK: “+” menu (proposed)

    /// Photo · File · Location · Live location, floating 8 pt above “+”
    /// in the 5.3 menu style; tap outside closes.
    private var attachMenuLayer: some View {
        let items = attachItems
        let height = FloatingMenu.height(of: items)
        let bottomPadding = isKeyboardVisible ? 8 : safeArea.bottom(36)
        let plusTop = composerFrame.maxY - bottomPadding - U2Size.composer
        let width: CGFloat = 220
        return ZStack(alignment: .topLeading) {
            Color.clear
                .contentShape(Rectangle())
                .onTapGesture(perform: viewModel.dismissAttachMenu)
                .accessibilityHidden(true)
            FloatingMenu(items: items, width: width)
                .transition(.u2Soft(offset: CGSize(width: 0, height: 8), blur: 2, scale: 0.96))
                .position(x: U2Spacing.chatComposerGutter + width / 2, y: plusTop - 8 - height / 2)
        }
        .ignoresSafeArea()
        .accessibilityAddTraits(.isModal)
        .accessibilityAction(.escape, viewModel.dismissAttachMenu)
    }

    private var attachItems: [FloatingMenuItem] {
        [
            FloatingMenuItem(id: "photo", title: Text("media.photo"), glyph: .image) { viewModel.sendDemoAttachment(.photo) },
            FloatingMenuItem(id: "file", title: Text("attach.file", table: .conversation), glyph: .filePdf) { viewModel.sendDemoAttachment(.file) },
            FloatingMenuItem(id: "location", title: Text("media.location"), glyph: .mapPin, glyphWeight: .fill) { viewModel.sendDemoAttachment(.location) },
            FloatingMenuItem(id: "liveLocation", title: Text("location.live"), glyph: .navigationArrow) {
                viewModel.sendDemoAttachment(.liveLocation)
            },
        ]
    }
}

/// The C21 composer bound to the conversation. It reads the draft itself, so
/// typing re-renders only the composer — never the message column.
private struct ConversationComposerBar: View {
    let viewModel: ConversationViewModel
    /// From the physical bottom: the design's 36, or 8 above the keyboard.
    let bottomPadding: CGFloat

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        // Each edit runs in a soft transaction: when the text wraps to a new
        // line (or a send clears it), the field grows (or shrinks) smoothly
        // and the column above moves with it. Up to five lines, then the
        // field scrolls (C21).
        let viewModel = viewModel
        let animation: Animation? = reduceMotion ? nil : U2Motion.micro
        let draft = Binding(
            get: { viewModel.draft },
            set: { text in withAnimation(animation) { viewModel.draft = text } })
        Composer(
            text: draft, quote: viewModel.replyTo, onCancelQuote: viewModel.cancelReply,
            onSend: viewModel.sendDraft, onAttach: viewModel.toggleAttachMenu, onTimer: viewModel.openGhostSettings,
            onSticker: viewModel.sendSticker, onRecord: {},
            bottomPadding: bottomPadding
        )
    }
}

/// The header status line: “Aktif” with the 7 pt dot, “Az önce aktifti”…
struct PresenceLine: View {
    let presence: Presence
    @Environment(\.locale) private var locale
    @Environment(\.u2Clock) private var clock

    var body: some View {
        switch presence {
        case .hidden:
            EmptyView()
        case .online:
            HStack(spacing: 6) {
                Circle().fill(Color.u2.online).frame(width: 7, height: 7)
                Text("presence.online")
            }
        case .justNow:
            Text("presence.justNow")
        case .lastSeen(let date):
            let minutes = Int(clock.now.timeIntervalSince(date) / 60)
            if minutes < 60 {
                Text("presence.minutesAgo \(max(1, minutes))")
            } else {
                Text("presence.todayAt \(U2Format.clockTime(date, locale: locale))")
            }
        }
    }
}

#Preview("5.1 Sohbet") {
    let model = AppModel.preview()
    return ConversationView(chatID: "deniz")
        .environment(model)
        .environment(\.locale, model.locale)
        .environment(\.u2Clock, model.clock)
}

#Preview("5.2 Medya ve konum") {
    let model = AppModel.preview()
    return ConversationView(chatID: "mert.k")
        .environment(model)
        .environment(\.locale, model.locale)
        .environment(\.u2Clock, model.clock)
}
