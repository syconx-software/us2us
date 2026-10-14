package com.syconx.us2us.runtime.storage

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.community.CommunityState
import com.syconx.us2us.core.community.SignedCommunityState

/** Separate SQLCipher tables: never overload a direct conversation or contact. */
data class StoredCommunity(val id: AccountId, val ownerKey: String, val name: String, val signed: SignedCommunityState?, val active: Boolean) {
    val state: CommunityState? get() = signed?.verify(ownerKey)

    /** Pinned by a request or an invitation, never admitted yet (or not yet seen admitted). */
    val pending: Boolean get() = signed == null
}
data class StoredCommunityMessage(val communityId: AccountId, val sender: AccountId, val messageId: AccountId, val sentAt: Long, val text: String, val status: String)
data class CommunityDelivery(val communityId: AccountId, val messageId: AccountId, val recipient: AccountId, val revision: Long, val envelope: ByteArray)

/**
 * Message bodies are additionally items under the community's own key slot, as one-to-one
 * content is under its conversation's: forgetting a community destroys the slot first, so its
 * history is crypto-shredded even where a row or a WAL page image outlives the delete.
 */
class CommunityRepository(private val storage: LocalStorage) {
    fun recoverInterruptedPreparations() = storage.write { tx ->
        tx.connection.run("UPDATE community_messages SET status='failed' WHERE status='preparing'")
    }
    fun all(): List<StoredCommunity> = storage.read { tx -> tx.connection.query("SELECT $COLUMNS FROM community_states ORDER BY name").map(::record) }
    fun get(id: AccountId): StoredCommunity? = storage.read { tx -> find(tx, id) }
    fun pin(id: AccountId, ownerKey: String, name: String) = storage.write { tx -> pin(tx, id, ownerKey, name) }
    fun save(signed: SignedCommunityState): CommunityState = storage.write { tx ->
        val decoded = signed.verify()
        val old = find(tx, decoded.id)
        val verified = signed.verify(old?.ownerKey, old?.state)
        val name = if (verified.visibility == "private") old?.name.orEmpty() else verified.name
        pin(tx, verified.id, verified.ownerIdentityKey, name)
        tx.connection.run("UPDATE community_states SET state=?,signature=?,name=?,active=1 WHERE id=?",
            signed.state, signed.signature, name, verified.id.toString())
        verified
    }
    fun deactivateMissing(activeIds: Set<AccountId>) = storage.write { tx ->
        for (row in tx.connection.query("SELECT id FROM community_states WHERE active = 1")) {
            val id = AccountId.parse(row.text("id")!!)
            if (id !in activeIds) {
                tx.connection.run("UPDATE community_states SET active=0 WHERE id=?", id.toString())
                tx.connection.run("DELETE FROM community_outbox WHERE community_id=?", id.toString())
                tx.connection.run("UPDATE community_messages SET status='stale' WHERE community_id=? AND status IN ('pending','partial')", id.toString())
            }
        }
    }

    /** Forgets the community on this phone: its key slot first, then every row (history, queued sends). */
    fun remove(id: AccountId) = storage.write { tx ->
        tx.connection.queryOne("SELECT slot_id FROM community_states WHERE id=?", id.toString())?.blob("slot_id")?.let {
            tx.storage.keyring.destroy(slotIdOf(it))
        }
        tx.connection.run("DELETE FROM community_states WHERE id=?", id.toString())
        tx.checkpointAfterCommit()
    }
    fun messages(id: AccountId): List<StoredCommunityMessage> = storage.read { tx ->
        val rows = tx.connection.query("SELECT * FROM (SELECT * FROM community_messages WHERE community_id=? ORDER BY sent_at DESC LIMIT 500) ORDER BY sent_at", id.toString())
        if (rows.isEmpty()) return@read emptyList()
        val key = key(tx, id)
        rows.map { row ->
            val messageId = AccountId.parse(row.text("message_id")!!)
            val body = tx.open(row.blob("body")!!, itemId(messageId), key)
            StoredCommunityMessage(id, AccountId.parse(row.text("sender")!!), messageId, row.long("sent_at")!!, String(body, Charsets.UTF_8), row.text("status")!!)
        }
    }
    fun add(message: StoredCommunityMessage) = storage.write { tx ->
        val exists = tx.connection.queryOne("SELECT 1 AS present FROM community_messages WHERE community_id=? AND sender=? AND message_id=?",
            message.communityId.toString(), message.sender.toString(), message.messageId.toString())
        if (exists != null) return@write
        val body = tx.seal(message.text.toByteArray(Charsets.UTF_8), itemId(message.messageId), key(tx, message.communityId))
        tx.connection.run("INSERT INTO community_messages(community_id,sender,message_id,sent_at,body,status) VALUES(?,?,?,?,?,?)",
            message.communityId.toString(), message.sender.toString(), message.messageId.toString(), message.sentAt, body, message.status)
    }
    fun enqueue(delivery: CommunityDelivery) = storage.write { tx ->
        tx.connection.run("INSERT OR IGNORE INTO community_outbox(community_id,message_id,recipient,revision,envelope) VALUES(?,?,?,?,?)",
            delivery.communityId.toString(), delivery.messageId.toString(), delivery.recipient.toString(), delivery.revision, delivery.envelope)
    }
    fun pending(): List<CommunityDelivery> = storage.read { tx ->
        tx.connection.query("SELECT * FROM community_outbox ORDER BY rowid LIMIT 500").map { row ->
            CommunityDelivery(AccountId.parse(row.text("community_id")!!), AccountId.parse(row.text("message_id")!!), AccountId.parse(row.text("recipient")!!), row.long("revision")!!, row.blob("envelope")!!)
        }
    }
    fun delivered(delivery: CommunityDelivery) = storage.write { tx -> settle(tx, delivery) }

