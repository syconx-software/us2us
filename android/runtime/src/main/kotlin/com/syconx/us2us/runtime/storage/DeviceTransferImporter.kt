package com.syconx.us2us.runtime.storage

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.community.SignedCommunityState
import com.syconx.us2us.core.encoding.Base64Url
import com.syconx.us2us.core.proto.DataMessage
import com.syconx.us2us.core.proto.TransferCommunity
import com.syconx.us2us.core.proto.TransferCommunityMessage
import com.syconx.us2us.core.proto.TransferConversation
import com.syconx.us2us.core.proto.TransferMessage
import com.syconx.us2us.core.proto.TransferRecord
import com.syconx.us2us.core.proto.TransferSettings
import com.syconx.us2us.core.transfer.DeviceTransferError
import com.syconx.us2us.core.transfer.DeviceTransferException
import com.syconx.us2us.core.transfer.DeviceTransferSpoolReader
import com.syconx.us2us.core.username.Username
import java.time.Instant

/**
 * The new phone's side of the history of a direct transfer (PROTOCOL.md §5.6): takes the
 * `TransferRecord`s kept in the spool into a vault that has just been created, under keys of this
 * phone. Blocking: run it off the main thread. A record that makes no sense is left out; a storage
 * that cannot take what is good ends the import, and with it the restore.
 */
