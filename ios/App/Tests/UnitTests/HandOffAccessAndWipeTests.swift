import Foundation
import Testing
import Us2usKit
import Us2usRuntime

@testable import us2us

// Three outcomes the user must be told plainly:
// 1. the generated backup password's hand-off (7.2): hidden until shown,
//    copied on this device only for a minute, and said so;
// 2. a restore whose token rotation got no answer (PROTOCOL §5.5 step 3.4):
//    the old phone may still have access until it is confirmed;
// 3. the optional wipe after 10 wrong codes (§5.2): “Veriler silindi” at
//    once, never “Kod yanlış”.

/// Waits (briefly) for an asynchronous state change.
@MainActor
private func eventually(_ condition: @MainActor () -> Bool) async {
    let clock = ContinuousClock()
    let deadline = clock.now.advanced(by: .seconds(3))
    while !condition(), clock.now < deadline { try? await Task.sleep(for: .milliseconds(20)) }
}

private let tr = Locale(identifier: "tr")
private let en = Locale(identifier: "en")

@MainActor
private func fixtures() -> DesignFixturesService {
    DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
}

// MARK: - 1 · The generated password's hand-off (7.2)

@MainActor
@Suite("7.2 · the generated password's hand-off")
struct GeneratedPasswordHandOffTests {
    private let generated = "k7mq-x2pd-9tfa-wn3h-b8ze"

    private func makeModel(lifetime: Duration = .seconds(60)) -> BackupExportModel {
        let now = DesignFixtures.referenceDate
        return BackupExportModel(
            messenger: fixtures(), pasteboard: SecurePasteboard(pasteboard: RecordingPasteboard(), now: { now }),
            copyLifetime: lifetime)
    }

    @Test("copied on this device only, for a minute, and still there after leaving the app or locking")
    func copyIsAHandOff() {
        let recorder = RecordingPasteboard()
        let now = DesignFixtures.referenceDate
        let pasteboard = SecurePasteboard(pasteboard: recorder, now: { now })
        let model = BackupExportModel(messenger: fixtures(), pasteboard: pasteboard)
        model.generatePassword { generated }

        #expect(model.copyGeneratedPassword())
        #expect(recorder.text == generated)
        #expect(recorder.localOnly == true, "no Universal Clipboard")
        #expect(recorder.expiresAt == now.addingTimeInterval(60))
        #expect(model.didCopy)

        // What the runtime does when the app leaves the foreground or locks:
        // a hand-off stays, because that is when it is pasted elsewhere.
        pasteboard.clearIfOwned()
        #expect(recorder.text == generated)
    }

    @Test("“Kopyalandı · yalnızca bu cihazda, 1 dk sonra silinir” stays only while the copy lives")
    func confirmationFollowsTheCopy() async {
        let model = makeModel(lifetime: .milliseconds(80))
        model.generatePassword { generated }
        #expect(model.copyGeneratedPassword())
        #expect(model.didCopy)
        await eventually { !model.didCopy }
        #expect(!model.didCopy, "the pasteboard has dropped it: the line goes too")

        // Copying again starts the minute over; editing the field ends it.
        #expect(model.copyGeneratedPassword())
        #expect(model.didCopy)
        model.password += "x"
        #expect(!model.didCopy)
    }

    @Test("hidden until “Göster”, and hidden again when the app leaves the foreground")
    func revealIsOptIn() {
        let model = makeModel()
        model.generatePassword { generated }
        #expect(model.showsGeneratedPassword)
        #expect(!model.revealsGeneratedPassword, "shown only on request")

        model.toggleReveal()
        #expect(model.revealsGeneratedPassword)
        model.hidePassword()
        #expect(!model.revealsGeneratedPassword)

        model.toggleReveal()
        model.toggleReveal()
        #expect(!model.revealsGeneratedPassword)
    }

    @Test("while the screen is recorded or mirrored it stays hidden whatever “Göster” says")
    func captureHides() {
        let model = makeModel()
        model.generatePassword { generated }
        model.toggleReveal()
        #expect(model.revealsGeneratedPassword)
        model.isScreenCaptured = true
        #expect(!model.revealsGeneratedPassword)
        model.toggleReveal()
        model.isScreenCaptured = false
        #expect(model.revealsGeneratedPassword, "the toggle was ignored while captured")
    }

