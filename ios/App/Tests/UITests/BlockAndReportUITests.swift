import XCTest

/// Blocking and reporting (App Review 1.2), with real taps on the design
/// fixtures: the contact screen's “…” › Engelle → the confirmation → the
/// blocked contact (the note, the mark by the name, 5.1's bar) → Engeli
/// kaldır (also for a blocked request, which leaves the chats); and
/// “Bildir” → the sheet → a reason → “E-postayla gönder”. The
/// simulator has no Mail, so the report takes the path every phone without
/// e-mail takes — the support address to copy (`-U2ReportMail address` keeps
/// it so whatever the simulator has installed). Screenshots are kept.
@MainActor
final class BlockAndReportUITests: XCTestCase {
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

    private func settle(_ seconds: TimeInterval = 0.7) {
        Thread.sleep(forTimeInterval: seconds)
    }

    /// 5.1 › the person → “…” › Engelle → “deniz engellensin mi?” › Engelle:
    /// the note and the mark on the contact screen, “Engellendi” and the bar
    /// in 5.1; “…” then offers “Engeli kaldır”, which brings the composer back.
    func testBlockAndUnblockFromTheContactScreen() {
        let app = launch(["-U2Route", "conversation:deniz"])
        tap(app, "chatHeader.peer", timeout: 8)
        tap(app, "contact.more")
        tap(app, "menu.block", timeout: 4)
        let alert = app.alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 4))
        XCTAssertTrue(alert.staticTexts["deniz engellensin mi?"].exists, "the request block's copy")
        XCTAssertTrue(alert.buttons["Bildir"].exists, "Bildir beside Engelle")
        snap("01 confirmation")
        alert.buttons["Engelle"].tap()
        XCTAssertTrue(element(app, "contact.blocked").waitForExistence(timeout: 6), "the blocked note")
        XCTAssertTrue(element(app, "contact.blockedMark").exists, "the mark by the name")
        settle()
        snap("02 contact screen · blocked")

        // 5.1: read only, the bar in the composer's place.
        tap(app, "safety.back")
        XCTAssertTrue(element(app, "conversation.blocked").waitForExistence(timeout: 6))
        XCTAssertTrue(element(app, "chatHeader.peer").label.contains("Engellendi"), "the header's status line")
        XCTAssertTrue(element(app, "conversation.unblock").exists)
        settle()
        snap("03 chat · blocked")

        // The same place offers “Engeli kaldır”.
        tap(app, "chatHeader.peer")
        tap(app, "contact.more")
        XCTAssertTrue(element(app, "menu.unblock").waitForExistence(timeout: 4))
        XCTAssertFalse(element(app, "menu.block").exists)
        snap("04 menu · Engeli kaldır")
        element(app, "menu.unblock").tap()
        XCTAssertTrue(element(app, "contact.blocked").waitForNonExistence(timeout: 6), "the note goes")
        XCTAssertFalse(element(app, "contact.blockedMark").exists)
        tap(app, "safety.back")
        XCTAssertTrue(element(app, "conversation.blocked").waitForNonExistence(timeout: 6), "the composer is back")
        settle()
        snap("05 chat · unblocked")
    }

    /// A request blocked in 4.4 stays in 4.1 as a blocked chat. “Engeli
    /// kaldır” on its contact screen makes it a request again: the contact
    /// screen goes back to 4.1 together with the chat under it — no empty
    /// chat on the way back — and 4.3 has the request again.
    func testUnblockingABlockedRequestFromItsContactScreenGoesBackToTheChats() {
        let app = launch(["-U2Route", "request:kuzey_dev"])
        tap(app, "preview.block", timeout: 8)
        let alert = app.alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 4))
        alert.buttons["Engelle"].tap()
        XCTAssertTrue(element(app, "request.kuzey_dev").waitForNonExistence(timeout: 6), "the request left 4.3")
        tap(app, "requests.back")
        tap(app, "chat.kuzey_dev")
        XCTAssertTrue(element(app, "conversation.blocked").waitForExistence(timeout: 6), "a blocked chat, read only")
        tap(app, "chatHeader.peer")
        tap(app, "contact.more")
        tap(app, "menu.unblock", timeout: 4)
        XCTAssertTrue(element(app, "contact.more").waitForNonExistence(timeout: 6), "the contact screen went")
        XCTAssertTrue(element(app, "chats.list").waitForExistence(timeout: 6), "back on 4.1")
        XCTAssertTrue(element(app, "chat.kuzey_dev").waitForNonExistence(timeout: 6), "no chat row any more")
        XCTAssertFalse(element(app, "chatHeader.peer").exists, "no empty chat left")
        settle()
        snap("01 4.1 after unblocking a blocked request")
        tap(app, "chats.requests")
        XCTAssertTrue(element(app, "request.kuzey_dev").waitForExistence(timeout: 6), "a request again")
    }

    /// “…” › Bildir → the sheet (send waits for a reason) → “Taciz veya
    /// zorbalık” → “E-postayla gönder”: no e-mail here, so the support
    /// address with “Kopyala”, and — “Bu kişiyi de engelle” was on — deniz
    /// blocked; “Tamam” closes on the blocked contact screen.
    func testReportFromTheContactScreenShowsTheAddressAndBlocks() {
        let app = launch(["-U2Route", "safety:deniz", "-U2ReportMail", "address"])
        tap(app, "contact.more", timeout: 8)
        tap(app, "menu.report", timeout: 4)
        XCTAssertTrue(element(app, "report.sheet").waitForExistence(timeout: 6))
        let send = element(app, "report.send")
        XCTAssertTrue(send.waitForExistence(timeout: 4))
        XCTAssertFalse(send.isEnabled, "a reason first")
        XCTAssertTrue(element(app, "report.alsoBlock").exists, "“Bu kişiyi de engelle”")
        XCTAssertTrue(element(app, "report.privacy").exists)
        tap(app, "report.reason.harassment")
        XCTAssertTrue(send.isEnabled)
        settle()
        snap("01 report sheet")
        send.tap()
        let address = element(app, "report.address")
        XCTAssertTrue(address.waitForExistence(timeout: 6), "no e-mail: the address")
        XCTAssertEqual(address.label, "support@us2us.app")
        XCTAssertTrue(element(app, "report.blocked").exists, "the block followed the report")
        let copy = element(app, "report.copy")
        copy.tap()
        let copied = NSPredicate(format: "label CONTAINS %@", "Kopyalandı")
        expectation(for: copied, evaluatedWith: copy)
        waitForExpectations(timeout: 4)
        settle()
        snap("02 address copied")
        tap(app, "report.done")
        XCTAssertTrue(element(app, "report.sheet").waitForNonExistence(timeout: 6))
        XCTAssertTrue(element(app, "contact.blocked").waitForExistence(timeout: 6), "deniz is blocked")
        settle()
        snap("03 contact screen · reported and blocked")
    }

    /// 4.4 › “…” › Bildir with “Bu kişiyi de engelle” off: nobody is blocked
    /// and the preview stays; on (the default), closing goes back to 4.3
    /// without the request.
    func testReportFromARequestPreview() {
        let app = launch(["-U2Route", "request:kuzey_dev", "-U2ReportMail", "address"])
        tap(app, "preview.more", timeout: 8)
        tap(app, "menu.report", timeout: 4)
        XCTAssertTrue(element(app, "report.sheet").waitForExistence(timeout: 6))
        tap(app, "report.reason.spam")
        tap(app, "report.alsoBlock")
        tap(app, "report.send")
        XCTAssertTrue(element(app, "report.address").waitForExistence(timeout: 6))
        XCTAssertFalse(element(app, "report.blocked").exists, "the toggle was off")
        tap(app, "report.done")
        XCTAssertTrue(element(app, "report.sheet").waitForNonExistence(timeout: 6))
        XCTAssertTrue(element(app, "preview.accept").waitForExistence(timeout: 4), "still on the preview")

        tap(app, "preview.more")
        tap(app, "menu.report", timeout: 4)
        tap(app, "report.reason.other")
        tap(app, "report.send")
        XCTAssertTrue(element(app, "report.blocked").waitForExistence(timeout: 6))
        tap(app, "report.done")
        XCTAssertTrue(element(app, "requests.back").waitForExistence(timeout: 6), "back on 4.3")
        XCTAssertTrue(element(app, "request.kuzey_dev").waitForNonExistence(timeout: 6), "the request is gone")
        settle()
        snap("01 4.3 after a report that blocked")
    }
}
