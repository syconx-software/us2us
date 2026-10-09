import XCTest

/// Performance of the list and the transitions, measured with XCUITest
/// metrics (run one test at a time; the numbers are in the result bundle and
/// in the log):
///
/// * scrolling the chat list with 500 chats (`-U2StressChats 500`): UIKit's
///   scroll signposts (drag + deceleration: duration, fps, hitches) and the
///   process hitch metric;
/// * the list while it keeps changing (`-U2StressTicks YES`): hitches while
///   rows move to the top and counters / statuses tick;
/// * push / pop of 5.1 over the 500-chat list, the welcome pages, the 4.2
///   sheet and the unlock dissolve: the app's own animation signposts
///   (`U2Signposts`, DEBUG builds) — duration, fps, hitches.
///
/// The clock runs (no `-U2FrozenClock`): packets, rings and countdowns move
/// as they do on a device.
@MainActor
final class FluidityPerformanceUITests: XCTestCase {
    private static let subsystem = "com.syconx.us2us"

    override func setUp() async throws {
        continueAfterFailure = false
    }

    private func launch(_ arguments: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments =
            ["-U2Fixtures", "YES", "-U2Language", "tr", "-U2Theme", "dark", "-U2ResetPreferences", "YES", "-U2FrameProbe", "YES"] + arguments
        app.launch()
        return app
    }

    private func signpost(_ name: String, category: String = "navigation") -> XCTOSSignpostMetric {
        XCTOSSignpostMetric(subsystem: Self.subsystem, category: category, name: name)
    }

    /// `measure` plus the app's main-thread frame drops (`FrameProbeMetric`,
    /// works on the simulator too) and, on iOS 26+, XCTest's own hitch
    /// metric (it reports on devices only).
    private func measureFrames(
        _ app: XCUIApplication, metrics: [any XCTMetric] = [], block: () -> Void
    ) {
        let recorder = FrameProbeRecorder()
        var all = metrics + [FrameProbeMetric(recorder: recorder) as any XCTMetric]
        if #available(iOS 26.0, *) { all.append(XCTHitchMetric(application: app)) }
        measure(metrics: all, options: options) {
            recorder.begin(app)
            block()
            recorder.end(app)
        }
    }

    private var options: XCTMeasureOptions {
        let options = XCTMeasureOptions()
        options.iterationCount = 5
        return options
    }

