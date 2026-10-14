package com.syconx.us2us.runtime.engine

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.attachment.AttachmentIntegrityException
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.messenger.AccessKeyRequiredException
import com.syconx.us2us.core.messenger.IdentityChangedException
import com.syconx.us2us.core.messenger.SendNotAllowedException
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.proto.DataMessage
import com.syconx.us2us.core.relay.RelayErrorCode
import com.syconx.us2us.core.relay.RelayException
import com.syconx.us2us.runtime.storage.AttachmentState
import com.syconx.us2us.runtime.storage.OutboxEntry
import com.syconx.us2us.runtime.storage.OutboxState
import com.syconx.us2us.runtime.storage.RetryPolicy
import com.syconx.us2us.runtime.storage.StoredAttachment
import com.syconx.us2us.runtime.storage.StoredContact
import com.syconx.us2us.runtime.support.LogLabel
import com.syconx.us2us.runtime.support.RuntimeLog
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What the outbox does with an entry after a send attempt. */
internal sealed interface SendResult {
    data object Sent : SendResult

    /** Transient: back off ([retryAfterSeconds] from the relay, when it said so). */
    data class Retry(val code: String, val retryAfterSeconds: Double?) : SendResult

    /** Blocked until the user acts (changed safety number) or the token problem is solved; kept as it is. */
    data object Hold : SendResult

    /** Permanent: given up; the message shows *Failed*. [code] is a fixed category, never content. */
    data class Drop(val code: String) : SendResult, LogLabel {
        override val logLabel: String get() = code
    }

    /** Must never be sent (an unaccepted requester): removed from the queue. */
    data object Discard : SendResult
}

/** The local media of an outgoing attachment is gone and cannot be uploaded. */
internal class AttachmentMissingException : IOException("attachment missing")

internal suspend fun MessagingEngine.outboxLoop() {
    while (running && currentCoroutineContext().isActive) {
        val next = flushOutbox()
        val wait = next?.let { maxOf(200L, it.toEpochMilli() - clock.now().toEpochMilli()) } ?: 300_000L
        outboxWake.wait(minOf(wait, 300_000L).milliseconds, clock)
    }
}

/**
 * Sends what can be sent now, strictly in order per contact: only the oldest entry of each contact
 * is considered, so a retrying message never lets a later one overtake it. Returns when the next
 * entry is due.
 */
internal suspend fun MessagingEngine.flushOutbox(): Instant? {
    if (!running) return null
    val entries = try {
        read { it.outbox.all() }
    } catch (_: Exception) {
        return null
    }
    val queues = LinkedHashMap<AccountId, ArrayDeque<OutboxEntry>>()
    for (entry in entries) {
        if (entry.state == OutboxState.FAILED) continue
        queues.getOrPut(entry.recipient) { ArrayDeque() }.addLast(entry)
    }
    var nextDue: Instant? = null
    fun due(at: Instant) {
        nextDue = nextDue?.let { minOf(it, at) } ?: at
    }
    for ((recipient, queue) in queues) {
        if (!running || !currentCoroutineContext().isActive) return null
        while (running && currentCoroutineContext().isActive) {
            var head = queue.firstOrNull() ?: break
            val now = clock.now()
            if (head.state == OutboxState.WAITING_FOR_UPLOAD) {
                val wait = upload(head)
                if (wait != null) {
                    due(wait)
                    break
                }
                // Uploaded (or given up): continue with its current state.
                val updated = try {
                    read { it.outbox.get(head.id) }
                } catch (_: Exception) {
                    null
                }
                if (updated == null || updated.state != OutboxState.READY) {
                    queue.removeFirst()
                    continue
                }
                head = updated
                queue[0] = updated
            }
            if (head.nextAttemptAt.isAfter(now)) {
                due(head.nextAttemptAt)
                break
            }
            if (recipient in held) break
            val batch = mergeableReceipts(queue.toList(), now)
            // While the relay call runs, its outcome is unknown: an unsend of these entries must send a `Delete`.
            val ids = batch.second.map { it.id }.toSet()
            inFlightOutbox += ids
            val result = try {
                send(batch.first, head)
            } finally {
                inFlightOutbox -= ids
            }
            record(result, batch.second)
            if (result == SendResult.Discard) {
                repeat(batch.second.size) { queue.removeFirst() }
                continue
            }
            if (result != SendResult.Sent) {
                if (result is SendResult.Retry) {
                    try {
                        read { it.outbox.get(head.id) }
                    } catch (_: Exception) {
                        null
                    }?.let { due(it.nextAttemptAt) }
                }
                break
            }
            repeat(batch.second.size) { queue.removeFirst() }
        }
    }
    return nextDue
}

