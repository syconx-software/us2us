package com.syconx.us2us.runtime.storage

import com.google.protobuf.ByteString
import com.syconx.us2us.core.proto.DataMessage
import com.syconx.us2us.core.proto.TransferRecord
import com.syconx.us2us.core.proto.TransferSettings
import com.syconx.us2us.core.proto.transferAttachment
import com.syconx.us2us.core.proto.transferCommunity
import com.syconx.us2us.core.proto.transferCommunityKey
import com.syconx.us2us.core.proto.transferCommunityMessage
import com.syconx.us2us.core.proto.transferCommunityRecipient
import com.syconx.us2us.core.proto.transferConversation
import com.syconx.us2us.core.proto.transferFilePart
import com.syconx.us2us.core.proto.transferMessage
import com.syconx.us2us.core.proto.transferReaction
import com.syconx.us2us.core.proto.transferRecord
import com.syconx.us2us.core.transfer.DeviceTransferHistorySource
import com.syconx.us2us.core.transfer.DeviceTransferLimits
import com.syconx.us2us.core.encoding.Base64Url
import java.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The old phone's history for a direct transfer (PROTOCOL.md §5.6), as `TransferRecord`s: everything
 * a conversation or community holds except what must not outlive this phone. Disappearing messages,
 * view-once items and the messages of ghost-mode conversations stay behind: their ids go along as
 * removed, so a retransmission does not bring them back.
 */
