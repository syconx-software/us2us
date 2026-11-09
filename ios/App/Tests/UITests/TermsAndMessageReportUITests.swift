import XCTest

/// App Review 1.2 with real taps on the design fixtures: the Terms of Use
/// before 1.3's “Hesabımı oluştur” (“Vazgeç” stays on 1.3; “Kabul ediyorum”
/// goes on to 2.1), the terms over the app for an account that never
/// accepted them (no way past but “Kabul ediyorum”), and 5.3 › “Bildir” on
/// someone else's message, in a chat and in a community. Screenshots are kept.
@MainActor
final class TermsAndMessageReportUITests: XCTestCase {
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

    private func settle(_ seconds: TimeInterval = 0.8) {
        Thread.sleep(forTimeInterval: seconds)
    }

    /// 1.3 “Hesabımı oluştur” → the terms → “Vazgeç”: still 1.3, nothing
    /// set up; again → “Kabul ediyorum” → 2.1. Asked once: back on 1.3,
    /// “Hesabımı oluştur” goes straight on.
    func testTheTermsComeBeforeCreatingAnAccount() {
        let app = launch(["-U2Start", "welcome", "-U2ResetPreferences", "YES", "-U2Terms", "ask"])
        tap(app, "welcome.start", timeout: 8)
        tap(app, "welcome.page2.cta")
        tap(app, "welcome.page3.cta")
        XCTAssertTrue(element(app, "terms.sheet").waitForExistence(timeout: 6), "the terms first")
        XCTAssertTrue(
            app.staticTexts["us2us'ta uygunsuz içeriğe ve kötü niyetli kullanıcılara hiçbir şekilde tolerans gösterilmez."]
                .exists, "the zero-tolerance rule")
        XCTAssertTrue(element(app, "terms.full").exists, "the full terms")
        XCTAssertTrue(element(app, "terms.rule.report").exists)
        settle()
        snap("01 terms before creating an account")

        tap(app, "terms.decline")
        XCTAssertTrue(element(app, "terms.sheet").waitForNonExistence(timeout: 6))
        XCTAssertFalse(app.textFields.firstMatch.exists, "declined: no 2.1")
        XCTAssertTrue(element(app, "welcome.page3.cta").exists, "still on 1.3")

        tap(app, "welcome.page3.cta")
        XCTAssertTrue(element(app, "terms.sheet").waitForExistence(timeout: 6), "asked again")
        tap(app, "terms.agree")
        XCTAssertTrue(app.textFields.firstMatch.waitForExistence(timeout: 6), "accepted: on to 2.1")
        settle()
        snap("02 accepted · 2.1")
    }

    /// The other ways in ask too: “Eski telefondan taşı”.
    func testTheTermsComeBeforeMovingFromAnotherPhone() {
        let app = launch(["-U2Start", "welcome", "-U2ResetPreferences", "YES", "-U2Terms", "ask"])
        tap(app, "welcome.transfer", timeout: 8)
        XCTAssertTrue(element(app, "terms.sheet").waitForExistence(timeout: 6), "the terms first")
        tap(app, "terms.decline")
        XCTAssertTrue(element(app, "terms.sheet").waitForNonExistence(timeout: 6))
        XCTAssertTrue(element(app, "welcome.transfer").exists, "still on 1.1")
    }

    /// An account here that never accepted the terms (an older build): they
    /// cover the app — no “Vazgeç”, pulling down keeps them — until accepted.
    func testAnExistingAccountAcceptsBeforeUsingTheApp() {
        let app = launch(["-U2Start", "chats", "-U2Terms", "ask"])
        let sheet = element(app, "terms.sheet.required")
        XCTAssertTrue(sheet.waitForExistence(timeout: 8), "the terms over the app")
        XCTAssertFalse(element(app, "terms.decline").exists, "no way past but agreeing")
        settle()
        snap("03 terms over the app")
        element(app, "terms.bar").swipeDown()
        settle()
        XCTAssertTrue(sheet.exists, "pulled down, the terms stay")
        tap(app, "terms.agree")
        XCTAssertTrue(sheet.waitForNonExistence(timeout: 6))
        XCTAssertTrue(element(app, "chat.deniz").waitForExistence(timeout: 6), "the app")
    }

