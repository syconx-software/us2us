package com.syconx.us2us.feature.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.SaveNoticeKind
import com.syconx.us2us.domain.ViewOnceState
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.ui.env.LocalAppModel
import com.syconx.us2us.ui.env.LocalHarnessState
import com.syconx.us2us.ui.env.LocalMessenger
import com.syconx.us2us.ui.sheet.LocalFloatingSheetPresenter
import com.syconx.us2us.ui.theme.U2Theme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 5.2 the photo viewer (a full-screen cover), opened at [message] of [chat]: a photo, or a picture
 * sent as a file. It asks to be dismissed when the message goes (deleted, expired, taken back) or
 * the app leaves the foreground: nothing decrypted waits behind the lock. `5.1-viewer-zoomed`
 * starts zoomed in.
 */
@Composable
fun MediaViewerCover(chat: ChatId, message: MessageId, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    MessageViewerCover(chat, message, ViewerItem.Kind.Photo, onDismiss, modifier) { item, saver, appDark, close ->
        MediaViewer(item, chat, saver, appDark, onClose = close)
    }
}

/**
 * 5.2 the video player, playing [message] of [chat] (a video, or a video sent as a file). It asks
 * to be dismissed when the message goes or the app leaves the foreground. `5.2-player` starts
 * paused at 0:07.
 */
@Composable
fun VideoPlayerCover(chat: ChatId, message: MessageId, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    MessageViewerCover(chat, message, ViewerItem.Kind.Video, onDismiss, modifier) { item, saver, appDark, close ->
        VideoPlayer(item, chat, saver, appDark, onClose = close)
    }
}

/** The photo viewer's and the player's cover: the message's item, closing once, closing when the message goes. */
@Composable
private fun MessageViewerCover(
    chat: ChatId,
    message: MessageId,
    kind: ViewerItem.Kind,
    onDismiss: () -> Unit,
    modifier: Modifier,
    content: @Composable (item: ViewerItem, saver: MediaSaver, appDark: Boolean, close: () -> Unit) -> Unit,
) {
    val appDark = U2Theme.isDark
    val messenger = LocalMessenger.current
    val harness = LocalHarnessState.current?.state
    val saver = rememberMediaSaver(chat)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val messages by remember(chat) { messenger.messages(chat) }.collectAsState()
    val own by messenger.ownUsername.collectAsState()
    val peer by remember(chat) { messenger.peer(chat) }.collectAsState()
    val found = messages.firstOrNull { it.id == message }
    var closed by remember(chat, message) { mutableStateOf(false) }
    val close = {
        if (!closed) {
            closed = true
            currentOnDismiss()
        }
    }
    val item = remember(chat, message, found != null) {
        found?.let { ViewerItem.forMessage(it, if (it.isOutgoing) own else peer?.username ?: chat.value) }
            ?.takeIf { it.kind == kind }
            ?.let {
                when (harness) {
                    MediaHarnessStates.VIEWER_ZOOMED -> it.copy(startsZoomed = true)
                    MediaHarnessStates.PLAYER -> it.copy(startTime = MediaHarnessStates.playerStartTime)
                    else -> it
                }
            }
    }
    // The message on screen was deleted, expired or wiped underneath — or taken back.
    val isGone = found == null || found.isDeleted || item == null
    LaunchedEffect(isGone) { if (isGone) close() }
    CloseOnBackground(close)
    // The harness's 5.1-save-notice: the one-time explanation over the viewer (nothing is saved).
    val presenter = LocalFloatingSheetPresenter.current
    LaunchedEffect(harness, item != null) {
        if (harness != MediaHarnessStates.SAVE_NOTICE || item?.kind != ViewerItem.Kind.Photo) return@LaunchedEffect
        delay(700)
        SaveNotice.ask(presenter, saver.peerName, SaveNoticeKind.Photo)
    }
    if (item == null) {
        Box(modifier.fillMaxSize().background(U2Theme.colors.mediaViewerBg))
        return
    }
    Box(modifier.fillMaxSize()) { content(item, saver, appDark, close) }
}

/**
 * 5.2 a file's preview (“File → Quick Look”): the copy is decrypted while the tile shows its ring
 * (`LocalConversationMediaActivity`), then the preview fades in — PDF, picture, text or audio, with
 * the share button unless the message disappears — or, for a file it cannot show, the floating
 * notice (“Dosyalara kaydet”) rises over a transparent cover. The copy is deleted when it closes.
 * It asks to be dismissed when the message goes or the app leaves the foreground.
 */
@Composable
fun FilePreviewCover(chat: ChatId, message: MessageId, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    FilePreview(chat, message, onDismiss, modifier)
}

