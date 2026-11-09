import SwiftUI

/// 01–02 · Onboarding: the paged welcome (1.1–1.3), then the setup steps
/// (2.1–2.3) pushed on a soft stack; “Yedekten geri yükle” hands off to 7.3
/// and comes back for a new secret code (2.2 ×2 → 2.3 → 4.1). Every way in
/// asks for the Terms of Use first, until they are accepted on this phone
/// (`OnboardingTermsGate`, `TermsSheet`).
///
/// All state lives in `OnboardingFlowModel` behind `MessengerService`
/// (fixtures today, the live service later).
struct OnboardingFlowView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        OnboardingFlowHost(appModel: model)
    }
}

private struct OnboardingFlowHost: View {
    @State private var flow: OnboardingFlowModel
    @State private var terms: OnboardingTermsGate
    @Environment(\.locale) private var locale
    private let focusesUsernameField: Bool
    #if DEBUG
        private let harness = OnboardingHarness.fromLaunchArguments()
    #endif

    init(appModel: AppModel) {
        let flow = OnboardingFlowModel(
            messenger: appModel.messenger,
            onFinished: { [weak appModel] in appModel?.didFinishOnboarding() },
            onReturnToLock: { [weak appModel] in appModel?.returnToLock() })
        switch appModel.onboardingEntry {
        case .newCodeAfterRestore: flow.continueAfterRestore()
        case .restoreFromBackup: flow.startRestore()
        case .welcome: break
        }
        var focuses = true
        #if DEBUG
            if let harness = OnboardingHarness.fromLaunchArguments() {
                harness.apply(to: flow)
                focuses = harness.focusesUsernameField
            }
        #endif
        _flow = State(initialValue: flow)
        let preferences = appModel.preferences
        _terms = State(
            initialValue: OnboardingTermsGate(
                isAccepted: { preferences.hasAcceptedCurrentTerms }, accept: { preferences.acceptTerms() },
                perform: { [weak flow] start in flow?.start(start) }))
        focusesUsernameField = focuses
    }

    var body: some View {
        SoftNavigationStack(router: flow.router, allowsSwipeBack: flow.allowsSwipeBack) {
            WelcomePagerView(
                pager: flow.pager, onCreateAccount: { terms.request(.create) }, onRestore: { terms.request(.restore) },
                onTransfer: { terms.request(.transfer) })
        } destination: { route in
            destination(route)
        }
        .onChange(of: flow.router.path) { old, new in
            flow.pathChanged(from: old, to: new)
        }
        .u2Sheet(item: $terms.pending, onDismiss: terms.sheetDidDismiss) { _ in
            TermsSheet(mode: .beforeStart, onAgree: terms.agree, onDecline: terms.decline)
                .environment(\.locale, locale)
        }
        #if DEBUG
            .task {
                if let harness, harness.isDemo { await harness.runDemo(flow) }
            }
        #endif
    }

    @ViewBuilder private func destination(_ route: OnboardingRoute) -> some View {
        switch route {
        case .username:
            UsernameStepView(
                model: flow.username, step: flow.stepLabel(for: route), focusesOnAppear: focusesUsernameField,
                onBack: flow.router.pop, onContinue: flow.usernameContinue)
        case .secretCode:
            SecretCodeStepView(model: flow.code, step: flow.stepLabel(for: route), onBack: flow.codeBack)
        case .noWayBack:
            NoWayBackStepView(model: flow.finish, step: flow.stepLabel(for: route), onBack: flow.router.pop)
        case .restore:
            OnboardingRestoreHook(onCancel: flow.router.pop, onFinished: flow.restoreFinished)
        case .transferReceive:
            DeviceTransferReceiveView(onCancel: flow.router.pop, onFinished: flow.restoreFinished)
        }
    }
}