    @Test("the hidden card keeps the shape, never the password")
    func masking() {
        #expect(BackupExportView.masked(generated) == "••••-••••-••••-••••-••••")
        #expect(!BackupExportView.masked(generated).contains("k"))
    }

    @Test("the confirmation and the reminder, in both languages")
    func copy() {
        #expect(StringTable.settings.string("backup.password.copied", locale: tr) == "Kopyalandı · yalnızca bu cihazda, 1 dk sonra silinir")
        #expect(StringTable.settings.string("backup.password.copied", locale: en).contains("1 minute"))
        #expect(StringTable.settings.string("backup.password.generated.hint", locale: tr).hasPrefix("Parola yöneticine"))
        #expect(StringTable.settings.string("backup.password.generated.hint", locale: en).contains("password manager"))
        for key: String.LocalizationValue in ["a11y.backup.copy.hint", "a11y.backup.generated.hidden"] {
            #expect(StringTable.settings.string(key, locale: tr) != StringTable.settings.string(key, locale: en))
        }
        #expect(StringTable.settings.string("a11y.backup.copy.hint", locale: tr).contains("1 dakika"))
    }
}

// MARK: - 2 · The old phone may still have access (§5.5 step 3.4)

@MainActor
@Suite("7.1 · 4.1 · an unanswered token rotation after a restore")
struct RestoreAccessNoticeTests {
    @Test("the notice stays while the server does not answer, and goes once it confirms")
    func unansweredThenClosed() async {
        let service = fixtures()
        let model = RestoreAccessModel(messenger: service)
        #expect(!model.isShown)

        service.restoreAccessPending = true
        #expect(model.isShown)

        service.restoreAccessAnswer = .unanswered
        #expect(await model.check() == .unanswered)
        #expect(model.isShown)
        #expect(model.lastCheckUnanswered)
        #expect(!model.isChecking)

        service.restoreAccessAnswer = .closed
        #expect(await model.check() == .closed)
        #expect(!model.isShown)
        #expect(!model.lastCheckUnanswered)
    }

    @Test("refused (restored on another phone first): the notice goes; 7.1 says why")
    func refused() async {
        let service = fixtures()
        service.restoreAccessPending = true
        service.restoreAccessAnswer = .refused
        let model = RestoreAccessModel(messenger: service)
        #expect(await model.check() == .refused)
        #expect(!model.isShown)
    }

    @Test("a later notice starts without an earlier check's answer (erased, wiped or restored in between)")
    func resetWhenTheAccountChanges() async {
        let service = fixtures()
        service.restoreAccessPending = true
        service.restoreAccessAnswer = .unanswered
        let model = RestoreAccessModel(messenger: service)
        #expect(await model.check() == .unanswered)
        #expect(model.lastCheckUnanswered)

        model.reset()
        #expect(!model.lastCheckUnanswered)
        #expect(!model.isChecking)
    }

    @Test("the live service maps the runtime's outcome one to one")
    func liveMapping() {
        #expect(RealMessengerService.restoreAccess(.settled) == .closed)
        #expect(RealMessengerService.restoreAccess(.unanswered) == .unanswered)
        #expect(RealMessengerService.restoreAccess(.refused) == .refused)
        // A signed-out phone's line never says “retrying”.
        #expect(LiveMapping.connection(.revoked) == .revoked)
    }

    @Test("what the notice, the card and the toasts say, in both languages")
    func copy() {
        let settingsKeys: [String.LocalizationValue] = [
            "access.notice.title", "access.notice.body", "access.notice.check", "access.notice.checking",
            "access.notice.unanswered", "access.closed.toast", "access.refused.toast",
        ]
        for key in settingsKeys {
            #expect(StringTable.settings.string(key, locale: tr) != StringTable.settings.string(key, locale: en))
        }
        #expect(StringTable.settings.string("access.notice.check", locale: tr) == "Şimdi kontrol et")
        // Honest: “may”, never a claim that the old phone has (or has no) access.
        #expect(StringTable.settings.string("access.notice.title", locale: tr).contains("olabilir"))
        #expect(StringTable.settings.string("access.notice.title", locale: en).contains("may"))
        for key: String.LocalizationValue in ["access.card.title", "access.card.subtitle"] {
            #expect(StringTable.chats.string(key, locale: tr) != StringTable.chats.string(key, locale: en))
        }
        #expect(L10n.string("chats.line.revoked", locale: tr) != L10n.string("chats.line.revoked", locale: en))
        // One line of Martian Mono 11 next to the line mark on a 375 pt phone.
        for locale in [tr, en] {
            #expect(L10n.string("chats.line.revoked", locale: locale).count <= 30)
        }
    }
}