/**
 * 5.2 the live-location map for the share in [message] (a fade cover; no swipe dismissal). It asks
 * to be dismissed when the share goes (deleted, the chat closed) and when the app leaves the
 * foreground: no position waits behind the lock.
 */
@Composable
fun LiveLocationMapCover(chat: ChatId, message: MessageId, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    var closed by remember(chat, message) { mutableStateOf(false) }
    val close = {
        if (!closed) {
            closed = true
            currentOnDismiss()
        }
    }
    CloseOnBackground(close)
    LiveLocationMap(chat, message, onClose = close, modifier = modifier)
}

/**
 * 5.6 a view-once photo or video: the message's media is fetched (`viewOnceMedia`) and shown until
 * the close button; closing marks it opened on both sides (`markViewOnceOpened`), a screenshot
 * tells the sender (`reportScreenshot`). Nothing to show (already opened, outgoing, gone): the
 * cover asks to be dismissed at once. The harness states `5.6`, `5.6-clean` and `5.6-captured`
 * show the design's own photo instead of a message's.
 */
@Composable
fun ViewOnceCover(chat: ChatId, message: MessageId, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val appDark = U2Theme.isDark
    val messenger = LocalMessenger.current
    val harness = LocalHarnessState.current?.state
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    var item by remember(chat, message) {
        mutableStateOf(
            if (MediaHarnessStates.isDesignViewOnce(harness)) {
                ViewerItem.designViewOnce(
                    showsScreenshotWarning = harness == MediaHarnessStates.VIEW_ONCE,
                    simulatesCapture = harness == MediaHarnessStates.VIEW_ONCE_CAPTURED,
                )
            } else {
                null
            },
        )
    }
    var closed by remember(chat, message) { mutableStateOf(false) }
    LaunchedEffect(chat, message) {
        if (item != null) return@LaunchedEffect
        val found = messenger.messages(chat).value.firstOrNull { it.id == message }
        val content = found?.content as? MessageContent.ViewOnce
        val media = if (found != null && content != null && !found.isOutgoing && content.state == ViewOnceState.Unopened) {
            messenger.viewOnceMedia(message, chat)
        } else {
            null
        }
        if (found == null || content == null || media == null) {
            currentOnDismiss()
            return@LaunchedEffect
        }
        val sender = messenger.peer(chat).value?.username ?: chat.value
        item = ViewerItem(ViewerItem.Kind.ViewOnce, message, sender, found.sentAt, media, content.isVideo)
    }
    val shown = item
    if (shown == null) {
        Box(modifier.fillMaxSize().background(U2Theme.colors.mediaViewerBg))
        return
    }
    ViewOnceViewer(
        item = shown,
        appDark = appDark,
        onScreenshot = { shown.messageId?.let { id -> MediaWork.scope.launch { messenger.reportScreenshot(id, chat) } } },
        onClose = {
            if (!closed) {
                closed = true
                // Closing deletes it on both sides; the bubble becomes “Açıldı”.
                shown.messageId?.let { id -> MediaWork.scope.launch { messenger.markViewOnceOpened(id, chat) } }
                currentOnDismiss()
            }
        },
        modifier = modifier,
    )
}

/**
 * 5.2 “Haritada aç”: the map app chooser for the place in [message] (a place, or a live share's
 * latest position), as the content of a floating `U2Sheet`. [onDismiss] asks the sheet to go down;
 * the chosen app opens once the sheet is down (when this content leaves the screen), never over it.
 */
@Composable
fun LocationOpenSheet(chat: ChatId, message: MessageId, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    MessageLocationChooser(chat, message, onDismiss, modifier)
}

/**
 * The one-time “<kişi> kaydettiğini görecek” explanation before the first save from a viewer, as the
 * content of a floating `U2Sheet` (the viewers ask it themselves through `MediaSaver`). “Kaydet”
 * remembers the acknowledgement and calls [onSave]; “Vazgeç” calls [onDismiss].
 */
@Composable
fun SaveNoticeSheet(chat: ChatId, onSave: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val messenger = LocalMessenger.current
    val preferences = LocalAppModel.current.preferences
    val peer by remember(chat) { messenger.peer(chat) }.collectAsState()
    SaveNoticeContent(
        peer = peer?.username.orEmpty(),
        kind = SaveNoticeKind.Photo,
        onSave = {
            preferences.hasAcknowledgedSaveNotice = true
            onSave()
        },
        onCancel = onDismiss,
        modifier = modifier,
    )
}

/**
 * The composer's voice recorder (hold, lock, review, send) with a voice player of its own. A
 * conversation that plays voice messages too passes its one player instead:
 * `rememberVoiceRecorderController(rememberVoicePlayback())`.
 */
@Composable
fun rememberVoiceRecorderController(): VoiceRecorderController = rememberVoiceRecorderController(rememberVoicePlayback())
