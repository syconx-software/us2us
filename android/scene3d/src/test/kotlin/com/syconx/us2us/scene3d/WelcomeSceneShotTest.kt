package com.syconx.us2us.scene3d

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.shot.ShotEnvironment
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotPaths
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.components.DotGridBackdrop
import com.syconx.us2us.ui.env.LocalU2Clock
import com.syconx.us2us.ui.theme.U2Theme
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The welcome scene as 1.1 places it — `bg`, the dot grid, the transparent canvas at y 84 — under
 * the frozen design clock. Robolectric has no GPU, so the composable draws its software still (the
 * same frame and shading as the GLES renderer). The canvas region is compared with
 * `ios/design/reference/{,light/}1.1-karsilama.png` and its frame with the layout's `r.1`.
 *
 * Shots: `android/build/shots/android/scene3d/<theme>-<lang>/welcome-scene.png` (outside the app
 * shot set: they are not a whole screen).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class WelcomeSceneShotTest {
    @get:Rule
    val host = ShotHost(environment = SceneShots, outputDir = File(ShotPaths.shotsDir, "android/scene3d"))

    private object SceneShots : ShotEnvironment {
        @Composable
        override fun Provide(spec: com.syconx.us2us.shot.ShotSpec, content: @Composable () -> Unit) {
            U2Theme(dark = spec.isDark, reduceMotion = false) {
                CompositionLocalProvider(LocalU2Clock provides DesignClock.frozen(Instant.ofEpochMilli(spec.nowMillis)), content = content)
            }
        }
    }

    @Composable
    private fun WelcomeBackdrop(dark: Boolean, frozenSeconds: Float?) {
        Box(Modifier.fillMaxSize().background(U2Theme.colors.bg)) {
            DotGridBackdrop()
            WelcomeScene(dark = dark, frozenSeconds = frozenSeconds, modifier = Modifier.padding(top = 84.dp).testTag("r.1"))
        }
    }

    /** Mean |Δ| per channel of the canvas rows (y 84–414) against the drawn reference. */
    private fun compareCanvas(shot: File, theme: ShotTheme, name: String): Double {
        val reference = File(ShotPaths.designDir, "reference/${if (theme == ShotTheme.Light) "light/" else ""}1.1-karsilama.png")
        val a = ImageIO.read(shot)
        val b = ImageIO.read(reference)
        val top = 84 * 3
        val height = 330 * 3
        val width = 1206
        val rowsA = a.getRGB(0, top, width, height, null, 0, width)
        val rowsB = b.getRGB(0, top, width, height, null, 0, width)
        SceneImages.writeSideBySide(name, width, height, rowsB, rowsA, SceneImages.diff(rowsB, rowsA))
        val (mean, over) = SceneImages.compare(rowsA, rowsB)
        println("$name: canvas vs reference mean |Δ| %.3f, %.3f %% over 24".format(java.util.Locale.ROOT, mean, over))
        return mean
    }

    @Test
    fun darkPinnedByTheFrozenClock() {
        // No explicit time: the frozen design clock pins the scene to the reference moment (4.5 s).
        val shot = host.shoot("welcome-scene", ShotTheme.Dark, "tr") { WelcomeBackdrop(dark = true, frozenSeconds = null) }
        host.compose.onNodeWithContentDescription("İki bardak ve aralarındaki hat").assertExists()
        host.useLayout("1.1-karsilama")
        host.assertFrame("r.1")
        val mean = compareCanvas(shot, ShotTheme.Dark, "welcome-scene-dark-compare")
        assertTrue("canvas mean |Δ| $mean", mean < MAX_MEAN)
    }

    @Test
    fun lightAtTheReferenceTime() {
        val shot = host.shoot("welcome-scene", ShotTheme.Light, "tr") {
            WelcomeBackdrop(dark = false, frozenSeconds = WelcomeSceneReference.seconds(dark = false))
        }
        host.useLayout("1.1-karsilama")
        host.assertFrame("r.1")
        val mean = compareCanvas(shot, ShotTheme.Light, "welcome-scene-light-compare")
        assertTrue("canvas mean |Δ| $mean", mean < MAX_MEAN)
    }

    @Test
    fun englishLabel() {
        host.render(ShotTheme.Dark, "en") { WelcomeBackdrop(dark = true, frozenSeconds = null) }
        host.compose.onNodeWithContentDescription("Two cups and the line between them").assertExists()
    }

    private companion object {
        /** The dot grid and the header glyphs at the canvas' top edge are not part of the scene. */
        const val MAX_MEAN = 1.5
    }
}