// MARK: - 3 · The wipe after 10 wrong codes (§5.2)

@MainActor
@Suite("03 · 7.1 · the optional wipe after 10 wrong codes")
struct WipeOutcomeTests {
    private static let wrongCode = "111112"

    private func type(_ code: String, into model: LockCodeModel) {
        for digit in code { model.type(Int(String(digit))!) }
    }

    @Test("the live service reports the wipe as such, never as a wrong code")
    func liveResult() {
        let status = UnlockStatus(
            failures: 9, nextAttemptAllowedAt: nil, upcoming: UnlockThrottle.standard.upcomingDelay(afterFailures: 9))
        #expect(RealMessengerService.attemptResult(UnlockError.vault(.wiped), status: status) == .wiped)
        #expect(RealMessengerService.attemptResult(VaultError.wiped, status: status) == .wiped)
        #expect(RealMessengerService.attemptResult(UnlockError.vault(.wrongCode), status: status) == .wrongCode(status))
    }

    @Test("the 10th wrong code on the lock shows “Veriler silindi” at once")
    func lockShowsTheOutcome() async {
        let service = fixtures()
        service.wipesAfterTenWrongCodes = true
        service.setWrongCodesForHarness(9)
        var startedOver: [Bool] = []
        let flow = LockFlowModel(
            messenger: service, biometry: .none, onUnlock: {}, onErase: {},
            onStartOver: { startedOver.append($0) })
        type(Self.wrongCode, into: flow.code)
        await eventually { flow.stage == .wiped }

        #expect(flow.stage == .wiped)
        #expect(flow.code.message == nil, "never “Kod yanlış”")
        #expect(flow.code.appearance == .normal)
        #expect(!service.hasAccount, "everything is erased")

        // Coming back from the background does not bring the keypad back.
        flow.relock()
        #expect(flow.stage == .wiped)

        flow.restoreAfterWipe()
        flow.startOverAfterWipe()
        #expect(startedOver == [true, false])
    }

    @Test("without the option the 10th wrong code is just a wrong code")
    func noWipeWithoutTheOption() async {
        let service = fixtures()
        service.setWrongCodesForHarness(9)
        let flow = LockFlowModel(messenger: service, biometry: .none, onUnlock: {}, onErase: {})
        type(Self.wrongCode, into: flow.code)
        await eventually { flow.code.message != nil }
        #expect(flow.stage == .code)
        #expect(service.hasAccount)
    }

    @Test("the code change's current code: the app leaves 7.1 for the outcome")
    func codeChangeLeaves() async {
        let service = fixtures()
        service.wipesAfterTenWrongCodes = true
        service.setWrongCodesForHarness(9)
        var wiped = false
        let model = ChangeCodeModel(messenger: service, onChanged: {}, onWiped: { wiped = true })
        for digit in [1, 3, 5, 7, 9, 0] { model.type(digit) }
        await eventually { wiped }
        #expect(wiped)
        #expect(model.message == nil, "never “Kod yanlış”")
        #expect(model.step == .current)
    }

    @Test("from 7.1 the lock opens on the outcome; its choices lead to 7.3 or 1.1")
    func appTransitions() {
        var configuration = AppConfiguration()
        configuration.start = .chats
        let defaults = UserDefaults(suiteName: "us2us.tests.\(UUID().uuidString)")!
        let app = AppModel(
            configuration: configuration, preferences: AppPreferences(defaults: defaults), messenger: fixtures())
        #expect(app.phase == .unlocked)

        app.dataWipedAfterWrongCodes()
        #expect(app.showsWipedOutcome)
        #expect(app.overlay == .lock)
        #expect(app.phase == .locked, "nothing of the erased account stays built")
        #expect(app.router.isAtRoot)

        app.leaveWipedLock(restoring: true)
        #expect(!app.showsWipedOutcome)
        #expect(app.phase == .onboarding)
        #expect(app.onboardingEntry == .restoreFromBackup)

        app.dataWipedAfterWrongCodes()
        app.leaveWipedLock(restoring: false)
        #expect(app.onboardingEntry == .welcome)
    }

