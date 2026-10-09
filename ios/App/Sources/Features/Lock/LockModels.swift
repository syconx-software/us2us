import Foundation
import SwiftUI
import Us2usKit

/// The lock flow (03): which lock screen shows, the biometric scan, the
/// 3.3 sheet and the restore hand-off. All data goes through
/// `MessengerService`, so the live implementation is a drop-in.
///
/// * 3.1 Face ID shows first when biometric unlock is enabled and enrolled,
///   and scans by itself as soon as the app is active — on every launch and
///   every return from the background. Failure or cancel → 3.2.
/// * 3.2 the code (``LockCodeModel``). The Face ID key goes back to 3.1.
/// * 3.3 “Kodu unuttum” — a floating sheet: restore (7.3) or erase
///   everything after a second confirmation.
@Observable
@MainActor
final class LockFlowModel {
    enum Stage: Equatable {
        /// 3.1
        case biometric
        /// 3.2
        case code
        /// 3.3 › “Yedekten geri yükle” → document picker → 7.3, inside the lock.
        case restore(URL?)
    }

    enum ForgotStep: Hashable {
        /// The two honest options.
        case options
        /// “Her şey silinsin mi?” — the second confirmation.
        case confirmErase
    }

    private(set) var stage: Stage
    private(set) var isScanning = false
    let biometry: Biometry
    let code: LockCodeModel

    /// 3.3 sheet.
    var showsForgot = false
    private(set) var forgotStep: ForgotStep = .options
    /// The confirmation's button ignores taps until its cross-fade has
    /// finished (no accidental double tap through the danger card).
    private(set) var confirmationArmed = false
    private(set) var isErasing = false
    /// The system document picker for the backup file.
    var showsBackupPicker = false

    private let messenger: any MessengerService
    private let onUnlock: () -> Void
    private let onErase: () async -> Void
    /// Scan automatically at the next “app became active”.
    private var autoScanArmed = true
    /// Invalidates late biometric results (backgrounding, relock).
    private var scanGeneration = 0
    private var stageShownAt = Date()
    /// What happens after the sheet has gone (it dismisses first, softly).
    private var afterForgotDismiss: (() -> Void)?
    private var armTask: Task<Void, Never>?
    /// DEBUG harness: no automatic scan, no transitions out of the drawn state.
    let isFrozenForHarness: Bool

    init(
        messenger: any MessengerService, biometry: Biometry, isFrozenForHarness: Bool = false,
        onUnlock: @escaping () -> Void, onErase: @escaping () async -> Void
    ) {
        self.messenger = messenger
        self.biometry = biometry
        self.onUnlock = onUnlock
        self.onErase = onErase
        self.isFrozenForHarness = isFrozenForHarness
        stage = biometry == .none ? .code : .biometric
        code = LockCodeModel(messenger: messenger, onUnlock: onUnlock)
        autoScanArmed = !isFrozenForHarness
    }

    // MARK: Lifecycle

    /// The app became active (or the lock appeared while active): start the
    /// armed Face ID scan once.
    func appBecameActive(locale: Locale) {
        guard autoScanArmed, stage == .biometric, !showsForgot else { return }
        autoScanArmed = false
        startScan(locale: locale)
    }

