package com.syconx.us2us.feature.onboarding

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 01–02 driven through the screens: the pages, the username rules, the code twice with a mismatch, and finishing. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class OnboardingInteractionTest {
    @get:Rule
    val host = ShotHost()

    private fun settle(millis: Long = 800) = host.advance(millis)

    private fun typeOnKeypad(code: String) {
        for (digit in code) {
            host.compose.onNodeWithContentDescription(digit.toString()).performClick()
            host.advance(16)
        }
    }

    private fun exists(tag: String): Boolean =
        host.compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theButtonPagesThroughTheWelcomeThenStartsSetup() {
        host.render(ShotTheme.Dark, "tr") { OnboardingApp() }
        host.compose.onNodeWithTag(OnboardingTags.START).performClick()
        settle()
        host.compose.onNodeWithTag(OnboardingTags.PAGE2_CTA).performClick()
        settle()
        assertTrue("the restore button leaves with 1.1", !exists(OnboardingTags.RESTORE))
        host.compose.onNodeWithTag(OnboardingTags.PAGE3_CTA).performClick()
        settle()
        assertTrue(exists(OnboardingTags.USERNAME_FIELD))
        host.compose.onNodeWithContentDescription("Adım 1 / 3").assertExists()
    }

    @Test
    fun aSwipePagesForwardAndBackAndAShortOneSettlesBack() {
        host.render(ShotTheme.Dark, "tr") { OnboardingApp() }
        host.compose.onRoot().performTouchInput { swipeLeft(startX = right * 0.9f, endX = left + width * 0.2f, durationMillis = 300) }
        settle()
        assertTrue(exists(OnboardingTags.PAGE2_CTA))
        host.compose.onRoot().performTouchInput { swipeLeft(startX = right * 0.9f, endX = left + width * 0.2f, durationMillis = 300) }
        settle()
        assertTrue(exists(OnboardingTags.PAGE3_CTA))
        // Past the last page the drag rubber-bands and settles back.
        host.compose.onRoot().performTouchInput { swipeLeft(startX = right * 0.9f, endX = left + width * 0.2f, durationMillis = 300) }
        settle()
        assertTrue(exists(OnboardingTags.PAGE3_CTA))
        // A short, slow drag does not commit.
        host.compose.onRoot().performTouchInput { swipeRight(startX = left + width * 0.3f, endX = left + width * 0.4f, durationMillis = 600) }
        settle()
        assertTrue(exists(OnboardingTags.PAGE3_CTA))
        host.compose.onRoot().performTouchInput { swipeRight(startX = left + width * 0.1f, endX = right * 0.9f, durationMillis = 300) }
        settle()
        assertTrue(exists(OnboardingTags.PAGE2_CTA))
    }

    @Test
    fun restoreHandsOffTo73() {
        host.render(ShotTheme.Dark, "tr") { OnboardingApp() }
        host.compose.onNodeWithTag(OnboardingTags.RESTORE).performClick()
        settle()
        assertTrue(exists(RESTORE_SCREEN_TAG))
    }

    /** Under reduce motion both bottom blocks are stacked: the hidden one's button must not take the tap. */
    @Test
    fun restoreHandsOffTo73UnderReduceMotion() {
        host.render(ShotTheme.Dark, "tr") { OnboardingApp(reduceMotion = true) }
        settle()
        host.compose.onNodeWithTag(OnboardingTags.RESTORE).performClick()
        settle()
        assertTrue(exists(RESTORE_SCREEN_TAG))
        assertTrue("still 1.1, not paged on", !exists(OnboardingTags.PAGE2_CTA))
    }

    @Test
    fun usernameRulesWhileTyping() {
        host.render(ShotTheme.Dark, "tr") { OnboardingApp("2.1-empty") }
        val field = host.compose.onNode(hasSetTextAction())
        host.compose.onNodeWithTag(OnboardingTags.USERNAME_CONTINUE).assertIsNotEnabled()

        field.performTextReplacement("Kağan")
        settle(500)
        host.compose.onNodeWithTag(OnboardingTags.USERNAME_STATUS, useUnmergedTree = true).assertTextEquals("Türkçe karakter kullanılamaz")
        host.compose.onNodeWithTag(OnboardingTags.USERNAME_COUNTER, useUnmergedTree = true).assertExists()

        field.performTextReplacement("1selin")
        settle(500)
        host.compose.onNodeWithTag(OnboardingTags.USERNAME_STATUS, useUnmergedTree = true).assertTextEquals("Harfle başlamalı")

        field.performTextReplacement("se")
        settle(300)
        assertTrue("too short is not said while typing", !host.compose.onAllNodesWithText("En az 3 karakter").fetchSemanticsNodes().any())
        settle(600)
        host.compose.onNodeWithTag(OnboardingTags.USERNAME_STATUS, useUnmergedTree = true).assertTextEquals("En az 3 karakter")

        field.performTextReplacement("selin_k")
        settle(1_000)
        host.compose.onNodeWithTag(OnboardingTags.USERNAME_STATUS, useUnmergedTree = true).assertTextEquals("Kullanılabilir")
        host.compose.onNodeWithTag(OnboardingTags.USERNAME_CONTINUE).assertIsEnabled()
        host.compose.onNodeWithText("@selin_k", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theCodeTwiceWithAMismatchThenFinish() {
        var fixture: OnboardingFixture? = null
        host.render(ShotTheme.Dark, "tr") { OnboardingApp("2.1-blurred", onFixture = { fixture = it }) }
        host.compose.onNodeWithTag(OnboardingTags.USERNAME_CONTINUE).performClick()
        settle()
        assertTrue(exists(OnboardingTags.KEYPAD))

        typeOnKeypad("246813")
        settle(400)
        host.compose.onNodeWithText("Kodu tekrar gir").assertExists()

        typeOnKeypad("246814")
        settle(400)
        host.compose.onNodeWithTag(OnboardingTags.CODE_PROBLEM, useUnmergedTree = true).assertTextEquals("Kodlar eşleşmedi. Baştan dene.")
        settle(1_000)
        host.compose.onNodeWithText("Gizli kodunu belirle").assertExists()

        typeOnKeypad("246813")
        settle(400)
        typeOnKeypad("246813")
        settle()
        host.compose.onNodeWithTag(OnboardingTags.FINISH).assertIsNotEnabled()
        host.compose.onNodeWithTag(OnboardingTags.ACKNOWLEDGE).performClick()
        settle(100)
        host.compose.onNodeWithTag(OnboardingTags.FINISH).assertIsEnabled().performClick()
        settle()
        val app = checkNotNull(fixture)
        assertEquals("selin_n", app.service.account.value?.username)
        assertTrue(app.model.calls.contains("didFinishOnboarding"))
    }
}
