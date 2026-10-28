package com.syconx.us2us.live

import androidx.core.net.toUri
import android.webkit.MimeTypeMap
import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.domain.AppSettings
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.BackupContents
import com.syconx.us2us.domain.BackupEstimate
import com.syconx.us2us.domain.BackupFileError
import com.syconx.us2us.domain.DeviceProtection
import com.syconx.us2us.domain.BackupFileInfo
import com.syconx.us2us.domain.BackupOpenResult
import com.syconx.us2us.domain.BackupPasswordError
import com.syconx.us2us.domain.BlockedPerson
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.ChangeSecretCodeError
import com.syconx.us2us.domain.ChatMediaKind
import com.syconx.us2us.domain.ChatSummary
import com.syconx.us2us.domain.ConnectionState
import com.syconx.us2us.domain.ContactCode
import com.syconx.us2us.domain.ContactCodeAddition
import com.syconx.us2us.domain.ContactCodeResolution
import com.syconx.us2us.domain.ConversationAttachment
import com.syconx.us2us.domain.DisappearingTimer
import com.syconx.us2us.domain.GhostState
import com.syconx.us2us.domain.LiveLocationSnapshot
import com.syconx.us2us.domain.MediaPayload
import com.syconx.us2us.domain.MediaSource
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageRequest
import com.syconx.us2us.domain.MessengerException
import com.syconx.us2us.domain.MessengerScenePhase
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.NoticePermission
import com.syconx.us2us.domain.OwnAccount
import com.syconx.us2us.domain.OwnPositionState
import com.syconx.us2us.domain.Peer
import com.syconx.us2us.domain.PickedMediaItem
import com.syconx.us2us.domain.PickedMediaMessage
import com.syconx.us2us.domain.PickedMediaPlan
import com.syconx.us2us.domain.Presence
import com.syconx.us2us.domain.PresenceVisibility
import com.syconx.us2us.domain.ProtectedPreviewFiles
import com.syconx.us2us.domain.QuotedMessage
import com.syconx.us2us.domain.RestoreAccessCheck
import com.syconx.us2us.domain.RestoreFailure
import com.syconx.us2us.domain.RestoreProgress
import com.syconx.us2us.domain.SafetyCodeComparison
import com.syconx.us2us.domain.SafetyNumberInfo
import com.syconx.us2us.domain.SafetyQRCodec
import com.syconx.us2us.domain.SecretCodeError
import com.syconx.us2us.domain.SecretCodeRules
import com.syconx.us2us.domain.SetupInterruption
import com.syconx.us2us.domain.UnlockAttemptResult
import com.syconx.us2us.domain.UnlockStatus
import com.syconx.us2us.domain.UserLookup
import com.syconx.us2us.domain.UsernameAvailability
import com.syconx.us2us.domain.UsernameRules
import com.syconx.us2us.domain.VerificationMethod
import com.syconx.us2us.domain.VoiceNoteRules
import com.syconx.us2us.domain.VoiceRecording
import com.syconx.us2us.domain.VoiceRecordingFiles
import com.syconx.us2us.domain.mapState
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.domain.sticker.BrandStickerSelection
import com.syconx.us2us.runtime.engine.AttachmentItem
import com.syconx.us2us.runtime.engine.GhostSettings
import com.syconx.us2us.runtime.engine.LocationInfo
import com.syconx.us2us.runtime.engine.MessageBody
import com.syconx.us2us.runtime.engine.MessageDirection as RuntimeDirection
import com.syconx.us2us.runtime.engine.MessageItem
import com.syconx.us2us.runtime.engine.QuoteInfo
import com.syconx.us2us.runtime.facade.AppRuntime
import com.syconx.us2us.runtime.facade.LockState
import com.syconx.us2us.runtime.media.MediaInput
import com.syconx.us2us.runtime.media.PreparedMedia
import com.syconx.us2us.runtime.storage.ContactIdentity
import com.syconx.us2us.runtime.storage.IdentityVerificationMethod
import com.syconx.us2us.runtime.support.AppPhase
import com.syconx.us2us.runtime.vault.QuickUnlockStatus
import com.syconx.us2us.runtime.vault.VaultError
import com.syconx.us2us.runtime.vault.VaultException
import java.io.File
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The live [MessengerService]: the screens' data surface over the runtime's [AppRuntime] (vault,
 * SQLCipher storage, crypto-shredding, the relay lifecycle, privacy guards) and the core
 * (libsignal, the relay client).
 *
 * - Every observable property is derived from the runtime's state flows; the mapping
 *   ([LiveMapping]) is cached per input and dropped when the app locks.
 * - Nothing decrypted is kept while locked: the runtime clears its state on every lock and the
 *   caches here follow it; decrypted media lives in memory only.
 * - One network endpoint: the relay ([RelayEndpoint]). The live-location map draws the design's
 *   dotted map, so no map tiles are loaded.
 *
 * Main thread only (like the screens that call it); [main] runs the service's own work.
 */
