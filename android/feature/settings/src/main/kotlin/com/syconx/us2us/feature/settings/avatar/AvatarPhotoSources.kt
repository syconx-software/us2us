package com.syconx.us2us.feature.settings.avatar

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect as AndroidRect
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.components.text.U2DisplayText
import com.syconx.us2us.ui.env.ExternalActivityGuard
import com.syconx.us2us.ui.env.LocalExternalActivityGuard
import com.syconx.us2us.ui.env.rememberGuardedLauncher
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.sheet.LocalFloatingSheetPresenter
import com.syconx.us2us.ui.sheet.U2FloatingSheetPresenter
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * A photo picked or taken for the profile picture, ready to be framed: the orientation applied to
 * the pixels and at most [MAX_SIDE] px on its longer side, held in memory only: no file is written,
 * and the picked original is not kept. [jpeg] is the same pixels with nothing else (no EXIF, no
 * location), which the messenger crops, scales to ≤ 256 px and re-encodes.
 */
@Immutable
class AvatarSourceImage internal constructor(
    /** The upright pixels, for the framing screen. */
    val bitmap: Bitmap,
    val jpeg: Bytes,
) {
    private val id: Long = ids.incrementAndGet()

    val pixelWidth: Int get() = bitmap.width
    val pixelHeight: Int get() = bitmap.height

    override fun equals(other: Any?): Boolean = other is AvatarSourceImage && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "AvatarSourceImage(${bitmap.width}×${bitmap.height}, ${jpeg.size} bytes)"

    companion object {
        /** The longer side a picked photo is reduced to before framing. */
        const val MAX_SIDE = 2_048

        private val ids = AtomicLong()

        /**
         * From a picked file's bytes (any format the platform decoder reads): the decoder applies the
         * orientation and never decodes more than [MAX_SIDE]. `null` when they are not a picture.
         */
        fun make(data: ByteArray): AvatarSourceImage? {
            val decoded = runCatching { decodeWithImageDecoder(data) }.getOrNull() ?: decodeWithBitmapFactory(data) ?: return null
            return make(decoded)
        }

        /** From pixels (a camera photo, the harness painting): drawn upright, opaque, at ≤ [MAX_SIDE]. */
        fun make(picture: Bitmap): AvatarSourceImage? {
            if (picture.width < 1 || picture.height < 1) return null
            val factor = min(1.0, MAX_SIDE.toDouble() / max(picture.width, picture.height))
            val width = (picture.width * factor).roundToInt().coerceAtLeast(1)
            val height = (picture.height * factor).roundToInt().coerceAtLeast(1)
            val upright = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(upright).apply {
                drawColor(AndroidColor.WHITE)
                drawBitmap(picture, AndroidRect(0, 0, picture.width, picture.height), AndroidRect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            val out = ByteArrayOutputStream()
            if (!upright.compress(Bitmap.CompressFormat.JPEG, 92, out)) return null
            return AvatarSourceImage(upright, Bytes.wrap(out.toByteArray()))
        }

        private fun decodeWithImageDecoder(data: ByteArray): Bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longer = max(info.size.width, info.size.height)
                if (longer > MAX_SIDE) {
                    val factor = MAX_SIDE.toDouble() / longer
                    decoder.setTargetSize(
                        (info.size.width * factor).roundToInt().coerceAtLeast(1),
                        (info.size.height * factor).roundToInt().coerceAtLeast(1),
                    )
                }
            }

        private fun decodeWithBitmapFactory(data: ByteArray): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}

/**
 * Where a profile photo comes from (7.1 › Profil görseli): the system photo picker and the camera.
 * Both are replaceable, so the editor's model runs in tests without the system.
 */
class AvatarPhotoSourcing(
    /** The picked photo, or `null` when the picker was cancelled or the file could not be read. */
    val pick: suspend () -> AvatarSourceImage?,
    /** Whether the camera can be opened (asks for access the first time). */
    val cameraAccess: suspend () -> CameraAccess,
    /** The photo taken, or `null` when the camera was closed. */
    val take: suspend () -> AvatarSourceImage?,
    /** Camera access is off: why, and the way to the settings. */
    val explainCameraDenied: () -> Unit = {},
) {
    enum class CameraAccess {
        Available,

        /** The user turned camera access off for us2us. */
        Denied,

        /** Disabled on this device (a device policy). */
        Restricted,

        /** No camera (or no camera app). */
        Unavailable,
    }
}

/**
 * The system photo picker (one photo; no storage permission: the picker hands over that one file)
 * and the camera (a still photo, nothing saved to the gallery), both launched under the app's
 * [ExternalActivityGuard] so returning from them does not lock the app.
 */
@Composable
fun rememberSystemAvatarPhotoSources(): AvatarPhotoSourcing {
    val context = LocalContext.current
    val guard = LocalExternalActivityGuard.current
    val presenter = LocalFloatingSheetPresenter.current
    val pending = remember { PendingResults() }
    val picker = rememberGuardedLauncher(ActivityResultContracts.PickVisualMedia()) { uri -> pending.pick?.complete(uri) }
    val permission = rememberGuardedLauncher(ActivityResultContracts.RequestPermission()) { granted -> pending.permission?.complete(granted) }
    val camera = rememberGuardedLauncher(ActivityResultContracts.TakePicture()) { saved -> pending.photo?.complete(saved) }
    return remember(context, guard, presenter, picker, permission, camera) {
        AvatarPhotoSourcing(
            pick = {
                val result = CompletableDeferred<Uri?>()
                pending.pick = result
                val launched = runCatching {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }.isSuccess
                val uri = if (launched) result.await() else null
                pending.pick = null
                uri?.let { picked ->
                    withContext(Dispatchers.IO) {
                        runCatching { context.contentResolver.openInputStream(picked)?.use { it.readBytes() } }.getOrNull()
                    }?.let { bytes -> withContext(Dispatchers.Default) { AvatarSourceImage.make(bytes) } }
                }
            },
            cameraAccess = {
                when {
                    !context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) -> AvatarPhotoSourcing.CameraAccess.Unavailable
                    cameraDisabledByPolicy(context) -> AvatarPhotoSourcing.CameraAccess.Restricted
                    context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ->
                        AvatarPhotoSourcing.CameraAccess.Available
                    else -> {
                        val answer = CompletableDeferred<Boolean>()
                        pending.permission = answer
                        val granted = runCatching { permission.launch(Manifest.permission.CAMERA) }.isSuccess && answer.await()
                        pending.permission = null
                        if (granted) AvatarPhotoSourcing.CameraAccess.Available else AvatarPhotoSourcing.CameraAccess.Denied
                    }
                }
            },
            take = {
                // The camera app writes the full-size photo into the app's cache through a
                // FileProvider grant; it is read into memory and the file goes at once.
                val capture = withContext(Dispatchers.IO) { CameraCapture.file(context) }
                try {
                    val result = CompletableDeferred<Boolean>()
                    pending.photo = result
                    val launched = try {
                        camera.launch(CameraCapture.uri(context, capture))
                        true
                    } catch (_: ActivityNotFoundException) {
                        false
                    } catch (_: IllegalArgumentException) {
                        false
                    }
                    val saved = launched && result.await()
                    pending.photo = null
                    if (!saved) return@AvatarPhotoSourcing null
                    val bytes = withContext(Dispatchers.IO) { runCatching { capture.readBytes() }.getOrNull() }
                    bytes?.let { withContext(Dispatchers.Default) { AvatarSourceImage.make(it) } }
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { capture.delete() }
                }
            },
            explainCameraDenied = { presenter?.let { CameraAccessSheet.present(it, context, guard) } },
        )
    }
}

/** The answers the system activities owe us, one at a time. */
private class PendingResults {
    var pick: CompletableDeferred<Uri?>? = null
    var permission: CompletableDeferred<Boolean>? = null
    var photo: CompletableDeferred<Boolean>? = null
}

/** The one file the camera app writes a capture into (the module's FileProvider shares only this folder). */
private object CameraCapture {
    private const val FOLDER = "avatar-camera"

    fun file(context: Context): File {
        val folder = File(context.cacheDir, FOLDER).apply { mkdirs() }
        folder.listFiles()?.forEach { it.delete() }
        return File(folder, "capture.jpg")
    }

    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.avatar.files", file)
}

private fun cameraDisabledByPolicy(context: Context): Boolean =
    runCatching { context.getSystemService(DevicePolicyManager::class.java)?.getCameraDisabled(null) == true }.getOrDefault(false)

/** Test tags of the camera-access sheet. */
object CameraAccessTags {
    const val SETTINGS = "avatar.camera.settings"
    const val CLOSE = "avatar.camera.close"
}

/**
 * Camera access is off (7.1 › Profil görseli › Kamera): why, and the way to the settings, the voice
 * recorder's and the gallery's permission sheet, for the camera.
 */
@Composable
fun CameraAccessContent(onOpenSettings: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 6.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            U2DisplayText(stringResource(R.string.settings_avatar_camera_denied_title), U2TextStyles.displaySheetTitle.balanced, isHeader = true)
            U2Text(stringResource(R.string.settings_avatar_camera_denied_body), U2TextStyles.sheetBody, color = U2Theme.colors.textSecondary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            U2PrimaryButton(
                title = stringResource(R.string.settings_avatar_camera_settings),
                onClick = onOpenSettings,
                glyph = PhosphorGlyph.GearSix,
                modifier = Modifier.testTag(CameraAccessTags.SETTINGS),
            )
            U2TextButton(
                title = stringResource(R.string.common_close),
                onClick = onClose,
                tone = U2TextButtonTone.Accent,
                height = U2Size.textButtonS,
                modifier = Modifier.testTag(CameraAccessTags.CLOSE),
            )
        }
    }
}

internal object CameraAccessSheet {
    fun present(presenter: U2FloatingSheetPresenter, context: Context, guard: ExternalActivityGuard) {
        presenter.present(
            content = { close ->
                CameraAccessContent(
                    onOpenSettings = {
                        close()
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { guard.launching { context.startActivity(intent) } }
                    },
                    onClose = close,
                )
            },
            onDismiss = {},
        )
    }
}
