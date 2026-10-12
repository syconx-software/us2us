package com.syconx.us2us.feature.media

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.syconx.us2us.domain.MediaExportPolicy
import com.syconx.us2us.domain.MediaPayload
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.SaveNoticeKind
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.LocalU2LabelStyle
import com.syconx.us2us.ui.components.U2ButtonLabel
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.components.currentLabelColor
import com.syconx.us2us.ui.components.text.U2DisplayText
import com.syconx.us2us.ui.env.ExternalActivityGuard
import com.syconx.us2us.ui.env.LocalAppModel
import com.syconx.us2us.ui.env.LocalExternalActivityGuard
import com.syconx.us2us.ui.env.LocalMessenger
import com.syconx.us2us.ui.env.U2Preferences
import com.syconx.us2us.ui.env.rememberGuardedLauncher
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.media.MediaGlyph
import com.syconx.us2us.ui.media.MediaGlyphIcon
import com.syconx.us2us.ui.sheet.LocalFloatingSheetPresenter
import com.syconx.us2us.ui.sheet.U2FloatingSheetPresenter
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.syconx.us2us.feature.media.R as MediaR

// Saving and handing on a photo, video or file from our viewers (proposed, PROTOCOL §8.4 SAVED):
// “Kaydet” in the photo viewer and the player (to the gallery, add-only), “Dosyalara kaydet” (the
// system's document picker) and the file preview's share sheet all go through `MediaSaver`. Before
// the first save of something received, a one-time floating sheet says that the sender will see
// it (“<kişi> fotoğrafı kaydettiğini görecek” · Kaydet · Vazgeç); once it is acknowledged it never
// comes back. Whenever something did leave the app the messenger is told (`markSaved`): the sender
// gets a SAVED receipt — always, whatever the read-receipt setting — and the message shows as saved
// here. View-once and disappearing media never leave the app.

/** Remembers that the one-time save explanation was acknowledged. */
interface SaveNoticeMemory {
    var hasAcknowledgedSaveNotice: Boolean
}

/** The acknowledgement in the app's device preferences (`u2.saveNotice.acknowledged`). */
class PreferencesSaveNoticeMemory(private val preferences: U2Preferences) : SaveNoticeMemory {
    override var hasAcknowledgedSaveNotice: Boolean
        get() = preferences.hasAcknowledgedSaveNotice
        set(value) {
            preferences.hasAcknowledgedSaveNotice = value
        }
}

/** In memory only (tests, previews). */
class InMemorySaveNoticeMemory(acknowledged: Boolean = false) : SaveNoticeMemory {
    override var hasAcknowledgedSaveNotice: Boolean = acknowledged
}

/**
 * Saves and hands on the media of one chat from our viewers, and tells the messenger when something
 * left the app. Everything that shows UI is a replaceable step, so the rules are tested without a
 * screen ([rememberMediaSaver] installs the real ones).
 */
