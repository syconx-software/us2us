import Foundation
import Testing
import Us2usKit

@testable import Us2usRuntime

@Suite("Backoff")
struct BackoffTests {
    @Test func exponentialBoundsWithFullJitter() {
        let backoff = Backoff(base: 1, cap: 60, floor: 0.5, random: { $0.upperBound })
        #expect(backoff.delay(forFailures: 0) == 0)
        #expect(backoff.delay(forFailures: 1) == 1)
        #expect(backoff.delay(forFailures: 2) == 2)
        #expect(backoff.delay(forFailures: 5) == 16)
        #expect(backoff.delay(forFailures: 30) == 60)
        let low = Backoff(base: 1, cap: 60, floor: 0.5, random: { $0.lowerBound })
        #expect(low.delay(forFailures: 6) == 0.5)
        // Real jitter stays inside the bound.
        let real = Backoff()
        for failures in 1...10 {
            let delay = real.delay(forFailures: failures)
            #expect(delay >= 0.5 && delay <= real.bound(forFailures: failures) + 0.5)
        }
    }

    @Test func relayHintsWin() {
        let backoff = Backoff(random: { $0.lowerBound })
        #expect(backoff.delay(forFailures: 1, after: RelayError.rateLimited(retryAfter: 42)) == 42)
        #expect(backoff.delay(forFailures: 1, after: RelayError.connectionReplaced) == 30)
        #expect(backoff.delay(forFailures: 1, after: RelayError.network(.timedOut)) == 0.5)
    }
}

@Suite("Connection supervisor (§4.7, §5.4)")
struct ConnectionSupervisorTests {
    final class Recorder: @unchecked Sendable {
        let delivered = LockedState<[String]>([])
        let queueEmpty = LockedState(0)
    }

    func make(_ backend: FakeBackend, clock: ManualClock, recorder: Recorder) -> ConnectionSupervisor {
        ConnectionSupervisor(
            backend: backend, clock: clock, backoff: Backoff(base: 1, cap: 8, floor: 1, random: { $0.upperBound }),
            events: ConnectionEvents(
                deliver: { message in recorder.delivered.withLock { $0.append(message.relayId) } },
                queueEmpty: { recorder.queueEmpty.withLock { $0 += 1 } }))
    }

    func inbound(_ id: String) -> InboundMessage {
        InboundMessage(
            relayId: id, serverTimestamp: 0, sender: AccountId(uuid: UUID()), senderUsername: nil,
            content: .textMessage("x"), identityChanged: false)
    }

    @Test func backlogThenLive() async throws {
        let backend = FakeBackend()
        let clock = ManualClock()
        let recorder = Recorder()
        let supervisor = make(backend, clock: clock, recorder: recorder)
        await supervisor.start()
        try await eventually { backend.latestStream?.isRunning == true }
        #expect(await supervisor.status == .syncing)
        backend.latestStream?.push(.message(inbound("a")))
        backend.latestStream?.push(.queueEmpty)
        try await eventually { await supervisor.status == .open }
        #expect(recorder.delivered.withLock { $0 } == ["a"])
        #expect(recorder.queueEmpty.withLock { $0 } == 1)
        await supervisor.stop()
        #expect(backend.latestStream?.isClosed == true)
        #expect(await supervisor.status == .offline)
    }

    @Test func reconnectsWithBackoffAndResetsAfterQueueEmpty() async throws {
        let backend = FakeBackend()
        let clock = ManualClock()
        let recorder = Recorder()
        let supervisor = make(backend, clock: clock, recorder: recorder)
        backend.failNextOpen(with: .network(.notConnectedToInternet))
        backend.failNextOpen(with: .network(.notConnectedToInternet))
        await supervisor.start()
        try await eventually {
            if case .retrying(let at) = await supervisor.status { return abs(at.timeIntervalSince(clock.now()) - 1) < 0.001 }
            return false
        }
        #expect(backend.streamCount == 0)
        // First failure: 1 s, second: 2 s, then the third attempt connects.
        try await eventually {
            if case .retrying(let at) = await supervisor.status, abs(at.timeIntervalSince(clock.now()) - 2) < 0.001 { return true }
            if clock.sleeperCount > 0 { clock.advance(by: 1) }
            return false
        }
        try await eventually {
            if clock.sleeperCount > 0 { clock.advance(by: 1) }
            return backend.latestStream?.isRunning == true
        }
        backend.latestStream?.push(.queueEmpty)
        try await eventually { await supervisor.status == .open }
        // A dropped connection after a good one (it stayed up a while, not
        // only until its backlog) starts over at the shortest delay.
        clock.advance(by: ConnectionSupervisor.stableConnection)
        backend.latestStream?.push(.fail(.connectionClosed(code: 1011)))
        try await eventually {
            if case .retrying(let at) = await supervisor.status { return at.timeIntervalSince(clock.now()) <= 1.01 }
            return false
        }
        try await eventually {
            if clock.sleeperCount > 0 { clock.advance(by: 1) }
            return backend.streamCount == 2
        }
        await supervisor.stop()
    }

    @Test func revokedTokenStopsForGood() async throws {
        let backend = FakeBackend()
        let clock = ManualClock()
        let supervisor = make(backend, clock: clock, recorder: Recorder())
        await supervisor.start()
        try await eventually { backend.latestStream?.isRunning == true }
        backend.latestStream?.push(.fail(.server(status: 401, code: .unauthorized, message: nil)))
        try await eventually { await supervisor.status == .revoked }
        clock.advance(by: 120)
        await settle()
        #expect(backend.streamCount == 1)
        await supervisor.start()
        await settle()
        #expect(backend.streamCount == 1)
    }

    @Test func waitsForTheNetworkAndReconnectsAtOnce() async throws {
        let backend = FakeBackend()
        let clock = ManualClock()
        let supervisor = make(backend, clock: clock, recorder: Recorder())
        await supervisor.networkChanged(available: false)
        await supervisor.start()
        try await eventually { await supervisor.status == .waitingForNetwork }
        #expect(backend.streamCount == 0)
        await supervisor.networkChanged(available: true)
        try await eventually { backend.latestStream?.isRunning == true }
        await supervisor.stop()
    }

    @Test func statusUpdatesArriveInOrder() async throws {
        let backend = FakeBackend()
        let clock = ManualClock()
        let supervisor = make(backend, clock: clock, recorder: Recorder())
        let collected = LockedState<[ConnectionStatus]>([])
        let listener = Task {
            for await status in supervisor.statusUpdates {
                collected.withLock { $0.append(status) }
                if status == .offline { break }
            }
        }
        await supervisor.start()
        try await eventually { backend.latestStream?.isRunning == true }
        backend.latestStream?.push(.queueEmpty)
        try await eventually { await supervisor.status == .open }
        await supervisor.stop()
        await listener.value
        #expect(collected.withLock { $0 } == [.connecting, .syncing, .open, .offline])
    }
}
