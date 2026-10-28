package com.syconx.us2us.runtime.engine

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.presence.PresenceStatus
import com.syconx.us2us.core.profile.ProfilePicture
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.runtime.storage.AttachmentState
import com.syconx.us2us.runtime.storage.ContactIdentity
import com.syconx.us2us.runtime.storage.ConversationId
import com.syconx.us2us.runtime.storage.IdentityVerification
import com.syconx.us2us.runtime.storage.LocalStorage
import com.syconx.us2us.runtime.storage.RetryPolicy
import com.syconx.us2us.runtime.storage.StorageTransaction
import com.syconx.us2us.runtime.storage.StoredContact
import com.syconx.us2us.runtime.storage.WipeReport
import com.syconx.us2us.runtime.support.RuntimeClock
import com.syconx.us2us.runtime.support.RuntimeLog
import com.syconx.us2us.runtime.support.SystemRuntimeClock
import com.syconx.us2us.runtime.support.WakeSignal
import com.syconx.us2us.runtime.support.logLabel
import com.syconx.us2us.runtime.vault.SecretKeyMaterial
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The messaging core: one serialized place that drives the storage (all records, atomic
 * transactions, crypto-shredding) and the backend (all crypto and relay calls). Inbound messages,
 * user actions, timers and the outbox never race each other.
 *
 * Responsibilities (PROTOCOL.md §5.4, §6.4, §8):
 * * inbound: one storage transaction per message (with the decryption's ratchet state, see
 *   [MessengerStream]), request state machine, receipts, reactions, deletes, view-once, live
 *   location, LWW settings, wipes, screenshots, profiles, presence;
 * * the durable outbox: per-contact order, delivery receipts merged into one `ReceiptMessage`
 *   (≤ 1000 ids), uploads first, classified retries;
 * * presence fan-out, replies, the 240 s refresh and OFFLINE (§8.6);
 * * ghost and disappearing deadlines; media downloads after acceptance;
 * * access-key rotation on block and the profile exchange after acceptance.
 *
 * Serialization works like an actor: every call runs on one dispatcher that executes one task at a
 * time ([dispatcher], `limitedParallelism(1)`); a call that waits for the network lets others run
 * meanwhile. Storage calls block that dispatcher, which is why it is an I/O one.
 *
 * @param stateDirectory where the runtime state and notice items live (inside the app's
 *   protected, backup-excluded directory).
 * @param key the DEK; sub-keys are derived from it and kept while running.
 */
