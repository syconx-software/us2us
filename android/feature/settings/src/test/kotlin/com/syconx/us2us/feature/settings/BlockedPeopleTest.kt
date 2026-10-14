package com.syconx.us2us.feature.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.SettingsPage
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotPaths
import com.syconx.us2us.shot.ShotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 7.1 › Gizlilik › Engellenenler (iOS `BlockedPeopleUITests` and the screen's states): the row's
 * count, both kinds of blocked people, “Engeli kaldır” without a confirmation, the empty state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class BlockedPeopleTest {
    @get:Rule
    val host = ShotHost()

    private lateinit var fixture: SettingsFixture

    private val bora = ChatId("bora")
    private val lale = ChatId("lale")

    private fun click(tag: String) {
        host.compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun shows(tag: String) {
        host.compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
    }

    /** What the 7.1 row says after its title (the count), as TalkBack reads the merged row. */
    private fun blockedRowText(): String {
        val node = host.compose.onNodeWithTag(SettingsTags.BLOCKED).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
    }

    @Test
    fun theStatesAreShotInBothThemes() {
        for (state in listOf("7.1-blocked", "blocked", "blocked-empty")) {
            val route = "settings:$state"
            for (theme in ShotTheme.entries) {
                val file = host.shoot(ShotPaths.routeName(route), theme, "tr") { SettingsApp(route) }
                assertTrue("$route was not written", file.isFile)
                when (state) {
                    "7.1-blocked" -> assertTrue(blockedRowText(), blockedRowText().contains("2"))
                    "blocked" -> {
                        shows(BlockedTags.LIST)
                        shows(BlockedTags.unblock(bora))
                        shows(BlockedTags.unblock(lale))
                        shows(BlockedTags.FOOTNOTE)
                    }
                    "blocked-empty" -> {
                        shows(BlockedTags.EMPTY)
                        host.compose.onNodeWithText("Engellediğin kimse yok.").assertIsDisplayed()
                    }
                }
            }
        }
        val file = host.shoot(ShotPaths.routeName("settings:blocked"), ShotTheme.Dark, "en") { SettingsApp("settings:blocked") }
        assertTrue(file.isFile)
        assertEquals(2, host.compose.onAllNodesWithText("Unblock").fetchSemanticsNodes().size)
    }

    @Test
    fun unblockingEveryoneFromSettingsEndsOnTheEmptyState() {
        host.render { SettingsApp("settings:7.1-blocked", onFixture = { fixture = it }) }
        host.settle(1_000)
        assertTrue(blockedRowText(), blockedRowText().contains("2"))

        click(SettingsTags.BLOCKED)
        host.settle(1_000)
        assertEquals(AppRoute.Page(SettingsPage.Blocked), fixture.router.top)
        shows(BlockedTags.unblock(bora))
        shows(BlockedTags.unblock(lale))
        assertFalse(host.has(BlockedTags.EMPTY))

        // No confirmation: the row leaves at once, the other stays.
        click(BlockedTags.unblock(lale))
        host.settle(1_000)
        assertTrue(host.gone(BlockedTags.unblock(lale)))
        assertTrue(host.has(BlockedTags.unblock(bora)))
        assertEquals(listOf(bora), fixture.service.blockedPeople.value.map { it.id })

        click(BlockedTags.unblock(bora))
        host.settle(1_000)
        shows(BlockedTags.EMPTY)
        assertTrue(host.gone(BlockedTags.LIST))

        // 7.1 counts nobody; bora's chat is back, not blocked; lale has no chat.
        click(BlockedTags.BACK)
        host.settle(1_000)
        assertFalse(blockedRowText(), blockedRowText().contains("2"))
        assertEquals(false, fixture.service.chat(bora).value?.peer?.isBlocked)
        assertEquals(null, fixture.service.chat(lale).value)
    }

    @Test
    fun someoneBlockedElsewhereIsListedAndCanBeUnblockedHere() {
        host.render { SettingsApp("settings", onFixture = { fixture = it }) }
        host.settle(800)
        kotlinx.coroutines.runBlocking { fixture.service.block(ChatId("deniz")) }
        host.settle(400)
        assertTrue(blockedRowText(), blockedRowText().contains("1"))
        click(SettingsTags.BLOCKED)
        host.settle(1_000)
        click(BlockedTags.unblock(ChatId("deniz")))
        host.settle(1_000)
        shows(BlockedTags.EMPTY)
        assertEquals(false, fixture.service.chat(ChatId("deniz")).value?.peer?.isBlocked)
    }
}
