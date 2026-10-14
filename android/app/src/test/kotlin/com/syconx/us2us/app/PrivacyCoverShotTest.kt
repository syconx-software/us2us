package com.syconx.us2us.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import com.syconx.us2us.shot.DesignMoment
import com.syconx.us2us.shot.ShotEnvironment
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.env.LocalDesignInsets
import com.syconx.us2us.ui.security.PrivacyCover
import com.syconx.us2us.ui.security.PrivacyCoverTags
import com.syconx.us2us.ui.security.PrivacySwitcherMock
import com.syconx.us2us.ui.security.PrivacySwitcherTags
import com.syconx.us2us.ui.security.rememberPrivacySkeletonSnapshot
import com.syconx.us2us.ui.theme.U2Theme
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 3.4 Uygulama değiştirici, dark and light: the privacy cover over the blurred design skeleton,
 * inside the recents mock, against `reference/{,light/}3.4-uygulama-degistirici.png` and its layout
 * (the `lock:switcher` state); and the cover alone at full size (`lock:cover`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class PrivacyCoverShotTest {
    @get:Rule
    val host = ShotHost(environment = CoverShots)

    @Test
    fun switcher() {
        for (theme in ShotTheme.entries) {
            host.shoot("3.4-uygulama-degistirici", theme, "tr") {
                PrivacySwitcherMock(rememberPrivacySkeletonSnapshot())
            }
            host.assertFrame(PrivacySwitcherTags.APP_LABEL, element = "r.2")
            host.assertFrame(PrivacySwitcherTags.CARD, element = "r.3")
            // Inside the card the cover is scaled by 0.7015: compare the drawn (transformed) bounds.
            assertDrawnFrame(PrivacyCoverTags.MARK, "r.3.2.0")
            assertDrawnFrame(PrivacyCoverTags.WORDMARK, "r.3.2.1")
            assertDrawnFrame(PrivacyCoverTags.LOCKED, "r.3.2.2")
        }
    }

    /** The node's bounds after its layers' transforms, against [element]'s frame, within 1 dp on every edge. */
    private fun assertDrawnFrame(tag: String, element: String) {
        val bounds = host.compose.onNode(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val density = DesignMoment.DENSITY
        val actual = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).map { it / density }
        val frame = checkNotNull(host.layout)[element].frame
        val expected = listOf(frame.x, frame.y, frame.x + frame.w, frame.y + frame.h).map { it.toFloat() }
        val off = actual.zip(expected).maxOf { (a, e) -> abs(a - e) }
        assertTrue("$tag ($element): drawn at $actual, designed at $expected", off <= 1f)
    }

    @Test
    fun switcherInEnglish() {
        host.shoot("lock:switcher", ShotTheme.Dark, "en") { PrivacySwitcherMock(rememberPrivacySkeletonSnapshot()) }
    }

    @Test
    fun coverAtFullSize() {
        for (theme in ShotTheme.entries) {
            host.shoot("lock:cover", theme, "tr") { PrivacyCover(rememberPrivacySkeletonSnapshot()) }
        }
    }

    @Test
    fun coverWithoutASnapshot() {
        for (theme in ShotTheme.entries) {
            host.shoot("privacy-cover-solid", theme, "tr") { PrivacyCover(snapshot = null) }
        }
    }
}

private val CoverShots = ShotEnvironment { spec, content ->
    U2Theme(dark = spec.isDark, reduceMotion = false) {
        CompositionLocalProvider(LocalDesignInsets provides spec.designInsets) { content() }
    }
}
