package com.syconx.us2us.feature.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.syconx.us2us.feature.settings.avatar.AvatarEditorTags
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotPaths
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.common.MyCodeTags
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every other harness state of 07 · Ayarlar, dark and light, named by its launch route as the iOS
 * captures are (`settings_7.2-weak`), for `compare_shots.py` against `android/build/ios-reference/`;
 * each with a check that it shows what it should.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class SettingsStatesShotTest {
    @get:Rule
    val host = ShotHost()

    private fun shootAll(states: List<String>, lang: String = "tr", check: (String) -> Unit = {}) {
        for (state in states) {
            val route = "settings:$state"
            for (theme in ShotTheme.entries) {
                val file = host.shoot(ShotPaths.routeName(route), theme, lang) { SettingsApp(route) }
                assertTrue("$route was not written", file.isFile)
                check(state)
            }
        }
    }

    private fun shows(tag: String) {
        host.compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
    }

    /** The generated password's card reads the password (spelled out) only when it is shown. */
    private fun generated(revealed: Boolean) {
        val spelled = SemanticsMatcher("spells the password") {
            it.config.getOrNull(SemanticsProperties.StateDescription)?.startsWith("k 7 m q") == true
        }
        val node = host.compose.onNodeWithTag(BackupTags.GENERATED, useUnmergedTree = true)
        node.assertIsDisplayed()
        node.assert(if (revealed) spelled else !spelled)
    }

    @Test
    fun settingsStates() {
        shootAll(listOf("7.1-end", "7.1-access", "7.1-access-checking", "7.1-access-unanswered", "qr", "erase", "erase-failed", "avatar", "avatar-crop")) { state ->
            when (state) {
                "7.1-end" -> shows(SettingsTags.ERASE)
                "7.1-access" -> shows(SettingsTags.ACCESS)
                "7.1-access-checking" -> host.compose.onNodeWithText("Kontrol ediliyor…", substring = true).assertIsDisplayed()
                "7.1-access-unanswered" -> shows(SettingsTags.ACCESS_UNANSWERED)
                "qr" -> shows(MyCodeTags.SHEET)
                "erase" -> shows(SettingsTags.CONFIRM_DESTRUCTIVE)
                "erase-failed" -> shows(SettingsTags.ERASE_THIS_PHONE)
                "avatar", "avatar-crop" -> shows(AvatarEditorTags.SCREEN)
            }
        }
    }

    @Test
    fun pickers() {
        shootAll(listOf("presence", "autolock", "ghost", "disappearing", "theme", "language")) {
            shows(SettingsTags.PICKER_CARD)
            shows(SettingsTags.pickerOption(0))
        }
    }

    @Test
    fun backupStates() {
        shootAll(listOf("7.2-empty", "7.2-weak", "7.2-generated", "7.2-revealed", "7.2-copied", "7.2-confirm", "7.2-mismatch", "7.2-exporting")) { state ->
            when (state) {
                "7.2-weak", "7.2-mismatch" -> shows(BackupTags.PROBLEM)
                "7.2-generated" -> generated(revealed = false)
                "7.2-revealed" -> generated(revealed = true)
                // Below the fold, under the card.
                "7.2-copied" -> host.compose.onNodeWithTag(BackupTags.COPIED, useUnmergedTree = true).assertExists()
                "7.2-confirm" -> shows(BackupTags.CONFIRM)
                "7.2-exporting" -> host.compose.onNodeWithText("%40", substring = true).assertIsDisplayed()
            }
        }
    }

    @Test
    fun restoreStates() {
        shootAll(listOf("7.3-pick", "7.3-password", "7.3-wrong", "7.3-done", "7.3-cancel")) { state ->
            when (state) {
                "7.3-pick" -> shows(RestoreTags.PICK)
                "7.3-password" -> shows(RestoreTags.ACTION_SUBMIT)
                "7.3-wrong" -> host.compose.onNodeWithText("Parola yanlış", substring = true).assertIsDisplayed()
                "7.3-done" -> shows(RestoreTags.ACTION_CONTINUE)
                "7.3-cancel" -> shows(RestoreTags.CANCEL_CONFIRM)
            }
        }
    }

    @Test
    fun codeAndPages() {
        shootAll(listOf("code-wrong", "code-weak", "code-confirm", "code-mismatch", "faceid", "about")) { state ->
            when (state) {
                "code-wrong", "code-weak", "code-mismatch" -> shows(ChangeCodeTags.MESSAGE)
                "code-confirm" -> shows(ChangeCodeTags.TITLE)
                "faceid" -> shows(ChangeCodeTags.FACE_ID_BACK)
                "about" -> shows(AboutTags.HERO)
            }
        }
    }

    @Test
    fun englishDrawnScreens() {
        for (state in listOf("7.1", "7.2", "7.3")) {
            val route = "settings:$state"
            for (theme in ShotTheme.entries) {
                host.shoot(ShotPaths.routeName(route), theme, "en") { SettingsApp(route) }
            }
        }
        host.compose.onNodeWithText("Restore", substring = true).assertIsDisplayed()
    }
}
