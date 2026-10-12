package com.syconx.us2us.runtime.storage

import com.google.protobuf.InvalidProtocolBufferException
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.proto.ConversationSettings
import java.time.Instant

/**
 * Conversations (one per contact), their LWW settings and ghost-mode deadlines (PROTOCOL.md §8.2).
 *
 * **Ghost deadline**: with ghost mode on and timeout G, `deadline = max(lastActivity, ghostAnchor) + G`
 * exactly (milliseconds), where `lastActivity` is the local time of the latest sent or received
 * `DataMessage` (reactions and deletes count, live-location ticks do not) and `ghostAnchor` the
 * local time ghost mode was enabled or its timeout changed — so enabling it in an old conversation
 * starts a full timeout instead of wiping at once.
 *
 * **Schedule ordering**: the deadline is stored in the database and in the background-readable
 * schedule file. When it moves later the schedule is written *before* the transaction commits; when
 * it moves earlier, *after*. So whatever crashes or fails, the background wipe never runs earlier
 * than the database says, and the unlocked app — which wipes by the database — never wipes later.
 * [LocalStorage.open] rewrites the schedule from the database to repair any drift.
 */
class ConversationRepository internal constructor(private val tx: StorageTransaction) {
    fun get(id: ConversationId): Conversation? =
        tx.connection.queryOne("SELECT $COLUMNS FROM conversations WHERE id = ?", id.raw)?.let(::conversation)

    fun forContact(account: AccountId): Conversation? =
        tx.connection.queryOne("SELECT $COLUMNS FROM conversations WHERE contact = ?", account.toBytes())?.let(::conversation)

    /** All conversations of visible contacts, most recent activity first. */
    fun all(): List<Conversation> = tx.connection.query(
        """
        SELECT c.id, c.contact, c.slot_id, c.settings, c.settings_author, c.ghost_anchor_at,
               c.last_activity_at, c.ghost_deadline_at, c.created_at
        FROM conversations c JOIN contacts k ON k.account = c.contact
        WHERE k.pending_forget = 0
        ORDER BY coalesce(c.last_activity_at, c.created_at) DESC
        """.trimIndent(),
    ).map(::conversation)

