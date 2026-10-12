package com.syconx.us2us.feature.media

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.MediaFileKind
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.CenteredTitleHeader
import com.syconx.us2us.ui.components.HeaderSpacer
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.env.LocalMessenger
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.bubbles.StateCrossFade
import com.syconx.us2us.ui.media.MediaGlassCircle
import com.syconx.us2us.ui.media.MediaGlyph
import com.syconx.us2us.ui.media.MediaGlyphIcon
import com.syconx.us2us.ui.media.LocalConversationMediaActivity
import com.syconx.us2us.ui.media.MediaImageDecoder
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.sheet.U2Sheet
import com.syconx.us2us.ui.sheet.U2SheetStyle
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// 5.2 “File → Quick Look” and “Dosyalara kaydet”: the file's content in the app's own screen (with
// the app's share button, so the sender is told, PROTOCOL §8.4 SAVED) and the system's document
// picker, both over a named copy in `ProtectedPreviewFiles` that is deleted the moment they close.
// A disappearing message's file gets neither the share button nor the picker.

/** How a file is shown in the app. */
enum class FilePreviewKind { Pdf, Image, Text, Audio }

/**
 * Which files the app previews: PDF (drawn page by page), pictures the platform decodes, plain
 * text, and audio. Web content (HTML, XML, SVG, web archives, scripts) never — it could fetch
 * remote resources and reveal the address. Office and iWork documents have no renderer on Android:
 * those, and whatever cannot be shown, can still be saved through the document picker.
 */
object FilePreviewPolicy {
    private val blocked = setOf("html", "htm", "xhtml", "xml", "svg", "svgz", "webarchive", "mht", "mhtml", "js", "mjs")
    private val text = setOf("txt", "text", "md", "markdown", "csv", "tsv", "log")

    fun kind(fileName: String): FilePreviewKind? {
        val extension = fileName.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        if (extension.isEmpty() || extension in blocked) return null
        if (extension in text) return FilePreviewKind.Text
        return when (MediaFileKind.of(fileName)) {
            MediaFileKind.Pdf -> FilePreviewKind.Pdf
            MediaFileKind.Audio -> FilePreviewKind.Audio
            MediaFileKind.Image -> if (MediaFileKind.opensInPhotoViewer(fileName)) FilePreviewKind.Image else null
            MediaFileKind.Movie, MediaFileKind.Other -> null
        }
    }

    fun allowsPreview(fileName: String): Boolean = kind(fileName) != null

    /** The copy can be shown: an allowed kind whose bytes are there. */
    fun canPreview(file: URI): Boolean {
        val copy = runCatching { File(file) }.getOrNull() ?: return false
        return copy.isFile && allowsPreview(copy.name)
    }
}

/** A file the app will not show (or that failed): the floating sheet says so and offers what is possible. */
data class FileNotice(val kind: Kind, val messageId: MessageId, val fileName: String, val savableFile: URI? = null) {
    enum class Kind {
        NotPreviewable,

        /** Not previewable, and in a disappearing message: it cannot be saved either. */
        NotPreviewableDisappearing,
        OpenFailed,
    }
}

/** Test tags of the file preview and its notice. */
object FilePreviewTags {
    const val CLOSE = "file.protected.close"
    const val SHARE = "file.share"
    const val NOTE = "file.protected.note"
    const val PREVIEW = "file.preview"
    const val PROTECTED_PREVIEW = "file.protected.preview"
    const val SAVE = "file.save"
    const val NOTICE_CLOSE = "file.notice.close"
    const val PAGE = "file.page"
}

/**
 * The file notice (C18 floating, 3.3's layout): the file's name (or “Dosya açılamadı”), why, and
 * “Dosyalara kaydet” when there is a copy to save; “Vazgeç” / “Kapat”.
 */
@Composable
fun FileNoticeContent(notice: FileNotice, onSave: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val title = when (notice.kind) {
        FileNotice.Kind.NotPreviewable, FileNotice.Kind.NotPreviewableDisappearing -> notice.fileName
        FileNotice.Kind.OpenFailed -> stringResource(R.string.conversation_file_openfailed)
    }
    val message = stringResource(
        when (notice.kind) {
            FileNotice.Kind.NotPreviewable -> R.string.conversation_file_notpreviewable
            FileNotice.Kind.NotPreviewableDisappearing -> R.string.conversation_file_notpreviewable_disappearing
            FileNotice.Kind.OpenFailed -> R.string.conversation_file_openfailed_detail
        },
    )
    FloatingSheetLayout(title, message, modifier) {
        if (notice.savableFile != null) {
            U2PrimaryButton(
                title = stringResource(R.string.conversation_file_savetofiles),
                onClick = onSave,
                glyph = PhosphorGlyph.Export,
                modifier = Modifier.testTag(FilePreviewTags.SAVE),
            )
        }
        U2TextButton(
            title = stringResource(if (notice.savableFile != null) R.string.common_cancel else R.string.common_close),
            onClick = onClose,
            tone = U2TextButtonTone.Accent,
            height = U2Size.textButtonS,
            modifier = Modifier.testTag(FilePreviewTags.NOTICE_CLOSE),
        )
    }
}

