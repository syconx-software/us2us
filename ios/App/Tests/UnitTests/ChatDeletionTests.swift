import SwiftUI
import Testing

@testable import us2us

/// “Sohbeti sil” / “Kişiyi sil” (4.1 long press, the contact screen's “…”)
/// and 4.2 opening the chat of the account a username belongs to now — not
/// an old chat that only shows the same name (PROTOCOL §3.3).

@MainActor
private func makeService() -> DesignFixturesService {
    DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
}

@MainActor
private func found(_ model: NewChatModel, _ name: String) async throws -> ChatID? {
    model.query = name
    model.submit()
    for _ in 0..<300 {
        if case .found(_, let existing) = model.phase { return existing }
        try await Task.sleep(for: .milliseconds(10))
    }
    Issue.record("no result for \(name): \(model.phase)")
    return nil
}

@MainActor
@Suite("04 · A username taken by another account")
struct UsernameReuseTests {
    @Test("“Mesaj yaz” opens the account the lookup found, not the old chat showing the same name")
    func theLiveAccountIsOpened() async throws {
        let service = makeService()
        #expect(service.chat("mert_k") != nil, "the old chat, still showing mert_k")
        // mert_k's account was deleted; someone else registered the name.
        service.directory["mert_k"] = "mert_k_yeni"
        let model = NewChatModel(messenger: service, debounce: .milliseconds(10), avatarSettle: .milliseconds(10))
        #expect(try await found(model, "mert_k") == nil, "no chat with the new account yet")

        let opened = try #require(await model.startChat())
        #expect(opened == "mert_k_yeni")
        #expect(service.chat("mert_k") != nil, "the old chat stays as it was")
        #expect(service.chats.filter { $0.peer.username == "mert_k" }.count == 2)

        // Searched again, the name leads to the new chat.
        let again = NewChatModel(messenger: service, debounce: .milliseconds(10), avatarSettle: .milliseconds(10))
        #expect(try await found(again, "mert_k") == "mert_k_yeni")
        #expect(await again.startChat() == "mert_k_yeni")
    }

    @Test("an existing chat found by account is opened without starting another")
    func theExistingChatIsOpened() async throws {
        let service = makeService()
        let model = NewChatModel(messenger: service, debounce: .milliseconds(10), avatarSettle: .milliseconds(10))
        let count = service.chats.count
        #expect(try await found(model, "mert_k") == "mert_k")
        #expect(await model.startChat() == "mert_k")
        #expect(service.chats.count == count)
    }
}

@MainActor
@Suite("04 · Deleting a chat or a contact")
struct ChatDeletionTests {
    @Test("deleting a chat removes it with its messages; 4.2 then starts it again")
    func deleteChat() async throws {
        let service = makeService()
        #expect(!service.messages(in: "mert_k").isEmpty)
        #expect(await service.deleteChat("mert_k"))
        #expect(service.chat("mert_k") == nil)
        #expect(service.messages(in: "mert_k").isEmpty)
        #expect(await service.startChat(username: "mert_k") == "mert_k")
        #expect(service.messages(in: "mert_k").isEmpty, "nothing of before comes back")
    }

    @Test("deleting a blocked contact keeps the block in Engellenenler")
    func deleteBlockedContact() async throws {
        let service = makeService()
        #expect(await service.block(chat: "bora"))
        #expect(await service.deleteContact("bora"))
        #expect(service.chat("bora") == nil)
        #expect(service.blockedPeople.contains { $0.id == "bora" })
    }

    @Test("an unknown chat deletes nothing")
    func nothingToDelete() async {
        let service = makeService()
        #expect(await !service.deleteChat("nobody_here"))
        #expect(await !service.deleteContact("nobody_here"))
    }

    @Test("the confirmation names the person and says what stays")
    func confirmationTexts() {
        let peer = Peer(username: "deniz", palette: .a, isVerified: false, isAcceptedContact: true, presence: .hidden)
        let chat = ChatDeletion(.chat, chat: "deniz", peer: peer)
        let contact = ChatDeletion(.contact, chat: "deniz", peer: peer)
        var blockedPeer = peer
        blockedPeer.isBlocked = true
        let blocked = ChatDeletion(.contact, chat: "deniz", peer: blockedPeer)
        #expect(chat.name == "deniz" && !chat.isBlocked)
        #expect(chat.id != contact.id)
        #expect(blocked.isBlocked)
        #expect(contact.message != blocked.message)
    }

    @Test("every delete text exists in Turkish and English")
    func catalogue() throws {
        let strings = try #require(try DesignSpec.catalog()["strings"] as? [String: Any])
        for key in [
            "delete.chat", "delete.contact", "delete.chat.title %@", "delete.chat.message", "delete.contact.title %@",
            "delete.contact.message", "delete.contact.message.blocked", "a11y.chatDeleted", "a11y.contactDeleted",
        ] {
            let entry = try #require(strings[key] as? [String: Any], "\(key) is missing")
            let localizations = try #require(entry["localizations"] as? [String: Any])
            #expect(localizations["tr"] != nil && localizations["en"] != nil, "\(key) needs tr and en")
        }
    }
}
