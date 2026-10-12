import Foundation
import Testing

@testable import us2us

// 7.1 › Engellenenler: the live mapping of blocked contacts (also without a
// chat, also without a username), the fixtures' two kinds, and unblocking
// from the list — which must leave the person exactly as unblocking from
// the contact screen does.

private let peerID = "6f1c2a8e-41d2-4d55-9a0e-2f7c1b3d9e10"

@MainActor
private func makeService() -> DesignFixturesService {
    DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
}

// MARK: - Live mapping

@Suite("Live service · 7.1 › Engellenenler")
@MainActor
struct BlockedPeopleMappingTests {
    @Test func aBlockedContactIsListedByUsernameWithTheInitial() {
        let person = LiveMapping.blockedPerson(LiveBlockedRecord(peer: peerID, username: "deniz", displayName: "Deniz Y."))
        #expect(person.id == peerID, "unblocked by its account, chat or not")
        #expect(person.name == "deniz")
        #expect(person.isUsernameKnown)
        #expect(person.palette == .forUsername("deniz"))
        #expect(person.avatar == .initials, "no picture for someone blocked")
    }

    /// A Ghost wipe with `wipe_contact` keeps only the block: the list shows
    /// what 4.1 would (the display name, else a short account id) and says
    /// that the username is unknown.
    @Test func withoutAUsernameTheListShowsWhat41WouldAndSaysSo() {
        let named = LiveMapping.blockedPerson(LiveBlockedRecord(peer: peerID, username: nil, displayName: "Deniz Y."))
        #expect(named.name == "Deniz Y.")
        #expect(!named.isUsernameKnown)
        let bare = LiveMapping.blockedPerson(LiveBlockedRecord(peer: peerID, username: "", displayName: nil))
        #expect(bare.name == String(peerID.prefix(8)))
        #expect(!bare.isUsernameKnown)
    }

    @Test func theListIsByNameWithUnknownNamesLast() {
        let people = LiveMapping.blockedPeople([
            LiveBlockedRecord(peer: "c-\(peerID)", username: nil, displayName: nil),
            LiveBlockedRecord(peer: "b", username: "mert_k", displayName: nil),
            LiveBlockedRecord(peer: "a", username: "ayla_", displayName: nil),
            LiveBlockedRecord(peer: "d", username: "kuzey_dev", displayName: nil),
        ])
        #expect(people.map(\.name) == ["ayla_", "kuzey_dev", "mert_k", "c-6f1c2a"])
    }
}

// MARK: - Fixtures

@Suite("Fixtures · 7.1 › Engellenenler")
@MainActor
struct BlockedPeopleFixtureTests {
    @Test func nobodyIsBlockedInTheDesignsData() {
        #expect(makeService().blockedPeople.isEmpty)
    }

    @Test func theHarnessBlocksOneWithAChatAndOneWithout() throws {
        let service = makeService()
        service.loadBlockedPeople()
        #expect(service.blockedPeople.map(\.id) == ["bora", "lale"])
        let bora = try #require(service.chat("bora"))
        #expect(bora.peer.isBlocked, "bora's chat stays in 4.1, read only")
        #expect(service.blockedPeople.first?.palette == bora.peer.palette, "the list draws bora as 4.1 does")
        #expect(service.chat("lale") == nil, "lale's chat is gone")
        #expect(service.blockedPeople.allSatisfy { $0.avatar == .initials && $0.isUsernameKnown })
        service.loadBlockedPeople()
        #expect(service.blockedPeople.count == 2, "loading twice blocks nobody twice")
    }

    @Test func blockingFromTheContactScreenOrARequestListsThePerson() async throws {
        let service = makeService()
        #expect(await service.block(chat: "mert_k"))
        let request = try #require(service.requests.first { $0.id == "kuzey_dev" })
        await service.block(request)
        #expect(service.blockedPeople.map(\.id) == ["kuzey_dev", "mert_k"])
    }

