// us2us — AGPL-3.0-only

import Foundation
import SQLCipher

/// Everything that can go wrong in local storage. Descriptions never contain
/// message content, names or keys.
public enum StorageError: Error, Equatable, Sendable {
    /// The storage was closed (vault locked); unlock and open it again.
    case closed
    /// The linked SQLite library is not SQLCipher. Storage refuses to run
    /// rather than write anything in plaintext.
    case encryptionUnavailable
    /// The key does not open the database, or the file is not a database.
    case wrongKeyOrNotADatabase
    /// The database was written by a newer app version.
    case schemaTooNew(found: Int, supported: Int)
    case migrationFailed(version: Int, SQLiteError)
    case database(SQLiteError)
    /// An item or key slot failed authentication (tampering or a wrong key).
    case integrity
    /// The conversation's key slot is gone: it was crypto-shredded.
    case conversationShredded
    case notFound
    /// The operation is not allowed in the current state (e.g. a §6.4 transition).
    case invalidState(String)
    case io(String)
}

extension StorageError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .closed: return "Storage is locked."
        case .encryptionUnavailable: return "SQLCipher is not available; refusing to store data unencrypted."
        case .wrongKeyOrNotADatabase: return "The database cannot be opened with this key."
        case .schemaTooNew(let found, let supported):
            return "Database schema \(found) is newer than this app supports (\(supported))."
        case .migrationFailed(let version, let error): return "Migration \(version) failed: \(error)"
        case .database(let error): return error.description
        case .integrity: return "Stored data failed its integrity check."
        case .conversationShredded: return "This conversation was wiped."
        case .notFound: return "Not found."
        case .invalidState(let reason): return "Invalid state: \(reason)"
        case .io(let reason): return "File error: \(reason)"
        }
    }
}

/// A versioned schema step. Each runs in its own transaction together with
/// the `user_version` bump, so a crash mid-migration leaves the previous
/// version intact.
struct Migration: Sendable {
    let version: Int
    let statements: [String]
}

/// Called at the start of every top-level write transaction, inside it. The
/// protocol store uses it to commit staged session changes atomically with
/// whatever the transaction stores (see `PersistentProtocolStore`).
protocol TransactionParticipant: AnyObject, Sendable {
    /// Writes pending state into `connection` (inside the open transaction).
    /// Returns a token describing what was written, or `nil` if nothing was.
    func transactionDidBegin(_ connection: SQLiteConnection, initiatedByProtocolStore: Bool) throws -> AnyObject?
    /// The transaction (or savepoint) that wrote `token` was rolled back:
    /// the written state is pending again.
    func transactionDidRollBack(token: AnyObject)
}

/// The SQLCipher database of one installation (PROTOCOL.md §5.3), keyed with
/// `dbKey = HKDF-SHA256(DEK, "BRP/db/v1")`.
///
/// One connection serialized by a recursive lock: every call is synchronous
/// and thread-safe, nested `write` calls become savepoints. WAL journal with
/// `synchronous = FULL`, so a committed transaction survives a crash or power
/// loss; `secure_delete` overwrites deleted content inside the file.
final class EncryptedDatabase: @unchecked Sendable {
    let url: URL
    private let lock = NSRecursiveLock()
    private var connection: SQLiteConnection?
    private var depth = 0
    private var commitHooks: [(depth: Int, run: () -> Void)] = []
    private var participantTokens: [(depth: Int, participant: any TransactionParticipant, token: AnyObject)] = []
    private weak var participant: (any TransactionParticipant)?
    private var reading = false
    /// A destructive change in the current transaction wants the WAL folded
    /// and truncated once it commits (see ``checkpointAfterCommit()``).
    private var checkpointRequested = false

    /// Opens (creating if needed) and migrates the database.
    init(url: URL, key: Data, migrations: [Migration]) throws(StorageError) {
        self.url = url
        let connection: SQLiteConnection
        do {
            connection = try SQLiteConnection(path: url.path)
        } catch {
            throw .database(error)
        }
        do {
            try Self.configure(connection, key: key)
            try Self.migrate(connection, migrations: migrations)
        } catch {
            connection.close()
            throw error
        }
        self.connection = connection
    }

