package com.syconx.us2us.runtime.engine

import com.google.protobuf.ByteString
import com.syconx.us2us.core.encoding.Hex
import com.syconx.us2us.core.vault.DataKey
import com.syconx.us2us.core.vault.ItemCipher
import com.syconx.us2us.core.vault.ItemDecryptionException
import com.syconx.us2us.runtime.support.ProtectedFiles
import com.syconx.us2us.runtime.vault.KeySlotException
import com.syconx.us2us.runtime.vault.KeySlotStore
import com.syconx.us2us.runtime.vault.SecretKeyMaterial
import com.syconx.us2us.runtime.vault.SecretKeyWipedException
import com.syconx.us2us.runtime.vault.SlotId
import com.syconx.us2us.runtime.vault.VaultFiles
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.withLock

/**
 * What the runtime keeps besides the storage's database: the user's settings and bookkeeping that
 * carries no message content. One file, encrypted (AES-256-GCM, the core's `ItemCipher`) under a key
 * derived from the DEK, so it is exactly as protected as the database.
 */
data class RuntimeStateData(
    val settings: UserSettings = UserSettings(),
    /** A block needs a new access key on the relay (§6.4); retried until done. */
    val pendingAccessKeyRotation: Boolean = false,
    /** Conversation id (hex) → notice key-slot id (hex), see [NoticeStore]. */
    val noticeSlots: Map<String, String> = emptyMap(),
    /**
     * When an exported account backup was last saved (7.1); only the time, nothing about the file
     * or where it went. `null` before the first one, also after a restore.
     */
    val lastBackupAt: Instant? = null,
    /**
     * The built-in stickers last sent (the picker's recents), newest first: short opaque ids the
     * app chose ("101.tr"), at most [MessagingEngine.MAX_RECENT_STICKERS]. Which stickers only —
     * never to whom or when.
     */
    val recentStickers: List<String> = emptyList(),
) {
    internal fun toJson(): JSONObject = JSONObject()
        .put("settings", settings.toJson())
        .put("pendingAccessKeyRotation", pendingAccessKeyRotation)
        .put("noticeSlots", JSONObject(noticeSlots))
        .put("lastBackupAt", lastBackupAt?.toEpochMilli() ?: JSONObject.NULL)
        .put("recentStickers", JSONArray(recentStickers))

    companion object {
        /**
         * Tolerant decoding: a field that is missing or unreadable falls back to its default, so a
         * state file written by another build still loads with everything else it holds.
         */
        internal fun fromJson(json: JSONObject): RuntimeStateData {
            val defaults = RuntimeStateData()
            val slots = json.optJSONObject("noticeSlots")?.let { object_ ->
                object_.keys().asSequence().mapNotNull { key -> (object_.opt(key) as? String)?.let { key to it } }.toMap()
            }
            val stickers = json.optJSONArray("recentStickers")?.let { array ->
                (0 until array.length()).mapNotNull { array.opt(it) as? String }
            }
            val backup = json.opt("lastBackupAt").let { value -> (value as? Number)?.toLong()?.let(Instant::ofEpochMilli) }
            return RuntimeStateData(
                settings = UserSettings.fromJson(json.optJSONObject("settings")),
                pendingAccessKeyRotation = (json.opt("pendingAccessKeyRotation") as? Boolean) ?: defaults.pendingAccessKeyRotation,
                noticeSlots = slots ?: defaults.noticeSlots,
                lastBackupAt = backup ?: defaults.lastBackupAt,
                recentStickers = stickers ?: defaults.recentStickers,
            )
        }
    }
}

/** HKDF-SHA256 (RFC 5869) with an empty salt: the runtime's sub-keys of the DEK. */
internal object RuntimeKeys {
    private const val LENGTH = 32

    fun derive(dek: ByteArray, purpose: String): ByteArray {
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(ByteArray(LENGTH), "HmacSHA256"))
        val prk = extract.doFinal(dek)
        val expand = Mac.getInstance("HmacSHA256")
        expand.init(SecretKeySpec(prk, "HmacSHA256"))
        expand.update(purpose.toByteArray(Charsets.UTF_8))
        expand.update(1.toByte())
        prk.fill(0)
        return expand.doFinal()
    }
}