    /// bora was an accepted contact: unblocked from the list, the chat is
    /// as before the block — the same as the contact screen's “Engeli kaldır”.
    @Test func unblockingSomeoneWithAChatFromTheList() async throws {
        let service = makeService()
        let before = try #require(service.chat("bora")).peer
        service.loadBlockedPeople()
        let bora = try #require(service.blockedPeople.first { $0.id == "bora" })
        #expect(await service.unblock(bora))
        #expect(service.blockedPeople.map(\.id) == ["lale"])
        #expect(service.chat("bora")?.peer == before, "accepted again, as before the block")

        // The contact screen's way gives the same result.
        let other = makeService()
        other.loadBlockedPeople()
        #expect(await other.unblock(chat: "bora"))
        #expect(other.chat("bora")?.peer == service.chat("bora")?.peer)
        #expect(other.blockedPeople == service.blockedPeople)
    }

    /// lale has no chat: unblocked, she leaves the list and nothing appears
    /// in 4.1 or 4.3 until she writes.
    @Test func unblockingSomeoneWithoutAChatFromTheList() async throws {
        let service = makeService()
        service.loadBlockedPeople()
        let chats = service.chats.map(\.id)
        let requests = service.requests.map(\.id)
        let lale = try #require(service.blockedPeople.first { $0.id == "lale" })
        #expect(await service.unblock(lale))
        #expect(service.blockedPeople.map(\.id) == ["bora"])
        #expect(service.chats.map(\.id) == chats)
        #expect(service.requests.map(\.id) == requests)
        #expect(!(await service.unblock(lale)), "a second tap changes nothing")
    }

    /// A blocked request unblocked from the list is a request again, as
    /// from its contact screen.
    @Test func unblockingABlockedRequestFromTheListMakesItARequestAgain() async throws {
        let service = makeService()
        let request = try #require(service.requests.first { $0.id == "kuzey_dev" })
        await service.block(request)
        let person = try #require(service.blockedPeople.first { $0.id == "kuzey_dev" })
        #expect(await service.unblock(person))
        #expect(service.blockedPeople.isEmpty)
        #expect(service.chat("kuzey_dev") == nil)
        #expect(service.requests.first?.id == "kuzey_dev")
    }

    /// 4.2 › “Mesaj yaz” for someone blocked without a chat brings the chat
    /// back, still blocked (as in the live app); unblocking it then restores
    /// the contact.
    @Test func writingToSomeoneBlockedWithoutAChatBringsTheBlockedChatBack() async throws {
        let service = makeService()
        service.loadBlockedPeople()
        #expect(await service.startChat(username: "lale") == "lale")
        let chat = try #require(service.chat("lale"))
        #expect(chat.peer.isBlocked)
        #expect(service.blockedPeople.map(\.id) == ["bora", "lale"], "listed once, now with its chat")
        #expect(await service.unblock(chat: "lale"))
        #expect(service.chat("lale")?.peer.isAcceptedContact == true)
        #expect(service.blockedPeople.map(\.id) == ["bora"])
    }

    @Test func erasingEverythingForgetsTheBlocks() async {
        let service = makeService()
        service.loadBlockedPeople()
        await service.eraseEverything()
        #expect(service.blockedPeople.isEmpty)
    }
}

// MARK: - Texts

@Suite("7.1 › Engellenenler · texts")
@MainActor
struct BlockedPeopleTextTests {
    @Test(arguments: ["settings.blocked", "blocked.empty", "blocked.footnote", "blocked.unknownUsername"])
    func everyTextIsInBothLanguages(_ key: String) {
        let turkish = StringTable.settings.string(String.LocalizationValue(key), locale: Locale(identifier: "tr_TR"))
        let english = StringTable.settings.string(String.LocalizationValue(key), locale: Locale(identifier: "en_TR"))
        #expect(turkish != key && english != key)
        #expect(turkish != english)
    }

    @Test func theEmptyStateSaysSoInTheAppsTone() {
        #expect(StringTable.settings.string("blocked.empty", locale: Locale(identifier: "tr_TR")) == "Engellediğin kimse yok.")
        #expect(StringTable.settings.string("blocked.empty", locale: Locale(identifier: "en_TR")) == "You haven't blocked anyone.")
    }
}
