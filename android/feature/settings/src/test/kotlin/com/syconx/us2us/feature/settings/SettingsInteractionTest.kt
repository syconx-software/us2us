package com.syconx.us2us.feature.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.syconx.us2us.domain.PresenceVisibility
import com.syconx.us2us.domain.ThemePreference
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.Phase
import com.syconx.us2us.domain.nav.SettingsPage
import com.syconx.us2us.domain.nav.SettingsPicker
import com.syconx.us2us.domain.UnlockAttemptResult
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.ui.l10n.AppLanguage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 07 · Ayarlar driven like a user: the theme and language pickers, the export, the erase, the code change and the restore. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class SettingsInteractionTest {
    @get:Rule
    val host = ShotHost()

    private lateinit var fixture: SettingsFixture
    private var activity: ComponentActivity? = null

    private fun open(route: String = "settings", pickBackup: Boolean = false) {
        host.render {
            activity = LocalContext.current as? ComponentActivity
            SettingsApp(route, pickBackup = pickBackup, onFixture = { fixture = it })
        }
    }

    /** Taps the control tagged [tag] (through its click action: rows below the fold need no scrolling). */
    private fun click(tag: String) {
        host.compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun type(fieldTag: String, text: String) {
        host.compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(fieldTag))).performTextInput(text)
    }

    private fun key(digit: Char) {
        host.compose.onNode(hasContentDescription(digit.toString()) and hasClickAction()).performClick()
    }

    /** Settles until [condition] holds: work on the fixtures' own threads (the backup's encryption) finishes in real time. */
    private fun settleUntil(limitMillis: Long = 15_000, condition: () -> Boolean) {
        var waited = 0L
        while (!condition() && waited < limitMillis) {
            host.settle(100)
            Thread.sleep(20)
            waited += 100
        }
        assertTrue("timed out after $limitMillis ms", condition())
    }

    @Test
    fun theThemeAppliesLive() {
        open()
        click(SettingsTags.picker(SettingsPicker.Theme.raw))
        host.settle(1_000)
        assertEquals(AppRoute.Picker(SettingsPicker.Theme), fixture.router.top)
        // Sistem · Koyu · Açık.
        click(SettingsTags.pickerOption(2))
        host.settle(600)
        assertEquals(ThemePreference.Light, fixture.model.preferences.theme)
        click(SettingsTags.PICKER_BACK)
        host.settle(1_000)
        assertEquals(AppRoute.Settings, fixture.router.top)
        // The row (below the fold) says it.
        host.compose.onNodeWithTag(SettingsTags.picker(SettingsPicker.Theme.raw)).assert(hasText("Açık", substring = true))
    }

    @Test
    fun theLanguageIsTheAppsOwn() {
        open()
        click(SettingsTags.picker(SettingsPicker.Language.raw))
        host.settle(1_000)
        // Sistem · Türkçe · English.
        click(SettingsTags.pickerOption(2))
        host.settle(600)
        assertEquals(AppLanguage.English, fixture.model.preferences.language)
        click(SettingsTags.pickerOption(0))
        host.settle(600)
        assertEquals(AppLanguage.System, fixture.model.preferences.language)
    }

    @Test
    fun pickersWriteTheSettings() {
        open()
        click(SettingsTags.picker(SettingsPicker.Presence.raw))
        host.settle(1_000)
        click(SettingsTags.pickerOption(1))
        host.settle(600)
        assertEquals(PresenceVisibility.Nobody, fixture.service.settings.value.presenceVisibility)
        click(SettingsTags.PICKER_BACK)
        host.settle(1_000)
        click(SettingsTags.READ_RECEIPTS)
        host.settle(600)
        assertEquals(false, fixture.service.settings.value.readReceipts)
    }

    @Test
    fun theBackupIsExportedToThePickedPlace() {
        open()
        click(SettingsTags.BACKUP)
        host.settle(1_000)
        assertEquals(AppRoute.BackupExport, fixture.router.top)

        type(BackupTags.PASSWORD, DesignFixtures.DEMO_EXPORT_PASSWORD)
        host.settle(400)
        click(BackupTags.EXPORT)
        host.settle(800)
        host.compose.onNodeWithTag(BackupTags.CONFIRM).assertIsDisplayed()
        type(BackupTags.CONFIRM, DesignFixtures.DEMO_EXPORT_PASSWORD)
        host.settle(400)
        click(BackupTags.EXPORT)

        // Encrypted on the device, then the system's “save as” picker.
        val shadow = shadowOf(checkNotNull(activity))
        settleUntil { shadow.peekNextStartedActivityForResult() != null }
        val started = shadow.nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, started.intent.action)
        assertEquals("us2us-yedek-2026-10-08.u2u", started.intent.getStringExtra(Intent.EXTRA_TITLE))

        val target = File.createTempFile("picked", ".u2u").apply { deleteOnExit() }
        shadow.receiveResult(started.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(target)))
        settleUntil { fixture.router.top == AppRoute.Settings }
        assertTrue("the backup was written where the user picked", target.length() > 0)
        assertEquals(DesignFixtures.referenceDate, fixture.service.settings.value.lastBackupAt)
        host.compose.onNodeWithText("Yedek dışa aktarıldı", substring = true).assertIsDisplayed()
    }

    @Test
    fun aCancelledSaveKeepsTheScreen() {
        open("settings:7.2-confirm")
        click(BackupTags.EXPORT)
        val shadow = shadowOf(checkNotNull(activity))
        settleUntil { shadow.peekNextStartedActivityForResult() != null }
        val started = shadow.nextStartedActivityForResult
        shadow.receiveResult(started.intent, Activity.RESULT_CANCELED, null)
        host.settle(1_000)
        assertEquals(AppRoute.BackupExport, fixture.router.top)
        assertNull(fixture.service.settings.value.lastBackupAt?.takeIf { it == DesignFixtures.referenceDate })
        host.compose.onNodeWithTag(BackupTags.EXPORT).assertIsDisplayed()
    }

    @Test
    fun eraseDeletesTheAccountThenThePhone() {
        open()
        click(SettingsTags.ERASE)
        host.settle(1_200)
        click(SettingsTags.CONFIRM_DESTRUCTIVE)
        host.settle(2_000)
        assertEquals(1, fixture.service.accountDeletionsOnRelay)
        assertTrue(fixture.model.calls.contains("eraseEverything"))
        assertNull(fixture.service.account.value)
        assertEquals(Phase.Onboarding, fixture.model.phase)
    }

    @Test
    fun aRefusedEraseOffersThisPhoneOnly() {
        open("settings:erase-failed")
        host.settle(500)
        host.compose.onNodeWithTag(SettingsTags.ERASE_THIS_PHONE).assertIsDisplayed()
        click(SettingsTags.ERASE_CANCEL)
        host.settle(1_500)
        assertTrue(host.gone(SettingsTags.ERASE_THIS_PHONE))
        assertNotNull(fixture.service.account.value)
        assertTrue(!fixture.model.calls.contains("eraseEverything"))
    }

    @Test
    fun theCodeIsChangedInThreeSteps() {
        open()
        click(SettingsTags.CHANGE_CODE)
        host.settle(1_000)
        "246813".forEach(::key)
        host.settle(800)
        "471958".forEach(::key)
        host.settle(800)
        "471958".forEach(::key)
        host.settle(1_500)
        assertEquals(AppRoute.Settings, fixture.router.top)
        host.compose.onNodeWithText("Gizli kodun değişti", substring = true).assertIsDisplayed()
        assertEquals(UnlockAttemptResult.Unlocked, runBlocking { fixture.service.unlock("471958") })
    }

    @Test
    fun aBackupIsRestoredFromThePickedFile() {
        open(pickBackup = true)
        host.compose.runOnIdle {
            fixture.router.push(AppRoute.Page(SettingsPage.Restore))
            Snapshot.sendApplyNotifications()
        }
        host.settle(1_500)
        host.compose.onNodeWithTag(RestoreTags.PASSWORD).assertIsDisplayed()
        type(RestoreTags.PASSWORD, DesignFixtures.DEMO_BACKUP_PASSWORD)
        host.settle(300)
        click(RestoreTags.ACTION_SUBMIT)
        host.settle(1_500)
        host.compose.onNodeWithTag(RestoreTags.ACTION_CANCEL).assertIsDisplayed()
        host.settle(10_000)
        click(RestoreTags.ACTION_CONTINUE)
        host.settle(1_000)
        assertEquals(AppRoute.Settings, fixture.router.top)
    }

    @Test
    fun aRestoreIsCancelledOnlyAfterAsking() {
        open("settings:7.3")
        click(RestoreTags.CLOSE)
        host.settle(1_200)
        host.compose.onNodeWithTag(RestoreTags.CANCEL_CONFIRM).assertIsDisplayed()
        click(RestoreTags.CANCEL_CONFIRM)
        host.settle(2_000)
        assertEquals(AppRoute.Settings, fixture.router.top)
    }

    @Test
    fun theRestoreAccessCheckSaysTheOutcome() {
        open("settings:7.1-access")
        host.compose.onNodeWithTag(SettingsTags.ACCESS).assertIsDisplayed()
        click(SettingsTags.ACCESS_CHECK)
        host.settle(1_500)
        assertTrue(host.gone(SettingsTags.ACCESS))
        host.compose.onNodeWithText("Eski telefonun", substring = true).assertExists()
    }

    @Test
    fun aboutOpensALicence() {
        open("settings:about")
        click(AboutTags.licence("phosphor"))
        host.settle(1_200)
        host.compose.onNodeWithTag(AboutTags.LICENCE_TEXT).assertIsDisplayed()
        host.compose.onNodeWithText("MIT License", substring = true).assertExists()
    }
}
