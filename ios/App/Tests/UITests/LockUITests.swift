import XCTest

/// 03 · Kilit end to end with the design fixtures (the simulator has no
/// enrolled Face ID, so the lock opens on the code keypad).
@MainActor
final class LockUITests: XCTestCase {
    override func setUp() async throws {
        continueAfterFailure = false
    }

    private func launchLocked() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-U2Fixtures", "YES", "-U2FrozenClock", "YES", "-U2Language", "tr", "-U2Theme", "dark", "-U2Start", "locked"]
        app.launch()
        XCTAssertTrue(app.staticTexts["Gizli kodunu gir"].waitForExistence(timeout: 5))
        return app
    }

    /// Third wrong code → the keypad waits with a live countdown.
    func testThirdWrongCodeWaits() {
        let app = launchLocked()
        for attempt in 0..<3 {
            for digit in "111112" { app.buttons[String(digit)].tap() }
            if attempt < 2 {
                XCTAssertTrue(app.staticTexts.matching(identifier: "lock.message").firstMatch.waitForExistence(timeout: 5))
            }
        }
        let wait = app.staticTexts.containing(NSPredicate(format: "label BEGINSWITH %@", "Çok fazla yanlış deneme."))
        XCTAssertTrue(wait.firstMatch.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["1"].isEnabled)
    }

    /// 3.3: “Tekrar dene” closes; erase asks a second time; “Vazgeç” goes
    /// back; confirming wipes and returns to 1.1.
    func testForgotCodeEraseNeedsASecondConfirmation() {
        let app = launchLocked()
        app.buttons["lock.forgot"].tap()
        XCTAssertTrue(app.staticTexts["Kod kurtarılamaz."].waitForExistence(timeout: 5))
        app.buttons["lock.forgot.retry"].tap()
        XCTAssertTrue(app.staticTexts["Kod kurtarılamaz."].waitForNonExistence(timeout: 5))

        app.buttons["lock.forgot"].tap()
        app.buttons["lock.forgot.erase"].tap()
        XCTAssertTrue(app.buttons["lock.erase.confirm"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Her şey silinsin mi?"].exists)
        XCTAssertTrue(app.buttons["lock.forgot.erase"].waitForNonExistence(timeout: 5), "only the live step is in the tree")
        app.buttons["lock.erase.cancel"].tap()
        XCTAssertTrue(app.buttons["lock.forgot.erase"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["lock.erase.confirm"].waitForNonExistence(timeout: 5))

        app.buttons["lock.forgot.erase"].tap()
        let confirm = app.buttons["lock.erase.confirm"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        Thread.sleep(forTimeInterval: 0.8)  // armed after its cross-fade
        confirm.tap()
        XCTAssertTrue(app.buttons["welcome.start"].waitForExistence(timeout: 8))
    }
}

/// 3.1 with an enrolled (simulated) Face ID. Run with Face ID enrolled on the
/// simulator and a matching face sent while it waits:
///
///     xcrun simctl spawn <sim> notifyutil -s com.apple.BiometricKit.enrollmentChanged '1'
///     xcrun simctl spawn <sim> notifyutil -p com.apple.BiometricKit.enrollmentChanged
///     # during the test, repeatedly:
///     xcrun simctl spawn <sim> notifyutil -p com.apple.BiometricKit_Sim.pearl.match
///
/// Skipped unless `US2US_FACEID_TEST=1` is set in the test environment.
@MainActor
final class LockFaceIDUITests: XCTestCase {
    func testFaceIDUnlocksAtLaunch() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["US2US_FACEID_TEST"] == "1", "needs simulated Face ID")
        let app = XCUIApplication()
        app.launchArguments = ["-U2Fixtures", "YES", "-U2Language", "tr", "-U2Theme", "dark", "-U2Start", "locked"]
        app.launch()
        XCTAssertTrue(app.staticTexts["Face ID ile açılıyor"].waitForExistence(timeout: 10))
        // The one-time system permission alert (“OK” / “Tamam”).
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for label in ["OK", "Tamam", "İzin Ver", "Allow"] where springboard.buttons[label].waitForExistence(timeout: 2) {
            springboard.buttons[label].tap()
            break
        }
        XCTAssertTrue(app.staticTexts["Sohbetler"].waitForExistence(timeout: 60), "a matching face unlocks")
    }
}
