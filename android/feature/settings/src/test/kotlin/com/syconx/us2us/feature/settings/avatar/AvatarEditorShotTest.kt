package com.syconx.us2us.feature.settings.avatar

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasContentDescription
import com.syconx.us2us.feature.settings.SettingsApp
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotPaths
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.brand.AvatarPickerTags
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * “Profil görseli” over 7.1 and the framing screen over it, as the iOS captures name them
 * (`settings_avatar`, `settings_avatar-crop`), in dark and light, Turkish and English.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class AvatarEditorShotTest {
    @get:Rule
    val host = ShotHost()

    private fun shows(tag: String) {
        host.compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun picker() {
        for (lang in listOf("tr", "en")) {
            for (theme in ShotTheme.entries) {
                val file = host.shoot(ShotPaths.routeName("settings:avatar"), theme, lang) { SettingsApp("settings:avatar") }
                assertTrue(file.isFile)
                shows(AvatarEditorTags.SCREEN)
                shows(AvatarPickerTags.preset(1))
                shows(AvatarPickerTags.FOOTNOTE)
                host.compose.onNode(hasContentDescription(if (lang == "tr") "Baş harfim kalsın" else "Keep my initial")).assertIsDisplayed()
            }
        }
    }

    @Test
    fun framing() {
        for (lang in listOf("tr", "en")) {
            for (theme in ShotTheme.entries) {
                host.shoot(ShotPaths.routeName("settings:avatar-crop"), theme, lang) { SettingsApp("settings:avatar-crop") }
                shows(AvatarCropTags.VIEWPORT)
                shows(AvatarCropTags.USE)
                shows(AvatarCropTags.SEARCH_NOTE)
            }
        }
    }
}
