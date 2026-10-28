package com.syconx.us2us.runtime.community

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.attachment.AttachmentIntegrityException
import com.syconx.us2us.core.attachment.BlobSource
import com.syconx.us2us.core.community.CommunityContent
import com.syconx.us2us.core.community.CommunityInvite
import com.syconx.us2us.core.community.CommunityMember
import com.syconx.us2us.core.community.CommunityState
import com.syconx.us2us.core.contacts.UsernameStatus
import com.syconx.us2us.core.encoding.Base64Url
import com.syconx.us2us.core.messenger.Messenger
import com.syconx.us2us.core.messenger.OutgoingAttachment
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.CommunityMessage
import com.syconx.us2us.core.proto.CommunityReceipt
import com.syconx.us2us.core.relay.CommunityDirectory
import com.syconx.us2us.core.relay.CommunityRequest
import com.syconx.us2us.core.relay.CommunitySummary
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.relay.RelayException
import com.syconx.us2us.runtime.media.PreparedMedia
import com.syconx.us2us.runtime.storage.CommunityActivity
import com.syconx.us2us.runtime.storage.CommunityArrival
import com.syconx.us2us.runtime.storage.CommunityDeleteResult
import com.syconx.us2us.runtime.storage.CommunityDeletion
import com.syconx.us2us.runtime.storage.CommunityDelivery
import com.syconx.us2us.runtime.storage.CommunityMediaState
import com.syconx.us2us.runtime.storage.CommunityRecipient
import com.syconx.us2us.runtime.storage.CommunityRepository
import com.syconx.us2us.runtime.storage.ContactIdentity
import com.syconx.us2us.runtime.storage.LocalStorage
import com.syconx.us2us.runtime.storage.StoredCommunity
import com.syconx.us2us.runtime.storage.StoredCommunityMessage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A message of a community: its sender and its id (the dedupe key within the community). */
data class CommunityMessageKey(val community: AccountId, val sender: AccountId, val messageId: AccountId)

/**
 * An unlocked account's community engine. No personal contacts or credentials are changed.
 * Building one touches nothing: the database is first written on first use.
 *
 * Extension v2 (COMMUNITIES.md): photos, videos and files (one blob, uploaded once, its delete
 * token kept here), DELIVERY and READ receipts, and delete for everyone. [readReceipts] is the
 * user's global read-receipt setting at the moment it is asked.
 */