/**
 * Consecutive delivery / read / viewed receipts to one contact go out as one `ReceiptMessage`
 * (≤ 1000 ids, §8.4); anything else alone.
 */
internal fun mergeableReceipts(queue: List<OutboxEntry>, now: Instant): Pair<Content, List<OutboxEntry>> {
    val head = queue.first()
    if (!head.content.hasReceipt()) return head.content to listOf(head)
    val first = head.content.receipt
    val ids = ArrayList(first.messageIdsList)
    val entries = arrayListOf(head)
    for (entry in queue.drop(1)) {
        val next = if (entry.content.hasReceipt()) entry.content.receipt else break
        if (entry.state != OutboxState.READY || entry.nextAttemptAt.isAfter(now) || next.type != first.type ||
            ids.size + next.messageIdsCount > ContentLimits.MAX_RECEIPT_IDS
        ) {
            break
        }
        ids += next.messageIdsList
        entries += entry
    }
    return InboundRules.receipt(first.type, ids) to entries
}

private suspend fun MessagingEngine.send(content: Content, entry: OutboxEntry): SendResult {
    val contact = try {
        read { it.contacts.get(entry.recipient) }
    } catch (_: Exception) {
        null
    } ?: return SendResult.Drop("no_contact")
    if (contact.requestState == ContactState.BLOCKED) return SendResult.Drop("blocked")
    // §6.4: nothing reaches a requester before the user accepted (the ACCEPT itself is sent directly).
    if (contact.requestState == ContactState.INCOMING_REQUEST) return SendResult.Discard
    if (contact.identityChanged) {
        held += contact.accountId
        return SendResult.Hold
    }
    return try {
        backend.send(content, entry.recipient, contact.requestState, contact.theirAccessKey, ephemeral = false)
        SendResult.Sent
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        classify(e, contact)
    }
}

/** Maps a send error to what the outbox does with the entry. */
internal fun MessagingEngine.classify(error: Exception, contact: StoredContact): SendResult = when (error) {
    is SendNotAllowedException -> if (error.state == ContactState.INCOMING_REQUEST) SendResult.Discard else SendResult.Drop("blocked")
    is IdentityChangedException -> {
        // Found while sending (the bundle carried another key than the trusted one): the same
        // warning as on receive (§5) — once, not again for every message that waits behind it.
        if (held.add(contact.accountId)) {
            addNotice(SystemNotice.SafetyNumberChanged, contact.accountId, clock.now())
            emit(EngineChange.SafetyNumberChanged(contact.accountId))
            emit(EngineChange.Conversations)
        }
        SendResult.Hold
    }
    is AccessKeyRequiredException, is IllegalArgumentException -> SendResult.Drop("invalid")
    is RelayException -> when {
        error.httpStatus == 404 -> SendResult.Drop("not_found")
        error.httpStatus == 403 && error.code == RelayErrorCode.ACCESS_DENIED -> {
            if (contact.theirAccessKey != null) {
                // Their key was rotated (they blocked someone); the new one arrives end to end. Until then, proof-of-work.
                try {
                    write { it.contacts.setTheirAccessKey(null, contact.accountId) }
                } catch (_: Exception) {
                }
                SendResult.Retry("access_key_rotated", 0.0)
            } else {
                SendResult.Retry("access_denied", null)
            }
        }
        error.httpStatus == 403 -> SendResult.Retry("pow", 0.0)
        error.httpStatus == 400 || error.httpStatus == 413 || error.code == RelayErrorCode.PAYLOAD_TOO_LARGE -> SendResult.Drop("invalid")
        error.httpStatus == 401 -> SendResult.Hold
        error.httpStatus == 429 -> SendResult.Retry("rate_limited", (error.retryAfterSeconds ?: 60L).toDouble())
        else -> SendResult.Retry("network", null)
    }
    is IOException -> SendResult.Retry("network", null)
    else -> SendResult.Retry("other", null)
}

