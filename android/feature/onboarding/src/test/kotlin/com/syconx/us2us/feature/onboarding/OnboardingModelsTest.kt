package com.syconx.us2us.feature.onboarding

import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.UsernameRules
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.nav.OnboardingRoute
import com.syconx.us2us.fixtures.FixturesMessengerService
import com.syconx.us2us.ui.components.CodeDotsAppearance
import com.syconx.us2us.ui.components.UsernameFieldStatus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.1 rules while typing, 2.2 set and confirm, 2.3 and the name found taken at setup (iOS UsernameSetupTests, LockAndSetupRecoveryTests). */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingModelsTest {
    private fun fixtures() = FixturesMessengerService(
        hasAccount = false,
        clock = DesignClock.frozen(DesignClock.referenceDate),
        latency = Duration.ZERO,
    )

    private fun TestScope.usernameModel() = UsernameStepModel(fixtures(), this, debounce = 10.milliseconds)

    private fun TestScope.flow(service: FixturesMessengerService, onFinished: () -> Unit = {}, onReturnToLock: () -> Unit = {}) =
        OnboardingFlowModel(service, this, onFinished = onFinished, onReturnToLock = onReturnToLock).also {
            it.username.debounce = 10.milliseconds
        }

    private fun TestScope.typeCode(flow: OnboardingFlowModel, code: String = DesignFixtures.DEMO_SECRET_CODE) {
        for (digit in code) flow.code.type(digit.digitToInt())
        advanceUntilIdle()
    }

    // MARK: 2.1

    @Test
    fun aTurkishLetterStaysInTheNameAndIsReported() = runTest {
        val model = usernameModel()
        model.text = "Kağan"
        assertEquals("the typed text is never rewritten by the model", "Kağan", model.text)
        assertEquals("kağan", model.name)
        assertEquals(listOf(UsernameRules.Violation.TurkishCharacters), model.violations)
        assertEquals(UsernameStepModel.Status.Invalid(UsernameRules.Violation.TurkishCharacters), model.status)
        assertTrue(model.isBroken(UsernameRules.Violation.TurkishCharacters))
        assertFalse(model.isBroken(UsernameRules.Violation.Characters) || model.isBroken(UsernameRules.Violation.FirstLetter) || model.isBroken(UsernameRules.Violation.Length))
        assertFalse(model.canContinue)
        advanceUntilIdle()
        assertEquals("an invalid name is never checked", UsernameStepModel.Status.Invalid(UsernameRules.Violation.TurkishCharacters), model.status)
    }

    @Test
    fun everyBrokenRuleIsMarkedAndTheStatusNamesTheFirst() = runTest {
        val model = usernameModel()
        model.text = "1.ş"
        assertEquals(
            listOf(UsernameRules.Violation.TurkishCharacters, UsernameRules.Violation.Characters, UsernameRules.Violation.FirstLetter),
            model.violations,
        )
        assertEquals(UsernameStepModel.Status.Invalid(UsernameRules.Violation.TurkishCharacters), model.status)
        for (rule in listOf(UsernameRules.Violation.TurkishCharacters, UsernameRules.Violation.Characters, UsernameRules.Violation.FirstLetter)) {
            assertTrue(model.isBroken(rule))
        }
        model.text = "selin.n"
        assertEquals(UsernameStepModel.Status.Invalid(UsernameRules.Violation.Characters), model.status)
        model.text = "a".repeat(25)
        assertEquals(UsernameStepModel.Status.Invalid(UsernameRules.Violation.Length), model.status)
        assertTrue("too long shows at once", model.isBroken(UsernameRules.Violation.Length))
        assertEquals("25 / 24", model.counter)
        assertEquals(R_TOO_LONG, violationText(UsernameRules.Violation.Length, model.name))
    }

    @Test
    fun tooShortIsSaidOnlyOnceTypingPauses() = runTest {
        val model = usernameModel()
        model.text = "se"
        assertEquals(UsernameStepModel.Status.Typing, model.status)
        assertFalse(model.isBroken(UsernameRules.Violation.Length))
        advanceUntilIdle()
        assertEquals(UsernameStepModel.Status.TooShort, model.status)
        assertTrue(model.isBroken(UsernameRules.Violation.Length))
        assertEquals("2 / 24", model.counter)
    }

    @Test
    fun whiteSpaceAndOneAtSignAreNotPartOfTheName() = runTest {
        val model = usernameModel()
        model.text = " @Selin_K "
        assertEquals("selin_k", model.name)
        advanceUntilIdle()
        assertEquals(UsernameStepModel.Status.Available, model.status)
        assertTrue(model.canContinue)
        assertEquals(UsernameFieldStatus.Positive, model.fieldStatus)
    }

    @Test
    fun aNameTheLiveServiceCanOnlyCheckByTheRulesMayContinue() = runTest {
        val model = usernameModel()
        model.text = DesignFixtures.TAKEN_AT_SETUP_USERNAME
        advanceUntilIdle()
        assertEquals(UsernameStepModel.Status.MeetsRules, model.status)
        assertEquals(UsernameFieldStatus.Positive, model.fieldStatus)
        assertTrue(model.canContinue)
        assertTrue(model.canShowOwnExample)
    }

    @Test
    fun aNameFoundTakenAtSetupStaysTakenInThisSetup() = runTest {
        val model = usernameModel()
        model.text = DesignFixtures.TAKEN_AT_SETUP_USERNAME
        advanceUntilIdle()
        model.markTaken(model.name)
        assertEquals(UsernameStepModel.Status.Taken, model.status)
        assertFalse(model.canContinue)
        model.text = "ilgaz_2"
        advanceUntilIdle()
        assertEquals(UsernameStepModel.Status.Available, model.status)
        model.text = DesignFixtures.TAKEN_AT_SETUP_USERNAME
        advanceUntilIdle()
        assertEquals("not offered again", UsernameStepModel.Status.Taken, model.status)
    }

    @Test
    fun aTakenDesignNameIsSaidTaken() = runTest {
        val model = usernameModel()
        model.text = "selin"
        advanceUntilIdle()
        assertEquals(UsernameStepModel.Status.Taken, model.status)
        assertEquals(UsernameFieldStatus.Negative, model.fieldStatus)
    }

    // MARK: 2.2

    @Test
    fun aWeakCodeFlashesThenStartsOver() = runTest {
        val code = SecretCodeStepModel(fixtures(), this)
        for (digit in "111111") code.type(digit.digitToInt())
        assertTrue("keys wait while the last dot is held", code.isBusy)
        advanceTimeBy(SecretCodeStepModel.HOLD + 1.milliseconds)
        assertEquals(SecretCodeStepModel.Problem.Weak, code.problem)
        assertEquals(CodeDotsAppearance.Error, code.appearance)
        assertEquals(1, code.shakeTrigger)
        advanceUntilIdle()
        assertEquals(CodeDotsAppearance.Normal, code.appearance)
        assertTrue(code.entered.isEmpty())
        assertEquals("the sentence stays until the next digit", SecretCodeStepModel.Problem.Weak, code.problem)
        assertEquals(SecretCodeStepModel.Step.Set, code.step)
        code.type(2)
        assertNull(code.problem)
    }

    @Test
    fun aMismatchReturnsToTheSetStep() = runTest {
        val code = SecretCodeStepModel(fixtures(), this)
        var confirmed: String? = null
        code.onConfirmed = { confirmed = it }
        for (digit in "246813") code.type(digit.digitToInt())
        advanceUntilIdle()
        assertEquals(SecretCodeStepModel.Step.Confirm, code.step)
        assertTrue(code.entered.isEmpty())
        for (digit in "246814") code.type(digit.digitToInt())
        advanceTimeBy(SecretCodeStepModel.HOLD + 1.milliseconds)
        assertEquals(SecretCodeStepModel.Problem.Mismatch, code.problem)
        advanceUntilIdle()
        assertEquals(SecretCodeStepModel.Step.Set, code.step)
        assertNull(confirmed)
        for (digit in "246813") code.type(digit.digitToInt())
        advanceUntilIdle()
        for (digit in "246813") code.type(digit.digitToInt())
        advanceUntilIdle()
        assertEquals("246813", confirmed)
    }

    @Test
    fun backOnTheConfirmStepReturnsToTheSetStep() = runTest {
        val service = fixtures()
        val flow = flow(service)
        flow.restoreFinished()
        typeCode(flow)
        assertEquals(SecretCodeStepModel.Step.Confirm, flow.code.step)
        assertFalse(flow.allowsSwipeBack(OnboardingRoute.SecretCode))
        flow.codeBack()
        assertEquals(SecretCodeStepModel.Step.Set, flow.code.step)
        assertEquals(listOf(OnboardingRoute.SecretCode), flow.router.path)
        flow.codeBack()
        assertTrue(flow.router.path.isEmpty())
    }

    // MARK: 2.3

    @Test
    fun twoThreeOffersAnotherNameWithoutTheCheckbox() = runTest {
        val finish = NoWayBackStepModel(this)
        var chose = 0
        finish.onChooseAnotherName = { chose += 1 }
        finish.acknowledged = true
        finish.onSubmit = { NoWayBackStepModel.Failure.UsernameTaken }
        finish.submit()
        advanceUntilIdle()
        assertTrue(finish.choosesAnotherName)
        assertFalse(finish.returnsToLock)
        assertEquals(R_CHOOSE_ANOTHER, actionText(finish))
        finish.acknowledged = false
        assertTrue(finish.canFinish)
        finish.submit()
        assertEquals(1, chose)
        finish.resume()
        assertNull(finish.failure)
        assertFalse("resuming keeps the checkbox as it was", finish.acknowledged)
    }

    @Test
    fun aFailureThatLeftAVaultLeadsToTheLockNeverToTryAgain() = runTest {
        for (failure in listOf(NoWayBackStepModel.Failure.AccountKept, NoWayBackStepModel.Failure.PreviousVaultKept)) {
            val finish = NoWayBackStepModel(this)
            var returned = 0
            finish.onReturnToLock = { returned += 1 }
            finish.acknowledged = true
            finish.onSubmit = { failure }
            finish.submit()
            advanceUntilIdle()
            assertEquals(failure, finish.failure)
            assertTrue(finish.returnsToLock)
            assertEquals(R_TO_LOCK, actionText(finish))
            finish.acknowledged = false
            assertTrue(finish.canFinish)
            finish.submit()
            assertEquals(1, returned)
            assertEquals("nothing is retried", failure, finish.failure)
        }
        for (failure in listOf(NoWayBackStepModel.Failure.Network, NoWayBackStepModel.Failure.BackupGone, NoWayBackStepModel.Failure.BackupDamaged)) {
            val finish = NoWayBackStepModel(this)
            finish.acknowledged = true
            finish.onSubmit = { failure }
            finish.submit()
            advanceUntilIdle()
            assertFalse(finish.returnsToLock)
        }
    }

    @Test
    fun takenAtSetupThenAnotherNameFinishesWithTheKeptCode() = runTest {
        val service = fixtures()
        var finished = 0
        val flow = flow(service, onFinished = { finished += 1 })
        flow.startSetup()
        flow.username.text = DesignFixtures.TAKEN_AT_SETUP_USERNAME
        advanceUntilIdle()
        assertEquals(UsernameStepModel.Status.MeetsRules, flow.username.status)
        flow.usernameContinue()
        assertEquals(listOf(OnboardingRoute.Username, OnboardingRoute.SecretCode), flow.router.path)

        // 2.2: the code, twice.
        typeCode(flow)
        assertEquals(SecretCodeStepModel.Step.Confirm, flow.code.step)
        typeCode(flow)
        assertEquals(listOf(OnboardingRoute.Username, OnboardingRoute.SecretCode, OnboardingRoute.NoWayBack), flow.router.path)

        // 2.3: taken.
        flow.finish.acknowledged = true
        flow.finish.submit()
        advanceUntilIdle()
        assertEquals(NoWayBackStepModel.Failure.UsernameTaken, flow.finish.failure)
        assertEquals(UsernameStepModel.Status.Taken, flow.username.status)
        assertNull(service.account.value)

        // “Başka bir ad seç” → 2.1, the name still there.
        flow.finish.submit()
        assertEquals(listOf(OnboardingRoute.Username), flow.router.path)
        flow.pathChanged(listOf(OnboardingRoute.Username, OnboardingRoute.SecretCode, OnboardingRoute.NoWayBack), listOf(OnboardingRoute.Username))
        assertEquals(DesignFixtures.TAKEN_AT_SETUP_USERNAME, flow.username.text)
        assertFalse(flow.username.canContinue)

        // Another name → straight back to 2.3 (the code is kept) → done.
        flow.username.text = "ilgaz_k"
        advanceUntilIdle()
        flow.usernameContinue()
        assertEquals(listOf(OnboardingRoute.Username, OnboardingRoute.NoWayBack), flow.router.path)
        assertNull(flow.finish.failure)
        assertTrue(flow.finish.acknowledged)
        flow.finish.submit()
        advanceUntilIdle()
        assertEquals(1, finished)
        assertEquals("ilgaz_k", service.account.value?.username)
    }

    @Test
    fun aNameTakenAtSetupIsNotSentASecondTime() = runTest {
        val service = fixtures()
        val flow = flow(service)
        flow.startSetup()
        flow.username.text = "ilgaz_k"
        advanceUntilIdle()
        flow.usernameContinue()
        typeCode(flow)
        typeCode(flow)
        assertEquals(OnboardingRoute.NoWayBack, flow.router.path.last())
        // What the relay's 409 leaves on 2.1 (a name the fixtures would register).
        flow.username.markTaken("ilgaz_k")

        // 2.3 › back → 2.2 (the code starts over) → the code twice → 2.3.
        flow.router.pop()
        flow.pathChanged(listOf(OnboardingRoute.Username, OnboardingRoute.SecretCode, OnboardingRoute.NoWayBack), listOf(OnboardingRoute.Username, OnboardingRoute.SecretCode))
        assertEquals(SecretCodeStepModel.Step.Set, flow.code.step)
        typeCode(flow)
        typeCode(flow)
        assertEquals(OnboardingRoute.NoWayBack, flow.router.path.last())
        flow.finish.acknowledged = true
        flow.finish.submit()
        advanceUntilIdle()
        assertEquals(NoWayBackStepModel.Failure.UsernameTaken, flow.finish.failure)
        assertNull("the relay is not asked again", service.account.value)
        flow.finish.submit()
        assertEquals(listOf(OnboardingRoute.Username), flow.router.path)
    }

    @Test
    fun backOnTheWelcomePagesAKeptCodeGoes() = runTest {
        val flow = flow(fixtures())
        flow.setForHarness(OnboardingFlowModel.Mode.Create, emptyList(), confirmedCode = DesignFixtures.DEMO_SECRET_CODE)
        flow.pathChanged(listOf(OnboardingRoute.Username), emptyList())
        assertNull(flow.heldCode)
        flow.startSetup()
        flow.username.setForHarness("ilgaz_k", UsernameStepModel.Status.Available)
        flow.usernameContinue()
        assertEquals(listOf(OnboardingRoute.Username, OnboardingRoute.SecretCode), flow.router.path)
    }

    @Test
    fun aRestoreCountsTwoStepsAndFinishesTheRestore() = runTest {
        val service = fixtures()
        var finished = 0
        val flow = flow(service, onFinished = { finished += 1 })
        flow.startRestore()
        assertEquals(listOf(OnboardingRoute.Restore), flow.router.path)
        flow.restoreFinished()
        assertEquals(SetupStepLabel(1, 2), flow.stepLabel(OnboardingRoute.SecretCode))
        typeCode(flow)
        typeCode(flow)
        assertEquals(SetupStepLabel(2, 2), flow.stepLabel(OnboardingRoute.NoWayBack))
        flow.finish.acknowledged = true
        flow.finish.submit()
        advanceUntilIdle()
        assertEquals(1, finished)
        assertEquals(DesignFixtures.OWN_USERNAME, service.account.value?.username)
    }

    @Test
    fun backingOutOfARestoreThatBeganOnTheLockReturnsToTheLock() = runTest {
        var returned = 0
        val flow = flow(fixtures(), onReturnToLock = { returned += 1 })
        flow.continueAfterRestore()
        assertTrue(flow.finish.replacesThisPhone)
        assertEquals(listOf(OnboardingRoute.SecretCode), flow.router.path)
        flow.router.pop()
        flow.pathChanged(listOf(OnboardingRoute.SecretCode), emptyList())
        assertEquals(1, returned)
        assertEquals(OnboardingFlowModel.Mode.Create, flow.mode)
        assertFalse(flow.finish.replacesThisPhone)
    }

    @Test
    fun harnessStatesSetWhatTheyDraw() = runTest {
        fun applied(state: String): OnboardingFlowModel = flow(fixtures()).also { OnboardingHarnessState(state).apply(it) }
        assertEquals(UsernameStepModel.Status.Taken, applied("2.3-taken").username.status)
        assertEquals(NoWayBackStepModel.Failure.UsernameTaken, applied("2.3-taken").finish.failure)
        assertTrue(applied("restore-2.3-toLock").finish.returnsToLock)
        assertTrue(applied("restore-2.3-lock").finish.replacesThisPhone)
        assertEquals(SetupStepLabel(2, 2), applied("restore-2.3").stepLabel(OnboardingRoute.NoWayBack))
        assertEquals(4, applied("2.2").code.entered.size)
        assertEquals(listOf(OnboardingRoute.Restore), applied("7.3").router.path)
        assertEquals(2, applied("1.3").pager.page)
        assertTrue(OnboardingHarnessState("2.1").focusesUsernameField)
        assertFalse(OnboardingHarnessState("2.1-blurred").focusesUsernameField)
        assertEquals(null, OnboardingHarness.plan(null, "nope", null))
    }

    @Test
    fun theGhostDemoStartsAt234107AndLoops() {
        val origin = DesignClock.referenceDate
        assertEquals(GhostDemo.start, GhostDemo.remaining(origin, origin))
        val later = origin.plus(java.time.Duration.ofSeconds(GhostDemo.start.inWholeSeconds))
        assertEquals("a whole day again once it ran out", 24L * 3_600, GhostDemo.remaining(later, origin).inWholeSeconds)
    }

    private companion object {
        val R_TOO_LONG = com.syconx.us2us.ui.R.string.onboarding_username_status_toolong
        val R_CHOOSE_ANOTHER = com.syconx.us2us.ui.R.string.onboarding_nowayback_chooseanothername
        val R_TO_LOCK = com.syconx.us2us.ui.R.string.onboarding_nowayback_tolock
    }
}
