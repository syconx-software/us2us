package com.syconx.us2us.app

import android.content.Intent
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.syconx.us2us.domain.nav.Overlay
import com.syconx.us2us.domain.nav.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The real activity on Robolectric: the 3.4 cover on pause, the lock after a stop, no lock on a
 * recreation, a locked launch, and the capture policy.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class AppLifecycleTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Us2usApplication = ApplicationProvider.getApplicationContext()

    private fun launch(route: String, vararg flags: String): ActivityScenario<MainActivity> {
        val intent = Intent(app, MainActivity::class.java)
            .putExtra("U2Route", route)
            .putExtra("U2Fixtures", true)
            .putExtra("U2FrozenClock", true)
            .putExtra("U2DesignInsets", true)
        flags.forEach { intent.putExtra(it, true) }
        return ActivityScenario.launch(intent)
    }

    private val model: AppModel get() = checkNotNull(app.model)

    @Test
    fun aPauseCoversAndAResumeDissolvesTheCover() {
        launch("chats").use { scenario ->
            compose.waitForIdle()
            assertEquals(Phase.Unlocked, model.phase)
            assertEquals(Overlay.None, model.overlay)
            compose.onNodeWithTag(RootTags.MAIN_STACK).assertExists()
            scenario.moveToState(Lifecycle.State.STARTED)
            assertEquals(Overlay.PrivacyCover, model.overlay)
            // (A paused activity's composition is not searchable by the test rule; the cover is drawn in the shots.)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(Overlay.None, model.overlay)
            compose.waitForIdle()
            compose.onNodeWithTag(RootTags.SECURITY).assertDoesNotExist()
        }
    }

    @Test
    fun aStopLocksOnTheWayBack() {
        launch("conversation:deniz").use { scenario ->
            compose.waitForIdle()
            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals(Overlay.PrivacyCover, model.overlay)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(Overlay.Lock, model.overlay)
            assertEquals("the stack stays alive under the lock", Phase.Unlocked, model.phase)
            assertEquals(1, model.router.path.size)
            compose.waitForIdle()
            compose.onNodeWithTag(RootTags.LOCK).assertExists()
        }
    }

    @Test
    fun aRecreationIsNotABackground() {
        launch("chats").use { scenario ->
            compose.waitForIdle()
            val before = model
            scenario.recreate()
            compose.waitForIdle()
            assertSame(before, model)
            assertEquals(Overlay.None, model.overlay)
            assertEquals(Phase.Unlocked, model.phase)
        }
    }

    @Test
    fun theLockRouteStartsLocked() {
        launch("lock:code").use {
            compose.waitForIdle()
            assertEquals(Phase.Locked, model.phase)
            assertEquals(Overlay.Lock, model.overlay)
            compose.onNodeWithTag(RootTags.LOCK).assertExists()
            compose.onNodeWithTag(RootTags.MAIN_STACK).assertDoesNotExist()
            model.didUnlock()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            assertEquals(Overlay.None, model.overlay)
            compose.onNodeWithTag(RootTags.MAIN_STACK).assertExists()
            compose.onNodeWithTag(RootTags.SECURITY).assertDoesNotExist()
        }
    }

    @Test
    fun theScreenIsNeverCapturedUnlessADebugLaunchAllowsIt() {
        launch("chats").use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
        }
    }

    @Test
    fun aDebugCaptureLaunchLiftsTheSecureFlag() {
        launch("chats", "U2AllowCapture").use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            }
            assertNotNull(app.model)
        }
    }
}
