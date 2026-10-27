import XCTest

/// “Sohbeti sil” and “Kişiyi sil” with real taps on the design fixtures: the
/// contact screen's “…” → the confirmation → back on 4.1 without the chat;
/// and a long press on a 4.1 row → “Kişiyi sil” → the confirmation → the row
/// goes. Screenshots are kept.
@MainActor
final class ChatDeletionUITests: XCTestCase {
    override func setUp() async throws {
        continueAfterFailure = false
    }

    private func launch(_ arguments: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments =
            ["-U2Fixtures", "YES", "-U2FrozenClock", "YES", "-U2Language", "tr", "-U2Theme", "dark"] + arguments
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

    /// 5.1 › the person → “…” › Sohbeti sil → “deniz ile sohbet silinsin
    /// mi?” › Sil: the contact screen and the chat go together; 4.1 has no
    /// deniz row any more.
    func testDeleteChatFromTheContactScreen() {
        let app = launch(["-U2Route", "conversation:deniz"])
        tap(app, "chatHeader.peer", timeout: 8)
        // The first tap can land while 5.1 is still being pushed.
        if !element(app, "contact.more").waitForExistence(timeout: 3) { tap(app, "chatHeader.peer") }
        tap(app, "contact.more")
        XCTAssertTrue(element(app, "menu.deleteContact").waitForExistence(timeout: 4))
        snap("01 menu")
        tap(app, "menu.deleteChat", timeout: 4)
        let alert = app.alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 4))
        XCTAssertTrue(alert.staticTexts["deniz ile sohbet silinsin mi?"].exists)
        XCTAssertTrue(
            alert.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "yalnızca bu telefondan")).count > 0,
            "it says: on this phone only")
        snap("02 confirmation")
        alert.buttons["Sil"].tap()
        XCTAssertTrue(element(app, "contact.more").waitForNonExistence(timeout: 6), "the contact screen went")
        XCTAssertTrue(element(app, "chats.list").waitForExistence(timeout: 6), "back on 4.1")
        XCTAssertTrue(element(app, "chat.deniz").waitForNonExistence(timeout: 6), "no deniz row")
        snap("03 4.1 without the chat")
    }

    /// The long press's “Kişiyi sil”. The system menu's items are not
    /// always in the app's accessibility tree; then the item is tapped where
    /// the menu draws it under the row (the alert's title checks the choice).
    private func chooseDeleteContact(_ app: XCUIApplication, on row: XCUIElement) {
        row.press(forDuration: 1.2)
        let item = app.buttons["Kişiyi sil"].firstMatch
        if item.waitForExistence(timeout: 3) {
            item.tap()
        } else {
            row.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: 110, dy: 162)).tap()
        }
    }

    /// 4.1 › a long press on mert_k → Kişiyi sil → Vazgeç keeps it; again →
    /// Sil removes the row.
    func testDeleteContactFromTheChatList() {
        let app = launch(["-U2Route", "chats"])
        let row = element(app, "chat.mert_k")
        XCTAssertTrue(row.waitForExistence(timeout: 8))
        chooseDeleteContact(app, on: row)
        var alert = app.alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 4))
        XCTAssertTrue(alert.staticTexts["mert_k silinsin mi?"].exists)
        // Not a block: a later message from them is a new request (§6.4).
        let returns = NSPredicate(format: "label CONTAINS %@", "yeniden yazarsa yeni bir istek olarak görünür")
        XCTAssertTrue(alert.staticTexts.containing(returns).firstMatch.exists)
        snap("01 confirmation")
        alert.buttons["Vazgeç"].tap()
        XCTAssertTrue(row.waitForExistence(timeout: 4), "cancelled: the chat stays")

        chooseDeleteContact(app, on: row)
        alert = app.alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 4))
        alert.buttons["Sil"].tap()
        XCTAssertTrue(row.waitForNonExistence(timeout: 6), "the row went")
        snap("02 4.1 without mert_k")
    }
}
