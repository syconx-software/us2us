package com.syconx.us2us.fixtures

import com.syconx.us2us.core.vault.BackupFile
import com.syconx.us2us.core.vault.BootClock
import com.syconx.us2us.core.vault.UnlockAttemptPolicy
import com.syconx.us2us.core.vault.UnlockAttempts
import com.syconx.us2us.domain.AccountCreationFailure
import com.syconx.us2us.domain.AppSettings
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.AvatarChoice
import com.syconx.us2us.domain.AvatarEdit
import com.syconx.us2us.domain.AvatarEditError
import com.syconx.us2us.domain.AvatarPhotoData
import com.syconx.us2us.domain.AvatarPicture
import com.syconx.us2us.domain.AvatarPresetCatalog
import com.syconx.us2us.domain.AvatarSearchCopy
import com.syconx.us2us.domain.BackupContents
import com.syconx.us2us.domain.BackupEstimate
import com.syconx.us2us.domain.BackupFileError
import com.syconx.us2us.domain.BackupFileInfo
import com.syconx.us2us.domain.BackupOpenResult
import com.syconx.us2us.domain.BackupPasswordError
import com.syconx.us2us.domain.BlockedPerson
import com.syconx.us2us.domain.Biometry
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.ChangeSecretCodeError
import com.syconx.us2us.domain.ChatMediaKind
import com.syconx.us2us.domain.ChatSummary
import com.syconx.us2us.domain.ConnectionState
import com.syconx.us2us.domain.ContactCode
import com.syconx.us2us.domain.ContactCodeAddition
import com.syconx.us2us.domain.ContactCodeResolution
import com.syconx.us2us.domain.ConversationAttachment
import com.syconx.us2us.domain.DesignArtwork
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.DisappearingTimer
import com.syconx.us2us.domain.GhostState
import com.syconx.us2us.domain.LiveLocationSnapshot
import com.syconx.us2us.domain.LivePosition
import com.syconx.us2us.domain.LiveShare
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.domain.MediaExportPolicy
import com.syconx.us2us.domain.MediaFileKind
import com.syconx.us2us.domain.MediaPayload
import com.syconx.us2us.domain.MediaSource
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.domain.MessagePreview
import com.syconx.us2us.domain.MessagePreviewKind
import com.syconx.us2us.domain.MessageRequest
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.domain.MessengerException
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.NoticePermission
import com.syconx.us2us.domain.OwnAccount
import com.syconx.us2us.domain.OwnAvatarState
import com.syconx.us2us.domain.OwnPositionState
import com.syconx.us2us.domain.Peer
import com.syconx.us2us.domain.PickedMediaItem
import com.syconx.us2us.domain.PickedMediaMessage
import com.syconx.us2us.domain.Presence
import com.syconx.us2us.domain.QuotedMessage
import com.syconx.us2us.domain.Reaction
import com.syconx.us2us.domain.ReportSubject
import com.syconx.us2us.domain.RestoreAccessCheck
import com.syconx.us2us.domain.RestoreProgress
import com.syconx.us2us.domain.SafetyCodeComparison
import com.syconx.us2us.domain.SafetyNumberInfo
import com.syconx.us2us.domain.SafetyQRCodec
import com.syconx.us2us.domain.SecretCodeError
import com.syconx.us2us.domain.SystemEvent
import com.syconx.us2us.domain.UnlockAttemptResult
import com.syconx.us2us.domain.UnlockStatus
import com.syconx.us2us.domain.UserLookup
import com.syconx.us2us.domain.UsernameAvailability
import com.syconx.us2us.domain.VerificationMethod
import com.syconx.us2us.domain.ViewOnceState
import com.syconx.us2us.domain.VoiceRecording
import com.syconx.us2us.domain.VoiceWaveform
import com.syconx.us2us.domain.combineStates
import com.syconx.us2us.domain.design.DesignAvatars
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.design.DesignMediaArchive
import com.syconx.us2us.domain.harness.AvatarPhotoPreparer
import com.syconx.us2us.domain.harness.BiometricCheck
import com.syconx.us2us.domain.harness.DesignArtworkEncoder
import com.syconx.us2us.domain.harness.FixtureControls
import com.syconx.us2us.domain.mapState
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.domain.sticker.BrandStickerSelection
import com.syconx.us2us.domain.sticker.StickerRecents
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A [MessengerService] that serves the design's own data ([DesignFixtures]), in memory, with
 * believable behaviour: availability checks, the real secret-code rules and wrong-attempt delays
 * from the core, sending (text, replies, attachments, reactions), Ghost changes posting system
 * lines, view-once, request triage, verification (QR and manual), the code change, backup export (a
 * real backup file for the share sheet) and restore. Nothing is persisted.
 *
 * Used by debug builds with `U2Fixtures`, by previews and by the screenshot and UI tests. The
 * harnesses turn its knobs through [FixtureControls].
 *
 * @param hasAccount start as an installed account (`false`: a fresh install, onboarding).
 * @param clock the clock countdowns are relative to (frozen for references).
 * @param ghostUrgent the design's last-hour variant.
 * @param english serve the demo user content in English (9.1 / 9.2).
 * @param latency simulated network latency for lookups (0 in tests).
 * @param scope runs the simulated background work (downloads, preparing, the restore's walk); the
 *   app passes a main-thread scope, tests their test scope.
 * @param bootClock time since boot, for the wrong-code delays (PROTOCOL §5.2): a clock the user
 *   cannot set. The app passes `SystemClock.elapsedRealtimeNanos()`; tests a fake one.
 * @param wallClock when the next attempt is allowed, as the lock shows it.
 * @param exportDirectory where exported backups are written (emptied before every export).
 */
class FixturesMessengerService(
    hasAccount: Boolean,
    val clock: DesignClock,
    ghostUrgent: Boolean = false,
    private val english: Boolean = false,
    val latency: Duration = 450.milliseconds,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    bootClock: () -> Duration = { System.nanoTime().nanoseconds },
    private val wallClock: Clock = Clock.systemUTC(),
    private val exportDirectory: File = File(System.getProperty("java.io.tmpdir"), "backup-export"),
) : MessengerService, FixtureControls {
    /** Biometrics for unlock and for turning biometric unlock on; `null`: none on this device (the harness sets [biometricsResult] instead). */
    @Volatile
    var biometricCheck: BiometricCheck? = null

    /** Encodes the design's pictures and clip as files; `null`: design photos have no payload and the design video does not play. */
    @Volatile
    var artworkEncoder: DesignArtworkEncoder? = null

    /** Makes a picked photo a profile photo; `null`: a JPEG or HEIC is taken as it is, anything else is refused. */
    @Volatile
    var avatarPhotoPreparer: AvatarPhotoPreparer? = null

    /** Accounts the simulated relay deleted (tests). */
    @Volatile
    var accountDeletionsOnRelay: Int = 0
        private set

    private val lock = Any()
    private val boot = BootClock { bootClock() }

    private val accountFlow = MutableStateFlow(if (hasAccount) OwnAccount(DesignFixtures.OWN_USERNAME) else null)
    private val connectionFlow = MutableStateFlow(ConnectionState.Open)
    private val chatsFlow = MutableStateFlow(DesignFixtures.chats(clock.now, ghostUrgent, english))
    private val requestsFlow = MutableStateFlow(DesignFixtures.requests(clock.now, english))
    private val settingsFlow = MutableStateFlow(DesignFixtures.settings)
    private val unlockStatusFlow = MutableStateFlow(initialStatus())
    private val ownAvatarFlow = MutableStateFlow(OwnAvatarState())
    private val restoreAccessPendingFlow = MutableStateFlow(false)

    /** The live shares' latest positions by message id (5.2 › harita), memory only. */
    private val livePositions = MutableStateFlow(mapOf(MYLIVE_SHARE to DesignFixtures.ownFixturePosition))

    private val messageTable = ChatMessageTable(designMessages())
    private var secretCode = DesignFixtures.DEMO_SECRET_CODE
    private var attempts = UnlockAttempts()
    private val safetyNumbers = HashMap<ChatId, SafetyNumberInfo>().apply { put(DENIZ, DesignFixtures.denizSafetyNumber) }

    /** SHA-256 of the passwords of backups exported in this session (never the plain text). */
    private val exportedPasswordDigests = HashSet<Bytes>()
    private var restoreGeneration = 0
    private var nextMessageNumber = 0
    private val downloadJobs = HashMap<MessageId, Job>()
    private val voiceAudio = HashMap<MessageId, Bytes>()
    private var stickerRecents = DesignFixtures.stickerRecents(english)

    /** The contact screen's older photos, videos and files of each chat, made on first use. */
    private val mediaArchive = HashMap<ChatId, List<Message>>()

    /** Blocked chats as they were before the block (“Engeli kaldır” restores them). */
    private val peersBeforeBlock = HashMap<ChatId, Peer>()

    /** Requests blocked from 4.3 / 4.4: unblocking makes them requests again. */
    private val blockedRequests = HashMap<ChatId, MessageRequest>()

    /**
     * People blocked whose chat is gone (Ghost erased it): only 7.1 › Engellenenler shows them, and
     * 4.2 › “Mesaj yaz” brings the chat back.
     */
    private val blockedWithoutChat = MutableStateFlow<List<BlockedPerson>>(emptyList())
    private val liveDemoJobs = HashMap<MessageId, Job>()

    // MARK: Fixture controls

    @Volatile
    override var wipesAfterTenWrongCodes: Boolean = false

    @Volatile
    override var nextUnlockResult: UnlockAttemptResult? = null

    @Volatile
    override var biometricsResult: Boolean? = null

    @Volatile
    override var biometry: Biometry? = null

    @Volatile
    override var biometricUnlockNeedsCode: Boolean = false

    @Volatile
    override var nextSetupFailure: Exception? = null

    @Volatile
    override var restoreAccessAnswer: RestoreAccessCheck = RestoreAccessCheck.Closed

    @Volatile
    override var nextBackupOpenResult: BackupOpenResult? = null

    @Volatile
    override var failsAccountDeletion: Boolean = false

    @Volatile
    override var holdsMediaPayloads: Boolean = false

    @Volatile
    override var ownPositionOverride: OwnPositionState? = null

    override var liveSharingNoticeOff: Boolean = false

    // MARK: Observable state

    override val account: StateFlow<OwnAccount?> = accountFlow.asStateFlow()
    override val connection: StateFlow<ConnectionState> = connectionFlow.asStateFlow()
    override val chats: StateFlow<List<ChatSummary>> = chatsFlow.asStateFlow()
    override val requests: StateFlow<List<MessageRequest>> = requestsFlow.asStateFlow()

    /** The blocked chats of 4.1 and the people blocked without a chat, by name. */
    override val blockedPeople: StateFlow<List<BlockedPerson>> = combineStates(chatsFlow, blockedWithoutChat) { chats, without ->
        val withChat = chats.filter { it.peer.isBlocked }.map { BlockedPerson(it.id, it.peer.username, palette = it.peer.palette, avatar = it.peer.avatar) }
        BlockedPerson.listed(withChat + without)
    }
    override val settings: StateFlow<AppSettings> = settingsFlow.asStateFlow()
    override val unlockStatus: StateFlow<UnlockStatus> = unlockStatusFlow.asStateFlow()
    override val ownAvatar: StateFlow<OwnAvatarState> = ownAvatarFlow.asStateFlow()
    override val restoreAccessPending: StateFlow<Boolean> = restoreAccessPendingFlow.asStateFlow()
    override val hasAccount: StateFlow<Boolean> = account.mapState { it != null }
    override val ownUsername: StateFlow<String> = account.mapState { it?.username.orEmpty() }
    override val readReceiptsEnabled: StateFlow<Boolean> = settings.mapState { it.readReceipts }

    // Per-chat flows, one instance per chat, so a screen that asks again gets the same flow.
    private val chatFlows = ConcurrentHashMap<ChatId, StateFlow<ChatSummary?>>()
    private val peerFlows = ConcurrentHashMap<ChatId, StateFlow<Peer?>>()
    private val messageFlows = ConcurrentHashMap<ChatId, StateFlow<List<Message>>>()
    private val liveFlows = ConcurrentHashMap<ChatId, StateFlow<LiveLocationSnapshot>>()

    // MARK: Account & onboarding

    override suspend fun usernameAvailability(username: String): UsernameAvailability {
        if (username.isEmpty()) return UsernameAvailability.Empty
        CoreRules.usernameViolation(username)?.let { return UsernameAvailability.Invalid(it) }
        delay(latency)
        // Like the live service, which cannot ask before setup: this one meets the rules, and
        // turns out taken at “Kurulumu tamamla”.
        if (username == DesignFixtures.TAKEN_AT_SETUP_USERNAME) return UsernameAvailability.MeetsRules
        return if (username in DesignFixtures.takenUsernames) UsernameAvailability.Taken else UsernameAvailability.Available
    }

    override fun secretCodeProblem(code: String): SecretCodeError? = CoreRules.secretCodeProblem(code)

    override suspend fun createAccount(username: String, secretCode: String, faceIdEnabled: Boolean) {
        delay(latency)
        takeSetupFailure()?.let { throw it }
        // The relay's 409: someone registered the name first.
        if (username == DesignFixtures.TAKEN_AT_SETUP_USERNAME || username in DesignFixtures.takenUsernames) {
            throw MessengerException.AccountCreation(AccountCreationFailure.UsernameTaken)
        }
        synchronized(lock) {
            accountFlow.value = OwnAccount(username)
            this.secretCode = secretCode
            settingsFlow.update { it.copy(faceIdEnabled = faceIdEnabled) }
        }
    }

    override suspend fun completeRestore(secretCode: String, faceIdEnabled: Boolean) {
        delay(latency)
        takeSetupFailure()?.let { throw it }
        synchronized(lock) {
            // The restored vault: the design's own account and chats.
            if (chatsFlow.value.isEmpty()) loadDesignData()
            accountFlow.value = OwnAccount(DesignFixtures.OWN_USERNAME)
            this.secretCode = secretCode
            settingsFlow.update { it.copy(faceIdEnabled = faceIdEnabled) }
            attempts = UnlockAttempts()
            unlockStatusFlow.value = initialStatus()
        }
    }

    private fun takeSetupFailure(): Exception? = synchronized(lock) { nextSetupFailure.also { nextSetupFailure = null } }

    private fun loadDesignData() {
        val now = clock.now
        chatsFlow.value = DesignFixtures.chats(now, ghostUrgent = false, english = english)
        requestsFlow.value = DesignFixtures.requests(now, english)
        peersBeforeBlock.clear()
        blockedRequests.clear()
        blockedWithoutChat.value = emptyList()
        settingsFlow.value = DesignFixtures.settings
        stickerRecents = DesignFixtures.stickerRecents(english)
        messageTable.replace(designMessages())
        safetyNumbers.clear()
        safetyNumbers[DENIZ] = DesignFixtures.denizSafetyNumber
    }

    private fun designMessages(): Map<ChatId, List<Message>> = mapOf(
        DENIZ to DesignFixtures.messages(DENIZ, english),
        MERT to DesignFixtures.messages(MERT, english),
    )

    // MARK: Lock

    override suspend fun unlock(code: String): UnlockAttemptResult {
        synchronized(lock) { nextUnlockResult?.let { forced -> nextUnlockResult = null; return forced } }
        if (!CoreRules.isWellFormedCode(code)) return UnlockAttemptResult.Malformed
        val wiped: Boolean
        val result: UnlockAttemptResult
        synchronized(lock) {
            val now = wallClock.instant()
            val observed = attempts.observed(boot.sinceBoot())
            attempts = observed
            val remaining = observed.remainingDelay()
            if (remaining > Duration.ZERO) return UnlockAttemptResult.Throttled(now + remaining)
            if (code == secretCode) {
                attempts = UnlockAttempts()
                unlockStatusFlow.value = initialStatus()
                return UnlockAttemptResult.Unlocked
            }
            val failures = observed.failures + 1
            wiped = UnlockAttemptPolicy.shouldWipe(failures, wipesAfterTenWrongCodes)
            if (wiped) {
                result = UnlockAttemptResult.Wiped
            } else {
                attempts = UnlockAttempts(failures, Duration.ZERO, boot.sinceBoot())
                val delay = UnlockAttemptPolicy.delayAfter(failures)
                val status = UnlockStatus(
                    failures = failures,
                    nextAttemptAllowedAt = if (delay > Duration.ZERO) now + delay else null,
                    upcoming = CoreRules.upcomingDelay(failures),
                )
                unlockStatusFlow.value = status
                result = UnlockAttemptResult.WrongCode(status)
            }
        }
        if (wiped) eraseEverything()
        return result
    }

    override fun setWrongCodes(failures: Int) {
        synchronized(lock) {
            // As if every delay so far had been waited out: no wait running.
            attempts = UnlockAttempts(failures, UnlockAttemptPolicy.delayAfter(failures), boot.sinceBoot())
            unlockStatusFlow.value = UnlockStatus(failures, nextAttemptAllowedAt = null, upcoming = CoreRules.upcomingDelay(failures))
        }
    }

    override suspend fun checkRestoreAccess(): RestoreAccessCheck {
        delay(latency)
        val answer = restoreAccessAnswer
        if (answer != RestoreAccessCheck.Unanswered) restoreAccessPendingFlow.value = false
        return answer
    }

    override suspend fun unlockWithBiometrics(reason: String, cancelTitle: String): Boolean {
        if (!settingsFlow.value.faceIdEnabled) return false
        biometricsResult?.let { return it }
        // No device-credential fallback: the secret code is the only other way in.
        return biometricCheck?.authenticate(reason, cancelTitle) ?: false
    }

    override suspend fun eraseEverything() {
        synchronized(lock) {
            liveDemoJobs.values.forEach { it.cancel() }
            liveDemoJobs.clear()
            downloadJobs.values.forEach { it.cancel() }
            downloadJobs.clear()
            restoreGeneration++
            livePositions.value = mapOf(MYLIVE_SHARE to DesignFixtures.ownFixturePosition)
            ownPositionOverride = null
            liveSharingNoticeOff = false
            accountFlow.value = null
            chatsFlow.value = emptyList()
            requestsFlow.value = emptyList()
            peersBeforeBlock.clear()
            blockedRequests.clear()
            blockedWithoutChat.value = emptyList()
            messageTable.replace(emptyMap())
            mediaArchive.clear()
            stickerRecents = emptyList()
            safetyNumbers.clear()
            voiceAudio.clear()
            settingsFlow.value = AppSettings()
            ownAvatarFlow.value = OwnAvatarState()
            restoreAccessPendingFlow.value = false
            attempts = UnlockAttempts()
            unlockStatusFlow.value = initialStatus()
        }
    }

    override suspend fun deleteAccountOnRelay() {
        delay(latency)
        if (failsAccountDeletion) throw MessengerException.RelayUnreachable("the simulated relay cannot be reached")
        accountDeletionsOnRelay += 1
    }

    // MARK: Chats

    override suspend fun lookup(username: String): UserLookup {
        val name = CoreRules.normalizeUsername(username)
        if (!CoreRules.isValidUsername(name)) return UserLookup.NotFound
        delay(latency)
        if (name == "deniz") {
            // 4.2 shows deniz as found and not yet a contact.
            return UserLookup.Found(
                Peer(username = "deniz", palette = AvatarPalette.A, isVerified = false, isAcceptedContact = false, presence = Presence.Hidden),
            )
        }
        chatsFlow.value.firstOrNull { it.peer.username == name }?.let { return UserLookup.Found(it.peer, existingChat = it.id) }
        if (name in DesignFixtures.takenUsernames) {
            return UserLookup.Found(stranger(name))
        }
        return UserLookup.NotFound
    }

    override suspend fun accept(request: MessageRequest): ChatId? {
        synchronized(lock) {
            requestsFlow.update { list -> list.filter { it.id != request.id } }
            // Accepted: the contact's profile update brings their picture (§8.9).
            val peer = stranger(request.sender).copy(isAcceptedContact = true, avatar = DesignAvatars.requestAvatar(request.sender))
            val last = request.messages.lastOrNull()
            val summary = ChatSummary(
                id = ChatId(request.sender), peer = peer,
                lastMessage = last?.let { MessagePreview(request.preview, MessageDirection.Incoming, null, it.sentAt) },
                unreadCount = 0, ghost = GhostState.Off, disappearing = DisappearingTimer.Off,
            )
            chatsFlow.update { listOf(summary) + it }
            messageTable[summary.id] = request.messages
            return summary.id
        }
    }

    /**
     * As in the live app: the request leaves 4.3 and stays in 4.1 as a blocked chat with its
     * messages (no unread count); unblocking makes it a request again.
     */
    override suspend fun block(request: MessageRequest) {
        synchronized(lock) {
            if (requestsFlow.value.none { it.id == request.id }) return
            requestsFlow.update { list -> list.filter { it.id != request.id } }
            val id = ChatId(request.id.value)
            val peer = stranger(request.sender).copy(isBlocked = true)
            val last = request.messages.lastOrNull()
            val summary = ChatSummary(
                id = id, peer = peer,
                lastMessage = last?.let { MessagePreview(request.preview, MessageDirection.Incoming, null, it.sentAt) },
                unreadCount = 0, ghost = GhostState.Off, disappearing = DisappearingTimer.Off,
            )
            // Where its last message puts it (the design's own order stays).
            val date = last?.sentAt ?: request.receivedAt
            chatsFlow.update { list ->
                val others = list.filter { it.id != id }
                val position = others.indexOfFirst { (it.lastMessage?.sentAt ?: Instant.MIN) < date }.let { if (it < 0) others.size else it }
                others.take(position) + summary + others.drop(position)
            }
            messageTable[id] = request.messages
            blockedRequests[id] = request
        }
    }

    override suspend fun delete(request: MessageRequest) {
        requestsFlow.update { list -> list.filter { it.id != request.id } }
    }

    // MARK: Blocking and reports

    /** Read only from now on: presence and the picture go (the live service shows the initial for anyone blocked) and nothing can be sent. */
    override suspend fun block(chat: ChatId): Boolean = setBlocked(chat)

    /** The block itself (also the harness's blocked states, set before the first frame). */
    override fun setBlocked(chat: ChatId): Boolean = synchronized(lock) {
        val summary = chatsFlow.value.firstOrNull { it.id == chat } ?: return false
        if (summary.peer.isBlocked) return true
        peersBeforeBlock[chat] = summary.peer
        updateChat(chat) {
            it.copy(peer = it.peer.copy(isBlocked = true, isAcceptedContact = false, presence = Presence.Hidden, avatar = AvatarPicture.Initials))
        }
        true
    }

    // MARK: Deleting

    /** The chat leaves 4.1 with its messages; someone blocked stays blocked, without a chat (7.1 › Engellenenler). */
    override suspend fun deleteChat(chat: ChatId): Boolean = removeChat(chat, forgetsContact = false)

    /** As [deleteChat]; the contact goes too (its verification and its state from before a block). */
    override suspend fun deleteContact(chat: ChatId): Boolean = removeChat(chat, forgetsContact = true)

    private fun removeChat(chat: ChatId, forgetsContact: Boolean): Boolean = synchronized(lock) {
        val summary = chatsFlow.value.firstOrNull { it.id == chat } ?: return false
        chatsFlow.update { list -> list.filter { it.id != chat } }
        messageTable[chat] = null
        if (summary.peer.isBlocked) {
            blockedRequests.remove(chat)
            if (forgetsContact) peersBeforeBlock.remove(chat)
            blockedWithoutChat.update { list ->
                list.filter { it.id != chat } + BlockedPerson(chat, summary.peer.username, palette = summary.peer.palette)
            }
        }
        true
    }

    /** Back as before the block; a blocked request goes back to 4.3. */
    override suspend fun unblock(chat: ChatId): Boolean = synchronized(lock) {
        val summary = chatsFlow.value.firstOrNull { it.id == chat }?.takeIf { it.peer.isBlocked } ?: return false
        val request = blockedRequests.remove(chat)
        if (request != null) {
            chatsFlow.update { list -> list.filter { it.id != chat } }
            messageTable[chat] = null
            requestsFlow.update { list ->
                val position = list.indexOfFirst { it.receivedAt < request.receivedAt }.let { if (it < 0) list.size else it }
                list.take(position) + request + list.drop(position)
            }
            return true
        }
        val before = peersBeforeBlock.remove(chat)
        updateChat(chat) { it.copy(peer = before ?: summary.peer.copy(isBlocked = false)) }
        true
    }

    /** As `unblock(chat)`; someone without a chat is back as before the block, still without one (4.1 shows nothing until they write). */
    override suspend fun unblock(person: BlockedPerson): Boolean {
        synchronized(lock) {
            if (blockedWithoutChat.value.none { it.id == person.id }) return@synchronized
            blockedWithoutChat.update { list -> list.filter { it.id != person.id } }
            peersBeforeBlock.remove(person.id)
            return true
        }
        return unblock(person.id)
    }

    /**
     * Harness and tests: 7.1 › Engellenenler's two kinds: bora blocked (the chat stays in 4.1, read
     * only) and lale, a contact blocked before Ghost erased the chat (none any more).
     */
    override fun loadBlockedPeople() {
        setBlocked(BORA)
        synchronized(lock) {
            if (chatsFlow.value.any { it.id == LALE } || blockedWithoutChat.value.any { it.id == LALE }) return
            val palette = AvatarPalette.forUsername(LALE.value)
            peersBeforeBlock[LALE] = Peer(LALE.value, palette, isVerified = false, isAcceptedContact = true, presence = Presence.Hidden)
            blockedWithoutChat.update { it + BlockedPerson(LALE, LALE.value, palette = palette) }
        }
    }

    override fun reportSubject(chat: ChatId): ReportSubject? =
        chatsFlow.value.firstOrNull { it.id == chat }?.let { ReportSubject(it.peer.username, isUsernameVerified = true) }

    /** The design's suspicious request (hediye_kazan) is one whose name the directory did not confirm. */
    override fun reportSubject(request: MessageRequest): ReportSubject =
        ReportSubject(request.sender, isUsernameVerified = MessageRequest.Flag.Suspicious !in request.flags)

    override suspend fun startChat(username: String): ChatId? {
        val name = CoreRules.normalizeUsername(username)
        if (!CoreRules.isValidUsername(name) || name == accountFlow.value?.username) return null
        synchronized(lock) {
            chatsFlow.value.firstOrNull { it.peer.username == name }?.let { return it.id }
            // Someone blocked whose chat is gone gets it back, read only, with “Engeli kaldır” (as in the live app).
            blockedWithoutChat.value.firstOrNull { it.id.value == name }?.let { person ->
                blockedWithoutChat.update { list -> list.filter { it.id != person.id } }
                val peer = Peer(name, person.palette, isVerified = false, isAcceptedContact = false, presence = Presence.Hidden, isBlocked = true)
                chatsFlow.update { listOf(ChatSummary(person.id, peer, null, 0, GhostState.Off, DisappearingTimer.Off)) + it }
                messageTable[person.id] = emptyList()
                return person.id
            }
            // A new, empty conversation; the first message becomes a request on the other side (no
            // presence until it is accepted). The user's own request shows the search copy of the account (§8.9).
            val peer = stranger(name).copy(avatar = DesignAvatars.searchAvatar(name))
            val id = ChatId(name)
            chatsFlow.update {
                listOf(ChatSummary(id, peer, null, 0, GhostState.Off, settingsFlow.value.defaultDisappearing)) + it
            }
            messageTable[id] = emptyList()
            return id
        }
    }

    override suspend fun ownContactCode(): String? {
        val username = accountFlow.value?.username ?: return null
        return ContactCode.encode(username, DesignFixtures.fixtureKeyFingerprint(username))
    }

    // MARK: Profile pictures

    override suspend fun setAvatar(edit: AvatarEdit) {
        val picture = when (edit) {
            AvatarEdit.Initials -> AvatarPicture.Initials
            is AvatarEdit.Preset -> {
                if (!AvatarPresetCatalog.isKnown(edit.id)) throw MessengerException.AvatarEdit(AvatarEditError.Failed)
                AvatarPicture.preset(edit.id)
            }
            is AvatarEdit.Photo -> {
                val prepared = avatarPhotoPreparer?.prepare(edit.data, edit.crop) ?: photoAsIs(edit.data)
                    ?: throw MessengerException.AvatarEdit(AvatarEditError.UnusablePhoto)
                AvatarPicture.photo(prepared)
            }
        }
        if (accountFlow.value == null) throw MessengerException.AvatarEdit(AvatarEditError.Failed)
        ownAvatarFlow.update { it.copy(picture = picture, searchCopy = AvatarSearchCopy.InSync) }
    }

    /** Without a preparer: a JPEG or HEIC taken as it is (the decoder still checks it), anything else refused. */
    private fun photoAsIs(data: Bytes): AvatarPhotoData? {
        if (data.size < 12) return null
        val isJpeg = (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xFF) == 0xD8 && (data[2].toInt() and 0xFF) == 0xFF
        if (isJpeg) return AvatarPhotoData(data, "image/jpeg")
        val brand = String(ByteArray(8) { data[4 + it] }, Charsets.US_ASCII)
        if (brand.startsWith("ftyp") && brand.substring(4) in setOf("heic", "heix", "mif1", "msf1")) return AvatarPhotoData(data, "image/heic")
        return null
    }

    override suspend fun setAvatarShownInSearch(shown: Boolean): Boolean {
        ownAvatarFlow.update { it.copy(showInSearch = shown, searchCopy = AvatarSearchCopy.InSync) }
        return shown
    }

    override suspend fun searchAvatar(username: String): AvatarPicture {
        val name = CoreRules.normalizeUsername(username)
        if (name == accountFlow.value?.username) return ownAvatarFlow.value.picture
        // An accepted contact's picture is already here (no request).
        chatsFlow.value.firstOrNull { it.peer.username == name }?.takeIf { it.peer.isAcceptedContact }?.let { return it.peer.avatar }
        delay(latency)
        return DesignAvatars.searchAvatar(name)
    }

    override suspend fun revealAvatar(request: MessageRequest): AvatarPicture {
        if (!request.offersAvatar) return AvatarPicture.Initials
        delay(latency)
        return DesignAvatars.requestAvatar(request.sender)
    }

    override fun setOwnAvatar(state: OwnAvatarState) {
        ownAvatarFlow.value = state
    }

    // MARK: Conversation

    override fun chat(id: ChatId): StateFlow<ChatSummary?> =
        chatFlows.getOrPut(id) { chatsFlow.mapState { list -> list.firstOrNull { it.id == id } } }

    override fun peer(chat: ChatId): StateFlow<Peer?> = peerFlows.getOrPut(chat) { this.chat(chat).mapState { it?.peer } }

    override fun messages(chat: ChatId): StateFlow<List<Message>> = messageFlows.getOrPut(chat) { messageTable.observe(chat) }

    override suspend fun sendText(text: String, replyTo: QuotedMessage?, chat: ChatId) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        appendOutgoing(MessageContent.Text(trimmed), replyTo, chat)
    }

    override suspend fun sendAttachment(attachment: ConversationAttachment, chat: ChatId) {
        val now = clock.now
        val content: MessageContent = when (attachment) {
            is ConversationAttachment.Photo -> MessageContent.Photo(attachment.source, attachment.caption)
            is ConversationAttachment.File -> MessageContent.File(attachment.name, attachment.byteCount, attachment.typeLabel, attachment.pageCount)
            is ConversationAttachment.Location -> MessageContent.Location(attachment.name, attachment.locality, attachment.latitude, attachment.longitude)
            is ConversationAttachment.LiveLocation -> MessageContent.LiveLocation(now + attachment.duration, isActive = true)
            is ConversationAttachment.Video -> MessageContent.Video(MediaSource.Artwork(DesignArtwork.SunsetVideo), 18.seconds, 12_400_000)
            is ConversationAttachment.Document -> {
                val name = attachment.uri.path?.substringAfterLast('/').orEmpty()
                MessageContent.File(name, byteCount = 0, typeLabel = name.substringAfterLast('.', "").uppercase(), pageCount = null)
            }
        }
        val id = appendOutgoing(content, null, chat) ?: return
        // A new live share starts where selin_n is (5.2 › harita).
        if (attachment is ConversationAttachment.LiveLocation) {
            livePositions.update { it + (id to DesignFixtures.ownFixturePosition) }
        }
    }

    /** The sticker becomes an outgoing sticker message (“Gönderildi”) and goes first in the recents. */
    override suspend fun sendSticker(sticker: BrandStickerSelection, replyTo: QuotedMessage?, chat: ChatId) {
        appendOutgoing(sticker.messageContent, replyTo, chat) ?: return
        synchronized(lock) { stickerRecents = StickerRecents.adding(sticker, stickerRecents) }
    }

    override suspend fun recentStickers(): List<String> = if (accountFlow.value == null) emptyList() else synchronized(lock) { stickerRecents }

    /**
     * “+” › Fotoğraf ve video with the design's pictures, as the live service shows a pick: every
     * bubble at once, in the order picked, “Hazırlanıyor”; then they are prepared one after
     * another, each sent in its own bubble. The fixtures never open the real picker: a picked copy
     * handed over anyway is not kept.
     */
    override suspend fun sendPickedMedia(messages: List<PickedMediaMessage>, chat: ChatId) {
        val ids = mutableListOf<MessageId>()
        for (message in messages) {
            val content = when (val source = message.item.source) {
                is PickedMediaItem.Source.Artwork -> when (message.item.kind) {
                    PickedMediaItem.Kind.Photo -> MessageContent.Photo(MediaSource.Artwork(source.artwork), message.caption)
                    PickedMediaItem.Kind.Video -> MessageContent.Video(MediaSource.Artwork(source.artwork), 18.seconds, 0)
                }
                is PickedMediaItem.Source.Copy -> {
                    scope.launch { source.file.await()?.let(::deletePickedCopy) }
                    null
                }
            } ?: continue
            appendOutgoing(content, null, chat, status = MessageStatus.Sending, availability = MediaAvailability.Preparing)?.let(ids::add)
        }
        finishPreparing(ids, chat, each = if (latency == Duration.ZERO) Duration.ZERO else 700.milliseconds, updatingList = true)
    }

    private fun deletePickedCopy(uri: URI) {
        runCatching { File(uri).delete() }
    }

    /** The recording becomes an outgoing voice bubble (“Gönderildi”); its bytes stay in memory so it plays back. */
    override suspend fun sendVoice(voice: VoiceRecording, replyTo: QuotedMessage?, chat: ChatId): Boolean {
        val content = MessageContent.Voice(
            duration = voice.duration,
            waveform = VoiceWaveform.bars(voice.waveform, DesignFixtures.voiceWaveform.size),
            playedFraction = 0.0,
        )
        val id = appendOutgoing(content, replyTo, chat) ?: return false
        synchronized(lock) { voiceAudio[id] = voice.audio }
        return true
    }

    override suspend fun markVoicePlayed(message: MessageId, chat: ChatId) {
        updateMessage(message, chat) { it.copy(isVoicePlayed = true) }
    }

    /** Saved from a viewer: shows as saved here (the fixtures have no other side to send SAVED to). Never for disappearing or view-once media. */
    override suspend fun markSaved(message: MessageId, chat: ChatId) {
        val found = messageTable[chat]?.firstOrNull { it.id == message }
        if (found != null) {
            if (!MediaExportPolicy.allowsExport(found)) return
            updateMessage(message, chat) { it.copy(savedByMe = true) }
        } else {
            synchronized(lock) {
                mediaArchive[chat]?.let { list -> mediaArchive[chat] = list.map { if (it.id == message) it.copy(savedByMe = true) else it } }
            }
        }
    }

    /** The contact screen's tabs: the conversation's photos, videos or files and the older ones of the archive, newest first, a page at a time. */
    override suspend fun media(chat: ChatId, kind: ChatMediaKind, before: MessageId?, limit: Int): List<Message> {
        if (this.chat(chat).value == null) return emptyList()
        val archive = synchronized(lock) { mediaArchive.getOrPut(chat) { DesignMediaArchive.archive(chat, english) } }
        delay(latency / 3)
        val current = synchronized(lock) { mediaArchive[chat] ?: archive }
        return DesignMediaArchive.page(current + messageTable[chat].orEmpty(), kind, before, limit)
    }

    override fun setSavedByPeer(message: MessageId, chat: ChatId) {
        val now = clock.now
        updateMessage(message, chat) { it.copy(savedByPeer = true, savedByPeerAt = now) }
    }

    override suspend fun resend(message: MessageId, chat: ChatId) {
        val original = messageTable[chat]?.firstOrNull { it.id == message } ?: return
        if (original.mediaAvailability == MediaAvailability.PreparationFailed) {
            // A photo or video that could not be prepared is prepared again, in its own bubble.
            updateMessage(message, chat) {
                it.copy(mediaAvailability = MediaAvailability.Preparing, status = MessageStatus.Sending, failedAt = null)
            }
            finishPreparing(listOf(message), chat, each = 2400.milliseconds)
            return
        }
        messageTable.update(chat) { list -> list.filter { it.id != message } }
        val audio = synchronized(lock) { voiceAudio.remove(message) }
        val id = appendOutgoing(original.content, original.replyTo, chat, disappearing = original.disappearing)
        if (id != null && audio != null) synchronized(lock) { voiceAudio[id] = audio }
    }

    /**
     * A new outgoing message (status “Gönderildi” unless [status] says otherwise), the row moved to
     * the top of 4.1, and the Ghost timer restarted. Its id; `null` without the chat.
     */
    private fun appendOutgoing(
        content: MessageContent,
        replyTo: QuotedMessage?,
        chat: ChatId,
        disappearing: DisappearingTimer? = null,
        status: MessageStatus = MessageStatus.Sent,
        availability: MediaAvailability = MediaAvailability.Ready,
    ): MessageId? = synchronized(lock) {
        // Like the runtime: nothing is sent to someone blocked.
        val summary = chatsFlow.value.firstOrNull { it.id == chat }?.takeIf { !it.peer.isBlocked } ?: return null
        val now = clock.now
        nextMessageNumber += 1
        val message = Message(
            id = MessageId("out-$nextMessageNumber-${UUID.randomUUID().toString().uppercase().take(8)}"),
            direction = MessageDirection.Outgoing, sentAt = now, content = content, status = status, replyTo = replyTo,
            disappearing = disappearing ?: summary.disappearing, mediaAvailability = availability,
        )
        messageTable.update(chat) { it + message }
        // Every new message restarts the Ghost timer.
        val ghost = if (summary.ghost.isEnabled) summary.ghost.copy(deadline = now + summary.ghost.period.duration) else summary.ghost
        val moved = summary.copy(lastMessage = preview(message), ghost = ghost)
        chatsFlow.update { list -> listOf(moved) + list.filter { it.id != chat } }
        message.id
    }

    /** Keeps the 4.1 row in step with the chat's newest message. */
    private fun refreshPreview(chat: ChatId) {
        synchronized(lock) {
            val last = messageTable[chat]?.lastOrNull { !it.isSystem } ?: return
            val preview = preview(last) ?: return
            chatsFlow.update { list ->
                list.map { summary ->
                    if (summary.id == chat && preview.sentAt >= (summary.lastMessage?.sentAt ?: Instant.MIN)) summary.copy(lastMessage = preview) else summary
                }
            }
        }
    }

    private fun updateMessage(id: MessageId, chat: ChatId, change: (Message) -> Message) {
        messageTable.update(chat) { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private fun updateChat(chat: ChatId, change: (ChatSummary) -> ChatSummary) {
        chatsFlow.update { list -> list.map { if (it.id == chat) change(it) else it } }
    }

    override suspend fun updateGhost(ghost: GhostState, disappearing: DisappearingTimer, chat: ChatId) {
        synchronized(lock) {
            val before = chatsFlow.value.firstOrNull { it.id == chat } ?: return
            val now = clock.now
            val updated = ghost.copy(deadline = if (ghost.isEnabled) now + ghost.period.duration else null)
            updateChat(chat) { it.copy(ghost = updated, disappearing = disappearing) }
            // Every change drops a system line into the chat (5.5 note).
            if (before.ghost.isEnabled != updated.isEnabled || before.ghost.period != updated.period) {
                val event = if (updated.isEnabled) SystemEvent.GhostEnabled(updated.period) else SystemEvent.GhostDisabled
                appendSystem(chat, MessageId(UUID.randomUUID().toString().uppercase()), event, now)
            }
            if (before.disappearing != disappearing) {
                appendSystem(chat, MessageId(UUID.randomUUID().toString().uppercase()), SystemEvent.DisappearingChanged(disappearing), now)
            }
        }
    }

    private fun appendSystem(chat: ChatId, id: MessageId, event: SystemEvent, at: Instant) {
        messageTable.update(chat) { it + Message(id = id, direction = MessageDirection.Outgoing, sentAt = at, content = MessageContent.System(event)) }
    }

    override suspend fun deleteForEveryone(message: MessageId, chat: ChatId) {
        synchronized(lock) {
            liveDemoJobs.remove(message)?.cancel()
            voiceAudio.remove(message)
        }
        updateMessage(message, chat) { it.copy(content = MessageContent.Deleted, reactions = emptyList()) }
        refreshPreview(chat)
    }

    override suspend fun deleteForMe(message: MessageId, chat: ChatId) {
        synchronized(lock) {
            liveDemoJobs.remove(message)?.cancel()
            voiceAudio.remove(message)
        }
        messageTable.update(chat) { list -> list.filter { it.id != message } }
    }

    override suspend fun setReaction(emoji: String?, message: MessageId, chat: ChatId) {
        updateMessage(message, chat) { found ->
            // The user's own reaction goes; the other side's stay.
            val reactions = found.reactions.mapNotNull { reaction ->
                when {
                    !reaction.includesMe -> reaction
                    reaction.count <= 1 -> null
                    else -> Reaction(reaction.emoji, reaction.count - 1, includesMe = false)
                }
            }.toMutableList()
            if (emoji != null) {
                val index = reactions.indexOfFirst { it.emoji == emoji }
                if (index >= 0) {
                    reactions[index] = reactions[index].copy(count = reactions[index].count + 1, includesMe = true)
                } else {
                    reactions += Reaction(emoji, 1, includesMe = true)
                }
            }
            found.copy(reactions = reactions)
        }
    }

    override suspend fun stopLiveLocation(message: MessageId, chat: ChatId) {
        val now = clock.now
        synchronized(lock) { liveDemoJobs.remove(message)?.cancel() }
        updateMessage(message, chat) { found ->
            val content = found.content
            if (content is MessageContent.LiveLocation && content.isActive) {
                found.copy(content = MessageContent.LiveLocation(minOf(content.expiresAt, now), isActive = false))
            } else {
                found
            }
        }
        refreshPreview(chat)
    }

    override suspend fun viewOnceMedia(message: MessageId, chat: ChatId): MediaSource? {
        val found = messageTable[chat]?.firstOrNull { it.id == message } ?: return null
        val content = found.content
        if (found.isOutgoing || content !is MessageContent.ViewOnce || content.state != ViewOnceState.Unopened) return null
        // The fixtures' view-once photo is the design's night-pier painting (5.6).
        return MediaSource.Artwork(DesignArtwork.NightPier)
    }

    override suspend fun markViewOnceOpened(message: MessageId, chat: ChatId) {
        updateMessage(message, chat) { found ->
            val content = found.content
            if (content is MessageContent.ViewOnce) found.copy(content = content.copy(state = ViewOnceState.Opened)) else found
        }
    }

    override suspend fun reportScreenshot(message: MessageId, chat: ChatId) {
        // The system line lands in the sender's chat, not in ours (5.6 note); the fixtures have no
        // other side to deliver it to.
    }

    // MARK: Live location (5.2 › harita)

    override fun liveLocations(chat: ChatId): StateFlow<LiveLocationSnapshot> = liveFlows.getOrPut(chat) {
        combineStates(messages(chat), livePositions) { messages, positions ->
            LiveLocationSnapshot(
                messages.mapNotNull { message ->
                    val content = message.content as? MessageContent.LiveLocation ?: return@mapNotNull null
                    val position = positions[message.id] ?: return@mapNotNull null
                    LiveShare(message.id, message.direction, message.sentAt, position, content.expiresAt, isStopped = !content.isActive)
                },
            )
        }
    }

    /** selin_n's phone: at once, then (running clock) a few metres either way every 4 s. No system prompt ever: the harness sets the other states. */
    override fun ownPositionUpdates(): Flow<OwnPositionState> = flow {
        val initial = ownPositionOverride ?: OwnPositionState.Position(DesignFixtures.ownFixturePosition)
        emit(initial)
        if (initial !is OwnPositionState.Position || clock.isFrozen) return@flow
        var step = 0
        while (currentCoroutineContext().isActive) {
            delay(4.seconds)
            step += 1
            emit(OwnPositionState.Position(drifted(initial.position, step)))
        }
    }

    /** “İzin ver” is granted at once; a refusal stays refused. */
    override suspend fun requestOwnPositionAccess(): Boolean {
        if (ownPositionOverride == OwnPositionState.NeedsPermission) ownPositionOverride = null
        return ownPositionOverride == null
    }

    override fun liveSharingContinuesInBackground(): Boolean = !liveSharingNoticeOff

    /** “Bildirimlere izin ver” is granted at once. */
    override suspend fun allowLiveSharingNotice(): NoticePermission {
        liveSharingNoticeOff = false
        return NoticePermission.Allowed
    }

    /** The demo share walks from the pier towards selin_n, about 15 m every 3 s, and stops 30 m short of her; it ends with its share. */
    private fun walkDemoShare(id: MessageId, until: Instant) {
        synchronized(lock) { liveDemoJobs.remove(id)?.cancel() }
        val start = DesignFixtures.peerFixturePosition
        val target = DesignFixtures.ownFixturePosition
        val job = scope.launch {
            var step = 0
            while (isActive) {
                delay(3.seconds)
                if (clock.now >= until) return@launch
                step += 1
                val fraction = min(0.9, step * 0.05)
                val position = LivePosition(
                    latitude = start.latitude + (target.latitude - start.latitude) * fraction,
                    longitude = start.longitude + (target.longitude - start.longitude) * fraction,
                    accuracy = start.accuracy, updatedAt = clock.now,
                )
                livePositions.update { it + (id to position) }
            }
        }
        synchronized(lock) { liveDemoJobs[id] = job }
    }

    // MARK: Media

    /** The design's content as real bytes: photos as JPEG and the video as MP4 (through [artworkEncoder]), the PDF, the voice. A file still “on the relay” downloads first. */
    override suspend fun mediaPayload(message: MessageId, chat: ChatId): MediaPayload? {
        while (currentCoroutineContext().isActive) {
            val found = messageTable[chat]?.firstOrNull { it.id == message }
                ?: synchronized(lock) { mediaArchive[chat]?.firstOrNull { it.id == message } }
                ?: return null
            if (holdsMediaPayloads) {
                delay(200)
                continue
            }
            when (found.mediaAvailability) {
                MediaAvailability.Ready -> return payload(found)
                MediaAvailability.Remote -> simulateDownload(message, chat)
                is MediaAvailability.Downloading -> Unit
                MediaAvailability.Held, MediaAvailability.Unavailable, MediaAvailability.Preparing,
                MediaAvailability.PreparationFailed,
                -> return null
            }
            delay(100)
        }
        return null
    }

    private suspend fun payload(message: Message): MediaPayload? {
        val encoder = artworkEncoder
        return when (val content = message.content) {
            is MessageContent.Photo -> when (val source = content.source) {
                is MediaSource.Artwork -> encoder?.jpeg(source.artwork, PHOTO_PIXEL_WIDTH)?.let { MediaPayload(it, "image/jpeg", null) }
                is MediaSource.Memory -> MediaPayload(source.data, "image/jpeg", null)
                is MediaSource.File -> readFile(source.uri)?.let { MediaPayload(it, "image/jpeg", null) }
            }
            is MessageContent.Video -> {
                val artwork = (content.source as? MediaSource.Artwork)?.artwork ?: DesignArtwork.SunsetVideo
                encoder?.mp4(artwork)?.let { MediaPayload(it, "video/mp4", null) }
            }
            is MessageContent.Voice -> {
                synchronized(lock) { voiceAudio[message.id] }?.let { return MediaPayload(it, VoiceRecording.CONTENT_TYPE, null) }
                // The design's voice message has no recording: a soft hum that follows its waveform stands in.
                val samples = VoiceWaveform.data(content.waveform)
                val audio = withContext(Dispatchers.Default) { FixtureMedia.voiceWav(content.duration, samples) }
                MediaPayload(audio, "audio/wav", null)
            }
            is MessageContent.File -> when (MediaFileKind.of(content.name)) {
                MediaFileKind.Pdf -> MediaPayload(withContext(Dispatchers.Default) { FixtureMedia.leasePdf(english) }, "application/pdf", content.name)
                MediaFileKind.Movie -> encoder?.mp4(DesignArtwork.SunsetVideo)?.let { MediaPayload(it, "video/mp4", content.name) }
                MediaFileKind.Image -> encoder?.jpeg(DesignArtwork.NightPier, PHOTO_PIXEL_WIDTH)?.let { MediaPayload(it, "image/jpeg", content.name) }
                MediaFileKind.Audio, MediaFileKind.Other -> MediaPayload(Bytes.utf8(content.name), "application/octet-stream", content.name)
            }
            else -> null
        }
    }

    private suspend fun readFile(uri: URI): Bytes? = withContext(Dispatchers.IO) {
        runCatching { Bytes.wrap(File(uri).readBytes()) }.getOrNull()
    }

    override suspend fun downloadMedia(message: MessageId, chat: ChatId) {
        val found = messageTable[chat]?.firstOrNull { it.id == message } ?: return
        if (found.mediaAvailability != MediaAvailability.Remote) return
        simulateDownload(message, chat)
    }

    override fun simulateDownload(message: MessageId, chat: ChatId, duration: Duration) {
        synchronized(lock) {
            if (downloadJobs[message] != null) return
            setMediaAvailability(MediaAvailability.Downloading(0.0), message, chat)
            downloadJobs[message] = scope.launch {
                val steps = 24
                for (step in 1..steps) {
                    delay(duration / steps)
                    setMediaAvailability(MediaAvailability.Downloading(step.toDouble() / steps), message, chat)
                }
                setMediaAvailability(MediaAvailability.Ready, message, chat)
                synchronized(lock) { downloadJobs.remove(message) }
            }
        }
    }

    // MARK: Verification

    override suspend fun safetyNumber(chat: ChatId): SafetyNumberInfo? =
        synchronized(lock) { safetyNumbers[chat] } ?: standInSafetyNumber(chat)

    override suspend fun markVerified(chat: ChatId, number: Bytes): Boolean {
        // Bound to the number the user compared, like the live service.
        val info = safetyNumber(chat) ?: return false
        if (info.scannablePayload != number) return false
        markVerified(chat, VerificationMethod.Manual)
        return true
    }

    override suspend fun markVerified(chat: ChatId, method: VerificationMethod) {
        synchronized(lock) {
            val info = safetyNumbers[chat] ?: standInSafetyNumber(chat)
            val now = clock.now
            val wasVerified = info.isVerified
            safetyNumbers[chat] = info.copy(verifiedAt = now, method = method, changedAt = null)
            updateChat(chat) { it.copy(peer = it.peer.copy(isVerified = true, safetyNumberChanged = false)) }
            if (!wasVerified) {
                // “Güvenlik numarası doğrulandı” (the 6.3 system line, proposed).
                nextMessageNumber += 1
                appendSystem(chat, MessageId("sys-verified-$nextMessageNumber"), SystemEvent.SafetyNumberVerified, now)
            }
        }
    }

    override suspend fun clearVerification(chat: ChatId) {
        synchronized(lock) {
            val info = safetyNumbers[chat] ?: standInSafetyNumber(chat)
            safetyNumbers[chat] = info.copy(verifiedAt = null, method = null)
            updateChat(chat) { it.copy(peer = it.peer.copy(isVerified = false)) }
        }
    }

    override suspend fun verifyScannedSafetyCode(code: String, chat: ChatId): SafetyCodeComparison {
        val scanned = SafetyQRCodec.decode(code) ?: return SafetyCodeComparison.NotASafetyCode
        val info = safetyNumber(chat) ?: return SafetyCodeComparison.Mismatch
        // Fixture semantics: the raw payloads (the live comparison is libsignal's).
        if (!SafetyQRCodec.constantTimeEquals(scanned, info.scannablePayload)) return SafetyCodeComparison.Mismatch
        markVerified(chat, VerificationMethod.FaceToFace)
        return SafetyCodeComparison.Match
    }

    override suspend fun acceptSafetyNumberChange(chat: ChatId, number: Bytes): Boolean {
        synchronized(lock) {
            val info = safetyNumbers[chat]
            if (info == null || !info.hasUnverifiedChange || info.scannablePayload != number) return false
            safetyNumbers[chat] = info.copy(changedAt = null)
            updateChat(chat) { it.copy(peer = it.peer.copy(safetyNumberChanged = false)) }
            // “Yeni güvenlik numarası kabul edildi · doğrulanmadı” (system line).
            nextMessageNumber += 1
            appendSystem(chat, MessageId("sys-accepted-$nextMessageNumber"), SystemEvent.SafetyNumberAccepted, clock.now)
            return true
        }
    }

    override suspend fun resolveContactCode(code: String): ContactCodeResolution {
        val decoded = ContactCode.decode(code) ?: return ContactCodeResolution.NotAContactCode
        val name = decoded.username
        if (name == accountFlow.value?.username) return ContactCodeResolution.OwnCode
        var exists = chatsFlow.value.any { it.peer.username == name }
        if (!exists && lookup(name) is UserLookup.Found) exists = true
        if (!exists) return ContactCodeResolution.Unknown
        // Fixture semantics: every user's key has `fixtureKeyFingerprint`; a code for another key is
        // a key mismatch, never an unknown code.
        val fingerprint = decoded.keyFingerprint
        if (fingerprint != null && fingerprint.lowercase() != DesignFixtures.fixtureKeyFingerprint(name)) return ContactCodeResolution.KeyMismatch
        return ContactCodeResolution.Contact(name)
    }

    override suspend fun addContact(code: String): ContactCodeAddition {
        val decoded = ContactCode.decode(code) ?: return ContactCodeAddition.Failed
        val resolution = resolveContactCode(code) as? ContactCodeResolution.Contact ?: return ContactCodeAddition.Failed
        val name = resolution.username
        val chat = synchronized(lock) {
            chatsFlow.value.firstOrNull { it.peer.username == name }?.id ?: run {
                // Scanned side by side: an accepted contact at once.
                val peer = stranger(name).copy(isAcceptedContact = true, identityFingerprint = DesignFixtures.fixtureKeyFingerprint(name))
                val id = ChatId(name)
                chatsFlow.update { listOf(ChatSummary(id, peer, null, 0, GhostState.Off, settingsFlow.value.defaultDisappearing)) + it }
                messageTable[id] = emptyList()
                id
            }
        }
        // Without the key's fingerprint the code proves nothing: added, not verified.
        if (decoded.keyFingerprint == null) return ContactCodeAddition.Added(chat)
        markVerified(chat, VerificationMethod.FaceToFace)
        return ContactCodeAddition.Verified(chat)
    }

    override suspend fun setSafetyNumberChanged(chat: ChatId) {
        synchronized(lock) {
            val info = safetyNumbers[chat] ?: standInSafetyNumber(chat)
            safetyNumbers[chat] = info.copy(changedAt = clock.now, verifiedAt = null, method = null)
            updateChat(chat) { it.copy(peer = it.peer.copy(isVerified = false, safetyNumberChanged = true)) }
        }
    }

    // MARK: Settings & backup

    override suspend fun updateSettings(settings: AppSettings) {
        settingsFlow.value = settings
    }

    override suspend fun backupEstimate(): BackupEstimate = DesignFixtures.backupEstimate

    override suspend fun setBiometricUnlock(enabled: Boolean, reason: String): Boolean {
        if (enabled) {
            val allowed = biometricsResult ?: biometricCheck?.authenticate(reason, cancelTitle = null)
            if (allowed != true) return settingsFlow.value.faceIdEnabled
        }
        settingsFlow.update { it.copy(faceIdEnabled = enabled) }
        return enabled
    }

    override suspend fun verifySecretCode(code: String): UnlockAttemptResult = unlock(code)

    override suspend fun enableBiometricUnlock(code: String): UnlockAttemptResult {
        val result = unlock(code)
        if (result == UnlockAttemptResult.Unlocked) settingsFlow.update { it.copy(faceIdEnabled = true) }
        return result
    }

    override suspend fun changeSecretCode(current: String, new: String) {
        if (current != synchronized(lock) { secretCode }) throw MessengerException.ChangeSecretCode(ChangeSecretCodeError.WrongCurrentCode)
        if (secretCodeProblem(new) != null) throw MessengerException.ChangeSecretCode(ChangeSecretCodeError.Failed)
        // Re-wrapping the vault key takes a moment in the live implementation.
        delay(latency)
        synchronized(lock) { secretCode = new }
    }

    override suspend fun backupPasswordIsSecretCode(password: String): Boolean = password == synchronized(lock) { secretCode }

    override fun backupPasswordProblem(password: String): BackupPasswordError? = CoreRules.backupPasswordProblem(password)

    override fun generateBackupPassword(): String = BackupPasswordGenerator.generate()

    /**
     * 24 progress steps over about 1.3 s while the core encrypts a stand-in account backup with
     * [password] (a real backup file: header, PBKDF2 1,000,000 iterations, AES-256-GCM).
     */
    override suspend fun exportBackup(contents: BackupContents, password: String, fileName: String, progress: (Double) -> Unit): URI {
        backupPasswordProblem(password)?.let { throw MessengerException.BackupPassword(it) }
        val file = coroutineScope {
            val sealed = async(Dispatchers.Default) {
                BackupFile.create(password, ByteArray(4_096).also(SecureRandom()::nextBytes))
            }
            val steps = 24
            for (step in 1..steps) {
                delay(55)
                progress(step.toDouble() / steps)
            }
            sealed.await()
        }
        synchronized(lock) { exportedPasswordDigests += digest(password) }
        return withContext(Dispatchers.IO) {
            // Emptied before every export, so a file left behind by a killed share sheet never lingers.
            exportDirectory.deleteRecursively()
            exportDirectory.mkdirs()
            val target = File(exportDirectory, fileName)
            target.writeBytes(file)
            target.toURI()
        }
    }

    override suspend fun finishExport(uri: URI, saved: Boolean) {
        withContext(Dispatchers.IO) { runCatching { File(uri).delete() } }
        if (saved) settingsFlow.update { it.copy(lastBackupAt = clock.now) }
    }

    /**
     * The fixtures describe every backup with the design's 7.3 card. A file must end in `.u2u`; a
     * document a picker handed over without a file name (a `content:` URI) is taken as the design's file.
     */
    override suspend fun inspectBackup(uri: URI): BackupFileInfo {
        val fileName: String
        val size: Long
        if (uri.scheme == "file") {
            val file = File(uri)
            if (file.extension.lowercase() != BackupContents.FILE_EXTENSION) throw MessengerException.BackupFile(BackupFileError.NotABackup)
            if (!file.canRead()) throw MessengerException.BackupFile(BackupFileError.Unreadable)
            fileName = file.name
            size = file.length()
        } else {
            val last = uri.path?.substringAfterLast('/').orEmpty()
            fileName = if (last.endsWith(".${BackupContents.FILE_EXTENSION}", ignoreCase = true)) last else DesignFixtures.backupEstimate.fileName
            size = 0
        }
        delay(350)
        return BackupFileInfo(
            uri = uri, fileName = fileName, byteCount = maxOf(size, DesignFixtures.backupEstimate.totalBytes),
            createdAt = DesignFixtures.designBackupDate,
        )
    }

    override suspend fun openBackup(file: BackupFileInfo, password: String): BackupOpenResult {
        delay(700)
        synchronized(lock) { nextBackupOpenResult?.let { forced -> nextBackupOpenResult = null; return forced } }
        val known = synchronized(lock) { digest(password) in exportedPasswordDigests }
        return if (password == DesignFixtures.DEMO_BACKUP_PASSWORD || known) BackupOpenResult.Opened else BackupOpenResult.WrongPassword
    }

    /** Keys 0 → 8 %, messages 8 → 80 %, media 80 → 100 % over about 9 s. A new restore or [cancelRestore] ends the previous one. */
    override fun restoreBackup(file: BackupFileInfo): Flow<RestoreProgress> = flow {
        val generation = synchronized(lock) { ++restoreGeneration }
        val total = DesignFixtures.restoreProgress
        val ticks = 90
        for (tick in 0..ticks) {
            if (synchronized(lock) { restoreGeneration } != generation) throw CancellationException("restore cancelled")
            val fraction = tick.toDouble() / ticks
            val stage = when {
                fraction < 0.08 -> RestoreProgress.Stage.Keys
                fraction < 0.8 -> RestoreProgress.Stage.Messages
                else -> RestoreProgress.Stage.Media
            }
            val messageShare = ((fraction - 0.08) / 0.72).coerceIn(0.0, 1.0)
            emit(
                RestoreProgress(
                    fraction = fraction, stage = stage,
                    messagesDone = (total.messagesTotal * messageShare).roundToInt(),
                    messagesTotal = total.messagesTotal, mediaBytes = total.mediaBytes,
                    estimatedRemaining = (160 * (1 - fraction)).seconds,
                ),
            )
            delay(100)
        }
    }

    override suspend fun cancelRestore() {
        synchronized(lock) { restoreGeneration++ }
    }

    // MARK: Fixture controls

    override fun setConnection(state: ConnectionState) {
        connectionFlow.value = state
    }

    override fun clearChats() {
        synchronized(lock) {
            chatsFlow.value = emptyList()
            messageTable.replace(emptyMap())
        }
    }

    override fun clearRequests() {
        requestsFlow.value = emptyList()
    }

    override fun setGhostUrgent(urgent: Boolean) {
        chatsFlow.value = DesignFixtures.chats(clock.now, urgent, english)
    }

    override fun setRestoreAccessPending(pending: Boolean) {
        restoreAccessPendingFlow.value = pending
    }

    override fun setStatus(status: MessageStatus, message: MessageId, chat: ChatId, at: Instant?) {
        val time = at ?: clock.now
        synchronized(lock) {
            updateMessage(message, chat) { found ->
                when (status) {
                    MessageStatus.Failed -> found.copy(status = status, failedAt = time)
                    MessageStatus.Delivered -> found.copy(status = status, deliveredAt = time)
                    MessageStatus.Read -> found.copy(status = status, readAt = time)
                    else -> found.copy(status = status)
                }
            }
            val newest = messageTable[chat]?.lastOrNull { !it.isSystem }
            updateChat(chat) { summary ->
                val last = summary.lastMessage
                if (last != null && last.direction == MessageDirection.Outgoing && newest?.id == message) summary.copy(lastMessage = last.copy(status = status)) else summary
            }
        }
    }

    override fun setMediaAvailability(availability: MediaAvailability, message: MessageId, chat: ChatId) {
        updateMessage(message, chat) { it.copy(mediaAvailability = availability) }
    }

    override fun setDisappearing(timer: DisappearingTimer, message: MessageId, chat: ChatId) {
        updateMessage(message, chat) { it.copy(disappearing = timer) }
    }

    override fun startDemoLiveLocation(chat: ChatId): MessageId {
        val id = DesignFixtures.demoLiveShareId
        val expiresAt: Instant
        synchronized(lock) {
            if (messageTable[chat]?.any { it.id == id } == true) return id
            val now = clock.now
            expiresAt = now + 38.minutes
            messageTable.update(chat) {
                it + Message(id = id, direction = MessageDirection.Incoming, sentAt = now, content = MessageContent.LiveLocation(expiresAt, isActive = true))
            }
            livePositions.update { it + (id to DesignFixtures.peerFixturePosition) }
        }
        refreshPreview(chat)
        if (!clock.isFrozen) walkDemoShare(id, expiresAt)
        return id
    }

    override fun addIncomingFile(chat: ChatId, availability: MediaAvailability): MessageId {
        val id = MessageId("${chat.value}-file-in")
        synchronized(lock) {
            if (messageTable[chat]?.any { it.id == id } == true) {
                setMediaAvailability(availability, id, chat)
                return id
            }
            val last = messageTable[chat]?.lastOrNull()?.sentAt ?: clock.now
            messageTable.update(chat) {
                it + Message(
                    id = id, direction = MessageDirection.Incoming, sentAt = last.plusSeconds(120),
                    content = MessageContent.File(if (english) "floor-plan.pdf" else "kat-plani.pdf", 1_400_000, "PDF", pageCount = 2),
                    mediaAvailability = availability,
                )
            }
        }
        return id
    }

    override fun addIncomingVoice(chat: ChatId, availability: MediaAvailability): MessageId {
        val id = MessageId("${chat.value}-voice-in")
        synchronized(lock) {
            if (messageTable[chat]?.any { it.id == id } == true) {
                setMediaAvailability(availability, id, chat)
                return id
            }
            val last = messageTable[chat]?.lastOrNull()?.sentAt ?: clock.now
            messageTable.update(chat) {
                it + Message(
                    id = id, direction = MessageDirection.Incoming, sentAt = last.plusSeconds(60),
                    content = MessageContent.Voice(9.seconds, DesignFixtures.voiceWaveform, playedFraction = 0.0),
                    mediaAvailability = availability,
                )
            }
        }
        refreshPreview(chat)
        return id
    }

    override fun addOutgoingVideo(chat: ChatId, failed: Boolean): MessageId {
        val id = MessageId("${chat.value}-video-out")
        val availability = if (failed) MediaAvailability.PreparationFailed else MediaAvailability.Preparing
        val status = if (failed) MessageStatus.Failed else MessageStatus.Sending
        synchronized(lock) {
            if (messageTable[chat]?.any { it.id == id } == true) {
                val failedAt = if (failed) clock.now else null
                updateMessage(id, chat) { it.copy(mediaAvailability = availability, status = status, failedAt = failedAt) }
                return id
            }
            val sentAt = (messageTable[chat]?.lastOrNull()?.sentAt ?: clock.now).plusSeconds(120)
            messageTable.update(chat) {
                it + Message(
                    id = id, direction = MessageDirection.Outgoing, sentAt = sentAt,
                    content = MessageContent.Video(MediaSource.Artwork(DesignArtwork.SunsetVideo), 18.seconds, 0), status = status,
                    disappearing = chatsFlow.value.firstOrNull { summary -> summary.id == chat }?.disappearing ?: DisappearingTimer.Off,
                    mediaAvailability = availability, failedAt = if (failed) sentAt else null,
                )
            }
        }
        return id
    }

    override fun simulateVideoSend(chat: ChatId, duration: Duration) {
        finishPreparing(listOf(addOutgoingVideo(chat)), chat, each = duration)
    }

    /** The items are prepared one after another, in order, [each] apart: each one sent in its own bubble (a video with its size). [updatingList]: the 4.1 row follows (“Gönderildi”). */
    private fun finishPreparing(ids: List<MessageId>, chat: ChatId, each: Duration, updatingList: Boolean = false) {
        if (ids.isEmpty()) return
        scope.launch {
            for (id in ids) {
                if (each > Duration.ZERO) delay(each)
                updateMessage(id, chat) { message ->
                    if (message.mediaAvailability != MediaAvailability.Preparing) return@updateMessage message
                    val content = message.content
                    message.copy(
                        content = if (content is MessageContent.Video) content.copy(byteCount = 12_400_000) else content,
                        mediaAvailability = MediaAvailability.Ready,
                        status = MessageStatus.Sent,
                    )
                }
                if (updatingList) refreshPreview(chat)
            }
        }
    }

    // MARK: Helpers

    /** Someone the user has no chat with: their palette from the name, no presence, not verified. */
    private fun stranger(name: String) =
        Peer(username = name, palette = AvatarPalette.forUsername(name), isVerified = false, isAcceptedContact = false, presence = Presence.Hidden)

    private fun initialStatus() = UnlockStatus(failures = 0, nextAttemptAllowedAt = null, upcoming = CoreRules.upcomingDelay(0))

    private fun digest(password: String): Bytes = Bytes.wrap(MessageDigest.getInstance("SHA-256").digest(password.encodeToByteArray()))

    private operator fun Instant.plus(duration: Duration): Instant = plus(duration.toJavaDuration())

    companion object {
        private val DENIZ = ChatId("deniz")
        private val MERT = ChatId("mert_k")
        private val BORA = ChatId("bora")

        /** A contact blocked before Ghost erased the chat (7.1 › Engellenenler). */
        private val LALE = ChatId("lale")

        /** selin_n's own live share in the chat with mert_k (m4). */
        private val MYLIVE_SHARE = MessageId("m4")

        /** The width the design photos are encoded at (the viewer's full image). */
        const val PHOTO_PIXEL_WIDTH = 1_536

        /**
         * Any other chat's safety number: a deterministic stand-in derived from the username
         * (FNV-1a seed, a linear congruential generator), the same digits as on iOS.
         */
        fun standInSafetyNumber(chat: ChatId): SafetyNumberInfo {
            var generator = chat.value.encodeToByteArray().fold(1469598103934665603uL) { hash, byte ->
                (hash xor byte.toUByte().toULong()) * 1099511628211uL
            }
            val groups = (0 until 12).map {
                generator = generator * 6364136223846793005uL + 1442695040888963407uL
                ((generator shr 33) % 100_000uL).toString().padStart(5, '0')
            }
            return SafetyNumberInfo(groups = groups, scannablePayload = Bytes.utf8(groups.joinToString("")), verifiedAt = null, method = null)
        }

        /** What 4.1 shows for a message (line 2 of the row); `null` for a system line. */
        fun preview(message: Message): MessagePreview? {
            val kind = when (val content = message.content) {
                is MessageContent.Text -> if (message.isDisappearing) MessagePreviewKind.Disappearing else MessagePreviewKind.Text(content.text)
                is MessageContent.Photo -> MessagePreviewKind.Photo
                is MessageContent.Video -> MessagePreviewKind.Video
                is MessageContent.Voice -> MessagePreviewKind.Voice
                is MessageContent.File -> MessagePreviewKind.File(content.name)
                is MessageContent.Location -> MessagePreviewKind.Location
                is MessageContent.LiveLocation -> if (content.isActive) MessagePreviewKind.LiveLocation else MessagePreviewKind.LiveLocationEnded
                is MessageContent.TextSticker, is MessageContent.Sticker -> MessagePreviewKind.Sticker
                is MessageContent.ViewOnce -> MessagePreviewKind.PhotoViewOnce
                MessageContent.Deleted -> MessagePreviewKind.Deleted
                is MessageContent.System -> return null
            }
            return MessagePreview(kind, message.direction, message.status, message.sentAt)
        }

        /** A few metres around [start] (a slow circle). */
        fun drifted(start: LivePosition, step: Int): LivePosition {
            val angle = step * 0.5
            return LivePosition(
                latitude = start.latitude + 0.00004 * sin(angle),
                longitude = start.longitude + 0.00005 * cos(angle),
                accuracy = start.accuracy,
            )
        }
    }
}
