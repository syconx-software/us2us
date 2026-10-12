package com.syconx.us2us.shot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A module's environment wraps every shot and sees the shot's spec. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ShotHost.SDK])
class ShotEnvironmentTest {
    private val wrapped = mutableListOf<String>()

    @get:Rule
    val host = ShotHost(
        environment = ShotEnvironment { spec, content ->
            SideEffect { wrapped += "${spec.theme.raw}-${spec.lang}-${spec.frozenClock}-${spec.designInsets}" }
            Box(Modifier.fillMaxSize().background(if (spec.isDark) Color.Black else Color.White)) { content() }
        },
        outputDir = File(ShotPaths.shotsDir, "smoke"),
    )

    @Test
    fun wrapsEveryShot() {
        val light = host.shoot("environment-light", ShotTheme.Light, "en") {}
        val dark = host.shoot("environment-dark", ShotTheme.Dark, "tr", options = ShotOptions(designInsets = false)) {}
        assertEquals(listOf("light-en-true-true", "dark-tr-true-false"), wrapped.distinct())
        assertEquals(0xFFFFFFFF.toInt(), ImageIO.read(light).getRGB(600, 1300))
        assertEquals(0xFF000000.toInt(), ImageIO.read(dark).getRGB(600, 1300))
    }
}
