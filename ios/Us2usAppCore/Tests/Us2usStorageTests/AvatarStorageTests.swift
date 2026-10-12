// us2us — AGPL-3.0-only

import Foundation
import Testing
import Us2usKit

@testable import Us2usStorage

@Suite("Contact pictures in the encrypted store (§8.9)")
struct AvatarStorageTests {
    @Test("a contact's picture is stored as its ProfileAvatar and goes with the contact")
    func roundTrip() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        defer { storage.close() }
        let (peer, _) = try makeContactWithConversation(storage)
        var profile = ContactProfile()
        profile.avatar = .preset(11)
        profile.displayName = "Deniz"
        try storage.write { try $0.contacts.setProfile(profile, for: peer) }
        #expect(try storage.read { try $0.contacts.get(peer) }?.profile.avatar == .preset(11))
        let stored = try storage.database.locked {
            try $0.queryOne("SELECT avatar FROM contacts WHERE account = ?", [peer.bytes])?.blob("avatar")
        }
        #expect(stored == ProfilePicture.preset(11).serializedProfileAvatar)

        // Whatever is stored is checked again on the way out.
        try storage.database.locked {
            try $0.run("UPDATE contacts SET avatar = ? WHERE account = ?", [Data([0x08, 0x00]), peer.bytes])
        }
        #expect(try storage.read { try $0.contacts.get(peer) }?.profile.avatar == .initials)

        profile.avatar = .initials
        try storage.write { try $0.contacts.setProfile(profile, for: peer) }
        #expect(
            try storage.database.locked {
                try $0.queryOne("SELECT avatar FROM contacts WHERE account = ?", [peer.bytes])?.blob("avatar")
            } == nil)

        profile.avatar = .preset(2)
        try storage.write { try $0.contacts.setProfile(profile, for: peer) }
        try storage.write { try $0.contacts.stripToBlock(peer) }
        #expect(try storage.read { try $0.contacts.get(peer) }?.profile.avatar == .initials)
        try storage.write { try $0.contacts.setProfile(profile, for: peer) }
        try storage.write { try $0.contacts.forget(peer) }
        #expect(try storage.read { try $0.contacts.get(peer) } == nil)
    }

    @Test("v5 drops what the deprecated avatar column held")
    func migration() throws {
        let environment = try TestEnvironment()
        try environment.layout.prepare()
        let dbKey = LocalKeyDerivation.databaseKey(dek: environment.dek)
        let peer = AccountId(uuid: UUID())
        do {
            let v4 = try EncryptedDatabase(
                url: environment.layout.databaseURL, key: dbKey,
                migrations: [Schema.v1, Schema.v2, Schema.v3, Schema.v4])
            _ = try PersistentProtocolStore.create(database: v4, identity: .generate(), registrationId: 9)
            try v4.write { connection in
                try connection.run(
                    "INSERT INTO contacts (account, request_state, avatar, display_name, created_at) VALUES (?, ?, ?, ?, ?)",
                    [peer.bytes, ConversationRequestState.accepted.rawValue, Data([0x0A, 0x10]) + Data(count: 16),
                     "Eski", Int64(0)])
            }
            v4.close()
        }
        let storage = try environment.open()
        defer { storage.close() }
        let contact = try #require(try storage.read { try $0.contacts.get(peer) })
        #expect(contact.profile.avatar == .initials)
        #expect(contact.profile.displayName == "Eski")
        #expect(
            try storage.database.locked {
                try $0.queryOne("SELECT avatar FROM contacts WHERE account = ?", [peer.bytes])?.blob("avatar")
            } == nil)
    }
}
