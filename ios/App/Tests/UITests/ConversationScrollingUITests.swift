import XCTest

@MainActor
final class ConversationScrollingUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }
    override func tearDown() { XCUIApplication().terminate(); super.tearDown() }

    private func launch(album: Bool = false, voice: Bool = false, older: Int = 120, theme: String = "dark", scrollTo: String? = nil, paged: Int = 0) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-U2Fixtures", "YES", "-U2FrozenClock", "YES", "-U2Language", "tr", "-U2Theme", theme,
            "-U2StressMessages", String(older), "-U2Route", album ? "conversation:deniz/5.1-album" : "conversation:deniz"]
        if voice { app.launchArguments += ["-U2ConversationDemo", "playVoice", "-U2ConversationDemoDelay", "0.1"] }
        if paged > 0 { app.launchArguments += ["-U2PagedHistory", String(paged)] }
        if let scrollTo { app.launchArguments += ["-U2ConversationScrollTo", scrollTo] }
        app.launch()
        return app
    }

    func testScrollingUpLoadsOlderPagesAndCanReturnToLatest() {
        let app = launch(older: 0, scrollTo: "stress-205", paged: 300)
        let list = app.scrollViews["conversation.list"].firstMatch
        let oldest = app.descendants(matching: .any)["message.stress-0"].firstMatch
        XCTAssertTrue(list.waitForExistence(timeout: 15))
        // A fast flick may pass an intermediate lazy row without making
        // it accessible to XCTest; the oldest row cannot be overshot.
        for _ in 0..<20 {
            if oldest.exists && oldest.isHittable { break }
            list.swipeDown(velocity: 3500)
        }
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "oldest retained history"
        attachment.lifetime = .keepAlways
        add(attachment)
        if !oldest.isHittable { print(app.debugDescription) }
        XCTAssertTrue(oldest.isHittable, "the oldest retained message must be reachable")
        let button = app.buttons["conversation.latest"]
        XCTAssertTrue(button.exists)
        button.tap()
        XCTAssertTrue(app.descendants(matching: .any)["message.d6"].firstMatch.waitForExistence(timeout: 5))
        XCTAssertFalse(button.waitForExistence(timeout: 1))
    }

    private func checkBothDirections(_ app: XCUIApplication, target: XCUIElement, velocity: Double = 200) {
        XCTAssertTrue(target.waitForExistence(timeout: 15))
        let before = target.frame
        let snapshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        snapshot.name = "scroll starts on \(target.label)"
        snapshot.lifetime = .keepAlways
        add(snapshot)
        let origin = app.coordinate(withNormalizedOffset: .zero)
        func drag(_ frame: CGRect, delta: CGFloat) {
            let start = CGPoint(x: frame.midX, y: frame.midY)
            origin.withOffset(CGVector(dx: start.x, dy: start.y)).press(forDuration: 0.02,
                thenDragTo: origin.withOffset(CGVector(dx: start.x, dy: start.y + delta)),
                withVelocity: XCUIGestureVelocity(velocity), thenHoldForDuration: 0.3)
        }
        drag(before, delta: 60)
        let afterDown = target.frame
        XCTAssertGreaterThan(afterDown.midY, before.midY + 20, "dragging on the content must move the conversation toward older messages")
        XCTAssertTrue(target.isHittable, "the next drag must start on visible content")
        XCTAssertFalse(app.descendants(matching: .any)["menu.reply"].firstMatch.exists)
        XCTAssertFalse(app.buttons["mediaViewer.close"].exists)
        drag(afterDown, delta: -60)
        XCTAssertLessThan(target.frame.midY, afterDown.midY - 20, "dragging on the content must move the conversation back")
    }

    func testScrollingStartsOnATextBubble() {
        let app = launch()
        checkBothDirections(app, target: app.descendants(matching: .any)["message.d3"].firstMatch)
    }

    func testScrollingStartsOnAPhoto() {
        let app = launch()
        checkBothDirections(app, target: app.descendants(matching: .any)["message.d2"].firstMatch)
    }

    func testScrollingStartsOnAnAlbumPhoto() {
        let app = launch(album: true)
        let photo = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Fotoğraf 1 / 3")).firstMatch
        checkBothDirections(app, target: photo)
    }

    func testSlowScrollingDoesNotLongPressText() {
        let app = launch()
        checkBothDirections(app, target: app.descendants(matching: .any)["message.d3"].firstMatch, velocity: 80)
    }

    func testSlowScrollingDoesNotLongPressPhoto() {
        let app = launch()
        checkBothDirections(app, target: app.descendants(matching: .any)["message.d2"].firstMatch, velocity: 80)
    }

    func testScrollingStartsOnAnActiveVoiceWaveform() {
        let app = launch(voice: true)
        let waveform = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Oynatma konumu")).firstMatch
        XCTAssertTrue(waveform.waitForExistence(timeout: 15))
        checkBothDirections(app, target: waveform)
    }

    func testActiveWaveformStillSeeksSidewaysAndByTap() {
        let app = launch(voice: true)
        let waveform = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Oynatma konumu")).firstMatch
        XCTAssertTrue(waveform.waitForExistence(timeout: 15))
        let pause = app.buttons["Sesli mesajı duraklat"].firstMatch
        XCTAssertTrue(pause.waitForExistence(timeout: 5))
        pause.tap()
        let position = waveform.frame.midY
        let before = waveform.value as? String
        waveform.coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: 0.5)).press(forDuration: 0.02,
            thenDragTo: waveform.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.5)),
            withVelocity: XCUIGestureVelocity(100), thenHoldForDuration: 0.2)
        let sought = waveform.value as? String
        XCTAssertNotNil(sought)
        XCTAssertNotEqual(sought, before)
        XCTAssertEqual(waveform.frame.midY, position, accuracy: 2)
        XCTAssertFalse(app.descendants(matching: .any)["menu.info"].firstMatch.exists)
        waveform.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.5)).tap()
        XCTAssertNotEqual(waveform.value as? String, sought)
    }

    func testLongPressOnActiveWaveformStillOpensMessageActions() {
        let app = launch(voice: true)
        let waveform = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Oynatma konumu")).firstMatch
        XCTAssertTrue(waveform.waitForExistence(timeout: 15))
        waveform.press(forDuration: 0.8)
        XCTAssertTrue(app.descendants(matching: .any)["menu.reply"].firstMatch.waitForExistence(timeout: 5))
    }

    private func readHistory(_ app: XCUIApplication) {
        let origin = app.coordinate(withNormalizedOffset: .zero)
        origin.withOffset(CGVector(dx: 160, dy: 330)).press(forDuration: 0.02,
            thenDragTo: origin.withOffset(CGVector(dx: 160, dy: 630)),
            withVelocity: XCUIGestureVelocity(500), thenHoldForDuration: 0.3)
    }

    private func returnsToLatest(_ app: XCUIApplication) {
        let latest = app.descendants(matching: .any)["message.d6"].firstMatch
        XCTAssertTrue(latest.waitForExistence(timeout: 15))
        let rest = latest.frame
        let button = app.buttons["conversation.latest"]
        XCTAssertFalse(button.exists)
        readHistory(app)
        XCTAssertTrue(button.waitForExistence(timeout: 5))
        XCTAssertTrue(button.isHittable)
        let field = app.textFields.firstMatch
        XCTAssertLessThan(button.frame.maxY, field.frame.minY)
        let snapshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        snapshot.name = "return to latest button"
        snapshot.lifetime = .keepAlways
        add(snapshot)
        button.tap()
        XCTAssertTrue(button.waitForNonExistence(timeout: 5))
        XCTAssertTrue(latest.isHittable)
        XCTAssertEqual(latest.frame.maxY, rest.maxY, accuracy: 3)
    }

    func testLatestButtonReturnsToNewestAndStaysAboveKeyboard() {
        let app = launch()
        returnsToLatest(app)
        readHistory(app)
        let field = app.textFields.firstMatch
        field.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        let button = app.buttons["conversation.latest"]
        XCTAssertTrue(button.waitForExistence(timeout: 5))
        XCTAssertLessThan(button.frame.maxY, field.frame.minY)
        button.tap()
        XCTAssertTrue(button.waitForNonExistence(timeout: 5))
        let latest = app.descendants(matching: .any)["message.d6"].firstMatch
        XCTAssertTrue(latest.isHittable)
        XCTAssertLessThan(latest.frame.maxY, field.frame.minY)
    }

    func testLatestButtonWorksInALongLightConversation() {
        let app = launch(older: 5000, theme: "light", scrollTo: "stress-50")
        let button = app.buttons["conversation.latest"]
        XCTAssertTrue(button.waitForExistence(timeout: 15))
        button.tap()
        let hidden = button.waitForNonExistence(timeout: 5)
        if !hidden {
            let snapshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
            snapshot.name = "far history return failed"
            snapshot.lifetime = .keepAlways
            add(snapshot)
        }
        XCTAssertTrue(hidden)
        let latest = app.descendants(matching: .any)["message.d6"].firstMatch
        XCTAssertTrue(latest.waitForExistence(timeout: 5))
        XCTAssertTrue(latest.isHittable)
        XCTAssertLessThan(latest.frame.maxY, app.textFields.firstMatch.frame.minY)
    }
}
