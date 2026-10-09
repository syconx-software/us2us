import XCTest

/// 4.1 · the chat list: scrolling a long list, and what the list costs while
/// nothing happens — on screen, covered by 5.1, and under the lock window.
@MainActor
final class ChatListPerformanceTests: U2PerformanceTestCase {
    private func launchList() -> (XCUIApplication, XCUIElement) {
        let app = launch(["-U2Route", "chats", "-U2PerfChats", "\(Self.stressChats)"])
        let list = waitFor(app, "chats.list")
        XCTAssertTrue(app.buttons["chat.deniz"].waitForExistence(timeout: 30))
        hold(2)
        return (app, list)
    }

    /// Fast flicks down and back up through 300+ rows: hitches, CPU, memory.
    func testScrollChatList() {
        let (app, list) = launchList()
        let metrics: [any XCTMetric] = [XCTOSSignpostMetric.scrollingAndDecelerationMetric] + appMetrics(app)
        measure(metrics: metrics, options: options()) {
            for _ in 0..<4 { list.swipeUp(velocity: .fast) }
            for _ in 0..<4 { list.swipeDown(velocity: .fast) }
        }
    }

    /// 4.1 at rest: the only motion is the connection line's packets.
    func testIdleChatList() {
        let (app, _) = launchList()
        measure(metrics: appMetrics(app), options: options(iterations: 3)) { hold(5) }
    }

    /// 4.1 stays alive under 5.1 (opacity 0); it should cost nothing there.
    func testIdleChatListCoveredByConversation() {
        let (app, _) = launchList()
        app.buttons["chat.mert.k"].tap()
        _ = waitFor(app, "conversation.ghost")
        hold(2)
        measure(metrics: appMetrics(app), options: options(iterations: 3)) { hold(5) }
    }

    /// After a trip to the background the lock window covers the live stack.
    func testIdleUnderLock() {
        let (app, _) = launchList()
        XCUIDevice.shared.press(.home)
        hold(2)
        app.activate()
        XCTAssertTrue(app.buttons["1"].waitForExistence(timeout: 20) || app.buttons["Kodla aç"].exists, "the lock is up")
        hold(2)
        measure(metrics: appMetrics(app), options: options(iterations: 3)) { hold(5) }
    }

    /// Instruments soak: flick up and down for `TEST_RUNNER_U2PERF_SOAK_SECONDS`.
    func testSoakScrollChatList() throws {
        try XCTSkipIf(soakSeconds == 0, "set TEST_RUNNER_U2PERF_SOAK_SECONDS")
        let (_, list) = launchList()
        soak("chatlist-scroll") {
            list.swipeUp(velocity: .fast)
            list.swipeUp(velocity: .fast)
            list.swipeDown(velocity: .fast)
            list.swipeDown(velocity: .fast)
        }
    }

    /// Instruments soak: 4.1 idle, then covered by 5.1, each for the soak time.
    func testSoakIdle() throws {
        try XCTSkipIf(soakSeconds == 0, "set TEST_RUNNER_U2PERF_SOAK_SECONDS")
        let (app, _) = launchList()
        soak("chatlist-idle") { hold(1) }
        app.buttons["chat.mert.k"].tap()
        _ = waitFor(app, "conversation.ghost")
        hold(2)
        soak("chatlist-covered") { hold(1) }
    }
}
