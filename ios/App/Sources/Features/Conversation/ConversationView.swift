import SwiftUI
import UIKit

/// 05 · Sohbet — the conversation (5.1, and 5.2 for the content catalogue).
///
/// C8 header (back · avatar · name + seal · presence · live Ghost chip → 5.5)
/// over the bottom-aligned bubble column (gap 6, pad 0 14 100) and the C21
/// composer, which follows the keyboard. Only the newest outgoing message says
/// its state as a sentence; earlier ones show the icon. Long-press a bubble →
/// 5.3; Bilgi → 5.4; a view-once bubble → 5.6; a photo → the viewer, a video
/// → the player, a file → Quick Look; the urgent banner floats above
/// the composer in Ghost's last hour, and a changed safety number keeps a
/// banner there until the user decides (an unverified chat starts with a
/// prompt to verify; both open 6.1). The mic records a voice message
/// (press and hold; proposed), a voice bubble plays one; the screen stays
/// on while either goes on. New bubbles fade
/// in with an 8 pt rise (0.36 s ease-out); every change cross-fades —
/// nothing bounces.
struct ConversationView: View {
    let chatID: ChatID
    @Environment(AppModel.self) private var model

    var body: some View {
        ConversationScreen(chatID: chatID, messenger: model.messenger, saveNotice: model.preferences)
    }
}

struct ConversationScreen: View {
    private struct Session: Hashable {
        let chatID: ChatID
        let community: ObjectIdentifier?
        let pickerIsPresented: Bool
    }
    @StateObject private var modelOwner: ViewModelOwner<ConversationViewModel>
    private var viewModel: ConversationViewModel { modelOwner.model }
    @State private var isKeyboardVisible = false
    @State private var composerFrame: CGRect = .zero
    /// Communities: members and management (the header's “…”).
    @State private var showsManage = false
    @Environment(AppModel.self) private var model
    @Environment(\.u2SafeArea) private var safeArea
    @Environment(\.locale) private var locale
    @Environment(\.colorScheme) private var colorScheme

    init(chatID: ChatID, messenger: any MessengerService, saveNotice: any SaveNoticeMemory) {
        _modelOwner = StateObject(wrappedValue: ViewModelOwner {
            ConversationSignposts.beginOpen(chatID)
            return ConversationViewModel(chatID: chatID, messenger: messenger, saveNotice: saveNotice)
        })
    }

