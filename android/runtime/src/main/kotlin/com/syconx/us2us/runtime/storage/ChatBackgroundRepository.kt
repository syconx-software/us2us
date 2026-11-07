package com.syconx.us2us.runtime.storage

import com.syconx.us2us.core.AccountId
import java.time.Instant

/** What one chat shows behind its messages on this phone: a built-in preset (its id) or a photo the user picked. */
sealed interface ChatBackgroundChoice {
    data class Preset(val id: String) : ChatBackgroundChoice

    /** A JPEG the app prepared. */
    class Photo(val jpeg: ByteArray) : ChatBackgroundChoice {
        override fun equals(other: Any?): Boolean = other is Photo && jpeg.contentEquals(other.jpeg)
        override fun hashCode(): Int = jpeg.contentHashCode()
    }

    companion object {
        const val MAX_PRESET_ID_LENGTH = 32
        const val MAX_PHOTO_BYTES = 2_500_000
    }
}

/**
 * Each chat's background (schema v12). It is a choice of this phone: never sent, and not part of
 * a backup. The row belongs to the conversation or the community, so it goes with it however that
 * ends (“Sohbeti sil”, “Kişiyi sil”, a Ghost wipe, leaving a community, erasing everything).
 */
class ChatBackgroundRepository internal constructor(private val tx: StorageTransaction) {
    fun forContact(account: AccountId): ChatBackgroundChoice? {
        val conversation = tx.conversations.forContact(account) ?: return null
        return read("conversation_id", conversation.id.raw)
    }

    /** `null` returns the chat to the app's own background. */
    fun setForContact(choice: ChatBackgroundChoice?, account: AccountId, now: Instant = Instant.now()) {
        tx.requireWritable()
        val conversation = tx.conversations.ensure(account, now)
        write(choice, "conversation_id", conversation.id.raw)
    }

    fun forCommunity(id: AccountId): ChatBackgroundChoice? = read("community_id", id.toString())

    /** `null` returns the community to the app's own background. */
    fun setForCommunity(choice: ChatBackgroundChoice?, id: AccountId) {
        tx.requireWritable()
        tx.connection.queryOne("SELECT 1 FROM community_states WHERE id = ?", id.toString())
            ?: storageFailure(StorageError.NotFound)
        write(choice, "community_id", id.toString())
    }

    private fun read(column: String, owner: Any): ChatBackgroundChoice? {
        val row = tx.connection.queryOne("SELECT preset, photo FROM chat_backgrounds WHERE $column = ?", owner)
            ?: return null
        row.text("preset")?.let { return ChatBackgroundChoice.Preset(it) }
        return row.blob("photo")?.let { ChatBackgroundChoice.Photo(it) }
    }

    private fun write(choice: ChatBackgroundChoice?, column: String, owner: Any) {
        if (choice == null) {
            tx.connection.run("DELETE FROM chat_backgrounds WHERE $column = ?", owner)
            return
        }
        val isValid = when (choice) {
            is ChatBackgroundChoice.Preset ->
                choice.id.isNotEmpty() && choice.id.toByteArray().size <= ChatBackgroundChoice.MAX_PRESET_ID_LENGTH
            is ChatBackgroundChoice.Photo ->
                choice.jpeg.isNotEmpty() && choice.jpeg.size <= ChatBackgroundChoice.MAX_PHOTO_BYTES
        }
        if (!isValid) storageFailure(StorageError.InvalidState("chat background"))
        tx.connection.run(
            """
            INSERT INTO chat_backgrounds ($column, preset, photo) VALUES (?, ?, ?)
            ON CONFLICT($column) DO UPDATE SET preset = excluded.preset, photo = excluded.photo
            """.trimIndent(),
            owner,
            (choice as? ChatBackgroundChoice.Preset)?.id,
            (choice as? ChatBackgroundChoice.Photo)?.jpeg,
        )
    }
}