@Stable
class MediaSaver(
    val chat: ChatId,
    val messenger: MessengerService,
    val memory: SaveNoticeMemory,
) {
    enum class Outcome {
        /** It left the app; the messenger was told. */
        Saved,

        /** The share sheet is up: whatever is shared from it is told. */
        Presented,

        /** “Vazgeç”, or the picker / share sheet closed without saving. */
        Cancelled,

        /** The gallery refused (storage permission off, Android 9). */
        Denied,

        /** It could not be read or written. */
        Failed,

        /** Never for this message (view-once, disappearing, gone …). */
        NotAllowed,
    }

    enum class GalleryResult { Saved, Denied, Failed }

    /** The explanation ([SaveNoticeSheet]); `true`: “Kaydet”. */
    var askNotice: suspend (peer: String, kind: SaveNoticeKind) -> Boolean = { _, _ -> false }

    /** Adds a photo or video to the gallery. */
    var addToGallery: suspend (payload: MediaPayload, isVideo: Boolean) -> GalleryResult = { _, _ -> GalleryResult.Failed }

    /** The system's document picker with the copy at [file]; `true`: saved. */
    var presentExporter: suspend (file: URI, name: String, mimeType: String) -> Boolean = { _, _, _ -> false }

    /**
     * The share sheet with the copy; [onShared] runs each time it went somewhere. `false`: it could
     * not be shown.
     */
    var presentShareSheet: (file: URI, mimeType: String, onShared: () -> Unit) -> Boolean = { _, _, _ -> false }

    /** The gallery refused: why, and the way to Settings. */
    var presentGalleryDenied: () -> Unit = {}

    /** Whose media this chat holds (the explanation names them); empty when the name is not known. */
    val peerName: String get() = messenger.peer(chat).value?.username.orEmpty()

    /** The message as it is now. */
    fun message(id: MessageId): Message? = messenger.messages(chat).value.firstOrNull { it.id == id }

    /** The one-time gate: something received, before it is first saved, says that the sender will see it; “Kaydet” is remembered, “Vazgeç” not. */
    suspend fun confirmFirstSave(message: Message): Boolean {
        if (!MediaExportPolicy.allowsExport(message)) return false
        if (!MediaExportPolicy.tellsSender(message) || memory.hasAcknowledgedSaveNotice) return true
        if (!askNotice(peerName, MediaExportPolicy.noticeKind(message))) return false
        memory.hasAcknowledgedSaveNotice = true
        return true
    }

    /** The viewer's and the player's “Kaydet”: to the gallery, the bytes from memory. */
    suspend fun saveToGallery(message: Message): Outcome {
        if (!MediaExportPolicy.savesToGallery(message)) return Outcome.NotAllowed
        if (!confirmFirstSave(message)) return Outcome.Cancelled
        val payload = messenger.mediaPayload(message.id, chat)
        if (payload == null || payload.data.isEmpty()) return Outcome.Failed
        return when (addToGallery(payload, MediaExportPolicy.isVideo(message))) {
            GalleryResult.Saved -> {
                messenger.markSaved(message.id, chat)
                Outcome.Saved
            }
            GalleryResult.Denied -> {
                presentGalleryDenied()
                Outcome.Denied
            }
            GalleryResult.Failed -> Outcome.Failed
        }
    }

    /** “Dosyalara kaydet” with the message's decrypted copy at [file] (the caller deletes the copy afterwards, saved or not). */
    suspend fun exportFile(message: Message, file: URI): Outcome {
        if (!MediaExportPolicy.allowsExport(message)) return Outcome.NotAllowed
        if (!confirmFirstSave(message)) return Outcome.Cancelled
        if (!presentExporter(file, fileName(file), mimeType(file))) return Outcome.Cancelled
        messenger.markSaved(message.id, chat)
        return Outcome.Saved
    }

    /** The file preview's share button: the share sheet with the copy; every share that went somewhere tells the messenger. */
    suspend fun shareFile(message: Message, file: URI): Outcome {
        if (!MediaExportPolicy.allowsExport(message)) return Outcome.NotAllowed
        if (!confirmFirstSave(message)) return Outcome.Cancelled
        val id = message.id
        val shown = presentShareSheet(file, mimeType(file)) { MediaWork.scope.launch { messenger.markSaved(id, chat) } }
        return if (shown) Outcome.Presented else Outcome.Failed
    }

    companion object {
        fun fileName(file: URI): String = runCatching { File(file).name }.getOrNull().orEmpty().ifEmpty { "file" }

        /** The MIME type from the copy's extension (the copy is named after the message's file). */
        fun mimeType(file: URI): String {
            val extension = fileName(file).substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        }
    }
}

/**
 * The [MediaSaver] of [chat] with the app's steps: the one-time sheet through the layer's floating
 * presenter, the gallery through MediaStore, the document picker and the share sheet under the
 * app's [ExternalActivityGuard] (so returning from them does not lock).
 */
