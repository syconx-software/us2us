import Foundation
import Testing
import Us2usKit
import Us2usStorage
@testable import Us2usRuntime

@Suite("Local message history pagination")
struct MessageHistoryTests {
    private func seed(_ count: Int, fixture: EngineFixture) throws -> AccountId {
        let peer = try fixture.acceptedContact()
        try fixture.storage.write { tx in
            let conversation = try #require(try tx.conversations.forContact(peer))
            for index in 0..<count {
                var data = BRPContent.textMessage("history-\(index)").data
                data.sentAt = UInt64(count-index)
                try tx.messages.storeIncoming(data, in: conversation.id, receivedAt: fixture.clock.now())
            }
        }
        return peer
    }

    @Test func pagesReachBeforeFiveThousandWithoutGapsOrDuplicates() async throws {
        let fixture = try EngineFixture()
        let peer = try seed(5107, fixture: fixture)
        let other = try seed(2, fixture: fixture)
        var page = try await fixture.engine.history(of: peer)
        #expect(page.items.count == 100 && page.hasOlder)
        var seen: [String] = []
        while true {
            seen.insert(contentsOf: page.items.compactMap { if case .text(let text) = $0.body { text } else { nil } }, at: 0)
            if !page.hasOlder { break }
            page = try await fixture.engine.history(of: peer, before: page.oldest)
        }
        #expect(seen == (0..<5107).map { "history-\($0)" })
        #expect(Set(seen).count == 5107)
        #expect(try await fixture.engine.history(of: other).items.count == 2)
        #expect(try await fixture.engine.messages(of: peer).count == 500)
        await fixture.engine.stop()
    }

    @Test func refreshPreservesWindowAndDeletedBoundaryWhileMessagesArrive() async throws {
        let fixture = try EngineFixture()
        let peer = try seed(350, fixture: fixture)
        let first = try await fixture.engine.history(of: peer)
        let older = try await fixture.engine.history(of: peer, before: first.oldest)
        let floor = try #require(older.oldest)
        try fixture.storage.write { tx in
            let conversation = try #require(try tx.conversations.forContact(peer))
            try tx.messages.deleteLocally([older.items[0].messageId, older.items[10].messageId], in: conversation.id, at: fixture.clock.now())
            try tx.messages.storeIncoming(BRPContent.textMessage("new arrival").data, in: conversation.id, receivedAt: fixture.clock.now())
        }
        let window = try await fixture.engine.history(of: peer, through: floor)
        #expect(window.items.count == 199)
        #expect(window.items.last?.body == .text("new arrival"))
        #expect(!window.items.contains { $0.messageId == older.items[0].messageId })
        let next = try await fixture.engine.history(of: peer, before: floor)
        #expect(next.items.last?.body == .text("history-149"))
        await fixture.engine.stop()
    }

    @Test func deletingAllHistoryCannotHideNewMessagesWhenSQLiteReusesIds() async throws {
        let fixture = try EngineFixture()
        let peer = try seed(350, fixture: fixture)
        let floor = try #require(try await fixture.engine.history(of: peer).oldest)
        try fixture.storage.write { tx in
            let conversation = try #require(try tx.conversations.forContact(peer))
            let ids = try tx.messages.page(in: conversation.id, limit: 1000, now: fixture.clock.now()).map(\.messageId)
            try tx.messages.deleteLocally(ids, in: conversation.id, at: fixture.clock.now())
            try tx.messages.storeIncoming(BRPContent.textMessage("after deletion").data, in: conversation.id, receivedAt: fixture.clock.now())
        }
        let window = try await fixture.engine.history(of: peer, through: floor)
        #expect(window.items.map(\.body) == [.text("after deletion")])
        #expect(!window.hasOlder)
        await fixture.engine.stop()
    }

    @Test func expiryIsEnforcedInEveryHistoryWindow() async throws {
        let fixture = try EngineFixture()
        let peer = try seed(120, fixture: fixture)
        let first = try await fixture.engine.history(of: peer)
        try fixture.storage.write { tx in
            let conversation = try #require(try tx.conversations.forContact(peer))
            let data = BRPContent.textMessage("expired", expire: 60).data
            try tx.messages.storeOutgoing(data, in: conversation.id, at: fixture.clock.now())
        }
        fixture.clock.advance(by: 600)
        let window = try await fixture.engine.history(of: peer, through: first.oldest)
        #expect(!window.items.contains { $0.body == .text("expired") })
        #expect(try await fixture.engine.history(of: peer, before: first.oldest).items.count == 20)
        await fixture.engine.stop()
    }
}
