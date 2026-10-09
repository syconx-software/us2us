// us2us — AGPL-3.0-only

import Foundation
import LibSignalClient
import Us2usKit

/// The encrypted local storage of one installation (PROTOCOL.md §5.3).
///
/// Lifecycle (§5.4):
///
/// 1. Unlock the vault → DEK.
/// 2. ``open(layout:dek:now:)``: crypto-shreds overdue ghost conversations
///    (no DEK needed), opens the SQLCipher database with
///    `dbKey = HKDF(DEK, "BRP/db/v1")`, migrates it, then finishes wipes and
///    garbage-collects leftovers (rows and files of shredded conversations,
///    expired disappearing messages, orphaned files and slots).
/// 3. Use ``write(_:)`` / ``read(_:)`` and ``protocolStore`` while unlocked;
///    call ``performDueWipes(now:)`` on foreground and from in-app timers
///    (``nextDeadline(now:)``).
/// 4. ``close()`` when the vault locks: keys are dropped, the database is
///    checkpointed and closed, plaintext staging files are removed.
///
/// While locked, only ``runBackgroundWipes(layout:now:)`` and
/// ``nextBackgroundWipeDate(layout:)`` work (BGTaskScheduler): they read the
/// schedule file `{slotId, deadline}` and delete slot files, nothing else.
///
/// With `Messenger` (Us2usKit): pass ``protocolStore`` as its store and
/// ``accountSaver`` as its `AccountSaver` (rebuild the messenger after every
/// reopen — a closed storage's store refuses all calls). Receive inside
/// ``PersistentProtocolStore/withStagedInboundWrites(_:)`` and store each
/// delivered message in one ``write(_:)`` that also records
/// `tx.envelopes.record(message.relayId)`:
///
///     try await store.withStagedInboundWrites {
///         try await messenger.run(connection) { message in
///             try storage.write { tx in
///                 // contacts.noteVerifiedMessage, messages.storeIncoming, …
///                 try tx.envelopes.record(message.relayId)
///             }
///         }
///     }
///
/// Thread-safe; every call is synchronous. Run them off the main thread.
/// Calling the protocol store (libsignal) inside ``read(_:)`` is an error;
/// inside ``write(_:)`` it joins the transaction.
public final class LocalStorage: @unchecked Sendable {
    public let layout: StorageLayout
    let database: EncryptedDatabase
    let keyring: ConversationKeyring
    let files: ItemFileStore
    private let lock = NSLock()
    private var cachedProtocolStore: PersistentProtocolStore?
    private var cachedAccountId: AccountId?

    /// The result of opening: the storage and what was wiped on the way.
    public struct Opened: Sendable {
        public let storage: LocalStorage
        public let wipeReport: WipeReport
    }

    private init(layout: StorageLayout, database: EncryptedDatabase, keyring: ConversationKeyring) {
        self.layout = layout
        self.database = database
        self.keyring = keyring
        self.files = ItemFileStore(layout: layout)
    }

    /// Opens (creating on first use) the storage with the vault's DEK.
    public static func open(layout: StorageLayout, dek: Data, now: Date = Date()) throws -> Opened {
        try OpenStorages.serialized { try openExclusively(layout: layout, dek: dek, now: now) }
    }

    private static func openExclusively(layout: StorageLayout, dek: Data, now: Date) throws -> Opened {
        guard dek.count == 32 else { throw StorageError.invalidState("DEK must be 32 bytes") }
        var alreadyOpen = false
        OpenStorages.forEach(root: layout.root) { if $0.isOpen { alreadyOpen = true } }
        // One instance per container: two would stage and cache keys independently.
        guard !alreadyOpen else { throw StorageError.invalidState("storage already open; close it first") }
        try layout.prepare()
        let keyring = ConversationKeyring(layout: layout, dek: dek)
        // §5.4 step 1: overdue wipes first, before any content can be read.
        _ = try? keyring.destroyExpiredSlots(now: now)
        var dbKey = LocalKeyDerivation.databaseKey(dek: dek)
        defer { dbKey.resetBytes(in: 0..<dbKey.count) }
        let database: EncryptedDatabase
        do {
            database = try EncryptedDatabase(url: layout.databaseURL, key: dbKey, migrations: Schema.migrations)
        } catch {
            keyring.close()
            throw error
        }
        for url in layout.databaseFiles { FileProtection.setProtection(.completeUnlessOpen, of: url) }
        // A WAL inherited from an unclean exit (crash, force-quit in the grace
        // period) can still hold page images of deleted content: fold it in now.
        try? database.checkpoint()
        let storage = LocalStorage(layout: layout, database: database, keyring: keyring)
        OpenStorages.register(storage)
        storage.files.clearStaging()
        do {
            storage.cachedProtocolStore = try PersistentProtocolStore.open(database: database)
            let report = try storage.performDueWipes(now: now)
            return Opened(storage: storage, wipeReport: report)
        } catch {
            storage.close()
            throw error
        }
    }

