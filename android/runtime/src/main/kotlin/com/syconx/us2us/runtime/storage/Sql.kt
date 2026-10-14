package com.syconx.us2us.runtime.storage

import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import com.google.protobuf.ByteString
import java.io.Closeable
import java.io.File
import java.time.Instant

// A deliberately small layer over SQLite: one connection, typed bindings, rows read in full. The
// storage runs it under the lock of EncryptedDatabase; nothing here is thread-safe on its own.

/**
 * An SQLite/SQLCipher failure. The message comes from SQLite and never contains bound values (it
 * can name tables, columns or constraints).
 */
class SqlException(
    /** The primary result code (`SQLITE_CONSTRAINT` = 19, `SQLITE_NOTADB` = 26, …). */
    val code: Int,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** `SQLITE_NOTADB`: wrong key, or not a database at all. */
    val isNotADatabase: Boolean get() = code == NOT_A_DATABASE
    val isConstraintViolation: Boolean get() = code == CONSTRAINT
    val isBusy: Boolean get() = code == BUSY || code == LOCKED

    override fun toString(): String = "SqlException($code): $message"

    companion object {
        const val ERROR = 1
        const val BUSY = 5
        const val LOCKED = 6
        const val IO = 10
        const val CORRUPT = 11
        const val FULL = 13
        const val CANT_OPEN = 14
        const val CONSTRAINT = 19
        const val NOT_A_DATABASE = 26

        private val CODE = Regex("""\(code (\d+)""")

        /** Translates the exception of either SQLite binding (SQLCipher's or the platform's). */
        internal fun of(e: SQLException): SqlException {
            val reported = CODE.find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.and(0xFF)
            val code = reported ?: when {
                e.javaClass.simpleName == "SQLiteNotADatabaseException" -> NOT_A_DATABASE
                e is SQLiteConstraintException -> CONSTRAINT
                e is SQLiteDatabaseCorruptException -> CORRUPT
                e is SQLiteFullException -> FULL
                e is SQLiteDiskIOException -> IO
                e is SQLiteDatabaseLockedException -> BUSY
                e is SQLiteCantOpenDatabaseException -> CANT_OPEN
                else -> ERROR
            }
            return SqlException(code, e.message ?: e.javaClass.simpleName, e)
        }
    }
}

/** One result row, addressed by column index or name; values are `Long`, `Double`, `String`, `ByteArray` or `null`. */
internal class Row(private val values: Array<Any?>, private val columns: Map<String, Int>) {
    operator fun get(index: Int): Any? = values[index]

    operator fun get(name: String): Any? = values[columns[name] ?: error("no column $name")]

    fun long(name: String): Long? = this[name] as? Long

    fun int(name: String): Int? = long(name)?.toInt()

    fun bool(name: String): Boolean = (long(name) ?: 0L) != 0L

    fun text(name: String): String? = this[name] as? String

    fun blob(name: String): ByteArray? = this[name] as? ByteArray

    fun bytes(name: String): ByteString? = blob(name)?.let(ByteString::copyFrom)

    fun instant(name: String): Instant? = long(name)?.let(Instant::ofEpochMilli)
}

/**
 * The calls the storage needs from an SQLite binding: SQLCipher's in the app, the platform's in
 * JVM tests. Arguments are already normalized to `Long`, `Double`, `String`, `ByteArray` or `null`.
 */
internal interface SqlEngine : Closeable {
    /** One statement that returns no rows. */
    fun execute(sql: String)

    fun query(sql: String, arguments: Array<Any?>): Cursor

    /** Runs one statement; returns how many rows it changed. */
    fun update(sql: String, arguments: Array<Any?>): Int

    /** Runs one `INSERT`; returns the new row id. */
    fun insert(sql: String, arguments: Array<Any?>): Long
}

/** Opens a database file keyed with a raw 32-byte key. */
internal fun interface SqlDriver {
    /** @throws StorageException ([StorageError.WrongKeyOrNotADatabase], [StorageError.EncryptionUnavailable]) or [SqlException]. */
    fun open(file: File, rawKey: ByteArray): SqlEngine
}

/** One open connection with typed bindings; SQLite errors surface as [SqlException]. */
internal class SqlConnection(private val engine: SqlEngine) {
    private var closed = false

    /** One statement without bindings or results. */
    fun execute(sql: String) = translating { engine.execute(sql) }

    /** A `PRAGMA`; returns the first column of its first row, if it has one. */
    fun pragma(sql: String): Any? = query(sql).firstOrNull()?.get(0)

    /** Runs one statement; returns the number of rows it changed. */
    fun run(sql: String, vararg arguments: Any?): Int = translating { engine.update(sql, normalized(arguments)) }

    /** Runs one `INSERT`; returns the new row id. */
    fun insert(sql: String, vararg arguments: Any?): Long = translating { engine.insert(sql, normalized(arguments)) }

    fun query(sql: String, vararg arguments: Any?): List<Row> =
        translating { engine.query(sql, normalized(arguments)).use { it.readRows() } }

    fun queryOne(sql: String, vararg arguments: Any?): Row? = query(sql, *arguments).firstOrNull()

    /** The first column of the first row. */
    fun scalar(sql: String, vararg arguments: Any?): Any? = queryOne(sql, *arguments)?.get(0)

    // Transaction control goes to SQLite verbatim, as on iOS. Both Android bindings look at the
    // first three letters of a statement and handle BEGIN, COMMIT and ROLLBACK themselves (a
    // ROLLBACK TO would end the whole transaction); behind a leading ';', which SQLite skips as an
    // empty statement, they pass it on unchanged.

    fun begin(mode: String) = execute(";BEGIN $mode")

    fun commit() = execute(";COMMIT")

    fun rollback() = execute(";ROLLBACK")

    fun savepoint(name: String) = execute(";SAVEPOINT $name")

    fun release(name: String) = execute(";RELEASE $name")

    fun rollbackTo(name: String) = execute(";ROLLBACK TO $name")

    fun close() {
        if (closed) return
        closed = true
        try {
            engine.close()
        } catch (_: SQLException) {
        }
    }

    private inline fun <T> translating(block: () -> T): T = try {
        block()
    } catch (e: SQLException) {
        throw SqlException.of(e)
    }

    private fun normalized(arguments: Array<out Any?>): Array<Any?> = Array(arguments.size) { argument(arguments[it]) }

    private fun argument(value: Any?): Any? = when (value) {
        null -> null
        is Long, is Double, is String, is ByteArray -> value
        is Int -> value.toLong()
        is Boolean -> if (value) 1L else 0L
        is ByteString -> value.toByteArray()
        is Instant -> value.toEpochMilli()
        is Float -> value.toDouble()
        is Short -> value.toLong()
        is Byte -> value.toLong()
        else -> throw IllegalArgumentException("cannot bind ${value.javaClass.simpleName}")
    }

    private fun Cursor.readRows(): List<Row> {
        val names = columnNames
        val index = HashMap<String, Int>(names.size * 2)
        names.forEachIndexed { i, name -> index.putIfAbsent(name, i) }
        val rows = ArrayList<Row>()
        while (moveToNext()) {
            val values = arrayOfNulls<Any>(names.size)
            for (i in names.indices) {
                values[i] = when (getType(i)) {
                    Cursor.FIELD_TYPE_INTEGER -> getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
                    Cursor.FIELD_TYPE_STRING -> getString(i)
                    Cursor.FIELD_TYPE_BLOB -> getBlob(i) ?: ByteArray(0)
                    else -> null
                }
            }
            rows += Row(values, index)
        }
        return rows
    }
}
