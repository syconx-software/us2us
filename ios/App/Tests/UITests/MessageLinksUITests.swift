import XCTest

@MainActor
final class MessageLinksUITests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }
    override func tearDown() { XCUIApplication().terminate(); super.tearDown() }

    private func send(_ text: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-U2Fixtures", "YES", "-U2FrozenClock", "YES", "-U2Language", "tr", "-U2Route", "conversation:deniz"]
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["message.d6"].firstMatch.waitForExistence(timeout: 10))
        let field = app.textFields.firstMatch
        field.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        // Synchronize each key with XCTest; long synthesized bursts lose characters on this simulator.
        for character in text { field.typeText(String(character)) }
        XCTAssertEqual(field.value as? String, text, "Verify input before testing message links")
        app.buttons["Gönder"].firstMatch.tap()
        return app
    }

    func testCommunityInviteAsksBeforeJoining() {
        let code = "us2us-community:v1:YWJj"
        let app = send(code)
        let link = app.links[code].firstMatch
        XCTAssertTrue(link.waitForExistence(timeout: 5))
        link.tap()
        XCTAssertTrue(app.staticTexts["Gruba katılmak istiyor musunuz?"].waitForExistence(timeout: 5))
        // Fixtures have no communities service: opening the prompt still cannot submit a request.
        XCTAssertFalse(app.buttons["messageInvite.confirm"].isEnabled)
        app.buttons["messageInvite.close"].tap()
        XCTAssertTrue(app.buttons["messageInvite.close"].waitForNonExistence(timeout: 5))
    }

    func testWebLinkOpensExternalBrowser() {
        // A loopback address keeps this test independent of public websites.
        let address = "http://127.0.0.1:9"
        let app = send(address)
        let link = app.links[address].firstMatch
        XCTAssertTrue(link.waitForExistence(timeout: 5))
        link.tap()
        let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
        XCTAssertTrue(safari.wait(for: .runningForeground, timeout: 10))
    }

    func testLinkLongPressOpensMessageActions() {
        let address = "http://127.0.0.1:9"
        let app = send(address)
        let link = app.links[address].firstMatch
        XCTAssertTrue(link.waitForExistence(timeout: 5))
        link.press(forDuration: 0.8)
        XCTAssertTrue(app.descendants(matching: .any)["menu.reply"].firstMatch.waitForExistence(timeout: 5))
        XCTAssertNotEqual(XCUIApplication(bundleIdentifier: "com.apple.mobilesafari").state, .runningForeground)
    }
}
