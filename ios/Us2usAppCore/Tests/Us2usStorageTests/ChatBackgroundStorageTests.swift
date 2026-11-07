// us2us — AGPL-3.0-only

import Foundation
import LibSignalClient
import Testing
import Us2usKit

@testable import Us2usStorage

/// A chat's own background (schema v12, the same step as Android's v12): each
/// chat keeps its own, and it goes with the chat however that ends.
@Suite("A chat's own background")
struct ChatBackgroundStorageTests {
    private let photo = Data("JPEG-BYTES".utf8)

    private func rowCount(_ storage: LocalStorage) throws -> Int {
        try storage.database.locked { try $0.query("SELECT 1 FROM chat_backgrounds") }.count
    }

    private func makeCommunity(_ storage: LocalStorage, _ account: TestAccount) throws -> AccountId {
        let identity = try account.store.identityKeyPair(context: NullContext())
        let owner = CommunityMember(accountId: account.accountId, identityKey: identity.identityKey.serialize())
        let state = try CommunityState(
            id: AccountId(uuid: UUID()), revision: 1, isPublic: false, owner: owner, name: "", members: [owner])
        let signed = SignedCommunityState(
            state: state.bytes, signature: identity.privateKey.generateSignature(message: state.bytes))
        try storage.write { try $0.communities.save(signed, localName: "topluluk") }
        return state.id
    }

    @Test("v12 adds the table to a v11 database and keeps what was there")
    func migrationFromV11() throws {
        let environment = try TestEnvironment()
        try environment.layout.prepare()
        let dbKey = LocalKeyDerivation.databaseKey(dek: environment.dek)
        let contact = AccountId(uuid: UUID())
        do {
            let v11 = try EncryptedDatabase(
                url: environment.layout.databaseURL, key: dbKey, migrations: Array(Schema.migrations.prefix(11)))
            #expect(try v11.locked { try $0.scalar("PRAGMA user_version") } == .integer(11))
            _ = try PersistentProtocolStore.create(database: v11, identity: .generate(), registrationId: 5)
            try v11.locked {
                try $0.run(
                    "INSERT INTO contacts (account, request_state, created_at) VALUES (?, ?, ?)",
                    [contact.bytes, "accepted", Int64(1_700_000_000_000)])
            }
            v11.close()
        }
        let storage = try environment.open()
        #expect(try storage.database.locked { try $0.scalar("PRAGMA user_version") } == .integer(12))
        #expect(try storage.read { try $0.contacts.get(contact) } != nil)
        #expect(try rowCount(storage) == 0)
        #expect(try storage.read { try $0.chatBackgrounds.forContact(contact) } == nil)
    }

    @Test("each chat keeps its own, and nothing returns it to the app's")
    func perChat() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        let account = try TestAccount(storage: storage)
        let (first, _) = try makeContactWithConversation(storage)
        let (second, _) = try makeContactWithConversation(storage)
        let community = try makeCommunity(storage, account)

        try storage.write {
            try $0.chatBackgrounds.setForContact(.preset("sage"), first)
            try $0.chatBackgrounds.setForContact(.photo(photo), second)
            try $0.chatBackgrounds.setForCommunity(.preset("dusk"), community)
        }
        try storage.read { tx throws in
            #expect(try tx.chatBackgrounds.forContact(first) == .preset("sage"))
            #expect(try tx.chatBackgrounds.forContact(second) == .photo(photo))
            #expect(try tx.chatBackgrounds.forCommunity(community) == .preset("dusk"))
        }

        // A new choice replaces the old one in the same row, of either kind.
        try storage.write { try $0.chatBackgrounds.setForContact(.photo(photo), first) }
        #expect(try storage.read { try $0.chatBackgrounds.forContact(first) } == .photo(photo))
        try storage.write { try $0.chatBackgrounds.setForContact(.preset("rose"), second) }
        #expect(try storage.read { try $0.chatBackgrounds.forContact(second) } == .preset("rose"))
        #expect(try rowCount(storage) == 3)

        try storage.write { try $0.chatBackgrounds.setForContact(nil, first) }
        #expect(try storage.read { try $0.chatBackgrounds.forContact(first) } == nil)
        #expect(try storage.read { try $0.chatBackgrounds.forContact(second) } == .preset("rose"))
        #expect(try rowCount(storage) == 2)
    }

    @Test("it survives closing and opening the database")
    func survivesReopen() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        _ = try TestAccount(storage: storage)
        let (contact, _) = try makeContactWithConversation(storage)
        try storage.write { try $0.chatBackgrounds.setForContact(.photo(photo), contact) }
        storage.close()
        let reopened = try environment.open()
        #expect(try reopened.read { try $0.chatBackgrounds.forContact(contact) } == .photo(photo))
    }

    @Test("it goes with a wiped conversation, and a new chat starts with the app's")
    func goesWithWipe() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        _ = try TestAccount(storage: storage)
        let (contact, conversation) = try makeContactWithConversation(storage)
        let (other, _) = try makeContactWithConversation(storage)
        try storage.write {
            try $0.chatBackgrounds.setForContact(.photo(photo), contact)
            try $0.chatBackgrounds.setForContact(.preset("sand"), other)
        }
        _ = try storage.wipeConversation(conversation.id, reason: .requested)
        #expect(try rowCount(storage) == 1)
        #expect(try storage.read { try $0.chatBackgrounds.forContact(other) } == .preset("sand"))
        try storage.write { _ = try $0.conversations.ensure(for: contact) }
        #expect(try storage.read { try $0.chatBackgrounds.forContact(contact) } == nil)
    }

    @Test("it goes with a forgotten contact and a forgotten community")
    func goesWithForget() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        let account = try TestAccount(storage: storage)
        let (contact, _) = try makeContactWithConversation(storage)
        let community = try makeCommunity(storage, account)
        try storage.write {
            try $0.chatBackgrounds.setForContact(.preset("mist"), contact)
            try $0.chatBackgrounds.setForCommunity(.photo(photo), community)
        }
        try storage.write { try $0.contacts.forget(contact) }
        #expect(try rowCount(storage) == 1)
        try storage.write { try $0.communities.forget(community) }
        #expect(try rowCount(storage) == 0)
    }

    @Test("a choice outside the limits, or for nobody, stores nothing")
    func limits() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        _ = try TestAccount(storage: storage)
        let (contact, _) = try makeContactWithConversation(storage)
        let oversized = Data(count: ChatBackgroundChoice.maxPhotoBytes + 1)
        let tooLong = String(repeating: "x", count: ChatBackgroundChoice.maxPresetIdLength + 1)
        for choice in [ChatBackgroundChoice.photo(oversized), .photo(Data()), .preset(""), .preset(tooLong)] {
            #expect(throws: StorageError.self) {
                try storage.write { try $0.chatBackgrounds.setForContact(choice, contact) }
            }
        }
        #expect(throws: StorageError.self) {
            try storage.write { try $0.chatBackgrounds.setForContact(.preset("sage"), AccountId(uuid: UUID())) }
        }
        #expect(throws: StorageError.self) {
            try storage.write { try $0.chatBackgrounds.setForCommunity(.preset("sage"), AccountId(uuid: UUID())) }
        }
        #expect(try rowCount(storage) == 0)
    }
}