    /// Locks: drops keys and caches, closes the database, removes staging files.
    public func close() {
        files.clearStaging()
        database.close()
        keyring.close()
        lock.withLock {
            cachedProtocolStore = nil
            cachedAccountId = nil
        }
    }

    public var isOpen: Bool { database.isOpen }

    // MARK: Identity and account

    /// The protocol store, once an identity exists (after registration or restore).
    public var protocolStore: PersistentProtocolStore? {
        lock.withLock { cachedProtocolStore }
    }

    /// Stores a new identity (registration) or the restored one (§5.5) and
    /// returns the store to hand to `Messenger`.
    public func createIdentity(_ identity: IdentityKeyPair, registrationId: UInt32) throws -> PersistentProtocolStore {
        let store = try PersistentProtocolStore.create(
            database: database, identity: identity, registrationId: registrationId)
        lock.withLock { cachedProtocolStore = store }
        return store
    }

    /// Persists `LocalAccount` inside the database (pass to `Messenger` as
    /// its `AccountSaver`).
    public var accountSaver: AccountSaver {
        { [self] account in try write { try $0.account.save(account) } }
    }

    func ownAccountId(_ connection: SQLiteConnection) throws -> AccountId {
        if let cached = lock.withLock({ cachedAccountId }) { return cached }
        guard let account = try AccountRepository.load(connection) else {
            throw StorageError.invalidState("no local account")
        }
        lock.withLock { cachedAccountId = account.accountId }
        return account.accountId
    }

    func forgetCachedAccount() {
        lock.withLock { cachedAccountId = nil }
    }

    // MARK: Transactions

    /// Runs `body` in one atomic write transaction. If it throws, nothing it
    /// did is kept (files it wrote become orphans, garbage-collected later).
    /// Inside ``PersistentProtocolStore/withStagedInboundWrites(_:)`` this
    /// also commits the staged decryption state of the current task.
    public func write<T>(_ body: (StorageTransaction) throws -> T) throws -> T {
        try database.write { connection in
            try body(StorageTransaction(connection: connection, storage: self, isWritable: true))
        }
    }

    /// Runs `body` on one consistent snapshot. Writing inside throws.
    public func read<T>(_ body: (StorageTransaction) throws -> T) throws -> T {
        try database.read { connection in
            try body(StorageTransaction(connection: connection, storage: self, isWritable: false))
        }
    }

    // MARK: Files

    /// A path for plaintext about to be encrypted (upload) or just decrypted
    /// (download). Complete protection, never backed up, wiped on open/close.
    public func makeStagingURL() -> URL {
        files.newStagingURL()
    }

    /// Removes every plaintext staging file of the container without a key.
    /// For process start (app launch, also a background-task launch): a
    /// crash, a jetsam kill or a swipe from the app switcher skips
    /// ``close()``, and decrypted media must not wait for the next unlock.
    /// Never call it while this process has the storage open (transfers in
    /// flight use staging); ``runBackgroundWipes(layout:now:)`` clears it
    /// only when no instance is open.
    public static func purgeStaging(layout: StorageLayout) {
        ItemFileStore(layout: layout).clearStaging()
    }

