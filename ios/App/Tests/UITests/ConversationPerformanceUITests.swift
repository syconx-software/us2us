import XCTest

/// Scrolling and opening a long conversation: 5.1 with deniz plus 5000 older
/// messages (`-U2StressMessages 5000`, DEBUG fixture).
///
/// * `testScrollDeceleration` / `testScrollingAndDeceleration`: hitch time
///   ratio, frame rate and frame count while flinging through the history
///   (`XCTOSSignpostMetric.scrollDecelerationMetric`,
///   `.scrollingAndDecelerationMetric`).
/// * `testOpenConversation`: 4.1 → 5.1 until the newest message is on screen
///   (`XCTClockMetric`, includes the UI-test round trips), plus the app's own
///   `Conversation › open` signpost interval — creation of the screen to its
///   first committed frame.
///
/// Simulator numbers are indicative only; run on a 120 Hz device (Profile
/// configuration with the stress fixture enabled) for real hitch ratios.
/// Run alone: `-only-testing:us2usUITests/ConversationPerformanceUITests`.
@MainActor
final class ConversationPerformanceUITests: XCTestCase {
    /// 5000 by default; `TEST_RUNNER_U2_STRESS=<n> xcodebuild test …` changes it.
    static var stressCount: String { ProcessInfo.processInfo.environment["U2_STRESS"] ?? "5000" }

    override func setUp() async throws {
        continueAfterFailure = false
    }

    private func launch(route: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-U2Fixtures", "YES", 
            "-U2FrozenClock", "YES", "-U2Language", "tr", "-U2Theme", "dark",
            "-U2StressMessages", Self.stressCount, "-U2FrameProbe", "YES", "-U2Route", route,
        ]
        app.launch()
        return app
    }

    private func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier].firstMatch
    }

    /// The message list. Only the list itself is queried: a query over every
    /// descendant has to snapshot the whole accessibility tree, which for a
    /// non-lazy column of 5000 rows does not finish (the baseline).
    private func conversationList(_ app: XCUIApplication) -> XCUIElement {
        let list = app.scrollViews["conversation.list"].firstMatch
        XCTAssertTrue(list.waitForExistence(timeout: 60), "conversation list not found")
        return list
    }

    /// A drag on the list by screen coordinates (402 × 874 pt), released at
    /// `velocity` (points per second). Coordinate gestures need no query of
    /// the conversation's accessibility tree (see `conversationList`).
    private func drag(_ app: XCUIApplication, fromY: CGFloat, toY: CGFloat, velocity: XCUIGestureVelocity) {
        let origin = app.coordinate(withNormalizedOffset: .zero)
        origin.withOffset(CGVector(dx: 201, dy: fromY))
            .press(forDuration: 0.01, thenDragTo: origin.withOffset(CGVector(dx: 201, dy: toY)), withVelocity: velocity, thenHoldForDuration: 0)
    }

    /// Fling towards older messages, measure the deceleration, fling back.
    func testScrollDeceleration() {
        let app = launch(route: "conversation:deniz")
        _ = conversationList(app)
        Thread.sleep(forTimeInterval: 1)
        let options = XCTMeasureOptions()
        options.invocationOptions = [.manuallyStop]
        options.iterationCount = 5
        measure(metrics: [XCTOSSignpostMetric.scrollDecelerationMetric], options: options) {
            drag(app, fromY: 250, toY: 700, velocity: .fast)
            stopMeasuring()
            drag(app, fromY: 700, toY: 250, velocity: .fast)
        }
    }

    /// Drag + deceleration through the history at a moderate speed.
    func testScrollingAndDeceleration() {
        let app = launch(route: "conversation:deniz")
        _ = conversationList(app)
        Thread.sleep(forTimeInterval: 1)
        let options = XCTMeasureOptions()
        options.invocationOptions = [.manuallyStop]
        options.iterationCount = 5
        measure(metrics: [XCTOSSignpostMetric.scrollingAndDecelerationMetric], options: options) {
            drag(app, fromY: 250, toY: 700, velocity: XCUIGestureVelocity(1200))
            drag(app, fromY: 250, toY: 700, velocity: XCUIGestureVelocity(1200))
            stopMeasuring()
            drag(app, fromY: 700, toY: 250, velocity: .fast)
            drag(app, fromY: 700, toY: 250, velocity: .fast)
        }
    }

    /// 4.1 → 5.1 with 5000 messages until the newest bubble exists.
    func testOpenConversation() {
        let app = launch(route: "chats")
        let row = element(app, "chat.deniz")
        XCTAssertTrue(row.waitForExistence(timeout: 30))
        Thread.sleep(forTimeInterval: 1)
        let options = XCTMeasureOptions()
        options.invocationOptions = [.manuallyStop]
        options.iterationCount = 5
        let open = XCTOSSignpostMetric(subsystem: "com.syconx.us2us", category: "Conversation", name: "open")
        measure(metrics: [XCTClockMetric(), open], options: options) {
            row.tap()
            _ = conversationList(app)
            stopMeasuring()
            Thread.sleep(forTimeInterval: 0.8)
            // The header's back button (12, 56, 40 × 40) by coordinate: no
            // query over the conversation's tree.
            app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: 32, dy: 76)).tap()
            XCTAssertTrue(row.waitForExistence(timeout: 30))
            Thread.sleep(forTimeInterval: 0.8)
        }
    }
}