    var body: some View {
        @Bindable var viewModel = modelOwner.model
        return U2Screen {
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
        .modifier(MessageInvitationModifier())
        .onChange(of: viewModel.actionsTargetIsGone) { _, gone in
            if gone { viewModel.clearMissingActions() }
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
        // The composer's sticker key (proposed): the built-in stickers in a
        // sheet that hugs its content; a sticker sent closes it.
        .u2Sheet(isPresented: $viewModel.showsStickerPicker, style: .content) {
            BrandStickerPicker(
                recents: viewModel.stickerRecents, onSend: viewModel.send(sticker:),
                onClose: viewModel.closeStickerPicker)
        }
        .u2Sheet(item: $viewModel.info, style: .fullHeight) { item in
            if let community = viewModel.community {
                CommunityMessageInfoSheet(viewModel: viewModel, community: community, messageID: item.id)
            } else {
                MessageInfoSheet(viewModel: viewModel, messageID: item.id)
            }
        }
        #if DEBUG
            .task {
                if ConversationHarness.screen == .communityManage, viewModel.community != nil {
                    try? await Task.sleep(for: .milliseconds(400))
                    showsManage = true
                }
            }
        #endif
        .u2Sheet(isPresented: $showsManage) {
            if let community = viewModel.community {
                CommunityManageSheet(communityID: community.communityID) { showsManage = false } onGone: {
                    showsManage = false
                    model.router.pop()
                }
                .environment(\.locale, locale)
                .environment(\.u2Clock, model.clock)
            }
        }
        .u2FadeCover(item: $viewModel.viewer) { item in
            Group {
                switch item.kind {
                case .viewOnce:
                    ViewOnceViewer(item: item, themeScheme: colorScheme) {
                        viewModel.viewerDidDetectScreenshot(item)
                    } onClose: {
                        viewModel.closeViewer()
                    }
                case .photo:
                    ConversationPhotoViewer(item: item, viewModel: viewModel, themeScheme: colorScheme, onClose: viewModel.closeViewer)
                case .video:
                    VideoPlayerScreen(item: item, viewModel: viewModel, themeScheme: colorScheme, onClose: viewModel.closeViewer)
                }
            }
            .environment(\.locale, locale)
            .environment(\.u2Clock, model.clock)
        }
        // A file Quick Look will not show (save it instead), or that failed.
        .u2Sheet(item: $viewModel.fileNotice, style: .floating, onDismiss: viewModel.fileNoticeDidDismiss) { notice in
            FileNoticeSheet(notice: notice, onSave: viewModel.saveNoticeFile, onClose: viewModel.closeFileNotice)
        }
        // 5.2 › konum (proposed): a place opens in Apple Maps or Google Maps
        // from the floating chooser; a running live share opens the map.
        .u2Sheet(item: $viewModel.locationChoice, style: .floating, onDismiss: viewModel.locationChoiceDidDismiss) { choice in
            LocationOpenSheet(choice: choice, onChoose: viewModel.chooseMapApp, onCancel: viewModel.closeLocationChoice)
        }
        .u2FadeCover(item: $viewModel.liveMap, onEscape: viewModel.closeLiveMap) { item in
            LiveLocationMapView(
                chatID: viewModel.chatID, focus: item.id, messenger: viewModel.messenger, onClose: viewModel.closeLiveMap
            )
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
                // A community: copies already downloaded or saved cannot be recalled.
                message: viewModel.community != nil
                    ? Text("confirm.deleteEveryone.message", table: .communities)
                    : Text("confirm.deleteEveryone.message", table: .conversation),
                confirmTitle: Text("actions.deleteEveryone", table: .conversation),
                confirmGlyph: .trash,
                identifiers: ("conversation.deleteEveryone.confirm", "conversation.deleteEveryone.cancel"),
                onConfirm: viewModel.confirmDeleteForEveryone,
                onCancel: { viewModel.deleteForEveryoneCandidate = nil }
            )
            .padding(EdgeInsets(top: 18, leading: 18, bottom: 28, trailing: 18))
        }
        // The microphone was refused: why, and the way to Settings.
        .u2Sheet(
            isPresented: Binding(
                get: { viewModel.voice.showsPermissionNotice },
                set: { viewModel.voice.showsPermissionNotice = $0 }),
            style: .floating
        ) {
            VoicePermissionSheet { viewModel.voice.showsPermissionNotice = false }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in
            isKeyboardVisible = true
            viewModel.dismissAttachMenu()
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
            isKeyboardVisible = false
        }
        // Ordinary media closes with the app (the lock follows) or when its
        // message goes; a file tapped while it downloaded opens once it is here.
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)) { _ in
            viewModel.closeMedia()
            viewModel.closeLocationScreens()
            // A recording and a voice message in memory go with the foreground too.
            viewModel.stopVoice()
        }
        .onChange(of: viewModel.presentedMediaIsGone) { _, isGone in
            if isGone { viewModel.closeMedia() }
        }
        .onChange(of: viewModel.playingVoiceIsGone) { _, isGone in
            if isGone { viewModel.stopVoicePlayback() }
        }
        .onChange(of: viewModel.pendingFileIsReady) { _, isReady in
            if isReady { viewModel.openPendingFile() }
        }
        // Blocked while the chat is open (from the contact screen): the
        // composer goes, and with it the keyboard and whatever it had open.
        .onChange(of: viewModel.isBlocked) { _, isBlocked in
            if isBlocked { viewModel.leaveComposer() }
        }
        .environment(viewModel.mediaActivity)
        .environment(viewModel.voicePlayer)
        .onChange(of: locale, initial: true) { _, locale in viewModel.presentationLocale = locale }
        // The chat ended underneath (Ghost erased it on both ends, or it was
        // removed elsewhere): leave softly instead of showing an empty shell —
        // also when this screen is on top again after it went under another
        // one (the contact screen).
        .onChange(of: viewModel.chat == nil && model.router.top == .conversation(viewModel.chatID)) { _, isOrphaned in
            if isOrphaned { model.router.pop() }
        }
        .background { VoiceKeepsScreenAwake(viewModel: viewModel) }
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
                ConversationMessageList(viewModel: viewModel) { showsLatest, returnToLatest in
                    composer(keyboard: keyboard, showsLatest: showsLatest, returnToLatest: returnToLatest)
                }
            }
            .onAppear { ConversationSignposts.didAppear(viewModel.chatID) }
            .onDisappear {
                ConversationSignposts.didDisappear(viewModel.chatID)
                viewModel.stopVoice()
                viewModel.messenger.conversationDisappeared(viewModel.chatID)
            }
            .task(id: Session(
                chatID: viewModel.chatID, community: viewModel.community?.sessionID,
                pickerIsPresented: viewModel.community != nil && SystemPickerActivity.shared.isPresented)
            ) {
                // Restore the retained community even if UIKit coverage did not restart
                // SwiftUI's appearance task. While covered, do not mark new messages read.
                if viewModel.community != nil && SystemPickerActivity.shared.isPresented { return }
                viewModel.loadStickerRecents()
                await viewModel.messenger.conversationAppeared(viewModel.chatID)
            }
            header
            if let community = viewModel.community, let problem = community.problem {
                CommunityProblemNote(problem: problem, onDismiss: community.dismissProblem)
                    .padding(.horizontal, U2Spacing.chatGutter)
                    .u2Top(116)
                    .transition(.u2Fade)
            }
        }
        .u2Animation(U2Motion.state, value: viewModel.community?.problem)
    }

    @ViewBuilder private var header: some View {
        if let community = viewModel.community {
            CommunityConversationHeader(context: community, onBack: model.router.pop) { showsManage = true }
        } else if let chat = viewModel.chat {
            ChatHeader(
                username: chat.peer.username, palette: chat.peer.palette, isVerified: chat.peer.isVerified,
                showsOnlineDot: chat.peer.isOnline, avatar: chat.peer.avatar, onBack: model.router.pop,
                onTapPeer: { model.router.push(.safetyNumber(viewModel.chatID)) },
                peerHint: Text("a11y.header.peer.hint", table: .conversation)
            ) {
                if chat.peer.isBlocked {
                    Text("block.blocked")
                        .transition(.u2Fade)
                } else {
                    PresenceLine(presence: chat.peer.isAcceptedContact ? chat.peer.presence : .hidden)
                }
            } trailing: {
                // Blocked: shown as it is, but nothing can be changed (nothing reaches them).
                GhostChip(ghost: chat.ghost, action: viewModel.openGhostSettings)
                    .disabled(chat.peer.isBlocked)
                    .opacity(chat.peer.isBlocked ? U2Opacity.disabled : 1)
                    .accessibilityIdentifier("conversation.ghost")
            }
        }
    }

    // MARK: Composer

    private func composer(keyboard: CGFloat, showsLatest: Bool, returnToLatest: @escaping () -> Void) -> some View {
        VStack(spacing: 6) {
            if let community = viewModel.community, community.row?.isOpenable != true {
                // Removed, left elsewhere or deleted by its owner: read only.
                Text("conversation.ended", table: .communities)
                    .u2Font(.callout)
                    .foregroundStyle(Color.u2.textSecondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, U2Spacing.screenGutter)
                    .padding(.top, 14)
                    .padding(.bottom, max(12, safeArea.bottom(36)))
                    .background(Color.u2.composerFade.allowsHitTesting(false))
                    .accessibilityIdentifier("community.ended")
            } else if viewModel.isBlocked, let chat = viewModel.chat {
                // Blocked (proposed): the bar takes the composer's place, as
                // 4.4's decision bar does; nothing is sent from here.
                BlockedChatBar(username: chat.peer.username, onUnblock: viewModel.unblock, bottomPadding: safeArea.bottom(40))
                    .transition(.u2Fade)
            } else {
                // A changed safety number holds sending until the user decides
                // (proposed): the banner stays above the composer, in the
                // column's inset, so it never covers the newest messages.
                if viewModel.showsSafetyNumberChanged {
                    SafetyNumberChangedBanner { model.router.push(.safetyNumber(viewModel.chatID)) }
                        .padding(.horizontal, 12)
                        .transition(.u2Rise)
                }
                ConversationComposerBar(viewModel: viewModel, bottomPadding: max(8, safeArea.bottom(36) - keyboard))
                    .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { composerFrame = $0 }
                    .transition(.u2Fade)
            }
        }
        .u2Animation(U2Motion.state, value: viewModel.showsSafetyNumberChanged)
        .u2Animation(U2Motion.state, value: viewModel.isBlocked)
        .overlay(alignment: .top) {
            VStack(alignment: .trailing, spacing: 6) {
                if showsLatest {
                    ConversationLatestButton(action: returnToLatest)
                        .padding(.trailing, 14)
                        .transition(.u2Soft(offset: .zero, blur: 0, scale: 0.94))
                }
                urgentBanner
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
            .alignmentGuide(.top) { $0[.bottom] + 6 }
            .u2Animation(U2Motion.micro, value: showsLatest)
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
        if viewModel.community != nil {
            // Communities: photos, videos and files.
            return [
                FloatingMenuItem(id: "photo", title: Text("attach.media", table: .conversation), glyph: .image) {
                    viewModel.sendDemoAttachment(.photo)
                },
                FloatingMenuItem(id: "file", title: Text("attach.file", table: .conversation), glyph: .filePdf) {
                    viewModel.sendDemoAttachment(.file)
                },
            ]
        }
        return [
            FloatingMenuItem(id: "photo", title: Text("attach.media", table: .conversation), glyph: .image) {
                viewModel.sendDemoAttachment(.photo)
            },
            FloatingMenuItem(id: "file", title: Text("attach.file", table: .conversation), glyph: .filePdf) { viewModel.sendDemoAttachment(.file) },
            FloatingMenuItem(id: "location", title: Text("media.location"), glyph: .mapPin, glyphWeight: .fill) { viewModel.sendDemoAttachment(.location) },
            FloatingMenuItem(id: "liveLocation", title: Text("location.live"), glyph: .navigationArrow) {
                viewModel.sendDemoAttachment(.liveLocation)
            },
        ]
    }
}

/// Keeps the screen on while a voice message records or plays (auto-lock
/// would send the app to the background, which throws a hands-free
/// recording away and stops playback), and lets it sleep again when that
/// ends or the chat closes. Only this view reads the state, so recording
/// and playing never re-render the screen itself.
private struct VoiceKeepsScreenAwake: View {
    let viewModel: ConversationViewModel
    @State private var owner = UUID()

    var body: some View {
        Color.clear
            .allowsHitTesting(false)
            .accessibilityHidden(true)
            .onChange(of: viewModel.keepsScreenAwake, initial: true) { _, awake in
                ScreenAwakeController.shared.setMediaActive(awake, owner: owner)
            }
            .onDisappear { ScreenAwakeController.shared.setMediaActive(false, owner: owner) }
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
        let isCommunity = viewModel.community != nil
        Composer(
            text: draft, quote: viewModel.replyTo, onCancelQuote: viewModel.cancelReply,
            onSend: viewModel.sendDraft, onAttach: viewModel.toggleAttachMenu, onTimer: viewModel.openGhostSettings,
            onSticker: viewModel.openStickerPicker, onRecord: {},
            bottomPadding: bottomPadding, voice: viewModel.voice,
            showsTimer: !isCommunity
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
    return ConversationView(chatID: "mert_k")
        .environment(model)
        .environment(\.locale, model.locale)
        .environment(\.u2Clock, model.clock)
}
