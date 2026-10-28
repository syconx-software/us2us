package com.syconx.us2us.feature.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import com.syconx.us2us.domain.PickedMediaPlan
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.MediaPayload
import com.syconx.us2us.domain.MediaSource
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.IOException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class PhotoGalleryInteractionTest {
    private val chat = ChatId("deniz")
    private val environment = MediaShotEnvironment().also { env ->
        env.prepare = { service -> runBlocking {
            service.sendPickedMedia(PickedMediaPlan.messages(DesignFixtures.pickedPhotos, null), chat)
        } }
    }
    @get:Rule val host = ShotHost(environment = environment)

    /**
     * Photo [number] is the page on show (its announced position) and its picture is decoded: the
     * state a gesture acts on. Waiting for a picture alone can stop on the page being left.
     */
    private fun showsPhoto(number: Int): Boolean = host.has(ZoomableImageTags.IMAGE) &&
        host.compose.onAllNodesWithTag("mediaViewer.position", useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .any { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } == "Photo $number of 4" }

    @Test fun arrowsAndSwipesFollowConversationOrderWithoutWrappingOrChangingWhileZoomed() {
        host.render(ShotTheme.Dark, "en") { MediaViewerCover(chat, MessageId("d2"), onDismiss = {}) }
        host.settle { showsPhoto(1) }
        fun position(number: Int) = host.compose.onNodeWithTag("mediaViewer.position").assertTextEquals("Photo $number of 4")
        position(1)
        host.compose.onNodeWithTag("mediaViewer.previous").assertIsNotEnabled()
        host.compose.onNodeWithTag("mediaViewer.next").performClick()
        host.settle { showsPhoto(2) }
        position(2)
        host.compose.onNodeWithTag(ZoomableImageTags.PHOTO).performTouchInput { swipeLeft() }
        host.settle { showsPhoto(3) }
        position(3)
        host.compose.onNodeWithTag(ZoomableImageTags.PHOTO).performTouchInput { swipeRight() }
        host.settle { showsPhoto(2) }
        position(2)
        host.compose.onNodeWithTag(ZoomableImageTags.PHOTO).performTouchInput { doubleClick(center) }
        host.advance(500)
        host.compose.onNodeWithTag(ZoomableImageTags.PHOTO).performTouchInput { swipeLeft() }
        host.advance(500)
        // The controls are hidden while zoomed; a tap reveals the unchanged selected page.
        host.compose.onNodeWithTag(ZoomableImageTags.PHOTO).performTouchInput { click(center) }
        host.advance(400)
        position(2)
    }

    @Test fun aTouchBeforeThePhotoIsDecodedLeavesDoubleTapZoomWorking() {
        var image by mutableStateOf<ImageBitmap?>(null)
        var zoomed = false
        host.render(ShotTheme.Dark, "en") {
            ZoomableImage(image = image, contentDescription = "Photo", zoomHint = "Double-tap to zoom",
                onSingleTap = {}, onZoomChange = { zoomed = it }, onDismissProgress = { _, _ -> }, onDismiss = {})
        }
        // A tap while the photo is still on its way starts the photo's gesture handling.
        host.compose.onNodeWithTag(ZoomableImageTags.PHOTO).performTouchInput { click(center) }
        host.advance(400)
        host.compose.runOnIdle { image = ImageBitmap(1200, 900) }
        host.advance(100)
        host.compose.onNodeWithTag(ZoomableImageTags.PHOTO).performTouchInput { doubleClick(center) }
        host.advance(500)
        assertTrue("a double tap after the photo arrived zooms in", zoomed)
    }

    @Test fun deletingTheVisiblePhotoClosesTheViewer() {
        var closed = 0
        host.render(ShotTheme.Dark, "en") { MediaViewerCover(chat, MessageId("d2"), onDismiss = { closed++ }) }
        host.settle { showsPhoto(1) }
        host.compose.onNodeWithTag("mediaViewer.next").performClick()
        host.settle { showsPhoto(2) }
        val visible = environment.service.messages(chat).value.filter { it.content is com.syconx.us2us.domain.MessageContent.Photo }[1]
        runBlocking { environment.service.deleteForMe(visible.id, chat) }
        host.advance(500)
        assertEquals(1, closed)
    }

    @Test fun aFailedFullPhotoLoadKeepsNavigationUsable() {
        environment.wrap = { service -> object : MessengerService by service {
            override suspend fun mediaPayload(message: MessageId, chat: ChatId): MediaPayload? = throw IOException("offline")
        } }
        var next = 0
        host.render(ShotTheme.Dark, "en") {
            MediaViewer(item = ViewerItem(ViewerItem.Kind.Photo, MessageId("d2"), "deniz", DesignFixtures.referenceDate,
                MediaSource.Memory(Bytes.Empty), false), chat = chat, saver = rememberMediaSaver(chat), appDark = true,
                onClose = {}, count = 2, onNext = { next++ })
        }
        host.settle { host.has(MediaViewerTags.FAILED) }
        host.compose.onNodeWithTag("mediaViewer.next").performClick()
        assertEquals(1, next)
    }
}
