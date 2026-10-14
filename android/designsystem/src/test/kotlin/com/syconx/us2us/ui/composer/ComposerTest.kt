package com.syconx.us2us.ui.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.QuotedMessage
import com.syconx.us2us.ui.theme.U2Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** C21: typing, sending, recording, the reply preview and the voice recorder's slots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "tr-rTR-w402dp-h874dp-xxhdpi")
class ComposerTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            U2Theme(dark = true, reduceMotion = false) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { content() }
            }
        }
    }

    @Test
    fun anEmptyDraftRecordsAndTextSends() {
        var text by mutableStateOf("")
        var sent = 0
        var recorded = 0
        show {
            Composer(
                text = text,
                onTextChange = { text = it },
                onSend = { sent++ },
                onRecord = { recorded++ },
                modifier = Modifier.fillMaxWidth(),
                bottomPadding = 36.dp,
            )
        }
        compose.onNodeWithContentDescription("Sesli mesaj kaydet").performClick()
        assertEquals(1, recorded)
        compose.onNodeWithText("Mesaj").assertExists()
        compose.onNodeWithTag("composer.input").performTextInput("Merhaba")
        assertEquals("Merhaba", text)
        compose.onNodeWithContentDescription("Gönder").performClick()
        assertEquals(1, sent)
        assertEquals(1, recorded)
    }

    @Test
    fun whitespaceDoesNotSend() {
        var sent = 0
        var recorded = 0
        show { Composer(text = "   ", onTextChange = {}, onSend = { sent++ }, onRecord = { recorded++ }, bottomPadding = 36.dp) }
        compose.onNodeWithContentDescription("Sesli mesaj kaydet").performClick()
        assertEquals(0, sent)
        assertEquals(1, recorded)
    }

    @Test
    fun theReplyPreviewCanBeCancelled() {
        var quote by mutableStateOf<QuotedMessage?>(QuotedMessage(author = "deniz", kind = QuotedMessage.Kind.Text("Bu akşam boş musun?")))
        show { Composer(text = "", onTextChange = {}, onSend = {}, quote = quote, onCancelQuote = { quote = null }, bottomPadding = 36.dp) }
        compose.onNodeWithText("Bu akşam boş musun?").assertExists()
        compose.onNodeWithContentDescription("Yanıtı iptal et").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Yanıtı iptal et").assertCountEquals(0)
    }

    @Test
    fun theFieldButtonsWork() {
        var timer = 0
        var sticker = 0
        var attach = 0
        show {
            Composer(text = "", onTextChange = {}, onSend = {}, onAttach = { attach++ }, onTimer = { timer++ }, onSticker = { sticker++ }, bottomPadding = 36.dp)
        }
        compose.onNodeWithContentDescription("Süreli mesaj").performClick()
        compose.onNodeWithContentDescription("Sticker").performClick()
        compose.onNodeWithContentDescription("Ekle").performClick()
        assertEquals(listOf(1, 1, 1), listOf(timer, sticker, attach))
    }

    @Test
    fun aHeldRecordingShowsItsStripLockAndHoldArea() {
        val voice = FakeVoice(ComposerVoiceMode.Holding)
        show { Composer(text = "", onTextChange = {}, onSend = {}, voice = voice, bottomPadding = 36.dp) }
        compose.onNodeWithTag("voice.strip").assertExists()
        compose.onNodeWithTag("voice.lock").assertExists()
        compose.onNodeWithTag("voice.hold").assertExists()
        // “+” is hidden and inert while held; “Sil” only appears once locked.
        compose.onAllNodesWithContentDescription("Kaydı sil").assertCountEquals(0)
    }

    @Test
    fun aLockedRecordingSendsOrIsDeleted() {
        val voice = FakeVoice(ComposerVoiceMode.Locked)
        show { Composer(text = "", onTextChange = {}, onSend = {}, voice = voice, bottomPadding = 36.dp) }
        compose.onAllNodesWithTag("voice.hold").assertCountEquals(0)
        compose.onNodeWithContentDescription("Sesli mesajı gönder").performClick()
        assertEquals(1, voice.sent)
        compose.onNodeWithContentDescription("Kaydı sil").performClick()
        assertEquals(1, voice.cancelled)
    }

    @Test
    fun aHintFloatsAboveTheRow() {
        val voice = FakeVoice(ComposerVoiceMode.Idle, hint = true)
        show { Composer(text = "", onTextChange = {}, onSend = {}, voice = voice, bottomPadding = 36.dp) }
        compose.onNodeWithTag("voice.hint").assertExists()
        compose.onNodeWithTag("voice.hold").assertExists()
    }

    private class FakeVoice(override val mode: ComposerVoiceMode, hint: Boolean = false) : ComposerVoice {
        var sent = 0
        var cancelled = 0
        override val showsHint: Boolean = hint
        override val micOffset: DpOffset = DpOffset.Zero

        override fun send() {
            sent++
        }

        override fun cancel() {
            cancelled++
        }

        @Composable
        override fun RecordingStrip(modifier: Modifier) {
            Box(modifier.testTag("voice.strip").fillMaxSize())
        }

        @Composable
        override fun HoldArea(modifier: Modifier) {
            Box(modifier.testTag("voice.hold"))
        }

        @Composable
        override fun LockHint(modifier: Modifier) {
            Box(modifier.testTag("voice.lock").size(40.dp, 88.dp))
        }

        @Composable
        override fun HintBubble(modifier: Modifier) {
            Box(modifier.testTag("voice.hint").size(120.dp, 34.dp))
        }
    }
}
