import XCTest

/// “Tümünü reddet” (owner, two or more join requests) with real taps on the
/// DEBUG community fixtures: 4.1's request row → the manage sheet → the
/// confirmation → every request gone. Cancelling first changes nothing.
@MainActor
final class CommunityRejectAllUITests: XCTestCase {
    private let bookClub = "6f1c2a8e-41d2-4d55-9a0e-2f7c1b3d9e10"
    private let requests = ["5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e", "6c7d8e9f-0a1b-4c2d-9e3f-4a5b6c7d8e9f"]

    override func setUp() async throws {
        continueAfterFailure = false
    }

    private func launch(_ language: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = [
            "-U2Fixtures", "YES", "-U2Communities", "YES", "-U2FrozenClock", "YES", "-U2Language", language,
            "-U2Theme", "dark", "-U2Route", "chats",
        ]
        app.launch()
        return app
    }

    private func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier].firstMatch
    }

    private func tap(_ app: XCUIApplication, _ identifier: String, timeout: TimeInterval = 8) {
        let target = element(app, identifier)
        XCTAssertTrue(target.waitForExistence(timeout: timeout), "\(identifier) exists")
        XCTAssertTrue(target.wait(for: \.isHittable, toEqual: true, timeout: 4), "\(identifier) is hittable")
        target.tap()
    }

    private func snap(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func openRequests(_ app: XCUIApplication) {
        tap(app, "chats.communityRequests." + bookClub)
        for id in requests {
            XCTAssertTrue(element(app, "manage.request." + id).waitForExistence(timeout: 6), "request \(id) shown")
        }
    }

    func testRejectAllAsksFirstThenRemovesEveryRequest() {
        let app = launch("tr")
        openRequests(app)
        let rejectAll = element(app, "manage.rejectAll")
        XCTAssertTrue(rejectAll.waitForExistence(timeout: 4))
        XCTAssertEqual(rejectAll.label, "Tümünü reddet")
        snap("01 requests")

        // Cancel: nothing changes.
        rejectAll.tap()
        XCTAssertTrue(app.staticTexts["Tüm katılım istekleri reddedilsin mi?"].waitForExistence(timeout: 4))
        XCTAssertTrue(
            app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "Daha sonra yeniden isteyebilirler")).count > 0)
        snap("02 confirmation")
        tap(app, "communities.confirm.cancel")
        for id in requests { XCTAssertTrue(element(app, "manage.request." + id).waitForExistence(timeout: 4)) }

        // Confirm: every request goes, and so does the button.
        tap(app, "manage.rejectAll")
        tap(app, "communities.confirm")
        for id in requests {
            XCTAssertTrue(element(app, "manage.request." + id).waitForNonExistence(timeout: 6), "request \(id) gone")
        }
        XCTAssertFalse(element(app, "manage.rejectAll").exists)
        snap("03 rejected")
    }

    func testEnglishWording() {
        let app = launch("en")
        openRequests(app)
        let rejectAll = element(app, "manage.rejectAll")
        XCTAssertTrue(rejectAll.waitForExistence(timeout: 4))
        XCTAssertEqual(rejectAll.label, "Reject all")
        rejectAll.tap()
        XCTAssertTrue(app.staticTexts["Reject all join requests?"].waitForExistence(timeout: 4))
        XCTAssertTrue(
            app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "They can ask again later")).count > 0)
        tap(app, "communities.confirm.cancel")
    }
}
