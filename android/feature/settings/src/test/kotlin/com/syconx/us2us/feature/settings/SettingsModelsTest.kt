package com.syconx.us2us.feature.settings

import com.syconx.us2us.domain.AppSettings
import com.syconx.us2us.domain.BackupFileError
import com.syconx.us2us.domain.BackupOpenResult
import com.syconx.us2us.domain.BackupPasswordRules
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.RestoreAccessCheck
import com.syconx.us2us.domain.RestoreProgress
import com.syconx.us2us.domain.UnlockAttemptResult
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.LaunchRoute
import com.syconx.us2us.domain.nav.OnboardingEntry
import com.syconx.us2us.domain.nav.Phase
import com.syconx.us2us.domain.nav.SettingsPage
import com.syconx.us2us.domain.nav.SettingsPicker
import com.syconx.us2us.domain.nav.StartScreen
import com.syconx.us2us.fixtures.FixturesMessengerService
import com.syconx.us2us.ui.components.CodeDotsAppearance
import com.syconx.us2us.ui.env.PreviewAppModel
import java.io.File
import java.net.URI
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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

private fun service(scope: CoroutineScope? = null): FixturesMessengerService =
    if (scope == null) {
        FixturesMessengerService(hasAccount = true, clock = DesignClock.frozen(DesignClock.referenceDate), latency = Duration.ZERO)
    } else {
        FixturesMessengerService(hasAccount = true, clock = DesignClock.frozen(DesignClock.referenceDate), latency = Duration.ZERO, scope = scope)
    }

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settle(time: Duration = 1.seconds) {
    advanceTimeBy(time)
    runCurrent()
}

/** Records what the model hands to the clipboard. */
private class RecordingClipboard : SensitiveClipboard {
    var text: String? = null
    var lifetime: Duration? = null
    var copies = 0

    override fun copy(text: String, lifetime: Duration): Boolean {
        copies += 1
        this.text = text
        this.lifetime = lifetime
        return true
    }
}

/** 07 · Backup export flow (7.2): iOS BackupExportModelTests. */
class BackupExportModelTest {
    private val demo = DesignFixtures.DEMO_EXPORT_PASSWORD
    private val generated = "k7mq-x2pd-9tfa-wn3h-b8ze"

    private fun TestScope.model(clipboard: SensitiveClipboard = RecordingClipboard(), messenger: FixturesMessengerService = service()) =
        BackupExportModel(messenger, backgroundScope, clipboard) to messenger

    @Test
    fun emptyPasswordCannotSubmit() = runTest {
        val (model, _) = model()
        assertFalse(model.canSubmit)
    }

    @Test
    fun theBackupIsTheAccountOnly() {
        assertFalse(BackupExportModel.CONTENTS.messages)
        assertFalse(BackupExportModel.CONTENTS.includesMedia)
        assertTrue(BackupExportModel.CONTENTS.settingsAndContacts)
    }

    /** Every §5.5 rule is shown before the confirmation field appears; none is left to fail silently at export. */
    @Test
    fun protocolRulesAreShown() = runTest {
        val cases = listOf(
            "kedi12" to BackupExportModel.Problem.TooShort,
            "Ab3\$xY7!q" to BackupExportModel.Problem.TooShort,
            "cumhuriyet1923" to BackupExportModel.Problem.TooCommon,
            "aaaaaaaaaaaa" to BackupExportModel.Problem.RepeatedCharacter,
            "abcdefghij" to BackupExportModel.Problem.Sequence,
            "poiuytrewq" to BackupExportModel.Problem.Sequence,
        )
        for ((password, problem) in cases) {
            val (model, _) = model()
            model.password = password
            assertNull(model.submit("x.u2u"))
            assertEquals(password, problem, model.problem)
            assertTrue(password, model.problem?.concernsPassword == true)
            assertFalse(password, model.isConfirming)
        }
    }

