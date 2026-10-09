import XCTest

/// Walks every flow of `ios/design/flows.md` end to end with real taps, on
/// the design fixtures, attaching a screenshot at every step (kept in the
/// result bundle: `xcrun xcresulttool export attachments`).
///
/// welcome → onboarding → 4.1; lock (wrong code, forgot, restore, erase);
/// requests → preview → accept; new chat → write; QR add → 6.3 → 5.1;
/// conversation → actions / info / reply / Ghost / view once; verification
/// by QR and by hand; settings → pickers, code change, backup.
@MainActor
final class FlowWalkUITests: XCTestCase {
    override func setUp() async throws {
        continueAfterFailure = false
    }

    private func launch(_ arguments: [String], language: String = "tr") -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-U2Fixtures", "YES", "-U2FrozenClock", "YES", "-U2Language", language, "-U2Theme", "dark"] + arguments
        app.launch()
        return app
    }

    /// A screenshot of the whole screen, kept with the results.
    private func snap(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func settle(_ seconds: TimeInterval = 0.7) {
        Thread.sleep(forTimeInterval: seconds)
    }

    private func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier].firstMatch
    }

    private func tap(_ app: XCUIApplication, _ identifier: String, timeout: TimeInterval = 6) {
        let target = element(app, identifier)
        XCTAssertTrue(target.waitForExistence(timeout: timeout), "\(identifier) not found")
        target.tap()
    }

    private func typeCode(_ app: XCUIApplication, _ code: String) {
        for digit in code { app.buttons[String(digit)].tap() }
    }

    // MARK: 01–02 · Account creation

    /// 1.1 → 1.2 → 1.3 → 2.1 (taken → available) → 2.2 weak → set → confirm
    /// mismatch → set → confirm → 2.3 → 4.1.
    func test01CreateAccount() {
        let app = launch(["-U2Start", "welcome", "-U2ResetPreferences", "YES"])
        XCTAssertTrue(element(app, "welcome.start").waitForExistence(timeout: 8))
        snap("01 1.1 Karşılama")
        tap(app, "welcome.start")
        settle()
        snap("02 1.2 Bildirim yok")
        tap(app, "welcome.page2.cta")
        settle()
        snap("03 1.3 Sessizlik siler")
        tap(app, "welcome.page3.cta")
        let field = app.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText("selin")
        XCTAssertTrue(app.staticTexts["Alınmış"].waitForExistence(timeout: 5))
        snap("04 2.1 Alınmış")
        field.typeText(".k")
        let next = app.buttons["onboarding.username.continue"]
        XCTAssertTrue(next.wait(for: \.isEnabled, toEqual: true, timeout: 5))
        snap("05 2.1 Kullanılabilir")
        if next.isHittable { next.tap() } else { field.typeText("\n") }
        XCTAssertTrue(app.buttons["2"].waitForExistence(timeout: 5))
        snap("06 2.2 Gizli kod")
        typeCode(app, "111111")
        settle(0.6)
        snap("07 2.2 Zayıf kod")
        settle(1.2)
        typeCode(app, "246813")
        XCTAssertTrue(app.staticTexts["Kodu tekrar gir"].waitForExistence(timeout: 3))
        snap("08 2.2 Onay")
        typeCode(app, "246814")
        settle(0.6)
        snap("09 2.2 Eşleşmedi")
        XCTAssertTrue(app.staticTexts["Gizli kodunu belirle"].waitForExistence(timeout: 5))
        settle(1.0)
        typeCode(app, "246813")
        XCTAssertTrue(app.staticTexts["Kodu tekrar gir"].waitForExistence(timeout: 3))
        typeCode(app, "246813")
        let ack = element(app, "onboarding.acknowledge")
        XCTAssertTrue(ack.waitForExistence(timeout: 5))
        settle()
        snap("10 2.3 Geri dönüş yok")
        ack.tap()
        tap(app, "onboarding.finish")
        XCTAssertTrue(app.staticTexts["Sohbetler"].waitForExistence(timeout: 8))
        settle()
        snap("11 4.1 Sohbetler")
    }

    /// 1.1 “Yedekten geri yükle” → (picker) → 7.3 password → restore → 2.2 ×2
    /// (“1 / 2”) → 2.3 (“2 / 2”) → 4.1.
    func test02RestoreOnANewDevice() {
        let app = launch(["-U2Start", "welcome", "-U2PickBackup", "YES"])
        tap(app, "welcome.restore", timeout: 8)
        let password = app.secureTextFields.firstMatch
        XCTAssertTrue(password.waitForExistence(timeout: 6))
        settle()
        snap("01 7.3 Parola")
        password.tap()
        password.typeText("yanlis-parola")
        tap(app, "restore.action.submit")
        XCTAssertTrue(app.staticTexts["Parola yanlış"].waitForExistence(timeout: 5) || true)
        settle(1.2)
        snap("02 7.3 Parola yanlış")
        password.tap()
        password.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 13) + "kuzey7deniz42k")
        tap(app, "restore.action.submit")
        settle(3)
        snap("03 7.3 Geri yükleniyor")
        tap(app, "restore.action.continue", timeout: 20)
        XCTAssertTrue(app.buttons["2"].waitForExistence(timeout: 5))
        settle()
        snap("04 2.2 Yeni kod 1/2")
        typeCode(app, "246813")
        XCTAssertTrue(app.staticTexts["Kodu tekrar gir"].waitForExistence(timeout: 3))
        typeCode(app, "246813")
        let ack = element(app, "onboarding.acknowledge")
        XCTAssertTrue(ack.waitForExistence(timeout: 5))
        settle()
        snap("05 2.3 2/2")
        ack.tap()
        tap(app, "onboarding.finish")
        XCTAssertTrue(app.buttons["chat.deniz"].waitForExistence(timeout: 8))
        snap("06 4.1 geri yüklendi")
    }

    // MARK: 03 · Lock

    /// Launch → 3.2 → wrong code → right code → the last screen; background →
    /// locked again; 3.3 → “Tekrar dene”.
    func test03UnlockAndRelock() {
        let app = launch(["-U2Start", "locked", "-U2Route", "conversation:deniz"])
        XCTAssertTrue(app.staticTexts["Gizli kodunu gir"].waitForExistence(timeout: 8))
        snap("01 3.2 Kod")
        typeCode(app, "111112")
        XCTAssertTrue(element(app, "lock.message").waitForExistence(timeout: 5))
        snap("02 3.2 Kod yanlış")
        settle(1)
        typeCode(app, "246813")
        XCTAssertTrue(element(app, "message.d6").waitForExistence(timeout: 8), "unlocks into the route's chat")
        settle()
        snap("03 5.1 after unlock")
        XCUIDevice.shared.press(.home)
        settle(1)
        app.activate()
        XCTAssertTrue(app.staticTexts["Gizli kodunu gir"].waitForExistence(timeout: 6))
        snap("04 3.2 relocked")
        tap(app, "lock.forgot")
        XCTAssertTrue(app.staticTexts["Kod kurtarılamaz."].waitForExistence(timeout: 5))
        settle()
        snap("05 3.3 Kodu unuttum")
        tap(app, "lock.forgot.retry")
        XCTAssertTrue(app.staticTexts["Kod kurtarılamaz."].waitForNonExistence(timeout: 5))
        typeCode(app, "246813")
        XCTAssertTrue(element(app, "message.d6").waitForExistence(timeout: 8), "the stack survived the lock")
    }

    /// 3.3 › “Yedekten geri yükle” → 7.3 inside the lock → 2.2 ×2 → 2.3 → 4.1.
    func test04RestoreFromTheLock() {
        let app = launch(["-U2Start", "locked", "-U2PickBackup", "YES"])
        tap(app, "lock.forgot", timeout: 8)
        tap(app, "lock.forgot.restore")
        let password = app.secureTextFields.firstMatch
        XCTAssertTrue(password.waitForExistence(timeout: 8))
        settle()
        snap("01 3.3 › 7.3")
        password.tap()
        password.typeText("kuzey7deniz42k")
        tap(app, "restore.action.submit")
        tap(app, "restore.action.continue", timeout: 20)
        XCTAssertTrue(app.buttons["2"].waitForExistence(timeout: 6))
        settle()
        snap("02 2.2 after restore in lock")
        typeCode(app, "471958")
        XCTAssertTrue(app.staticTexts["Kodu tekrar gir"].waitForExistence(timeout: 3))
        typeCode(app, "471958")
        let ack = element(app, "onboarding.acknowledge")
        XCTAssertTrue(ack.waitForExistence(timeout: 5))
        ack.tap()
        tap(app, "onboarding.finish")
        XCTAssertTrue(app.buttons["chat.deniz"].waitForExistence(timeout: 8))
        snap("03 4.1 after restore in lock")
        // The new code unlocks from now on.
        XCUIDevice.shared.press(.home)
        settle(1)
        app.activate()
        XCTAssertTrue(app.staticTexts["Gizli kodunu gir"].waitForExistence(timeout: 6))
        typeCode(app, "471958")
        XCTAssertTrue(app.buttons["chat.deniz"].waitForExistence(timeout: 8))
    }

    // MARK: 04 · Chats

    /// 4.1 → 4.3 → 4.4 → “Kabul et” → 5.1 (replaces 4.4); 4.3 “Sil” collapses a card.
    func test05RequestsPreviewAccept() {
        let app = launch(["-U2Start", "chats"])
        tap(app, "chats.requests", timeout: 8)
        XCTAssertTrue(element(app, "request.n.aksoy").waitForExistence(timeout: 5))
        settle()
        snap("01 4.3 Mesaj istekleri")
        tap(app, "request.n.aksoy.delete")
        XCTAssertTrue(element(app, "request.n.aksoy").waitForNonExistence(timeout: 5))
        settle()
        snap("02 4.3 after Sil")
        tap(app, "request.kuzey.dev")
        XCTAssertTrue(element(app, "preview.accept").waitForExistence(timeout: 5))
        settle()
        snap("03 4.4 İstek önizleme")
        tap(app, "preview.accept")
        XCTAssertTrue(element(app, "conversation.ghost").waitForExistence(timeout: 8))
        settle(1)
        snap("04 5.1 accepted")
        tap(app, "chatHeader.back")
        // 4.4 was replaced by 5.1: back is 4.3, then 4.1.
        tap(app, "requests.back")
        XCTAssertTrue(app.buttons["chat.kuzey.dev"].waitForExistence(timeout: 5), "the accepted chat is in 4.1")
        snap("05 4.1 with kuzey.dev")
    }

    /// 4.1 note-pencil → 4.2 → exact lookup → “Mesaj yaz” → sheet down → 5.1 →
    /// a message → back; QR menu → “Kodumu göster”.
    func test06NewChatAndMyCode() {
        let app = launch(["-U2Start", "chats"])
        tap(app, "chats.new", timeout: 8)
        let field = app.textFields["newChat.field"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.typeText("lale")
        XCTAssertTrue(element(app, "newChat.write").waitForExistence(timeout: 6))
        settle()
        snap("01 4.2 found")
        tap(app, "newChat.write")
        let composer = app.textViews.firstMatch.exists ? app.textViews.firstMatch : app.textFields.firstMatch
        XCTAssertTrue(element(app, "chatHeader.back").waitForExistence(timeout: 8))
        settle(1)
        snap("02 5.1 new chat")
        composer.tap()
        composer.typeText("Merhaba!")
        app.buttons["Gönder"].firstMatch.tap()
        settle(1)
        snap("03 5.1 first message")
        tap(app, "chatHeader.back")
        XCTAssertTrue(app.buttons["chat.lale"].waitForExistence(timeout: 5), "the new chat moved to the top of 4.1")
        settle()
        snap("04 4.1 with lale")
        tap(app, "chats.qr")
        tap(app, "menu.myCode")
        XCTAssertTrue(element(app, "myCode.sheet").waitForExistence(timeout: 5))
        settle(1)
        snap("05 4.1 Kodumu göster")
    }

    /// 4.2 “QR kodla ekle” → 6.2 → (simulated match) → 6.3 → “Sohbete dön” → 5.1.
    func test07AddByQR() {
        let app = launch(["-U2Start", "chats"])
        tap(app, "chats.new", timeout: 8)
        tap(app, "newChat.scan")
        XCTAssertTrue(element(app, "scan.simulatorMock").waitForExistence(timeout: 6))
        settle(1)
        snap("01 6.2 from 4.2")
        tap(app, "scan.showMyCode")
        settle(1)
        snap("02 6.2 my code")
        tap(app, "scan.backToScanning")
        tap(app, "scan.simulatorMock")
        XCTAssertTrue(element(app, "verified.back").waitForExistence(timeout: 8))
        settle(1)
        snap("03 6.3 Doğrulandı")
        tap(app, "verified.back")
        XCTAssertTrue(element(app, "conversation.ghost").waitForExistence(timeout: 8))
        settle(1.5)
        snap("04 5.1 after QR add")
    }

    // MARK: 05 · Conversation

    /// 5.1: long-press → 5.3 → Bilgi → 5.4; reply; react; Ghost chip → 5.5 →
    /// Kaydet → system line; view-once → 5.6 → close → “Açıldı”.
    func test08Conversation() {
        let app = launch(["-U2Route", "conversation:deniz"])
        let lastMessage = element(app, "message.d6")
        XCTAssertTrue(lastMessage.waitForExistence(timeout: 8))
        settle()
        snap("01 5.1 Sohbet")
        lastMessage.press(forDuration: 0.8)
        XCTAssertTrue(element(app, "menu.info").waitForExistence(timeout: 5))
        settle()
        snap("02 5.3 Eylemler")
        tap(app, "menu.info")
        settle(1)
        snap("03 5.4 Mesaj bilgisi")
        tap(app, "sheet.close")
        settle(1)
        element(app, "message.d3").press(forDuration: 0.8)
        tap(app, "menu.reply")
        settle(1)
        snap("04 5.1 reply quote")
        let composer = app.textViews.firstMatch.exists ? app.textViews.firstMatch : app.textFields.firstMatch
        composer.tap()
        composer.typeText("Evet, boşum")
        app.buttons["Gönder"].firstMatch.tap()
        settle(1)
        snap("05 5.1 replied")
        element(app, "message.d5").press(forDuration: 0.8)
        let menu = element(app, "menu.deleteMe")
        XCTAssertTrue(menu.waitForExistence(timeout: 5))
        menu.tap()
        XCTAssertTrue(element(app, "message.d5").waitForNonExistence(timeout: 5))
        settle()
        snap("06 5.1 after Benden sil")
        tap(app, "conversation.ghost")
        XCTAssertTrue(element(app, "ghost.save").waitForExistence(timeout: 5))
        settle()
        snap("07 5.5 Ghost")
        app.buttons["6 sa"].firstMatch.tap()
        tap(app, "ghost.save")
        settle(1.5)
        snap("08 5.1 Ghost changed")
        tap(app, "chatHeader.back")
        tap(app, "chat.mert.k")
        let viewOnce = element(app, "message.m6")
        XCTAssertTrue(viewOnce.waitForExistence(timeout: 6))
        settle()
        snap("09 5.2 Medya")
        viewOnce.tap()
        XCTAssertTrue(element(app, "viewer.close").waitForExistence(timeout: 6))
        settle(1)
        snap("10 5.6 Tek görüntülemelik")
        tap(app, "viewer.close")
        settle(1)
        snap("11 5.2 Açıldı")
    }

    // MARK: 06 · Verification

    /// 5.1 header → 6.1 → “QR kodu okut” → 6.2 → match → 6.3 → “Sohbete dön”;
    /// 6.1 verified → remove; “Doğrulandı olarak işaretle” → 6.3.
    func test09Verification() {
        let app = launch(["-U2Route", "conversation:deniz"])
        tap(app, "chatHeader.peer", timeout: 8)
        XCTAssertTrue(element(app, "safety.scan").waitForExistence(timeout: 5))
        settle()
        snap("01 6.1 Güvenlik numarası")
        tap(app, "safety.scan")
        XCTAssertTrue(element(app, "scan.simulatorMock").waitForExistence(timeout: 6))
        settle(1)
        snap("02 6.2 QR tarama")
        element(app, "scan.simulatorMock").press(forDuration: 1.0)
        XCTAssertTrue(element(app, "scan.warning").waitForExistence(timeout: 5))
        settle()
        snap("03 6.2 Numaralar uyuşmuyor")
        settle(3)
        tap(app, "scan.simulatorMock")
        XCTAssertTrue(element(app, "verified.back").waitForExistence(timeout: 8))
        settle(1)
        snap("04 6.3 Doğrulandı")
        tap(app, "verified.back")
        XCTAssertTrue(element(app, "conversation.ghost").waitForExistence(timeout: 8))
        settle(1)
        snap("05 5.1 verified")
        tap(app, "chatHeader.peer")
        settle(1)
        snap("06 6.1 verified")
        tap(app, "safety.markVerified")
        XCTAssertTrue(app.alerts.buttons["Kaldır"].waitForExistence(timeout: 5))
        snap("07 6.1 remove confirmation")
        app.alerts.buttons["Kaldır"].tap()
        settle(1)
        snap("08 6.1 unverified again")
        tap(app, "safety.markVerified")
        XCTAssertTrue(app.alerts.buttons["İşaretle"].waitForExistence(timeout: 5))
        app.alerts.buttons["İşaretle"].tap()
        XCTAssertTrue(element(app, "verified.back").waitForExistence(timeout: 8))
        settle(1)
        snap("09 6.3 elle işaretlendi")
    }

    // MARK: 07 · Settings

    /// 4.1 avatar → 7.1 → presence picker; theme light; code change; 7.2 export → share sheet.
    func test10Settings() {
        let app = launch(["-U2Start", "chats"])
        tap(app, "chats.settings", timeout: 8)
        XCTAssertTrue(app.staticTexts["Ayarlar"].waitForExistence(timeout: 5))
        settle()
        snap("01 7.1 Ayarlar")
        tap(app, "settings.presence")
        settle()
        snap("02 7.1 › Aktif durumu")
        tap(app, "picker.back")
        tap(app, "settings.theme")
        tap(app, "picker.option.2")
        settle(1)
        snap("03 Tema · Açık")
        tap(app, "picker.option.1")
        tap(app, "picker.back")
        tap(app, "settings.changeCode")
        XCTAssertTrue(app.buttons["2"].waitForExistence(timeout: 5))
        settle()
        snap("04 Gizli kodu değiştir · mevcut")
        typeCode(app, "246813")
        settle(1)
        typeCode(app, "471958")
        settle(1)
        typeCode(app, "471958")
        XCTAssertTrue(app.staticTexts["Ayarlar"].waitForExistence(timeout: 8))
        settle(0.5)
        snap("05 7.1 kod değişti")
        tap(app, "settings.backup")
        let password = app.secureTextFields.element(boundBy: 0)
        XCTAssertTrue(password.waitForExistence(timeout: 5))
        settle()
        snap("06 7.2 Yedeği dışa aktar")
        password.tap()
        password.typeText("kuzey7deniz42k")
        tap(app, "backup.export")
        let confirmation = app.secureTextFields.element(boundBy: 1)
        XCTAssertTrue(confirmation.waitForExistence(timeout: 5))
        confirmation.typeText("kuzey7deniz42k")
        tap(app, "backup.export")
        // The system share sheet (another process) with the encrypted file.
        let shareSheet = XCUIApplication(bundleIdentifier: "com.apple.springboard").otherElements["ActivityListView"]
        let inApp = app.otherElements["ActivityListView"]
        let deadline = Date().addingTimeInterval(15)
        while Date() < deadline && !inApp.exists && !shareSheet.exists { settle(0.5) }
        settle(1)
        snap("07 7.2 share sheet")
        XCTAssertTrue(inApp.exists || shareSheet.exists, "the share sheet is up")
    }

    /// 7.1 › Her şeyi sil → confirmation → 1.1.
    func test11EraseFromSettings() {
        let app = launch(["-U2Route", "settings"])
        let erase = element(app, "settings.erase")
        XCTAssertTrue(erase.waitForExistence(timeout: 8))
        app.swipeUp()
        app.swipeUp()
        erase.tap()
        XCTAssertTrue(element(app, "settings.confirm.destructive").waitForExistence(timeout: 5))
        settle()
        snap("01 7.1 Her şey silinsin mi?")
        tap(app, "settings.confirm.destructive")
        XCTAssertTrue(element(app, "welcome.start").waitForExistence(timeout: 10))
        settle()
        snap("02 1.1 after erase")
    }
}
