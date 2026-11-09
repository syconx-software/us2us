import Foundation
import Testing

@testable import us2us

// App Review 1.2 in communities: blocking a member — from 5.3 › “Engelle”
// or the report sheet's “Bu kişiyi de engelle” — hides what they write in
// every community (the conversation, the 4.1 preview and badge); they stay
// a member. “Engeli kaldır” (7.1 › Engellenenler) shows it again. The live
// path hides in storage (Us2usStorageTests › CommunityBlockingStorageTests);
// here the screens' side on the design fixtures.

#if DEBUG
    @MainActor
    private struct BookClub {
        let fixtures: DesignFixturesService
        let communities: DesignFixtureCommunities
        let model: ConversationViewModel

        static let id = DesignFixtureCommunities.bookClub
        /// mert_k wrote c2 and c7; deniz c1 and c3; the user c4, c6, c8.
        static let mert = "2b3c4d5e-0000"

        init() {
            fixtures = DesignFixturesService(
                hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
            communities = fixtures.attachFixtureCommunities()
            model = ConversationViewModel(
                chatID: Self.id, messenger: CommunityChatMessenger(base: fixtures, communityID: Self.id))
        }

        var shown: [MessageID] { communities.conversation(Self.id).map(\.id) }
        var unread: Int? { communities.chatRows.first { $0.id == Self.id }?.unreadCount }

        func message(_ id: MessageID) throws -> Message {
            try #require(communities.conversation(Self.id).first { $0.id == id })
        }
    }

    @MainActor
    private final class AddressMailer: ReportMailing {
        func deliver(_ mail: ReportMail) async -> ReportDelivery { .addressShown }
    }

    @MainActor
    private func eventually(_ condition: @MainActor () -> Bool) async {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(3))
        while !condition(), clock.now < deadline { try? await Task.sleep(for: .milliseconds(20)) }
    }

    @Suite("Communities · blocked members are hidden")
    @MainActor
    struct CommunityBlockingTests {
        @Test("5.3 › Engelle: the member's messages leave every view; the others' and the user's own stay")
        func blockFromTheMenuHidesTheMember() async throws {
            let club = BookClub()
            #expect(club.shown == ["c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8"])
            #expect(club.unread == 2)
            let theirs = try club.message("c2")
            #expect(club.model.canBlockSender(of: theirs))
            #expect(!club.model.canBlockSender(of: try club.message("c4")), "not the user's own message")

            club.model.askBlockSender(of: "c2")
            #expect(club.model.blockCandidate == CommunityBlockCandidate(id: BookClub.mert, name: "mert_k"))
            await club.model.confirmBlockSender()

            #expect(club.model.blockCandidate == nil)
            #expect(club.fixtures.blockedPeople.map(\.id) == [BookClub.mert])
            #expect(club.shown == ["c1", "c3", "c4", "c5", "c6", "c8"])
            #expect(club.unread == 1, "only the stranger's message is still unread")
            #expect(club.communities.chatRows.first { $0.id == BookClub.id }?.lastMessage?.direction == .outgoing)
            // Still a member: the roster is untouched.
            #expect(club.communities.members(of: BookClub.id).contains { $0.id == BookClub.mert })
            // What is left of others can still be blocked; what is hidden offers nothing.
            #expect(club.model.canBlockSender(of: try club.message("c1")))
            #expect(!club.model.canBlockSender(of: theirs))
        }

        @Test("“Bu kişiyi de engelle” on a community report blocks the member who wrote it")
        func reportAlsoBlocksTheSender() async throws {
            let club = BookClub()
            club.model.reportMailer = AddressMailer()
            let report = try #require(club.model.makeReport(for: try club.message("c7")))
            #expect(report.offersBlock && report.alsoBlock)
            report.reason = .spam
            await report.send(locale: Locale(identifier: "tr"))
            await report.blocking?.value
            #expect(club.fixtures.blockedPeople.map(\.id) == [BookClub.mert])
            #expect(!club.shown.contains("c2") && !club.shown.contains("c7"))
            // A second report on the same member does not offer the block again.
            let again = try #require(club.model.makeReport(for: try club.message("c1")))
            #expect(again.offersBlock, "deniz is not blocked")
        }

        @Test("unblocking shows the member's messages again, unread as before")
        func unblockRestores() async throws {
            let club = BookClub()
            club.model.askBlockSender(of: "c2")
            await club.model.confirmBlockSender()
            let person = try #require(club.fixtures.blockedPeople.first)
            #expect(person.name == "mert_k")
            #expect(await club.fixtures.unblock(person))
            #expect(club.fixtures.blockedPeople.isEmpty)
            #expect(club.shown == ["c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8"])
            #expect(club.unread == 2)
        }

        @Test("blocking a 1:1 contact hides them in communities too; blocking the owner leaves the community as it is")
        func blockedContactAndOwner() async throws {
            let club = BookClub()
            // deniz is a chat of the user (1:1) and owns “Pazar koşusu”.
            #expect(await club.fixtures.block(chat: "deniz"))
            #expect(club.shown == ["c2", "c4", "c5", "c6", "c7", "c8"])
            let run = DesignFixtureCommunities.run
            #expect(club.communities.row(run)?.phase == .member)
            #expect(club.communities.members(of: run).first { $0.isOwner }?.username == "deniz")
            #expect(await club.fixtures.unblock(chat: "deniz"))
            #expect(club.shown.contains("c1") && club.shown.contains("c3"))
        }

        @Test("odd cases: an unknown sender, the user's own account and a message without a sender block nobody")
        func oddCases() async throws {
            let club = BookClub()
            // Not a member of any community here, and no chat: nothing to block.
            #expect(!(await club.fixtures.block(chat: "ffffffff-0000")))
            // The user is a member too, but never someone to block.
            #expect(!(await club.fixtures.block(chat: "self")))
            #expect(club.fixtures.blockedPeople.isEmpty)
            #expect(club.shown.count == 8)

            // A candidate whose block cannot be stored: the confirmation goes, nothing changes.
            club.model.blockCandidate = CommunityBlockCandidate(id: "ffffffff-0000", name: "Üye · ffffffff")
            await club.model.confirmBlockSender()
            #expect(club.model.blockCandidate == nil)
            #expect(club.fixtures.blockedPeople.isEmpty)

            // Asking about a message that is not there, or has no community sender, opens nothing.
            club.model.askBlockSender(of: "missing")
            #expect(club.model.blockCandidate == nil)
            let plain = Message(id: "p1", direction: .incoming, sentAt: .now, content: .text("x"))
            #expect(!club.model.canBlockSender(of: plain))

            // A one-to-one chat never offers the community block.
            let chat = ConversationViewModel(chatID: "deniz", messenger: club.fixtures)
            let incoming = try #require(club.fixtures.messages(in: "deniz").first { !$0.isOutgoing && !$0.isSystem })
            #expect(!chat.canBlockSender(of: incoming))
        }
    }
#endif
