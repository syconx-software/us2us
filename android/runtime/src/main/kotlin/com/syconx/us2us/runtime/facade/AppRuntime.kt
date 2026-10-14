package com.syconx.us2us.runtime.facade

import android.content.Context
import android.graphics.RectF
import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.messenger.TokenRefusedException
import com.syconx.us2us.core.presence.PresenceStatus
import com.syconx.us2us.core.profile.ProfilePicture
import com.syconx.us2us.core.relay.RelayException
import com.syconx.us2us.core.safety.SafetyNumber
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.core.vault.BackupPasswordPolicy
import com.syconx.us2us.core.vault.UnlockStatus
import com.syconx.us2us.runtime.engine.AvatarLookup
import com.syconx.us2us.runtime.engine.AvatarRecordStatus
import com.syconx.us2us.runtime.engine.ChatSummary
import com.syconx.us2us.runtime.engine.ContactInfo
import com.syconx.us2us.runtime.engine.ConversationInfo
import com.syconx.us2us.runtime.engine.DirectoryEntry
import com.syconx.us2us.runtime.engine.EngineChange
import com.syconx.us2us.runtime.engine.GhostSettings
import com.syconx.us2us.runtime.engine.LocationInfo
import com.syconx.us2us.runtime.engine.MediaKind
import com.syconx.us2us.runtime.engine.MessageBody
import com.syconx.us2us.runtime.engine.MessageId
import com.syconx.us2us.runtime.engine.MessageItem
import com.syconx.us2us.runtime.engine.MessagingBackend
import com.syconx.us2us.runtime.engine.MessagingEngine
import com.syconx.us2us.runtime.engine.OutgoingContent
import com.syconx.us2us.runtime.engine.OwnProfilePicture
import com.syconx.us2us.runtime.engine.QuoteInfo
import com.syconx.us2us.runtime.engine.TokenRotationResult
import com.syconx.us2us.runtime.engine.UserSettings
import com.syconx.us2us.runtime.media.AvatarPhotoProcessor
import com.syconx.us2us.runtime.media.MediaInput
import com.syconx.us2us.runtime.media.MediaPreparation
import com.syconx.us2us.runtime.media.PreparedMedia
import com.syconx.us2us.runtime.media.VideoPreparation
import com.syconx.us2us.runtime.net.ConnectionEvents
import com.syconx.us2us.runtime.net.ConnectionStatus
import com.syconx.us2us.runtime.net.ConnectionSupervisor
import com.syconx.us2us.runtime.storage.AccountBackupService
import com.syconx.us2us.runtime.storage.ContactIdentity
import com.syconx.us2us.runtime.storage.IdentityVerification
import com.syconx.us2us.runtime.storage.LocalStorage
import com.syconx.us2us.runtime.storage.StorageLayout
import com.syconx.us2us.runtime.engine.NoticeStore
import com.syconx.us2us.runtime.engine.RuntimeStateFile
import com.syconx.us2us.runtime.support.AppPhase
import com.syconx.us2us.runtime.support.BackgroundTaskRunning
import com.syconx.us2us.runtime.support.CaptureEventSource
import com.syconx.us2us.runtime.support.RuntimeLog
import com.syconx.us2us.runtime.support.RuntimeString
import com.syconx.us2us.runtime.support.RuntimeStrings
import com.syconx.us2us.runtime.support.SecurePasteboard
import com.syconx.us2us.runtime.support.after
import com.syconx.us2us.runtime.support.logLabel
import com.syconx.us2us.runtime.support.utf8Prefix
import com.syconx.us2us.runtime.vault.AppDirectories
import com.syconx.us2us.runtime.vault.BiometryKind
import com.syconx.us2us.runtime.vault.QuickUnlockStatus
import com.syconx.us2us.runtime.vault.SecretCodeError
import com.syconx.us2us.runtime.vault.SecretKeyMaterial
import com.syconx.us2us.runtime.vault.UnlockError
import com.syconx.us2us.runtime.vault.UnlockException
import com.syconx.us2us.runtime.vault.VaultController
import com.syconx.us2us.runtime.vault.VaultError
import com.syconx.us2us.runtime.vault.VaultException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The one object the app binds to. Its state is a set of [StateFlow]s, changed on [main] only;
 * every action is a `suspend` function that runs crypto, storage and network work off the main
 * thread (vault unlock and backup export take about a second).
 *
 * Lifecycle (PROTOCOL.md §5.4, with the app's own locking rules):
 *
 * 1. Launch → [LockState.LOCKED] (or [LockState.NEEDS_SETUP]). Overdue ghost wipes run without the DEK.
 * 2. Unlock (code or biometrics) → the DEK lives in memory only now; the store opens, overdue wipes
 *    run, the WebSocket connects (only while the app is active), backlog until `queue_empty`, then
 *    presence and receipts.
 * 3. Background → the UI locks **immediately** (nothing stays in observable state) and the privacy
 *    cover stays up; the connection lives on for a 30-second grace period to receive what is in
 *    flight; then presence OFFLINE, the socket closes, the store closes and the DEK is zeroed.
 *    (Exception: while the user shares live location, the store stays open for the location ticks
 *    until sharing ends; the socket is closed.)
 * 4. Return → the lock screen again; unlocking within the grace period resumes the same session
 *    without reconnecting.
 *
 * The vault is never left unlocked in the background: an unlock, registration or restore that
 * finishes after the user left the app closes its session at once and stays locked.
 */
class AppRuntime(
    val environment: RuntimeEnvironment,
    private val backgroundTasks: BackgroundTaskRunning,
    private val captureEvents: CaptureEventSource,
    val pasteboard: SecurePasteboard,
    private val strings: RuntimeStrings,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    // MARK: Observable state

    private val lockStateFlow = MutableStateFlow(LockState.NEEDS_SETUP)
    val lockState: StateFlow<LockState> = lockStateFlow.asStateFlow()

    private val unlockStatusFlow = MutableStateFlow<UnlockStatus?>(null)

    /** Wrong codes so far, the current wait and what further wrong codes cost. */
    val unlockStatus: StateFlow<UnlockStatus?> = unlockStatusFlow.asStateFlow()

    private val quickUnlockFlow = MutableStateFlow<QuickUnlockStatus>(QuickUnlockStatus.NotEnabled)
    val quickUnlock: StateFlow<QuickUnlockStatus> = quickUnlockFlow.asStateFlow()

    private val biometryKindFlow = MutableStateFlow(BiometryKind.NONE)
    val biometryKind: StateFlow<BiometryKind> = biometryKindFlow.asStateFlow()

    private val connectionFlow = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Offline)

    /** The "hat" line ([ConnectionStatus.line]). */
    val connection: StateFlow<ConnectionStatus> = connectionFlow.asStateFlow()

    private val chatsFlow = MutableStateFlow<List<ChatSummary>>(emptyList())

    /** Conversations (accepted, outgoing requests, blocked), most recent first. */
    val chats: StateFlow<List<ChatSummary>> = chatsFlow.asStateFlow()

    private val requestsFlow = MutableStateFlow<List<ChatSummary>>(emptyList())

    /** Incoming message requests ("Mesaj istekleri"). */
    val requests: StateFlow<List<ChatSummary>> = requestsFlow.asStateFlow()

    private val blockedContactsFlow = MutableStateFlow<List<ContactInfo>>(emptyList())

    /**
     * Everyone blocked, with or without a conversation ("Engellenenler"): a ghost wipe or a deleted
     * conversation keeps the block, and these are not in [chats] then.
     */
    val blockedContacts: StateFlow<List<ContactInfo>> = blockedContactsFlow.asStateFlow()

    private val openPeerFlow = MutableStateFlow<AccountId?>(null)

    /** The conversation on screen. */
    val openPeer: StateFlow<AccountId?> = openPeerFlow.asStateFlow()

    private val openMessagesFlow = MutableStateFlow<List<MessageItem>>(emptyList())

    /** The messages of the open conversation, oldest first. */
    val openMessages: StateFlow<List<MessageItem>> = openMessagesFlow.asStateFlow()

    private val downloadProgressFlow = MutableStateFlow<Map<MessageId, Double>>(emptyMap())

    /** Attachments of the open conversation downloading right now: the fraction received (0…1) by message id. Memory only. */
    val downloadProgress: StateFlow<Map<MessageId, Double>> = downloadProgressFlow.asStateFlow()

    private val messageRevisionsFlow = MutableStateFlow<Map<AccountId, Int>>(emptyMap())

    /**
     * Grows each time a conversation's stored messages change, open or not: a screen that lists a
     * conversation's items outside it (the contact screen's media) reads them again. Memory only.
     */
    val messageRevisions: StateFlow<Map<AccountId, Int>> = messageRevisionsFlow.asStateFlow()

    private val presenceFlow = MutableStateFlow<Map<AccountId, PresenceStatus>>(emptyMap())

    /** "Active" / "Active N min ago" of eligible contacts (§8.6). */
    val presence: StateFlow<Map<AccountId, PresenceStatus>> = presenceFlow.asStateFlow()

    private val typingFlow = MutableStateFlow<Set<AccountId>>(emptySet())
    val typing: StateFlow<Set<AccountId>> = typingFlow.asStateFlow()

    private val settingsFlow = MutableStateFlow(UserSettings())
    val settings: StateFlow<UserSettings> = settingsFlow.asStateFlow()

    private val lastBackupAtFlow = MutableStateFlow<Instant?>(null)

    /** When an exported backup was last saved (7.1), from the vault's encrypted runtime state; `null` while locked and before the first one. */
    val lastBackupAt: StateFlow<Instant?> = lastBackupAtFlow.asStateFlow()

    private val ownUsernameFlow = MutableStateFlow<String?>(null)
    val ownUsername: StateFlow<String?> = ownUsernameFlow.asStateFlow()

    private val ownAvatarFlow = MutableStateFlow<OwnProfilePicture?>(null)

    /** The own profile picture, "Show in search" and the state of the search record (§8.9, §8.10); `null` while locked. */
    val ownAvatar: StateFlow<OwnProfilePicture?> = ownAvatarFlow.asStateFlow()

    private val safetyNumberChangesFlow = MutableStateFlow<Set<AccountId>>(emptySet())

    /** Contacts whose safety number changed and was not accepted yet. */
    val safetyNumberChanges: StateFlow<Set<AccountId>> = safetyNumberChangesFlow.asStateFlow()

    private val wipeAfterTenFailuresFlow = MutableStateFlow(false)
    val wipeAfterTenFailures: StateFlow<Boolean> = wipeAfterTenFailuresFlow.asStateFlow()

    private val keptAccountAfterFailedSetupFlow = MutableStateFlow(false)

    /**
     * The last [createAccount] or [restore] failed after its account was saved: the new vault is
     * kept, locked under the new code, and its next unlock finishes what is missing.
     */
    val keptAccountAfterFailedSetup: StateFlow<Boolean> = keptAccountAfterFailedSetupFlow.asStateFlow()

    private val tokenRotationPendingFlow = MutableStateFlow(false)

    /**
     * A restore's token rotation got no answer yet (§5.5 step 3.4): until the relay says which token
     * the account has, the old phone may still have access to the account, and this one does not
     * connect. Read from the account at every unlock and whenever the connection changes (every
     * connection attempt asks the relay first); `false` while locked. [checkPendingTokenRotation]
     * asks at once.
     */
    val tokenRotationPending: StateFlow<Boolean> = tokenRotationPendingFlow.asStateFlow()

    private val privacyCoverFlow = MutableStateFlow(false)

    /** Cover the UI (recents snapshot, inactive states). */
    val isPrivacyCoverVisible: StateFlow<Boolean> = privacyCoverFlow.asStateFlow()

    private val screenCapturedFlow = MutableStateFlow(false)

    /** The screen is recorded or mirrored: hide conversation content. */
    val isScreenCaptured: StateFlow<Boolean> = screenCapturedFlow.asStateFlow()

    private val bannerFlow = MutableStateFlow<MessageBanner?>(null)
    val banner: StateFlow<MessageBanner?> = bannerFlow.asStateFlow()

    // MARK: Dependencies

    internal val vault = VaultController(
        environment.directories,
        environment.secureItems,
        environment.biometrics,
        environment.javaClock,
        environment.vault,
        environment.bootClock,
        io,
    )
    private val scope = CoroutineScope(
        SupervisorJob() + main + CoroutineExceptionHandler { _, error -> RuntimeLog.lifecycle.error("runtime task failed", error.logLabel) },
    )
    private val directories: AppDirectories get() = environment.directories
    private val layout: StorageLayout get() = environment.storageLayout

    internal var session: Session? = null
    private var phase = AppPhase.ACTIVE
    private var networkAvailable = true
    private var graceJob: Job? = null
    private var graceToken: Int? = null

    /**
     * When the grace period ends on the wall clock. Its timer counts only the time the CPU is
     * awake, so a phone asleep in a pocket would stretch it; every location tick and connection
     * change ends an overdue grace at once.
     */
    private var graceDeadline: Instant? = null

    /** A teardown still closing the previous session. */
    private var teardownJob: Job? = null
    private var refreshJob: Job? = null
    private var pendingRefresh = PendingRefresh()
    private var tickJob: Job? = null

    /** Conversations already told about the current screen recording. */
    private val recordingNotified = HashSet<AccountId>()

    /** The app went to the background while an unlock, registration or restore was running: it must not end unlocked. */
    private var leftDuringWork = false

    /** Sessions whose teardown has not finished: the system's expiration handler zeroes their keys at once. */
    private val closingSessions = ArrayList<Session>()

    /** Ends a live-location-only session when its sharing runs out, even though the UI tick is cancelled while locked. */
    private var liveLocationWatch: Job? = null

    init {
        try {
            directories.prepare()
        } catch (_: Exception) {
        }
        // A new process: plaintext that a crash or a kill left in either staging directory goes now,
        // with no key and before anything else — not at the next unlock.
        directories.purgeStaging()
        LocalStorage.purgeStaging(layout)
        // Overdue ghost wipes need no key (§5.3): run them before anything else.
        runBackgroundWipes(directories, environment.clock.now())
        lockStateFlow.value = if (vault.hasVault) LockState.LOCKED else LockState.NEEDS_SETUP
        captureEvents.start(
            screenshot = { scope.launch { screenshotTaken() } },
            captureChanged = { captured -> scope.launch { captureChanged(captured) } },
        )
        screenCapturedFlow.value = captureEvents.isCaptured
        environment.network?.start { available ->
            scope.launch {
                networkAvailable = available
                session?.supervisor?.networkChanged(available)
            }
        }
        scope.launch { refreshLockScreen() }
    }

    companion object {
        /** Overdue ghost wipes from an alarm or at launch: no DEK, no network, only key slots are deleted (§5.3). */
        fun runBackgroundWipes(directories: AppDirectories, now: Instant): Int {
            val wiped = LocalStorage.runBackgroundWipes(StorageLayout.standard(directories), now)
            NoticeStore.runBackgroundWipes(File(directories.runtime, MessagingEngine.NOTICE_DIRECTORY), now)
            return wiped
        }

        /** When the next background wipe is due (the alarm's time). */
        fun nextBackgroundWipeDate(directories: AppDirectories): Instant? =
            LocalStorage.nextBackgroundWipeDate(StorageLayout.standard(directories))

        /** `401` or `404`: the account is gone on the relay already (or its token no longer works). */
        fun meansAccountGone(error: RelayException): Boolean = error.httpStatus == 401 || error.httpStatus == 404
    }

    // MARK: App phase

    /** Map the process and activity lifecycle here (`AppPhase.of`). Call on the main thread. */
    fun scenePhaseChanged(newPhase: AppPhase) {
        val previous = phase
        phase = newPhase
        when (newPhase) {
            AppPhase.ACTIVE -> {
                privacyCoverFlow.value = false
                val current = session
                if (lockStateFlow.value == LockState.UNLOCKED && current != null) current.supervisor.start()
                if (previous == AppPhase.BACKGROUND) scope.launch { refreshLockScreen() }
            }
            AppPhase.INACTIVE -> privacyCoverFlow.value = true
            AppPhase.BACKGROUND -> {
                privacyCoverFlow.value = true
                pasteboard.clearIfOwned()
                scheduleBackgroundWipes()
                when (lockStateFlow.value) {
                    LockState.UNLOCKED -> lockForBackground()
                    LockState.WORKING -> leftDuringWork = true
                    LockState.LOCKED, LockState.NEEDS_SETUP -> Unit
                }
            }
        }
    }

    /** Locks on every return from the background, by design. The UI state is cleared now; the session receives for the grace period. */
    private fun lockForBackground() {
        clearUnlockedState()
        lockStateFlow.value = LockState.LOCKED
        val current = session ?: return
        current.inGrace = true
        graceToken = backgroundTasks.begin("us2us.grace") { scope.launch { graceExpired() } }
        if (graceToken == null) {
            // The system grants no background time: there is no grace period — the key and the
            // store go now, never into a suspension (§5.4 step 4).
            session = null
            wipeNow(current)
            close(current, announceOffline = false)
            RuntimeLog.lifecycle.info("no background time, locked at once")
            scope.launch { refreshLockScreen() }
            return
        }
        val clock = environment.clock
        val grace = environment.gracePeriod
        graceDeadline = clock.now().after(grace)
        graceJob?.cancel()
        graceJob = scope.launch {
            clock.sleep(grace)
            endGrace()
        }
        scope.launch { refreshLockScreen() }
    }

    /** The grace period is over on the wall clock while its timer still sleeps (the CPU slept meanwhile): it ends now. Main thread. */
    private fun endGraceIfOverdue() {
        val deadline = graceDeadline ?: return
        if (environment.clock.now().isBefore(deadline)) return
        graceDeadline = null
        graceJob?.cancel()
        graceJob = scope.launch { endGrace() }
    }

    /** The system wants the background time back: zero the key and close the store at once, then let the teardown finish. */
    private fun graceExpired() {
        graceJob?.cancel()
        graceJob = null
        graceDeadline = null
        graceToken?.let {
            graceToken = null
            backgroundTasks.end(it)
        }
        val current = session
        if (current != null && current.inGrace) {
            session = null
            wipeNow(current)
            close(current, announceOffline = true)
        }
        wipeClosingSessions()
    }

    /** Zeroes the DEK and every key derived from it, and closes the store, synchronously. */
    private fun wipeNow(session: Session) {
        session.key.wipe()
        session.engine.wipeDerivedKeys()
        session.storage.close()
    }

    private fun wipeClosingSessions() {
        for (closing in closingSessions.toList()) wipeNow(closing)
    }

    /**
     * Tears a session down in a tracked job: opening the storage again (an unlock right after
     * locking) waits for it. Outside the grace period's own background task, a background task
     * covers the teardown, and its expiration zeroes the key at once.
     */
    private fun close(closing: Session, announceOffline: Boolean): Job {
        if (session === closing) session = null
        liveLocationWatch?.cancel()
        liveLocationWatch = null
        if (closingSessions.none { it === closing }) closingSessions += closing
        val needsBackgroundTime = graceToken == null
        val token = if (needsBackgroundTime) backgroundTasks.begin("us2us.teardown") { scope.launch { wipeClosingSessions() } } else null
        if (needsBackgroundTime && token == null && phase != AppPhase.ACTIVE) wipeNow(closing)
        val previous = teardownJob
        val job = scope.launch(NonCancellable) {
            previous?.join()
            teardown(closing, announceOffline)
            closingSessions.removeAll { it === closing }
            token?.let(backgroundTasks::end)
        }
        teardownJob = job
        return job
    }

    private suspend fun awaitPendingTeardown() {
        while (true) {
            val pending = teardownJob ?: return
            pending.join()
            if (teardownJob === pending) teardownJob = null
        }
    }

    private suspend fun endGrace() {
        graceJob = null
        graceDeadline = null
        try {
            val current = session
            if (current == null || !current.inGrace || lockStateFlow.value == LockState.UNLOCKED) return
            if (phase == AppPhase.BACKGROUND && current.engine.activeLiveLocationSessions().isNotEmpty()) {
                // Live location keeps sending ticks in the background (§8.3); the socket closes,
                // receiving waits for the next open. The store and the DEK stay only while sharing runs.
                current.liveLocationOnly = true
                current.engine.goingOffline()
                current.supervisor.stop()
                current.engine.connectionClosed()
                // Nor is anything fetched: a download already running stops too.
                current.engine.enterLiveLocationOnly()
                RuntimeLog.lifecycle.info("grace over, live location continues")
                startLiveLocationWatch(current)
                endLiveLocationOnlySessionIfIdle()
                return
            }
            close(current, announceOffline = true).join()
            RuntimeLog.lifecycle.info("grace over, locked")
        } finally {
            graceToken?.let {
                graceToken = null
                backgroundTasks.end(it)
            }
        }
    }

    /** Ends a live-location-only session when sharing is over. Returns only once the key is zeroed. */
    internal suspend fun endLiveLocationOnlySessionIfIdle() {
        val current = session
        if (current != null && current.liveLocationOnly && lockStateFlow.value != LockState.UNLOCKED &&
            current.engine.activeLiveLocationSessions().isEmpty() &&
            session === current && current.liveLocationOnly && lockStateFlow.value != LockState.UNLOCKED
        ) {
            close(current, announceOffline = false)
            RuntimeLog.lifecycle.info("live location over, locked")
        }
        if (session == null) awaitPendingTeardown()
    }

    /** Sleeps until the last running live-location session ends by itself and closes the session then. */
    private fun startLiveLocationWatch(watched: Session) {
        liveLocationWatch?.cancel()
        val clock = environment.clock
        liveLocationWatch = scope.launch {
            while (true) {
                val end = watched.engine.liveLocationEnd() ?: break
                val wait = (end.toEpochMilli() - clock.now().toEpochMilli()).coerceAtLeast(1000L)
                clock.sleep(wait.milliseconds)
            }
            endLiveLocationOnlySessionIfIdle()
        }
    }

    /** Closes everything of a session and zeroes its key, whatever fails on the way. */
    private suspend fun teardown(closing: Session, announceOffline: Boolean) {
        closing.stopListening()
        try {
            if (announceOffline && !closing.liveLocationOnly) {
                try {
                    closing.engine.goingOffline()
                } catch (_: Exception) {
                }
            }
            closing.supervisor.stop()
            closing.engine.stop()
        } finally {
            closing.engine.wipeDerivedKeys()
            closing.backend.close()
            try {
                withContext(io) { closing.storage.close() }
            } catch (e: Exception) {
                RuntimeLog.lifecycle.error("storage not closed cleanly", e.logLabel)
            }
            closing.key.wipe()
            closing.supervisorScope.cancel()
            directories.purgeStaging()
            if (session === closing) session = null
            if (lockStateFlow.value != LockState.UNLOCKED) connectionFlow.value = ConnectionStatus.Offline
        }
    }

    // MARK: Lock screen

    suspend fun refreshLockScreen() = withContext(main) {
        try {
            val status = vault.unlockStatus()
            val quick = vault.quickUnlockStatus()
            unlockStatusFlow.value = status
            quickUnlockFlow.value = quick
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The vault file is gone or unreadable right now: what was shown stays.
            RuntimeLog.lifecycle.notice("lock screen not refreshed", e.logLabel)
        }
        wipeAfterTenFailuresFlow.value = vault.wipeAfterTenFailures
        biometryKindFlow.value = environment.biometrics.availability().kind
    }

    /**
     * Unlocks with the six-digit code. Wrong codes are counted before they are checked; after two,
     * increasing delays apply. If the optional "wipe after 10" fires, everything is erased and the
     * error is `UnlockError.Vault(VaultError.Wiped)`.
     */
    suspend fun unlock(code: String) = withContext(main) {
        if (lockStateFlow.value != LockState.LOCKED) return@withContext
        lockStateFlow.value = LockState.WORKING
        leftDuringWork = false
        try {
            val key = vault.unlock(code)
            openSession(key)
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                if (e is UnlockException && e.error == UnlockError.Vault(VaultError.Wiped)) eraseLocalData()
                settleAfterFailedUnlock()
            }
            throw e
        }
    }

    /** Biometric quick unlock. `UnlockError.CodeRequired` and a failed or cancelled prompt mean: show the code keypad. */
    suspend fun unlockWithBiometrics() = withContext(main) {
        if (lockStateFlow.value != LockState.LOCKED) return@withContext
        lockStateFlow.value = LockState.WORKING
        leftDuringWork = false
        try {
            val key = vault.unlockWithBiometrics(strings.string(RuntimeString.BIOMETRIC_REASON), strings.string(RuntimeString.BIOMETRIC_USE_CODE))
            openSession(key)
        } catch (e: Throwable) {
            withContext(NonCancellable) { settleAfterFailedUnlock() }
            throw e
        }
    }

    private suspend fun settleAfterFailedUnlock() {
        if (lockStateFlow.value == LockState.WORKING) {
            lockStateFlow.value = if (vault.hasVault) LockState.LOCKED else LockState.NEEDS_SETUP
        }
        refreshLockScreen()
    }

    /** Opens the store with a fresh key, or resumes the session that is still receiving in the grace period (same key, compared in constant time). */
    private suspend fun openSession(key: SecretKeyMaterial) {
        val current = session
        if (current != null && (current.inGrace || current.liveLocationOnly) && !current.key.isWiped) {
            if (current.key.matches(key)) {
                key.wipe()
                liveLocationWatch?.cancel()
                liveLocationWatch = null
                graceJob?.cancel()
                graceJob = null
                graceDeadline = null
                graceToken?.let {
                    graceToken = null
                    backgroundTasks.end(it)
                }
                val wasLiveLocationOnly = current.liveLocationOnly
                current.inGrace = false
                current.liveLocationOnly = false
                if (wasLiveLocationOnly) current.engine.leaveLiveLocationOnly()
                becomeUnlocked(current)
                return
            }
            close(current, announceOffline = false)
        }
        awaitPendingTeardown()
        val opened = openStorage(key)
        val storage = opened.storage
        val account = try {
            withContext(io) { storage.read { it.account.load() } }
        } catch (_: Exception) {
            null
        }
        if (account == null) {
            // A vault without an account: registration never finished. Start over.
            withContext(io) { storage.close() }
            key.wipe()
            eraseLocalData()
            runtimeFailure(RuntimeError.Locked)
        }
        val backend = withContext(io) { environment.backends.open(account, storage) }
        val opening = makeSession(key, storage, backend)
        opening.engine.start(opened.wipeReport)
        becomeUnlocked(opening)
    }

    /** Opens (or creates) the encrypted storage off the main thread. */
    private suspend fun openStorage(key: SecretKeyMaterial): LocalStorage.Opened = withContext(io) {
        environment.storage.open(layout, key, environment.clock.now())
    }

    private fun makeSession(key: SecretKeyMaterial, storage: LocalStorage, backend: MessagingBackend): Session {
        val engine = MessagingEngine(storage, backend, directories.runtime, key, environment.clock, environment.engine)
        val supervisorScope = CoroutineScope(SupervisorJob() + io)
        val supervisor = ConnectionSupervisor(
            opener = { backend.openStream() },
            events = ConnectionEvents(
                deliver = { message -> engine.receive(message) },
                queueEmpty = { engine.connectionReachedQueueEmpty() },
            ),
            scope = supervisorScope,
            clock = environment.clock,
            backoff = environment.reconnectBackoff,
        )
        if (!networkAvailable) supervisor.networkChanged(false)
        val opened = Session(key, storage, backend, engine, supervisor, supervisorScope)
        opened.listeners += scope.launch { engine.changes.collect { engineChanged(it) } }
        opened.listeners += scope.launch {
            supervisor.statusUpdates.collect { status ->
                connectionChanged(status)
                if (!status.isConnected) engine.connectionClosed()
            }
        }
        return opened
    }

    private suspend fun becomeUnlocked(opened: Session) {
        if (leftDuringWork || phase == AppPhase.BACKGROUND) {
            // The user left while the code was derived, the proof-of-work solved or the backup
            // restored: never unlock in the background and never show the app again without the code.
            leftDuringWork = false
            clearUnlockedState()
            lockStateFlow.value = LockState.LOCKED
            if (session === opened) session = null
            close(opened, announceOffline = true)
            RuntimeLog.lifecycle.info("unlocked after leaving the app, locked again")
            refreshLockScreen()
            return
        }
        session = opened
        lockStateFlow.value = LockState.UNLOCKED
        recordingNotified.clear()
        ownUsernameFlow.value = opened.backend.username().value
        ownAvatarFlow.value = opened.backend.ownAvatar()
        refreshTokenRotationState(opened)
        pendingRefresh = PendingRefresh(all = true)
        performRefresh()
        startTicking()
        if (phase == AppPhase.ACTIVE) opened.supervisor.start()
        connectionFlow.value = opened.supervisor.status.value
        refreshLockScreen()
    }

    /** Locks now (a "lock" button): same as going to the background, without the grace period. */
    suspend fun lockNow() = withContext(main) {
        if (lockStateFlow.value != LockState.UNLOCKED) return@withContext
        clearUnlockedState()
        lockStateFlow.value = LockState.LOCKED
        session?.let { close(it, announceOffline = true).join() }
        refreshLockScreen()
    }

    private fun clearUnlockedState() {
        refreshJob?.cancel()
        refreshJob = null
        tickJob?.cancel()
        tickJob = null
        chatsFlow.value = emptyList()
        requestsFlow.value = emptyList()
        blockedContactsFlow.value = emptyList()
        openPeerFlow.value = null
        openMessagesFlow.value = emptyList()
        downloadProgressFlow.value = emptyMap()
        messageRevisionsFlow.value = emptyMap()
        presenceFlow.value = emptyMap()
        typingFlow.value = emptySet()
        bannerFlow.value = null
        safetyNumberChangesFlow.value = emptySet()
        settingsFlow.value = UserSettings()
        lastBackupAtFlow.value = null
        ownUsernameFlow.value = null
        ownAvatarFlow.value = null
        tokenRotationPendingFlow.value = false
        pasteboard.clearIfOwned()
    }

    // MARK: Onboarding

    /**
     * Creates the vault (the code entered twice), the encrypted store and the relay account
     * (proof-of-work: a few seconds). A failure before the account exists on the relay leaves
     * nothing behind, so the UI can retry, e.g. with another username after `username_taken`.
     * Once the account is registered and saved it is never erased again: a later failure leaves
     * the vault locked, and the code opens it.
     */
    suspend fun createAccount(username: String, displayName: String, code: String, confirmation: String) = withContext(main) {
        if (lockStateFlow.value != LockState.NEEDS_SETUP) runtimeFailure(RuntimeError.AlreadySetUp)
        keptAccountAfterFailedSetupFlow.value = false
        val normalized = normalizedUsername(username)
        lockStateFlow.value = LockState.WORKING
        leftDuringWork = false
        var key: SecretKeyMaterial? = null
        var storage: LocalStorage? = null
        try {
            eraseLocalData() // leftovers of an earlier installation
            lockStateFlow.value = LockState.WORKING
            val created = vault.create(code, confirmation)
            key = created
            val opened = openStorage(created).storage
            storage = opened
            saveDisplayName(displayName.utf8Prefix(MAX_DISPLAY_NAME_BYTES), created)
            val backend = environment.backends.register(normalized, opened)
            val session = makeSession(created, opened, backend)
            session.engine.start()
            becomeUnlocked(session)
        } catch (e: Throwable) {
            withContext(NonCancellable) { settleAfterFailedSetup(e, key, storage) }
        }
    }

    /**
     * Restores an account from a backup file (§5.5): on a new phone, or — [replacingLockedVault],
     * a forgotten code — in place of the locked vault on this one. In this order, and nothing on
     * this phone changes before the first two steps have passed:
     *
     * 1. The new code is checked, the file decrypted and the **whole** backup verified.
     * 2. The relay is asked, read-only, whether the backup's token still works.
     * 3. Only now is this phone erased and a new vault created under the new code.
     * 4. The token is rotated (the old phone loses access) and new keys are uploaded.
     *
     * Once the restored account is saved it is kept whatever fails next (a token rotation whose
     * answer was lost is settled at the next connection, [tokenRotationPending]). Only when the
     * relay refuses the backup during the rotation is the new vault erased again
     * ([RuntimeError.BackupRevoked]), or when it refuses the new token alone (the core's
     * `TokenRefusedException`; the backup still works).
     */
    suspend fun restore(backupFile: ByteArray, password: String, code: String, confirmation: String, replacingLockedVault: Boolean = false) =
        withContext(main) {
            val previous = lockStateFlow.value
            when (previous) {
                LockState.NEEDS_SETUP -> Unit
                LockState.LOCKED -> if (!replacingLockedVault) runtimeFailure(RuntimeError.AlreadySetUp)
                LockState.WORKING, LockState.UNLOCKED -> runtimeFailure(RuntimeError.AlreadySetUp)
            }
            keptAccountAfterFailedSetupFlow.value = false
            SecretCodeError.check(code, confirmation)?.let { throw VaultException(VaultError.InvalidNewCode(it)) }
            lockStateFlow.value = LockState.WORKING
            leftDuringWork = false
            val backup = try {
                readBackup(backupFile, password).also { checkOnRelay(it) }
            } catch (e: Throwable) {
                // Nothing was changed: a locked vault stays as it was, under its code.
                withContext(NonCancellable) {
                    if (lockStateFlow.value == LockState.WORKING) lockStateFlow.value = previous
                    refreshLockScreen()
                }
                throw e
            }
            var key: SecretKeyMaterial? = null
            var storage: LocalStorage? = null
            try {
                // Only now: the locked vault, or leftovers of an earlier installation.
                eraseLocalData()
                lockStateFlow.value = LockState.WORKING
                val created = vault.create(code, confirmation)
                key = created
                val opened = openStorage(created).storage
                storage = opened
                // Kept before the irreversible token rotation.
                saveDisplayName(backup.backup.displayName, created)
                val backend = environment.backends.restore(backup, opened)
                val session = makeSession(created, opened, backend)
                session.engine.start()
                becomeUnlocked(session)
            } catch (e: Throwable) {
                // The relay refused the backup's token and the new one (no token of this vault works), or
                // refused the new token alone (the backup still works): nothing in this vault is worth keeping.
                val refused = (e is RuntimeException && e.error == RuntimeError.BackupRevoked) || e is TokenRefusedException
                withContext(NonCancellable) { settleAfterFailedSetup(e, key, storage, keepingAccount = !refused) }
            }
        }

    /**
     * 7.3: opens a backup file and asks the relay, read-only, whether its token still works — the
     * checks [restore] makes before it changes anything — and changes nothing on this phone.
     */
    suspend fun checkBackup(backupFile: ByteArray, password: String) = withContext(main) {
        checkOnRelay(readBackup(backupFile, password))
    }

    /** Decrypts and fully verifies a backup file, off the main thread (about a second). */
    private suspend fun readBackup(file: ByteArray, password: String): AccountBackupFile = withContext(Dispatchers.Default) {
        if (file.isEmpty()) runtimeFailure(RuntimeError.UnreadableBackup)
        AccountBackupFile.import(file, password)
    }

    /** The backup's token still works on the relay: one read-only request on a client of its own. */
    private suspend fun checkOnRelay(backup: AccountBackupFile) {
        environment.backends.checkBackup(backup)
    }

    /** The display name of a new or restored account, into the encrypted runtime state the engine loads. */
    private suspend fun saveDisplayName(name: String, key: SecretKeyMaterial) = withContext(io) {
        RuntimeStateFile.update(directories.runtime, key) { state -> state.copy(settings = state.settings.copy(displayName = name)) }
    }

    /**
     * After a failed registration or restore: a saved account (its token is the only credential
     * now) is kept, locked; anything earlier is erased so onboarding can start over. Rethrows.
     */
    private suspend fun settleAfterFailedSetup(error: Throwable, key: SecretKeyMaterial?, storage: LocalStorage?, keepingAccount: Boolean = true): Nothing {
        if (keepingAccount && storage != null && holdsAccount(storage)) {
            if (session?.storage === storage) session = null
            withContext(io) { storage.close() }
            key?.wipe()
            clearUnlockedState()
            val kept = vault.hasVault
            lockStateFlow.value = if (kept) LockState.LOCKED else LockState.NEEDS_SETUP
            keptAccountAfterFailedSetupFlow.value = kept
            RuntimeLog.lifecycle.notice("setup interrupted after the account was saved; kept, locked")
            refreshLockScreen()
            throw error
        }
        key?.wipe()
        storage?.let { withContext(io) { it.close() } }
        eraseLocalData()
        lockStateFlow.value = LockState.NEEDS_SETUP
        keptAccountAfterFailedSetupFlow.value = false
        throw error
    }

    /**
     * Whether the new vault holds an account worth keeping: first from the durable fact that one
     * was saved through this storage, then from reading it back.
     */
    private suspend fun holdsAccount(storage: LocalStorage): Boolean {
        if (storage.hasSavedAccount) return true
        if (!storage.isOpen) return false
        return try {
            withContext(io) { storage.read { it.account.load() } } != null
        } catch (_: Exception) {
            false
        }
    }

    // MARK: Erase ("forgot code", "erase everything")

    /** Crypto-shreds everything on this phone: keys first, then every file. The relay account is left alone. */
    suspend fun eraseEverything() = withContext(main) {
        clearUnlockedState()
        eraseLocalData()
        lockStateFlow.value = LockState.NEEDS_SETUP
        refreshLockScreen()
    }

    /**
     * "Delete my account": `DELETE /v1/accounts/me` first, then [eraseEverything]. Only when the relay
     * confirms (or answers that the account is gone already) is anything erased.
     */
    suspend fun deleteAccountAndEraseEverything() = withContext(main) {
        deleteAccountOnRelay()
        eraseEverything()
    }

    /** The first half of [deleteAccountAndEraseEverything] alone: nothing erased. 401/404 count as deleted. */
    suspend fun deleteAccountOnRelay() = withContext(main) {
        val current = session
        if (lockStateFlow.value != LockState.UNLOCKED || current == null) runtimeFailure(RuntimeError.Locked)
        try {
            current.backend.deleteAccount()
        } catch (e: RelayException) {
            if (!meansAccountGone(e)) throw e
            RuntimeLog.lifecycle.info("account already gone on the relay")
        }
    }

    /** [deleteAccountAndEraseEverything] or [eraseEverything]; `false` when the relay did not confirm the deletion (then nothing was erased). */
    suspend fun eraseEverything(deleteAccountOnRelay: Boolean): Boolean = withContext(main) {
        if (!deleteAccountOnRelay) {
            eraseEverything()
            return@withContext true
        }
        try {
            deleteAccountAndEraseEverything()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RuntimeLog.lifecycle.notice("account deletion failed", e.logLabel)
            false
        }
    }

    private suspend fun eraseLocalData() {
        graceJob?.cancel()
        graceJob = null
        graceDeadline = null
        graceToken?.let {
            graceToken = null
            backgroundTasks.end(it)
        }
        session?.let { close(it, announceOffline = false) }
        // Keys out of memory and the store closed now; the teardown's network steps may still take a while.
        wipeClosingSessions()
        vault.destroyKeys()
        withContext(io) {
            try {
                LocalStorage.destroyAll(layout)
            } catch (_: Exception) {
            }
        }
        awaitPendingTeardown()
        withContext(io) {
            try {
                directories.eraseAll()
            } catch (_: Exception) {
            }
            try {
                directories.prepare()
            } catch (_: Exception) {
            }
        }
        pasteboard.clearIfOwned()
        connectionFlow.value = ConnectionStatus.Offline
        environment.wipeScheduler?.schedule(null)
        RuntimeLog.lifecycle.info("local data erased")
    }

    // MARK: Security settings

    /** A counted attempt: a wrong code updates [unlockStatus]; if the optional wipe fires, everything is erased. */
    suspend fun enableQuickUnlock(code: String) = withContext(main) {
        requireUnlocked()
        try {
            vault.enableQuickUnlock(code)
        } catch (e: UnlockException) {
            if (e.error == UnlockError.Vault(VaultError.Wiped)) {
                erasedByWrongCodes()
            } else {
                refreshLockScreen()
            }
            throw e
        }
        refreshLockScreen()
    }

    /** Whether quick unlock could be turned on now: biometrics enrolled and not locked out. */
    val quickUnlockAvailable: Boolean get() = environment.biometrics.availability().isUsable

    suspend fun disableQuickUnlock() = withContext(main) {
        vault.disableQuickUnlock()
        refreshLockScreen()
    }

    /** The current code is a counted attempt: if the optional wipe fires, everything is erased and the error is `VaultError.Wiped`. */
    suspend fun changeCode(current: String, new: String, confirmation: String) = withContext(main) {
        requireUnlocked()
        try {
            vault.changeCode(current, new, confirmation)
        } catch (e: VaultException) {
            if (e.error == VaultError.Wiped) erasedByWrongCodes()
            throw e
        }
        refreshLockScreen()
    }

    /** The vault crypto-shredded itself (10th wrong code, §5.2): the rest goes too, and onboarding starts over. */
    private suspend fun erasedByWrongCodes() {
        clearUnlockedState()
        eraseLocalData()
        lockStateFlow.value = LockState.NEEDS_SETUP
        refreshLockScreen()
    }

    suspend fun setWipeAfterTenFailures(enabled: Boolean) = withContext(main) {
        requireUnlocked()
        vault.setWipeAfterTenFailures(enabled)
        wipeAfterTenFailuresFlow.value = enabled
    }

    /**
     * Checks the current code while unlocked (7.1 code change, first step). A counted attempt with
     * the lock's throttle; the derived key is wiped at once. If the optional wipe fires, everything
     * is erased.
     */
    suspend fun verifyCode(code: String) = withContext(main) {
        if (lockStateFlow.value != LockState.UNLOCKED) throw UnlockException(UnlockError.Busy)
        try {
            vault.unlock(code).wipe()
            refreshLockScreen()
        } catch (e: UnlockException) {
            if (e.error == UnlockError.Vault(VaultError.Wiped)) eraseEverything()
            refreshLockScreen()
            throw e
        }
    }

    private fun requireUnlocked() {
        if (lockStateFlow.value != LockState.UNLOCKED || session == null) runtimeFailure(RuntimeError.Locked)
    }

    private suspend fun engine(): MessagingEngine = withContext(main) {
        val current = session
        if (lockStateFlow.value != LockState.UNLOCKED || current == null) runtimeFailure(RuntimeError.Locked)
        current.engine
    }

    // MARK: Refresh

    private fun engineChanged(change: EngineChange) {
        val current = session
        if (current != null && current.liveLocationOnly && lockStateFlow.value != LockState.UNLOCKED) {
            // Sharing may just have ended (stopped or expired).
            scope.launch { endLiveLocationOnlySessionIfIdle() }
        }
        if (lockStateFlow.value == LockState.UNLOCKED) {
            val peer = when (change) {
                is EngineChange.Messages -> change.peer
                is EngineChange.Wiped -> change.peer
                else -> null
            }
            if (peer != null) {
                val revisions = messageRevisionsFlow.value
                messageRevisionsFlow.value = revisions + (peer to (revisions[peer] ?: 0) + 1)
            }
        }
        when (change) {
            EngineChange.Conversations -> pendingRefresh.chats = true
            is EngineChange.Messages -> if (change.peer == openPeerFlow.value) pendingRefresh.messages = true
            EngineChange.Presence -> pendingRefresh.presence = true
            EngineChange.Typing -> pendingRefresh.typing = true
            is EngineChange.NewMessage -> {
                pendingRefresh.chats = true
                if (change.peer != openPeerFlow.value) pendingRefresh.bannerPeer = change.peer
            }
            is EngineChange.Wiped -> {
                pendingRefresh.chats = true
                if (change.peer == openPeerFlow.value) pendingRefresh.messages = true
            }
            is EngineChange.SafetyNumberChanged -> safetyNumberChangesFlow.value = safetyNumberChangesFlow.value + change.peer
            is EngineChange.DownloadProgress -> {
                // The bubble's ring only; the messages themselves did not change.
                if (change.peer == openPeerFlow.value) {
                    val progress = downloadProgressFlow.value
                    downloadProgressFlow.value = progress + (change.messageId to maxOf(progress[change.messageId] ?: 0.0, change.fraction))
                }
                return
            }
        }
        scheduleRefresh()
    }

    private fun connectionChanged(status: ConnectionStatus) {
        endGraceIfOverdue()
        val current = session
        if (lockStateFlow.value != LockState.UNLOCKED && current?.inGrace != true) return
        connectionFlow.value = status
        // Every connection attempt settles a pending rotation first (§5.5 step 3.4): once it has, the notice goes.
        if (tokenRotationPendingFlow.value && current != null) scope.launch { refreshTokenRotationState(current) }
    }

    // MARK: Token rotation after a restore (§5.5 step 3.4)

    /** "Şimdi kontrol et": asks the relay now which token the account has; once known, the connection is tried at once. */
    suspend fun checkPendingTokenRotation(): TokenRotationCheck = withContext(main) {
        val current = session
        if (lockStateFlow.value != LockState.UNLOCKED || current == null) return@withContext TokenRotationCheck.UNANSWERED
        val outcome = try {
            when (current.backend.finishPendingTokenRotation()) {
                TokenRotationResult.READY -> TokenRotationCheck.SETTLED
                TokenRotationResult.BACKUP_NO_LONGER_VALID -> TokenRotationCheck.REFUSED
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RuntimeLog.relay.info("token rotation still unanswered", e.logLabel)
            TokenRotationCheck.UNANSWERED
        }
        if (!isCurrent(current)) return@withContext outcome
        refreshTokenRotationState(current)
        if (outcome != TokenRotationCheck.UNANSWERED && phase == AppPhase.ACTIVE && isCurrent(current)) current.supervisor.start()
        outcome
    }

    private suspend fun refreshTokenRotationState(current: Session) {
        val pending = current.backend.hasPendingTokenRotation()
        if (!isCurrent(current)) return
        tokenRotationPendingFlow.value = pending
    }

    /** Coalesces bursts (a backlog of hundreds of messages) into one refresh. */
    private fun scheduleRefresh() {
        if (refreshJob != null) return
        refreshJob = scope.launch {
            delay(REFRESH_COALESCING)
            performRefresh()
        }
    }

    /** Every suspension can let the app lock; decrypted state is assigned and messages marked displayed only while the same session is still unlocked. */
    private suspend fun performRefresh() {
        refreshJob = null
        val current = session
        if (lockStateFlow.value != LockState.UNLOCKED || current == null) return
        val engine = current.engine
        val work = pendingRefresh
        pendingRefresh = PendingRefresh()
        val now = environment.clock.now()
        if (work.chats || work.all) {
            val summaries = try {
                engine.chatSummaries()
            } catch (_: Exception) {
                null
            }
            val blocked = try {
                engine.blockedContacts()
            } catch (_: Exception) {
                null
            }
            val currentSettings = engine.currentSettings()
            val lastBackup = engine.lastBackupAt()
            if (!isCurrent(current)) return
            if (summaries != null) {
                chatsFlow.value = summaries.filter { !it.isRequest }
                requestsFlow.value = summaries.filter { it.isRequest }
            }
            if (blocked != null) blockedContactsFlow.value = blocked
            settingsFlow.value = currentSettings
            lastBackupAtFlow.value = lastBackup
        }
        val peer = openPeerFlow.value
        if ((work.messages || work.all) && peer != null) {
            val messages = try {
                engine.messages(peer)
            } catch (_: Exception) {
                emptyList()
            }
            if (!isCurrent(current) || openPeerFlow.value != peer) return
            openMessagesFlow.value = messages
            pruneDownloadProgress()
            try {
                engine.markDisplayed(peer)
            } catch (_: Exception) {
            }
        }
        if (work.presence || work.all) {
            val status = engine.presenceStatus(now)
            if (!isCurrent(current)) return
            presenceFlow.value = status
        }
        if (work.typing || work.all) {
            val contacts = engine.typingContacts(now)
            if (!isCurrent(current)) return
            typingFlow.value = contacts
        }
        val bannerPeer = work.bannerPeer
        if (bannerPeer != null && bannerPeer != openPeerFlow.value && isCurrent(current)) {
            val title = chatsFlow.value.firstOrNull { it.id == bannerPeer }?.contact?.displayName
                ?: requestsFlow.value.firstOrNull { it.id == bannerPeer }?.contact?.displayName
            bannerFlow.value = MessageBanner(UUID.randomUUID(), bannerPeer, title.orEmpty())
        }
    }

    /** Presence labels ("N min ago") and typing indicators age; recompute them. */
    private fun startTicking() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (true) {
                delay(TICK)
                pendingRefresh.presence = true
                pendingRefresh.typing = true
                performRefresh()
                endLiveLocationOnlySessionIfIdle()
            }
        }
    }

    fun dismissBanner() {
        bannerFlow.value = null
    }

    /** Still unlocked, with the same session as before a suspension. */
    private fun isCurrent(checked: Session): Boolean = lockStateFlow.value == LockState.UNLOCKED && session === checked

    // MARK: Screenshots and screen recording

    private fun screenshotTaken() {
        val engine = session?.engine ?: return
        val peer = openPeerFlow.value ?: return
        if (lockStateFlow.value != LockState.UNLOCKED) return
        scope.launch { quietly { engine.screenCaptured(peer, recording = false) } }
    }

    private fun captureChanged(captured: Boolean) {
        screenCapturedFlow.value = captured
        if (!captured) {
            recordingNotified.clear()
            return
        }
        notifyRecordingIfNeeded()
    }

    private fun notifyRecordingIfNeeded() {
        if (!screenCapturedFlow.value || lockStateFlow.value != LockState.UNLOCKED) return
        val peer = openPeerFlow.value ?: return
        val engine = session?.engine ?: return
        if (!recordingNotified.add(peer)) return
        scope.launch { quietly { engine.screenCaptured(peer, recording = true) } }
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    // MARK: Background wipes

    /** Schedules the wake for the next overdue ghost wipe (§5.3), when an alarm scheduler is set. */
    private fun scheduleBackgroundWipes() {
        val scheduler = environment.wipeScheduler ?: return
        scope.launch(io) {
            try {
                scheduler.schedule(nextBackgroundWipeDate(directories))
            } catch (_: Exception) {
            }
        }
    }

    // MARK: Conversations

    /**
     * The conversation is on screen: loads its messages, starts disappearing timers of what is now
     * displayed, sends read receipts (if enabled), and checks the contact's username claim once
     * (§3.3). If the app locks meanwhile, nothing is shown and nothing is marked displayed.
     */
    suspend fun openConversation(peer: AccountId) = withContext(main) {
        val current = session
        if (lockStateFlow.value != LockState.UNLOCKED || current == null) return@withContext
        val engine = current.engine
        openPeerFlow.value = peer
        if (bannerFlow.value?.peer == peer) bannerFlow.value = null
        val messages = try {
            engine.messages(peer)
        } catch (_: Exception) {
            emptyList()
        }
        if (!isCurrent(current) || openPeerFlow.value != peer) return@withContext
        openMessagesFlow.value = messages
        pruneDownloadProgress()
        quietly { engine.markDisplayed(peer) }
        notifyRecordingIfNeeded()
        scope.launch { quietly { engine.verifyUsernameIfNeeded(peer) } }
        val trust = try {
            engine.identityTrust(peer)
        } catch (_: Exception) {
            null
        }
        if (!isCurrent(current)) return@withContext
        safetyNumberChangesFlow.value = if (trust is ContactIdentity.Changed) {
            safetyNumberChangesFlow.value + peer
        } else {
            safetyNumberChangesFlow.value - peer
        }
    }

    fun closeConversation() {
        openPeerFlow.value = null
        openMessagesFlow.value = emptyList()
        downloadProgressFlow.value = emptyMap()
    }

    /** Progress is kept only for attachments that are still transferring. */
    private fun pruneDownloadProgress() {
        if (downloadProgressFlow.value.isEmpty()) return
        val transferring = openMessagesFlow.value.filter { item ->
            val body = item.body
            body is MessageBody.Attachment && body.attachment.availability == com.syconx.us2us.runtime.engine.AttachmentItem.Availability.TRANSFERRING
        }.map { it.messageId }.toSet()
        downloadProgressFlow.value = downloadProgressFlow.value.filterKeys { it in transferring }
    }

    /** Opens a conversation with [username] (exact match only, §4.4). */
    suspend fun startConversation(username: String): AccountId = engine().startConversation(normalizedUsername(username))

    suspend fun contact(peer: AccountId): ContactInfo? = try {
        session?.engine?.contactInfo(peer)
    } catch (_: Exception) {
        null
    }

    suspend fun conversation(peer: AccountId): ConversationInfo? = try {
        session?.engine?.conversationInfo(peer)
    } catch (_: Exception) {
        null
    }

    // MARK: Sending

    suspend fun sendText(text: String, peer: AccountId, quote: QuoteInfo? = null): MessageId = engine().send(OutgoingContent.Text(text), peer, quote)

    /** Compresses and strips an image (≤ 2048 px, JPEG, no metadata) for sending. */
    suspend fun prepareImage(input: MediaInput, caption: String? = null): PreparedMedia {
        withContext(main) { requireUnlocked() }
        return withContext(Dispatchers.Default) { MediaPreparation.prepareImage(input, directories, caption) }
    }

    /** Re-encodes a video at ≤ 720p without location or other metadata. */
    suspend fun prepareVideo(context: Context, input: MediaInput, caption: String? = null): PreparedMedia {
        withContext(main) { requireUnlocked() }
        return VideoPreparation.prepareVideo(context, input, directories, caption)
    }

    /** A recorded voice note file (AAC in M4A, up to 100 waveform samples), moved into staging. */
    suspend fun prepareVoiceNote(file: File, durationMs: Int, waveform: ByteArray): PreparedMedia {
        withContext(main) { requireUnlocked() }
        return withContext(io) { MediaPreparation.prepareVoice(file, directories, durationMs, waveform) }
    }

    /** A voice note recorded by the app and held in memory, written to staging for [sendMedia]. */
    suspend fun prepareVoiceNote(audio: ByteArray, durationMs: Int, waveform: ByteArray): PreparedMedia {
        withContext(main) { requireUnlocked() }
        return withContext(io) { MediaPreparation.prepareVoice(audio, directories, durationMs, waveform) }
    }

    /** A document as it is — except photos and videos, which lose their metadata like any other photo or video (§7). */
    suspend fun prepareFile(context: Context, input: MediaInput, fileName: String, contentType: String): PreparedMedia {
        withContext(main) { requireUnlocked() }
        if (MediaPreparation.isMovie(contentType, fileName)) return VideoPreparation.prepareVideoFile(context, input, directories, fileName)
        return withContext(Dispatchers.Default) { MediaPreparation.prepareFile(input, directories, fileName, contentType) }
    }

    /**
     * Sends prepared media; [viewOnce] for photos and videos that can be opened once (sent without
     * thumbnail and waveform; a caption is refused). [messageId]: the id the app gave the bubble it
     * showed while the media was being prepared.
     */
    suspend fun sendMedia(media: PreparedMedia, peer: AccountId, viewOnce: Boolean = false, quote: QuoteInfo? = null, messageId: MessageId? = null): MessageId =
        engine().send(OutgoingContent.Media(media), peer, quote, viewOnce, messageId)

    suspend fun sendLocation(location: LocationInfo, peer: AccountId): MessageId = engine().send(OutgoingContent.Location(location), peer)

    /** Sends a built-in sticker (§8.8); [lang] is the language its words were shown in, empty for a wordless sticker. */
    suspend fun sendSticker(packId: String, stickerId: Int, emoji: String, lang: String = "", quote: QuoteInfo? = null, peer: AccountId): MessageId =
        engine().send(OutgoingContent.Sticker(packId, stickerId, emoji, lang), peer, quote)

    suspend fun recentStickers(): List<String> = engine().recentStickers()

    suspend fun setRecentStickers(ids: List<String>) = engine().setRecentStickers(ids)

    suspend fun noteRecentSticker(id: String) = engine().noteRecentSticker(id)

    /** Starts live location (§8.3); returns the session id for updates. */
    suspend fun startLiveLocation(peer: AccountId, latitude: Double, longitude: Double, accuracy: Float, duration: Duration): ByteString =
        engine().startLiveLocation(peer, latitude, longitude, accuracy, duration)

    /** Works while locked too, as long as sharing is active (§8.3). */
    suspend fun updateLiveLocation(
        sessionId: ByteString,
        peer: AccountId,
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        heading: Float = -1f,
        speed: Float = -1f,
    ) {
        val engine = withContext(main) {
            endGraceIfOverdue()
            session?.engine
        } ?: runtimeFailure(RuntimeError.Locked)
        engine.updateLiveLocation(sessionId, peer, latitude, longitude, accuracy, heading, speed)
    }

    /** Works while locked too. When the last sharing ends while the app is in the background, the store closes and the DEK is zeroed. */
    suspend fun stopLiveLocation(sessionId: ByteString, peer: AccountId) {
        val engine = withContext(main) { session?.engine } ?: runtimeFailure(RuntimeError.Locked)
        try {
            engine.stopLiveLocation(sessionId, peer)
        } finally {
            // Also when its stop could not be stored: the share has ended either way.
            withContext(main + NonCancellable) { endLiveLocationOnlySessionIfIdle() }
        }
    }

    suspend fun react(message: MessageId, peer: AccountId, emoji: String?) = engine().react(message, peer, emoji)

    suspend fun deleteForEveryone(message: MessageId, peer: AccountId) = engine().deleteForEveryone(message, peer)

    suspend fun deleteForMe(message: MessageId, peer: AccountId) = engine().deleteForMe(message, peer)

    /** "Tekrar gönder" for a failed or not delivered message. */
    suspend fun resend(message: MessageId, peer: AccountId): MessageId = engine().resend(message, peer)

    suspend fun setTyping(started: Boolean, peer: AccountId) {
        val engine = try {
            engine()
        } catch (_: RuntimeException) {
            return
        }
        engine.setTyping(started, peer)
    }

    // MARK: Media

    /** A decrypted copy of an attachment for players and the share sheet (staging), deleted when its message disappears and when the app locks. */
    suspend fun mediaFile(message: MessageId, peer: AccountId): File = engine().exportMedia(message, peer)

    /** The decrypted bytes of an attachment, in memory only. Not for view-once items. */
    suspend fun mediaData(message: MessageId, peer: AccountId): ByteArray = engine().mediaData(message, peer)

    suspend fun download(message: MessageId, peer: AccountId) = engine().download(message, peer)

    /** Opens a received view-once item exactly once, for a player that needs a file; call [closeViewOnce] when the viewer closes. */
    suspend fun openViewOnce(message: MessageId, peer: AccountId): File = engine().openViewOnce(message, peer)

    /** [openViewOnce] into memory only (photos): no file exists. */
    suspend fun openViewOnceData(message: MessageId, peer: AccountId): ByteArray = engine().openViewOnceData(message, peer)

    suspend fun closeViewOnce(message: MessageId, peer: AccountId, file: File?) = engine().closeViewOnce(message, peer, file)

    /** A received voice note was played for the first time (§8.4). */
    suspend fun markVoiceNotePlayed(message: MessageId, peer: AccountId) = engine().markVoiceNotePlayed(message, peer)

    /** A photo, video or file was saved outside the app: recorded once, and SAVED goes to the sender (§8.4). */
    suspend fun markMediaSaved(message: MessageId, peer: AccountId) = engine().markMediaSaved(message, peer)

    /** The conversation's photos and videos or files, newest first, [limit] at a time. */
    suspend fun mediaMessages(peer: AccountId, kind: MediaKind, before: MessageId?, limit: Int): List<MessageItem> =
        engine().mediaMessages(peer, kind, before, limit)

    suspend fun messageItem(message: MessageId, peer: AccountId): MessageItem? = engine().messageItem(message, peer)

    // MARK: Requests and blocking (§6.4)

    suspend fun acceptRequest(peer: AccountId) = engine().acceptRequest(peer)

    /** "Sil": deletes the request locally; the requester is not told. */
    suspend fun declineRequest(peer: AccountId) = engine().declineRequest(peer)

    suspend fun block(peer: AccountId) = engine().block(peer)

    suspend fun unblock(peer: AccountId) = engine().unblock(peer)

    suspend fun deleteConversation(peer: AccountId, forBoth: Boolean) {
        val engine = engine()
        if (forBoth) engine.deleteConversationForBoth(peer) else engine.deleteConversationLocally(peer)
        withContext(main) { if (openPeerFlow.value == peer) closeConversation() }
    }

    // MARK: Ghost mode and disappearing messages (§8.1, §8.2)

    suspend fun setConversationSettings(peer: AccountId, ghost: GhostSettings?, defaultExpireTimerSeconds: Long) =
        engine().updateConversationSettings(peer, ghost, defaultExpireTimerSeconds)

    // MARK: Safety numbers (§5)

    suspend fun safetyNumber(peer: AccountId): SafetyNumber? {
        val current = withContext(main) {
            requireUnlocked()
            session
        } ?: runtimeFailure(RuntimeError.Locked)
        return withContext(io) { current.backend.safetyNumber(peer) }
    }

    /** 6.2: compares a scanned safety-number QR payload and, on a match, marks the contact verified. */
    suspend fun verifySafetyNumber(peer: AccountId, scanned: ByteArray): Boolean =
        engine().verifySafetyNumber(peer, scanned).also { if (it) withContext(main) { safetyNumberChangesFlow.value -= peer } }

    /** 6.1: verifies only if the safety number is still [comparedNumber] (the `scannable` of the number compared). */
    suspend fun markVerified(peer: AccountId, comparedNumber: ByteArray): Boolean =
        engine().markVerified(peer, comparedNumber).also { if (it) withContext(main) { safetyNumberChangesFlow.value -= peer } }

    /** "QR kodla ekle": pins [identityKey] (the key the directory serves for [peer], matching the scanned contact code) as verified. */
    suspend fun verifyContactCode(peer: AccountId, identityKey: ByteArray): Boolean =
        engine().verifyContactCode(peer, identityKey).also { if (it) withContext(main) { safetyNumberChangesFlow.value -= peer } }

    /** "Doğrulamayı kaldır". */
    suspend fun clearVerification(peer: AccountId) = engine().clearVerification(peer)

    suspend fun identityVerification(peer: AccountId): IdentityVerification? = engine().identityVerification(peer)

    suspend fun identityTrust(peer: AccountId): ContactIdentity = engine().identityTrust(peer)

    /** Accepts a changed safety number without verifying it — only for the key whose number the user was shown. */
    suspend fun acceptSafetyNumberChange(peer: AccountId, shownNumber: ByteArray): Boolean =
        engine().acceptSafetyNumberChange(peer, shownNumber).also { if (it) withContext(main) { safetyNumberChangesFlow.value -= peer } }

    // MARK: Settings

    suspend fun setDisplayName(name: String) {
        val trimmed = name.utf8Prefix(MAX_DISPLAY_NAME_BYTES)
        updateSettings { it.copy(displayName = trimmed) }
    }

    /** "Show when I'm active" (§8.6). Reciprocal: off also hides others. */
    suspend fun setPresenceSharing(enabled: Boolean) = updateSettings { it.copy(presenceSharing = enabled) }

    suspend fun setReadReceipts(enabled: Boolean) = updateSettings { it.copy(readReceipts = enabled) }

    suspend fun setTypingIndicators(enabled: Boolean) = updateSettings { it.copy(typingIndicators = enabled) }

    suspend fun setAutoDownloadMedia(enabled: Boolean) = updateSettings { it.copy(autoDownloadMedia = enabled) }

    suspend fun setDefaults(ghost: GhostSettings?, expireTimerSeconds: Long) =
        updateSettings { it.copy(defaultGhost = ghost, defaultExpireTimerSeconds = expireTimerSeconds) }

    private suspend fun updateSettings(change: (UserSettings) -> UserSettings) = withContext(main) {
        val current = session
        if (lockStateFlow.value != LockState.UNLOCKED || current == null) runtimeFailure(RuntimeError.Locked)
        val engine = current.engine
        engine.updateUserSettings(change)
        val settings = engine.currentSettings()
        val status = engine.presenceStatus(environment.clock.now())
        if (!isCurrent(current)) return@withContext
        settingsFlow.value = settings
        presenceFlow.value = status
    }

    /** Changes the username (§3.3); contacts get a new profile. */
    suspend fun changeUsername(raw: String) = withContext(main) {
        val username = normalizedUsername(raw)
        val current = session
        if (lockStateFlow.value != LockState.UNLOCKED || current == null) runtimeFailure(RuntimeError.Locked)
        current.engine.changeUsername(username)
        if (isCurrent(current)) ownUsernameFlow.value = username.value
    }

    // MARK: Backup (§5.5)

    /**
     * Exports the account backup, encrypted with the separate backup password (≥ 10 characters,
     * entered twice), to a staging file for the share sheet. It holds the identity and contacts,
     * never messages; the staging copy is deleted when the app locks.
     */
    suspend fun exportBackup(password: String, confirmation: String): File = withContext(main) {
        BackupPasswordPolicy.requireValid(password)
        if (password != confirmation) runtimeFailure(RuntimeError.BackupPasswordMismatch)
        val current = session
        if (current == null || lockStateFlow.value != LockState.UNLOCKED) runtimeFailure(RuntimeError.Locked)
        val data = current.engine.exportBackup(password)
        withContext(io) { AccountBackupService.writeToStaging(data, current.storage, environment.clock.now()) }
    }

    /**
     * The exported file was saved (the share sheet completed): records [at] in the vault's
     * encrypted runtime state. Also allowed while the locked app's store is still open (the grace
     * period): a share often finishes after the app left the foreground.
     */
    suspend fun recordBackupSaved(at: Instant? = null) = withContext(main) {
        val current = session
        if (current == null || !(lockStateFlow.value == LockState.UNLOCKED || current.inGrace || current.liveLocationOnly)) {
            runtimeFailure(RuntimeError.Locked)
        }
        val saved = at ?: environment.clock.now()
        current.engine.recordBackupSaved(saved)
        if (isCurrent(current)) lastBackupAtFlow.value = saved
    }

    // MARK: Clipboard

    /** Copies text with the clipboard rules (local only, expiring). Text of disappearing and view-once messages cannot be copied. */
    fun copy(message: MessageItem): Boolean {
        val body = message.body
        if (!message.isCopyable || body !is MessageBody.Text) return false
        return pasteboard.copy(body.text, SecurePasteboard.Sensitivity.ORDINARY)
    }

    /** Copies a safety number or similar value (expires after a minute). */
    fun copySensitive(text: String): Boolean = pasteboard.copy(text, SecurePasteboard.Sensitivity.SENSITIVE)

    // MARK: Profile pictures (§8.9, §8.10)

    /**
     * Sets the own picture and "Show in search", in one step: contacts get a new picture end to
     * end (not for the setting alone), and the avatar record for search is published, replaced or
     * deleted. A relay that cannot be reached never fails this: [ownAvatar]'s `searchRecord` says
     * whether the record is in line.
     */
    suspend fun setAvatar(selection: AvatarSelection, showInSearch: Boolean) = withContext(main) {
        val current = session
        if (lockStateFlow.value != LockState.UNLOCKED || current == null) runtimeFailure(RuntimeError.Locked)
        val picture: ProfilePicture? = when (selection) {
            AvatarSelection.Initials -> null
            is AvatarSelection.Preset -> ProfilePicture.preset(selection.id)
            is AvatarSelection.Photo -> withContext(Dispatchers.Default) { AvatarPhotoProcessor.prepare(selection.data, selection.crop) }
        }
        val own = current.engine.setOwnAvatar(picture, showInSearch)
        if (isCurrent(current)) ownAvatarFlow.value = own
    }

    /** Changes the own picture and keeps "Show in search" as the vault has it. */
    suspend fun setAvatar(selection: AvatarSelection) = withContext(main) {
        val current = session
        if (lockStateFlow.value != LockState.UNLOCKED || current == null) runtimeFailure(RuntimeError.Locked)
        val own = current.engine.ownAvatar()
        if (!isCurrent(current)) runtimeFailure(RuntimeError.Locked)
        setAvatar(selection, own.showInSearch)
    }

    /** Turns "Show in search" on or off and keeps the picture. Contacts are not told. */
    suspend fun setAvatarShownInSearch(shown: Boolean) = withContext(main) {
        val current = session
        if (lockStateFlow.value != LockState.UNLOCKED || current == null) runtimeFailure(RuntimeError.Locked)
        val own = current.engine.ownAvatar()
        val updated = current.engine.setOwnAvatar(own.picture, shown)
        if (isCurrent(current)) ownAvatarFlow.value = updated
    }

    /** The own picture and setting as stored in the vault (also refreshes [ownAvatar]); `null` while locked. */
    suspend fun currentAvatar(): OwnProfilePicture? = withContext(main) {
        val current = session
        if (lockStateFlow.value != LockState.UNLOCKED || current == null) return@withContext null
        val own = current.engine.ownAvatar()
        if (isCurrent(current)) ownAvatarFlow.value = own
        own
    }

    /** Makes a search-record write that is still owed now; also refreshes [ownAvatar]. */
    suspend fun retryAvatarPublishing(): AvatarRecordStatus? = withContext(main) {
        val current = session
        if (lockStateFlow.value != LockState.UNLOCKED || current == null) return@withContext null
        val status = current.engine.syncAvatarRecord()
        val own = current.engine.ownAvatar()
        if (isCurrent(current)) ownAvatarFlow.value = own
        status
    }

    /** The picture to show next to a search result (§8.10), after [lookupAccount] found [accountId] for exactly [username]. Never throws. */
    suspend fun avatar(username: String, accountId: AccountId): AvatarLookup = withContext(main) {
        val parsed = Username.parse(username)
        val current = session
        if (parsed == null || lockStateFlow.value != LockState.UNLOCKED || current == null) return@withContext AvatarLookup.INITIALS
        val lookup = current.engine.avatar(parsed, accountId)
        if (isCurrent(current)) lookup else AvatarLookup.INITIALS
    }

    /** The picture of a conversation's contact (§8.9); safe for list rows. Never throws. */
    suspend fun avatar(peer: AccountId): AvatarLookup = withContext(main) {
        val current = session
        if (lockStateFlow.value != LockState.UNLOCKED || current == null) return@withContext AvatarLookup.INITIALS
        val lookup = current.engine.avatar(peer)
        if (isCurrent(current)) lookup else AvatarLookup.INITIALS
    }

    /** An incoming message request's picture after the user tapped "Show picture" ([userConfirmed]). Never throws. */
    suspend fun requestAvatar(peer: AccountId, userConfirmed: Boolean): AvatarLookup = withContext(main) {
        val current = session
        if (lockStateFlow.value != LockState.UNLOCKED || current == null) return@withContext AvatarLookup.INITIALS
        val lookup = current.engine.requestAvatar(peer, userConfirmed)
        if (isCurrent(current)) lookup else AvatarLookup.INITIALS
    }

    // MARK: Directory and own identity

    /** Exact-match lookup of [username] (§4.4); `null` when nobody has it. Unlike [startConversation] it creates nothing. */
    suspend fun lookupAccount(username: String): DirectoryEntry? {
        val normalized = normalizedUsername(username)
        val current = withContext(main) {
            requireUnlocked()
            session
        } ?: runtimeFailure(RuntimeError.Locked)
        return current.backend.findAccount(normalized)
    }

    /** The own account id while unlocked (to tell own reactions from the contact's). */
    val ownAccountId: AccountId?
        get() = if (lockStateFlow.value == LockState.UNLOCKED) session?.backend?.accountId else null

    /** The own identity key (serialized public key) for the contact code's fingerprint: this phone's own key, never what the relay serves. */
    suspend fun ownIdentityKey(): ByteArray {
        val current = withContext(main) {
            requireUnlocked()
            session
        } ?: runtimeFailure(RuntimeError.Locked)
        return current.backend.ownIdentityKey()
    }

    private fun normalizedUsername(raw: String): Username {
        Username.parse(raw)?.let { return it }
        val error = Username.check(raw) ?: com.syconx.us2us.core.username.UsernameError.entries.first()
        runtimeFailure(RuntimeError.InvalidUsername(error))
    }
}