    private func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier].firstMatch
    }

    private func settle(_ seconds: TimeInterval = 0.8) {
        RunLoop.current.run(until: Date().addingTimeInterval(seconds))
    }

    // MARK: 4.1 · 500 chats

    func testChatListScroll500() {
        scroll(launch(["-U2Start", "chats", "-U2StressChats", "500"]))
    }

    /// The same without the header's flowing packets (line “connecting”):
    /// what the packets' per-frame updates cost while scrolling.
    func testChatListScroll500NoPackets() {
        scroll(launch(["-U2Route", "chats:4.1-connecting", "-U2StressChats", "500"]))
    }

    private func scroll(_ app: XCUIApplication) {
        XCTAssertTrue(element(app, "chat.kisi.000").waitForExistence(timeout: 20))
        settle(1.5)
        // Flicks by screen coordinates: no element query (an accessibility
        // snapshot of the app) between them.
        let low = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.82))
        let high = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.32))
        measureFrames(app, metrics: [XCTOSSignpostMetric.scrollDraggingMetric, XCTOSSignpostMetric.scrollDecelerationMetric]) {
            for _ in 0..<3 {
                low.press(forDuration: 0.02, thenDragTo: high, withVelocity: .fast, thenHoldForDuration: 0)
                settle(0.9)
            }
            for _ in 0..<3 {
                high.press(forDuration: 0.02, thenDragTo: low, withVelocity: .fast, thenHoldForDuration: 0)
                settle(0.9)
            }
        }
    }

    func testChatListTicking500() {
        let app = launch(["-U2Start", "chats", "-U2StressChats", "500", "-U2StressTicks", "YES"])
        XCTAssertTrue(element(app, "chat.kisi.000").waitForExistence(timeout: 20))
        settle(1.5)
        measureFrames(app, metrics: [XCTCPUMetric(application: app)]) {
            settle(6)
        }
    }

    // MARK: 4.1 → 5.1 → 4.1

    func testConversationPushPop() {
        let app = launch(["-U2Start", "chats", "-U2StressChats", "500"])
        let row = element(app, "chat.deniz")
        XCTAssertTrue(row.waitForExistence(timeout: 20))
        settle(1.5)
        measureFrames(app, metrics: [signpost("push"), signpost("pop")]) {
            row.tap()
            let back = app.buttons.matching(identifier: "chatHeader.back").firstMatch
            XCTAssertTrue(back.waitForExistence(timeout: 5))
            settle()
            back.tap()
            XCTAssertTrue(row.waitForExistence(timeout: 5))
            settle()
        }
    }

    // MARK: 1.1 ↔ 1.3

    func testWelcomePages() {
        let app = launch(["-U2Start", "welcome"])
        XCTAssertTrue(element(app, "welcome.start").waitForExistence(timeout: 30))
        settle(2)
        measureFrames(app, metrics: [signpost("page")]) {
            element(app, "welcome.start").tap()
            settle()
            element(app, "welcome.page2.cta").tap()
            settle()
            app.swipeRight()
            settle()
            app.swipeRight()
            settle()
        }
    }

    // MARK: 4.2 sheet

    func testNewChatSheet() {
        let app = launch(["-U2Start", "chats", "-U2StressChats", "500"])
        XCTAssertTrue(element(app, "chat.deniz").waitForExistence(timeout: 20))
        settle(1.5)
        measureFrames(
            app, metrics: [signpost("sheetRise", category: "presentation"), signpost("sheetDismiss", category: "presentation")]
        ) {
            element(app, "chats.new").tap()
            let cancel = app.buttons["Vazgeç"].firstMatch
            XCTAssertTrue(cancel.waitForExistence(timeout: 5))
            settle(1.2)
            cancel.tap()
            settle(1.2)
        }
    }

    // MARK: 03 · unlock

    func testUnlockDissolve() {
        let app = launch(["-U2Start", "locked"])
        XCTAssertTrue(app.buttons["2"].waitForExistence(timeout: 20))
        measureFrames(app, metrics: [signpost("unlock", category: "presentation")]) {
            for digit in "246813" { app.buttons[String(digit)].tap() }
            XCTAssertTrue(app.staticTexts["Sohbetler"].waitForExistence(timeout: 5))
            settle(1)
            XCUIDevice.shared.press(.home)
            settle(1)
            app.activate()
            XCTAssertTrue(app.buttons["2"].waitForExistence(timeout: 10))
            settle(1)
        }
    }
}

/// The app's DEBUG frame probe (`-U2FrameProbe YES`, `FrameProbe`), read on
/// the main thread at the start and the end of each iteration (inside the
/// measure block); `FrameProbeMetric` reports the difference.
///
/// Reading is an accessibility snapshot, which itself costs the app a frame:
/// the start reads twice, 0.4 s apart, so the first read's cost is not
/// counted (a read's own cost lands after the value it returns).
final class FrameProbeRecorder: @unchecked Sendable {
    struct Stats {
        var frames = 0
        var hitches = 0
        var hitchMs = 0.0
        var long50 = 0
        var animationFrames = 0
        var animationHitches = 0
        var animationHitchMs = 0.0
        var animationLong50 = 0

        init?(_ text: String?) {
            guard let text else { return nil }
            var values: [String: String] = [:]
            for pair in text.split(separator: " ") {
                let parts = pair.split(separator: "=", maxSplits: 1).map(String.init)
                if parts.count == 2 { values[parts[0]] = parts[1] }
            }
            guard let frames = values["frames"].flatMap(Int.init), let hitches = values["hitches"].flatMap(Int.init),
                let hitchMs = values["hitchMs"].flatMap(Double.init), let long50 = values["long50"].flatMap(Int.init)
            else { return nil }
            self.frames = frames
            self.hitches = hitches
            self.hitchMs = hitchMs
            self.long50 = long50
            animationFrames = values["aframes"].flatMap(Int.init) ?? 0
            animationHitches = values["ahitches"].flatMap(Int.init) ?? 0
            animationHitchMs = values["ahitchMs"].flatMap(Double.init) ?? 0
            animationLong50 = values["along50"].flatMap(Int.init) ?? 0
        }
    }

