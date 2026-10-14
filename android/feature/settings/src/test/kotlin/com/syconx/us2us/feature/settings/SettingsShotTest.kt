package com.syconx.us2us.feature.settings

import com.syconx.us2us.domain.RestoreProgress
import com.syconx.us2us.shot.Frame
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.measuredFrame
import com.syconx.us2us.shot.ShotTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The drawn screens of 07 · Ayarlar against `ios/design/reference` and the layout JSON: 7.1, 7.2 and
 * 7.3, dark and light. The frames are the gate (±1 dp) where the iOS app still draws what the design
 * drew; `scripts/compare_shots.py` diffs the pictures for review.
 *
 * The iOS app has moved on from the drawing in two places, and the Android port follows the app:
 * 7.1's Gizlilik card has a third row (“Aramada göster”) and a footnote, so everything below it sits
 * lower; 7.2's backup is the account only (three rows with checks instead of switches, a different
 * footnote), so everything below “Yedeğe girecekler” sits lower.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class SettingsShotTest {
    @get:Rule
    val host = ShotHost()

    @Test
    fun settings() {
        for (theme in ShotTheme.entries) {
            host.shoot("7.1-ayarlar", theme, "tr") { SettingsApp("settings:7.1") }
            host.assertFrame(SettingsTags.BACK, element = "r.0.0")
            host.assertFrame(SettingsTags.TITLE, element = "r.1")
            host.assertFrame(SettingsTags.PROFILE, element = "r.2.0")
            host.assertFrame(SettingsTags.AVATAR, element = "r.2.0.0")
            host.assertFrame(SettingsTags.QR, element = "r.2.0.2")
            host.assertFrame(SettingsTags.PRIVACY_LABEL, element = "r.2.1.0")
            host.assertFrame(SettingsTags.picker("presence"), element = "r.2.1.1.0")
            host.assertFrame(SettingsTags.PRIVACY_SEPARATOR, element = "r.2.1.1.1")
            host.assertFrame(SettingsTags.READ_RECEIPTS, element = "r.2.1.1.2")
        }
    }

    @Test
    fun backupExport() {
        for (theme in ShotTheme.entries) {
            host.shoot("7.2-yedegi-disa-aktar", theme, "tr") { SettingsApp("settings:7.2") }
            host.assertFrame(BackupTags.BACK, element = "r.0.0")
            host.assertFrame(BackupTags.FILE, element = "r.1.0")
            host.assertFrame(BackupTags.CONTENTS_LABEL, element = "r.1.1.0")
            host.assertFrame(BackupTags.EXPORT, element = "r.3.0")
        }
    }

    @Test
    fun restore() {
        for (theme in ShotTheme.entries) {
            host.shoot("7.3-yedekten-geri-yukle", theme, "tr") { SettingsApp("settings:7.3") }
            host.assertFrame(RestoreTags.CLOSE, element = "r.0.0")
            host.assertFrame(RestoreTags.FILE, element = "r.1.0")
            host.assertFrame(RestoreTags.FILE_OK, element = "r.1.0.2")
            host.assertFrame(RestoreTags.PASSWORD, element = "r.1.1")
            host.assertFrame(RestoreTags.CAPTION, element = "r.1.2")
            host.assertFrame(RestoreTags.PERCENT_ROW, element = "r.2.0")
            host.assertFrame(RestoreTags.METER, element = "r.2.1")
            host.assertFrame(RestoreTags.step(RestoreProgress.Stage.Keys), element = "r.2.2.0")
            host.assertFrame(RestoreTags.step(RestoreProgress.Stage.Messages), element = "r.2.2.1")
            host.assertFrame(RestoreTags.step(RestoreProgress.Stage.Media), element = "r.2.2.2")
            host.assertFrame(RestoreTags.NOTE, element = "r.3.0")
            host.assertFrame(RestoreTags.ACTION_CANCEL, element = "r.3.1")
        }
    }

    /** The code change's steps sit where 3.2 (current code) and 2.2 (new code) draw theirs. */
    @Test
    fun codeChangeFollowsTheLockAndSetupLayouts() {
        for (theme in ShotTheme.entries) {
            host.shoot("settings_code", theme, "tr") { SettingsApp("settings:code") }
            host.useLayout("3.2-kod-yanlis")
            host.assertFrame(ChangeCodeTags.BADGE, element = "r.0.0")
            host.assertFrame(ChangeCodeTags.DOTS, element = "r.1.0")

            host.shoot("settings_code-new", theme, "tr") { SettingsApp("settings:code-new") }
            val layout = host.useLayout("2.2-gizli-kod")
            host.assertFrame(ChangeCodeTags.TITLE, element = "r.1.0")
            assertNear(ChangeCodeTags.DOTS, union(layout["r.2.0"].frame, layout["r.2.5"].frame))
            assertNear(ChangeCodeTags.KEYPAD, union(layout["r.4.0"].frame, layout["r.4.11"].frame))
        }
    }

    private fun union(a: Frame, b: Frame): Frame {
        val x = minOf(a.x, b.x)
        val y = minOf(a.y, b.y)
        return Frame(x, y, maxOf(a.right, b.right) - x, maxOf(a.bottom, b.bottom) - y)
    }

    private fun assertNear(tag: String, expected: Frame) {
        val actual = host.compose.measuredFrame(tag)
        assertTrue("$tag: expected [$expected], got [$actual]", expected.distanceTo(actual) <= 1f)
    }
}
