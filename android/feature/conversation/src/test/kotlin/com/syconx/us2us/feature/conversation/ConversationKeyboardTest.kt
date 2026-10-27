package com.syconx.us2us.feature.conversation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class ConversationKeyboardTest {
    private val rig = ConversationRig(english = true)
    private val openedUrls = mutableListOf<String>()
    private val uriHandler = object : UriHandler { override fun openUri(uri: String) { openedUrls += uri } }
    @get:Rule val host = ShotHost(environment = conversationShots { rig })

    private fun focusDraft(): SemanticsNodeInteraction {
        host.render(ShotTheme.Dark, "en") {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) { ConversationScreen(ChatId("deniz")) }
        }
        host.advance(400)
        return host.compose.onNodeWithTag("composer.input").apply {
            performClick()
            performTextInput("Keep this draft")
            host.advance(200)
            assertIsFocused()
        }
    }

    @Test fun tappingAMessageDismissesFocusAndKeepsTheDraft() {
        val input = focusDraft()
        host.compose.onNodeWithTag("message.d5", useUnmergedTree = true).performTouchInput { click() }
        host.advance(300)
        input.assertIsNotFocused().assertTextContains("Keep this draft")
        input.performClick().assertIsFocused().assertTextContains("Keep this draft")
    }

    @Test fun tappingTheGutterDismissesFocus() {
        val input = focusDraft()
        host.compose.onNodeWithTag("conversation.list").performTouchInput { click(Offset(width - 4f, height * 0.4f)) }
        host.advance(300)
        input.assertIsNotFocused().assertTextContains("Keep this draft")
    }

    @Test fun draggingMessagesDismissesFocusWithoutOpeningActions() {
        val input = focusDraft()
        host.compose.onNodeWithTag("message.d5", useUnmergedTree = true).performTouchInput {
            swipe(center, center + Offset(0f, 240f), 500)
        }
        host.advance(400)
        input.assertIsNotFocused().assertTextContains("Keep this draft")
        host.compose.onNodeWithTag("actions.overlay").assertDoesNotExist()
    }

    @Test fun keyboardDismissalDoesNotConsumeWebLinks() {
        val address = "https://example.invalid"
        runBlocking { rig.fixtures.sendText(address, null, ChatId("deniz")) }
        val input = focusDraft()
        host.compose.onNodeWithText(address, useUnmergedTree = true).performTouchInput { click(center) }
        host.advance(300)
        assertEquals(listOf(address), openedUrls)
        input.assertIsNotFocused().assertTextContains("Keep this draft")
    }

    @Test fun keyboardDismissalDoesNotTurnLongPressIntoALinkTap() {
        val address = "https://example.invalid"
        runBlocking { rig.fixtures.sendText(address, null, ChatId("deniz")) }
        focusDraft()
        host.compose.onNodeWithText(address, useUnmergedTree = true).performTouchInput { longClick(center) }
        host.advance(500)
        host.compose.onNodeWithTag("actions.overlay").assertExists()
        assertTrue(openedUrls.isEmpty())
        // The actions overlay hides the underlying field's semantics.
    }

    @Test fun keyboardDismissalDoesNotConsumeCommunityInvitations() {
        val code = "us2us-community:v1:YWJj"
        runBlocking { rig.fixtures.sendText(code, null, ChatId("deniz")) }
        focusDraft()
        host.compose.onNodeWithText(code, useUnmergedTree = true).performTouchInput { click(center) }
        host.advance(400)
        host.compose.onNodeWithTag("messageInvite.close").assertExists()
        assertTrue(openedUrls.isEmpty())
    }

    @Test fun sendingDoesNotDismissTheKeyboard() {
        val input = focusDraft()
        host.compose.onNodeWithTag("composer.action").performTouchInput { click() }
        host.advance(400)
        input.assertIsFocused()
        input.performTextInput("next draft")
        host.advance(200)
        input.assertTextContains("next draft")
        host.compose.onNodeWithText("Keep this draft", useUnmergedTree = true).assertExists()
    }

    @Test fun programmaticScrollingKeepsTheComposerFocused() {
        val input = focusDraft()
        host.compose.onNodeWithTag("conversation.list").performScrollToIndex(3)
        host.advance(300)
        input.assertIsFocused().assertTextContains("Keep this draft")
    }
}
