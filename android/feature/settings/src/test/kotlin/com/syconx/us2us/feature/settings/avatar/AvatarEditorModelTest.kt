package com.syconx.us2us.feature.settings.avatar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.syconx.us2us.domain.AvatarChoice
import com.syconx.us2us.domain.AvatarPicture
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.CropRect
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.fixtures.FixturesMessengerService
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.ui.R
import java.io.ByteArrayOutputStream
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A JPEG photo of [width] × [height] (two colours, so crops differ), in memory. */
internal fun photoJpeg(width: Int, height: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).apply {
        drawColor(Color.rgb(48, 176, 199))
        drawRect(0f, 0f, width / 2f, height.toFloat(), Paint().apply { color = Color.rgb(255, 149, 0) })
    }
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
    return out.toByteArray()
}

internal fun fixturesService(): FixturesMessengerService =
    FixturesMessengerService(hasAccount = true, clock = DesignClock.frozen(DesignClock.referenceDate), latency = Duration.ZERO).apply {
        avatarPhotoPreparer = AvatarPhotoScaler
    }

internal fun fakeSources(
    pick: AvatarSourceImage? = null,
    camera: AvatarPhotoSourcing.CameraAccess = AvatarPhotoSourcing.CameraAccess.Available,
    take: AvatarSourceImage? = null,
    onExplain: () -> Unit = {},
) = AvatarPhotoSourcing(pick = { pick }, cameraAccess = { camera }, take = { take }, explainCameraDenied = onExplain)

