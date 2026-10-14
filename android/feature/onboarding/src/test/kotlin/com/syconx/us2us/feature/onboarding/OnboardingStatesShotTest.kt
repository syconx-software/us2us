package com.syconx.us2us.feature.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every onboarding harness state the design does not draw, dark and light, named by route
 * (`onboarding_2.1-taken`) as the iOS captures are, for Q1's comparison with the simulator.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class OnboardingStatesShotTest {
    @get:Rule
    val host = ShotHost()

    private val drawn = setOf("1.1", "1.2", "1.3", "2.1", "2.2", "2.3")

    @Test
    fun everyState() {
        val states = OnboardingHarness.states.filter { !it.startsWith("demo") && it !in drawn }
        for (theme in ShotTheme.entries) {
            for (state in states) {
                host.shoot("onboarding:$state", theme, "tr") { OnboardingApp(state) }
            }
        }
    }

    @Test
    fun usernameStatesSayWhy() {
        host.render(ShotTheme.Dark, "tr") { OnboardingApp("2.1-turkish") }
        host.compose.onNodeWithTag(OnboardingTags.USERNAME_STATUS, useUnmergedTree = true).assertTextEquals("Türkçe karakter kullanılamaz")
        host.compose.onNodeWithTag(OnboardingTags.USERNAME_CONTINUE).assertIsNotEnabled()
        host.render(ShotTheme.Dark, "tr") { OnboardingApp("2.1-taken") }
        host.compose.onNodeWithTag(OnboardingTags.USERNAME_STATUS, useUnmergedTree = true).assertTextEquals("Alınmış")
        host.render(ShotTheme.Dark, "tr") { OnboardingApp("2.1-short") }
        host.compose.onNodeWithTag(OnboardingTags.USERNAME_COUNTER, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun restoreStatesCountTwoSteps() {
        host.render(ShotTheme.Dark, "tr") { OnboardingApp("restore-2.3-lock") }
        host.compose.onNodeWithTag(OnboardingTags.REPLACES_THIS_PHONE).assertIsDisplayed()
        host.compose.onNodeWithContentDescription("Adım 2 / 2").assertExists()
        host.render(ShotTheme.Dark, "tr") { OnboardingApp("restore-2.3-toLock") }
        host.compose.onNodeWithText("Kilit ekranına dön", useUnmergedTree = true).assertIsDisplayed()
        host.compose.onNodeWithTag(OnboardingTags.ERROR, useUnmergedTree = true).assertIsDisplayed()
    }
}
