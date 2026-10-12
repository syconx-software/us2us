import XCTest

/// 5.1 / 5.2 media with real gestures, on the design fixtures: the photo
/// viewer (fitted, double-tap and pinch zoom, swipe away), the video player
/// (plays, pauses, scrubber, mute) and files (Quick Look, “Dosyalara
/// kaydet” in 5.3). Screenshots are kept with the results.
@MainActor
final class MediaUITests: XCTestCase {
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

    private func snap(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func settle(_ seconds: TimeInterval = 0.7) {
        Thread.sleep(forTimeInterval: seconds)
    }

    /// Tap the photo → the viewer (not 5.6: no “1 kez” badge); zoom in and
    /// out; swipe down closes it and the chat is back.
    func testPhotoViewerZoomsAndSwipesAway() {
        let app = launch(["-U2Route", "conversation:deniz"])
        let photo = element(app, "message.d2")
        XCTAssertTrue(photo.waitForExistence(timeout: 8))
        photo.tap()
        let close = element(app, "mediaViewer.close")
        XCTAssertTrue(close.waitForExistence(timeout: 6))
        XCTAssertFalse(app.staticTexts["1 kez"].exists, "an ordinary photo is not view-once")
        settle()
        snap("01 viewer fitted")
        let image = element(app, "mediaViewer.photo")
        XCTAssertTrue(image.exists)
        image.doubleTap()
        settle()
        snap("02 viewer zoomed (double-tap)")
        image.doubleTap()
        settle()
        image.pinch(withScale: 2.5, velocity: 2)
        settle()
        snap("03 viewer pinched")
        image.doubleTap()
        settle()
        image.swipeDown(velocity: .fast)
        XCTAssertTrue(close.waitForNonExistence(timeout: 4), "swipe down closes the viewer")
        XCTAssertTrue(photo.waitForExistence(timeout: 4))
        // The close button closes it too.
        photo.tap()
        XCTAssertTrue(close.waitForExistence(timeout: 6))
        close.tap()
        XCTAssertTrue(close.waitForNonExistence(timeout: 4))
    }

    /// Tap the video → the player loads and plays; pause, play, mute; the
    /// scrubber is there; close.
    func testVideoPlaysInTheConversation() {
        let app = launch(["-U2Route", "conversation:mert_k"])
        let video = element(app, "message.m2")
        XCTAssertTrue(video.waitForExistence(timeout: 8))
        video.tap()
        XCTAssertTrue(element(app, "player.close").waitForExistence(timeout: 6))
        snap("01 player opening")
        let play = element(app, "player.play")
        XCTAssertTrue(play.waitForExistence(timeout: 20), "the clip loads and plays")
        XCTAssertEqual(play.label, "Duraklat", "it starts playing by itself")
        play.tap()
        XCTAssertEqual(play.label, "Oynat")
        settle()
        snap("02 player paused")
        XCTAssertTrue(element(app, "player.scrubber").exists)
        let mute = element(app, "player.mute")
        XCTAssertTrue(mute.exists)
        mute.tap()
        XCTAssertEqual(mute.label, "Sesi aç")
        play.tap()
        settle(1)
        snap("03 player playing")
        let close = element(app, "player.close")
        XCTAssertTrue(close.waitForExistence(timeout: 4))
        close.tap()
        XCTAssertTrue(close.waitForNonExistence(timeout: 4))
    }

    /// Tap the PDF → Quick Look in the app's screen, shared only through its
    /// own button (the sender is told); close → back; 5.3 offers
    /// “Dosyalara kaydet”.
    func testFileOpensInQuickLookAndCanBeSaved() {
        let app = launch(["-U2Route", "conversation:mert_k"])
        let file = element(app, "message.m1")
        XCTAssertTrue(file.waitForExistence(timeout: 8))
        file.tap()
        let close = element(app, "file.protected.close")
        XCTAssertTrue(close.waitForExistence(timeout: 10), "Quick Look opens")
        XCTAssertTrue(element(app, "file.share").exists, "the app's share button")
        XCTAssertFalse(app.navigationBars.firstMatch.exists, "not Quick Look's own bar (its share would tell no one)")
        settle(1)
        snap("01 Quick Look")
        close.tap()
        XCTAssertTrue(file.waitForExistence(timeout: 6))
        file.press(forDuration: 0.8)
        let save = element(app, "menu.saveToFiles")
        XCTAssertTrue(save.waitForExistence(timeout: 5))
        settle()
        snap("02 5.3 Dosyalara kaydet")
    }

    /// A disappearing message's file: Quick Look inside the app's own screen —
    /// no Quick Look bar, so no share button; close brings the chat back.
    func testADisappearingFileOpensWithoutSharing() {
        let app = launch(["-U2Route", "conversation:mert_k/5.2-file-disappearing"])
        let close = element(app, "file.protected.close")
        XCTAssertTrue(close.waitForExistence(timeout: 10), "the protected preview opens")
        settle(1)
        snap("01 disappearing file, no sharing")
        XCTAssertFalse(app.navigationBars.firstMatch.exists, "not Quick Look's own bar")
        XCTAssertFalse(app.buttons["Paylaş"].exists)
        XCTAssertFalse(app.buttons["Share"].exists)
        close.tap()
        XCTAssertTrue(close.waitForNonExistence(timeout: 4))
        XCTAssertTrue(element(app, "message.m1").waitForExistence(timeout: 4))
    }

    /// The person in the header opens the contact screen: Doğrulama first,
    /// then the chat's photos (a tile opens the viewer with “Kaydet”, which
    /// swipes to the older ones) and its files.
    func testContactScreenShowsPhotosAndDocuments() {
        let app = launch(["-U2Route", "conversation:deniz"])
        let peer = element(app, "chatHeader.peer")
        XCTAssertTrue(peer.waitForExistence(timeout: 8))
        peer.tap()
        XCTAssertTrue(element(app, "safety.scan").waitForExistence(timeout: 6), "Doğrulama is the first tab")
        settle()
        snap("01 contact screen · Doğrulama")
        element(app, "contact.tab.photos").tap()
        let tile = element(app, "contact.tile.d2")
        XCTAssertTrue(tile.waitForExistence(timeout: 6), "the newest photo leads the grid")
        settle()
        snap("02 Fotoğraflar")
        tile.tap()
        let close = element(app, "mediaViewer.close")
        XCTAssertTrue(close.waitForExistence(timeout: 6))
        XCTAssertTrue(element(app, "viewer.save").exists, "Kaydet")
        settle()
        snap("03 viewer from the grid")
        app.swipeLeft()
        settle()
        snap("04 swiped to an older one")
        element(app, "mediaViewer.close").tap()
        XCTAssertTrue(tile.waitForExistence(timeout: 6))
        element(app, "contact.tab.documents").tap()
        XCTAssertTrue(element(app, "contact.documents").waitForExistence(timeout: 6))
        settle()
        snap("05 Belgeler")
    }

    /// The first “Kaydet” of something received explains, once, that the
    /// sender will see it; “Vazgeç” saves nothing.
    func testTheFirstSaveExplainsThatTheSenderSeesIt() {
        let app = launch(["-U2Route", "conversation:deniz", "-U2ResetPreferences", "YES"])
        let photo = element(app, "message.d2")
        XCTAssertTrue(photo.waitForExistence(timeout: 8))
        photo.tap()
        let save = element(app, "viewer.save")
        XCTAssertTrue(save.waitForExistence(timeout: 6))
        save.tap()
        let cancel = element(app, "save.notice.cancel")
        XCTAssertTrue(cancel.waitForExistence(timeout: 6), "the one-time explanation")
        XCTAssertTrue(element(app, "save.notice.confirm").exists, "Kaydet")
        settle()
        snap("01 deniz fotoğrafı kaydettiğini görecek")
        cancel.tap()
        XCTAssertTrue(cancel.waitForNonExistence(timeout: 4))
        XCTAssertTrue(element(app, "mediaViewer.close").exists, "back in the viewer, nothing saved")
    }

    /// The other side saved the user's PDF: “Kaydetti” under its bubble.
    func testASavedFileShowsTheChip() {
        let app = launch(["-U2Route", "conversation:mert_k/5.2-saved"])
        let chip = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier == %@ OR label == %@", "bubble.saved", "Karşı taraf kaydetti"))
            .firstMatch
        XCTAssertTrue(chip.waitForExistence(timeout: 8))
        settle()
        snap("01 Kaydetti")
    }

    /// A video sent from “+”: its bubble is there at once (“Hazırlanıyor”),
    /// then the same bubble is sent.
    func testASentVideoShowsAtOnceThenSends() {
        let app = launch([
            "-U2Route", "conversation:mert_k", "-U2ConversationDemo", "sendVideo", "-U2ConversationDemoDelay", "0.5",
        ])
        let bubble = element(app, "message.mert_k-video-out")
        XCTAssertTrue(bubble.waitForExistence(timeout: 8))
        settle(0.4)
        snap("01 video preparing")
        settle(3)
        snap("02 video sent")
        XCTAssertTrue(bubble.exists, "the same bubble")
    }

    /// Preparing failed: the warning and “Tekrar gönder” under the bubble.
    func testAFailedVideoOffersSendAgain() {
        let app = launch(["-U2Route", "conversation:mert_k/5.2-prepare-failed"])
        XCTAssertTrue(element(app, "message.mert_k-video-out").waitForExistence(timeout: 8))
        let sentence = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", "Video hazırlanamadı")).firstMatch
        XCTAssertTrue(sentence.waitForExistence(timeout: 4))
        settle()
        snap("01 video failed")
    }

    /// An incoming file still on the relay: the ring fills, then Quick Look opens.
    func testARemoteFileDownloadsThenOpens() {
        let app = launch(["-U2Route", "conversation:mert_k/5.2-remote"])
        let file = element(app, "message.mert_k-file-in")
        XCTAssertTrue(file.waitForExistence(timeout: 8))
        settle()
        snap("01 file on the relay")
        file.tap()
        settle(0.8)
        snap("02 downloading")
        XCTAssertTrue(element(app, "file.protected.close").waitForExistence(timeout: 10), "opens once it is here")
    }
}