    /// Wipes everything on this phone: database, slots, files (duress code,
    /// account deletion, §5.2). Call ``LocalVault/destroy()`` too.
    public static func destroyAll(layout: StorageLayout) throws {
        OpenStorages.forEach(root: layout.root) { $0.close() }
        // Slots first: from here on nothing is decryptable, even if the rest fails.
        let slotNames = (try? FileManager.default.contentsOfDirectory(atPath: layout.slotDirectory.path)) ?? []
        for name in slotNames {
            try FileProtection.removeIfPresent(layout.slotDirectory.appendingPathComponent(name))
        }
        for url in layout.databaseFiles {
            try FileProtection.removeIfPresent(url)
        }
        try FileProtection.removeIfPresent(layout.root)
    }
}

/// One open transaction. Only valid inside the closure it was passed to.
public struct StorageTransaction {
    let connection: SQLiteConnection
    let storage: LocalStorage
    let isWritable: Bool

    public var account: AccountRepository { AccountRepository(tx: self) }
    public var contacts: ContactRepository { ContactRepository(tx: self) }
    public var conversations: ConversationRepository { ConversationRepository(tx: self) }
    public var messages: MessageRepository { MessageRepository(tx: self) }
    public var attachments: AttachmentRepository { AttachmentRepository(tx: self) }
    public var outbox: OutboxRepository { OutboxRepository(tx: self) }
    public var envelopes: EnvelopeLog { EnvelopeLog(tx: self) }

    func requireWritable() throws {
        guard isWritable else { throw StorageError.invalidState("write inside a read") }
    }

    /// Runs after the transaction commits (never after a rollback).
    func afterCommit(_ hook: @escaping () -> Void) {
        storage.database.afterCommit(hook)
    }

    /// Folds and truncates the WAL after the commit, so destroyed or
    /// rewritten content leaves no older page image behind.
    func checkpointAfterCommit() {
        storage.database.checkpointAfterCommit()
    }

    func ownAccountId() throws -> AccountId {
        try storage.ownAccountId(connection)
    }

    /// `CK` of a conversation.
    func conversationKey(slotId: Data) throws -> Data {
        try storage.keyring.key(for: slotId)
    }

    func conversationKey(_ conversationId: ConversationId) throws -> Data {
        guard
            let slotId = try connection.queryOne(
                "SELECT slot_id FROM conversations WHERE id = ?", [conversationId.rawValue])?.blob("slot_id")
        else { throw StorageError.notFound }
        return try conversationKey(slotId: slotId)
    }

    func seal(_ plaintext: Data, itemId: Data, key: Data) throws -> Data {
        do {
            return try ItemCipher.seal(plaintext, itemId: itemId, conversationKey: key)
        } catch {
            throw StorageError.integrity
        }
    }

    func open(_ item: Data, itemId: Data, key: Data) throws -> Data {
        do {
            return try ItemCipher.open(item, itemId: itemId, conversationKey: key)
        } catch {
            throw StorageError.integrity
        }
    }
}

/// The persisted ``LocalAccount`` (credentials, username, access key,
/// pending relay setup), inside the encrypted database.
public struct AccountRepository {
    let tx: StorageTransaction

    public func load() throws -> LocalAccount? {
        try Self.load(tx.connection)
    }

    static func load(_ connection: SQLiteConnection) throws -> LocalAccount? {
        guard let data = try connection.queryOne("SELECT account FROM local_account WHERE id = 1")?.blob("account")
        else { return nil }
        do {
            return try JSONDecoder().decode(LocalAccount.self, from: data)
        } catch {
            throw StorageError.integrity
        }
    }

    public func save(_ account: LocalAccount) throws {
        try tx.requireWritable()
        if let existing = try load(), existing.accountId != account.accountId {
            throw StorageError.invalidState("a different account is stored")
        }
        let data = try JSONEncoder().encode(account)
        try tx.connection.run("INSERT OR REPLACE INTO local_account (id, account) VALUES (1, ?)", [data])
    }

    /// Removes the account (after `DELETE /v1/accounts/me`).
    public func delete() throws {
        try tx.requireWritable()
        try tx.connection.run("DELETE FROM local_account")
        tx.afterCommit { [storage = tx.storage] in storage.forgetCachedAccount() }
    }
}
