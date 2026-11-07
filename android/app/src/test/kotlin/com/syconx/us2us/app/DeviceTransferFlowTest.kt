package com.syconx.us2us.app

import android.content.Context
import android.view.View
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.syconx.us2us.domain.BackupOpenResult
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.DeviceTransferFailure
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.harness.HarnessPlan
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.LaunchConfig
import com.syconx.us2us.domain.nav.SettingsPage
import com.syconx.us2us.domain.nav.StartScreen
import com.syconx.us2us.feature.onboarding.OnboardingTags
import com.syconx.us2us.feature.settings.DeviceTransferTags
import com.syconx.us2us.feature.settings.RestoreTags
import com.syconx.us2us.feature.settings.SettingsTags
import com.syconx.us2us.feature.verification.VerificationTags
import com.syconx.us2us.fixtures.FixturesMessengerService
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
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
 * “Yeni telefona taşı” through the app's real routes and screens, on the design fixtures (iOS
 * `DeviceTransferUITests`): the old phone from 7.1 through the secret code and the scanner, the new
 * phone from 1.1 through its code to the restore and a new secret code.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class DeviceTransferFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var model: AppModel? = null
    private lateinit var service: FixturesMessengerService
    private lateinit var view: View

    private inner class Services(private val prepare: FixturesMessengerService.() -> Unit) : AppServices {
        override fun usesFixtures(configuration: LaunchConfig) = true

        override fun fixtures(configuration: LaunchConfig, clock: DesignClock, english: Boolean, scope: CoroutineScope): MessengerService =
            FixturesMessengerService(
                hasAccount = configuration.start != StartScreen.Welcome,
                clock = clock,
                latency = Duration.ZERO,
                scope = scope,
                exportDirectory = context.cacheDir,
            ).apply(prepare).also { service = it }

        override fun live(configuration: LaunchConfig): MessengerService = error("no live service in tests")

        override fun harnessPlan(configuration: LaunchConfig, messenger: MessengerService): HarnessPlan? = null
    }

    private fun show(start: StartScreen, path: List<AppRoute> = emptyList(), prepare: FixturesMessengerService.() -> Unit = {}): AppModel {
        val prefs = AppPreferences(context.getSharedPreferences("transfer", Context.MODE_PRIVATE), appliesLanguage = false)
        val config = LaunchConfig(start = start, usesFixtures = true, frozenClock = true, designInsets = true)
        val model = AppModel(config, prefs, Services(prepare))
        this.model = model
        if (path.isNotEmpty()) model.router.setPath(path)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            view = LocalView.current
            AppRoot(model)
        }
        settle()
        return model
    }

    @After
    fun tearDown() {
        model?.close()
    }

    /** On the paused clock: what a tap changed has to reach the composition before the frames run. */
    private fun settle(millis: Long = 1_200) {
        compose.runOnIdle { Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    private fun shows(tag: String): Boolean =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    /** Settles until [tag] is on screen: file work on the fixtures' own threads finishes in real time. */
    private fun settleUntilShown(tag: String, limitMillis: Long = 30_000) {
        var waited = 0L
        while (!shows(tag) && waited < limitMillis) {
            settle(200)
            Thread.sleep(10)
            waited += 200
        }
        assertTrue("$tag on screen", shows(tag))
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    private fun typeCode(code: String) {
        for (digit in code) {
            compose.onNode(hasContentDescription(digit.toString()) and hasClickAction()).performClick()
            settle(40)
        }
        settle()
    }

    /** The scanner's mock, as the emulator's camera stands in for the code on the new phone. */
    private fun readTheNewPhonesCode() {
        settleUntilShown(VerificationTags.SIMULATOR_MOCK)
        val node = compose.onNodeWithTag(VerificationTags.SIMULATOR_MOCK, useUnmergedTree = true).fetchSemanticsNode()
        compose.runOnIdle { node.config[SemanticsActions.CustomActions].first().action() }
        settle(300)
    }

    @Test
    fun theSettingsRowOpensTheOldPhonesPage() {
        val app = show(StartScreen.Chats, listOf(AppRoute.Settings))
        tap(SettingsTags.TRANSFER)
        assertEquals(AppRoute.Page(SettingsPage.TransferSend), app.router.path.last())
        assertTrue(shows(DeviceTransferTags.SEND_CONTINUE))
    }

    @Test
    fun theOldPhoneSendsAfterItsCodeAndTheScan() {
        show(StartScreen.Chats, listOf(AppRoute.Settings, AppRoute.Page(SettingsPage.TransferSend)))
        tap(DeviceTransferTags.SEND_CONTINUE)
        assertFalse("not before the code", shows(VerificationTags.SIMULATOR_MOCK))
        typeCode(DesignFixtures.DEMO_SECRET_CODE)
        // The scanner of the transfer: its own hint, and no “own code” to show.
        settleUntilShown(VerificationTags.SCAN_SCREEN)
        assertFalse(shows(VerificationTags.SHOW_MY_CODE))
        readTheNewPhonesCode()
        settleUntilShown(DeviceTransferTags.SEND_DONE)
        assertFalse("the camera is gone", shows(VerificationTags.SCAN_SCREEN))
    }

    @Test
    fun aWrongCodeSendsNothing() {
        show(StartScreen.Chats, listOf(AppRoute.Settings, AppRoute.Page(SettingsPage.TransferSend)))
        tap(DeviceTransferTags.SEND_CONTINUE)
        typeCode("111111")
        settle(1_000)
        assertFalse(shows(VerificationTags.SCAN_SCREEN))
    }

    @Test
    fun aFailureSaysWhatToDoAndCanBeRetried() {
        show(StartScreen.Chats, listOf(AppRoute.Settings, AppRoute.Page(SettingsPage.TransferSend))) {
            transferSendFailure = DeviceTransferFailure.Unreachable
        }
        tap(DeviceTransferTags.SEND_CONTINUE)
        typeCode(DesignFixtures.DEMO_SECRET_CODE)
        readTheNewPhonesCode()
        settleUntilShown(DeviceTransferTags.SEND_FAILED)
        tap(DeviceTransferTags.ACTION)
        settleUntilShown(VerificationTags.SCAN_SCREEN)
    }

    @Test
    fun theNewPhoneShowsItsCodeThenRestoresWithoutAskingAnything() {
        show(StartScreen.Welcome) { transferDelivers = true }
        // The code is up, and gone again when the account starts to arrive.
        compose.onNodeWithTag(OnboardingTags.TRANSFER, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        settleUntilShown(DeviceTransferTags.RECEIVE_CODE)
        settleUntilShown(RestoreTags.ACTION_CONTINUE)
        assertTrue(shows(DeviceTransferTags.RESTORE_CARD))
        assertFalse("nothing to pick", shows(RestoreTags.FILE))
        assertFalse("nothing to type", shows(RestoreTags.PASSWORD))
        tap(RestoreTags.ACTION_CONTINUE)
        // 2.2: a new secret code on this phone, as after any restore.
        settle()
        compose.onNode(hasContentDescription("2") and hasClickAction()).assertExists()
    }

    @Test
    fun theMessagesSettleInWithTheScreenOnAndSayHowFar() {
        show(StartScreen.Welcome) { transferDelivers = true }
        tap(OnboardingTags.TRANSFER)
        settleUntilShown(RestoreTags.ACTION_CONTINUE)
        tap(RestoreTags.ACTION_CONTINUE)
        settle()
        typeCode("730415")
        settle()
        typeCode("730415")
        settleUntilShown(OnboardingTags.ACKNOWLEDGE)
        tap(OnboardingTags.ACKNOWLEDGE)
        compose.onNodeWithTag(OnboardingTags.FINISH, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        settle(500)
        assertTrue("nothing may stop it now", view.keepScreenOn)
        compose.onNodeWithText("42", substring = true, useUnmergedTree = true).assertExists()
        settle(2_500)
        assertFalse(view.keepScreenOn)
    }

    @Test
    fun noInternetOnTheNewPhoneCanBeTriedAgain() {
        show(StartScreen.Welcome) {
            transferDelivers = true
            nextBackupOpenResult = BackupOpenResult.Unverified
        }
        tap(OnboardingTags.TRANSFER)
        settleUntilShown(RestoreTags.ACTION_SUBMIT)
        assertFalse("nothing to type", shows(RestoreTags.PASSWORD))
        tap(RestoreTags.ACTION_SUBMIT)
        settleUntilShown(RestoreTags.ACTION_CONTINUE)
    }

    @Test
    fun whatCanNeverBeRestoredStartsOver() {
        show(StartScreen.Welcome) {
            transferDelivers = true
            nextBackupOpenResult = BackupOpenResult.NoLongerValid
        }
        tap(OnboardingTags.TRANSFER)
        settleUntilShown(DeviceTransferTags.RESTORE_START_OVER)
        assertFalse("no file picker here", shows(RestoreTags.ACTION_PICK))
        tap(DeviceTransferTags.RESTORE_START_OVER)
        assertTrue(shows(OnboardingTags.TRANSFER))
    }

    @Test
    fun theNewPhoneWaitsWithItsCodeOnScreenAndBackLeaves() {
        show(StartScreen.Welcome)
        tap(OnboardingTags.TRANSFER)
        settleUntilShown(DeviceTransferTags.RECEIVE_CODE)
        assertTrue("the code stays readable while the old phone is fetched", view.keepScreenOn)
        tap(DeviceTransferTags.RECEIVE_BACK)
        assertFalse(view.keepScreenOn)
        assertTrue(shows(OnboardingTags.TRANSFER))
        assertFalse(shows(DeviceTransferTags.RECEIVE_CODE))
    }

    @Test
    fun aNewPhoneWithoutRoomSaysSoAndOfferingAgainStartsOver() {
        show(StartScreen.Welcome) { transferDoesNotFit = true }
        tap(OnboardingTags.TRANSFER)
        settleUntilShown(DeviceTransferTags.RECEIVE_FAILED)
        service.transferDoesNotFit = false
        tap(DeviceTransferTags.ACTION)
        settleUntilShown(DeviceTransferTags.RECEIVE_CODE)
    }

    @Test
    fun anOldPhoneWhoseNewPhoneHasNoRoomSaysSo() {
        show(StartScreen.Chats, listOf(AppRoute.Settings, AppRoute.Page(SettingsPage.TransferSend))) {
            transferSendFailure = DeviceTransferFailure.NoSpace
        }
        tap(DeviceTransferTags.SEND_CONTINUE)
        typeCode(DesignFixtures.DEMO_SECRET_CODE)
        readTheNewPhonesCode()
        settleUntilShown(DeviceTransferTags.SEND_FAILED)
    }

    @Test
    fun theNewPhoneWithoutANetworkSaysSo() {
        show(StartScreen.Welcome) { transferReceiveFailure = DeviceTransferFailure.NoLocalNetwork }
        tap(OnboardingTags.TRANSFER)
        settleUntilShown(DeviceTransferTags.RECEIVE_FAILED)
        assertFalse(shows(DeviceTransferTags.RECEIVE_CODE))
    }
}
