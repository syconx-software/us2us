package com.syconx.us2us.app

import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.MessengerScenePhase
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.ProtectedPreviewFiles
import com.syconx.us2us.domain.ThemePreference
import com.syconx.us2us.domain.VoiceRecordingFiles
import com.syconx.us2us.domain.harness.HarnessUiState
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.LaunchConfig
import com.syconx.us2us.domain.nav.OnboardingEntry
import com.syconx.us2us.domain.nav.Overlay
import com.syconx.us2us.domain.nav.Phase
import com.syconx.us2us.domain.nav.StartScreen
import com.syconx.us2us.ui.bubbles.BubbleImageCache
import com.syconx.us2us.ui.components.AvatarImageCache
import com.syconx.us2us.ui.env.ExternalActivityGuard
import com.syconx.us2us.ui.env.U2AppModel
import com.syconx.us2us.ui.l10n.AppLanguage
import com.syconx.us2us.ui.nav.U2Router
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The app's root state (flows.md › App states), the iOS `AppModel`:
 *
 * - [Phase.Onboarding]: no account on this device (1.1 → 1.3 → 2.x).
 * - [Phase.Locked]: an account exists but nothing has been unlocked since launch; the window shows
 *   only the background (no content is built).
 * - [Phase.Unlocked]: the main stack (4.1 …) is alive. When the app locks again it stays alive
 *   underneath the lock, so the stack and scroll positions survive.
 *
 * The lock (03) and the privacy cover (3.4) are the security layer above everything (sheets
 * included). The app locks on every launch and on every return from the background, with no grace
 * period; our own system activities (pickers, camera, share sheet, permissions) do not count as
 * leaving ([ExternalActivityGuard]).
 *
 * It lives in the application, so an activity recreated for a theme or language change keeps the
 * stack and does not lock. The views animate its changes: [overlayAnimates] and [phaseAnimates] say
 * whether the last change cross-fades or swaps at once, and the views report back when a fade has
 * finished ([overlayFadeFinished], [onboardingFadedOut], [mainStackRemoved], [unlockContentShown]).
 */
