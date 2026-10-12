package com.syconx.us2us.ui.env

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.syconx.us2us.domain.AvatarPicture
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.ThemePreference
import com.syconx.us2us.domain.harness.HarnessUiState
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.LaunchConfig
import com.syconx.us2us.domain.nav.OnboardingEntry
import com.syconx.us2us.domain.nav.Overlay
import com.syconx.us2us.domain.nav.Phase
import com.syconx.us2us.ui.l10n.AppLanguage
import com.syconx.us2us.ui.nav.U2Router

/**
 * Device-level preferences that must be readable before unlocking (the lock screen is themed and
 * localized too): theme and language, plus whether the one-time save explanation was acknowledged.
 * Not sensitive; everything else is in the vault. Snapshot-backed: screens recompose on change.
 */
@Stable
interface U2Preferences {
    /** 7.1 › Tema. */
    var theme: ThemePreference

    /** 7.1 › Dil. Setting it switches the app's language (the activity is recreated, the app's state survives). */
    var language: AppLanguage

    /**
     * The one-time explanation before the first save from a viewer (“<kişi> kaydettiğini görecek”)
     * was acknowledged with “Kaydet”. It goes with the account.
     */
    var hasAcknowledgedSaveNotice: Boolean
}

/**
 * What screens may ask of the app (iOS `AppModel` read from the environment): its root state, the
 * main stack, the service, the clock, the preferences and the transitions between the root phases.
 * Implemented by the app; [PreviewAppModel] stands in for it in previews and tests.
 */
@Stable
interface U2AppModel {
    /** Onboarding, locked or unlocked. */
    val phase: Phase

    /** What the security layer shows: nothing, the 3.4 cover or the lock. */
    val overlay: Overlay

    /** Where onboarding starts when the root swaps to it. */
    val onboardingEntry: OnboardingEntry

    /** 2.3 → 4.1: onboarding is still on screen, fading out over 4.1. */
    val isLeavingOnboarding: Boolean

    /** The optional wipe erased everything outside the lock: the lock opens on “Veriler silindi”. */
    val showsWipedOutcome: Boolean

    /**
     * Counts the times the account on this phone went (erased, wiped after wrong codes, replaced by
     * a restore): state kept for an account (settings' restore-access notice) resets when it changes.
     */
    val accountResets: Int

    /** The launch configuration (debug launch extras; [LaunchConfig.Release] in release builds). */
    val configuration: LaunchConfig

    val preferences: U2Preferences
    val messenger: MessengerService
    val clock: DesignClock

    /** The main stack (rooted at 4.1). */
    val router: U2Router<AppRoute>

    /** The lock flow after biometrics or the right code: the content is built under the lock, which then dissolves. */
    fun didUnlock()

    /** 2.3 “Kurulumu tamamla” succeeded: the root cross-fades to 4.1 (0.5 s). */
    fun didFinishOnboarding()

    /** 3.3 / 7.1 “Her şeyi sil” after the second confirmation: wipe → 1.1. */
    suspend fun eraseEverything()

    /** The account on this phone went: what the app keeps about it outside the vault goes too. */
    fun forgetAccountPreferences()

    /** 3.3 › 7.3 finished restoring inside the lock: onboarding continues at 2.2 for a new code. */
    fun continueAfterRestore()

    /** 2.3 could not finish but left a vault on this phone, locked: the lock opens it. */
    fun returnToLock()

    /** The optional wipe fired outside the lock (7.1 › Gizli kodu değiştir): the lock rises over the erased app. */
    fun dataWipedAfterWrongCodes()

    /** “Yedekten geri yükle” ([restoring]) or “Baştan başla” after the wipe: onboarding at 7.3 or 1.1. */
    fun leaveWipedLock(restoring: Boolean)
}

/** The app model of the current window. Provided at the root by `ProvideU2Environment`. */
val LocalAppModel = staticCompositionLocalOf<U2AppModel> {
    error("LocalAppModel is not provided: wrap the screen in ProvideU2Environment")
}

