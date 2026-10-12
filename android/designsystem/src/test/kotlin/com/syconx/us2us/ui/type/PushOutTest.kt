package com.syconx.us2us.ui.type

import android.content.Context
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2TextStyles
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The widow avoidance of iOS `Text` in [U2Text]: a two-line text never ends on a lone word
 * (3.2 “… 01:00 sonra / tekrar dene.”, 7.1 “Eski telefonun erişimi / kapanmamış olabilir”), while
 * longer texts and the design's greedy layouts break as before.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class PushOutTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val density = Density(3f)
    private val measurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)

    private fun layout(text: String, widthDp: Int): TextLayoutResult = measurer.measure(
        text = AnnotatedString(text),
        style = U2TextStyles.noteTitle.toTextStyle(density),
        constraints = Constraints(maxWidth = (widthDp * 3f).roundToInt()),
    )

    private fun lastLine(layout: TextLayoutResult): String = layout.layoutInput.text.text.substring(layout.getLineStart(layout.lineCount - 1)).trim()

    @Test
    fun aLoneWordOnTheSecondOfTwoLinesIsAWidow() {
        val plain = layout("Eski telefonun erişimi kapanmamış olabilir", 302)
        assertEquals(2, plain.lineCount)
        assertEquals("olabilir", lastLine(plain))
        assertTrue(PushOut.leavesWidow(plain))
        val joined = layout(PushOut.join("Eski telefonun erişimi kapanmamış olabilir"), 302)
        assertEquals(2, joined.lineCount)
        assertEquals("kapanmamış\u00A0olabilir", lastLine(joined))
        assertFalse(PushOut.leavesWidow(joined))
    }

    @Test
    fun oneLineTwoWordLastLinesAndLongerTextsAreLeftAlone() {
        assertFalse(PushOut.leavesWidow(layout("Eski telefonun erişimi kapanmamış olabilir", 400)))
        assertFalse(PushOut.leavesWidow(layout("Eski telefonun erişimi kapanmamış olabilir", 200)))
        val three = layout("Sohbet bu kadar sessiz kalırsa iki uçtan da tamamen silinir. Her yeni mesaj sayacı baştan başlatır.", 300)
        assertTrue(three.lineCount >= 3)
        assertFalse(PushOut.leavesWidow(three))
        assertEquals("tamam!", PushOut.join("tamam!"))
    }

    @Test
    fun u2TextKeepsTheLastTwoWordsTogether() {
        var shown: TextLayoutResult? = null
        compose.mainClock.autoAdvance = true
        compose.setContent {
            U2Theme(dark = true) {
                U2Text("Eski telefonun erişimi kapanmamış olabilir", U2TextStyles.noteTitle, Modifier.width(302.dp), onTextLayout = { shown = it })
            }
        }
        compose.waitForIdle()
        val layout = checkNotNull(shown)
        assertEquals(2, layout.lineCount)
        assertEquals("kapanmamış\u00A0olabilir", lastLine(layout))
    }
}
