package com.syconx.us2us.shot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The JVM screenshot host renders at the iOS reference size: 402 × 874 dp at 3× = 1206 × 2622 px. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class RobolectricSmokeTest {
    @get:Rule
    val compose = createComposeRule()

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun rendersAtReferenceSize() {
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF161826)))
        }
        val androidDir = File(System.getProperty("us2us.androidDir") ?: ".")
        val file = File(androidDir, "build/shots/smoke/robolectric-smoke.png")
        compose.onRoot().captureRoboImage(file, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        val image = ImageIO.read(file)
        assertEquals(1206, image.width)
        assertEquals(2622, image.height)
        assertEquals(0xFF161826.toInt(), image.getRGB(600, 1300))
    }
}
