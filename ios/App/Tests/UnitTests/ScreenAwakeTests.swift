import Foundation
import Testing
@testable import us2us

@Suite("Screen awake preference and media ownership")
@MainActor
struct ScreenAwakeTests {
    @Test func preferenceIsOffUntilSelectedAndResetRemovesIt() throws {
        let name = "screen-awake-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        let preferences = AppPreferences(defaults: defaults)
        #expect(!preferences.keepScreenOn)
        preferences.keepScreenOn = true
        let reopened = AppPreferences(defaults: defaults)
        #expect(reopened.keepScreenOn)
        reopened.reset()
        #expect(!reopened.keepScreenOn)
        #expect(!AppPreferences(defaults: defaults).keepScreenOn)
    }

    @Test func closingVoiceDoesNotOverridePreferenceOrAnotherVoiceOwner() {
        var screenOn = false
        let controller = ScreenAwakeController { screenOn = $0 }
        let first = UUID(), second = UUID()
        controller.configure(preference: true, contentVisible: true)
        #expect(!screenOn)
        controller.setActive(true)
        controller.setMediaActive(true, owner: first)
        controller.setMediaActive(false, owner: first)
        #expect(screenOn)
        controller.setMediaActive(true, owner: first)
        controller.setMediaActive(true, owner: second)
        controller.configure(preference: false, contentVisible: true)
        controller.setMediaActive(false, owner: first)
        #expect(screenOn)
        controller.setMediaActive(false, owner: second)
        #expect(!screenOn)
    }

    @Test func inactiveAndLockedAppCannotBeHeldOpenByPreferenceOrVoice() {
        var screenOn = false
        let controller = ScreenAwakeController { screenOn = $0 }
        controller.configure(preference: true, contentVisible: true)
        controller.setMediaActive(true, owner: UUID())
        controller.setActive(true)
        #expect(screenOn)
        controller.setActive(false)
        #expect(!screenOn)
        controller.configure(preference: true, contentVisible: false)
        controller.setActive(true)
        #expect(!screenOn)
        controller.configure(preference: true, contentVisible: true)
        #expect(screenOn)
    }
}
