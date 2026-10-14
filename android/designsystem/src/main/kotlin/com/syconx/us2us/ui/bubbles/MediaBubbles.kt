package com.syconx.us2us.ui.bubbles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.domain.MediaFileKind
import com.syconx.us2us.domain.MediaSource
import com.syconx.us2us.domain.Message
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.l10n.U2Format
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.media.LocalConversationMediaActivity
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
import com.syconx.us2us.ui.theme.rememberU2BackdropSource
import com.syconx.us2us.ui.theme.u2BackdropSource
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlin.time.Duration

/**
 * A photo: padding 5 5 8 5, the photo r15 (258 × 176 in the design), the caption (padding 2 8 0),
 * the time with 8 dp at its end. While only the thumbnail is here (still on the relay,
 * downloading, gone) the 5.2 glass circle sits in its centre with the state (proposed). A photo
 * sent from here shows at once while it is prepared: its preview with the ring; if preparing it
 * fails, the warning.
 */
@Composable
fun PhotoBubble(message: Message, source: MediaSource, caption: String?, modifier: Modifier = Modifier, isTail: Boolean = false) {
    val availability = message.mediaAvailability
    val photo = stringResource(R.string.common_media_photo)
    val state = mediaStateDescription(availability)
    MessageBubble(message.direction, modifier, isTail = isTail, padding = BubblePadding.Media) {
        val backdrop = rememberU2BackdropSource()
        Box(
            Modifier
                .clip(RoundedCornerShape(U2Radius.bubbleMedia))
                .clearAndSetSemantics {
                    contentDescription = photo
                    if (state.isNotEmpty()) stateDescription = state
                },
            contentAlignment = Alignment.Center,
        ) {
            BubbleMediaView(source, PhotoSize, Modifier.u2BackdropSource(backdrop))
            val fade = U2Transitions.fade()
            AnimatedVisibility(!availability.isReady, enter = fade.enter, exit = fade.exit) {
                MediaGlassCircle(source = backdrop) { MediaStateGlyph(availability, readyGlyph = ReadyGlyph.None) }
            }
        }
        if (caption != null) {
            BubbleText(caption, bubbleInk(message), Modifier.padding(start = 8.dp, top = 2.dp, end = 8.dp))
        }
        BubbleMeta(message.sentAt, Modifier.padding(end = 8.dp).bubbleTrailing(), status = message.status, disappearing = message.disappearing)
    }
}

/**
 * A video poster (236 × 124 r15) with the centred glass play button and the “0:18 · 12,4 MB”
 * label. A video sent from here shows at once while it is prepared (proposed): the ring and
 * “0:18 · Hazırlanıyor”, the poster and the length as soon as they are read; if preparing it
 * fails, the warning and the length.
 */
