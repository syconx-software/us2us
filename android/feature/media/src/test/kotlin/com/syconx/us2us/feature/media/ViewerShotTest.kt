package com.syconx.us2us.feature.media

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.media.BitmapDesignArtworkEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * The photo viewer and the player as the conversation harness opens them (`5.1-viewer`,
 * `5.1-viewer-zoomed`, `5.2-player`, `5.2-player-loading`), in both themes, and their gestures.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class ViewerShotTest {
    private val environment = MediaShotEnvironment()

    @get:Rule
    val host = ShotHost(environment = environment)

    private val deniz = ChatId("deniz")
    private val mert = ChatId("mert_k")
    private val engine = FakeVideoEngine()
    private val engines = VideoEngineFactory { engine }

    @Test
    fun photoViewer() {
        for (theme in ShotTheme.entries) {
            environment.state = null
            host.render(theme, "tr") { MediaViewerCover(deniz, MessageId("d2"), onDismiss = {}) }
            host.settle { host.has(ZoomableImageTags.IMAGE) }
            host.capture("conversation:deniz/5.1-viewer")
            host.compose.onNodeWithTag(MediaViewerTags.CLOSE).assertIsDisplayed()
            host.compose.onNodeWithTag("viewer.save").assertIsDisplayed()
            environment.state = MediaHarnessStates.VIEWER_ZOOMED
            host.render(theme, "tr") { MediaViewerCover(deniz, MessageId("d2"), onDismiss = {}) }
            host.settle { host.has(ZoomableImageTags.IMAGE) }
            host.capture("conversation:deniz/5.1-viewer-zoomed")
        }
    }

    @Test
    fun photoViewerZooms() {
        environment.state = null
        host.render(ShotTheme.Dark, "tr") { MediaViewerCover(deniz, MessageId("d2"), onDismiss = {}) }
        host.settle { host.has(ZoomableImageTags.IMAGE) }
        val photo = host.compose.onNodeWithTag(ZoomableImageTags.PHOTO)
        // Turkish writes the percent sign first.
        assertEquals("%100", photo.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        photo.performTouchInput { doubleClick(center) }
        host.advance(800)
        assertEquals("%250", photo.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        // Zoomed in, the chrome goes; double-tap again and it is back at 1×.
        photo.performTouchInput { doubleClick(center) }
        host.advance(800)
        assertEquals("%100", photo.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }

    @Test
    fun photoViewerSwipesAway() {
        environment.state = null
        var dismissed = 0
        host.render(ShotTheme.Dark, "tr") { MediaViewerCover(deniz, MessageId("d2"), onDismiss = { dismissed++ }) }
        host.settle { host.has(ZoomableImageTags.IMAGE) }
        host.compose.onNodeWithTag(ZoomableImageTags.PHOTO).performTouchInput { swipeDown(startY = centerY, endY = centerY + 600f, durationMillis = 200) }
        host.advance(600)
        assertEquals(1, dismissed)
    }

    @Test
    fun photoViewerTapTogglesTheChrome() {
        environment.state = null
        host.render(ShotTheme.Dark, "tr") { MediaViewerCover(deniz, MessageId("d2"), onDismiss = {}) }
        host.settle { host.has(ZoomableImageTags.IMAGE) }
        host.compose.onNodeWithTag(ZoomableImageTags.PHOTO).performTouchInput { click(center) }
        host.advance(1_000)
        host.compose.onNodeWithTag(MediaViewerTags.CLOSE).assertDoesNotExist()
        host.compose.onNodeWithTag(ZoomableImageTags.PHOTO).performTouchInput { click(center) }
        host.advance(1_000)
        host.compose.onNodeWithTag(MediaViewerTags.CLOSE).assertIsDisplayed()
    }

    @Test
    fun saveAsksFirst() {
        environment.state = null
        host.render(ShotTheme.Dark, "tr") { MediaViewerCover(deniz, MessageId("d2"), onDismiss = {}) }
        host.settle { host.has(ZoomableImageTags.IMAGE) }
        host.compose.onNodeWithTag("viewer.save").performClick()
        host.advance(1_200)
        host.capture("conversation:deniz/5.1-save-notice")
        host.compose.onNodeWithTag(SaveNoticeTags.CONFIRM).assertIsDisplayed()
        host.compose.onNodeWithTag(SaveNoticeTags.CANCEL).performClick()
        host.advance(1_200)
        host.compose.onNodeWithTag(SaveNoticeTags.CONFIRM).assertDoesNotExist()
        assertTrue(!environment.appModel.preferences.hasAcknowledgedSaveNotice)
    }

    @Test
    fun player() {
        for (theme in ShotTheme.entries) {
            environment.state = MediaHarnessStates.PLAYER
            environment.prepare = { it.artworkEncoder = BitmapDesignArtworkEncoder { Bytes.utf8("clip") } }
            host.render(theme, "tr") {
                CompositionLocalProvider(LocalVideoEngineFactory provides engines) { VideoPlayerCover(mert, MessageId("m2"), onDismiss = {}) }
            }
            host.settle { host.has(VideoPlayerTags.POSTER) && host.has(VideoPlayerTags.PLAY) }
            host.capture("conversation:mert_k/5.2-player")
            host.compose.onNodeWithTag(VideoPlayerTags.PLAY).assertIsDisplayed()
            assertEquals(listOf(7.0), engine.seeks.takeLast(1))
            assertTrue(!engine.playing)
        }
    }

    @Test
    fun playerLoading() {
        for (theme in ShotTheme.entries) {
            environment.state = MediaHarnessStates.PLAYER_LOADING
            environment.prepare = { it.holdsMediaPayloads = true }
            host.render(theme, "tr") {
                CompositionLocalProvider(LocalVideoEngineFactory provides engines) { VideoPlayerCover(mert, MessageId("m2"), onDismiss = {}) }
            }
            host.settle { host.has(VideoPlayerTags.POSTER) }
            host.capture("conversation:mert_k/5.2-player-loading")
            host.compose.onNodeWithTag(VideoPlayerTags.LOADING, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun scrubbingSeeksAndPlaysOn() {
        environment.state = null
        environment.prepare = { it.artworkEncoder = BitmapDesignArtworkEncoder { Bytes.utf8("clip") } }
        host.render(ShotTheme.Dark, "tr") {
            CompositionLocalProvider(LocalVideoEngineFactory provides engines) { VideoPlayerCover(mert, MessageId("m2"), onDismiss = {}) }
        }
        host.settle { engine.prepared != null }
        assertTrue(engine.playing)
        host.compose.onNodeWithTag(VideoPlayerTags.SCRUBBER).performTouchInput {
            down(Offset(width * 0.5f, centerY))
            moveTo(Offset(width * 0.75f, centerY))
            up()
        }
        host.advance(100)
        assertEquals(13.5, engine.seeks.last(), 0.2)
        assertTrue("it plays on after the scrub", engine.playing)
    }

    @Test
    fun playerPlaysPausesAndCloses() {
        environment.state = null
        environment.prepare = { it.artworkEncoder = BitmapDesignArtworkEncoder { Bytes.utf8("clip") } }
        var dismissed = 0
        host.render(ShotTheme.Dark, "tr") {
            CompositionLocalProvider(LocalVideoEngineFactory provides engines) { VideoPlayerCover(mert, MessageId("m2"), onDismiss = { dismissed++ }) }
        }
        host.settle { engine.prepared != null }
        // Without a start time the clip plays at once.
        assertTrue(engine.playing)
        host.compose.onNodeWithTag(VideoPlayerTags.CLOSE).assertIsDisplayed()
        host.compose.onNodeWithTag(VideoPlayerTags.MUTE).performClick()
        assertTrue(engine.silent)
        host.compose.onNodeWithTag(VideoPlayerTags.PLAY).performClick()
        host.advance(100)
        assertTrue(!engine.playing)
        host.compose.onNodeWithTag(VideoPlayerTags.CLOSE).performClick()
        host.advance(700)
        assertEquals(1, dismissed)
        // Everything decrypted goes just after the cover's fade.
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        assertTrue(engine.released)
    }
}
