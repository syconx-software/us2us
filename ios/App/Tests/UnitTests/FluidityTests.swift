import Foundation
import Testing

@testable import us2us

/// The fluidity pass: staged pushes keep the path's meaning, the layer-motion
/// chain, gesture hand-off velocities, the cached formatters and the stress
/// fixture.
@MainActor
@Suite("Fluidity")
struct FluidityTests {
    @Test func stagedPushIsOnThePathAtOnce() {
        let router = U2Router<AppRoute>()
        router.push(.settings)
        // Built for one frame before it moves in, but already the top.
        #expect(router.path == [.settings])
        #expect(router.top == .settings)
        #expect(!router.isAtRoot)
        #expect(!router.motion.isSettled)
        router.push(.settingsPicker(.theme))
        router.replaceTop(with: .backupExport)
        #expect(router.path == [.settings, .backupExport])
        #expect(router.top == .backupExport)
    }

    @Test func layerMotionChains() {
        let outer = U2LayerMotion()
        let inner = U2LayerMotion(parent: outer)
        #expect(inner.isSettled)
        outer.setMoving(true)
        #expect(!inner.isSettled)
        outer.setMoving(false)
        inner.setMoving(true)
        #expect(!inner.isSettled)
        #expect(outer.isSettled)
    }

    @Test func releaseVelocityIsRelativeAndClamped() {
        #expect(U2Motion.relativeVelocity(1_000, remaining: 500) == 2)
        #expect(U2Motion.relativeVelocity(-1_000, remaining: 500) == -2)
        #expect(U2Motion.relativeVelocity(100_000, remaining: 10) == 12)
        #expect(U2Motion.relativeVelocity(800, remaining: 0.1) == 0)
    }

    @Test(arguments: ["tr", "en"])
    func cachedFormattersMatchFreshOnes(language: String) {
        let locale = Locale(identifier: language)
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = locale
        calendar.timeZone = TimeZone(identifier: "Europe/Istanbul")!
        let now = DesignFixtures.referenceDate
        let offsets: [TimeInterval] = [-600, -86_400, -3 * 86_400, -40 * 86_400, -400 * 86_400]
        for offset in offsets {
            let date = now.addingTimeInterval(offset)
            let fresh = DateFormatter()
            fresh.locale = locale
            fresh.calendar = calendar
            fresh.timeZone = calendar.timeZone
            fresh.dateFormat = "HH:mm"
            #expect(U2Format.clockTime(date, locale: locale, calendar: calendar) == fresh.string(from: date))
            fresh.setLocalizedDateFormatFromTemplate("dMMM")
            #expect(
                U2Format.dayMonth(date, locale: locale, calendar: calendar)
                    == fresh.string(from: date).replacingOccurrences(of: ".", with: ""))
            // Twice: the second call is served from the cache.
            let first = U2Format.chatListTime(date, now: now, locale: locale, calendar: calendar)
            #expect(U2Format.chatListTime(date, now: now, locale: locale, calendar: calendar) == first)
        }
    }

    @Test func stressFixtureKeepsTheDesignChatsFirst() {
        let clock = U2Clock.frozen(at: DesignFixtures.referenceDate)
        let base = DesignFixtures.chats(now: clock.now, ghostUrgent: false, english: false)
        let feed = ChatsHarness.StressFeed(base: base, count: 500, clock: clock)
        #expect(feed.chats.count == 500)
        #expect(Array(feed.chats.prefix(base.count)) == base)
        #expect(Set(feed.chats.map(\.id)).count == 500)
        // A tick moves a chat to the top with one more unread message.
        let moved = feed.chats[2...9].map(\.id)
        feed.tick()
        #expect(moved.contains(feed.chats[0].id))
        #expect(feed.chats.count == 500)
    }
}