/**
 * The encrypted runtime state file. [close] may come from another thread (the system ending the
 * background time); no load or save ever runs with a half-zeroed key.
 */
class RuntimeStateFile(directory: File, dek: SecretKeyMaterial) {
    val file = File(directory, FILE_NAME)
    private val key: ByteArray = dek.withBytes { RuntimeKeys.derive(it, PURPOSE) }
    private val lock = ReentrantLock()
    private var closed = false

    fun load(): RuntimeStateData = lock.withLock {
        if (closed) return RuntimeStateData()
        val sealed = try {
            VaultFiles.read(file)
        } catch (_: IOException) {
            null
        } ?: return RuntimeStateData()
        try {
            val plaintext = ItemCipher.open(key, ITEM_ID, sealed)
            try {
                RuntimeStateData.fromJson(JSONObject(String(plaintext, Charsets.UTF_8)))
            } finally {
                plaintext.fill(0)
            }
        } catch (_: ItemDecryptionException) {
            RuntimeStateData()
        } catch (_: JSONException) {
            RuntimeStateData()
        }
    }

    /** @throws SecretKeyWipedException after [close]: never written under a zeroed key. */
    fun save(state: RuntimeStateData) = lock.withLock {
        if (closed) throw SecretKeyWipedException()
        val plaintext = state.toJson().toString().toByteArray(Charsets.UTF_8)
        try {
            ProtectedFiles.write(ItemCipher.seal(key, ITEM_ID, plaintext), file)
        } finally {
            plaintext.fill(0)
        }
    }

    fun close() = lock.withLock {
        closed = true
        key.fill(0)
    }

    companion object {
        const val FILE_NAME = "runtime-state.item"
        private const val PURPOSE = "us2us/runtime-state/v1"

        /** Fixed item id binding the ciphertext to its purpose. */
        private val ITEM_ID = "us2us-runtime-01".toByteArray(Charsets.UTF_8)

        /** Changes the state of a vault that has no engine yet (a new or restored account's display name). */
        fun update(directory: File, dek: SecretKeyMaterial, change: (RuntimeStateData) -> RuntimeStateData) {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create the runtime directory")
            val file = RuntimeStateFile(directory, dek)
            try {
                file.save(change(file.load()))
            } finally {
                file.close()
            }
        }
    }
}

/** A local system line of a conversation. */
data class NoticeRecord(val id: ByteString, val at: Instant, val notice: SystemNotice)

/**
 * Local system lines ("screenshot taken", "safety number changed", ghost settings changed), which
 * the storage has no table for.
 *
 * Privacy: each conversation's lines are one item encrypted under their own conversation key, held
 * in a key slot whose deadline mirrors the conversation's ghost deadline. Deleting the slot (when
 * the conversation is wiped, by the background wipe while locked, or at the next unlock for anything
 * left over) makes the lines unreadable at the same moment as the conversation itself (§5.3).
 */
class NoticeStore(val directory: File, dek: SecretKeyMaterial) {
    private val slots = KeySlotStore(slotDirectory(directory))
    private val dek: ByteArray = dek.withBytes { it.copyOf() }
    private val lock = ReentrantLock()
    private var closed = false

    init {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create the notice directory")
    }

    fun close() = lock.withLock {
        closed = true
        dek.fill(0)
    }

    private fun file(slotId: SlotId) = File(directory, "${slotId.hex}$SUFFIX")

    private fun <T> withDataKey(body: (DataKey) -> T): T {
        val key = DataKey(dek)
        try {
            return body(key)
        } finally {
            key.wipe()
        }
    }