/**
 * A file message previewed (proposed, 5.2 › File → Quick Look): the app's own screen — the C7
 * header (close · the file's name · share) and the content under it. Everything that leaves the app
 * goes through the header's share button ([onShare]), which tells the sender after the one-time
 * explanation. A file of a disappearing message has no share button ([onShare] `null`): a line
 * under the header says the file stays in the chat. Follows the app's theme.
 */
@Composable
fun ProtectedFilePreviewScreen(
    title: String,
    file: URI,
    kind: FilePreviewKind,
    onShare: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = U2Theme.colors
    U2Screen(modifier) {
        Column(Modifier.fillMaxSize()) {
            CenteredTitleHeader(
                title = title,
                leading = {
                    U2GlassIconButton(
                        PhosphorGlyph.X,
                        stringResource(R.string.common_a11y_close),
                        onClick = onClose,
                        modifier = Modifier.testTag(FilePreviewTags.CLOSE),
                    )
                },
                trailing = {
                    if (onShare != null) {
                        U2GlassIconButton(
                            PhosphorGlyph.Export,
                            stringResource(R.string.conversation_file_share) + ". " + stringResource(R.string.conversation_file_share_hint),
                            onClick = onShare,
                            modifier = Modifier.testTag(FilePreviewTags.SHARE),
                        )
                    } else {
                        HeaderSpacer()
                    }
                },
            )
            if (onShare == null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, top = 10.dp, end = 24.dp, bottom = 12.dp)
                        .testTag(FilePreviewTags.NOTE)
                        .semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhosphorIcon(PhosphorGlyph.Timer, 14.dp, tint = colors.textSecondary)
                    U2Text(stringResource(R.string.conversation_file_protected_note), U2TextStyles.meta, color = colors.textSecondary, maxLines = 2)
                }
            } else {
                Spacer(Modifier.height(12.dp))
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag(if (onShare == null) FilePreviewTags.PROTECTED_PREVIEW else FilePreviewTags.PREVIEW),
            ) {
                when (kind) {
                    FilePreviewKind.Pdf -> PdfPreview(file)
                    FilePreviewKind.Image -> ImagePreview(file)
                    FilePreviewKind.Text -> TextPreview(file)
                    FilePreviewKind.Audio -> AudioPreview(file)
                }
            }
        }
    }
}

// MARK: - Renderers

/**
 * The PDF's pages, drawn as they scroll into view, as iOS Quick Look lays them out: nearly the
 * screen's width (8 px margins at 3×), square corners, 19 px apart, on black in the dark theme.
 */
@Composable
private fun PdfPreview(file: URI) {
    val document = remember(file) { PdfDocument.open(file) }
    DisposableEffect(document) { onDispose { document?.close() } }
    if (document == null || document.pageCount == 0) {
        PreviewFailed()
        return
    }
    val margin = PdfPageMargin
    val width = with(LocalDensity.current) { (LocalConfiguration.current.screenWidthDp.dp - margin * 2).roundToPx() }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(if (U2Theme.isDark) Color.Black else U2Theme.colors.bg),
        contentPadding = PaddingValues(start = margin, top = margin, end = margin, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(PdfPageGap),
    ) {
        items((0 until document.pageCount).toList()) { index ->
            val aspect = document.aspect(index)
            val page by produceState<ImageBitmap?>(null, document, index, width) { value = document.render(index, width) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect)
                    .background(Color.White)
                    .testTag(FilePreviewTags.PAGE),
            ) {
                page?.let { Image(it, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
            }
        }
    }
}

/** One open PDF: its page sizes, and pages rendered one at a time (the renderer is not thread-safe). */
internal class PdfDocument private constructor(private val descriptor: ParcelFileDescriptor, private val renderer: PdfRenderer) {
    private val lock = Mutex()
    private var closed = false
    private val sizes: List<Pair<Int, Int>> = (0 until renderer.pageCount).map { index ->
        renderer.openPage(index).use { it.width to it.height }
    }

    val pageCount: Int get() = sizes.size

    fun aspect(index: Int): Float = sizes[index].let { (w, h) -> if (h > 0) w.toFloat() / h else 1f / 1.414f }

    suspend fun render(index: Int, width: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (closed || width <= 0) return@withLock null
            runCatching {
                renderer.openPage(index).use { page ->
                    val height = (width / aspect(index)).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(AndroidColor.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.asImageBitmap()
                }
            }.getOrNull()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    companion object {
        fun open(file: URI): PdfDocument? = runCatching {
            val descriptor = ParcelFileDescriptor.open(File(file), ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                PdfDocument(descriptor, PdfRenderer(descriptor))
            } catch (failure: Exception) {
                descriptor.close()
                throw failure
            }
        }.getOrNull()
    }
}

@Composable
private fun ImagePreview(file: URI) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, file) { value = withContext(Dispatchers.IO) { MediaImageDecoder.decode(context, file, 4_096) } }
    Box(Modifier.fillMaxSize().padding(bottom = 34.dp), contentAlignment = Alignment.Center) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
    }
}

