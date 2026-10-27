package com.syconx.us2us.ui.nav

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.theme.U2Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The soft stack on the test clock: push and pop with their short fades, covered screens kept with
 * their state but untouchable and out of the semantics tree, the edge swipe's thresholds, and back
 * (plain and predictive).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class SoftNavigationStackTest {
    @get:Rule
    val compose = createComposeRule()

    private val router = U2Router<String>()
    private val composed = mutableSetOf<String>()
    private val visible = mutableMapOf<String, Boolean>()
    private var dispatcher: OnBackPressedDispatcher? = null

    @Composable
    private fun Screen(name: String) {
        var count by rememberSaveable { mutableIntStateOf(0) }
        val isVisible = LocalIsLayerVisible.current
        visible[name] = isVisible
        DisposableEffect(name) {
            composed += name
            onDispose { composed -= name }
        }
        Box(Modifier.fillMaxSize().testTag("screen-$name")) {
            BasicText("$name:$count", Modifier.align(Alignment.Center))
            // The root's button sits top left, the pushed screens' bottom right.
            Box(
                Modifier
                    .align(if (name == "root") Alignment.TopStart else Alignment.BottomEnd)
                    .size(100.dp)
                    .testTag("button-$name")
                    .clickable { count++ },
            )
        }
    }

    private fun setUp(reduceMotion: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            U2Theme(dark = true, reduceMotion = reduceMotion) {
                SoftNavigationStack(router, root = { Screen("root") }, destination = { Screen(it) })
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun advance(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    /** Runs [block] on the UI thread as an event handler would, and lets the composition see its writes. */
    private fun act(block: () -> Unit) {
        compose.runOnIdle {
            block()
            Snapshot.sendApplyNotifications()
        }
    }

    private fun push(route: String) {
        act { router.push(route) }
        advance(700)
    }

    @Test
    fun pushHidesTheScreenBelowButKeepsItComposed() {
        setUp()
        push("a")
        assertEquals(listOf("a"), router.path)
        compose.onNodeWithTag("screen-a").assertExists()
        // The covered root is out of the semantics tree, but alive.
        compose.onNodeWithTag("screen-root").assertDoesNotExist()
        assertTrue("root" in composed)
        assertEquals(false, visible["root"])
        assertEquals(true, visible["a"])
    }

    @Test
    fun theStagedScreenIsBuiltBeforeThePushPlays() {
        setUp()
        act { router.push("a") }
        compose.mainClock.advanceTimeByFrame()
        // Composed (staged, transparent) while the root is still the top.
        assertTrue("a" in composed)
        compose.onNodeWithTag("screen-root").assertExists()
        advance(700)
        assertFalse(router.entries.single().isStaged)
        compose.onNodeWithTag("screen-root").assertDoesNotExist()
    }

    @Test
    fun popPlaysItsFadeThenDropsTheScreen() {
        setUp()
        push("a")
        act { router.pop() }
        advance(64)
        // Mid-fade: the leaving screen is still drawn, the root is the top again.
        assertTrue("a" in composed)
        compose.onNodeWithTag("screen-root").assertExists()
        advance(160)
        assertFalse("a" in composed)
    }

    @Test
    fun aCoveredScreenKeepsItsSavedState() {
        setUp()
        compose.onNodeWithTag("button-root").performClick()
        advance(100)
        compose.onNodeWithText("root:1").assertExists()
        push("a")
        push("b")
        act { router.popToRoot() }
        advance(700)
        compose.onNodeWithText("root:1").assertExists()
    }

    @Test
    fun aCoveredScreenTakesNoTouches() {
        setUp()
        push("a")
        // Where only the root has a button (top left): the tap reaches nothing.
        compose.onRoot().performTouchInput { click(Offset(50.dp.toPx(), 50.dp.toPx())) }
        advance(100)
        act { router.pop() }
        advance(700)
        compose.onNodeWithText("root:0").assertExists()
    }

    @Test
    fun anEdgeSwipePastTheThresholdPops() {
        setUp()
        push("a")
        compose.onRoot().performTouchInput {
            swipe(Offset(10.dp.toPx(), 400.dp.toPx()), Offset(250.dp.toPx(), 410.dp.toPx()), durationMillis = 400)
        }
        advance(1500)
        assertTrue(router.isAtRoot)
        assertFalse("a" in composed)
        compose.onNodeWithTag("screen-root").assertExists()
    }

    @Test
    fun aShortSlowEdgeSwipeSettlesBack() {
        setUp()
        push("a")
        compose.onRoot().performTouchInput {
            swipe(Offset(10.dp.toPx(), 400.dp.toPx()), Offset(80.dp.toPx(), 402.dp.toPx()), durationMillis = 1200)
        }
        advance(1500)
        assertEquals(listOf("a"), router.path)
        compose.onNodeWithTag("screen-a").assertExists()
    }

    @Test
    fun aSwipeAwayFromTheEdgeDoesNothing() {
        setUp()
        push("a")
        compose.onRoot().performTouchInput {
            swipe(Offset(60.dp.toPx(), 400.dp.toPx()), Offset(350.dp.toPx(), 400.dp.toPx()), durationMillis = 300)
        }
        advance(1500)
        assertEquals(listOf("a"), router.path)
    }

    @Test
    fun backPops() {
        setUp()
        push("a")
        push("b")
        act { dispatcher!!.onBackPressed() }
        advance(700)
        assertEquals(listOf("a"), router.path)
        act { dispatcher!!.onBackPressed() }
        advance(700)
        assertTrue(router.isAtRoot)
    }

    @Test
    fun predictiveBackFollowsTheGestureAndCommits() {
        setUp()
        push("a")
        act {
            dispatcher!!.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher!!.dispatchOnBackProgressed(BackEventCompat(120f, 400f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        advance(100)
        // Mid-gesture both screens are drawn and the one below is visible.
        assertEquals(true, visible["root"])
        assertEquals(listOf("a"), router.path)
        act { dispatcher!!.onBackPressed() }
        advance(1500)
        assertTrue(router.isAtRoot)
    }

    @Test
    fun aCancelledPredictiveBackStays() {
        setUp()
        push("a")
        act {
            dispatcher!!.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher!!.dispatchOnBackProgressed(BackEventCompat(120f, 400f, 0.4f, BackEventCompat.EDGE_LEFT))
        }
        advance(100)
        act { dispatcher!!.dispatchOnBackCancelled() }
        advance(1500)
        assertEquals(listOf("a"), router.path)
        assertEquals(false, visible["root"])
    }

    @Test
    fun backAtTheRootIsNotTaken() {
        setUp()
        assertFalse(dispatcher!!.hasEnabledCallbacks())
        push("a")
        assertTrue(dispatcher!!.hasEnabledCallbacks())
    }

    @Test
    fun underReduceMotionPushesCrossFade() {
        setUp(reduceMotion = true)
        push("a")
        compose.onNodeWithTag("screen-a").assertExists()
        act { router.pop() }
        advance(400)
        assertFalse("a" in composed)
    }
}
