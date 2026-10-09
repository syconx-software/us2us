import XCTest

/// End-to-end walks through the shell with the design fixtures.
///
/// Note: XCUITest's automation tree also lists elements hidden from VoiceOver
/// (`accessibilityHidden`), so screens covered on the soft stack stay
/// queryable here — match by identifier.
@MainActor
final class AppFlowUITests: XCTestCase {
    override func setUp() async throws {
        continueAfterFailure = false
    }

    private func launch(_ arguments: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-U2Fixtures", "YES", "-U2FrozenClock", "YES", "-U2Language", "tr", "-U2Theme", "dark"] + arguments
        app.launch()
        return app
    }

    /// 1.1 → 1.2 → 1.3 → 2.1 → 2.2 (set, confirm) → 2.3 → 4.1.
    func testOnboardingReachesTheChatList() {
        let app = launch(["-U2Start", "welcome", "-U2ResetPreferences", "YES"])
        app.buttons["welcome.start"].tap()
        app.buttons["welcome.page2.cta"].tap()
        app.buttons["welcome.page3.cta"].tap()
        let field = app.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText("ada.k")
        let next = app.buttons["onboarding.username.continue"]
        XCTAssertTrue(next.wait(for: \.isEnabled, toEqual: true, timeout: 5))
        // The software keyboard covers “Devam”; its Continue key does the same.
        if next.isHittable { next.tap() } else { field.typeText("\n") }
        XCTAssertTrue(app.buttons["2"].waitForExistence(timeout: 5))
        for digit in "246813" { app.buttons[String(digit)].tap() }
        XCTAssertTrue(app.staticTexts["Kodu tekrar gir"].waitForExistence(timeout: 3))
        for digit in "246813" { app.buttons[String(digit)].tap() }
        let ack = app.descendants(matching: .any)["onboarding.acknowledge"].firstMatch
        XCTAssertTrue(ack.waitForExistence(timeout: 5))
        ack.tap()
        app.buttons["onboarding.finish"].tap()
        XCTAssertTrue(app.staticTexts["Sohbetler"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["chat.deniz"].exists)
    }

    /// Launch locked → the code (wrong once, then right) → 4.1.
    func testLockScreenUnlocksWithTheCode() {
        let app = launch(["-U2Start", "locked"])
        XCTAssertTrue(app.staticTexts["Gizli kodunu gir"].waitForExistence(timeout: 5))
        for digit in "111112" { app.buttons[String(digit)].tap() }
        XCTAssertTrue(app.staticTexts["Kod yanlış. 2 yanlış daha olursa 1 dakika beklemen gerekir."].waitForExistence(timeout: 5))
        for digit in "246813" { app.buttons[String(digit)].tap() }
        XCTAssertTrue(app.staticTexts["Sohbetler"].waitForExistence(timeout: 5))
    }

    /// 4.1 → 5.1 → back; 4.1 → 7.1 → theme picker.
    func testMainStackNavigation() {
        let app = launch(["-U2Start", "chats"])
        app.buttons["chat.deniz"].tap()
        XCTAssertTrue(app.staticTexts["Kapı kodu 4471"].waitForExistence(timeout: 5))
        app.buttons.matching(identifier: "chatHeader.back").firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Sohbetler"].waitForExistence(timeout: 5))
        app.buttons["chats.settings"].tap()
        XCTAssertTrue(app.staticTexts["Ayarlar"].waitForExistence(timeout: 5))
        app.buttons["settings.theme"].tap()
        app.buttons["picker.option.2"].tap()  // Açık
        XCTAssertTrue(app.buttons["picker.option.2"].isSelected)
        app.buttons["picker.option.1"].tap()  // Koyu
    }

    /// Every return from the background locks (owner decision).
    func testReturningFromBackgroundLocks() {
        let app = launch(["-U2Start", "chats"])
        XCTAssertTrue(app.staticTexts["Sohbetler"].waitForExistence(timeout: 5))
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(app.staticTexts["Gizli kodunu gir"].waitForExistence(timeout: 5))
    }

    /// The English UI (09).
    func testEnglish() {
        let app = XCUIApplication()
        app.launchArguments = ["-U2Fixtures", "YES", "-U2FrozenClock", "YES", "-U2Language", "en", "-U2Start", "chats"]
        app.launch()
        XCTAssertTrue(app.staticTexts["Chats"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Line open · messages arriving"].exists)
    }
}
