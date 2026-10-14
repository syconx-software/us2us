import Foundation
import Testing
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime

/// After a restore whose token rotation got no answer (§5.5 step 3.4) the
/// old phone may still have access until the relay says which token the
/// account has. The runtime says so (`tokenRotationPending`), asks again on
/// every connection attempt — from the first unlock after a launch on — and
/// at once on “Şimdi kontrol et” (`checkPendingTokenRotation()`); the flag
/// goes as soon as the outcome is known.
@Suite("Pending token rotation after a restore")
@MainActor
struct PendingTokenRotationTests {
    private static let noAnswer = RelayError.network(.notConnectedToInternet)

    /// An account whose last rotation is unanswered, unlocked again: the
    /// connection's first attempt asked the relay and got no answer.
    /// `streams`: the sockets opened before (onboarding's own connection).
    private func unlockedWithPendingRotation() async throws -> (fixture: RuntimeFixture, streams: Int) {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        await fixture.runtime.lockNow()
        let streams = fixture.backend.streamCount
        fixture.backend.state.withLock {
            $0.pendingTokenRotation = true
            $0.rotationAnswer = .failure(Self.noAnswer)
        }
        try await fixture.runtime.unlock(code: RuntimeFixture.code)
        #expect(fixture.runtime.lockState == .unlocked)
        try await eventually("the connection asked") { fixture.backend.rotationProbes >= 1 }
        try await eventually("the connection waits to retry") {
            if case .retrying = fixture.runtime.connection { return true }
            return false
        }
        return (fixture, streams)
    }

    @Test("the notice shows at unlock while the rotation is unanswered, and no socket opens")
    func pendingAtUnlock() async throws {
        let (fixture, streams) = try await unlockedWithPendingRotation()
        #expect(fixture.runtime.tokenRotationPending)
        #expect(fixture.backend.streamCount == streams, "nothing uses either token until the outcome is known")
    }

    @Test("“Şimdi kontrol et” settles it: the notice goes and the line opens at once")
    func checkNowSettles() async throws {
        let (fixture, _) = try await unlockedWithPendingRotation()
        fixture.backend.answerTokenRotation(.success(.ready))
        let probes = fixture.backend.rotationProbes

        #expect(await fixture.runtime.checkPendingTokenRotation() == .settled)
        #expect(!fixture.runtime.tokenRotationPending)
        #expect(fixture.backend.rotationProbes == probes + 1)
        // No waiting for the backoff: the connection is tried now.
        try await fixture.connect()
    }

    @Test("still no answer: the notice stays and both tokens stay unused")
    func checkNowUnanswered() async throws {
        let (fixture, streams) = try await unlockedWithPendingRotation()
        let probes = fixture.backend.rotationProbes

        #expect(await fixture.runtime.checkPendingTokenRotation() == .unanswered)
        #expect(fixture.runtime.tokenRotationPending)
        #expect(fixture.backend.rotationProbes == probes + 1)
        #expect(fixture.backend.streamCount == streams)
    }

    @Test("refused (another phone restored first): the notice goes and the line ends as revoked")
    func checkNowRefused() async throws {
        let (fixture, _) = try await unlockedWithPendingRotation()
        fixture.backend.answerTokenRotation(.success(.backupNoLongerValid))

        #expect(await fixture.runtime.checkPendingTokenRotation() == .refused)
        #expect(!fixture.runtime.tokenRotationPending)
        try await eventually("revoked") { fixture.runtime.connection == .revoked }
    }

    @Test("refused by the connection's own attempt first: checking afterwards says refused, never settled")
    func refusedByTheConnectionFirst() async throws {
        let (fixture, _) = try await unlockedWithPendingRotation()
        fixture.backend.answerTokenRotation(.success(.backupNoLongerValid))
        try await eventually("the connection's next attempt is refused") {
            fixture.clock.advance(by: 1)
            return fixture.backend.state.withLock { !$0.pendingTokenRotation }
        }
        let probes = fixture.backend.rotationProbes

        #expect(await fixture.runtime.checkPendingTokenRotation() == .refused)
        #expect(fixture.backend.rotationProbes == probes, "nothing pending: nothing asked again")
        #expect(!fixture.runtime.tokenRotationPending)
        try await eventually("revoked") { fixture.runtime.connection == .revoked }
    }

    @Test("a connection attempt that settles it removes the notice by itself")
    func settledByTheConnection() async throws {
        let (fixture, _) = try await unlockedWithPendingRotation()
        fixture.backend.answerTokenRotation(.success(.ready))
        // The supervisor's next attempt (after its backoff) asks again.
        try await eventually("the next attempt") {
            fixture.clock.advance(by: 1)
            return fixture.backend.latestStream?.isRunning == true
        }
        try await eventually("the notice goes") { !fixture.runtime.tokenRotationPending }
    }

    @Test("locked: no notice; after a relaunch the first unlock shows it again and asks again")
    func resumesAfterRelaunch() async throws {
        let (fixture, _) = try await unlockedWithPendingRotation()
        await fixture.runtime.lockNow()
        #expect(!fixture.runtime.tokenRotationPending, "nothing about the account is shown while locked")

        fixture.relaunch()
        let probes = fixture.backend.rotationProbes
        try await fixture.runtime.unlock(code: RuntimeFixture.code)
        #expect(fixture.runtime.tokenRotationPending)
        try await eventually("asked again after the relaunch") { fixture.backend.rotationProbes > probes }
    }

    @Test("nothing pending: no notice, and checking is harmless")
    func nothingPending() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        #expect(!fixture.runtime.tokenRotationPending)
        #expect(await fixture.runtime.checkPendingTokenRotation() == .settled)
        #expect(fixture.backend.rotationProbes == 0)
    }

    @Test("while locked the check asks nothing")
    func lockedCheck() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        await fixture.runtime.lockNow()
        fixture.backend.state.withLock { $0.pendingTokenRotation = true }
        #expect(await fixture.runtime.checkPendingTokenRotation() == .unanswered)
        #expect(fixture.backend.rotationProbes == 0)
    }
}
