package com.syconx.us2us.ui.env

import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ScreenAwakeTest {
    @get:Rule val compose = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun mediaDisposalDoesNotClearPreferenceAndAnotherMediaRequestSurvives() {
        val preference = mutableStateOf(false)
        val first = mutableStateOf(false)
        val second = mutableStateOf(false)
        lateinit var view: View
        lateinit var owner: Owner
        compose.runOnIdle { owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED } }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                ScreenAwakeScope(preference.value, contentVisible = true) {
                    val current = LocalView.current
                    SideEffect { view = current }
                    if (first.value) KeepScreenAwakeForMedia(true)
                    if (second.value) KeepScreenAwakeForMedia(true)
                }
            }
        }
        compose.runOnIdle { assertFalse(view.keepScreenOn); preference.value = true; first.value = true }
        compose.runOnIdle { assertTrue(view.keepScreenOn); first.value = false }
        compose.runOnIdle { assertTrue(view.keepScreenOn); preference.value = false; first.value = true; second.value = true }
        compose.runOnIdle { assertTrue(view.keepScreenOn); first.value = false }
        compose.runOnIdle { assertTrue(view.keepScreenOn); second.value = false }
        compose.runOnIdle { assertFalse(view.keepScreenOn) }
    }

    @Test fun coverAndLifecycleAlwaysReleaseTheRealViewFlag() {
        val visible = mutableStateOf(true)
        lateinit var view: View
        lateinit var owner: Owner
        compose.runOnIdle { owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED } }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                ScreenAwakeScope(preference = true, contentVisible = visible.value) {
                    val current = LocalView.current
                    SideEffect { view = current }
                    KeepScreenAwakeForMedia(true)
                }
            }
        }
        compose.runOnIdle { assertTrue(view.keepScreenOn); visible.value = false }
        compose.runOnIdle {
            assertFalse(view.keepScreenOn)
            owner.registry.currentState = Lifecycle.State.STARTED
            visible.value = true
        }
        compose.runOnIdle { assertFalse(view.keepScreenOn); owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle {
            assertTrue(view.keepScreenOn)
            owner.registry.currentState = Lifecycle.State.STARTED
            assertFalse("Pause clears the flag synchronously", view.keepScreenOn)
        }
    }
}