    @Test
    fun weakPasswordIsRefused() = runTest {
        val (model, _) = model()
        model.password = "Ahmet1985!"
        assertNull(model.submit("x.u2u"))
        assertEquals(BackupExportModel.Problem.Weak, model.problem)
        assertFalse(model.isConfirming)
    }

    @Test
    fun everyListedCommonPasswordStopsBeforeTheConfirmation() = runTest {
        for (password in CommonBackupPasswords.load()) {
            val (model, _) = model()
            model.password = password
            assertNull(password, model.submit("x.u2u"))
            assertNotNull(password, model.problem)
            assertFalse(password, model.isConfirming)
        }
    }

    @Test
    fun passwordIsAskedTwice() = runTest {
        val (model, backend) = model()
        model.password = demo
        assertNull(model.submit("x.u2u"))
        assertTrue(model.isConfirming)

        model.confirmation = "something else"
        assertNull(model.submit("x.u2u"))
        assertEquals(BackupExportModel.Problem.Mismatch, model.problem)

        model.confirmation = demo
        val uri = model.submit("us2us-test.u2u")
        assertNotNull(uri)
        assertEquals(BackupExportModel.Phase.Sharing, model.phase)
        assertTrue(File(uri!!).exists())
        model.finishSharing(uri, saved = true)
        assertFalse(File(uri).exists())
        assertEquals(DesignFixtures.referenceDate, backend.settings.value.lastBackupAt)
        assertTrue(model.password.isEmpty())
        assertEquals(BackupExportModel.Phase.Editing, model.phase)
    }

    @Test
    fun editingThePasswordClearsTheConfirmation() = runTest {
        val (model, _) = model()
        model.password = demo
        model.submit("x.u2u")
        model.confirmation = "abc"
        model.password += "!"
        assertTrue(model.confirmation.isEmpty())
    }

    /** The confirmation field stays up when the password changes, but the new password is checked again. */
    @Test
    fun aPasswordChangedAfterTheFirstStepIsCheckedAgain() = runTest {
        val (model, _) = model()
        model.password = demo
        model.submit("x.u2u")
        assertTrue(model.isConfirming)
        model.password = "Ahmet1985!"
        model.confirmation = "Ahmet1985!"
        assertNull(model.submit("x.u2u"))
        assertEquals(BackupExportModel.Problem.Weak, model.problem)
        assertEquals(BackupExportModel.Phase.Editing, model.phase)
    }

    @Test
    fun aFailedExportIsShown() = runTest {
        val (model, _) = model()
        model.password = demo
        model.submit("x.u2u")
        model.confirmation = demo
        // A cancelled export throws (the fixtures' encryption steps wait between steps).
        val export = async { model.submit("x.u2u") }
        runCurrent()
        export.cancel()
        runCatching { export.await() }
        assertEquals(BackupExportModel.Problem.ExportFailed, model.problem)
        assertEquals(BackupExportModel.Phase.Editing, model.phase)
        assertTrue(model.isConfirming)
    }

    @Test
    fun generatedPasswordFillsTheFieldAtItsExactStrength() = runTest {
        val (model, _) = model()
        model.generatePassword { generated }
        assertEquals(generated, model.password)
        assertTrue(model.showsGeneratedPassword)
        assertEquals(PasswordStrength.Generated, model.strength)
        assertEquals(PasswordStrength.Level.VeryStrong, model.strength.level)

        // The confirmation still has to be entered.
        assertNull(model.submit("x.u2u"))
        assertTrue(model.isConfirming)
        model.confirmation = generated
        val uri = model.submit("us2us-generated.u2u")
        assertNotNull(uri)
        model.finishSharing(uri!!, saved = true)
        assertFalse(model.showsGeneratedPassword)
    }

    @Test
    fun theRealGeneratorIsUsedByDefault() = runTest {
        val (model, _) = model()
        model.generatePassword()
        assertTrue(BackupPasswordRules.isGeneratedFormat(model.password))
        assertEquals(PasswordStrength.Generated, model.strength)
    }

