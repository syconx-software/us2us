package com.syconx.us2us.feature.chats

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.feature.chats.invite.InviteTags
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotPaths
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.common.MyCodeTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every harness state of 04 · Sohbetler that this module draws (the 4.4 states are the request
 * preview's), the `newChat` route and the invite sheet, dark and light, named by their launch route
 * as the iOS captures are (`chats_4.2-notfound`), for `compare_shots.py` against
 * `android/build/ios-reference/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class ChatsStatesShotTest {
    @get:Rule
    val host = ShotHost()

    private fun shootAll(routes: List<String>, check: (String) -> Unit = {}) {
        for (route in routes) {
            for (theme in ShotTheme.entries) {
                val file = host.shoot(ShotPaths.routeName(route), theme, "tr") { ChatsApp(route) }
                assertTrue("$route was not written", file.isFile)
                check(route)
            }
        }
    }

    @Test
    fun chatListStates() {
        shootAll(listOf("chats:4.1-connecting", "chats:4.1-closed", "chats:4.1-empty", "chats:4.1-qr", "chats:4.1-mycode", "chats:4.1-blocked")) { route ->
            when (route) {
                "chats:4.1-empty" -> host.compose.onNodeWithTag(ChatsTags.EMPTY_NEW).assertIsDisplayed()
                "chats:4.1-qr" -> host.compose.onNodeWithTag("menu.myCode").assertIsDisplayed()
                "chats:4.1-mycode" -> host.compose.onNodeWithTag(MyCodeTags.SHEET).assertIsDisplayed()
                // bora blocked in the service: “Engellendi” in the preview's place, nothing else changes.
                "chats:4.1-blocked" -> {
                    host.compose.onNode(hasTestTag(ChatsTags.chat(ChatId("bora"))) and hasAnyDescendant(hasText("Engellendi")), useUnmergedTree = true).assertIsDisplayed()
                    assertEquals(1, host.compose.onAllNodesWithText("Engellendi", useUnmergedTree = true).fetchSemanticsNodes().size)
                }
            }
        }
    }

    @Test
    fun newChatStates() {
        shootAll(
            listOf(
                "chats:4.2-keyboard", "chats:4.2-empty", "chats:4.2-searching", "chats:4.2-notfound", "chats:4.2-existing",
                "chats:4.2-self", "chats:4.2-mycode", "newChat:kaan",
            ),
        ) { route ->
            when (route) {
                "chats:4.2-notfound" -> host.compose.onNodeWithTag(NewChatTags.INVITE).assertIsDisplayed()
                "chats:4.2-mycode" -> host.compose.onNodeWithTag(MyCodeTags.SHEET).assertIsDisplayed()
                "chats:4.2-existing", "newChat:kaan" -> host.compose.onNodeWithTag(NewChatTags.WRITE).assertIsDisplayed()
            }
        }
        // The rules a name breaks are said without asking anyone.
        host.shoot(ShotPaths.routeName("newChat:ayşe"), ShotTheme.Dark, "tr") { ChatsApp("newChat:ayşe") }
        host.compose.onNodeWithTag(NewChatTags.INVITE).assertIsDisplayed()
    }

    @Test
    fun requestStates() {
        shootAll(listOf("chats:4.3-empty", "chats:4.3-block")) { route ->
            when (route) {
                "chats:4.3-empty" -> host.compose.onNodeWithTag(RequestsTags.EMPTY).assertIsDisplayed()
                "chats:4.3-block" -> {
                    host.compose.onNodeWithTag("confirm.destructive").assertIsDisplayed()
                    host.compose.onNodeWithTag("confirm.report").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun inviteStates() {
        shootAll(listOf("invite", "invite:edit", "invite:edited", "invite:copied", "invite:sent")) {
            host.compose.onNodeWithTag(InviteTags.SHEET).assertIsDisplayed()
        }
        shootAll(listOf("invite:link", "invite:link-unknown")) { route ->
            val tag = if (route == "invite:link") NewChatTags.WRITE else NewChatTags.INVITE
            host.compose.onNodeWithTag(tag).assertIsDisplayed()
        }
    }

    /** The review matrix (`UX_PHASE`): 4.1, the long-press menu and both deletion confirmations. */
    @Test
    fun uxScenes() {
        org.junit.Assume.assumeTrue(com.syconx.us2us.shot.UxMatrix.enabled)
        for (variant in com.syconx.us2us.shot.UxMatrix.variants) {
            fun show() { host.render(variant.theme, variant.lang, variant.options()) { ChatsApp("chats:4.1") } }
            fun press() {
                host.compose.onNodeWithTag(ChatsTags.chat(ChatId("deniz")), useUnmergedTree = true).performTouchInput { longClick() }
                host.advance(1_000)
            }
            show()
            host.captureUx("chats_list", variant)
            press()
            host.captureUx("chats_row_menu", variant)
            host.compose.onNodeWithTag("menu.${com.syconx.us2us.ui.common.ChatDeletionTags.DELETE_CHAT}", useUnmergedTree = true).performClick()
            host.advance(1_500)
            host.captureUx("chats_delete_chat", variant)
            show()
            press()
            host.compose.onNodeWithTag("menu.${com.syconx.us2us.ui.common.ChatDeletionTags.DELETE_CONTACT}", useUnmergedTree = true).performClick()
            host.advance(1_500)
            host.captureUx("chats_delete_contact", variant)
        }
    }
}
