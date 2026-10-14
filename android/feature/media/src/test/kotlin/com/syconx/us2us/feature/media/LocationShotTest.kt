package com.syconx.us2us.feature.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.syconx.us2us.domain.OwnPositionState
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.sheet.U2Sheet
import com.syconx.us2us.ui.sheet.U2SheetStyle
import com.syconx.us2us.ui.theme.U2Theme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 5.2 › konum: the “Haritada aç” chooser (`5.2-place`) and the live-location map in the harness's
 * four states (`5.2-map`, `-peer`, `-ask`, `-denied`), as the conversation harness sets them up.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class LocationShotTest {
    private val environment = MediaShotEnvironment()

    @get:Rule
    val host = ShotHost(environment = environment)

    private val mert = ChatId("mert_k")

    @Test
    fun placeChooser() {
        for (theme in ShotTheme.entries) {
            host.render(theme, "tr") {
                Box(Modifier.fillMaxSize().background(U2Theme.colors.bg))
                U2Sheet(isPresented = true, onDismissRequest = {}, style = U2SheetStyle.Floating) {
                    LocationOpenSheet(mert, MessageId("m3"), onDismiss = {})
                }
            }
            host.advance(1_000)
            host.capture("conversation:mert_k/5.2-place")
            host.compose.onNodeWithTag(LocationOpenTags.HANDED_OVER, useUnmergedTree = true)
                .assertTextContains("Moda İskelesi · 40.98460, 29.02490")
        }
    }

    @Test
    fun liveMapStates() {
        val states = listOf("5.2-map", "5.2-map-peer", "5.2-map-ask", "5.2-map-denied")
        for (theme in ShotTheme.entries) {
            for (state in states) {
                var focus = MessageId("m4")
                environment.prepare = { fixtures ->
                    if (state == "5.2-map-ask") fixtures.ownPositionOverride = OwnPositionState.NeedsPermission
                    if (state == "5.2-map-denied") fixtures.ownPositionOverride = OwnPositionState.Denied
                    val peerShare = fixtures.startDemoLiveLocation(mert)
                    // Only mert_k shares: selin_n's own share has ended.
                    if (state != "5.2-map") runBlocking { fixtures.stopLiveLocation(MessageId("m4"), mert) }
                    if (state != "5.2-map") focus = peerShare
                }
                host.render(theme, "tr") { LiveLocationMapCover(mert, focus, onDismiss = {}) }
                host.settle { host.has(LiveMapTags.PEER_MARKER) }
                host.capture("conversation:mert_k/$state")
                when (state) {
                    "5.2-map" -> host.compose.onNodeWithTag(LiveMapTags.STOP).assertIsDisplayed()
                    "5.2-map-ask", "5.2-map-denied" -> host.compose.onNodeWithTag(LiveMapTags.PERMISSION).assertIsDisplayed()
                    else -> host.compose.onNodeWithTag(LiveMapTags.OWN_MARKER, useUnmergedTree = true).assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun stoppingTheOnlyShareClosesTheMap() {
        var dismissed = 0
        host.render(ShotTheme.Dark, "tr") { LiveLocationMapCover(mert, MessageId("m4"), onDismiss = { dismissed++ }) }
        host.settle { host.has(LiveMapTags.STOP) }
        host.compose.onNodeWithTag(LiveMapTags.STOP).performClick()
        host.advance(500)
        assertEquals(1, dismissed)
    }
}