private fun MessagingEngine.record(result: SendResult, entries: List<OutboxEntry>) {
    val now = clock.now()
    val touched = HashSet<AccountId>()
    try {
        write { tx ->
            for (entry in entries) {
                when (result) {
                    SendResult.Sent -> tx.outbox.markSent(entry.id, now)
                    is SendResult.Retry -> {
                        var policy = configuration.retryPolicy
                        result.retryAfterSeconds?.let { retryAfter ->
                            // The relay said when; otherwise exponential backoff.
                            policy = RetryPolicy(
                                base = maxOf(0.5, retryAfter).seconds,
                                maximum = maxOf(0.5, minOf(retryAfter, 3600.0)).seconds,
                                jitter = 0.1,
                                maximumAge = policy.maximumAge,
                            )
                        }
                        tx.outbox.markFailed(entry.id, result.code, retryable = true, policy = policy, now = now)
                    }
                    is SendResult.Drop -> {
                        RuntimeLog.messaging.notice("send dropped", result.logLabel)
                        tx.outbox.markFailed(entry.id, result.code, retryable = false, now = now)
                    }
                    SendResult.Hold -> continue
                    SendResult.Discard -> {
                        RuntimeLog.messaging.notice("send discarded: request not accepted")
                        tx.outbox.remove(entry.id)
                    }
                }
                if (entry.messageId != null) touched += entry.recipient
            }
        }
    } catch (_: Exception) {
    }
    for (peer in touched) emit(EngineChange.Messages(peer))
    if (result == SendResult.Sent && entries.any { it.messageId != null }) emit(EngineChange.Conversations)
}

// MARK: Uploads (§7, §4.9)

/**
 * Uploads the attachments of a waiting entry, puts the pointers into its content and marks it
 * ready. Returns when to try again after a transient failure, `null` when done or given up.
 */