    /// Back from the background while the lock was already up: lock afresh —
    /// 3.1 (scanning again) or an empty 3.2.
    func relock() {
        guard !isFrozenForHarness, !isErasing else { return }
        scanGeneration += 1
        isScanning = false
        showsForgot = false
        showsBackupPicker = false
        code.reset()
        autoScanArmed = biometry != .none
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            stage = biometry == .none ? .code : .biometric
            forgotStep = .options
        }
        stageShownAt = Date()
    }

    // MARK: 3.1 Face ID

    /// Runs the system biometric check through the service. Success unlocks
    /// (the lock dissolves 0.36 s); failure or cancel cross-fades to 3.2.
    /// The system prompt is worded in the in-app language: the reason, and
    /// “Kodla aç” on its cancel button (no device-passcode fallback).
    func startScan(locale: Locale) {
        guard biometry != .none, !isScanning, !isFrozenForHarness else { return }
        isScanning = true
        scanGeneration += 1
        let generation = scanGeneration
        let startedAt = Date()
        Task { [weak self] in
            guard let self else { return }
            let success = await self.messenger.unlockWithBiometrics(
                reason: self.biometry.reason(locale: locale),
                cancelTitle: StringTable.lock.string("lock.faceID.useCode", locale: locale))
            guard generation == self.scanGeneration else { return }
            self.isScanning = false
            if success {
                U2Haptics.success()
                self.onUnlock()
                return
            }
            // Let the screen settle before leaving it (an instant refusal —
            // biometry locked out — must not flicker 3.2 → 3.1 → 3.2).
            let dwell = 0.7 - Date().timeIntervalSince(max(startedAt, self.stageShownAt))
            if dwell > 0 { try? await Task.sleep(for: .seconds(dwell)) }
            guard generation == self.scanGeneration, self.stage == .biometric else { return }
            self.show(.code)
        }
    }

    /// “Kodla aç”: straight to 3.2. A biometric result still in flight is
    /// ignored from here on.
    func useCode() {
        scanGeneration += 1
        isScanning = false
        show(.code)
    }

    /// The keypad's Face ID key: back to 3.1, scanning.
    func useBiometrics(locale: Locale) {
        guard biometry != .none else { return }
        code.clearEntry()
        show(.biometric)
        startScan(locale: locale)
    }

    // MARK: 3.3 Kodu unuttum

    func openForgot() {
        forgotStep = .options
        confirmationArmed = false
        showsForgot = true
    }

    /// “Tekrar dene” (or swipe down) → 3.2.
    func closeForgot() {
        showsForgot = false
    }

    /// Called by the sheet once it has gone.
    func forgotDidDismiss() {
        forgotStep = .options
        let next = afterForgotDismiss
        afterForgotDismiss = nil
        next?()
    }

    /// “Her şeyi sil, baştan başla” → the second confirmation, in the same sheet.
    func askEraseConfirmation() {
        confirmationArmed = false
        withU2Animation(U2Motion.screen) { forgotStep = .confirmErase }
        armTask?.cancel()
        armTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(U2Motion.Duration.screen))
            guard let self, !Task.isCancelled, self.forgotStep == .confirmErase else { return }
            self.confirmationArmed = true
        }
    }

    /// “Vazgeç” in the confirmation → back to the options.
    func cancelErase() {
        armTask?.cancel()
        confirmationArmed = false
        withU2Animation(U2Motion.screen) { forgotStep = .options }
    }

    /// “Her şeyi sil” (second confirmation): the sheet goes down, then the
    /// service crypto-shreds everything and the app returns to 1.1.
    func confirmErase() {
        guard forgotStep == .confirmErase, confirmationArmed, !isErasing else { return }
        isErasing = true
        U2Haptics.error()
        afterForgotDismiss = { [weak self] in
            guard let self else { return }
            Task { await self.onErase() }
        }
        showsForgot = false
    }

    /// “Yedekten geri yükle”: the sheet goes down, then the system picker
    /// for the `.u2u` file opens (7.3 continues with the chosen file).
    func chooseBackup() {
        afterForgotDismiss = { [weak self] in
            #if DEBUG
                if let url = DesignFixturesService.pickedBackupForTesting {
                    self?.backupChosen(url)
                    return
                }
            #endif
            self?.showsBackupPicker = true
        }
        showsForgot = false
    }

    func backupChosen(_ url: URL) {
        show(.restore(url))
    }

    /// Back from 7.3 (cancelled) → 3.2.
    func leaveRestore() {
        show(.code)
    }

    // MARK: Harness

    #if DEBUG
        /// Puts the flow in a drawn state without animation (DEBUG harness).
        func setForHarness(stage: Stage, forgot: ForgotStep? = nil) {
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) {
                self.stage = stage
                if let forgot {
                    forgotStep = forgot
                    confirmationArmed = true
                    showsForgot = true
                }
            }
        }
    #endif

    // MARK: Private

    private func show(_ next: Stage) {
        guard stage != next else { return }
        stageShownAt = Date()
        withU2Animation(U2Motion.state) { stage = next }
    }
}

/// 3.2 — the code entry: six digits auto-submit; a wrong code turns the
/// dots red with a soft shake and a plain sentence; after the free attempts
/// the keypad waits (PROTOCOL §5.2, Us2usKit `UnlockThrottle`), live `mm:ss`.
@Observable
@MainActor
final class LockCodeModel {
    enum Message: Equatable {
        /// “Kod yanlış. {n} yanlış daha olursa {süre} beklemen gerekir.”
        case wrong(attemptsLeft: Int, delay: TimeInterval)
        /// “Kod yanlış.” (no further delay ahead)
        case wrongPlain
        /// “Çok fazla yanlış deneme. {mm:ss} sonra tekrar dene.”
        case throttled(until: Date)
    }

    private(set) var entered = ""
    private(set) var appearance: CodeDots.Appearance = .normal
    private(set) var shakeTrigger = 0
    private(set) var message: Message?
    private(set) var isVerifying = false
    /// DEBUG harness: keep the red dots on screen (the 3.2 reference).
    var holdsErrorState = false
    /// DEBUG harness: a wait never ends on its own.
    var isFrozenForHarness = false

