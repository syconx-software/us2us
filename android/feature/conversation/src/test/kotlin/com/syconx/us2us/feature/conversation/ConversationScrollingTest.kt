package com.syconx.us2us.feature.conversation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import com.syconx.us2us.domain.*
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.shot.ShotEnvironment
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.env.PreviewAppModel
import com.syconx.us2us.ui.env.U2PreviewRoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
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
class ConversationScrollingTest {
    private val rig = ConversationRig(english = true)
    private val chat = ChatId("deniz")
    private val messages = MutableStateFlow<List<Message>>(emptyList())
    private var reducedMotion = false
    private var historyCalls = 0
    private var historyLoader: (suspend () -> Boolean)? = null
    private val service = object : MessengerService by rig.fixtures {
        override fun messages(chat: ChatId): StateFlow<List<Message>> = messages
        override suspend fun loadOlderMessages(chat: ChatId): Boolean {
            historyCalls++
            return historyLoader?.invoke() ?: false
        }
    }
    @get:Rule val host = ShotHost(environment = ShotEnvironment { spec, content ->
        U2PreviewRoot(PreviewAppModel(service, clock = rig.fixtures.clock), dark = spec.isDark, designInsets = spec.designInsets, reduceMotion = reducedMotion) { content() }
    })

    private fun open(album: Boolean = false, older: Int = 120, theme: ShotTheme = ShotTheme.Dark): List<Message> {
        if (album) runBlocking {
            rig.fixtures.updateGhost(GhostState.Off, DisappearingTimer.Off, chat)
            rig.fixtures.sendPickedMedia(PickedMediaPlan.messages(DesignFixtures.pickedPhotos, null), chat)
        }
        val live = rig.fixtures.messages(chat).value
        val before = live.first().sentAt.minusSeconds(older.toLong() + 80)
        messages.value = (0..<older).map { index -> Message(MessageId("older-$index"), MessageDirection.Incoming,
            before.plusSeconds(index.toLong()), MessageContent.Text("Older message $index")) } + live
        host.render(theme, "en") { ConversationScreen(chat) }
        host.advance(600)
        return live
    }

    private fun bothDirections(id: MessageId, duration: Long = 200) {
        val node = host.compose.onNodeWithTag("message.$id", useUnmergedTree = true)
        node.assertIsDisplayed()
        val before = node.fetchSemanticsNode().boundsInRoot.center.y
        node.performTouchInput { swipe(center, center + Offset(0f, 240f), duration) }
        host.advance(500)
        val afterDown = node.fetchSemanticsNode().boundsInRoot.center.y
        assertTrue("the drag on $id must scroll toward older messages: $before -> $afterDown", afterDown > before + 60f)
        host.compose.onNodeWithTag("actions.overlay").assertDoesNotExist()
        node.performTouchInput { swipe(center, center - Offset(0f, 240f), duration) }
        host.advance(500)
        val afterUp = node.fetchSemanticsNode().boundsInRoot.center.y
        assertTrue("the reverse drag on $id must scroll back: $afterDown -> $afterUp", afterUp < afterDown - 60f)
    }

    @Test fun textBubbleDoesNotCaptureVerticalScrolling() { open(); bothDirections(MessageId("d5")) }
    @Test fun photoDoesNotCaptureVerticalScrolling() { open(); bothDirections(MessageId("d2")) }
    @Test fun albumPhotoDoesNotCaptureVerticalScrolling() { val live = open(album = true); bothDirections(live.takeLast(3).first().id) }
    @Test fun slowScrollingDoesNotLongPressText() { open(); bothDirections(MessageId("d5"), 1500) }
    @Test fun slowScrollingDoesNotLongPressPhoto() { open(); bothDirections(MessageId("d2"), 1500) }
    @Test fun slowScrollingDoesNotLongPressAlbum() { val live = open(album = true); bothDirections(live.takeLast(3).first().id, 1500) }

