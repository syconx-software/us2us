import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// 03 · Kilit — shown in the security window on every launch and every
/// return from the background (`AppModel.overlay == .lock`).
///
/// 3.1 Face ID (when enabled and enrolled) or 3.2 the code; 3.3 “Kodu
/// unuttum” as a floating sheet; 3.3 › restore hands the chosen backup file
/// to 7.3 inside the lock. The lock's background is in place at once (it must
/// hide everything before the first frame); its content fades in (0.36 s).
/// Screens swap with a 0.36 s cross-fade; nothing bounces.
struct LockFlowView: View {
    @Environment(AppModel.self) private var app
    @State private var flow: LockFlowModel?
    #if DEBUG
        @State private var harness = LockHarness.fromLaunchArguments()
    #endif

    var body: some View {
        ZStack {
            Color.u2.bg.ignoresSafeArea()
            if let flow {
                LockFlowContent(flow: flow)
            }
            #if DEBUG
                if let harness, harness.replacesLock {
                    LockHarnessScreen(state: harness)
                }
            #endif
        }
        .onAppear(perform: makeFlowIfNeeded)
    }

    private func makeFlowIfNeeded() {
        guard flow == nil else { return }
        let messenger = app.messenger
        var frozen = false
        var biometry = messenger.settings.faceIDEnabled ? Biometry.enrolled : .none
        #if DEBUG
            if let harness {
                frozen = true
                biometry = harness.biometry ?? biometry
            }
        #endif
        let model = LockFlowModel(
            messenger: messenger, biometry: biometry, isFrozenForHarness: frozen,
            onUnlock: { [app] in app.didUnlock() },
            onErase: { [app] in await app.eraseEverything() })
        #if DEBUG
            harness?.apply(to: model, messenger: messenger)
        #endif
        flow = model
    }
}

/// The lock's screens, the 3.3 sheet and the backup picker.
private struct LockFlowContent: View {
    let flow: LockFlowModel
    @Environment(AppModel.self) private var app
    @Environment(\.locale) private var locale
    @State private var contentShown = false

    var body: some View {
        @Bindable var flow = flow
        ZStack {
            switch flow.stage {
            case .biometric:
                LockBiometricScreen(flow: flow)
                    .transition(.u2Fade)
            case .code:
                LockCodeScreen(flow: flow, showsDemoHint: app.configuration.usesFixtures && !app.configuration.frozenClock)
                    .transition(.u2Fade)
            case .restore(let file):
                // 3.3 › “Yedekten geri yükle” → 7.3 with the chosen file, inside
                // the lock; when it is done, a new code (2.2 ×2 → 2.3 → 4.1).
                RestoreBackupView(
                    file: file, opensPickerOnAppear: false, onClose: flow.leaveRestore,
                    onFinished: { [app] in app.continueAfterRestore() }
                )
                .transition(.u2Push)
            }
        }
        // “cover present instantly; content inside fades in 0.36 s”
        .opacity(contentShown ? 1 : 0)
        .onAppear {
            withU2Animation(U2Motion.state) { contentShown = true }
            if UIApplication.isActive { flow.appBecameActive(locale: locale) }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
            flow.appBecameActive(locale: locale)
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
            flow.relock()
        }
        .u2Sheet(isPresented: $flow.showsForgot, style: .floating, onDismiss: flow.forgotDidDismiss) {
            LockForgotSheet(flow: flow)
        }
        #if DEBUG
            .task { await LockHarness.runDemo(flow) }
        #endif
        .fileImporter(
            isPresented: $flow.showsBackupPicker, allowedContentTypes: [.us2usBackup],
            allowsMultipleSelection: false
        ) { result in
            if case .success(let urls) = result, let url = urls.first {
                flow.backupChosen(url)
            }
        }
    }
}

#Preview("03 · Lock") {
    LockFlowView()
        .environment(AppModel.preview(start: .locked))
}
