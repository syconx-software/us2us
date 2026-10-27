package com.syconx.us2us.runtime.storage

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.community.CommunityState
import com.syconx.us2us.core.community.SignedCommunityState
import com.syconx.us2us.core.proto.Sticker
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.CommunityMessage
import com.syconx.us2us.core.proto.CommunityReceipt

/** Separate SQLCipher tables: never overload a direct conversation or contact. */
data class StoredCommunity(val id: AccountId, val ownerKey: String, val name: String, val signed: SignedCommunityState?, val active: Boolean) {
    val state: CommunityState? get() = signed?.verify(ownerKey)

    /** Pinned by a request or an invitation, never admitted yet (or not yet seen admitted). */
    val pending: Boolean get() = signed == null
}

/** Where the file of a community photo, video or file is. */
enum class CommunityMediaState(val raw: Int) {
    /** On the relay: downloaded on demand (or automatically, for photos). */
    REMOTE(0),

    /** Decrypted here (received), or the file as it was sent (own media). */
    LOCAL(1),

    /** Gone from the relay (`404`), or its digest or MAC did not verify: "no longer available". */
    UNAVAILABLE(2),
    ;

    companion object {
        fun of(raw: Long?): CommunityMediaState? = entries.firstOrNull { it.raw.toLong() == raw }
    }
}

/** Who deleted a message for everyone. */
enum class CommunityDeletion(val raw: Int) {
    SENDER(1),
    OWNER(2),
    ;

    companion object {
        fun of(raw: Long?): CommunityDeletion? = entries.firstOrNull { it.raw.toLong() == raw }
    }
}

/**
 * Own messages: how far the fanout got and what came back, over the recipient set fixed at send
 * time. Members the relay no longer delivers to (left, removed) are not counted.
 */
data class CommunityDeliverySummary(val recipients: Int, val accepted: Int, val delivered: Int, val read: Int, val failed: Int) {
    val allAccepted: Boolean get() = accepted == recipients
    val allDelivered: Boolean get() = recipients > 0 && delivered == recipients
    val allRead: Boolean get() = recipients > 0 && read == recipients
}

data class CommunityHistoryCursor(val receivedAt: Long, val position: Long)

data class CommunityHistory(val items: List<StoredCommunityMessage>, val oldest: CommunityHistoryCursor?, val hasOlder: Boolean)

data class StoredCommunityMessage(
    val communityId: AccountId,
    val sender: AccountId,
    val messageId: AccountId,
    val sentAt: Long,
    /** The text, or a media message's caption. Empty for a placeholder. */
    val text: String,
    val status: String,
    /** A media message's pointer (never a delete token); `null` for text and for a placeholder. */
    val attachment: Attachment? = null,
    val media: CommunityMediaState? = null,
    val deletedBy: CommunityDeletion? = null,
    /** When this phone stored it (orders the conversation and the chat list). */
    val receivedAt: Long = sentAt,
    /** Incoming: when it was displayed in the open conversation; `null` while unread. */
    val displayedAt: Long? = null,
    /** Own text and media messages with a recorded recipient set. */
    val delivery: CommunityDeliverySummary? = null,
    val sticker: Sticker? = null,
) {
    val isDeleted: Boolean get() = deletedBy != null
    val isMedia: Boolean get() = attachment != null
}

data class CommunityDelivery(
    val communityId: AccountId,
    val messageId: AccountId,
    val recipient: AccountId,
    val revision: Long,
    val envelope: ByteArray,
    /** Receipts and deletes: their content, so they can be encrypted again under a newer revision. */
    val payload: CommunityMessage? = null,
)

/** One recipient of an own message (5.4 › Bilgi): transport state, delivered and read times. */
data class CommunityRecipient(val member: AccountId, val transport: String, val deliveredAt: Long?, val readAt: Long?) {
    companion object {
        const val PENDING = "pending"
        const val ACCEPTED = "accepted"

        /** The relay no longer delivers to this member (left, removed or deleted): not counted. */
        const val SKIPPED = "skipped"
        const val FAILED = "failed"
        const val STALE = "stale"
    }
}

/** A community's row in the chat list: its newest text or media message and how many are unread. */
data class CommunityActivity(val communityId: AccountId, val last: StoredCommunityMessage?, val unread: Int)

/** What storing a received text or media message did. */
enum class CommunityArrival {
    STORED,

    /** A delete for everyone arrived first: kept as its placeholder (DELIVERY is still sent). */
    STORED_DELETED,

    /** Already here, or deleted for me: nothing changes. */
    DUPLICATE,
}

/** What a delete for everyone did on this phone. */
data class CommunityDeleteResult(
    val outcome: Outcome,
    /** The deleted message was this account's own media, with its delete token still here. */
    val ownBlob: Pair<ByteArray, ByteArray>? = null,
) {
    enum class Outcome { APPLIED, ALREADY_DELETED, REMEMBERED }
}

