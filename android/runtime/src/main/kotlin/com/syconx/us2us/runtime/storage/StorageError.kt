package com.syconx.us2us.runtime.storage

/**
 * Everything that can go wrong in local storage. Descriptions never contain message content,
 * names or keys.
 */
sealed interface StorageError {
    /** The storage was closed (vault locked); unlock and open it again. */
    data object Closed : StorageError

    /** The linked SQLite library is not SQLCipher. Storage refuses to run rather than write anything in plaintext. */
    data object EncryptionUnavailable : StorageError

    /** The key does not open the database, or the file is not a database. */
    data object WrongKeyOrNotADatabase : StorageError

    /** The database was written by a newer app version. */
    data class SchemaTooNew(val found: Int, val supported: Int) : StorageError

    data class MigrationFailed(val version: Int, val cause: SqlException) : StorageError

    data class Database(val cause: SqlException) : StorageError

    /** An item or key slot failed authentication (tampering or a wrong key). */
    data object Integrity : StorageError

    /** The conversation's key slot is gone: it was crypto-shredded. */
    data object ConversationShredded : StorageError

    data object NotFound : StorageError

    /** The operation is not allowed in the current state (e.g. a §6.4 transition). */
    data class InvalidState(val reason: String) : StorageError

    data class Io(val reason: String) : StorageError
}

/** The text of a [StorageError], safe to log. */
val StorageError.description: String
    get() = when (this) {
        StorageError.Closed -> "Storage is locked."
        StorageError.EncryptionUnavailable -> "SQLCipher is not available; refusing to store data unencrypted."
        StorageError.WrongKeyOrNotADatabase -> "The database cannot be opened with this key."
        is StorageError.SchemaTooNew -> "Database schema $found is newer than this app supports ($supported)."
        is StorageError.MigrationFailed -> "Migration $version failed: ${cause.message}"
        is StorageError.Database -> cause.message.orEmpty()
        StorageError.Integrity -> "Stored data failed its integrity check."
        StorageError.ConversationShredded -> "This conversation was wiped."
        StorageError.NotFound -> "Not found."
        is StorageError.InvalidState -> "Invalid state: $reason"
        is StorageError.Io -> "File error: $reason"
    }

class StorageException(val error: StorageError, cause: Throwable? = null) : Exception(error.description, cause)

internal fun storageFailure(error: StorageError, cause: Throwable? = null): Nothing = throw StorageException(error, cause)