    @Test
    fun editingTheGeneratedPasswordCountsItAsTyped() = runTest {
        val (model, _) = model()
        model.generatePassword { generated }
        model.password += "x"
        assertFalse(model.showsGeneratedPassword)
        assertEquals(PasswordStrength.estimate(generated + "x"), model.strength)
    }

    @Test
    fun copyIsSensitiveForAMinute() = runTest {
        val clipboard = RecordingClipboard()
        val (model, _) = model(clipboard)
        assertFalse(model.copyGeneratedPassword()) // nothing generated yet
        assertNull(clipboard.text)

        model.generatePassword { generated }
        assertTrue(model.copyGeneratedPassword())
        assertTrue(model.didCopy)
        assertEquals(generated, clipboard.text)
        assertEquals(60.seconds, clipboard.lifetime)

        // The confirmation goes with the copy.
        settle(61.seconds)
        assertFalse(model.didCopy)

        // Once the field holds something else, there is nothing to copy.
        assertTrue(model.copyGeneratedPassword())
        model.password = demo
        assertFalse(model.didCopy)
        assertFalse(model.copyGeneratedPassword())
    }

    @Test
    fun revealIsOnlyForTheGeneratedPasswordAndEndsWithTheForeground() = runTest {
        val (model, _) = model()
        model.password = demo
        model.toggleReveal()
        assertFalse(model.isRevealed)
        model.generatePassword { generated }
        model.toggleReveal()
        assertTrue(model.revealsGeneratedPassword)
        model.hidePassword()
        assertFalse(model.isRevealed)
        model.isScreenCaptured = true
        model.toggleReveal()
        assertFalse(model.revealsGeneratedPassword)
    }

    @Test
    fun harnessStates() = runTest {
        fun state(id: String) = BackupExportModel(service(), backgroundScope, RecordingClipboard()).also { it.applyHarness(id) }
        state("7.2").let {
            assertEquals(demo, it.password)
            assertTrue(it.highlightsPassword)
            assertEquals(PasswordStrength.Level.Strong, it.strength.level)
        }
        assertEquals(BackupExportModel.Problem.Weak, state("7.2-weak").problem)
        assertTrue(state("7.2-generated").showsGeneratedPassword)
        assertTrue(state("7.2-revealed").revealsGeneratedPassword)
        assertTrue(state("7.2-copied").didCopy)
        assertTrue(state("7.2-confirm").confirmationMatches)
        assertEquals(BackupExportModel.Problem.Mismatch, state("7.2-mismatch").problem)
        assertEquals(BackupExportModel.Phase.Exporting(0.4), state("7.2-exporting").phase)
        assertTrue(state("7.2-empty").password.isEmpty())
        assertEquals("••••-••••-••••-••••-••••", BackupExportModel.masked(generated))
    }
}

/** 07 · Code change: iOS ChangeCodeModelTests, and the steps around them. */
class ChangeCodeModelTest {
    private fun ChangeCodeModel.typeAll(digits: String) = digits.forEach { type(it.digitToInt()) }

    @Test
    fun wrongCurrentCodeCountsAsAnUnlockAttempt() = runTest {
        val messenger = service()
        val model = ChangeCodeModel(messenger, backgroundScope, onChanged = {})
        model.typeAll("135790")
        settle(400.milliseconds)
        assertEquals(1, messenger.unlockStatus.value.failures)
        assertEquals(ChangeCodeModel.Step.Current, model.step)
        assertEquals(CodeDotsAppearance.Error, model.appearance)
        assertTrue(model.message is ChangeCodeModel.Message.WrongPlain || model.message is ChangeCodeModel.Message.Wrong)
        // The dots clear after 0.8 s.
        settle(1.seconds)
        assertEquals("", model.entered)
        assertEquals(CodeDotsAppearance.Normal, model.appearance)
    }

