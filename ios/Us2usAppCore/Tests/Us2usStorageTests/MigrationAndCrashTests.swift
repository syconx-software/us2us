// us2us — AGPL-3.0-only

import Foundation
import LibSignalClient
import SQLCipher
import Testing
import Us2usKit

@testable import Us2usStorage

@Suite("Schema migrations")
struct MigrationTests {
    private func key() -> Data { TestEnvironment.randomKey() }

    @Test("a new database is created at the latest version")
    func freshDatabase() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        let version = try storage.database.locked { try $0.scalar("PRAGMA user_version") }
        #expect(version == .integer(Int64(Schema.latestVersion)))
        #expect(Schema.latestVersion == 10)
    }

    @Test("an older database is upgraded in place, keeping its data")
    func upgradeKeepsData() throws {
        let environment = try TestEnvironment()
        try environment.layout.prepare()
        let dbKey = LocalKeyDerivation.databaseKey(dek: environment.dek)
        let identity = IdentityKeyPair.generate()
        // A v1 database (protocol store only), as shipped by an older version.
        do {
            let v1 = try EncryptedDatabase(url: environment.layout.databaseURL, key: dbKey, migrations: [Schema.v1])
            _ = try PersistentProtocolStore.create(database: v1, identity: identity, registrationId: 5)
            v1.close()
        }
        let storage = try environment.open()
        let version = try storage.database.locked { try $0.scalar("PRAGMA user_version") }
        #expect(version == .integer(Int64(Schema.latestVersion)))
        #expect(try storage.protocolStore?.identityKeyPair(context: NullContext()).serialize() == identity.serialize())
        // The v2 tables work.
        _ = try TestAccount(storage: storage)
        _ = try makeContactWithConversation(storage)
    }

    @Test("a database from a newer app version is refused, not damaged")
    func tooNew() throws {
        let environment = try TestEnvironment()
        try environment.layout.prepare()
        let dbKey = LocalKeyDerivation.databaseKey(dek: environment.dek)
        let future = Migration(version: Schema.latestVersion + 1, statements: ["CREATE TABLE future (x)"])
        let database = try EncryptedDatabase(
            url: environment.layout.databaseURL, key: dbKey, migrations: Schema.migrations + [future])
        database.close()
        #expect(throws: StorageError.schemaTooNew(found: Schema.latestVersion + 1, supported: Schema.latestVersion)) { _ = try environment.open() }
        let reopened = try EncryptedDatabase(
            url: environment.layout.databaseURL, key: dbKey, migrations: Schema.migrations + [future])
        #expect(try reopened.locked { try $0.scalar("PRAGMA user_version") } == .integer(Int64(Schema.latestVersion + 1)))
    }

    @Test("a failing migration rolls back completely and can be retried")
    func failingMigration() throws {
        let environment = try TestEnvironment()
        try environment.layout.prepare()
        let dbKey = LocalKeyDerivation.databaseKey(dek: environment.dek)
        let next = Schema.latestVersion + 1
        let broken = Migration(version: next, statements: ["CREATE TABLE half (x)", "THIS IS NOT SQL"])
        #expect(throws: StorageError.self) {
            _ = try EncryptedDatabase(
                url: environment.layout.databaseURL, key: dbKey, migrations: Schema.migrations + [broken])
        }
        let database = try EncryptedDatabase(
            url: environment.layout.databaseURL, key: dbKey, migrations: Schema.migrations)
        let (version, half) = try database.locked { connection in
            (
                try connection.scalar("PRAGMA user_version"),
                try connection.queryOne("SELECT name FROM sqlite_master WHERE name = 'half'")
            )
        }
        #expect(version == .integer(Int64(Schema.latestVersion)))
        #expect(half == nil)
        database.close()
        let fixed = Migration(version: next, statements: ["CREATE TABLE half (x)"])
        let upgraded = try EncryptedDatabase(
            url: environment.layout.databaseURL, key: dbKey, migrations: Schema.migrations + [fixed])
        #expect(try upgraded.locked { try $0.scalar("PRAGMA user_version") } == .integer(Int64(next)))
    }
}

@Suite("Crash safety")
struct CrashSafetyTests {
    @Test("an uncommitted transaction is invisible after a crash; a committed one is durable")
    func writeAheadLog() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        _ = try TestAccount(storage: storage)
        let (_, conversation) = try makeContactWithConversation(storage)
        _ = try storage.write { tx throws in try tx.messages.storeIncoming(textMessage("committed"), in: conversation.id) }

