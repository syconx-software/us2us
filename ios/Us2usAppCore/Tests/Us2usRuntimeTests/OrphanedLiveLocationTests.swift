import Foundation
import Testing
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime

/// A live-location share lives in the engine's memory. When the process ends
/// while sharing (killed in the background, a crash, a relaunch) nothing sends
/// its ticks any more, yet both bubbles would still look live until the share
/// expires (up to 8 h). The next start sends the share's normal stop instead
/// (§8.3); it never resumes sharing by itself.
@Suite("Orphaned live-location shares")
struct OrphanedLiveLocationTests {
    /// A new engine on the same store: the next unlock in a new process.
    private func nextStart(_ fixture: EngineFixture) async throws -> MessagingEngine {
        let engine = try MessagingEngine(
            storage: fixture.storage, backend: fixture.backend,
            stateDirectory: fixture.directory.directories.runtime, key: fixture.key, clock: fixture.clock,
            configuration: .fast)
        await engine.start()
        return engine
    }

    private func stops(_ fixture: EngineFixture, to peer: AccountId, session: Data) -> Int {
        fixture.backend.sent(to: peer).filter {
            if case .liveLocation(let live)? = $0.data?.payload { return live.stopped && live.sessionID == session }
            return false
        }.count
    }

    @Test("a share the last process left running is stopped at the next start")
    func stoppedAtTheNextStart() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let session = try await fixture.engine.startLiveLocation(
            peer: peer, latitude: 41.0, longitude: 29.0, accuracy: 5, duration: 3600)
        try await eventually { fixture.backend.sent(to: peer).contains { $0.data != nil } }
        await fixture.engine.stop()

        let engine = try await nextStart(fixture)
        try await eventually("the stop is sent") { stops(fixture, to: peer, session: session) == 1 }
        let bubbles = try await engine.messages(of: peer).compactMap { item -> LiveLocationInfo? in
            if case .liveLocation(let live) = item.body { return live }
            return nil
        }
        let shown = try #require(bubbles.first { $0.sessionId == session })
        #expect(shown.stopped, "the bubble here no longer claims to be live")
        #expect(await engine.activeLiveLocationSessions().isEmpty, "sharing is not resumed")
        await engine.stop()
    }

    @Test("a share that was stopped or has expired is left alone")
    func stoppedOrExpiredShares() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let stopped = try await fixture.engine.startLiveLocation(
            peer: peer, latitude: 41.0, longitude: 29.0, accuracy: 5, duration: 3600)
        try await fixture.engine.stopLiveLocation(stopped, peer: peer)
        let expiring = try await fixture.engine.startLiveLocation(
            peer: peer, latitude: 41.0, longitude: 29.0, accuracy: 5, duration: 60)
        try await eventually { stops(fixture, to: peer, session: stopped) == 1 }
        await fixture.engine.stop()
        fixture.clock.advance(by: 120)

        let engine = try await nextStart(fixture)
        await settle()
        #expect(stops(fixture, to: peer, session: stopped) == 1, "no second stop")
        #expect(stops(fixture, to: peer, session: expiring) == 0, "an expired share ends by itself")
        await engine.stop()
    }
}