/** Plain text (at most the first megabyte), selectable like Quick Look's. */
@Composable
private fun TextPreview(file: URI) {
    val text by produceState<String?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching { File(file).inputStream().use { String(it.readNBytes(1 shl 20), Charsets.UTF_8) } }.getOrNull()
        }
    }
    val shown = text ?: return
    androidx.compose.foundation.text.selection.SelectionContainer {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 40.dp),
        ) {
            U2Text(shown, U2TextStyles.body, color = U2Theme.colors.text)
        }
    }
}

/** An audio file: the player's play button and scrubber in the middle. */
@Composable
private fun AudioPreview(file: URI) {
    val context = LocalContext.current
    val factory = LocalVideoEngineFactory.current
    val playback = remember(file) { VideoPlaybackModel(kotlin.time.Duration.ZERO, factory.create(context)) }
    LaunchedEffect(playback) {
        val bytes = withContext(Dispatchers.IO) { runCatching { File(file).readBytes() }.getOrNull() }
        val name = runCatching { File(file).name }.getOrDefault("audio")
        playback.start(bytes?.let { com.syconx.us2us.domain.MediaPayload(com.syconx.us2us.domain.Bytes.wrap(it), MediaSaver.mimeType(file), name) }, autoplay = false)
    }
    LaunchedEffect(playback.isPlaying) {
        playback.refresh()
        while (playback.isPlaying) {
            kotlinx.coroutines.delay(33)
            playback.refresh()
        }
    }
    DisposableEffect(playback) { onDispose { playback.close() } }
    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        AudioControls(playback)
    }
}

/** An audio file's controls on a media card: the player's 64 glass play button over its scrubber. */
@Composable
private fun AudioControls(playback: VideoPlaybackModel) {
    AlwaysDark {
        val colors = U2Theme.colors
        val ready = playback.phase == VideoPlaybackModel.Phase.Ready || playback.phase == VideoPlaybackModel.Phase.Ended
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.mediaViewerBg)
                .padding(start = 16.dp, top = 28.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val label = stringResource(if (playback.isPlaying) R.string.conversation_player_pause else R.string.conversation_player_play)
            MediaGlassCircle(
                modifier = Modifier
                    .clickable(enabled = ready, role = Role.Button, onClickLabel = label) { playback.togglePlay() }
                    .semantics { contentDescription = label },
                diameter = 64.dp,
            ) {
                StateCrossFade(playback.isPlaying) { playing ->
                    if (playing) {
                        MediaGlyphIcon(MediaGlyph.Pause, 26.dp, tint = colors.onMedia)
                    } else {
                        PhosphorIcon(PhosphorGlyph.Play, 26.dp, weight = PhosphorWeight.Fill, tint = colors.onMedia)
                    }
                }
            }
            VideoScrubber(playback = playback, onInteraction = {}, enabled = ready)
        }
    }
}

@Composable
private fun PreviewFailed() {
    val colors = U2Theme.colors
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PhosphorIcon(PhosphorGlyph.WarningCircle, 28.dp, tint = colors.textSecondary)
        U2Text(stringResource(R.string.conversation_file_openfailed), U2TextStyles.label15Emphasis, color = colors.textSecondary)
    }
}

// MARK: - The cover

private sealed interface FileStep {
    data object Loading : FileStep

    data class Preview(val file: URI, val kind: FilePreviewKind, val shares: Boolean) : FileStep

    data class Notice(val notice: FileNotice) : FileStep
}

