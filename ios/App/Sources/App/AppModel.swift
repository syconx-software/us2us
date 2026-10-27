import SwiftUI
import UIKit

/// The app's root state (flows.md › App states).
///
/// * `onboarding` — no account on this device: 1.1 → 1.3 → 2.x.
/// * `locked` — an account exists but nothing has been unlocked since launch;
///   the main window shows only the background (no content is built).
/// * `unlocked` — the main stack (4.1 …) is alive. When the app locks again
///   it stays alive underneath the lock, so the stack and scroll positions
///   survive.
///
/// The lock (03) and the privacy cover (3.4) live in their own window above
/// everything — sheets, system pickers — managed by `SecurityOverlayController`.
@Observable
@MainActor
final class AppModel {
    enum Phase: Equatable {
        case onboarding
        case locked
        case unlocked
    }

    /// What the security window shows.
    enum Overlay: Equatable {
        case none
        /// 3.4 — instantly on resign-active; dissolves on return if no lock is due.
        case privacyCover
        /// 03 — Face ID (3.1) or the code (3.2), over the cover.
        case lock
    }

    /// Where onboarding starts when the root swaps to it.
    enum OnboardingEntry: Equatable {
        /// 1.1 (fresh install, after “Her şeyi sil”).
        case welcome
        /// 2.2 “1 / 2” — a backup was restored inside the lock (3.3 › 7.3).
        case newCodeAfterRestore
        /// 7.3 with the document picker — “Yedekten geri yükle” after the
        /// optional wipe erased everything.
        case restoreFromBackup
    }

    private(set) var phase: Phase
    /// 2.3 → 4.1: onboarding is still on screen, fading out over 4.1.
    private(set) var isLeavingOnboarding = false
    private(set) var overlay: Overlay = .none
    private(set) var onboardingEntry: OnboardingEntry = .welcome
    /// The blurred snapshot under the privacy cover (nil = solid cover).
    private(set) var privacySnapshot: UIImage?
    /// The optional wipe erased everything outside the lock (7.1 › Gizli
    /// kodu değiştir): the lock opens on “Veriler silindi”.
    private(set) var showsWipedOutcome = false

    let messenger: any MessengerService
    let preferences: AppPreferences
    let configuration: AppConfiguration
    let clock: U2Clock
    let router = U2Router<AppRoute>()

    /// Set when the app went to the background: the next foreground locks.
    private var lockIsDue = false
    weak var securityWindow: SecurityOverlayController?
    @ObservationIgnored private let pickerActivity: SystemPickerActivity

    init(
        configuration: AppConfiguration, preferences: AppPreferences, messenger: (any MessengerService)? = nil,
        pickerActivity: SystemPickerActivity = .shared
    ) {
        self.pickerActivity = pickerActivity
        self.configuration = configuration
        self.preferences = preferences
        // Decrypted preview copies never survive a launch (a crash, a kill),
        // nor do the copies of picked photos, videos and files, nor a voice
        // recording that was cut off.
        ProtectedPreviewFiles.removeAll()
        SystemPickers.removeAllPicked()
        VoiceRecordingFiles.removeAll()
        if configuration.resetPreferences { preferences.reset() }
        if let theme = configuration.theme { preferences.theme = theme }
        if let language = configuration.language { preferences.language = language }

        // The design's moment is for the design's data only: the live service
        // runs on the wall clock (countdowns, presence, “today”, file names).
        let clock: U2Clock = Self.clock(for: configuration, injected: messenger != nil)
        self.clock = clock
        let english = preferences.language.resolvedLocale().language.languageCode?.identifier == "en"
        let service: any MessengerService
        if let messenger {
            service = messenger
        } else if configuration.usesFixtures {
            service = DesignFixturesService(
                hasAccount: configuration.start != .welcome, clock: clock, ghostUrgent: configuration.ghostUrgent,
                english: english)
        } else {
            service = Self.makeLiveService(relayURL: configuration.relayURL)
        }
        self.messenger = service

        // The live service decides the start: onboarding without an account,
        // otherwise the lock — at every launch, by design.
        var start = configuration.start
        if service is RealMessengerService {
            start = service.hasAccount ? .locked : .welcome
            // No account (a fresh start, or a wipe the app did not live to
            // finish): nothing of an earlier account's acknowledgements stays.
            if !service.hasAccount { preferences.hasAcknowledgedSaveNotice = false }
        }

        switch start {
        case .welcome:
            phase = .onboarding
        case .locked:
            phase = .locked
            overlay = .lock
        case .chats:
            phase = .unlocked
            router.setPath(configuration.routePath)
        }
    }

