package com.syconx.us2us.app

import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.harness.HarnessPlan
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.LaunchConfig
import com.syconx.us2us.domain.nav.Overlay
import com.syconx.us2us.domain.nav.Phase
import com.syconx.us2us.domain.nav.StartScreen
import com.syconx.us2us.fixtures.FixturesMessengerService
import com.syconx.us2us.ui.env.ExternalActivityGuard
import com.syconx.us2us.ui.env.U2Haptic
import com.syconx.us2us.ui.env.U2SafeArea
import com.syconx.us2us.ui.env.u2BottomPadding
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The root on the test clock: the phase cross-fades (onboarding kept while it fades out over
 * 4.1), the lock dissolving only after the content's first frames, the cover drawn the moment the
 * app pauses; and the environment's small rules (haptics, safe area, the guard).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class RootViewTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var model: AppModel? = null

    private object Services : AppServices {
        override fun usesFixtures(configuration: LaunchConfig) = true

        override fun fixtures(configuration: LaunchConfig, clock: DesignClock, english: Boolean, scope: CoroutineScope): MessengerService =
            FixturesMessengerService(hasAccount = configuration.start != StartScreen.Welcome, clock = clock, latency = Duration.ZERO, scope = scope)

        override fun live(configuration: LaunchConfig): MessengerService = error("no live service in tests")

        override fun harnessPlan(configuration: LaunchConfig, messenger: MessengerService): HarnessPlan? = null
    }

    private fun show(start: StartScreen, path: List<AppRoute> = emptyList()): AppModel {
        val prefs = AppPreferences(context.getSharedPreferences("root", Context.MODE_PRIVATE), appliesLanguage = false)
        val config = LaunchConfig(start = start, usesFixtures = true, frozenClock = true, designInsets = true)
        val model = AppModel(config, prefs, Services)
        this.model = model
        if (path.isNotEmpty()) model.router.setPath(path)
        compose.mainClock.autoAdvance = false
        compose.setContent { AppRoot(model) }
        advance(100)
        return model
    }

    @After
    fun tearDown() {
        model?.close()
    }

    private fun advance(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    private fun act(block: () -> Unit) {
        compose.runOnIdle {
            block()
            Snapshot.sendApplyNotifications()
        }
    }

    @Test
    fun onboardingFadesOutOverTheChats() {
        val model = show(StartScreen.Welcome)
        compose.onNodeWithTag(RootTags.ONBOARDING).assertExists()
        compose.onNodeWithTag(RootTags.MAIN_STACK).assertDoesNotExist()
        act { model.didFinishOnboarding() }
        advance(100)
        // 4.1 is there at once, under the fading onboarding.
        compose.onNodeWithTag(RootTags.MAIN_STACK).assertExists()
        compose.onNodeWithTag(RootTags.ONBOARDING).assertExists()
        advance(700)
        assertFalse(model.isLeavingOnboarding)
        advance(100)
        compose.onNodeWithTag(RootTags.ONBOARDING).assertDoesNotExist()
    }

    @Test
    fun erasingCrossFadesToOnboardingAndEmptiesTheStackAfterwards() {
        val model = show(StartScreen.Chats, path = listOf(AppRoute.Settings))
        compose.onNodeWithTag(RootTags.MAIN_STACK).assertExists()
        act { model.scope.launch { model.eraseEverything() } }
        advance(100)
        assertEquals(Phase.Onboarding, model.phase)
        assertEquals("the stack goes only once it has faded out", listOf<AppRoute>(AppRoute.Settings), model.router.path)
        advance(800)
        compose.onNodeWithTag(RootTags.MAIN_STACK).assertDoesNotExist()
        compose.onNodeWithTag(RootTags.ONBOARDING).assertExists()
        assertTrue(model.router.isAtRoot)
    }

    @Test
    fun theLockDissolvesAfterTheContentIsOnScreen() {
        val model = show(StartScreen.Locked)
        compose.onNodeWithTag(RootTags.LOCK).assertExists()
        compose.onNodeWithTag(RootTags.MAIN_STACK).assertDoesNotExist()
        act { model.didUnlock() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        // The content is built under the still opaque lock.
        compose.onNodeWithTag(RootTags.MAIN_STACK).assertExists()
        assertEquals(Overlay.Lock, model.overlay)
        advance(100)
        assertEquals(Overlay.None, model.overlay)
        advance(500)
        compose.onNodeWithTag(RootTags.SECURITY).assertDoesNotExist()
    }

    @Test
    fun thePauseCoverIsDrawnAtOnceAndTheLockReplacesIt() {
        val model = show(StartScreen.Chats)
        act { model.sceneWillResignActive { null } }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithTag(RootTags.PRIVACY_COVER).assertExists()
        act {
            model.sceneDidEnterBackground()
            model.sceneWillEnterForeground()
        }
        advance(600)
        compose.onNodeWithTag(RootTags.LOCK).assertExists()
        compose.onNodeWithTag(RootTags.PRIVACY_COVER).assertDoesNotExist()
    }

    @Test
    fun hapticsMapToTheAndroidConstants() {
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, U2Haptic.KeypadTap.feedbackConstant(36))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, U2Haptic.LongPress.feedbackConstant(36))
        assertEquals(HapticFeedbackConstants.SEGMENT_TICK, U2Haptic.Selection.feedbackConstant(34))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, U2Haptic.Selection.feedbackConstant(33))
        assertEquals(HapticFeedbackConstants.REJECT, U2Haptic.Error.feedbackConstant(30))
        assertEquals(HapticFeedbackConstants.CONFIRM, U2Haptic.Success.feedbackConstant(30))
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, U2Haptic.Send.feedbackConstant(36))
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, U2Haptic.Voice.feedbackConstant(36))
    }

    @Test
    fun theSafeAreaMapsDesignCoordinates() {
        assertEquals(56f, U2SafeArea.Design.top(56.dp).value, 0.001f)
        assertEquals(44f, u2BottomPadding(44.dp, U2SafeArea.Design).value, 0.001f)
        // A phone with a 24 dp status bar and a 16 dp navigation bar: everything moves up by the difference.
        val phone = U2SafeArea(top = 24.dp, bottom = 16.dp)
        assertEquals(18f, phone.top(56.dp).value, 0.001f)
        assertEquals(26f, u2BottomPadding(44.dp, phone).value, 0.001f)
        // Never less than the padding above the design's home indicator.
        assertEquals(10f, u2BottomPadding(44.dp, U2SafeArea(top = 24.dp, bottom = 0.dp)).value, 0.001f)
    }

    /** No focused field (and so no keyboard) stays under the cover or comes back with the lock (iOS resigns the first responder). */
    @Test
    fun theCoverTakesTheFocusAway() {
        val model = show(StartScreen.Chats, path = listOf(AppRoute.Conversation(ChatId("deniz"))))
        advance(1_000)
        compose.onNodeWithTag("composer.input").performSemanticsAction(SemanticsActions.RequestFocus)
        advance(100)
        compose.onNodeWithTag("composer.input").assertIsFocused()
        act { model.sceneWillResignActive { null } }
        advance(100)
        compose.onNodeWithTag("composer.input").assertIsNotFocused()
    }

    @Test
    fun theGuardCountsLaunchesAndResumes() {
        var now = 0L
        val guard = ExternalActivityGuard(elapsedRealtime = { now })
        assertFalse(guard.isActive)
        guard.begin()
        guard.begin()
        guard.end()
        assertTrue(guard.isActive)
        guard.end()
        assertFalse(guard.isActive)
        guard.beginUntilResume()
        assertTrue(guard.isActive)
        guard.resumed()
        assertFalse(guard.isActive)
        assertTrue(guard.covers(4 * 60_000L))
        assertFalse(guard.covers(5 * 60_000L))
    }
}
