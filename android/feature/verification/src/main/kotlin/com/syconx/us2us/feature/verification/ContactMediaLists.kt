package com.syconx.us2us.feature.verification

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.ChatMediaKind
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.domain.MediaFileKind
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.bubbles.BubbleMediaView
import com.syconx.us2us.ui.bubbles.mediaStateDescription
import com.syconx.us2us.ui.components.GroupedSeparator
import com.syconx.us2us.ui.components.IconTile
import com.syconx.us2us.ui.components.IconTileContent
import com.syconx.us2us.ui.components.SpinningGlyph
import com.syconx.us2us.ui.components.groupedRowPress
import com.syconx.us2us.ui.components.u2CardPress
import com.syconx.us2us.ui.env.LocalU2Clock
import com.syconx.us2us.ui.env.LocalU2SafeArea
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.l10n.U2Format
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.l10n.rememberU2Strings
import com.syconx.us2us.ui.media.MediaGlassCircle
import com.syconx.us2us.ui.media.MediaGlyph
import com.syconx.us2us.ui.media.MediaGlyphIcon
import com.syconx.us2us.ui.media.MediaProgressRing
import com.syconx.us2us.ui.media.MediaStateGlyph
import com.syconx.us2us.ui.media.ReadyGlyph
import com.syconx.us2us.ui.media.RingShape
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.onMediaGlass
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text

// MARK: - Fotoğraflar

/**
 * The chat's photos and videos: three square tiles a row, 3 dp apart, r8, 14 dp gutters (the
 * chat's), newest first; a video carries its length on the 5.2 glass label (▶ 0:18); one still on
 * the relay or on its way shows the bubble's state circle. The next page loads as the end comes
 * near. A tap opens the viewer or the player, swiping through them.
 */
@Composable
internal fun ContactPhotosGrid(model: ContactMediaModel, modifier: Modifier = Modifier) {
    // The open conversation's copies show their current state: follow them.
    remember(model) { model.messenger.messages(model.chatId) }.collectAsStateValue()
    val items = model.items(ChatMediaKind.Visual)
    val page = model.visual
    val safeArea = LocalU2SafeArea.current
    LaunchedEffect(model) { model.loadFirstPageIfNeeded(ChatMediaKind.Visual) }
    if (items.isEmpty()) {
        if (page.hasLoaded && !page.isLoading) {
            ContactEmptyState(
                glyph = PhosphorGlyph.Image,
                title = stringResource(R.string.contact_photos_empty_title),
                message = stringResource(R.string.contact_photos_empty_body, model.peerUsername),
                modifier = modifier.testTag(VerificationTags.PHOTOS_EMPTY),
            )
        } else {
            Box(modifier.fillMaxSize())
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.fillMaxSize().testTag(VerificationTags.PHOTOS),
        contentPadding = PaddingValues(start = U2Spacing.chatGutter, top = 16.dp, end = U2Spacing.chatGutter, bottom = safeArea.bottom(24.dp)),
        horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
        verticalArrangement = Arrangement.spacedBy(GRID_SPACING),
    ) {
        items(items, key = { it.id.value }) { message ->
            ContactMediaTile(message, peer = model.peerUsername, onOpen = { model.openVisual(message) })
            LaunchedEffect(message.id) { model.itemAppeared(message.id, ChatMediaKind.Visual) }
        }
        if (page.isLoading && page.hasLoaded) {
            item(span = { GridItemSpan(maxLineSpan) }) { ContactLoadingFooter() }
        }
    }
}

private val GRID_SPACING = 3.dp

/** One square of the grid. */
@Composable
internal fun ContactMediaTile(message: Message, peer: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val locale = currentAppLocale()
    val shape = RoundedCornerShape(U2Radius.iconTile)
    val source = when (val content = message.content) {
        is MessageContent.Photo -> content.source
        is MessageContent.Video -> content.source
        else -> null
    }
    val duration = (message.content as? MessageContent.Video)?.duration?.takeIf { it.isPositive() }
    val date = U2Format.dayMonth(message.sentAt, locale)
    val sender = if (message.isOutgoing) stringResource(R.string.contact_documents_sender_you) else peer
    val label = if (duration != null) {
        stringResource(R.string.contact_a11y_video, U2Format.voiceDuration(duration), sender, date)
    } else {
        stringResource(R.string.contact_a11y_photo, sender, date)
    }
    val state = mediaStateDescription(message.mediaAvailability)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .u2CardPress(onOpen)
            .clip(shape)
            .background(colors.surface)
            .clearAndSetSemantics {
                contentDescription = label
                if (state.isNotEmpty()) stateDescription = state
                role = Role.Button
                testTag = VerificationTags.tile(message.id.value)
            },
    ) {
        if (source != null) BubbleMediaView(source, DpSize(maxWidth, maxHeight))
        androidx.compose.animation.AnimatedVisibility(
            visible = !message.mediaAvailability.isReady,
            enter = U2Transitions.fade().enter,
            exit = U2Transitions.fade().exit,
            modifier = Modifier.align(Alignment.Center),
        ) {
            MediaGlassCircle(diameter = 36.dp) {
                MediaStateGlyph(message.mediaAvailability, diameter = 36.dp, readyGlyph = ReadyGlyph.None)
            }
        }
        if (duration != null) {
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(colors.onMediaGlass(0.55f), RoundedCornerShape(6.dp))
                    .padding(start = 5.dp, top = 2.dp, end = 6.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhosphorIcon(PhosphorGlyph.Play, size = 9.dp, weight = PhosphorWeight.Fill, tint = colors.onMedia)
                U2Text(U2Format.voiceDuration(duration), U2TextStyles.monoTag, color = colors.onMedia, maxLines = 1)
            }
        }
    }
}

