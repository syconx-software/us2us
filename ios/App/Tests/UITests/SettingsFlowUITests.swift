import XCTest

/// 07 · settings flows with the design fixtures.
@MainActor
final class SettingsFlowUITests: XCTestCase {
    override func setUp() async throws {
        continueAfterFailure = false
    }

    private func launch(_ arguments: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-U2Fixtures", "YES", "-U2FrozenClock", "YES", "-U2Language", "tr", "-U2Theme", "dark", "-U2Start", "chats"] + arguments
        app.launch()
        return app
    }

    private func type(_ text: String, into field: XCUIElement) {
        field.tap()
        field.typeText(text)
    }

    /// 7.1 › Yedek → 7.2: weak password refused, asked twice, mismatch shown,
    /// then the system share sheet with the .u2u file.
    func testBackupExportAsksTwiceAndOpensTheShareSheet() {
        let app = launch(["-U2Route", "settings"])
        app.buttons["settings.backup"].tap()
        XCTAssertTrue(app.staticTexts["YEDEĞE GİRECEKLER"].waitForExistence(timeout: 5))

        let password = app.secureTextFields.element(boundBy: 0)
        type("kedi12", into: password)
        app.buttons["backup.export"].tap()
        XCTAssertTrue(app.staticTexts["Bu parola kolay tahmin edilir. Daha uzun bir parola seç ya da harf, rakam ve işaretleri karıştır."].waitForExistence(timeout: 5))

        password.tap()
        password.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 6) + "kuzey7deniz42k")
        app.buttons["backup.export"].tap()
        let confirmation = app.secureTextFields.element(boundBy: 1)
        XCTAssertTrue(confirmation.waitForExistence(timeout: 5))
        confirmation.typeText("kuzey7deniz4")
        app.buttons["backup.export"].tap()
        XCTAssertTrue(app.staticTexts["Parolalar eşleşmedi."].waitForExistence(timeout: 5))

        confirmation.tap()
        confirmation.typeText("2k")
        app.buttons["backup.export"].tap()
        let sheetTitle = app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "u2u")).firstMatch
        XCTAssertTrue(sheetTitle.waitForExistence(timeout: 15))
    }

    /// 7.2: switching “Mesajlar” off also switches (and disables) the media row;
    /// the estimate follows. (Regression: the model's didSet re-entered itself.)
    func testMessagesOffTakesMediaWithIt() {
        let app = launch(["-U2Route", "settings:7.2-empty"])
        let messages = app.switches["backup.contents.messages"]
        XCTAssertTrue(messages.waitForExistence(timeout: 8))
        messages.tap()
        XCTAssertTrue(app.staticTexts["Yalnızca ayarlar ve kişiler"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.switches["backup.contents.media"].value as? String, "0")
        XCTAssertFalse(app.switches["backup.contents.media"].isEnabled)
        messages.tap()
        XCTAssertTrue(app.staticTexts["Tahmini 184 MB · 3.920 mesaj"].waitForExistence(timeout: 5))
    }

    /// 7.1 › Gizli kodu değiştir: current (through the lock's throttle) → new ×2 →
    /// back on 7.1; the lock then wants the new code.
    func testChangingTheSecretCode() {
        let app = launch(["-U2Route", "settings"])
        app.buttons["settings.changeCode"].tap()
        XCTAssertTrue(app.staticTexts["Mevcut kodunu gir"].waitForExistence(timeout: 5))
        for digit in "135790" { app.buttons[String(digit)].tap() }
        XCTAssertTrue(app.staticTexts["Kod yanlış. 2 yanlış daha olursa 1 dakika beklemen gerekir."].waitForExistence(timeout: 5))
        for digit in "246813" { app.buttons[String(digit)].tap() }
        XCTAssertTrue(app.staticTexts["Yeni kodunu belirle"].waitForExistence(timeout: 5))
        for digit in "123456" { app.buttons[String(digit)].tap() }
        XCTAssertTrue(app.staticTexts["Bu kod çok kolay tahmin edilir. Başka bir kod seç."].waitForExistence(timeout: 5))
        for digit in "471958" { app.buttons[String(digit)].tap() }
        XCTAssertTrue(app.staticTexts["Yeni kodu tekrar gir"].waitForExistence(timeout: 5))
        for digit in "471958" { app.buttons[String(digit)].tap() }
        XCTAssertTrue(app.staticTexts["Gizli kodun değişti."].waitForExistence(timeout: 8))

        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(app.staticTexts["Gizli kodunu gir"].waitForExistence(timeout: 5))
        for digit in "471958" { app.buttons[String(digit)].tap() }
        XCTAssertTrue(app.staticTexts["Ayarlar"].waitForExistence(timeout: 5))
    }

    /// 7.1 › Aktif durumu → Kimse (reciprocal), back shows the new value.
    func testPresencePicker() {
        let app = launch(["-U2Route", "settings"])
        app.buttons["settings.presence"].tap()
        XCTAssertTrue(app.staticTexts["Kimse görmez; sen de kimsenin aktif durumunu görmezsin."].waitForExistence(timeout: 5))
        app.buttons["picker.option.1"].tap()
        XCTAssertTrue(app.buttons["picker.option.1"].isSelected)
    }

    /// 7.1 › Her şeyi sil → second confirmation → 1.1.
    func testEraseEverythingReturnsToWelcome() {
        let app = launch(["-U2Route", "settings"])
        let erase = app.buttons["settings.erase"]
        for _ in 0..<4 where !erase.isHittable { app.swipeUp() }
        erase.tap()
        // Tap once the floating sheet has settled (under load its spring can
        // still be moving when the button first becomes hittable).
        let confirm = app.buttons["settings.confirm.destructive"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        var frame = confirm.frame
        for _ in 0..<20 {
            Thread.sleep(forTimeInterval: 0.15)
            let next = confirm.frame
            if next == frame, confirm.isHittable { break }
            frame = next
        }
        confirm.tap()
        XCTAssertTrue(app.buttons["welcome.start"].waitForExistence(timeout: 8))
    }

    /// 7.3: wrong password → “Parola yanlış”; right one → restore → “Devam”.
    func testRestoreWithWrongThenRightPassword() {
        let app = launch(["-U2Route", "settings:7.3-password"])
        let password = app.secureTextFields.element(boundBy: 0)
        XCTAssertTrue(password.waitForExistence(timeout: 8))
        type("yanlis-parola", into: password)
        app.buttons["restore.action.submit"].tap()
        XCTAssertTrue(app.staticTexts["Parola yanlış"].waitForExistence(timeout: 5))
        password.tap()
        password.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 13) + "kuzey7deniz42k")
        app.buttons["restore.action.submit"].tap()
        XCTAssertTrue(app.staticTexts["Parola doğru · yedek açıldı"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["restore.action.continue"].waitForExistence(timeout: 20))
    }
}
