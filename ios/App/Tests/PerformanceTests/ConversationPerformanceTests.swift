import XCTest

/// 05 · a 5000-message conversation (deniz, `-U2PerfMessages 5000`):
/// opening it, scrolling through it, typing, and the sheets on top of it.
@MainActor
final class ConversationPerformanceTests: U2PerformanceTestCase {
    private var stressArguments: [String] { ["-U2PerfMessages", "\(Self.stressMessages)"] }

    private func launchConversation() -> XCUIApplication {
        let app = launch(["-U2Route", "conversation:deniz"] + stressArguments)
        _ = waitFor(app, "conversation.ghost", timeout: 120)
        hold(2)
        return app
    }

    /// 4.1 → 5.1 → back, repeatedly: time to the header, CPU, memory (a leak
    /// of the conversation's model or rows shows as growth over iterations).
    func testOpenAndCloseLargeConversation() {
        let app = launch(["-U2Route", "chats"] + stressArguments)
        XCTAssertTrue(app.buttons["chat.deniz"].waitForExistence(timeout: 60))
        hold(2)
        measure(metrics: [XCTClockMetric()] + appMetrics(app), options: options()) {
            app.buttons["chat.deniz"].tap()
            _ = waitFor(app, "conversation.ghost", timeout: 120)
            tap(app, "chatHeader.back")
            XCTAssertTrue(app.buttons["chat.deniz"].waitForExistence(timeout: 60))
        }
    }

    /// Fast flicks up into the history and back down: hitches, CPU, memory.
    func testScrollLargeConversation() {
        let app = launchConversation()
        let column = conversationScrollView(app)
        let metrics: [any XCTMetric] = [XCTOSSignpostMetric.scrollingAndDecelerationMetric] + appMetrics(app)
        measure(metrics: metrics, options: options()) {
            for _ in 0..<5 { column.swipeDown(velocity: .fast) }
            for _ in 0..<5 { column.swipeUp(velocity: .fast) }
        }
    }

    /// Typing a sentence into the composer of the big conversation (each
    /// keystroke should only re-render the composer).
    func testTypingInLargeConversation() {
        let app = launchConversation()
        let field = composerField(app)
        field.tap()
        hold(1.5)
        measure(metrics: [XCTClockMetric()] + appMetrics(app), options: options(iterations: 3)) {
            field.typeText("Yarın akşam Moda'da buluşalım mı, saat sekiz gibi? ")
        }
    }

    /// 5.5 Ghost sheet up and down, then 5.4 info up and down (long-press → Bilgi).
    func testSheetsOverLargeConversation() {
        let app = launchConversation()
        measure(metrics: [XCTClockMetric()] + appMetrics(app), options: options()) {
            tap(app, "conversation.ghost")
            tap(app, "ghost.save")
            XCTAssertTrue(element(app, "ghost.save").waitForNonExistence(timeout: 10))
            waitFor(app, "message.d6").press(forDuration: 0.8)
            tap(app, "menu.info")
            tap(app, "sheet.close")
            XCTAssertTrue(element(app, "sheet.close").waitForNonExistence(timeout: 10))
        }
    }

    /// Instruments soak: scroll the big conversation up and down.
    func testSoakScrollLargeConversation() throws {
        try XCTSkipIf(soakSeconds == 0, "set TEST_RUNNER_U2PERF_SOAK_SECONDS")
        let app = launchConversation()
        let column = conversationScrollView(app)
        soak("conversation-scroll") {
            column.swipeDown(velocity: .fast)
            column.swipeDown(velocity: .fast)
            column.swipeUp(velocity: .fast)
            column.swipeUp(velocity: .fast)
        }
    }

    /// Instruments soak: open and close the big conversation from 4.1.
    func testSoakOpenClose() throws {
        try XCTSkipIf(soakSeconds == 0, "set TEST_RUNNER_U2PERF_SOAK_SECONDS")
        let app = launch(["-U2Route", "chats"] + stressArguments)
        XCTAssertTrue(app.buttons["chat.deniz"].waitForExistence(timeout: 60))
        hold(2)
        soak("conversation-open-close") {
            app.buttons["chat.deniz"].tap()
            _ = waitFor(app, "conversation.ghost", timeout: 120)
            tap(app, "chatHeader.back")
            XCTAssertTrue(app.buttons["chat.deniz"].waitForExistence(timeout: 60))
        }
    }

    /// Instruments soak: type, send, open/close the Ghost sheet.
    func testSoakTypingAndSheets() throws {
        try XCTSkipIf(soakSeconds == 0, "set TEST_RUNNER_U2PERF_SOAK_SECONDS")
        let app = launchConversation()
        let field = composerField(app)
        soak("conversation-typing-sheets") {
            field.tap()
            field.typeText("Yarın akşam buluşalım mı?")
            app.buttons["Gönder"].firstMatch.tap()
            tap(app, "conversation.ghost")
            tap(app, "ghost.save")
            XCTAssertTrue(element(app, "ghost.save").waitForNonExistence(timeout: 10))
        }
    }
}
