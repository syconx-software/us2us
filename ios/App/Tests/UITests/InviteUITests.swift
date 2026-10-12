import XCTest

/// Features/Invite: the invite sheet and its ways in (docs/INVITES.md).
/// Screenshots of each step are attached to the result.
@MainActor
final class InviteUITests: XCTestCase {
    override func setUp() async throws {
        continueAfterFailure = false
    }

    private func launch(_ arguments: [String], language: String = "tr", theme: String = "dark") -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-U2Fixtures", "YES", "-U2FrozenClock", "YES", "-U2Language", language, "-U2Theme", theme, "-U2Start", "chats"] + arguments
        app.launch()
        return app
    }

    private func attachScreenshot(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private static let channels = ["sms", "whatsapp", "email", "more", "copy"]

    private func assertChannels(_ app: XCUIApplication, titles: [String], file: StaticString = #filePath, line: UInt = #line) {
        for (id, title) in zip(Self.channels, titles) {
            let row = app.buttons["invite.channel.\(id)"]
            XCTAssertTrue(row.waitForExistence(timeout: 5), "channel \(id)", file: file, line: line)
            XCTAssertTrue(row.isHittable, "channel \(id) hittable", file: file, line: line)
            XCTAssertTrue(row.label.hasPrefix(title), "channel \(id): “\(row.label)”", file: file, line: line)
        }
    }

    /// `-U2Route invite`: the sheet with the message and the five channels;
    /// copy shows its status, editing works, “Kapat” closes.
    func testInviteSheetShowsTheMessageAndChannels() {
        let app = launch(["-U2Route", "invite"])
        XCTAssertTrue(app.buttons["invite.channel.sms"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["Davet et"].exists)
        let preview = app.descendants(matching: .any)["invite.preview"]
        XCTAssertTrue(preview.waitForExistence(timeout: 5))
        let text = preview.value as? String ?? ""
        XCTAssertTrue(text.contains("Kullanıcı adım: @selin_n"), text)
        XCTAssertTrue(text.contains("https://us2us.app/u/selin_n"), text)
        assertChannels(app, titles: ["Mesaj", "WhatsApp", "E-posta", "Diğer", "Bağlantıyı kopyala"])
        XCTAssertTrue(app.buttons["invite.channel.copy"].label.contains("us2us.app/u/selin_n"))
        attachScreenshot(app, "invite-tr-dark")

        // Copy: the row says so (and that the pasteboard forgets it).
        app.buttons["invite.channel.copy"].tap()
        XCTAssertTrue(
            app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Kopyalandı")).firstMatch.waitForExistence(timeout: 3))
        attachScreenshot(app, "invite-copied")

        // Edit, then done: the bubble shows the edited text.
        app.buttons["invite.edit"].tap()
        let editor = app.textViews["invite.editor"]
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        editor.tap()
        // A fresh simulator explains swipe typing on its first keyboard.
        let keyboardTip = app.buttons["Continue"]
        if keyboardTip.waitForExistence(timeout: 1.5) { keyboardTip.tap() }
        editor.typeText(" Görüşürüz!")
        attachScreenshot(app, "invite-editing")
        app.buttons["invite.done"].tap()
        let edited = app.descendants(matching: .any)["invite.preview"]
        XCTAssertTrue(edited.waitForExistence(timeout: 5))
        XCTAssertTrue((edited.value as? String ?? "").contains("Görüşürüz!"))

        app.buttons["Kapat"].tap()
        XCTAssertTrue(app.buttons["invite.channel.sms"].waitForNonExistence(timeout: 5))
    }

    /// The share sheet, out of process or in it.
    private func waitForShareSheet(_ app: XCUIApplication, timeout: TimeInterval = 10) -> Bool {
        let outside = XCUIApplication(bundleIdentifier: "com.apple.springboard").otherElements["ActivityListView"]
        let inside = app.otherElements["ActivityListView"]
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if inside.exists || outside.exists { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        return false
    }

    /// “Diğer” opens the system share sheet over the invite sheet; “Mesaj”
    /// falls back to it where Messages cannot send (the simulator).
    func testMoreAndMessagesReachTheShareSheet() {
        let app = launch(["-U2Route", "invite"])
        XCTAssertTrue(app.buttons["invite.channel.more"].waitForExistence(timeout: 8))
        app.buttons["invite.channel.more"].tap()
        XCTAssertTrue(waitForShareSheet(app), "share sheet for “Diğer”")
        attachScreenshot(app, "invite-more-share-sheet")
        // Close it (the sheet's own close button, else a swipe down).
        let close = app.buttons["header.closeButton"].exists ? app.buttons["header.closeButton"] : app.buttons["Close"]
        if close.exists { close.tap() } else { app.swipeDown(velocity: .fast) }
        XCTAssertTrue(app.buttons["invite.channel.sms"].waitForExistence(timeout: 5))
        RunLoop.current.run(until: Date().addingTimeInterval(1))
        app.buttons["invite.channel.sms"].tap()
        XCTAssertTrue(waitForShareSheet(app), "Messages, or the share sheet where it cannot send")
        attachScreenshot(app, "invite-sms")
    }

    /// English UI: the English template and channel names.
    func testInviteSheetInEnglish() {
        let app = launch(["-U2Route", "invite"], language: "en", theme: "light")
        XCTAssertTrue(app.buttons["invite.channel.sms"].waitForExistence(timeout: 8))
        let preview = app.descendants(matching: .any)["invite.preview"]
        XCTAssertTrue(preview.waitForExistence(timeout: 5))
        XCTAssertTrue((preview.value as? String ?? "").contains("My username: @selin_n"))
        assertChannels(app, titles: ["Messages", "WhatsApp", "Email", "More", "Copy link"])
        attachScreenshot(app, "invite-en-light")
    }

    /// 4.2: no exact match → “Davet et” opens the invitation inside the sheet; “Geri” returns.
    func testNewChatNotFoundOffersAnInvite() {
        let app = launch(["-U2Route", "chats:4.2-notfound"])
        let invite = app.buttons["newChat.invite"]
        XCTAssertTrue(invite.waitForExistence(timeout: 8))
        attachScreenshot(app, "newchat-notfound-invite")
        invite.tap()
        XCTAssertTrue(app.buttons["invite.channel.sms"].waitForExistence(timeout: 5))
        assertChannels(app, titles: ["Mesaj", "WhatsApp", "E-posta", "Diğer", "Bağlantıyı kopyala"])
        attachScreenshot(app, "newchat-invite-page")
        app.buttons["Geri"].tap()
        XCTAssertTrue(app.buttons["newChat.invite"].waitForExistence(timeout: 5))
    }

    /// 7.1 › Uygulama › “Arkadaşını davet et”.
    func testSettingsRowOpensTheInvitation() {
        let app = launch(["-U2Route", "settings"])
        let row = app.buttons["settings.invite"]
        XCTAssertTrue(app.buttons["settings.back"].waitForExistence(timeout: 8))
        for _ in 0..<6 where !row.isHittable { app.swipeUp() }
        XCTAssertTrue(row.isHittable)
        attachScreenshot(app, "settings-invite-row")
        row.tap()
        XCTAssertTrue(app.buttons["invite.channel.sms"].waitForExistence(timeout: 5))
        assertChannels(app, titles: ["Mesaj", "WhatsApp", "E-posta", "Diğer", "Bağlantıyı kopyala"])
    }

    /// “Kodum” sheet: “Davet bağlantını paylaş” is there.
    func testMyCodeOffersToShareTheLink() {
        let app = launch(["-U2Route", "chats:4.1-mycode"])
        // The sheet's own identifier (“myCode.sheet”) covers its children, so by label.
        let share = app.buttons["Davet bağlantını paylaş"]
        XCTAssertTrue(share.waitForExistence(timeout: 8))
        XCTAssertTrue(share.isHittable)
        attachScreenshot(app, "mycode-share")
    }

    /// A universal link for deniz (as iOS would deliver it): 4.2 rises with
    /// the name searched and found; nothing is sent until “Mesaj yaz”.
    func testInviteLinkOpensNewChatSearched() {
        let app = launch(["-U2Route", "invite:link"])
        XCTAssertTrue(app.buttons["newChat.write"].waitForExistence(timeout: 8))
        attachScreenshot(app, "link-deniz")
    }

    /// Links that are not invite links are ignored.
    func testForeignLinkIsIgnored() {
        let app = launch(["-U2Route", "chats", "-U2OpenURL", "https://evil.example/u/deniz"])
        XCTAssertTrue(app.buttons["chats.new"].waitForExistence(timeout: 8))
        XCTAssertFalse(app.staticTexts["Yeni sohbet"].waitForExistence(timeout: 2))
    }
}
