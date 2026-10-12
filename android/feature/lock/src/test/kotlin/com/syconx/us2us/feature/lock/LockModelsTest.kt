package com.syconx.us2us.feature.lock

import com.syconx.us2us.domain.Biometry
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.UnlockAttemptResult
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.fixtures.FixturesMessengerService
import com.syconx.us2us.ui.components.CodeDotsAppearance
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 03 · Kilit — the lock flow's behaviour with the design fixtures (iOS LockFlowTests). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LockModelsTest {
    private val clocks = TestClocks()

    private fun service(): FixturesMessengerService = FixturesMessengerService(
        hasAccount = true,
        clock = DesignClock.frozen(DesignClock.referenceDate),
        latency = Duration.ZERO,
        bootClock = clocks::sinceBoot,
        wallClock = clocks.wallClock,
    )

    private val prompt = BiometricPromptText("reason", "Kodla aç")

    private fun TestScope.flow(
        service: FixturesMessengerService = service(),
        biometry: Biometry = Biometry.None,
        onUnlock: () -> Unit = {},
        onErase: () -> Unit = {},
        onStartOver: (Boolean) -> Unit = {},
        startsWiped: Boolean = false,
    ) = LockFlowModel(
        messenger = service,
        scope = this,
        biometry = biometry,
        startsWiped = startsWiped,
        onUnlock = onUnlock,
        onErase = onErase,
        onStartOver = onStartOver,
        now = { clocks.now },
    )

    private fun TestScope.codeModel(service: FixturesMessengerService = service(), onUnlock: () -> Unit = {}) =
        LockCodeModel(service, this, onUnlock = onUnlock, now = { clocks.now })

    private fun type(code: String, into: LockCodeModel) {
        for (digit in code) into.type(digit.digitToInt())
    }

    @Test
    fun startsOnFaceIdOnlyWhenBiometricsAreAvailable() = runTest {
        assertEquals(LockFlowModel.Stage.Biometric, flow(biometry = Biometry.Face).stage)
        assertEquals(LockFlowModel.Stage.Code, flow(biometry = Biometry.None).stage)
        assertEquals(LockFlowModel.Stage.Wiped, flow(biometry = Biometry.Face, startsWiped = true).stage)
    }

    @Test
    fun useCodeLeavesFaceId() = runTest {
        val flow = flow(biometry = Biometry.Face)
        flow.useCode()
        assertEquals(LockFlowModel.Stage.Code, flow.stage)
    }

    @Test
    fun aRefusedScanFallsBackToTheCodeAfterADwell() = runTest {
        val service = service()
        service.biometricsResult = false
        val flow = flow(service, biometry = Biometry.Face)
        flow.appBecameActive(prompt)
        assertTrue(flow.isScanning)
        runCurrent()
        assertEquals("an instant refusal does not flicker", LockFlowModel.Stage.Biometric, flow.stage)
        advanceTimeBy(LockFlowModel.MIN_DWELL + 1.milliseconds)
        assertEquals(LockFlowModel.Stage.Code, flow.stage)
        // Armed once: becoming active again does not scan by itself.
        flow.appBecameActive(prompt)
        assertFalse(flow.isScanning)
    }

    @Test
    fun aSuccessfulScanUnlocks() = runTest {
        val service = service()
        service.biometricsResult = true
        var unlocked = 0
        val flow = flow(service, biometry = Biometry.Face, onUnlock = { unlocked += 1 })
        flow.appBecameActive(prompt)
        advanceUntilIdle()
        assertEquals(1, unlocked)
    }

    @Test
    fun wrongCodesCountDownThenWait() = runTest {
        val code = codeModel()
        type("111112", code)
        advanceTimeBy(300.milliseconds)
        assertEquals(LockCodeModel.Message.Wrong(attemptsLeft = 2, delay = 1.minutes), code.message)
        assertEquals(CodeDotsAppearance.Error, code.appearance)
        // The red dots clear after 0.8 s; the sentence stays.
        advanceUntilIdle()
        assertTrue(code.entered.isEmpty())
        assertEquals(CodeDotsAppearance.Normal, code.appearance)
        assertNotNull(code.message)

        // The next digit starts over and the sentence goes.
        code.type(1)
        assertNull(code.message)
        assertEquals("1", code.entered)
        type("11112", code)
        advanceTimeBy(300.milliseconds)
        assertEquals(LockCodeModel.Message.Wrong(attemptsLeft = 1, delay = 1.minutes), code.message)

        advanceTimeBy(1_000.milliseconds)
        type("111112", code)
        advanceTimeBy(300.milliseconds)
        assertTrue("the third wrong code waits a minute (PROTOCOL §5.2)", code.isThrottled)
        advanceTimeBy(1_000.milliseconds)
        assertEquals(CodeDotsAppearance.Dimmed, code.appearance)
        // While waiting, digits are ignored.
        code.type(2)
        assertEquals("", code.entered)

        // The wait ends with the wall clock: the keypad comes back.
        clocks.advance(61)
        assertFalse(code.isThrottled)
        advanceUntilIdle()
        assertNull(code.message)
        assertEquals(CodeDotsAppearance.Normal, code.appearance)
    }

    @Test
    fun aWaitSurvivesANewLock() = runTest {
        val service = service()
        repeat(3) { service.unlock("111112") }
        val code = codeModel(service)
        assertTrue(code.isThrottled)
        assertEquals(CodeDotsAppearance.Dimmed, code.appearance)
    }

    @Test
    fun theRightCodeUnlocks() = runTest {
        var unlocked = false
        val code = codeModel(onUnlock = { unlocked = true })
        type(DesignFixtures.DEMO_SECRET_CODE, code)
        advanceUntilIdle()
        assertTrue(unlocked)
        assertTrue("no more input while the lock dissolves", code.isVerifying)
    }

    @Test
    fun aVaultThatCannotBeReadIsNotAWrongCode() = runTest {
        val service = service()
        service.nextUnlockResult = UnlockAttemptResult.VaultUnavailable
        val code = codeModel(service)
        type(DesignFixtures.DEMO_SECRET_CODE, code)
        advanceUntilIdle()
        assertEquals(LockCodeModel.Message.Unavailable, code.message)
        assertEquals(CodeDotsAppearance.Normal, code.appearance)
        assertEquals(0, code.shakeTrigger)
    }

    @Test
    fun theTenthWrongCodeShowsTheWipedOutcome() = runTest {
        val service = service()
        service.wipesAfterTenWrongCodes = true
        service.setWrongCodes(9)
        var startedOver: Boolean? = null
        val flow = flow(service, onStartOver = { startedOver = it })
        type("111112", flow.code)
        advanceUntilIdle()
        assertEquals(LockFlowModel.Stage.Wiped, flow.stage)
        assertNull("never “Kod yanlış”", flow.code.message)
        assertNull(service.account.value)
        flow.restoreAfterWipe()
        assertEquals(true, startedOver)
    }

    @Test
    fun eraseNeedsASecondConfirmation() = runTest {
        var erased = false
        val flow = flow(onErase = { erased = true })
        flow.openForgot()
        assertTrue(flow.showsForgot)
        // Not without the second step.
        flow.confirmErase()
        assertFalse(flow.isErasing)
        flow.askEraseConfirmation()
        assertEquals(LockFlowModel.ForgotStep.ConfirmErase, flow.forgotStep)
        // A double tap straight through the danger card does nothing.
        flow.confirmErase()
        assertFalse(flow.isErasing)
        advanceUntilIdle()
        assertTrue(flow.confirmationArmed)
        flow.confirmErase()
        assertTrue(flow.isErasing)
        assertFalse("the sheet goes down first", flow.showsForgot)
        assertFalse(erased)
        flow.forgotDidDismiss()
        assertTrue(erased)
    }

    @Test
    fun cancelReturnsToTheOptions() = runTest {
        val flow = flow()
        flow.openForgot()
        flow.askEraseConfirmation()
        flow.cancelErase()
        assertEquals(LockFlowModel.ForgotStep.Options, flow.forgotStep)
        advanceTimeBy(700.milliseconds)
        assertFalse(flow.confirmationArmed)
        flow.confirmErase()
        assertFalse(flow.isErasing)
    }

    @Test
    fun restoreOpensAfterTheSheet() = runTest {
        val flow = flow()
        flow.openForgot()
        flow.chooseBackup()
        assertFalse(flow.showsForgot)
        assertEquals(LockFlowModel.Stage.Code, flow.stage)
        flow.forgotDidDismiss()
        // The picker opens in the lock; a cancelled pick keeps 3.2, a chosen file goes to 7.3.
        assertTrue(flow.showsBackupPicker)
        assertEquals(LockFlowModel.Stage.Code, flow.stage)
        flow.backupPickerClosed(null)
        assertFalse(flow.showsBackupPicker)
        assertEquals(LockFlowModel.Stage.Code, flow.stage)
        val file = java.net.URI("file:///backup.u2u")
        flow.openForgot()
        flow.chooseBackup()
        flow.forgotDidDismiss()
        flow.backupPickerClosed(file)
        assertEquals(LockFlowModel.Stage.Restore(file), flow.stage)
        flow.leaveRestore()
        assertEquals(LockFlowModel.Stage.Code, flow.stage)
    }

    @Test
    fun relockStartsAfresh() = runTest {
        val flow = flow(biometry = Biometry.Face)
        flow.useCode()
        flow.code.type(4)
        flow.openForgot()
        flow.relock()
        assertEquals(LockFlowModel.Stage.Biometric, flow.stage)
        assertEquals("", flow.code.entered)
        assertFalse(flow.showsForgot)
    }

    @Test
    fun waitDurationsReadNaturally() {
        val tr = Locale.forLanguageTag("tr-TR")
        val en = Locale.forLanguageTag("en-US")
        assertEquals("1 dakika", LockFormat.waitDuration(1.minutes, tr))
        assertEquals("5 dakika", LockFormat.waitDuration(5.minutes, tr))
        assertEquals("1 saat", LockFormat.waitDuration(1.hours, tr))
        assertEquals("1 minute", LockFormat.waitDuration(1.minutes, en))
        assertEquals("15 minutes", LockFormat.waitDuration(15.minutes, en))
    }

    @Test
    fun harnessRoutes() {
        assertEquals(LockHarnessState.Screen.Wrong, (LockHarness.plan(null, "wrong", null)?.uiState as LockHarnessState).screen)
        assertTrue((LockHarness.plan(null, "switcher", null)?.uiState as LockHarnessState).replacesLock)
        assertNull(LockHarness.plan(null, "nope", null))
        assertEquals(Biometry.Fingerprint, LockHarnessState(LockHarnessState.Screen.TouchId).biometry)
        assertEquals(15, LockHarness.states.size)
    }
}
