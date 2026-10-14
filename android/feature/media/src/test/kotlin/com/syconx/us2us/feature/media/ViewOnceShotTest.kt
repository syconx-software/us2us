package com.syconx.us2us.feature.media

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.ViewOnceState
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.shot.assertFrames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 5.6 Tek görüntülemelik against `reference/{,light/}5.6-tek-goruntulemelik.png` and its layout:
 * the viewer after a screenshot (the drawn state), without the warning, while recorded, and in
 * English; and closing a real view-once message marks it opened.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class ViewOnceShotTest {
    private val environment = MediaShotEnvironment()

    @get:Rule
    val host = ShotHost(environment = environment)

    private val deniz = ChatId("deniz")

    @Test
    fun drawnState() {
        environment.state = MediaHarnessStates.VIEW_ONCE
        for (theme in ShotTheme.entries) {
            host.shoot("5.6-tek-goruntulemelik", theme, "tr") {
                ViewOnceCover(deniz, MessageId("m6"), onDismiss = {})
            }
            host.compose.assertFrames(
                checkNotNull(host.layout),
                mapOf(
                    ViewOnceTags.CLOSE to "r.2.0",
                    ViewOnceTags.TITLE to "r.2.1",
                    ViewOnceTags.BADGE to "r.2.2",
                    ViewOnceTags.WARNING to "r.3",
                    ViewOnceTags.FOOTER to "r.6",
                ),
            )
        }
    }

    @Test
    fun cleanAndCaptured() {
        for (theme in ShotTheme.entries) {
            environment.state = MediaHarnessStates.VIEW_ONCE_CLEAN
            host.shoot("conversation:deniz/5.6-clean", theme, "tr") { ViewOnceCover(deniz, MessageId("m6"), onDismiss = {}) }
            environment.state = MediaHarnessStates.VIEW_ONCE_CAPTURED
            host.shoot("conversation:deniz/5.6-captured", theme, "tr") { ViewOnceCover(deniz, MessageId("m6"), onDismiss = {}) }
            host.compose.onNodeWithTag(ViewOnceTags.CAPTURED, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun english() {
        environment.state = MediaHarnessStates.VIEW_ONCE
        host.shoot("conversation:deniz/5.6", ShotTheme.Dark, "en") { ViewOnceCover(deniz, MessageId("m6"), onDismiss = {}) }
    }

    @Test
    fun closingMarksTheMessageOpened() {
        environment.state = null
        val mert = ChatId("mert_k")
        var dismissed = 0
        host.render(ShotTheme.Dark, "tr") { ViewOnceCover(mert, MessageId("m6"), onDismiss = { dismissed++ }) }
        host.advance(500)
        host.compose.onNodeWithTag(ViewOnceTags.MEDIA, useUnmergedTree = true).assertIsDisplayed()
        host.compose.onNodeWithTag(ViewOnceTags.CLOSE).performClick()
        host.advance(100)
        host.compose.onNodeWithTag(ViewOnceTags.CLOSE).performClick()
        host.advance(100)
        assertEquals(1, dismissed)
        val message = environment.service.messages(mert).value.first { it.id == MessageId("m6") }
        assertEquals(ViewOnceState.Opened, (message.content as MessageContent.ViewOnce).state)
    }

    @Test
    fun anOpenedMessageAsksToBeDismissed() {
        environment.state = null
        environment.prepare = { service -> kotlinx.coroutines.runBlocking { service.markViewOnceOpened(MessageId("m6"), ChatId("mert_k")) } }
        var dismissed = 0
        host.render(ShotTheme.Dark, "tr") { ViewOnceCover(ChatId("mert_k"), MessageId("m6"), onDismiss = { dismissed++ }) }
        host.advance(500)
        assertTrue(dismissed >= 1)
    }
}
