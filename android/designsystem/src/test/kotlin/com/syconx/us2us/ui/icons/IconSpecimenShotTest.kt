package com.syconx.us2us.ui.icons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.Shots
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The icon specimen (the iOS `#Preview("Phosphor glyphs")`): every glyph and weight at 24 dp,
 * regular in `text`, fill in `accent300`, with its name, in both themes; and the composable's
 * box, tint and semantics.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class IconSpecimenShotTest {
    @get:Rule
    val compose = createComposeRule()

    private val combinations = PhosphorGlyph.entries.flatMap { glyph ->
        listOf(PhosphorWeight.Regular, PhosphorWeight.Fill).filter { it in glyph.availableWeights }.map { glyph to it }
    }

    @Test
    fun specimenDark() = specimen(dark = true)

    @Test
    fun specimenLight() = specimen(dark = false)

    @OptIn(ExperimentalLayoutApi::class)
    private fun specimen(dark: Boolean) {
        compose.setContent {
            U2Theme(dark = dark, reduceMotion = false) {
                FlowRow(
                    Modifier.fillMaxSize().background(U2Theme.colors.bg).padding(horizontal = 12.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for ((glyph, weight) in combinations) {
                        Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            PhosphorIcon(
                                glyph,
                                size = 24.dp,
                                weight = weight,
                                tint = if (weight == PhosphorWeight.Fill) U2Theme.colors.accent300 else U2Theme.colors.text,
                                contentDescription = glyph.assetName(weight),
                            )
                            U2Text(
                                text = glyph.raw + if (weight == PhosphorWeight.Fill) "·f" else "",
                                style = U2TextStyles.monoMicro,
                                color = U2Theme.colors.textSecondary,
                                textAlign = TextAlign.Center,
                                maxLines = 3,
                                overflow = TextOverflow.Clip,
                                modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                            )
                        }
                    }
                }
            }
        }
        compose.onAllNodesWithContentDescription("ph-", substring = true).assertCountEquals(combinations.size)
        Shots.capture(compose, "icons-specimen-${if (dark) "dark" else "light"}")
    }

    /** The box is the glyph size, the glyph is drawn in the tint, and a decorative icon has no semantics. */
    @Test
    fun boxTintAndSemantics() {
        compose.setContent {
            U2Theme(dark = true, reduceMotion = false) {
                Row(Modifier.background(Color.Black)) {
                    Box(Modifier.testTag("ghost")) {
                        PhosphorIcon(PhosphorGlyph.Ghost, size = 16.dp, tint = Color.Red)
                    }
                    Box(Modifier.testTag("x").size(40.dp), contentAlignment = Alignment.Center) {
                        PhosphorIcon(PhosphorGlyph.X, size = 21.dp, contentDescription = "Kapat")
                    }
                }
            }
        }
        val ghost = compose.onNodeWithTag("ghost").fetchSemanticsNode()
        assertEquals(48, ghost.size.width)
        assertEquals(48, ghost.size.height)
        // Decorative: no description reaches the accessibility tree below the tagged box.
        assertTrue(ghost.children.none { it.config.contains(SemanticsProperties.ContentDescription) })
        compose.onAllNodesWithContentDescription("Kapat").assertCountEquals(1)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)).assertCountEquals(1)

        val file = Shots.capture(compose, "icons-tint-probe")
        val image: BufferedImage = ImageIO.read(file)
        var red = 0
        var other = 0
        for (y in 0 until 48) for (x in 0 until 48) {
            val p = image.getRGB(x, y)
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (g > 8 || b > 8) other++ else if (r > 40) red++
        }
        assertTrue("the ghost is drawn in its tint ($red red pixels)", red > 300)
        assertEquals("nothing but the tint and the background", 0, other)
    }
}
