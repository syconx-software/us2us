import SwiftUI
import UIKit

/// 7.1 › Hesap › “Her şeyi sil” (App Review 5.1.1(v): the account is
/// deleted in the app).
///
/// 1. “Hesabın silinsin mi?” › “Hesabı ve her şeyi sil”: the account is
///    deleted on the relay first, authenticated, while the danger button
///    spins.
/// 2. The relay confirmed: the sheet goes down, then everything on this
///    phone is erased (`AppModel.eraseEverything`, with the preview folder)
///    and the app cross-fades to 1.1. Never erased under a sheet that is
///    still on screen.
/// 3. The relay did not confirm (offline, a timeout, a server error):
///    nothing is erased — erasing first would destroy the only credentials
///    that can delete the account. The same sheet cross-fades to
///    “Hesap silinemedi.” with “Tekrar dene” and “Yalnızca bu telefondan
///    sil” (the account then stays on the relay until it is removed after
///    180 days without a connection, PROTOCOL §4.10).
@Observable
@MainActor
final class EraseAccountModel {
    enum Step: Hashable {
        /// “Hesabın silinsin mi?”
        case confirm
        /// “Hesap silinemedi.” — try again, or erase this phone only.
        case failed
    }

    /// What the sheet's dismissal erases.
    enum Erasure: Equatable {
        /// The relay deleted the account: this phone follows.
        case accountDeleted
        /// The user leaves the account on the relay.
        case thisPhoneOnly
    }

    var isPresented = false
    private(set) var step: Step = .confirm
    /// The relay request runs, or the erase is decided: the button spins,
    /// the other controls ignore taps and the sheet cannot be swiped away.
    private(set) var isWorking = false
    /// Decided; performed once the sheet has gone.
    private(set) var erasure: Erasure?
    /// The failure step's controls ignore taps until its cross-fade has
    /// finished (no double tap through the danger button onto the card).
    private(set) var isArmed = false
    /// Failed attempts in this presentation (each one is announced).
    private(set) var failures = 0

    private let messenger: any MessengerService
    private let pause: Duration
    private let armDelay: Duration
    @ObservationIgnored private var armTask: Task<Void, Never>?

    /// - Parameters:
    ///   - pause: how long the decided button keeps spinning before the sheet goes.
    ///   - armDelay: the failure step's cross-fade (0 in tests).
    init(
        messenger: any MessengerService, pause: Duration = .milliseconds(250),
        armDelay: Duration = .seconds(U2Motion.Duration.screen)
    ) {
        self.messenger = messenger
        self.pause = pause
        self.armDelay = armDelay
    }

    /// The 7.1 row: the sheet rises on the confirmation.
    func present() {
        guard !isPresented, !isWorking else { return }
        reset()
        isPresented = true
    }

    /// “Hesabı ve her şeyi sil”, and “Tekrar dene” after a failure.
    func deleteAccount() async {
        guard isPresented, !isWorking, erasure == nil, step == .confirm || isArmed else { return }
        isWorking = true
        do {
            try await messenger.deleteAccountOnRelay()
        } catch {
            isWorking = false
            failures += 1
            U2Haptics.error()
            if step != .failed {
                withU2Animation(U2Motion.screen) { step = .failed }
                arm()
            }
            return
        }
        await decide(.accountDeleted)
    }

    /// “Yalnızca bu telefondan sil” (after a failure only).
    func eraseThisPhoneOnly() async {
        guard isPresented, step == .failed, isArmed, !isWorking, erasure == nil else { return }
        isWorking = true
        await decide(.thisPhoneOnly)
    }

    /// “Vazgeç” — nothing happens, the sheet goes down.
    func cancel() {
        guard !isWorking else { return }
        isPresented = false
    }

    /// The sheet has gone (also after a swipe down): erase now if that was
    /// decided, otherwise start over for the next time.
    func sheetDidDismiss(erase: @MainActor () async -> Void) async {
        guard erasure != nil else {
            reset()
            return
        }
        await erase()
        reset()
    }

    private func decide(_ erasure: Erasure) async {
        self.erasure = erasure
        U2Haptics.error()
        try? await Task.sleep(for: pause)
        isPresented = false
    }

    private func arm() {
        isArmed = false
        armTask?.cancel()
        let delay = armDelay
        armTask = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard let self, !Task.isCancelled, self.step == .failed else { return }
            self.isArmed = true
        }
    }

    private func reset() {
        armTask?.cancel()
        armTask = nil
        step = .confirm
        isWorking = false
        erasure = nil
        isArmed = false
        failures = 0
    }

    #if DEBUG
        /// The failure step at once, without animation (DEBUG harness).
        func showFailureForHarness() {
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) { step = .failed }
            isArmed = true
        }
    #endif
}

