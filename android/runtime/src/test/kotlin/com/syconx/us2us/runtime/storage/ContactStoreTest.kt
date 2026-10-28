package com.syconx.us2us.runtime.storage

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccessKey
import com.syconx.us2us.core.contacts.Contact
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.contacts.UsernameStatus
import com.syconx.us2us.core.messenger.LocalAccount
import com.syconx.us2us.core.profile.ProfilePicture
import com.syconx.us2us.core.proto.accountBackup
import com.syconx.us2us.core.proto.backupContact
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The core's contact store on the `contacts` table, and the storage's part of backups (§5.5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContactStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var storage: LocalStorage
    private lateinit var account: LocalAccount

    @Before
    fun setUp() {
        storage = TestEnvironment(temporary.newFolder()).open()
        account = storage.saveAccount()
    }

    @After
    fun tearDown() = storage.close()

    @Test
    fun aCoreContactRoundTripsWithEveryField() {
        val peer = newAccountId()
        val contact = Contact(
            accountId = peer,
            username = "deniz_7",
            state = ContactState.ACCEPTED,
            theirAccessKey = AccessKey(ByteArray(16) { 3 }),
            accessKeyShared = true,
            displayName = "Deniz",
            presenceSharing = true,
            profileShared = true,
            usernameStatus = UsernameStatus.VERIFIED,
            picture = ProfilePicture.preset(1),
            profileReceived = true,
            stateBeforeBlock = null,
        )
        storage.contactStore.put(contact)
        assertEquals(contact, storage.contactStore.get(peer))
        // The same row through the repository, with the values the iOS columns hold.
        val stored = storage.read { it.contacts.get(peer)!! }
        assertEquals(ByteString.copyFrom(account.accessKey.toBytes()), stored.ourAccessKeySent)
        assertNotNull(stored.profileSentAt)
        assertEquals("Deniz", stored.profile.displayName)
        assertEquals(ProfilePicture.preset(1), stored.profile.picture)

        // Flags off: the columns are cleared, and an update keeps the creation time.
        val created = stored.createdAt
        val blocked = contact.copy(state = ContactState.BLOCKED, stateBeforeBlock = ContactState.ACCEPTED, accessKeyShared = false, profileShared = false, profileReceived = false, picture = null)
        storage.contactStore.put(blocked)
        assertEquals(blocked, storage.contactStore.get(peer))
        val after = storage.read { it.contacts.get(peer)!! }
        assertNull(after.ourAccessKeySent)
        assertNull(after.profileSentAt)
        assertEquals(0L, after.profile.updatedAt)
        assertEquals(created, after.createdAt)
    }

    @Test
    fun theRequestStateTextIsTheIosOne() {
        val peer = newAccountId()
        storage.contactStore.put(Contact(peer, null, ContactState.OUTGOING_REQUEST))
        val raw = storage.read { it.connection.scalar("SELECT request_state FROM contacts") }
        assertEquals("outgoingRequest", raw)
        storage.write { tx ->
            tx.contacts.markRequestSent(peer)
            tx.contacts.noteVerifiedMessage(com.syconx.us2us.core.contacts.MessageRequests.acceptContent(), peer)
        }
        assertEquals(ContactState.ACCEPTED, storage.contactStore.get(peer)?.state)
    }

    @Test
    fun removingForgetsTheContactWithItsConversationAndPendingSends() {
        val (peer, conversation) = storage.contactWithConversation()
        storage.write { tx -> tx.outbox.enqueue(textContent("pending"), peer, conversation.id) }
        storage.contactStore.remove(peer)
        assertNull(storage.contactStore.get(peer))
        assertNull(storage.read { it.conversations.get(conversation.id) })
        assertFalse(storage.keyring.slotExists(conversation.slotId))
        assertTrue(storage.read { it.outbox.all() }.isEmpty())
    }

    @Test
    fun contactsWaitingToBeForgottenAreFoundButNotListed() {
        val (peer, _) = storage.contactWithConversation()
        storage.write { tx -> tx.contacts.markPendingForget(peer) }
        assertNotNull(storage.contactStore.get(peer))
        assertTrue(storage.contactStore.all().isEmpty())
    }

    @Test
    fun aBackupHoldsAcceptedAndBlockedContactsOnly() {
        val accepted = storage.contactWithConversation().first
        val blocked = newAccountId()
        val requested = newAccountId()
        storage.write { tx ->
            tx.contacts.create(blocked, ContactState.BLOCKED)
            tx.contacts.create(requested, ContactState.INCOMING_REQUEST)
            tx.contacts.setProfile(ContactProfile(displayName = "Kabul", presenceSharing = true, picture = ProfilePicture.preset(2)), accepted)
        }
        val entries = storage.read { AccountBackupService.backupContacts(it) }
        assertEquals(setOf(accepted, blocked), entries.map { accountIdOf(it.accountId.toByteArray()) }.toSet())
        val entry = entries.first { accountIdOf(it.accountId.toByteArray()) == accepted }
        assertEquals("Kabul", entry.displayName)
        assertTrue(entry.presenceSharing)
        assertEquals(2, entry.avatar.presetId)
        assertTrue(entries.first { accountIdOf(it.accountId.toByteArray()) == blocked }.blocked)
    }

    @Test
    fun restoringContactsGivesEveryNonBlockedOneItsConversation() {
        val friend = newAccountId()
        val enemy = newAccountId()
        val accessKey = ByteString.copyFrom(ByteArray(16) { 6 })
        val backup = accountBackup {
            this.accessKey = accessKey
            contacts += backupContact {
                accountId = ByteString.copyFrom(friend.toBytes())
                username = "dost_1"
                displayName = "Dost"
                theirAccessKey = ByteString.copyFrom(ByteArray(16) { 9 })
                presenceSharing = true
            }
            contacts += backupContact {
                accountId = ByteString.copyFrom(enemy.toBytes())
                blocked = true
            }
        }
        storage.write { tx -> AccountBackupService.restoreContacts(backup, tx) }
        storage.read { tx ->
            val restored = tx.contacts.get(friend)!!
            assertEquals(ContactState.ACCEPTED, restored.requestState)
            assertEquals("dost_1", restored.profile.username?.value)
            assertEquals(UsernameStatus.UNCHECKED, restored.profile.usernameStatus)
            assertEquals(accessKey, restored.ourAccessKeySent)
            assertNotNull(tx.conversations.forContact(friend))
            assertTrue(tx.contacts.get(enemy)!!.isBlocked)
            assertNull(tx.conversations.forContact(enemy))
        }
    }

    @Test
    fun theBackupFileGoesToAStagingFileWithoutAccountInformationInItsName() {
        val file = AccountBackupService.writeToStaging("backup".toByteArray(), storage)
        assertTrue(file.name.matches(Regex("us2us-backup-\\d{8}-\\d{4}\\.brpb")))
        assertEquals("backup", file.readText())
        assertTrue(file.path.startsWith(storage.layout.stagingDirectory.path))
    }
}