    /**
     * This recipient will never get the message (the relay refused it for good): its envelope goes,
     * the message stays `failed` while the others are still delivered.
     */
    fun failed(delivery: CommunityDelivery) = storage.write { tx ->
        tx.connection.run("UPDATE community_messages SET status='failed' WHERE community_id=? AND message_id=? AND status!='stale'",
            delivery.communityId.toString(), delivery.messageId.toString())
        settle(tx, delivery)
    }
    fun mark(id: AccountId, messageId: AccountId, status: String, clearOutbox: Boolean = false) = storage.write { tx ->
        tx.connection.run("UPDATE community_messages SET status=? WHERE community_id=? AND message_id=?", status, id.toString(), messageId.toString())
        if (clearOutbox) tx.connection.run("DELETE FROM community_outbox WHERE community_id=? AND message_id=?", id.toString(), messageId.toString())
    }
    fun wasProcessed(envelope: String): Boolean = storage.read { it.envelopes.contains("community:$envelope") }
    fun processed(envelope: String) = storage.write { it.envelopes.record("community:$envelope") }

    private fun settle(tx: StorageTransaction, delivery: CommunityDelivery) {
        tx.connection.run("DELETE FROM community_outbox WHERE community_id=? AND message_id=? AND recipient=?", delivery.communityId.toString(), delivery.messageId.toString(), delivery.recipient.toString())
        val count = tx.connection.scalar("SELECT count(*) FROM community_outbox WHERE community_id=? AND message_id=?", delivery.communityId.toString(), delivery.messageId.toString()) as Long
        tx.connection.run("UPDATE community_messages SET status=? WHERE community_id=? AND message_id=? AND status NOT IN ('stale','failed')",
            if (count == 0L) "sent" else "partial", delivery.communityId.toString(), delivery.messageId.toString())
    }
    private fun pin(tx: StorageTransaction, id: AccountId, ownerKey: String, name: String) {
        val old = tx.connection.queryOne("SELECT owner_key FROM community_states WHERE id = ?", id.toString())
        if (old != null) {
            require(old.text("owner_key") == ownerKey) { "community owner identity mismatch" }
            return
        }
        // The slot is written first; if the transaction rolls back it is an orphan, collected at the next open.
        val (slotId, _) = tx.storage.keyring.createSlot()
        tx.connection.run("INSERT INTO community_states(id, owner_key, name, slot_id, active) VALUES(?,?,?,?,0)", id.toString(), ownerKey, name, slotId.bytes())
    }
    private fun find(tx: StorageTransaction, id: AccountId): StoredCommunity? =
        tx.connection.queryOne("SELECT $COLUMNS FROM community_states WHERE id = ?", id.toString())?.let(::record)
    private fun key(tx: StorageTransaction, id: AccountId): ByteArray {
        val slot = tx.connection.queryOne("SELECT slot_id FROM community_states WHERE id=?", id.toString())?.blob("slot_id") ?: storageFailure(StorageError.NotFound)
        return tx.conversationKey(slotIdOf(slot))
    }
    private fun itemId(messageId: AccountId): ByteString = ByteString.copyFrom(messageId.toBytes())
    private fun record(row: Row): StoredCommunity = StoredCommunity(AccountId.parse(row.text("id")!!), row.text("owner_key")!!, row.text("name")!!,
        row.text("state")?.let { SignedCommunityState(it, row.text("signature")!!) }, row.bool("active"))

    private companion object {
        const val COLUMNS = "id, owner_key, name, state, signature, active"
    }
}