    /// `-U2FrozenClock` freezes the design's moment; the design's data (and
    /// an injected service: previews, tests) run from it; the live service
    /// uses wall-clock time.
    static func clock(for configuration: AppConfiguration, injected: Bool) -> U2Clock {
        if configuration.frozenClock { return .frozen(at: DesignFixtures.referenceDate) }
        if configuration.usesFixtures || injected { return .running(from: DesignFixtures.referenceDate) }
        return .live
    }

    private static func makeLiveService(relayURL: URL) -> RealMessengerService {
        do {
            return try RealMessengerService.makeDefault(relayURL: relayURL)
        } catch {
            // Application Support or the relay URL is unusable: nothing can
            // run safely (no fixtures in place of real data).
            fatalError("us2us: the app runtime could not start")
        }
    }

    // MARK: Appearance

    /// The UI locale (strings, upper-casing, formatting) from the language setting.
    var locale: Locale { preferences.language.resolvedLocale() }

    var interfaceStyle: UIUserInterfaceStyle {
        switch preferences.theme {
        case .system: .unspecified
        case .dark: .dark
        case .light: .light
        }
    }

    // MARK: Scene lifecycle (driven by SceneDelegate)

    /// Resign active (app switcher, Control Center, a system prompt): cover the
    /// content *now*, before the system snapshot. Never animated.
    func sceneWillResignActive(snapshot: () -> UIImage?) {
        messenger.scenePhaseChanged(.inactive)
        // The full-screen system picker already covers the conversation. Do not put
        // our security window above Photos/Files during its temporary inactive phase.
        guard !pickerActivity.isPresented else { return }
        guard phase == .unlocked, overlay == .none else { return }
        privacySnapshot = snapshot()
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { overlay = .privacyCover }
        securityWindow?.show()
    }

