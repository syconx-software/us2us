package com.syconx.us2us.feature.chats

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.syconx.us2us.domain.ReportReason
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.RequestId
import com.syconx.us2us.feature.report.ReportTags
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.ui.common.MyCodeTags
import com.syconx.us2us.ui.sheet.U2SheetTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 04 · Sohbetler driven like a user: push into a chat, the 4.2 sheet, triage, invite links, my code. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class ChatsInteractionTest {
    @get:Rule
    val host = ShotHost()

    private lateinit var fixture: ChatsFixture

    private fun open(route: String? = null) {
        host.render { ChatsApp(route, onFixture = { fixture = it }) }
    }

    private fun gone(tag: String): Boolean = host.compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()

    /** The lifecycle of the app's activity, as a mail app started from it moves it. */
    private fun appLifecycle(): LifecycleRegistry {
        val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()
        return (activity as ComponentActivity).lifecycle as LifecycleRegistry
    }

    @Test
    fun aRowPushesTheConversation() {
        open()
        host.compose.onNodeWithTag(ChatsTags.chat(ChatId("deniz"))).performClick()
        host.advance(1_000)
        assertEquals(AppRoute.Conversation(ChatId("deniz")), fixture.router.top)
        host.compose.onNodeWithTag(OTHER_SCREEN_TAG).assertIsDisplayed()
    }

    @Test
    fun composeOpensTheSheetAndMessageWritesInANewChat() {
        open()
        host.compose.onNodeWithTag(ChatsTags.NEW).performClick()
        host.advance(1_200)
        host.compose.onNodeWithTag(NewChatTags.BAR).assertIsDisplayed()
        host.compose.onNodeWithTag("newChat.field").performTextInput("kaan")
        host.advance(1_000)
        // kaan has a chat already: “Sohbete git”, the sheet goes down, then 5.1 is pushed.
        host.compose.onNodeWithTag(NewChatTags.WRITE).performClick()
        host.advance(2_000)
        assertTrue(gone(U2SheetTags.SHEET))
        assertEquals(AppRoute.Conversation(ChatId("kaan")), fixture.router.top)
    }

    @Test
    fun myCodeIsPushedInsideTheSheetAndCancelCloses() {
        open()
        host.compose.onNodeWithTag(ChatsTags.NEW).performClick()
        host.advance(1_200)
        host.compose.onNodeWithTag(NewChatTags.MY_CODE).performClick()
        host.advance(1_000)
        host.compose.onNodeWithTag(MyCodeTags.SHEET).assertIsDisplayed()
        assertTrue(gone(NewChatTags.BAR))
        host.compose.onNodeWithText("Geri").performClick()
        host.advance(1_000)
        host.compose.onNodeWithTag(NewChatTags.BAR).assertIsDisplayed()
        host.compose.onNodeWithText("Vazgeç").performClick()
        host.advance(1_500)
        assertTrue(gone(U2SheetTags.SHEET))
        assertTrue(fixture.router.isAtRoot)
    }

    @Test
    fun requestsAreTriaged() {
        open()
        host.compose.onNodeWithTag(ChatsTags.REQUESTS).performClick()
        host.advance(1_000)
        assertEquals(AppRoute.Requests, fixture.router.top)

        // Sil: silent, the card collapses.
        host.compose.onNodeWithTag(RequestsTags.delete(RequestId("n_aksoy"))).performClick()
        host.advance(1_000)
        assertTrue(gone(RequestsTags.container(RequestId("n_aksoy"))))
        assertEquals(listOf("kuzey_dev", "hediye_kazan"), fixture.service.requests.value.map { it.id.value })

        // Engelle asks first.
        host.compose.onNodeWithTag(RequestsTags.block(RequestId("hediye_kazan"))).performClick()
        host.advance(1_200)
        host.compose.onNodeWithTag("confirm.destructive").performClick()
        host.advance(1_500)
        assertTrue(gone(RequestsTags.container(RequestId("hediye_kazan"))))
        assertEquals(listOf("kuzey_dev"), fixture.service.requests.value.map { it.id.value })

        // Kabul et: 5.1 takes 4.3's place.
        host.compose.onNodeWithTag(RequestsTags.accept(RequestId("kuzey_dev"))).performClick()
        host.advance(1_500)
        assertEquals(listOf(AppRoute.Conversation(ChatId("kuzey_dev"))), fixture.router.path)
        assertEquals(ChatId("kuzey_dev"), fixture.service.chats.value.first().id)
    }

    /**
     * 4.3 › Engelle › Bildir: the report sheet rises once the confirmation has gone, with “Bu kişiyi
     * de engelle” on; handed to a mail app, the sender is blocked: the card collapses and the request
     * stays in 4.1 as a blocked chat.
     */
    @Test
    fun aReportFromTheBlockConfirmationBlocksOnceHandedToAMailApp() {
        open("chats:4.3")
        val kuzey = RequestId("kuzey_dev")
        host.compose.onNodeWithTag(RequestsTags.block(kuzey)).performClick()
        host.advance(1_200)
        host.compose.onNodeWithTag("confirm.report").performClick()
        host.advance(1_500)
        host.compose.onNodeWithTag(ReportTags.SHEET).assertIsDisplayed()
        host.compose.onNodeWithText("kuzey_dev hakkındaki bildirimin e-postayla us2us ekibine gider. Ne olduğunu seç; istersen kısa bir not ekle.").assertIsDisplayed()
        host.compose.onNodeWithTag(ReportTags.ALSO_BLOCK).assertIsOn()
        host.compose.onNodeWithTag(ReportTags.reason(ReportReason.Spam)).performClick()
        host.compose.onNodeWithTag(ReportTags.SEND).performClick()
        host.advance(300)
        // The mail app comes up over the app; later the user is back.
        val app = appLifecycle()
        app.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        app.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        host.advance(300)
        app.handleLifecycleEvent(Lifecycle.Event.ON_START)
        app.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        host.advance(1_500)
        val mail = checkNotNull(Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity)
        assertEquals(Intent.ACTION_SENDTO, mail.action)
        assertTrue(mail.dataString!!.startsWith("mailto:support@us2us.app?subject=us2us%20bildirimi%3A%20%40kuzey_dev"))
        assertTrue(gone(ReportTags.SHEET))
        assertTrue(gone(RequestsTags.container(kuzey)))
        assertTrue(fixture.service.requests.value.none { it.id == kuzey })
        assertTrue(fixture.service.chat(ChatId("kuzey_dev")).value?.peer?.isBlocked == true)
    }

    /**
     * Several mail apps and no default: the system asks which one (“Open with”, the app only pauses).
     * Dismissed, nothing was handed off: nobody is blocked and the report stays as it was.
     */
    @Test
    fun noMailAppPickedBlocksNobody() {
        open("chats:4.3")
        val kuzey = RequestId("kuzey_dev")
        host.compose.onNodeWithTag(RequestsTags.block(kuzey)).performClick()
        host.advance(1_200)
        host.compose.onNodeWithTag("confirm.report").performClick()
        host.advance(1_500)
        host.compose.onNodeWithTag(ReportTags.reason(ReportReason.Spam)).performClick()
        host.compose.onNodeWithTag(ReportTags.SEND).performClick()
        host.advance(300)
        val app = appLifecycle()
        app.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        host.advance(3_000)
        app.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        host.advance(1_000)
        host.compose.onNodeWithTag(ReportTags.SHEET).assertIsDisplayed()
        host.compose.onNodeWithTag(ReportTags.SEND).assertIsEnabled()
        host.compose.onNodeWithTag(ReportTags.ALSO_BLOCK).assertIsOn()
        assertTrue(fixture.service.requests.value.any { it.id == kuzey })
        assertFalse(fixture.service.chat(ChatId("kuzey_dev")).value?.peer?.isBlocked == true)
    }

    @Test
    fun aCancelledReportBlocksNobody() {
        open("chats:4.3")
        val kuzey = RequestId("kuzey_dev")
        host.compose.onNodeWithTag(RequestsTags.block(kuzey)).performClick()
        host.advance(1_200)
        host.compose.onNodeWithTag("confirm.report").performClick()
        host.advance(1_500)
        host.compose.onNodeWithTag(ReportTags.reason(ReportReason.Spam)).performClick()
        host.compose.onNodeWithText("Vazgeç").performClick()
        host.advance(1_500)
        assertTrue(gone(ReportTags.SHEET))
        assertTrue(fixture.service.requests.value.any { it.id == kuzey })
        host.compose.onNodeWithTag(RequestsTags.container(kuzey)).assertIsDisplayed()
    }

    @Test
    fun deleteAllEmptiesTheRequests() {
        open("chats:4.3")
        host.compose.onNodeWithTag(RequestsTags.MORE).performClick()
        host.advance(600)
        host.compose.onNodeWithTag("menu.deleteAll").performClick()
        host.advance(1_500)
        host.compose.onNodeWithTag("confirm.destructive").performClick()
        host.advance(1_500)
        assertTrue(fixture.service.requests.value.isEmpty())
        host.compose.onNodeWithTag(RequestsTags.EMPTY).assertIsDisplayed()
        assertTrue(gone(RequestsTags.MORE))
    }

    @Test
    fun anInviteLinkOpensTheSheetSearched() {
        open()
        host.compose.runOnIdle {
            assertTrue(InviteLinkInbox.handle("https://us2us.app/u/deniz"))
            // The paused test clock does not send snapshot changes made outside an event by itself.
            Snapshot.sendApplyNotifications()
        }
        host.advance(1_500)
        host.compose.onNodeWithTag(NewChatTags.WRITE).assertIsDisplayed()
        assertTrue(InviteLinkInbox.shared.pending == null)
        // Anything else is not an invite link.
        assertTrue(!InviteLinkInbox.handle("https://example.com/u/deniz"))
    }

    @Test
    fun qrMenuShowsMyCode() {
        open()
        host.compose.onNodeWithTag(ChatsTags.QR).performClick()
        host.advance(600)
        host.compose.onNodeWithTag("menu.myCode").performClick()
        host.advance(1_500)
        host.compose.onNodeWithTag(MyCodeTags.SHEET).assertIsDisplayed()
        host.compose.onNodeWithTag(MyCodeTags.SHARE).assertIsDisplayed()
    }
}