class MessagingEngine(
    internal val storage: LocalStorage,
    internal val backend: MessagingBackend,
    stateDirectory: File,
    key: SecretKeyMaterial,
    internal val clock: RuntimeClock = SystemRuntimeClock,
    internal val configuration: Configuration = Configuration(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    data class Configuration(
        /** Debounce before presence replies go out in one request. */
        val presenceReplyDelay: Duration = 500.milliseconds,
        /** How long a typing indicator stays without a refresh. */
        val typingTimeout: Duration = 15.seconds,
        /** Minimum spacing of our own "typing started" sends. */
        val typingResendInterval: Duration = 5.seconds,
        /** Pre-key maintenance at most this often while connected. */
        val maintenanceInterval: Duration = 6.hours,
        /** Messages loaded per conversation for the UI. */
        val pageSize: Int = 500,
        /** Outbox retries. */
        val retryPolicy: RetryPolicy = RetryPolicy.STANDARD,
    )

    internal val context: CoroutineDispatcher = dispatcher
    internal val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, error -> RuntimeLog.messaging.error("engine task failed", error.logLabel) },
    )
    internal val stateFile: RuntimeStateFile
    internal val notices: NoticeStore

    internal var runtime: RuntimeStateData
    internal val presence = PresenceTracker()
    internal val typing = HashMap<AccountId, Instant>()
    internal val lastTypingSent = HashMap<AccountId, Instant>()

    /** Our live-location sessions (§8.3): session id → peer and end. */
    internal val liveSessions = HashMap<ByteString, LiveSession>()

    /** Contacts whose changed safety number blocks sending. */
    internal val held = HashSet<AccountId>()
    internal var running = false
    internal var connected = false
    internal var lastMaintenance: Instant? = null

    internal val outboxWake = WakeSignal()
    internal val schedulerWake = WakeSignal()
    internal val tasks = ArrayList<Job>()
    internal var presenceReplyTask: Job? = null
    internal val pendingPresenceReplies = HashSet<AccountId>()
    internal var downloadTask: Job? = null
    internal var downloadAgain = false

    /** Only live location runs, in the background: nothing is downloaded ([enterLiveLocationOnly]). */
    internal var downloadsPaused = false

    /** Attachments the user asked for while auto-download is off. */
    internal val requestedDownloads = HashSet<ByteString>()

    /** Uploads that failed transiently: not before this time. */
    internal val uploadBackoff = HashMap<Long, UploadBackoff>()

    /** Outbox entries handed to the relay right now (their fate is unknown until the call returns): an unsend must then send a `Delete`. */
    internal val inFlightOutbox = HashSet<Long>()

    /** Decrypted copies handed out by [exportMedia]: deleted as soon as their message is gone (expired, deleted, wiped) and on stop. */
    internal val exportedMedia = HashMap<File, ExportedMedia>()

    /** The last ONLINE fan-out (§8.6); a reconnect within its validity does not announce again. */
    internal var lastOnlineAnnouncement: Instant? = null

    internal class LiveSession(val peer: AccountId, val expiresAt: Instant)

    internal class UploadBackoff(val attempts: Int, val notBefore: Instant)

    internal class ExportedMedia(val conversation: ConversationId, val message: MessageId)

    private val changeChannel = Channel<EngineChange>(Channel.UNLIMITED)

    /** Every change, in order, for the facade (one collector). */
    val changes: Flow<EngineChange> = changeChannel.receiveAsFlow()

    init {
        if (!stateDirectory.isDirectory && !stateDirectory.mkdirs()) throw java.io.IOException("cannot create the runtime directory")
        stateFile = RuntimeStateFile(stateDirectory, key)
        notices = NoticeStore(File(stateDirectory, NOTICE_DIRECTORY), key)
        runtime = stateFile.load()
    }

    internal val me: AccountId get() = backend.accountId
    internal val settings: UserSettings get() = runtime.settings

    /** Runs [block] serialized with every other call of this engine. */
    internal suspend fun <T> serialized(block: suspend () -> T): T = withContext(context) { block() }

    internal fun emit(change: EngineChange) {
        when (change) {
            is EngineChange.Wiped -> {
                // The share's bubble went with the conversation: nothing keeps the store open for it (§8.3, §5.4).
                endLiveSessions(change.peer)
                discardStaleExports()
            }
            is EngineChange.Messages, EngineChange.Conversations -> discardStaleExports()
            else -> Unit
        }
        changeChannel.trySend(change)
    }

    /** Forgets our live-location sessions with [peer]; no stop is sent (its conversation is gone, or nothing may reach it). */
    internal fun endLiveSessions(peer: AccountId) {
        liveSessions.values.removeAll { it.peer == peer }
    }

    /**
     * Deletes decrypted copies whose message is no longer shown (expired, deleted for me or for
     * everyone, wiped, its file gone): §8.1 "attachments … are deleted with it" holds for
     * plaintext copies too.
     */
    internal fun discardStaleExports() {
        if (exportedMedia.isEmpty()) return
        val now = clock.now()
        for ((file, reference) in exportedMedia.entries.toList()) {
            val alive = try {
                read { tx ->
                    val message = tx.messages.get(reference.message, reference.conversation, now)
                    message != null && !message.isDeleted && message.content != null &&
                        tx.attachments.forMessage(reference.message, reference.conversation).any { it.hasFile }
                }
            } catch (_: Exception) {
                false
            }
            if (!alive || !file.exists()) {
                file.delete()
                exportedMedia.remove(file)
            }
        }
    }

    internal fun saveRuntime() {
        try {
            stateFile.save(runtime)
        } catch (e: Exception) {
            RuntimeLog.messaging.error("runtime state not saved", e.logLabel)
        }
    }

    // MARK: Lifecycle

    /** Starts after an unlock (§5.4 step 1). [openReport] is what opening the storage already wiped. */
    suspend fun start(openReport: WipeReport? = null) = serialized {
        if (running) return@serialized
        running = true
        // A wipe alarm while the store is open runs on it directly: what went with it is forgotten here too.
        storage.wipeListener = { report -> scope.launch { if (running) handleWipeReport(report) } }
        openReport?.let(::handleWipeReport)
        collectNoticeGarbage()
        resetInterruptedTransfers()
        refreshHeld()
        // Before the outbox loop runs, so the stops go out with the first sends.
        stopOrphanedLiveLocations()
        tasks += scope.launch { outboxLoop() }
        tasks += scope.launch { schedulerLoop() }
        tasks += scope.launch { presenceRefreshLoop() }
        tasks += scope.launch { reconcileProfile() }
        startDownloads()
    }

    /**
     * Stops everything (the vault is locking). Pending work stays in the database and resumes
     * after the next unlock. Keys derived from the DEK are zeroed.
     */
    suspend fun stop() = serialized {
        running = false
        connected = false
        storage.wipeListener = null
        val all = tasks + listOfNotNull(presenceReplyTask, downloadTask)
        tasks.clear()
        presenceReplyTask = null
        downloadTask = null
        for (task in all) task.cancelAndJoin()
        presence.clear()
        typing.clear()
        liveSessions.clear()
        lastOnlineAnnouncement = null
        for (file in exportedMedia.keys) file.delete()
        exportedMedia.clear()
        stateFile.close()
        notices.close()
    }

    /**
     * The system takes the background time back while the engine is still stopping (a slow
     * network): zero every key derived from the DEK now, from any thread. Later writes of the
     * runtime state and notices fail.
     */
    fun wipeDerivedKeys() {
        stateFile.close()
        notices.close()
    }

    /**
     * The connection received its backlog (`queue_empty`): batched receipts and the outbox go out,
     * presence is announced, keys topped up, and a restore's profile announcement finished.
     */
    suspend fun connectionReachedQueueEmpty() = serialized {
        if (!running) return@serialized
        connected = true
        outboxWake.signal()
        // A connection that keeps dropping right after its backlog must not fan ONLINE out to every
        // contact each time (§8.6, §1.1): within the refresh interval the last announcement holds.
        val now = clock.now()
        val last = lastOnlineAnnouncement
        if (last == null || !now.isBefore(last.plusMillis(com.syconx.us2us.core.presence.PresencePolicy.REFRESH_INTERVAL.inWholeMilliseconds))) {
            lastOnlineAnnouncement = now
            announceOnline()
        }
        finishRestoreAnnouncementIfNeeded()
        maintainKeysIfDue()
        startDownloads()
    }

    suspend fun connectionClosed() = serialized { connected = false }

    /**
     * The grace period is over and only live location goes on in the background (§8.3): nothing is
     * fetched or decrypted any more, a download already running included, until
     * [leaveLiveLocationOnly].
     */
    suspend fun enterLiveLocationOnly() = serialized {
        downloadsPaused = true
        downloadTask?.cancelAndJoin()
        resetInterruptedTransfers(uploads = false)
    }

    /** The same session is unlocked again: downloads go on. */
    suspend fun leaveLiveLocationOnly() = serialized {
        downloadsPaused = false
        startDownloads()
    }

    /** End of the background grace period (§5.4 step 3): OFFLINE to the contacts we see online. */
    suspend fun goingOffline() = serialized {
        if (running) announceOffline()
    }

    /**
     * Handles one received message in **one** storage transaction, which also commits the
     * decryption's staged session state (atomic receive). When this returns, the message is stored
     * or deliberately dropped and may be acknowledged; a thrown error leaves it on the relay and its
     * staged decryption is discarded, so the redelivery decrypts again.
     */
    suspend fun receive(message: InboundMessage) = serialized { receiveMessage(message) }

    // MARK: Requests and blocking (§6.4)

    /** Finds [username] (exact match, §4.4) and opens a conversation as an outgoing request. Nothing is sent until the first message. */
    suspend fun startConversation(username: Username): AccountId = serialized { startConversationNow(username) }

    /**
     * A contact code: opens the conversation with the account of [entry] (the lookup the code was
     * checked against, no second one) and, with [pinKey], first pins its key as verified. `false`
     * when another key is stored for that account: then nothing is created.
     */
    suspend fun addContactFromCode(username: Username, entry: DirectoryEntry, pinKey: Boolean): Boolean =
        serialized { addContactFromCodeNow(username, entry, pinKey) }

    /** Accepts an incoming request (§6.4): `ACCEPT` first, the state changes only after the relay answered. */
    suspend fun acceptRequest(peer: AccountId) = serialized { acceptRequestNow(peer) }

    /** "Sil": deletes the request (crypto-shredded) and the contact. Nothing is sent. */
    suspend fun declineRequest(peer: AccountId) = serialized { declineRequestNow(peer) }

    suspend fun block(peer: AccountId) = serialized { blockNow(peer) }

    suspend fun unblock(peer: AccountId) = serialized { unblockNow(peer) }

    // MARK: Composing (§8)

    /**
     * Stores a bubble (status *Sending*) and queues it; the outbox sends it when it can. [messageId]:
     * an id the app made beforehand (a video's bubble is on screen while it is prepared); it must be
     * new to the conversation.
     */
    suspend fun send(
        content: OutgoingContent,
        peer: AccountId,
        quote: QuoteInfo? = null,
        viewOnce: Boolean = false,
        messageId: MessageId? = null,
    ): MessageId = serialized { sendNow(content, peer, quote, viewOnce, messageId) }

    /** "Tekrar gönder": a failed message goes back into the queue; a *Not delivered* one is sent again as new. */
    suspend fun resend(messageId: MessageId, peer: AccountId): MessageId = serialized { resendNow(messageId, peer) }

    /** Adds ([emoji]) or removes (`null`) our reaction (§8.5). */
    suspend fun react(target: MessageId, peer: AccountId, emoji: String?) = serialized { reactNow(target, peer, emoji) }

    suspend fun deleteForEveryone(messageId: MessageId, peer: AccountId) = serialized { deleteForEveryoneNow(messageId, peer) }

    suspend fun deleteForMe(messageId: MessageId, peer: AccountId) = serialized { deleteForMeNow(messageId, peer) }

    // MARK: Reading (§8.1, §8.4)

    /** The conversation is on screen: received messages are displayed now (timers start) and read receipts go out if enabled. */
    suspend fun markDisplayed(peer: AccountId) = serialized { markDisplayedNow(peer) }

    suspend fun markVoiceNotePlayed(messageId: MessageId, peer: AccountId) = serialized { markVoiceNotePlayedNow(messageId, peer) }

    suspend fun markMediaSaved(messageId: MessageId, peer: AccountId) = serialized { markMediaSavedNow(messageId, peer) }

    /** Opens a received view-once item **once** into memory; it is destroyed here and VIEWED queued in the same transaction. */
    suspend fun openViewOnceData(messageId: MessageId, peer: AccountId): ByteArray = serialized { openViewOnceDataNow(messageId, peer) }

    /** [openViewOnceData] for players that need a file; [closeViewOnce] deletes it. */
    suspend fun openViewOnce(messageId: MessageId, peer: AccountId): File = serialized { openViewOnceNow(messageId, peer) }

    suspend fun closeViewOnce(messageId: MessageId, peer: AccountId, displayedFile: File?) =
        serialized { closeViewOnceNow(messageId, peer, displayedFile) }

    /** A stored attachment (not view-once), decrypted into memory. */
    suspend fun mediaData(messageId: MessageId, peer: AccountId): ByteArray = serialized { mediaDataNow(messageId, peer) }

    /** Decrypts a stored attachment (not view-once) into a staging file, deleted as soon as its message is gone. */
    suspend fun exportMedia(messageId: MessageId, peer: AccountId): File = serialized { exportMediaNow(messageId, peer) }

    /** Downloads one attachment now (auto-download off, or a retry). */
    suspend fun download(messageId: MessageId, peer: AccountId) = serialized { downloadNow(messageId, peer) }

    suspend fun setTyping(started: Boolean, peer: AccountId) = serialized { setTypingNow(started, peer) }

    // MARK: Conversation settings (§8.1, §8.2)

    suspend fun updateConversationSettings(peer: AccountId, ghost: GhostSettings?, defaultExpireTimerSeconds: Long) =
        serialized { updateConversationSettingsNow(peer, ghost, defaultExpireTimerSeconds) }

    /** A screenshot or screen recording while [peer]'s conversation was on screen. */
    suspend fun screenCaptured(peer: AccountId, recording: Boolean) = serialized { screenCapturedNow(peer, recording) }

    /** "Delete for both" (§8.2 `USER_REQUEST`): wipe here first, then ask the contact to wipe too. */
    suspend fun deleteConversationForBoth(peer: AccountId) = serialized { deleteConversationForBothNow(peer) }

    /** Deletes the conversation on this phone only. */
    suspend fun deleteConversationLocally(peer: AccountId) = serialized { deleteConversationLocallyNow(peer) }

    /** A directory lookup answered that [username] belongs to [owner]: other contacts showing it no longer own it (§3.3). */
    suspend fun noteUsernameOwner(username: Username, owner: AccountId) = serialized {
        if (write { it.contacts.noteUsernameOwner(username, owner) } > 0) emit(EngineChange.Conversations)
    }

    /** Deletes the contact on this phone only: conversation, contact, session and identity; a block stays. */
    suspend fun deleteContact(peer: AccountId) = serialized { deleteContactNow(peer) }

    // MARK: Safety numbers (§5)

    suspend fun acceptSafetyNumberChange(peer: AccountId, shownNumber: ByteArray): Boolean =
        serialized { acceptSafetyNumberChangeNow(peer, shownNumber) }

    suspend fun markVerified(peer: AccountId, comparedNumber: ByteArray): Boolean = serialized { markVerifiedNow(peer, comparedNumber) }

    suspend fun verifySafetyNumber(peer: AccountId, scanned: ByteArray): Boolean = serialized { verifySafetyNumberNow(peer, scanned) }

    suspend fun verifyContactCode(peer: AccountId, identityKey: ByteArray): Boolean =
        serialized { verifyContactCodeNow(peer, identityKey) }

    suspend fun clearVerification(peer: AccountId) = serialized {
        backend.clearVerification(peer)
        emit(EngineChange.Conversations)
    }

    suspend fun identityVerification(peer: AccountId): IdentityVerification? = serialized { backend.identityVerification(peer) }

    suspend fun identityTrust(peer: AccountId): ContactIdentity = serialized { backend.identityTrust(peer) }

    /** Checks a contact's username claim against the directory, once, when the conversation is opened. */
    suspend fun verifyUsernameIfNeeded(peer: AccountId) = serialized { verifyUsernameIfNeededNow(peer) }

    // MARK: Settings, backup, stickers

    suspend fun currentSettings(): UserSettings = serialized { settings }

    suspend fun updateUserSettings(change: (UserSettings) -> UserSettings) = serialized { updateUserSettingsNow(change) }

    /** Changes our username on the relay (§3.3) and tells the contacts (§8.7). */
    suspend fun changeUsername(username: Username) = serialized { changeUsernameNow(username) }

    /** The encrypted account backup: identity and accepted / blocked contacts, never messages (§5.5). */
    suspend fun exportBackup(password: String): ByteArray = serialized { backend.exportBackup(password) }

    suspend fun lastBackupAt(): Instant? = serialized { runtime.lastBackupAt }

    suspend fun recordBackupSaved(at: Instant) = serialized { recordBackupSavedNow(at) }

    suspend fun recentStickers(): List<String> = serialized { runtime.recentStickers }

    suspend fun setRecentStickers(ids: List<String>) = serialized { setRecentStickersNow(ids) }

    suspend fun noteRecentSticker(id: String) = serialized { setRecentStickersNow(listOf(id) + runtime.recentStickers) }

    // MARK: Presence and live location (§8.6, §8.3)

    suspend fun presenceStatus(now: Instant): Map<AccountId, PresenceStatus> = serialized { presenceStatusNow(now) }

    suspend fun typingContacts(now: Instant): Set<AccountId> = serialized { typing.filterValues { it.isAfter(now) }.keys.toSet() }

    suspend fun startLiveLocation(peer: AccountId, latitude: Double, longitude: Double, accuracy: Float, duration: Duration): ByteString =
        serialized { startLiveLocationNow(peer, latitude, longitude, accuracy, duration) }

    suspend fun updateLiveLocation(
        sessionId: ByteString,
        peer: AccountId,
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        heading: Float = -1f,
        speed: Float = -1f,
    ) = serialized { updateLiveLocationNow(sessionId, peer, latitude, longitude, accuracy, heading, speed) }

    suspend fun stopLiveLocation(sessionId: ByteString, peer: AccountId) = serialized { stopLiveLocationNow(sessionId, peer) }

    /** Our live-location sessions still running (the app keeps the store open in the background only for these, §8.3). */
    suspend fun activeLiveLocationSessions(): List<ByteString> = serialized {
        val now = clock.now()
        liveSessions.filterValues { it.expiresAt.isAfter(now) }.keys.toList()
    }

    /** When the last running session ends by itself, `null` when none runs. */
    suspend fun liveLocationEnd(): Instant? = serialized {
        val now = clock.now()
        liveSessions.values.map { it.expiresAt }.filter { it.isAfter(now) }.maxOrNull()
    }

    // MARK: Queries

    /** The chat list: every visible contact with a conversation, most recent first. */
    suspend fun chatSummaries(): List<ChatSummary> = serialized { chatSummariesNow() }

    /** The newest messages of a conversation and its system lines, oldest first. */
    suspend fun messages(peer: AccountId): List<MessageItem> = serialized { messagesNow(peer) }

    suspend fun history(peer: AccountId, before: Long? = null, through: Long? = null, limit: Int = 100): MessageHistory =
        serialized { historyNow(peer, before, through, limit) }

    /** The conversation's photos and videos or files, **newest first**, [limit] older than [before]. */
    suspend fun mediaMessages(peer: AccountId, kind: MediaKind, before: MessageId?, limit: Int): List<MessageItem> =
        serialized { mediaMessagesNow(peer, kind, before, limit) }

    /** One message of the conversation as it is now; `null` once it is gone or expired. */
    suspend fun messageItem(messageId: MessageId, peer: AccountId): MessageItem? = serialized { messageItemNow(messageId, peer) }

    /**
     * Everyone blocked, also those without a conversation (a ghost wipe or a deleted conversation
     * keeps the block): the only place they can be unblocked from without finding them by username again.
     */
    suspend fun blockedContacts(): List<ContactInfo> = serialized { read { tx -> tx.contacts.blocked().map(ContactInfo::of) } }

    suspend fun contactInfo(peer: AccountId): ContactInfo? = serialized { read { tx -> tx.contacts.get(peer)?.let(ContactInfo::of) } }

    suspend fun conversationInfo(peer: AccountId): ConversationInfo? = serialized {
        read { tx ->
            tx.conversations.forContact(peer)?.let { ConversationInfo.of(it, tx.conversations.unreadCount(it.id)) }
        }
    }

    // MARK: Profile pictures (§8.9, §8.10)

    suspend fun ownAvatar(): OwnProfilePicture = serialized { backend.ownAvatar() }

    suspend fun setOwnAvatar(picture: ProfilePicture?, showInSearch: Boolean): OwnProfilePicture =
        serialized { setOwnAvatarNow(picture, showInSearch) }

    suspend fun syncAvatarRecord(): AvatarRecordStatus = serialized { backend.syncAvatarRecord() }

    /** The picture of a search result (§8.10 "Search"); every failure is the initial letter. */
    suspend fun avatar(username: Username, account: AccountId): AvatarLookup = serialized { avatarForUsername(username, account) }

    /** The picture of a conversation's contact (§8.9); safe for list rows. */
    suspend fun avatar(peer: AccountId): AvatarLookup = serialized { avatarOf(peer, fetchRequest = false) }

    /** An incoming request's picture after the user tapped "Show picture" ([userConfirmed]). */
    suspend fun requestAvatar(peer: AccountId, userConfirmed: Boolean): AvatarLookup = serialized { avatarOf(peer, userConfirmed) }

    // MARK: Helpers

    internal fun <T> read(body: (StorageTransaction) -> T): T = storage.read(body)

    internal fun <T> write(body: (StorageTransaction) -> T): T = storage.write(body)

    /** Our current access key (saved by the backend into the database). */
    internal fun ourAccessKey(tx: StorageTransaction): ByteString {
        val account = tx.account.load() ?: engineFailure(EngineError.STOPPED)
        return ByteString.copyFrom(account.accessKey.toBytes())
    }

    internal fun isPresenceEligible(contact: StoredContact): Boolean =
        settings.presenceSharing && contact.requestState == com.syconx.us2us.core.contacts.ContactState.ACCEPTED &&
            contact.presenceSharing && contact.theirAccessKey != null && !contact.pendingForget

    internal fun refreshHeld() {
        val contacts = try {
            read { it.contacts.all() }
        } catch (_: Exception) {
            emptyList()
        }
        held.clear()
        held += contacts.filter { it.identityChanged }.map { it.accountId }
    }

    /** Interrupted transfers (the app locked or crashed mid-way) start over. */
    internal fun resetInterruptedTransfers(uploads: Boolean = true) {
        try {
            write { tx ->
                for (attachment in tx.attachments.inStates(listOf(AttachmentState.DOWNLOADING), limit = 1000)) {
                    tx.attachments.setState(AttachmentState.PENDING_DOWNLOAD, attachment.id)
                }
                if (!uploads) return@write
                for (attachment in tx.attachments.inStates(listOf(AttachmentState.UPLOADING), limit = 1000)) {
                    tx.attachments.setState(AttachmentState.PENDING_UPLOAD, attachment.id)
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * The display name and presence setting live in the runtime state; a backend whose account
     * keeps them too (it builds its own profile updates and the backup from it) is brought in line.
     */
    internal suspend fun reconcileProfile() {
        try {
            backend.updateProfile(settings.displayName, settings.presenceSharing)
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            RuntimeLog.messaging.info("profile not reconciled", e.logLabel)
        }
    }

    companion object {
        /** The most recents the runtime state keeps, and the longest id. */
        const val MAX_RECENT_STICKERS = 12
        const val MAX_RECENT_STICKER_ID_LENGTH = 32

        internal const val NOTICE_DIRECTORY = "notices"
    }
}

/** In-memory view of the contacts' presence, fed by received `PresenceMessage`s; nothing older than an hour. */
internal class PresenceTracker {
    private sealed interface Entry {
        class Online(val lastSeen: Instant, val until: Instant) : Entry

        class Offline(val since: Instant) : Entry
    }

    private val entries = HashMap<AccountId, Entry>()

    fun record(message: com.syconx.us2us.core.proto.PresenceMessage, contact: AccountId, time: Instant) {
        when (message.state) {
            com.syconx.us2us.core.proto.PresenceMessage.State.ONLINE -> {
                val validFor = message.validForSeconds.unsigned().coerceAtMost(com.syconx.us2us.core.presence.PresencePolicy.VALID_FOR_SECONDS.toLong())
                entries[contact] = Entry.Online(time, time.plusSeconds(validFor))
            }
            com.syconx.us2us.core.proto.PresenceMessage.State.OFFLINE -> entries[contact] = Entry.Offline(time)
            else -> Unit
        }
    }

    fun forget(contact: AccountId) {
        entries.remove(contact)
    }

    fun clear() = entries.clear()

    fun status(contact: AccountId, now: Instant): PresenceStatus {
        val offlineSince = when (val entry = entries[contact]) {
            null -> return PresenceStatus.None
            is Entry.Online -> if (now.isBefore(entry.until)) return PresenceStatus.Active else entry.lastSeen
            is Entry.Offline -> entry.since
        }
        val elapsed = java.time.Duration.between(offlineSince, now).toMillis()
        return when {
            elapsed < com.syconx.us2us.core.presence.PresencePolicy.JUST_NOW_WINDOW.inWholeMilliseconds -> PresenceStatus.ActiveJustNow
            elapsed < com.syconx.us2us.core.presence.PresencePolicy.RECENT_WINDOW.inWholeMilliseconds ->
                PresenceStatus.ActiveMinutesAgo((elapsed / 60_000).toInt())
            else -> PresenceStatus.None
        }
    }

    /** Contacts currently seen online: the targets of the 240 s refresh and of OFFLINE. */
    fun onlineContacts(now: Instant): List<AccountId> =
        entries.filter { (_, entry) -> entry is Entry.Online && now.isBefore(entry.until) }.keys.toList()

    /** Drops entries that can no longer be displayed. */
    fun prune(now: Instant) {
        entries.keys.toList().forEach { if (status(it, now) == PresenceStatus.None) entries.remove(it) }
    }
}

/** Passes a download's progress on at most every 2 % (and at the end), so the conversation is not refreshed for every chunk. */
internal class DownloadProgressThrottle(private val report: (Double) -> Unit) {
    private var last = -1.0

    @Synchronized
    private fun due(value: Double): Boolean {
        if (!(value - last >= 0.02 || (value >= 1 && last < 1))) return false
        last = value
        return true
    }

    fun update(fraction: Double) {
        val value = fraction.coerceIn(0.0, 1.0)
        if (due(value)) report(value)
    }
}