@Composable
fun rememberMediaSaver(chat: ChatId): MediaSaver {
    val messenger = LocalMessenger.current
    val preferences = LocalAppModel.current.preferences
    val presenter = LocalFloatingSheetPresenter.current
    val guard = LocalExternalActivityGuard.current
    val context = LocalContext.current
    val requests = remember { PendingRequests() }
    val permission = rememberGuardedLauncher(ActivityResultContracts.RequestPermission()) { granted -> requests.permission?.complete(granted) }
    val exporter = rememberGuardedLauncher(ExportDocument()) { uri -> requests.document?.complete(uri) }
    return remember(chat, messenger, preferences, presenter, guard, context) {
        MediaSaver(chat, messenger, PreferencesSaveNoticeMemory(preferences)).apply {
            askNotice = { peer, kind -> SaveNotice.ask(presenter, peer, kind) }
            addToGallery = { payload, isVideo ->
                val allowed = GalleryWriter.hasAccess(context) || run {
                    val answer = CompletableDeferred<Boolean>()
                    requests.permission = answer
                    runCatching { permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }.onFailure { answer.complete(false) }
                    answer.await()
                }
                if (allowed) GalleryWriter.add(context, payload, isVideo) else MediaSaver.GalleryResult.Denied
            }
            presentExporter = { file, name, mimeType ->
                val answer = CompletableDeferred<Uri?>()
                requests.document = answer
                runCatching { exporter.launch(ExportDocument.Input(name, mimeType)) }.onFailure { answer.complete(null) }
                val target = answer.await()
                target != null && DocumentCopier.copy(context, file, target)
            }
            presentShareSheet = { file, mimeType, onShared -> ShareSheet.present(context, guard, file, mimeType, onShared) }
            presentGalleryDenied = { presenter?.let { GalleryAccessSheet.present(it, context, guard) } }
        }
    }
}

/** The answers the saver waits for from activity results. */
private class PendingRequests {
    var permission: CompletableDeferred<Boolean>? = null
    var document: CompletableDeferred<Uri?>? = null
}

/** `CreateDocument` with the MIME type given per launch (the copy's own). */
private class ExportDocument : androidx.activity.result.contract.ActivityResultContract<ExportDocument.Input, Uri?>() {
    data class Input(val name: String, val mimeType: String)

    override fun createIntent(context: Context, input: Input): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.name)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}

/** Copies the preview copy into the document the user picked. */
private object DocumentCopier {
    suspend fun copy(context: Context, file: URI, target: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(target, "w")?.use { out -> File(file).inputStream().use { it.copyTo(out) } } != null
        }.getOrDefault(false)
    }
}

// MARK: - The gallery

/**
 * Adds to the device's gallery through MediaStore (Pictures/us2us, Movies/us2us): the library is
 * never read. Android 10+ needs no permission; Android 9 needs the storage permission.
 */
object GalleryWriter {
    private const val FOLDER = "us2us"

    fun hasAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** A gallery name for the bytes: `us2us-<random>.<ext>` from the content type. */
    fun displayName(payload: MediaPayload, isVideo: Boolean): String {
        val type = payload.contentType.lowercase().substringBefore(';').trim()
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: if (isVideo) "mp4" else "jpg"
        return "us2us-${UUID.randomUUID().toString().take(8)}.$extension"
    }

    suspend fun add(context: Context, payload: MediaPayload, isVideo: Boolean): MediaSaver.GalleryResult = withContext(Dispatchers.IO) {
        val name = displayName(payload, isVideo)
        val mime = payload.contentType.substringBefore(';').trim().ifEmpty { if (isVideo) "video/mp4" else "image/jpeg" }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) addScoped(context, payload, isVideo, name, mime) else addLegacy(context, payload, isVideo, name, mime)
        }.getOrDefault(MediaSaver.GalleryResult.Failed)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun addScoped(context: Context, payload: MediaPayload, isVideo: Boolean, name: String, mime: String): MediaSaver.GalleryResult {
        val resolver = context.contentResolver
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return MediaSaver.GalleryResult.Failed
        val written = runCatching { resolver.openOutputStream(uri)?.use { it.write(payload.data.toByteArray()) } != null }.getOrDefault(false)
        if (!written) {
            resolver.delete(uri, null, null)
            return MediaSaver.GalleryResult.Failed
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return MediaSaver.GalleryResult.Saved
    }

    @Suppress("DEPRECATION")
    private fun addLegacy(context: Context, payload: MediaPayload, isVideo: Boolean, name: String, mime: String): MediaSaver.GalleryResult {
        if (!hasAccess(context)) return MediaSaver.GalleryResult.Denied
        val base = Environment.getExternalStoragePublicDirectory(if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES)
        val folder = File(base, FOLDER)
        if (!folder.isDirectory && !folder.mkdirs()) return MediaSaver.GalleryResult.Failed
        val file = File(folder, name)
        file.writeBytes(payload.data.toByteArray())
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
        return MediaSaver.GalleryResult.Saved
    }
}