    private static func configure(_ connection: SQLiteConnection, key: Data) throws(StorageError) {
        do {
            // Fail closed: a plain SQLite would silently ignore PRAGMA key.
            guard case .text(let version)? = try connection.scalar("PRAGMA cipher_version"), !version.isEmpty else {
                throw StorageError.encryptionUnavailable
            }
            // SQLCipher logs nothing about this database (privacy; errors still surface as StorageError).
            try connection.execute("PRAGMA cipher_log_level = NONE")
            try connection.key(rawKey: key)
            // Wipe memory SQLCipher frees (keys, decrypted pages).
            try connection.execute("PRAGMA cipher_memory_security = ON")
            sqlite3_busy_timeout(connection.handle, 5_000)
            do {
                _ = try connection.scalar("SELECT count(*) FROM sqlite_master")
            } catch let error where error.isNotADatabase {
                throw StorageError.wrongKeyOrNotADatabase
            }
            guard case .text(let mode)? = try connection.scalar("PRAGMA journal_mode = WAL"), mode == "wal" else {
                throw StorageError.database(SQLiteError(code: 1, extendedCode: 1, message: "WAL unavailable"))
            }
            try connection.execute(
                """
                PRAGMA synchronous = FULL;
                PRAGMA foreign_keys = ON;
                PRAGMA secure_delete = ON;
                PRAGMA temp_store = MEMORY;
                PRAGMA trusted_schema = OFF;
                """)
        } catch let error as StorageError {
            throw error
        } catch let error as SQLiteError {
            throw .database(error)
        } catch {
            throw .io("\(error)")
        }
    }

    static func migrate(_ connection: SQLiteConnection, migrations: [Migration]) throws(StorageError) {
        let supported = migrations.map(\.version).max() ?? 0
        let current: Int
        do {
            guard case .integer(let value)? = try connection.scalar("PRAGMA user_version") else {
                throw StorageError.database(SQLiteError(code: 1, extendedCode: 1, message: "user_version"))
            }
            current = Int(value)
        } catch let error as StorageError {
            throw error
        } catch {
            throw .database(error as? SQLiteError ?? SQLiteError(code: 1, extendedCode: 1, message: "\(error)"))
        }
        guard current <= supported else { throw .schemaTooNew(found: current, supported: supported) }
        for migration in migrations.sorted(by: { $0.version < $1.version }) where migration.version > current {
            do {
                try connection.execute("BEGIN IMMEDIATE")
                for statement in migration.statements {
                    try connection.execute(statement)
                }
                try connection.execute("PRAGMA user_version = \(migration.version)")
                try connection.execute("COMMIT")
            } catch {
                if connection.isInTransaction { try? connection.execute("ROLLBACK") }
                throw .migrationFailed(version: migration.version, error)
            }
        }
    }

    var isOpen: Bool { lock.withLock { connection != nil } }

    func setParticipant(_ participant: any TransactionParticipant) {
        lock.withLock { self.participant = participant }
    }

    /// Runs `body` in a write transaction (`BEGIN IMMEDIATE`), or in a
    /// savepoint when already inside one. Rolls back if `body` throws.
    func write<T>(_ body: (SQLiteConnection) throws -> T) throws -> T {
        try write(protocolStore: false, body)
    }