/**
 * Message bodies are additionally items under the community's own key slot, as one-to-one
 * content is under its conversation's: forgetting a community destroys the slot first, so its
 * history is crypto-shredded even where a row or a WAL page image outlives the delete. Attachment
 * pointers, delete tokens, the content of queued receipts and deletes, and decrypted files are
 * items under the same key.
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
                tx.connection.run("DELETE FROM community_receipt_queue WHERE community_id=?", id.toString())
                tx.connection.run("UPDATE community_messages SET status='stale' WHERE community_id=? AND status IN ('pending','partial')", id.toString())
                tx.connection.run("UPDATE community_recipients SET transport=? WHERE community_id=? AND transport=?",
                    CommunityRecipient.STALE, id.toString(), CommunityRecipient.PENDING)
            }
        }
    }

    /** Forgets the community on this phone: its key slot first, then every row (history, queued sends) and its files. */
    fun remove(id: AccountId) = storage.write { tx ->
        val files = tx.connection.query("SELECT file_id FROM community_messages WHERE community_id=? AND file_id IS NOT NULL", id.toString())
            .mapNotNull { it.bytes("file_id") }
        tx.connection.queryOne("SELECT slot_id FROM community_states WHERE id=?", id.toString())?.blob("slot_id")?.let {
            tx.storage.keyring.destroy(slotIdOf(it))
        }
        tx.connection.run("DELETE FROM community_states WHERE id=?", id.toString())
        removeFilesAfterCommit(tx, files)
        tx.checkpointAfterCommit()
    }

    // MARK: Messages

    fun messages(id: AccountId, limit: Int = 500): List<StoredCommunityMessage> = history(id, limit = limit).items

    fun history(id: AccountId, before: CommunityHistoryCursor? = null, through: CommunityHistoryCursor? = null, limit: Int = 100): CommunityHistory = storage.read { tx ->
        var predicate = "community_id=?"
        val arguments = mutableListOf<Any?>(id.toString())
        if (before != null) {
            predicate += " AND (coalesce(received_at,sent_at),rowid)<(?,?)"
            arguments.addAll(listOf(before.receivedAt, before.position))
        }
        if (through != null) {
            predicate += " AND (coalesce(received_at,sent_at),rowid)>=(?,?)"
            arguments.addAll(listOf(through.receivedAt, through.position))
        }
        arguments += if (through == null) maxOf(0, limit) else Int.MAX_VALUE
        val rows = tx.connection.query(
            "SELECT rowid AS position,* FROM community_messages WHERE $predicate ORDER BY coalesce(received_at,sent_at) DESC,rowid DESC LIMIT ?",
            *arguments.toTypedArray(),
        )
        if (rows.isEmpty()) return@read CommunityHistory(emptyList(), null, false)
        val key = key(tx, id)
        val own = ownId(tx)
        val summaries = deliverySummaries(tx, id)
        val items = rows.asReversed().map { row -> message(tx, id, row, key).let { if (it.sender == own) it.copy(delivery = summaries[it.messageId]) else it } }
        val oldest = CommunityHistoryCursor(items.first().receivedAt, rows.last().long("position")!!)
        val hasOlder = tx.connection.queryOne(
            "SELECT 1 FROM community_messages WHERE community_id=? AND (coalesce(received_at,sent_at),rowid)<(?,?) LIMIT 1",
            id.toString(), oldest.receivedAt, oldest.position,
        ) != null
        CommunityHistory(items, oldest, hasOlder)
    }

    fun message(id: AccountId, sender: AccountId, messageId: AccountId): StoredCommunityMessage? = storage.read { tx -> find(tx, id, sender, messageId) }

    /** Stores a message (own, or received in tests and upgrades); an existing key is left as it is. */
    fun add(message: StoredCommunityMessage) = storage.write { tx -> insert(tx, message) }

    /**
     * A received text or media message: dropped as a duplicate when the key is here or was
     * deleted for me; stored as its placeholder when a delete for everyone came first.
     */
    fun addReceived(message: StoredCommunityMessage, now: Long): CommunityArrival = storage.write { tx ->
        val community = message.communityId.toString()
        val sender = message.sender.toString()
        val messageId = message.messageId.toString()
        if (tx.connection.queryOne("SELECT 1 AS present FROM community_messages WHERE community_id=? AND sender=? AND message_id=?", community, sender, messageId) != null) {
            return@write CommunityArrival.DUPLICATE
        }
        if (tx.connection.queryOne("SELECT 1 AS present FROM community_removed WHERE community_id=? AND sender=? AND message_id=?", community, sender, messageId) != null) {
            return@write CommunityArrival.DUPLICATE
        }
        val pending = tx.connection.queryOne("SELECT by_owner FROM community_pending_deletes WHERE community_id=? AND target_sender=? AND target_id=?", community, sender, messageId)
        if (pending != null) {
            tx.connection.run("DELETE FROM community_pending_deletes WHERE community_id=? AND target_sender=? AND target_id=?", community, sender, messageId)
            val by = if (pending.bool("by_owner")) CommunityDeletion.OWNER else CommunityDeletion.SENDER
            insert(tx, message.copy(text = "", attachment = null, media = null, sticker = null, deletedBy = by, receivedAt = now, displayedAt = null), deletedAt = now)
            return@write CommunityArrival.STORED_DELETED
        }
        insert(tx, message.copy(receivedAt = now, media = if (message.attachment != null) CommunityMediaState.REMOTE else null))
        CommunityArrival.STORED
    }

    /** An own text or media message with its recipient set, every member pending. */
    fun addOwn(message: StoredCommunityMessage, recipients: List<AccountId>, file: ByteArray? = null) = storage.write { tx ->
        val fileId = file?.let { RandomIds.make() }
        insert(tx, message, fileId = fileId)
        if (file != null && fileId != null) tx.storage.files.write(file, fileId, key(tx, message.communityId))
        setRecipients(tx, message.communityId, message.messageId, recipients)
    }

    /** The recipient set of an own message, fixed when its fanout is prepared. */
    fun setRecipients(id: AccountId, messageId: AccountId, recipients: List<AccountId>) = storage.write { tx -> setRecipients(tx, id, messageId, recipients) }

    /** One recipient's transport state (a retried fanout sets the ones it sends to pending again). */
    fun markRecipient(id: AccountId, messageId: AccountId, member: AccountId, transport: String) = storage.write { tx ->
        tx.connection.run("UPDATE community_recipients SET transport=? WHERE community_id=? AND message_id=? AND member=?",
            transport, id.toString(), messageId.toString(), member.toString())
    }

    fun recipients(id: AccountId, messageId: AccountId): List<CommunityRecipient> = storage.read { tx ->
        tx.connection.query("SELECT member, transport, delivered_at, read_at FROM community_recipients WHERE community_id=? AND message_id=? ORDER BY member", id.toString(), messageId.toString())
            .map { CommunityRecipient(AccountId.parse(it.text("member")!!), it.text("transport")!!, it.long("delivered_at"), it.long("read_at")) }
    }

    /** The pointer of an own upload, once the blob is on the relay, and its delete token (kept on this phone only). */
    fun uploaded(id: AccountId, messageId: AccountId, pointer: Attachment, deleteToken: ByteArray, now: Long) = storage.write { tx ->
        val key = key(tx, id)
        val sealedPointer = tx.seal(pointer.toByteArray(), item(ATTACHMENT, id, messageId), key)
        val sealedToken = tx.seal(deleteToken, item(TOKEN, id, messageId), key)
        tx.connection.run("UPDATE community_messages SET attachment=?, delete_token=?, uploaded_at=? WHERE community_id=? AND sender=? AND message_id=?",
            sealedPointer, sealedToken, now, id.toString(), ownId(tx).toString(), messageId.toString())
    }

    /** Own media messages deleted for everyone whose blob still waits for its `DELETE`, with their tokens. */
    fun blobsToDelete(): List<Triple<AccountId, AccountId, Pair<ByteArray, ByteArray>>> = storage.read { tx ->
        val own = ownId(tx)
        tx.connection.query("SELECT community_id, message_id, delete_token, attachment, uploaded_at FROM community_messages WHERE sender=? AND deleted_by IS NOT NULL AND delete_token IS NOT NULL", own.toString())
            .mapNotNull { row ->
                val id = AccountId.parse(row.text("community_id")!!)
                val messageId = AccountId.parse(row.text("message_id")!!)
                val token = token(tx, id, messageId, row) ?: return@mapNotNull null
                Triple(id, messageId, token)
            }
    }

    /** The blob is deleted (or can no longer be): its token is forgotten. */
    fun forgetToken(id: AccountId, messageId: AccountId) = storage.write { tx ->
        tx.connection.run("UPDATE community_messages SET delete_token=NULL, attachment=CASE WHEN deleted_by IS NULL THEN attachment ELSE NULL END WHERE community_id=? AND sender=? AND message_id=?",
            id.toString(), ownId(tx).toString(), messageId.toString())
    }

    fun setMedia(id: AccountId, sender: AccountId, messageId: AccountId, state: CommunityMediaState) = storage.write { tx ->
        tx.connection.run("UPDATE community_messages SET media_state=? WHERE community_id=? AND sender=? AND message_id=? AND deleted_by IS NULL",
            state.raw, id.toString(), sender.toString(), messageId.toString())
    }

    /** A downloaded file, encrypted under the community key. `false` when the message went meanwhile (deleted): nothing is kept. */
    fun storeFile(id: AccountId, sender: AccountId, messageId: AccountId, plaintext: ByteArray): Boolean = storage.write { tx ->
        val row = tx.connection.queryOne("SELECT file_id, deleted_by FROM community_messages WHERE community_id=? AND sender=? AND message_id=?",
            id.toString(), sender.toString(), messageId.toString()) ?: return@write false
        if (row.long("deleted_by") != null) return@write false
        val fileId = row.bytes("file_id") ?: RandomIds.make()
        tx.storage.files.write(plaintext, fileId, key(tx, id))
        tx.connection.run("UPDATE community_messages SET file_id=?, media_state=? WHERE community_id=? AND sender=? AND message_id=?",
            fileId, CommunityMediaState.LOCAL.raw, id.toString(), sender.toString(), messageId.toString())
        true
    }

    /** The decrypted file of a media message, or `null` when it is not here. */
    fun readFile(id: AccountId, sender: AccountId, messageId: AccountId): ByteArray? = storage.read { tx ->
        val row = tx.connection.queryOne("SELECT file_id, media_state, deleted_by FROM community_messages WHERE community_id=? AND sender=? AND message_id=?",
            id.toString(), sender.toString(), messageId.toString()) ?: return@read null
        if (row.long("deleted_by") != null || CommunityMediaState.of(row.long("media_state")) != CommunityMediaState.LOCAL) return@read null
        val fileId = row.bytes("file_id") ?: return@read null
        try {
            tx.storage.files.read(fileId, key(tx, id))
        } catch (_: StorageException) {
            null
        }
    }

    /**
     * Incoming messages shown in the open conversation. Returns the ones that may be READ: not
     * shown before, text or media, not deleted.
     */
    fun markDisplayed(id: AccountId, keys: Collection<Pair<AccountId, AccountId>>, now: Long): List<Pair<AccountId, AccountId>> = storage.write { tx ->
        val own = ownId(tx)
        val firstTime = ArrayList<Pair<AccountId, AccountId>>()
        for ((sender, messageId) in keys) {
            if (sender == own) continue
            val row = tx.connection.queryOne("SELECT displayed_at, deleted_by FROM community_messages WHERE community_id=? AND sender=? AND message_id=?",
                id.toString(), sender.toString(), messageId.toString()) ?: continue
            if (row.long("displayed_at") != null) continue
            tx.connection.run("UPDATE community_messages SET displayed_at=? WHERE community_id=? AND sender=? AND message_id=?", now, id.toString(), sender.toString(), messageId.toString())
            if (row.long("deleted_by") == null) firstTime += sender to messageId
        }
        firstTime
    }

    /**
     * A receipt from [from]: each id applies only to a text or media message this account sent in
     * the community, not deleted, whose recipient set contains [from]. DELIVERY sets the delivered
     * time; READ the read time and, if unset, the delivered time. Set once, never moved back.
     */
    fun applyReceipt(id: AccountId, from: AccountId, type: CommunityReceipt.Type, ids: List<AccountId>, now: Long): Int = storage.write { tx ->
        val own = ownId(tx).toString()
        var applied = 0
        for (messageId in ids.distinct()) {
            val target = tx.connection.queryOne("SELECT deleted_by FROM community_messages WHERE community_id=? AND sender=? AND message_id=?",
                id.toString(), own, messageId.toString()) ?: continue
            if (target.long("deleted_by") != null) continue
            val changed = when (type) {
                CommunityReceipt.Type.DELIVERY -> tx.connection.run(
                    "UPDATE community_recipients SET delivered_at=? WHERE community_id=? AND message_id=? AND member=? AND delivered_at IS NULL",
                    now, id.toString(), messageId.toString(), from.toString(),
                )
                CommunityReceipt.Type.READ -> tx.connection.run(
                    "UPDATE community_recipients SET read_at=coalesce(read_at, ?), delivered_at=coalesce(delivered_at, ?) WHERE community_id=? AND message_id=? AND member=?",
                    now, now, id.toString(), messageId.toString(), from.toString(),
                )
                else -> 0
            }
            applied += changed
        }
        applied
    }

    /**
     * A delete for everyone, already authorised (the target's sender or the owner). A message is
     * replaced by its placeholder (sender, id, time and who deleted it are kept); its text,
     * pointer, file and receipt state go. Not here yet: remembered (30 days, 1000 per community).
     */
    fun applyDelete(id: AccountId, targetSender: AccountId, target: AccountId, by: CommunityDeletion, now: Long): CommunityDeleteResult = storage.write { tx ->
        val row = tx.connection.queryOne("SELECT * FROM community_messages WHERE community_id=? AND sender=? AND message_id=?",
            id.toString(), targetSender.toString(), target.toString())
        if (row == null) {
            tx.connection.run("INSERT OR IGNORE INTO community_pending_deletes(community_id,target_sender,target_id,by_owner,remembered_at) VALUES(?,?,?,?,?)",
                id.toString(), targetSender.toString(), target.toString(), if (by == CommunityDeletion.OWNER) 1 else 0, now)
            val count = tx.connection.scalar("SELECT count(*) FROM community_pending_deletes WHERE community_id=?", id.toString()) as Long
            if (count > MAX_PENDING_DELETES) {
                tx.connection.run(
                    "DELETE FROM community_pending_deletes WHERE rowid IN (SELECT rowid FROM community_pending_deletes WHERE community_id=? ORDER BY remembered_at, rowid LIMIT ?)",
                    id.toString(), count - MAX_PENDING_DELETES,
                )
            }
            return@write CommunityDeleteResult(CommunityDeleteResult.Outcome.REMEMBERED)
        }
        if (row.long("deleted_by") != null) return@write CommunityDeleteResult(CommunityDeleteResult.Outcome.ALREADY_DELETED)
        val own = targetSender == ownId(tx)
        val token = if (own) token(tx, id, target, row) else null
        val key = key(tx, id)
        row.bytes("file_id")?.let { removeFilesAfterCommit(tx, listOf(it)) }
        val empty = tx.seal(ByteArray(0), itemId(target), key)
        // An own blob that still has to be deleted keeps its token (and pointer) until it is.
        tx.connection.run(
            "UPDATE community_messages SET sticker=NULL, body=?, attachment=CASE WHEN delete_token IS NULL THEN NULL ELSE attachment END, media_state=NULL, file_id=NULL, deleted_by=?, deleted_at=? WHERE community_id=? AND sender=? AND message_id=?",
            empty, by.raw, now, id.toString(), targetSender.toString(), target.toString(),
        )
        if (own) {
            tx.connection.run("DELETE FROM community_recipients WHERE community_id=? AND message_id=?", id.toString(), target.toString())
            // No envelope of it leaves any more.
            tx.connection.run("DELETE FROM community_outbox WHERE community_id=? AND message_id=?", id.toString(), target.toString())
            tx.connection.run("UPDATE community_messages SET status='sent' WHERE community_id=? AND sender=? AND message_id=? AND status IN ('pending','partial','preparing')",
                id.toString(), targetSender.toString(), target.toString())
        }
        tx.connection.run("DELETE FROM community_receipt_queue WHERE community_id=? AND message_id=?", id.toString(), target.toString())
        CommunityDeleteResult(CommunityDeleteResult.Outcome.APPLIED, token)
    }

    /** "Delete for me": the message, its file and receipt state go from this phone; its key is remembered for 30 days. */
    fun deleteForMe(id: AccountId, sender: AccountId, messageId: AccountId, now: Long): Boolean = storage.write { tx ->
        val row = tx.connection.queryOne("SELECT file_id FROM community_messages WHERE community_id=? AND sender=? AND message_id=?",
            id.toString(), sender.toString(), messageId.toString()) ?: return@write false
        row.bytes("file_id")?.let { removeFilesAfterCommit(tx, listOf(it)) }
        tx.connection.run("DELETE FROM community_messages WHERE community_id=? AND sender=? AND message_id=?", id.toString(), sender.toString(), messageId.toString())
        if (sender == ownId(tx)) {
            tx.connection.run("DELETE FROM community_recipients WHERE community_id=? AND message_id=?", id.toString(), messageId.toString())
        }
        tx.connection.run("DELETE FROM community_receipt_queue WHERE community_id=? AND message_id=?", id.toString(), messageId.toString())
        tx.connection.run("INSERT OR REPLACE INTO community_removed(community_id,sender,message_id,removed_at) VALUES(?,?,?,?)",
            id.toString(), sender.toString(), messageId.toString(), now)
        true
    }

    /** Every joined or ended community's newest text or media message (placeholders included) and its unread count. */
    fun activity(): List<CommunityActivity> = storage.read { tx ->
        val own = ownId(tx).toString()
        tx.connection.query("SELECT id FROM community_states WHERE state IS NOT NULL").map { group ->
            val id = AccountId.parse(group.text("id")!!)
            val last = tx.connection.queryOne(
                "SELECT rowid AS position, * FROM community_messages WHERE community_id=? ORDER BY coalesce(received_at, sent_at) DESC, rowid DESC LIMIT 1",
                id.toString(),
            )?.let { row ->
                val message = message(tx, id, row, key(tx, id))
                if (message.sender.toString() == own) message.copy(delivery = deliverySummary(tx, id, message.messageId)) else message
            }
            val unread = (tx.connection.scalar(
                "SELECT count(*) FROM community_messages WHERE community_id=? AND sender != ? AND displayed_at IS NULL AND deleted_by IS NULL",
                id.toString(), own,
            ) as Long).toInt()
            CommunityActivity(id, last, unread)
        }
    }

    // MARK: Receipts to send

    /** Queues receipts for [ids] to their original sender, persisted before anything is sent. */
    fun queueReceipts(id: AccountId, recipient: AccountId, type: CommunityReceipt.Type, ids: List<AccountId>, now: Long) = storage.write { tx ->
        for (messageId in ids) {
            tx.connection.run("INSERT OR IGNORE INTO community_receipt_queue(community_id,recipient,type,message_id,queued_at) VALUES(?,?,?,?,?)",
                id.toString(), recipient.toString(), type.number, messageId.toString(), now)
        }
    }

    /** Queued receipts, per community, original sender and kind, oldest first. */
    fun queuedReceipts(): Map<Triple<AccountId, AccountId, CommunityReceipt.Type>, List<AccountId>> = storage.read { tx ->
        val grouped = LinkedHashMap<Triple<AccountId, AccountId, CommunityReceipt.Type>, MutableList<AccountId>>()
        for (row in tx.connection.query("SELECT community_id, recipient, type, message_id FROM community_receipt_queue ORDER BY queued_at, rowid")) {
            val type = CommunityReceipt.Type.forNumber(row.int("type")!!) ?: continue
            val key = Triple(AccountId.parse(row.text("community_id")!!), AccountId.parse(row.text("recipient")!!), type)
            grouped.getOrPut(key) { ArrayList() } += AccountId.parse(row.text("message_id")!!)
        }
        grouped
    }

    fun dequeueReceipts(id: AccountId, recipient: AccountId, type: CommunityReceipt.Type, ids: List<AccountId>) = storage.write { tx ->
        for (messageId in ids) {
            tx.connection.run("DELETE FROM community_receipt_queue WHERE community_id=? AND recipient=? AND type=? AND message_id=?",
                id.toString(), recipient.toString(), type.number, messageId.toString())
        }
    }

    // MARK: Outbox

    fun enqueue(delivery: CommunityDelivery) = storage.write { tx ->
        val payload = delivery.payload?.let { tx.seal(it.toByteArray(), item(EVENT, delivery.communityId, delivery.messageId), key(tx, delivery.communityId)) }
        tx.connection.run("INSERT OR IGNORE INTO community_outbox(community_id,message_id,recipient,revision,envelope,payload) VALUES(?,?,?,?,?,?)",
            delivery.communityId.toString(), delivery.messageId.toString(), delivery.recipient.toString(), delivery.revision, delivery.envelope, payload)
    }

    /** The same delivery encrypted again (a receipt or a delete under a newer revision). */
    fun replace(delivery: CommunityDelivery) = storage.write { tx ->
        tx.connection.run("UPDATE community_outbox SET revision=?, envelope=? WHERE community_id=? AND message_id=? AND recipient=?",
            delivery.revision, delivery.envelope, delivery.communityId.toString(), delivery.messageId.toString(), delivery.recipient.toString())
    }

    fun pending(): List<CommunityDelivery> = storage.read { tx ->
        tx.connection.query("SELECT * FROM community_outbox ORDER BY rowid LIMIT 500").map { row ->
            val id = AccountId.parse(row.text("community_id")!!)
            val messageId = AccountId.parse(row.text("message_id")!!)
            val payload = row.blob("payload")?.let { sealed ->
                try {
                    CommunityMessage.parseFrom(tx.open(sealed, item(EVENT, id, messageId), key(tx, id)))
                } catch (_: InvalidProtocolBufferException) {
                    null
                }
            }
            CommunityDelivery(id, messageId, AccountId.parse(row.text("recipient")!!), row.long("revision")!!, row.blob("envelope")!!, payload)
        }
    }
    fun delivered(delivery: CommunityDelivery) = storage.write { tx -> settle(tx, delivery, CommunityRecipient.ACCEPTED) }

    /** The relay no longer delivers to this recipient (left, removed or deleted): it is not counted. */
    fun skipped(delivery: CommunityDelivery) = storage.write { tx -> settle(tx, delivery, CommunityRecipient.SKIPPED) }

    /** A queued receipt or delete that can no longer be delivered: it just goes. */
    fun drop(delivery: CommunityDelivery) = storage.write { tx ->
        tx.connection.run("DELETE FROM community_outbox WHERE community_id=? AND message_id=? AND recipient=?",
            delivery.communityId.toString(), delivery.messageId.toString(), delivery.recipient.toString())
    }

    /**
     * This recipient will never get the message (the relay refused it for good): its envelope goes,
     * the message stays `failed` while the others are still delivered.
     */
    fun failed(delivery: CommunityDelivery) = storage.write { tx ->
        tx.connection.run("UPDATE community_messages SET status='failed' WHERE community_id=? AND message_id=? AND status!='stale'",
            delivery.communityId.toString(), delivery.messageId.toString())
        settle(tx, delivery, CommunityRecipient.FAILED)
    }
    fun mark(id: AccountId, messageId: AccountId, status: String, clearOutbox: Boolean = false) = storage.write { tx ->
        tx.connection.run("UPDATE community_messages SET status=? WHERE community_id=? AND message_id=?", status, id.toString(), messageId.toString())
        if (clearOutbox) {
            tx.connection.run("DELETE FROM community_outbox WHERE community_id=? AND message_id=?", id.toString(), messageId.toString())
            if (status == "stale" || status == "failed") {
                tx.connection.run("UPDATE community_recipients SET transport=? WHERE community_id=? AND message_id=? AND transport=?",
                    status, id.toString(), messageId.toString(), CommunityRecipient.PENDING)
            }
        }
    }
    fun wasProcessed(envelope: String): Boolean = storage.read { it.envelopes.contains("community:$envelope") }
    fun processed(envelope: String) = storage.write { it.envelopes.record("community:$envelope") }

    // MARK: Maintenance

    /**
     * Forgets what is kept for a while only: "delete for me" keys, pending deletes and placeholders
     * after 30 days, delete tokens of own uploads after 14 days (the relay's TTL has removed the
     * blob by then).
     */
    fun prune(now: Long) = storage.write { tx ->
        val monthAgo = now - KEEP_MILLIS
        tx.connection.run("DELETE FROM community_removed WHERE removed_at < ?", monthAgo)
        tx.connection.run("DELETE FROM community_pending_deletes WHERE remembered_at < ?", monthAgo)
        tx.connection.run("DELETE FROM community_messages WHERE deleted_by IS NOT NULL AND deleted_at < ? AND delete_token IS NULL", monthAgo)
        tx.connection.run("UPDATE community_messages SET delete_token=NULL, attachment=CASE WHEN deleted_by IS NULL THEN attachment ELSE NULL END WHERE delete_token IS NOT NULL AND uploaded_at < ?", now - TOKEN_MILLIS)
    }

    // MARK: Internals

    private fun insert(tx: StorageTransaction, message: StoredCommunityMessage, fileId: ByteString? = null, deletedAt: Long? = null) {
        val exists = tx.connection.queryOne("SELECT 1 AS present FROM community_messages WHERE community_id=? AND sender=? AND message_id=?",
            message.communityId.toString(), message.sender.toString(), message.messageId.toString())
        if (exists != null) return
        val key = key(tx, message.communityId)
        val body = tx.seal(message.text.toByteArray(Charsets.UTF_8), itemId(message.messageId), key)
        val pointer = message.attachment?.let { tx.seal(it.toByteArray(), item(ATTACHMENT, message.communityId, message.messageId), key) }
        tx.connection.run(
            "INSERT INTO community_messages(community_id,sender,message_id,sent_at,body,status,received_at,attachment,media_state,file_id,deleted_by,deleted_at,displayed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            message.communityId.toString(), message.sender.toString(), message.messageId.toString(), message.sentAt, body, message.status,
            message.receivedAt, pointer, message.media?.raw, fileId, message.deletedBy?.raw, deletedAt ?: message.deletedBy?.let { message.receivedAt },
            message.displayedAt,
        )
        if (message.sticker != null && !message.isDeleted) {
            tx.connection.run("UPDATE community_messages SET sticker=? WHERE community_id=? AND sender=? AND message_id=?",
                tx.seal(message.sticker.toByteArray(), itemId(message.messageId), key), message.communityId.toString(), message.sender.toString(), message.messageId.toString())
        }
    }

    private fun setRecipients(tx: StorageTransaction, id: AccountId, messageId: AccountId, recipients: List<AccountId>) {
        for (member in recipients) {
            tx.connection.run("INSERT OR IGNORE INTO community_recipients(community_id,message_id,member,transport) VALUES(?,?,?,?)",
                id.toString(), messageId.toString(), member.toString(), CommunityRecipient.PENDING)
        }
    }

    private fun find(tx: StorageTransaction, id: AccountId, sender: AccountId, messageId: AccountId): StoredCommunityMessage? {
        val row = tx.connection.queryOne("SELECT rowid AS position, * FROM community_messages WHERE community_id=? AND sender=? AND message_id=?",
            id.toString(), sender.toString(), messageId.toString()) ?: return null
        val message = message(tx, id, row, key(tx, id))
        return if (sender == ownId(tx)) message.copy(delivery = deliverySummary(tx, id, messageId)) else message
    }

    private fun message(tx: StorageTransaction, id: AccountId, row: Row, key: ByteArray): StoredCommunityMessage {
        val messageId = AccountId.parse(row.text("message_id")!!)
        val body = tx.open(row.blob("body")!!, itemId(messageId), key)
        val deletedBy = CommunityDeletion.of(row.long("deleted_by"))
        val pointer = if (deletedBy != null) null else row.blob("attachment")?.let { sealed ->
            try {
                Attachment.parseFrom(tx.open(sealed, item(ATTACHMENT, id, messageId), key))
            } catch (e: InvalidProtocolBufferException) {
                storageFailure(StorageError.Integrity, e)
            }
        }
        val sentAt = row.long("sent_at")!!
        return StoredCommunityMessage(
            communityId = id,
            sender = AccountId.parse(row.text("sender")!!),
            messageId = messageId,
            sentAt = sentAt,
            text = String(body, Charsets.UTF_8),
            sticker = if (deletedBy != null) null else row.blob("sticker")?.let { Sticker.parseFrom(tx.open(it, itemId(messageId), key)) },
            status = row.text("status")!!,
            attachment = pointer,
            media = if (pointer != null) CommunityMediaState.of(row.long("media_state")) ?: CommunityMediaState.REMOTE else null,
            deletedBy = deletedBy,
            receivedAt = row.long("received_at") ?: sentAt,
            displayedAt = row.long("displayed_at"),
        )
    }

    private fun token(tx: StorageTransaction, id: AccountId, messageId: AccountId, row: Row): Pair<ByteArray, ByteArray>? {
        val sealedToken = row.blob("delete_token") ?: return null
        val sealedPointer = row.blob("attachment") ?: return null
        val key = key(tx, id)
        val token = tx.open(sealedToken, item(TOKEN, id, messageId), key)
        val pointer = try {
            Attachment.parseFrom(tx.open(sealedPointer, item(ATTACHMENT, id, messageId), key))
        } catch (_: InvalidProtocolBufferException) {
            return null
        }
        if (pointer.mediaId.size() != RandomIds.LENGTH) return null
        return pointer.mediaId.toByteArray() to token
    }

    private fun deliverySummaries(tx: StorageTransaction, id: AccountId): Map<AccountId, CommunityDeliverySummary> =
        tx.connection.query(
            """
            SELECT message_id,
                sum(CASE WHEN transport != 'skipped' THEN 1 ELSE 0 END) AS total,
                sum(CASE WHEN transport = 'accepted' THEN 1 ELSE 0 END) AS accepted,
                sum(CASE WHEN transport != 'skipped' AND delivered_at IS NOT NULL THEN 1 ELSE 0 END) AS delivered,
                sum(CASE WHEN transport != 'skipped' AND read_at IS NOT NULL THEN 1 ELSE 0 END) AS seen,
                sum(CASE WHEN transport IN ('failed','stale') THEN 1 ELSE 0 END) AS failed
            FROM community_recipients WHERE community_id = ? GROUP BY message_id
            """.trimIndent(),
            id.toString(),
        ).associate { row ->
            AccountId.parse(row.text("message_id")!!) to CommunityDeliverySummary(
                row.int("total") ?: 0, row.int("accepted") ?: 0, row.int("delivered") ?: 0, row.int("seen") ?: 0, row.int("failed") ?: 0,
            )
        }

    private fun deliverySummary(tx: StorageTransaction, id: AccountId, messageId: AccountId): CommunityDeliverySummary? {
        val count = tx.connection.scalar("SELECT count(*) FROM community_recipients WHERE community_id=? AND message_id=?", id.toString(), messageId.toString()) as Long
        if (count == 0L) return null
        return deliverySummaries(tx, id)[messageId]
    }

    private fun settle(tx: StorageTransaction, delivery: CommunityDelivery, transport: String) {
        tx.connection.run("DELETE FROM community_outbox WHERE community_id=? AND message_id=? AND recipient=?", delivery.communityId.toString(), delivery.messageId.toString(), delivery.recipient.toString())
        tx.connection.run("UPDATE community_recipients SET transport=? WHERE community_id=? AND message_id=? AND member=?",
            transport, delivery.communityId.toString(), delivery.messageId.toString(), delivery.recipient.toString())
        val count = tx.connection.scalar("SELECT count(*) FROM community_outbox WHERE community_id=? AND message_id=?", delivery.communityId.toString(), delivery.messageId.toString()) as Long
        tx.connection.run("UPDATE community_messages SET status=? WHERE community_id=? AND message_id=? AND status NOT IN ('stale','failed','received')",
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
    private fun ownId(tx: StorageTransaction): AccountId = tx.ownAccountId()
    private fun removeFilesAfterCommit(tx: StorageTransaction, itemIds: List<ByteString>) {
        if (itemIds.isEmpty()) return
        val files = tx.storage.files
        tx.afterCommit {
            for (fileId in itemIds) {
                try {
                    files.remove(fileId)
                } catch (_: StorageException) {
                }
            }
        }
    }
    private fun itemId(messageId: AccountId): ByteString = ByteString.copyFrom(messageId.toBytes())
    private fun record(row: Row): StoredCommunity = StoredCommunity(AccountId.parse(row.text("id")!!), row.text("owner_key")!!, row.text("name")!!,
        row.text("state")?.let { SignedCommunityState(it, row.text("signature")!!) }, row.bool("active"))

    internal companion object {
        const val COLUMNS = "id, owner_key, name, state, signature, active"
        const val MAX_PENDING_DELETES = 1000L
        const val KEEP_MILLIS = 30L * 24 * 60 * 60 * 1000
        const val TOKEN_MILLIS = 14L * 24 * 60 * 60 * 1000
        private const val ATTACHMENT = "us2us community attachment"
        private const val TOKEN = "us2us community delete token"
        private const val EVENT = "us2us community event"

        /** A distinct item id per purpose, so one sealed field can never be read as another. */
        fun item(label: String, id: AccountId, messageId: AccountId): ByteString =
            ByteString.copyFrom(java.security.MessageDigest.getInstance("SHA-256").digest(label.toByteArray(Charsets.UTF_8) + id.toBytes() + messageId.toBytes()).copyOf(RandomIds.LENGTH))

        /** Item ids of every community file present in the database (garbage collection keeps them). */
        fun fileIds(tx: StorageTransaction): Set<ByteString> =
            tx.connection.query("SELECT file_id FROM community_messages WHERE file_id IS NOT NULL").mapNotNullTo(HashSet()) { it.bytes("file_id") }
    }
}
