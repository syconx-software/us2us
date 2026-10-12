package com.syconx.us2us.feature.settings.avatar

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.syconx.us2us.domain.AvatarChoice
import com.syconx.us2us.domain.AvatarEdit
import com.syconx.us2us.domain.AvatarEditError
import com.syconx.us2us.domain.CropRect
import com.syconx.us2us.domain.DesignArtwork
import com.syconx.us2us.domain.MessengerException
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.media.DesignArtworkBitmap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 7.1 › Profil görseli (proposed): the state of the full-screen picker and of the framing screen
 * over it. Choosing the initial or a preset saves at once; a photo is picked (or taken), framed on
 * the framing screen, then handed to the messenger with the square the user framed. Nothing here
 * keeps the photo after it was saved or given up.
 */
@Stable
class AvatarEditorModel(
    val messenger: MessengerService,
    private val sources: AvatarPhotoSourcing,
) {
    /** Where a save stands (the picker's mark, the framing screen's button). */
    enum class Phase {
        Idle,

        /** A picked photo is being read (before the framing screen). */
        LoadingPhoto,
        Saving,
    }

    /** The photo on the framing screen; `null` closes it. */
    var framing: AvatarSourceImage? by mutableStateOf(null)

    var phase: Phase by mutableStateOf(Phase.Idle)
        private set

    /** A short note on the picker or the framing screen (“Fotoğraf kullanılamadı.”). */
    @get:StringRes
    var toast: Int? by mutableStateOf(null)

    /** Counts saved changes: the picker closes after one. */
    var savedChanges: Int by mutableIntStateOf(0)
        private set

    /** The picker's mark: the current choice. */
    val current: AvatarChoice get() = messenger.ownAvatar.value.picture.choice

    /** The own username (the initial's letter). */
    val username: String get() = messenger.ownUsername.value

    /** “Aramada göster” is on: the picker and the framing screen say who can see the picture (PROTOCOL §8.10). */
    val notesSearch: Boolean get() = messenger.ownAvatar.value.showInSearch

    val isBusy: Boolean get() = phase != Phase.Idle

    // MARK: Initial and presets

    /** A tap on the initial or a preset: saved at once (the picker has moved its mark already). `false` when it could not be saved. */
    suspend fun choose(choice: AvatarChoice): Boolean {
        toast = null
        val edit = when (choice) {
            AvatarChoice.Initials -> AvatarEdit.Initials
            is AvatarChoice.Preset -> AvatarEdit.Preset(choice.id)
            AvatarChoice.Photo -> return false
        }
        if (choice == current) {
            savedChanges += 1
            return true
        }
        return save(edit)
    }

    // MARK: Photo

    /** “Fotoğraf yükle”: the system picker, then the framing screen. */
    suspend fun pickPhoto() {
        if (isBusy) return
        toast = null
        phase = Phase.LoadingPhoto
        val image = try {
            sources.pick()
        } finally {
            phase = Phase.Idle
        }
        if (image != null) framing = image
    }

    /** “Kamera”: the camera (access asked the first time), then framing. */
    suspend fun takePhoto() {
        if (isBusy) return
        toast = null
        when (sources.cameraAccess()) {
            AvatarPhotoSourcing.CameraAccess.Available -> {
                phase = Phase.LoadingPhoto
                val image = try {
                    sources.take()
                } finally {
                    phase = Phase.Idle
                }
                if (image != null) framing = image
            }
            AvatarPhotoSourcing.CameraAccess.Denied -> {
                toast = R.string.settings_avatar_camera_denied
                sources.explainCameraDenied()
            }
            AvatarPhotoSourcing.CameraAccess.Restricted -> toast = R.string.settings_avatar_camera_restricted
            AvatarPhotoSourcing.CameraAccess.Unavailable -> toast = R.string.settings_avatar_camera_unavailable
        }
    }

    /**
     * “Kullan” on the framing screen: the framed square of the photo in view, in its pixels. The
     * screen closes once it is saved; it stays (with a note) when the photo cannot be used.
     */
    suspend fun useFramedPhoto(crop: CropRect): Boolean {
        val source = framing ?: return false
        val saved = save(AvatarEdit.Photo(source.jpeg, crop))
        if (saved) framing = null
        return saved
    }

    /** “Vazgeç” on the framing screen: the photo is dropped. */
    fun cancelFraming() {
        if (phase == Phase.Saving) return
        framing = null
    }

    private suspend fun save(edit: AvatarEdit): Boolean {
        if (phase == Phase.Saving) return false
        phase = Phase.Saving
        try {
            messenger.setAvatar(edit)
            savedChanges += 1
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: MessengerException.AvatarEdit) {
            toast = if (failure.error == AvatarEditError.UnusablePhoto) R.string.settings_avatar_error_photo else R.string.settings_avatar_error_save
        } catch (_: Exception) {
            toast = R.string.settings_avatar_error_save
        } finally {
            phase = Phase.Idle
        }
        return false
    }

    companion object {
        /** The framing screen's photo in the screen harness (`settings:avatar-crop`): the 5.1 night-pier painting. */
        fun harnessSource(): AvatarSourceImage? = runCatching {
            AvatarSourceImage.make(DesignArtworkBitmap.render(DesignArtwork.NightPier, 1_536))
        }.getOrNull()
    }
}

