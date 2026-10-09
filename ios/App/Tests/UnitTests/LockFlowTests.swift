import Us2usKit
import XCTest

@testable import us2us

/// 03 · Kilit — the lock flow's behaviour with the design fixtures.
@MainActor
final class LockFlowTests: XCTestCase {
    private func makeService() -> DesignFixturesService {
        DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
    }

    private func type(_ code: String, into model: LockCodeModel) {
        for digit in code { model.type(Int(String(digit))!) }
    }

    /// Waits (briefly) for an asynchronous state change.
    private func eventually(_ condition: @MainActor () -> Bool, timeout: TimeInterval = 3) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline { try? await Task.sleep(for: .milliseconds(20)) }
    }

    func testStartsOnFaceIDOnlyWhenBiometricsAreAvailable() {
        let service = makeService()
        let withFaceID = LockFlowModel(messenger: service, biometry: .faceID, onUnlock: {}, onErase: {})
        XCTAssertEqual(withFaceID.stage, .biometric)
        let without = LockFlowModel(messenger: service, biometry: .none, onUnlock: {}, onErase: {})
        XCTAssertEqual(without.stage, .code)
    }

    func testUseCodeLeavesFaceID() {
        let flow = LockFlowModel(messenger: makeService(), biometry: .faceID, onUnlock: {}, onErase: {})
        flow.useCode()
        XCTAssertEqual(flow.stage, .code)
    }

    func testWrongCodesCountDownThenWait() async {
        let service = makeService()
        let code = LockCodeModel(messenger: service, onUnlock: {})
        type("111112", into: code)
        await eventually { code.message != nil }
        XCTAssertEqual(code.message, .wrong(attemptsLeft: 2, delay: 60))
        XCTAssertEqual(code.appearance, .error)
        // The red dots clear after 0.8 s; the sentence stays.
        await eventually { code.entered.isEmpty }
        XCTAssertEqual(code.appearance, .normal)
        XCTAssertNotNil(code.message)

        // The next digit starts over and the sentence goes.
        code.type(1)
        XCTAssertNil(code.message)
        XCTAssertEqual(code.entered, "1")
        type("11112", into: code)
        await eventually { code.message != nil }
        XCTAssertEqual(code.message, .wrong(attemptsLeft: 1, delay: 60))

        await eventually { code.entered.isEmpty }
        type("111112", into: code)
        await eventually { code.isThrottled }
        XCTAssertTrue(code.isThrottled, "the third wrong code waits a minute (PROTOCOL §5.2)")
        // While waiting, digits are ignored.
        await eventually { code.appearance == .dimmed }
        code.type(2)
        XCTAssertEqual(code.entered, "")
    }

    func testAWaitSurvivesANewLock() async {
        let service = makeService()
        for _ in 0..<3 { _ = await service.unlock(code: "111112") }
        let code = LockCodeModel(messenger: service, onUnlock: {})
        XCTAssertTrue(code.isThrottled)
        XCTAssertEqual(code.appearance, .dimmed)
    }

    func testTheRightCodeUnlocks() async {
        var unlocked = false
        let code = LockCodeModel(messenger: makeService(), onUnlock: { unlocked = true })
        type(DesignFixtures.demoSecretCode, into: code)
        await eventually { unlocked }
        XCTAssertTrue(unlocked)
    }

    func testEraseNeedsASecondConfirmation() async {
        var erased = false
        let flow = LockFlowModel(messenger: makeService(), biometry: .none, onUnlock: {}, onErase: { erased = true })
        flow.openForgot()
        XCTAssertTrue(flow.showsForgot)
        // Not without the second step.
        flow.confirmErase()
        XCTAssertFalse(flow.isErasing)
        flow.askEraseConfirmation()
        XCTAssertEqual(flow.forgotStep, .confirmErase)
        // A double tap straight through the danger card does nothing.
        flow.confirmErase()
        XCTAssertFalse(flow.isErasing)
        await eventually { flow.confirmationArmed }
        flow.confirmErase()
        XCTAssertTrue(flow.isErasing)
        XCTAssertFalse(flow.showsForgot, "the sheet goes down first")
        XCTAssertFalse(erased)
        flow.forgotDidDismiss()
        await eventually { erased }
        XCTAssertTrue(erased)
    }

    func testCancelReturnsToTheOptions() async {
        let flow = LockFlowModel(messenger: makeService(), biometry: .none, onUnlock: {}, onErase: {})
        flow.openForgot()
        flow.askEraseConfirmation()
        flow.cancelErase()
        XCTAssertEqual(flow.forgotStep, .options)
        try? await Task.sleep(for: .seconds(0.7))
        XCTAssertFalse(flow.confirmationArmed)
        flow.confirmErase()
        XCTAssertFalse(flow.isErasing)
    }

    func testRestoreOpensThePickerAfterTheSheet() {
        let flow = LockFlowModel(messenger: makeService(), biometry: .none, onUnlock: {}, onErase: {})
        flow.openForgot()
        flow.chooseBackup()
        XCTAssertFalse(flow.showsForgot)
        XCTAssertFalse(flow.showsBackupPicker)
        flow.forgotDidDismiss()
        XCTAssertTrue(flow.showsBackupPicker)
        let file = URL(fileURLWithPath: "/tmp/yedek.u2u")
        flow.backupChosen(file)
        XCTAssertEqual(flow.stage, .restore(file))
        flow.leaveRestore()
        XCTAssertEqual(flow.stage, .code)
    }

    func testRelockStartsAfresh() async {
        let flow = LockFlowModel(messenger: makeService(), biometry: .faceID, onUnlock: {}, onErase: {})
        flow.useCode()
        flow.code.type(4)
        flow.openForgot()
        flow.relock()
        XCTAssertEqual(flow.stage, .biometric)
        XCTAssertEqual(flow.code.entered, "")
        XCTAssertFalse(flow.showsForgot)
    }

    func testWaitDurationsReadNaturally() {
        XCTAssertEqual(LockFormat.waitDuration(60, locale: Locale(identifier: "tr_TR")), "1 dakika")
        XCTAssertEqual(LockFormat.waitDuration(300, locale: Locale(identifier: "tr_TR")), "5 dakika")
        XCTAssertEqual(LockFormat.waitDuration(3600, locale: Locale(identifier: "tr_TR")), "1 saat")
        XCTAssertEqual(LockFormat.waitDuration(60, locale: Locale(identifier: "en_US")), "1 minute")
        XCTAssertEqual(LockFormat.waitDuration(900, locale: Locale(identifier: "en_US")), "15 minutes")
    }

    func testStringsAreInBothLanguages() {
        let tr = Locale(identifier: "tr")
        let en = Locale(identifier: "en")
        XCTAssertEqual(StringTable.lock.string("lock.faceID.title", locale: tr), "Face ID ile açılıyor")
        XCTAssertEqual(StringTable.lock.string("lock.faceID.title", locale: en), "Unlocking with Face ID")
        XCTAssertEqual(
            StringTable.lock.string("lock.code.wrong.warning \(2) \("1 dakika")", locale: tr),
            "Kod yanlış. 2 yanlış daha olursa 1 dakika beklemen gerekir.")
        XCTAssertEqual(
            StringTable.lock.string("lock.code.wrong.warning \(1) \("1 minute")", locale: en),
            "Wrong code. 1 more wrong attempt and you'll have to wait 1 minute.")
        XCTAssertEqual(StringTable.lock.string("lock.forgot.title", locale: tr), "Kod kurtarılamaz.")
        XCTAssertEqual(L10n.string("erase.confirm.action", locale: en), "Erase everything")
    }

    #if DEBUG
        func testHarnessRoutes() {
            func screen(_ argument: String) -> LockHarness.Screen? {
                LaunchRoute(argument: argument)?.state(for: .lock).flatMap(LockHarness.Screen.init(rawValue:))
            }
            XCTAssertEqual(screen("lock:wrong"), .wrong)
            XCTAssertEqual(screen("lock:switcher").map(LockHarness.init(screen:))?.replacesLock, true)
            XCTAssertNil(screen("conversation:deniz"))
            XCTAssertEqual(LaunchRoute(argument: "lock:wrong")?.path, [], "lock states are not navigation routes")
        }
    #endif
}
