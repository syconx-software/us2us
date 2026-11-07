import XCTest

/// “Yeni telefona taşı” with real taps on the design fixtures: the old phone
/// from 7.1 through the secret code and the scanner to “Aktarıldı”, and the new
/// phone from 1.1 through its code to the restore. Screenshots are kept.
@MainActor
final class DeviceTransferUITests: XCTestCase {
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

    private func tap(_ app: XCUIApplication, _ identifier: String, timeout: TimeInterval = 8) {
        let target = element(app, identifier)
        XCTAssertTrue(target.waitForExistence(timeout: timeout), "\(identifier) exists")
        target.tap()
    }

    private func snap(_ name: String) {
        Thread.sleep(forTimeInterval: 0.6)
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func openSend(_ app: XCUIApplication) {
        let row = element(app, "settings.transfer")
        XCTAssertTrue(row.waitForExistence(timeout: 8))
        var swipes = 0
        while !row.isHittable, swipes < 6 {
            app.swipeUp()
            swipes += 1
        }
        row.tap()
        XCTAssertTrue(element(app, "transfer.send.continue").waitForExistence(timeout: 5))
    }

    private func typeCode(_ app: XCUIApplication, _ code: String) {
        XCTAssertTrue(app.buttons["2"].waitForExistence(timeout: 5))
        for digit in code { app.buttons[String(digit)].tap() }
    }

    func testTheOldPhoneSendsAfterItsCodeAndTheScan() {
        let app = launch(["-U2Route", "settings"])
        openSend(app)
        snap("01 eski telefon")
        tap(app, "transfer.send.continue")
        typeCode(app, "246813")
        XCTAssertTrue(element(app, "scan.simulatorMock").waitForExistence(timeout: 6), "the scanner opens after the code")
        snap("02 tarayıcı")
        tap(app, "scan.simulatorMock")
        XCTAssertTrue(element(app, "transfer.send.done").waitForExistence(timeout: 8), "the account was sent")
        snap("03 aktarıldı")
    }

    func testAWrongCodeSendsNothing() {
        let app = launch(["-U2Route", "settings"])
        openSend(app)
        tap(app, "transfer.send.continue")
        typeCode(app, "111111")
        XCTAssertFalse(element(app, "scan.simulatorMock").waitForExistence(timeout: 2), "no scanner after a wrong code")
    }

    func testAFailureSaysWhatToDoAndCanBeRetried() {
        let app = launch(["-U2Route", "settings", "-U2TransferUnreachable", "YES"])
        openSend(app)
        tap(app, "transfer.send.continue")
        typeCode(app, "246813")
        tap(app, "scan.simulatorMock")
        XCTAssertTrue(element(app, "transfer.action").waitForExistence(timeout: 8))
        snap("04 ulaşılamadı")
        tap(app, "transfer.action")
        XCTAssertTrue(element(app, "scan.simulatorMock").waitForExistence(timeout: 6), "back at the scanner")
    }

    func testTheNewPhoneShowsItsCodeThenRestoresWithoutAskingAnything() {
        let app = launch(["-U2Route", "onboarding:1.1", "-U2TransferDelivers", "YES"])
        snap("05 karşılama")
        // The fixtures deliver a moment after the code is up: the next test looks at the code itself.
        tap(app, "welcome.transfer")
        XCTAssertTrue(element(app, "restore.action.continue").waitForExistence(timeout: 25))
        XCTAssertTrue(element(app, "transfer.restore.card").exists)
        XCTAssertFalse(element(app, "restore.password").exists, "nothing to type")
        snap("11 hesabın geldi")
        tap(app, "restore.action.continue")
        // 2.2: a new secret code on this phone, as after any restore.
        XCTAssertTrue(app.buttons["2"].waitForExistence(timeout: 6))
        snap("07 yeni gizli kod")
    }

    func testTheMessagesSettleInAfterTheNewSecretCode() {
        let app = launch(["-U2Route", "onboarding:1.1", "-U2TransferDelivers", "YES"])
        tap(app, "welcome.transfer")
        XCTAssertTrue(element(app, "restore.action.continue").waitForExistence(timeout: 25))
        tap(app, "restore.action.continue")
        typeCode(app, "730415")
        // Asked twice; the first keypad goes away first.
        Thread.sleep(forTimeInterval: 1)
        typeCode(app, "730415")
        let acknowledge = element(app, "onboarding.acknowledge")
        XCTAssertTrue(acknowledge.waitForExistence(timeout: 8))
        acknowledge.tap()
        tap(app, "onboarding.finish")
        let settling = app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'yerleşiyor'")).firstMatch
        XCTAssertTrue(settling.waitForExistence(timeout: 6), "the button says how far it got")
        snap("14 mesajlar yerleşiyor")
    }

    func testTheNewPhoneWaitsWithItsCodeOnScreen() {
        let app = launch(["-U2Route", "onboarding:1.1"])
        XCTAssertTrue(element(app, "welcome.restore").waitForExistence(timeout: 8))
        snap("09 karşılama")
        tap(app, "welcome.transfer")
        XCTAssertTrue(element(app, "transfer.receive.code").waitForExistence(timeout: 5))
        snap("10 yeni telefon kodu")
        tap(app, "transfer.receive.back")
        XCTAssertTrue(element(app, "welcome.transfer").waitForExistence(timeout: 5))
    }

    func testANewPhoneWithoutRoomSaysSo() {
        let app = launch(["-U2Route", "onboarding:1.1", "-U2TransferDoesNotFit", "YES"])
        tap(app, "welcome.transfer")
        XCTAssertTrue(element(app, "transfer.receive.failed").waitForExistence(timeout: 8))
        XCTAssertTrue(element(app, "transfer.action").exists)
        snap("12 yer yok yeni telefon")
    }

    func testAnOldPhoneWhoseNewPhoneHasNoRoomSaysSo() {
        let app = launch(["-U2Route", "settings", "-U2TransferNoSpace", "YES"])
        openSend(app)
        tap(app, "transfer.send.continue")
        typeCode(app, "246813")
        tap(app, "scan.simulatorMock")
        XCTAssertTrue(element(app, "transfer.action").waitForExistence(timeout: 8))
        snap("13 yer yok eski telefon")
    }

    func testTheNewPhoneWithoutANetworkSaysSo() {
        let app = launch(["-U2Route", "onboarding:1.1", "-U2TransferNoNetwork", "YES"])
        tap(app, "welcome.transfer")
        XCTAssertTrue(element(app, "transfer.action").waitForExistence(timeout: 6))
        snap("08 ağ yok")
    }
}
