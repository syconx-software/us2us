package com.syconx.us2us.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.ThemePreference
import com.syconx.us2us.domain.harness.HarnessPlan
import com.syconx.us2us.domain.harness.HarnessStateId
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.LaunchConfig
import com.syconx.us2us.domain.nav.OnboardingEntry
import com.syconx.us2us.domain.nav.Overlay
import com.syconx.us2us.domain.nav.Phase
import com.syconx.us2us.domain.nav.StartScreen
import com.syconx.us2us.fixtures.FixturesMessengerService
import com.syconx.us2us.ui.env.ExternalActivityGuard
import com.syconx.us2us.ui.l10n.AppLanguage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

/**
 * The root state machine (iOS `NavigationTests.testAppModelLockCycle` and more): locking on every
 * return from the background and never on our own pickers, the privacy cover, unlock, onboarding,
 * erasing and the wiped outcome, preferences and the debug harness plan.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 1_000_000L
    private val models = mutableListOf<AppModel>()

    private fun preferences(name: String = "test") =
        AppPreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE), appliesLanguage = false)

    private class Services(private val plan: HarnessPlan? = null) : AppServices {
        var fixturesMade = 0

        override fun usesFixtures(configuration: LaunchConfig) = true

        override fun fixtures(configuration: LaunchConfig, clock: DesignClock, english: Boolean, scope: CoroutineScope): MessengerService {
            fixturesMade++
            return FixturesMessengerService(hasAccount = configuration.start != StartScreen.Welcome, clock = clock, latency = Duration.ZERO, scope = scope)
        }

        override fun live(configuration: LaunchConfig): MessengerService = error("no live service in tests")

        override fun harnessPlan(configuration: LaunchConfig, messenger: MessengerService): HarnessPlan? = plan
    }

    private fun model(
        start: StartScreen = StartScreen.Chats,
        config: LaunchConfig = LaunchConfig(start = start, usesFixtures = true, frozenClock = true),
        services: AppServices = Services(),
        guard: ExternalActivityGuard = ExternalActivityGuard(elapsedRealtime = { now }),
        prefs: AppPreferences = preferences(),
    ): AppModel = AppModel(config, prefs, services, guard = guard, elapsedRealtime = { now }).also { models += it }

    @After
    fun tearDown() {
        models.forEach(AppModel::close)
    }

    @Test
    fun theLockCycle() {
        val model = model()
        assertEquals(Phase.Unlocked, model.phase)
        assertEquals(Overlay.None, model.overlay)
        // App switcher: the cover appears at once, without a lock.
        model.sceneWillResignActive { null }
        assertEquals(Overlay.PrivacyCover, model.overlay)
        assertFalse(model.overlayAnimates)
        // Background → foreground: locked; the cover cross-fades into the lock.
        model.sceneDidEnterBackground()
        model.sceneWillEnterForeground()
        assertEquals(Overlay.Lock, model.overlay)
        assertTrue(model.overlayAnimates)
        assertEquals("the stack stays alive under the lock", Phase.Unlocked, model.phase)
        model.sceneDidBecomeActive()
        assertEquals(Overlay.Lock, model.overlay)
    }

    @Test
    fun aPauseWithoutBackgroundDissolvesTheCover() {
        val model = model()
        model.sceneWillResignActive { null }
        model.sceneDidBecomeActive()
        assertEquals(Overlay.None, model.overlay)
        assertTrue(model.overlayAnimates)
    }

    @Test
    fun aBackgroundWithoutAPauseCoversAtOnceAndLocksWithoutAFadeIn() {
        val model = model()
        model.sceneDidEnterBackground()
        assertEquals(Overlay.PrivacyCover, model.overlay)
        model.sceneWillEnterForeground()
        assertEquals(Overlay.Lock, model.overlay)
    }

    @Test
    fun ourOwnPickersDoNotLock() {
        val guard = ExternalActivityGuard(maxAway = 5.minutes, elapsedRealtime = { now })
        val model = model(guard = guard)
        guard.begin()
        model.sceneWillResignActive { null }
        assertEquals("no cover for our own picker", Overlay.None, model.overlay)
        model.sceneDidEnterBackground()
        now += 60_000
        model.sceneWillEnterForeground()
        guard.end()
        model.sceneDidBecomeActive()
        assertEquals(Overlay.None, model.overlay)
    }

    @Test
    fun stayingAwayFromAPickerTooLongLocks() {
        val guard = ExternalActivityGuard(maxAway = 5.minutes, elapsedRealtime = { now })
        val model = model(guard = guard)
        guard.begin()
        model.sceneWillResignActive { null }
        model.sceneDidEnterBackground()
        now += 6 * 60_000
        model.sceneWillEnterForeground()
        assertEquals(Overlay.Lock, model.overlay)
        assertFalse("no cover was up: the lock is there at once", model.overlayAnimates)
    }

    /** A permission dialog only pauses the app: no cover for it, but a stop behind it is the user leaving (home, recents). */
    @Test
    fun aPermissionDialogCoversThePauseButNotLeaving() {
        val guard = ExternalActivityGuard(maxAway = 5.minutes, elapsedRealtime = { now })
        val model = model(guard = guard)
        guard.beginDialog()
        model.sceneWillResignActive { null }
        assertEquals("no cover for our own dialog", Overlay.None, model.overlay)
        model.sceneDidEnterBackground()
        assertEquals("covered at once: the app is in the background", Overlay.PrivacyCover, model.overlay)
        now += 10_000
        model.sceneWillEnterForeground()
        assertEquals(Overlay.Lock, model.overlay)
        guard.endDialog()
        assertFalse(guard.isActive)

        // A picker in flight as well: it decides on return, as before.
        guard.begin()
        guard.beginDialog()
        assertFalse(guard.onlyDialogs)
    }

    @Test
    fun aShareSheetGuardEndsAtTheNextResume() {
        val guard = ExternalActivityGuard(elapsedRealtime = { now })
        val model = model(guard = guard)
        guard.launching { }
        assertTrue(guard.isActive)
        model.sceneWillResignActive { null }
        model.sceneDidEnterBackground()
        model.sceneWillEnterForeground()
        model.sceneDidBecomeActive()
        assertFalse(guard.isActive)
        assertEquals(Overlay.None, model.overlay)
        // The next background is a real one.
        model.sceneWillResignActive { null }
        model.sceneDidEnterBackground()
        model.sceneWillEnterForeground()
        assertEquals(Overlay.Lock, model.overlay)
    }

    @Test
    fun onboardingNeverLocks() {
        val model = model(start = StartScreen.Welcome)
        assertEquals(Phase.Onboarding, model.phase)
        model.sceneWillResignActive { null }
        model.sceneDidEnterBackground()
        model.sceneWillEnterForeground()
        assertEquals(Overlay.None, model.overlay)
    }

    @Test
    fun theFirstUnlockBuildsTheContentThenDissolvesTheLock() {
        val config = LaunchConfig(
            start = StartScreen.Locked,
            usesFixtures = true,
            frozenClock = true,
            launchRoute = com.syconx.us2us.domain.nav.LaunchRoute.parse("conversation:deniz"),
        )
        val model = model(config = config)
        assertEquals(Phase.Locked, model.phase)
        assertEquals(Overlay.Lock, model.overlay)
        assertTrue(model.router.isAtRoot)
        model.didUnlock()
        assertEquals(Phase.Unlocked, model.phase)
        assertEquals("the route's screens are pushed under the lock", listOf<AppRoute>(AppRoute.Conversation(ChatId("deniz"))), model.router.path)
        assertEquals("the lock stays until the content is on screen", Overlay.Lock, model.overlay)
        assertTrue(model.unlockDissolvePending)
        model.unlockContentShown()
        assertEquals(Overlay.None, model.overlay)
        assertTrue(model.overlayAnimates)
        // A later unlock only dissolves the lock.
        model.sceneDidEnterBackground()
        model.sceneWillEnterForeground()
        model.didUnlock()
        assertEquals(Overlay.None, model.overlay)
    }

    @Test
    fun finishingOnboardingCrossFadesToTheChats() {
        val model = model(start = StartScreen.Welcome)
        model.didFinishOnboarding()
        assertEquals(Phase.Unlocked, model.phase)
        assertTrue(model.isLeavingOnboarding)
        assertTrue(model.phaseAnimates)
        model.onboardingFadedOut()
        assertFalse(model.isLeavingOnboarding)
    }

    @Test
    fun erasingGoesBackToTheWelcome() = runBlocking {
        val prefs = preferences("erase")
        prefs.hasAcknowledgedSaveNotice = true
        val model = model(prefs = prefs)
        model.router.setPath(listOf(AppRoute.Settings))
        model.sceneDidEnterBackground()
        model.sceneWillEnterForeground()
        model.eraseEverything()
        assertEquals(Phase.Onboarding, model.phase)
        assertEquals(OnboardingEntry.Welcome, model.onboardingEntry)
        assertEquals(Overlay.None, model.overlay)
        assertEquals(1, model.accountResets)
        assertFalse(prefs.hasAcknowledgedSaveNotice)
        assertNull(model.messenger.account.value)
        // The stack empties only once it has faded out.
        assertEquals(listOf<AppRoute>(AppRoute.Settings), model.router.path)
        model.mainStackRemoved()
        assertTrue(model.router.isAtRoot)
    }

    @Test
    fun aRestoreInsideTheLockContinuesAtANewCode() {
        val model = model(start = StartScreen.Locked)
        model.continueAfterRestore()
        assertEquals(Phase.Onboarding, model.phase)
        assertEquals(OnboardingEntry.NewCodeAfterRestore, model.onboardingEntry)
        assertEquals(Overlay.None, model.overlay)
    }

    @Test
    fun aKeptAccountReturnsToTheLock() {
        val model = model(start = StartScreen.Welcome)
        model.returnToLock()
        assertEquals(Phase.Locked, model.phase)
        assertEquals(Overlay.Lock, model.overlay)
        assertFalse(model.overlayAnimates)
    }

    @Test
    fun theWipeOutsideTheLockAndLeavingIt() {
        val model = model()
        model.router.setPath(listOf(AppRoute.Settings))
        model.dataWipedAfterWrongCodes()
        assertEquals(Phase.Locked, model.phase)
        assertEquals(Overlay.Lock, model.overlay)
        assertTrue(model.showsWipedOutcome)
        assertTrue(model.router.isAtRoot)
        assertEquals(1, model.accountResets)
        model.leaveWipedLock(restoring = true)
        assertEquals(Phase.Onboarding, model.phase)
        assertEquals(OnboardingEntry.RestoreFromBackup, model.onboardingEntry)
        assertFalse(model.showsWipedOutcome)
        assertEquals(Overlay.None, model.overlay)
    }

    @Test
    fun preferencesPersistAndReset() {
        val first = preferences("persist")
        first.theme = ThemePreference.Light
        first.language = AppLanguage.English
        first.hasAcknowledgedSaveNotice = true
        val reloaded = preferences("persist")
        assertEquals(ThemePreference.Light, reloaded.theme)
        assertEquals(AppLanguage.English, reloaded.language)
        assertTrue(reloaded.hasAcknowledgedSaveNotice)
        reloaded.reset()
        val again = preferences("persist")
        assertEquals(ThemePreference.System, again.theme)
        assertEquals(AppLanguage.System, again.language)
        assertFalse(again.hasAcknowledgedSaveNotice)
    }

    @Test
    fun launchOverridesAreSaved() {
        val prefs = preferences("overrides")
        model(config = LaunchConfig(start = StartScreen.Chats, usesFixtures = true, theme = "light", language = "en"), prefs = prefs)
        assertEquals(ThemePreference.Light, prefs.theme)
        assertEquals(AppLanguage.English, prefs.language)
        model(config = LaunchConfig(start = StartScreen.Chats, usesFixtures = true, resetPreferences = true), prefs = prefs)
        assertEquals(ThemePreference.System, prefs.theme)
    }

    @Test
    fun theClockFollowsTheLaunch() {
        assertTrue(model().clock.isFrozen)
        val running = model(config = LaunchConfig(start = StartScreen.Chats, usesFixtures = true))
        assertFalse(running.clock.isFrozen)
        // The design's data runs from the design's moment.
        assertTrue(kotlin.math.abs(running.clock.now.toEpochMilli() - DesignClock.referenceDate.toEpochMilli()) < 60_000)
    }

    @Test
    fun theHarnessPlanDecidesTheStartAndRunsItsPreparationFirst() {
        var prepared = false
        val plan = HarnessPlan(
            start = StartScreen.Chats,
            stack = listOf(AppRoute.Requests),
            uiState = HarnessStateId("4.3-empty"),
            prepare = { prepared = true },
        )
        val model = model(
            config = LaunchConfig(start = StartScreen.Welcome, usesFixtures = true, frozenClock = true),
            services = Services(plan),
        )
        assertEquals(Phase.Unlocked, model.phase)
        assertEquals(listOf<AppRoute>(AppRoute.Requests), model.router.path)
        assertEquals(HarnessStateId("4.3-empty"), model.harnessState)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(prepared)
        assertTrue(model.isReady)
    }

    /** `U2Start locked` with a route (iOS FlowWalk 03): the lock first, the route's screens after the unlock. */
    @Test
    fun anExplicitLockedStartOpensTheRouteAfterTheUnlock() {
        val plan = HarnessPlan(start = StartScreen.Chats, stack = listOf(AppRoute.Conversation(ChatId("deniz"))))
        val model = model(
            config = LaunchConfig(start = StartScreen.Locked, usesFixtures = true, frozenClock = true),
            services = Services(plan),
        )
        assertEquals(Phase.Locked, model.phase)
        assertEquals(Overlay.Lock, model.overlay)
        assertTrue(model.router.path.isEmpty())
        model.didUnlock()
        assertEquals(Phase.Unlocked, model.phase)
        assertEquals(listOf<AppRoute>(AppRoute.Conversation(ChatId("deniz"))), model.router.path)
    }
}
