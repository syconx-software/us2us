package com.syconx.us2us.feature.conversation

import androidx.compose.ui.test.*
import com.syconx.us2us.domain.*
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class PhotoAlbumInteractionTest {
    private val rig = ConversationRig(english = true)
    private val chat = ChatId("deniz")
    @get:Rule val host = ShotHost(environment = conversationShots { rig })

    @Test fun photosShareOneBubbleAndKeepIndividualActions() {
        runBlocking {
            rig.fixtures.updateGhost(GhostState.Off, DisappearingTimer.Off, chat)
            rig.fixtures.sendPickedMedia(PickedMediaPlan.messages(DesignFixtures.pickedPhotos, "Album caption"), chat)
        }
        val photos = rig.fixtures.messages(chat).value.takeLast(3)
        host.render(ShotTheme.Dark, "en") { ConversationScreen(chat) }
        host.advance(600)
        host.compose.onNodeWithTag("photoAlbum.${photos.first().id}").assertIsDisplayed()
        val photoBottom = host.compose.onNodeWithTag("message.${photos.last().id}", useUnmergedTree = true)
            .getUnclippedBoundsInRoot().bottom
        val composerTop = host.compose.onNodeWithTag("composer.input").getUnclippedBoundsInRoot().top
        assertTrue("the album stays above the composer", photoBottom <= composerTop)
        photos.forEach { host.compose.onNodeWithTag("message.${it.id}", useUnmergedTree = true).assertIsDisplayed() }
        host.compose.onNodeWithText("Album caption").assertIsDisplayed()
        host.capture("conversation:deniz/5.1-album")
        host.compose.onNodeWithTag("message.${photos[1].id}", useUnmergedTree = true).performTouchInput { longClick() }
        host.advance(700)
        host.compose.onNodeWithTag("menu.info", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(3, rig.fixtures.messages(chat).value.takeLast(3).size)
        runBlocking { rig.fixtures.deleteForMe(photos[1].id, chat) }
        host.advance(700)
        host.compose.onNodeWithTag("message.${photos.first().id}", useUnmergedTree = true).assertIsDisplayed()
        host.compose.onNodeWithTag("message.${photos.last().id}", useUnmergedTree = true).assertIsDisplayed()
        host.capture("conversation:deniz/5.1-album-after-delete")
        host.compose.onNodeWithTag("photoAlbum.${photos.first().id}").assertIsDisplayed()
        assertTrue("deletion must not move the album under the composer",
            host.compose.onNodeWithTag("message.${photos.last().id}", useUnmergedTree = true).getUnclippedBoundsInRoot().bottom <=
                host.compose.onNodeWithTag("composer.input").getUnclippedBoundsInRoot().top)
    }
}
