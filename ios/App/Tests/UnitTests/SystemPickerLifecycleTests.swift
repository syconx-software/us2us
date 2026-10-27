import XCTest
@testable import us2us

@MainActor
final class SystemPickerLifecycleTests: XCTestCase {
    private func model(_ activity: SystemPickerActivity) -> AppModel {
        var configuration = AppConfiguration()
        configuration.start = .chats
        let defaults = UserDefaults(suiteName: "us2us.picker-tests.\(UUID().uuidString)")!
        return AppModel(
            configuration: configuration, preferences: AppPreferences(defaults: defaults),
            messenger: DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero),
            pickerActivity: activity)
    }

    func testPickerInactiveDoesNotPutSecurityWindowAbovePicker() {
        let activity = SystemPickerActivity()
        let model = model(activity)
        let token = activity.begin(cancel: {})
        var snapshots = 0
        model.sceneWillResignActive { snapshots += 1; return nil }
        XCTAssertEqual(model.overlay, .none)
        XCTAssertEqual(snapshots, 0)
        activity.end(token)
        model.sceneDidBecomeActive()
        XCTAssertEqual(model.overlay, .none)
    }

    func testActualBackgroundStillCancelsPickerAndLocksOnReturn() {
        let activity = SystemPickerActivity()
        let model = model(activity)
        var cancellations = 0
        _ = activity.begin { cancellations += 1 }
        model.sceneWillResignActive { nil }
        model.sceneDidEnterBackground()
        XCTAssertFalse(activity.isPresented)
        XCTAssertEqual(cancellations, 1)
        XCTAssertEqual(model.overlay, .privacyCover)
        model.sceneWillEnterForeground()
        model.sceneDidBecomeActive()
        XCTAssertEqual(model.overlay, .lock)
    }

    func testStaleDismissalCannotEndANewerPicker() {
        let activity = SystemPickerActivity()
        let old = activity.begin(cancel: {})
        XCTAssertNil(activity.begin(cancel: {}))
        activity.end(old)
        let next = activity.begin(cancel: {})
        activity.end(old)
        XCTAssertTrue(activity.isPresented)
        activity.end(next)
        XCTAssertFalse(activity.isPresented)
    }

    func testCancellationRunsOnlyOnceAndReleasesThePicker() {
        let activity = SystemPickerActivity()
        var cancellations = 0
        _ = activity.begin { cancellations += 1 }
        activity.cancel()
        activity.cancel()
        XCTAssertEqual(cancellations, 1)
        XCTAssertNotNil(activity.begin(cancel: {}))
    }
}
