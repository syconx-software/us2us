import XCTest

// Performance UI tests (target `us2usPerformanceTests`, scheme `us2usPerformance`).
//
// Not part of the normal `us2us` test plan: they are slow and only mean
// something on a Release build of the app, one class at a time, on an idle
// machine. XcodeGen: `include: [Tests/PerformanceTests/target.yml]` in
// project.yml adds the target and its scheme (see target.yml).
//
//   xcodebuild build-for-testing -project us2us.xcodeproj -scheme us2usPerformance \
//     -configuration Release -destination 'platform=iOS Simulator,name=<sim>' -derivedDataPath <dd>
//   xcodebuild test-without-building -project us2us.xcodeproj -scheme us2usPerformance \
//     -configuration Release -destination 'platform=iOS Simulator,name=<sim>' -derivedDataPath <dd> \
//     -only-testing:us2usPerformanceTests/ConversationPerformanceTests
//
// Stress data: `-U2PerfMessages <n>` (deniz gets n older messages) and
// `-U2PerfChats <n>` (n extra chats in 4.1) are launch arguments read by
// DesignFixturesService (fixture data only; the live service ignores them).
// An app without that hook still runs every test, on the design's own small
// data — the numbers then say nothing about long histories.
//
// With the message column as an eager VStack, a 5000-message 5.1 makes every
// XCUITest query snapshot ~5000 accessibility elements (~90 s each; the first
// swipe fails). Run with `-test-timeouts-enabled YES` if that can happen.
//
// `TEST_RUNNER_U2PERF_EXTRA_ARGS="-U2Foo YES"` appends launch arguments.
//
// Profiling with Instruments while a test drives the app: set
// `TEST_RUNNER_U2PERF_SOAK_SECONDS=30` on the xcodebuild command line; the
// `testSoak…` tests then repeat their interaction for that long and print
// `PERFPHASE <name> start|end <epoch>` so `xcrun xctrace record --attach us2us`
// can be lined up with the phase.

@MainActor
class U2PerformanceTestCase: XCTestCase {
    /// Messages in the stress conversation (deniz).
    static let stressMessages = 5000
    /// Extra chats in the stress chat list.
    static let stressChats = 300

    override func setUp() async throws {
        continueAfterFailure = false
    }

    /// The soak duration from `TEST_RUNNER_U2PERF_SOAK_SECONDS` (0 = no soak).
    var soakSeconds: TimeInterval {
        ProcessInfo.processInfo.environment["U2PERF_SOAK_SECONDS"].flatMap(TimeInterval.init) ?? 0
    }

    /// Launches the app on the design fixtures (dark, Turkish, live clock so
    /// countdowns and the line's packets run as they do for a user).
    func launch(_ arguments: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-U2Language", "tr", "-U2Theme", "dark"] + arguments + extraArguments
        app.launch()
        return app
    }

    /// More launch arguments from `TEST_RUNNER_U2PERF_EXTRA_ARGS` (space separated),
    /// e.g. to compare a candidate change behind a launch flag.
    var extraArguments: [String] {
        (ProcessInfo.processInfo.environment["U2PERF_EXTRA_ARGS"] ?? "").split(separator: " ").map(String.init)
    }

    func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier].firstMatch
    }

    func waitFor(_ app: XCUIApplication, _ identifier: String, timeout: TimeInterval = 60) -> XCUIElement {
        let target = element(app, identifier)
        XCTAssertTrue(target.waitForExistence(timeout: timeout), "\(identifier) not found")
        return target
    }

    func tap(_ app: XCUIApplication, _ identifier: String, timeout: TimeInterval = 60) {
        waitFor(app, identifier, timeout: timeout).tap()
    }

    /// Lets the run loop (and the app) run without touching it.
    func hold(_ seconds: TimeInterval) {
        RunLoop.current.run(until: Date().addingTimeInterval(seconds))
    }

    /// `PERFPHASE <name> start|end <epoch>` in the test log.
    func phase(_ name: String, _ edge: String) {
        print(String(format: "PERFPHASE %@ %@ %.3f", name, edge, Date().timeIntervalSince1970))
    }

    /// Runs `body` repeatedly for `soakSeconds` between two phase marks.
    func soak(_ name: String, _ body: () -> Void) {
        guard soakSeconds > 0 else { return }
        phase(name, "start")
        let end = Date().addingTimeInterval(soakSeconds)
        while Date() < end { body() }
        phase(name, "end")
    }

    func options(iterations: Int = 5) -> XCTMeasureOptions {
        let options = XCTMeasureOptions()
        options.iterationCount = iterations
        return options
    }

    /// CPU (time, cycles, instructions) and memory of the app process.
    func appMetrics(_ app: XCUIApplication) -> [any XCTMetric] {
        [XCTCPUMetric(application: app), XCTMemoryMetric(application: app)]
    }

    /// The conversation's message column (the only scroll view of 5.1).
    func conversationScrollView(_ app: XCUIApplication) -> XCUIElement {
        app.scrollViews.firstMatch
    }

    /// The composer's text input (SwiftUI's vertical TextField is a text view).
    func composerField(_ app: XCUIApplication) -> XCUIElement {
        app.textViews.firstMatch.exists ? app.textViews.firstMatch : app.textFields.firstMatch
    }
}
