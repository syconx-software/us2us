package com.syconx.us2us.shot

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class ShotHostTest {
    private val outputDir = File(ShotPaths.shotsDir, "smoke")

    @get:Rule
    val host = ShotHost(outputDir = outputDir)

    @Test
    fun shotsAreTheReferenceSize() {
        val file = host.shoot("host-size", ShotTheme.Dark, "tr") {
            Box(Modifier.fillMaxSize().background(Color(0xFF161826)))
        }
        assertEquals(File(outputDir, "dark-tr/host-size.png"), file)
        val image = ImageIO.read(file)
        assertEquals(DesignMoment.WIDTH_PX, image.width)
        assertEquals(DesignMoment.HEIGHT_PX, image.height)
        assertEquals(0xFF161826.toInt(), image.getRGB(600, 1300))
        assertEquals(0xFF161826.toInt(), image.getRGB(0, 0))
        assertEquals(0xFF161826.toInt(), image.getRGB(1205, 2621))
    }

    @Test
    fun insetsAreTheDesignSafeArea() {
        val seen = mutableMapOf<String, Int>()
        val file = host.shoot("host-insets", ShotTheme.Light, "tr") {
            val density = LocalDensity.current
            val status = WindowInsets.statusBars.getTop(density)
            val navigation = WindowInsets.navigationBars.getBottom(density)
            val safeTop = WindowInsets.safeDrawing.getTop(density)
            val safeBottom = WindowInsets.safeDrawing.getBottom(density)
            SideEffect {
                seen["status"] = status
                seen["navigation"] = navigation
                seen["safeTop"] = safeTop
                seen["safeBottom"] = safeBottom
            }
            Box(Modifier.fillMaxSize().background(Color.Red)) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).background(Color.White))
            }
        }
        assertEquals(186, seen["status"])
        assertEquals(102, seen["navigation"])
        assertEquals(186, seen["safeTop"])
        assertEquals(102, seen["safeBottom"])
        val image = ImageIO.read(file)
        assertEquals(0xFFFF0000.toInt(), image.getRGB(600, 185))
        assertEquals(0xFFFFFFFF.toInt(), image.getRGB(600, 186))
        assertEquals(0xFFFFFFFF.toInt(), image.getRGB(600, 2519))
        assertEquals(0xFFFF0000.toInt(), image.getRGB(600, 2520))
    }

    @Test
    fun withoutDesignInsetsTheWindowInsetsPassThrough() {
        var status = -1
        host.render(ShotTheme.Dark, "tr", ShotOptions(designInsets = false)) {
            val top = WindowInsets.statusBars.getTop(LocalDensity.current)
            SideEffect { status = top }
        }
        assertTrue("status bar inset $status", status in 0 until 186)
    }

    @Test
    fun themeAndLanguageFollowTheShot() {
        val seen = mutableListOf<String>()

        @Composable
        fun Probe() {
            val dark = isSystemInDarkTheme()
            val locale = LocalConfiguration.current.locales[0]
            val spec = LocalShotSpec.current
            SideEffect { seen += "${if (dark) "dark" else "light"}-${locale.language}-${spec?.theme?.raw}-${spec?.lang}" }
        }
        host.render(ShotTheme.Dark, "tr") { Probe() }
        host.render(ShotTheme.Light, "en") { Probe() }
        host.render(ShotTheme.Light, "tr") { Probe() }
        assertEquals(listOf("dark-tr-dark-tr", "light-en-light-en", "light-tr-light-tr"), seen.distinct())
        assertEquals("tr", Locale.getDefault().language)
    }

    @Test
    fun theClockStandsAtTheDesignMoment() {
        var now = 0L
        host.render {
            val spec = LocalShotSpecValue
            SideEffect { now = spec.nowMillis }
        }
        assertEquals("Europe/Istanbul", TimeZone.getDefault().id)
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", Locale.US)
        assertEquals("2026-10-08 18:52:00 Thursday", format.format(Date(now)))
    }

    @Test
    fun loopingAnimationsStopWhereTheReferencesCaughtThem() {
        var progress = -1f
        host.shoot("host-animation", ShotTheme.Dark, "tr") {
            val transition = rememberInfiniteTransition(label = "loop")
            val value by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(4_000, easing = LinearEasing), RepeatMode.Restart),
                label = "progress",
            )
            val current = value
            SideEffect { progress = current }
            Box(Modifier.fillMaxSize().background(Color.Black))
        }
        // Two seconds into a four-second loop (give or take the first frame and frame rounding).
        assertEquals(0.5f, progress, 0.02f)
        val before = progress
        host.advance(1_000)
        assertEquals(before + 0.25f, progress, 0.02f)
    }

    @Test
    fun framesAreCheckedAgainstTheLayout() {
        val layout = LayoutSpec.load("3.2-kod-yanlis")
        val tags = listOf("r.0.0", "r.0.1", "r.3.0.0", "r.3.0.11", "frame.3.0")
        host.render { ElementsAt(layout, tags, nudge = mapOf("r.3.0.11" to 0.6f)) }
        host.useLayout("3.2")
        host.assertFrames(*tags.toTypedArray())
        host.assertFrame("r.3.0.11", tolerance = 0.7.dp)
        host.compose.assertSize("r.3.0.0", 78.dp, 78.dp)

        host.render { ElementsAt(layout, listOf("r.3.0.0", "r.3.0.1"), nudge = mapOf("r.3.0.1" to 2f)) }
        try {
            host.assertFrames("r.3.0.0", "r.3.0.1")
            fail("a frame 2 dp off passed")
        } catch (expected: AssertionError) {
            val message = expected.message.orEmpty()
            assertTrue(message, message.contains("1 of 2 frames differ from 3.2-kod-yanlis"))
            assertTrue(message, message.contains("r.3.0.1: expected [x=162 y=414 w=78 h=78], got [x=164 y=414 w=78 h=78] — off by x+2"))
            assertFalse(message, message.contains("r.3.0.0:"))
        }
        try {
            host.assertFrame("r.9.9")
            fail("a missing node passed")
        } catch (expected: Throwable) {
            assertTrue(expected.message, expected.message.orEmpty().contains("r.9.9"))
        }
    }

    @Test
    fun aDrawnScreenSelectsItsLayoutAndSidecar() {
        val file = host.shoot("lock:wrong", ShotTheme.Light, "tr", design = "3.2-kod-yanlis") {
            Box(Modifier.fillMaxSize().background(Color.White))
        }
        assertEquals("lock_wrong.png", file.name)
        assertEquals("3.2-kod-yanlis", host.layout?.name)
        val sidecar = File(file.parentFile, "lock_wrong.json").readText()
        @Suppress("UNCHECKED_CAST")
        val json = Json.parse(sidecar) as Map<String, Any?>
        assertEquals("lock:wrong", json["name"])
        assertEquals("light", json["theme"])
        assertEquals("tr", json["lang"])
        assertEquals("3.2-kod-yanlis", json["design"])
        assertEquals(File(System.getProperty("user.dir") ?: ".").absoluteFile.name, File(json["module"] as String).name)
        assertEquals("com.syconx.us2us.shot.ShotHostTest#aDrawnScreenSelectsItsLayoutAndSidecar", json["test"])
    }
}

/** The current spec's clock, for content that has no environment of its own. */
private val LocalShotSpecValue: ShotSpec
    @Composable get() = checkNotNull(LocalShotSpec.current)

/** Boxes at the layout frames of [ids], each tagged with its id; [nudge] moves one to the right by that many dp. */
@Composable
private fun ElementsAt(layout: LayoutSpec, ids: List<String>, nudge: Map<String, Float> = emptyMap()) {
    Box(Modifier.fillMaxSize()) {
        for (id in ids) {
            val frame = layout.frame(id)
            Box(
                Modifier
                    .offset((frame.x + (nudge[id] ?: 0f)).dp, frame.y.dp)
                    .size(frame.w.dp, frame.h.dp)
                    .testTag(id)
                    .background(Color.Gray),
            )
        }
    }
}
