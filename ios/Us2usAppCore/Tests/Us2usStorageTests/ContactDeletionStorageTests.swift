// us2us — AGPL-3.0-only

import Foundation
import LibSignalClient
import Testing
import Us2usKit

@testable import Us2usStorage

/// Deleting a contact on this phone (“Kişiyi sil”, §5.3 / §6.4): only its
/// session, identity and former name stay, so it can write again as a new
/// request; “leave no trace” (`forget`) keeps nothing; nothing of anyone
/// else goes with either.
@Suite("Deleting a contact")
struct ContactDeletionStorageTests {
    private func count(_ table: String, _ storage: LocalStorage) throws -> Int {
        try storage.database.locked { try $0.query("SELECT 1 FROM \(table)") }.count
    }

    @Test("forgetting a contact leaves no rows, no key slot, no files, no session and no identity")
    func forgetLeavesNothing() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open(now: t0)
        let account = try TestAccount(storage: storage)
        let peer = Party.inMemory()
        try Party(account: account.accountId, store: account.store).cipher.processBundle(try peer.bundle())
        let (contact, conversation) = try makeContactWithConversation(storage, contact: peer.account, now: t0)
        let fileId = try storage.write { tx -> Data in
            try tx.messages.storeIncoming(textMessage("GIZLI-METIN"), in: conversation.id)
            var data = BRPDataMessage(payload: .attachment(sampleAttachment()))
            data.messageID = RandomId.make()
            try tx.messages.storeIncoming(data, in: conversation.id)
            let attachment = try #require(try tx.attachments.forMessage(data.messageID, in: conversation.id).first)
            try tx.attachments.storeFile(Data("GIZLI-DOSYA".utf8), for: attachment.id)
            // A pending send bound to the conversation and one without it (a receipt).
            let outgoing = textMessage("GIDEN")
            _ = try tx.messages.storeOutgoing(outgoing, in: conversation.id, at: t0)
            try tx.outbox.enqueue(
                BRPContent(data: outgoing), to: contact, conversation: conversation.id, messageId: outgoing.messageID,
                now: t0)
            try tx.outbox.enqueue(BRPContent(receipt: .delivery, messageIds: [RandomId.make()]), to: contact, now: t0)
            var profile = ContactProfile()
            profile.displayName = "Deniz"
            try tx.contacts.setProfile(profile, for: contact)
            try tx.contacts.confirmUsername(try NormalizedUsername("deniz"), for: contact)
            return attachment.id
        }
        // Someone else, who must not be touched.
        let (other, otherConversation) = try makeContactWithConversation(storage, now: t0)
        try storage.write { tx in try tx.messages.storeIncoming(textMessage("kalsin"), in: otherConversation.id) }
        #expect(try account.store.loadSession(for: contact.protocolAddress, context: NullContext()) != nil)
        #expect(try account.store.identityTrust(for: contact) != .unknown)

        try storage.write { tx in try tx.contacts.forget(contact) }
        try storage.database.checkpoint()