    /// - Parameter protocolStore: the protocol store itself writes. Staged
    ///   decryption state is then committed first, also inside a savepoint,
    ///   so a write built on staged state can never be overtaken by it.
    func write<T>(protocolStore: Bool, _ body: (SQLiteConnection) throws -> T) throws -> T {
        lock.lock()
        defer { lock.unlock() }
        guard let connection else { throw StorageError.closed }
        guard !reading else { throw StorageError.invalidState("write inside a read") }
        if depth > 0 {
            return try savepoint(connection, protocolStore: protocolStore, body)
        }
        do {
            try connection.execute("BEGIN IMMEDIATE")
        } catch {
            throw StorageError.database(error)
        }
        depth = 1
        do {
            try flushParticipant(connection, protocolStore: protocolStore)
            let result = try body(connection)
            try connection.execute("COMMIT")
            depth = 0
            let hooks = commitHooks
            commitHooks.removeAll()
            participantTokens.removeAll()
            let checkpoint = checkpointRequested
            checkpointRequested = false
            for hook in hooks { hook.run() }
            // Deleted or rewritten content must not survive as an older page
            // image in the WAL (secure_delete only zeroes the new image).
            if checkpoint { try? connection.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
            return result
        } catch {
            depth = 0
            commitHooks.removeAll()
            checkpointRequested = false
            if connection.isInTransaction { try? connection.execute("ROLLBACK") }
            rollBackParticipantTokens(fromDepth: 1)
            throw Self.storageError(error)
        }
    }

    private func rollBackParticipantTokens(fromDepth level: Int) {
        let undone = participantTokens.filter { $0.depth >= level }
        participantTokens.removeAll { $0.depth >= level }
        for entry in undone.reversed() { entry.participant.transactionDidRollBack(token: entry.token) }
    }

    private func flushParticipant(_ connection: SQLiteConnection, protocolStore: Bool) throws {
        guard let participant,
            let token = try participant.transactionDidBegin(connection, initiatedByProtocolStore: protocolStore)
        else { return }
        participantTokens.append((depth, participant, token))
    }

    private func savepoint<T>(
        _ connection: SQLiteConnection, protocolStore: Bool, _ body: (SQLiteConnection) throws -> T
    ) throws -> T {
        let name = "s\(depth)"
        do {
            try connection.execute("SAVEPOINT \(name)")
        } catch {
            throw StorageError.database(error)
        }
        depth += 1
        let level = depth
        do {
            if protocolStore { try flushParticipant(connection, protocolStore: true) }
            let result = try body(connection)
            depth -= 1
            try connection.execute("RELEASE \(name)")
            return result
        } catch {
            depth -= 1
            commitHooks.removeAll { $0.depth >= level }
            try? connection.execute("ROLLBACK TO \(name)")
            try? connection.execute("RELEASE \(name)")
            rollBackParticipantTokens(fromDepth: level)
            throw Self.storageError(error)
        }
    }

    /// Reads inside a deferred transaction, so multi-statement reads see one
    /// consistent snapshot. Inside a write it simply runs.
    func read<T>(_ body: (SQLiteConnection) throws -> T) throws -> T {
        lock.lock()
        defer { lock.unlock() }
        guard let connection else { throw StorageError.closed }
        if depth > 0 || reading { return try body(connection) }
        do {
            try connection.execute("BEGIN DEFERRED")
        } catch {
            throw StorageError.database(error)
        }
        reading = true
        defer {
            reading = false
            if connection.isInTransaction { try? connection.execute("COMMIT") }
        }
        do {
            return try body(connection)
        } catch {
            throw Self.storageError(error)
        }
    }

    /// Runs `body` holding the database lock, outside any explicit
    /// transaction: for reads plus in-memory state that must stay consistent
    /// with transactions. `body` must not write.
    func locked<T>(_ body: (SQLiteConnection) throws -> T) throws -> T {
        lock.lock()
        defer { lock.unlock() }
        guard let connection else { throw StorageError.closed }
        do {
            return try body(connection)
        } catch {
            throw Self.storageError(error)
        }
    }

    /// Runs `hook` after the current top-level transaction commits (never if
    /// it rolls back). Outside a transaction it runs immediately.
    func afterCommit(_ hook: @escaping () -> Void) {
        lock.withLock {
            if depth == 0 {
                hook()
            } else {
                commitHooks.append((depth, hook))
            }
        }
    }

    /// Asks for ``checkpoint()`` once the current top-level transaction has
    /// committed (immediately outside a transaction). In WAL mode an UPDATE
    /// or DELETE appends a new page image and leaves the old one, still
    /// holding the content, in `-wal` until a checkpoint; every path that
    /// destroys or rewrites content calls this. Nested writes (savepoints)
    /// keep the request for the outer commit; a rollback drops it.
    func checkpointAfterCommit() {
        lock.withLock {
            if depth == 0 {
                guard let connection, !reading else { return }
                try? connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            } else {
                checkpointRequested = true
            }
        }
    }

    /// Folds the WAL into the main file and truncates it, so deleted pages
    /// do not linger in the log (after wipes).
    func checkpoint() throws {
        lock.lock()
        defer { lock.unlock() }
        guard let connection else { throw StorageError.closed }
        guard depth == 0, !reading else { return }
        do {
            try connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        } catch {
            throw StorageError.database(error)
        }
    }

    /// Checkpoints and closes. Further calls throw ``StorageError/closed``.
    func close() {
        lock.withLock {
            guard let connection else { return }
            try? connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            connection.close()
            self.connection = nil
        }
    }

    static func storageError(_ error: any Error) -> any Error {
        if let error = error as? SQLiteError { return StorageError.database(error) }
        return error
    }
}

