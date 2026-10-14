package com.syconx.us2us.runtime.storage

import com.google.protobuf.ByteString
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.proto.ConversationWipe
import com.syconx.us2us.core.proto.content
import com.syconx.us2us.core.proto.controlMessage
import com.syconx.us2us.core.proto.conversationWipe
import com.syconx.us2us.runtime.vault.SlotId
import java.time.Instant

/**
 * Ghost-mode and disappearing-message wipes and garbage collection (PROTOCOL.md §5.3, §8.1, §8.2).
 *
 * A conversation is always destroyed in this order:
 * 1. **crypto-shred**: delete its key slot (irreversible, instant; from here on every item under
 *    its `CK` is unreadable),
 * 2. delete its rows (messages, reactions, attachments, pending sends),
 * 3. delete its files after the transaction committed.
 *
 * A crash after step 1 leaves only ciphertext, which the next pass collects.
 */
internal object WipeService {
    /** Wipes one conversation (inside an open write transaction). */
    fun wipe(
        conversation: Conversation,
        reason: WipeReason,
        tx: StorageTransaction,
        now: Instant = Instant.now(),
        notifyContact: Boolean = false,
    ): WipedConversation {
        tx.requireWritable()
        tx.storage.keyring.destroy(conversation.slotId)
        val itemIds = ArrayList<ByteString>()
        for (row in tx.connection.query("SELECT id, thumbnail_id FROM attachments WHERE conversation_id = ?", conversation.id.raw)) {
            row.bytes("id")?.let(itemIds::add)
            row.bytes("thumbnail_id")?.let(itemIds::add)
        }
        // Cascades to messages, reactions, attachments, pending deletes, removed-message ids and
        // the conversation's outbox entries.
        tx.connection.run("DELETE FROM conversations WHERE id = ?", conversation.id.raw)
        tx.attachments.removeFilesAfterCommit(itemIds)
        // Also when called inside an outer write (a received wipe, "delete for both"): the rows'
        // old page images must not stay in the WAL.
        tx.checkpointAfterCommit()

        val ghost = conversation.settings.ghost
        val isGhostWipe = reason == WipeReason.GHOST_TIMEOUT || (reason == WipeReason.SHREDDED_EARLIER && ghost.enabled)
        val wipeContact = isGhostWipe && ghost.enabled && ghost.wipeContact
        val contactState = tx.contacts.requestState(conversation.contact)
        if (notifyContact && isGhostWipe && contactState != null && contactState != ContactState.BLOCKED) {
            val wipe = content {
                control = controlMessage {
                    this.wipe = conversationWipe {
                        requestedAt = now.toEpochMilli()
                        this.reason = ConversationWipe.Reason.GHOST_TIMEOUT
                    }
                }
            }
            tx.outbox.enqueue(wipe, conversation.contact, now = now)
        }
        if (wipeContact && contactState != null) {
            if (contactState == ContactState.BLOCKED) {
                // "Leave no trace" must not lift a block: keep only the block itself.
                tx.contacts.stripToBlock(conversation.contact)
            } else {
                // Hidden now; forgotten (contact, session, identity) once the ConversationWipe left
                // (OutboxRepository.markSent) — or right away if there is nothing to send.
                tx.contacts.markPendingForget(conversation.contact)
                val pending = tx.connection.queryOne(
                    "SELECT 1 FROM outbox WHERE recipient = ? AND state != ? LIMIT 1",
                    conversation.contact.toBytes(),
                    OutboxState.FAILED.raw,
                )
                if (pending == null) tx.contacts.forget(conversation.contact)
            }
        }
        return WipedConversation(conversation.id, conversation.contact, reason, wipeContact, isGhostWipe)
    }

    /** One maintenance pass. [collectGarbage] also removes orphaned slots and files (done on every unlock). */
    fun performDueWipes(storage: LocalStorage, now: Instant, collectGarbage: Boolean): WipeReport {
        // Overdue ghost slots first (the same pass the locked background wipe runs).
        val shreddedNow: Set<SlotId> = try {
            storage.keyring.destroyExpiredSlots(now).toSet()
        } catch (_: StorageException) {
            emptySet()
        }
        val report = storage.write { tx ->
            val nowMillis = now.toEpochMilli()
            val conversations = tx.connection.query("SELECT ${ConversationRepository.COLUMNS} FROM conversations")
                .map { tx.conversations.conversation(it) }
            val wiped = ArrayList<WipedConversation>()
            for (conversation in conversations) {
                val due = conversation.ghostDeadline?.let { it.toEpochMilli() <= nowMillis } ?: false
                val missing = !storage.keyring.slotExists(conversation.slotId)
                if (!due && !missing) continue
                val reason = if (due || conversation.slotId in shreddedNow) WipeReason.GHOST_TIMEOUT else WipeReason.SHREDDED_EARLIER
                wiped += wipe(conversation, reason, tx, now, notifyContact = true)
            }

            // Disappearing messages (§8.1).
            val byConversation = LinkedHashMap<ConversationId, MutableList<ByteString>>()
            for (row in tx.connection.query("SELECT conversation_id, message_id FROM messages WHERE expires_at <= ?", nowMillis)) {
                val id = ConversationId.of(row.bytes("conversation_id")) ?: continue
                val messageId = row.bytes("message_id") ?: continue
                byConversation.getOrPut(id) { ArrayList() } += messageId
            }
            for ((conversationId, messageIds) in byConversation) tx.messages.deleteLocally(messageIds, conversationId, now)

            var orphanSlots = 0
            var orphanFiles = 0
            if (collectGarbage) {
                // Communities keep their own slots (CommunityRepository); only unreferenced ones go.
                val referencedSlots = tx.connection.query("SELECT slot_id FROM conversations UNION SELECT slot_id FROM community_states")
                    .mapNotNullTo(HashSet()) { row -> row.blob("slot_id")?.let(::slotIdOf) }
                for (slotId in storage.keyring.slotIds() - referencedSlots) {
                    storage.keyring.destroy(slotId)
                    orphanSlots += 1
                }
                val referencedItems = HashSet<ByteString>()
                for (row in tx.connection.query("SELECT id, thumbnail_id FROM attachments")) {
                    row.bytes("id")?.let(referencedItems::add)
                    row.bytes("thumbnail_id")?.let(referencedItems::add)
                }
                for (itemId in storage.files.allItemIds() - referencedItems) {
                    storage.files.remove(itemId)
                    orphanFiles += 1
                }
                for (stray in storage.files.strayFiles()) {
                    removeIfPresent(stray)
                    orphanFiles += 1
                }
                tx.envelopes.prune(now)
                tx.messages.pruneRemovedIds(now)
            }
            tx.messages.markUndelivered(now)
            WipeReport(wiped, byConversation, orphanFiles, orphanSlots)
        }
        if (!report.isEmpty) {
            try {
                storage.database.checkpoint()
            } catch (_: StorageException) {
            }
        }
        // Repairs schedule drift (failed after-commit writes, a damaged file).
        try {
            storage.reconcileGhostSchedule()
        } catch (_: StorageException) {
        }
        return report
    }
}
