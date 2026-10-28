package com.syconx.us2us.runtime.storage

import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.runtime.vault.VaultFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Schema versions, transactions, savepoints, crash safety of files (the iOS MigrationAndCrashTests). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationAndCrashTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun rawDatabase(file: File, key: ByteArray = ByteArray(32)): EncryptedDatabase {
        file.parentFile?.mkdirs()
        return EncryptedDatabase.open(file, key, Schema.migrations, PlainSqlDriver)
    }

    @Test
    fun aNewDatabaseIsCreatedAtTheLatestVersionInWalMode() {
        val database = rawDatabase(File(temporary.newFolder(), "main.sqlite"))
        database.read { connection ->
            assertEquals(Schema.latestVersion.toLong(), connection.pragma("PRAGMA user_version"))
            assertEquals("wal", connection.pragma("PRAGMA journal_mode"))
            assertEquals(1L, connection.pragma("PRAGMA foreign_keys"))
            assertEquals(1L, connection.pragma("PRAGMA secure_delete"))
            // FULL = 2
            assertEquals(2L, connection.pragma("PRAGMA synchronous"))
            val tables = connection.query("SELECT name FROM sqlite_master WHERE type = 'table'").map { it.text("name") }.toSet()
            for (table in listOf(
                "local_identity", "local_account", "identities", "pre_keys", "signed_pre_keys", "kyber_pre_keys",
                "kyber_last_resort_uses", "sessions", "key_id_counters", "contacts", "conversations", "messages",
                "reactions", "pending_deletes", "attachments", "processed_envelopes", "outbox", "removed_messages",
                "community_states", "community_messages", "community_outbox",
            )) {
                assertTrue(table, table in tables)
            }
            assertFalse("no Android metadata table", "android_metadata" in tables)
        }
        database.close()
    }

    @Test
    fun anOlderDatabaseIsUpgradedInPlaceKeepingItsData() {
        val file = File(temporary.newFolder(), "main.sqlite")
        val old = EncryptedDatabase.open(file, ByteArray(32), Schema.migrations.take(2), PlainSqlDriver)
        old.write { connection ->
            connection.run("INSERT INTO processed_envelopes (relay_id, processed_at) VALUES (?, ?)", "env-1", 1_760_000_123_456L)
            connection.run(
                "INSERT INTO identities (account, identity_key, approved, verified) VALUES (?, ?, 1, 1)",
                ByteArray(16) { 1 },
                ByteArray(33) { 2 },
            )
        }
        old.close()
        val upgraded = rawDatabase(file)
        upgraded.read { connection ->
            assertEquals(Schema.latestVersion.toLong(), connection.pragma("PRAGMA user_version"))
            // v3 keeps only the day of processing.
            assertEquals(1_760_000_123_456L - 1_760_000_123_456L % 86_400_000L, connection.scalar("SELECT processed_at FROM processed_envelopes"))
            // v4: identities verified before keep their verification, without a record.
            val row = connection.queryOne("SELECT verified, verified_at, previous_verified FROM identities")!!
            assertTrue(row.bool("verified"))
            assertNull(row.long("verified_at"))
            assertNull(row.long("previous_verified"))
        }
        upgraded.close()
    }

    @Test
    fun theLatestSchemaIsVersion11WithKeySlottedCommunities() {
        assertEquals(11, Schema.latestVersion)
        val database = rawDatabase(File(temporary.newFolder(), "main.sqlite"))
        database.read { connection ->
            val contactColumns = connection.query("PRAGMA table_info(contacts)").map { it.text("name") }
            assertTrue("an outgoing request records when it was first sent (M-9)", "request_sent_at" in contactColumns)
            val columns = connection.query("PRAGMA table_info(community_states)").map { it.text("name") }
            assertTrue("a community has its own key slot", "slot_id" in columns)
            val bodyType = connection.query("PRAGMA table_info(community_messages)").first { it.text("name") == "body" }.text("type")
            assertEquals("bodies are sealed items, never text", "BLOB", bodyType)
            val tables = connection.query("SELECT name FROM sqlite_master WHERE type = 'table'").map { it.text("name") }.toSet()
            for (table in listOf("community_recipients", "community_pending_deletes", "community_removed", "community_receipt_queue")) assertTrue(table, table in tables)
            val sealed = connection.query("PRAGMA table_info(community_messages)").filter { it.text("name") in setOf("attachment", "delete_token") }
            assertTrue("pointers and tokens are sealed items", sealed.size == 2 && sealed.all { it.text("type") == "BLOB" })
        }
        database.close()
    }

    @Test
    fun aVersion10DatabaseKeepsItsOutgoingRequestsAnswerable() {
        // M-9: rows from before v11 are treated as sent, so an ACCEPT still ends them after the upgrade.
        val file = File(temporary.newFolder(), "main.sqlite")
        val old = EncryptedDatabase.open(file, ByteArray(32), Schema.migrations.take(10), PlainSqlDriver)
        old.write { connection ->
            val insert = "INSERT INTO contacts (account, request_state, created_at) VALUES (?, ?, ?)"
            connection.run(insert, ByteArray(16) { 1 }, "outgoingRequest", 1_760_000_000_000L)
            connection.run(insert, ByteArray(16) { 2 }, "accepted", 1_760_000_000_001L)
        }
        old.close()
        val upgraded = rawDatabase(file)
        upgraded.read { connection ->
            assertEquals(1_760_000_000_000L, connection.scalar("SELECT request_sent_at FROM contacts WHERE request_state = 'outgoingRequest'"))
            assertNull(connection.queryOne("SELECT request_sent_at FROM contacts WHERE request_state = 'accepted'")!!.long("request_sent_at"))
        }
        upgraded.close()
    }

    @Test
    fun aVersion7DatabaseIsUpgradedKeepingItsCommunities() {
        val file = File(temporary.newFolder(), "main.sqlite")
        val old = EncryptedDatabase.open(file, ByteArray(32), Schema.migrations.take(7), PlainSqlDriver)
        old.write { connection ->
            connection.run("INSERT INTO community_states (id, owner_key, name, slot_id, state, signature, active) VALUES ('c', 'k', 'n', ?, 's', 'g', 1)", ByteArray(16) { 6 })
            connection.run("INSERT INTO community_messages (community_id, sender, message_id, sent_at, body, status) VALUES ('c', 'me', 'm1', 100, ?, 'sent')", byteArrayOf(1))
            connection.run("INSERT INTO community_messages (community_id, sender, message_id, sent_at, body, status) VALUES ('c', 'them', 'm2', 200, ?, 'received')", byteArrayOf(2))
            connection.run("INSERT INTO community_outbox (community_id, message_id, recipient, revision, envelope) VALUES ('c', 'm1', 'them', 1, ?)", byteArrayOf(3))
        }
        old.close()
        val upgraded = rawDatabase(file)
        upgraded.write { connection ->
            assertEquals(Schema.latestVersion.toLong(), connection.pragma("PRAGMA user_version"))
            val rows = connection.query("SELECT * FROM community_messages ORDER BY sent_at")
            assertEquals(listOf("m1", "m2"), rows.map { it.text("message_id") })
            for (row in rows) {
                assertEquals("ordered as before", row.long("sent_at"), row.long("received_at"))
                assertEquals("earlier messages are not unread, and no READ goes for them", row.long("sent_at"), row.long("displayed_at"))
                assertNull(row.blob("attachment")); assertNull(row.long("deleted_by")); assertNull(row.blob("delete_token"))
            }
            assertEquals(byteArrayOf(2).toList(), rows[1].blob("body")!!.toList())
            assertNull(connection.queryOne("SELECT payload FROM community_outbox")!!.blob("payload"))
            connection.run("INSERT INTO community_recipients (community_id, message_id, member, transport) VALUES ('c', 'm1', 'them', 'accepted')")
            connection.run("INSERT INTO community_pending_deletes (community_id, target_sender, target_id, by_owner, remembered_at) VALUES ('c', 'x', 'y', 0, 1)")
            connection.run("INSERT INTO community_removed (community_id, sender, message_id, removed_at) VALUES ('c', 'x', 'z', 1)")
            connection.run("INSERT INTO community_receipt_queue (community_id, recipient, type, message_id, queued_at) VALUES ('c', 'them', 1, 'm2', 1)")
            connection.run("DELETE FROM community_states WHERE id = 'c'")
            for (table in listOf("community_messages", "community_outbox", "community_recipients", "community_pending_deletes", "community_removed", "community_receipt_queue")) {
                assertEquals("$table goes with the community", 0L, connection.scalar("SELECT count(*) FROM $table"))
            }
        }
        upgraded.close()
    }

    @Test
    fun aVersion6DatabaseIsUpgradedTo7KeepingItsDataAndAddingCommunities() {
        val file = File(temporary.newFolder(), "main.sqlite")
        val old = EncryptedDatabase.open(file, ByteArray(32), Schema.migrations.take(6), PlainSqlDriver)
        old.read { assertEquals(6L, it.pragma("PRAGMA user_version")) }
        old.write { connection ->
            connection.run("INSERT INTO processed_envelopes (relay_id, processed_at) VALUES (?, ?)", "env-6", 1_760_000_000_000L)
            connection.run(
                "INSERT INTO identities (account, identity_key, approved, verified) VALUES (?, ?, 1, 0)",
                ByteArray(16) { 3 },
                ByteArray(33) { 4 },
            )
        }
        old.close()
        val upgraded = EncryptedDatabase.open(file, ByteArray(32), Schema.migrations.take(7), PlainSqlDriver)
        upgraded.write { connection ->
            assertEquals(7L, connection.pragma("PRAGMA user_version"))
            assertEquals(1L, connection.scalar("SELECT count(*) FROM processed_envelopes WHERE relay_id = 'env-6'"))
            assertEquals(1L, connection.scalar("SELECT count(*) FROM identities"))
            assertEquals(0L, connection.scalar("SELECT count(*) FROM community_states"))
            connection.run("INSERT INTO community_states (id, owner_key, name, slot_id) VALUES ('c', 'k', 'n', ?)", ByteArray(16) { 5 })
            connection.run("INSERT INTO community_messages (community_id, sender, message_id, sent_at, body, status) VALUES ('c', 's', 'm', 1, ?, 'received')", byteArrayOf(1))
            connection.run("DELETE FROM community_states WHERE id = 'c'")
            assertEquals("a forgotten community takes its history with it", 0L, connection.scalar("SELECT count(*) FROM community_messages"))
        }
        upgraded.close()
    }

    @Test
    fun aDatabaseFromANewerAppVersionIsRefusedNotDamaged() {
        val file = File(temporary.newFolder(), "main.sqlite")
        rawDatabase(file).apply {
            write { it.execute("PRAGMA user_version = 99") }
            close()
        }
        try {
            rawDatabase(file)
            fail("expected schemaTooNew")
        } catch (e: StorageException) {
            assertEquals(StorageError.SchemaTooNew(99, Schema.latestVersion), e.error)
        }
        val reopened = EncryptedDatabase.open(file, ByteArray(32), Schema.migrations + Migration(99, emptyList()), PlainSqlDriver)
        reopened.read { assertEquals(99L, it.pragma("PRAGMA user_version")) }
        reopened.close()
    }

    @Test
    fun aFailingMigrationRollsBackCompletelyAndCanBeRetried() {
        val file = File(temporary.newFolder(), "main.sqlite")
        rawDatabase(file).close()
        val nextVersion = Schema.latestVersion + 1
        val broken = Migration(nextVersion, listOf("CREATE TABLE extra (id INTEGER PRIMARY KEY)", "INSERT INTO missing VALUES (1)"))
        try {
            EncryptedDatabase.open(file, ByteArray(32), Schema.migrations + broken, PlainSqlDriver)
            fail("expected a failed migration")
        } catch (e: StorageException) {
            assertEquals(nextVersion, (e.error as StorageError.MigrationFailed).version)
        }
        val fixed = Migration(nextVersion, listOf("CREATE TABLE extra (id INTEGER PRIMARY KEY)"))
        val database = EncryptedDatabase.open(file, ByteArray(32), Schema.migrations + fixed, PlainSqlDriver)
        database.read { assertEquals(nextVersion.toLong(), it.pragma("PRAGMA user_version")) }
        database.close()
    }

    @Test
    fun anUncommittedTransactionIsInvisibleAndACommittedOneDurable() {
        val file = File(temporary.newFolder(), "main.sqlite")
        val database = rawDatabase(file)
        database.write { it.run("INSERT INTO processed_envelopes (relay_id, processed_at) VALUES ('kept', 0)") }
        try {
            database.write {
                it.run("INSERT INTO processed_envelopes (relay_id, processed_at) VALUES ('lost', 0)")
                error("crash")
            }
        } catch (_: IllegalStateException) {
        }
        database.close()
        val reopened = rawDatabase(file)
        reopened.read { connection ->
            assertEquals(listOf("kept"), connection.query("SELECT relay_id FROM processed_envelopes").map { it.text("relay_id") })
        }
        reopened.close()
    }

    @Test
    fun aSavepointFailureInsideATransactionKeepsTheOuterChanges() {
        val database = rawDatabase(File(temporary.newFolder(), "main.sqlite"))
        val hooks = mutableListOf<String>()
        database.write { connection ->
            connection.run("INSERT INTO processed_envelopes (relay_id, processed_at) VALUES ('outer', 0)")
            database.write { inner ->
                inner.run("INSERT INTO processed_envelopes (relay_id, processed_at) VALUES ('first', 0)")
                database.afterCommit { hooks += "first" }
            }
            try {
                database.write { inner ->
                    inner.run("INSERT INTO processed_envelopes (relay_id, processed_at) VALUES ('second', 0)")
                    database.afterCommit { hooks += "second" }
                    error("fails")
                }
            } catch (_: IllegalStateException) {
            }
            assertTrue(hooks.isEmpty())
        }
        database.read { connection ->
            assertEquals(
                listOf("first", "outer"),
                connection.query("SELECT relay_id FROM processed_envelopes ORDER BY relay_id").map { it.text("relay_id") },
            )
        }
        // The successful sibling's hook survives the failed one, and runs after the outer commit.
        assertEquals(listOf("first"), hooks)
        database.close()
    }

    @Test
    fun writingInsideAReadIsRefused() {
        val database = rawDatabase(File(temporary.newFolder(), "main.sqlite"))
        try {
            database.read { database.write { } }
            fail("expected invalidState")
        } catch (e: StorageException) {
            assertTrue(e.error is StorageError.InvalidState)
        }
        database.close()
    }

    @Test
    fun fileDeletionsHappenOnlyAfterTheTransactionCommits() {
        val environment = TestEnvironment(temporary.newFolder())
        val storage = environment.open()
        storage.saveAccount()
        val conversation = storage.conversationWith()
        val message = attachmentMessage()
        val attachmentId = storage.write { tx ->
            tx.messages.storeIncoming(message, conversation.id)
            val attachment = tx.attachments.forMessage(message.messageId, conversation.id).single()
            tx.attachments.storeFile("photo".toByteArray(), attachment.id)
            attachment.id
        }
        val file = storage.files.file(attachmentId)
        assertTrue(file.exists())
        // Rolled back: the file stays.
        try {
            storage.write { tx ->
                tx.messages.deleteLocally(listOf(message.messageId), conversation.id)
                error("rolled back")
            }
        } catch (_: IllegalStateException) {
        }
        assertTrue(file.exists())
        storage.write { tx -> tx.messages.deleteLocally(listOf(message.messageId), conversation.id) }
        assertFalse(file.exists())
        storage.close()
    }

    @Test
    fun anInterruptedAtomicWriteNeverDamagesTheExistingFile() {
        val directory = temporary.newFolder()
        val file = File(directory, "x.item")
        VaultFiles.write(file, "old".toByteArray())
        // A crash mid-write leaves only the temporary file next to it.
        File(directory, "x.item.pending").writeBytes("half".toByteArray())
        assertEquals("old", String(file.readBytes()))
    }

    @Test
    fun aTamperedKeySlotIsDetected() {
        val environment = TestEnvironment(temporary.newFolder())
        val storage = environment.open()
        storage.saveAccount()
        val conversation = storage.conversationWith(state = ContactState.ACCEPTED)
        storage.close()
        val slot = File(environment.layout.slotDirectory, "${conversation.slotId.hex}.slot")
        val bytes = slot.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        slot.writeBytes(bytes)
        val reopened = environment.open()
        try {
            reopened.read { tx -> tx.conversations.draft(conversation.id) }
            reopened.write { tx -> tx.conversations.setDraft("x", conversation.id) }
            fail("expected an integrity error")
        } catch (e: StorageException) {
            assertEquals(StorageError.Integrity, e.error)
        }
        assertNotNull(reopened.read { tx -> tx.conversations.get(conversation.id) })
        reopened.close()
    }
}
