package com.syconx.us2us.runtime.storage

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.community.CommunityMember
import com.syconx.us2us.core.community.CommunityState
import com.syconx.us2us.core.encoding.Base64Url
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.libsignal.protocol.IdentityKeyPair
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CommunityRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var environment: TestEnvironment
    private lateinit var storage: LocalStorage
    private lateinit var repository: CommunityRepository
    private val identity = IdentityKeyPair.generate()
    private val owner = AccountId(UUID(0, 1))
    private val key = Base64Url.encode(identity.publicKey.serialize())
    private val group = AccountId(UUID(0, 3))
    private val state get() = CommunityState(group, 1, "private", owner, key, "", listOf(CommunityMember(owner, key)))
    @Before fun open() {
        environment = TestEnvironment(temporary.newFolder())
        storage = environment.open()
        storage.saveAccount()
        repository = CommunityRepository(storage)
        repository.pin(group, key, "Özel topluluk")
        repository.save(state.sign(identity))
    }
    @After fun close() = storage.close()

    @Test fun historyPagesReachAll5107MessagesWithEqualTimesAndDeletedBoundary() {
        val inserted = (0..<5107).map { index -> StoredCommunityMessage(group, owner, newAccountId(),
            (5107-index).toLong(), "history-$index", "received", receivedAt = 1234) }
        storage.write { inserted.forEach(repository::add) }
        var page = repository.history(group)
        assertEquals(100, page.items.size)
        val floor = page.oldest!!
        val seen = mutableListOf<StoredCommunityMessage>()
        while (true) {
            seen.addAll(0, page.items)
            if (!page.hasOlder) break
            page = repository.history(group, before = page.oldest)
        }
        assertEquals(inserted.map { it.messageId }, seen.map { it.messageId })
        repository.deleteForMe(group, owner, inserted[5007].messageId, 1234)
        val older = repository.history(group, before = floor)
        assertEquals(inserted[5006].messageId, older.items.last().messageId)
        repository.add(StoredCommunityMessage(group, owner, newAccountId(), 1, "new arrival", "received", receivedAt = 1235))
        val window = repository.history(group, through = older.oldest)
        assertEquals(200, window.items.size)
        assertEquals("new arrival", window.items.last().text)
    }

    @Test fun pinMessagesOutboxAndDedupeSurviveReopenWithoutCreatingDirectContacts() {
        val message = StoredCommunityMessage(group, owner, newAccountId(), 1234, "Şifreli yerel mesaj", "pending")
        val delivery = CommunityDelivery(group, message.messageId, newAccountId(), 1, byteArrayOf(1, 2, 3))
        storage.write { repository.add(message); repository.enqueue(delivery); repository.processed("envelope") }
        storage.close(); storage = environment.open(); repository = CommunityRepository(storage)
        assertEquals("Özel topluluk", repository.get(group)!!.name)
        assertEquals(message, repository.messages(group).single())
        assertArrayEquals(delivery.envelope, repository.pending().single().envelope)
        assertTrue(repository.wasProcessed("envelope"))
        assertTrue(storage.contactStore.all().isEmpty())
        repository.add(message)
        assertEquals(1, repository.messages(group).size)
        repository.delivered(delivery)
        assertTrue(repository.pending().isEmpty())
        assertEquals("sent", repository.messages(group).single().status)
    }

    @Test fun removedMembershipDropsQueuedDeliveryAndMarksPartialSendStale() {
        val message = StoredCommunityMessage(group, owner, newAccountId(), 1234, "text", "partial")
        repository.add(message)
        repository.enqueue(CommunityDelivery(group, message.messageId, newAccountId(), 1, byteArrayOf(1)))
        repository.deactivateMissing(emptySet())
        assertFalse(repository.get(group)!!.active)
        assertTrue(repository.pending().isEmpty())
        assertEquals("stale", repository.messages(group).single().status)
        assertEquals("existing history remains on this device", "text", repository.messages(group).single().text)
    }

    @Test fun previouslyPinnedOwnerAndObservedRevisionCannotBeReplaced() {
        val newer = state.copy(revision = 2)
        repository.save(newer.sign(identity))
        try { repository.save(state.sign(identity)); fail("rollback accepted") } catch (_: IllegalArgumentException) { }
        try { repository.pin(group, Base64Url.encode(IdentityKeyPair.generate().publicKey.serialize()), "spoof"); fail("owner replaced") } catch (_: IllegalArgumentException) { }
        assertEquals(2L, repository.get(group)!!.state!!.revision)
    }

    @Test fun inboundRatchetAndMessageRollbackAndCommitTogether() = runBlocking {
        val peer = newAccountId()
        val protocol = storage.protocolStorage
        val message = StoredCommunityMessage(group, peer, newAccountId(), 1234, "atomic", "received")
        protocol.withStagedInboundWrites {
            protocol.storeSession(peer.toString(), 1, byteArrayOf(11))
            try {
                storage.write { repository.add(message); repository.processed("crash"); throw IllegalStateException("disk write interrupted") }
            } catch (_: IllegalStateException) {
                assertTrue(protocol.discardStagedInboundWrites())
            }
        }
        assertTrue(repository.messages(group).isEmpty())
        assertFalse(repository.wasProcessed("crash"))
        assertNull(protocol.loadSession(peer.toString(), 1))
        protocol.withStagedInboundWrites {
            protocol.storeSession(peer.toString(), 1, byteArrayOf(22))
            storage.write { repository.add(message); repository.processed("committed") }
        }
        storage.close(); storage = environment.open(); repository = CommunityRepository(storage)
        assertArrayEquals(byteArrayOf(22), storage.protocolStorage.loadSession(peer.toString(), 1))
        assertEquals(message, repository.messages(group).single())
        assertTrue(repository.wasProcessed("committed"))
    }

    private fun slot() = storage.read { tx -> slotIdOf(tx.connection.queryOne("SELECT slot_id FROM community_states WHERE id=?", group.toString())!!.blob("slot_id")!!) }

    @Test fun bodiesAreSealedUnderTheCommunitySlotAndForgettingShredsIt() {
        val text = "Gizli topluluk metni"
        repository.add(StoredCommunityMessage(group, owner, newAccountId(), 1234, text, "received"))
        val stored = storage.read { tx -> tx.connection.queryOne("SELECT body FROM community_messages")!!.blob("body")!! }
        assertFalse("no plaintext body in the database", String(stored, Charsets.ISO_8859_1).contains("topluluk"))
        assertEquals(text, repository.messages(group).single().text)
        val slot = slot()
        assertTrue(storage.keyring.slotExists(slot))
        repository.remove(group)
        assertFalse("forgetting destroys the key slot", storage.keyring.slotExists(slot))
        assertNull(repository.get(group))
        assertTrue(repository.messages(group).isEmpty())
    }

    @Test fun garbageCollectionKeepsCommunitySlotsAndDropsUnreferencedOnes() {
        val slot = slot()
        val (orphan, _) = storage.keyring.createSlot()
        val report = storage.performDueWipes(collectGarbage = true)
        assertEquals(1, report.orphanSlotsRemoved)
        assertTrue("a community's slot is referenced", storage.keyring.slotExists(slot))
        assertFalse(storage.keyring.slotExists(orphan))
        assertEquals("Özel topluluk", repository.get(group)!!.name)
    }

    @Test fun oneRefusedRecipientFailsOnlyItsOwnDeliveryAndTheMessageStaysFailed() {
        val message = StoredCommunityMessage(group, owner, newAccountId(), 1234, "fanout", "pending")
        repository.add(message)
        val refused = CommunityDelivery(group, message.messageId, newAccountId(), 1, byteArrayOf(1))
        val other = CommunityDelivery(group, message.messageId, newAccountId(), 1, byteArrayOf(2))
        storage.write { repository.enqueue(refused); repository.enqueue(other) }
        repository.failed(refused)
        assertEquals(listOf(other.recipient), repository.pending().map { it.recipient })
        assertEquals("failed", repository.messages(group).single().status)
        repository.delivered(other)
        assertTrue(repository.pending().isEmpty())
        assertEquals("a later delivery cannot hide the failed recipient", "failed", repository.messages(group).single().status)
    }

    @Test fun aHundredMemberCommunityPersists99CiphertextsAndRemovesAllOnDelete() {
        val message = StoredCommunityMessage(group, owner, newAccountId(), 1234, "fanout", "pending")
        repository.add(message)
        val deliveries = (1L..99L).map { CommunityDelivery(group, message.messageId, AccountId(UUID(4, it)), 1, ByteArray(2048) { 23 }) }
        storage.write { deliveries.forEach(repository::enqueue) }
        assertEquals(99, repository.pending().size)
        deliveries.take(98).forEach(repository::delivered)
        assertEquals("partial", repository.messages(group).single().status)
        assertEquals(1, repository.pending().size)
        repository.delivered(deliveries.last())
        assertEquals("sent", repository.messages(group).single().status)
        repository.remove(group)
        assertTrue(repository.pending().isEmpty()); assertTrue(repository.messages(group).isEmpty()); assertNull(repository.get(group))
    }
}
