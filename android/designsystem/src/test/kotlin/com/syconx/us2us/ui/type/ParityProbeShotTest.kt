package com.syconx.us2us.ui.type

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.theme.U2BoxShadows
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2BoxShadow
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.tokens.DesignSpec
import com.syconx.us2us.ui.tokens.Shots
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2TextStyles
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

/**
 * Pieces of the reference screens drawn with this package alone (type, colours, glow, stroke)
 * at their design frames, compared region by region with ios/design/reference.
 * The side-by-sides land in android/build/shots/android/designsystem as <name>-compare.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class ParityProbeShotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun reference(name: String, dark: Boolean) =
        File(DesignSpec.directory, if (dark) "reference/$name.png" else "reference/light/$name.png")

    /** 1.1: the outline CTA with its glow and the text button under it. */
    @Composable
    private fun WelcomeActions() {
        Box(Modifier.fillMaxSize().background(U2Theme.colors.bg)) {
            val shape = RoundedCornerShape(U2Radius.button)
            Box(
                Modifier
                    .offset(24.dp, 726.dp)
                    .size(354.dp, 54.dp)
                    .u2BoxShadow(shape, U2BoxShadows.glowButton)
                    .u2InnerStroke(U2Theme.colors.accent, 1.dp, shape),
                contentAlignment = Alignment.Center,
            ) {
                U2Text("Başla", U2TextStyles.button, color = U2Theme.colors.accent300)
            }
            Box(Modifier.offset(24.dp, 790.dp).size(354.dp, 44.dp), contentAlignment = Alignment.Center) {
                U2Text("Yedekten geri yükle", U2TextStyles.rowTitle, color = U2Theme.colors.textQuiet)
            }
        }
    }

    /** 1.2: the Doto clock (ROND 100, wght 800) and the date under it. */
    @Composable
    private fun NotificationsClock() {
        Box(Modifier.fillMaxSize().background(U2Theme.colors.bg)) {
            Box(Modifier.fillMaxWidth().offset(y = 131.dp), contentAlignment = Alignment.TopCenter) {
                U2Text("09:41", U2TextStyles.displayClock, color = U2Theme.colors.textClock)
            }
            Box(Modifier.fillMaxWidth().offset(y = 213.dp), contentAlignment = Alignment.TopCenter) {
                U2Text("Perşembe, 8 Ekim", U2TextStyles.label15, color = U2Theme.colors.textSecondary)
            }
        }
    }

    @Test
    fun welcomeActionsDark() = probe("1.1-karsilama", dark = true, region = intArrayOf(0, 2136, 1206, 420)) { WelcomeActions() }

    @Test
    fun welcomeActionsLight() = probe("1.1-karsilama", dark = false, region = intArrayOf(0, 2136, 1206, 420)) { WelcomeActions() }

    @Test
    fun clockDark() = probe("1.2-bildirim-yok", dark = true, region = intArrayOf(150, 330, 906, 400)) { NotificationsClock() }

    @Test
    fun clockLight() = probe("1.2-bildirim-yok", dark = false, region = intArrayOf(150, 330, 906, 400)) { NotificationsClock() }

    private fun probe(screen: String, dark: Boolean, region: IntArray, content: @Composable () -> Unit) {
        compose.setContent { U2Theme(dark = dark, reduceMotion = false) { content() } }
        val name = "probe-$screen-${if (dark) "dark" else "light"}"
        val shot = Shots.capture(compose, name)
        val diff = Shots.compare(shot, reference(screen, dark), region, name)
        println("$name: $diff")
        File(DesignSpec.androidDir, "build/shots/android/designsystem/$name.txt").writeText("$diff\n")
        assertTrue("$name: $diff", diff.meanAbsolute < 8.0)
    }
}
