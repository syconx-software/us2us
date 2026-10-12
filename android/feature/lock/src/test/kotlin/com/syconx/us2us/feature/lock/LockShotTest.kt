package com.syconx.us2us.feature.lock

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.sheet.U2SheetTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The drawn screens of 03 · Kilit against `ios/design/reference` and the layout JSON: 3.1, 3.2 (and
 * its light 8.3), 3.3, dark and light; the English versions are named by route. The frames are the
 * gate (±1 dp); `scripts/compare_shots.py` diffs the pictures for review. (3.4 is the app's: D1.)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class LockShotTest {
    @get:Rule
    val host = ShotHost()

    @Test
    fun faceId() {
        for (theme in ShotTheme.entries) {
            host.shoot("3.1-face-id", theme, "tr") { LockApp("faceid") }
            host.compose.assertAt(LockTags.BRAND, 165f, 112f, 72f, 63.19f)
            host.assertFrame(LockTags.BIOMETRIC_DISC, element = "r.2.0")
            host.assertFrame(LockTags.USE_CODE, element = "r.3.0")
        }
    }

    private fun assertCodeFrames() {
        host.compose.assertAt(LockTags.BADGE, 175f, 106f, 52f, 52f)
        host.compose.assertAt(LockTags.DOTS, 106f, 240f, 190f, 15f)
        host.compose.assertAt(LockTags.KEYPAD, 58f, 414f, 286f, 360f)
        host.compose.assertAt(LockTags.FORGOT, 24f, 788f, 354f, 46f)
    }

    @Test
    fun wrongCode() {
        for (theme in ShotTheme.entries) {
            host.shoot("3.2-kod-yanlis", theme, "tr") { LockApp("wrong") }
            assertCodeFrames()
            host.compose.onNodeWithTag(LockTags.MESSAGE, useUnmergedTree = true)
                .assertTextEquals("Kod yanlış. 2 yanlış daha olursa 1 dakika beklemen gerekir.")
        }
        host.shoot("8.3-kod-yanlis-acik", ShotTheme.Light, "tr") { LockApp("wrong") }
        assertCodeFrames()
    }

    @Test
    fun forgot() {
        for (theme in ShotTheme.entries) {
            host.shoot("3.3-kodu-unuttum", theme, "tr") { LockApp("forgot") }
            // The sheet hugs its content: the app's 3.3 copy (the paragraph and the honest restore
            // description) is longer than the drawn one, so the sheet starts that much higher.
            host.compose.assertAt(U2SheetTags.GRABBER, 182f, 413.02f - COPY_GROWTH, 38f, 5f)
            host.assertFrame(LockTags.FORGOT_RETRY, element = "r.2.3")
            host.compose.assertAt(LockTags.BADGE, 175f, 106f, 52f, 52f)
            host.compose.assertAt(LockTags.DOTS, 106f, 240f, 190f, 15f)
        }
    }

    @Test
    fun english() {
        for (state in listOf("faceid", "wrong", "forgot", "erase", "wiped", "throttled")) {
            host.shoot("lock:$state", ShotTheme.Dark, "en") { LockApp(state) }
        }
    }

    private companion object {
        /** How much taller the app's 3.3 copy is than the drawn one (dp). */
        const val COPY_GROWTH = 37.35f
    }
}