/// The 7.1 floating sheet: “Hesabın silinsin mi?” and, when the relay could
/// not delete the account, “Hesap silinemedi.” in the same sheet — the
/// contents cross-fade and the height follows softly (0.5 s, the design
/// curve), as in 3.3.
///
/// The failure step follows 3.3's layout: the text block, an option card
/// (danger, “Yalnızca bu telefondan sil”) and the C2 “Tekrar dene”, then
/// the C3 “Vazgeç”.
struct EraseAccountSheet: View {
    let model: EraseAccountModel
    /// Natural heights of the two steps, measured on hidden copies.
    @State private var heights: [EraseAccountModel.Step: CGFloat] = [:]

    var body: some View {
        ZStack(alignment: .top) {
            // Only the current step is live (touches, VoiceOver, UI tests).
            switch model.step {
            case .confirm:
                confirmation(live: true)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(.u2Fade)
            case .failed:
                failure(live: true)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(.u2Fade)
            }
        }
        .frame(height: heights[model.step], alignment: .top)
        .frame(maxWidth: .infinity, alignment: .top)
        .background(alignment: .top) { measurements }
        .padding(EdgeInsets(top: 18, leading: 18, bottom: 28, trailing: 18))
        .onChange(of: model.failures) { _, failures in
            // VoiceOver moves to the failure's title (again after a retry).
            guard failures > 0 else { return }
            UIAccessibility.post(notification: .layoutChanged, argument: nil)
        }
    }

    /// Both steps at the sheet's width, never drawn, never hit, far off-screen.
    private var measurements: some View {
        ZStack(alignment: .top) {
            measured(.confirm) { confirmation(live: false) }
            measured(.failed) { failure(live: false) }
        }
        .hidden()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .offset(x: 4_000)
    }

    private func measured<Content: View>(_ step: EraseAccountModel.Step, @ViewBuilder content: () -> Content) -> some View {
        content()
            .fixedSize(horizontal: false, vertical: true)
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { heights[step] = $0 }
    }

    /// `live: false` — the measuring copy: no identifiers.
    private func confirmation(live: Bool) -> some View {
        EraseConfirmation(
            scope: .account, isWorking: model.isWorking && model.step == .confirm,
            identifiers: live ? ("settings.confirm.destructive", "settings.confirm.cancel") : nil,
            onConfirm: { Task { await model.deleteAccount() } }, onCancel: model.cancel)
    }

    private func failure(live: Bool) -> some View {
        // The retry spins until the sheet has gone; “this phone only” just
        // stills every control.
        let retrying = model.isWorking && model.erasure != .thisPhoneOnly
        return VStack(alignment: .leading, spacing: 18) {
            // Text block: pad 6 4 0 4, gap 10 (as 3.3).
            VStack(alignment: .leading, spacing: 10) {
                U2DisplayText(
                    StringTable.settings.resource("settings.erase.failed.title"), style: .displaySheetTitle.balanced)
                Text("settings.erase.failed.body", table: .settings)
                    .u2Font(.sheetBody)
                    .foregroundStyle(Color.u2.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(EdgeInsets(top: 6, leading: 4, bottom: 0, trailing: 4))
            OptionCard(
                glyph: .trash, tone: .danger, title: Text("settings.erase.thisPhone.title", table: .settings),
                description: Text("settings.erase.thisPhone.body", table: .settings),
                action: { Task { await model.eraseThisPhoneOnly() } }
            )
            .disabled(model.isWorking)
            .accessibilityIdentifier(live ? "settings.erase.thisPhone" : "")
            VStack(spacing: 8) {
                Button {
                    Task { await model.deleteAccount() }
                } label: {
                    U2ButtonLabel(
                        retrying
                            ? Text("settings.erase.confirm.working", table: .settings)
                            : Text("settings.erase.retry", table: .settings),
                        isWorking: retrying)
                }
                .buttonStyle(.u2Secondary)
                .disabled(model.isWorking)
                .accessibilityIdentifier(live ? "settings.erase.retry" : "")
                Button(action: model.cancel) { U2ButtonLabel(Text("common.cancel")) }
                    .buttonStyle(U2TextButtonStyle(tone: .accent, height: U2Size.textButtonS))
                    .disabled(model.isWorking)
                    .accessibilityIdentifier(live ? "settings.erase.cancel" : "")
            }
        }
    }
}

#Preview("7.1 · Hesap silinemedi") {
    let model = EraseAccountModel(
        messenger: DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate)))
    #if DEBUG
        model.showFailureForHarness()
    #endif
    return EraseAccountSheet(model: model)
        .background(Color.u2.surface)
}
