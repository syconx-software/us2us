import XCTest

@MainActor
final class ConversationKeyboardUITests: XCTestCase {
    override func setUp() { super.setUp(); continueAfterFailure = false }
    override func tearDown() { XCUIApplication().terminate(); super.tearDown() }

    private func openDraft() -> (XCUIApplication, XCUIElement) {
        let app = XCUIApplication()
        app.launchArguments = ["-U2Fixtures", "YES", "-U2FrozenClock", "YES", "-U2Language", "tr",
            "-U2Theme", "dark", "-U2Route", "conversation:deniz"]
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["message.d6"].firstMatch.waitForExistence(timeout: 10))
        let field = app.textFields.firstMatch
        field.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        for character in "Taslak" { field.typeText(String(character)) }
        XCTAssertEqual(field.value as? String, "Taslak")
        return (app, field)
    }

    func testTappingAMessageDismissesKeyboardAndKeepsTheDraft() {
        let (app, field) = openDraft()
        let bubble = app.descendants(matching: .any)["message.d5"].firstMatch
        XCTAssertTrue(bubble.isHittable)
        bubble.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5))
        XCTAssertEqual(field.value as? String, "Taslak")
        field.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        XCTAssertEqual(field.value as? String, "Taslak")
        XCTAssertFalse(app.descendants(matching: .any)["menu.reply"].firstMatch.exists)
    }

    func testSendingKeepsTheKeyboardOpenForTheNextMessage() {
        let (app, field) = openDraft()
        app.buttons["Gönder"].firstMatch.tap()
        XCTAssertTrue(app.keyboards.firstMatch.exists)
        field.typeText("x")
        XCTAssertEqual(field.value as? String, "x")
        XCTAssertTrue(app.staticTexts["Taslak"].exists)
    }

    func testTappingTheEmptyGutterDismissesKeyboard() {
        let (app, field) = openDraft()
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.99, dy: 0.3)).tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5))
        XCTAssertEqual(field.value as? String, "Taslak")
    }
}
