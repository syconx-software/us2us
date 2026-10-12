package com.syconx.us2us.ui.components.marks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.GhostState
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotOptions
import com.syconx.us2us.ui.components.Avatar
import com.syconx.us2us.ui.components.GhostChip
import com.syconx.us2us.ui.components.GhostPill
import com.syconx.us2us.ui.components.GroupedNavigationRow
import com.syconx.us2us.ui.components.GroupedToggleRow
import com.syconx.us2us.ui.components.LineBar
import com.syconx.us2us.ui.components.LocalU2Ticker
import com.syconx.us2us.ui.components.U2Ticker
import com.syconx.us2us.ui.env.LocalU2Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What TalkBack reads and what taps do: avatars, Ghost pills and chips (with the shared ticker), grouped rows. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class MarkInteractionTest {
    @get:Rule
    val host = MarkShots.host()

    @Test
    fun avatarsReadTheNameAndPresence() {
        host.render {
            Column {
                Avatar("deniz", AvatarPalette.A)
                Avatar("mert_k", AvatarPalette.B, showsOnlineDot = true)
            }
        }
        host.compose.onNode(hasContentDescription("deniz")).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        host.compose.onNode(hasContentDescription("mert_k")).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Aktif"))
        // The letter is part of the avatar, not read on its own.
        assertEquals(0, host.compose.onAllNodes(hasText("D")).fetchSemanticsNodes().size)
    }

    @Test
    fun ghostChipReadsTheTimeLeftAndOpensTheSheet() {
        var taps = 0
        host.render {
            val now = LocalU2Clock.current.now
            val ghost = DesignFixtures.chats(now, ghostUrgent = false, english = false)[0].ghost
            Column {
                GhostChip(ghost, onClick = { taps++ })
                GhostChip(GhostState.Off, onClick = {})
            }
        }
        host.compose.onNode(hasContentDescription("Ghost mod: 23 sa kaldı", substring = true)).performClick()
        assertEquals(1, taps)
        host.compose.onNode(hasContentDescription("Ghost mod kapalı", substring = true)).assertExists()
    }

    @Test
    fun pillsFollowTheScreensSharedTicker() {
        host.render {
            val now = LocalU2Clock.current.now
            val ghost = DesignFixtures.chats(now, ghostUrgent = false, english = false)[0].ghost
            // The screen's ticker stands an hour later than the clock: the pill reads it, not the clock.
            val ticker = remember { U2Ticker(DesignClock.frozen(now.plusSeconds(3_600))) }
            CompositionLocalProvider(LocalU2Ticker provides ticker) { GhostPill(ghost) }
        }
        host.compose.onNode(hasContentDescription("22 sa", substring = true)).assertExists()
    }

    @Test
    fun groupedRowsToggleAndNavigate() {
        var opened = 0
        host.render {
            var checked by remember { mutableStateOf(true) }
            Column {
                GroupedNavigationRow(null, "Otomatik kilit", onClick = { opened++ }, detail = "Hemen")
                GroupedToggleRow(null, "Okundu bilgisi", checked = checked, onCheckedChange = { checked = it })
            }
        }
        host.compose.onNode(hasText("Otomatik kilit"), useUnmergedTree = false).performClick()
        assertEquals(1, opened)
        val toggle = host.compose.onNode(hasText("Okundu bilgisi"))
        toggle.assertIsOn()
        toggle.performClick()
        host.advance(500) // the screenshot clock is paused: let the frame (and the knob) run
        toggle.assertIsOff()
    }

    @Test
    fun packetsFlowOnALiveClockAndStandStillOnAFrozenOne() {
        fun lineRow(file: java.io.File): IntArray {
            val image = javax.imageio.ImageIO.read(file)
            // The bar's middle row, 30 dp below the top, across the 300 dp mark.
            return IntArray(900) { x -> image.getRGB(60 + x, 90 + 3) }
        }
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            Column(androidx.compose.ui.Modifier.padding(start = 20.dp, top = 30.dp)) {
                LineBar(androidx.compose.ui.Modifier.width(300.dp), isFlowing = true)
            }
        }
        host.render(options = ShotOptions(frozenClock = false), content = content)
        val first = lineRow(host.capture("packets-live-0"))
        host.advance(1_000)
        val second = lineRow(host.capture("packets-live-1"))
        assertTrue("a running clock moves the packets", !first.contentEquals(second))

        host.render(content = content)
        val frozen = lineRow(host.capture("packets-frozen-0"))
        host.advance(1_000)
        assertTrue("a frozen clock holds them", frozen.contentEquals(lineRow(host.capture("packets-frozen-1"))))
    }
}