        // Snapshot the files while a big transaction is open (its pages spill
        // into the WAL), as if the process died at that moment.
        let crashCopy = environment.directory.appendingPathComponent("crash", isDirectory: true)
        struct Abort: Error {}
        #expect(throws: Abort.self) {
            try storage.write { tx throws in
                try tx.connection.execute("PRAGMA cache_size = 2")
                for index in 0..<300 {
                    try tx.messages.storeIncoming(textMessage(String(repeating: "u\(index)", count: 50)), in: conversation.id)
                }
                _ = try environment.copyDatabase(to: crashCopy)
                throw Abort()
            }
        }
        // The live database rolled back.
        #expect(try storage.read { try $0.messages.count(in: conversation.id) } == 1)

        // The crashed copy recovers to the last commit and passes integrity checks.
        let dbKey = LocalKeyDerivation.databaseKey(dek: environment.dek)
        let recovered = try EncryptedDatabase(
            url: crashCopy.appendingPathComponent(environment.layout.databaseURL.lastPathComponent), key: dbKey,
            migrations: Schema.migrations)
        let (count, integrity, cipherIntegrity) = try recovered.locked { connection in
            (
                try connection.scalar("SELECT count(*) FROM messages"),
                try connection.scalar("PRAGMA integrity_check"),
                try connection.query("PRAGMA cipher_integrity_check")
            )
        }
        #expect(count == .integer(1))
        #expect(integrity == .text("ok"))
        #expect(cipherIntegrity.isEmpty)
    }

    @Test("a savepoint failure inside a transaction keeps the outer changes")
    func savepoints() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        _ = try TestAccount(storage: storage)
        let (_, conversation) = try makeContactWithConversation(storage)
        struct Inner: Error {}
        try storage.write { tx throws in
            try tx.messages.storeIncoming(textMessage("outer"), in: conversation.id)
            #expect(throws: Inner.self) {
                try storage.write { inner throws in
                    try inner.messages.storeIncoming(textMessage("inner"), in: conversation.id)
                    throw Inner()
                }
            }
        }
        let bodies = try storage.read { try $0.messages.page(in: conversation.id) }
            .compactMap { $0.content.map { BRPContent(data: $0).textBody } }
        #expect(bodies == ["outer"])
    }

    @Test("file deletions happen only after the transaction commits")
    func filesAfterCommit() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        _ = try TestAccount(storage: storage)
        let (_, conversation) = try makeContactWithConversation(storage)
        var data = BRPDataMessage(payload: .attachment(sampleAttachment()))
        data.messageID = RandomId.make()
        let attachmentId = try storage.write { tx -> Data in
            try tx.messages.storeIncoming(data, in: conversation.id)
            let id = try #require(try tx.attachments.forMessage(data.messageID, in: conversation.id).first?.id)
            try tx.attachments.storeFile(Data("keep me".utf8), for: id)
            return id
        }
        struct Abort: Error {}
        #expect(throws: Abort.self) {
            try storage.write { tx throws in
                try tx.messages.deleteLocally([data.messageID], in: conversation.id)
                throw Abort()
            }
        }
        #expect(storage.files.exists(attachmentId))
        #expect(try storage.read { try $0.attachments.readFile(attachmentId) } == Data("keep me".utf8))
    }

    @Test("atomic file writes: an interrupted write never damages the existing file")
    func atomicFiles() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        let (_, conversation) = try makeContactWithConversation(storage)
        let key = try storage.keyring.key(for: conversation.slotId)
        let itemId = RandomId.make()
        try storage.files.write(Data("version 1".utf8), itemId: itemId, key: key)
        // A crash mid-write leaves at most a temporary sibling, never a torn target.
        let torn = storage.files.directory.appendingPathComponent(".\(itemId.hexString).item.tmp")
        try Data([0x01, 0x02]).write(to: torn)
        #expect(try storage.files.read(itemId, key: key) == Data("version 1".utf8))
        try storage.files.write(Data("version 2".utf8), itemId: itemId, key: key)
        #expect(try storage.files.read(itemId, key: key) == Data("version 2".utf8))
        #expect(storage.files.strayFiles().contains(torn))
        // A corrupted item is detected, not misread.
        try Data(repeating: 0x01, count: 40).write(to: storage.files.url(for: itemId))
        #expect(throws: StorageError.integrity) { try storage.files.read(itemId, key: key) }
    }

    @Test("a tampered key slot is detected")
    func tamperedSlot() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        let (_, conversation) = try makeContactWithConversation(storage)
        storage.close()
        let url = environment.layout.slotDirectory.appendingPathComponent(conversation.slotId.hexString + ".slot")
        let slot = try Data(contentsOf: url)
        try slot.flippingBit(at: 30).write(to: url)
        let reopened = try environment.open()
        #expect(throws: StorageError.integrity) { try reopened.keyring.key(for: conversation.slotId) }
    }
}
