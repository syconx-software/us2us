package com.syconx.us2us.runtime.storage

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.runtime.vault.VaultFiles
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * Attachments: local encrypted files and upload/download state (§5.3, §7).
 *
 * The relay pointer and keys (`Attachment`: media id, delete token, key, digest, content type, file
 * name, caption…) are an item under `CK` (`itemId` = the attachment id). The decrypted media itself
 * is an item file named by the same id; a locally generated thumbnail is an item file of its own.
 * Plaintext exists on disk only in the staging directory, while the core encrypts or decrypts it.
 */
class AttachmentRepository internal constructor(private val tx: StorageTransaction) {
    /** Creates the row for an attachment of [messageId]. */
    fun create(
        messageId: ByteString,
        conversationId: ConversationId,
        descriptor: Attachment?,
        state: AttachmentState,
        now: Instant = Instant.now(),
    ): StoredAttachment {
        tx.requireWritable()
        val id = RandomIds.make()
        val key = tx.conversationKey(conversationId)
        val sealed = descriptor?.let { tx.seal(it.toByteArray(), id, key) }
        tx.connection.run(
            """
            INSERT INTO attachments (id, conversation_id, message_id, descriptor, state, byte_count, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            conversationId.raw,
            messageId,
            sealed,
            state.raw,
            descriptor?.size?.clampedUnsigned(),
            now.toEpochMilli(),
        )
        return get(id) ?: storageFailure(StorageError.NotFound)
    }

    fun get(id: ByteString): StoredAttachment? =
        tx.connection.queryOne("SELECT $COLUMNS FROM attachments WHERE id = ?", id)?.let(::attachment)

    fun forMessage(messageId: ByteString, conversationId: ConversationId): List<StoredAttachment> = tx.connection.query(
        "SELECT $COLUMNS FROM attachments WHERE conversation_id = ? AND message_id = ?",
        conversationId.raw,
        messageId,
    ).map(::attachment)

    /** Attachments in [states] (e.g. pending downloads to resume on open). */
    fun inStates(states: List<AttachmentState>, limit: Int = 100): List<StoredAttachment> {
        if (states.isEmpty()) return emptyList()
        val placeholders = states.joinToString(", ") { "?" }
        return tx.connection.query(
            "SELECT $COLUMNS FROM attachments WHERE state IN ($placeholders) ORDER BY updated_at LIMIT ?",
            *states.map { it.raw }.toTypedArray(),
            limit,
        ).map(::attachment)
    }

    fun setState(
        state: AttachmentState,
        id: ByteString,
        transferredBytes: Long? = null,
        countsAsAttempt: Boolean = false,
        now: Instant = Instant.now(),
    ) {
        tx.requireWritable()
        val changed = tx.connection.run(
            """
            UPDATE attachments SET state = ?, transferred = coalesce(?, transferred),
                attempts = attempts + ?, updated_at = ?
            WHERE id = ?
            """.trimIndent(),
            state.raw,
            transferredBytes,
            if (countsAsAttempt) 1 else 0,
            now.toEpochMilli(),
            id,
        )
        if (changed == 0) storageFailure(StorageError.NotFound)
    }

    /** The `Attachment` after an upload; with a relay pointer the state becomes uploaded. */
    fun setDescriptor(descriptor: Attachment, id: ByteString, now: Instant = Instant.now()) {
        tx.requireWritable()
        val attachment = get(id) ?: storageFailure(StorageError.NotFound)
        val key = tx.conversationKey(attachment.conversationId)
        val sealed = tx.seal(descriptor.toByteArray(), id, key)
        // A pointer means the blob is on the relay; without one it is still to upload.
        val state = if (descriptor.mediaId.size() == RandomIds.LENGTH) AttachmentState.UPLOADED else attachment.state
        tx.connection.run(
            "UPDATE attachments SET descriptor = ?, byte_count = ?, state = ?, updated_at = ? WHERE id = ?",
            sealed,
            descriptor.size.clampedUnsigned(),
            state.raw,
            now.toEpochMilli(),
            id,
        )
    }

    // MARK: Files

    /** Encrypts [plaintext] as the attachment's file and marks it present (downloaded unless an upload is in progress). */
    fun storeFile(plaintext: ByteArray, id: ByteString, now: Instant = Instant.now()) {
        tx.requireWritable()
        val attachment = get(id) ?: storageFailure(StorageError.NotFound)
        val key = tx.conversationKey(attachment.conversationId)
        tx.storage.files.write(plaintext, id, key)
        val uploading = attachment.state in setOf(AttachmentState.PENDING_UPLOAD, AttachmentState.UPLOADING, AttachmentState.UPLOADED)
        val state = if (uploading) attachment.state else AttachmentState.DOWNLOADED
        tx.connection.run(
            "UPDATE attachments SET has_file = 1, state = ?, byte_count = coalesce(byte_count, ?), updated_at = ? WHERE id = ?",
            state.raw,
            plaintext.size.toLong(),
            now.toEpochMilli(),
            id,
        )
    }

    /** Encrypts the plaintext [file] (e.g. a staging file the core downloaded into) and deletes the plaintext. */
    fun importFile(file: File, id: ByteString, now: Instant = Instant.now()) {
        try {
            val plaintext = try {
                file.readBytes()
            } catch (_: IOException) {
                storageFailure(StorageError.Io("staging file unreadable"))
            }
            storeFile(plaintext, id, now)
        } finally {
            file.delete()
        }
    }

    /** Decrypts the attachment's file. */
    fun readFile(id: ByteString): ByteArray {
        val attachment = get(id)
        if (attachment == null || !attachment.hasFile) storageFailure(StorageError.NotFound)
        return tx.storage.files.read(id, tx.conversationKey(attachment.conversationId))
    }

    /**
     * Decrypts the file into a staging file for APIs that need a path (upload, the media player).
     * Delete it when done; it is wiped on the next open or close anyway.
     */
    fun exportToStaging(id: ByteString): File {
        val plaintext = readFile(id)
        val file = tx.storage.files.newStagingFile()
        try {
            VaultFiles.write(file, plaintext)
        } catch (_: IOException) {
            storageFailure(StorageError.Io("write staging file"))
        } finally {
            plaintext.fill(0)
        }
        return file
    }

    fun storeThumbnail(plaintext: ByteArray, id: ByteString) {
        tx.requireWritable()
        val attachment = get(id) ?: storageFailure(StorageError.NotFound)
        val thumbnailId = attachment.thumbnailId ?: RandomIds.make()
        tx.storage.files.write(plaintext, thumbnailId, tx.conversationKey(attachment.conversationId))
        tx.connection.run("UPDATE attachments SET thumbnail_id = ? WHERE id = ?", thumbnailId, id)
    }

    fun readThumbnail(id: ByteString): ByteArray? {
        val attachment = get(id) ?: return null
        val thumbnailId = attachment.thumbnailId ?: return null
        return tx.storage.files.read(thumbnailId, tx.conversationKey(attachment.conversationId))
    }

    /** Deletes rows and (after commit) files of the given messages' attachments. */
    internal fun deleteAll(messageIds: List<ByteString>, conversationId: ConversationId) {
        val itemIds = ArrayList<ByteString>()
        for (messageId in messageIds) {
            val rows = tx.connection.query(
                "SELECT id, thumbnail_id FROM attachments WHERE conversation_id = ? AND message_id = ?",
                conversationId.raw,
                messageId,
            )
            for (row in rows) {
                row.bytes("id")?.let(itemIds::add)
                row.bytes("thumbnail_id")?.let(itemIds::add)
            }
            tx.connection.run("DELETE FROM attachments WHERE conversation_id = ? AND message_id = ?", conversationId.raw, messageId)
        }
        removeFilesAfterCommit(itemIds)
    }

    internal fun removeFilesAfterCommit(itemIds: List<ByteString>) {
        if (itemIds.isEmpty()) return
        val files = tx.storage.files
        tx.afterCommit {
            for (id in itemIds) {
                try {
                    files.remove(id)
                } catch (_: StorageException) {
                }
            }
        }
    }

    // MARK: Rows

    private fun attachment(row: Row): StoredAttachment {
        val id = row.bytes("id") ?: storageFailure(StorageError.Integrity)
        val conversationId = ConversationId.of(row.bytes("conversation_id")) ?: storageFailure(StorageError.Integrity)
        val messageId = row.bytes("message_id") ?: storageFailure(StorageError.Integrity)
        val state = AttachmentState.of(row.long("state")) ?: storageFailure(StorageError.Integrity)
        val descriptor = row.blob("descriptor")?.let { sealed ->
            try {
                Attachment.parseFrom(tx.open(sealed, id, tx.conversationKey(conversationId)))
            } catch (e: InvalidProtocolBufferException) {
                storageFailure(StorageError.Integrity, e)
            }
        }
        return StoredAttachment(
            id = id,
            conversationId = conversationId,
            messageId = messageId,
            descriptor = descriptor,
            state = state,
            hasFile = row.bool("has_file"),
            thumbnailId = row.bytes("thumbnail_id"),
            byteCount = row.long("byte_count"),
            transferredBytes = row.long("transferred") ?: 0L,
            attempts = row.int("attempts") ?: 0,
            updatedAt = row.instant("updated_at") ?: Instant.EPOCH,
        )
    }

    private companion object {
        const val COLUMNS =
            "id, conversation_id, message_id, descriptor, state, has_file, thumbnail_id, byte_count, transferred, attempts, updated_at"
    }
}