    private fun returnsToLatest(fromFarHistory: Boolean = false) {
        val latest = host.compose.onNodeWithTag("message.d6", useUnmergedTree = true)
        val rest = latest.fetchSemanticsNode().boundsInRoot.top
        val button = host.compose.onNodeWithTag("conversation.latest")
        button.assertDoesNotExist()
        val list = host.compose.onNodeWithTag("conversation.list")
        if (fromFarHistory) {
            list.performScrollToIndex(4500)
        } else list.performTouchInput {
            swipe(Offset(width * 0.4f, height * 0.35f), Offset(width * 0.4f, height * 0.7f), 600)
        }
        host.advance(500)
        button.assertIsDisplayed().assertHasClickAction()
        assertTrue("the button stays above the composer", button.fetchSemanticsNode().boundsInRoot.bottom <
            host.compose.onNodeWithTag("composer.input").fetchSemanticsNode().boundsInRoot.top)
        host.capture("conversation:deniz/latest-button")
        button.performTouchInput { click() }
        host.advance(if (reducedMotion) 200 else 1000)
        button.assertDoesNotExist()
        latest.assertIsDisplayed()
        org.junit.Assert.assertEquals(rest, latest.fetchSemanticsNode().boundsInRoot.top, 3f)
    }

    @Test fun olderPagesPreserveVisibleRowAndStopAtBeginning() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        historyLoader = {
            gate.await()
            if (historyCalls == 1) {
                val before = messages.value.first().sentAt.minusSeconds(101)
                val page = (0..<100).map { index -> Message(MessageId("page-$index"), MessageDirection.Incoming,
                    before.plusSeconds(index.toLong()), MessageContent.Text("Earlier page $index")) }
                messages.value = page + messages.value
                true
            } else false
        }
        open(older = 100)
        org.junit.Assert.assertEquals(0, historyCalls)
        val list = host.compose.onNodeWithTag("conversation.list")
        list.performScrollToIndex(98)
        host.advance(100)
        org.junit.Assert.assertEquals(1, historyCalls)
        val anchor = host.compose.onNodeWithTag("message.older-5", useUnmergedTree = true)
        anchor.assertIsDisplayed()
        val before = anchor.fetchSemanticsNode().boundsInRoot.top
        gate.complete(Unit)
        host.advance(400)
        org.junit.Assert.assertEquals(207, messages.value.size)
        anchor.assertIsDisplayed()
        org.junit.Assert.assertEquals(before, anchor.fetchSemanticsNode().boundsInRoot.top, 3f)
        list.performScrollToIndex(205)
        host.advance(300)
        org.junit.Assert.assertEquals(2, historyCalls)
        host.compose.onNodeWithTag("message.page-0", useUnmergedTree = true).assertIsDisplayed()
        list.performScrollToIndex(0)
        host.advance(100)
        list.performScrollToIndex(205)
        host.advance(100)
        org.junit.Assert.assertEquals(2, historyCalls)
        host.compose.onNodeWithTag("conversation.latest").performTouchInput { click() }
        host.advance(1200)
        host.compose.onNodeWithTag("message.d6", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun reachingOldestBeforeAWipeDoesNotDisableTheNextConversationHistory() {
        open(older = 100)
        val list = host.compose.onNodeWithTag("conversation.list")
        list.performScrollToIndex(105)
        host.advance(200)
        org.junit.Assert.assertEquals(1, historyCalls)
        val newest = messages.value.last().sentAt
        messages.value = emptyList()
        host.advance(200)
        messages.value = (0..<200).map { index -> Message(MessageId("fresh-$index"), MessageDirection.Incoming,
            newest.plusSeconds(index.toLong()+1), MessageContent.Text("After wipe $index")) }
        host.advance(300)
        list.performScrollToIndex(199)
        host.advance(300)
        org.junit.Assert.assertEquals(2, historyCalls)
        org.junit.Assert.assertEquals(200, messages.value.size)
    }

    @Test fun historyReadFailureCanBeRetriedWithoutClearingMessages() {
        historyLoader = { throw java.io.IOException("test read failure") }
        open(older = 100)
        val list = host.compose.onNodeWithTag("conversation.list")
        list.performScrollToIndex(105)
        host.advance(300)
        org.junit.Assert.assertEquals(1, historyCalls)
        org.junit.Assert.assertEquals(107, messages.value.size)
        list.performScrollToIndex(0)
        host.advance(100)
        list.performScrollToIndex(105)
        host.advance(300)
        org.junit.Assert.assertEquals(2, historyCalls)
    }

    @Test fun latestButtonReturnsToNewestAndHides() { open(); returnsToLatest() }
    @Test fun latestButtonWorksInALongLightConversation() { open(older = 5000, theme = ShotTheme.Light); returnsToLatest(fromFarHistory = true) }
    @Test fun latestButtonHonorsReducedMotion() { reducedMotion = true; open(); returnsToLatest() }
}
