package com.syconx.us2us.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.DesignArtwork
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.bubbles.BubbleShotDir
import com.syconx.us2us.ui.bubbles.BubbleShotEnvironment
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.rememberU2BackdropSource
import com.syconx.us2us.ui.theme.u2BackdropSource
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The media atoms in both themes (the iOS previews “Media glyphs”, “Design artwork” and
 * “Kaydetti”): every media glyph, the glass circle with every state over the poster, the rings,
 * the artwork and the saved chip.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class MediaSpecimenShotTest {
    @get:Rule
    val host = ShotHost(environment = BubbleShotEnvironment, outputDir = BubbleShotDir)

    @Test
    fun specimenDark() {
        host.render(ShotTheme.Dark, "tr") { Specimen() }
        host.capture("media-specimen", design = null)
    }

    @Test
    fun specimenLight() {
        host.render(ShotTheme.Light, "tr") { Specimen() }
        host.capture("media-specimen", design = null)
    }

    @Composable
    private fun Specimen() {
        val colors = U2Theme.colors
        Column(
            Modifier.fillMaxSize().background(colors.bg).padding(start = 20.dp, top = 80.dp, end = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Label("MediaGlyph · 24")
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                for (glyph in MediaGlyph.entries) MediaGlyphIcon(glyph, 24.dp, tint = colors.text)
            }
            Label("MediaGlassCircle · MediaStateGlyph")
            val states = listOf(
                MediaAvailability.Ready,
                MediaAvailability.Remote,
                MediaAvailability.Downloading(0.4),
                MediaAvailability.Downloading(null),
                MediaAvailability.Unavailable,
            )
            val source = rememberU2BackdropSource()
            Box(Modifier.size(362.dp, 124.dp).clip(RoundedCornerShape(15.dp))) {
                DesignArtworkView(DesignArtwork.SunsetVideo, Modifier.fillMaxSize().u2BackdropSource(source))
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (state in states) MediaGlassCircle(source = source) { MediaStateGlyph(state) }
                }
            }
            Label("Player circle · 64")
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (state in listOf(MediaAvailability.Ready, MediaAvailability.Remote, MediaAvailability.Downloading(0.75))) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(32.dp)).background(colors.mediaViewerBg)) {
                        MediaGlassCircle(diameter = 64.dp) { MediaStateGlyph(state, diameter = 64.dp) }
                    }
                }
            }
            Label("MediaProgressRing")
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                for (fraction in listOf(0.0, 0.25, 0.5, 0.9, null)) {
                    MediaProgressRing(fraction, Modifier.size(28.dp), tint = colors.accent300, track = colors.accent300.copy(alpha = 0.25f))
                }
                MediaProgressRing(
                    0.62,
                    Modifier.size(48.dp, 56.dp),
                    shape = RingShape.TopStartRoundedRect(13.dp),
                    tint = colors.accent300,
                    track = colors.text.copy(alpha = 0.18f),
                )
            }
            Label("DesignArtwork · SavedChip")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                DesignArtworkView(DesignArtwork.NightPier, Modifier.size(120.dp, 80.dp).clip(RoundedCornerShape(15.dp)))
                DesignArtworkView(DesignArtwork.SunsetVideo, Modifier.size(112.dp, 63.dp).clip(RoundedCornerShape(15.dp)))
                SavedChip()
            }
        }
    }

    @Composable
    private fun Label(text: String) {
        U2Text(text, U2TextStyles.monoMeta, color = U2Theme.colors.textSecondary)
    }
}