    @Test
    fun fullChangeSetsTheNewCode() = runTest {
        val messenger = service()
        var changed = false
        val model = ChangeCodeModel(messenger, backgroundScope, onChanged = { changed = true })
        model.typeAll("246813")
        settle(400.milliseconds)
        assertEquals(ChangeCodeModel.Step.New, model.step)
        model.typeAll("471958")
        settle(400.milliseconds)
        assertEquals(ChangeCodeModel.Step.Confirm, model.step)
        model.typeAll("471958")
        settle(700.milliseconds)
        assertTrue(changed)
        assertEquals(UnlockAttemptResult.Unlocked, messenger.unlock("471958"))
    }

    @Test
    fun aWeakOrUnchangedNewCodeIsRefused() = runTest {
        val model = ChangeCodeModel(service(), backgroundScope, onChanged = {})
        model.typeAll("246813")
        settle(400.milliseconds)
        model.typeAll("123456")
        settle(400.milliseconds)
        assertEquals(ChangeCodeModel.Message.Weak, model.message)
        assertEquals(ChangeCodeModel.Step.New, model.step)
        settle(1.seconds)
        model.typeAll("246813")
        settle(400.milliseconds)
        assertEquals(ChangeCodeModel.Message.SameAsCurrent, model.message)
    }

    @Test
    fun aDifferentConfirmationGoesBackToTheNewCode() = runTest {
        val model = ChangeCodeModel(service(), backgroundScope, onChanged = {})
        model.typeAll("246813")
        settle(400.milliseconds)
        model.typeAll("471958")
        settle(400.milliseconds)
        model.typeAll("471959")
        settle(400.milliseconds)
        assertEquals(ChangeCodeModel.Message.Mismatch, model.message)
        assertEquals(ChangeCodeModel.Step.Confirm, model.step)
        settle(1.seconds)
        assertEquals(ChangeCodeModel.Step.New, model.step)
        assertEquals(ChangeCodeModel.Message.Mismatch, model.message)
    }

    @Test
    fun backFromTheConfirmationReturnsToTheNewCode() = runTest {
        val model = ChangeCodeModel(service(), backgroundScope, onChanged = {})
        assertFalse(model.back())
        model.applyHarness("code-confirm")
        assertTrue(model.back())
        assertEquals(ChangeCodeModel.Step.New, model.step)
    }

    @Test
    fun turningBiometricsOnTakesTheCodeOnce() = runTest {
        val messenger = service()
        messenger.updateSettings(AppSettings(faceIdEnabled = false))
        var changed = false
        val model = ChangeCodeModel(messenger, backgroundScope, purpose = ChangeCodeModel.Purpose.EnableBiometrics, onChanged = { changed = true })
        model.typeAll("246813")
        settle(400.milliseconds)
        assertTrue(changed)
        assertTrue(messenger.settings.value.faceIdEnabled)
        assertTrue(model.isBusy)
    }

    @Test
    fun anUnavailableVaultIsNotAWrongCode() = runTest {
        val messenger = service()
        messenger.nextUnlockResult = UnlockAttemptResult.VaultUnavailable
        val model = ChangeCodeModel(messenger, backgroundScope, onChanged = {})
        model.typeAll("246813")
        settle(400.milliseconds)
        assertEquals(ChangeCodeModel.Message.Unavailable, model.message)
        assertEquals(CodeDotsAppearance.Normal, model.appearance)
        assertEquals(0, messenger.unlockStatus.value.failures)
    }

    @Test
    fun theTenthWrongCodeWithTheWipeLeavesForTheLock() = runTest {
        val messenger = service()
        messenger.wipesAfterTenWrongCodes = true
        messenger.setWrongCodes(9)
        var wiped = false
        val model = ChangeCodeModel(messenger, backgroundScope, onChanged = {}, onWiped = { wiped = true })
        model.typeAll("135790")
        settle(400.milliseconds)
        assertTrue(wiped)
        assertNull(model.message)
    }

