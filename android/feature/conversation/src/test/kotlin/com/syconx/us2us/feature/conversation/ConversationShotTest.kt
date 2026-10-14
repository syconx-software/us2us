package com.syconx.us2us.feature.conversation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.RequestId
import com.syconx.us2us.feature.conversation.FrameChecks.Companion.Position
import com.syconx.us2us.feature.conversation.FrameChecks.Companion.RightAndVertical
import com.syconx.us2us.feature.conversation.FrameChecks.Edge
import com.syconx.us2us.shot.LayoutSpec
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 5.1, 5.3, 5.4, 5.5 and 4.4 with their states, against the design: the reference PNGs (compared by
 * `scripts/compare_shots.py`) and the layout JSON (the geometry gate: every checked frame within
 * 1 dp). Drawn screens are named after their design (`5.3-mesaj-eylemleri`); the other harness
 * states by their route (`conversation:deniz/5.1-failed`), as the iOS captures are.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class ConversationShotTest {
    private var rig = ConversationRig()

    @get:Rule
    val host = ShotHost(environment = conversationShots { rig })

    private val deniz = ChatId("deniz")

    // 5.1 Sohbet (8.2 light, 9.2 English)

    @Test
    fun conversation() {
        chat("5.1-sohbet", ShotTheme.Dark, "tr", "5.1-sohbet")
        chat("5.1-sohbet", ShotTheme.Light, "tr", "5.1-sohbet")
        chat("8.2-sohbet-acik", ShotTheme.Light, "tr", "8.2-sohbet-acik")
    }

    @Test
    fun conversationEnglish() {
        chat("9.2-chat-en", ShotTheme.Dark, "en", "9.2-chat-en")
        chat("9.2-chat-en", ShotTheme.Light, "en", "9.2-chat-en")
    }

    @Test
    fun conversationUrgent() {
        chat("5.1-sohbet-urgent", ShotTheme.Dark, "tr", "5.1-sohbet-urgent", urgent = true)
        chat("5.1-sohbet-urgent", ShotTheme.Light, "tr", "5.1-sohbet-urgent", urgent = true)
        chat("8.2-sohbet-acik-urgent", ShotTheme.Light, "tr", "8.2-sohbet-acik-urgent", urgent = true)
        chat("9.2-chat-en-urgent", ShotTheme.Dark, "en", "9.2-chat-en-urgent", urgent = true)
        chat("9.2-chat-en-urgent", ShotTheme.Light, "en", "9.2-chat-en-urgent", urgent = true)
    }

    private fun chat(name: String, theme: ShotTheme, lang: String, design: String, urgent: Boolean = false) {
        rig = ConversationRig(english = lang == "en", ghostUrgent = urgent)
        host.shoot(name, theme, lang, design = design) { ConversationScreen(deniz) }
        val layout = LayoutSpec.load(design)
        val checks = FrameChecks(host.compose, layout)
        headerChecks(checks, layout)
        checks.tag("message.d1", "r.2.1")
        // The photo bubble keeps 14 below for its reaction chip.
        checks.tag("message.d2", "r.2.2") { it.copy(h = it.h + 14f) }
        checks.tag("message.d3", "r.2.3")
        checks.tag("message.d4", "r.2.4")
        checks.tag("message.d5", "r.2.5")
        checks.tag("message.d6", "r.2.6")
        // The sentence is right-aligned: its end and its line must match (the design's row includes its 4 dp end padding).
        checks.tag("message.d6.status", "r.2.7", RightAndVertical)
        val composer = if (urgent) "r.4" else "r.3"
        if (urgent) checks.tag("conversation.urgent", "r.3")
        checks.tag("composer", composer)
        checks.tag("composer.field", "$composer.1")
        checks.tag("composer.action", "$composer.2")
        checks.description(string(R.string.common_a11y_composer_attach), "$composer.0")
        checks.assertAll()
    }

    private fun headerChecks(checks: FrameChecks, layout: LayoutSpec) {
        checks.tag("chatHeader", "r.0")
        checks.tag("chatHeader.back", "r.0.0")
        checks.tag("chatHeader.avatar", "r.0.1")
        // The name and the status line hug their text; the design's spans fill the column.
        checks.tag("chatHeader.name", "r.0.2.0", setOf(Edge.X, Edge.Y, Edge.H))
        checks.text(checkNotNull(layout["r.0.2.1"].text), "r.0.2.1", setOf(Edge.X, Edge.Y, Edge.H))
        checks.tag("conversation.ghost", "r.0.3")
    }

    @Test
    fun conversationStates() {
        for (state in listOf("5.1-failed", "5.1-reply", "5.1-attach", "5.1-voice-hold", "5.1-voice-locked", "5.1-voice-review", "5.1-voice-hint", "5.1-voice-permission", "5.1-voice-unplayed")) {
            rig = ConversationRig()
            rig.plan("deniz", state)
            host.shoot("conversation:deniz/$state", ShotTheme.Dark, "tr") { ConversationScreen(deniz) }
            if (state == "5.1-attach") {
                // The “+” menu floats 8 dp above “+” (794), at the composer's 12 dp gutter, 220 wide.
                val menu = host.compose.onAllNodes(androidx.compose.ui.test.hasTestTag("attach.menu"), useUnmergedTree = true).fetchSemanticsNodes().single().frame()
                org.junit.Assert.assertEquals(12f, menu.x, 0.5f)
                org.junit.Assert.assertEquals(220f, menu.w, 0.5f)
                org.junit.Assert.assertEquals(786f, menu.y + menu.h, 0.5f)
            }
        }
        rig = ConversationRig()
        rig.plan("deniz", "5.1-failed")
        host.shoot("conversation:deniz/5.1-failed", ShotTheme.Light, "tr") { ConversationScreen(deniz) }
        rig = ConversationRig()
        rig.plan("deniz", "5.1-reply")
        host.shoot("conversation:deniz/5.1-reply", ShotTheme.Light, "tr") { ConversationScreen(deniz) }
    }

    // 5.3 Mesaj eylemleri

    @Test
    fun actions() {
        for (theme in ShotTheme.entries) {
            rig = ConversationRig()
            rig.plan("deniz", "5.3")
            host.shoot("5.3-mesaj-eylemleri", theme, "tr") { ConversationScreen(deniz) }
            val layout = LayoutSpec.load("5.3-mesaj-eylemleri")
            val checks = FrameChecks(host.compose, layout)
            checks.tag("actions.reactions", "r.2.0")
            checks.tag("reaction.❤️", "r.2.0.0")
            checks.tag("reaction.🙏", "r.2.0.5")
            checks.description(string(R.string.conversation_a11y_reactions_more), "r.2.0.6")
            checks.tag("actions.bubble", "r.2.1")
            checks.tag("actions.menu", "r.2.2")
            checks.tag("menu.reply", "r.2.2.0")
            checks.tag("menu.info", "r.2.2.2")
            checks.tag("menu.copy", "r.2.2.4")
            checks.tag("menu.deleteEveryone", "r.2.2.6")
            checks.tag("menu.deleteMe", "r.2.2.8")
            checks.text(checkNotNull(layout["r.2.2.0.0.0"].text), "r.2.2.0.0.0", Position)
            checks.assertAll()
        }
        for (state in listOf("5.3-incoming", "5.3-confirm")) {
            rig = ConversationRig()
            rig.plan("deniz", state)
            host.shoot("conversation:deniz/$state", ShotTheme.Dark, "tr") { ConversationScreen(deniz) }
        }
    }

    // 5.4 Mesaj bilgisi

    @Test
    fun info() {
        for (theme in ShotTheme.entries) {
            rig = ConversationRig()
            rig.plan("deniz", "5.4")
            host.shoot("5.4-mesaj-bilgisi", theme, "tr") { ConversationScreen(deniz) }
            val layout = LayoutSpec.load("5.4-mesaj-bilgisi")
            val checks = FrameChecks(host.compose, layout)
            checks.tag("info.header", "r.1.1")
            checks.tag("sheet.close", "r.1.1.1")
            checks.text(checkNotNull(layout["r.1.1.0"].text), "r.1.1.0", Position)
            checks.tag("info.bubble", "r.1.2.0")
            checks.tag("info.timeline", "r.1.3")
            checks.tag("info.step.sent", "r.1.3.0")
            checks.tag("info.step.delivered", "r.1.3.1")
            checks.tag("info.step.read", "r.1.3.2")
            checks.text(checkNotNull(layout["r.1.3.0.1.1"].text), "r.1.3.0.1.1", Position)
            // The time cell stretches over the row in the design; its text sits 8 dp into it, as iOS pads it.
            checks.text("18:06", "r.1.3.1.2", setOf(Edge.Right)) { it.copy(y = it.y + 8f) }
            checks.tag("info.note", "r.1.4")
            checks.assertAll()
        }
        for (state in listOf("5.4-pending", "5.4-failed")) {
            rig = ConversationRig()
            rig.plan("deniz", state)
            host.shoot("conversation:deniz/$state", ShotTheme.Dark, "tr") { ConversationScreen(deniz) }
        }
    }

    // 5.5 Ghost ve süreli mesaj

    @Test
    fun ghost() {
        for (theme in ShotTheme.entries) {
            rig = ConversationRig()
            rig.plan("deniz", "5.5")
            host.shoot("5.5-ghost-ve-sureli-mesaj", theme, "tr") { ConversationScreen(deniz) }
            val layout = LayoutSpec.load("5.5-ghost-ve-sureli-mesaj")
            val checks = FrameChecks(host.compose, layout)
            checks.tag("ghost.kicker", "r.1.1.0")
            checks.tag("ghost.countdown", "r.1.1.1")
            checks.tag("ghost.caption", "r.1.1.2")
            checks.tag("ghost.toggle", "r.1.2.0")
            checks.tag("ghost.periods", "r.1.2.2")
            checks.tag("ghost.period.OneDay", "r.1.2.2.2")
            checks.text(checkNotNull(layout["r.1.4.0.0"].text), "r.1.4.0.0", Position)
            checks.tag("ghost.disappearing", "r.1.4.1")
            checks.tag("ghost.save", "r.1.5")
            checks.assertAll()
        }
        rig = ConversationRig()
        rig.plan("mert_k", "5.5")
        host.shoot("conversation:mert_k/5.5", ShotTheme.Dark, "tr") { ConversationScreen(ChatId("mert_k")) }
    }

    // 4.4 İstek önizleme

    @Test
    fun request() {
        for (theme in ShotTheme.entries) {
            rig = ConversationRig()
            host.shoot("4.4-istek-onizleme", theme, "tr") { RequestPreviewScreen(RequestId("kuzey_dev")) }
            val layout = LayoutSpec.load("4.4-istek-onizleme")
            val checks = FrameChecks(host.compose, layout)
            checks.tag("chatHeader", "r.0")
            checks.tag("chatHeader.back", "r.0.0")
            checks.tag("chatHeader.avatar", "r.0.1")
            checks.tag("chatHeader.name", "r.0.2.0", setOf(Edge.X, Edge.Y, Edge.H))
            checks.tag("preview.more", "r.0.3")
            checks.tag("preview.avatar", "r.2.0.0")
            checks.tag("preview.name", "r.2.0.1", setOf(Edge.Y, Edge.H))
            checks.tag("preview.intro", "r.2.0.2")
            // “Profil resmini göster” (proposed, as the iOS app shows it for this request) sits
            // between the intro and the thread: everything below moves down by its block.
            val shift = host.compose.onAllNodes(androidx.compose.ui.test.hasTestTag("preview.date"), useUnmergedTree = true)
                .fetchSemanticsNodes().first().frame().y - layout["r.2.1"].frame.y
            println("4.4 thread below the avatar action: +${"%.2f".format(java.util.Locale.ROOT, shift)} dp")
            checks.tag("preview.date", "r.2.1", setOf(Edge.X, Edge.W, Edge.H))
            checks.tag("preview.message.k1", "r.2.2") { it.copy(y = it.y + shift) }
            checks.tag("preview.message.k2", "r.2.3") { it.copy(y = it.y + shift) }
            checks.tag("preview.message.k3", "r.2.4") { it.copy(y = it.y + shift) }
            checks.tag("preview.bar", "r.3")
            checks.tag("preview.decision", "r.3.0")
            checks.tag("preview.block", "r.3.1.0")
            checks.tag("preview.delete", "r.3.1.1")
            checks.tag("preview.accept", "r.3.1.2")
            checks.assertAll()
        }
        rig = ConversationRig()
        host.shoot("chats:4.4-media", ShotTheme.Dark, "tr") { RequestPreviewScreen(RequestId("n_aksoy")) }
        rig = ConversationRig()
        host.shoot("chats:4.4-link", ShotTheme.Dark, "tr") { RequestPreviewScreen(RequestId("hediye_kazan")) }
    }

    private fun string(id: Int): String = ApplicationProvider.getApplicationContext<Context>().resources.getString(id)
}
