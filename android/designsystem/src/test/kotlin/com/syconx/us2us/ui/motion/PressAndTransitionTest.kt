package com.syconx.us2us.ui.motion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.theme.U2Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The press effect, the soft transitions and the collapse, driven on the test clock. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class PressAndTransitionTest {
    @get:Rule
    val compose = createComposeRule()

    private fun pressedWidth(reduceMotion: Boolean): Float {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            U2Theme(dark = true, reduceMotion = reduceMotion) {
                val source = remember { MutableInteractionSource() }
                Box(
                    Modifier.testTag("button").size(200.dp, 54.dp)
                        .u2PressEffect(source)
                        .clickable(source, indication = null) {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(100)
        assertEquals(200f, compose.onNodeWithTag("button").getBoundsInRoot().let { (it.right - it.left).value }, 0.01f)
        compose.onNodeWithTag("button").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        val width = compose.onNodeWithTag("button").getBoundsInRoot().let { (it.right - it.left).value }
        compose.onNodeWithTag("button").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(200f, compose.onNodeWithTag("button").getBoundsInRoot().let { (it.right - it.left).value }, 0.01f)
        return width
    }

    @Test
    fun pressScalesTo97Percent() {
        assertEquals(200f * 0.97f, pressedWidth(reduceMotion = false), 0.05f)
    }

    @Test
    fun pressDoesNotMoveUnderReduceMotion() {
        assertEquals(200f, pressedWidth(reduceMotion = true), 0.01f)
    }

    @Test
    fun collapseProgressReportsAFractionOfTheHeight() {
        var progress by mutableFloatStateOf(1f)
        compose.setContent {
            Column {
                Box(Modifier.testTag("row").u2CollapseProgress(progress)) { Box(Modifier.size(300.dp, 80.dp)) }
            }
        }
        compose.onNodeWithTag("row").assertHeightIsEqualTo(80.dp)
        progress = 0.25f
        compose.waitForIdle()
        compose.onNodeWithTag("row").assertHeightIsEqualTo(20.dp)
        progress = 0f
        compose.waitForIdle()
        compose.onNodeWithTag("row").assertHeightIsEqualTo(0.dp)
    }

    /** u2Rise: enters over the state duration (0.36 s), rising 8 dp. */
    @Test
    fun riseEntersFromEightPointsBelow() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            U2Theme(dark = true, reduceMotion = false) {
                val rise = U2Transitions.rise()
                val state = remember { MutableTransitionState(false).apply { targetState = true } }
                Box(Modifier.size(200.dp)) {
                    AnimatedVisibility(state, enter = rise.enter, exit = rise.exit) {
                        Box(Modifier.testTag("card").size(100.dp).then(u2TransitionBlur(rise)))
                    }
                }
            }
        }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val start = compose.onNodeWithTag("card").getBoundsInRoot().top.value
        assertTrue("starts below its place: $start", start > 4f)
        compose.mainClock.advanceTimeBy(400)
        assertEquals(0f, compose.onNodeWithTag("card").getBoundsInRoot().top.value, 0.01f)
    }
}