    struct Iteration {
        let start: Stats
        let end: Stats
        let seconds: Double
    }

    private let lock = NSLock()
    private var pendingStart: (Stats, Date)?
    private var completed: [Iteration] = []

    @MainActor
    private func read(_ app: XCUIApplication) -> Stats? {
        Stats(app.descendants(matching: .any)["debug.frameProbe"].firstMatch.value as? String)
    }

    @MainActor
    func begin(_ app: XCUIApplication) {
        _ = read(app)
        RunLoop.current.run(until: Date().addingTimeInterval(0.4))
        guard let stats = read(app) else { return }
        lock.withLock { pendingStart = (stats, Date()) }
    }

    @MainActor
    func end(_ app: XCUIApplication) {
        guard let stats = read(app) else { return }
        lock.withLock {
            guard let (start, date) = pendingStart else { return }
            pendingStart = nil
            completed.append(Iteration(start: start, end: stats, seconds: Date().timeIntervalSince(date)))
        }
    }

    func next() -> Iteration? {
        lock.withLock { completed.isEmpty ? nil : completed.removeFirst() }
    }
}

/// Main-thread hitches (a frame gap over 1.5 frames), hitch time, hitch-time
/// ratio (ms of hitch per second) and frames over 50 ms of one iteration.
final class FrameProbeMetric: NSObject, XCTMetric, @unchecked Sendable {
    let recorder: FrameProbeRecorder

    init(recorder: FrameProbeRecorder) {
        self.recorder = recorder
    }

    func copy(with zone: NSZone? = nil) -> Any {
        FrameProbeMetric(recorder: recorder)
    }

    func reportMeasurements(
        from startTime: XCTPerformanceMeasurementTimestamp, to endTime: XCTPerformanceMeasurementTimestamp
    ) throws -> [XCTPerformanceMeasurement] {
        guard let iteration = recorder.next() else { return [] }
        let hitchMs = iteration.end.hitchMs - iteration.start.hitchMs
        func measurement(_ id: String, _ name: String, _ value: Double, _ unit: String) -> XCTPerformanceMeasurement {
            XCTPerformanceMeasurement(
                identifier: "com.syconx.us2us.frames.\(id)", displayName: name, doubleValue: value, unitSymbol: unit,
                polarity: .prefersSmaller)
        }
        let start = iteration.start
        let end = iteration.end
        var transitions: [XCTPerformanceMeasurement] = []
        let animationFrames = end.animationFrames - start.animationFrames
        if animationFrames > 0 {
            // At 60 Hz (simulator) one frame is 16.7 ms: the ratio is per second of transition.
            let animationMs = end.animationHitchMs - start.animationHitchMs
            transitions = [
                measurement("transitionHitches", "Transition hitches", Double(end.animationHitches - start.animationHitches), "hitches"),
                measurement("transitionHitchTime", "Transition hitch time", animationMs, "ms"),
                measurement("transitionLong50", "Transition frames over 50 ms", Double(end.animationLong50 - start.animationLong50), "frames"),
                measurement("transitionFrames", "Transition frames", Double(animationFrames), "frames"),
            ]
        }
        return transitions + [
            measurement("hitches", "Main-thread hitches", Double(iteration.end.hitches - iteration.start.hitches), "hitches"),
            measurement("hitchTime", "Main-thread hitch time", hitchMs, "ms"),
            measurement("hitchRatio", "Main-thread hitch time ratio", hitchMs / max(iteration.seconds, 0.001), "ms per s"),
            measurement("long50", "Frames over 50 ms", Double(iteration.end.long50 - iteration.start.long50), "frames"),
        ]
    }
}
