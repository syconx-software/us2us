package com.syconx.us2us.ui.components.atoms

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.syconx.us2us.shot.ShotHost
import androidx.compose.ui.test.hasContentDescription
import com.syconx.us2us.ui.components.CodeDots
import com.syconx.us2us.ui.components.CodeDotsAppearance
import com.syconx.us2us.ui.components.FloatingMenuItem
import com.syconx.us2us.ui.components.FloatingMenuMetrics
import com.syconx.us2us.ui.components.U2AnchoredMenu
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.components.EraseConfirmation
import com.syconx.us2us.ui.components.EraseScope
import com.syconx.us2us.ui.components.KeypadBottomLeading
import com.syconx.us2us.ui.components.NumericKeypad
import com.syconx.us2us.ui.components.SegmentedChips
import com.syconx.us2us.ui.components.U2Checkbox
import com.syconx.us2us.ui.components.U2FlexRowLayout
import com.syconx.us2us.ui.components.U2Toggle
import com.syconx.us2us.ui.components.UsernameField
import com.syconx.us2us.ui.components.text.CSSLineClamp
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import com.syconx.us2us.ui.type.textStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What the atoms do when touched: keypad, switches, chips, the username field and the clamp. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class AtomInteractionTest {
    @get:Rule
    val host = AtomShots.host()

    @Test
    fun keypadReportsDigitsDeleteAndFaceId() {
        val typed = StringBuilder()
        var deletes = 0
        var faceId = 0
        host.render {
            NumericKeypad(
                onDigit = { typed.append(it) },
                onDelete = { deletes++ },
                bottomLeading = KeypadBottomLeading.FaceId { faceId++ },
            )
        }
        for (digit in listOf("4", "4", "7", "1", "0")) host.compose.onNodeWithContentDescription(digit).performClick()
        host.compose.onNodeWithContentDescription("Sil").performClick()
        host.compose.onNodeWithContentDescription("Biyometrik ile aç").performClick()
        assertEquals("44710", typed.toString())
        assertEquals(1, deletes)
        assertEquals(1, faceId)
    }

    @Test
    fun disabledKeypadIgnoresTaps() {
        var digits = 0
        host.render { NumericKeypad(onDigit = { digits++ }, onDelete = {}, enabled = false) }
        host.compose.onNodeWithContentDescription("5").assertIsNotEnabled()
        assertEquals(0, digits)
    }

    @Test
    fun toggleCheckboxAndChipsChange() {
        host.render {
            var on by remember { mutableStateOf(false) }
            var ack by remember { mutableStateOf(false) }
            var period by remember { mutableStateOf("24 sa") }
            Column {
                U2Toggle(on, { on = it }, Modifier.testTag("toggle")) { U2Text("Ghost mod", U2TextStyles.rowTitleEmphasis) }
                U2Checkbox(ack, { ack = it }, Modifier.testTag("checkbox")) { U2Text("Anladım", U2TextStyles.calloutL) }
                SegmentedChips(listOf("1 sa", "6 sa", "24 sa"), period, { period = it }, identifierPrefix = "period") { it }
            }
        }
        // The shot clock is paused: every change needs a frame.
        host.compose.onNodeWithTag("toggle").assertIsOff().performClick()
        host.advance(100)
        host.compose.onNodeWithTag("toggle").assertIsOn()
        host.compose.onNodeWithTag("checkbox").assertIsOff().performClick()
        host.advance(100)
        host.compose.onNodeWithTag("checkbox").assertIsOn()
        host.compose.onNodeWithTag("period.6 sa").performClick()
        host.advance(500)
        host.compose.onNodeWithTag("period.6 sa").assertIsSelected()
    }

    @Test
    fun usernameFieldShowsTheLowerCaseForm() {
        var reported = ""
        host.render {
            var name by remember { mutableStateOf("") }
            UsernameField(name, { name = it; reported = it }, Modifier.width(362.dp))
        }
        host.compose.onNode(hasSetTextAction()).performTextInput("Selin.N")
        host.advance(100)
        assertEquals("selin.n", reported)
        host.compose.onNode(hasSetTextAction() and hasText("selin.n")).assertExists()
    }

    @Test
    fun eraseConfirmationButtonsAct() {
        var confirms = 0
        var cancels = 0
        host.render { EraseConfirmation(onConfirm = { confirms++ }, onCancel = { cancels++ }) }
        host.compose.onNodeWithTag("erase.confirm").performClick()
        host.compose.onNodeWithTag("erase.cancel").performClick()
        assertEquals(1, confirms)
        assertEquals(1, cancels)
        host.compose.onNodeWithText("Her şey silinsin mi?").assertExists()
    }

    @Test
    fun workingEraseConfirmationIgnoresTaps() {
        var confirms by mutableIntStateOf(0)
        host.render { EraseConfirmation(onConfirm = { confirms++ }, onCancel = {}, scope = EraseScope.Account, isWorking = true) }
        host.compose.onNodeWithTag("erase.confirm").assertIsNotEnabled()
        host.compose.onNodeWithTag("erase.cancel").assertIsNotEnabled()
        host.compose.onNode(hasTestTag("erase.confirm") and hasText("Hesap siliniyor…", substring = true)).assertExists()
    }

    @Test
    fun anchoredMenuClosesBeforeTheRowActs() {
        var presented by mutableStateOf(true)
        var copies = 0
        host.render {
            U2AnchoredMenu(
                isPresented = presented,
                onDismiss = { presented = false },
                items = listOf(FloatingMenuItem("copy", "Kopyala", PhosphorGlyph.Copy) { copies++ }),
            )
        }
        host.compose.onNodeWithTag("menu.copy").performClick()
        assertEquals("the menu closes at once", false, presented)
        assertEquals("the row waits for the fade", 0, copies)
        host.advance(FloatingMenuMetrics.ACTION_DELAY_MILLIS + 50)
        assertEquals(1, copies)
    }

    @Test
    fun codeDotsShakeWhenTriggered() {
        var trigger by mutableIntStateOf(0)
        host.render { CodeDots(filled = 6, appearance = CodeDotsAppearance.Error, shakeTrigger = trigger) }
        val dots = hasContentDescription("6 / 6 hane girildi")
        val rest = with(AtomShots) { host.compose.frameOf(dots) }
        trigger++
        host.advance(16)
        host.advance(60)
        val shaken = with(AtomShots) { host.compose.frameOf(dots) }
        assertTrue("moved sideways mid-shake: ${shaken.x - rest.x}", kotlin.math.abs(shaken.x - rest.x) > 2f)
        host.advance(600)
        val after = with(AtomShots) { host.compose.frameOf(dots) }
        assertEquals("back in place", rest.x, after.x, 0.01f)
    }

    @Test
    fun flexWidthsTakeTheBorderOffFirst() {
        val widths = U2FlexRowLayout.widths(width = 340f, spacing = 8f, items = listOf(1f to 0f, 1f to 0f, 1.3f to 2f))
        assertEquals(97.58f, widths[0], 0.01f)
        assertEquals(128.84f, widths[2], 0.01f)
        val bar = U2FlexRowLayout.widths(width = 370f, spacing = 8f, items = listOf(1f to 0f, 1f to 0f, 1.4f to 2f))
        assertEquals(103.53f, bar[0], 0.01f)
        assertEquals(146.94f, bar[2], 0.01f)
    }

    @Test
    fun lineClampEndsTheLastLineWithAnEllipsis() {
        val snippet = "Merhaba! Dünkü meetup’taki sunumunu çok beğendim, slaytları paylaşabilir misin?"
        val long = "Merhaba, toplantı için hazırladığım slaytları paylaşabilir misin? Akşama kadar bakmam gerekiyor, sonra da yola çıkacağım."
        var drawn = ""
        var cut = ""
        var short = ""
        host.render {
            val measurer = rememberTextMeasurer()
            val style = U2TextStyles.requestSnippet.textStyle()
            drawn = CSSLineClamp.clamp(snippet, measurer, style, width = 3 * 284, lines = 2)
            cut = CSSLineClamp.clamp(long, measurer, style, width = 3 * 250, lines = 2)
            short = CSSLineClamp.clamp("Fotoğraf", measurer, style, width = 3 * 284, lines = 2)
        }
        // 4.3: the second line keeps its last word and the ellipsis follows it directly.
        assertEquals("Merhaba! Dünkü meetup’taki sunumunu çok beğendim, slaytları paylaşabilir…", drawn)
        // A last line too full for the ellipsis loses characters until it fits, as the design's renderer does.
        assertTrue(cut, cut.endsWith("…") && long.startsWith(cut.removeSuffix("…")) && cut.length < long.length)
        assertEquals("Fotoğraf", short)
    }
}
