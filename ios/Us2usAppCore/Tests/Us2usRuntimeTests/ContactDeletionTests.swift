import Foundation
import Testing
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime

/// “Sohbeti sil” and “Kişiyi sil” on this phone (§5.3, §6.4) — a deleted
/// contact is not blocked: if it writes again, it is a new request — and the
/// chat a username opens once its account was deleted and the name taken
/// again (§3.3).
@Suite("Deleting chats and contacts")
struct ContactDeletionTests {
    @Test("deleting a chat keeps the contact: writing again continues as before")
    func deletingAChatKeepsTheContact() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        _ = try await fixture.engine.send(.text("selam"), to: peer)
        try await fixture.engine.deleteConversationLocally(peer)
        #expect(try await !fixture.engine.chatSummaries().contains { $0.id == peer })
        #expect(try fixture.contact(peer)?.requestState == .accepted)
        #expect(try fixture.outbox().allSatisfy { $0.messageId == nil }, "the chat's pending messages went with it")
    }

    @Test("a chat deleted while sharing the location still stops the share on the other phone")
    func deletingAChatStopsTheLiveShare() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        let session = try await fixture.engine.startLiveLocation(
            peer: peer, latitude: 41.0, longitude: 29.0, accuracy: 5, duration: 3600)
        try await fixture.engine.deleteConversationLocally(peer)
        let stops = try fixture.outbox().filter { entry in
            guard entry.recipient == peer, case .data(let data) = entry.content.body,
                case .liveLocation(let live) = data.payload
            else { return false }
            return live.sessionID == session && live.stopped
        }
        #expect(stops.count == 1)
        #expect(stops.first?.conversationId == nil, "not bound to the deleted conversation")
        #expect(await fixture.engine.activeLiveLocationSessions().isEmpty)
    }

    @Test("a deleted contact found again by username is a new chat: an outgoing request with nothing of before")
    func deletedContactStartsFresh() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        fixture.backend.register("deniz", as: peer)
        await fixture.start()
        try await fixture.receive(.textMessage("eski mesaj"), from: peer)
        try await fixture.engine.deleteContact(peer)
        #expect(try fixture.contact(peer) == nil)
        #expect(try await fixture.engine.chatSummaries().isEmpty)
        #expect(try fixture.outbox().allSatisfy { $0.recipient != peer })
        #expect(try fixture.storage.read { try $0.contacts.formerName(of: peer) } == nil, "it never had a name here")

        let found = try await fixture.engine.startConversation(username: try NormalizedUsername("deniz"))
        #expect(found == peer)
        let contact = try #require(try fixture.contact(peer))
        #expect(contact.requestState == .outgoingRequest)
        #expect(contact.theirAccessKey == nil && contact.ourAccessKeySent == nil && contact.profileSentAt == nil)
        #expect(contact.profile.displayName == nil)
        #expect(try await fixture.messages(peer).isEmpty)
        await fixture.engine.stop()
    }

    @Test("deleting a blocked contact keeps only the block")
    func deletingABlockedContactKeepsTheBlock() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        try await fixture.receive(.textMessage("eski"), from: peer)
        try await fixture.engine.block(peer)
        try await fixture.engine.deleteContact(peer)
        let kept = try #require(try fixture.contact(peer))
        #expect(kept.isBlocked)
        #expect(kept.profile.displayName == nil)
        #expect(try await !fixture.engine.chatSummaries().contains { $0.id == peer })
        #expect(try await fixture.engine.blockedContacts().map(\.account) == [peer], "still in Engellenenler")
        await fixture.engine.stop()
    }

    @Test("an unknown contact cannot be deleted")
    func unknownContact() async throws {
        let fixture = try EngineFixture()
        await #expect(throws: (any Error).self) { try await fixture.engine.deleteContact(AccountId(uuid: UUID())) }
    }

    @Test("a contact that deleted us and writes as a new request gets our ACCEPT, access key and profile again")
    func acceptedContactWritingAsARequestIsAcceptedAgain() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        // Only an OUTGOING_REQUEST carries `sender_username` (§6.4).
        try await fixture.receive(.textMessage("selam, yeniden"), from: peer, username: "deniz")
        try await eventually("ACCEPT, access key and profile sent") {
            let sent = fixture.backend.sent(to: peer)
            return sent.contains(where: \.isAccept) && sent.contains(where: \.isAccessKeyUpdate)
                && sent.contains(where: \.isProfile)
        }
        let sent = fixture.backend.sent(to: peer)
        let accept = try #require(sent.firstIndex { $0.isAccept })
        let key = try #require(sent.firstIndex { $0.isAccessKeyUpdate })
        let profile = try #require(sent.firstIndex { $0.isProfile })
        #expect(accept < key && key < profile, "ACCEPT first, then the access key, then the profile")
        #expect(try fixture.contact(peer)?.requestState == .accepted)
        #expect(try await fixture.messages(peer).count == 1)

        // Without `sender_username` nothing is sent again.
        try await fixture.receive(.textMessage("devam"), from: peer)
        await settle()
        #expect(fixture.backend.sent(to: peer).filter(\.isAccept).count == 1)
        await fixture.engine.stop()
    }

    @Test("a deleted contact that writes again opens a request under its former name; accepting continues the chat")
    func deletedContactWritingAgainIsARequest() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        try fixture.storage.write { tx in try tx.contacts.confirmUsername(try NormalizedUsername("deniz"), for: peer) }
        await fixture.start()
        try await fixture.receive(.textMessage("eski"), from: peer)
        try await fixture.engine.deleteContact(peer)
        #expect(try fixture.contact(peer) == nil)
        #expect(try fixture.storage.read { try $0.contacts.formerName(of: peer) }?.value == "deniz")
        let sentBefore = fixture.backend.sent(to: peer).count

        // It still holds us as accepted: no `sender_username`, its old session.
        try await fixture.receive(.textMessage("ben yine"), from: peer)
        let contact = try #require(try fixture.contact(peer))
        #expect(contact.requestState == .incomingRequest)
        #expect(contact.profile.username?.value == "deniz")
        #expect(contact.profile.usernameStatus == .unchecked, "a claim until the directory is asked again")
        #expect(contact.theirAccessKey == nil && contact.ourAccessKeySent == nil && contact.profile.displayName == nil)
        let chat = try #require(try await fixture.engine.chatSummaries().first { $0.id == peer })
        #expect(chat.isRequest)
        #expect(chat.contact.username == "deniz")
        #expect(try await fixture.messages(peer).map(\.body) == [.text("ben yine")], "nothing of before")
        await settle()
        #expect(fixture.backend.sent(to: peer).count == sentBefore, "a request is not answered (no receipt)")

        try await fixture.engine.acceptRequest(peer)
        #expect(try fixture.contact(peer)?.requestState == .accepted)
        #expect(fixture.backend.sent(to: peer).dropFirst(sentBefore).first?.isAccept == true)
        try await eventually("our access key follows") {
            fixture.backend.sent(to: peer).dropFirst(sentBefore).contains { $0.isAccessKeyUpdate }
        }
        _ = try await fixture.engine.send(.text("hoş geldin"), to: peer)
        try await eventually("the reply is sent") {
            fixture.backend.sent(to: peer).contains { $0.data.flatMap { data -> String? in
                if case .text(let text) = data.payload { return text.body } else { return nil }
            } == "hoş geldin" }
        }
        await fixture.engine.stop()
    }

    @Test("a deleted contact under a new identity key opens its request flagged as changed")
    func returningContactWithANewKeyIsFlagged() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        try await fixture.engine.deleteContact(peer)
        try await fixture.receive(.textMessage("yeni telefon"), from: peer, identityChanged: true)
        #expect(try fixture.contact(peer)?.requestState == .incomingRequest)
        let items = try await fixture.messages(peer)
        #expect(items.contains { $0.body == .notice(.safetyNumberChanged) })
        await fixture.engine.stop()
    }

    @Test("a deleted blocked contact that writes again stays blocked and nothing is stored")
    func deletedBlockedContactStaysBlocked() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        try await fixture.engine.block(peer)
        try await fixture.engine.deleteContact(peer)
        try await fixture.receive(.textMessage("açar mısın"), from: peer)
        try await fixture.receive(.textMessage("bir istek"), from: peer, username: "deniz")
        #expect(try fixture.contact(peer)?.isBlocked == true)
        #expect(try await !fixture.engine.chatSummaries().contains { $0.id == peer })
        #expect(try await fixture.engine.blockedContacts().map(\.account) == [peer])
        await fixture.engine.stop()
    }

    @Test("ACCEPT from a contact we are accepted with sends our access key and profile again, once")
    func acceptFromAnAcceptedContactResendsKeyAndProfile() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        // It had deleted us and accepted the request our message became there.
        try await fixture.receive(.messageRequestResponse(.accept), from: peer)
        try await eventually("access key and profile sent again") {
            let sent = fixture.backend.sent(to: peer)
            return sent.contains(where: \.isAccessKeyUpdate) && sent.contains(where: \.isProfile)
        }
        await settle()
        let sent = fixture.backend.sent(to: peer)
        #expect(sent.filter(\.isAccessKeyUpdate).count == 1 && sent.filter(\.isProfile).count == 1)
        let key = try #require(sent.firstIndex { $0.isAccessKeyUpdate })
        let profile = try #require(sent.firstIndex { $0.isProfile })
        #expect(key < profile)
        #expect(!sent.contains { $0.isAccept }, "never answered with another ACCEPT")
        #expect(try fixture.contact(peer)?.requestState == .accepted)
        await fixture.engine.stop()
    }

    @Test("ACCEPT that crosses an exchange still under way sends nothing extra")
    func acceptDuringTheExchangeChangesNothing() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        try fixture.storage.write { tx in try tx.contacts.setProfileSent(at: nil, to: peer) }
        try fixture.storage.write { tx in try tx.contacts.setTheirAccessKey(nil, for: peer) }
        await fixture.start()
        try await fixture.receive(.messageRequestResponse(.accept), from: peer)
        await settle()
        #expect(fixture.backend.sent(to: peer).isEmpty)
        await fixture.engine.stop()
    }

    @Test("a username taken by another account opens that account; the old chat no longer shows the name")
    func usernameTakenOverOpensTheNewAccount() async throws {
        let fixture = try EngineFixture()
        let old = try fixture.acceptedContact()
        try fixture.storage.write { tx in try tx.contacts.confirmUsername(try NormalizedUsername("deniz"), for: old) }
        #expect(try await fixture.engine.contactInfo(old)?.username == "deniz")
        // The account was deleted and someone else registered the name.
        let new = AccountId(uuid: UUID())
        fixture.backend.register("deniz", as: new)

        let opened = try await fixture.engine.startConversation(username: try NormalizedUsername("deniz"))
        #expect(opened == new)
        let chats = try await fixture.engine.chatSummaries()
        #expect(Set(chats.map(\.id)) == [old, new])
        #expect(chats.first { $0.id == new }?.contact.username == "deniz")
        let oldInfo = try #require(chats.first { $0.id == old }?.contact)
        #expect(oldInfo.username == nil, "a name the directory gives to another account is never shown for this one")
        #expect(oldInfo.displayName == "Peer")
        #expect(try fixture.contact(old)?.requestState == .accepted, "the old chat itself stays")
    }
}
