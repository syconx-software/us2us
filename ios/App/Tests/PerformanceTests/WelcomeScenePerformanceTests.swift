import XCTest

/// 1.1 · the welcome screen's Metal scene: what it costs while it runs, and
/// that it costs nothing once 2.1 covers it (it must not render hidden).
@MainActor
final class WelcomeScenePerformanceTests: U2PerformanceTestCase {
    private func launchWelcome() -> XCUIApplication {
        let app = launch(["-U2Start", "welcome", "-U2ResetPreferences", "YES"])
        XCTAssertTrue(app.buttons["welcome.start"].waitForExistence(timeout: 60))
        hold(3)
        return app
    }

    private func goToUsernameStep(_ app: XCUIApplication) {
        app.buttons["welcome.start"].tap()
        XCTAssertTrue(app.buttons["welcome.page2.cta"].waitForExistence(timeout: 30))
        app.buttons["welcome.page2.cta"].tap()
        XCTAssertTrue(app.buttons["welcome.page3.cta"].waitForExistence(timeout: 30))
        app.buttons["welcome.page3.cta"].tap()
        XCTAssertTrue(element(app, "onboarding.username.continue").waitForExistence(timeout: 30))
        hold(2)
    }

    /// 1.1 with the scene running (60 fps, 30 in Low Power Mode).
    func testWelcomeSceneRunning() {
        let app = launchWelcome()
        measure(metrics: appMetrics(app), options: options(iterations: 3)) { hold(5) }
    }

    /// 2.1 pushed over 1.1: the scene is paused (no display link, no GPU work).
    func testWelcomeSceneCovered() {
        let app = launchWelcome()
        goToUsernameStep(app)
        measure(metrics: appMetrics(app), options: options(iterations: 3)) { hold(5) }
    }

    /// Paging 1.1 → 1.2 → 1.3 and back by swipes (the scene pauses off 1.1).
    func testWelcomePaging() {
        let app = launchWelcome()
        measure(metrics: [XCTClockMetric()] + appMetrics(app), options: options(iterations: 3)) {
            app.swipeLeft()
            app.swipeLeft()
            app.swipeRight()
            app.swipeRight()
            hold(1)
        }
    }

    /// Instruments soak: 1.1 running, then 2.1 over it, each for the soak time.
    func testSoakWelcome() throws {
        try XCTSkipIf(soakSeconds == 0, "set TEST_RUNNER_U2PERF_SOAK_SECONDS")
        let app = launchWelcome()
        soak("welcome-running") { hold(1) }
        goToUsernameStep(app)
        soak("welcome-covered") { hold(1) }
    }
}