@Stable
class AppModel(
    override val configuration: LaunchConfig,
    override val preferences: AppPreferences,
    services: AppServices,
    messenger: MessengerService? = null,
    val guard: ExternalActivityGuard = ExternalActivityGuard(),
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : U2AppModel {
    /** Main-thread work of the app (the harness's preparation, erasing). */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val clock: DesignClock
    override val messenger: MessengerService
    override val router = U2Router<AppRoute>()

    override var phase: Phase by mutableStateOf(Phase.Onboarding)
        private set

    override var overlay: Overlay by mutableStateOf(Overlay.None)
        private set

    override var onboardingEntry: OnboardingEntry by mutableStateOf(OnboardingEntry.Welcome)
        private set

    override var isLeavingOnboarding: Boolean by mutableStateOf(false)
        private set

    override var showsWipedOutcome: Boolean by mutableStateOf(false)
        private set

    override var accountResets: Int by mutableIntStateOf(0)
        private set

    /** The blurred picture of the screen under the privacy cover (`null`: the solid cover). */
    var privacySnapshot: ImageBitmap? by mutableStateOf(null)
        private set

    /** Whether the last [overlay] change cross-fades (0.36 s); appearing is never animated. */
    var overlayAnimates: Boolean by mutableStateOf(false)
        private set

    /** Whether the last [phase] change cross-fades (0.5 s). */
    var phaseAnimates: Boolean by mutableStateOf(false)
        private set

    /** The first unlock built the content under the lock: the lock dissolves once that frame is on screen. */
    var unlockDissolvePending: Boolean by mutableStateOf(false)
        private set

    /** The debug harness state for the route's feature (`null` in production). */
    val harnessState: HarnessUiState?

    /** The harness's work on the service is done: the root may draw (always `true` without a harness). */
    var isReady: Boolean by mutableStateOf(true)
        private set

    /** The main-stack screens of the launch route (pushed at start, or after the first unlock). */
    val launchPath: List<AppRoute>

    /** Whether the service is the design's data. */
    val usesFixtures: Boolean

    /** Set when the app went to the background: the next foreground locks. */
    private var lockIsDue = false

    /** When the app went to the background with one of our own system activities in front. */
    private var guardedStopAt: Long? = null

    /** Called when the phase cross-fade to onboarding has finished. */
    private var afterRootFade: (() -> Unit)? = null

    init {
        // Decrypted preview copies never survive a launch (a crash, a kill), nor does a voice
        // recording that was cut off.
        ProtectedPreviewFiles.removeAll()
        VoiceRecordingFiles.removeAll()
        if (configuration.resetPreferences) preferences.reset()
        configuration.theme?.let(ThemePreference::fromRaw)?.let { preferences.theme = it }
        configuration.language?.let(AppLanguage::fromRaw)?.let { preferences.language = it }

        usesFixtures = messenger == null && services.usesFixtures(configuration)
        // The design's moment is for the design's data only: the live service runs on the wall clock.
        clock = when {
            configuration.frozenClock -> DesignClock.frozen(DesignClock.referenceDate)
            usesFixtures || messenger != null -> DesignClock.running(DesignClock.referenceDate)
            else -> DesignClock.Live
        }
        val english = preferences.language.resolvedLocale().language == "en"
        this.messenger = messenger
            ?: if (usesFixtures) services.fixtures(configuration, clock, english, scope) else services.live(configuration)

        // The live service decides the start: onboarding without an account, otherwise the lock,
        // at every launch.
        var start = configuration.start
        if (messenger == null && !usesFixtures) {
            val hasAccount = this.messenger.hasAccount.value
            start = if (hasAccount) StartScreen.Locked else StartScreen.Welcome
            // No account: nothing of an earlier account's acknowledgements stays.
            if (!hasAccount) preferences.hasAcknowledgedSaveNotice = false
        }

        // Debug: the route's harness says where its state starts and what it needs.
        val plan = if (messenger == null) services.harnessPlan(configuration, this.messenger) else null
        // `U2Start locked` keeps the lock in front of a route's state: it opens after the unlock.
        if (plan != null && !(start == StartScreen.Locked && plan.start == StartScreen.Chats)) start = plan.start
        harnessState = plan?.uiState
        launchPath = plan?.stack ?: configuration.routePath

        when (start) {
            StartScreen.Welcome -> phase = Phase.Onboarding
            StartScreen.Locked -> {
                phase = Phase.Locked
                overlay = Overlay.Lock
            }
            StartScreen.Chats -> {
                phase = Phase.Unlocked
                router.setPath(launchPath)
            }
        }

        plan?.prepare?.let { prepare ->
            isReady = false
            scope.launch {
                try {
                    prepare()
                } finally {
                    isReady = true
                }
            }
        }
    }

    // MARK: Scene lifecycle (driven by the activity)

    /**
     * The activity pauses (recents, a system dialog over it): cover the content *now*, before the
     * system takes its picture. Never animated. Not for our own system activities.
     */
    fun sceneWillResignActive(snapshot: () -> ImageBitmap?) {
        messenger.scenePhaseChanged(MessengerScenePhase.Inactive)
        if (guard.isActive) return
        if (phase != Phase.Unlocked || overlay != Overlay.None) return
        privacySnapshot = snapshot()
        setOverlay(Overlay.PrivacyCover, animated = false)
    }

    /** The activity stopped: the app is in the background (unless one of our own system activities is in front). */
    fun sceneDidEnterBackground() {
        // A permission dialog only pauses the app: stopped behind one, the user went home or to the recents.
        if (guard.isActive && !guard.onlyDialogs) {
            // A picker, the camera or the share sheet: not a background yet (iOS shows these inside
            // the app). Whether it was one is decided on return, by how long it took.
            guardedStopAt = elapsedRealtime()
            messenger.scenePhaseChanged(MessengerScenePhase.Inactive)
            return
        }
        enterBackground()
    }

    private fun enterBackground(coverNow: Boolean = true) {
        messenger.scenePhaseChanged(MessengerScenePhase.Background)
        // Preview copies go with the foreground (the screens close them too), and so does a voice recording.
        ProtectedPreviewFiles.removeAll()
        VoiceRecordingFiles.removeAll()
        if (!messenger.hasAccount.value || phase == Phase.Onboarding) return
        lockIsDue = true
        if (coverNow && overlay == Overlay.None) {
            // Background without passing through a pause cover: cover now.
            setOverlay(Overlay.PrivacyCover, animated = false)
        }
    }

    /** The activity starts again. */
    fun sceneWillEnterForeground() {
        guardedStopAt?.let { stoppedAt ->
            guardedStopAt = null
            // Away from the picker for too long: that was a background after all.
            if (!guard.covers(elapsedRealtime() - stoppedAt)) enterBackground(coverNow = false)
        }
        if (!lockIsDue) return
        lockIsDue = false
        // Every return from the background locks (no grace period). The cover is already up; it
        // cross-fades into the lock (0.36 s) over an opaque base instead of snapping to it. Without a
        // cover the lock is never faded in over the app: it is there at once.
        setOverlay(Overlay.Lock, animated = overlay != Overlay.None)
    }

    /** The activity resumed. */
    fun sceneDidBecomeActive() {
        guard.resumed()
        messenger.scenePhaseChanged(MessengerScenePhase.Active)
        // Back without having been in the background: dissolve the cover.
        if (overlay == Overlay.PrivacyCover && !lockIsDue) dismissOverlay()
    }

    // MARK: Transitions

    /**
     * Called by the lock flow after biometrics or the right code. The first unlock builds the
     * app's content (4.1 …) under the still opaque lock, at once, and only once that heavy frame is on
     * screen (two frames later, [unlockContentShown]) dissolves the lock (0.36 s): a true dissolve,
     * with the content complete underneath.
     */
    override fun didUnlock() {
        if (phase != Phase.Locked) {
            dismissOverlay()
            return
        }
        phaseAnimates = false
        phase = Phase.Unlocked
        if (router.isAtRoot && launchPath.isNotEmpty()) router.setPath(launchPath)
        unlockDissolvePending = true
    }

    /** The content built by [didUnlock] has been on screen for two frames: the lock dissolves. */
    fun unlockContentShown() {
        if (!unlockDissolvePending) return
        unlockDissolvePending = false
        dismissOverlay()
    }

    /** 2.3 “Kurulumu tamamla” succeeded: the root is replaced with 4.1 (cross-fade 0.5 s). */
    override fun didFinishOnboarding() {
        router.setPath(emptyList())
        onboardingEntry = OnboardingEntry.Welcome
        isLeavingOnboarding = true
        phaseAnimates = true
        phase = Phase.Unlocked
        afterRootFade = null
    }

    /** 3.3 / 7.1 “Her şeyi sil” after the second confirmation: wipe → 1.1. */
    override suspend fun eraseEverything() {
        messenger.eraseEverything()
        ProtectedPreviewFiles.removeAll()
        VoiceRecordingFiles.removeAll()
        forgetDecodedPictures()
        forgetAccountPreferences()
        lockIsDue = false
        onboardingEntry = OnboardingEntry.Welcome
        swapToOnboarding()
    }

    /**
     * The account on this phone went (erased, wiped after wrong codes, replaced by a restore): what
     * the app keeps about it outside the vault goes too; the next account is told again before its
     * first save (PROTOCOL §8.4).
     */
    override fun forgetAccountPreferences() {
        preferences.hasAcknowledgedSaveNotice = false
    }

    /**
     * 3.3 › 7.3 finished restoring inside the lock: the restored vault needs a new secret code on
     * this device (7.3 done → 2.2 ×2 → 2.3 → 4.1). The lock dissolves over onboarding at 2.2.
     */
    override fun continueAfterRestore() {
        lockIsDue = false
        onboardingEntry = OnboardingEntry.NewCodeAfterRestore
        swapToOnboarding()
    }

    /**
     * 2.3 could not finish but left a vault on this phone, locked: onboarding is over, the lock opens
     * it. The lock is in place at once (never faded in over content); the root behind it becomes the
     * locked one.
     */
    override fun returnToLock() {
        lockIsDue = false
        onboardingEntry = OnboardingEntry.Welcome
        setOverlay(Overlay.Lock, animated = false)
        phaseAnimates = false
        phase = Phase.Locked
        router.setPath(emptyList())
    }

    /**
     * The optional wipe fired outside the lock (the 10th wrong code in a row was the current code of
     * 7.1 › Gizli kodu değiştir): everything is erased, so the main stack goes at once, under the
     * lock, which says what happened.
     */
    override fun dataWipedAfterWrongCodes() {
        lockIsDue = false
        showsWipedOutcome = true
        forgetAccountPreferences()
        accountResets++
        ProtectedPreviewFiles.removeAll()
        VoiceRecordingFiles.removeAll()
        setOverlay(Overlay.Lock, animated = false)
        phaseAnimates = false
        phase = Phase.Locked
        router.setPath(emptyList())
    }

    /** “Yedekten geri yükle” or “Baştan başla” after the wipe: onboarding at 7.3 (the picker opens) or at 1.1. The lock dissolves. */
    override fun leaveWipedLock(restoring: Boolean) {
        lockIsDue = false
        showsWipedOutcome = false
        onboardingEntry = if (restoring) OnboardingEntry.RestoreFromBackup else OnboardingEntry.Welcome
        swapToOnboarding()
    }

    /**
     * The root cross-fades to onboarding first; the main stack's path is emptied only once it has
     * gone ([mainStackRemoved]).
     */
    private fun swapToOnboarding() {
        // Whatever comes next is another account (or the same one restored).
        accountResets++
        forgetAccountPreferences()
        phaseAnimates = true
        phase = Phase.Onboarding
        afterRootFade = {
            if (phase == Phase.Onboarding) router.setPath(emptyList())
        }
        dismissOverlay()
    }

    /** Onboarding has faded out over 4.1 (the end of [didFinishOnboarding]'s cross-fade). */
    fun onboardingFadedOut() {
        if (isLeavingOnboarding && phase == Phase.Unlocked) isLeavingOnboarding = false
    }

    /** The main stack has faded out and left the window (the end of the cross-fade to onboarding). */
    fun mainStackRemoved() {
        afterRootFade?.invoke()
        afterRootFade = null
    }

    private fun dismissOverlay() {
        if (overlay == Overlay.None) return
        setOverlay(Overlay.None, animated = true)
    }

    /** The security layer has faded out completely. */
    fun overlayFadeFinished() {
        if (overlay == Overlay.None) privacySnapshot = null
    }

    private fun setOverlay(value: Overlay, animated: Boolean) {
        overlayAnimates = animated
        overlay = value
        // Decoded pictures do not outlive the unlock: the lock empties the in-memory caches (iOS does the same).
        if (value == Overlay.Lock) forgetDecodedPictures()
    }

    private fun forgetDecodedPictures() {
        BubbleImageCache.clear()
        AvatarImageCache.removeAll()
    }

    /** Ends the model's work (tests). */
    fun close() {
        scope.cancel()
    }
}
