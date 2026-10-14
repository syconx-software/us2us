package com.syconx.us2us.feature.settings.avatar

import android.graphics.BitmapFactory
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.syconx.us2us.domain.AvatarPicture
import com.syconx.us2us.fixtures.FixturesMessengerService
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.brand.AvatarPickerTags
import com.syconx.us2us.ui.env.PreviewAppModel
import com.syconx.us2us.ui.env.U2PreviewRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** “Profil görseli” driven like a user: a preset, a photo through the framing screen, the camera refused, giving up a photo. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class AvatarEditorInteractionTest {
    @get:Rule
    val host = ShotHost()

    private lateinit var service: FixturesMessengerService
    private var closed = 0

    private fun open(sources: AvatarPhotoSourcing) {
        service = fixturesService()
        host.render(ShotTheme.Dark, "tr") {
            val model = remember { PreviewAppModel(service) }
            U2PreviewRoot(model, dark = true) {
                AvatarEditorScreen(onClose = { closed += 1 }, sources = sources)
            }
        }
    }

    private fun node(tag: String) = host.compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun gone(tag: String) = host.compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()

    @Test
    fun aPresetIsSavedAndThePageCloses() {
        open(fakeSources())
        assertEquals(true, node(AvatarPickerTags.INITIALS).fetchSemanticsNode().config[SemanticsProperties.Selected])
        node(AvatarPickerTags.preset(2)).performClick()
        host.advance(200)
        assertEquals(AvatarPicture.preset(2), service.ownAvatar.value.picture)
        assertEquals(true, node(AvatarPickerTags.preset(2)).fetchSemanticsNode().config[SemanticsProperties.Selected])
        assertEquals(false, node(AvatarPickerTags.INITIALS).fetchSemanticsNode().config[SemanticsProperties.Selected])
        assertEquals("the moved mark is seen first", 0, closed)
        host.advance(500)
        assertEquals(1, closed)
    }

    @Test
    fun aPhotoIsFramedThenSaved() {
        val source = requireNotNull(AvatarSourceImage.make(photoJpeg(900, 600)))
        open(fakeSources(pick = source))
        node(AvatarPickerTags.UPLOAD).performClick()
        host.advance(600)
        node(AvatarCropTags.VIEWPORT).assertIsDisplayed()
        node(AvatarCropTags.USE).performClick()
        host.advance(100)
        // The scaler works off the main thread.
        var waited = 0
        while (service.ownAvatar.value.picture.photo == null && waited < 100) {
            Thread.sleep(20)
            host.advance(20)
            waited += 1
        }
        val photo = requireNotNull(service.ownAvatar.value.picture.photo)
        val bytes = photo.data.toByteArray()
        assertEquals(256, BitmapFactory.decodeByteArray(bytes, 0, bytes.size).width)
        host.advance(1_000)
        assertTrue("the framing screen has gone", gone(AvatarCropTags.VIEWPORT))
        assertEquals(1, closed)
    }

    @Test
    fun giveUpAPhoto() {
        val source = requireNotNull(AvatarSourceImage.make(photoJpeg(600, 900)))
        open(fakeSources(pick = source))
        node(AvatarPickerTags.UPLOAD).performClick()
        host.advance(600)
        host.compose.onNode(hasText("Vazgeç")).performClick()
        host.advance(600)
        assertTrue(gone(AvatarCropTags.VIEWPORT))
        assertEquals(AvatarPicture.Initials, service.ownAvatar.value.picture)
        assertEquals(0, closed)
    }

    @Test
    fun theCameraRefusedSaysSo() {
        var explained = 0
        open(fakeSources(camera = AvatarPhotoSourcing.CameraAccess.Denied, onExplain = { explained += 1 }))
        node(AvatarPickerTags.CAMERA).performClick()
        host.advance(600)
        node(AvatarPickerTags.ALERT).assertIsDisplayed()
        assertEquals(1, explained)
        assertFalse(gone(AvatarPickerTags.PICKER))
        assertEquals(0, closed)
    }
}
