package com.syconx.us2us.runtime.storage

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.community.CommunityMember
import com.syconx.us2us.core.community.CommunityState
import com.syconx.us2us.core.encoding.Base64Url
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.libsignal.protocol.IdentityKeyPair
import java.io.File
import java.util.UUID

/**
 * A chat's own background (schema v12, the same step as iOS's v12): each chat keeps its own, and
 * it goes with the chat however that ends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatBackgroundStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var environment: TestEnvironment
    private lateinit var storage: LocalStorage
    private val photo = "JPEG-BYTES".toByteArray()

    @Before fun open() {
        environment = TestEnvironment(temporary.newFolder())
        storage = environment.open()
        storage.saveAccount()
    }

    @After fun close() = storage.close()

    private fun rowCount(): Int = storage.database.locked { it.query("SELECT 1 FROM chat_backgrounds").size }

    private fun community(): AccountId {
        val identity = IdentityKeyPair.generate()
        val owner = AccountId(UUID(0, 1))
        val key = Base64Url.encode(identity.publicKey.serialize())
        val id = newAccountId()
        val repository = CommunityRepository(storage)
        repository.pin(id, key, "topluluk")
        repository.save(CommunityState(id, 1, "private", owner, key, "", listOf(CommunityMember(owner, key))).sign(identity))
        return id
    }

    @Test fun v12AddsTheTableToAV11DatabaseAndKeepsWhatWasThere() {
        val file = File(temporary.newFolder(), "main.sqlite")
        val old = EncryptedDatabase.open(file, ByteArray(32), Schema.migrations.take(11), PlainSqlDriver)
        val account = ByteArray(16) { 7 }
        old.write { connection ->
            connection.run("INSERT INTO contacts (account, request_state, created_at) VALUES (?, ?, ?)", account, "accepted", 1_700_000_000_000L)
        }
        old.close()
        val upgraded = EncryptedDatabase.open(file, ByteArray(32), Schema.migrations, PlainSqlDriver)
        upgraded.read { connection ->
            assertEquals(12L, connection.pragma("PRAGMA user_version"))
            assertEquals(1, connection.query("SELECT 1 FROM contacts WHERE account = ?", account).size)
            assertEquals(0, connection.query("SELECT 1 FROM chat_backgrounds").size)
        }
        upgraded.close()
    }

    @Test fun eachChatKeepsItsOwnAndNothingReturnsItToTheAppsOwn() {
        val first = newAccountId().also { storage.conversationWith(it) }
        val second = newAccountId().also { storage.conversationWith(it) }
        val group = community()
        storage.write {
            it.chatBackgrounds.setForContact(ChatBackgroundChoice.Preset("sage"), first)
            it.chatBackgrounds.setForContact(ChatBackgroundChoice.Photo(photo), second)
            it.chatBackgrounds.setForCommunity(ChatBackgroundChoice.Preset("dusk"), group)
        }
        storage.read {
            assertEquals(ChatBackgroundChoice.Preset("sage"), it.chatBackgrounds.forContact(first))
            assertEquals(ChatBackgroundChoice.Photo(photo), it.chatBackgrounds.forContact(second))
            assertEquals(ChatBackgroundChoice.Preset("dusk"), it.chatBackgrounds.forCommunity(group))
        }

        // A new choice replaces the old one in the same row, of either kind.
        storage.write { it.chatBackgrounds.setForContact(ChatBackgroundChoice.Photo(photo), first) }
        storage.write { it.chatBackgrounds.setForContact(ChatBackgroundChoice.Preset("rose"), second) }
        assertEquals(ChatBackgroundChoice.Photo(photo), storage.read { it.chatBackgrounds.forContact(first) })
        assertEquals(ChatBackgroundChoice.Preset("rose"), storage.read { it.chatBackgrounds.forContact(second) })
        assertEquals(3, rowCount())

        storage.write { it.chatBackgrounds.setForContact(null, first) }
        assertNull(storage.read { it.chatBackgrounds.forContact(first) })
        assertEquals(ChatBackgroundChoice.Preset("rose"), storage.read { it.chatBackgrounds.forContact(second) })
        assertEquals(2, rowCount())
    }

    @Test fun itSurvivesClosingAndOpeningTheDatabase() {
        val contact = newAccountId().also { storage.conversationWith(it) }
        storage.write { it.chatBackgrounds.setForContact(ChatBackgroundChoice.Photo(photo), contact) }
        storage.close()
        storage = environment.open()
        val stored = storage.read { it.chatBackgrounds.forContact(contact) } as ChatBackgroundChoice.Photo
        assertArrayEquals(photo, stored.jpeg)
    }

    @Test fun itGoesWithAWipedConversationAndANewChatStartsWithTheAppsOwn() {
        val contact = newAccountId()
        val conversation = storage.conversationWith(contact)
        val other = newAccountId().also { storage.conversationWith(it) }
        storage.write {
            it.chatBackgrounds.setForContact(ChatBackgroundChoice.Photo(photo), contact)
            it.chatBackgrounds.setForContact(ChatBackgroundChoice.Preset("sand"), other)
        }
        assertNotNull(storage.wipeConversation(conversation.id, WipeReason.REQUESTED))
        assertEquals(1, rowCount())
        assertEquals(ChatBackgroundChoice.Preset("sand"), storage.read { it.chatBackgrounds.forContact(other) })
        storage.conversationWith(contact)
        assertNull(storage.read { it.chatBackgrounds.forContact(contact) })
    }

    @Test fun itGoesWithAForgottenContactAndARemovedCommunity() {
        val contact = newAccountId().also { storage.conversationWith(it) }
        val group = community()
        storage.write {
            it.chatBackgrounds.setForContact(ChatBackgroundChoice.Preset("mist"), contact)
            it.chatBackgrounds.setForCommunity(ChatBackgroundChoice.Photo(photo), group)
        }
        storage.write { it.contacts.forget(contact) }
        assertEquals(1, rowCount())
        CommunityRepository(storage).remove(group)
        assertEquals(0, rowCount())
    }

    @Test fun aChoiceOutsideTheLimitsOrForNobodyStoresNothing() {
        val contact = newAccountId().also { storage.conversationWith(it) }
        val invalid = listOf(
            ChatBackgroundChoice.Photo(ByteArray(ChatBackgroundChoice.MAX_PHOTO_BYTES + 1)),
            ChatBackgroundChoice.Photo(ByteArray(0)),
            ChatBackgroundChoice.Preset(""),
            ChatBackgroundChoice.Preset("x".repeat(ChatBackgroundChoice.MAX_PRESET_ID_LENGTH + 1)),
        )
        for (choice in invalid) {
            assertThrows(StorageException::class.java) { storage.write { it.chatBackgrounds.setForContact(choice, contact) } }
        }
        assertThrows(StorageException::class.java) {
            storage.write { it.chatBackgrounds.setForContact(ChatBackgroundChoice.Preset("sage"), newAccountId()) }
        }
        assertThrows(StorageException::class.java) {
            storage.write { it.chatBackgrounds.setForCommunity(ChatBackgroundChoice.Preset("sage"), newAccountId()) }
        }
        assertEquals(0, rowCount())
        assertFalse(storage.read { it.conversations.all() }.isEmpty())
    }
}
