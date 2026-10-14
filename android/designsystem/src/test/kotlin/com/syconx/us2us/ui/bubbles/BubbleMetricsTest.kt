package com.syconx.us2us.ui.bubbles

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.shot.assertSize
import com.syconx.us2us.ui.composer.Composer
import com.syconx.us2us.ui.media.LocalMediaClock
import com.syconx.us2us.ui.theme.U2Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every bubble laid out with the design's data measures what the design measures (the iOS
 * ComponentMetricsTests bubble, status and composer cases, from Chrome's getBoundingClientRect).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "tr-rTR-w402dp-h874dp-xxhdpi")
class BubbleMetricsTest {
    @get:Rule
    val compose = createComposeRule()

    private val messages by lazy { DesignFixtures.denizMessages(english = false) + DesignFixtures.mertMessages(english = false) }

    private fun message(id: String): Message = messages.first { it.id.value == id }

    private var subject by mutableStateOf<Pair<Dp?, @Composable () -> Unit>?>(null)
    private var version by mutableStateOf(0)
    private var started = false

    /** Shows [content] as the subject (the rule's content is set once; later calls swap the subject). */
    private fun measure(width: Dp? = null, content: @Composable () -> Unit) {
        subject = width to content
        version++
        if (!started) {
            started = true
            compose.setContent {
                U2Theme(dark = true, reduceMotion = false) {
                    CompositionLocalProvider(LocalMediaClock provides DesignClock.frozen(DesignFixtures.referenceDate)) {
                        key(version) {
                            subject?.let { (w, body) ->
                                Box(Modifier.testTag("subject").let { if (w != null) it.width(w) else it }) { body() }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bubble(id: String, width: Float, height: Float, isTail: Boolean = false, tolerance: Float = 0.5f) {
        measure { MessageBubbleView(message(id), isTail = isTail) }
        compose.assertSize("subject", width.dp, height.dp, tolerance.dp)
    }

    @Test
    fun textBubbles() {
        bubble("d1", 195.5f, 55.58f)
        bubble("d5", 228.95f, 59.08f)
        bubble("d6", 141.92f, 59.08f, isTail = true)
    }

    @Test
    fun replyPhotoVoiceBubbles() {
        bubble("d3", 248.45f, 113.08f, isTail = true)
        // The photo bubble keeps 14 below for its reaction chip.
        bubble("d2", 268f, 234.58f + 14f, isTail = true)
        bubble("d4", 239.81f, 69.5f, isTail = true)
    }

    @Test
    fun contentBubbles() {
        bubble("m1", 237.7f, 88f, isTail = true, tolerance = 1f)
        bubble("m3", 196.11f, 96.5f, isTail = true, tolerance = 1f)
        bubble("m4", 266f, 170.17f, isTail = true, tolerance = 1f)
        bubble("m5", 227.98f, 37f, tolerance = 1f)
        bubble("m6", 290f, 95.38f)
    }

    @Test
    fun statusLines() {
        measure(374.dp) { MessageStatusSentence(MessageStatus.Sent) }
        compose.assertSize("subject", null, 18.59.dp, 0.5.dp)
        measure(354.dp) { MessageStatusLegendRow(MessageStatus.Sent) }
        compose.assertSize("subject", null, 18.89.dp, 0.5.dp)
    }

    @Test
    fun composerIs94High() {
        measure(402.dp) { Composer(text = "", onTextChange = {}, onSend = {}, bottomPadding = 36.dp) }
        compose.assertSize("subject", 402.dp, 94.dp, 0.5.dp)
    }

    @Test
    fun composerFieldGrowsToFiveLines() {
        val lines = (1..8).joinToString("\n") { "satır $it" }
        measure(402.dp) { Composer(text = lines, onTextChange = {}, onSend = {}, bottomPadding = 36.dp) }
        // Five lines of `rowTitle` (24.8 each) plus the field's 9.6 above and below.
        compose.assertSize("composer.field", 274.dp, (5 * 24.8f + 2 * 9.6f).dp, 1.5.dp)
    }
}