private suspend fun MessagingEngine.upload(entry: OutboxEntry): Instant? {
    val now = clock.now()
    uploadBackoff[entry.id]?.let { if (it.notBefore.isAfter(now)) return it.notBefore }
    val messageId = entry.messageId
    val conversationId = entry.conversationId
    if (messageId == null || conversationId == null || !entry.content.hasData()) {
        try {
            write { it.outbox.markReady(entry.id, now = now) }
        } catch (_: Exception) {
        }
        return null
    }
    var data = entry.content.data
    try {
        val attachments = read { it.attachments.forMessage(messageId, conversationId) }
        for (attachment in attachments) {
            if (attachment.state == AttachmentState.UPLOADED && attachment.descriptor?.mediaId?.isEmpty == false) continue
            val draft = attachment.descriptor
            if (!attachment.hasFile || draft == null) throw AttachmentMissingException()
            val staging = read { it.attachments.exportToStaging(attachment.id) }
            try {
                write { it.attachments.setState(AttachmentState.UPLOADING, attachment.id) }
                val sent = backend.uploadAttachment(staging, draft.kind, draft.contentType, draft.fileName.ifEmpty { null })
                // Unsent or deleted meanwhile: the blob must not stay on the relay.
                val still = try {
                    read { it.outbox.get(entry.id) }
                } catch (_: Exception) {
                    null
                }
                if (still == null) {
                    try {
                        backend.deleteUploadedMedia(sent)
                    } catch (_: IOException) {
                    }
                    uploadBackoff.remove(entry.id)
                    return null
                }
                // Keep what the composer set (dimensions, thumbnail, caption, …).
                val uploaded = sent.toBuilder()
                    .setWidth(draft.width)
                    .setHeight(draft.height)
                    .setDurationMs(draft.durationMs)
                    .setThumbnail(draft.thumbnail)
                    .setCaption(draft.caption)
                    .setWaveform(draft.waveform)
                    .apply { if (draft.fileName.isNotEmpty()) fileName = draft.fileName }
                    .build()
                write { it.attachments.setDescriptor(uploaded, attachment.id) }
                data = replaceAttachment(data, uploaded)
            } finally {
                staging.delete()
            }
        }
        val content = entry.content.toBuilder().setData(data).build()
        write { it.outbox.markReady(entry.id, content, clock.now()) }
        uploadBackoff.remove(entry.id)
        return null
    } catch (e: CancellationException) {
        throw e
    } catch (_: AttachmentMissingException) {
        giveUpUpload(entry, "attachment_missing")
        return null
    } catch (e: RelayException) {
        if (e.httpStatus == 413 || e.code == RelayErrorCode.PAYLOAD_TOO_LARGE) {
            giveUpUpload(entry, "attachment_too_large")
            return null
        }
        return backOffUpload(entry, messageId, conversationId)
    } catch (_: Exception) {
        return backOffUpload(entry, messageId, conversationId)
    }
}

private fun MessagingEngine.backOffUpload(
    entry: OutboxEntry,
    messageId: MessageId,
    conversationId: com.syconx.us2us.runtime.storage.ConversationId,
): Instant {
    try {
        write { tx ->
            for (attachment in tx.attachments.forMessage(messageId, conversationId)) {
                if (attachment.state == AttachmentState.UPLOADING) {
                    tx.attachments.setState(AttachmentState.PENDING_UPLOAD, attachment.id, countsAsAttempt = true)
                }
            }
        }
    } catch (_: Exception) {
    }
    val attempts = (uploadBackoff[entry.id]?.attempts ?: 0) + 1
    val notBefore = clock.now().plusMillis(configuration.retryPolicy.delay(attempts).inWholeMilliseconds)
    uploadBackoff[entry.id] = MessagingEngine.UploadBackoff(attempts, notBefore)
    return notBefore
}

private fun MessagingEngine.giveUpUpload(entry: OutboxEntry, code: String) {
    uploadBackoff.remove(entry.id)
    try {
        write { it.outbox.markFailed(entry.id, code, retryable = false, now = clock.now()) }
    } catch (_: Exception) {
    }
    emit(EngineChange.Messages(entry.recipient))
}

internal fun replaceAttachment(data: DataMessage, attachment: Attachment): DataMessage = when (data.payloadCase) {
    DataMessage.PayloadCase.ATTACHMENT -> data.toBuilder().setAttachment(attachment).build()
    DataMessage.PayloadCase.STICKER -> data.toBuilder().setSticker(data.sticker.toBuilder().setCustom(attachment)).build()
    else -> data
}

// MARK: Downloads (§4.9, §7)

/**
 * Downloads pending media of accepted contacts (requests keep theirs on the relay until accepted,
 * §6.4). The core checks digest and HMAC and deletes the blob from the relay once the download is
 * complete.
 */
internal fun MessagingEngine.startDownloads(force: Boolean = false) {
    if (!running || downloadsPaused || !(settings.autoDownloadMedia || force)) return
    if (downloadTask != null) {
        downloadAgain = true
        return
    }
    downloadTask = scope.launch {
        try {
            downloadLoop()
        } finally {
            downloadTask = null
        }
    }
}

