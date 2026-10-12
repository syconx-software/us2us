package com.syconx.us2us.runtime.vault

import com.syconx.us2us.core.encoding.Hex
import com.syconx.us2us.core.vault.DataKey
import com.syconx.us2us.core.vault.InvalidVaultFileException
import com.syconx.us2us.core.vault.KeySlot
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant

/** A conversation's key slot: 16 random bytes, the file name in lowercase hex (§5.3). */
@JvmInline
value class SlotId(val hex: String) {
    init {
        require(hex.length == KeySlot.SLOT_ID_LENGTH * 2 && hex.all { it in '0'..'9' || it in 'a'..'f' }) { "a slot id is 16 bytes in lowercase hex" }
    }

    fun bytes(): ByteArray = Hex.decode(hex)

    override fun toString(): String = hex

    companion object {
        fun of(bytes: ByteArray): SlotId = SlotId(Hex.encode(bytes))

        fun random(): SlotId = of(KeySlot.newSlotId())

        /** The id in a file or schedule name, or `null` when it is not one. */
        fun parse(text: String): SlotId? = try {
            SlotId(text)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** A key slot or the schedule could not be read or written. */
class KeySlotException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The directory of key-slot files plus the ghost-mode schedule (PROTOCOL.md §5.3, §8.2):
 * `<slotId hex>.slot` (the core's [KeySlot] format under `HKDF(DEK, "BRP/slot/v1")`) and
 * `schedule.json`, `[{"slotId": "<hex>", "deadline": <ms since 1970>}, …]` sorted by deadline.
 *
 * Destroying slots needs no DEK, so overdue wipes run even while the app is locked (from an alarm
 * or at the next launch): the schedule holds only slot ids and deadlines, no names or content.
 * Deleting a slot file destroys the conversation; its leftover ciphertext is garbage-collected at
 * the next unlock.
 */
class KeySlotStore(private val directory: File) {
    private val scheduleFile: File get() = File(directory, SCHEDULE_NAME)

    private fun file(slotId: SlotId) = File(directory, "${slotId.hex}$SLOT_SUFFIX")

    /** Creates a slot holding a fresh random conversation key; returns the slot id and the key. */
    @Synchronized
    fun createSlot(dek: DataKey): Pair<SlotId, ByteArray> {
        val slotId = SlotId.random()
        val conversationKey = KeySlot.newConversationKey()
        val slot = KeySlot.create(dek, slotId.bytes(), conversationKey)
        io("slot not written") { VaultFiles.write(file(slotId), slot) }
        return slotId to conversationKey
    }

    /**
     * The conversation key `CK` of [slotId].
     *
     * @throws KeySlotException when the slot is missing (destroyed) or does not open under [dek].
     */
    fun conversationKey(slotId: SlotId, dek: DataKey): ByteArray {
        val slot = io("slot unreadable") { VaultFiles.read(file(slotId)) } ?: throw KeySlotException("slot missing")
        return try {
            KeySlot.open(dek, slotId.bytes(), slot)
        } catch (e: InvalidVaultFileException) {
            throw KeySlotException("slot does not open", e)
        }
    }

    /** Crypto-shreds one conversation and drops its schedule entry. */
    @Synchronized
    fun destroySlot(slotId: SlotId) {
        io("slot not deleted") { VaultFiles.delete(file(slotId)) }
        saveSchedule(loadSchedule().filterKeys { it != slotId })
    }

    /** Crypto-shreds every conversation (erase everything) and the schedule. */
    @Synchronized
    fun destroyAll() {
        for (slotId in slotIds()) io("slot not deleted") { VaultFiles.delete(file(slotId)) }
        io("schedule not deleted") { VaultFiles.delete(scheduleFile) }
    }

    fun slotIds(): List<SlotId> = directory.list().orEmpty()
        .filter { it.endsWith(SLOT_SUFFIX) }
        .mapNotNull { SlotId.parse(it.removeSuffix(SLOT_SUFFIX)) }

    /** Sets (or clears, with `null`) the ghost-mode deadline of a conversation. */
    @Synchronized
    fun setDeadline(deadline: Instant?, slotId: SlotId) {
        val schedule = loadSchedule().toMutableMap()
        schedule.remove(slotId)
        if (deadline != null) schedule[slotId] = deadline.toEpochMilli()
        saveSchedule(schedule)
    }

    @Synchronized
    fun deadlines(): Map<SlotId, Instant> = loadSchedule().mapValues { Instant.ofEpochMilli(it.value) }

    /** Destroys every slot whose deadline has passed and returns their ids. */
    @Synchronized
    fun destroyExpiredSlots(now: Instant = Instant.now()): List<SlotId> {
        val schedule = loadSchedule()
        val nowMillis = now.toEpochMilli()
        val expired = schedule.filterValues { it <= nowMillis }.keys
        for (slotId in expired) io("slot not deleted") { VaultFiles.delete(file(slotId)) }
        saveSchedule(schedule.filterValues { it > nowMillis })
        return expired.toList()
    }

    private fun loadSchedule(): Map<SlotId, Long> {
        val bytes = io("schedule unreadable") { VaultFiles.read(scheduleFile) } ?: return emptyMap()
        try {
            val entries = JSONArray(String(bytes, Charsets.UTF_8))
            val schedule = LinkedHashMap<SlotId, Long>()
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val slotId = SlotId.parse(entry.getString(SLOT_ID)) ?: continue
                schedule[slotId] = entry.getLong(DEADLINE)
            }
            return schedule
        } catch (e: JSONException) {
            throw KeySlotException("schedule unreadable", e)
        }
    }

    private fun saveSchedule(schedule: Map<SlotId, Long>) {
        val entries = JSONArray()
        for ((slotId, deadline) in schedule.entries.sortedBy { it.value }) {
            entries.put(JSONObject().put(SLOT_ID, slotId.hex).put(DEADLINE, deadline))
        }
        io("schedule not written") { VaultFiles.write(scheduleFile, entries.toString().toByteArray(Charsets.UTF_8)) }
    }

    private inline fun <T> io(what: String, block: () -> T): T = try {
        block()
    } catch (e: KeySlotException) {
        throw e
    } catch (e: IOException) {
        throw KeySlotException(what, e)
    }

    companion object {
        const val SCHEDULE_NAME = "schedule.json"
        private const val SLOT_SUFFIX = ".slot"
        private const val SLOT_ID = "slotId"
        private const val DEADLINE = "deadline"
    }
}
