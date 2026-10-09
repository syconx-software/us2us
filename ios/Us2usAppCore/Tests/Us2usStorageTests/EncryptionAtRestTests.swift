// us2us — AGPL-3.0-only

import Foundation
import LibSignalClient
import SQLCipher
import Testing
import Us2usKit

@testable import Us2usStorage

@Suite("Encryption at rest (§5.3)")
struct EncryptionAtRestTests {
    /// Writes content with recognizable markers everywhere it can go.
    private func populate(_ storage: LocalStorage) throws -> (Conversation, StoredAttachment) {
        _ = try TestAccount(storage: storage)
        let (contact, conversation) = try makeContactWithConversation(storage)
        return try storage.write { tx throws in
            var profile = ContactProfile()
            profile.displayName = "MARKER-DISPLAYNAME"
            try tx.contacts.setProfile(profile, for: contact)
            try tx.messages.storeIncoming(textMessage("MARKER-BODY-INCOMING"), in: conversation.id)
            var data = BRPDataMessage(payload: .attachment(sampleAttachment(caption: "MARKER-CAPTION")))
            data.messageID = RandomId.make()
            try tx.messages.storeOutgoing(data, in: conversation.id)
            try tx.outbox.enqueue(BRPContent(data: data), to: contact, conversation: conversation.id, messageId: data.messageID)
            try tx.conversations.setDraft("MARKER-DRAFT", for: conversation.id)
            let attachment = try #require(try tx.attachments.forMessage(data.messageID, in: conversation.id).first)
            try tx.attachments.storeFile(Data("MARKER-FILE-CONTENT".utf8), for: attachment.id)
            return (conversation, attachment)
        }
    }

    private static let markers = [
        "MARKER-DISPLAYNAME", "MARKER-BODY-INCOMING", "MARKER-CAPTION", "MARKER-DRAFT", "MARKER-FILE-CONTENT",
    ]

