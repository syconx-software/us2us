package com.syconx.us2us.ui.sheet

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
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
 * Sheets, fade covers, the floating sheet and toasts on the test clock: how they ask to go (drag,
 * backdrop, back), how they go, and what runs when they are gone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class U2SheetBehaviourTest {
    @get:Rule
    val compose = createComposeRule()

    private var presented by mutableStateOf(true)
    private var requests = 0
    private var dismissed = 0
    private var settled: Boolean? = null
    private var dispatcher: OnBackPressedDispatcher? = null

    private fun advance(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    private fun act(block: () -> Unit) {
        compose.runOnIdle {
            block()
            Snapshot.sendApplyNotifications()
        }
    }

    private fun show(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            U2Theme(dark = true, reduceMotion = false) {
                U2OverlayHost {
                    Box(Modifier.fillMaxSize().testTag("screen"))
                    content()
                }
            }
        }
        advance(2_000)
    }

    private fun sheet(style: U2SheetStyle, dismissible: Boolean = true) = show {
        U2Sheet(
            isPresented = presented,
            onDismissRequest = {
                requests++
                presented = false
            },
            style = style,
            isDismissible = dismissible,
            onDismissed = { dismissed++ },
        ) {
            settled = LocalSheetSettled.current
            Box(Modifier.fillMaxWidth().height(300.dp).testTag("content"))
        }
    }

    @Test
    fun risesSettlesAndGoesWhenNoLongerPresented() {
        sheet(U2SheetStyle.Content)
        compose.onNodeWithTag(U2SheetTags.SHEET, useUnmergedTree = true).assertExists()
        assertEquals(true, settled)
        act { presented = false }
        advance(200)
        assertEquals(false, settled)
        assertEquals(0, dismissed)
        advance(1_000)
        assertEquals(1, dismissed)
        compose.onNodeWithTag(U2SheetTags.SHEET, useUnmergedTree = true).assertDoesNotExist()
        assertEquals("nobody asked", 0, requests)
    }

    @Test
    fun aTapOnTheBackdropAsksForContentAndFloatingSheets() {
        sheet(U2SheetStyle.Content)
        compose.onRoot().performTouchInput { click(Offset(200.dp.toPx(), 100.dp.toPx())) }
        advance(1_500)
        assertEquals(1, requests)
        assertEquals(1, dismissed)
    }

    @Test
    fun aTapAboveAFullHeightSheetDoesNothing() {
        sheet(U2SheetStyle.FullHeight)
        compose.onRoot().performTouchInput { click(Offset(200.dp.toPx(), 30.dp.toPx())) }
        advance(1_500)
        assertEquals(0, requests)
    }

    @Test
    fun aLongDragDownDismissesAndAShortOneSettlesBack() {
        sheet(U2SheetStyle.Content)
        val sheet = compose.onNodeWithTag(U2SheetTags.SHEET, useUnmergedTree = true)
        // A third of 313 dp is about 104: 40 dp slowly is not enough.
        sheet.performTouchInput { swipe(Offset(centerX, 30.dp.toPx()), Offset(centerX, 70.dp.toPx()), durationMillis = 1_500) }
        advance(1_500)
        assertEquals(0, requests)
        compose.onNodeWithTag(U2SheetTags.SHEET, useUnmergedTree = true).assertExists()
        sheet.performTouchInput { swipe(Offset(centerX, 30.dp.toPx()), Offset(centerX, 200.dp.toPx()), durationMillis = 600) }
        advance(2_000)
        assertEquals(1, requests)
        assertEquals(1, dismissed)
    }

    @Test
    fun backAsksAndANonDismissibleSheetSwallowsIt() {
        sheet(U2SheetStyle.Floating, dismissible = false)
        act { dispatcher!!.onBackPressed() }
        advance(1_500)
        assertEquals(0, requests)
        compose.onNodeWithTag(U2SheetTags.SHEET, useUnmergedTree = true).assertExists()
    }

    @Test
    fun backAsksADismissibleSheetToGo() {
        sheet(U2SheetStyle.FullHeight)
        act { dispatcher!!.onBackPressed() }
        advance(1_500)
        assertEquals(1, requests)
        assertEquals(1, dismissed)
    }

    @Test
    fun anItemSheetKeepsItsItemWhileItGoes() {
        var item by mutableStateOf<String?>("deniz")
        show {
            U2Sheet(item = item, onDismissRequest = { item = null }) { shown -> BasicText(shown) }
        }
        compose.onNodeWithText("deniz").assertExists()
        act { item = null }
        advance(200)
        compose.onNodeWithText("deniz").assertExists()
        advance(1_000)
        compose.onNodeWithText("deniz").assertDoesNotExist()
    }

    @Test
    fun aFadeCoverEscapesOnlyWhenAllowed() {
        var shown by mutableStateOf(true)
        var escapes = 0
        var gone = 0
        show {
            U2FadeCover(isPresented = shown, onEscape = { escapes++; shown = false }, onDismissed = { gone++ }) {
                Box(Modifier.fillMaxSize().testTag("cover"))
            }
        }
        compose.onNodeWithTag("cover").assertExists()
        act { dispatcher!!.onBackPressed() }
        advance(1_000)
        assertEquals(1, escapes)
        assertEquals(1, gone)
        compose.onNodeWithTag("cover").assertDoesNotExist()
    }

    @Test
    fun aFadeCoverWithoutEscapeIgnoresBack() {
        show {
            U2FadeCover(isPresented = true) { Box(Modifier.fillMaxSize().testTag("cover")) }
        }
        act { dispatcher!!.onBackPressed() }
        advance(1_000)
        compose.onNodeWithTag("cover").assertExists()
    }

    @Test
    fun theFloatingPresenterShowsOneSheetAndGoesWithTheForeground() {
        var presenter: U2FloatingSheetPresenter? = null
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                U2Theme(dark = true, reduceMotion = false) {
                    U2OverlayHost { presenter = LocalFloatingSheetPresenter.current }
                }
            }
        }
        advance(100)
        var closed = 0
        var first = false
        var second = true
        act {
            first = presenter!!.present({ BasicText("notice", Modifier.testTag("notice")) }, onDismiss = { closed++ })
            second = presenter!!.present({ BasicText("other") }, onDismiss = { closed += 10 })
        }
        advance(2_000)
        assertTrue(first)
        assertFalse(second)
        compose.onNodeWithTag("notice").assertExists()
        assertTrue(presenter!!.isPresenting)
        // The app goes to the background: the sheet goes, as a cancel.
        act { owner.registry.currentState = Lifecycle.State.CREATED }
        advance(1_500)
        assertEquals(1, closed)
        assertFalse(presenter!!.isPresenting)
        compose.onNodeWithTag("notice").assertDoesNotExist()
    }

    @Test
    fun aToastGoesAfterTwoPointFourSecondsOrOnATap() {
        var message by mutableStateOf<String?>("one")
        var dismissals = 0
        show {
            U2Toast(message = message, onDismiss = { dismissals++; message = null })
        }
        // Shown for 2 s so far.
        compose.onNodeWithTag(U2_TOAST_TAG, useUnmergedTree = true).assertExists()
        assertEquals(0, dismissals)
        advance(600)
        assertEquals(1, dismissals)
        act { message = "two" }
        advance(500)
        compose.onNodeWithTag(U2_TOAST_TAG, useUnmergedTree = true).performClick()
        advance(500)
        assertEquals(2, dismissals)
    }
}
