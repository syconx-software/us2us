package com.syconx.us2us.feature.lock

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every lock harness state, dark and light, named by route (`lock_throttled`) as the iOS captures
 * are, for comparison with the simulator; and what the drawn states say.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class LockStatesShotTest {
    @get:Rule
    val host = ShotHost()

    @Test
    fun everyState() {
        for (theme in ShotTheme.entries) {
            for (state in LockHarness.states) {
                host.shoot("lock:$state", theme, "tr") { LockApp(state) }
            }
        }
    }

    @Test
    fun theStatesSayWhatHappened() {
        host.render(ShotTheme.Dark, "tr") { LockApp("lastchance") }
        host.compose.onNodeWithTag(LockTags.MESSAGE, useUnmergedTree = true)
            .assertTextEquals("Kod yanlış. 1 yanlış daha olursa 1 dakika beklemen gerekir.")

        host.render(ShotTheme.Dark, "tr") { LockApp("throttled") }
        host.compose.onNodeWithTag(LockTags.MESSAGE, useUnmergedTree = true)
            .assertTextEquals("Çok fazla yanlış deneme. 01:00 sonra tekrar\u00A0dene.")
        host.compose.onNodeWithTag(LockTags.KEYPAD).assertExists()

        host.render(ShotTheme.Dark, "en") { LockApp("throttled") }
        host.compose.onNodeWithTag(LockTags.MESSAGE, useUnmergedTree = true)
            .assertTextEquals("Too many wrong attempts. Try again in\u00A001:00.")

        host.render(ShotTheme.Dark, "tr") { LockApp("unusable") }
        host.compose.onNodeWithTag(LockTags.MESSAGE, useUnmergedTree = true)
            .assertTextEquals("Bu telefon verilerinin kilidini artık açamıyor. Yedeğin varsa “Kodu unuttum” ile geri yükleyebilirsin.")

        host.render(ShotTheme.Dark, "tr") { LockApp("restore") }
        host.compose.onNodeWithTag(RESTORE_SCREEN_TAG).assertExists()
    }

    @Test
    fun theThrottledKeypadWaits() {
        host.render(ShotTheme.Dark, "tr") { LockApp("throttled") }
        host.compose.onNodeWithTag(LockTags.KEYPAD).assertExists()
        // Every key of the waiting keypad is disabled.
        host.compose.onNodeWithTag(LockTags.FORGOT).assertExists()
        host.compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("1"))[0].assertIsNotEnabled()
    }
}