class DeviceTransferHistoryImporter(
    private val storage: LocalStorage,
    private val ownAccount: AccountId,
    private val reader: DeviceTransferSpoolReader,
    private val applySettings: (TransferSettings) -> Unit,
    private val now: Instant = Instant.now(),
) {
    private class PendingFile(val file: ByteArray?, val thumbnail: ByteArray?)

    private class Pending(val message: TransferMessage, val files: List<PendingFile>)

    private val communities = CommunityRepository(storage)
    private var conversation: Conversation? = null
    private var community: AccountId? = null
    private val pending = ArrayList<Pending>()
    private val pendingInCommunity = ArrayList<Pair<TransferCommunityMessage, ByteArray?>>()

    fun run(progress: (Double) -> Unit = {}) {
        var reported = -1
        while (true) {
            val bytes = reader.next() ?: break
            val record = TransferRecord.parseFrom(bytes)
            when (record.recordCase) {
                TransferRecord.RecordCase.SETTINGS -> applySettings(record.settings)
                TransferRecord.RecordCase.CONVERSATION -> begin(record.conversation)
                TransferRecord.RecordCase.MESSAGE -> add(record.message)
                TransferRecord.RecordCase.COMMUNITY -> begin(record.community)
                TransferRecord.RecordCase.COMMUNITY_MESSAGE -> add(record.communityMessage)
                else -> throw DeviceTransferException(DeviceTransferError.BAD_MESSAGE)
            }
            // Whole percents: there can be hundreds of thousands of records.
            val percent = (reader.fraction * 100).toInt()
            if (percent != reported) {
                reported = percent
                progress(reader.fraction)
            }
        }
        flush()
        storage.write { it.checkpointAfterCommit() }
    }

    // MARK: Conversations

    private fun begin(head: TransferConversation) {
        flush()
        conversation = null
        community = null
        val photo = readFile(head.backgroundPhotoBytes)
        val contact = accountIdOf(head.contact.toByteArray()) ?: return
        conversation = storage.write { tx ->
            if (contact == ownAccount) return@write null
            if (tx.contacts.get(contact) == null) {
                // A message request: no backup holds those.
                val state = contactState(head.requestState) ?: return@write null
                tx.contacts.create(contact, state, now)
                tx.contacts.setProfile(
                    ContactProfile(
                        username = head.username.takeIf { it.isNotEmpty() }?.let { text -> Username.parse(text)?.takeIf { it.value == text } },
                        displayName = head.displayName.ifEmpty { null },
                    ),
                    contact,
                )
                // Else its answer would not count as an answer to a request of ours (§6.4).
                instant(head.requestSentAt)?.let { tx.contacts.markRequestSent(contact, it) }
            }
            contactState(head.stateBeforeBlock)?.let { tx.contacts.adoptStateBeforeBlock(it, contact) }
            val conversation = tx.conversations.ensure(contact, now)
            tx.conversations.adopt(
                head.settings,
                accountIdOf(head.settingsAuthor.toByteArray()),
                instant(head.createdAt),
                instant(head.lastActivityAt),
                conversation,
                now,
            )
            if (head.draft.isNotEmpty() && !head.settings.ghost.enabled) tx.conversations.setDraft(head.draft, conversation.id)
            val day = EnvelopeLog.day(now)
            for (id in head.removedMessageIdsList) {
                if (id.size() != RandomIds.LENGTH) continue
                tx.connection.run(
                    "INSERT OR IGNORE INTO removed_messages (conversation_id, message_id, removed_day) VALUES (?, ?, ?)",
                    conversation.id.raw,
                    id,
                    day,
                )
            }
            background(head.backgroundPreset, photo)?.let { tx.chatBackgrounds.setForContact(it, contact, now) }
            tx.conversations.get(conversation.id) ?: conversation
        }
    }

    private fun add(message: TransferMessage) {
        val files = message.attachmentsList.map { attachment ->
            PendingFile(
                file = if (attachment.hasFile) readFile(attachment.fileBytes) ?: ByteArray(0) else null,
                thumbnail = readFile(attachment.thumbnailBytes),
            )
        }
        pending += Pending(message, files)
        if (pending.size >= BATCH || files.any { it.file != null || it.thumbnail != null }) flush()
    }

    private fun flush() {
        val items = ArrayList(pending)
        pending.clear()
        conversation?.takeIf { items.isNotEmpty() }?.let { conversation ->
            storage.write { tx -> for (item in items) store(item, conversation, tx) }
        }
        val messages = ArrayList(pendingInCommunity)
        pendingInCommunity.clear()
        community?.takeIf { messages.isNotEmpty() }?.let { id ->
            storage.write { tx -> for ((message, file) in messages) communities.adopt(tx, id, ownAccount, message, file, now) }
        }
    }

    private fun store(item: Pending, conversation: Conversation, tx: StorageTransaction) {
        val message = item.message
        if (conversation.isGhostModeEnabled || message.messageId.size() != RandomIds.LENGTH || !isValid(message)) return
        var content: DataMessage? = null
        if (!message.content.isEmpty) {
            val data = try {
                DataMessage.parseFrom(message.content)
            } catch (_: InvalidProtocolBufferException) {
                return
            }
            val kind = MessageKind.of(data.payloadCase)
            if (data.messageId != message.messageId || data.expireTimerSeconds != 0 || data.viewOnce ||
                kind == MessageKind.UNKNOWN || kind.raw != message.kind
            ) {
                return
            }
            content = data
        } else if (message.deletedAt == 0L) {
            return
        }
        val key = tx.conversationKey(conversation.slotId)
        val body = content?.let { tx.seal(message.content.toByteArray(), message.messageId, key) }
        val changes = tx.connection.run(
            """
            INSERT OR IGNORE INTO messages (conversation_id, message_id, outgoing, sender, kind, body, sent_at, created_at,
                server_timestamp, status, accepted_at, delivered_at, read_at, viewed_at, not_delivered_at, failed_at,
                displayed_at, expire_timer, view_once, quote_target, live_session, deleted_at, saved_by_peer_at, saved_by_me_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?, ?, ?)
            """.trimIndent(),
            conversation.id.raw,
            message.messageId,
            message.outgoing,
            if (message.outgoing) ownAccount.toBytes() else conversation.contact.toBytes(),
            message.kind,
            body,
            message.sentAt.clampedUnsigned(),
            stamp(message.createdAt) ?: now.toEpochMilli(),
            stamp(message.serverTimestamp),
            message.status,
            stamp(message.acceptedAt),
            stamp(message.deliveredAt),
            stamp(message.readAt),
            stamp(message.viewedAt),
            stamp(message.notDeliveredAt),
            stamp(message.failedAt),
            stamp(message.displayedAt),
            if (content?.hasQuote() == true) content.quote.targetMessageId else null,
            if (content?.hasLiveLocation() == true) content.liveLocation.sessionId else null,
            stamp(message.deletedAt),
            stamp(message.savedByPeerAt),
            stamp(message.savedByMeAt),
        )
        if (changes == 0) return
        for (reaction in message.reactionsList) {
            if (reaction.reactor.size() != AccountId.BYTES || reaction.emoji.isEmpty()) continue
            val reactionId = RandomIds.make()
            tx.connection.run(
                """
                INSERT OR REPLACE INTO reactions (conversation_id, target_message_id, reactor, reaction_id, emoji, sent_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                conversation.id.raw,
                message.messageId,
                reaction.reactor,
                reactionId,
                tx.seal(reaction.emoji.toByteArray(Charsets.UTF_8), reactionId, key),
                reaction.sentAt.clampedUnsigned(),
            )
        }
        if (content == null) return
        message.attachmentsList.forEachIndexed { index, attachment ->
            val files = item.files[index]
            val created = tx.attachments.create(
                message.messageId,
                conversation.id,
                if (attachment.hasDescriptor()) attachment.descriptor else null,
                AttachmentState.of(attachment.state.toLong()) ?: AttachmentState.UNAVAILABLE,
                now,
            )
            files.file?.let { file ->
                tx.storage.files.write(file, created.id, key)
                tx.connection.run(
                    "UPDATE attachments SET has_file = 1, byte_count = coalesce(byte_count, ?) WHERE id = ?",
                    file.size.toLong(),
                    created.id,
                )
            }
            if (attachment.byteCount > 0) {
                tx.connection.run("UPDATE attachments SET byte_count = ? WHERE id = ?", attachment.byteCount, created.id)
            }
            files.thumbnail?.let { tx.attachments.storeThumbnail(it, created.id) }
        }
        // Its place in the outbox did not come along: "Tekrar gönder" needs one.
        if (!message.outgoing || MessageStatus.of(message.status.toLong()) != MessageStatus.FAILED) return
        val builder = content.toBuilder()
        for (attachment in message.attachmentsList) {
            if (!attachment.hasDescriptor() || attachment.descriptor.mediaId.size() != RandomIds.LENGTH) continue
            when {
                builder.hasAttachment() -> builder.setAttachment(attachment.descriptor)
                builder.hasSticker() -> builder.setSticker(builder.sticker.toBuilder().setCustom(attachment.descriptor))
            }
        }
        val data = builder.build()
        tx.outbox.adoptFailed(contentOf(data), conversation.contact, conversation.id, message.messageId, now)
    }

    private fun contentOf(message: DataMessage) = com.syconx.us2us.core.proto.content { data = message }

    private fun isValid(message: TransferMessage): Boolean {
        val status = MessageStatus.of(message.status.toLong()) ?: return false
        if (status == MessageStatus.SENDING) return false
        return message.outgoing != (status == MessageStatus.RECEIVED)
    }

    // MARK: Communities

    private fun begin(head: TransferCommunity) {
        flush()
        conversation = null
        community = null
        val photo = readFile(head.backgroundPhotoBytes)
        val id = accountIdOf(head.id.toByteArray()) ?: return
        val signed = if (head.state.isEmpty) {
            null
        } else {
            SignedCommunityState(Base64Url.encode(head.state.toByteArray()), Base64Url.encode(head.signature.toByteArray()))
        }
        community = communities.adopt(id, Base64Url.encode(head.ownerKey.toByteArray()), head.name, signed, head.active, head.removedList, now)
        if (community != null) {
            background(head.backgroundPreset, photo)?.let { choice ->
                storage.write { it.chatBackgrounds.setForCommunity(choice, id) }
            }
        }
    }

    private fun add(message: TransferCommunityMessage) {
        val file = if (message.hasFile) readFile(message.fileBytes) ?: ByteArray(0) else null
        if (community == null) return
        pendingInCommunity += message to file
        if (pendingInCommunity.size >= BATCH || file != null) flush()
    }

    // MARK: Support

    /** The next [count] bytes, from the file parts that follow; `null` for none. */
    private fun readFile(count: Long): ByteArray? {
        if (count <= 0) return null
        if (count > Int.MAX_VALUE) throw DeviceTransferException(DeviceTransferError.BAD_MESSAGE)
        // No more than a file's worth of memory, however it is cut: phones' heaps are small. The count
        // comes with the record, so only a real file gets its whole size at once.
        var file = ByteArray(minOf(count, FIRST_ALLOCATION).toInt())
        var filled = 0
        while (filled < count) {
            val bytes = reader.next() ?: throw DeviceTransferException(DeviceTransferError.CLOSED)
            val record = TransferRecord.parseFrom(bytes)
            val part = record.filePart.data
            if (record.recordCase != TransferRecord.RecordCase.FILE_PART || filled + part.size().toLong() > count) {
                throw DeviceTransferException(DeviceTransferError.BAD_MESSAGE)
            }
            if (filled + part.size() > file.size) file = file.copyOf(count.toInt())
            part.copyTo(file, filled)
            filled += part.size()
        }
        return file
    }

    private fun background(preset: String, photo: ByteArray?): ChatBackgroundChoice? = when {
        photo != null && photo.isNotEmpty() && photo.size <= ChatBackgroundChoice.MAX_PHOTO_BYTES -> ChatBackgroundChoice.Photo(photo)
        preset.isNotEmpty() && preset.toByteArray().size <= ChatBackgroundChoice.MAX_PRESET_ID_LENGTH -> ChatBackgroundChoice.Preset(preset)
        else -> null
    }

    private fun stamp(value: Long): Long? = if (value == 0L) null else value.clampedUnsigned()

    private fun instant(value: Long): Instant? = stamp(value)?.let(Instant::ofEpochMilli)

    private companion object {
        /** Messages per transaction, when none of them brings a file. */
        const val BATCH = 200

        /** What the largest attachment of the relay takes; anything larger grows once to its size. */
        const val FIRST_ALLOCATION = 64L * 1024 * 1024
    }
}