    /// 5.3 on deniz's “Bu akşam boş musun?” → “Bildir” → the sheet with
    /// “Bu mesajın metnini bildirime ekle” on and “Bu kişiyi de engelle”;
    /// own messages have no “Bildir”.
    func testReportAMessageInAChat() {
        let app = launch(["-U2Route", "conversation:deniz", "-U2ReportMail", "address"])
        let own = element(app, "message.d5")
        XCTAssertTrue(own.waitForExistence(timeout: 10))
        own.press(forDuration: 0.8)
        XCTAssertTrue(element(app, "menu.deleteMe").waitForExistence(timeout: 5))
        XCTAssertFalse(element(app, "menu.report").exists, "not on your own message")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.95)).tap()
        XCTAssertTrue(element(app, "menu.deleteMe").waitForNonExistence(timeout: 5))
        settle(0.5)

        let theirs = element(app, "message.d1")
        XCTAssertTrue(theirs.waitForExistence(timeout: 6))
        theirs.press(forDuration: 0.8)
        XCTAssertTrue(element(app, "menu.report").waitForExistence(timeout: 5))
        settle()
        snap("04 5.3 · Bildir")
        element(app, "menu.report").tap()
        XCTAssertTrue(element(app, "report.sheet").waitForExistence(timeout: 6))
        let include = element(app, "report.includeMessage")
        XCTAssertTrue(include.waitForExistence(timeout: 4), "“Bu mesajın metnini bildirime ekle”")
        XCTAssertEqual(include.value as? String, "1", "on by default")
        XCTAssertTrue(element(app, "report.alsoBlock").exists, "“Bu kişiyi de engelle”")
        tap(app, "report.reason.harassment")
        settle()
        snap("05 report sheet from a message")
        tap(app, "report.send")
        XCTAssertTrue(element(app, "report.address").waitForExistence(timeout: 6), "no e-mail here: the address")
        tap(app, "report.done")
        XCTAssertTrue(element(app, "report.sheet").waitForNonExistence(timeout: 6))
        XCTAssertTrue(element(app, "conversation.blocked").waitForExistence(timeout: 6), "the block followed")
    }

    /// A community: another member's message offers “Bildir” too, and
    /// “Bu kişiyi de engelle” there hides everything that member wrote.
    func testReportAMessageInACommunity() {
        let app = launch([
            "-U2Communities", "YES", "-U2Route", "community:6f1c2a8e-41d2-4d55-9a0e-2f7c1b3d9e10",
            "-U2ReportMail", "address",
        ])
        let theirs = element(app, "message.c2")
        XCTAssertTrue(theirs.waitForExistence(timeout: 10))
        theirs.press(forDuration: 0.8)
        XCTAssertTrue(element(app, "menu.report").waitForExistence(timeout: 5))
        element(app, "menu.report").tap()
        XCTAssertTrue(element(app, "report.sheet").waitForExistence(timeout: 6))
        XCTAssertTrue(element(app, "report.includeMessage").waitForExistence(timeout: 4))
        XCTAssertTrue(element(app, "report.alsoBlock").exists, "“Bu kişiyi de engelle” for a community member")
        settle()
        snap("06 report sheet from a community message")
        tap(app, "report.reason.spam")
        tap(app, "report.send")
        XCTAssertTrue(element(app, "report.address").waitForExistence(timeout: 6))
        tap(app, "report.done")
        XCTAssertTrue(element(app, "report.sheet").waitForNonExistence(timeout: 6))
        XCTAssertTrue(element(app, "message.c2").waitForNonExistence(timeout: 6), "the member's messages are hidden")
        XCTAssertFalse(element(app, "message.c7").exists)
        XCTAssertTrue(element(app, "message.c1").exists, "the others' stay")
    }

    /// 5.3 on mert_k's message in a community → “Engelle” → the
    /// confirmation → everything mert_k wrote leaves the conversation; the
    /// others' and the user's own messages stay. Own messages offer no block.
    func testBlockAMemberInACommunity() {
        let app = launch(["-U2Communities", "YES", "-U2Route", "community:6f1c2a8e-41d2-4d55-9a0e-2f7c1b3d9e10"])
        let own = element(app, "message.c4")
        XCTAssertTrue(own.waitForExistence(timeout: 10))
        own.press(forDuration: 0.8)
        XCTAssertTrue(element(app, "menu.deleteMe").waitForExistence(timeout: 5))
        XCTAssertFalse(element(app, "menu.block").exists, "not on your own message")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.95)).tap()
        XCTAssertTrue(element(app, "menu.deleteMe").waitForNonExistence(timeout: 5))
        settle(0.5)

        XCTAssertTrue(element(app, "message.c7").exists)
        let theirs = element(app, "message.c2")
        theirs.press(forDuration: 0.8)
        XCTAssertTrue(element(app, "menu.block").waitForExistence(timeout: 5))
        XCTAssertTrue(element(app, "menu.report").exists)
        settle()
        snap("07 5.3 in a community · Engelle")
        element(app, "menu.block").tap()
        XCTAssertTrue(element(app, "community.block.confirm").waitForExistence(timeout: 6))
        settle()
        snap("08 community block confirmation")
        element(app, "community.block.confirm").tap()
        XCTAssertTrue(element(app, "community.block.confirm").waitForNonExistence(timeout: 6))
        XCTAssertTrue(element(app, "message.c2").waitForNonExistence(timeout: 6), "mert_k's messages are hidden")
        XCTAssertFalse(element(app, "message.c7").exists)
        XCTAssertTrue(element(app, "message.c1").exists, "deniz's stay")
        XCTAssertTrue(element(app, "message.c4").exists, "the user's own stay")
        settle()
        snap("09 community with a blocked member's messages hidden")
    }

    /// The relay suspended the account: “Hesabın askıya alındı” covers the
    /// app; “Destek ekibine yaz” on a phone without e-mail shows the
    /// address to copy; the terms are a tap away.
    func testASuspendedAccountShowsTheNotice() {
        let app = launch(["-U2Route", "chats:4.1-suspended", "-U2ReportMail", "address"])
        XCTAssertTrue(element(app, "suspended.screen").waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Hesabın askıya alındı"].waitForExistence(timeout: 4))
        XCTAssertTrue(element(app, "suspended.message").exists)
        XCTAssertTrue(element(app, "suspended.terms").exists)
        XCTAssertTrue(element(app, "suspended.erase").exists)
        XCTAssertFalse(element(app, "chats.new").exists, "the app is covered")
        settle()
        snap("10 suspended account")
        tap(app, "suspended.mail")
        XCTAssertTrue(element(app, "suspended.address").waitForExistence(timeout: 6), "no e-mail here: the address")
        tap(app, "suspended.copy")
        settle()
        snap("11 suspended account · address to copy")
    }
}