    @Test
    fun harnessStates() = runTest {
        fun state(id: String) = ChangeCodeModel(service(), backgroundScope, onChanged = {}).also { it.applyHarness(id) }
        state("code-wrong").let {
            assertEquals(6, it.entered.length)
            assertEquals(ChangeCodeModel.Message.Wrong(2, 60.0), it.message)
        }
        assertEquals(ChangeCodeModel.Step.New, state("code-new").step)
        assertEquals(ChangeCodeModel.Message.Weak, state("code-weak").message)
        assertEquals(ChangeCodeModel.Step.Confirm, state("code-confirm").step)
        assertEquals(ChangeCodeModel.Message.Mismatch, state("code-mismatch").message)
    }
}

/**
 * 07 · Account deletion (7.1 › Her şeyi sil): the account is deleted on the relay first, the phone is
 * erased only once the relay confirmed (or the user chose this phone only) and the sheet has gone.
 * iOS AccountDeletionTests.
 */
class AccountDeletionTest {
    private fun TestScope.app(): Pair<PreviewAppModel, FixturesMessengerService> {
        val service = service()
        return PreviewAppModel(service, clock = service.clock) to service
    }

    @Test
    fun theRelayDeletesTheAccountBeforeThePhoneIsErased() = runTest {
        val (app, service) = app()
        val erasure = EraseAccountModel(service, backgroundScope, pause = Duration.ZERO, armDelay = Duration.ZERO)
        erasure.present()
        assertTrue(erasure.isPresented)
        assertEquals(EraseAccountModel.Step.Confirm, erasure.step)

        erasure.deleteAccount()
        assertEquals(1, service.accountDeletionsOnRelay)
        assertEquals(EraseAccountModel.Erasure.AccountDeleted, erasure.erasure)
        assertFalse("the sheet goes down first", erasure.isPresented)
        assertTrue("the button keeps spinning while it goes", erasure.isWorking)
        assertNotNull("nothing is erased under the sheet", service.account.value)
        assertEquals(Phase.Unlocked, app.phase)

        erasure.sheetDidDismiss { app.eraseEverything() }
        assertNull(service.account.value)
        assertTrue(service.chats.value.isEmpty())
        assertEquals(Phase.Onboarding, app.phase)
        assertEquals(OnboardingEntry.Welcome, app.onboardingEntry)
        assertEquals(EraseAccountModel.Step.Confirm, erasure.step)
        assertFalse(erasure.isWorking)
        assertNull(erasure.erasure)
    }

    @Test
    fun aRefusedDeletionErasesNothingAndCanBeRetried() = runTest {
        val (app, service) = app()
        service.failsAccountDeletion = true
        val erasure = EraseAccountModel(service, backgroundScope, pause = Duration.ZERO, armDelay = 200.milliseconds)
        erasure.present()

        erasure.deleteAccount()
        assertEquals(EraseAccountModel.Step.Failed, erasure.step)
        assertTrue("the error shows in the same sheet", erasure.isPresented)
        assertFalse(erasure.isWorking)
        assertNull(erasure.erasure)
        assertEquals(1, erasure.failures)
        assertEquals(0, service.accountDeletionsOnRelay)
        assertNotNull("nothing was erased", service.account.value)
        assertTrue(service.chats.value.isNotEmpty())

        // A tap straight through the cross-fade does nothing.
        erasure.deleteAccount()
        erasure.eraseThisPhoneOnly()
        assertEquals(1, erasure.failures)
        assertNull(erasure.erasure)

        settle(300.milliseconds)
        assertTrue(erasure.isArmed)
        erasure.deleteAccount()
        assertEquals("still unreachable: still nothing erased", 2, erasure.failures)
        assertEquals(EraseAccountModel.Step.Failed, erasure.step)
        assertNotNull(service.account.value)

        service.failsAccountDeletion = false
        erasure.deleteAccount()
        assertEquals(1, service.accountDeletionsOnRelay)
        assertEquals(EraseAccountModel.Erasure.AccountDeleted, erasure.erasure)
        assertFalse(erasure.isPresented)
        erasure.sheetDidDismiss { app.eraseEverything() }
        assertNull(service.account.value)
        assertEquals(Phase.Onboarding, app.phase)
    }

