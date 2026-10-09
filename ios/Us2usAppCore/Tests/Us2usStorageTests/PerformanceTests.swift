// us2us — AGPL-3.0-only

import Foundation
import Testing
import Us2usKit

@testable import Us2usStorage

/// 10 000 messages in one conversation: storing, paging, counting, expiring,
/// wiping and reopening stay fast. Budgets are generous for a Debug build on
/// the Simulator; the measured times are printed.
@Suite("Performance with 10k messages", .serialized)
struct PerformanceTests {
    private func measure<T>(_ label: String, budget: Duration, _ body: () throws -> T) rethrows -> T {
        let clock = ContinuousClock()
        var result: T?
        let elapsed = try clock.measure { result = try body() }
        let milliseconds = Double(elapsed.components.seconds) * 1000 + Double(elapsed.components.attoseconds) / 1e15
        print("[perf] \(label): \(Int(milliseconds.rounded())) ms")
        #expect(elapsed < budget, "\(label) took \(elapsed)")
        return result!
    }

    @Test func tenThousandMessages() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open(now: t0)
        _ = try TestAccount(storage: storage)
        let (_, conversation) = try makeContactWithConversation(storage, now: t0)
        let total = 10_000
        let batch = 500

        try measure("store 10k messages (20 transactions)", budget: .seconds(30)) {
            for start in stride(from: 0, to: total, by: batch) {
                try storage.write { tx throws in
                    for index in start..<(start + batch) {
                        let timer: UInt32 = index % 10 == 0 ? 60 : 0
                        let message = textMessage(
                            "Mesaj \(index): " + String(repeating: "lorem ipsum ", count: 8), timer: timer)
                        let time = t0.addingTimeInterval(Double(index))
                        if index.isMultiple(of: 2) {
                            try tx.messages.storeIncoming(message, in: conversation.id, receivedAt: time)
                        } else {
                            try tx.messages.storeOutgoing(message, in: conversation.id, at: time)
                        }
                    }
                }
            }
        }
        #expect(try storage.read { try $0.messages.count(in: conversation.id) } == total)

        let now = t0.addingTimeInterval(Double(total))
        let page = try measure("latest page of 50", budget: .milliseconds(250)) {
            try storage.read { try $0.messages.page(in: conversation.id, limit: 50, now: now) }
        }
        #expect(page.count == 50)
        try measure("scroll back 20 pages", budget: .seconds(3)) {
            var cursor = page.first?.localId
            for _ in 0..<20 {
                let older = try storage.read {
                    try $0.messages.page(in: conversation.id, before: cursor, limit: 50, now: now)
                }
                cursor = older.first?.localId
            }
        }
        _ = try measure("unread count", budget: .milliseconds(250)) {
            try storage.read { try $0.conversations.unreadCount(conversation.id) }
        }
        let displayed = try measure("mark 5k displayed", budget: .seconds(10)) {
            try storage.write { try $0.messages.markAllDisplayed(in: conversation.id, at: now) }
        }
        #expect(displayed.count == total / 2)

        let report = try measure("expire 1k disappearing messages", budget: .seconds(10)) {
            try storage.performDueWipes(now: now.addingTimeInterval(t0.distance(to: now) + 120))
        }
        #expect(report.expiredMessages[conversation.id]?.count == total / 10)

        storage.close()
        let reopened = try measure("reopen (incl. maintenance and GC)", budget: .seconds(5)) {
            try environment.open(now: now)
        }
        _ = try measure("wipe the conversation (9k messages)", budget: .seconds(10)) {
            try reopened.wipeConversation(conversation.id, reason: .requested)
        }
        #expect(try reopened.read { try $0.conversations.all() }.isEmpty)
    }
}