// MARK: - Belgeler

/**
 * The chat's files: one C13 card, rows of the 5.2 file tile (42 × 50, r10, the type glyph in
 * `accent300`; the ring while it is decrypted or downloaded), the name (one line, middle
 * truncation) over “2,1 MB · PDF”, and at the end the date over who sent it. Newest first, a page
 * at a time. A tap previews it, a video file plays.
 */
@Composable
internal fun ContactDocumentsList(model: ContactMediaModel, modifier: Modifier = Modifier) {
    remember(model) { model.messenger.messages(model.chatId) }.collectAsStateValue()
    val items = model.items(ChatMediaKind.Documents)
    val page = model.documents
    val safeArea = LocalU2SafeArea.current
    LaunchedEffect(model) { model.loadFirstPageIfNeeded(ChatMediaKind.Documents) }
    if (items.isEmpty()) {
        if (page.hasLoaded && !page.isLoading) {
            ContactEmptyState(
                glyph = PhosphorGlyph.FilePdf,
                title = stringResource(R.string.contact_documents_empty_title),
                message = stringResource(R.string.contact_documents_empty_body, model.peerUsername),
                modifier = modifier.testTag(VerificationTags.DOCUMENTS_EMPTY),
            )
        } else {
            Box(modifier.fillMaxSize())
        }
        return
    }
    val radius = U2Radius.group
    LazyColumn(
        modifier.fillMaxSize().testTag(VerificationTags.DOCUMENTS),
        contentPadding = PaddingValues(start = U2Spacing.screenGutterS, top = 16.dp, end = U2Spacing.screenGutterS, bottom = safeArea.bottom(24.dp)),
    ) {
        itemsIndexed(items, key = { _, message -> message.id.value }) { index, message ->
            val first = index == 0
            val last = index == items.lastIndex
            val shape = RoundedCornerShape(
                topStart = if (first) radius else 0.dp,
                topEnd = if (first) radius else 0.dp,
                bottomStart = if (last) radius else 0.dp,
                bottomEnd = if (last) radius else 0.dp,
            )
            Column(Modifier.clip(shape).background(U2Theme.colors.surface)) {
                if (!first) GroupedSeparator(inset = 70.dp)
                ContactDocumentRow(
                    message = message,
                    sender = model.sender(message),
                    isOpening = model.activity.isOpening(message.id),
                    onOpen = { model.openDocument(message) },
                )
            }
            LaunchedEffect(message.id) { model.itemAppeared(message.id, ChatMediaKind.Documents) }
        }
        if (page.isLoading && page.hasLoaded) {
            item { ContactLoadingFooter() }
        }
    }
}