/** What the user picked as their profile picture (PROTOCOL.md §8.9). */
sealed interface AvatarSelection {
    /** The initial letter of the username (the default). */
    data object Initials : AvatarSelection

    /** A built-in preset of the app's catalog, by its stable id (never 0). */
    data class Preset(val id: Int) : AvatarSelection

    /**
     * A photo as picked or taken, and the square the user cropped in it as displayed (pixels,
     * orientation applied; `null`: the centred square). It is scaled, stripped and re-encoded on the
     * phone; the original is not kept.
     */
    class Photo(val data: ByteArray, val crop: RectF? = null) : AvatarSelection
}

/** What [AppRuntime.checkPendingTokenRotation] found out (§5.5 step 3.4). */
enum class TokenRotationCheck {
    /** Nothing is pending (any more): the relay holds this phone's token. */
    SETTLED,

    /** Still no answer: every connection attempt asks again. */
    UNANSWERED,

    /** The relay refused both tokens: this phone is signed out for good. */
    REFUSED,
}

/** What the next refresh must reload. */
internal class PendingRefresh(
    var all: Boolean = false,
    var chats: Boolean = false,
    var messages: Boolean = false,
    var presence: Boolean = false,
    var typing: Boolean = false,
    var bannerPeer: AccountId? = null,
)

/** An open vault: its key, store, backend, engine and connection. */
internal class Session(
    val key: SecretKeyMaterial,
    val storage: LocalStorage,
    val backend: MessagingBackend,
    val engine: MessagingEngine,
    val supervisor: ConnectionSupervisor,
    val supervisorScope: CoroutineScope,
) {
    /** In the background grace period (UI locked, still receiving). */
    var inGrace = false

    /** Grace over, but live location is being shared: store open, socket closed. */
    var liveLocationOnly = false
    val listeners = ArrayList<Job>()

    fun stopListening() {
        for (listener in listeners) listener.cancel()
        listeners.clear()
    }
}

private const val MAX_DISPLAY_NAME_BYTES = 64
private val REFRESH_COALESCING = 50.milliseconds
private val TICK = 15.seconds
