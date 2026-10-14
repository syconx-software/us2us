package com.syconx.us2us.feature.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import com.syconx.us2us.domain.Biometry
import com.syconx.us2us.shot.ShotHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 7.1 › Güvenlik › “Biyometrik ile aç”: offered when the phone has an enrolled
 * strong biometric; without one only the code is used, and a switch that is still on stays until it
 * is turned off.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class BiometricSwitchTest {
    @get:Rule
    val host = ShotHost()

    private lateinit var fixture: SettingsFixture

    private fun open(biometry: Biometry) {
        host.render {
            SettingsApp("settings", onFixture = {
                fixture = it
                it.service.biometry = biometry
            })
        }
        host.settle(800)
    }

    private fun turnOff() {
        host.compose.onNodeWithTag(SettingsTags.FACE_ID, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        host.settle(800)
        assertEquals(false, fixture.service.settings.value.faceIdEnabled)
    }

    @Test
    fun withoutBiometricsTheSwitchLeavesOnceItIsOff() {
        open(Biometry.None)
        // The design's account has it on: it stays so it can be turned off.
        host.compose.onNodeWithTag(SettingsTags.FACE_ID).assertIsDisplayed()
        turnOff()
        assertTrue(host.gone(SettingsTags.FACE_ID))
    }

    @Test
    fun withBiometricsTheSwitchStays() {
        open(Biometry.Fingerprint)
        turnOff()
        host.compose.onNodeWithTag(SettingsTags.FACE_ID).assertIsDisplayed()
    }
}