    @Test("the database file and its WAL contain no plaintext and no SQLite header")
    func rawFilesAreOpaque() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        _ = try populate(storage)
        // Before and after a checkpoint (content in the WAL, then in the main file).
        for checkpoint in [false, true] {
            if checkpoint { try storage.database.checkpoint() }
            let bytes = environment.databaseBytes()
            #expect(bytes.count > 4096)
            #expect(!bytes.contains(Data("SQLite format 3".utf8)))
            for marker in Self.markers {
                #expect(!bytes.contains(.marker(marker)), "\(marker) in database files")
            }
            #expect(!bytes.contains(Data("CREATE TABLE".utf8)))
        }
        storage.close()
        let raw = try Data(contentsOf: environment.layout.databaseURL)
        #expect(!raw.prefix(16).contains(Data("SQLite".utf8)))
    }

    @Test("the database cannot be opened without the key, or with a wrong one")
    func unreadableWithoutKey() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        _ = try populate(storage)
        storage.close()

        // A plain connection (no key) sees "file is not a database".
        var db: OpaquePointer?
        #expect(sqlite3_open_v2(environment.layout.databaseURL.path, &db, SQLITE_OPEN_READONLY, nil) == SQLITE_OK)
        defer { sqlite3_close_v2(db) }
        let status = sqlite3_exec(db, "SELECT count(*) FROM sqlite_master", nil, nil, nil)
        #expect(status == SQLITE_NOTADB)

        // A wrong DEK is refused, and nothing is created or changed.
        #expect(throws: StorageError.wrongKeyOrNotADatabase) {
            _ = try LocalStorage.open(layout: environment.layout, dek: TestEnvironment.randomKey())
        }
        // The right key still works afterwards.
        let reopened = try environment.open()
        #expect(try reopened.read { try $0.contacts.all() }.count == 1)
    }

    @Test("the SQLCipher key is exactly HKDF-SHA256(DEK, \"BRP/db/v1\") as a raw key")
    func databaseKeyDerivation() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        _ = try populate(storage)
        storage.close()

        let dbKey = LocalKeyDerivation.databaseKey(dek: environment.dek)
        var db: OpaquePointer?
        #expect(sqlite3_open_v2(environment.layout.databaseURL.path, &db, SQLITE_OPEN_READONLY, nil) == SQLITE_OK)
        defer { sqlite3_close_v2(db) }
        #expect(sqlite3_exec(db, "PRAGMA key = \"x'\(dbKey.hexString)'\"", nil, nil, nil) == SQLITE_OK)
        var statement: OpaquePointer?
        #expect(sqlite3_prepare_v2(db, "SELECT count(*) FROM contacts", -1, &statement, nil) == SQLITE_OK)
        #expect(sqlite3_step(statement) == SQLITE_ROW)
        #expect(sqlite3_column_int(statement, 0) == 1)
        sqlite3_finalize(statement)
    }

    @Test("SQLCipher is linked, keyed, and its integrity check passes")
    func cipherSettings() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        _ = try populate(storage)
        let memorySecurity = try storage.database.locked { try $0.scalar("PRAGMA cipher_memory_security") }
        #expect(memorySecurity == .text("1") || memorySecurity == .integer(1), "\(String(describing: memorySecurity))")
        let (version, integrity, journal, secureDelete, foreignKeys) = try storage.database.locked { connection in
            (
                try connection.scalar("PRAGMA cipher_version"),
                try connection.query("PRAGMA cipher_integrity_check"),
                try connection.scalar("PRAGMA journal_mode"),
                try connection.scalar("PRAGMA secure_delete"),
                try connection.scalar("PRAGMA foreign_keys")
            )
        }
        guard case .text(let versionText)? = version else {
            Issue.record("no cipher_version")
            return
        }
        #expect(versionText.hasPrefix("4."))
        #expect(integrity.isEmpty)  // no rows = no errors
        #expect(journal == .text("wal"))
        #expect(secureDelete == .integer(1))
        #expect(foreignKeys == .integer(1))
    }

    @Test("message bodies, captions, drafts and pending sends are items under CK even inside the database")
    func columnsHoldItems() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        let (conversation, _) = try populate(storage)
        let key = try storage.keyring.key(for: conversation.slotId)
        try storage.database.locked { connection in
            for row in try connection.query("SELECT message_id, body FROM messages") {
                let body = try #require(row.blob("body"))
                let messageId = try #require(row.blob("message_id"))
                #expect(body.first == 0x01)
                for marker in Self.markers { #expect(!body.contains(.marker(marker))) }
                // Bound to its row: another item id does not open it.
                #expect(throws: LocalStorageError.integrityCheckFailed) {
                    try ItemCipher.open(body, itemId: RandomId.make(), conversationKey: key)
                }
                _ = try ItemCipher.open(body, itemId: messageId, conversationKey: key)
            }
            for row in try connection.query("SELECT payload FROM outbox") {
                let payload = try #require(row.blob("payload"))
                for marker in Self.markers { #expect(!payload.contains(.marker(marker))) }
            }
            let draft = try #require(
                try connection.queryOne("SELECT draft FROM conversations")?.blob("draft"))
            #expect(!draft.contains(.marker("MARKER-DRAFT")))
        }
        #expect(try storage.read { try $0.conversations.draft(for: conversation.id) } == "MARKER-DRAFT")
    }

    @Test("attachment files are items: random names, no plaintext, bound to their id")
    func attachmentFiles() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        let (conversation, attachment) = try populate(storage)
        let url = storage.files.url(for: attachment.id)
        #expect(url.lastPathComponent == attachment.id.hexString + ".item")
        let item = try Data(contentsOf: url)
        let plaintext = Data("MARKER-FILE-CONTENT".utf8)
        #expect(item.count == 1 + 12 + plaintext.count + 16)
        #expect(item.first == 0x01)
        #expect(!item.contains(plaintext))
        let key = try storage.keyring.key(for: conversation.slotId)
        #expect(try ItemCipher.open(item, itemId: attachment.id, conversationKey: key) == plaintext)
        #expect(throws: LocalStorageError.self) {
            try ItemCipher.open(item.flippingBit(at: 20), itemId: attachment.id, conversationKey: key)
        }
        #expect(try storage.read { try $0.attachments.readFile(attachment.id) } == plaintext)
    }

    @Test("key slots are BRPS files named by a random slot id")
    func slotFiles() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        let (conversation, _) = try populate(storage)
        let slot = try Data(contentsOf: storage.keyring.slotURL(conversation.slotId))
        #expect(slot.prefix(5) == Data("BRPS".utf8) + [0x01])
        #expect(slot.count == 5 + 12 + 32 + 16)
        #expect(conversation.slotId.count == 16)
        #expect(conversation.slotId != conversation.id.rawValue)
        let key = try KeySlot.open(slot, slotId: conversation.slotId, dek: environment.dek)
        #expect(key == (try storage.keyring.key(for: conversation.slotId)))
        // Without the DEK the slot does not open.
        #expect(throws: LocalStorageError.self) {
            try KeySlot.open(slot, slotId: conversation.slotId, dek: TestEnvironment.randomKey())
        }
    }

    @Test("the storage tree is excluded from backups and plaintext staging is wiped")
    func backupExclusionAndStaging() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        _ = try populate(storage)
        let layout = environment.layout
        for url in [layout.root, layout.databaseDirectory, layout.slotDirectory, layout.filesDirectory,
                    layout.stagingDirectory]
        {
            let values = try url.resourceValues(forKeys: [.isExcludedFromBackupKey])
            #expect(values.isExcludedFromBackup == true, "\(url.lastPathComponent) not excluded")
        }
        // Protection classes, where the platform reports them (devices do;
        // the Simulator may not).
        let expected: [(URL, FileProtectionType)] = [
            (layout.slotDirectory, .complete), (layout.databaseDirectory, .completeUnlessOpen),
            (layout.filesDirectory, .completeUnlessOpen), (layout.stagingDirectory, .complete),
        ]
        for (url, protection) in expected {
            if let actual = try FileManager.default.attributesOfItem(atPath: url.path)[.protectionKey]
                as? FileProtectionType
            {
                #expect(actual == protection, "\(url.lastPathComponent)")
            }
        }
        let staged = storage.makeStagingURL()
        try Data("PLAINTEXT".utf8).write(to: staged)
        storage.close()
        #expect(!FileManager.default.fileExists(atPath: staged.path))
    }

    @Test("only one open instance per container")
    func singleInstance() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        #expect(throws: StorageError.self) { _ = try environment.open() }
        storage.close()
        _ = try environment.open()
    }

    @Test("closing drops keys: nothing is readable until reopened with the DEK")
    func closeLocks() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        let (conversation, _) = try populate(storage)
        storage.close()
        #expect(throws: StorageError.closed) { try storage.read { try $0.contacts.all() } }
        #expect(throws: StorageError.closed) { try storage.keyring.key(for: conversation.slotId) }
        let reopened = try environment.open()
        #expect(try reopened.read { try $0.messages.page(in: conversation.id) }.count == 2)
    }
}

extension Data {
    func flippingBit(at index: Int) -> Data {
        var copy = self
        copy[copy.startIndex + index] ^= 0x01
        return copy
    }
}
