package com.syconx.us2us.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.tokens.U2Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keypad keeps the design's 78 dp keys when it fits and shrinks, never below 58 dp, when it does not. */
class NumericKeypadMetricsTest {
    @Test fun aPadThatFitsKeepsItsDesignSize() {
        assertEquals(1f, NumericKeypadMetrics.scale(Dp.Infinity), 0f)
        assertEquals(1f, NumericKeypadMetrics.scale(NumericKeypadMetrics.height), 0f)
        assertEquals(1f, NumericKeypadMetrics.scale(600.dp), 0f)
    }

    @Test fun aShortScreenShrinksTheKeysToTheRoomGiven() {
        val scale = NumericKeypadMetrics.scale(300.dp)
        assertEquals(300f / NumericKeypadMetrics.height.value, scale, 0.0001f)
        assertTrue(U2Size.keypadKey * scale >= NumericKeypadMetrics.minimumKey)
    }

    @Test fun keysNeverShrinkBelowTheMinimum() {
        val scale = NumericKeypadMetrics.scale(80.dp)
        assertEquals(NumericKeypadMetrics.minimumKey.value, (U2Size.keypadKey * scale).value, 0.01f)
        assertTrue("still a comfortable touch target", NumericKeypadMetrics.minimumKey >= 48.dp)
    }
}
