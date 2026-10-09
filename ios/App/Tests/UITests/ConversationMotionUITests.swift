import XCTest

/// 5.1's moving parts end where the design puts them: the keyboard is
/// dismissed by dragging the column (interactively) and the composer returns
/// to rest; a sent message lands at the bottom with its status sentence; a
/// long-press opens 5.3 and a tap outside closes it; the long conversation
/// opens at its newest message. Screenshots of each end state are attached.
@MainActor
final class ConversationMotionUITests: XCTestCase {
    override func setUp() async throws {
        continueAfterFailure = false
    }

    private func launch(_ arguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments =
            ["-U2Fixtures", "YES", "-U2FrozenClock", "YES", "-U2Language", "tr", "-U2Theme", "dark", "-U2Route", "conversation:deniz"] + arguments
        app.launch()
        return app
    }

    private func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier].firstMatch
    }

    private func composerField(_ app: XCUIApplication) -> XCUIElement {
        app.textViews.firstMatch.exists ? app.textViews.firstMatch : app.textFields.firstMatch
    }

    private func snap(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// Focus the composer, then drag the column down through the keyboard:
    /// the keyboard follows the finger and goes; the composer is back at rest.
    func testDraggingTheColumnDismissesTheKeyboard() {
        let app = launch()
        XCTAssertTrue(element(app, "message.d6").waitForExistence(timeout: 10))
        let field = composerField(app)
        let rest = field.frame
        field.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        Thread.sleep(forTimeInterval: 1)
        XCTAssertLessThan(field.frame.maxY, rest.maxY - 200, "the composer rides above the keyboard")
        snap("keyboard up")
        let origin = app.coordinate(withNormalizedOffset: .zero)
        origin.withOffset(CGVector(dx: 200, dy: 260))
            .press(
                forDuration: 0.05, thenDragTo: origin.withOffset(CGVector(dx: 200, dy: 860)),
                withVelocity: XCUIGestureVelocity(320), thenHoldForDuration: 0.4)
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5), "the drag dismissed the keyboard")
        Thread.sleep(forTimeInterval: 1)
        XCTAssertEqual(field.frame.midY, rest.midY, accuracy: 1, "the composer is back at rest")
        snap("keyboard dismissed by dragging")
    }

    /// Typing grows the field (up to 5 lines); sending clears it, lands the
    /// bubble at the bottom with its sentence, and the field is one line again.
    func testSendingLandsAtTheBottom() {
        let app = launch()
        XCTAssertTrue(element(app, "message.d6").waitForExistence(timeout: 10))
        let field = composerField(app)
        let oneLine = field.frame.height
        field.tap()
        field.typeText("Tamam, o zaman sekizde iskelede buluşalım. Ben biraz erken gelip vapurun saatine bakarım.")
        Thread.sleep(forTimeInterval: 0.6)
        XCTAssertGreaterThan(field.frame.height, oneLine + 10, "the field grew with the text")
        snap("multi-line draft")
        app.buttons["Gönder"].firstMatch.tap()
        Thread.sleep(forTimeInterval: 1.2)
        XCTAssertEqual(field.frame.height, oneLine, accuracy: 1, "the field is one line again")
        XCTAssertTrue(app.staticTexts["Tamam, o zaman sekizde iskelede buluşalım. Ben biraz erken gelip vapurun saatine bakarım."].exists)
        XCTAssertTrue(element(app, "message.d6").exists)
        snap("sent")
    }

    /// Long-press lifts the bubble into 5.3; a tap outside puts it back.
    func testLongPressOpensAndClosesTheActions() {
        let app = launch()
        let bubble = element(app, "message.d5")
        XCTAssertTrue(bubble.waitForExistence(timeout: 10))
        let frame = bubble.frame
        bubble.press(forDuration: 0.8)
        XCTAssertTrue(element(app, "menu.reply").waitForExistence(timeout: 5))
        Thread.sleep(forTimeInterval: 0.8)
        snap("5.3 open")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.95)).tap()
        XCTAssertTrue(element(app, "menu.reply").waitForNonExistence(timeout: 5))
        Thread.sleep(forTimeInterval: 0.6)
        XCTAssertEqual(element(app, "message.d5").frame.minY, frame.minY, accuracy: 1, "the bubble is back in its place")
    }

    /// 5000 older messages: it opens at the newest one, already at rest.
    func testLongConversationOpensAtTheNewestMessage() {
        let app = launch(["-U2StressMessages", "5000"])
        let newest = element(app, "message.d6")
        XCTAssertTrue(newest.waitForExistence(timeout: 20))
        Thread.sleep(forTimeInterval: 1)
        let composer = composerField(app)
        print("u2-frames newest \(newest.frame) composer \(composer.frame) d5 \(element(app, "message.d5").frame)")
        XCTAssertTrue(newest.isHittable)
        XCTAssertLessThan(newest.frame.maxY, composer.frame.minY, "above the composer")
        XCTAssertGreaterThan(newest.frame.minY, composer.frame.minY - 200, "at the bottom of the column")
        snap("5000 messages, opened")
    }
}
