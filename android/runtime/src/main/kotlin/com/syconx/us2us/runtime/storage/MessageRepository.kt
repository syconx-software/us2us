package com.syconx.us2us.runtime.storage

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.messenger.SavedMedia
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.DataMessage
import com.syconx.us2us.core.proto.LiveLocation
import com.syconx.us2us.core.proto.ReceiptMessage
import com.syconx.us2us.core.proto.Reaction
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.days

/**
 * Messages of PROTOCOL.md §8: every content type of `DataMessage`, delivery status, reactions,
 * quotes, view-once, delete-for-everyone and disappearing deadlines.
 *
 * The whole `DataMessage` is stored as an item under the conversation key (`itemId = message_id`,
 * §5.3); columns hold only what queries need.
 *
 * **Disappearing (§8.1)**: an outgoing message with timer T expires at `createdAt + T`; an incoming
 * one at `displayedAt + T`, set the first time it is displayed ([markDisplayed]). Wipes delete
 * expired rows and their files ([LocalStorage.performDueWipes]); queries never return a message
 * past its deadline even before that runs.
 */
class MessageRepository internal constructor(private val tx: StorageTransaction) {
    // MARK: Storing

    /**
     * Stores a `DataMessage` received from the conversation's contact and applies its semantics
     * (bubble, reaction, delete-for-everyone, live-location update). Records activity for ghost
     * mode, except for duplicates and live-location updates (§8.2).
     */
    fun storeIncoming(
        data: DataMessage,
        conversationId: ConversationId,
        serverTimestamp: Long? = null,
        receivedAt: Instant = Instant.now(),
    ): DataMessageOutcome {
        tx.requireWritable()
        val conversation = tx.conversations.get(conversationId) ?: storageFailure(StorageError.NotFound)
        return store(data, conversation, outgoing = false, sender = conversation.contact, serverTimestamp, receivedAt)
    }

    /**
     * Stores a `DataMessage` we are sending (status sending), with the same semantics as
     * [storeIncoming]. Enqueue the send with [OutboxRepository.enqueue] in the same transaction.
     */
    fun storeOutgoing(data: DataMessage, conversationId: ConversationId, time: Instant = Instant.now()): DataMessageOutcome {
        tx.requireWritable()
        val conversation = tx.conversations.get(conversationId) ?: storageFailure(StorageError.NotFound)
        return store(data, conversation, outgoing = true, sender = tx.ownAccountId(), serverTimestamp = null, time = time)
    }

    private fun store(
        data: DataMessage,
        conversation: Conversation,
        outgoing: Boolean,
        sender: AccountId,
        serverTimestamp: Long?,
        time: Instant,
    ): DataMessageOutcome {
        if (data.messageId.size() != RandomIds.LENGTH) storageFailure(StorageError.InvalidState("message id"))
        if (exists(data.messageId, conversation.id)) return DataMessageOutcome.Duplicate
        var countsAsActivity = true
        val outcome = when (data.payloadCase) {
            DataMessage.PayloadCase.REACTION ->
                applyReaction(data.reaction, data.messageId, sender, data.sentAt, conversation)
            DataMessage.PayloadCase.DELETE -> applyDelete(data.delete.targetMessageId, sender, conversation, time)
            DataMessage.PayloadCase.LIVE_LOCATION -> {
                val existing = liveLocationBubble(data.liveLocation.sessionId, conversation.id)
                if (existing != null) {
                    countsAsActivity = false
                    updateLiveLocation(existing, data, data.liveLocation, sender, conversation, time)
                } else {
                    insertBubble(data, conversation, outgoing, sender, serverTimestamp, time)
                }
            }
            DataMessage.PayloadCase.TEXT,
            DataMessage.PayloadCase.ATTACHMENT,
            DataMessage.PayloadCase.LOCATION,
            DataMessage.PayloadCase.STICKER,
            -> insertBubble(data, conversation, outgoing, sender, serverTimestamp, time)
            // A payload added by a newer client: ignore it (brp.proto rules).
            else -> return DataMessageOutcome.Ignored
        }
        if (countsAsActivity && outcome != DataMessageOutcome.Duplicate) tx.conversations.recordActivity(conversation.id, time)
        return outcome
    }

    /**
     * A row with this id, or one that was removed entirely (expired, deleted for me): a
     * retransmission of the same `message_id` must not bring it back.
     */
    private fun exists(messageId: ByteString, conversationId: ConversationId): Boolean {
        if (tx.connection.queryOne(
                "SELECT 1 FROM messages WHERE conversation_id = ? AND message_id = ?",
                conversationId.raw,
                messageId,
            ) != null
        ) {
            return true
        }
        return wasRemoved(messageId, conversationId)
    }

    internal fun wasRemoved(messageId: ByteString, conversationId: ConversationId): Boolean = tx.connection.queryOne(
        "SELECT 1 FROM removed_messages WHERE conversation_id = ? AND message_id = ?",
        conversationId.raw,
        messageId,
    ) != null

