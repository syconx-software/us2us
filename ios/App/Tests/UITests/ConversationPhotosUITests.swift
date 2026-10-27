import XCTest

@MainActor
final class ConversationPhotosUITests: XCTestCase {
    override func tearDown() {
        XCUIApplication().terminate()
        super.tearDown()
    }

    func testAlbumPhotosOpenAtTheTappedPhotoAndNavigateBothWays() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-U2Fixtures", "YES", "-U2FrozenClock", "YES", "-U2Language", "tr", "-U2Theme", "dark",
            "-U2Route", "conversation:deniz/5.1-album"]
        app.launch()
        let album = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "photoAlbum.")).firstMatch
        XCTAssertTrue(album.waitForExistence(timeout: 15))
        let photo = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Fotoğraf 1 / 3")).firstMatch
        XCTAssertTrue(photo.waitForExistence(timeout: 5))
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "Photo album"
        attachment.lifetime = .keepAlways
        add(attachment)
        photo.tap()
        let position = app.staticTexts["mediaViewer.position"]
        XCTAssertTrue(position.waitForExistence(timeout: 5))
        XCTAssertEqual(position.label, "Fotoğraf 2 / 4")
        app.buttons["mediaViewer.next"].tap()
        XCTAssertEqual(position.label, "Fotoğraf 3 / 4")
        app.descendants(matching: .any)["mediaViewer.photo"].firstMatch.swipeLeft()
        XCTAssertEqual(position.label, "Fotoğraf 4 / 4")
        XCTAssertFalse(app.buttons["mediaViewer.next"].isEnabled)
        app.buttons["mediaViewer.previous"].tap()
        XCTAssertEqual(position.label, "Fotoğraf 3 / 4")
        app.descendants(matching: .any)["mediaViewer.photo"].firstMatch.swipeRight()
        XCTAssertEqual(position.label, "Fotoğraf 2 / 4")
        app.buttons["mediaViewer.close"].tap()
        XCTAssertTrue(album.waitForExistence(timeout: 5))
        photo.press(forDuration: 0.7)
        XCTAssertTrue(app.descendants(matching: .any)["menu.info"].firstMatch.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["mediaViewer.close"].exists, "a long press must not also open the viewer")
    }
}
