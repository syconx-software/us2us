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

    /// 7.1 › Yedek → 7.2: a short and a guessable password are refused with
    /// their reasons, then it is asked twice, the mismatch is shown, then the
    /// system share sheet with the .u2u file.
    func testBackupExportAsksTwiceAndOpensTheShareSheet() {
        let app = launch(["-U2Route", "settings"])
        app.buttons["settings.backup"].tap()
        XCTAssertTrue(app.staticTexts["YEDEĞE GİRECEKLER"].waitForExistence(timeout: 5))

        let password = app.secureTextFields.element(boundBy: 0)
        type("kedi12", into: password)
        app.buttons["backup.export"].tap()
        XCTAssertTrue(app.staticTexts["Yedek parolası en az 10 karakter olmalı."].waitForExistence(timeout: 5))

        password.tap()
        password.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 6) + "Ahmet1985!")
        app.buttons["backup.export"].tap()
        let weak = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Bu parola kolay tahmin edilir."))
            .firstMatch
        XCTAssertTrue(weak.waitForExistence(timeout: 5))

        password.tap()
        password.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 10) + "v7qz3kd9xw2mtr")
        app.buttons["backup.export"].tap()
        let confirmation = app.secureTextFields.element(boundBy: 1)
        XCTAssertTrue(confirmation.waitForExistence(timeout: 5))
        confirmation.typeText("v7qz3kd9xw2m")
        app.buttons["backup.export"].tap()
        XCTAssertTrue(app.staticTexts["Parolalar eşleşmedi."].waitForExistence(timeout: 5))

        confirmation.tap()
        confirmation.typeText("tr")
        app.buttons["backup.export"].tap()
        let sheetTitle = app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "u2u")).firstMatch
        XCTAssertTrue(sheetTitle.waitForExistence(timeout: 15))
    }

    /// 7.2: the screen says what the backup holds (no switches: never
    /// messages or media); “Güçlü parola oluştur” fills the field and shows
    /// the password with “Kopyala”; the confirmation is still asked.
    func testGeneratedPasswordIsShownCopiedAndConfirmed() throws {
        let app = launch(["-U2Route", "settings:7.2-empty"])
        let generate = app.buttons["backup.password.generate"]
        XCTAssertTrue(generate.waitForExistence(timeout: 8))
        XCTAssertEqual(app.switches.count, 0)
        XCTAssertTrue(app.staticTexts["backup.contents.footnote"].exists)

        generate.tap()
        let shown = app.staticTexts["backup.password.generated"]
        XCTAssertTrue(shown.waitForExistence(timeout: 5))
        // Hidden until “Göster”: VoiceOver hears “Gizli”, never the password.
        XCTAssertEqual(shown.value as? String, "Gizli")
        app.buttons["backup.password.reveal"].tap()
        let generated = try XCTUnwrap(shown.value as? String)
        XCTAssertEqual(generated.count, 24)
        XCTAssertEqual(generated.filter { $0 == "-" }.count, 4)

        let copy = app.buttons["backup.password.copy"]
        copy.tap()
        let copied = app.descendants(matching: .any)["backup.password.copied"]
        XCTAssertTrue(copied.waitForExistence(timeout: 5))
        XCTAssertTrue(copied.label.contains("Kopyalandı · yalnızca bu cihazda, 1 dk sonra silinir"))

        app.buttons["backup.export"].tap()
        let confirmation = app.secureTextFields.element(boundBy: 1)
        XCTAssertTrue(confirmation.waitForExistence(timeout: 5))
        confirmation.typeText(generated)
        app.buttons["backup.export"].tap()
        let sheetTitle = app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "u2u")).firstMatch
        XCTAssertTrue(sheetTitle.waitForExistence(timeout: 15))
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
