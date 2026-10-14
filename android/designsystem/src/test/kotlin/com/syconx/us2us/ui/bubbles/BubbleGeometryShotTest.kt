package com.syconx.us2us.ui.bubbles

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.shot.Frame
import com.syconx.us2us.shot.LayoutSpec
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.bubbles.FrameChecks.Companion.RightAndVertical
import com.syconx.us2us.ui.bubbles.FrameChecks.Edge
import com.syconx.us2us.ui.tokens.DesignSpec
import com.syconx.us2us.ui.tokens.Shots
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The 5.1 and 5.2 bubbles laid out like the conversation column, against the design's layout JSON
 * (the geometry gate: every frame within 1 dp) and, for review, the reference PNGs (region diffs
 * written next to the shots). The header is the conversation screen's; it is not drawn here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class BubbleGeometryShotTest {
    @get:Rule
    val host = ShotHost(environment = BubbleShotEnvironment, outputDir = BubbleShotDir)

    @Test
    fun conversationDark() = conversation("bubbles-5.1", ShotTheme.Dark, "tr", "5.1-sohbet", "5.1-sohbet.png")

    @Test
    fun conversationLight() = conversation("bubbles-8.2", ShotTheme.Light, "tr", "8.2-sohbet-acik", "8.2-sohbet-acik.png")

    @Test
    fun conversationEnglish() = conversation("bubbles-9.2", ShotTheme.Dark, "en", "9.2-chat-en", "9.2-chat-en.png")

    @Test
    fun mediaDark() = media("bubbles-5.2", ShotTheme.Dark, "reference/5.2-medya-ve-konum.png")

    @Test
    fun mediaLight() = media("bubbles-5.2", ShotTheme.Light, "reference/light/5.2-medya-ve-konum.png")

    private fun conversation(name: String, theme: ShotTheme, lang: String, design: String, reference: String) {
        val messages = DesignFixtures.denizMessages(english = lang == "en")
        host.render(theme, lang) { ConversationSpecimen(messages, top = 81.13.dp, sentenceFor = MessageId("d6")) }
        val shot = host.capture(name, design = null)
        val layout = LayoutSpec.load(design)
        val checks = FrameChecks(host.compose, layout)
        fun text(id: String) = checkNotNull(layout[id].text) { "$id has no text" }

        checks.text(text("r.2.0.1"), "r.2.0.1")
        checks.tag("msg.d1", "r.2.1")
        checks.text(text("r.2.1.0"), "r.2.1.0")
        checks.text(text("r.2.1.1"), "r.2.1.1")
        // The photo bubble keeps 14 below for its reaction chip.
        checks.tag("msg.d2", "r.2.2") { it.copy(h = it.h + 14f) }
        checks.description(string(R.string.common_media_photo), "r.2.2.0")
        checks.text(text("r.2.2.1"), "r.2.2.1", edges = setOf(Edge.X, Edge.Y, Edge.H)) { Frame(it.x + 8f, it.y + 2f, it.w - 16f, it.h - 2f) }
        checks.text(text("r.2.2.2"), "r.2.2.2")
        checks.description("❤️", "r.2.2.3")
        checks.tag("msg.d3", "r.2.3")
        checks.tag("bubble.quote", "r.2.3.0")
        checks.text(text("r.2.3.0.0.0"), "r.2.3.0.0.0", edges = setOf(Edge.X, Edge.Y, Edge.H)) { it.copy(x = it.x + 18f) }
        checks.text(text("r.2.3.0.0.1"), "r.2.3.0.0.1", edges = setOf(Edge.X, Edge.Y, Edge.H)) { it.copy(x = it.x + 19f) }
        checks.text(text("r.2.3.1"), "r.2.3.1", edges = setOf(Edge.X, Edge.Y, Edge.H)) { it.copy(x = it.x + 7f) }
        checks.text("18:05", "r.2.3.2", edges = setOf(Edge.X, Edge.Y, Edge.H)) { Frame(it.x, it.y + 1.75f, 32.92f, 11.5f) }
        checks.tag("msg.d4", "r.2.4")
        checks.description(string(R.string.common_a11y_voice_play), "r.2.4.0.0")
        checks.tag("voice.waveform", "r.2.4.0.1")
        checks.tag("voice.time", "r.2.4.0.2")
        checks.text(text("r.2.4.1"), "r.2.4.1")
        checks.tag("msg.d5", "r.2.5")
        checks.text(text("r.2.5.0"), "r.2.5.0")
        checks.text("18:09", "r.2.5.1", edges = setOf(Edge.X, Edge.Y, Edge.H)) { Frame(it.x, it.y + 1.75f, 32.92f, 11.5f) }
        checks.tag("msg.d6", "r.2.6")
        checks.text(text("r.2.6.0"), "r.2.6.0")
        // The timer badge: the 13 dp glyph, 3 dp, then the text (Mono 10.5, centred on the glyph).
        checks.text(text("r.2.6.1.0"), "r.2.6.1.0", edges = setOf(Edge.X, Edge.Y, Edge.H)) { Frame(it.x + 16f, it.y + 1.25f, it.w - 16f, 10.5f) }
        checks.text("18:10", "r.2.6.1.1", edges = setOf(Edge.X, Edge.Y, Edge.H)) { Frame(it.x, it.y + 1.75f, 32.92f, 11.5f) }
        // The sentence is right-aligned: its end and its line must match; its start follows the text's width.
        // The design's row includes its 4 dp end padding; the node's frame is the content's.
        checks.tag("sentence", "r.2.7", edges = RightAndVertical) { it.copy(w = it.w - 4f) }
        checks.check("sentence text", hasText(text("r.2.7.1"), substring = true), "r.2.7.1", 1f, RightAndVertical) { it }
        composerChecks(checks, layout)
        checks.assertAll()

        compareRegions(name, shot, File(DesignSpec.directory, "reference/$reference"), theme)
    }

    private fun media(name: String, theme: ShotTheme, reference: String) {
        val messages = DesignFixtures.mertMessages(english = false).filter { it.id.value.startsWith("m") && !it.id.value.startsWith("ms") }
        host.render(theme, "tr") { ConversationSpecimen(messages, top = 20.66.dp) }
        val shot = host.capture(name, design = null)
        val layout = LayoutSpec.load("5.2-medya-ve-konum")
        val checks = FrameChecks(host.compose, layout)
        fun text(id: String) = checkNotNull(layout[id].text) { "$id has no text" }

        checks.tag("msg.m1", "r.2.0")
        checks.text(text("r.2.0.0.1.0"), "r.2.0.0.1.0", edges = setOf(Edge.X, Edge.Y, Edge.H))
        checks.text(text("r.2.0.0.1.1"), "r.2.0.0.1.1", edges = setOf(Edge.X, Edge.Y, Edge.H))
        checks.text("12:40", "r.2.0.1", edges = setOf(Edge.X, Edge.Y, Edge.H)) { Frame(it.x, it.y + 1.75f, 32.92f, 11.5f) }
        checks.tag("msg.m2", "r.2.1")
        checks.description("Video, 0:18, 12,4 MB", "r.2.1.0")
        checks.text(text("r.2.1.0.1"), "r.2.1.0.1", edges = setOf(Edge.X, Edge.Y, Edge.H)) { Frame(it.x + 7f, it.y + 3f, it.w - 14f, it.h - 6f) }
        checks.text("12:52", "r.2.1.1")
        checks.tag("msg.m3", "r.2.2")
        checks.text(text("r.2.2.0.1.0"), "r.2.2.0.1.0", edges = setOf(Edge.X, Edge.Y, Edge.H))
        checks.text(text("r.2.2.0.1.1"), "r.2.2.0.1.1", edges = setOf(Edge.X, Edge.Y, Edge.H))
        checks.text("17:58", "r.2.2.1")
        checks.tag("msg.m4", "r.2.3")
        checks.text(text("r.2.3.1.0.0"), "r.2.3.1.0.0", edges = setOf(Edge.X, Edge.Y, Edge.H)) { it.copy(x = it.x + 21f) }
        checks.text(text("r.2.3.1.0.1"), "r.2.3.1.0.1", edges = setOf(Edge.X, Edge.Y, Edge.H))
        checks.text("Durdur", "r.2.3.1.1", edges = setOf(Edge.Right, Edge.Y, Edge.H)) { Frame(it.x + 13f, it.y + (32f - 18.63f) / 2f, it.w - 26f, 18.63f) }
        checks.text("18:01", "r.2.3.2", edges = setOf(Edge.X, Edge.Y, Edge.H)) { Frame(it.x, it.y + 1.75f, 32.92f, 11.5f) }
        checks.tag("msg.m5", "r.2.4")
        checks.text(text("r.2.4.0"), "r.2.4.0", edges = setOf(Edge.X, Edge.Y, Edge.H)) { it.copy(x = it.x + 24f) }
        checks.tag("msg.m6", "r.2.5")
        checks.text(text("r.2.5.0.1.0"), "r.2.5.0.1.0", edges = setOf(Edge.X, Edge.Y, Edge.H))
        // Greedy wrapping: “…açmak için / dokun” on two lines, 220 wide.
        checks.text(text("r.2.5.0.1.1"), "r.2.5.0.1.1")
        checks.text("18:40", "r.2.5.1")
        checks.description("Sticker: tamam!", "r.2.6")
        composerChecks(checks, layout)
        checks.assertAll()

        compareRegions(name, shot, File(DesignSpec.directory, reference), theme)
    }

    private fun composerChecks(checks: FrameChecks, layout: LayoutSpec) {
        checks.tag("composer", "r.3")
        checks.tag("composer.field", "r.3.1")
        checks.tag("composer.action", "r.3.2")
        checks.text(checkNotNull(layout["r.3.1.0"].text), "r.3.1.0", edges = setOf(Edge.X, Edge.Y, Edge.H))
        checks.description(string(R.string.common_a11y_composer_attach), "r.3.0")
    }

    private fun string(id: Int): String = ApplicationProvider.getApplicationContext<Context>().resources.getString(id)

    /**
     * Writes side-by-sides of the bubble column (below the header, above the composer) and of the
     * composer, and fails only on a gross difference: pixel parity is for review (text shaping and
     * emoji differ), the frames above are the gate.
     */
    private fun compareRegions(name: String, shot: File, reference: File, theme: ShotTheme) {
        val variant = "${theme.raw}-$name"
        val column = Shots.compare(shot, reference, intArrayOf(0, 110 * 3, 1206, (780 - 110) * 3), "$variant-column")
        val composer = Shots.compare(shot, reference, intArrayOf(0, 780 * 3, 1206, 2520 - 780 * 3), "$variant-composer")
        println("$variant column: $column")
        println("$variant composer: $composer")
        assertTrue("$variant column differs grossly: $column", column.meanAbsolute < 12.0)
        assertTrue("$variant composer differs grossly: $composer", composer.meanAbsolute < 12.0)
    }
}