    @Test
    fun thisPhoneOnlyLeavesTheAccountOnTheRelay() = runTest {
        val (app, service) = app()
        service.failsAccountDeletion = true
        val erasure = EraseAccountModel(service, backgroundScope, pause = Duration.ZERO, armDelay = Duration.ZERO)
        erasure.present()

        erasure.eraseThisPhoneOnly()
        assertNull("offered only once the relay has refused", erasure.erasure)
        assertTrue(erasure.isPresented)

        erasure.deleteAccount()
        settle(10.milliseconds)
        assertTrue(erasure.isArmed)
        erasure.eraseThisPhoneOnly()
        assertEquals(EraseAccountModel.Erasure.ThisPhoneOnly, erasure.erasure)
        assertFalse(erasure.isPresented)
        assertNotNull("nothing is erased under the sheet", service.account.value)

        erasure.sheetDidDismiss { app.eraseEverything() }
        assertNull(service.account.value)
        assertEquals("the account stays on the relay", 0, service.accountDeletionsOnRelay)
        assertEquals(Phase.Onboarding, app.phase)
    }

    @Test
    fun cancellingOrSwipingAwayErasesNothing() = runTest {
        val (app, service) = app()
        service.failsAccountDeletion = true
        val erasure = EraseAccountModel(service, backgroundScope, pause = Duration.ZERO, armDelay = Duration.ZERO)
        var erased = false

        erasure.present()
        erasure.cancel()
        assertFalse(erasure.isPresented)
        erasure.sheetDidDismiss { erased = true }

        // After a failure the sheet can be swiped away too; it starts over.
        erasure.present()
        erasure.deleteAccount()
        erasure.isPresented = false
        erasure.sheetDidDismiss { erased = true }
        assertEquals(EraseAccountModel.Step.Confirm, erasure.step)
        assertEquals(0, erasure.failures)

        assertFalse(erased)
        assertNotNull(service.account.value)
        assertEquals(0, service.accountDeletionsOnRelay)
        assertEquals(Phase.Unlocked, app.phase)
    }

    @Test
    fun theHarnessShowsTheFailureAtOnce() = runTest {
        val (_, service) = app()
        val erasure = EraseAccountModel(service, backgroundScope)
        erasure.present()
        erasure.showFailureForHarness()
        assertEquals(EraseAccountModel.Step.Failed, erasure.step)
        assertTrue(erasure.isArmed)
    }
}

/** 7.3: the restore steps, the honest copy's data, the harness (iOS RestoreHonestyTests' model side). */
class RestoreBackupModelTest {
    /** A document a picker handed over (the fixtures describe it as the design's file). */
    private val file = URI("content://documents/${DesignFixtures.backupEstimate.fileName}")

    @Test
    fun pickPasswordRestoreDone() = runTest {
        val messenger = service(backgroundScope)
        val model = RestoreBackupModel(messenger, backgroundScope)
        assertEquals(RestoreBackupModel.Stage.Pick, model.stage)
        model.pick(URI("content://documents/picked"))
        assertEquals(RestoreBackupModel.Stage.Password, model.stage)
        assertEquals(DesignFixtures.backupEstimate.fileName, model.file?.fileName)
        assertFalse(model.canSubmitPassword)

        model.password = "yanlis-parola"
        model.submitPassword()
        assertEquals(RestoreBackupModel.Stage.WrongPassword, model.stage)
        // Typing again leaves the error.
        model.password = DesignFixtures.DEMO_BACKUP_PASSWORD
        assertEquals(RestoreBackupModel.Stage.Password, model.stage)

        model.submitPassword()
        assertTrue(model.isRestoring)
        assertTrue(model.isPasswordAccepted)
        settle(20.seconds)
        val done = model.stage as RestoreBackupModel.Stage.Done
        assertEquals(1.0, done.progress.fraction, 0.0)
        assertEquals(done.progress.messagesTotal, done.progress.messagesDone)
        assertEquals(Duration.ZERO, done.progress.estimatedRemaining)
    }

