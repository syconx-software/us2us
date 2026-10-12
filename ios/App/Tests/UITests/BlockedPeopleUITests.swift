import XCTest

/// 7.1 › Gizlilik › Engellenenler with real taps on the design fixtures
/// (`settings:7.1-blocked`: bora blocked with the chat still in 4.1, lale
/// blocked after Ghost erased the chat): the row's count, both people in the
/// list, “Engeli kaldır” with no confirmation for each (the row leaves), the
/// empty state, the row without a count, and bora's chat in 4.1 no longer
/// blocked. Screenshots are kept.
@MainActor
final class BlockedPeopleUITests: XCTestCase {
    override func setUp() async throws {
        continueAfterFailure = false
    }

    private func launch(_ arguments: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments =
            ["-U2Fixtures", "YES", "-U2FrozenClock", "YES", "-U2Language", "tr", "-U2Theme", "dark", "-U2Start", "chats"]
            + arguments
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

    private func settle(_ seconds: TimeInterval = 0.7) {
        Thread.sleep(forTimeInterval: seconds)
    }

    func testUnblockingEveryoneFromSettingsEndsOnTheEmptyState() {
        let app = launch(["-U2Route", "settings:7.1-blocked"])
        let row = element(app, "settings.blocked")
        XCTAssertTrue(row.waitForExistence(timeout: 8))
        XCTAssertTrue(row.label.hasPrefix("Engellenenler"), row.label)
        XCTAssertTrue(row.label.contains("2"), "the row counts who is blocked: \(row.label)")
        settle()
        snap("01 7.1 · Engellenenler 2")

        row.tap()
        XCTAssertTrue(element(app, "blocked.unblock.bora").waitForExistence(timeout: 6), "bora, whose chat is in 4.1")
        XCTAssertTrue(element(app, "blocked.unblock.lale").exists, "lale, whose chat is gone")
        XCTAssertTrue(app.staticTexts["lale"].exists)
        XCTAssertFalse(element(app, "blocked.empty").exists)
        settle()
        snap("02 Engellenenler")

        // No confirmation: the row leaves at once, the other stays.
        tap(app, "blocked.unblock.lale")
        XCTAssertTrue(element(app, "blocked.unblock.lale").waitForNonExistence(timeout: 6), "lale left the list")
        XCTAssertFalse(app.staticTexts["lale"].exists)
        XCTAssertFalse(app.alerts.firstMatch.exists, "nothing asks first")
        XCTAssertTrue(element(app, "blocked.unblock.bora").exists)
        settle()
        snap("03 lale unblocked")

        tap(app, "blocked.unblock.bora")
        let empty = element(app, "blocked.empty")
        XCTAssertTrue(empty.waitForExistence(timeout: 6), "nobody left: the empty state")
        XCTAssertEqual(empty.label, "Engellediğin kimse yok.")
        XCTAssertFalse(element(app, "blocked.list").exists)
        settle()
        snap("04 empty")

        // 7.1 counts nobody; 4.1 has bora's chat back, not blocked.
        tap(app, "blocked.back")
        XCTAssertTrue(row.waitForExistence(timeout: 6))
        XCTAssertFalse(row.label.contains("2"), "no count with nobody blocked: \(row.label)")
        tap(app, "settings.back")
        let bora = element(app, "chat.bora")
        XCTAssertTrue(bora.waitForExistence(timeout: 6))
        XCTAssertFalse(bora.label.contains("Engellendi"), "bora's chat is not blocked any more: \(bora.label)")
        XCTAssertFalse(element(app, "chat.lale").exists, "lale has no chat: none appears")
    }

    /// Someone blocked from the contact screen shows up in the list, and
    /// unblocking them there is the contact screen's “Engeli kaldır”: 5.1
    /// has its composer back.
    func testSomeoneBlockedFromTheirContactScreenIsListedAndUnblockedThere() {
        let app = launch(["-U2Route", "conversation:deniz"])
        tap(app, "chatHeader.peer", timeout: 8)
        tap(app, "contact.more")
        tap(app, "menu.block", timeout: 4)
        let alert = app.alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 4))
        alert.buttons["Engelle"].tap()
        XCTAssertTrue(element(app, "contact.blocked").waitForExistence(timeout: 6))
        tap(app, "safety.back")
        XCTAssertTrue(element(app, "conversation.blocked").waitForExistence(timeout: 6))
        tap(app, "chatHeader.back")
        tap(app, "chats.settings")
        let row = element(app, "settings.blocked")
        XCTAssertTrue(row.waitForExistence(timeout: 6))
        XCTAssertTrue(row.label.contains("1"), row.label)
        row.tap()
        tap(app, "blocked.unblock.deniz")
        XCTAssertTrue(element(app, "blocked.empty").waitForExistence(timeout: 6))
        tap(app, "blocked.back")
        tap(app, "settings.back")
        tap(app, "chat.deniz")
        XCTAssertTrue(element(app, "chatHeader.peer").waitForExistence(timeout: 6))
        XCTAssertFalse(element(app, "conversation.blocked").exists, "the composer is back")
    }
}
