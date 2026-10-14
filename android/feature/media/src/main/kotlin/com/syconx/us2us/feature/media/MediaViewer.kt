package com.syconx.us2us.feature.media

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.domain.MediaExportPolicy
import com.syconx.us2us.domain.MediaSource
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.env.ForceDarkSystemBars
import com.syconx.us2us.ui.env.LocalExternalActivityGuard
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.env.LocalMessenger
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.media.DesignArtworkBitmap
import com.syconx.us2us.ui.media.MediaGlassCircle
import com.syconx.us2us.ui.media.MediaImageDecoder
import com.syconx.us2us.ui.media.MediaStateGlyph
import com.syconx.us2us.ui.media.ReadyGlyph
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.u2Spec
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.sheet.U2Toast
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Test tags of the photo viewer. */
object MediaViewerTags {
    const val CLOSE = "mediaViewer.close"
    const val LOADING = "mediaViewer.loading"
    const val FAILED = "mediaViewer.failed"
    const val CAPTION = "mediaViewer.caption"
    const val CHROME = "mediaViewer.chrome"
}

/**
 * The photo viewer (5.1 “tap photo → media viewer”, proposed in 5.6's language): the whole photo,
 * fitted to the screen, on `#0d0e19`; pinch to zoom (up to 4× or the photo's own detail),
 * double-tap to zoom in at that point and out again, pan while zoomed, swipe up or down to close.
 *
 * 5.6's chrome without its view-once parts: the shades, close · sender · “Bugün 18:40” · “Kaydet”
 * (to the gallery; the sender is told, PROTOCOL §8.4 SAVED — never for a disappearing photo), and
 * the caption over the bottom shade. A single tap shows or hides the chrome (0.24 s); zooming in
 * hides it, back at 1× it returns. The bubble's image shows at once; the full photo is decrypted
 * (downloaded first if needed) and cross-fades in (0.24 s). Everything stays in memory.
 */
@Composable
fun MediaViewer(
    item: ViewerItem,
    chat: ChatId,
    saver: MediaSaver,
    appDark: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val messenger = LocalMessenger.current
    val context = LocalContext.current
    val locale = currentAppLocale()
    val haptics = LocalHaptics.current
    val reduceMotion = LocalReduceMotion.current
    val messages by remember(chat) { messenger.messages(chat) }.collectAsState()
    val message = item.messageId?.let { id -> messages.firstOrNull { it.id == id } }

    var image by remember(item) { mutableStateOf<ImageBitmap?>(null) }
    var isLoadingFull by remember(item) { mutableStateOf(true) }
    var showsLoading by remember(item) { mutableStateOf(false) }
    var failed by remember(item) { mutableStateOf(false) }
    var chromeVisible by remember(item) { mutableStateOf(true) }
    var dismissProgress by remember { mutableFloatStateOf(0f) }
    var dismissAnimated by remember { mutableStateOf(false) }
    val saving = remember(item) { ViewerSaveState() }

    LaunchedEffect(item) {
        val initialData = (item.media as? MediaSource.Memory)?.data
        if (image == null) image = MediaImages.load(context, item.media, MediaImages.VIEWER_MAX_PIXEL)
        if (item.media is MediaSource.Artwork) isLoadingFull = false
        val id = item.messageId
        if (!isLoadingFull || id == null) {
            isLoadingFull = false
            return@LaunchedEffect
        }
        val indicator = launch {
            delay(350)
            if (isLoadingFull) showsLoading = true
        }
        try {
            val payload = messenger.mediaPayload(id, chat)
            if (payload == null) {
                failed = true
                return@LaunchedEffect
            }
            if (payload.data == initialData && image != null) return@LaunchedEffect
            val full = MediaImages.decode(payload.data, MediaImages.VIEWER_MAX_PIXEL)
            if (full != null) image = full else if (image == null) failed = true
        } finally {
            indicator.cancel()
            isLoadingFull = false
            showsLoading = false
        }
    }

    val progress by animateFloatAsState(
        dismissProgress,
        if (dismissAnimated) U2Motion.resolve(U2Motion.spring(), reduceMotion) else androidx.compose.animation.core.snap(),
        label = "dismissProgress",
    )
    val chromeTarget = if (chromeVisible) 1f else 0f
    val chromeShown by animateFloatAsState(chromeTarget, u2Spec(U2Motion.micro), label = "chrome")
    val chromeOpacity = chromeShown * MediaDismissGesture.chromeOpacity(progress)

    ForceDarkSystemBars()
    AlwaysDark {
        val colors = U2Theme.colors
        U2Screen(modifier, background = androidx.compose.ui.graphics.Color.Transparent) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = MediaDismissGesture.backdropOpacity(progress) }
                    .background(colors.mediaViewerBg),
            )
            ZoomableImage(
                    image = image,
                    contentDescription = stringResource(R.string.conversation_viewer_photo_a11y),
                    zoomHint = stringResource(R.string.conversation_viewer_zoomhint),
                    startsZoomed = item.startsZoomed,
                    locale = locale,
                    onSingleTap = { chromeVisible = !chromeVisible },
                    onZoomChange = { zoomed -> chromeVisible = !zoomed },
                    onDismissProgress = { value, animated ->
                        dismissAnimated = animated
                        dismissProgress = value
                    },
                    onDismiss = onClose,
                )
            val fade = U2Transitions.fade(U2Motion.state)
            AnimatedVisibility(showsLoading, Modifier.align(Alignment.Center), enter = fade.enter, exit = fade.exit) {
                MediaGlassCircle(Modifier.testTag(MediaViewerTags.LOADING)) {
                    MediaStateGlyph(loadingState(message), readyGlyph = ReadyGlyph.None)
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag(MediaViewerTags.CHROME)
                    .graphicsLayer { alpha = chromeOpacity },
            ) {
                // Drawn while it shows at all; it fades with the chrome's opacity.
                if (chromeOpacity > 0.01f) ViewerChrome(item, message, appDark, failed, saving, saver, haptics, onClose)
            }
            val toast = saving.toast
            U2Toast(toast?.text(), onDismiss = { saving.toast = null })
        }
    }
}