    private fun insertBubble(
        received: DataMessage,
        conversation: Conversation,
        outgoing: Boolean,
        sender: AccountId,
        serverTimestamp: Long?,
        time: Instant,
    ): DataMessageOutcome {
        var data = received.strippingViewOncePreview()
        // A quote shows the excerpt only of a message that stays: never one with a timer, a
        // view-once item, a deleted or a removed one (§9: nothing is displayed after its deadline).
        if (data.hasQuote() && data.quote.previewText.isNotEmpty() && !mayKeepQuotePreview(data.quote.targetMessageId, conversation.id)) {
            data = data.toBuilder().setQuote(data.quote.toBuilder().setPreviewText("")).build()
        }
        val key = tx.conversationKey(conversation.slotId)
        var body: ByteArray? = tx.seal(data.toByteArray(), data.messageId, key)
        var deletedAt: Instant? = null
        // A delete-for-everyone that arrived before its message (§8.5).
        val pending = tx.connection.queryOne(
            "SELECT sender, requested_at FROM pending_deletes WHERE conversation_id = ? AND message_id = ?",
            conversation.id.raw,
            data.messageId,
        )
        if (pending != null && pending.blob("sender").contentEquals(sender.toBytes())) {
            body = null
            deletedAt = pending.instant("requested_at") ?: time
            tx.connection.run(
                "DELETE FROM pending_deletes WHERE conversation_id = ? AND message_id = ?",
                conversation.id.raw,
                data.messageId,
            )
        }
        val timer = data.expireTimerSeconds.unsigned()
        var expiresAt: Long? = if (outgoing && timer > 0) time.toEpochMilli() + timer * 1000 else null
        var liveSession: ByteString? = null
        if (data.hasLiveLocation()) {
            liveSession = data.liveLocation.sessionId
            if (outgoing && timer > 0) {
                val now = time.toEpochMilli()
                expiresAt = liveLocationDeadline(now, timer, data.liveLocation, now, now)
            }
        }
        tx.connection.run(
            """
            INSERT INTO messages (conversation_id, message_id, outgoing, sender, kind, body, sent_at, created_at,
                server_timestamp, status, expire_timer, expires_at, view_once, quote_target, live_session, deleted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            conversation.id.raw,
            data.messageId,
            outgoing,
            sender.toBytes(),
            MessageKind.of(data.payloadCase).raw,
            body,
            data.sentAt.clampedUnsigned(),
            time.toEpochMilli(),
            serverTimestamp,
            (if (outgoing) MessageStatus.SENDING else MessageStatus.RECEIVED).raw,
            timer,
            expiresAt,
            data.viewOnce,
            if (data.hasQuote()) data.quote.targetMessageId else null,
            liveSession,
            deletedAt?.toEpochMilli(),
        )
        if (deletedAt == null) linkAttachments(data, outgoing, conversation.id, time)
        val stored = get(data.messageId, conversation.id) ?: storageFailure(StorageError.NotFound)
        return DataMessageOutcome.Stored(stored)
    }

    /**
     * Creates the attachment rows of a stored message. An outgoing message whose rows already exist
     * (created to hold the picked file before the upload) reuses them. An outgoing attachment
     * without a relay pointer yet is pending upload.
     */
    private fun linkAttachments(data: DataMessage, outgoing: Boolean, conversationId: ConversationId, time: Instant) {
        val existing = if (outgoing) tx.attachments.forMessage(data.messageId, conversationId) else emptyList()
        for ((index, attachment) in attachments(data).withIndex()) {
            val uploaded = attachment.mediaId.size() == RandomIds.LENGTH
            if (index < existing.size) {
                if (uploaded) tx.attachments.setDescriptor(attachment, existing[index].id, time)
                continue
            }
            val state = when {
                !outgoing -> AttachmentState.PENDING_DOWNLOAD
                uploaded -> AttachmentState.UPLOADED
                else -> AttachmentState.PENDING_UPLOAD
            }
            tx.attachments.create(data.messageId, conversationId, attachment, state, time)
        }
    }

    /**
     * Replaces the content of an outgoing message that has not been sent yet — typically once its
     * attachment was uploaded and the `Attachment` now carries the relay pointer and keys. Updates
     * the attachment rows too.
     */
    fun updateOutgoingContent(data: DataMessage, conversationId: ConversationId, time: Instant = Instant.now()) {
        tx.requireWritable()
        val message = get(data.messageId, conversationId, includeExpired = true)
        if (message == null || !message.isOutgoing || message.status != MessageStatus.SENDING || message.isDeleted ||
            MessageKind.of(data.payloadCase) != message.kind
        ) {
            storageFailure(StorageError.InvalidState("not an unsent outgoing message"))
        }
        val key = tx.conversationKey(conversationId)
        val body = tx.seal(data.toByteArray(), data.messageId, key)
        tx.connection.run("UPDATE messages SET body = ? WHERE id = ?", body, message.localId)
        linkAttachments(data, outgoing = true, conversationId, time)
    }

    // MARK: Reactions

    private fun applyReaction(
        reaction: Reaction,
        reactionId: ByteString,
        reactor: AccountId,
        sentAt: Long,
        conversation: Conversation,
    ): DataMessageOutcome {
        val target = reaction.targetMessageId
        val targetRow = tx.connection.queryOne(
            "SELECT deleted_at FROM messages WHERE conversation_id = ? AND message_id = ?",
            conversation.id.raw,
            target,
        )
        if (targetRow == null || targetRow.long("deleted_at") != null) return DataMessageOutcome.Ignored
        val existing = tx.connection.queryOne(
            "SELECT sent_at FROM reactions WHERE conversation_id = ? AND target_message_id = ? AND reactor = ?",
            conversation.id.raw,
            target,
            reactor.toBytes(),
        )
        // Last writer wins per reactor and message.
        val existingSentAt = existing?.long("sent_at")
        if (existingSentAt != null && existingSentAt > sentAt.clampedUnsigned()) return DataMessageOutcome.Ignored
        if (reaction.remove || reaction.emoji.isEmpty()) {
            tx.connection.run(
                "DELETE FROM reactions WHERE conversation_id = ? AND target_message_id = ? AND reactor = ?",
                conversation.id.raw,
                target,
                reactor.toBytes(),
            )
            return DataMessageOutcome.Reaction(target)
        }
        val key = tx.conversationKey(conversation.slotId)
        val emoji = tx.seal(reaction.emoji.toByteArray(Charsets.UTF_8), reactionId, key)
        tx.connection.run(
            """
            INSERT OR REPLACE INTO reactions (conversation_id, target_message_id, reactor, reaction_id, emoji, sent_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            conversation.id.raw,
            target,
            reactor.toBytes(),
            reactionId,
            emoji,
            sentAt.clampedUnsigned(),
        )
        return DataMessageOutcome.Reaction(target)
    }

    /** Reactions on the given messages, keyed by target message id. */
    fun reactions(messageIds: List<ByteString>, conversationId: ConversationId): Map<ByteString, List<StoredReaction>> {
        if (messageIds.isEmpty()) return emptyMap()
        val key = tx.conversationKey(conversationId)
        val result = LinkedHashMap<ByteString, MutableList<StoredReaction>>()
        for (messageId in messageIds) {
            val rows = tx.connection.query(
                """
                SELECT reactor, reaction_id, emoji, sent_at FROM reactions
                WHERE conversation_id = ? AND target_message_id = ? ORDER BY sent_at
                """.trimIndent(),
                conversationId.raw,
                messageId,
            )
            for (row in rows) {
                val reactor = accountIdOf(row.blob("reactor")) ?: continue
                val reactionId = row.bytes("reaction_id") ?: continue
                val item = row.blob("emoji") ?: continue
                val emoji = String(tx.open(item, reactionId, key), Charsets.UTF_8)
                result.getOrPut(messageId) { ArrayList() } +=
                    StoredReaction(messageId, reactor, emoji, row.instant("sent_at") ?: Instant.EPOCH)
            }
        }
        return result
    }

    // MARK: Deletes

    /**
     * Delete-for-everyone from [sender] (§8.5): only the original sender may delete. A delete for a
     * message not received yet is remembered and applied when it arrives.
     */
    private fun applyDelete(target: ByteString, sender: AccountId, conversation: Conversation, time: Instant): DataMessageOutcome {
        val row = tx.connection.queryOne(
            "SELECT sender FROM messages WHERE conversation_id = ? AND message_id = ?",
            conversation.id.raw,
            target,
        )
        if (row == null) {
            // Already removed here (expired, deleted for me): nothing to remember.
            if (wasRemoved(target, conversation.id)) return DataMessageOutcome.Ignored
            tx.connection.run(
                """
                INSERT OR REPLACE INTO pending_deletes (conversation_id, message_id, sender, requested_at)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                conversation.id.raw,
                target,
                sender.toBytes(),
                time.toEpochMilli(),
            )
            return DataMessageOutcome.Ignored
        }
        if (!row.blob("sender").contentEquals(sender.toBytes())) return DataMessageOutcome.Ignored
        tombstone(target, conversation.id, time)
        return DataMessageOutcome.Deleted(target)
    }

    /** Our own delete-for-everyone of an outgoing message: tombstones it locally. Send the `Delete` through the outbox in the same transaction. */
    fun deleteForEveryone(messageId: ByteString, conversationId: ConversationId, time: Instant = Instant.now()) {
        tx.requireWritable()
        val message = get(messageId, conversationId, includeExpired = true)
        if (message == null || !message.isOutgoing) {
            storageFailure(StorageError.InvalidState("only own messages can be deleted for everyone"))
        }
        tombstone(messageId, conversationId, time)
        tx.conversations.recordActivity(conversationId, time)
    }

    /**
     * Removes body, attachments and reactions; keeps a "deleted" row. Quote excerpts of it in
     * replies are scrubbed, and the WAL is checkpointed after the commit.
     */
    private fun tombstone(messageId: ByteString, conversationId: ConversationId, time: Instant) {
        tx.attachments.deleteAll(listOf(messageId), conversationId)
        tx.connection.run(
            "DELETE FROM reactions WHERE conversation_id = ? AND target_message_id = ?",
            conversationId.raw,
            messageId,
        )
        tx.connection.run(
            """
            UPDATE messages SET body = NULL, deleted_at = coalesce(deleted_at, ?)
            WHERE conversation_id = ? AND message_id = ?
            """.trimIndent(),
            time.toEpochMilli(),
            conversationId.raw,
            messageId,
        )
        scrubQuotePreviews(listOf(messageId), conversationId)
        tx.checkpointAfterCommit()
    }

    /**
     * "Delete for me" (and expired disappearing messages): removes the rows, attachments and
     * reactions entirely, and pending sends of them. The ids are remembered ([REMOVED_ID_RETENTION])
     * so a retransmission is a duplicate; quote excerpts of them are scrubbed; the WAL is
     * checkpointed after the commit.
     */
    fun deleteLocally(messageIds: List<ByteString>, conversationId: ConversationId, time: Instant = Instant.now()) {
        tx.requireWritable()
        if (messageIds.isEmpty()) return
        tx.attachments.deleteAll(messageIds, conversationId)
        val day = EnvelopeLog.day(time)
        for (messageId in messageIds) {
            tx.connection.run(
                "DELETE FROM reactions WHERE conversation_id = ? AND target_message_id = ?",
                conversationId.raw,
                messageId,
            )
            tx.connection.run("DELETE FROM messages WHERE conversation_id = ? AND message_id = ?", conversationId.raw, messageId)
            tx.connection.run("DELETE FROM outbox WHERE conversation_id = ? AND message_id = ?", conversationId.raw, messageId)
            tx.connection.run(
                "INSERT OR IGNORE INTO removed_messages (conversation_id, message_id, removed_day) VALUES (?, ?, ?)",
                conversationId.raw,
                messageId,
                day,
            )
        }
        scrubQuotePreviews(messageIds, conversationId)
        tx.checkpointAfterCommit()
    }

    /** Forgets removed ids older than [REMOVED_ID_RETENTION]. */
    fun pruneRemovedIds(now: Instant = Instant.now()): Int {
        tx.requireWritable()
        return tx.connection.run(
            "DELETE FROM removed_messages WHERE removed_day < ?",
            now.toEpochMilli() - REMOVED_ID_RETENTION.inWholeMilliseconds,
        )
    }

    // MARK: Quote previews

    /** A reply may keep (and show) the excerpt of its target only while the target is here and permanent. */
    internal fun mayKeepQuotePreview(target: ByteString, conversationId: ConversationId): Boolean {
        val row = tx.connection.queryOne(
            "SELECT expire_timer, view_once, deleted_at FROM messages WHERE conversation_id = ? AND message_id = ?",
            conversationId.raw,
            target,
        ) ?: return false
        return (row.long("expire_timer") ?: 0L) == 0L && !row.bool("view_once") && row.long("deleted_at") == null
    }

    /** Clears `Quote.preview_text` in every reply to [targets] (deleted, expired or consumed), so their text does not live on. */
    internal fun scrubQuotePreviews(targets: List<ByteString>, conversationId: ConversationId) {
        var key: ByteArray? = null
        for (target in targets) {
            val rows = tx.connection.query(
                """
                SELECT id, message_id, body FROM messages
                WHERE conversation_id = ? AND quote_target = ? AND body IS NOT NULL
                """.trimIndent(),
                conversationId.raw,
                target,
            )
            for (row in rows) {
                val localId = row.long("id") ?: continue
                val messageId = row.bytes("message_id") ?: continue
                val body = row.blob("body") ?: continue
                val conversationKey = key ?: tx.conversationKey(conversationId)
                key = conversationKey
                val data = try {
                    DataMessage.parseFrom(tx.open(body, messageId, conversationKey))
                } catch (_: InvalidProtocolBufferException) {
                    continue
                }
                if (!data.hasQuote() || data.quote.previewText.isEmpty()) continue
                val scrubbed = data.toBuilder().setQuote(data.quote.toBuilder().setPreviewText("")).build()
                val sealed = tx.seal(scrubbed.toByteArray(), messageId, conversationKey)
                tx.connection.run("UPDATE messages SET body = ? WHERE id = ?", sealed, localId)
            }
        }
    }

    // MARK: Live location (§8.3)

    /**
     * Our live-location bubbles in every conversation that are neither deleted nor past their
     * deadline, oldest first. Only rows that carry a session are read (the
     * `messages_by_live_session` index); a row that cannot be read is skipped instead of hiding the others.
     */
    fun outgoingLiveLocations(now: Instant = Instant.now()): List<StoredMessage> {
        val rows = tx.connection.query(
            """
            SELECT $COLUMNS FROM messages
            WHERE live_session IS NOT NULL AND outgoing = 1 AND deleted_at IS NULL
                AND (expires_at IS NULL OR expires_at > ?)
            ORDER BY id
            """.trimIndent(),
            now.toEpochMilli(),
        )
        val keys = HashMap<ConversationId, ByteArray>()
        return rows.mapNotNull { row ->
            val conversationId = ConversationId.of(row.bytes("conversation_id")) ?: return@mapNotNull null
            try {
                val key = keys[conversationId] ?: tx.conversationKey(conversationId)
                keys[conversationId] = key
                message(row, key)
            } catch (_: StorageException) {
                null
            }
        }
    }

    private fun liveLocationBubble(session: ByteString, conversationId: ConversationId): StoredMessage? {
        if (session.size() != RandomIds.LENGTH) return null
        val row = tx.connection.queryOne(
            "SELECT $COLUMNS FROM messages WHERE conversation_id = ? AND live_session = ? ORDER BY id LIMIT 1",
            conversationId.raw,
            session,
        ) ?: return null
        return message(row, tx.conversationKey(conversationId))
    }

    private fun updateLiveLocation(
        existing: StoredMessage,
        data: DataMessage,
        live: LiveLocation,
        sender: AccountId,
        conversation: Conversation,
        time: Instant,
    ): DataMessageOutcome {
        val current = existing.content?.takeIf { it.hasLiveLocation() }?.liveLocation
        if (existing.sender != sender || existing.isDeleted || current == null || current.stopped ||
            !(live.sequence.unsigned() > current.sequence.unsigned() || live.stopped)
        ) {
            return DataMessageOutcome.Ignored
        }
        // Keep the bubble's own message id (the item id) and replace its payload.
        val updated = (existing.content ?: data).toBuilder().setLiveLocation(live).build()
        val key = tx.conversationKey(conversation.slotId)
        val body = tx.seal(updated.toByteArray(), existing.messageId, key)
        // With a disappearing timer the deadline follows the end of sharing.
        var expiresAt = existing.expiresAt?.toEpochMilli()
        val start = if (existing.isOutgoing) existing.createdAt else existing.timestamps.displayedAt
        if (existing.expireTimerSeconds > 0 && start != null) {
            expiresAt = liveLocationDeadline(
                start.toEpochMilli(),
                existing.expireTimerSeconds,
                live,
                existing.createdAt.toEpochMilli(),
                time.toEpochMilli(),
            )
        }
        tx.connection.run("UPDATE messages SET body = ?, expires_at = ? WHERE id = ?", body, expiresAt, existing.localId)
        // Earlier positions must not survive as older page images in the WAL.
        tx.checkpointAfterCommit()
        val stored = get(existing.messageId, conversation.id, now = time) ?: storageFailure(StorageError.NotFound)
        return DataMessageOutcome.LiveLocationUpdated(stored)
    }

    // MARK: Reading

    fun get(
        messageId: ByteString,
        conversationId: ConversationId,
        now: Instant = Instant.now(),
        includeExpired: Boolean = false,
    ): StoredMessage? {
        val row = tx.connection.queryOne(
            "SELECT $COLUMNS FROM messages WHERE conversation_id = ? AND message_id = ?",
            conversationId.raw,
            messageId,
        ) ?: return null
        val expiresAt = row.long("expires_at")
        if (!includeExpired && expiresAt != null && expiresAt <= now.toEpochMilli()) return null
        return message(row, tx.conversationKey(conversationId))
    }

    /**
     * Up to [limit] messages before [before] (a [StoredMessage.localId]; `null` = the latest), in
     * chronological order. Expired messages are never returned (§9: never displayed after the deadline).
     */
    fun page(conversationId: ConversationId, before: Long? = null, limit: Int = 50, now: Instant = Instant.now(), through: Long? = null): List<StoredMessage> {
        val key = tx.conversationKey(conversationId)
        val rows = tx.connection.query(
            """
            SELECT $COLUMNS FROM messages
            WHERE conversation_id = ? AND id < ? AND id >= ? AND (expires_at IS NULL OR expires_at > ?)
            ORDER BY id DESC LIMIT ?
            """.trimIndent(),
            conversationId.raw,
            before ?: Long.MAX_VALUE,
            through ?: 0L,
            now.toEpochMilli(),
            limit,
        )
        return rows.asReversed().map { message(it, key) }
    }

    /** The newest visible message (conversation list preview). */
    fun latest(conversationId: ConversationId, now: Instant = Instant.now()): StoredMessage? =
        page(conversationId, limit = 1, now = now).lastOrNull()

    /**
     * The conversation's photos and videos, or its files: up to [limit] attachment messages before
     * [before] whose `Attachment.kind` is in [kinds], **newest first**. Never view-once, deleted or
     * expired items, and no disappearing item at all (§8.1). Only attachment rows are read
     * (`messages_attachments`), in batches.
     */
    fun mediaPage(
        conversationId: ConversationId,
        kinds: Set<Attachment.Kind>,
        before: Long? = null,
        limit: Int = 50,
        now: Instant = Instant.now(),
    ): List<StoredMessage> {
        if (limit <= 0 || kinds.isEmpty()) return emptyList()
        val key = tx.conversationKey(conversationId)
        val batch = max(limit, 64)
        var cursor = before ?: Long.MAX_VALUE
        val result = ArrayList<StoredMessage>()
        while (result.size < limit) {
            val rows = tx.connection.query(
                """
                SELECT $COLUMNS FROM messages
                WHERE conversation_id = ? AND kind = ${MessageKind.ATTACHMENT.raw} AND id < ?
                    AND view_once = 0 AND deleted_at IS NULL AND body IS NOT NULL
                    AND expire_timer = 0 AND (expires_at IS NULL OR expires_at > ?)
                ORDER BY id DESC LIMIT ?
                """.trimIndent(),
                conversationId.raw,
                cursor,
                now.toEpochMilli(),
                batch,
            )
            for (row in rows) {
                val stored = message(row, key)
                val content = stored.content ?: continue
                if (!content.hasAttachment() || content.attachment.kind !in kinds) continue
                result += stored
                if (result.size == limit) break
            }
            val last = rows.lastOrNull()?.long("id")
            if (rows.size != batch || last == null) break
            cursor = last
        }
        return result
    }

    /** The local row id of a message, if it is still here; nothing is decrypted. */
    fun localId(messageId: ByteString, conversationId: ConversationId): Long? = tx.connection.queryOne(
        "SELECT id FROM messages WHERE conversation_id = ? AND message_id = ?",
        conversationId.raw,
        messageId,
    )?.long("id")

    fun count(conversationId: ConversationId): Int =
        ((tx.connection.scalar("SELECT count(*) AS n FROM messages WHERE conversation_id = ?", conversationId.raw) as? Long) ?: 0L).toInt()

    /** The message a quote points to, if it is still here. */
    fun quoted(message: StoredMessage, now: Instant = Instant.now()): StoredMessage? {
        val target = message.quoteTarget ?: return null
        return get(target, message.conversationId, now)
    }

    // MARK: Status (§5.4, §8.4)

    /** The relay answered 202: Sent. */
    fun markAccepted(messageId: ByteString, conversationId: ConversationId, time: Instant = Instant.now()) {
        tx.requireWritable()
        tx.connection.run(
            """
            UPDATE messages SET status = CASE WHEN status = ? THEN ? ELSE status END,
                accepted_at = coalesce(accepted_at, ?)
            WHERE conversation_id = ? AND message_id = ? AND outgoing = 1
            """.trimIndent(),
            MessageStatus.SENDING.raw,
            MessageStatus.SENT.raw,
            time.toEpochMilli(),
            conversationId.raw,
            messageId,
        )
    }

    /** The relay refused it for good: Failed (not retried). */
    fun markFailed(messageId: ByteString, conversationId: ConversationId, time: Instant = Instant.now()) {
        tx.requireWritable()
        tx.connection.run(
            """
            UPDATE messages SET status = ?, failed_at = ?
            WHERE conversation_id = ? AND message_id = ? AND outgoing = 1 AND status = ?
            """.trimIndent(),
            MessageStatus.FAILED.raw,
            time.toEpochMilli(),
            conversationId.raw,
            messageId,
            MessageStatus.SENDING.raw,
        )
    }

    /**
     * Applies a `ReceiptMessage` from the conversation's contact to our outgoing messages. Status only
     * moves forward (a late delivery receipt also lifts Not delivered); VIEWED and SAVED are set
     * once. Returns the message ids that changed.
     */
    fun applyReceipt(receipt: ReceiptMessage, conversationId: ConversationId, time: Instant = Instant.now()): List<ByteString> {
        tx.requireWritable()
        val now = time.toEpochMilli()
        val upgradable = listOf(MessageStatus.SENDING, MessageStatus.SENT, MessageStatus.NOT_DELIVERED).map { it.raw }
        val changed = ArrayList<ByteString>()
        for (messageId in receipt.messageIdsList.take(StorageRules.MAX_RECEIPT_IDS)) {
            if (messageId.size() != RandomIds.LENGTH) continue
            val changes = when (receipt.type) {
                ReceiptMessage.Type.DELIVERY -> tx.connection.run(
                    """
                    UPDATE messages SET status = ?, delivered_at = coalesce(delivered_at, ?)
                    WHERE conversation_id = ? AND message_id = ? AND outgoing = 1 AND status IN (?, ?, ?)
                    """.trimIndent(),
                    MessageStatus.DELIVERED.raw,
                    now,
                    conversationId.raw,
                    messageId,
                    *upgradable.toTypedArray(),
                )
                ReceiptMessage.Type.READ -> tx.connection.run(
                    """
                    UPDATE messages SET status = ?, read_at = coalesce(read_at, ?),
                        delivered_at = coalesce(delivered_at, ?)
                    WHERE conversation_id = ? AND message_id = ? AND outgoing = 1 AND status IN (?, ?, ?, ?)
                    """.trimIndent(),
                    MessageStatus.READ.raw,
                    now,
                    now,
                    conversationId.raw,
                    messageId,
                    *(upgradable + MessageStatus.DELIVERED.raw).toTypedArray(),
                )
                ReceiptMessage.Type.VIEWED -> tx.connection.run(
                    """
                    UPDATE messages SET viewed_at = coalesce(viewed_at, ?)
                    WHERE conversation_id = ? AND message_id = ? AND outgoing = 1 AND viewed_at IS NULL
                    """.trimIndent(),
                    now,
                    conversationId.raw,
                    messageId,
                )
                ReceiptMessage.Type.SAVED -> {
                    // Only for our own photo, video or file to this contact that can be saved at
                    // all (§8.4); ignored for anything else.
                    val message = try {
                        get(messageId, conversationId, time, includeExpired = true)
                    } catch (_: StorageException) {
                        null
                    }
                    val content = message?.content
                    if (message == null || !message.isOutgoing || message.isDeleted || message.isViewOnce ||
                        message.expireTimerSeconds != 0L || content == null || !SavedMedia.isSavable(content)
                    ) {
                        continue
                    }
                    tx.connection.run(
                        "UPDATE messages SET saved_by_peer_at = ? WHERE id = ? AND saved_by_peer_at IS NULL",
                        now,
                        message.localId,
                    )
                }
                else -> continue
            }
            if (changes > 0) changed += messageId
        }
        return changed
    }

    /**
     * Incoming messages were displayed: sets `displayedAt` once and starts their disappearing timers
     * (§8.1). Returns the ids displayed for the first time (the candidates for an optional read receipt).
     */
    fun markDisplayed(messageIds: List<ByteString>, conversationId: ConversationId, time: Instant = Instant.now()): List<ByteString> {
        tx.requireWritable()
        val now = time.toEpochMilli()
        val displayed = ArrayList<ByteString>()
        for (messageId in messageIds) {
            val changes = tx.connection.run(
                """
                UPDATE messages SET displayed_at = ?,
                    expires_at = CASE WHEN expire_timer > 0 THEN ? + expire_timer * 1000 ELSE expires_at END
                WHERE conversation_id = ? AND message_id = ? AND outgoing = 0 AND displayed_at IS NULL
                """.trimIndent(),
                now,
                now,
                conversationId.raw,
                messageId,
            )
            if (changes > 0) displayed += messageId
        }
        // A displayed live-location bubble keeps showing while sharing runs.
        for (messageId in displayed) {
            val row = tx.connection.queryOne(
                "SELECT kind, expire_timer FROM messages WHERE conversation_id = ? AND message_id = ?",
                conversationId.raw,
                messageId,
            ) ?: continue
            if (row.long("kind") != MessageKind.LIVE_LOCATION.raw.toLong() || (row.long("expire_timer") ?: 0L) <= 0L) continue
            val message = get(messageId, conversationId, includeExpired = true) ?: continue
            val live = message.content?.takeIf { it.hasLiveLocation() }?.liveLocation ?: continue
            if (message.kind != MessageKind.LIVE_LOCATION || message.expireTimerSeconds <= 0) continue
            val deadline = liveLocationDeadline(now, message.expireTimerSeconds, live, message.createdAt.toEpochMilli(), now)
            tx.connection.run("UPDATE messages SET expires_at = ? WHERE id = ?", deadline, message.localId)
        }
        return displayed
    }

    /** An incoming voice note was played on this phone: `viewedAt` is set once. Returns whether it changed (the first play). */
    fun markPlayed(messageId: ByteString, conversationId: ConversationId, time: Instant = Instant.now()): Boolean {
        tx.requireWritable()
        return tx.connection.run(
            """
            UPDATE messages SET viewed_at = ?
            WHERE conversation_id = ? AND message_id = ? AND outgoing = 0 AND viewed_at IS NULL AND deleted_at IS NULL
            """.trimIndent(),
            time.toEpochMilli(),
            conversationId.raw,
            messageId,
        ) > 0
    }

    /**
     * A photo, video or file was saved outside the app on this phone: `savedByMeAt` is set once.
     * Only a message that can be saved at all (§8.4: not view-once, no disappearing timer, not
     * deleted) — anything else is refused. Returns whether it changed (the first save).
     */
    fun markSavedByMe(messageId: ByteString, conversationId: ConversationId, time: Instant = Instant.now()): Boolean {
        tx.requireWritable()
        val message = get(messageId, conversationId, time)
        val content = message?.content
        if (message == null || message.isDeleted || message.isViewOnce || message.expireTimerSeconds != 0L || content == null ||
            !SavedMedia.isSavable(content)
        ) {
            storageFailure(StorageError.InvalidState("not a savable message"))
        }
        return tx.connection.run(
            "UPDATE messages SET saved_by_me_at = ? WHERE id = ? AND saved_by_me_at IS NULL",
            time.toEpochMilli(),
            message.localId,
        ) > 0
    }

    /** Displays every incoming message of the conversation (opening it). */
    fun markAllDisplayed(conversationId: ConversationId, time: Instant = Instant.now()): List<ByteString> {
        val ids = tx.connection.query(
            """
            SELECT message_id FROM messages
            WHERE conversation_id = ? AND outgoing = 0 AND displayed_at IS NULL AND deleted_at IS NULL
            """.trimIndent(),
            conversationId.raw,
        ).mapNotNull { it.bytes("message_id") }
        return markDisplayed(ids, conversationId, time)
    }

    /** A view-once message was opened: its attachment files and body are destroyed; the row stays as "viewed". */
    fun consumeViewOnce(messageId: ByteString, conversationId: ConversationId, time: Instant = Instant.now()) {
        tx.requireWritable()
        val message = get(messageId, conversationId, includeExpired = true)
        if (message == null || !message.isViewOnce) storageFailure(StorageError.InvalidState("not a view-once message"))
        tx.attachments.deleteAll(listOf(messageId), conversationId)
        tx.connection.run(
            """
            UPDATE messages SET body = NULL, viewed_at = coalesce(viewed_at, ?), displayed_at = coalesce(displayed_at, ?)
            WHERE conversation_id = ? AND message_id = ?
            """.trimIndent(),
            time.toEpochMilli(),
            time.toEpochMilli(),
            conversationId.raw,
            messageId,
        )
        scrubQuotePreviews(listOf(messageId), conversationId)
        // The descriptor (media key, caption) must not survive in the WAL.
        tx.checkpointAfterCommit()
    }

    /** Sent messages without a delivery receipt for 14 days become Not delivered (§5.4). Returns how many changed. */
    fun markUndelivered(now: Instant = Instant.now()): Int {
        tx.requireWritable()
        val cutoff = now.toEpochMilli() - UNDELIVERED_AFTER.inWholeMilliseconds
        return tx.connection.run(
            """
            UPDATE messages SET status = ?, not_delivered_at = ?
            WHERE outgoing = 1 AND status = ? AND accepted_at <= ?
            """.trimIndent(),
            MessageStatus.NOT_DELIVERED.raw,
            now.toEpochMilli(),
            MessageStatus.SENT.raw,
            cutoff,
        )
    }

    /** The earliest disappearing deadline still ahead (for an in-app timer). */
    fun nextExpiry(): Instant? =
        tx.connection.queryOne("SELECT min(expires_at) AS t FROM messages WHERE expires_at IS NOT NULL")?.instant("t")

    // MARK: Rows

    internal fun message(row: Row, key: ByteArray): StoredMessage {
        val localId = row.long("id") ?: storageFailure(StorageError.Integrity)
        val conversationId = ConversationId.of(row.bytes("conversation_id")) ?: storageFailure(StorageError.Integrity)
        val messageId = row.bytes("message_id") ?: storageFailure(StorageError.Integrity)
        val sender = accountIdOf(row.blob("sender")) ?: storageFailure(StorageError.Integrity)
        val status = MessageStatus.of(row.long("status")) ?: storageFailure(StorageError.Integrity)
        val content = row.blob("body")?.let { body ->
            try {
                DataMessage.parseFrom(tx.open(body, messageId, key))
            } catch (e: InvalidProtocolBufferException) {
                storageFailure(StorageError.Integrity, e)
            }
        }
        return StoredMessage(
            localId = localId,
            conversationId = conversationId,
            messageId = messageId,
            isOutgoing = row.bool("outgoing"),
            sender = sender,
            kind = MessageKind.of(row.long("kind")),
            content = content,
            sentAt = row.instant("sent_at") ?: Instant.EPOCH,
            createdAt = row.instant("created_at") ?: Instant.EPOCH,
            serverTimestamp = row.instant("server_timestamp"),
            status = status,
            timestamps = MessageTimestamps(
                acceptedAt = row.instant("accepted_at"),
                deliveredAt = row.instant("delivered_at"),
                readAt = row.instant("read_at"),
                viewedAt = row.instant("viewed_at"),
                notDeliveredAt = row.instant("not_delivered_at"),
                failedAt = row.instant("failed_at"),
                displayedAt = row.instant("displayed_at"),
                savedByPeerAt = row.instant("saved_by_peer_at"),
                savedByMeAt = row.instant("saved_by_me_at"),
            ),
            expireTimerSeconds = (row.long("expire_timer") ?: 0L).coerceIn(0L, 0xFFFF_FFFFL),
            expiresAt = row.instant("expires_at"),
            isViewOnce = row.bool("view_once"),
            quoteTarget = row.bytes("quote_target"),
            liveSessionId = row.bytes("live_session"),
            deletedAt = row.instant("deleted_at"),
        )
    }

    companion object {
        internal const val COLUMNS =
            "id, conversation_id, message_id, outgoing, sender, kind, body, sent_at, created_at, server_timestamp, " +
                "status, accepted_at, delivered_at, read_at, viewed_at, not_delivered_at, failed_at, displayed_at, " +
                "expire_timer, expires_at, view_once, quote_target, live_session, deleted_at, saved_by_peer_at, saved_by_me_at"

        /** After this long without a delivery receipt a sent message is Not delivered (§5.4). */
        val UNDELIVERED_AFTER = 14.days

        /**
         * How long removed ids are remembered: a sender retries for up to 14 days
         * ([RetryPolicy.maximumAge]) and the relay keeps a retransmission up to 14 more.
         */
        val REMOVED_ID_RETENTION = 30.days

        /** An incoming `expires_at` is a sender claim; sharing lasts at most 8 h (plus an hour for clock skew). */
        internal const val MAX_LIVE_SHARING_MILLISECONDS: Long = 9L * 3600 * 1000

        /**
         * A live-location bubble with timer T disappears like any message, T after it was sent (ours)
         * or first displayed (theirs) — but never while the sharing it shows is still running:
         * `max(start + T, end)`, where `end` is the stop (now) or the session's `expires_at`.
         */
        internal fun liveLocationDeadline(start: Long, timer: Long, live: LiveLocation, createdAt: Long, now: Long): Long {
            val byTimer = start + timer * 1000
            val end = if (live.stopped) now else min(live.expiresAt.clampedUnsigned(), createdAt + MAX_LIVE_SHARING_MILLISECONDS)
            return max(byTimer, end)
        }

        /** The attachments a `DataMessage` points to (the attachment payload or a custom sticker). */
        internal fun attachments(data: DataMessage): List<Attachment> = when {
            data.hasAttachment() -> listOf(data.attachment)
            data.hasSticker() && data.sticker.hasCustom() -> listOf(data.sticker.custom)
            else -> emptyList()
        }
    }
}