    /** The contact's conversation, created with a fresh key slot if needed. The contact must exist. */
    fun ensure(contact: AccountId, now: Instant = Instant.now()): Conversation {
        tx.requireWritable()
        forContact(contact)?.let { return it }
        if (tx.contacts.requestState(contact) == null) storageFailure(StorageError.NotFound)
        val id = ConversationId.make()
        // The slot is written first; if the transaction rolls back, the slot is an orphan and
        // garbage-collected at the next open.
        val (slotId, _) = tx.storage.keyring.createSlot()
        tx.connection.run(
            """
            INSERT INTO conversations (id, contact, slot_id, settings, draft_item_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id.raw,
            contact.toBytes(),
            slotId.bytes(),
            ConversationSettings.getDefaultInstance().toByteArray(),
            RandomIds.make(),
            now.toEpochMilli(),
        )
        return get(id) ?: storageFailure(StorageError.NotFound)
    }

    // MARK: Settings (LWW)

    /**
     * Applies settings received from [author] (last writer wins, brp.proto). Returns the settings now
     * in effect. An `updated_at` more than a day ahead of [now] is clamped to that bound.
     */
    fun applySettings(
        incoming: ConversationSettings,
        author: AccountId,
        id: ConversationId,
        now: Instant = Instant.now(),
    ): ConversationSettings {
        tx.requireWritable()
        val ceiling = now.toEpochMilli() + StorageRules.MAX_CLOCK_SKEW.inWholeMilliseconds
        val clamped = if (java.lang.Long.compareUnsigned(incoming.updatedAt, ceiling) > 0) {
            incoming.toBuilder().setUpdatedAt(ceiling).build()
        } else {
            incoming
        }
        val conversation = get(id) ?: storageFailure(StorageError.NotFound)
        val current = conversation.settings
        val currentAuthor = conversation.settingsAuthor
        // Nobody wrote settings yet: the defaults lose to anything written.
        val resolved = if (currentAuthor != null) StorageRules.resolve(current, currentAuthor, clamped, author) else clamped
        if (resolved == current && currentAuthor != null) return current
        val winner = if (resolved == clamped) author else currentAuthor ?: author
        store(resolved, winner, conversation, now)
        return resolved
    }

    /**
     * Our own change: stamped `updated_at = now`, authored by us. Returns the `ConversationSettings`
     * to send to the contact.
     */
    fun updateSettings(
        id: ConversationId,
        now: Instant = Instant.now(),
        change: (ConversationSettings.Builder) -> Unit,
    ): ConversationSettings {
        tx.requireWritable()
        val conversation = get(id) ?: storageFailure(StorageError.NotFound)
        val builder = conversation.settings.toBuilder()
        change(builder)
        // Saturating: a stored stamp at the maximum must never wrap.
        val previous = conversation.settings.updatedAt
        val next = if (previous == -1L) previous else previous + 1
        val stamp = if (java.lang.Long.compareUnsigned(now.toEpochMilli(), next) > 0) now.toEpochMilli() else next
        val settings = builder.setUpdatedAt(stamp).build()
        if (settings.ghost.enabled && !StorageRules.isValidGhostTimeout(settings.ghost.timeoutSeconds)) {
            storageFailure(StorageError.InvalidState("ghost timeout out of range"))
        }
        store(settings, tx.ownAccountId(), conversation, now)
        return settings
    }

    private fun store(settings: ConversationSettings, author: AccountId, previous: Conversation, now: Instant) {
        val old = previous.settings.ghost
        val new = settings.ghost
        var anchor = previous.ghostAnchorAt
        if (new.enabled && (!old.enabled || old.timeoutSeconds != new.timeoutSeconds)) anchor = now
        if (!new.enabled) anchor = null
        tx.connection.run(
            "UPDATE conversations SET settings = ?, settings_author = ?, ghost_anchor_at = ? WHERE id = ?",
            settings.toByteArray(),
            author.toBytes(),
            anchor?.toEpochMilli(),
            previous.id.raw,
        )
        updateDeadline(previous, settings, previous.lastActivityAt, anchor)
    }

    // MARK: Activity and ghost deadlines

    /** Records a sent or received `DataMessage` at [time] (§8.2) and moves the ghost deadline. Live-location ticks must not be recorded. */
    fun recordActivity(id: ConversationId, time: Instant) {
        tx.requireWritable()
        val conversation = get(id) ?: storageFailure(StorageError.NotFound)
        val latest = maxOf(conversation.lastActivityAt ?: time, time)
        tx.connection.run("UPDATE conversations SET last_activity_at = ? WHERE id = ?", latest.toEpochMilli(), id.raw)
        updateDeadline(conversation, conversation.settings, latest, conversation.ghostAnchorAt)
    }

    private fun updateDeadline(conversation: Conversation, settings: ConversationSettings, lastActivity: Instant?, anchor: Instant?) {
        val deadline = ghostDeadline(settings, lastActivity, anchor)
        tx.connection.run(
            "UPDATE conversations SET ghost_deadline_at = ? WHERE id = ?",
            deadline?.toEpochMilli(),
            conversation.id.raw,
        )
        if (deadline == conversation.ghostDeadline) return
        val keyring = tx.storage.keyring
        val slotId = conversation.slotId
        if (isLater(deadline, conversation.ghostDeadline)) {
            keyring.setDeadline(deadline, slotId)
        } else {
            tx.afterCommit {
                try {
                    keyring.setDeadline(deadline, slotId)
                } catch (_: StorageException) {
                }
            }
        }
    }

    // MARK: Drafts

    /** The unsent draft, encrypted under `CK` like a message body (§8.2 wipes drafts with the conversation). */
    fun setDraft(text: String?, id: ConversationId) {
        tx.requireWritable()
        val row = tx.connection.queryOne("SELECT slot_id, draft_item_id FROM conversations WHERE id = ?", id.raw)
            ?: storageFailure(StorageError.NotFound)
        val slotId = row.blob("slot_id") ?: storageFailure(StorageError.NotFound)
        val itemId = row.bytes("draft_item_id") ?: storageFailure(StorageError.NotFound)
        val item = if (!text.isNullOrEmpty()) {
            tx.seal(text.toByteArray(Charsets.UTF_8), itemId, tx.conversationKey(slotIdOf(slotId)))
        } else {
            null
        }
        tx.connection.run("UPDATE conversations SET draft = ? WHERE id = ?", item, id.raw)
    }

    fun draft(id: ConversationId): String? {
        val row = tx.connection.queryOne("SELECT slot_id, draft, draft_item_id FROM conversations WHERE id = ?", id.raw)
            ?: storageFailure(StorageError.NotFound)
        val item = row.blob("draft") ?: return null
        val itemId = row.bytes("draft_item_id") ?: return null
        val slotId = row.blob("slot_id") ?: return null
        return String(tx.open(item, itemId, tx.conversationKey(slotIdOf(slotId))), Charsets.UTF_8)
    }

    // MARK: Counts

    /** Incoming messages not displayed yet. */
    fun unreadCount(id: ConversationId): Int = (
        tx.connection.scalar(
            """
            SELECT count(*) AS n FROM messages
            WHERE conversation_id = ? AND outgoing = 0 AND displayed_at IS NULL AND deleted_at IS NULL
            """.trimIndent(),
            id.raw,
        ) as? Long ?: 0L
        ).toInt()

    // MARK: Rows

    internal fun conversation(row: Row): Conversation {
        val id = ConversationId.of(row.bytes("id")) ?: storageFailure(StorageError.Integrity)
        val contact = accountIdOf(row.blob("contact")) ?: storageFailure(StorageError.Integrity)
        val slotId = slotIdOf(row.blob("slot_id") ?: storageFailure(StorageError.Integrity))
        val settings = try {
            ConversationSettings.parseFrom(row.blob("settings") ?: storageFailure(StorageError.Integrity))
        } catch (e: InvalidProtocolBufferException) {
            storageFailure(StorageError.Integrity, e)
        }
        return Conversation(
            id = id,
            contact = contact,
            slotId = slotId,
            settings = settings,
            settingsAuthor = accountIdOf(row.blob("settings_author")),
            lastActivityAt = row.instant("last_activity_at"),
            ghostAnchorAt = row.instant("ghost_anchor_at"),
            ghostDeadline = row.instant("ghost_deadline_at"),
            createdAt = row.instant("created_at") ?: Instant.EPOCH,
        )
    }

    companion object {
        internal const val COLUMNS =
            "id, contact, slot_id, settings, settings_author, ghost_anchor_at, last_activity_at, ghost_deadline_at, created_at"

        /** The exact ghost deadline, or `null` when ghost mode is off. */
        fun ghostDeadline(settings: ConversationSettings, lastActivity: Instant?, anchor: Instant?): Instant? {
            if (!settings.ghost.enabled || !StorageRules.isValidGhostTimeout(settings.ghost.timeoutSeconds)) return null
            val start = listOfNotNull(lastActivity, anchor).maxOfOrNull { it.toEpochMilli() } ?: return null
            return Instant.ofEpochMilli(start + settings.ghost.timeoutSeconds.unsigned() * 1000)
        }

        /** `null` counts as "never" (later than any date). */
        internal fun isLater(lhs: Instant?, rhs: Instant?): Boolean = when {
            lhs == null -> true
            rhs == null -> false
            else -> lhs >= rhs
        }
    }
}
