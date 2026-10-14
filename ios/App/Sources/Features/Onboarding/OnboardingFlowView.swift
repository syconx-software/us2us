import SwiftUI

/// 01–02 · Onboarding: the paged welcome (1.1–1.3), then the setup steps
/// (2.1–2.3) pushed on a soft stack; “Yedekten geri yükle” hands off to 7.3
/// and comes back for a new secret code (2.2 ×2 → 2.3 → 4.1).
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
        focusesUsernameField = focuses
    }

    var body: some View {
        SoftNavigationStack(router: flow.router, allowsSwipeBack: flow.allowsSwipeBack) {
            WelcomePagerView(pager: flow.pager, onCreateAccount: flow.startSetup, onRestore: flow.startRestore)
        } destination: { route in
            destination(route)
        }
        .onChange(of: flow.router.path) { old, new in
            flow.pathChanged(from: old, to: new)
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
        }
    }
}
