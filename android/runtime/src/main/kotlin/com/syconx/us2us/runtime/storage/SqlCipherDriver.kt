package com.syconx.us2us.runtime.storage

import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDoneException
import android.database.sqlite.SQLiteException
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException
import net.zetetic.database.sqlcipher.SQLiteStatement
import java.io.File

/**
 * SQLCipher (net.zetetic sqlcipher-android) as the storage's SQL binding: one connection per
 * database, keyed with the raw-key syntax.
 *
 * The keying order is the iOS one: a fail-closed check that the library is SQLCipher, then
 * `cipher_log_level = NONE`, the key, `cipher_memory_security = ON`. SQLCipher then reads the
 * schema, so a wrong key fails right here. No journal flag is passed: the pool keeps a single
 * connection, and [EncryptedDatabase] switches it to WAL itself.
 */
internal object SqlCipherDriver : SqlDriver {
    private val library by lazy { System.loadLibrary("sqlcipher") }

    override fun open(file: File, rawKey: ByteArray): SqlEngine {
        library
        val keyText = rawKeyText(rawKey)
        try {
            val database = SQLiteDatabase.openDatabase(
                file.path,
                keyText,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
                KeepFiles,
                Keying,
            )
            return CipherEngine(database)
        } catch (_: CipherUnavailableException) {
            storageFailure(StorageError.EncryptionUnavailable)
        } catch (e: SQLiteNotADatabaseException) {
            storageFailure(StorageError.WrongKeyOrNotADatabase, e)
        } catch (e: SQLiteDatabaseCorruptException) {
            storageFailure(StorageError.WrongKeyOrNotADatabase, e)
        } catch (e: SQLException) {
            throw SqlException.of(e)
        } finally {
            // SQLCipher keeps this very array for reconnecting; the pool never opens a second
            // connection here, and a reconnect with the zeroed text fails instead of opening.
            keyText.fill(0)
        }
    }

    /**
     * The ASCII text `x'<64 hex digits>'`: SQLCipher's raw-key syntax skips its passphrase KDF.
     * The key is already uniformly random (HKDF output, PROTOCOL.md §5.3).
     */
    internal fun rawKeyText(rawKey: ByteArray): ByteArray {
        require(rawKey.size == 32) { "SQLCipher raw keys are 32 bytes" }
        val digits = "0123456789ABCDEF".toByteArray(Charsets.US_ASCII)
        val text = ByteArray(3 + 64)
        text[0] = 'x'.code.toByte()
        text[1] = '\''.code.toByte()
        for ((offset, byte) in rawKey.withIndex()) {
            val value = byte.toInt() and 0xFF
            text[2 + offset * 2] = digits[value ushr 4]
            text[3 + offset * 2] = digits[value and 0x0F]
        }
        text[66] = '\''.code.toByte()
        return text
    }

    private class CipherUnavailableException : SQLiteException("the SQLite library is not SQLCipher")

    private object Keying : SQLiteDatabaseHook {
        override fun preKey(connection: SQLiteConnection) {
            // Fail closed: a plain SQLite would silently ignore the key.
            val version = try {
                connection.executeForString("PRAGMA cipher_version", null, null)
            } catch (_: SQLiteDoneException) {
                null
            }
            if (version.isNullOrEmpty()) throw CipherUnavailableException()
            // SQLCipher logs nothing about this database (errors still surface as StorageError).
            connection.executeRaw("PRAGMA cipher_log_level = NONE", null, null)
        }

        override fun postKey(connection: SQLiteConnection) {
            // Wipe memory SQLCipher frees (keys, decrypted pages).
            connection.executeRaw("PRAGMA cipher_memory_security = ON", null, null)
        }
    }

    /** The default handler deletes a "corrupt" file, which is also what a wrong key looks like. */
    private object KeepFiles : DatabaseErrorHandler {
        override fun onCorruption(database: SQLiteDatabase, exception: SQLiteException) = Unit
    }

    private class CipherEngine(private val database: SQLiteDatabase) : SqlEngine {
        override fun execute(sql: String) = database.execSQL(sql)

        override fun query(sql: String, arguments: Array<Any?>): Cursor = database.rawQuery(sql, *arguments)

        override fun update(sql: String, arguments: Array<Any?>): Int =
            database.compileStatement(sql).use { it.bind(arguments).executeUpdateDelete() }

        override fun insert(sql: String, arguments: Array<Any?>): Long =
            database.compileStatement(sql).use { it.bind(arguments).executeInsert() }

        override fun close() = database.close()

        private fun SQLiteStatement.bind(arguments: Array<Any?>): SQLiteStatement {
            arguments.forEachIndexed { offset, value ->
                val index = offset + 1
                when (value) {
                    null -> bindNull(index)
                    is Long -> bindLong(index, value)
                    is Double -> bindDouble(index, value)
                    is String -> bindString(index, value)
                    is ByteArray -> bindBlob(index, value)
                    else -> throw IllegalArgumentException("cannot bind ${value.javaClass.simpleName}")
                }
            }
            return this
        }
    }
}