    @Test
    fun aFileThatIsNotABackupIsNamedAsSuch() = runTest {
        val model = RestoreBackupModel(service(backgroundScope), backgroundScope)
        val notABackup = File.createTempFile("photo", ".jpg").apply { deleteOnExit() }
        model.pick(notABackup.toURI())
        assertEquals(RestoreBackupModel.Stage.Failed(BackupFileError.NotABackup), model.stage)
        assertNull(model.file)
    }

    @Test
    fun aDeadOrDamagedBackupIsRefused() = runTest {
        val messenger = service(backgroundScope)
        val model = RestoreBackupModel(messenger, backgroundScope)
        model.pick(file)
        messenger.nextBackupOpenResult = BackupOpenResult.NoLongerValid
        model.password = "whatever-it-is"
        model.submitPassword()
        assertEquals(RestoreBackupModel.Stage.Failed(BackupFileError.NoLongerValid), model.stage)
        assertNull(model.file)
        assertEquals("", model.password)

        model.pick(file)
        messenger.nextBackupOpenResult = BackupOpenResult.Unverified
        model.password = DesignFixtures.DEMO_BACKUP_PASSWORD
        model.submitPassword()
        assertEquals(RestoreBackupModel.Stage.Unverified, model.stage)
        assertTrue("an unverified check can be tried again", model.canSubmitPassword)
    }

    @Test
    fun cancelStopsTheRestore() = runTest {
        val messenger = service(backgroundScope)
        val model = RestoreBackupModel(messenger, backgroundScope)
        model.pick(file)
        model.password = DesignFixtures.DEMO_BACKUP_PASSWORD
        model.submitPassword()
        settle(1.seconds)
        val before = (model.stage as RestoreBackupModel.Stage.Restoring).progress.fraction
        model.cancelRestore()
        settle(20.seconds)
        val after = model.stage as RestoreBackupModel.Stage.Restoring
        assertEquals(before, after.progress.fraction, 0.02)
    }

    @Test
    fun theLiveRestoreListsOnlyTheVerifiedKeys() {
        val live = RestoreProgress(
            fraction = 1.0, stage = RestoreProgress.Stage.Keys, messagesDone = 0, messagesTotal = 0, mediaBytes = 0,
            estimatedRemaining = Duration.ZERO, includesHistory = false,
        )
        assertEquals(listOf(RestoreProgress.Stage.Keys), live.stages)
        assertEquals(RestoreProgress.Stage.entries, DesignFixtures.restoreProgress.stages)
    }

    @Test
    fun harnessStates() = runTest {
        fun state(id: String) = RestoreBackupModel(service(), backgroundScope).also { it.applyHarness(id) }
        assertEquals(RestoreBackupModel.Stage.Restoring(DesignFixtures.restoreProgress), state("7.3").stage)
        assertEquals(RestoreBackupModel.Stage.Pick, state("7.3-pick").stage)
        assertEquals(RestoreBackupModel.Stage.Password, state("7.3-password").stage)
        assertEquals(RestoreBackupModel.Stage.WrongPassword, state("7.3-wrong").stage)
        assertTrue(state("7.3-done").stage is RestoreBackupModel.Stage.Done)
    }
}