@Composable
private fun ViewerChrome(
    item: ViewerItem,
    message: Message?,
    appDark: Boolean,
    failed: Boolean,
    saving: ViewerSaveState,
    saver: MediaSaver,
    haptics: com.syconx.us2us.ui.env.U2Haptics,
    onClose: () -> Unit,
) {
    val colors = U2Theme.colors
    MediaViewerShades()
    Column(Modifier.fillMaxSize()) {
        MediaViewerTopBar(item.sender, item.sentAt, appDark, MediaViewerTags.CLOSE, onClose) {
            val savable = message?.takeIf(MediaExportPolicy::savesToGallery)
            if (savable != null) {
                AppThemed(appDark) {
                    MediaSaveButton(saving.phase(savable), MediaExportPolicy.tellsSender(savable), onClick = { saving.save(savable, saver, haptics) })
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .u2Bottom(50.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (failed) {
                Row(
                    Modifier.testTag(MediaViewerTags.FAILED),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val ink = colors.onMedia.copy(alpha = 0.72f)
                    PhosphorIcon(PhosphorGlyph.WarningCircle, 18.dp, tint = ink)
                    U2Text(stringResource(R.string.conversation_viewer_photo_failed), U2TextStyles.label15Emphasis, color = ink)
                }
            }
            val caption = item.caption
            if (!caption.isNullOrEmpty()) {
                U2Text(
                    caption,
                    U2TextStyles.bubble,
                    color = colors.onMedia,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    modifier = Modifier.testTag(MediaViewerTags.CAPTION),
                )
            }
        }
    }
}

/** While the full photo is on its way: the bubble's state (the ring fills while it downloads). */
private fun loadingState(message: Message?): MediaAvailability = when (val availability = message?.mediaAvailability) {
    is MediaAvailability.Downloading -> availability
    MediaAvailability.Unavailable, MediaAvailability.Held -> MediaAvailability.Unavailable
    else -> MediaAvailability.Downloading(null)
}

/** Decodes a [MediaSource] (or image bytes) into a bitmap off the main thread, at most `maxPixel` on its longest edge. */
object MediaImages {
    /** The longest edge decoded in the viewer: room to zoom without holding a 48 MP bitmap. */
    const val VIEWER_MAX_PIXEL = 4_096

    suspend fun load(context: Context, source: MediaSource, maxPixel: Int): ImageBitmap? = when (source) {
        // The design's painting, large enough to zoom into.
        is MediaSource.Artwork -> withContext(Dispatchers.Default) {
            runCatching { DesignArtworkBitmap.render(source.artwork, minOf(maxPixel, 2_048)).asImageBitmap() }.getOrNull()
        }
        is MediaSource.Memory -> decode(source.data, maxPixel)
        is MediaSource.File -> withContext(Dispatchers.IO) { MediaImageDecoder.decode(context, source.uri, maxPixel) }
    }

    suspend fun decode(data: Bytes, maxPixel: Int): ImageBitmap? {
        if (data.isEmpty()) return null
        return withContext(Dispatchers.Default) { MediaImageDecoder.decode(data, maxPixel) }
    }
}

/**
 * Ordinary media closes when the app leaves the foreground (the lock follows on return): nothing
 * decrypted waits behind it. Our own system activities (the share sheet, the document picker, a
 * permission dialog) do not count.
 */
@Composable
internal fun CloseOnBackground(onClose: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val guard = LocalExternalActivityGuard.current
    val currentOnClose by rememberUpdatedState(onClose)
    DisposableEffect(owner, guard) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !guard.isActive) currentOnClose()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