// MARK: - The share sheet

/**
 * The system share sheet with the preview copy (through the module's FileProvider, readable by the
 * chosen app only). The chooser reports the app it went to; each time, [onShared] runs.
 */
private object ShareSheet {
    private const val ACTION = "com.syconx.us2us.media.SHARED"

    fun present(context: Context, guard: ExternalActivityGuard, file: URI, mimeType: String, onShared: () -> Unit): Boolean {
        val uri = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.media.files", File(file)) }.getOrNull() ?: return false
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        val action = "$ACTION.${UUID.randomUUID()}"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching { context.applicationContext.unregisterReceiver(this) }
                onShared()
            }
        }
        ContextCompat.registerReceiver(context.applicationContext, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        val callback = PendingIntent.getBroadcast(
            context,
            0,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
        )
        val chooser = Intent.createChooser(send, null, callback.intentSender)
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            guard.launching { context.startActivity(chooser) }
            true
        }.getOrElse {
            runCatching { context.applicationContext.unregisterReceiver(receiver) }
            false
        }
    }
}

// MARK: - The explanation (floating sheet)

/** Asks the one-time explanation through the floating presenter of the current layer. */
internal object SaveNotice {
    suspend fun ask(presenter: U2FloatingSheetPresenter?, peer: String, kind: SaveNoticeKind): Boolean {
        presenter ?: return false
        val answer = CompletableDeferred<Boolean>()
        var saved = false
        val shown = presenter.present(
            content = { close ->
                SaveNoticeContent(
                    peer = peer,
                    kind = kind,
                    onSave = {
                        saved = true
                        close()
                    },
                    onCancel = close,
                )
            },
            onDismiss = { answer.complete(saved) },
        )
        if (!shown) return false
        return answer.await()
    }
}

/** Test tags of the floating sheets of this file. */
object SaveNoticeTags {
    const val CONFIRM = "save.notice.confirm"
    const val CANCEL = "save.notice.cancel"
    const val GALLERY_SETTINGS = "save.photos.settings"
    const val GALLERY_CLOSE = "save.photos.close"
}

/**
 * “deniz fotoğrafı kaydettiğini görecek”: the 3.3 floating sheet's layout — the Doto title, the
 * `sheetBody` line, “Kaydet” (C1, download glyph) and “Vazgeç” (C3). Padding 18 18 28 18.
 */
@Composable
fun SaveNoticeContent(peer: String, kind: SaveNoticeKind, onSave: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val title = when {
        peer.isEmpty() -> stringResource(R.string.conversation_save_notice_title_generic)
        kind == SaveNoticeKind.Photo -> stringResource(R.string.conversation_save_notice_title_photo, peer)
        kind == SaveNoticeKind.Video -> stringResource(R.string.conversation_save_notice_title_video, peer)
        else -> stringResource(R.string.conversation_save_notice_title_file, peer)
    }
    FloatingSheetLayout(title, stringResource(R.string.conversation_save_notice_body), modifier) {
        U2PrimaryButton(onClick = onSave, modifier = Modifier.testTag(SaveNoticeTags.CONFIRM)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                MediaGlyphIcon(MediaGlyph.Download, 20.dp, tint = currentLabelColor())
                U2Text(stringResource(R.string.conversation_save_notice_confirm), LocalU2LabelStyle.current.style, color = currentLabelColor())
            }
        }
        U2TextButton(
            title = stringResource(R.string.conversation_save_notice_cancel),
            onClick = onCancel,
            tone = U2TextButtonTone.Accent,
            height = U2Size.textButtonS,
            modifier = Modifier.testTag(SaveNoticeTags.CANCEL),
        )
    }
}

