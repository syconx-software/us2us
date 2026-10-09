import SwiftUI

/// The main window's content for each root phase. Root swaps cross-fade
/// (0.5 s, the design curve).
struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ZStack {
            Color.u2.bg.ignoresSafeArea()
            switch model.phase {
            case .onboarding:
                EmptyView()
            case .locked:
                // Nothing is built until the first unlock (the vault is closed).
                Color.u2.bg.ignoresSafeArea()
            case .unlocked:
                // Leaving onboarding, 4.1 is drawn at once *under* 2.3, which
                // dissolves over it (one continuous 0.5 s cross-fade).
                // Under the lock window or the privacy cover nothing of the
                // stack can be seen: its continuous animations pause.
                MainStackView()
                    .environment(\.u2IsLayerVisible, model.overlay == .none)
                    .transition(model.isLeavingOnboarding ? .identity : .u2Fade)
            }
            // Onboarding stays in the tree (not a removal transition) until it
            // has faded out completely, so it never cuts away in one frame.
            if model.phase == .onboarding || model.isLeavingOnboarding {
                OnboardingFlowView()
                    .opacity(model.phase == .onboarding ? 1 : 0)
                    .allowsHitTesting(model.phase == .onboarding)
                    .accessibilityHidden(model.phase != .onboarding)
                    .transition(.u2Fade)
                    .zIndex(1)
            }
        }
        // Universal links (https://us2us.app/u/<username>) → InviteLinkInbox;
        // 4.1 opens them after the unlock (Features/Invite, docs/INVITES.md).
        .u2InviteLinks()
        .environment(\.locale, model.locale)
        .environment(\.u2Clock, model.clock)
        .dynamicTypeSize(...DynamicTypeSize.accessibility3)
        .onChange(of: model.interfaceStyle, initial: true) { _, style in
            applyInterfaceStyle(style)
        }
        #if DEBUG
            .overlay {
                if model.configuration.showsGallery {
                    ComponentGallery()
                }
            }
            .u2FrameProbe()
        #endif
    }

    /// The in-app theme (7.1 › Tema) is applied to every window of the scene,
    /// so UIKit-backed views, the status bar and the security window follow.
    private func applyInterfaceStyle(_ style: UIUserInterfaceStyle) {
        for scene in UIApplication.shared.connectedScenes {
            guard let windowScene = scene as? UIWindowScene else { continue }
            for window in windowScene.windows {
                window.overrideUserInterfaceStyle = style
            }
        }
        model.securityWindow?.apply(interfaceStyle: style)
    }
}

/// 04–07 · the main stack rooted at 4.1.
struct MainStackView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        SoftNavigationStack(router: model.router, allowsSwipeBack: \.allowsSwipeBack) {
            ChatsView()
        } destination: { route in
            switch route {
            case .conversation(let chat):
                ConversationView(chatID: chat)
            case .requests:
                MessageRequestsView()
            case .requestPreview(let request):
                RequestPreviewView(requestID: request)
            case .safetyNumber(let chat):
                SafetyNumberView(chatID: chat)
            case .settings:
                SettingsView()
            case .settingsPicker(let picker):
                SettingsPickerView(picker: picker)
            case .backupExport:
                BackupExportView()
            case .settingsPage(let page):
                SettingsPageView(page: page)
            }
        }
    }
}