    private let messenger: any MessengerService
    private let onUnlock: () -> Void
    private var clearTask: Task<Void, Never>?
    private var throttleTask: Task<Void, Never>?

    init(messenger: any MessengerService, onUnlock: @escaping () -> Void) {
        self.messenger = messenger
        self.onUnlock = onUnlock
        resumeThrottleIfNeeded()
    }

    var isThrottled: Bool {
        if case .throttled(let until) = message { return until > Date() }
        return false
    }

    var throttleEnd: Date? {
        if case .throttled(let until) = message { return until }
        return nil
    }

    func type(_ digit: Int) {
        guard !isVerifying, !isThrottled, entered.count < SecretCode.length else { return }
        if appearance == .error || message != nil {
            // The next digit starts over: the red dots clear, the sentence fades.
            clearTask?.cancel()
            withU2Animation(U2Motion.micro) {
                appearance = .normal
                entered = ""
            }
            withU2Animation(U2Motion.state) { message = nil }
        }
        entered.append(String(digit))
        if entered.count == SecretCode.length {
            Task { await verify() }
        }
    }

    func deleteLast() {
        guard !isVerifying, !entered.isEmpty, appearance == .normal else { return }
        entered.removeLast()
    }

    /// Empties the dots (Face ID key, relock) — the sentence and any wait stay.
    func clearEntry() {
        guard !isVerifying else { return }
        clearTask?.cancel()
        entered = ""
        if appearance == .error { appearance = .normal }
    }

    /// A fresh lock: empty dots, no sentence — unless a wait is still running.
    func reset() {
        clearTask?.cancel()
        isVerifying = false
        entered = ""
        appearance = .normal
        message = nil
        resumeThrottleIfNeeded()
    }

    private func verify() async {
        isVerifying = true
        // Let the sixth dot be seen before anything happens (setupAdvance, 0.25 s).
        try? await Task.sleep(for: .milliseconds(250))
        let result = await messenger.unlock(code: entered)
        switch result {
        case .unlocked:
            // Stays “verifying” while the lock dissolves (no more input).
            U2Haptics.success()
            onUnlock()
        case .wrongCode(let status):
            isVerifying = false
            U2Haptics.error()
            withU2Animation(U2Motion.micro) {
                appearance = .error
                shakeTrigger += 1
            }
            let next: Message
            if let until = status.nextAttemptAllowedAt, until > Date() {
                next = .throttled(until: until)
            } else if let upcoming = status.upcoming {
                next = .wrong(attemptsLeft: upcoming.attemptsLeft, delay: upcoming.delay)
            } else {
                next = .wrongPlain
            }
            withU2Animation(U2Motion.state) { message = next }
            scheduleThrottleEnd()
            scheduleClear()
        case .throttled(let until):
            isVerifying = false
            withU2Animation(U2Motion.state) {
                message = .throttled(until: until)
                appearance = .dimmed
                entered = ""
            }
            scheduleThrottleEnd()
        case .malformed:
            isVerifying = false
            entered = ""
        }
    }

    /// The red dots clear after 0.8 s; the sentence stays until the next digit.
    private func scheduleClear() {
        clearTask?.cancel()
        guard !holdsErrorState else { return }
        clearTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(800))
            guard let self, !Task.isCancelled else { return }
            withU2Animation(U2Motion.micro) {
                self.entered = ""
                self.appearance = self.isThrottled ? .dimmed : .normal
            }
        }
    }

    /// A wait still running (the status survives relaunch in the vault).
    private func resumeThrottleIfNeeded() {
        guard let until = messenger.unlockStatus.nextAttemptAllowedAt, until > Date() else { return }
        message = .throttled(until: until)
        appearance = .dimmed
        scheduleThrottleEnd()
    }

    /// When the wait is over the keypad comes back (0.36 s) and the sentence fades.
    private func scheduleThrottleEnd() {
        throttleTask?.cancel()
        guard let until = throttleEnd, !isFrozenForHarness else { return }
        throttleTask = Task { [weak self] in
            let remaining = until.timeIntervalSinceNow
            if remaining > 0 { try? await Task.sleep(for: .seconds(remaining)) }
            guard let self, !Task.isCancelled, self.throttleEnd == until else { return }
            withU2Animation(U2Motion.state) {
                self.message = nil
                self.appearance = .normal
            }
        }
    }

    #if DEBUG
        /// DEBUG harness: digits on screen without verifying.
        func setEnteredForHarness(_ digits: String) {
            entered = String(digits.prefix(SecretCode.length - 1))
        }
    #endif
}
