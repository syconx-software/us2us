package com.syconx.us2us.feature.conversation

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The conversation's interactions on the fixtures: long press → 5.3 → react, reply, send, resend. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class ConversationInteractionTest {
    private var rig = ConversationRig()

    @get:Rule
    val host = ShotHost(environment = conversationShots { rig })

    private val deniz = ChatId("deniz")

    private fun messages() = rig.fixtures.messages(deniz).value

    private fun exists(tag: String) = host.compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Long press on a bubble, then the time the press and the lift take. */
    private fun longPress(tag: String) {
        host.compose.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput { longClick() }
        host.advance(1_000)
    }

    @Test
    fun longPressOpensActionsAndReacts() {
        host.render(ShotTheme.Dark, "tr") { ConversationScreen(deniz) }
        assertFalse(exists("actions.overlay"))
        longPress("message.d5")
        assertTrue("the 5.3 overlay opens on a long press", exists("actions.overlay"))
        assertTrue(exists("actions.reactions"))
        host.compose.onNodeWithTag("reaction.❤️", useUnmergedTree = true).performClick()
        host.advance(1_000)
        assertFalse("the overlay closes after a reaction", exists("actions.overlay"))
        val reactions = messages().first { it.id == MessageId("d5") }.reactions
        assertTrue("❤️ is the user's reaction on d5: $reactions", reactions.any { it.emoji == "❤️" && it.includesMe })
        assertTrue("the chip shows under the bubble", host.compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("❤️", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun replyThenSend() {
        host.render(ShotTheme.Dark, "tr") { ConversationScreen(deniz) }
        longPress("message.d1")
        host.compose.onNodeWithTag("menu.reply", useUnmergedTree = true).performClick()
        host.advance(1_000)
        assertFalse(exists("actions.overlay"))
        host.compose.onNodeWithTag("composer.input", useUnmergedTree = true).performTextInput("Evet, boşum")
        host.advance(500)
        host.compose.onNodeWithTag("composer.action", useUnmergedTree = true).performClick()
        host.advance(1_000)
        val sent = messages().last()
        assertTrue(sent.isOutgoing)
        assertEquals(MessageContent.Text("Evet, boşum"), sent.content)
        assertEquals("the reply quotes d1", MessageId("d1"), sent.replyTo?.messageId)
        assertEquals("deniz", sent.replyTo?.author)
        // The new row arrives at the bottom of the column with its sentence; the composer is empty again.
        assertTrue(exists("message.${sent.id}"))
        assertTrue(exists("message.${sent.id}.status"))
        host.compose.onNodeWithText("Mesaj", useUnmergedTree = true).assertExists()
    }

    @Test
    fun sendText() {
        host.render(ShotTheme.Dark, "tr") { ConversationScreen(deniz) }
        val before = messages().size
        host.compose.onNodeWithTag("composer.input", useUnmergedTree = true).performTextInput("Görüşürüz 👋")
        host.advance(300)
        host.compose.onNodeWithTag("composer.action", useUnmergedTree = true).performClick()
        host.advance(1_000)
        assertEquals(before + 1, messages().size)
        val sent = messages().last()
        assertEquals(MessageContent.Text("Görüşürüz 👋"), sent.content)
        assertNotNull(sent.status)
        // Only the newest outgoing message spells out its state: d6 gave its sentence to the new one.
        assertTrue(exists("message.${sent.id}.status"))
        host.advance(1_000)
        assertFalse(exists("message.d6.status"))
    }

    @Test
    fun resendFailedMessage() {
        rig = ConversationRig()
        rig.plan("deniz", "5.1-failed")
        host.render(ShotTheme.Dark, "tr") { ConversationScreen(deniz) }
        assertEquals(MessageStatus.Failed, messages().first { it.id == MessageId("d6") }.status)
        host.compose.onNodeWithText("Tekrar gönder", substring = true, useUnmergedTree = true).performClick()
        host.advance(1_000)
        assertTrue("nothing is failed after “Tekrar gönder”: ${messages().map { it.id to it.status }}", messages().none { it.status == MessageStatus.Failed })
        assertTrue("the message went again", messages().last().content == MessageContent.Text("Kapı kodu 4471"))
    }

    @Test
    fun deleteForEveryoneAsksFirst() {
        host.render(ShotTheme.Dark, "tr") { ConversationScreen(deniz) }
        longPress("message.d5")
        host.compose.onNodeWithTag("menu.deleteEveryone", useUnmergedTree = true).performClick()
        host.advance(1_000)
        assertTrue("the floating confirmation is up", exists("conversation.deleteEveryone.confirm"))
        assertTrue(messages().first { it.id == MessageId("d5") }.content is MessageContent.Text)
        host.compose.onNodeWithTag("conversation.deleteEveryone.confirm", useUnmergedTree = true).performClick()
        host.advance(1_500)
        assertEquals(MessageContent.Deleted, messages().first { it.id == MessageId("d5") }.content)
    }

    @Test
    fun ghostSettingsSave() {
        host.render(ShotTheme.Dark, "tr") { ConversationScreen(deniz) }
        host.compose.onNodeWithTag("conversation.ghost", useUnmergedTree = true).performClick()
        host.advance(1_000)
        assertTrue(exists("ghost.sheet"))
        host.compose.onNodeWithTag("ghost.period.SixHours", useUnmergedTree = true).performClick()
        host.compose.onNodeWithTag("ghost.save", useUnmergedTree = true).performClick()
        host.advance(2_000)
        assertFalse(exists("ghost.sheet"))
        val chat = checkNotNull(rig.fixtures.chat(deniz).value)
        assertEquals(com.syconx.us2us.domain.GhostPeriod.SixHours, chat.ghost.period)
    }

    @Test
    fun acceptingARequestBecomesTheConversation() {
        val request = com.syconx.us2us.domain.nav.RequestId("kuzey_dev")
        rig.model.router.setPath(listOf(com.syconx.us2us.domain.nav.AppRoute.RequestPreview(request)))
        host.render(ShotTheme.Dark, "tr") { RequestPreviewScreen(request) }
        host.compose.onNodeWithTag("preview.accept", useUnmergedTree = true).performClick()
        host.advance(1_500)
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        assertEquals(com.syconx.us2us.domain.nav.AppRoute.Conversation(ChatId("kuzey_dev")), rig.model.router.top)
        assertTrue(rig.fixtures.requests.value.none { it.id == request })
        assertNotNull(rig.fixtures.chat(ChatId("kuzey_dev")).value)
    }

    @Test
    fun blockingARequestAsksThenGoesBack() {
        val request = com.syconx.us2us.domain.nav.RequestId("kuzey_dev")
        rig.model.router.setPath(listOf(com.syconx.us2us.domain.nav.AppRoute.Requests, com.syconx.us2us.domain.nav.AppRoute.RequestPreview(request)))
        host.render(ShotTheme.Dark, "tr") { RequestPreviewScreen(request) }
        host.compose.onNodeWithTag("preview.block", useUnmergedTree = true).performClick()
        host.advance(1_000)
        assertTrue(exists("preview.block.confirm"))
        host.compose.onNodeWithTag("preview.block.confirm", useUnmergedTree = true).performClick()
        host.advance(1_500)
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        assertEquals(com.syconx.us2us.domain.nav.AppRoute.Requests, rig.model.router.top)
        assertTrue("blocked after the pop", rig.fixtures.requests.value.none { it.id == request })
    }

    /** Android, owner decision 0b: without notifications “Canlı konum” opens the share sheet first; sharing is never forced. */
    @Test
    fun liveLocationWithoutNotificationsSaysSoInTheShareSheet() {
        rig = ConversationRig()
        rig.plan("deniz", "5.1-attach")
        rig.fixtures.liveSharingNoticeOff = true
        host.render(ShotTheme.Dark, "tr") { ConversationScreen(deniz) }
        val before = messages().size
        host.compose.onNodeWithTag("menu.liveLocation", useUnmergedTree = true).performClick()
        host.advance(1_000)
        assertTrue(exists(LiveShareSheetTags.SHEET))
        assertFalse("the “+” menu made way", exists("attach.menu"))
        assertEquals("nothing is shared before the choice", before, messages().size)
        host.compose.onNodeWithText("Bildirimlere izin vermezsen uygulamadan çıkınca paylaşım durur.", useUnmergedTree = true).assertExists()
        host.compose.onNodeWithText("Bildirimlere izin ver", useUnmergedTree = true).assertExists()
        host.capture("conversation:deniz/5.1-live-share")

        host.compose.onNodeWithTag(LiveShareSheetTags.ANYWAY, useUnmergedTree = true).performClick()
        host.advance(1_000)
        assertFalse(exists(LiveShareSheetTags.SHEET))
        assertEquals(before + 1, messages().size)
        assertTrue("shared, in front only", messages().last().content is MessageContent.LiveLocation)
        assertTrue(rig.fixtures.liveSharingNoticeOff)
    }

    @Test
    fun theShareSheetAllowsNotificationsThenShares() {
        rig = ConversationRig(english = true)
        rig.plan("deniz", "5.1-attach")
        rig.fixtures.liveSharingNoticeOff = true
        host.render(ShotTheme.Light, "en") { ConversationScreen(deniz) }
        val before = messages().size
        host.compose.onNodeWithTag("menu.liveLocation", useUnmergedTree = true).performClick()
        host.advance(1_000)
        host.compose.onNodeWithText("Sharing stops when you leave the app unless notifications are allowed.", useUnmergedTree = true).assertExists()
        host.capture("conversation:deniz/5.1-live-share")
        host.compose.onNodeWithTag(LiveShareSheetTags.ALLOW, useUnmergedTree = true).performClick()
        host.advance(1_000)
        assertFalse(exists(LiveShareSheetTags.SHEET))
        assertFalse("allowed", rig.fixtures.liveSharingNoticeOff)
        assertEquals(before + 1, messages().size)
        assertTrue(messages().last().content is MessageContent.LiveLocation)
    }

    @Test
    fun withNotificationsLiveLocationSharesAtOnceAsOnIos() {
        rig = ConversationRig()
        rig.plan("deniz", "5.1-attach")
        host.render(ShotTheme.Dark, "tr") { ConversationScreen(deniz) }
        val before = messages().size
        host.compose.onNodeWithTag("menu.liveLocation", useUnmergedTree = true).performClick()
        host.advance(1_000)
        assertFalse(exists(LiveShareSheetTags.SHEET))
        assertEquals(before + 1, messages().size)
        assertTrue(messages().last().content is MessageContent.LiveLocation)
    }
}
