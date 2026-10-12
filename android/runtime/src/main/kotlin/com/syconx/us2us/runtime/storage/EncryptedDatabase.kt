package com.syconx.us2us.runtime.storage

import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Called at the start of every top-level write transaction, inside it. The protocol storage uses
 * it to commit staged session changes atomically with whatever the transaction stores (see
 * [InboundStaging]).
 */
internal interface TransactionParticipant {
    /**
     * Writes pending state into [connection] (inside the open transaction). Returns a token
     * describing what was written, or `null` if nothing was.
     */
    fun transactionDidBegin(connection: SqlConnection, initiatedByProtocolStore: Boolean): Any?

    /** The transaction (or savepoint) that wrote [token] was rolled back: the written state is pending again. */
    fun transactionDidRollBack(token: Any)
}

/** Who opens a write transaction, which decides whether staged protocol state goes in first. */
internal enum class Writer {
    /** The app: staged state is committed with it only inside the inbound staging scope. */
    APP,

    /** The protocol storage itself: staged state is always committed first, also inside a savepoint. */
    PROTOCOL_STORE,

    /**
     * The core's contact and account stores: never take staged state along. What a received message
     * changes about its sender is idempotent, so it may commit before the message; the ratchet must not.
     */
    DETACHED,
}

/**
 * The SQLCipher database of one installation (PROTOCOL.md §5.3), keyed with
 * `dbKey = HKDF-SHA256(DEK, "BRP/db/v1")`.
 *
 * One connection serialized by a reentrant lock: every call is synchronous and thread-safe, nested
 * [write] calls become savepoints. WAL journal with `synchronous = FULL`, so a committed transaction
 * survives a crash or power loss; `secure_delete` overwrites deleted content inside the file.
 */
internal class EncryptedDatabase private constructor(val file: File, connection: SqlConnection) {
    private class Hook(var depth: Int, val run: () -> Unit)

    private class ParticipantToken(var depth: Int, val participant: TransactionParticipant, val token: Any)

    private val lock = ReentrantLock()
    private var connection: SqlConnection? = connection
    private var depth = 0
    private val commitHooks = ArrayList<Hook>()
    private val participantTokens = ArrayList<ParticipantToken>()
    private var participant: TransactionParticipant? = null
    private var reading = false

    /** A destructive change in the current transaction wants the WAL folded and truncated once it commits. */
    private var checkpointRequested = false

    val isOpen: Boolean get() = lock.withLock { connection != null }

    fun setParticipant(participant: TransactionParticipant) {
        lock.withLock { this.participant = participant }
    }

    /**
     * Runs [body] in a write transaction (`BEGIN IMMEDIATE`), or in a savepoint when already inside
     * one. Rolls back if [body] throws.
     *
     * @param writer [Writer.PROTOCOL_STORE] when the protocol storage itself writes: staged
     *   decryption state is then committed first, also inside a savepoint, so a write built on
     *   staged state can never be overtaken by it.
     */
    fun <T> write(writer: Writer = Writer.APP, body: (SqlConnection) -> T): T = lock.withLock {
        val connection = connection ?: storageFailure(StorageError.Closed)
        if (reading) storageFailure(StorageError.InvalidState("write inside a read"))
        if (depth > 0) return savepoint(connection, writer, body)
        try {
            connection.begin("IMMEDIATE")
        } catch (e: SqlException) {
            storageFailure(StorageError.Database(e), e)
        }
        depth = 1
        try {
            if (writer != Writer.DETACHED) flushParticipant(connection, writer == Writer.PROTOCOL_STORE)
            val result = body(connection)
            connection.commit()
            depth = 0
            val hooks = commitHooks.toList()
            commitHooks.clear()
            participantTokens.clear()
            val checkpoint = checkpointRequested
            checkpointRequested = false
            for (hook in hooks) runCatching { hook.run() }
            // Deleted or rewritten content must not survive as an older page image in the WAL
            // (secure_delete only zeroes the new image).
            if (checkpoint) checkpointQuietly(connection)
            result
        } catch (e: Throwable) {
            depth = 0
            commitHooks.clear()
            checkpointRequested = false
            rollbackQuietly(connection)
            rollBackParticipantTokens(fromDepth = 1)
            throw translated(e)
        }
    }