class DeviceTransferHistoryExporter(
    private val storage: LocalStorage,
    private val settings: TransferSettings,
    private val now: () -> Instant = Instant::now,
) : DeviceTransferHistorySource {
    private val communities = CommunityRepository(storage)

    // MARK: Estimate

    override suspend fun estimatedBytes(): Long = storage.read { tx ->
        var total = 0L
        for (conversation in tx.conversations.all()) {
            if (conversation.isGhostModeEnabled) continue
            total += sum(tx, "SELECT coalesce(sum(coalesce(length(body), 0) + 96), 0) AS n FROM messages WHERE conversation_id = ? AND $MOVABLE", conversation.id.raw)
            total += sum(
                tx,
                """
                SELECT coalesce(sum(a.byte_count), 0) AS n FROM attachments a
                JOIN messages m ON m.conversation_id = a.conversation_id AND m.message_id = a.message_id
                WHERE a.conversation_id = ? AND a.has_file = 1 AND m.${MOVABLE.replace(" AND ", " AND m.")}
                """.trimIndent(),
                conversation.id.raw,
            )
        }
        total + communities.transferBytes(tx)
    }

    private fun sum(tx: StorageTransaction, sql: String, owner: Any): Long = tx.connection.queryOne(sql, owner)?.long("n") ?: 0L

    // MARK: Records

    override suspend fun export(emit: suspend (ByteArray) -> Unit) {
        emit(transferRecord { settings = this@DeviceTransferHistoryExporter.settings }.toByteArray())
        for (conversation in storage.read { it.conversations.all() }) {
            currentCoroutineContext().ensureActive()
            leavingBehind { export(conversation, emit) }
        }
        for (community in communities.all()) {
            currentCoroutineContext().ensureActive()
            leavingBehind { export(community, emit) }
        }
    }

    /**
     * What cannot be read (damaged, or wiped just now) stays behind and the move goes on; a store that
     * cannot be read at all (the vault locked) ends it. Nothing is read between a record and its file
     * parts, so none of this can leave a record without them.
     */
    private inline fun leavingBehind(block: () -> Unit) {
        try {
            block()
        } catch (e: StorageException) {
            when (e.error) {
                StorageError.Integrity, StorageError.ConversationShredded, StorageError.NotFound -> Unit
                else -> throw e
            }
        }
    }

    private suspend fun export(conversation: Conversation, emit: suspend (ByteArray) -> Unit) {
        var photo: ByteArray? = null
        val head = storage.read { tx ->
            val contact = tx.contacts.get(conversation.contact)
            val background = quietly { tx.chatBackgrounds.forContact(conversation.contact) }
            photo = (background as? ChatBackgroundChoice.Photo)?.jpeg
            transferConversation {
                this.contact = ByteString.copyFrom(conversation.contact.toBytes())
                contact?.let {
                    requestState = it.requestState.raw
                    username = it.profile.username?.value.orEmpty()
                    displayName = it.profile.displayName.orEmpty()
                    requestSentAt = tx.contacts.requestSentAt(conversation.contact)?.toEpochMilli() ?: 0
                    stateBeforeBlock = tx.contacts.stateBeforeBlock(conversation.contact).orEmpty()
                }
                settings = conversation.settings
                conversation.settingsAuthor?.let { settingsAuthor = ByteString.copyFrom(it.toBytes()) }
                createdAt = conversation.createdAt.toEpochMilli()
                lastActivityAt = conversation.lastActivityAt?.toEpochMilli() ?: 0
                // What was typed in a ghost chat goes the way of its messages.
                if (!conversation.isGhostModeEnabled) draft = quietly { tx.conversations.draft(conversation.id) }.orEmpty()
                removedMessageIds += removedIds(tx, conversation)
                (background as? ChatBackgroundChoice.Preset)?.let { backgroundPreset = it.id }
                photo?.let { backgroundPhotoBytes = it.size.toLong() }
            }
        }
        emit(transferRecord { this.conversation = head }.toByteArray())
        photo?.let { emitFile(it, emit) }
        if (conversation.isGhostModeEnabled) return

        var after = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            var last: Long? = null
            val page = storage.read { tx ->
                val key = tx.conversationKey(conversation.slotId)
                val rows = tx.connection.query(
                    """
                    SELECT ${MessageRepository.COLUMNS} FROM messages
                    WHERE conversation_id = ? AND id > ? AND $MOVABLE ORDER BY id LIMIT ?
                    """.trimIndent(),
                    conversation.id.raw,
                    after,
                    BATCH,
                )
                last = rows.lastOrNull()?.long("id")
                // A row that cannot be read is left behind instead of ending the move.
                rows.mapNotNull { row ->
                    try {
                        tx.messages.message(row, key)
                    } catch (_: StorageException) {
                        null
                    }
                }
            }
            after = last ?: return
            for (message in page) {
                currentCoroutineContext().ensureActive()
                leavingBehind { export(message, conversation.id, emit) }
            }
        }
    }

    private fun removedIds(tx: StorageTransaction, conversation: Conversation): List<ByteString> {
        val ids = tx.connection.query(
            "SELECT message_id FROM removed_messages WHERE conversation_id = ? LIMIT ?",
            conversation.id.raw,
            MAX_REMOVED_IDS,
        ).mapNotNull { it.bytes("message_id") }
        val left = MAX_REMOVED_IDS - ids.size
        if (left <= 0) return ids
        val filter = if (conversation.isGhostModeEnabled) "" else "AND NOT ($MOVABLE)"
        return ids + tx.connection.query(
            "SELECT message_id FROM messages WHERE conversation_id = ? $filter ORDER BY id DESC LIMIT ?",
            conversation.id.raw,
            left,
        ).mapNotNull { it.bytes("message_id") }
    }

    private suspend fun export(message: StoredMessage, conversationId: ConversationId, emit: suspend (ByteArray) -> Unit) {
        val id = message.messageId
        val (reactions, attachments) = storage.read { tx ->
            quietly { tx.messages.reactions(listOf(id), conversationId)[id] }.orEmpty() to tx.attachments.forMessage(id, conversationId)
        }
        val files = ArrayList<ByteArray>()
        val record = transferMessage {
            messageId = id
            outgoing = message.isOutgoing
            message.content?.let { content ->
                // Whatever a sharing still running would need stays here: on the new phone it has ended.
                val moved: DataMessage = if (content.hasLiveLocation() && !content.liveLocation.stopped) {
                    content.toBuilder().setLiveLocation(content.liveLocation.toBuilder().setStopped(true)).build()
                } else {
                    content
                }
                this.content = moved.toByteString()
            }
            kind = message.kind.raw
            sentAt = message.sentAt.toEpochMilli()
            createdAt = message.createdAt.toEpochMilli()
            serverTimestamp = message.serverTimestamp?.toEpochMilli() ?: 0
            val stamps = message.timestamps
            // What was still on its way stays on this phone's outbox: the new phone says it failed.
            val unsent = message.status == MessageStatus.SENDING
            status = (if (unsent) MessageStatus.FAILED else message.status).raw
            failedAt = (if (unsent) now() else stamps.failedAt)?.toEpochMilli() ?: 0
            acceptedAt = stamps.acceptedAt?.toEpochMilli() ?: 0
            deliveredAt = stamps.deliveredAt?.toEpochMilli() ?: 0
            readAt = stamps.readAt?.toEpochMilli() ?: 0
            viewedAt = stamps.viewedAt?.toEpochMilli() ?: 0
            notDeliveredAt = stamps.notDeliveredAt?.toEpochMilli() ?: 0
            displayedAt = stamps.displayedAt?.toEpochMilli() ?: 0
            savedByPeerAt = stamps.savedByPeerAt?.toEpochMilli() ?: 0
            savedByMeAt = stamps.savedByMeAt?.toEpochMilli() ?: 0
            deletedAt = message.deletedAt?.toEpochMilli() ?: 0
            this.reactions += reactions.map { reaction ->
                transferReaction {
                    reactor = ByteString.copyFrom(reaction.reactor.toBytes())
                    emoji = reaction.emoji
                    sentAt = reaction.sentAt.toEpochMilli()
                }
            }
            for (attachment in attachments) {
                // A file that cannot be read is treated as one that never came.
                val file = if (attachment.hasFile) quietly { storage.read { it.attachments.readFile(attachment.id) } } else null
                val thumbnail = quietly { storage.read { it.attachments.readThumbnail(attachment.id) } }
                this.attachments += transferAttachment {
                    attachment.descriptor?.let { descriptor = it }
                    byteCount = attachment.byteCount ?: 0
                    state = state(attachment, file != null).raw
                    hasFile = file != null
                    fileBytes = file?.size?.toLong() ?: 0
                    thumbnailBytes = thumbnail?.size?.toLong() ?: 0
                }
                file?.let(files::add)
                thumbnail?.let(files::add)
            }
        }
        emit(transferRecord { this.message = record }.toByteArray())
        for (file in files) emitFile(file, emit)
    }

    /**
     * Transitional states do not move: nothing runs on the new phone yet. A file that never reached
     * the relay stays ready to go up, when "Tekrar gönder" asks for it.
     */
    private fun state(attachment: StoredAttachment, hasFile: Boolean): AttachmentState {
        val pointed = attachment.descriptor?.mediaId?.size() == RandomIds.LENGTH
        return when (attachment.state) {
            AttachmentState.PENDING_UPLOAD, AttachmentState.UPLOADING -> if (hasFile) AttachmentState.PENDING_UPLOAD else AttachmentState.FAILED
            AttachmentState.UPLOADED -> if (pointed) AttachmentState.UPLOADED else AttachmentState.FAILED
            AttachmentState.PENDING_DOWNLOAD, AttachmentState.DOWNLOADING ->
                if (hasFile) AttachmentState.DOWNLOADED else AttachmentState.PENDING_DOWNLOAD
            AttachmentState.DOWNLOADED -> when {
                hasFile -> AttachmentState.DOWNLOADED
                pointed -> AttachmentState.PENDING_DOWNLOAD
                else -> AttachmentState.UNAVAILABLE
            }
            AttachmentState.FAILED, AttachmentState.UNAVAILABLE -> attachment.state
        }
    }

    private suspend fun export(community: StoredCommunity, emit: suspend (ByteArray) -> Unit) {
        var photo: ByteArray? = null
        val head = storage.read { tx ->
            val background = quietly { tx.chatBackgrounds.forCommunity(community.id) }
            photo = (background as? ChatBackgroundChoice.Photo)?.jpeg
            transferCommunity {
                id = ByteString.copyFrom(community.id.toBytes())
                ownerKey = ByteString.copyFrom(Base64Url.decode(community.ownerKey))
                name = community.name
                community.signed?.let {
                    state = ByteString.copyFrom(Base64Url.decode(it.state))
                    signature = ByteString.copyFrom(Base64Url.decode(it.signature))
                }
                active = community.active
                removed += communities.removedKeys(tx, community.id, MAX_REMOVED_IDS).map { (sender, messageId, removedAt) ->
                    transferCommunityKey {
                        this.sender = ByteString.copyFrom(sender.toBytes())
                        this.messageId = ByteString.copyFrom(messageId.toBytes())
                        this.removedAt = removedAt
                    }
                }
                (background as? ChatBackgroundChoice.Preset)?.let { backgroundPreset = it.id }
                photo?.let { backgroundPhotoBytes = it.size.toLong() }
            }
        }
        emit(transferRecord { this.community = head }.toByteArray())
        photo?.let { emitFile(it, emit) }

        // Oldest first: the order the new phone stores them in.
        val pages = ArrayList<List<StoredCommunityMessage>>()
        var before: CommunityHistoryCursor? = null
        while (true) {
            val page = communities.history(community.id, before = before, limit = BATCH, skippingUnreadable = true)
            if (page.items.isNotEmpty()) pages += page.items
            before = page.oldest
            if (!page.hasOlder || before == null) break
        }
        for (page in pages.asReversed()) {
            for (item in page) {
                currentCoroutineContext().ensureActive()
                leavingBehind { export(item, emit) }
            }
        }
    }

    private suspend fun export(item: StoredCommunityMessage, emit: suspend (ByteArray) -> Unit) {
        val extras = communities.transferExtras(item.communityId, item.sender, item.messageId)
        val file = if (item.attachment != null) quietly { communities.readFile(item.communityId, item.sender, item.messageId) } else null
        val own = extras.own
        val waiting = if (own) communities.recipients(item.communityId, item.messageId) else emptyList()
        val record = transferCommunityMessage {
            sender = ByteString.copyFrom(item.sender.toBytes())
            messageId = ByteString.copyFrom(item.messageId.toBytes())
            sentAt = item.sentAt
            receivedAt = item.receivedAt
            displayedAt = item.displayedAt ?: 0
            text = item.text
            item.sticker?.let { sticker = it }
            item.attachment?.let { attachment = it }
            mediaState = when {
                file != null -> CommunityMediaState.LOCAL.raw
                item.media == CommunityMediaState.REMOTE -> CommunityMediaState.REMOTE.raw
                else -> CommunityMediaState.UNAVAILABLE.raw
            }
            hasFile = file != null
            fileBytes = file?.size?.toLong() ?: 0
            extras.deleteToken?.let { deleteToken = ByteString.copyFrom(it) }
            uploadedAt = extras.uploadedAt ?: 0
            deletedBy = item.deletedBy?.raw ?: 0
            deletedAt = extras.deletedAt ?: 0
            status = when {
                !own -> 3
                item.status == "sent" -> 0
                // Done for all but those who left, unless someone still waits: this phone's queue stays behind.
                item.status == "partial" && waiting.all { it.transport == CommunityRecipient.ACCEPTED || it.transport == CommunityRecipient.SKIPPED } -> 1
                else -> 2
            }
            if (own) {
                recipients += waiting.map { recipient ->
                    transferCommunityRecipient {
                        member = ByteString.copyFrom(recipient.member.toBytes())
                        state = when (recipient.transport) {
                            CommunityRecipient.ACCEPTED -> 1
                            CommunityRecipient.SKIPPED -> 2
                            else -> 3
                        }
                        deliveredAt = recipient.deliveredAt ?: 0
                        readAt = recipient.readAt ?: 0
                    }
                }
            }
        }
        emit(transferRecord { communityMessage = record }.toByteArray())
        file?.let { emitFile(it, emit) }
    }

    private suspend fun emitFile(file: ByteArray, emit: suspend (ByteArray) -> Unit) {
        var start = 0
        while (start < file.size) {
            currentCoroutineContext().ensureActive()
            val end = minOf(start + DeviceTransferLimits.CHUNK_BYTES, file.size)
            val part = ByteString.copyFrom(file, start, end - start)
            emit(transferRecord { filePart = transferFilePart { data = part } }.toByteArray())
            start = end
        }
    }

    private inline fun <T> quietly(block: () -> T): T? = try {
        block()
    } catch (_: StorageException) {
        null
    } catch (_: OutOfMemoryError) {
        // Whole files are read into memory: one too large for this phone must not end the move.
        null
    }

    private companion object {
        /** One read's worth of messages. */
        const val BATCH = 200

        /** Removed ids per conversation (at most some 360 KiB of a 1 MiB frame). */
        const val MAX_REMOVED_IDS = 20_000

        /** What may leave this phone at all. */
        const val MOVABLE = "expire_timer = 0 AND expires_at IS NULL AND view_once = 0"
    }
}