/** The messenger service (fixtures or live). Provided at the root by `ProvideU2Environment`. */
val LocalMessenger = staticCompositionLocalOf<MessengerService> {
    error("LocalMessenger is not provided: wrap the screen in ProvideU2Environment")
}

/**
 * The debug harness state of the launch route (`U2Route feature:…/state`) for the feature that
 * owns it; `null` in production and for every other feature. Features read it back as their own
 * `HarnessUiState` type.
 */
val LocalHarnessState = staticCompositionLocalOf<HarnessUiState?> { null }

/**
 * The own profile picture, for every place that draws it (4.1's header, my code, the live map);
 * the initial outside the unlocked app. The app root provides the service's current one.
 */
val LocalOwnAvatar = compositionLocalOf { AvatarPicture.Initials }

/** In-memory [U2Preferences] (previews, tests). */
class MemoryPreferences(
    theme: ThemePreference = ThemePreference.System,
    language: AppLanguage = AppLanguage.System,
) : U2Preferences {
    override var theme: ThemePreference by mutableStateOf(theme)
    override var language: AppLanguage by mutableStateOf(language)
    override var hasAcknowledgedSaveNotice: Boolean by mutableStateOf(false)
}

/**
 * A [U2AppModel] for previews and tests: the given service and clock, an unlocked app, and every
 * transition recorded in [calls] (and applied to the state where that is simple).
 */
class PreviewAppModel(
    override val messenger: MessengerService,
    override val clock: DesignClock = DesignClock.frozen(DesignClock.referenceDate),
    override val configuration: LaunchConfig = LaunchConfig.Release,
    override val preferences: U2Preferences = MemoryPreferences(),
    override val router: U2Router<AppRoute> = U2Router(),
    phase: Phase = Phase.Unlocked,
) : U2AppModel {
    override var phase: Phase by mutableStateOf(phase)
    override var overlay: Overlay by mutableStateOf(Overlay.None)
    override var onboardingEntry: OnboardingEntry by mutableStateOf(OnboardingEntry.Welcome)
    override var isLeavingOnboarding: Boolean by mutableStateOf(false)
    override var showsWipedOutcome: Boolean by mutableStateOf(false)
    override var accountResets: Int by mutableIntStateOf(0)

    /** The transitions asked for, in order (`didUnlock`, `leaveWipedLock(true)` …). */
    val calls: MutableList<String> = mutableListOf()

    override fun didUnlock() {
        calls += "didUnlock"
        phase = Phase.Unlocked
        overlay = Overlay.None
    }

    override fun didFinishOnboarding() {
        calls += "didFinishOnboarding"
        phase = Phase.Unlocked
    }

    override suspend fun eraseEverything() {
        calls += "eraseEverything"
        messenger.eraseEverything()
        accountResets++
        phase = Phase.Onboarding
    }

    override fun forgetAccountPreferences() {
        calls += "forgetAccountPreferences"
        preferences.hasAcknowledgedSaveNotice = false
    }

    override fun continueAfterRestore() {
        calls += "continueAfterRestore"
        onboardingEntry = OnboardingEntry.NewCodeAfterRestore
        phase = Phase.Onboarding
    }

    override fun returnToLock() {
        calls += "returnToLock"
        overlay = Overlay.Lock
        phase = Phase.Locked
    }

    override fun dataWipedAfterWrongCodes() {
        calls += "dataWipedAfterWrongCodes"
        showsWipedOutcome = true
        accountResets++
        overlay = Overlay.Lock
        phase = Phase.Locked
    }

    override fun leaveWipedLock(restoring: Boolean) {
        calls += "leaveWipedLock($restoring)"
        showsWipedOutcome = false
        onboardingEntry = if (restoring) OnboardingEntry.RestoreFromBackup else OnboardingEntry.Welcome
        phase = Phase.Onboarding
        overlay = Overlay.None
    }
}
