package com.syconx.us2us.ui.bubbles

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.syconx.us2us.domain.*
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.ui.theme.U2Theme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "tr-rTR-w402dp-h874dp-xxhdpi")
class MessageLinkInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val opened = mutableListOf<String>()
    private val invitations = mutableListOf<String>()
    private var longPresses = 0
    private var mediaOpens = 0

    private fun show(text: String, photo: Boolean = false) {
        val handler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        val content = if (photo) MessageContent.Photo(MediaSource.Artwork(DesignArtwork.NightPier), text) else MessageContent.Text(text)
        val message = Message(MessageId("link"), MessageDirection.Incoming, Instant.EPOCH, content)
        compose.setContent {
            U2Theme(dark = true, reduceMotion = true) {
                CompositionLocalProvider(LocalUriHandler provides handler, LocalMessageInviteAction provides { invitations += it }) {
                    MessageBubbleView(message, onLongPress = { longPresses++ }, onOpenMedia = { mediaOpens++ })
                }
            }
        }
    }
    @Test fun webTapOpensOnlyTheBrowserHandler() {
        val text = "https://example.org"
        show(text)
        assertTrue(opened.isEmpty())
        compose.onNodeWithText(text, useUnmergedTree = true).performTouchInput { click(center) }
        assertEquals(listOf(text), opened)
        assertTrue(invitations.isEmpty())
    }
    @Test fun inviteTapStaysInsideTheApp() {
        val text = "us2us-community:v1:YWJj"
        show(text)
        assertTrue(invitations.isEmpty())
        compose.onNodeWithText(text, useUnmergedTree = true).performTouchInput { click(center) }
        assertEquals(listOf(text), invitations)
        assertTrue(opened.isEmpty())
    }
    @Test fun longPressStillOpensMessageActionsWithoutOpeningALink() {
        val text = "https://example.org"
        show(text)
        compose.onNodeWithText(text, useUnmergedTree = true).performTouchInput { longClick(center) }
        assertEquals(1, longPresses)
        assertTrue(opened.isEmpty())
    }
    @Test fun captionLinkOpensBrowserWithoutOpeningPhoto() {
        val text = "https://example.org"
        show(text, photo = true)
        compose.onNodeWithText(text, useUnmergedTree = true).performTouchInput { click(center) }
        assertEquals(listOf(text), opened)
        assertEquals(0, mediaOpens)
    }
    @Test fun captionLongPressStillOpensMessageActions() {
        val text = "https://example.org"
        show(text, photo = true)
        compose.onNodeWithText(text, useUnmergedTree = true).performTouchInput { longClick(center) }
        assertEquals(1, longPresses)
        assertTrue(opened.isEmpty())
        assertEquals(0, mediaOpens)
    }
}
