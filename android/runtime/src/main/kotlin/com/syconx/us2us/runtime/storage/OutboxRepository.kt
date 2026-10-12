package com.syconx.us2us.runtime.storage

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.proto.Content
import java.time.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaDuration

/**
 * Pending sends with retry (§5.4: messages wait for the relay, not the other way round). Content
 * bound to a conversation is an item under its `CK`, so a crypto-shredded conversation leaves no
 * readable pending text; conversation-less sends (receipts, `ConversationWipe` after a wipe,
 * profile and access-key updates) are protobufs inside the database.
 *
 * Typical loop: [due] → send → [markSent] (which also marks the message Sent) or [markFailed].
 */
class OutboxRepository internal constructor(private val tx: StorageTransaction) {
    fun enqueue(
        content: Content,
        recipient: AccountId,
        conversationId: ConversationId? = null,
        messageId: ByteString? = null,
        ephemeral: Boolean = false,
        waitingForUpload: Boolean = false,
        now: Instant = Instant.now(),
    ): Long {
        tx.requireWritable()
        val payloadId = RandomIds.make()
        var payload = content.toByteArray()
        if (conversationId != null) payload = tx.seal(payload, payloadId, tx.conversationKey(conversationId))
        return tx.connection.insert(
            """
            INSERT INTO outbox (recipient, conversation_id, message_id, payload, payload_id, ephemeral, state,
                next_attempt_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            recipient.toBytes(),
            conversationId?.raw,
            messageId,
            payload,
            payloadId,
            ephemeral,
            (if (waitingForUpload) OutboxState.WAITING_FOR_UPLOAD else OutboxState.READY).raw,
            now.toEpochMilli(),
            now.toEpochMilli(),
        )
    }

    fun get(id: Long): OutboxEntry? = tx.connection.queryOne("SELECT $COLUMNS FROM outbox WHERE id = ?", id)?.let(::entry)

    /** Ready entries whose retry time has come, oldest first (per-recipient order is kept). */
    fun due(now: Instant = Instant.now(), limit: Int = 50): List<OutboxEntry> = tx.connection.query(
        "SELECT $COLUMNS FROM outbox WHERE state = ? AND next_attempt_at <= ? ORDER BY id LIMIT ?",
        OutboxState.READY.raw,
        now.toEpochMilli(),
        limit,
    ).map(::entry)

    fun all(state: OutboxState? = null): List<OutboxEntry> = if (state != null) {
        tx.connection.query("SELECT $COLUMNS FROM outbox WHERE state = ? ORDER BY id", state.raw).map(::entry)
    } else {
        tx.connection.query("SELECT $COLUMNS FROM outbox ORDER BY id").map(::entry)
    }

    /** The queued sends of one message (normally one; none once the relay accepted it). */
    fun entries(messageId: ByteString, conversationId: ConversationId): List<OutboxEntry> = tx.connection.query(
        "SELECT $COLUMNS FROM outbox WHERE conversation_id = ? AND message_id = ? ORDER BY id",
        conversationId.raw,
        messageId,
    ).map(::entry)

    /** When the next ready entry is due (for a retry timer). */
    fun nextAttemptDate(): Instant? =
        tx.connection.queryOne("SELECT min(next_attempt_at) AS t FROM outbox WHERE state = ?", OutboxState.READY.raw)?.instant("t")

    /** The attachment upload finished: the entry may be sent. */
    fun markReady(id: Long, content: Content? = null, now: Instant = Instant.now()) {
        tx.requireWritable()
        if (content != null) {
            val existing = get(id) ?: storageFailure(StorageError.NotFound)
            var payload = content.toByteArray()
            existing.conversationId?.let { conversationId ->
                payload = tx.seal(payload, payloadId(id), tx.conversationKey(conversationId))
            }
            tx.connection.run("UPDATE outbox SET payload = ? WHERE id = ?", payload, id)
        }
        tx.connection.run(
            "UPDATE outbox SET state = ?, next_attempt_at = ? WHERE id = ?",
            OutboxState.READY.raw,
            now.toEpochMilli(),
            id,
        )
    }

    /**
     * The relay accepted the envelope: the entry is removed and its message becomes Sent. A contact
     * waiting to be forgotten (ghost `wipe_contact`) is forgotten once nothing is left to send to it.
     */
    fun markSent(id: Long, now: Instant = Instant.now()) {
        tx.requireWritable()
        val entry = get(id) ?: return
        tx.connection.run("DELETE FROM outbox WHERE id = ?", id)
        if (entry.messageId != null && entry.conversationId != null) {
            tx.messages.markAccepted(entry.messageId, entry.conversationId, now)
        }
        forgetIfPending(entry.recipient)
    }

    /**
     * Records a failed attempt. Retryable failures back off per [policy]; permanent ones (or entries
     * older than [RetryPolicy.maximumAge]) become failed and mark their message Failed. [error] is a
     * short code, never content.
     */
    fun markFailed(id: Long, error: String, retryable: Boolean, policy: RetryPolicy = RetryPolicy.STANDARD, now: Instant = Instant.now()) {
        tx.requireWritable()
        val entry = get(id) ?: return
        val attempts = entry.attempts + 1
        val tooOld = !now.isBefore(entry.createdAt.plus(policy.maximumAge.toJavaDuration()))
        val code = error.take(64)
        if (retryable && !tooOld) {
            val next = now.plusMillis(policy.delay(attempts).inWholeMilliseconds)
            tx.connection.run(
                "UPDATE outbox SET attempts = ?, next_attempt_at = ?, last_error = ? WHERE id = ?",
                attempts,
                next.toEpochMilli(),
                code,
                id,
            )
            return
        }
        tx.connection.run(
            "UPDATE outbox SET attempts = ?, state = ?, last_error = ? WHERE id = ?",
            attempts,
            OutboxState.FAILED.raw,
            code,
            id,
        )
        if (entry.messageId != null && entry.conversationId != null) {
            tx.messages.markFailed(entry.messageId, entry.conversationId, now)
        }
        forgetIfPending(entry.recipient)
    }

    /** Puts a failed entry back in the queue (the user tapped "retry"). */
    fun retry(id: Long, now: Instant = Instant.now()) {
        tx.requireWritable()
        tx.connection.run(
            "UPDATE outbox SET state = ?, next_attempt_at = ?, attempts = 0 WHERE id = ? AND state = ?",
            OutboxState.READY.raw,
            now.toEpochMilli(),
            id,
            OutboxState.FAILED.raw,
        )
        val entry = get(id) ?: return
        val messageId = entry.messageId ?: return
        val conversationId = entry.conversationId ?: return
        tx.connection.run(
            """
            UPDATE messages SET status = ?, failed_at = NULL
            WHERE conversation_id = ? AND message_id = ? AND status = ?
            """.trimIndent(),
            MessageStatus.SENDING.raw,
            conversationId.raw,
            messageId,
            MessageStatus.FAILED.raw,
        )
    }

    fun remove(id: Long) {
        tx.requireWritable()
        val entry = get(id) ?: return
        tx.connection.run("DELETE FROM outbox WHERE id = ?", id)
        forgetIfPending(entry.recipient)
    }

    private fun forgetIfPending(recipient: AccountId) {
        val contact = tx.contacts.get(recipient) ?: return
        if (!contact.pendingForget) return
        val remaining = tx.connection.queryOne(
            "SELECT 1 FROM outbox WHERE recipient = ? AND state != ? LIMIT 1",
            recipient.toBytes(),
            OutboxState.FAILED.raw,
        )
        if (remaining == null) tx.contacts.forget(recipient)
    }

    private fun payloadId(id: Long): ByteString =
        tx.connection.queryOne("SELECT payload_id FROM outbox WHERE id = ?", id)?.bytes("payload_id") ?: storageFailure(StorageError.NotFound)

    private fun entry(row: Row): OutboxEntry {
        val id = row.long("id") ?: storageFailure(StorageError.Integrity)
        val recipient = accountIdOf(row.blob("recipient")) ?: storageFailure(StorageError.Integrity)
        var payload = row.blob("payload") ?: storageFailure(StorageError.Integrity)
        val payloadId = row.bytes("payload_id") ?: storageFailure(StorageError.Integrity)
        val state = OutboxState.of(row.long("state")) ?: storageFailure(StorageError.Integrity)
        val conversationId = ConversationId.of(row.bytes("conversation_id"))
        if (conversationId != null) payload = tx.open(payload, payloadId, tx.conversationKey(conversationId))
        val content = try {
            Content.parseFrom(payload)
        } catch (e: InvalidProtocolBufferException) {
            storageFailure(StorageError.Integrity, e)
        }
        return OutboxEntry(
            id = id,
            recipient = recipient,
            conversationId = conversationId,
            messageId = row.bytes("message_id"),
            content = content,
            ephemeral = row.bool("ephemeral"),
            state = state,
            attempts = row.int("attempts") ?: 0,
            nextAttemptAt = row.instant("next_attempt_at") ?: Instant.EPOCH,
            createdAt = row.instant("created_at") ?: Instant.EPOCH,
            lastError = row.text("last_error"),
        )
    }

    private companion object {
        const val COLUMNS =
            "id, recipient, conversation_id, message_id, payload, payload_id, ephemeral, state, attempts, next_attempt_at, created_at, last_error"
    }
}

/**
 * Relay ids already stored or dropped, so a redelivered envelope (§4.7: the relay re-sends until
 * acknowledged) is acknowledged without being handled twice — also across app restarts, which the
 * core's in-memory set cannot cover. Record the id in the same transaction as the message.
 *
 * Privacy: only the UTC **day** of processing is kept, never the exact time. The log is not tied
 * to a conversation, so exact times would outlive a ghost wipe (§8.2) as a per-message receive
 * timeline; pruning needs no more than the day. An entry stays at least 14 days (the relay's
 * retention, after which no redelivery can happen) and at most 15.
 */
class EnvelopeLog internal constructor(private val tx: StorageTransaction) {
    fun contains(relayId: String): Boolean =
        tx.connection.queryOne("SELECT 1 FROM processed_envelopes WHERE relay_id = ?", relayId) != null

    fun record(relayId: String, time: Instant = Instant.now()) {
        tx.requireWritable()
        tx.connection.run("INSERT OR IGNORE INTO processed_envelopes (relay_id, processed_at) VALUES (?, ?)", relayId, day(time))
    }

    fun prune(now: Instant = Instant.now()): Int {
        tx.requireWritable()
        return tx.connection.run(
            "DELETE FROM processed_envelopes WHERE processed_at < ?",
            now.toEpochMilli() - RETENTION.inWholeMilliseconds,
        )
    }

    companion object {
        val RETENTION = 15.days
        private const val DAY_MILLISECONDS = 86_400_000L

        /** Midnight UTC of [time]'s day, in milliseconds. */
        internal fun day(time: Instant): Long {
            val milliseconds = time.toEpochMilli()
            return milliseconds - Math.floorMod(milliseconds, DAY_MILLISECONDS)
        }
    }
}
