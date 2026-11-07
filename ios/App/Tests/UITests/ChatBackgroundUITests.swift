import XCTest

/// “Sohbet arka planı” with real taps on the design fixtures: the contact
/// screen's “…” → the sheet → a swatch is only previewed → “Kaydet” → the chat
/// shows it. Screenshots are kept.
@MainActor
final class ChatBackgroundUITests: XCTestCase {
    override func setUp() async throws {
        continueAfterFailure = false
    }

    private func launch(theme: String = "dark", _ arguments: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments =
            ["-U2Fixtures", "YES", "-U2FrozenClock", "YES", "-U2Language", "tr", "-U2Theme", theme] + arguments
        app.launch()
        return app
    }

    private func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier].firstMatch
    }

    private func tap(_ app: XCUIApplication, _ identifier: String, timeout: TimeInterval = 6) {
        let target = element(app, identifier)
        XCTAssertTrue(target.waitForExistence(timeout: timeout), "\(identifier) exists")
        target.tap()
    }

    private func snap(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func openSheet(_ app: XCUIApplication) {
        tap(app, "chatHeader.peer", timeout: 8)
        // The first tap can land while 5.1 is still being pushed.
        if !element(app, "contact.more").waitForExistence(timeout: 3) { tap(app, "chatHeader.peer") }
        tap(app, "contact.more")
        tap(app, "menu.background", timeout: 4)
        XCTAssertTrue(element(app, "background.save").waitForExistence(timeout: 4))
    }

    func testAPresetIsPreviewedThenSavedAndShownInTheChat() {
        let app = launch(["-U2Route", "conversation:deniz"])
        openSheet(app)
        snap("01 sheet dark")
        tap(app, "background.swatch.dusk")
        XCTAssertTrue(element(app, "background.swatch.dusk").isSelected)
        snap("02 preview dark")
        tap(app, "background.save")
        XCTAssertTrue(element(app, "background.save").waitForNonExistence(timeout: 10))
        tap(app, "safety.back")
        XCTAssertTrue(element(app, "chatHeader.peer").waitForExistence(timeout: 4))
        Thread.sleep(forTimeInterval: 0.8)
        snap("03 chat dark")
    }

    func testAPickedPhotoIsPreviewedThenSavedAndShownInTheChat() {
        let app = launch(["-U2Route", "conversation:deniz"])
        openSheet(app)
        tap(app, "background.photo")
        let photo = app.images.matching(identifier: "PXGGridLayout-Info").firstMatch
        XCTAssertTrue(photo.waitForExistence(timeout: 10), "no photo in the simulator's library to pick")
        photo.tap()
        XCTAssertTrue(photo.waitForNonExistence(timeout: 10))
        // None of the swatches is selected once a photo is.
        let standard = app.buttons["background.swatch.standard"]
        let unselected = expectation(for: NSPredicate(format: "isSelected == false"), evaluatedWith: standard)
        XCTAssertEqual(XCTWaiter().wait(for: [unselected], timeout: 8), .completed)
        Thread.sleep(forTimeInterval: 0.5)
        snap("05 photo preview")
        tap(app, "background.save")
        XCTAssertTrue(element(app, "background.save").waitForNonExistence(timeout: 10))
        tap(app, "safety.back")
        XCTAssertTrue(element(app, "chatHeader.peer").waitForExistence(timeout: 4))
        Thread.sleep(forTimeInterval: 0.8)
        snap("06 photo chat")
    }

    func testTheSheetOpensOnTheSavedChoiceAndCanReturnToTheDefault() {
        let app = launch(theme: "light", ["-U2Route", "conversation:deniz"])
        openSheet(app)
        tap(app, "background.swatch.peach")
        tap(app, "background.save")
        XCTAssertTrue(element(app, "background.save").waitForNonExistence(timeout: 10))
        tap(app, "safety.back")
        XCTAssertTrue(element(app, "chatHeader.peer").waitForExistence(timeout: 4))
        Thread.sleep(forTimeInterval: 0.8)
        snap("04 chat light")

        openSheet(app)
        XCTAssertTrue(element(app, "background.swatch.peach").isSelected)
        tap(app, "background.swatch.standard")
        tap(app, "background.save")
        XCTAssertTrue(element(app, "background.save").waitForNonExistence(timeout: 10))
    }
}
