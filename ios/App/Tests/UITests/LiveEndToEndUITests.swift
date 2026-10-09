import CoreLocation
import XCTest

/// The real app against a real relay and a real peer (the Kotlin echo bot of
/// `scripts/interop.sh`): no fixtures. Skipped unless the run passes the relay
/// and the bot (README › Live end-to-end run):
///
///     TEST_RUNNER_U2E2E_RELAY=http://127.0.0.1:<port>   the local relay (DEBUG)
///     TEST_RUNNER_U2E2E_BOT=<bot username>               the echo bot
///     TEST_RUNNER_U2E2E_USER=<new username>              this phone's account
///     TEST_RUNNER_U2E2E_CODE=<6 digits>                  its secret code
///     TEST_RUNNER_U2E2E_TEXT=<text>                      the Turkish message sent
///
/// `test1…` creates the account and talks to the bot; `test2…` exports a
/// backup, erases everything and restores it. Run them one at a time
/// (`-only-testing:us2usUITests/LiveEndToEndUITests/<test>`), test1 first on a
/// freshly installed app, so the container can be inspected in between.
@MainActor
final class LiveEndToEndUITests: XCTestCase {
    private struct Run {
        let relay: String
        let bot: String
        let user: String
        let code: String
        let text: String
        let backupPassword = "kuzey7deniz42k!"
    }

    private var run: Run!