class RealMessengerService(
    val runtime: AppRuntime,
    private val platform: LivePlatform,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val sharingNotice: SharingNotice = ForegroundSharingNotice(platform),
) : MessengerService {
    override val communities: com.syconx.us2us.domain.CommunitiesService = RealCommunitiesService(runtime)
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val context get() = platform.context
    private val pickers = SystemPickers(platform.context)
    private val locationAccess = LocationAccess(platform.context, platform)
    private val location = LocationProvider(platform.context, locationAccess)
    private val positions = DevicePositionWatcher(platform.context, locationAccess)

    /** Live-location sessions this device runs, and what keeps them going in the background. */
    internal val sharing = LiveLocationSharing(LiveLocationSharing.runtimeOf(runtime), location, sharingNotice, scope)

    /**
     * Counts the vaults this process has seen: bumped whenever this phone's vault is erased or
     * replaced, so a backup exported from one vault is never recorded for the next
     * ([forgetExportedBackups]).
     */
    private var vaultGeneration = 0

    /** Exported backup files whose share sheet is still open, with the vault they came from. */
    private val exportedBackups = HashMap<URI, Int>()

    /**
     * A backup saved while the vault's store was closed (the share sheet finished after the app had
     * left the foreground and locked): recorded in the vault at the next unlock. Memory only; the
     * time alone.
     */
    private var unrecordedBackup: Pair<Instant, Int>? = null

    /** A picked backup whose password was checked (7.3), restored with the new code chosen in 2.2 ([completeRestore]). Memory only. */
    private var pendingRestore: Pair<File, String>? = null

    /** Why the last opened backup can never be restored, after it was dropped with its password: 2.3 says the same if asked again. */
    private var unrestorable: RestoreFailure? = null

    /** Decrypted full images by message id, the photos sent from here this session as they were sent, and the posters of videos sent from here (memory only; cleared on lock). */
    private val media = MutableStateFlow<Map<MessageId, Bytes>>(emptyMap())

    /** Photos and videos picked in “+” while they are prepared, one at a time: their bubbles show at once. */
    val outgoingMedia: OutgoingMedia<PreparedMedia> = OutgoingMedia(mediaSteps(), scope, io = io)

    /** Communities in the chat list and the conversation screen (docs/COMMUNITIES.md). */
    private val communityChats = LiveCommunities(runtime, scope, outgoingMedia, showsRead = { runtime.settings.value.readReceipts })
    private val mediaLoading = HashSet<MessageId>()

    /** Attachments of the open conversation the user asked to download, so the bubble shows its ring at once (the runtime reports the transfer only once it runs). */
    private val downloads = MutableStateFlow<Map<MessageId, LiveDownload>>(emptyMap())

    /** Photos, videos and files the media list (6.1) returned, by message id, so their bytes load also while their chat is not open. Memory only. */
    private val listedMedia = HashMap<Bytes, Pair<AccountId, MessageItem>>()

    /** The live-location map's tick times (memory only). */
    private var liveTicks: Map<Bytes, LiveTick> = emptyMap()

    /** Accounts the exact lookups of this session found, by normalized username: what a 4.2 result's picture is fetched for. Memory only; cleared on lock. */
    private val avatarAccounts = HashMap<String, AccountId>()

    /** Search copies found this session, by chat: what the user's own message request draws (§8.9). Memory only; cleared on lock. */
    private val searchedAvatars = MutableStateFlow<Map<String, LiveAvatar>>(emptyMap())

    /** What 4.4 › “Profil resmini göster” found this session, by request: shown again without asking the relay a second time. Memory only; cleared on lock. */
    private val revealedAvatars = HashMap<String, LiveAvatar>()

    // MARK: Derived state

    private val chatsState = MutableStateFlow<List<ChatSummary>>(emptyList())
    private val requestsState = MutableStateFlow<List<MessageRequest>>(emptyList())
    private val blockedState = MutableStateFlow<List<BlockedPerson>>(emptyList())
    private val settingsState = MutableStateFlow(AppSettings())
    private val ownAvatarState = MutableStateFlow(LiveMapping.ownAvatar(null))

    private class OpenConversation(val peer: String?, val messages: List<Message>, val liveLocations: LiveLocationSnapshot)

    private val openState = MutableStateFlow(OpenConversation(null, emptyList(), LiveLocationSnapshot.Empty))

    private val chatFlows = HashMap<ChatId, StateFlow<ChatSummary?>>()
    private val messageFlows = HashMap<ChatId, StateFlow<List<Message>>>()
    private val liveLocationFlows = HashMap<ChatId, StateFlow<LiveLocationSnapshot>>()
    private val revisionFlows = HashMap<ChatId, StateFlow<Int>>()

    init {
        outgoingMedia.onSent = ::keepImage
        communityChats.start()
        // Picked copies a crash or a kill left behind go with the new process.
        pickers.removeAllPicked()
        recomputeChats()
        recomputeRequests()
        recomputeBlocked()
        recomputeSettings()
        recomputeOwnAvatar()
        recomputeOpen()
        scope.launch {
            merge(runtime.chats, runtime.presence, runtime.settings, runtime.safetyNumberChanges, searchedAvatars, communityChats.summaries).conflate().collect { recomputeChats() }
        }
        scope.launch { runtime.requests.collect { recomputeRequests() } }
        scope.launch { runtime.blockedContacts.collect { recomputeBlocked() } }
        scope.launch { merge(runtime.settings, runtime.quickUnlock, runtime.lastBackupAt).conflate().collect { recomputeSettings() } }
        scope.launch { runtime.ownAvatar.collect { recomputeOwnAvatar() } }
        scope.launch {
            merge(
                runtime.openPeer, runtime.openMessages, runtime.downloadProgress, runtime.settings, runtime.ownUsername,
                media, downloads, outgoingMedia.items, chatsState,
            ).conflate().collect { recomputeOpen() }
        }
    }

    /**
     * A picked photo or video goes out through the runtime: a photo re-encoded (≤ 2048 px JPEG, no
     * metadata) with its caption, a video re-exported (≤ 720p, no metadata), then stored and queued,
     * under the id its bubble already has.
     */
    private fun mediaSteps() = OutgoingMedia.Steps<PreparedMedia>(
        preview = { file, kind ->
            withContext(io) { if (kind == PickedMediaItem.Kind.Photo) PhotoPreviewReader.read(file) else VideoPreviewReader.read(file) }
        },
        prepare = { file, kind, caption ->
            if (kind == PickedMediaItem.Kind.Photo) {
                runtime.prepareImage(MediaInput.of(file), caption)
            } else {
                runtime.prepareVideo(context, MediaInput.of(file))
            }
        },
        preparedFile = { it.file },
        send = { prepared, id, chat ->
            if (communityChats.isCommunity(chat)) {
                communityChats.sendMedia(prepared, chat, AccountId.fromBytes(id.toByteArray()))
            } else {
                val peer = account(chat) ?: throw IOException("unknown chat")
                runtime.sendMedia(prepared, peer, messageId = id.toByteString())
            }
        },
        discardSource = pickers::discardPicked,
        discardPrepared = { file -> file.delete() },
    )

    /**
     * An item was stored: a photo as it was sent, a video's poster, stays for the stored bubble
     * (memory only, like the photos), which would otherwise show the small inline thumbnail until
     * the upload finished.
     */
    private fun keepImage(item: OutgoingMediaItem) {
        if (communityChats.isCommunity(item.chat)) return communityChats.keep(item)
        val image = item.keptImage ?: return
        if (runtime.openPeer.value?.toString() != item.chat.value) return
        media.value = media.value + (item.id to image)
    }

    private val now: Instant get() = Instant.now()

    private fun account(chat: ChatId): AccountId? = account(chat.value)

    private fun account(value: String): AccountId? = try {
        AccountId.parse(value)
    } catch (_: IllegalArgumentException) {
        null
    }

    // MARK: Account & onboarding

    override val account: StateFlow<OwnAccount?> = runtime.ownUsername.mapState { it?.let(::OwnAccount) }

    override val hasAccount: StateFlow<Boolean> = runtime.lockState.mapState { it != LockState.NEEDS_SETUP }

    override val ownUsername: StateFlow<String> = runtime.ownUsername.mapState { it.orEmpty() }

    override val sendsDesignContent: Boolean get() = false

    override suspend fun usernameAvailability(username: String): UsernameAvailability {
        if (username.isEmpty()) return UsernameAvailability.Empty
        UsernameRules.violation(username)?.let { return UsernameAvailability.Invalid(it) }
        // The directory answers only authenticated accounts (§4.4): whether the name is free shows
        // at “Kurulumu tamamla” (409), not while typing.
        return UsernameAvailability.MeetsRules
    }

    override fun secretCodeProblem(code: String): SecretCodeError? = LiveOutcomes.secretCodeProblem(code)

    override suspend fun createAccount(username: String, secretCode: String, faceIdEnabled: Boolean) = withContext(main) {
        // A new vault: a backup of an erased one is never recorded for it.
        forgetExportedBackups()
        try {
            runtime.createAccount(username, displayName = "", code = secretCode, confirmation = secretCode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Registered and saved, then something failed: the account is kept, locked under this
            // code; 2.3 sends the user to the lock.
            if (runtime.lockState.value == LockState.LOCKED && runtime.keptAccountAfterFailedSetup.value) {
                throw MessengerException.SetupInterrupted(SetupInterruption.AccountKept)
            }
            throw LiveOutcomes.creationFailure(e)
        }
        if (faceIdEnabled) quietly { runtime.enableQuickUnlock(secretCode) }
    }

    override suspend fun completeRestore(secretCode: String, faceIdEnabled: Boolean) = withContext(main) {
        val pending = pendingRestore ?: throw MessengerException.Restore(unrestorable ?: RestoreFailure.BackupDamaged)
        // 3.3 › 7.3 inside the lock: the runtime replaces the locked vault, and only once the backup
        // is fully valid and the relay still accepts its token (PROTOCOL §5.5); before that nothing is erased.
        val replacing = runtime.lockState.value == LockState.LOCKED
        // A backup exported before is never recorded for the restored account: the restore rotates
        // the token, so that file stops working.
        forgetExportedBackups()
        try {
            val file = withContext(io) { pending.first.readBytes() }
            runtime.restore(file, pending.second, secretCode, secretCode, replacingLockedVault = replacing)
        } catch (e: CancellationException) {
            forgetVaultReplacedBy(replacing)
            throw e
        } catch (e: Exception) {
            forgetVaultReplacedBy(replacing)
            if (runtime.lockState.value == LockState.LOCKED && runtime.keptAccountAfterFailedSetup.value) {
                // Restored and saved, then something failed: the restored account stays, locked under the new code.
                dropPendingRestore()
                throw MessengerException.SetupInterrupted(SetupInterruption.AccountKept)
            }
            // A dead or damaged backup is said as such on 2.3, instead of asking to check the connection.
            val failure = LiveOutcomes.restoreFailure(e)
            if (failure != null) {
                dropPendingRestore()
                unrestorable = failure
            }
            if (replacing && runtime.lockState.value == LockState.LOCKED && failure != null) {
                // Refused before anything was erased: the vault this phone held is untouched, and only the lock can go on from here.
                throw MessengerException.SetupInterrupted(SetupInterruption.PreviousVaultKept)
            }
            if (failure != null) throw MessengerException.Restore(failure)
            // No connection, the relay busy: nothing changed, 2.3 offers to try again (with the backup still opened).
            throw e
        }
        forgetVaultReplacedBy(replacing)
        dropPendingRestore()
        if (faceIdEnabled) quietly { runtime.enableQuickUnlock(secretCode) }
    }

    /**
     * The vault this phone held is gone once the restore erased it (it succeeded, failed after
     * erasing, or kept the restored account): what the service kept of it goes too.
     */
    private fun forgetVaultReplacedBy(replacing: Boolean) {
        if (replacing && (runtime.lockState.value != LockState.LOCKED || runtime.keptAccountAfterFailedSetup.value)) forgetReplacedVault()
    }

    /** The opened backup is not needed any more (restored, or it can never be): its private copy and its password go. */
    private fun dropPendingRestore() {
        pendingRestore?.first?.delete()
        pendingRestore = null
    }

    /** A restore replaced the vault this phone held: what the service kept of it (live-location updates, preview and recording files, caches) goes as well. */
    private fun forgetReplacedVault() {
        sharing.forget()
        ProtectedPreviewFiles.removeAll()
        VoiceRecordingFiles.removeAll()
        outgoingMedia.removeAll()
        clearCaches()
        forgetExportedBackups()
    }

    // MARK: Lock

    override val unlockStatus: StateFlow<UnlockStatus> = runtime.unlockStatus.mapState(LiveOutcomes::unlockStatus)

    override suspend fun unlock(code: String): UnlockAttemptResult = withContext(main) {
        if (!SecretCodeRules.isWellFormed(code)) return@withContext UnlockAttemptResult.Malformed
        try {
            runtime.unlock(code)
            // No error but not unlocked: the app left the foreground while the code was checked (it
            // locks again at once), or another unlock was running. The code was not wrong.
            if (runtime.lockState.value != LockState.UNLOCKED) return@withContext UnlockAttemptResult.Interrupted
            recordUnrecordedBackup()
            UnlockAttemptResult.Unlocked
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val result = LiveOutcomes.attemptResult(e, unlockStatus.value)
            // The 10th wrong code erased everything (the runtime already did): what the service kept goes too, and the lock says so.
            if (result == UnlockAttemptResult.Wiped) forgetErasedVault()
            result
        }
    }

    override suspend fun unlockWithBiometrics(reason: String, cancelTitle: String): Boolean = withContext(main) {
        try {
            runtime.unlockWithBiometrics()
            if (runtime.lockState.value != LockState.UNLOCKED) return@withContext false
            recordUnrecordedBackup()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun eraseEverything() = withContext(main) {
        forgetErasedVault()
        runtime.eraseEverything()
    }

    /**
     * Everything the service keeps beside the runtime goes with an erased vault (“Her şeyi sil”, or
     * the optional wipe after 10 wrong codes): live-location updates, preview, recording and picked
     * files, an opened backup with its password, caches.
     */
    private fun forgetErasedVault() {
        sharing.forget()
        ProtectedPreviewFiles.removeAll()
        VoiceRecordingFiles.removeAll()
        outgoingMedia.removeAll()
        pickers.removeAllPicked()
        dropPendingRestore()
        unrestorable = null
        clearCaches()
        forgetExportedBackups()
    }

    override suspend fun deleteAccountOnRelay() {
        try {
            runtime.deleteAccountOnRelay()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw MessengerException.RelayUnreachable(e.javaClass.simpleName)
        }
    }

    // MARK: Lifecycle

    override fun scenePhaseChanged(phase: MessengerScenePhase) {
        when (phase) {
            MessengerScenePhase.Active -> {
                runtime.scenePhaseChanged(AppPhase.ACTIVE)
                sharing.appReturned()
                outgoingMedia.didBecomeActive()
                communityChats.foregroundChanged(true)
            }
            MessengerScenePhase.Inactive -> runtime.scenePhaseChanged(AppPhase.INACTIVE)
            MessengerScenePhase.Background -> {
                runtime.scenePhaseChanged(AppPhase.BACKGROUND)
                sharing.appLeft()
                clearCaches()
                outgoingMedia.didEnterBackground()
                communityChats.foregroundChanged(false)
            }
        }
    }

    private fun clearCaches() {
        communityChats.clearCaches()
        liveTicks = emptyMap()
        media.value = emptyMap()
        mediaLoading.clear()
        downloads.value = emptyMap()
        listedMedia.clear()
        avatarAccounts.clear()
        searchedAvatars.value = emptyMap()
        revealedAvatars.clear()
    }

    // MARK: Restore access (§5.5 step 3.4)

    override val restoreAccessPending: StateFlow<Boolean> get() = runtime.tokenRotationPending

    // The runtime's pasteboard (audit 2026-10-28 L-8): cleared on lock and background, not only by a timer.
    override fun copyToClipboard(text: String): Boolean =
        runtime.pasteboard.copy(text, com.syconx.us2us.runtime.support.SecurePasteboard.Sensitivity.ORDINARY)

    override val deviceProtection: StateFlow<DeviceProtection> by lazy {
        runtime.deviceProtection.mapState(LiveMapping::deviceProtection)
    }

    override suspend fun checkRestoreAccess(): RestoreAccessCheck = LiveOutcomes.restoreAccess(runtime.checkPendingTokenRotation())

    // MARK: Line & chats

    override val connection: StateFlow<ConnectionState> = runtime.connection.mapState { LiveMapping.connection(LiveConnection.of(it)) }

    override val chats: StateFlow<List<ChatSummary>> = chatsState.asStateFlow()

    override val requests: StateFlow<List<MessageRequest>> = requestsState.asStateFlow()

    private fun recomputeChats() {
        val own = runtime.ownAccountId
        val presence = runtime.presence.value
        val sharing = runtime.settings.value.presenceSharing
        val changed = runtime.safetyNumberChanges.value
        val searched = searchedAvatars.value
        val date = now
        val source = runtime.chats.value
        val direct = source.map { summary ->
            var record = LiveChatRecord.of(summary, own)
            if (summary.id in changed) record = record.copy(safetyNumberChanged = true)
            if (record.requestState == LiveRequestState.OutgoingRequest) searched[record.peer]?.let { record = record.copy(avatar = it) }
            LiveMapping.chatSummary(record, presence[summary.id]?.let(LivePresence::of), date, sharing)
                .copy(lastMessageActivity = summary.lastMessage?.storedAt)
        }
        // Communities take their place among the chats by last activity.
        chatsState.value = LiveCommunityMapping.mixed(direct, communityChats.summaries.value)
    }

    private fun recomputeRequests() {
        val own = runtime.ownAccountId
        val date = now
        requestsState.value = runtime.requests.value.map { LiveMapping.request(LiveChatRecord.of(it, own), date) }
    }

    override suspend fun lookup(username: String): UserLookup = withContext(main) {
        val normalized = Username.parse(username) ?: return@withContext UserLookup.NotFound
        val entry = try {
            runtime.lookupAccount(username)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return@withContext UserLookup.NotFound
        rememberLookup(username, entry.account)
        // The chat of the account the directory answered with, never one that only shows the name.
        chat(ChatId(entry.account.toString())).value?.let { return@withContext UserLookup.Found(it.peer, existingChat = it.id) }
        UserLookup.Found(
            Peer(
                username = normalized.value,
                palette = AvatarPalette.forUsername(normalized.value),
                isVerified = false,
                isAcceptedContact = false,
                presence = Presence.Hidden,
            ),
        )
    }

    override suspend fun accept(request: MessageRequest): ChatId? {
        val peer = account(request.id.value) ?: return null
        return try {
            runtime.acceptRequest(peer)
            listed(ChatId(request.id.value))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun block(request: MessageRequest) {
        val peer = account(request.id.value) ?: return
        quietly { block(peer) }
    }

    override suspend fun delete(request: MessageRequest) {
        val peer = account(request.id.value) ?: return
        quietly { runtime.declineRequest(peer) }
    }

    // MARK: Blocking and reports

    override suspend fun block(chat: ChatId): Boolean {
        val peer = account(chat) ?: return false
        return succeeds { block(peer) }
    }

    /**
     * A live location shared with the person ends first: its stop is queued (the block then cancels
     * it with everything else waiting for them), the bubble here stops, and with the last share so
     * do the location updates: nothing keeps tracking for someone blocked.
     */
    private suspend fun block(peer: AccountId) = withContext(main) {
        sharing.stopSharingWith(peer)
        runtime.block(peer)
    }

    // MARK: Deleting

    override suspend fun deleteChat(chat: ChatId): Boolean {
        val peer = account(chat) ?: return false
        return succeeds {
            withContext(main) { forgetChat(peer, chat) }
            runtime.deleteConversation(peer, forBoth = false)
        }
    }

    override suspend fun deleteContact(chat: ChatId): Boolean {
        val peer = account(chat) ?: return false
        return succeeds {
            withContext(main) { forgetChat(peer, chat) }
            runtime.deleteContact(peer)
            withContext(main) {
                avatarAccounts.values.removeAll { it == peer }
                revealedAvatars.remove(chat.value)
            }
        }
    }

    /**
     * What this service holds for a chat being deleted: a live location shared in it ends here
     * (nothing keeps tracking for a chat that is gone; the runtime's deletion sends its stop, so it
     * is not asked to stop it first), photos and videos still being prepared for it are dropped
     * with their picked copies, and the decrypted media, downloads and search picture kept for it go.
     */
    private fun forgetChat(peer: AccountId, chat: ChatId) {
        sharing.forgetSharesWith(peer)
        for (item in outgoingMedia.items(chat)) outgoingMedia.remove(item.id)
        listedMedia.values.removeAll { it.first == peer }
        searchedAvatars.value = searchedAvatars.value - chat.value
        if (runtime.openPeer.value == peer) {
            liveTicks = emptyMap()
            media.value = emptyMap()
            mediaLoading.clear()
            downloads.value = emptyMap()
        }
    }

    override suspend fun unblock(chat: ChatId): Boolean {
        val peer = account(chat) ?: return false
        return succeeds { runtime.unblock(peer) }
    }

    /** The runtime's blocked contacts, also those without a conversation. */
    override val blockedPeople: StateFlow<List<BlockedPerson>> = blockedState.asStateFlow()

    private fun recomputeBlocked() {
        blockedState.value = LiveMapping.blockedPeople(runtime.blockedContacts.value.map { LiveBlockedRecord.of(it) })
    }

    /** The chat id is the account: the runtime unblocks it with a conversation or without one. */
    override suspend fun unblock(person: BlockedPerson): Boolean = unblock(person.id)

    override fun reportSubject(chat: ChatId): com.syconx.us2us.domain.ReportSubject? {
        val summary = runtime.chats.value.firstOrNull { it.id.toString() == chat.value } ?: return null
        return LiveMapping.reportSubject(LiveChatRecord.of(summary, runtime.ownAccountId))
    }

    override fun reportSubject(request: MessageRequest): com.syconx.us2us.domain.ReportSubject? {
        val summary = runtime.requests.value.firstOrNull { it.id.toString() == request.id.value } ?: return null
        return LiveMapping.reportSubject(LiveChatRecord.of(summary, runtime.ownAccountId))
    }

    override suspend fun startChat(username: String): ChatId? = try {
        listed(ChatId(runtime.startConversation(username).toString()))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    override suspend fun ownContactCode(): String? {
        val username = runtime.ownUsername.value ?: return null
        val key = try {
            runtime.ownIdentityKey()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return ContactCode.encode(username, key?.let { LiveMapping.identityFingerprint(Bytes.wrap(it)) })
    }

    // MARK: Profile pictures (RealMessengerServiceAvatars.kt has the rest)

    override val ownAvatar: StateFlow<com.syconx.us2us.domain.OwnAvatarState> = ownAvatarState.asStateFlow()

    private fun recomputeOwnAvatar() {
        ownAvatarState.value = LiveMapping.ownAvatar(runtime.ownAvatar.value?.let(LiveOwnAvatar::of))
    }

    override suspend fun setAvatar(edit: com.syconx.us2us.domain.AvatarEdit) = LiveAvatars.setAvatar(runtime, edit)

    override suspend fun setAvatarShownInSearch(shown: Boolean): Boolean {
        quietly { runtime.setAvatarShownInSearch(shown) }
        return runtime.ownAvatar.value?.showInSearch ?: shown
    }

    override suspend fun searchAvatar(username: String): com.syconx.us2us.domain.AvatarPicture = withContext(main) {
        val normalized = Username.parse(username) ?: return@withContext com.syconx.us2us.domain.AvatarPicture.Initials
        val account = avatarAccounts[normalized.value] ?: return@withContext com.syconx.us2us.domain.AvatarPicture.Initials
        val lookup = runtime.avatar(normalized.value, account)
        // Kept for the user's own request to this account (§8.9), this session only.
        LiveAvatar.searchCopy(lookup)?.let { searchedAvatars.value = searchedAvatars.value + (account.toString() to it) }
        LiveMapping.avatar(LiveAvatar.of(lookup.picture))
    }

    override suspend fun revealAvatar(request: MessageRequest): com.syconx.us2us.domain.AvatarPicture = withContext(main) {
        val peer = account(request.id.value)
        if (!request.offersAvatar || peer == null) return@withContext com.syconx.us2us.domain.AvatarPicture.Initials
        // Once per request and session: the relay is not asked again.
        revealedAvatars[request.id.value]?.let { return@withContext LiveMapping.avatar(it) }
        val lookup = runtime.requestAvatar(peer, userConfirmed = true)
        val avatar = LiveAvatar.of(lookup.picture)
        revealedAvatars[request.id.value] = avatar
        LiveMapping.avatar(avatar)
    }

    override suspend fun refreshOwnAvatar() {
        runtime.currentAvatar()
    }

    /** An exact lookup found [username] (4.2): its picture may be fetched next. */
    private fun rememberLookup(username: String, account: AccountId) {
        val normalized = Username.parse(username) ?: return
        avatarAccounts[normalized.value] = account
    }

    /**
     * The user's own message request opened (5.1) without a search copy from this session: its
     * record is looked up once per session (§8.9: the account of the user's own request shows its
     * search copy; none found is remembered as the initial). Accepted contacts, incoming requests and
     * blocked contacts cost nothing here.
     */
    private suspend fun loadRequestAvatar(peer: AccountId) {
        val summary = runtime.chats.value.firstOrNull { it.id == peer } ?: return
        if (summary.contact.requestState != com.syconx.us2us.core.contacts.ContactState.OUTGOING_REQUEST) return
        if (searchedAvatars.value[peer.toString()] != null) return
        val lookup = runtime.avatar(peer)
        val avatar = if (lookup.source == com.syconx.us2us.runtime.engine.AvatarLookup.Source.SEARCH) LiveAvatar.of(lookup.picture) else LiveAvatar.Initials
        searchedAvatars.value = searchedAvatars.value + (peer.toString() to avatar)
    }

    // MARK: Conversation

    override fun chat(id: ChatId): StateFlow<ChatSummary?> =
        chatFlows.getOrPut(id) { chatsState.mapState { list -> list.firstOrNull { it.id == id } } }

    /** The chat's row as it is now. */
    private fun summary(id: ChatId): ChatSummary? = chatsState.value.firstOrNull { it.id == id }

    /**
     * [chat] once its row is in [chats]: the runtime refreshes its list a moment after a change, and
     * a screen opened on a chat that is not listed yet would close at once (5.1 leaves a chat that
     * is gone). Waits a few seconds at most.
     */
    private suspend fun listed(chat: ChatId): ChatId {
        withTimeoutOrNull(LISTING_WAIT) { chatsState.first { list -> list.any { it.id == chat } } }
        return chat
    }

    override suspend fun loadOlderMessages(chat: ChatId): Boolean = withContext(main) {
        if (communityChats.isCommunity(chat)) return@withContext communityChats.loadOlder(chat)
        val peer = account(chat) ?: return@withContext false
        runtime.loadOlderMessages(peer)
    }

    override suspend fun conversationAppeared(chat: ChatId) = withContext(main) {
        if (communityChats.isCommunity(chat)) return@withContext communityChats.appeared(chat)
        val peer = account(chat) ?: return@withContext
        runtime.openConversation(peer)
        loadRequestAvatar(peer)
    }

    override fun conversationDisappeared(chat: ChatId) {
        outgoingMedia.conversationClosed(chat)
        if (communityChats.isCommunity(chat)) return communityChats.disappeared(chat)
        if (runtime.openPeer.value?.toString() != chat.value) return
        runtime.closeConversation()
        liveTicks = emptyMap()
        media.value = emptyMap()
        mediaLoading.clear()
        downloads.value = emptyMap()
    }

    override fun messages(chat: ChatId): StateFlow<List<Message>> =
        if (communityChats.isCommunity(chat)) {
            communityChats.messages(chat)
        } else {
            messageFlows.getOrPut(chat) { openState.mapState { if (it.peer == chat.value) it.messages else emptyList() } }
        }

    override fun liveLocations(chat: ChatId): StateFlow<LiveLocationSnapshot> =
        liveLocationFlows.getOrPut(chat) { openState.mapState { if (it.peer == chat.value) it.liveLocations else LiveLocationSnapshot.Empty } }

    /** The open conversation as the screens show it, with its side effects (media loads, settled downloads, live ticks). */
    private fun recomputeOpen() {
        val peer = runtime.openPeer.value
        if (peer == null) {
            openState.value = OpenConversation(null, emptyList(), LiveLocationSnapshot.Empty)
            return
        }
        val chat = ChatId(peer.toString())
        val source = runtime.openMessages.value
        val current = currentDownloads()
        val own = runtime.ownAccountId
        val records = source.map { LiveMessageRecord.of(it, own) }
        val peerName = chatsState.value.firstOrNull { it.id == chat }?.peer?.username.orEmpty()
        // Read receipts are mutual: with them off, a voice message never shows that the other side played it either.
        val stored = LiveMapping.messages(
            records, runtime.ownUsername.value.orEmpty(), peerName, now, media.value, current,
            showsPeerPlayback = runtime.settings.value.readReceipts,
        )
        // Photos and videos picked here and still being prepared show where they were picked, in the order picked.
        val value = OutgoingMedia.merge(outgoingMedia.items(chat), stored, outgoingMedia.landed)
        val live = source.mapNotNull(LiveLocationRecord::of)
        liveTicks = LiveMapping.liveTicks(live, liveTicks, now)
        openState.value = OpenConversation(peer.toString(), value, LiveMapping.liveLocationSnapshot(live, liveTicks))
        loadMedia(source, peer)
        settleDownloads(source)
        outgoingMedia.settle(stored)
    }

    /** What is downloading: the user's requests and the runtime's progress. */
    private fun currentDownloads(): Map<MessageId, LiveDownload> =
        LiveMapping.downloads(downloads.value, runtime.downloadProgress.value.mapKeys { (id, _) -> id.toBytes() })

    /** Requested downloads that finished (or can no longer finish) are no longer shown as such. */
    private fun settleDownloads(items: List<MessageItem>) {
        if (downloads.value.isEmpty()) return
        val done = downloads.value.keys.filter { id ->
            val availability = items.firstOrNull { LiveMapping.messageId(it.messageId.toBytes()) == id }?.let(::attachment)?.availability
            availability != AttachmentItem.Availability.REMOTE && availability != AttachmentItem.Availability.TRANSFERRING
        }
        if (done.isEmpty()) return
        // Not in this pass: the conversation was just mapped from these values.
        scope.launch { downloads.value = downloads.value - done.toSet() }
    }

    private fun attachment(item: MessageItem): AttachmentItem? {
        if (item.viewOnce) return null
        return (item.body as? MessageBody.Attachment)?.attachment
    }

    /** Full images of the open conversation, decrypted into memory (and downloaded first when they are still on the relay). */
    private fun loadMedia(items: List<MessageItem>, peer: AccountId) {
        for (item in items) {
            if (item.viewOnce) continue
            val attachment = (item.body as? MessageBody.Attachment)?.attachment ?: continue
            if (attachment.kind != AttachmentItem.Kind.IMAGE) continue
            val id = LiveMapping.messageId(item.messageId.toBytes())
            if (media.value[id] != null || id in mediaLoading) continue
            when (attachment.availability) {
                AttachmentItem.Availability.LOCAL -> {
                    mediaLoading += id
                    scope.launch {
                        val data = try {
                            runtime.mediaData(item.messageId, peer)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        if (runtime.openPeer.value != peer) return@launch
                        if (data == null) {
                            // Not loaded: the next change of the conversation tries again.
                            mediaLoading -= id
                            return@launch
                        }
                        media.value = media.value + (id to Bytes.wrap(data))
                    }
                }
                AttachmentItem.Availability.REMOTE -> {
                    mediaLoading += id
                    scope.launch {
                        quietly { runtime.download(item.messageId, peer) }
                        mediaLoading -= id
                    }
                }
                else -> Unit
            }
        }
    }

    // MARK: Media of the open conversation

    override suspend fun mediaPayload(message: MessageId, chat: ChatId): MediaPayload? = withContext(main) {
        if (communityChats.isCommunity(chat)) return@withContext communityChats.mediaPayload(message, chat)
        val peer = account(chat) ?: return@withContext null
        val id = LiveMapping.messageData(message) ?: return@withContext null
        val attachment = openAttachment(id, peer) ?: return@withContext null
        // A “voice message” far larger than any recording is never decrypted into memory (nor downloaded for it): it shows as unplayable.
        if (attachment.kind == AttachmentItem.Kind.VOICE && attachment.size > VoiceNoteRules.MAXIMUM_PLAYABLE_BYTES) return@withContext null
        val isImage = attachment.kind == AttachmentItem.Kind.IMAGE || attachment.kind == AttachmentItem.Kind.STICKER_IMAGE
        if (isImage) media.value[message]?.let { return@withContext MediaPayload(it, attachment.contentType, attachment.fileName) }
        if (!waitUntilStored(id, peer)) return@withContext null
        val data = try {
            runtime.mediaData(id.toByteString(), peer)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        // Photos stay for the bubble too; video and file bytes belong to the player or the preview that asked, and go with it.
        val bytes = Bytes.wrap(data)
        if (isImage && runtime.openPeer.value == peer) media.value = media.value + (message to bytes)
        MediaPayload(bytes, attachment.contentType, attachment.fileName)
    }

    override suspend fun downloadMedia(message: MessageId, chat: ChatId) = withContext(main) {
        if (communityChats.isCommunity(chat)) return@withContext communityChats.downloadMedia(message, chat)
        val peer = account(chat) ?: return@withContext
        val id = LiveMapping.messageData(message) ?: return@withContext
        val attachment = openAttachment(id, peer) ?: return@withContext
        when (attachment.availability) {
            AttachmentItem.Availability.REMOTE, AttachmentItem.Availability.UNAVAILABLE -> {
                if (downloads.value[message] == null) downloads.value = downloads.value + (message to LiveDownload())
                quietly { runtime.download(id.toByteString(), peer) }
            }
            else -> Unit
        }
    }

    /** The attachment of a message in the open conversation, or of one the media list returned (never view-once). */
    private fun openAttachment(id: Bytes, peer: AccountId): AttachmentItem? {
        if (runtime.openPeer.value == peer) {
            runtime.openMessages.value.lastOrNull { it.messageId.toBytes() == id }?.let { return attachment(it) }
        }
        val listed = listedMedia[id] ?: return null
        if (listed.first != peer) return null
        return attachment(listed.second)
    }

    /** A message as it is now: in the open conversation, or (an item of the media list outside it) read again from storage. */
    private suspend fun currentItem(id: Bytes, peer: AccountId): MessageItem? {
        if (runtime.openPeer.value == peer) {
            runtime.openMessages.value.lastOrNull { it.messageId.toBytes() == id }?.let { return it }
        }
        if (listedMedia[id]?.first != peer) return null
        val item = try {
            runtime.messageItem(id.toByteString(), peer)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        listedMedia[id] = peer to item
        return item
    }

    /**
     * Waits until the attachment is stored on this phone, downloading it first when it is still on
     * the relay. `false` when it never can be (held, expired, failed, gone), the conversation closed
     * (an item of the media list is followed in storage instead), or the calling coroutine was
     * cancelled.
     */
    private suspend fun waitUntilStored(id: Bytes, peer: AccountId): Boolean {
        val message = LiveMapping.messageId(id)
        var requested = false
        val deadline = now.plus(WAIT_FOR_DOWNLOAD.toJavaDuration())
        while (currentCoroutineContext().isActive && now.isBefore(deadline)) {
            val item = currentItem(id, peer) ?: return false
            val attachment = attachment(item) ?: return false
            when (attachment.availability) {
                AttachmentItem.Availability.LOCAL -> return true
                // Uploading: the file is here.
                AttachmentItem.Availability.TRANSFERRING -> if (item.direction == RuntimeDirection.OUTGOING) return true
                AttachmentItem.Availability.REMOTE -> if (!requested) {
                    requested = true
                    if (downloads.value[message] == null) downloads.value = downloads.value + (message to LiveDownload())
                    quietly { runtime.download(id.toByteString(), peer) }
                }
                AttachmentItem.Availability.HELD_UNTIL_ACCEPTED, AttachmentItem.Availability.UNAVAILABLE -> return false
            }
            delay(250.milliseconds)
        }
        return false
    }

    override suspend fun sendText(text: String, replyTo: QuotedMessage?, chat: ChatId) {
        if (communityChats.isCommunity(chat)) return withContext(main) { communityChats.sendText(text, chat) }
        val peer = account(chat) ?: return
        quietly { runtime.sendText(text, peer, quote(replyTo)) }
    }

    private fun quote(replyTo: QuotedMessage?): QuoteInfo? {
        replyTo ?: return null
        val target = replyTo.messageId?.let(LiveMapping::messageData) ?: return null
        return QuoteInfo(target.toByteString(), quoteText(replyTo))
    }

    override suspend fun sendAttachment(attachment: ConversationAttachment, chat: ChatId) = withContext(main) {
        // A community takes photos, videos and files (Extension v2); places and live location stay one-to-one.
        val community = communityChats.isCommunity(chat)
        val peer = account(chat)
        if (peer == null && !community) return@withContext
        if (community && (attachment is ConversationAttachment.Location || attachment is ConversationAttachment.LiveLocation)) return@withContext
        val disappearing = summary(chat)?.disappearing ?: DisappearingTimer.Off
        when (attachment) {
            is ConversationAttachment.Photo -> {
                val uri = (attachment.source as? MediaSource.File)?.uri ?: return@withContext // The design's sample content exists only in the fixtures.
                // Its bubble is up at once (“Hazırlanıyor”); preparing and sending follow.
                val id = outgoingMedia.begin(PickedMediaItem.Kind.Photo, PickedMediaPlan.usableCaption(attachment.caption), chat, disappearing)
                outgoingMedia.provide(picked(uri), id)
            }
            is ConversationAttachment.Video -> {
                val id = outgoingMedia.begin(PickedMediaItem.Kind.Video, null, chat, disappearing)
                outgoingMedia.provide(picked(attachment.uri), id)
            }
            is ConversationAttachment.Document -> {
                val file = picked(attachment.uri) ?: return@withContext
                try {
                    val type = contentType(file, attachment.uri)
                    val prepared = runtime.prepareFile(context, MediaInput.of(file), file.name, type)
                    try {
                        if (community) communityChats.sendMedia(prepared, chat) else runtime.sendMedia(prepared, peer!!)
                    } catch (e: Exception) {
                        prepared.file.delete()
                        throw e
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                } finally {
                    pickers.discardPicked(file)
                }
            }
            is ConversationAttachment.Location -> quietly {
                if (peer == null) return@quietly
                runtime.sendLocation(
                    LocationInfo(
                        latitude = attachment.latitude,
                        longitude = attachment.longitude,
                        accuracyMeters = 0f,
                        name = attachment.name.ifEmpty { null },
                        address = attachment.locality.ifEmpty { null },
                    ),
                    peer,
                )
            }
            is ConversationAttachment.LiveLocation -> if (peer != null) sharing.start(peer, attachment.duration)
            // The design's sample content exists only in the fixtures.
            is ConversationAttachment.File -> Unit
        }
    }

    /** A picker's URI as a private copy in the pick folder (off the main thread); `null` when it cannot be read. */
    private suspend fun picked(uri: URI): File? = withContext(io) { pickers.copy(uri, context.contentResolver) }

    /** The content type of a picked file, for the runtime's metadata rules. */
    private fun contentType(file: File, source: URI): String {
        if (source.scheme == "content") {
            runCatching { context.contentResolver.getType(source.toString().toUri()) }.getOrNull()?.let { return it }
        }
        val extension = file.extension.lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    /**
     * A built-in sticker: `Sticker { pack_id, sticker_id, emoji, lang }` inside the E2EE message (the
     * relay never learns which). The recents (ids only) go to the vault's encrypted runtime state.
     */
    override suspend fun sendSticker(sticker: BrandStickerSelection, replyTo: QuotedMessage?, chat: ChatId) {
        val community = LiveCommunityMapping.community(chat)
        if (community != null) {
            val wire = com.syconx.us2us.core.proto.Sticker.newBuilder().setPackId(sticker.sticker.packId)
                .setStickerId(sticker.sticker.id).setEmoji(sticker.sticker.emoji).setLang(sticker.wireLanguage).build()
            if (succeeds { runtime.withCommunities { it.sendSticker(community, wire) } }) quietly { runtime.noteRecentSticker(sticker.id) }
            return
        }
        val peer = account(chat) ?: return
        val sent = succeeds {
            runtime.sendSticker(
                packId = sticker.sticker.packId,
                stickerId = sticker.sticker.id,
                emoji = sticker.sticker.emoji,
                lang = sticker.wireLanguage,
                quote = quote(replyTo),
                peer = peer,
            )
        }
        // One step inside the engine: two stickers sent close together both land.
        if (sent) quietly { runtime.noteRecentSticker(sticker.id) }
    }

    override suspend fun recentStickers(): List<String> = try {
        runtime.recentStickers()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * The recording goes to the runtime from memory: written to a staging file that storing it
     * encrypts and deletes at once, under a bubble that shows at once; the outbox uploads and sends
     * it. `false` when it could not be stored.
     */
    override suspend fun sendVoice(voice: VoiceRecording, replyTo: QuotedMessage?, chat: ChatId): Boolean {
        val community = communityChats.isCommunity(chat)
        val peer = if (community) null else account(chat) ?: return false
        val prepared = try {
            runtime.prepareVoiceNote(voice.audio.toByteArray(), voice.durationMilliseconds.toInt(), voice.waveform.toByteArray())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        return try {
            if (community) communityChats.sendMedia(prepared, chat) else runtime.sendMedia(prepared, peer!!, quote = quote(replyTo))
            true
        } catch (e: CancellationException) {
            prepared.file.delete()
            throw e
        } catch (_: Exception) {
            // Not stored (locked meanwhile, a refused contact): the staged copy goes now.
            prepared.file.delete()
            false
        }
    }

    override suspend fun markVoicePlayed(message: MessageId, chat: ChatId) {
        if (communityChats.isCommunity(chat)) return // v3 has no PLAYED receipt.
        val peer = account(chat) ?: return
        val id = LiveMapping.messageData(message) ?: return
        quietly { runtime.markVoiceNotePlayed(id.toByteString(), peer) }
    }

    // MARK: Saved media and the media list (PROTOCOL §8.4)

    override suspend fun markSaved(message: MessageId, chat: ChatId) {
        val peer = account(chat) ?: return
        val id = LiveMapping.messageData(message) ?: return
        // View-once, disappearing and deleted items are refused by the runtime.
        quietly { runtime.markMediaSaved(id.toByteString(), peer) }
    }

    override suspend fun media(chat: ChatId, kind: ChatMediaKind, before: MessageId?, limit: Int): List<Message> = withContext(main) {
        val peer = account(chat)
        if (peer == null || limit <= 0) return@withContext emptyList()
        val cursor = if (before != null) LiveMapping.messageData(before) ?: return@withContext emptyList() else null
        val items = try {
            runtime.mediaMessages(peer, mediaKind(kind), cursor?.toByteString(), limit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext emptyList()
        }
        for (item in items) listedMedia[item.messageId.toBytes()] = peer to item
        val own = runtime.ownAccountId
        LiveMapping.messages(
            items.map { LiveMessageRecord.of(it, own) },
            runtime.ownUsername.value.orEmpty(),
            summary(chat)?.peer?.username.orEmpty(),
            now,
            media.value,
            currentDownloads(),
            showsPeerPlayback = runtime.settings.value.readReceipts,
        )
    }

    override fun mediaRevision(chat: ChatId): StateFlow<Int> = revisionFlows.getOrPut(chat) {
        val peer = account(chat)
        runtime.messageRevisions.mapState { revisions -> peer?.let { revisions[it] } ?: 0 }
    }

    /**
     * Every bubble first, in the order picked; each picked copy goes to its bubble as soon as the
     * picker has handed it over (a long or cloud item takes a while), and [OutgoingMedia] prepares
     * and stores them one at a time. The design's pictures (fixtures) have no place here.
     */
    override suspend fun sendPickedMedia(messages: List<PickedMediaMessage>, chat: ChatId) = withContext(main) {
        val disappearing = summary(chat)?.disappearing ?: DisappearingTimer.Off
        for (message in messages) {
            val copy = message.item.source as? PickedMediaItem.Source.Copy ?: continue
            val id = outgoingMedia.begin(message.item.kind, message.caption, chat, disappearing)
            scope.launch {
                val uri = try {
                    copy.file.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                outgoingMedia.provide(uri?.let { picked(it) }, id)
            }
        }
    }

    override suspend fun sendCurrentLocation(chat: ChatId) = withContext(main) {
        val peer = account(chat) ?: return@withContext
        val fix = location.currentLocation() ?: return@withContext
        quietly { runtime.sendLocation(LocationInfo(fix.latitude, fix.longitude, fix.accuracy), peer) }
    }

    override suspend fun resend(message: MessageId, chat: ChatId) = withContext(main) {
        // A photo or video whose preparing failed is prepared again (it never reached the runtime).
        if (outgoingMedia.resend(message)) return@withContext
        if (communityChats.isCommunity(chat)) return@withContext communityChats.resend(message, chat)
        val peer = account(chat) ?: return@withContext
        val id = LiveMapping.messageData(message) ?: return@withContext
        quietly { runtime.resend(id.toByteString(), peer) }
    }

    override suspend fun updateGhost(ghost: GhostState, disappearing: DisappearingTimer, chat: ChatId) {
        val peer = account(chat) ?: return
        val settings = if (ghost.isEnabled) GhostSettings(enabled = true, timeoutSeconds = LiveMapping.seconds(ghost.period)) else null
        quietly { runtime.setConversationSettings(peer, settings, LiveMapping.seconds(disappearing)) }
    }

    override suspend fun deleteForEveryone(message: MessageId, chat: ChatId) = withContext(main) {
        // A photo or video still being prepared never left the phone: it just goes.
        if (outgoingMedia.remove(message)) return@withContext
        if (communityChats.isCommunity(chat)) return@withContext communityChats.deleteForEveryone(message, chat)
        val peer = account(chat) ?: return@withContext
        val id = LiveMapping.messageData(message) ?: return@withContext
        quietly { runtime.deleteForEveryone(id.toByteString(), peer) }
    }

    override suspend fun deleteForMe(message: MessageId, chat: ChatId) = withContext(main) {
        if (outgoingMedia.remove(message)) return@withContext
        if (communityChats.isCommunity(chat)) return@withContext communityChats.deleteForMe(message, chat)
        val peer = account(chat) ?: return@withContext
        val id = LiveMapping.messageData(message) ?: return@withContext
        quietly { runtime.deleteForMe(id.toByteString(), peer) }
    }

    override suspend fun messageRecipients(message: MessageId, chat: ChatId): List<com.syconx.us2us.domain.MessageRecipient> =
        withContext(main) { communityChats.recipients(message, chat) }

    override suspend fun setReaction(emoji: String?, message: MessageId, chat: ChatId) {
        val peer = account(chat) ?: return
        val id = LiveMapping.messageData(message) ?: return
        quietly { runtime.react(id.toByteString(), peer, emoji) }
    }

    override suspend fun stopLiveLocation(message: MessageId, chat: ChatId) = withContext(main) {
        val id = LiveMapping.messageData(message) ?: return@withContext
        val item = runtime.openMessages.value.firstOrNull { it.messageId.toBytes() == id } ?: return@withContext
        val live = (item.body as? MessageBody.LiveLocation)?.location ?: return@withContext
        if (!sharing.stop(live.sessionId)) {
            account(chat)?.let { peer -> quietly { runtime.stopLiveLocation(live.sessionId, peer) } }
        }
    }

    override fun ownPositionUpdates(): Flow<OwnPositionState> = positions.updates()

    override suspend fun requestOwnPositionAccess(): Boolean = withContext(main) { positions.requestAccess() }

    override fun liveSharingContinuesInBackground(): Boolean = sharingNotice.canBeSeen()

    override suspend fun allowLiveSharingNotice(): NoticePermission = withContext(main) { sharingNotice.allow() }

    override suspend fun viewOnceMedia(message: MessageId, chat: ChatId): MediaSource? {
        val peer = account(chat) ?: return null
        val id = LiveMapping.messageData(message) ?: return null
        val data = try {
            runtime.openViewOnceData(id.toByteString(), peer)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return MediaSource.Memory(Bytes.wrap(data))
    }

    override suspend fun markViewOnceOpened(message: MessageId, chat: ChatId) {
        val peer = account(chat) ?: return
        val id = LiveMapping.messageData(message) ?: return
        quietly { runtime.closeViewOnce(id.toByteString(), peer, null) }
    }

    override suspend fun reportScreenshot(message: MessageId, chat: ChatId) {
        // The runtime hears the system's screenshot callback itself and posts the line in the open
        // conversation (one line, not two).
    }

    // MARK: Verification

    override suspend fun safetyNumber(chat: ChatId): SafetyNumberInfo? {
        val peer = account(chat) ?: return null
        val number = try {
            runtime.safetyNumber(peer)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        // The verification, its date and its method are stored with the key (they survive
        // relaunches and go when the key changes). A verification without a record shows no date,
        // never a made-up one.
        val trust = try {
            runtime.identityTrust(peer)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val verified = (trust as? ContactIdentity.Trusted)?.verified ?: false
        val changed = trust is ContactIdentity.Changed
        val record = if (verified) {
            try {
                runtime.identityVerification(peer)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        return SafetyNumberInfo(
            groups = number.groups,
            scannablePayload = Bytes.wrap(number.scannable),
            verifiedAt = record?.verifiedAt,
            method = record?.let {
                when (it.method) {
                    IdentityVerificationMethod.MANUAL -> VerificationMethod.Manual
                    IdentityVerificationMethod.QR_CODE -> VerificationMethod.FaceToFace
                }
            },
            changedAt = if (changed) now else null,
            isVerifiedWithoutRecord = verified && record == null,
        )
    }

    override suspend fun markVerified(chat: ChatId, number: Bytes): Boolean {
        val peer = account(chat) ?: return false
        return valueOr(false) { runtime.markVerified(peer, number.toByteArray()) }
    }

    override suspend fun clearVerification(chat: ChatId) {
        val peer = account(chat) ?: return
        quietly { runtime.clearVerification(peer) }
    }

    override suspend fun verifyScannedSafetyCode(code: String, chat: ChatId): SafetyCodeComparison {
        val payload = SafetyQRCodec.decode(code) ?: return SafetyCodeComparison.NotASafetyCode
        val peer = account(chat) ?: return SafetyCodeComparison.Mismatch
        // Compared with the current number and verified in one step.
        return if (valueOr(false) { runtime.verifySafetyNumber(peer, payload.toByteArray()) }) SafetyCodeComparison.Match else SafetyCodeComparison.Mismatch
    }

    override suspend fun acceptSafetyNumberChange(chat: ChatId, number: Bytes): Boolean {
        val peer = account(chat) ?: return false
        return valueOr(false) { runtime.acceptSafetyNumberChange(peer, number.toByteArray()) }
    }

    override suspend fun resolveContactCode(code: String): ContactCodeResolution {
        val decoded = ContactCode.decode(code) ?: return ContactCodeResolution.NotAContactCode
        if (decoded.username == ownUsername.value) return ContactCodeResolution.OwnCode
        if (Username.parse(decoded.username) == null) return ContactCodeResolution.Unknown
        val entry = try {
            runtime.lookupAccount(decoded.username)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No answer is not “nobody found”: the user may well exist.
            return ContactCodeResolution.LookupFailed
        } ?: return ContactCodeResolution.Unknown
        val fingerprint = decoded.keyFingerprint
        if (fingerprint != null) {
            // The relay must serve the key the code commits to: another key is what a relay swapping
            // keys looks like, never an unknown code (PROTOCOL §5 › Contact code) …
            if (!LiveMapping.fingerprintMatches(fingerprint, Bytes.wrap(entry.identityKey))) return ContactCodeResolution.KeyMismatch
            // … and this phone must not hold another key for the contact.
            val stored = storedIdentityKey(entry.account)
            if (stored != null && !LiveMapping.fingerprintMatches(fingerprint, stored)) return ContactCodeResolution.KeyMismatch
        }
        return ContactCodeResolution.Contact(decoded.username)
    }

    /** The contact's current identity key as this phone holds it, if any. */
    private fun storedIdentityKey(peer: AccountId): Bytes? =
        (runtime.chats.value + runtime.requests.value).firstOrNull { it.contact.account == peer }?.contact?.identityKey?.toBytes()

    override suspend fun addContact(code: String): ContactCodeAddition {
        val decoded = ContactCode.decode(code) ?: return ContactCodeAddition.Failed
        if (decoded.username == ownUsername.value) return ContactCodeAddition.Failed
        val entry = valueOr(null) { runtime.lookupAccount(decoded.username) } ?: return ContactCodeAddition.Failed
        val fingerprint = decoded.keyFingerprint
        val peer = entry.account
        if (fingerprint == null) {
            // A code without the key's fingerprint (older versions) names the user but proves nothing about the key: added, not verified.
            valueOr(null) { runtime.addContactFromCode(decoded.username, entry, pinKey = false) } ?: return ContactCodeAddition.Failed
            return ContactCodeAddition.Added(listed(ChatId(peer.toString())))
        }
        // The relay must serve the very key the code commits to; otherwise nobody is added.
        if (!LiveMapping.fingerprintMatches(fingerprint, Bytes.wrap(entry.identityKey))) return ContactCodeAddition.Mismatch
        // One lookup only: the contact is created for the account whose key was just checked, and
        // that key is pinned (a different one later, from a bundle or a message, is a safety-number
        // change) before anything is created. Another key already stored for that account adds
        // nothing (§5, audit 2026-10-28 M-10).
        val added = try {
            runtime.addContactFromCode(decoded.username, entry, pinKey = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return ContactCodeAddition.Failed
        }
        return if (added) ContactCodeAddition.Verified(listed(ChatId(peer.toString()))) else ContactCodeAddition.Mismatch
    }

    // MARK: Settings & backup

    override val settings: StateFlow<AppSettings> = settingsState.asStateFlow()

    private fun recomputeSettings() {
        val current = runtime.settings.value
        settingsState.value = AppSettings(
            presenceVisibility = if (current.presenceSharing) PresenceVisibility.AcceptedContacts else PresenceVisibility.Nobody,
            readReceipts = current.readReceipts,
            faceIdEnabled = runtime.quickUnlock.value != QuickUnlockStatus.NotEnabled,
            defaultGhost = current.defaultGhost?.takeIf { it.enabled }?.let { LiveMapping.ghostPeriod(it.timeoutSeconds) },
            defaultDisappearing = LiveMapping.disappearing(current.defaultExpireTimerSeconds),
            // Kept in the vault's encrypted runtime state: it survives relaunches.
            lastBackupAt = runtime.lastBackupAt.value,
        )
    }

    override suspend fun updateSettings(settings: AppSettings) = withContext(main) {
        val old = settingsState.value
        if (settings.presenceVisibility != old.presenceVisibility) {
            quietly { runtime.setPresenceSharing(settings.presenceVisibility == PresenceVisibility.AcceptedContacts) }
        }
        if (settings.readReceipts != old.readReceipts) quietly { runtime.setReadReceipts(settings.readReceipts) }
        if (settings.defaultGhost != old.defaultGhost || settings.defaultDisappearing != old.defaultDisappearing) {
            quietly {
                runtime.setDefaults(
                    ghost = settings.defaultGhost?.let { GhostSettings(enabled = true, timeoutSeconds = LiveMapping.seconds(it)) },
                    expireTimerSeconds = LiveMapping.seconds(settings.defaultDisappearing),
                )
            }
        }
        // `lastBackupAt` is not a preference: only a saved backup sets it (finishExport).
        recomputeChats()
    }

    override suspend fun backupEstimate(): BackupEstimate {
        val day = LocalDate.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT))
        val count = runtime.chats.value.size
        // The account backup holds the account, its identity key and the contacts, never messages, media or settings (PROTOCOL §5.5).
        return BackupEstimate(
            fileName = "us2us-$day.${BackupContents.FILE_EXTENSION}",
            chatCount = count,
            messageCount = 0,
            mediaBytes = 0,
            totalBytes = 2_048L + 256L * count,
        )
    }

    override suspend fun setBiometricUnlock(enabled: Boolean, reason: String): Boolean {
        if (!enabled) {
            quietly { runtime.disableQuickUnlock() }
            return false
        }
        // Turning it on needs the code (the key is wrapped from the vault): without it the setting stays as it is.
        return runtime.quickUnlock.value != QuickUnlockStatus.NotEnabled
    }

    /**
     * Quick unlock is off, and this phone's biometrics can be used now: turning it on needs the code
     * once. Nothing enrolled or locked out: `false`, so 7.1 says it could not be turned on at once
     * instead of asking for a code that cannot help.
     */
    override val biometricUnlockNeedsCode: Boolean
        get() = runtime.quickUnlock.value == QuickUnlockStatus.NotEnabled && runtime.quickUnlockAvailable

    override suspend fun enableBiometricUnlock(code: String): UnlockAttemptResult = withContext(main) {
        if (!SecretCodeRules.isWellFormed(code)) return@withContext UnlockAttemptResult.Malformed
        try {
            runtime.enableQuickUnlock(code)
            UnlockAttemptResult.Unlocked
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Biometrics not usable right now (nothing enrolled, locked out) is refused before the
            // code is checked, and maps to VaultUnavailable like any other failure that is no wrong code.
            val result = LiveOutcomes.attemptResult(e, unlockStatus.value)
            if (result == UnlockAttemptResult.Wiped) forgetErasedVault()
            result
        }
    }

    override suspend fun verifySecretCode(code: String): UnlockAttemptResult = withContext(main) {
        if (!SecretCodeRules.isWellFormed(code)) return@withContext UnlockAttemptResult.Malformed
        try {
            runtime.verifyCode(code)
            UnlockAttemptResult.Unlocked
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val result = LiveOutcomes.attemptResult(e, unlockStatus.value)
            if (result == UnlockAttemptResult.Wiped) forgetErasedVault()
            result
        }
    }

    override suspend fun changeSecretCode(current: String, new: String) {
        try {
            runtime.changeCode(current, new, new)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VaultException) {
            // The 10th wrong current code erased everything (the runtime already did): what the service kept goes too.
            if (e.error == VaultError.Wiped) forgetErasedVault()
            throw MessengerException.ChangeSecretCode(
                if (e.error == VaultError.WrongCode) ChangeSecretCodeError.WrongCurrentCode else ChangeSecretCodeError.Failed,
            )
        } catch (_: Exception) {
            throw MessengerException.ChangeSecretCode(ChangeSecretCodeError.Failed)
        }
    }

    /**
     * Only six-digit passwords are asked about, and the backup password rules (≥ 10 characters)
     * refuse those anyway; comparing with the code would need a counted vault attempt, which this
     * must never be.
     */
    override suspend fun backupPasswordIsSecretCode(password: String): Boolean = false

    override fun backupPasswordProblem(password: String): BackupPasswordError? = LiveOutcomes.backupPasswordProblem(password)

    override fun generateBackupPassword(): String = LiveOutcomes.generateBackupPassword()

    override suspend fun exportBackup(contents: BackupContents, password: String, fileName: String, progress: (Double) -> Unit): URI = withContext(main) {
        progress(0.05)
        val ticker = scope.launch {
            var fraction = 0.05
            while (isActive && fraction < 0.9) {
                delay(80.milliseconds)
                fraction = minOf(0.9, fraction + 0.03)
                progress(fraction)
            }
        }
        try {
            val vault = vaultGeneration
            val staged = try {
                runtime.exportBackup(password, password)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LiveOutcomes.exportPasswordError(e)?.let { throw MessengerException.BackupPassword(it) }
                throw e
            }
            val named = File(staged.parentFile, FileNameNames.backup(fileName))
            if (named != staged) {
                withContext(io) {
                    named.delete()
                    if (!staged.renameTo(named)) {
                        staged.copyTo(named, overwrite = true)
                        staged.delete()
                    }
                }
            }
            val uri = named.toURI()
            exportedBackups[uri] = vault
            progress(1.0)
            uri
        } finally {
            ticker.cancel()
        }
    }

    override suspend fun finishExport(uri: URI, saved: Boolean) = withContext(main) {
        withContext(io) { runCatching { File(uri).delete() } }
        val vault = exportedBackups.remove(uri)
        // Only a file that was really saved counts, and only for the vault it came from; a cancelled share records nothing.
        if (saved && vault != null) recordBackup(now, vault)
    }

    /**
     * Records a saved backup in the vault; when its store is already closed (the app left the
     * foreground and locked), at the next unlock. Never for another vault than [vault].
     */
    private suspend fun recordBackup(date: Instant, vault: Int) {
        if (vault != vaultGeneration) return
        try {
            runtime.recordBackupSaved(date)
            if (unrecordedBackup?.second == vault) unrecordedBackup = null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (vault == vaultGeneration) unrecordedBackup = date to vault
        }
    }

    /** The backup saved while the vault was closed, now that it is open. */
    private suspend fun recordUnrecordedBackup() {
        val pending = unrecordedBackup ?: return
        recordBackup(pending.first, pending.second)
    }

    /** This phone's vault is erased or replaced: backups exported from it are never recorded for the next one. */
    private fun forgetExportedBackups() {
        vaultGeneration += 1
        exportedBackups.clear()
        unrecordedBackup = null
    }

    override suspend fun inspectBackup(uri: URI): BackupFileInfo = withContext(io) {
        val directory = File(context.cacheDir, "backup-import")
        directory.deleteRecursively()
        directory.mkdirs()
        val destination = File(directory, "restore.u2u")
        var name: String? = null
        var size: Long? = null
        var created: Instant? = null
        val input = try {
            if (uri.scheme == "file") {
                val file = File(uri)
                name = file.name
                created = file.lastModified().takeIf { it > 0 }?.let(Instant::ofEpochMilli)
                file.inputStream()
            } else {
                val android = uri.toString().toUri()
                name = SystemPickers.displayName(context.contentResolver, android)
                size = SystemPickers.size(context.contentResolver, android)
                context.contentResolver.openInputStream(android)
            }
        } catch (_: Exception) {
            null
        } ?: throw MessengerException.BackupFile(BackupFileError.Unreadable)
        val header = ByteArray(8)
        var headerLength = 0
        try {
            input.use { stream ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        if (headerLength < header.size) {
                            val take = minOf(read, header.size - headerLength)
                            System.arraycopy(buffer, 0, header, headerLength, take)
                            headerLength += take
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (_: Exception) {
            directory.deleteRecursively()
            throw MessengerException.BackupFile(BackupFileError.Unreadable)
        }
        if (!LiveMapping.looksLikeBackup(header.copyOf(headerLength))) {
            directory.deleteRecursively()
            throw MessengerException.BackupFile(BackupFileError.NotABackup)
        }
        BackupFileInfo(
            uri = destination.toURI(),
            fileName = name ?: destination.name,
            byteCount = size ?: destination.length(),
            createdAt = created,
        )
    }

    /**
     * Decrypts the backup, verifies every field and asks the relay, read-only, whether its token
     * still works (`AppRuntime.checkBackup`): “opened” means a restore will accept it. Nothing
     * changes on this phone; the restore itself waits for the new code (2.2 › 2.3).
     */
    override suspend fun openBackup(file: BackupFileInfo, password: String): BackupOpenResult = withContext(main) {
        pendingRestore = null
        unrestorable = null
        val copy = File(file.uri)
        val result = try {
            val bytes = withContext(io) { copy.readBytes() }
            runtime.checkBackup(bytes, password)
            BackupOpenResult.Opened
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LiveOutcomes.openResult(e)
        }
        when (result) {
            BackupOpenResult.Opened -> pendingRestore = copy to password
            // It can never be restored: its private copy goes now.
            BackupOpenResult.Damaged, BackupOpenResult.NoLongerValid -> copy.delete()
            BackupOpenResult.WrongPassword, BackupOpenResult.Unverified -> Unit
        }
        result
    }

    /** An account backup holds the account and the contacts, never message history (PROTOCOL §5.5). */
    override val backupsIncludeHistory: Boolean get() = false

    /**
     * [openBackup] has decrypted the backup, verified every field and checked its token with the
     * relay: the keys step is done. The account itself is restored with the new code
     * ([completeRestore]), because the vault is bound to that code, and there is no message history
     * to restore.
     */
    override fun restoreBackup(file: BackupFileInfo): Flow<RestoreProgress> = flow {
        emit(
            RestoreProgress(
                fraction = 1.0,
                stage = RestoreProgress.Stage.Keys,
                messagesDone = 0,
                messagesTotal = 0,
                mediaBytes = 0,
                estimatedRemaining = Duration.ZERO,
                includesHistory = false,
            ),
        )
    }

    override suspend fun cancelRestore() = withContext(main) { dropPendingRestore() }

    // MARK: Helpers

    /** Runs [block]; a failure means the action did not happen, which the screens already show. */
    private suspend inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend inline fun succeeds(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private suspend inline fun <T> valueOr(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        fallback
    }

    /** Ends the service's own work (tests). */
    fun close() {
        sharing.forget()
        outgoingMedia.removeAll()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    companion object {
        /** How long a new or accepted chat may take to be listed. */
        private val LISTING_WAIT = 3.seconds

        /** How long a media request waits for its download. */
        private val WAIT_FOR_DOWNLOAD = 15.minutes

        /** A quote's preview: the text (at most 140 characters), or what the quoted message is. */
        fun quoteText(quote: QuotedMessage): String = when (val kind = quote.kind) {
            is QuotedMessage.Kind.Text -> kind.text.characterPrefix(140)
            QuotedMessage.Kind.Photo -> "📷"
            QuotedMessage.Kind.Video -> "🎬"
            QuotedMessage.Kind.Voice -> "🎙"
            is QuotedMessage.Kind.File -> kind.name
        }

        /** The first [count] user-perceived characters (Swift's `prefix` on a `String`). */
        private fun String.characterPrefix(count: Int): String {
            val iterator = java.text.BreakIterator.getCharacterInstance(Locale.ROOT)
            iterator.setText(this)
            var end = 0
            repeat(count) {
                val next = iterator.next()
                if (next == java.text.BreakIterator.DONE) return this
                end = next
            }
            return substring(0, end)
        }
    }
}

/** The name 7.2 gives the exported file, kept to its last path component. */
internal object FileNameNames {
    fun backup(fileName: String): String {
        val last = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
        return last.ifEmpty { "us2us.${BackupContents.FILE_EXTENSION}" }
    }
}