@Composable
fun VideoBubble(
    message: Message,
    poster: MediaSource,
    duration: Duration,
    byteCount: Long,
    modifier: Modifier = Modifier,
    isTail: Boolean = false,
) {
    val colors = U2Theme.colors
    val locale = currentAppLocale()
    val availability = message.mediaAvailability
    val length = U2Format.voiceDuration(duration)
    val hasLength = duration.isPositive()
    val preparing = stringResource(R.string.conversation_video_preparing)
    // “0:18 · 12,4 MB”; preparing “0:18 · Hazırlanıyor” (the size is not known yet); failed “0:18”.
    val label: String? = when (availability) {
        MediaAvailability.Preparing -> if (hasLength) "$length · $preparing" else preparing
        MediaAvailability.PreparationFailed -> if (hasLength) length else null
        else -> "$length · ${U2Format.byteCount(byteCount, locale)}"
    }
    val spoken = when {
        message.isPendingMedia && !hasLength -> stringResource(R.string.common_media_video)
        message.isPendingMedia -> stringResource(R.string.conversation_a11y_video_pending, length)
        else -> stringResource(R.string.conversation_a11y_video, length, U2Format.byteCount(byteCount, locale))
    }
    val state = mediaStateDescription(availability)
    MessageBubble(message.direction, modifier, isTail = isTail, padding = BubblePadding.Media) {
        val backdrop = rememberU2BackdropSource()
        Box(
            Modifier
                .clip(RoundedCornerShape(U2Radius.bubbleMedia))
                .clearAndSetSemantics {
                    contentDescription = spoken
                    if (state.isNotEmpty()) stateDescription = state
                },
        ) {
            BubbleMediaView(poster, PosterSize, Modifier.u2BackdropSource(backdrop))
            // Play; or (proposed) download, the ring, the warning.
            MediaGlassCircle(Modifier.align(Alignment.Center), source = backdrop) { MediaStateGlyph(availability) }
            StateCrossFade(label, Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 9.dp)) { shown ->
                if (shown != null) {
                    U2Text(
                        shown,
                        U2TextStyles.monoFileMeta,
                        Modifier
                            .background(colors.onMediaGlass(0.55f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        color = colors.onMedia,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        BubbleMeta(message.sentAt, Modifier.padding(end = 8.dp).bubbleTrailing(), status = message.status, disappearing = message.disappearing)
    }
}

/**
 * A file: padding 10 10 8 12, a 42 × 50 r10 `bg·35%` tile with `ph:file-pdf` 25 `accent300`, the
 * name in `bubbleTitleS` (one line, truncated in the middle) and the meta in Mono 10.5 `text·68%`.
 *
 * States (5.2, proposed): still on the relay → the download glyph in the tile; downloading or
 * opening → a 2 dp `accent300` ring around the tile (3 dp out, over `text·18%`) filling with the
 * progress; gone → the glyph in `text·40%` and “Artık indirilemez” after the meta. Overlays only:
 * the bubble keeps its size.
 */
@Composable
fun FileBubble(
    message: Message,
    name: String,
    byteCount: Long,
    typeLabel: String,
    pageCount: Int?,
    modifier: Modifier = Modifier,
    isTail: Boolean = false,
) {
    val colors = U2Theme.colors
    val locale = currentAppLocale()
    val availability = message.mediaAvailability
    val isOpening = LocalConversationMediaActivity.current?.isOpening(message.id) ?: false
    val pages = pageCount?.let { pluralStringResource(R.plurals.common_file_pages, it, it) }
    val unavailable = stringResource(R.string.conversation_file_unavailable)
    val held = stringResource(R.string.conversation_file_held)
    val meta = buildList {
        add(U2Format.byteCount(byteCount, locale))
        add(typeLabel)
        pages?.let(::add)
        when (availability) {
            MediaAvailability.Unavailable -> add(unavailable)
            MediaAvailability.Held -> add(held)
            else -> Unit
        }
    }.joinToString(" · ")
    // “Artık indirilemez” is already part of the meta.
    val state = mediaStateDescription(
        if (availability == MediaAvailability.Unavailable || availability == MediaAvailability.Held) MediaAvailability.Ready else availability,
    )
    MessageBubble(
        message.direction,
        modifier.semantics(mergeDescendants = true) { if (state.isNotEmpty()) stateDescription = state },
        isTail = isTail,
        padding = PaddingValues(start = 10.dp, top = 10.dp, end = 12.dp, bottom = 8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val tileGlyph = when {
                availability == MediaAvailability.Remote -> FileTileGlyph.Download
                else -> when (MediaFileKind.of(name)) {
                    MediaFileKind.Pdf -> FileTileGlyph.Pdf
                    MediaFileKind.Movie -> FileTileGlyph.Movie
                    else -> FileTileGlyph.File
                }
            }
            val ink = if (availability == MediaAvailability.Unavailable) colors.textTertiary else colors.accent300
            Box(
                Modifier
                    .size(42.dp, 50.dp)
                    .background(colors.fileIconFill, RoundedCornerShape(U2Radius.iconTileL)),
                contentAlignment = Alignment.Center,
            ) {
                StateCrossFade(tileGlyph) { glyph ->
                    when (glyph) {
                        FileTileGlyph.Pdf -> PhosphorIcon(PhosphorGlyph.FilePdf, 25.dp, tint = ink)
                        FileTileGlyph.Movie -> PhosphorIcon(PhosphorGlyph.Play, 19.dp, weight = PhosphorWeight.Fill, tint = ink)
                        FileTileGlyph.File -> MediaGlyphIcon(MediaGlyph.File, 25.dp, tint = ink)
                        FileTileGlyph.Download -> MediaGlyphIcon(MediaGlyph.Download, 23.dp, tint = ink)
                    }
                }
                FileTileRing(
                    visible = availability.isDownloading || isOpening,
                    fraction = if (isOpening) null else (availability as? MediaAvailability.Downloading)?.fraction,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                U2Text(
                    name,
                    U2TextStyles.bubbleTitleS,
                    color = bubbleInk(message),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                U2Text(meta, U2TextStyles.monoFileMeta, color = colors.textSecondary, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
        BubbleMeta(message.sentAt, Modifier.bubbleTrailing(), status = message.status, disappearing = message.disappearing)
    }
}

private enum class FileTileGlyph { Pdf, Movie, File, Download }

/** Downloading or opening: a 2 dp `accent300` ring 3 dp around the tile, over `text·18%`. */
@Composable
private fun BoxScope.FileTileRing(visible: Boolean, fraction: Double?) {
    val colors = U2Theme.colors
    val fade = U2Transitions.fade()
    AnimatedVisibility(visible, Modifier.matchParentSize().outset(3.dp), enter = fade.enter, exit = fade.exit) {
        MediaProgressRing(
            fraction = fraction,
            shape = RingShape.TopStartRoundedRect(U2Radius.iconTileL + 3.dp),
            tint = colors.accent300,
            track = colors.text.copy(alpha = 0.18f),
        )
    }
}

/** The design sizes of the photo and the video poster in their bubbles. */
private val PhotoSize = DpSize(258.dp, 176.dp)
private val PosterSize = DpSize(236.dp, 124.dp)

/** Grows the content by [amount] on every side without taking more room (a ring around a tile). */
private fun Modifier.outset(amount: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val extra = (amount * 2).roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.minWidth + extra,
            maxWidth = constraints.maxWidth + extra,
            minHeight = constraints.minHeight + extra,
            maxHeight = constraints.maxHeight + extra,
        ),
    )
    layout(placeable.width - extra, placeable.height - extra) { placeable.place(-extra / 2, -extra / 2) }
}

/**
 * What a screen reader hears about a photo, video or file beyond its name: nothing when it is
 * here, otherwise its state (iOS `MediaAccessibility.state`).
 */
@Composable
fun mediaStateDescription(availability: MediaAvailability): String {
    val resources = LocalResources.current
    return when (availability) {
        MediaAvailability.Ready -> ""
        MediaAvailability.Remote -> resources.getString(R.string.conversation_a11y_media_remote)
        is MediaAvailability.Downloading -> availability.fraction?.let {
            resources.getString(R.string.conversation_a11y_media_downloading_progress, Math.round(it * 100).toInt())
        } ?: resources.getString(R.string.conversation_a11y_media_downloading)
        MediaAvailability.Held -> resources.getString(R.string.conversation_file_held)
        MediaAvailability.Unavailable -> resources.getString(R.string.conversation_file_unavailable)
        MediaAvailability.Preparing -> resources.getString(R.string.conversation_video_preparing)
        MediaAvailability.PreparationFailed -> resources.getString(R.string.conversation_video_preparationfailed)
    }
}