/** One file of Belgeler. */
@Composable
internal fun ContactDocumentRow(message: Message, sender: String, isOpening: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val strings = rememberU2Strings()
    val clock = LocalU2Clock.current
    val file = message.content as? MessageContent.File
    val name = file?.name.orEmpty()
    val pages = file?.pageCount?.let { pluralStringResource(R.plurals.common_file_pages, it, it) }
    val availabilityNote = when (message.mediaAvailability) {
        MediaAvailability.Unavailable -> stringResource(R.string.conversation_file_unavailable)
        MediaAvailability.Held -> stringResource(R.string.conversation_file_held)
        else -> null
    }
    val meta = buildList {
        if (file != null) add(U2Format.byteCount(file.byteCount, strings.locale))
        file?.typeLabel?.takeIf { it.isNotEmpty() }?.let(::add)
        pages?.let(::add)
        availabilityNote?.let(::add)
    }.joinToString(" · ")
    val who = if (message.isOutgoing) stringResource(R.string.contact_documents_sender_you) else sender
    val source = remember { MutableInteractionSource() }
    val state = mediaStateDescription(message.mediaAvailability)
    Row(
        modifier
            .fillMaxWidth()
            .groupedRowPress(source)
            .clickable(source, indication = null, role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { if (state.isNotEmpty()) stateDescription = state }
            .testTag(VerificationTags.document(message.id.value))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileTile(name, message.mediaAvailability, isOpening)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            U2Text(name, U2TextStyles.bubbleTitleS, color = colors.text, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            U2Text(meta, U2TextStyles.monoFileMeta, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            U2Text(U2Format.chatListTime(message.sentAt, clock.now, strings), U2TextStyles.monoMeta, color = colors.textSecondary, maxLines = 1)
            U2Text(who, U2TextStyles.monoMeta, color = colors.textTertiary, maxLines = 1)
        }
    }
}

/** The 5.2 file tile: 42 × 50, r10, `fileIconFill`, the type glyph; the ring while it is opened or downloaded. */
@Composable
private fun FileTile(name: String, availability: MediaAvailability, isOpening: Boolean) {
    val colors = U2Theme.colors
    val ink = if (availability == MediaAvailability.Unavailable) colors.textTertiary else colors.accent300
    Box(
        Modifier
            .size(42.dp, 50.dp)
            .background(colors.fileIconFill, RoundedCornerShape(U2Radius.iconTileL))
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        when {
            availability == MediaAvailability.Remote -> MediaGlyphIcon(MediaGlyph.Download, 23.dp, tint = ink)
            MediaFileKind.of(name) == MediaFileKind.Pdf -> PhosphorIcon(PhosphorGlyph.FilePdf, size = 25.dp, tint = ink)
            MediaFileKind.of(name) == MediaFileKind.Movie -> PhosphorIcon(PhosphorGlyph.Play, size = 19.dp, weight = PhosphorWeight.Fill, tint = ink)
            else -> MediaGlyphIcon(MediaGlyph.File, 25.dp, tint = ink)
        }
        val busy = availability.isDownloading || isOpening
        androidx.compose.animation.AnimatedVisibility(visible = busy, enter = U2Transitions.fade().enter, exit = U2Transitions.fade().exit) {
            MediaProgressRing(
                fraction = if (isOpening) null else (availability as? MediaAvailability.Downloading)?.fraction,
                modifier = Modifier.requiredSize(48.dp, 56.dp),
                shape = RingShape.TopStartRoundedRect(U2Radius.iconTileL + 3.dp),
                tint = colors.accent300,
                track = colors.text.copy(alpha = 0.18f),
            )
        }
    }
}

// MARK: - Empty and loading

/**
 * A tab with nothing in it yet: a glyph in the C13 tile, a title and one friendly line, in the
 * middle of what is left of the screen.
 */
@Composable
internal fun ContactEmptyState(glyph: PhosphorGlyph, title: String, message: String, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        IconTile(IconTileContent.Glyph(glyph), size = 52.dp, cornerRadius = U2Radius.iconTileXL, glyphSize = 26.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            U2Text(title, U2TextStyles.rowTitleEmphasis, color = colors.text, textAlign = TextAlign.Center)
            U2Text(message, U2TextStyles.caption, color = colors.textSecondary, textAlign = TextAlign.Center)
        }
    }
}

/** The next page on its way (under the grid or the list). */
@Composable
internal fun ContactLoadingFooter() {
    val label = stringResource(R.string.contact_a11y_loadingmore)
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        SpinningGlyph(size = 18.dp, tint = U2Theme.colors.textSecondary)
    }
}

/** Collects a flow for its recompositions only (the value is read through the model). */
@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateValue(): T {
    val value by collectAsState()
    return value
}