        #expect(try storage.read { try $0.contacts.get(contact) } == nil)
        #expect(try storage.read { try $0.conversations.forContact(contact) } == nil)
        #expect(!FileManager.default.fileExists(atPath: storage.keyring.slotURL(conversation.slotId).path))
        #expect(throws: StorageError.conversationShredded) { try storage.keyring.key(for: conversation.slotId) }
        #expect(!storage.files.exists(fileId))
        #expect(try account.store.loadSession(for: contact.protocolAddress, context: NullContext()) == nil)
        #expect(try account.store.identityTrust(for: contact) == .unknown)
        for table in ["sessions", "identities", "outbox", "attachments", "reactions"] {
            #expect(try count(table, storage) == 0, "\(table) still holds rows")
        }
        #expect(try count("contacts", storage) == 1)
        #expect(try count("conversations", storage) == 1)
        #expect(try count("messages", storage) == 1, "only the other contact's message")
        #expect(!environment.databaseBytes().contains(Data("GIZLI".utf8)))
        #expect(try storage.read { try $0.contacts.get(other) } != nil)
    }

    @Test("adding the same account again starts from nothing")
    func addingAgainIsFresh() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open(now: t0)
        _ = try TestAccount(storage: storage)
        let (contact, conversation) = try makeContactWithConversation(storage, now: t0)
        try storage.write { tx in
            try tx.messages.storeIncoming(textMessage("eski"), in: conversation.id)
            try tx.contacts.setTheirAccessKey(Data(repeating: 1, count: 16), for: contact)
        }
        try storage.write { tx in try tx.contacts.forget(contact) }

        let fresh = try storage.write { tx -> Conversation in
            try tx.contacts.prepareToSend(to: contact, now: t0.addingTimeInterval(60))
            return try tx.conversations.ensure(for: contact, now: t0.addingTimeInterval(60))
        }
        #expect(fresh.id != conversation.id)
        #expect(fresh.slotId != conversation.slotId)
        let again = try #require(try storage.read { try $0.contacts.get(contact) })
        #expect(again.requestState == .outgoingRequest)
        #expect(again.theirAccessKey == nil)
        #expect(try storage.read { try $0.messages.page(in: fresh.id, limit: 50) }.isEmpty)
    }

    @Test("a username our lookup found elsewhere is a mismatch for every other contact claiming it")
    func usernameOwnerMarksOthers() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open(now: t0)
        _ = try TestAccount(storage: storage)
        let name = try NormalizedUsername("deniz")
        let (old, _) = try makeContactWithConversation(storage, now: t0)
        let (unrelated, _) = try makeContactWithConversation(storage, now: t0)
        let (current, _) = try makeContactWithConversation(storage, now: t0)
        try storage.write { tx in
            try tx.contacts.confirmUsername(name, for: old)
            try tx.contacts.confirmUsername(try NormalizedUsername("mert"), for: unrelated)
            try tx.contacts.noteUsernameOwner(name, owner: current)
        }
        let contacts = try storage.read { tx in try [old, unrelated].map { try #require(try tx.contacts.get($0)) } }
        #expect(contacts[0].profile.usernameStatus == .mismatch)
        #expect(contacts[0].profile.username == name, "the claim stays, it is just not this contact's")
        #expect(contacts[1].profile.usernameStatus == .verified)
    }

    /// Both sides hold a session: the peer wrote first (PreKey message) and
    /// got an answer, so what it sends next is a plain session message.
    private func establishedPair(_ storage: LocalStorage) throws -> (us: Party, peer: Party, account: TestAccount) {
        let account = try TestAccount(storage: storage)
        let us = Party(account: account.accountId, store: account.store)
        let peer = Party.inMemory()
        try peer.cipher.processBundle(try us.bundle())
        #expect(try peer.send(.text("merhaba"), to: us).content.textBody == "merhaba")
        #expect(try us.send(.text("selam"), to: peer).content.textBody == "selam")
        return (us, peer, account)
    }

    @Test("a deleted contact's next message on its old session decrypts and opens a request under its former name")
    func deletedContactWritesAgain() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open(now: t0)
        let (us, peer, account) = try establishedPair(storage)
        let (contact, conversation) = try makeContactWithConversation(storage, contact: peer.account, now: t0)
        try storage.write { tx in
            try tx.messages.storeIncoming(textMessage("GIZLI-ESKI"), in: conversation.id)
            try tx.contacts.setTheirAccessKey(Data(repeating: 1, count: 16), for: contact)
            try tx.contacts.confirmUsername(try NormalizedUsername("deniz"), for: contact)
            try tx.outbox.enqueue(BRPContent(receipt: .delivery, messageIds: [RandomId.make()]), to: contact, now: t0)
        }
        let trust = try account.store.identityTrust(for: contact)

        try storage.write { tx in try tx.contacts.removeKeepingSession(contact) }
        try storage.database.checkpoint()
        #expect(try storage.read { try $0.contacts.get(contact) } == nil)
        #expect(try storage.read { try $0.conversations.forContact(contact) } == nil)
        #expect(throws: StorageError.conversationShredded) { try storage.keyring.key(for: conversation.slotId) }
        #expect(try count("outbox", storage) == 0)
        #expect(!environment.databaseBytes().contains(Data("GIZLI".utf8)))
        #expect(try account.store.identityTrust(for: contact) == trust, "the identity stays")
        #expect(try storage.read { try $0.contacts.formerName(of: contact) }?.value == "deniz")

        // It still holds us as accepted and writes on the session it has.
        let decrypted = try peer.send(.text("ben yine"), to: us)
        #expect(decrypted.content.textBody == "ben yine")
        #expect(decrypted.sender == peer.account)
        let state = try storage.write { tx in try tx.contacts.noteVerifiedMessage(decrypted.content, from: contact, now: t0) }
        #expect(state == .incomingRequest)
        let back = try #require(try storage.read { try $0.contacts.get(contact) })
        #expect(back.profile.username?.value == "deniz")
        #expect(back.profile.usernameStatus == .unchecked)
        #expect(back.theirAccessKey == nil && back.profile.displayName == nil)
        #expect(back.identity == trust)
        #expect(try storage.read { try $0.contacts.formerName(of: contact) } == nil, "taken back, not kept twice")
        let fresh = try storage.write { tx in try tx.conversations.ensure(for: contact, now: t0) }
        #expect(fresh.id != conversation.id)
        #expect(try storage.read { try $0.messages.page(in: fresh.id, limit: 50) }.isEmpty)
    }

    @Test("a deleted contact that comes back under a new identity key is flagged as changed, not trusted anew")
    func deletedContactWithANewKeyIsFlagged() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open(now: t0)
        let (us, peer, account) = try establishedPair(storage)
        let (contact, _) = try makeContactWithConversation(storage, contact: peer.account, now: t0)
        guard case .trusted(let before, _) = try account.store.identityTrust(for: contact) else {
            Issue.record("no trusted key")
            return
        }
        try storage.write { tx in try tx.contacts.removeKeepingSession(contact) }

        // Same account, new identity key (a new phone): a new PreKey session.
        let renewed = Party.inMemory(account: peer.account)
        try renewed.cipher.processBundle(try us.bundle())
        let decrypted = try renewed.send(.text("yeni telefon"), to: us)
        #expect(decrypted.content.textBody == "yeni telefon")
        #expect(decrypted.identityChanged)
        try storage.write { tx in _ = try tx.contacts.noteVerifiedMessage(decrypted.content, from: contact, now: t0) }
        let back = try #require(try storage.read { try $0.contacts.get(contact) })
        #expect(back.requestState == .incomingRequest)
        #expect(back.identity == .changed(previous: before, current: renewed.identity.identityKey))
        #expect(back.identityChanged)
    }

    @Test("the former name goes with “leave no trace”, a bare block, or a lookup finding the name elsewhere")
    func formerNameIsNotKept() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open(now: t0)
        _ = try TestAccount(storage: storage)
        let name = try NormalizedUsername("deniz")
        func deleted() throws -> AccountId {
            let (contact, _) = try makeContactWithConversation(storage, now: t0)
            try storage.write { tx in
                try tx.contacts.confirmUsername(name, for: contact)
                try tx.contacts.removeKeepingSession(contact)
            }
            #expect(try storage.read { try $0.contacts.formerName(of: contact) } == name)
            return contact
        }
        let forgotten = try deleted()
        try storage.write { tx in try tx.contacts.forget(forgotten) }
        #expect(try storage.read { try $0.contacts.formerName(of: forgotten) } == nil)

        let blocked = try deleted()
        try storage.write { tx in
            try tx.contacts.block(blocked, now: t0)
            try tx.contacts.stripToBlock(blocked)
        }
        #expect(try storage.read { try $0.contacts.formerName(of: blocked) } == nil)
        #expect(try storage.read { try $0.contacts.get(blocked) }?.profile.username == nil)

        let renamed = try deleted()
        try storage.write { tx in try tx.contacts.noteUsernameOwner(name, owner: AccountId(uuid: UUID())) }
        #expect(try storage.read { try $0.contacts.formerName(of: renamed) } == nil)

        // A name that is already a mismatch is never kept.
        let (mismatched, _) = try makeContactWithConversation(storage, now: t0)
        try storage.write { tx in
            try tx.contacts.recordUsernameCheck(name, owner: nil, for: mismatched)
            try tx.contacts.removeKeepingSession(mismatched)
        }
        #expect(try storage.read { try $0.contacts.formerName(of: mismatched) } == nil)
    }
}