/** 7.1 › Profil görseli's model (iOS AvatarEditorModelTests). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class AvatarEditorModelTest {
    private fun picked(width: Int = 600, height: Int = 400): AvatarSourceImage = requireNotNull(AvatarSourceImage.make(photoJpeg(width, height)))

    @Test
    fun aPresetIsSavedAtOnceAndThePageMayClose() = runTest {
        val service = fixturesService()
        val model = AvatarEditorModel(service, fakeSources())
        assertEquals(AvatarChoice.Initials, model.current)
        assertTrue(model.choose(AvatarChoice.Preset(2)))
        assertEquals(AvatarPicture.preset(2), service.ownAvatar.value.picture)
        assertEquals(AvatarChoice.Preset(2), model.current)
        assertEquals(1, model.savedChanges)
        assertTrue(model.choose(AvatarChoice.Initials))
        assertEquals(AvatarPicture.Initials, service.ownAvatar.value.picture)
        assertNull(model.toast)
        // The current choice again: nothing to send, the page may close.
        assertTrue(model.choose(AvatarChoice.Initials))
        assertEquals(3, model.savedChanges)
    }

    @Test
    fun aPresetThisBuildDoesNotListIsNeverSent() = runTest {
        val service = fixturesService()
        val model = AvatarEditorModel(service, fakeSources())
        assertFalse(model.choose(AvatarChoice.Preset(999)))
        assertEquals(AvatarPicture.Initials, service.ownAvatar.value.picture)
        assertEquals(R.string.settings_avatar_error_save, model.toast)
        assertEquals(0, model.savedChanges)
    }

    @Test
    fun aPickedPhotoIsFramedThenSavedSmallWithoutMetadata() = runTest {
        val service = fixturesService()
        val source = picked()
        assertEquals(600, source.pixelWidth)
        assertEquals(400, source.pixelHeight)
        val model = AvatarEditorModel(service, fakeSources(pick = source))
        model.pickPhoto()
        assertEquals(source, model.framing)
        assertEquals(AvatarEditorModel.Phase.Idle, model.phase)
        val crop = AvatarCropGeometry(source.pixelWidth.toDouble(), source.pixelHeight.toDouble(), 300.0)
            .cropRect(1.0, AvatarCropGeometry.Vector.Zero)
        assertEquals(CropRect(100.0, 0.0, 400.0, 400.0), crop)
        assertTrue(model.useFramedPhoto(crop))
        assertNull(model.framing)
        assertEquals(1, model.savedChanges)
        val photo = requireNotNull(service.ownAvatar.value.picture.photo)
        assertEquals("image/jpeg", photo.contentType)
        val bytes = photo.data.toByteArray()
        assertTrue("${bytes.size} bytes", bytes.size <= AvatarPhotoScaler.MAX_BYTES)
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        assertEquals(256, decoded.width)
        assertEquals(256, decoded.height)
        assertFalse("no EXIF segment", hasSegment(bytes, 0xE1))
        // The framed square: orange on its left quarter (x 100…300 of the 600 is orange), teal on the right.
        assertTrue(Color.red(decoded.getPixel(20, 128)) > 200)
        assertTrue(Color.blue(decoded.getPixel(236, 128)) > 150)
    }

    @Test
    fun aPhotoThatCannotBeUsedKeepsTheFramingScreenAndSaysSo() = runTest {
        val service = fixturesService()
        val good = picked()
        val broken = AvatarSourceImage(good.bitmap, Bytes.wrap(byteArrayOf(0x00, 0x01, 0x02)))
        val model = AvatarEditorModel(service, fakeSources(pick = broken))
        model.pickPhoto()
        assertFalse(model.useFramedPhoto(CropRect(0.0, 0.0, 100.0, 100.0)))
        assertEquals(broken, model.framing)
        assertEquals(R.string.settings_avatar_error_photo, model.toast)
        assertEquals(AvatarPicture.Initials, service.ownAvatar.value.picture)
        model.cancelFraming()
        assertNull(model.framing)
    }

    @Test
    fun aCancelledPickerChangesNothing() = runTest {
        val model = AvatarEditorModel(fixturesService(), fakeSources(pick = null))
        model.pickPhoto()
        assertNull(model.framing)
        assertEquals(AvatarEditorModel.Phase.Idle, model.phase)
        assertNull(model.toast)
    }

    @Test
    fun theCameraDeniedOrMissingSaysSoAndAPhotoTakenIsFramed() = runTest {
        var explained = 0
        val denied = AvatarEditorModel(fixturesService(), fakeSources(camera = AvatarPhotoSourcing.CameraAccess.Denied, onExplain = { explained += 1 }))
        denied.takePhoto()
        assertNull(denied.framing)
        assertEquals(R.string.settings_avatar_camera_denied, denied.toast)
        assertEquals(1, explained)
        val missing = AvatarEditorModel(fixturesService(), fakeSources(camera = AvatarPhotoSourcing.CameraAccess.Unavailable))
        missing.takePhoto()
        assertEquals(R.string.settings_avatar_camera_unavailable, missing.toast)
        val restricted = AvatarEditorModel(fixturesService(), fakeSources(camera = AvatarPhotoSourcing.CameraAccess.Restricted))
        restricted.takePhoto()
        assertEquals(R.string.settings_avatar_camera_restricted, restricted.toast)
        assertNull(restricted.framing)
        val taken = picked(400, 700)
        val camera = AvatarEditorModel(fixturesService(), fakeSources(take = taken))
        camera.takePhoto()
        assertEquals(taken, camera.framing)
    }

    @Test
    fun theSearchNoteFollowsShowInSearch() = runTest {
        val service = fixturesService()
        val model = AvatarEditorModel(service, fakeSources())
        assertTrue("on by default (PROTOCOL §8.10)", model.notesSearch)
        assertFalse(service.setAvatarShownInSearch(false))
        assertFalse(model.notesSearch)
        assertFalse(service.ownAvatar.value.isShownInSearch)
    }

    @Test
    fun sourcesAreReducedAndUpright() {
        val large = requireNotNull(AvatarSourceImage.make(photoJpeg(4_000, 1_000)))
        assertEquals(AvatarSourceImage.MAX_SIDE, large.pixelWidth)
        assertEquals(512, large.pixelHeight)
        assertFalse(hasSegment(large.jpeg.toByteArray(), 0xE1))
        assertNull(AvatarSourceImage.make(byteArrayOf(1, 2, 3)))
        assertNotNull(AvatarEditorModel.harnessSource())
    }

    @Test
    fun theScalerTakesTheFramedSquare() {
        assertEquals(android.graphics.Rect(100, 0, 500, 400), AvatarPhotoScaler.square(600, 400, null))
        assertEquals(android.graphics.Rect(10, 20, 110, 120), AvatarPhotoScaler.square(600, 400, CropRect(10.0, 20.0, 100.0, 100.0)))
        assertNull(AvatarPhotoScaler.square(600, 400, CropRect(700.0, 0.0, 100.0, 100.0)))
        // Never enlarged: a 120 px square stays 120.
        val small = requireNotNull(AvatarPhotoScaler.prepareNow(photoJpeg(300, 200), CropRect(0.0, 0.0, 120.0, 120.0)))
        val bytes = small.data.toByteArray()
        assertEquals(120, BitmapFactory.decodeByteArray(bytes, 0, bytes.size).width)
    }

    /** Whether the JPEG has a marker segment [marker] (0xE1: EXIF / XMP) before its image data. */
    private fun hasSegment(jpeg: ByteArray, marker: Int): Boolean {
        var index = 2
        while (index + 4 <= jpeg.size && (jpeg[index].toInt() and 0xFF) == 0xFF) {
            val type = jpeg[index + 1].toInt() and 0xFF
            if (type == marker) return true
            if (type == 0xDA) return false
            val length = ((jpeg[index + 2].toInt() and 0xFF) shl 8) or (jpeg[index + 3].toInt() and 0xFF)
            index += 2 + length
        }
        return false
    }
}
