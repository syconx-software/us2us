package com.syconx.us2us.feature.chats

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.ChatSummary
import com.syconx.us2us.domain.ConnectionState
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.domain.MessagePreview
import com.syconx.us2us.domain.MessagePreviewKind
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.bubbles.MessageStatusIcon
import com.syconx.us2us.ui.components.Avatar
import com.syconx.us2us.ui.components.AvatarSize
import com.syconx.us2us.ui.components.CommunityAvatar
import com.syconx.us2us.ui.components.communityMemberName
import com.syconx.us2us.ui.components.communityName
import com.syconx.us2us.ui.components.CountBadge
import com.syconx.us2us.ui.components.DotPattern
import com.syconx.us2us.ui.components.DottedRule
import com.syconx.us2us.ui.components.GhostPill
import com.syconx.us2us.ui.components.LineMark
import com.syconx.us2us.ui.components.LineMarkMetrics
import com.syconx.us2us.ui.components.u2CardPress
import com.syconx.us2us.ui.env.LocalU2Clock
import com.syconx.us2us.ui.env.LocalU2SafeArea
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.l10n.U2Format
import com.syconx.us2us.ui.l10n.rememberU2Strings
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.theme.U2BackdropSource
import com.syconx.us2us.ui.theme.U2BackdropStyle
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2Backdrop
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text

/**
 * 4.1 chat row: min height 76, pad 0 18 0 16, gap 13. Avatar 52 (with the online dot for accepted
 * contacts) · line 1: username (Mono 500 15, one line) + verified seal 14 + time (`caption`; `accent300`
 * when unread) · line 2 (min 22, gap 6): status glyph, type glyph, preview (`preview` 14.5, one line;
 * `text` when unread, `danger` when failed) and the Ghost pill or the unread badge. Someone blocked:
 * “Engellendi” with `ph:prohibit` in the preview's place (proposed).
 *
 * Changes animate in place, never by rebuilding the row: the preview and the time cross-fade, the
 * counters roll, the status glyph cross-fades, all on the state motion (0.36 s).
 */
