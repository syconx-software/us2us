import XCTest

/// TEMPORARY probe (integration check, removed afterwards): holds the app in
/// each state for a while so the scene's CPU/GPU work can be sampled from outside.
@MainActor
final class ZZSceneIdleProbeUITests: XCTestCase {
    private func mark(_ phase: String) {
        print("PROBE \(phase) \(Date().timeIntervalSince1970)")
    }

    private func hold(_ seconds: TimeInterval) {
        RunLoop.current.run(until: Date().addingTimeInterval(seconds))
    }

    /// Settles for 6 s, then samples for 15 s between two marks.
    private func measure(_ phase: String) {
        hold(6)
        mark("\(phase)-start")
        hold(15)
        mark("\(phase)-end")
    }

    func testProbeSceneWork() {
        let app = XCUIApplication()
        app.launchArguments = ["-U2Fixtures", "YES", "-U2Language", "tr", "-U2Theme", "dark", "-U2Start", "welcome", "-U2ResetPreferences", "YES"]
        app.launch()
        XCTAssertTrue(app.buttons["welcome.start"].waitForExistence(timeout: 60))
        measure("welcome")
        app.buttons["welcome.start"].tap()
        XCTAssertTrue(app.buttons["welcome.page2.cta"].waitForExistence(timeout: 30))
        measure("page2")
        app.buttons["welcome.page2.cta"].tap()
        measure("page3")
        app.buttons["welcome.page3.cta"].tap()
        XCTAssertTrue(app.buttons["onboarding.username.continue"].waitForExistence(timeout: 30))
        measure("pushed")
        XCUIDevice.shared.press(.home)
        measure("background")
        app.activate()
        measure("foreground-pushed")
        app.buttons["Geri"].firstMatch.tap()
        measure("popped-page3")
        mark("end")
    }
}
