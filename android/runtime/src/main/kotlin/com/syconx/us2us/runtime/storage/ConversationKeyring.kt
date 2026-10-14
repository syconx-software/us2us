package com.syconx.us2us.runtime.storage

import com.syconx.us2us.core.vault.DataKey
import com.syconx.us2us.core.vault.InvalidVaultFileException
import com.syconx.us2us.runtime.vault.KeySlotException
import com.syconx.us2us.runtime.vault.KeySlotStore
import com.syconx.us2us.runtime.vault.SlotId
import java.io.File
import java.time.Instant

/**
 * The conversation keys `CK` of PROTOCOL.md §5.3, one per conversation, each in its own key-slot
 * file ([KeySlotStore], the BRPS format). Opened keys are cached in memory while storage is
 * unlocked and dropped on [close].
 *
 * Destroying a slot is the crypto-shred: once the file is gone, every item encrypted under its
 * `CK` (bodies, attachments, thumbnails, drafts, pending sends) is unreadable, whether or not the
 * rows and files still exist. A destroyed slot id is also remembered in memory, so a key being
 * loaded concurrently can never be cached (or returned) after the shred.
 */
internal class ConversationKeyring(layout: StorageLayout, dek: DataKey) {
    val slots = KeySlotStore(layout.slotDirectory)
    private val directory = layout.slotDirectory
    private val scheduleFile = layout.scheduleFile
    private val lock = Any()
    private var dek: DataKey? = DataKey(dek.toBytes())
    private val cache = HashMap<SlotId, ByteArray>()
    private val shredded = HashSet<SlotId>()

    private fun currentDek(): DataKey = synchronized(lock) { dek } ?: storageFailure(StorageError.Closed)

    fun slotFile(slotId: SlotId): File = File(directory, "${slotId.hex}.slot")

    fun slotExists(slotId: SlotId): Boolean = slotFile(slotId).exists()

    /** Creates a slot with a fresh random `CK` (written atomically). */
    fun createSlot(): Pair<SlotId, ByteArray> {
        val dek = currentDek()
        val created = try {
            slots.createSlot(dek)
        } catch (e: KeySlotException) {
            storageFailure(StorageError.Io("cannot create key slot"), e)
        }
        synchronized(lock) { cache[created.first] = created.second }
        return created
    }

    /** `CK` of a conversation, or [StorageError.ConversationShredded] if its slot is gone. */
    fun key(slotId: SlotId): ByteArray {
        synchronized(lock) {
            if (slotId in shredded) storageFailure(StorageError.ConversationShredded)
            cache[slotId]?.let { return it }
        }
        val dek = currentDek()
        if (!slotExists(slotId)) storageFailure(StorageError.ConversationShredded)
        val key = try {
            slots.conversationKey(slotId, dek)
        } catch (e: KeySlotException) {
            when {
                e.cause is InvalidVaultFileException -> storageFailure(StorageError.Integrity, e)
                slotExists(slotId) -> storageFailure(StorageError.Io("slot unreadable"), e)
                else -> storageFailure(StorageError.ConversationShredded, e)
            }
        }
        synchronized(lock) {
            if (slotId in shredded) {
                key.fill(0)
                storageFailure(StorageError.ConversationShredded)
            }
            cache[slotId] = key
        }
        return key
    }

    /** Crypto-shreds one conversation (deletes its slot and schedule entry). */
    fun destroy(slotId: SlotId) {
        markShredded(listOf(slotId))
        try {
            slots.destroySlot(slotId)
        } catch (_: KeySlotException) {
            // The schedule could not be rewritten; the slot itself must be gone.
            removeIfPresent(slotFile(slotId))
            if (slotExists(slotId)) storageFailure(StorageError.Io("cannot destroy key slot"))
            repairScheduleIfUnreadable()
        }
    }

    /** Forgets cached keys of slots destroyed elsewhere (the locked-path wipe). */
    fun markShredded(slotIds: Collection<SlotId>) {
        synchronized(lock) {
            for (slotId in slotIds) {
                shredded += slotId
                cache.remove(slotId)?.fill(0)
            }
        }
    }

    fun slotIds(): Set<SlotId> = slots.slotIds().toSet()

    fun setDeadline(deadline: Instant?, slotId: SlotId) {
        try {
            slots.setDeadline(deadline, slotId)
        } catch (_: KeySlotException) {
            // A damaged schedule must not block ghost mode forever: start a fresh one (the
            // unlocked app re-adds every deadline from the database).
            repairScheduleIfUnreadable()
            try {
                slots.setDeadline(deadline, slotId)
            } catch (e: KeySlotException) {
                storageFailure(StorageError.Io("cannot write ghost schedule"), e)
            }
        }
    }

    fun deadlines(): Map<SlotId, Instant> = try {
        slots.deadlines()
    } catch (_: KeySlotException) {
        emptyMap()
    }

    /** Destroys every slot whose ghost deadline passed; no DEK needed. */
    fun destroyExpiredSlots(now: Instant): List<SlotId> {
        val expired = try {
            slots.destroyExpiredSlots(now)
        } catch (e: KeySlotException) {
            storageFailure(StorageError.Io("ghost schedule unreadable"), e)
        }
        markShredded(expired)
        return expired
    }

    private fun repairScheduleIfUnreadable() {
        try {
            slots.deadlines()
        } catch (_: KeySlotException) {
            try {
                removeIfPresent(scheduleFile)
            } catch (_: StorageException) {
            }
        }
    }

    /** Drops the DEK and every cached `CK`. */
    fun close() {
        synchronized(lock) {
            dek?.wipe()
            dek = null
            for (key in cache.values) key.fill(0)
            cache.clear()
        }
    }
}