/**
 * The file preview of [message], for a full-screen cover: the copy is decrypted (`previewFile`,
 * downloaded first if needed) while the tile shows its ring; then the preview fades in — or, for a
 * file it cannot show, the floating notice rises over the conversation. The copy is deleted when it
 * closes, however it closes.
 */
@Composable
internal fun FilePreview(chat: ChatId, message: MessageId, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val messenger = LocalMessenger.current
    val haptics = LocalHaptics.current
    val activity = LocalConversationMediaActivity.current
    val reduceMotion = LocalReduceMotion.current
    val saver = rememberMediaSaver(chat)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val messages by remember(chat) { messenger.messages(chat) }.collectAsState()
    val found = messages.firstOrNull { it.id == message }
    val name = (found?.content as? MessageContent.File)?.name.orEmpty()
    var step by remember(chat, message) { mutableStateOf<FileStep>(FileStep.Loading) }
    var copy by remember(chat, message) { mutableStateOf<URI?>(null) }
    var closed by remember(chat, message) { mutableStateOf(false) }
    var noticeShown by remember(chat, message) { mutableStateOf(false) }
    var exportsAfterNotice by remember(chat, message) { mutableStateOf(false) }
    val alpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    fun close() {
        if (closed) return
        closed = true
        currentOnDismiss()
    }

    LaunchedEffect(chat, message) {
        activity?.begin(message)
        val file = try {
            messenger.previewFile(message, chat)
        } finally {
            activity?.end(message)
        }
        val current = messenger.messages(chat).value.firstOrNull { it.id == message }
        when {
            file == null -> {
                haptics.error()
                step = FileStep.Notice(FileNotice(FileNotice.Kind.OpenFailed, message, name))
            }
            current == null || current.isDeleted || closed -> {
                messenger.discardPreviewFile(file)
                close()
            }
            else -> {
                copy = file
                val kind = FilePreviewPolicy.kind(runCatching { File(file).name }.getOrDefault(name))
                step = when {
                    kind != null && FilePreviewPolicy.canPreview(file) -> FileStep.Preview(file, kind, shares = !current.isDisappearing)
                    current.isDisappearing -> {
                        messenger.discardPreviewFile(file)
                        copy = null
                        FileStep.Notice(FileNotice(FileNotice.Kind.NotPreviewableDisappearing, message, name))
                    }
                    else -> FileStep.Notice(FileNotice(FileNotice.Kind.NotPreviewable, message, name, savableFile = file))
                }
            }
        }
        if (step is FileStep.Notice) noticeShown = true
        if (step is FileStep.Preview) alpha.animateTo(1f, U2Motion.resolve(U2Motion.state, reduceMotion).spec())
    }
    // However it went away, its copy goes (closing twice does nothing).
    DisposableEffect(chat, message) {
        onDispose { copy?.let(messenger::discardPreviewFile) }
    }
    // The message went (deleted, expired, taken back): so does its preview.
    val isGone = found == null || found.isDeleted
    LaunchedEffect(isGone) { if (isGone) close() }
    CloseOnBackground(::close)

    Box(modifier.fillMaxSize()) {
        when (val shown = step) {
            is FileStep.Preview -> ProtectedFilePreviewScreen(
                title = name,
                file = shown.file,
                kind = shown.kind,
                onShare = if (shown.shares) {
                    {
                        val current = saver.message(message)
                        if (current != null) MediaWork.scope.launch { saver.shareFile(current, shown.file) }
                    }
                } else {
                    null
                },
                onClose = ::close,
                modifier = Modifier.graphicsLayer { this.alpha = alpha.value },
            )
            is FileStep.Notice -> U2Sheet(
                isPresented = noticeShown,
                onDismissRequest = { noticeShown = false },
                style = U2SheetStyle.Floating,
                onDismissed = {
                    val file = shown.notice.savableFile
                    val current = saver.message(message)
                    if (exportsAfterNotice && file != null && current != null) {
                        // The picker answers this screen: it stays (empty) until the picker is done.
                        scope.launch {
                            val outcome = saver.exportFile(current, file)
                            if (outcome == MediaSaver.Outcome.Saved) haptics.success()
                            messenger.discardPreviewFile(file)
                            copy = null
                            close()
                        }
                    } else {
                        close()
                    }
                },
            ) {
                FileNoticeContent(
                    shown.notice,
                    onSave = {
                        exportsAfterNotice = true
                        noticeShown = false
                    },
                    onClose = { noticeShown = false },
                )
            }
            FileStep.Loading -> Unit
        }
    }
}

/** Quick Look's page margin (8 px at 3×) and the gap between pages (19 px), measured on the simulator. */
private val PdfPageMargin = 2.67.dp
private val PdfPageGap = 6.33.dp
