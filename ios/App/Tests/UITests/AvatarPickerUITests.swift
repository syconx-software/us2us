import XCTest

/// 7.1 › Profil görseli with the design fixtures: one tap on the profile
/// card's avatar opens the picker full screen at once (1.0.7 opened an
/// empty half-height sheet the first time), a preset is saved and 7.1 shows
/// it; “Fotoğraf yükle” raises the system photo picker on the first tap, a
/// picked photo is framed full screen and becomes the picture.
@MainActor
final class AvatarPickerUITests: XCTestCase {
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

    private func avatar(_ app: XCUIApplication) -> XCUIElement {
        app.buttons["settings.avatar"]
    }

    /// The picker after one tap: there within the fade, covering the whole
    /// window, its header at the top and the first preset in reach.
    private func assertPickerFullScreen(_ app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        let picker = app.otherElements["avatarPicker"]
        XCTAssertTrue(picker.waitForExistence(timeout: 3), "the picker did not open on the first tap", file: file, line: line)
        let window = app.windows.firstMatch.frame
        XCTAssertEqual(picker.frame, window, "the picker does not cover the screen", file: file, line: line)
        let title = app.staticTexts["Profil görseli"]
        XCTAssertTrue(title.waitForExistence(timeout: 2), file: file, line: line)
        XCTAssertTrue(title.isHittable, file: file, line: line)
        XCTAssertLessThan(title.frame.minY, window.height * 0.2, "the title is not at the top", file: file, line: line)
        XCTAssertTrue(app.buttons["avatarPicker.close"].isHittable, file: file, line: line)
        XCTAssertTrue(app.buttons["avatarPicker.initials"].isHittable, file: file, line: line)
        XCTAssertTrue(app.buttons["avatarPicker.preset.1"].isHittable, file: file, line: line)
    }

    private func waitForValue(_ element: XCUIElement, _ value: String, timeout: TimeInterval = 5) -> Bool {
        let predicate = NSPredicate(format: "value == %@", value)
        return XCTWaiter().wait(for: [expectation(for: predicate, evaluatedWith: element)], timeout: timeout) == .completed
    }

    /// Cold launch into 7.1, one tap: the picker is full screen; a preset is
    /// saved, the picker fades out and 7.1's avatar is the preset.
    func testOneTapOpensThePickerFullScreenAndAPresetIsSaved() {
        let app = launch(["-U2Route", "settings"])
        XCTAssertTrue(avatar(app).waitForExistence(timeout: 8))
        XCTAssertEqual(avatar(app).value as? String, "Baş harfin")

        avatar(app).tap()
        assertPickerFullScreen(app)

        let preset = app.buttons["avatarPicker.preset.2"]
        preset.tap()
        XCTAssertTrue(app.otherElements["avatarPicker"].waitForNonExistence(timeout: 5), "the picker did not close")
        XCTAssertTrue(waitForValue(avatar(app), "Kulaklıklı bardak"), "7.1 does not show the chosen preset")
        XCTAssertTrue(avatar(app).isHittable)

        // Again: it opens just the same, the preset marked.
        avatar(app).tap()
        assertPickerFullScreen(app)
        XCTAssertTrue(app.buttons["avatarPicker.preset.2"].isSelected)
        app.buttons["avatarPicker.close"].tap()
        XCTAssertTrue(app.otherElements["avatarPicker"].waitForNonExistence(timeout: 5))
    }

    /// As on the phone: launched locked, unlocked with the code, 4.1 → 7.1,
    /// one tap on the avatar.
    func testOneTapAfterUnlockingOpensThePickerFullScreen() {
        let app = launch(["-U2Start", "locked"])
        XCTAssertTrue(app.staticTexts["Gizli kodunu gir"].waitForExistence(timeout: 8))
        for digit in "246813" { app.buttons[String(digit)].tap() }
        XCTAssertTrue(app.staticTexts["Gizli kodunu gir"].waitForNonExistence(timeout: 5))
        let settings = app.buttons["chats.settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 5))
        // The lock dissolves over 0.36 s; 4.1 takes taps once it has gone.
        for _ in 0..<3 where !avatar(app).exists {
            settings.tap()
            _ = avatar(app).waitForExistence(timeout: 2)
        }
        XCTAssertTrue(avatar(app).waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["Ayarlar"].waitForExistence(timeout: 3))

        avatar(app).tap()
        assertPickerFullScreen(app)
    }

    /// “Kamera” answers on the first tap (the simulator has none: the note);
    /// “Fotoğraf yükle” raises the system photo picker on the first tap, and
    /// again after a cancel; a picked photo is framed on the full-screen
    /// framing screen and, used, becomes 7.1's picture.
    func testPhotoLibraryOpensOnTheFirstTapAndAPickedPhotoIsFramedAndSaved() {
        let app = launch(["-U2Route", "settings"])
        XCTAssertTrue(avatar(app).waitForExistence(timeout: 8))
        avatar(app).tap()
        assertPickerFullScreen(app)

        app.buttons["avatarPicker.camera"].tap()
        let note = app.staticTexts["avatarPicker.alert"]
        XCTAssertTrue(note.waitForExistence(timeout: 3))
        XCTAssertEqual(note.label, "Bu cihazda kamera kullanılamıyor.")

        let upload = app.buttons["avatarPicker.upload"]
        upload.tap()
        let cancel = app.navigationBars.buttons["Cancel"]
        XCTAssertTrue(cancel.waitForExistence(timeout: 10), "the system photo picker did not open on the first tap")
        cancel.tap()
        XCTAssertTrue(cancel.waitForNonExistence(timeout: 5))
        XCTAssertTrue(app.otherElements["avatarPicker"].exists)
        let enabled = NSPredicate(format: "isEnabled == true")
        XCTAssertEqual(
            XCTWaiter().wait(for: [expectation(for: enabled, evaluatedWith: upload)], timeout: 5), .completed)

        upload.tap()
        let photo = app.images.matching(identifier: "PXGGridLayout-Info").firstMatch
        XCTAssertTrue(photo.waitForExistence(timeout: 10), "no photo in the simulator's library to pick")
        photo.tap()

        let use = app.buttons["avatarCrop.use"]
        XCTAssertTrue(use.waitForExistence(timeout: 10), "the framing screen did not open")
        XCTAssertTrue(app.staticTexts["Fotoğrafı yerleştir"].isHittable)
        let viewport = app.descendants(matching: .any)["avatarCrop.viewport"]
        XCTAssertTrue(viewport.exists)
        XCTAssertGreaterThan(viewport.frame.width, app.windows.firstMatch.frame.width * 0.8)
        XCTAssertEqual(
            XCTWaiter().wait(for: [expectation(for: enabled, evaluatedWith: use)], timeout: 5), .completed)
        use.tap()

        XCTAssertTrue(use.waitForNonExistence(timeout: 8))
        XCTAssertTrue(app.otherElements["avatarPicker"].waitForNonExistence(timeout: 5), "the picker did not close")
        XCTAssertTrue(waitForValue(avatar(app), "Fotoğraf"), "7.1 does not show the photo")
    }
}