/** 07 · the harness plans, the restore-access check and the website links. */
class SettingsHarnessTest {
    @Test
    fun everyStatePushesItsScreenOver71() {
        val controls = service()
        for (state in SettingsHarness.states) {
            val plan = SettingsHarness.plan(null, state, controls)
            assertNotNull(state, plan)
            assertEquals(state, StartScreen.Chats, plan!!.start)
            assertEquals(state, AppRoute.Settings, plan.stack.first())
            assertEquals(state, state, plan.uiState?.state)
        }
        assertEquals(listOf(AppRoute.Settings, AppRoute.Picker(SettingsPicker.Theme)), SettingsHarness.plan(null, "theme", controls)?.stack)
        assertEquals(listOf(AppRoute.Settings, AppRoute.BackupExport), SettingsHarness.plan(null, "7.2-weak", controls)?.stack)
        assertEquals(listOf(AppRoute.Settings, AppRoute.Page(SettingsPage.Restore)), SettingsHarness.plan(null, "7.3-done", controls)?.stack)
        assertEquals(listOf(AppRoute.Settings, AppRoute.Page(SettingsPage.ChangeCode)), SettingsHarness.plan(null, "code-new", controls)?.stack)
        assertEquals(listOf(AppRoute.Settings), SettingsHarness.plan(null, "erase-failed", controls)?.stack)
        assertNull(SettingsHarness.plan(null, null, controls))
        assertEquals(LaunchRoute.Feature.Settings, settingsHarnesses.single().feature)
    }

    @Test
    fun aStateIsTakenOnce() {
        val ui = SettingsUiState("7.2-weak")
        assertNull(ui.take("7.3"))
        assertNull(ui.take("7"))
        assertEquals("7.2-weak", ui.take("7.2"))
        assertNull(ui.take("7.2"))
        assertEquals("code", SettingsUiState("code").take("code"))
    }

    @Test
    fun theHarnessPreparesTheService() = runTest {
        val controls = service()
        SettingsHarness.plan(null, "7.1-access-unanswered", controls)!!.prepare!!.invoke()
        assertTrue(controls.restoreAccessPending.value)
        assertEquals(RestoreAccessCheck.Unanswered, controls.restoreAccessAnswer)
        SettingsHarness.plan(null, "erase-failed", controls)!!.prepare!!.invoke()
        assertTrue(controls.failsAccountDeletion)
    }

    @Test
    fun theRestoreAccessCheckSaysWhatItFound() = runTest {
        val messenger = service()
        messenger.setRestoreAccessPending(true)
        val model = RestoreAccessModel(messenger)
        messenger.restoreAccessAnswer = RestoreAccessCheck.Unanswered
        assertEquals(RestoreAccessCheck.Unanswered, model.check())
        assertTrue(model.lastCheckUnanswered)
        assertTrue(model.isShown.value)
        messenger.restoreAccessAnswer = RestoreAccessCheck.Closed
        assertEquals(RestoreAccessCheck.Closed, model.check())
        assertFalse(model.lastCheckUnanswered)
        assertFalse(model.isShown.value)
        model.applyHarness(checking = true, unanswered = true)
        model.reset()
        assertFalse(model.isChecking)
        assertFalse(model.lastCheckUnanswered)
    }

    @Test
    fun theWebsitePagesOpenInTheAppsLanguage() {
        assertEquals("https://us2us.app/tr/gizlilik", WebsitePage.Privacy.url(Locale.forLanguageTag("tr")))
        assertEquals("https://us2us.app/en/privacy", WebsitePage.Privacy.url(Locale.ENGLISH))
        assertEquals("https://us2us.app/tr/destek", WebsitePage.Support.url(Locale.forLanguageTag("tr")))
        assertEquals("https://us2us.app/en/support", WebsitePage.Support.url(Locale.GERMAN))
    }

    @Test
    fun theLicencesAreTheOnesShipped() {
        assertEquals(listOf("doto", "martian", "inter", "phosphor", "libsignal"), Licence.all.map { it.id })
        val assets = listOf(File("src/main/assets/licenses"), File("../../designsystem/src/main/assets/licenses"))
        for (licence in Licence.all) {
            assertTrue(licence.resource, assets.any { File(it, "${licence.resource}.txt").isFile })
        }
    }
}