class CommunityEngine(
    private val messenger: Messenger,
    private val storage: LocalStorage,
    private val readReceipts: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val repository = CommunityRepository(storage)
    private val preparation = Any()

    @Volatile
    private var prepared = false

    /** Recipients whose community inbox is full, and until when (this session only). */
    private val deferredUntil = HashMap<AccountId, Long>()
    private val relay get() = messenger.communityRelay
    val accountId get() = messenger.accountId
    private val ownKey get() = Base64Url.encode(messenger.account.identityKeyPair.publicKey.serialize())
    var supported: Boolean = true
        private set

    /**
     * The last pass met a roster it could not accept: a rollback, a fork, a substituted owner or
     * member identity, or one without this account's own key. Nothing of it was applied.
     */
    @Volatile
    var securityWarning: Boolean = false
        private set

    private val progressFlow = MutableStateFlow<Map<CommunityMessageKey, Double>>(emptyMap())

    /** Downloads under way (0…1 of the blob). Memory only. */
    val downloadProgress: StateFlow<Map<CommunityMessageKey, Double>> = progressFlow.asStateFlow()
    private val running = HashMap<CommunityMessageKey, Deferred<Unit>>()

    /** Bumped whenever stored community content changes, so screens know to read it again. */
    private val revisionFlow = MutableStateFlow(0L)
    val revision: StateFlow<Long> = revisionFlow.asStateFlow()

    fun communities(): List<StoredCommunity> = prepare().let { repository.all() }
    fun messages(id: AccountId): List<StoredCommunityMessage> = prepare().let { repository.messages(id) }
    fun history(id: AccountId, before: com.syconx.us2us.runtime.storage.CommunityHistoryCursor? = null,
        through: com.syconx.us2us.runtime.storage.CommunityHistoryCursor? = null) =
        prepare().let { repository.history(id, before, through) }
    fun message(key: CommunityMessageKey): StoredCommunityMessage? = prepare().let { repository.message(key.community, key.sender, key.messageId) }

    /** Every joined or ended community's newest text or media message and unread count (the chat list). */
    fun activity(): List<CommunityActivity> = prepare().let { repository.activity() }

    /** Who an own message went to: each recipient's transport state, delivered and read times. */
    fun recipients(id: AccountId, messageId: AccountId): List<CommunityRecipient> = prepare().let { repository.recipients(id, messageId) }

    /**
     * The verified username of each member who is also a contact with the very identity key the
     * roster lists; anyone else stays unnamed.
     */
    fun memberNames(state: CommunityState): Map<AccountId, String> = memberNames(state.members)

    /** As [memberNames], for any members (also ones a newer roster no longer lists). */
    fun memberNames(members: List<CommunityMember>): Map<AccountId, String> {
        val names = HashMap<AccountId, String>()
        for (member in members) {
            if (member.accountId == accountId) continue
            val contact = storage.read { it.contacts.get(member.accountId) } ?: continue
            val username = contact.profile.username?.takeIf { contact.profile.usernameStatus == UsernameStatus.VERIFIED } ?: continue
            val trust = storage.protocolStorage.identityTrust(member.accountId)
            if (trust is ContactIdentity.Trusted && trust.key.serialize().contentEquals(member.keyBytes())) names[member.accountId] = username.value
        }
        return names
    }

    suspend fun search(query: String, after: AccountId? = null): CommunityDirectory {
        // The relay applies the name rule to queries: what it would refuse is never sent.
        val prefix = requireNotNull(CommunityState.searchPrefix(query)) { "invalid search query" }
        return relay.communityDirectory(prefix, after).also { result ->
            require(result.communities.size <= 20)
            result.communities.forEach { require(it.visibility == "public" && CommunityState.validName(it.name) && it.memberCount in 1..100); CommunityMember(it.ownerAccountId, it.ownerIdentityKey).keyBytes() }
        }
    }

    suspend fun create(name: String, isPublic: Boolean): AccountId = mutex.withLock {
        prepare()
        val normalized = requireNotNull(CommunityState.normalizedName(name)) { "invalid community name" }
        val id = AccountId(UUID.randomUUID())
        val state = CommunityState(id, 1, if (isPublic) "public" else "private", accountId, ownKey, if (isPublic) normalized else "", listOf(CommunityMember(accountId, ownKey)))
        val signed = messenger.signCommunityState(state)
        repository.pin(id, ownKey, normalized)
        val saved = relay.createCommunity(signed)
        require(saved.verify(ownKey).encode().contentEquals(state.encode()))
        repository.save(saved)
        changed()
        id
    }

    suspend fun request(summary: CommunitySummary) = mutex.withLock {
        prepare()
        require(summary.visibility == "public" && CommunityState.validName(summary.name))
        CommunityMember(summary.ownerAccountId, summary.ownerIdentityKey).keyBytes()
        repository.pin(summary.id, summary.ownerIdentityKey, summary.name)
        relay.requestCommunity(summary.id)
    }

    suspend fun redeem(code: String): AccountId = mutex.withLock {
        prepare()
        val invite = CommunityInvite.parse(code.trim())
        repository.pin(invite.communityId, invite.ownerIdentityKey, invite.name)
        val result = relay.redeemCommunityInvite(invite.communityId, invite.token)
        require(result.id == invite.communityId && result.ownerIdentityKey == invite.ownerIdentityKey)
        invite.communityId
    }

    suspend fun requests(id: AccountId): List<CommunityRequest> = mutex.withLock {
        prepare()
        require(current(id).ownerAccountId == accountId)
        relay.communityRequests(id).requests.also { list -> require(list.size <= 100); list.forEach { CommunityMember(it.accountId, it.identityKey).keyBytes() } }
    }

    /**
     * Admits a pending request. A member who left stays listed in the signed roster, and the relay
     * admits only accounts new to the roster (M-11): such a request is a re-admission, done as two
     * signed states, first without the account, then with it (its request stays valid in between).
     */
    suspend fun approve(id: AccountId, request: CommunityRequest) = mutex.withLock {
        prepare()
        var state = current(id)
        require(state.ownerAccountId == accountId && request.accountId != accountId)
        val pending = relay.communityRequests(id).requests.single { it.accountId == request.accountId }
        require(pending.identityKey == request.identityKey) { "request identity changed; review again" }
        val added = CommunityMember(pending.accountId, pending.identityKey).also { it.keyBytes() }
        if (state.member(pending.accountId) != null) {
            state = state.copy(revision = state.revision + 1, members = state.members.filter { it.accountId != pending.accountId })
            update(state)
        }
        require(state.members.size < 100)
        update(state.copy(revision = state.revision + 1, members = (state.members + added).sortedBy { it.accountId.toString() }))
    }

    suspend fun reject(id: AccountId, account: AccountId) = mutex.withLock { prepare(); relay.rejectCommunityRequest(id, account) }

    /** Owner: rejects every pending request at once (M-14); one by one on a relay without the bulk route. */
    suspend fun rejectAll(id: AccountId) = mutex.withLock {
        prepare()
        require(current(id).ownerAccountId == accountId)
        try {
            relay.rejectAllCommunityRequests(id)
        } catch (e: RelayException) {
            if (e.httpStatus != HTTP_NOT_FOUND && e.httpStatus != HTTP_METHOD_NOT_ALLOWED) throw e
            for (pending in relay.communityRequests(id).requests.take(100)) {
                try {
                    relay.rejectCommunityRequest(id, pending.accountId)
                } catch (gone: RelayException) {
                    if (gone.httpStatus != HTTP_NOT_FOUND) throw gone
                }
            }
        }
    }
    suspend fun removeMember(id: AccountId, member: AccountId) = mutex.withLock {
        prepare()
        val state = current(id)
        require(state.ownerAccountId == accountId && member != accountId && state.member(member) != null)
        update(state.copy(revision = state.revision + 1, members = state.members.filter { it.accountId != member }))
    }
    suspend fun invite(id: AccountId): String = mutex.withLock {
        prepare()
        val state = current(id)
        require(state.ownerAccountId == accountId)
        val created = relay.createCommunityInvite(id)
        CommunityInvite(id, created.token, state.ownerIdentityKey, repository.get(id)!!.name).code()
    }
    suspend fun revokeInvites(id: AccountId) = mutex.withLock { prepare(); relay.revokeCommunityInvites(id) }
    suspend fun delete(id: AccountId) = mutex.withLock {
        prepare()
        relay.deleteCommunity(id)
        repository.remove(id)
        changed()
    }

    /** A member leaves; the community, its history, its files and its key are then forgotten on this phone. The owner deletes instead. */
    suspend fun leave(id: AccountId) = mutex.withLock {
        prepare()
        val known = repository.get(id) ?: return@withLock
        require(known.signed == null || known.state?.ownerAccountId != accountId) { "the owner deletes the community instead" }
        try {
            relay.leaveCommunity(id)
        } catch (e: RelayException) {
            // Not a member (any more): nothing left to leave on the relay.
            if (e.httpStatus != 404) throw e
        }
        repository.remove(id)
        changed()
    }

    /** Withdraws this account's pending request (idempotent on the relay), then forgets the community here. */
    suspend fun withdraw(id: AccountId) = mutex.withLock {
        prepare()
        try {
            relay.withdrawCommunityRequest(id)
        } catch (e: RelayException) {
            if (e.httpStatus != 404) throw e
        }
        repository.remove(id)
        changed()
    }

    /** Forgets an ended membership on this phone only: rows, files, queued sends and the key slot. */
    suspend fun forget(id: AccountId) = mutex.withLock {
        prepare()
        val known = repository.get(id) ?: return@withLock
        require(!known.active) { "a current membership is left, not forgotten" }
        repository.remove(id)
        changed()
    }

    // MARK: Sending

    /**
     * A text message: stored at once (so it shows, and a failure can be retried), then encrypted
     * separately for every other member of the current roster, every ciphertext persisted before
     * the first is sent.
     */
    suspend fun send(id: AccountId, text: String): AccountId = mutex.withLock {
        prepare()
        require(CommunityContent.isSendableText(text)) { "invalid community text" }
        requireNotNull(repository.get(id)?.takeIf { it.active }) { "not a current member" }
        val messageId = AccountId(UUID.randomUUID())
        val sentAt = clock()
        repository.addOwn(StoredCommunityMessage(id, accountId, messageId, sentAt, text, "preparing"), emptyList())
        changed()
        fanoutCurrent(id, messageId) { revision -> CommunityContent.text(id, revision, messageId, sentAt, text) }
        messageId
    }

    suspend fun sendSticker(id: AccountId, sticker: com.syconx.us2us.core.proto.Sticker): AccountId = mutex.withLock {
        prepare()
        requireNotNull(repository.get(id)?.takeIf { it.active }) { "not a current member" }
        val messageId = AccountId(UUID.randomUUID())
        val sentAt = clock()
        require(CommunityContent.kind(CommunityContent.sticker(id, 1, messageId, sentAt, sticker)) == CommunityContent.Kind.STICKER)
        repository.addOwn(StoredCommunityMessage(id, accountId, messageId, sentAt, sticker.emoji, "preparing", sticker = sticker), emptyList())
        changed()
        fanoutCurrent(id, messageId) { revision -> CommunityContent.sticker(id, revision, messageId, sentAt, sticker) }
        messageId
    }

    /**
     * A photo, video or file: kept here as it is sent (no re-download), encrypted once and
     * uploaded once with `POST /v1/media`, then the pointer (without its delete token, which stays
     * on this phone) goes to every member, the caption as `text`. A failed upload fails the
     * message; nothing is sent. [media]'s staged file is consumed.
     */
    suspend fun sendMedia(id: AccountId, media: PreparedMedia, caption: String? = null, messageId: AccountId = AccountId(UUID.randomUUID())): AccountId {
        val sentAt = clock()
        val text = CommunityContent.caption(caption ?: media.caption)
        val pending = try {
            mutex.withLock {
                prepare()
                require(media.kind == Attachment.Kind.IMAGE || media.kind == Attachment.Kind.VIDEO || media.kind == Attachment.Kind.FILE || media.kind == Attachment.Kind.VOICE) { "not a community attachment" }
                requireNotNull(repository.get(id)?.takeIf { it.active }) { "not a current member" }
                if (media.kind == Attachment.Kind.VOICE) {
                    require(media.contentType == "audio/mp4" && media.size in 1..16L * 1024 * 1024 &&
                        media.durationMs in 1000..300000 && (media.waveform?.size() ?: 0) <= 100 && text.isEmpty() &&
                        media.width == 0 && media.height == 0 && (media.thumbnail?.isEmpty != false) && media.fileName.isNullOrEmpty())
                }
                val pointer = pendingPointer(media)
                val bytes = media.file.readBytes()
                repository.addOwn(
                    StoredCommunityMessage(id, accountId, messageId, sentAt, text, "preparing", attachment = pointer, media = CommunityMediaState.LOCAL),
                    emptyList(), file = bytes,
                )
                changed()
                pointer
            }
        } catch (e: Throwable) {
            media.file.delete()
            throw e
        }
        try {
            upload(id, messageId, pending, BlobSource { media.file.inputStream() })
        } finally {
            media.file.delete()
        }
        mutex.withLock { fanoutCurrent(id, messageId) { revision -> mediaContent(id, revision, messageId) } }
        return messageId
    }

    /**
     * "Tekrar gönder" for an own message that failed or went stale: uploaded again if its blob
     * never reached the relay, then sent to the recipients that have not got it, recomputed
     * against the current roster (members gone since are skipped). The blob is reused.
     */
    suspend fun resend(id: AccountId, messageId: AccountId) {
        prepare()
        val message = repository.message(id, accountId, messageId) ?: return
        if (message.isDeleted || message.status !in setOf("failed", "stale", "partial")) return
        val pointer = message.attachment
        if (pointer != null && pointer.mediaId.size() != MEDIA_ID_BYTES) {
            val file = repository.readFile(id, accountId, messageId) ?: return
            mutex.withLock { repository.mark(id, messageId, "preparing", clearOutbox = true) }
            changed()
            upload(id, messageId, pointer, BlobSource { file.inputStream() })
        }
        mutex.withLock {
            val current = repository.message(id, accountId, messageId) ?: return@withLock
            if (current.isDeleted) return@withLock
            val build: (Long) -> CommunityMessage = { revision ->
                if (current.isMedia) mediaContent(id, revision, messageId) else if (current.sticker != null) CommunityContent.sticker(id, revision, messageId, current.sentAt, current.sticker) else CommunityContent.text(id, revision, messageId, current.sentAt, current.text)
            }
            val already = repository.recipients(id, messageId)
            val done = already.filter { it.transport == CommunityRecipient.ACCEPTED || it.transport == CommunityRecipient.SKIPPED }.map { it.member }.toSet()
            repository.mark(id, messageId, "preparing", clearOutbox = true)
            fanoutCurrent(id, messageId, exclude = done, fixed = already.map { it.member }.takeIf { it.isNotEmpty() }?.toSet(), build = build)
        }
    }

    /**
     * Delete for everyone: by its sender, or by the owner (moderation). This phone's copy becomes
     * the placeholder at once and no envelope of the target leaves any more; the delete goes to
     * every other member of the roster; an own blob is deleted with its token.
     */
    suspend fun deleteForEveryone(id: AccountId, sender: AccountId, messageId: AccountId) = mutex.withLock {
        prepare()
        val state = requireNotNull(storedState(id)) { "unknown community" }
        require(sender == accountId || state.ownerAccountId == accountId) { "only the sender or the owner deletes for everyone" }
        require(state.member(accountId) != null && repository.get(id)?.active == true) { "not a current member" }
        val by = if (sender == accountId) CommunityDeletion.SENDER else CommunityDeletion.OWNER
        val result = repository.applyDelete(id, sender, messageId, by, clock())
        cancelDownload(CommunityMessageKey(id, sender, messageId))
        changed()
        if (result.outcome == CommunityDeleteResult.Outcome.REMEMBERED) return@withLock
        val eventId = AccountId(UUID.randomUUID())
        val event = CommunityContent.delete(id, state.revision, eventId, clock(), messageId, sender)
        try {
            enqueueEvent(state, eventId, others(state), event)
        } finally {
            result.ownBlob?.let { deleteBlob(id, messageId, it) }
            flushQuietly()
        }
    }

    /** "Delete for me": this phone only. The message, its file and receipt state go; nothing is sent; no blob is deleted. */
    fun deleteForMe(id: AccountId, sender: AccountId, messageId: AccountId) {
        prepare()
        if (repository.deleteForMe(id, sender, messageId, clock())) {
            cancelDownload(CommunityMessageKey(id, sender, messageId))
            changed()
        }
    }

    /**
     * Incoming messages displayed in the open community conversation. READ is queued once per
     * message, for others' text and media messages that are not deleted, only while read receipts
     * are on; nothing is sent later for what was read while they were off.
     */
    fun markDisplayed(id: AccountId, keys: Collection<Pair<AccountId, AccountId>>): Boolean {
        prepare()
        if (keys.isEmpty()) return false
        val shown = repository.markDisplayed(id, keys, clock())
        if (shown.isEmpty()) return false
        if (readReceipts()) {
            for ((sender, ids) in shown.groupBy({ it.first }, { it.second })) repository.queueReceipts(id, sender, CommunityReceipt.Type.READ, ids, clock())
        }
        changed()
        return true
    }

    /** Sends what is queued (READ coalesced over a few seconds by the caller), then the outbox. */
    suspend fun flushReceipts() = mutex.withLock {
        prepare()
        sendQueuedReceipts()
        flushQuietly()
    }

    // MARK: Media

    /** The decrypted file of a media message that is here, in memory only. */
    fun mediaData(id: AccountId, sender: AccountId, messageId: AccountId): ByteArray? = prepare().let { repository.readFile(id, sender, messageId) }

    /**
     * Downloads a received photo, video or file: `GET /v1/media/{id}` stopped at the blob size its
     * declared size allows, digest and MAC verified, decrypted into memory and stored under the
     * community key. A `404` or a failed check leaves it "no longer available"; a network failure
     * can be tried again. The blob is never deleted by a recipient.
     */
    suspend fun download(id: AccountId, sender: AccountId, messageId: AccountId) {
        prepare()
        val key = CommunityMessageKey(id, sender, messageId)
        val job = coroutineScope {
            val started = synchronized(running) {
                running[key]?.let { return@coroutineScope it }
                async { fetch(key) }.also { running[key] = it }
            }
            started
        }
        try {
            job.await()
        } catch (e: CancellationException) {
            // Cancelled by a delete: the download just ends.
            currentCoroutineContext().ensureActive()
        } finally {
            synchronized(running) { if (running[key] === job) running.remove(key) }
        }
    }

    private suspend fun fetch(key: CommunityMessageKey) {
        val message = repository.message(key.community, key.sender, key.messageId) ?: return
        val pointer = message.attachment ?: return
        if (message.isDeleted || message.media == CommunityMediaState.LOCAL || key.sender == accountId) return
        progressFlow.update { it + (key to 0.0) }
        try {
            val plaintext = ByteArrayOutputStream()
            messenger.downloadAttachment(pointer, plaintext, deleteFromRelay = false) { fraction ->
                progressFlow.update { current -> if (key in current) current + (key to fraction) else current }
            }
            val bytes = plaintext.toByteArray()
            try {
                repository.storeFile(key.community, key.sender, key.messageId, bytes)
            } finally {
                bytes.fill(0)
            }
        } catch (e: RelayException) {
            if (e.httpStatus == 404) repository.setMedia(key.community, key.sender, key.messageId, CommunityMediaState.UNAVAILABLE) else throw e
        } catch (_: AttachmentIntegrityException) {
            repository.setMedia(key.community, key.sender, key.messageId, CommunityMediaState.UNAVAILABLE)
        } finally {
            progressFlow.update { it - key }
            changed()
        }
    }

    private fun cancelDownload(key: CommunityMessageKey) {
        synchronized(running) { running.remove(key) }?.cancel()
        progressFlow.update { it - key }
    }

    // MARK: Sync

    /** Invoked only while unlocked. An old relay disables just this extension. */
    suspend fun sync() = mutex.withLock {
        prepare()
        securityWarning = false
        val mine = try { relay.myCommunities() } catch (e: RelayException) {
            if (e.httpStatus == 404 || e.httpStatus == 405) { supported = false; return@withLock }
            throw e
        }
        require(mine.communities.size <= 50)
        supported = true
        val active = HashSet<AccountId>()
        var malformedState = false
        for (signed in mine.communities) {
            val decoded = try { signed.verify() } catch (_: IllegalArgumentException) { malformedState = true; securityWarning = true; continue }
            // Restored clients must recover owner pins by discovery or a new private invitation.
            val known = repository.get(decoded.id)
            if (known == null && !(decoded.ownerAccountId == accountId && decoded.ownerIdentityKey == ownKey)) continue
            if (decoded.member(accountId)?.identityKey != ownKey) { malformedState = true; securityWarning = true; continue }
            // A rollback, fork or substituted identity against the pinned state is refused, never applied.
            try { repository.save(signed) } catch (_: IllegalArgumentException) { malformedState = true; securityWarning = true; continue }
            active += decoded.id
        }
        // A malformed response cannot force valid locally pinned memberships to disappear.
        if (!malformedState) repository.deactivateMissing(active)
        // The outbox never holds up the inbox: what fails here is retried on the next pass.
        flushQuietly()
        // A page may be empty and still have more behind it; the rest waits for the next pass.
        var pages = 0
        while (pages++ < MAX_INBOX_PAGES && receive()) currentCoroutineContext().ensureActive()
        // The pass is over: DELIVERY per community and original sender, then what is still queued.
        quietly { sendQueuedReceipts() }
        flushQuietly()
        quietly { deletePendingBlobs() }
        quietly { repository.prune(clock()) }
        changed()
    }

    /** One inbox page, acknowledged; whether the relay has more. */
    private suspend fun receive(): Boolean {
        val inbox = relay.communityInbox()
        require(inbox.envelopes.size <= 100 && inbox.envelopes.sumOf { it.envelope.size.toLong() } <= 1048576)
        val acknowledgements = ArrayList<String>()
        for (entry in inbox.envelopes) {
            currentCoroutineContext().ensureActive()
            if (repository.wasProcessed(entry.id)) { acknowledgements += entry.id; continue }
            val known = repository.get(entry.communityId)
            if (known?.active != true) { acknowledgements += entry.id; continue }
            val state = try { current(entry.communityId) } catch (_: IllegalArgumentException) {
                securityWarning = true
                repository.processed(entry.id)
                acknowledgements += entry.id
                continue
            } catch (e: RelayException) {
                // Gone, or no longer ours to read: this record can never be opened, and must not block the ones behind it.
                if (e.httpStatus != 403 && e.httpStatus != 404) throw e
                repository.processed(entry.id)
                acknowledgements += entry.id
                continue
            }
            if (entry.revision != state.revision || entry.envelope.size !in 1..RelayClient.MAX_COMMUNITY_ENVELOPE_BYTES) { acknowledgements += entry.id; continue }
            var deleted: CommunityMessageKey? = null
            storage.protocolStorage.withStagedInboundWrites {
                try {
                    val decoded = messenger.decryptCommunityEnvelope(entry.envelope, state)
                    storage.write {
                        if (decoded != null) deleted = apply(state, decoded.first, decoded.second)
                        repository.processed(entry.id)
                    }
                } catch (_: IllegalArgumentException) {
                    // Malformed/unauthorised payloads are poison records, not a retryable disk/network failure.
                    securityWarning = true
                    storage.protocolStorage.discardStagedInboundWrites()
                    repository.processed(entry.id)
                } catch (e: Throwable) {
                    storage.protocolStorage.discardStagedInboundWrites()
                    throw e
                }
            }
            deleted?.let(::cancelDownload)
            acknowledgements += entry.id
        }
        relay.acknowledgeCommunityEnvelopes(acknowledgements)
        if (inbox.envelopes.isNotEmpty()) changed()
        return inbox.more
    }

    /**
     * One validated message from [sender] (a current member other than this account, checked by
     * the core), inside the receive transaction. Returns the key of a message a delete removed.
     */
    private fun apply(state: CommunityState, sender: AccountId, message: CommunityMessage): CommunityMessageKey? {
        val now = clock()
        when (CommunityContent.kind(message)) {
            CommunityContent.Kind.TEXT, CommunityContent.Kind.MEDIA, CommunityContent.Kind.STICKER -> {
                val messageId = AccountId.fromBytes(message.messageId.toByteArray())
                val stored = StoredCommunityMessage(
                    state.id, sender, messageId, message.sentAt, message.text, "received",
                    attachment = if (message.hasAttachment()) message.attachment else null,
                    sticker = if (message.hasSticker()) message.sticker else null,
                )
                if (repository.addReceived(stored, now) != CommunityArrival.DUPLICATE) {
                    repository.queueReceipts(state.id, sender, CommunityReceipt.Type.DELIVERY, listOf(messageId), now)
                }
            }
            CommunityContent.Kind.RECEIPT -> {
                val ids = CommunityContent.distinctIds(message.receipt).map { AccountId.fromBytes(it.toByteArray()) }
                repository.applyReceipt(state.id, sender, message.receipt.type, ids, now)
            }
            CommunityContent.Kind.DELETE -> {
                val targetSender = AccountId.fromBytes(message.deleteTargetSenderId.toByteArray())
                val target = AccountId.fromBytes(message.deleteTargetMessageId.toByteArray())
                // Only its sender, or the owner of the current roster (moderation); anything else is dropped.
                val by = when {
                    targetSender == sender -> CommunityDeletion.SENDER
                    sender == state.ownerAccountId -> CommunityDeletion.OWNER
                    else -> return null
                }
                val result = repository.applyDelete(state.id, targetSender, target, by, now)
                if (result.outcome == CommunityDeleteResult.Outcome.APPLIED) return CommunityMessageKey(state.id, targetSender, target)
            }
            null -> Unit
        }
        return null
    }

    // MARK: Internals

    private fun prepare() {
        if (prepared) return
        synchronized(preparation) {
            if (prepared) return
            // A send cut off before its ciphertexts were queued never went anywhere.
            repository.recoverInterruptedPreparations()
            prepared = true
        }
    }

    private fun changed() = revisionFlow.update { it + 1 }

    private fun storedState(id: AccountId): CommunityState? = try {
        repository.get(id)?.state
    } catch (_: IllegalArgumentException) {
        securityWarning = true
        null
    }

    private fun others(state: CommunityState): List<CommunityMember> = state.members.filter { it.accountId != accountId }

    private suspend fun current(id: AccountId): CommunityState {
        require(repository.get(id) != null) { "unknown community owner; discover or redeem an invitation first" }
        val signed = relay.community(id)
        val decoded = signed.verify()
        require(decoded.id == id)
        require(decoded.member(accountId)?.identityKey == ownKey) { "not a current member" }
        return repository.save(signed)
    }
    private suspend fun update(state: CommunityState) {
        val signed = messenger.signCommunityState(state)
        val result = relay.updateCommunity(state.id, signed)
        require(result.verify(state.ownerIdentityKey).encode().contentEquals(state.encode()))
        repository.save(result)
        changed()
    }

    /**
     * Fetches the current roster and encrypts the message for every other member (or, retried, for
     * those of [fixed] still listed and not in [exclude]); the recipient set is recorded first.
     * A failure leaves the message `failed`, to be sent again.
     */
    private suspend fun fanoutCurrent(
        id: AccountId,
        messageId: AccountId,
        exclude: Set<AccountId> = emptySet(),
        fixed: Set<AccountId>? = null,
        build: (Long) -> CommunityMessage,
    ) {
        try {
            val state = current(id)
            val targets = others(state).filter { (fixed == null || it.accountId in fixed) && it.accountId !in exclude }
            val gone = fixed.orEmpty() - exclude - targets.map { it.accountId }.toSet()
            val message = build(state.revision)
            // A bounded 99-envelope batch; no delivery until every ciphertext is durable.
            val deliveries = targets.map { member ->
                currentCoroutineContext().ensureActive()
                CommunityDelivery(id, messageId, member.accountId, state.revision, messenger.encryptCommunityMessage(member, message))
            }
            storage.write {
                repository.setRecipients(id, messageId, targets.map { it.accountId })
                for (member in targets) repository.markRecipient(id, messageId, member.accountId, CommunityRecipient.PENDING)
                for (member in gone) repository.markRecipient(id, messageId, member, CommunityRecipient.SKIPPED)
                deliveries.forEach(repository::enqueue)
                repository.mark(id, messageId, if (deliveries.isEmpty()) "sent" else "pending")
            }
        } catch (e: Throwable) {
            repository.mark(id, messageId, "failed", clearOutbox = true)
            changed()
            throw e
        }
        changed()
        // Every ciphertext is queued: a relay that cannot take them now gets them on a later pass.
        flushQuietly()
    }

    /** A receipt or delete, encrypted for [recipients] and queued with its content (re-encrypted under a newer revision if needed). */
    private suspend fun enqueueEvent(state: CommunityState, eventId: AccountId, recipients: List<CommunityMember>, event: CommunityMessage) {
        val deliveries = recipients.map { member ->
            currentCoroutineContext().ensureActive()
            CommunityDelivery(state.id, eventId, member.accountId, state.revision, messenger.encryptCommunityMessage(member, event), event)
        }
        storage.write { deliveries.forEach(repository::enqueue) }
    }

    private fun mediaContent(id: AccountId, revision: Long, messageId: AccountId): CommunityMessage {
        val message = requireNotNull(repository.message(id, accountId, messageId)) { "message gone" }
        val pointer = requireNotNull(message.attachment) { "not a media message" }
        require(pointer.mediaId.size() == MEDIA_ID_BYTES) { "not uploaded" }
        return CommunityContent.media(id, revision, messageId, message.sentAt, pointer, message.text)
    }

    /** The pointer before the upload: no relay fields yet, a thumbnail of at most 10 KiB, a file name only for files. */
    private fun pendingPointer(media: PreparedMedia): Attachment {
        val thumbnail = media.thumbnail?.takeIf { it.size() <= CommunityContent.MAX_THUMBNAIL_BYTES } ?: ByteString.EMPTY
        val contentType = media.contentType.takeIf { it.toByteArray(Charsets.UTF_8).size <= CommunityContent.MAX_NAME_BYTES } ?: "application/octet-stream"
        val fileName = if (media.kind == Attachment.Kind.FILE) boundedName(media.fileName.orEmpty()) else ""
        return Attachment.newBuilder()
            .setKind(media.kind)
            .setContentType(contentType)
            .setFileName(fileName)
            .setSize(media.size)
            .setWidth(media.width)
            .setHeight(media.height)
            .setDurationMs(media.durationMs)
            .setThumbnail(thumbnail)
            .setWaveform(if (media.kind == Attachment.Kind.VOICE) media.waveform ?: ByteString.EMPTY else ByteString.EMPTY)
            .build()
    }

    /** Uploads the blob once (outside the engine lock); a failure fails the message. */
    private suspend fun upload(id: AccountId, messageId: AccountId, pointer: Attachment, source: BlobSource) {
        val uploaded = try {
            messenger.uploadAttachment(
                OutgoingAttachment(
                    kind = pointer.kind,
                    contentType = pointer.contentType,
                    source = source,
                    fileName = pointer.fileName,
                    width = pointer.width,
                    height = pointer.height,
                    durationMs = pointer.durationMs,
                    waveform = pointer.waveform.takeIf { !it.isEmpty }?.toByteArray(),
                    thumbnail = pointer.thumbnail.takeIf { !it.isEmpty }?.toByteArray(),
                ),
            )
        } catch (e: Throwable) {
            repository.mark(id, messageId, "failed")
            changed()
            throw e
        }
        repository.uploaded(id, messageId, CommunityContent.pointer(uploaded), uploaded.deleteToken.toByteArray(), clock())
    }

    /** `DELETE /v1/media/{id}` with the token; `204` and `404` are final, a network failure is tried again on a later pass. */
    private suspend fun deleteBlob(id: AccountId, messageId: AccountId, blob: Pair<ByteArray, ByteArray>) {
        try {
            messenger.deleteCommunityMedia(blob.first, blob.second)
            repository.forgetToken(id, messageId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayException) {
            // Refused for good (a bad token): nothing more can be done; the relay's TTL removes it.
            if (e.httpStatus in 400..499 && e.httpStatus != 429) repository.forgetToken(id, messageId)
        } catch (_: IOException) {
        }
    }

    private suspend fun deletePendingBlobs() {
        for ((id, messageId, blob) in repository.blobsToDelete()) {
            currentCoroutineContext().ensureActive()
            deleteBlob(id, messageId, blob)
        }
    }

    /** Queued receipts become envelopes to their original sender: one per community, sender and kind, at most 1000 ids each. */
    private suspend fun sendQueuedReceipts() {
        for ((group, ids) in repository.queuedReceipts()) {
            val (id, recipient, type) = group
            val known = repository.get(id)
            val state = storedState(id)
            val member = state?.member(recipient)
            if (known?.active != true || state == null || member == null || recipient == accountId) {
                // No longer a member (or no longer ours): the receipt is dropped.
                repository.dequeueReceipts(id, recipient, type, ids)
                continue
            }
            for (chunk in ids.chunked(CommunityContent.MAX_RECEIPT_IDS)) {
                currentCoroutineContext().ensureActive()
                val eventId = AccountId(UUID.randomUUID())
                val event = CommunityContent.receipt(id, state.revision, eventId, clock(), type, chunk)
                val envelope = try {
                    messenger.encryptCommunityMessage(member, event)
                } catch (_: IllegalArgumentException) {
                    // The member's identity no longer matches the roster: nothing goes to it.
                    repository.dequeueReceipts(id, recipient, type, chunk)
                    continue
                }
                storage.write {
                    repository.enqueue(CommunityDelivery(id, eventId, recipient, state.revision, envelope, event))
                    repository.dequeueReceipts(id, recipient, type, chunk)
                }
            }
        }
    }

    private suspend inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun flushQuietly() = quietly { flush() }

    /**
     * One pass over the outbox. A full recipient inbox (`429`) only defers that recipient, until its
     * `Retry-After`; a recipient no longer in the community (`404`) is skipped and the roster
     * fetched again; any other refusal of one recipient (`4xx`, an envelope too large) fails that
     * recipient alone. Only a changed roster (another revision, `409`) stops the whole message.
     * Receipts and deletes carry their content: under a newer roster they are encrypted again for
     * a recipient that is still a member, and dropped otherwise.
     */
    private suspend fun flush() {
        val stopped = HashSet<Pair<AccountId, AccountId>>()
        val gone = HashSet<AccountId>()
        val departed = HashMap<AccountId, MutableSet<AccountId>>()
        val now = System.currentTimeMillis()
        deferredUntil.values.removeAll { it <= now }
        for (queued in repository.pending()) {
            var delivery = queued
            if (delivery.communityId to delivery.messageId in stopped || delivery.recipient in deferredUntil) continue
            currentCoroutineContext().ensureActive()
            val state = storedState(delivery.communityId)
            val event = delivery.payload
            if (state == null || state.revision != delivery.revision || state.member(delivery.recipient) == null) {
                val member = state?.member(delivery.recipient)
                if (event == null) {
                    repository.mark(delivery.communityId, delivery.messageId, "stale", clearOutbox = true)
                    stopped += delivery.communityId to delivery.messageId
                    continue
                }
                if (state == null || member == null || repository.get(delivery.communityId)?.active != true) {
                    repository.drop(delivery)
                    continue
                }
                val refreshed = try {
                    messenger.encryptCommunityMessage(member, CommunityContent.withRevision(event, state.revision))
                } catch (_: IllegalArgumentException) {
                    repository.drop(delivery)
                    continue
                }
                delivery = delivery.copy(revision = state.revision, envelope = refreshed)
                repository.replace(delivery)
            }
            if (delivery.envelope.size !in 1..RelayClient.MAX_COMMUNITY_ENVELOPE_BYTES) {
                if (event != null) repository.drop(delivery) else repository.failed(delivery)
                continue
            }
            try {
                relay.sendCommunityEnvelope(delivery.communityId, delivery.recipient, delivery.messageId, delivery.revision, delivery.envelope)
                repository.delivered(delivery)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalArgumentException) {
                if (event != null) repository.drop(delivery) else repository.failed(delivery)
            } catch (e: RelayException) {
                when {
                    e.httpStatus == HTTP_CONFLICT && event != null -> {
                        // Refreshed and encrypted again on the next pass.
                        stopped += delivery.communityId to delivery.messageId
                        gone += delivery.communityId
                    }
                    e.httpStatus == HTTP_CONFLICT -> {
                        repository.mark(delivery.communityId, delivery.messageId, "stale", clearOutbox = true)
                        stopped += delivery.communityId to delivery.messageId
                    }
                    e.httpStatus == HTTP_TOO_MANY_REQUESTS ->
                        deferredUntil[delivery.recipient] = System.currentTimeMillis() + 1000 * (e.retryAfterSeconds ?: 0).coerceIn(0, MAX_RETRY_AFTER_SECONDS)
                    e.httpStatus == HTTP_NOT_FOUND -> {
                        // Left, removed or deleted: there is no one to deliver to any more.
                        if (event != null) repository.drop(delivery) else repository.skipped(delivery)
                        gone += delivery.communityId
                        departed.getOrPut(delivery.communityId) { HashSet() } += delivery.recipient
                    }
                    e.httpStatus in 400..499 -> if (event != null) repository.drop(delivery) else repository.failed(delivery)
                    else -> throw e
                }
            }
        }
        changed()
        for (id in gone) {
            try {
                val state = current(id)
                // M-11: a member who left stays listed until the owner signs a roster without it;
                // the owner's phone does that as soon as a delivery to it is refused as gone.
                val leavers = departed[id].orEmpty().filter { it != accountId && state.member(it) != null }
                if (state.ownerAccountId == accountId && leavers.isNotEmpty()) {
                    update(state.copy(revision = state.revision + 1, members = state.members.filter { it.accountId !in leavers }))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalArgumentException) {
                securityWarning = true
            } catch (_: IOException) {
            }
        }
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
        const val HTTP_METHOD_NOT_ALLOWED = 405
        const val HTTP_CONFLICT = 409
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val MAX_RETRY_AFTER_SECONDS = 3600L
        const val MAX_INBOX_PAGES = 5
        const val MEDIA_ID_BYTES = 16

        /** A display name of at most 255 UTF-8 bytes, shortened before its extension. */
        fun boundedName(name: String): String {
            if (name.toByteArray(Charsets.UTF_8).size <= CommunityContent.MAX_NAME_BYTES) return name
            val extension = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 16 }?.let { ".$it" }.orEmpty()
            var base = name.removeSuffix(extension)
            while (base.isNotEmpty() && (base + extension).toByteArray(Charsets.UTF_8).size > CommunityContent.MAX_NAME_BYTES) {
                base = base.dropLast(if (base.length >= 2 && base[base.length - 1].isLowSurrogate()) 2 else 1)
            }
            return base + extension
        }
    }
}