    override func setUp() async throws {
        continueAfterFailure = false
        let env = ProcessInfo.processInfo.environment
        guard let relay = env["U2E2E_RELAY"], let bot = env["U2E2E_BOT"], let user = env["U2E2E_USER"],
            let code = env["U2E2E_CODE"], let text = env["U2E2E_TEXT"]
        else { throw XCTSkip("live end-to-end run: set TEST_RUNNER_U2E2E_* (see the class comment)") }
        run = Run(relay: relay, bot: bot, user: user, code: code, text: text)
    }

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-U2RelayURL", run.relay, "-U2Language", "tr", "-U2Theme", "dark"]
        app.launch()
        return app
    }

    // MARK: helpers

    private func shot(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier].firstMatch
    }

    private func labelled(_ app: XCUIApplication, contains text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    private func anyLabel(_ app: XCUIApplication, _ labels: [String]) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label IN %@", labels)).firstMatch
    }

    private func anyButton(_ app: XCUIApplication, _ labels: [String]) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label IN %@ OR identifier IN %@", labels, labels)).firstMatch
    }

    private func waitAndTap(_ element: XCUIElement, _ what: String, timeout: TimeInterval = 15) {
        XCTAssertTrue(element.waitForExistence(timeout: timeout), "\(what) did not appear")
        // A floating sheet's spring can still move under load: tap when it settled.
        var frame = element.frame
        for _ in 0..<20 {
            Thread.sleep(forTimeInterval: 0.15)
            let next = element.frame
            if next == frame, element.isHittable { break }
            frame = next
        }
        element.tap()
    }

    private func typeCode(_ app: XCUIApplication) {
        XCTAssertTrue(app.buttons["2"].waitForExistence(timeout: 15), "keypad")
        for digit in run.code { app.buttons[String(digit)].tap() }
    }

    private func unlock(_ app: XCUIApplication) {
        XCTAssertTrue(app.staticTexts["Gizli kodunu gir"].waitForExistence(timeout: 15), "lock screen")
        if !app.buttons["2"].exists, element(app, "lock.useCode").exists { element(app, "lock.useCode").tap() }
        typeCode(app)
    }

    /// The system alert (location) — accepted while in use; retried until it is gone.
    private func allowSystemAlert(_ buttons: [String]) {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let alert = springboard.alerts.firstMatch
        guard alert.waitForExistence(timeout: 15) else { return }
        for _ in 0..<5 {
            Thread.sleep(forTimeInterval: 1)  // let the alert finish presenting
            guard alert.exists else { return }
            for title in buttons where alert.buttons[title].exists {
                alert.buttons[title].tap()
                break
            }
            if alert.waitForNonExistence(timeout: 3) { return }
        }
    }

    private func composerField(_ app: XCUIApplication) -> XCUIElement {
        let view = app.textViews.firstMatch
        return view.exists ? view : app.textFields.firstMatch
    }

    private func send(_ text: String, in app: XCUIApplication) {
        let field = composerField(app)
        XCTAssertTrue(field.waitForExistence(timeout: 10), "composer")
        field.tap()
        field.typeText(text)
        waitAndTap(app.buttons["Gönder"], "send")
    }

    private func openAttachMenu(_ app: XCUIApplication, item: String) {
        if app.keyboards.firstMatch.exists { composerField(app).typeText("") }
        waitAndTap(app.buttons["Ekle"].firstMatch, "+")
        waitAndTap(element(app, "menu.\(item)"), "menu.\(item)")
    }

    // MARK: 1 · onboarding → bot → messages → ghost → lock

    func test1_OnboardingMessagingGhostLock() throws {
        let app = launch()
        // 1.1 → 1.3 → 2.1
        waitAndTap(app.buttons["welcome.start"], "welcome", timeout: 20)
        waitAndTap(app.buttons["welcome.page2.cta"], "1.2")
        waitAndTap(app.buttons["welcome.page3.cta"], "1.3")
        let username = app.textFields.firstMatch
        XCTAssertTrue(username.waitForExistence(timeout: 10))
        username.tap()
        username.typeText(run.user)
        let next = app.buttons["onboarding.username.continue"]
        XCTAssertTrue(next.wait(for: \.isEnabled, toEqual: true, timeout: 10), "username accepted")
        shot(app, "2.1 username")
        if next.isHittable { next.tap() } else { username.typeText("\n") }
        // 2.2: set, confirm
        typeCode(app)
        XCTAssertTrue(app.staticTexts["Kodu tekrar gir"].waitForExistence(timeout: 5))
        typeCode(app)
        // 2.3: acknowledge, finish (account creation with proof of work)
        waitAndTap(element(app, "onboarding.acknowledge"), "2.3")
        shot(app, "2.3")
        app.buttons["onboarding.finish"].tap()
        XCTAssertTrue(app.staticTexts["Sohbetler"].waitForExistence(timeout: 90), "4.1 after sign-up")
        shot(app, "4.1 empty")

        // 4.2: the bot by its exact username → 5.1
        let newChat = app.buttons["chats.new"].exists ? app.buttons["chats.new"] : app.buttons["chats.empty.new"]
        waitAndTap(newChat, "new chat")
        let search = element(app, "newChat.field")
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.tap()
        search.typeText(run.bot)
        XCTAssertTrue(element(app, "newChat.result").waitForExistence(timeout: 20), "bot found")
        shot(app, "4.2 found")
        waitAndTap(element(app, "newChat.write"), "write")

        // 5.1: the message request (Turkish text) → the bot accepts and echoes
        send(run.text, in: app)
        XCTAssertTrue(labelled(app, contains: "echo: \(run.text)").waitForExistence(timeout: 60), "text echo")
        // A request gets no receipts (PROTOCOL §8.4): the first bubble stays “Gönderildi”.
        XCTAssertTrue(labelled(app, contains: "Gönderildi").exists, "sent status")
        shot(app, "5.1 request echoed")
        // Accepted now: the next message is delivered.
        let second = "İkinci mesaj: çay mı kahve mi? ğüşöçı"
        send(second, in: app)
        XCTAssertTrue(labelled(app, contains: "echo: \(second)").waitForExistence(timeout: 60), "second echo")
        XCTAssertTrue(
            labelled(app, contains: "İletildi").waitForExistence(timeout: 30)
                || labelled(app, contains: "Okundu").exists, "delivered status")
        shot(app, "5.1 delivered")

        // A photo from the library (system picker, no photo permission)
        openAttachMenu(app, item: "photo")
        let photo = app.images.matching(NSPredicate(format: "label BEGINSWITH %@", "Photo")).firstMatch
        let anyImage = app.scrollViews.images.firstMatch
        if photo.waitForExistence(timeout: 20) { photo.tap() } else {
            XCTAssertTrue(anyImage.waitForExistence(timeout: 5), "photo picker")
            anyImage.tap()
        }
        XCTAssertTrue(labelled(app, contains: "echo-attachment:").waitForExistence(timeout: 90), "photo echo")
        shot(app, "5.2 photo echoed")

        // The current location (when-in-use asked now), simulated at Sultanahmet
        XCUIDevice.shared.location = XCUILocation(location: CLLocation(latitude: 41.0082, longitude: 28.9784))
        openAttachMenu(app, item: "location")
        allowSystemAlert(["Allow While Using App", "Uygulamayı Kullanırken İzin Ver", "Allow Once"])
        XCTAssertTrue(labelled(app, contains: "echo-location:").waitForExistence(timeout: 60), "location echo")
        shot(app, "5.2 location echoed")

        // Ghost on → the bot sees the settings
        waitAndTap(element(app, "conversation.ghost"), "ghost chip")
        let toggle = element(app, "ghost.toggle")
        XCTAssertTrue(toggle.waitForExistence(timeout: 10))
        shot(app, "5.5 ghost sheet")
        toggle.tap()
        waitAndTap(element(app, "ghost.save"), "ghost save")
        XCTAssertTrue(labelled(app, contains: "echo-settings: ghost=on").waitForExistence(timeout: 60), "ghost echo")
        shot(app, "5.1 ghost on")
        let read = labelled(app, contains: "Okundu").exists
        print("U2E2E status read=\(read)")

        // Background → foreground locks; the code opens it again
        XCUIDevice.shared.press(.home)
        Thread.sleep(forTimeInterval: 2)
        app.activate()
        unlock(app)
        XCTAssertTrue(composerField(app).waitForExistence(timeout: 20) || app.staticTexts["Sohbetler"].exists)
        shot(app, "after unlock")
        XCUIDevice.shared.press(.home)
    }

    // MARK: 2 · backup → erase → restore

    func test2_BackupEraseRestore() throws {
        let app = launch()
        unlock(app)
        if !app.buttons["chats.settings"].waitForExistence(timeout: 5) {
            let back = app.buttons.matching(identifier: "chatHeader.back").firstMatch
            if back.exists { back.tap() }
        }
        waitAndTap(app.buttons["chats.settings"], "settings")
        waitAndTap(app.buttons["settings.backup"], "backup")
        let password = app.secureTextFields.element(boundBy: 0)
        XCTAssertTrue(password.waitForExistence(timeout: 10))
        password.tap()
        password.typeText(run.backupPassword)
        app.buttons["backup.export"].tap()
        let confirmation = app.secureTextFields.element(boundBy: 1)
        XCTAssertTrue(confirmation.waitForExistence(timeout: 10))
        confirmation.tap()
        confirmation.typeText(run.backupPassword)
        app.buttons["backup.export"].tap()
        // The system share sheet → Save to Files → On My iPhone → Save (the
        // system UI follows the app's language: Turkish here).
        let saveToFiles = anyLabel(app, ["Dosyalar’a Kaydet", "Save to Files"])
        XCTAssertTrue(saveToFiles.waitForExistence(timeout: 60), "share sheet")
        shot(app, "share sheet")
        saveToFiles.tap()
        let save = anyButton(app, ["Kaydet", "Save"])
        XCTAssertTrue(save.waitForExistence(timeout: 20), "files save")
        let onMyPhone = anyLabel(app, ["iPhone’umda", "iPhone'umda", "On My iPhone"])
        if onMyPhone.waitForExistence(timeout: 3), onMyPhone.isHittable { onMyPhone.tap() }
        shot(app, "files save")
        anyButton(app, ["Kaydet", "Save"]).tap()
        // An earlier run's file of the same day: replace it.
        let replace = anyLabel(app, ["Değiştir", "Replace"])
        if replace.waitForExistence(timeout: 8) { replace.tap() }
        Thread.sleep(forTimeInterval: 2)
        shot(app, "after save")

        // 7.1 › Her şeyi sil → 1.1
        let back = element(app, "backup.back")
        if back.waitForExistence(timeout: 10), back.isHittable { back.tap() }
        let erase = app.buttons["settings.erase"]
        XCTAssertTrue(erase.waitForExistence(timeout: 10))
        for _ in 0..<4 where !erase.isHittable { app.swipeUp() }
        erase.tap()
        waitAndTap(app.buttons["settings.confirm.destructive"], "erase confirmation")
        XCTAssertTrue(app.buttons["welcome.start"].waitForExistence(timeout: 30), "1.1 after erase")
        shot(app, "1.1 after erase")

        // 1.1 › Yedekten geri yükle → 7.3 → the file → password → 2.2 → 2.3 → 4.1
        waitAndTap(app.buttons["welcome.restore"], "restore entry")
        let pick = app.buttons["restore.action.pick"].exists ? app.buttons["restore.action.pick"] : app.buttons["restore.pick"]
        waitAndTap(pick, "pick backup")
        let file = labelled(app, contains: "us2us-")
        if !file.waitForExistence(timeout: 10) {
            let browse = anyButton(app, ["Göz At", "Browse"])
            if browse.exists { browse.tap() }
            let onMyPhone2 = anyLabel(app, ["iPhone’umda", "iPhone'umda", "On My iPhone"])
            if onMyPhone2.waitForExistence(timeout: 5) { onMyPhone2.tap() }
        }
        shot(app, "document picker")
        XCTAssertTrue(file.waitForExistence(timeout: 20), "backup file in Files")
        file.tap()
        let open = anyButton(app, ["Aç", "Open"])
        if open.waitForExistence(timeout: 2) { open.tap() }
        let restorePassword = app.secureTextFields.element(boundBy: 0)
        XCTAssertTrue(restorePassword.waitForExistence(timeout: 20), "7.3 password")
        restorePassword.tap()
        restorePassword.typeText(run.backupPassword)
        app.buttons["restore.action.submit"].tap()
        XCTAssertTrue(app.staticTexts["Parola doğru · yedek açıldı"].waitForExistence(timeout: 30), "backup opened")
        waitAndTap(app.buttons["restore.action.continue"], "continue", timeout: 30)
        typeCode(app)
        XCTAssertTrue(app.staticTexts["Kodu tekrar gir"].waitForExistence(timeout: 5))
        typeCode(app)
        waitAndTap(element(app, "onboarding.acknowledge"), "2.3")
        app.buttons["onboarding.finish"].tap()
        XCTAssertTrue(app.staticTexts["Sohbetler"].waitForExistence(timeout: 90), "4.1 after restore")
        XCTAssertTrue(labelled(app, contains: run.bot).waitForExistence(timeout: 30), "the bot is still a contact")
        shot(app, "4.1 restored")
        XCUIDevice.shared.press(.home)
    }
}
