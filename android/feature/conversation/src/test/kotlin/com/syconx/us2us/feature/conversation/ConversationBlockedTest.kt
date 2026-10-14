package com.syconx.us2us.feature.conversation

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.syconx.us2us.domain.ReportReason
import com.syconx.us2us.domain.harness.HarnessStateId
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.domain.nav.RequestId
import com.syconx.us2us.feature.report.ReportTags
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Someone blocked (proposed): 5.1 read only (`conversation:deniz/5.1-blocked`: “Engellendi” in the
 * header, the dimmed Ghost chip, the bar with “Engeli kaldır”), 5.3 without reactions, Yanıtla or
 * Herkesten sil, a block while the chat is open, the unblock; and 4.4's “Bildir” (`chats:4.4-report`,
 * the “…” menu and the block confirmation).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class ConversationBlockedTest {
    private var rig = ConversationRig()

    @get:Rule
    val host = ShotHost(environment = conversationShots { rig })

    private val deniz = ChatId("deniz")

    private fun exists(tag: String) = host.compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** The lifecycle of the app's activity, as a mail app started from it moves it. */
    private fun appLifecycle(): LifecycleRegistry {
        val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()
        return (activity as ComponentActivity).lifecycle as LifecycleRegistry
    }

    private fun longPress(tag: String) {
        host.compose.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput { longClick() }
        host.advance(1_000)
    }

    @Test
    fun blockedConversation() {
        for (theme in ShotTheme.entries) {
            rig = ConversationRig()
            rig.plan("deniz", "5.1-blocked")
            host.shoot("conversation:deniz/5.1-blocked", theme, "tr") { ConversationScreen(deniz) }
            host.compose.onNodeWithTag(BlockedChatTags.BAR, useUnmergedTree = true).assertIsDisplayed()
            host.compose.onNodeWithTag("conversation.blockedStatus", useUnmergedTree = true).assertIsDisplayed()
            assertFalse("no composer", exists("composer"))
            // The bar sits where the composer was: on the bottom edge.
            val bar = host.compose.onAllNodes(hasTestTag(BlockedChatTags.BAR), useUnmergedTree = true).fetchSemanticsNodes().single().frame()
            assertEquals(874f, bar.y + bar.h, 0.5f)
        }
        rig = ConversationRig(english = true)
        rig.plan("deniz", "5.1-blocked")
        host.shoot("conversation:deniz/5.1-blocked", ShotTheme.Dark, "en") { ConversationScreen(deniz) }
        host.compose.onNode(hasText("Blocked"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun actionsOfferOnlyWhatStaysOnThisPhone() {
        rig.plan("deniz", "5.1-blocked")
        host.render(ShotTheme.Dark, "tr") { ConversationScreen(deniz) }
        longPress("message.d6")
        assertTrue(exists("actions.overlay"))
        assertFalse("no reactions for someone blocked", exists("actions.reactions"))
        assertFalse(exists("menu.reply"))
        assertFalse(exists("menu.deleteEveryone"))
        assertTrue(exists("menu.info"))
        assertTrue(exists("menu.copy"))
        assertTrue(exists("menu.deleteMe"))
        host.capture("conversation:deniz/5.3-blocked")
    }

    @Test
    fun theGhostChipCannotBeChanged() {
        rig.plan("deniz", "5.1-blocked")
        host.render(ShotTheme.Dark, "tr") { ConversationScreen(deniz) }
        host.compose.onNodeWithTag("conversation.ghost", useUnmergedTree = true).performClick()
        host.advance(1_000)
        assertFalse(exists("ghost.sheet"))
    }

    @Test
    fun unblockingBringsTheComposerBack() {
        rig.plan("deniz", "5.1-blocked")
        host.render(ShotTheme.Dark, "tr") { ConversationScreen(deniz) }
        host.compose.onNodeWithTag(BlockedChatTags.UNBLOCK, useUnmergedTree = true).performClick()
        host.advance(1_000)
        assertFalse(checkNotNull(rig.fixtures.chat(deniz).value).peer.isBlocked)
        assertTrue(exists("composer"))
        assertFalse(exists(BlockedChatTags.BAR))
        assertTrue(exists("chatHeader.status"))
    }

    /** “Engeli kaldır” tells TalkBack what it does (iOS `blocked.unblock.hint`), and its action unblocks. */
    @Test
    fun theUnblockButtonSaysWhatItDoes() {
        rig.plan("deniz", "5.1-blocked")
        host.render(ShotTheme.Dark, "tr") { ConversationScreen(deniz) }
        val unblock = host.compose.onNodeWithTag(BlockedChatTags.UNBLOCK, useUnmergedTree = true)
        assertEquals("Mesajlaşma yeniden açılır", unblock.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        unblock.performSemanticsAction(SemanticsActions.OnClick)
        host.advance(1_000)
        assertFalse(checkNotNull(rig.fixtures.chat(deniz).value).peer.isBlocked)
    }

    /** Blocked from elsewhere while the chat is open: the composer goes, with the reply quote and the “+” menu. */
    @Test
    fun aBlockWhileOpenTakesTheComposerAway() {
        host.render(ShotTheme.Dark, "tr") { ConversationScreen(deniz) }
        longPress("message.d1")
        host.compose.onNodeWithTag("menu.reply", useUnmergedTree = true).performClick()
        host.advance(1_000)
        assertTrue(exists("composer"))
        runBlocking { rig.fixtures.block(deniz) }
        Snapshot.sendApplyNotifications()
        host.advance(1_000)
        assertFalse(exists("composer"))
        assertTrue(exists(BlockedChatTags.BAR))
        // Nothing is sent to someone blocked, “Tekrar gönder” included.
        val count = rig.fixtures.messages(deniz).value.size
        runBlocking { rig.fixtures.sendText("Selam", null, deniz) }
        assertEquals(count, rig.fixtures.messages(deniz).value.size)
    }

    @Test
    fun resendDoesNothingWhileBlocked() {
        rig.plan("deniz", "5.1-failed")
        rig.fixtures.setBlocked(deniz)
        host.render(ShotTheme.Dark, "tr") { ConversationScreen(deniz) }
        val before = rig.fixtures.messages(deniz).value
        host.compose.onNode(hasText("Tekrar gönder", substring = true), useUnmergedTree = true).performClick()
        host.advance(1_000)
        assertEquals(before, rig.fixtures.messages(deniz).value)
    }

    // MARK: 4.4 · Bildir

    @Test
    fun requestReport() {
        for (theme in ShotTheme.entries) {
            rig = ConversationRig()
            rig.harness = HarnessStateId("4.4-report")
            host.shoot("chats:4.4-report", theme, "tr") { RequestPreviewScreen(RequestId("hediye_kazan")) }
            host.compose.onNodeWithTag(ReportTags.SHEET, useUnmergedTree = true).assertIsDisplayed()
            // hediye_kazan's name was not confirmed by the directory: the e-mail says so (ReportMailTest).
            host.compose.onNode(hasText("hediye_kazan hakkındaki bildirimin", substring = true), useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun theMenuOffersBildirFirst() {
        rig = ConversationRig()
        host.render(ShotTheme.Light, "tr") { RequestPreviewScreen(RequestId("kuzey_dev")) }
        host.compose.onNodeWithTag("preview.more", useUnmergedTree = true).performClick()
        host.advance(800)
        host.capture("chats:4.4-menu")
        val items = host.compose.onAllNodes(hasTestTag("menu.report") or hasTestTag("menu.block") or hasTestTag("menu.delete"), useUnmergedTree = true)
            .fetchSemanticsNodes().sortedBy { it.frame().y }.map { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" } }
        assertEquals(listOf("menu.report", "menu.block", "menu.delete"), items)
        host.compose.onNodeWithTag("menu.report", useUnmergedTree = true).performClick()
        host.advance(1_500)
        host.compose.onNodeWithTag(ReportTags.SHEET, useUnmergedTree = true).assertIsDisplayed()
        host.compose.onNodeWithTag(ReportTags.ALSO_BLOCK, useUnmergedTree = true).assertIsDisplayed()
    }

    /** Engelle › Bildir › handed to a mail app: the sender is blocked, and closing goes back to 4.3. */
    @Test
    fun aReportThatBlockedGoesBackToRequests() {
        val request = RequestId("kuzey_dev")
        rig.model.router.setPath(listOf(AppRoute.Requests, AppRoute.RequestPreview(request)))
        host.render(ShotTheme.Dark, "tr") { RequestPreviewScreen(request) }
        host.compose.onNodeWithTag("preview.block", useUnmergedTree = true).performClick()
        host.advance(1_000)
        assertTrue(exists("preview.block.report"))
        host.compose.onNodeWithTag("preview.block.report", useUnmergedTree = true).performClick()
        host.advance(300)
        assertFalse("the report rises once the confirmation has gone", exists(ReportTags.SHEET))
        host.advance(1_200)
        assertTrue(exists(ReportTags.SHEET))
        host.compose.onNodeWithTag(ReportTags.reason(ReportReason.Harassment), useUnmergedTree = true).performClick()
        host.compose.onNodeWithTag(ReportTags.SEND, useUnmergedTree = true).performClick()
        host.advance(300)
        // The mail app comes up over the app; later the user is back.
        val app = appLifecycle()
        app.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        app.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        host.advance(300)
        app.handleLifecycleEvent(Lifecycle.Event.ON_START)
        app.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        host.advance(2_000)
        Snapshot.sendApplyNotifications()
        val mail = checkNotNull(Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity)
        assertEquals(Intent.ACTION_SENDTO, mail.action)
        assertTrue(rig.fixtures.requests.value.none { it.id == request })
        assertTrue(rig.fixtures.chat(ChatId("kuzey_dev")).value?.peer?.isBlocked == true)
        assertEquals(AppRoute.Requests, rig.model.router.top)
    }

    /**
     * 4.4 › Engelle pops first and blocks 450 ms later. With animations off the stack drops 4.4 at
     * once (no exit to play): the block must still happen, not end with the screen.
     */
    @Test
    fun engelleBlocksEvenWhenThePreviewLeavesAtOnce() {
        val request = RequestId("kuzey_dev")
        rig.model.router.setPath(listOf(AppRoute.Requests, AppRoute.RequestPreview(request)))
        host.render(ShotTheme.Dark, "tr") {
            // As a stack without an exit animation shows it: gone with the pop.
            if (rig.model.router.top == AppRoute.RequestPreview(request)) RequestPreviewScreen(request)
        }
        host.compose.onNodeWithTag("preview.block", useUnmergedTree = true).performClick()
        host.advance(1_000)
        host.compose.onNodeWithTag("preview.block.confirm", useUnmergedTree = true).performClick()
        host.advance(100)
        assertFalse("4.4 has gone", exists("preview"))
        assertTrue("not before the card can be seen going", rig.fixtures.requests.value.any { it.id == request })
        host.advance(1_000)
        Snapshot.sendApplyNotifications()
        assertTrue(rig.fixtures.requests.value.none { it.id == request })
        assertTrue(rig.fixtures.chat(ChatId("kuzey_dev")).value?.peer?.isBlocked == true)
    }

    /** Cancelled, the report blocks nobody and the preview stays. */
    @Test
    fun aCancelledReportKeepsThePreview() {
        val request = RequestId("kuzey_dev")
        rig.model.router.setPath(listOf(AppRoute.Requests, AppRoute.RequestPreview(request)))
        host.render(ShotTheme.Dark, "tr") { RequestPreviewScreen(request) }
        host.compose.onNodeWithTag("preview.more", useUnmergedTree = true).performClick()
        host.advance(800)
        host.compose.onNodeWithTag("menu.report", useUnmergedTree = true).performClick()
        host.advance(1_500)
        host.compose.onNode(hasText("Vazgeç"), useUnmergedTree = true).performClick()
        host.advance(1_500)
        assertFalse(exists(ReportTags.SHEET))
        assertTrue(rig.fixtures.requests.value.any { it.id == request })
        assertEquals(AppRoute.RequestPreview(request), rig.model.router.top)
        assertNull(Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity)
        assertTrue(rig.fixtures.messages(ChatId("kuzey_dev")).value.isEmpty())
        assertFalse(rig.fixtures.chat(ChatId("kuzey_dev")).value?.peer?.isBlocked == true)
        assertEquals(MessageId("k1"), rig.fixtures.requests.value.first { it.id == request }.messages.first().id)
    }
}