    fun notices(slotId: SlotId): List<NoticeRecord> = lock.withLock {
        if (closed) return emptyList()
        try {
            val sealed = VaultFiles.read(file(slotId)) ?: return emptyList()
            val key = withDataKey { slots.conversationKey(slotId, it) }
            val plaintext = try {
                ItemCipher.open(key, slotId.bytes(), sealed)
            } finally {
                key.fill(0)
            }
            decode(String(plaintext, Charsets.UTF_8))
        } catch (_: IOException) {
            emptyList()
        } catch (_: ItemDecryptionException) {
            emptyList()
        } catch (_: JSONException) {
            emptyList()
        }
    }

    /** Appends a line; creates the conversation's slot on first use. Returns the slot id (stored in the runtime state). */
    fun append(record: NoticeRecord, existing: SlotId?, deadline: Instant?): SlotId = lock.withLock {
        // After close the key is zeros: never create slots or items under it.
        if (closed) throw SecretKeyWipedException()
        val usable = existing?.takeIf { slotId ->
            try {
                withDataKey { slots.conversationKey(slotId, it) }.fill(0)
                true
            } catch (_: KeySlotException) {
                false
            }
        }
        val slotId = usable ?: withDataKey { slots.createSlot(it) }.let { (id, key) ->
            key.fill(0)
            id
        }
        val records = (notices(slotId) + record).takeLast(MAX_RECORDS)
        val key = withDataKey { slots.conversationKey(slotId, it) }
        try {
            val plaintext = encode(records).toByteArray(Charsets.UTF_8)
            ProtectedFiles.write(ItemCipher.seal(key, slotId.bytes(), plaintext), file(slotId))
        } finally {
            key.fill(0)
        }
        slots.setDeadline(deadline, slotId)
        slotId
    }

    fun setDeadline(deadline: Instant?, slotId: SlotId) {
        try {
            slots.setDeadline(deadline, slotId)
        } catch (_: KeySlotException) {
        }
    }

    /** Crypto-shreds one conversation's lines (slot first, then the file). */
    fun destroy(slotId: SlotId) {
        try {
            slots.destroySlot(slotId)
        } catch (_: KeySlotException) {
        }
        try {
            VaultFiles.delete(file(slotId))
        } catch (_: IOException) {
        }
    }

    /** Removes everything not in [keep] (slots and files of wiped conversations, orphans after a crash). */
    fun collectGarbage(keep: Set<SlotId>) {
        for (slotId in slots.slotIds()) if (slotId !in keep) destroy(slotId)
        for (name in directory.list().orEmpty()) {
            if (!name.endsWith(SUFFIX)) continue
            val slotId = SlotId.parse(name.removeSuffix(SUFFIX)) ?: continue
            if (slotId !in keep) {
                try {
                    VaultFiles.delete(File(directory, name))
                } catch (_: IOException) {
                }
            }
        }
    }

    companion object {
        private const val SUFFIX = ".notices"

        /** Lines are few; the newest 500 per conversation are kept. */
        private const val MAX_RECORDS = 500

        fun slotDirectory(directory: File): File = File(directory, "slots")

        /** Crypto-shreds notices of ghost conversations whose deadline passed, without the DEK (alarm, launch). */
        fun runBackgroundWipes(directory: File, now: Instant): Int = try {
            KeySlotStore(slotDirectory(directory)).destroyExpiredSlots(now).size
        } catch (_: KeySlotException) {
            0
        }

        private fun encode(records: List<NoticeRecord>): String {
            val array = JSONArray()
            for (record in records) {
                array.put(
                    JSONObject()
                        .put("id", Hex.encode(record.id.toByteArray()))
                        .put("at", record.at.toEpochMilli())
                        .put("notice", SystemNotice.toJson(record.notice)),
                )
            }
            return array.toString()
        }

        private fun decode(text: String): List<NoticeRecord> {
            val array = JSONArray(text)
            return (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                val id = try {
                    ByteString.copyFrom(Hex.decode(entry.getString("id")))
                } catch (_: IllegalArgumentException) {
                    return@mapNotNull null
                }
                val notice = entry.optJSONObject("notice")?.let(SystemNotice::fromJson) ?: return@mapNotNull null
                NoticeRecord(id, Instant.ofEpochMilli(entry.optLong("at")), notice)
            }
        }
    }
}