    func sceneDidEnterBackground() {
        messenger.scenePhaseChanged(.background)
        // Home, device lock and switching apps still lock normally, even with a picker up.
        defer { pickerActivity.cancel() }
        // Quick Look / Files copies go with the foreground (the screens close
        // them too), and so does a voice recording (the chat throws it away).
        ProtectedPreviewFiles.removeAll()
        VoiceRecordingFiles.removeAll()
        guard messenger.hasAccount, phase != .onboarding else { return }
        lockIsDue = true
        if overlay == .none {
            // Background without passing through inactive (rare): cover now.
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) { overlay = .privacyCover }
            securityWindow?.show()
        }
    }

    func sceneWillEnterForeground() {
        guard lockIsDue else { return }
        lockIsDue = false
        // Every return from the background locks (by design, no grace
        // period). The cover is already up; it cross-fades into the lock
        // (0.36 s) over an opaque base instead of snapping to it.
        securityWindow?.show()
        if overlay == .none {
            // Never fade the lock *in* over the app: without a cover it is instant.
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) { overlay = .lock }
        } else {
            withU2Animation(U2Motion.state) { overlay = .lock }
        }
    }

    func sceneDidBecomeActive() {
        messenger.scenePhaseChanged(.active)
        // Back without having been in the background: dissolve the cover.
        if overlay == .privacyCover && !lockIsDue {
            dismissOverlay()
        }
    }

    // MARK: Transitions

    /// Called by the lock flow after Face ID or the right code.
    ///
    /// The first unlock builds the app's content (4.1 …) under the still
    /// opaque lock, at once, and only once that heavy frame is on screen
    /// (two display frames later) dissolves the lock (0.36 s). The dissolve
    /// then gets every one of its frames, and it is a true dissolve: the
    /// content is complete underneath instead of fading in at the same time
    /// (which dipped through the background).
    func didUnlock() {
        guard phase == .locked else {
            dismissOverlay()
            return
        }
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { phase = .unlocked }
        if router.isAtRoot, !configuration.routePath.isEmpty { router.setPath(configuration.routePath) }
        AfterFrames.run(2) { [weak self] in self?.dismissOverlay() }
    }

    /// 2.3 “Kurulumu tamamla” succeeded: replace the root with 4.1 (cross-fade 0.5 s).
    func didFinishOnboarding() {
        router.setPath([])
        onboardingEntry = .welcome
        isLeavingOnboarding = true
        withU2Animation(U2Motion.screen) {
            phase = .unlocked
        } completion: { [weak self] in
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) { self?.isLeavingOnboarding = false }
        }
    }

    /// 3.3 / 7.1 “Her şeyi sil” after the second confirmation: wipe → 1.1.
    func eraseEverything() async {
        await messenger.eraseEverything()
        ProtectedPreviewFiles.removeAll()
        VoiceRecordingFiles.removeAll()
        forgetAccountPreferences()
        lockIsDue = false
        onboardingEntry = .welcome
        swapToOnboarding()
    }

    /// The account on this phone went (erased, wiped after wrong codes,
    /// replaced by a restore): what the app keeps about it outside the vault
    /// goes too — the next account is told again before its first save
    /// (PROTOCOL §8.4), and nothing says that this phone ever saved
    /// received media.
    func forgetAccountPreferences() {
        preferences.hasAcknowledgedSaveNotice = false
    }

    /// 3.3 › 7.3 finished restoring inside the lock: the restored vault needs
    /// a new secret code on this device (flows.md: 7.3 done → 2.2 ×2 → 2.3 →
    /// 4.1). The lock dissolves over onboarding at 2.2.
    func continueAfterRestore() {
        lockIsDue = false
        onboardingEntry = .newCodeAfterRestore
        swapToOnboarding()
    }

    /// 2.3 could not finish but left a vault on this phone, locked — the
    /// account saved before the failure, or the vault a refused restore
    /// inside the lock never touched (`SetupInterruption`): onboarding is
    /// over, the lock opens it. The lock is in place at once (never faded in
    /// over content); the root behind it becomes the locked one.
    func returnToLock() {
        lockIsDue = false
        onboardingEntry = .welcome
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { overlay = .lock }
        securityWindow?.show()
        withTransaction(transaction) { phase = .locked }
        router.setPath([])
    }

    /// The optional wipe fired outside the lock — the 10th wrong code in a
    /// row was the current code of 7.1 › Gizli kodu değiştir (PROTOCOL
    /// §5.2): everything is erased, so the main stack goes at once, under
    /// the lock, which says what happened (on the lock itself, the lock
    /// flow shows it without this).
    func dataWipedAfterWrongCodes() {
        lockIsDue = false
        showsWipedOutcome = true
        forgetAccountPreferences()
        SettingsFlow.of(self).restoreAccess.reset()
        ProtectedPreviewFiles.removeAll()
        VoiceRecordingFiles.removeAll()
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { overlay = .lock }
        securityWindow?.show()
        withTransaction(transaction) { phase = .locked }
        router.setPath([])
    }

    /// “Yedekten geri yükle” or “Baştan başla” after the wipe: onboarding,
    /// at 7.3 (the document picker opens) or at 1.1. The lock dissolves.
    func leaveWipedLock(restoring: Bool) {
        lockIsDue = false
        showsWipedOutcome = false
        onboardingEntry = restoring ? .restoreFromBackup : .welcome
        swapToOnboarding()
    }

    /// The root cross-fades to onboarding first; the main stack's path is
    /// emptied only once it has gone (resetting it in the same turn, after
    /// an awaited erase, left the root un-updated: 4.1 stayed on screen).
    private func swapToOnboarding() {
        // Whatever comes next is another account (or the same one restored).
        SettingsFlow.of(self).restoreAccess.reset()
        forgetAccountPreferences()
        withU2Animation(U2Motion.screen) {
            phase = .onboarding
        } completion: { [weak self] in
            guard let self, self.phase == .onboarding else { return }
            self.router.setPath([])
        }
        dismissOverlay()
    }

    private func dismissOverlay() {
        guard overlay != .none else { return }
        let end = U2Signposts.animation("unlock", category: .presentation)
        withU2Animation(U2Motion.state) {
            overlay = .none
        } completion: { [weak self] in
            end()
            guard let self, self.overlay == .none else { return }
            self.privacySnapshot = nil
            self.securityWindow?.hide()
        }
    }
}

extension AppModel {
    /// A model for SwiftUI previews of whole screens: fixtures, frozen clock,
    /// preferences in their own defaults suite.
    static func preview(start: AppConfiguration.Start = .chats, language: AppLanguage = .turkish) -> AppModel {
        var configuration = AppConfiguration()
        configuration.start = start
        configuration.usesFixtures = true
        configuration.frozenClock = true
        configuration.language = language
        let defaults = UserDefaults(suiteName: "us2us.previews") ?? .standard
        return AppModel(configuration: configuration, preferences: AppPreferences(defaults: defaults))
    }
}