@Composable
fun ChatListRow(chat: ChatSummary, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .padding(start = U2Spacing.listRowInsetLeading, end = U2Spacing.listRowInsetTrailing),
        horizontalArrangement = Arrangement.spacedBy(U2Spacing.rowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val community = chat.community
        if (community != null) {
            // A community (Communities extension): its mark where a person's avatar would be.
            CommunityAvatar(communityName(community), size = AvatarSize.ListRow)
        } else {
            Avatar(
                username = chat.peer.username,
                palette = chat.peer.palette,
                size = AvatarSize.ListRow,
                showsOnlineDot = chat.peer.isOnline,
                picture = chat.peer.avatar,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FirstLine(chat)
            SecondLine(chat)
        }
    }
}

@Composable
private fun FirstLine(chat: ChatSummary) {
    val colors = U2Theme.colors
    val strings = rememberU2Strings()
    val now = LocalU2Clock.current.now
    val isUnread = chat.unreadCount > 0
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        // The name and the seal take what the time leaves; a long name truncates before the seal.
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            U2Text(
                chat.community?.let { communityName(it) } ?: chat.peer.username,
                U2TextStyles.monoUsername,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (chat.peer.isVerified) {
                PhosphorIcon(
                    PhosphorGlyph.SealCheck,
                    size = 14.dp,
                    weight = PhosphorWeight.Fill,
                    tint = colors.accent,
                    contentDescription = stringResource(R.string.common_a11y_verified),
                )
            }
        }
        val last = chat.lastMessage
        if (last != null) {
            val time = U2Format.chatListTime(last.sentAt, now, strings)
            val ink by animateColorAsState(
                if (isUnread) colors.accent300 else colors.textSecondary,
                U2Motion.resolve(U2Motion.state, LocalReduceMotion.current).spec(),
                label = "rowTime",
            )
            CrossFadeText(time, label = "rowTimeText") {
                U2Text(it, U2TextStyles.caption, color = ink, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun SecondLine(chat: ChatSummary) {
    val isUnread = chat.unreadCount > 0
    val timing = U2Motion.resolve(U2Motion.state, LocalReduceMotion.current)
    Row(
        Modifier.heightIn(min = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Blocked or unblocked, the line cross-fades in place (state motion).
        AnimatedContent(
            targetState = chat.peer.isBlocked,
            transitionSpec = { fadeIn(timing.spec()) togetherWith fadeOut(timing.spec()) },
            modifier = Modifier.weight(1f),
            label = "rowBlocked",
        ) { isBlocked ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                PreviewLine(chat, isBlocked)
            }
        }
        when {
            isUnread -> CountBadge(chat.unreadCount)
            chat.ghost.isEnabled -> GhostPill(chat.ghost)
        }
    }
}

/** Line 2's preview: “Engellendi” for someone blocked, else the status glyph, the type glyph and the preview text. */
@Composable
private fun RowScope.PreviewLine(chat: ChatSummary, isBlocked: Boolean) {
    val colors = U2Theme.colors
    val isUnread = chat.unreadCount > 0
    val last = chat.lastMessage
    val isFailed = last?.status == MessageStatus.Failed
    when {
        isBlocked -> {
            // Blocked (proposed): the state in the preview's place, as a deleted message's line.
            PhosphorIcon(PhosphorGlyph.Prohibit, size = 15.dp, tint = colors.textSecondary)
            U2Text(
                stringResource(R.string.common_block_blocked),
                U2TextStyles.preview,
                color = colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
        last != null -> {
            if (last.direction == MessageDirection.Outgoing && last.status != null) {
                MessageStatusIcon(last.status!!, size = 15.dp)
            }
            val glyph = typeGlyph(last.kind)
            if (glyph != null && !isFailed) PhosphorIcon(glyph, size = 15.dp, tint = colors.textSecondary)
            val preview = previewText(last, isFailed, isCommunity = chat.isCommunity)
            // A community's incoming message says who wrote it.
            val text = last.sender?.takeIf { !isFailed }?.let { sender ->
                stringResource(R.string.community_preview_sender, communityMemberName(sender.account, sender.username), preview)
            } ?: preview
            val ink by animateColorAsState(
                when {
                    isFailed -> colors.danger
                    isUnread -> colors.text
                    else -> colors.textSecondary
                },
                U2Motion.resolve(U2Motion.state, LocalReduceMotion.current).spec(),
                label = "rowPreview",
            )
            Box(Modifier.weight(1f)) {
                CrossFadeText(text, label = "rowPreviewText") {
                    U2Text(it, U2TextStyles.preview, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        else -> Spacer(Modifier.weight(1f))
    }
}

/** A text that cross-fades when it changes (state motion; the short cross-fade under reduce motion). */
@Composable
private fun CrossFadeText(text: String, label: String, content: @Composable (String) -> Unit) {
    val timing = U2Motion.resolve(U2Motion.state, LocalReduceMotion.current)
    AnimatedContent(
        targetState = text,
        transitionSpec = { fadeIn(timing.spec()) togetherWith fadeOut(timing.spec()) },
        label = label,
    ) { content(it) }
}

private fun typeGlyph(kind: MessagePreviewKind): PhosphorGlyph? = when (kind) {
    MessagePreviewKind.Disappearing -> PhosphorGlyph.Timer
    MessagePreviewKind.Sticker -> PhosphorGlyph.Sticker
    MessagePreviewKind.LiveLocation, MessagePreviewKind.LiveLocationEnded, MessagePreviewKind.Location -> PhosphorGlyph.NavigationArrow
    MessagePreviewKind.Deleted -> PhosphorGlyph.Prohibit
    is MessagePreviewKind.Text, MessagePreviewKind.Photo, MessagePreviewKind.PhotoViewOnce, MessagePreviewKind.Video,
    MessagePreviewKind.Voice, is MessagePreviewKind.File,
    -> null
}

@Composable
private fun previewText(last: MessagePreview, isFailed: Boolean, isCommunity: Boolean = false): String {
    if (isFailed) return stringResource(R.string.common_chats_preview_failed)
    val text = kindText(last, isCommunity)
    // A community photo, video or file shows its caption after its kind.
    return last.caption?.let { stringResource(R.string.community_preview_caption, text, it) } ?: text
}

@Composable
private fun kindText(last: MessagePreview, isCommunity: Boolean): String {
    if (isCommunity && last.kind == MessagePreviewKind.Deleted) {
        return stringResource(if (last.removedByOwner) R.string.community_message_removed else R.string.community_message_deleted)
    }
    return when (val kind = last.kind) {
        is MessagePreviewKind.Text -> kind.text
        MessagePreviewKind.Disappearing -> stringResource(R.string.common_chats_preview_disappearing)
        MessagePreviewKind.Sticker -> stringResource(R.string.common_chats_preview_sticker)
        MessagePreviewKind.Photo -> stringResource(R.string.common_media_photo)
        MessagePreviewKind.PhotoViewOnce -> stringResource(R.string.common_chats_preview_photoviewonce)
        MessagePreviewKind.Video -> stringResource(R.string.common_media_video)
        MessagePreviewKind.Voice -> stringResource(R.string.common_media_voice)
        is MessagePreviewKind.File -> kind.name
        MessagePreviewKind.Location -> stringResource(R.string.common_media_location)
        MessagePreviewKind.LiveLocation -> stringResource(R.string.common_location_live)
        MessagePreviewKind.LiveLocationEnded -> stringResource(R.string.common_chats_preview_livelocationended)
        MessagePreviewKind.Deleted -> stringResource(R.string.common_message_deleted)
    }
}

/**
 * The 4.1 row separator: `DottedRule()` (C19 `rule`: r .75 / 1.1, pitch 5, 2 high, `dotRule`) from
 * the row's leading inset (81) to the edge. The rule belongs to the row's place, not the row: when
 * a row moves to the top it simply has none there.
 */
@Composable
fun ChatListSeparator(modifier: Modifier = Modifier) {
    DottedRule(
        modifier
            .padding(start = U2Spacing.listSeparatorInset)
            .fillMaxWidth()
            .height(DotPattern.Rule.thickness)
            .clearAndSetSemantics {},
    )
}

/**
 * 4.1 requests card: `surface` r18, pad 13 14, gap 13: tray tile 42 (circle `accent900`, ring
 * `accent800`, `ph:tray` 21) · “Mesaj istekleri” + “{n} kişi seninle yazışmak istiyor” · count
 * badge · chevron.
 */
@Composable
fun RequestsCard(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier, communityName: String? = null) {
    val colors = U2Theme.colors
    CardRow(onClick, modifier) {
        Box(
            Modifier
                .size(42.dp)
                .background(colors.accent900, CircleShape)
                .u2InnerStroke(colors.accent800, 1.dp, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            PhosphorIcon(PhosphorGlyph.Tray, size = 21.dp, tint = colors.accent300)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            U2Text(communityName ?: stringResource(R.string.common_chats_requests_title), U2TextStyles.rowTitleEmphasis, color = colors.text)
            U2Text(
                if (communityName != null) stringResource(R.string.community_requests_count, count) else pluralStringResource(R.plurals.common_chats_requests_subtitle, count, count),
                U2TextStyles.caption,
                color = colors.textSecondary,
            )
        }
        CountBadge(count)
        PhosphorIcon(PhosphorGlyph.CaretRight, size = 15.dp, tint = colors.textTertiary)
    }
}

/**
 * 4.1 after a restore whose token rotation is unanswered (PROTOCOL §5.5 step 3.4): the old phone may
 * still have access. A card in the requests card's place and shape (`surface` r18, pad 13 14, gap
 * 13; 42 round tile in `dangerTint` with `ph:warning-circle` in `danger`; title `rowTitleEmphasis`,
 * subtitle `caption`; chevron), shown only in that state; it opens 7.1, where the notice explains it
 * and checks again. No design reference (proposed).
 */
@Composable
fun RestoreAccessCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    CardRow(onClick, modifier) {
        Box(
            Modifier
                .size(42.dp)
                .background(colors.dangerTint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            PhosphorIcon(PhosphorGlyph.WarningCircle, size = 21.dp, tint = colors.danger)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            U2Text(stringResource(R.string.chats_access_card_title), U2TextStyles.rowTitleEmphasis, color = colors.text)
            U2Text(stringResource(R.string.chats_access_card_subtitle), U2TextStyles.caption, color = colors.textSecondary)
        }
        PhosphorIcon(PhosphorGlyph.CaretRight, size = 15.dp, tint = colors.textTertiary)
    }
}

/** The shared shape of the 4.1 cards: `surface` r18, pad 13 14, gap 13, pressing like a card. */
@Composable
private fun CardRow(onClick: () -> Unit, modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(U2Radius.card)
    Row(
        modifier
            .fillMaxWidth()
            .u2CardPress(onClick)
            .background(U2Theme.colors.surface, shape)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * The connection line and caption under the chat-list title (4.1): the C20 mark (60 × 9) with
 * flowing packets while the line is open, gap 10, “Hat açık · mesajlar geliyor” (`monoMeta`,
 * `text·68%`).
 */
@Composable
fun ConnectionStatusLine(state: ConnectionState, modifier: Modifier = Modifier) {
    Row(
        // A change of the line (connecting, closed) is said as it happens.
        modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineMark(metrics = LineMarkMetrics.Connection, isFlowing = state == ConnectionState.Open)
        val text = stringResource(
            when (state) {
                ConnectionState.Open -> R.string.common_chats_line_open
                ConnectionState.Connecting -> R.string.common_chats_line_connecting
                ConnectionState.Closed -> R.string.common_chats_line_closed
                ConnectionState.Revoked -> R.string.common_chats_line_revoked
            },
        )
        // One line as drawn; with a large system font it wraps rather than cutting “yeniden deneniyor”.
        val wraps = LocalDensity.current.fontScale > 1f
        CrossFadeText(text, label = "connectionLine") {
            U2Text(it, U2TextStyles.monoMeta, color = U2Theme.colors.textSecondary, maxLines = if (wraps) 2 else 1, softWrap = wraps, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** “Sohbetler”: the 4.1 title (Doto 900 38). */
@Composable
internal fun ChatsTitle(modifier: Modifier = Modifier) {
    U2Text(
        stringResource(R.string.common_chats_title),
        U2TextStyles.displayTitle,
        color = U2Theme.colors.text,
        modifier = modifier.semantics { heading() },
    )
}

/**
 * The fixed header band of 4.1 / 4.3 (y 0 … [designHeight]): clear at rest, exactly as drawn, and,
 * once content scrolls beneath it, the C8 chat-header treatment (`bg·80%` + blur over [source],
 * dotted rule) fading in softly (proposed).
 */
@Composable
fun ChatsHeaderBackdrop(isVisible: Boolean, source: U2BackdropSource?, modifier: Modifier = Modifier, designHeight: Float = 108f) {
    val safeArea = LocalU2SafeArea.current
    val alpha by animateFloatAsState(
        if (isVisible) 1f else 0f,
        U2Motion.resolve(U2Motion.state, LocalReduceMotion.current).spec(),
        label = "headerBackdrop",
    )
    if (alpha <= 0f) return
    Box(
        modifier
            .fillMaxWidth()
            .height(safeArea.top(designHeight.dp))
            .graphicsLayer { this.alpha = alpha }
            .u2Backdrop(U2BackdropStyle.Header, source = source)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.BottomCenter,
    ) {
        DottedRule(Modifier.fillMaxWidth().padding(bottom = 2.dp), color = U2Theme.colors.dotRuleHeader)
    }
}