// MARK: - Framing geometry

/**
 * The framing screen's arithmetic (pure): the photo fills a square viewport at zoom 1 (its shorter
 * side edge to edge) and zooms up to [maxZoom]; it can be moved only as far as it still covers the
 * square. [cropRect] is the square of the photo's pixels the viewport shows: what is sent. Sizes
 * and offsets are in any one unit (the screen uses pixels).
 */
@Immutable
data class AvatarCropGeometry(
    /** The photo's size in pixels (orientation applied). */
    val imageWidth: Double,
    val imageHeight: Double,
    /** The viewport's side. */
    val side: Double,
) {
    /** A pair of lengths (an offset or a size). */
    data class Vector(val x: Double, val y: Double) {
        companion object {
            val Zero = Vector(0.0, 0.0)
        }
    }

    /** Viewport units per pixel at zoom 1. */
    val baseScale: Double
        get() {
            val shorter = min(imageWidth, imageHeight)
            return if (shorter > 0) side / shorter else 1.0
        }

    val maxZoom: Double
        get() {
            val shorter = min(imageWidth, imageHeight)
            return max(1.0, min(ZOOM_CAP, shorter / MINIMUM_CROP_PIXELS))
        }

    fun clampedZoom(zoom: Double): Double {
        if (!zoom.isFinite()) return 1.0
        return min(max(zoom, 1.0), maxZoom)
    }

    /** The photo's size on screen at [zoom]. */
    fun displayedSize(zoom: Double): Vector {
        val scale = baseScale * zoom
        return Vector(imageWidth * scale, imageHeight * scale)
    }

    /** How far the photo's centre may sit from the viewport's centre. */
    fun clampedOffset(offset: Vector, zoom: Double): Vector {
        val size = displayedSize(zoom)
        val maxX = max(0.0, (size.x - side) / 2)
        val maxY = max(0.0, (size.y - side) / 2)
        val x = if (offset.x.isFinite()) offset.x else 0.0
        val y = if (offset.y.isFinite()) offset.y else 0.0
        return Vector(min(max(x, -maxX), maxX), min(max(y, -maxY), maxY))
    }

    /** The framed square in the photo's pixels (origin top left), in whole pixels and inside the photo. */
    fun cropRect(zoom: Double, offset: Vector): CropRect {
        val z = clampedZoom(zoom)
        val o = clampedOffset(offset, z)
        val scale = baseScale * z
        val shorter = min(imageWidth, imageHeight)
        // A hair over the quotient, so floating-point noise never costs a pixel.
        val cropSide = min(floor(side / scale + 1e-6), floor(shorter))
        if (cropSide < 1) return CropRect(0.0, 0.0, imageWidth, imageHeight)
        // The viewport's centre, in the photo's pixels.
        val centreX = imageWidth / 2 - o.x / scale
        val centreY = imageHeight / 2 - o.y / scale
        val x = min(max(roundHalfAway(centreX - cropSide / 2), 0.0), floor(imageWidth - cropSide))
        val y = min(max(roundHalfAway(centreY - cropSide / 2), 0.0), floor(imageHeight - cropSide))
        return CropRect(x, y, cropSide, cropSide)
    }

    /** The accessibility zoom step: one step in or out. */
    fun steppedZoom(zoom: Double, direction: Int): Double {
        val step = max(0.25, (maxZoom - 1) / 6)
        return clampedZoom(zoom + direction * step)
    }

    companion object {
        /** The framed square never gets smaller than this many pixels (the picture is 256 at most; a smaller square only blurs). */
        const val MINIMUM_CROP_PIXELS = 96.0
        const val ZOOM_CAP = 6.0

        /** Rounds half away from zero, as Swift's `rounded()` does. */
        private fun roundHalfAway(value: Double): Double = if (value < 0) -floor(abs(value) + 0.5) else floor(value + 0.5)
    }
}
