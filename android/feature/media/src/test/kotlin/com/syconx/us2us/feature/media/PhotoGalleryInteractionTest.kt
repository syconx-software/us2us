package com.syconx.us2us.feature.media

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

    @Test fun arrowsAndSwipesFollowConversationOrderWithoutWrappingOrChangingWhileZoomed() {
        host.render(ShotTheme.Dark, "en") { MediaViewerCover(chat, MessageId("d2"), onDismiss = {}) }
        host.settle { host.has(ZoomableImageTags.IMAGE) }
        fun position(number: Int) = host.compose.onNodeWithTag("mediaViewer.position").assertTextEquals("Photo $number of 4")
        position(1)
        host.compose.onNodeWithTag("mediaViewer.previous").assertIsNotEnabled()
        host.compose.onNodeWithTag("mediaViewer.next").performClick()
        host.settle { host.has(ZoomableImageTags.IMAGE) }
        position(2)
        host.compose.onNodeWithTag(ZoomableImageTags.PHOTO).performTouchInput { swipeLeft() }
        host.settle { host.has(ZoomableImageTags.IMAGE) }
        position(3)
        host.compose.onNodeWithTag(ZoomableImageTags.PHOTO).performTouchInput { swipeRight() }
        host.settle { host.has(ZoomableImageTags.IMAGE) }
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

    @Test fun deletingTheVisiblePhotoClosesTheViewer() {
        var closed = 0
        host.render(ShotTheme.Dark, "en") { MediaViewerCover(chat, MessageId("d2"), onDismiss = { closed++ }) }
        host.settle { host.has(ZoomableImageTags.IMAGE) }
        host.compose.onNodeWithTag("mediaViewer.next").performClick()
        host.settle { host.has(ZoomableImageTags.IMAGE) }
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