private suspend fun MessagingEngine.downloadLoop() {
    do {
        downloadAgain = false
        val candidates = try {
            read { tx ->
                tx.attachments.inStates(listOf(AttachmentState.PENDING_DOWNLOAD), limit = DOWNLOAD_BATCH).mapNotNull { attachment ->
                    if (!(settings.autoDownloadMedia || attachment.id in requestedDownloads)) return@mapNotNull null
                    val conversation = tx.conversations.get(attachment.conversationId) ?: return@mapNotNull null
                    if (tx.contacts.requestState(conversation.contact) != ContactState.ACCEPTED) return@mapNotNull null
                    attachment to conversation.contact
                }
            }
        } catch (_: Exception) {
            return
        }
        for ((attachment, peer) in candidates) {
            if (!running || !currentCoroutineContext().isActive) return
            if (!download(attachment, peer)) return // network: wait for the next trigger
        }
        if (candidates.size < DOWNLOAD_BATCH && !downloadAgain) return
    } while (running && currentCoroutineContext().isActive)
}

private const val DOWNLOAD_BATCH = 50

/** Returns `false` on a transient failure (stop for now). */
private suspend fun MessagingEngine.download(attachment: StoredAttachment, peer: AccountId): Boolean {
    val descriptor = attachment.descriptor
    if (descriptor == null) {
        try {
            write { it.attachments.setState(AttachmentState.UNAVAILABLE, attachment.id) }
        } catch (_: Exception) {
        }
        return true
    }
    val staging = storage.makeStagingFile()
    val messageId = attachment.messageId
    val progress = DownloadProgressThrottle { fraction -> emit(EngineChange.DownloadProgress(messageId, peer, fraction)) }
    try {
        write { it.attachments.setState(AttachmentState.DOWNLOADING, attachment.id) }
        // The bubble shows the transfer while it runs, not only its end.
        emit(EngineChange.Messages(peer))
        backend.downloadAttachment(descriptor, staging) { progress.update(it) }
        write { it.attachments.importFile(staging, attachment.id, clock.now()) }
        requestedDownloads.remove(attachment.id)
        emit(EngineChange.Messages(peer))
        return true
    } catch (e: CancellationException) {
        throw e
    } catch (e: RelayException) {
        if (e.httpStatus == 404 || e.code == RelayErrorCode.NOT_FOUND) {
            markDownload(attachment, AttachmentState.UNAVAILABLE) // 14-day TTL or unsent
        } else {
            markDownload(attachment, AttachmentState.PENDING_DOWNLOAD, countsAsAttempt = true)
            return false
        }
    } catch (_: AttachmentIntegrityException) {
        markDownload(attachment, AttachmentState.FAILED) // integrity: never shown
    } catch (_: Exception) {
        markDownload(attachment, AttachmentState.PENDING_DOWNLOAD, countsAsAttempt = true)
        return false
    } finally {
        staging.delete()
    }
    emit(EngineChange.Messages(peer))
    return true
}

private fun MessagingEngine.markDownload(attachment: StoredAttachment, state: AttachmentState, countsAsAttempt: Boolean = false) {
    try {
        write { it.attachments.setState(state, attachment.id, countsAsAttempt = countsAsAttempt) }
    } catch (_: Exception) {
    }
}

/** Downloads one attachment now (auto-download off, or a retry). */
internal fun MessagingEngine.downloadNow(messageId: MessageId, peer: AccountId) {
    try {
        write { tx ->
            val conversation = tx.conversations.forContact(peer) ?: return@write
            for (attachment in tx.attachments.forMessage(messageId, conversation.id)) {
                if (attachment.state != AttachmentState.FAILED && attachment.state != AttachmentState.PENDING_DOWNLOAD) continue
                requestedDownloads += attachment.id
                if (attachment.state == AttachmentState.FAILED) tx.attachments.setState(AttachmentState.PENDING_DOWNLOAD, attachment.id)
            }
        }
    } catch (_: Exception) {
    }
    startDownloads(force = true)
}
