package com.syconx.us2us.runtime.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.proto.attachment
import com.syconx.us2us.core.proto.dataMessage
import com.syconx.us2us.core.proto.text
import com.syconx.us2us.core.vault.DataKey
import com.syconx.us2us.runtime.vault.SecretKeyMaterial
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom
import java.util.UUID

/**
 * The storage on the phone's SQLCipher (the iOS EncryptionAtRestTests): the files are opaque, the
 * key is the raw HKDF key, a wrong key is refused without touching the files, the pragmas are
 * set, and transactions with savepoints behave as on iOS through sqlcipher-android.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var root: File
    private lateinit var layout: StorageLayout
    private val dek = ByteArray(32).also(SecureRandom()::nextBytes)

    @Before
    fun setUp() {
        root = File(context.noBackupFilesDir, "storage-device-test").also { it.deleteRecursively() }
        layout = StorageLayout(File(root, "storage"))
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun open(key: ByteArray = dek): LocalStorage = LocalStorage.open(layout, SecretKeyMaterial(key)).storage

    private fun databaseBytes(): ByteArray =
        layout.databaseFiles.filter { it.exists() }.fold(ByteArray(0)) { result, file -> result + file.readBytes() }

    private fun ByteArray.contains(text: String): Boolean {
        val needle = text.toByteArray(Charsets.UTF_8)
        outer@ for (start in 0..size - needle.size) {
            for (i in needle.indices) if (this[start + i] != needle[i]) continue@outer
            return true
        }
        return false
    }

    /**
     * A contact, a conversation, a message and an attachment file, all with markers. No account:
     * these tests need no libsignal, so they also run from a test APK without its native library.
     */
    private fun fill(storage: LocalStorage): ConversationId {
        val contact = AccountId(UUID.randomUUID())
        return storage.write { tx ->
            tx.contacts.create(contact, ContactState.ACCEPTED)
            tx.contacts.setProfile(ContactProfile(displayName = "MARKER-NAME"), contact)
            val conversation = tx.conversations.ensure(contact)
            tx.messages.storeIncoming(
                dataMessage {
                    messageId = ByteString.copyFrom(ByteArray(16).also(SecureRandom()::nextBytes))
                    sentAt = 1_800_000_000_000
                    text = text { body = "MARKER-BODY" }
                },
                conversation.id,
            )
            val withFile = dataMessage {
                messageId = ByteString.copyFrom(ByteArray(16).also(SecureRandom()::nextBytes))
                sentAt = 1_800_000_000_000
                attachment = attachment {
                    contentType = "image/jpeg"
                    caption = "MARKER-CAPTION"
                }
            }
            tx.messages.storeIncoming(withFile, conversation.id)
            val attachment = tx.attachments.forMessage(withFile.messageId, conversation.id).first()
            tx.attachments.storeFile("MARKER-FILE".toByteArray(), attachment.id)
            tx.conversations.setDraft("MARKER-DRAFT", conversation.id)
            conversation.id
        }
    }

    @Test
    fun theDatabaseFileAndItsWalContainNoPlaintextAndNoSqliteHeader() {
        val storage = open()
        fill(storage)
        val raw = databaseBytes()
        assertFalse(raw.copyOfRange(0, 16).contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)))
        for (marker in listOf("MARKER-NAME", "MARKER-BODY", "MARKER-CAPTION", "MARKER-DRAFT", "CREATE TABLE")) {
            assertFalse(marker, raw.contains(marker))
        }
        for (file in layout.filesDirectory.listFiles().orEmpty()) assertFalse(file.readBytes().contains("MARKER-FILE"))
        storage.close()
        assertFalse(databaseBytes().contains("MARKER-NAME"))
    }

    @Test
    fun theDatabaseCannotBeOpenedWithAWrongKeyAndIsLeftIntact() {
        val storage = open()
        val conversation = fill(storage)
        storage.close()
        val before = layout.databaseFile.readBytes()
        try {
            open(ByteArray(32) { 1 })
            fail("opened with a wrong DEK")
        } catch (e: StorageException) {
            assertEquals(StorageError.WrongKeyOrNotADatabase, e.error)
        }
        assertArrayEquals(before, layout.databaseFile.readBytes())
        val reopened = open()
        assertNotNull(reopened.read { it.conversations.get(conversation) })
        reopened.close()
    }

    @Test
    fun theSqlCipherKeyIsTheRawHkdfKey() {
        open().apply {
            fill(this)
            close()
        }
        val databaseKey = DataKey(dek).databaseKey()
        val raw = SQLiteDatabase.openDatabase(
            layout.databaseFile.path,
            SqlCipherDriver.rawKeyText(databaseKey),
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null,
            null,
        )
        raw.rawQuery("SELECT count(*) FROM contacts", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        raw.close()
        // The same bytes as a passphrase (through SQLCipher's KDF) do not open it.
        try {
            SQLiteDatabase.openDatabase(layout.databaseFile.path, databaseKey, null, SQLiteDatabase.OPEN_READWRITE, null, null).close()
            fail("opened through the passphrase KDF")
        } catch (_: Exception) {
        }
    }

    @Test
    fun sqlCipherIsLinkedKeyedAndConfiguredLikeOnIos() {
        val storage = open()
        fill(storage)
        storage.read { tx ->
            val connection = tx.connection
            assertTrue((connection.pragma("PRAGMA cipher_version") as String).isNotEmpty())
            assertEquals("wal", connection.pragma("PRAGMA journal_mode"))
            assertEquals(2L, connection.pragma("PRAGMA synchronous"))
            assertEquals(1L, connection.pragma("PRAGMA foreign_keys"))
            assertEquals(1L, connection.pragma("PRAGMA secure_delete"))
            assertEquals(2L, connection.pragma("PRAGMA temp_store"))
            assertEquals(0L, connection.pragma("PRAGMA trusted_schema"))
            assertEquals(Schema.latestVersion.toLong(), connection.pragma("PRAGMA user_version"))
            // No integrity problems reported.
            assertTrue(connection.query("PRAGMA cipher_integrity_check").isEmpty())
            val tables = connection.query("SELECT name FROM sqlite_master WHERE type = 'table'").map { it.text("name") }
            assertFalse("android_metadata" in tables)
        }
        storage.close()
    }

    @Test
    fun savepointsRollBackOnlyTheirOwnPartThroughSqlCipher() {
        val storage = open()
        storage.write { tx ->
            tx.envelopes.record("outer")
            try {
                storage.write { inner ->
                    inner.envelopes.record("inner")
                    error("fails")
                }
            } catch (_: IllegalStateException) {
            }
            storage.write { inner -> inner.envelopes.record("kept") }
        }
        storage.read { tx ->
            assertTrue(tx.envelopes.contains("outer"))
            assertFalse(tx.envelopes.contains("inner"))
            assertTrue(tx.envelopes.contains("kept"))
        }
        try {
            storage.write { tx ->
                tx.envelopes.record("rolled back")
                error("fails")
            }
        } catch (_: IllegalStateException) {
        }
        assertFalse(storage.read { it.envelopes.contains("rolled back") })
        storage.close()
    }

    @Test
    fun everythingSurvivesClosingAndReopening() {
        val storage = open()
        val conversation = fill(storage)
        storage.close()
        val reopened = open()
        reopened.read { tx ->
            assertEquals("MARKER-DRAFT", tx.conversations.draft(conversation))
            val messages = tx.messages.page(conversation)
            assertEquals("MARKER-BODY", messages.first().content?.text?.body)
            val attachment = tx.attachments.forMessage(messages.last().messageId, conversation).first()
            assertArrayEquals("MARKER-FILE".toByteArray(), tx.attachments.readFile(attachment.id))
        }
        reopened.close()
        LocalStorage.destroyAll(layout)
        assertFalse(layout.root.exists())
    }
}
