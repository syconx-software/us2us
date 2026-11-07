import Foundation
import Testing

@testable import Us2usRuntime

/// Coming back from the background: neither Face ID nor the right code may be
/// left without an answer because something of the previous session or of an
/// earlier attempt has not finished.
@MainActor
@Suite("Unlocking after the background")
struct UnlockAfterBackgroundTests {
    /// The app leaves while the socket is reconnecting, the reconnect gets no
    /// answer (as after a suspension), and the grace period ends.
    private func leaveWithAHungConnection(_ fixture: RuntimeFixture) async throws {
        try await fixture.connect()
        fixture.backend.setOpensHang(true)
        fixture.backend.latestStream?.push(.fail(.connectionClosed(code: 1011)))
        try await eventually("reconnecting") {
            fixture.clock.advance(by: 1)
            return fixture.runtime.connection == .connecting
        }
        fixture.runtime.scenePhaseChanged(.background)
        try await eventually("grace over") {
            fixture.clock.advance(by: 1)
            return fixture.runtime.session == nil
        }
        fixture.backend.setOpensHang(false)
        fixture.runtime.scenePhaseChanged(.active)
    }

    @Test("the right code opens the app though the old socket never answers")
    func codeWithHungConnection() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        try await leaveWithAHungConnection(fixture)
        let unlock = Task { try await fixture.runtime.unlock(code: RuntimeFixture.code) }
        try await eventually("unlocked") {
            fixture.clock.advance(by: 1)
            return fixture.runtime.lockState == .unlocked
        }
        try await unlock.value
    }

    @Test("Face ID opens the app though the old socket never answers")
    func faceIDWithHungConnection() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        try await fixture.runtime.enableQuickUnlock(code: RuntimeFixture.code)
        try await leaveWithAHungConnection(fixture)
        await fixture.runtime.refreshLockScreen()
        let unlock = Task { try await fixture.runtime.unlockWithBiometrics() }
        try await eventually("unlocked") {
            fixture.clock.advance(by: 1)
            return fixture.runtime.lockState == .unlocked
        }
        try await unlock.value
        #expect(fixture.biometrics.prompts.count == 1)
    }

    @Test("“Kodla aç” closes a Face ID prompt left open, and the code then opens the app")
    func codeAfterCancellingAnOpenPrompt() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        try await fixture.runtime.enableQuickUnlock(code: RuntimeFixture.code)
        fixture.runtime.scenePhaseChanged(.background)
        fixture.runtime.scenePhaseChanged(.active)
        fixture.biometrics.holdNextPrompt()
        let attempt = Task { try? await fixture.runtime.unlockWithBiometrics() }
        try await eventually("prompt open") { fixture.biometrics.promptIsOpen }
        #expect(fixture.runtime.lockState == .working)

        fixture.runtime.cancelBiometricUnlock()
        await attempt.value
        #expect(fixture.runtime.lockState == .locked)
        try await fixture.runtime.unlock(code: RuntimeFixture.code)
        #expect(fixture.runtime.lockState == .unlocked)
    }

    @Test("a code entered while an attempt is finishing waits for it instead of being dropped")
    func codeWaitsForAnAttemptInFlight() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        try await fixture.runtime.enableQuickUnlock(code: RuntimeFixture.code)
        fixture.runtime.scenePhaseChanged(.background)
        fixture.runtime.scenePhaseChanged(.active)
        fixture.biometrics.holdNextPrompt()
        let attempt = Task { try? await fixture.runtime.unlockWithBiometrics() }
        try await eventually("prompt open") { fixture.biometrics.promptIsOpen }

        let code = Task { try await fixture.runtime.unlock(code: RuntimeFixture.code) }
        try await Task.sleep(nanoseconds: 150_000_000)
        #expect(fixture.runtime.lockState == .working)
        fixture.biometrics.cancelPrompt()
        try await code.value
        await attempt.value
        #expect(fixture.runtime.lockState == .unlocked)
    }
}
