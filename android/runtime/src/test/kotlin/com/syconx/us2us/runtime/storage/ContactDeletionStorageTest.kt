package com.syconx.us2us.runtime.storage

import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.contacts.UsernameStatus
import com.syconx.us2us.core.keys.Keys
import com.syconx.us2us.core.store.InMemoryProtocolStore
import com.syconx.us2us.core.store.ProtocolStore
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.runtime.engine.ContactInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * “Kişiyi sil” in the storage (§5.3): forgetting a contact leaves no row, key slot, item file,
 * session or identity of it behind; and a directory answer stops other contacts from showing a
 * username that is no longer theirs (§3.3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContactDeletionStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun forgettingAContactLeavesNoRowsKeysOrFiles() {
        val environment = TestEnvironment(temporary.newFolder())
        val storage = environment.open(t0)
        storage.saveAccount()
        val account = storage.read { it.account.load()!! }
        val store = ProtocolStore(account.identityKeyPair, account.registrationId, storage.protocolStorage)
        val peer = Party.inMemory()
        Party(store, account.accountId).sessions.startSession(peer.accountId, peer.bundle())
        val (contact, conversation) = storage.contactWithConversation(peer.accountId, now = t0)
        val other = storage.contactWithConversation(now = t0).first
        val data = attachmentMessage()
        val attachmentId = storage.write { tx ->
            tx.messages.storeIncoming(textMessage("SECRET-TEXT"), conversation.id)
            tx.messages.storeIncoming(data, conversation.id)
            val attachment = tx.attachments.forMessage(data.messageId, conversation.id).first()
            tx.attachments.storeFile("SECRET-FILE".toByteArray(), attachment.id)
            tx.outbox.enqueue(textContent("still waiting"), contact, conversation.id, now = t0)
            tx.outbox.enqueue(textContent("a receipt"), contact, now = t0)
            tx.outbox.enqueue(textContent("for someone else"), other, now = t0)
            attachment.id
        }
        assertNotNull(store.loadSession(contact.protocolAddress()))

        storage.write { it.contacts.forget(contact) }

        fun count(sql: String, vararg arguments: Any?): Int = storage.database.locked { it.query(sql, *arguments) }.size
        val id = contact.toBytes()
        assertEquals(0, count("SELECT 1 FROM contacts WHERE account = ?", id))
        assertEquals(0, count("SELECT 1 FROM conversations WHERE contact = ?", id))
        assertEquals(0, count("SELECT 1 FROM messages WHERE conversation_id = ?", conversation.id.raw.toByteArray()))
        assertEquals(0, count("SELECT 1 FROM attachments WHERE conversation_id = ?", conversation.id.raw.toByteArray()))
        assertEquals(0, count("SELECT 1 FROM outbox WHERE recipient = ?", id))
        assertEquals(0, count("SELECT 1 FROM sessions WHERE account = ?", id))
        assertEquals(0, count("SELECT 1 FROM identities WHERE account = ?", id))
        assertFalse("the key slot is gone", storage.keyring.slotFile(conversation.slotId).exists())
        assertFalse("the item file is gone", storage.files.exists(attachmentId))
        assertNull(store.loadSession(contact.protocolAddress()))
        assertEquals(ContactIdentity.Unknown, storage.protocolStorage.identityTrust(contact))
        assertFalse(environment.databaseBytes().contains("SECRET"))
        // Someone else's chat and sends are untouched.
        assertNotNull(storage.read { it.contacts.get(other) })
        assertEquals(1, count("SELECT 1 FROM outbox WHERE recipient = ?", other.toBytes()))
        storage.close()
    }

    @Test
    fun aDirectoryAnswerStopsOtherContactsShowingTheUsername() {
        val environment = TestEnvironment(temporary.newFolder())
        val storage = environment.open(t0)
        storage.saveAccount()
        val deniz = Username("deniz")
        val (old, _) = storage.contactWithConversation(now = t0)
        val (owner, _) = storage.contactWithConversation(now = t0)
        val (unrelated, _) = storage.contactWithConversation(now = t0)
        storage.write { tx ->
            tx.contacts.setProfile(ContactProfile(username = deniz, usernameStatus = UsernameStatus.VERIFIED), old)
            tx.contacts.setProfile(ContactProfile(username = deniz, usernameStatus = UsernameStatus.UNCHECKED), owner)
            tx.contacts.setProfile(ContactProfile(username = Username("mert"), usernameStatus = UsernameStatus.VERIFIED), unrelated)
        }

        assertEquals("only the old contact changes", 1, storage.write { it.contacts.noteUsernameOwner(deniz, owner) })
        val stale = storage.read { it.contacts.get(old)!! }
        assertEquals(UsernameStatus.MISMATCH, stale.profile.usernameStatus)
        assertNull("never shown as its name", ContactInfo.of(stale).username)
        assertEquals("deniz", ContactInfo.of(storage.read { it.contacts.get(owner)!! }).username)
        assertEquals(UsernameStatus.VERIFIED, storage.read { it.contacts.get(unrelated)!! }.profile.usernameStatus)
        assertTrue("asked again, nothing changes", storage.write { it.contacts.noteUsernameOwner(deniz, owner) } == 0)
        storage.close()
    }

    // MARK: "Kişiyi sil" keeps the session (§6.4): a contact that writes again comes back as a request

    private class Deleted(
        val environment: TestEnvironment,
        val storage: LocalStorage,
        val us: Party,
        val peer: Party,
        val conversation: Conversation,
    )

    /** A contact we talked with both ways (sessions on both sides), shown as “deniz”, then deleted with “Kişiyi sil”. */
    private fun deletedContact(): Deleted {
        val environment = TestEnvironment(temporary.newFolder())
        val storage = environment.open(t0)
        val account = storage.saveAccount()
        val us = Party.on(storage, account)
        val peer = Party.inMemory()
        peer.sessions.startSession(us.accountId, us.bundle())
        assertEquals("merhaba", us.decryptFrom(peer, peer.encryptTo(us, "merhaba")))
        assertEquals("selam", peer.decryptFrom(us, us.encryptTo(peer, "selam")))
        val (contact, conversation) = storage.contactWithConversation(peer.accountId, now = t0)
        storage.write { tx ->
            tx.contacts.confirmUsername(Username("deniz"), contact)
            tx.messages.storeIncoming(textMessage("ESKI-MESAJ"), conversation.id)
            tx.outbox.enqueue(textContent("still waiting"), contact, conversation.id, now = t0)
        }
        storage.write { it.contacts.removeKeepingSession(contact) }
        return Deleted(environment, storage, us, peer, conversation)
    }

    @Test
    fun aDeletedContactWritingAgainOnItsOldSessionDecryptsAndOpensARequestUnderItsName() {
        val d = deletedContact()
        val contact = d.peer.accountId
        fun count(sql: String): Int = d.storage.database.locked { it.query(sql, contact.toBytes()) }.size
        // Gone: the contact, its chat (key slot shredded) and its pending sends; kept: session, identity, name.
        assertNull(d.storage.read { it.contacts.get(contact) })
        assertNull(d.storage.read { it.conversations.forContact(contact) })
        assertFalse(d.storage.keyring.slotFile(d.conversation.slotId).exists())
        assertEquals(0, count("SELECT 1 FROM outbox WHERE recipient = ?"))
        assertEquals(1, count("SELECT 1 FROM sessions WHERE account = ?"))
        assertTrue(d.storage.protocolStorage.identityTrust(contact) is ContactIdentity.Trusted)
        assertEquals(Username("deniz"), d.storage.read { it.contacts.formerName(contact) })
        assertFalse(d.environment.databaseBytes().contains("ESKI-MESAJ"))

        // The other phone still holds its session (and our access key): it writes as an accepted contact.
        val again = d.peer.encryptTo(d.us, "yine ben")
        assertEquals(CiphertextMessage.WHISPER_TYPE, again.type)
        assertEquals("yine ben", d.us.decryptFrom(d.peer, again))
        val state = d.storage.write { it.contacts.noteVerifiedMessage(textContent("yine ben"), contact, t0.plusSeconds(60)) }
        assertEquals(ContactState.INCOMING_REQUEST, state)
        val back = d.storage.read { it.contacts.get(contact)!! }
        assertEquals(Username("deniz"), back.profile.username)
        assertEquals("a kept name is a claim, checked again", UsernameStatus.UNCHECKED, back.profile.usernameStatus)
        assertNull(back.theirAccessKey)
        assertNull(back.ourAccessKeySent)
        assertNull("the kept name is used up", d.storage.read { it.contacts.formerName(contact) })
        val fresh = d.storage.write { it.conversations.ensure(contact, t0.plusSeconds(60)) }
        assertTrue(fresh.id != d.conversation.id)
        assertTrue(d.storage.read { it.messages.page(fresh.id, limit = 50) }.isEmpty())
        d.storage.close()
    }

    @Test
    fun aDeletedContactUnderANewIdentityIsStillFlaggedAsChanged() {
        val d = deletedContact()
        val contact = d.peer.accountId
        val before = d.storage.protocolStorage.identityTrust(contact) as ContactIdentity.Trusted
        // The same account under a new identity key (e.g. reinstalled) starts a new session to us.
        val reinstalled = Party(InMemoryProtocolStore(Keys.generateIdentityKeyPair(), Keys.generateRegistrationId()), contact)
        reinstalled.sessions.startSession(d.us.accountId, d.us.bundle())
        val first = reinstalled.encryptTo(d.us, "yeni telefon")
        assertEquals(CiphertextMessage.PREKEY_TYPE, first.type)
        assertEquals("received, never lost", "yeni telefon", d.us.decryptFrom(reinstalled, first))
        val trust = d.storage.protocolStorage.identityTrust(contact)
        assertTrue("not trusted as new: $trust", trust is ContactIdentity.Changed)
        assertEquals(before.key, (trust as ContactIdentity.Changed).previous)
        d.storage.write { it.contacts.noteVerifiedMessage(textContent("yeni telefon"), contact, t0.plusSeconds(60)) }
        assertTrue(d.storage.read { it.contacts.get(contact)!! }.identity is ContactIdentity.Changed)
        d.storage.close()
    }

    @Test
    fun theKeptNameGoesWithLeaveNoTraceABareBlockOrAnotherOwner() {
        val environment = TestEnvironment(temporary.newFolder())
        val storage = environment.open(t0)
        storage.saveAccount()
        val deniz = Username("deniz")
        fun deleted(): AccountId {
            val (contact, _) = storage.contactWithConversation(now = t0)
            storage.write { tx ->
                tx.contacts.confirmUsername(deniz, contact)
                tx.contacts.removeKeepingSession(contact)
            }
            assertEquals(deniz, storage.read { it.contacts.formerName(contact) })
            return contact
        }
        fun count(): Int = storage.database.locked { it.query("SELECT 1 FROM former_contacts") }.size

        val forgotten = deleted()
        storage.write { it.contacts.block(forgotten, t0) }
        assertEquals("a block is a new row: the name is taken along", deniz, storage.read { it.contacts.get(forgotten)!! }.profile.username)
        storage.write { it.contacts.stripToBlock(forgotten) }
        assertNull(storage.read { it.contacts.get(forgotten)!! }.profile.username)
        assertEquals(0, count())

        // Ghost mode's "leave no trace" (§8.2) forgets the account entirely.
        val ghost = deleted()
        storage.write { it.contacts.forget(ghost) }
        assertNull(storage.read { it.contacts.formerName(ghost) })

        val taken = deleted()
        val (owner, _) = storage.contactWithConversation(now = t0)
        storage.write { it.contacts.noteUsernameOwner(deniz, owner) }
        assertNull("the name is someone else's now", storage.read { it.contacts.formerName(taken) })

        // A contact shown under a mismatched name keeps none.
        val (mismatched, _) = storage.contactWithConversation(now = t0)
        storage.write { tx ->
            tx.contacts.setProfile(ContactProfile(username = deniz, usernameStatus = UsernameStatus.MISMATCH), mismatched)
            tx.contacts.removeKeepingSession(mismatched)
        }
        assertEquals(0, count())
        storage.close()
    }
}