    private fun <T> savepoint(connection: SqlConnection, writer: Writer, body: (SqlConnection) -> T): T {
        val name = "s$depth"
        try {
            connection.savepoint(name)
        } catch (e: SqlException) {
            storageFailure(StorageError.Database(e), e)
        }
        depth += 1
        val level = depth
        try {
            if (writer == Writer.PROTOCOL_STORE) flushParticipant(connection, initiatedByProtocolStore = true)
            val result = body(connection)
            connection.release(name)
            depth = level - 1
            // What this savepoint kept now belongs to the enclosing level: a later sibling that
            // rolls back must not drop it.
            for (hook in commitHooks) if (hook.depth >= level) hook.depth = level - 1
            for (token in participantTokens) if (token.depth >= level) token.depth = level - 1
            return result
        } catch (e: Throwable) {
            depth = level - 1
            commitHooks.removeAll { it.depth >= level }
            try {
                connection.rollbackTo(name)
                connection.release(name)
            } catch (_: SqlException) {
            }
            rollBackParticipantTokens(fromDepth = level)
            throw translated(e)
        }
    }

    private fun flushParticipant(connection: SqlConnection, initiatedByProtocolStore: Boolean) {
        val participant = participant ?: return
        val token = participant.transactionDidBegin(connection, initiatedByProtocolStore) ?: return
        participantTokens += ParticipantToken(depth, participant, token)
    }

    private fun rollBackParticipantTokens(fromDepth: Int) {
        val undone = participantTokens.filter { it.depth >= fromDepth }
        participantTokens.removeAll { it.depth >= fromDepth }
        for (entry in undone.asReversed()) entry.participant.transactionDidRollBack(entry.token)
    }

    /**
     * Reads inside a deferred transaction, so multi-statement reads see one consistent snapshot.
     * Inside a write it simply runs.
     */
    fun <T> read(body: (SqlConnection) -> T): T = lock.withLock {
        val connection = connection ?: storageFailure(StorageError.Closed)
        if (depth > 0 || reading) return body(connection)
        try {
            connection.begin("DEFERRED")
        } catch (e: SqlException) {
            storageFailure(StorageError.Database(e), e)
        }
        reading = true
        try {
            body(connection)
        } catch (e: Throwable) {
            throw translated(e)
        } finally {
            reading = false
            try {
                connection.commit()
            } catch (_: SqlException) {
            }
        }
    }

    /**
     * Runs [body] holding the database lock, outside any explicit transaction: for reads plus
     * in-memory state that must stay consistent with transactions. [body] must not write.
     */
    fun <T> locked(body: (SqlConnection) -> T): T = lock.withLock {
        val connection = connection ?: storageFailure(StorageError.Closed)
        try {
            body(connection)
        } catch (e: Throwable) {
            throw translated(e)
        }
    }

    /**
     * Runs [hook] after the current top-level transaction commits (never if it rolls back).
     * Outside a transaction it runs immediately.
     */
    fun afterCommit(hook: () -> Unit) {
        lock.withLock {
            if (depth == 0) hook() else commitHooks += Hook(depth, hook)
        }
    }

    /**
     * Asks for [checkpoint] once the current top-level transaction has committed (immediately
     * outside a transaction). In WAL mode an UPDATE or DELETE appends a new page image and leaves
     * the old one, still holding the content, in `-wal` until a checkpoint; every path that destroys
     * or rewrites content calls this. Nested writes keep the request for the outer commit; a
     * rollback drops it.
     */
    fun checkpointAfterCommit() {
        lock.withLock {
            if (depth == 0) {
                val connection = connection
                if (connection != null && !reading) checkpointQuietly(connection)
            } else {
                checkpointRequested = true
            }
        }
    }