    @Test("a lock opened after the wipe starts on the outcome and never scans")
    func startsWiped() {
        let flow = LockFlowModel(
            messenger: fixtures(), biometry: .faceID, startsWiped: true, onUnlock: {}, onErase: {})
        #expect(flow.stage == .wiped)
        flow.appBecameActive(locale: tr)
        #expect(!flow.isScanning)
    }

    @Test("what the outcome says, in both languages")
    func copy() {
        let keys: [String.LocalizationValue] = [
            "lock.wiped.title", "lock.wiped.body", "lock.wiped.restore.body", "lock.wiped.startOver.title",
            "lock.wiped.startOver.body",
        ]
        for key in keys {
            let turkish = StringTable.lock.string(key, locale: tr)
            let english = StringTable.lock.string(key, locale: en)
            #expect(turkish != english)
            #expect(!turkish.contains("Kod yanlış"))
        }
        #expect(StringTable.lock.string("lock.wiped.body", locale: tr).contains("10 kez"))
    }
}

// MARK: - Backing out of a restore that began on the lock

@MainActor
@Suite("2.2 · leaving a restore that began on the lock")
struct LockRestoreBackOutTests {
    @Test("backing out of 2.2 returns to the lock, not to 1.1, and drops the opened backup")
    func returnsToTheLock() {
        var returned = 0
        let flow = OnboardingFlowModel(messenger: fixtures(), onFinished: {}, onReturnToLock: { returned += 1 })
        flow.continueAfterRestore()
        flow.pathChanged(from: [.secretCode], to: [])
        #expect(returned == 1)
        #expect(!flow.finish.replacesThisPhone)
        #expect(flow.mode == .create)
    }

    @Test("a restore from 1.1 and a new account go back as before")
    func otherPathsUnchanged() {
        var returned = 0
        let flow = OnboardingFlowModel(messenger: fixtures(), onFinished: {}, onReturnToLock: { returned += 1 })
        flow.restoreFinished()
        flow.pathChanged(from: [.restore, .secretCode], to: [.restore])
        flow.pathChanged(from: [.restore], to: [])
        flow.startSetup()
        flow.pathChanged(from: [.username], to: [])
        #expect(returned == 0)
    }
}

// MARK: - Turning Face ID on from 7.1

@MainActor
@Suite("7.1 · turning Face ID on needs the code once")
struct EnableFaceIDTests {
    @Test("a wrong code is a counted wrong code; the right one turns Face ID on and closes the page")
    func codeTurnsItOn() async {
        let service = fixtures()
        var changed = false
        var unavailable = false
        let model = ChangeCodeModel(
            messenger: service, purpose: .enableBiometrics, onChanged: { changed = true },
            onUnavailable: { unavailable = true })
        for digit in [1, 3, 5, 7, 9, 0] { model.type(digit) }
        await eventually { model.message != nil }
        #expect(service.unlockStatus.failures == 1, "through the lock's throttle")
        #expect(!changed)

        await eventually { model.entered.isEmpty }
        for digit in DesignFixtures.demoSecretCode { model.type(Int(String(digit))!) }
        await eventually { changed }
        #expect(changed)
        #expect(!unavailable)
        #expect(service.settings.faceIDEnabled)
        #expect(model.step == .current, "there is no second step")
    }

    @Test("the live service asks for the code only when quick unlock is off and biometrics exist")
    func needsCodeOnlyWhenOff() {
        // The fixtures turn Face ID on through the system prompt.
        #expect(!fixtures().biometricUnlockNeedsCode)
        #expect(StringTable.settings.string("faceID.enable.title", locale: tr) != StringTable.settings.string("faceID.enable.title", locale: en))
    }
}
