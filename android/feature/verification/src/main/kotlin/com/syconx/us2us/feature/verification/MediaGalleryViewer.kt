package com.syconx.us2us.feature.verification

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.DpSize
import com.syconx.us2us.domain.ChatMediaKind
import com.syconx.us2us.domain.MediaFileKind
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.combineStates
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.ui.bubbles.BubbleMediaView
import com.syconx.us2us.ui.env.LocalFeatureScreens
import com.syconx.us2us.ui.env.LocalMessenger
import com.syconx.us2us.ui.theme.U2Theme
import kotlinx.coroutines.flow.StateFlow

/**
 * The contact screen's photos and videos full screen: the photo viewer and the player as pages,
 * swiped sideways in the grid's order (newest first: a swipe to the left goes back in time). Each
 * page keeps its own chrome and gestures (a tap shows or hides it, pinch and double-tap zoom a
 * photo, a swipe up or down closes; “Kaydet” saves the page on screen and the sender is told).
 * Only the page on screen plays: the player is made when its page arrives and lets go of its clip
 * when it leaves; the next page of the grid loads as the end comes near. A page whose message goes
 * (deleted, expired) closes the gallery.
 *
 * The viewers are media's (through `LocalFeatureScreens`); they find an older item of the grid,
 * which the open conversation does not hold, through [ContactMediaMessenger].
 */
@Composable
internal fun MediaGalleryViewer(model: ContactMediaModel, gallery: ContactMediaModel.GalleryItem, onClose: () -> Unit) {
    val base = LocalMessenger.current
    val messenger = remember(base, model) { ContactMediaMessenger(base, model.chatId, model.loaded) }
    // The open conversation's changes (a page deleted meanwhile) reach the pages.
    val live by remember(model) { model.messenger.messages(model.chatId) }.collectAsState()
    val pages = remember(live, model.visual, model.documents, gallery) { model.galleryPages(gallery) }
    val start = pages.indexOfFirst { it.id == gallery.startId }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { pages.size }
    var currentId by remember(gallery) { mutableStateOf(gallery.startId) }
    val currentClose by rememberUpdatedState(onClose)
    val currentPages by rememberUpdatedState(pages)
    LaunchedEffect(pager, gallery) {
        snapshotFlow { pager.settledPage }.collect { index ->
            val message = currentPages.getOrNull(index) ?: return@collect
            currentId = message.id
            if (gallery.pages == ContactMediaModel.GalleryItem.Pages.Visual) model.itemAppeared(message.id, ChatMediaKind.Visual)
        }
    }
    // The page on screen went: so does the gallery.
    val currentIsGone = pages.none { it.id == currentId }
    LaunchedEffect(currentIsGone) { if (currentIsGone) currentClose() }
    CompositionLocalProvider(LocalMessenger provides messenger) {
        HorizontalPager(
            state = pager,
            key = { index -> pages.getOrNull(index)?.id?.value ?: index },
            modifier = Modifier
                .fillMaxSize()
                .background(U2Theme.colors.mediaViewerBg)
                .testTag(VerificationTags.GALLERY),
        ) { index ->
            val message = pages.getOrNull(index) ?: return@HorizontalPager
            GalleryPage(model.chatId, message, isCurrent = index == pager.currentPage, onClose = onClose)
        }
    }
}

/** One page: the photo viewer for a photo (or a picture sent as a file), the player for the video on screen, its poster otherwise. */
@Composable
private fun GalleryPage(chat: ChatId, message: Message, isCurrent: Boolean, onClose: () -> Unit) {
    val screens = LocalFeatureScreens.current
    val isPicture = when (val content = message.content) {
        is MessageContent.Photo -> true
        is MessageContent.File -> MediaFileKind.opensInPhotoViewer(content.name)
        else -> false
    }
    when {
        isPicture -> screens.MediaViewer(chat, message.id, onClose, Modifier.fillMaxSize())
        isCurrent -> screens.VideoPlayer(chat, message.id, onClose, Modifier.fillMaxSize())
        else -> VideoPosterPage(message)
    }
}

/**
 * A video's page while it is not the one on screen: its poster, fitted, on the viewer's backdrop
 * (what the player shows first, so the swipe ends without a jump when the player takes over).
 */
@Composable
private fun VideoPosterPage(message: Message) {
    val poster = (message.content as? MessageContent.Video)?.source
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(U2Theme.colors.mediaViewerBg)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        if (poster != null) {
            val aspect = message.mediaSize?.aspectRatio ?: (16f / 9f)
            val width = if (maxWidth / maxHeight > aspect) maxHeight * aspect else maxWidth
            BubbleMediaView(poster, DpSize(width, width / aspect))
        } else {
            Box(Modifier)
        }
    }
}

/**
 * The messenger the viewers over the contact screen see: the same service, with this chat's
 * messages completed by the older items the grid and the list loaded (the open conversation holds
 * only the newest), so a viewer opened on any tile finds its message, its file and its sender
 * (iOS gives the viewers the contact screen as their message context).
 */
internal class ContactMediaMessenger(
    private val base: MessengerService,
    private val chatId: ChatId,
    loaded: StateFlow<List<Message>>,
) : MessengerService by base {
    private val merged: StateFlow<List<Message>> = combineStates(base.messages(chatId), loaded) { live, extra ->
        val known = live.mapTo(HashSet()) { it.id }
        val older = extra.filter { it.id !in known }
        if (older.isEmpty()) live else (older + live).sortedBy { it.sentAt }
    }

    override fun messages(chat: ChatId): StateFlow<List<Message>> = if (chat == chatId) merged else base.messages(chat)
}