    /** Folds the WAL into the main file and truncates it, so deleted pages do not linger in the log. */
    fun checkpoint() {
        lock.withLock {
            val connection = connection ?: storageFailure(StorageError.Closed)
            if (depth != 0 || reading) return
            try {
                connection.pragma("PRAGMA wal_checkpoint(TRUNCATE)")
            } catch (e: SqlException) {
                storageFailure(StorageError.Database(e), e)
            }
        }
    }

    /** Checkpoints and closes. Further calls throw [StorageError.Closed]. */
    fun close() {
        lock.withLock {
            val connection = connection ?: return
            checkpointQuietly(connection)
            connection.close()
            this.connection = null
        }
    }

    private fun checkpointQuietly(connection: SqlConnection) {
        try {
            connection.pragma("PRAGMA wal_checkpoint(TRUNCATE)")
        } catch (_: SqlException) {
        }
    }

    private fun rollbackQuietly(connection: SqlConnection) {
        try {
            connection.rollback()
        } catch (_: SqlException) {
            // SQLite may already have rolled back on its own (an I/O error, a full disk).
        }
    }

    companion object {
        /** Opens (creating if needed) and migrates the database. */
        fun open(file: File, key: ByteArray, migrations: List<Migration>, driver: SqlDriver): EncryptedDatabase {
            val connection = try {
                SqlConnection(driver.open(file, key))
            } catch (e: SqlException) {
                storageFailure(StorageError.Database(e), e)
            }
            try {
                configure(connection)
                migrate(connection, migrations)
            } catch (e: Throwable) {
                connection.close()
                throw e
            }
            return EncryptedDatabase(file, connection)
        }

        private fun configure(connection: SqlConnection) {
            try {
                try {
                    connection.scalar("SELECT count(*) FROM sqlite_master")
                } catch (e: SqlException) {
                    if (e.isNotADatabase) storageFailure(StorageError.WrongKeyOrNotADatabase, e)
                    throw e
                }
                connection.pragma("PRAGMA busy_timeout = 5000")
                val mode = connection.pragma("PRAGMA journal_mode = WAL") as? String
                if (!mode.equals("wal", ignoreCase = true)) {
                    storageFailure(StorageError.Database(SqlException(SqlException.ERROR, "WAL unavailable")))
                }
                for (
                    pragma in listOf(
                        "PRAGMA synchronous = FULL",
                        "PRAGMA foreign_keys = ON",
                        "PRAGMA secure_delete = ON",
                        "PRAGMA temp_store = MEMORY",
                        "PRAGMA trusted_schema = OFF",
                    )
                ) {
                    connection.pragma(pragma)
                }
            } catch (e: SqlException) {
                storageFailure(StorageError.Database(e), e)
            }
        }

        fun migrate(connection: SqlConnection, migrations: List<Migration>) {
            val supported = migrations.maxOfOrNull { it.version } ?: 0
            val current = try {
                (connection.pragma("PRAGMA user_version") as? Long)?.toInt()
                    ?: storageFailure(StorageError.Database(SqlException(SqlException.ERROR, "user_version")))
            } catch (e: SqlException) {
                storageFailure(StorageError.Database(e), e)
            }
            if (current > supported) storageFailure(StorageError.SchemaTooNew(current, supported))
            for (migration in migrations.sortedBy { it.version }) {
                if (migration.version <= current) continue
                try {
                    connection.begin("IMMEDIATE")
                    for (statement in migration.statements) connection.execute(statement)
                    connection.execute("PRAGMA user_version = ${migration.version}")
                    connection.commit()
                } catch (e: SqlException) {
                    try {
                        connection.rollback()
                    } catch (_: SqlException) {
                    }
                    storageFailure(StorageError.MigrationFailed(migration.version, e), e)
                }
            }
        }

        fun translated(error: Throwable): Throwable =
            if (error is SqlException) StorageException(StorageError.Database(error), error) else error
    }
}