/**
 * The 3.3 floating sheet's layout for this module's sheets: the text block (pad 6 4 0 4, gap 10:
 * the balanced Doto title, the `sheetBody` paragraph in `text·68%`, then [extra]) and 18 below it
 * the buttons (gap 8). The host sheet's padding is 18 18 28 18.
 */
@Composable
internal fun FloatingSheetLayout(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    extra: @Composable () -> Unit = {},
    buttons: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 6.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            U2DisplayText(title, U2TextStyles.displaySheetTitle.balanced, isHeader = true)
            U2Text(message, U2TextStyles.sheetBody, color = U2Theme.colors.textSecondary)
            extra()
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
    }
}

/**
 * The gallery refused (the storage permission is off, Android 9): why, and the way to the app's
 * settings (the voice recorder's permission sheet, for the gallery).
 */
@Composable
fun GalleryAccessContent(onOpenSettings: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    FloatingSheetLayout(
        title = stringResource(MediaR.string.media_android_gallery_denied_title),
        message = stringResource(MediaR.string.media_android_gallery_denied_body),
        modifier = modifier,
    ) {
        U2PrimaryButton(
            title = stringResource(R.string.conversation_voice_permission_settings),
            onClick = onOpenSettings,
            glyph = PhosphorGlyph.GearSix,
            modifier = Modifier.testTag(SaveNoticeTags.GALLERY_SETTINGS),
        )
        U2TextButton(
            title = stringResource(R.string.common_close),
            onClick = onClose,
            tone = U2TextButtonTone.Accent,
            height = U2Size.textButtonS,
            modifier = Modifier.testTag(SaveNoticeTags.GALLERY_CLOSE),
        )
    }
}

internal object GalleryAccessSheet {
    fun present(presenter: U2FloatingSheetPresenter, context: Context, guard: ExternalActivityGuard) {
        presenter.present(
            content = { close ->
                GalleryAccessContent(
                    onOpenSettings = {
                        close()
                        AppSettings.open(context, guard)
                    },
                    onClose = close,
                )
            },
            onDismiss = {},
        )
    }
}

/** The app's page in the system settings (permissions). */
internal object AppSettings {
    fun open(context: Context, guard: ExternalActivityGuard) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { guard.launching { context.startActivity(intent) } }
    }
}

// MARK: - The viewers' “Kaydet”

/**
 * The viewers' “Kaydet”: what the button shows and the toast after it (“Galeriye kaydedildi”, or
 * that it could not be saved).
 */
@Stable
class ViewerSaveState {
    var phase: MediaSavePhase by mutableStateOf(MediaSavePhase.Idle)
        private set

    /** The toast to show (a string resource of this module or the design system). */
    var toast: ViewerToast? by mutableStateOf(null)

    /** Saved here before (`savedByMe`): the check, until it is saved again. */
    fun phase(message: Message): MediaSavePhase = if (phase == MediaSavePhase.Idle && message.savedByMe) MediaSavePhase.Saved else phase

    fun save(message: Message, saver: MediaSaver, haptics: com.syconx.us2us.ui.env.U2Haptics) {
        if (phase == MediaSavePhase.Working) return
        phase = MediaSavePhase.Working
        MediaWork.scope.launch {
            when (saver.saveToGallery(message)) {
                MediaSaver.Outcome.Saved -> {
                    phase = MediaSavePhase.Saved
                    haptics.success()
                    toast = ViewerToast.Saved
                }
                MediaSaver.Outcome.Failed -> {
                    phase = MediaSavePhase.Idle
                    haptics.error()
                    toast = ViewerToast.Failed
                }
                else -> phase = MediaSavePhase.Idle
            }
        }
    }
}

/** The viewers' toasts. */
enum class ViewerToast { Saved, Failed }

/** The toast's text. */
@Composable
internal fun ViewerToast.text(): String = when (this) {
    ViewerToast.Saved -> stringResource(MediaR.string.media_android_saved_toast)
    ViewerToast.Failed -> stringResource(R.string.conversation_viewer_save_failed)
}
