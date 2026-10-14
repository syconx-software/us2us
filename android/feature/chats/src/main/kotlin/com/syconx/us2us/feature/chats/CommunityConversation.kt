package com.syconx.us2us.feature.chats

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.CommunityApplication
import com.syconx.us2us.domain.CommunityCard
import com.syconx.us2us.domain.CommunityFormat
import com.syconx.us2us.domain.CommunityMemberCard
import com.syconx.us2us.domain.CommunityText
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.bubbles.BubbleMeta
import com.syconx.us2us.ui.bubbles.BubbleText
import com.syconx.us2us.ui.bubbles.MessageBubble
import com.syconx.us2us.ui.components.CenteredTitleHeader
import com.syconx.us2us.ui.components.ConfirmationTags
import com.syconx.us2us.ui.components.DestructiveConfirmation
import com.syconx.us2us.ui.components.FlagTag
import com.syconx.us2us.ui.components.GroupedCard
import com.syconx.us2us.ui.components.GroupedRow
import com.syconx.us2us.ui.components.GroupedSeparator
import com.syconx.us2us.ui.components.NoteCard
import com.syconx.us2us.ui.components.SectionLabel
import com.syconx.us2us.ui.components.SheetCloseHeader
import com.syconx.us2us.ui.components.SystemPill
import com.syconx.us2us.ui.components.U2Card
import com.syconx.us2us.ui.components.U2DangerButton
import com.syconx.us2us.ui.components.U2DecisionButton
import com.syconx.us2us.ui.components.U2DecisionKind
import com.syconx.us2us.ui.components.U2FlexRow
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.U2PrimaryButtonSize
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.components.u2Flex
import com.syconx.us2us.ui.composer.TextComposer
import com.syconx.us2us.ui.env.LocalExternalActivityGuard
import com.syconx.us2us.ui.env.currentSafeArea
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.rememberU2BackdropSource
import com.syconx.us2us.ui.theme.u2BackdropSource
import com.syconx.us2us.ui.tokens.U2Opacity
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import java.time.Instant

/** “Sen”, a verified contact's username, else “Üye · 1a2b3c4d”: never a whole account id. */
@Composable
internal fun memberName(account: String, username: String?, isSelf: Boolean = false): String = when {
    isSelf -> stringResource(R.string.community_you)
    username != null -> "@$username"
    else -> stringResource(R.string.community_member_short, CommunityFormat.shortId(account))
}

/**
 * A community's conversation (proposed; iOS `CommunityConversationView`): 4.3's centred-title
 * header (back, the name, “…” for members and management) over C15 bubbles, incoming ones under
 * their sender's name, and the text-only composer. The list keeps the whole screen; everything
 * else is in [CommunityManageSheet].
 */
@Composable
internal fun CommunityConversation(
    group: CommunityCard,
    title: String,
    messages: List<CommunityText>,
    draft: String,
    onDraft: (String) -> Unit,
    canSend: Boolean,
    onSend: () -> Unit,
    failed: Boolean,
    warning: Boolean,
    busy: Boolean,
    onBack: () -> Unit,
    onManage: () -> Unit,
) {
    val density = LocalDensity.current
    val safeArea = currentSafeArea
    val backdrop = rememberU2BackdropSource()
    val listState = rememberLazyListState()
    var composerHeight by remember { mutableStateOf(U2Size.composer + 50.dp) }
    // The newest message stays in view as messages arrive or are sent.
    val newest = messages.lastOrNull()?.id
    LaunchedEffect(newest) { if (newest != null) listState.animateScrollToItem(0) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().u2BackdropSource(backdrop).testTag(CommunityTags.CONVERSATION),
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(
                start = U2Spacing.chatGutter,
                top = safeArea.top(116.dp),
                end = U2Spacing.chatGutter,
                bottom = composerHeight + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(U2Spacing.bubbleGap, Alignment.Bottom),
        ) {
            val newestFirst = messages.asReversed()
            itemsIndexed(newestFirst, key = { _, message -> "message:${message.id}" }) { index, message ->
                val previous = newestFirst.getOrNull(index + 1)
                CommunityMessageRow(message, showsSender = !message.outgoing && previous?.sender != message.sender)
            }
            if (failed) item(key = "error") { ErrorText(Modifier.fillMaxWidth().padding(bottom = 8.dp)) }
            if (warning) item(key = "warning") {
                NoteCard(
                    stringResource(R.string.community_warning_title),
                    stringResource(R.string.community_warning_message),
                    Modifier.padding(bottom = 8.dp).testTag(CommunityTags.WARNING),
                )
            }
            item(key = "intro") {
                Box(Modifier.fillMaxWidth().padding(bottom = 10.dp), contentAlignment = Alignment.Center) {
                    SystemPill(stringResource(R.string.community_history_note), glyph = PhosphorGlyph.ShieldCheck)
                }
            }
        }
        val bottomModifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .onPlaced { composerHeight = with(density) { it.size.height.toDp() } }
        if (group.active) {
            val keyboard = with(density) { WindowInsets.ime.getBottom(density).toDp() }
            TextComposer(
                text = draft,
                onTextChange = onDraft,
                onSend = onSend,
                canSend = canSend,
                // The design's 36 above the bottom at rest, 8 above the keyboard (as in 1:1 chats).
                bottomPadding = maxOf(8.dp, safeArea.bottom(36.dp) - keyboard),
                modifier = bottomModifier.testTag(CommunityTags.COMPOSER),
            )
        } else {
            U2Text(
                stringResource(R.string.community_inactive),
                U2TextStyles.callout,
                bottomModifier.padding(horizontal = U2Spacing.screenGutter).u2Bottom(U2Spacing.ctaBottom),
                color = U2Theme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        ChatsHeaderBackdrop(isVisible = true, source = backdrop)
        CenteredTitleHeader(
            title = title,
            leading = {
                U2GlassIconButton(
                    glyph = PhosphorGlyph.CaretLeft,
                    contentDescription = stringResource(R.string.community_back),
                    onClick = onBack,
                    modifier = Modifier.testTag(CommunityTags.BACK),
                    enabled = !busy,
                )
            },
            trailing = {
                U2GlassIconButton(
                    glyph = PhosphorGlyph.DotsThree,
                    contentDescription = stringResource(R.string.community_manage),
                    onClick = onManage,
                    modifier = Modifier.testTag(CommunityTags.MANAGE),
                )
            },
        )
    }
}

@Composable
private fun CommunityMessageRow(message: CommunityText, showsSender: Boolean) {
    val colors = U2Theme.colors
    val outgoing = message.outgoing
    val status = when (message.status) {
        "received" -> null
        "sent" -> MessageStatus.Sent
        "partial", "failed", "stale" -> MessageStatus.Failed
        else -> MessageStatus.Sending
    }
    val note = when (message.status) {
        "partial" -> stringResource(R.string.community_partial)
        "failed", "stale" -> stringResource(R.string.community_failed)
        else -> null
    }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (showsSender) {
            U2Text(
                memberName(message.sender, message.senderName),
                if (message.senderName == null) U2TextStyles.captionCompact else U2TextStyles.monoMeta,
                Modifier.padding(start = 6.dp, top = 6.dp, end = 6.dp),
                color = colors.textSecondary,
            )
        }
        MessageBubble(if (outgoing) MessageDirection.Outgoing else MessageDirection.Incoming) {
            SelectionContainer { BubbleText(AnnotatedString(message.text), if (outgoing) colors.onBubbleOutgoing else colors.text) }
            BubbleMeta(Instant.ofEpochMilli(message.time), Modifier.bubbleTrailing(), status = status)
        }
        if (note != null) {
            FlagTag(
                PhosphorGlyph.WarningCircle,
                note,
                Modifier.padding(horizontal = 6.dp).widthIn(max = 290.dp),
                color = if (status == MessageStatus.Failed && message.status != "partial") colors.danger else colors.textSecondary,
            )
        }
    }
}

/**
 * A community's members and management (proposed, full-height sheet; iOS `CommunityManageSheet`):
 * the owner reviews join requests with the identity key each asks to add, removes members and
 * makes one-use invitations; a member leaves; the owner deletes the community. Irreversible steps
 * are confirmed in the same sheet.
 */
@Composable
internal fun CommunityManageSheet(
    group: CommunityCard,
    title: String,
    applications: List<CommunityApplication>,
    invite: String?,
    busy: Boolean,
    failed: Boolean,
    confirmation: CommunityConfirmation?,
    onClose: () -> Unit,
    onApprove: (CommunityApplication) -> Unit,
    onReject: (CommunityApplication) -> Unit,
    onInvite: () -> Unit,
    onRevoke: () -> Unit,
    onConfirm: (CommunityConfirmation) -> Unit,
    onConfirmed: (CommunityConfirmation) -> Unit,
    onCancelConfirmation: () -> Unit,
) {
    val forward = confirmation != null
    val enter = U2Transitions.soft(DpOffset(if (forward) 16.dp else (-16).dp, 0.dp), blur = 0.dp, timing = U2Motion.screen)
    val exit = U2Transitions.soft(DpOffset(if (forward) (-16).dp else 16.dp, 0.dp), blur = 0.dp, timing = U2Motion.screen)
    Box(Modifier.fillMaxSize().testTag(CommunityTags.MANAGE_SHEET), contentAlignment = Alignment.TopCenter) {
        AnimatedContent(
            targetState = confirmation,
            transitionSpec = { (enter.enter togetherWith exit.exit).using(SizeTransform(clip = false)) },
            contentAlignment = Alignment.TopCenter,
            label = "communityManage",
        ) { shown ->
            if (shown != null) {
                val (confirmTitle, message, action) = shown.texts()
                DestructiveConfirmation(
                    title = stringResource(confirmTitle),
                    message = stringResource(message),
                    confirmTitle = stringResource(action),
                    onConfirm = { onConfirmed(shown) },
                    onCancel = onCancelConfirmation,
                    confirmGlyph = shown.glyph,
                    isWorking = busy,
                    tags = ConfirmationTags("communities.confirm", "communities.cancel"),
                    modifier = Modifier.padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 28.dp),
                )
            } else {
                ManagePage(group, title, applications, invite, busy, failed, onClose, onApprove, onReject, onInvite, onRevoke, onConfirm)
            }
        }
    }
}

@Composable
private fun ManagePage(
    group: CommunityCard,
    title: String,
    applications: List<CommunityApplication>,
    invite: String?,
    busy: Boolean,
    failed: Boolean,
    onClose: () -> Unit,
    onApprove: (CommunityApplication) -> Unit,
    onReject: (CommunityApplication) -> Unit,
    onInvite: () -> Unit,
    onRevoke: () -> Unit,
    onConfirm: (CommunityConfirmation) -> Unit,
) {
    val owner = group.isOwner && group.active
    Column(Modifier.fillMaxSize()) {
        SheetCloseHeader(title, onClose, Modifier.padding(start = 18.dp, top = 12.dp, end = 18.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, top = 14.dp, end = 18.dp)
                .u2Bottom(28.dp),
            verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionGap),
        ) {
            if (failed) ErrorText()
            if (owner) Requests(applications, full = group.count >= 100, busy, onApprove, onReject)
            Members(group, busy, onRemove = { onConfirm(CommunityConfirmation.RemoveMember(group.id, it.account)) })
            if (owner) Invitations(invite, busy, onInvite, onRevoke)
            when {
                owner -> U2DangerButton(
                    stringResource(R.string.community_delete), onClick = { onConfirm(CommunityConfirmation.Delete(group.id)) },
                    glyph = PhosphorGlyph.Trash, enabled = !busy,
                )
                group.active -> U2DangerButton(
                    stringResource(R.string.community_leave), onClick = { onConfirm(CommunityConfirmation.Leave(group.id)) },
                    glyph = PhosphorGlyph.LinkBreak, enabled = !busy,
                )
                else -> U2DangerButton(
                    stringResource(R.string.community_forget), onClick = { onConfirm(CommunityConfirmation.Forget(group.id)) },
                    glyph = PhosphorGlyph.Trash, enabled = !busy,
                )
            }
        }
    }
}

/** Join requests: who, the identity key they ask to add, and Reddet · Onayla (the 4.3 decision row). */
@Composable
private fun Requests(
    applications: List<CommunityApplication>,
    full: Boolean,
    busy: Boolean,
    onApprove: (CommunityApplication) -> Unit,
    onReject: (CommunityApplication) -> Unit,
) {
    val colors = U2Theme.colors
    Column(verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionLabelGap)) {
        SectionLabel(stringResource(R.string.community_applications))
        if (applications.isEmpty()) {
            U2Text(stringResource(R.string.community_requests_empty), U2TextStyles.callout, Modifier.padding(horizontal = 16.dp), color = colors.textSecondary)
            return@Column
        }
        if (full) U2Text(stringResource(R.string.community_full), U2TextStyles.captionCompact, Modifier.padding(horizontal = 16.dp), color = colors.danger)
        applications.forEach { application ->
            U2Card(Modifier.testTag(CommunityTags.request(application.account)), fill = colors.bg) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    U2Text(memberName(application.account, null), U2TextStyles.monoUsername, color = colors.text)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        U2Text(stringResource(R.string.community_identity), U2TextStyles.captionCompact, color = colors.textSecondary)
                        SelectionContainer { U2Text(application.fingerprint, U2TextStyles.monoMeta, color = colors.text) }
                    }
                    U2FlexRow(spacing = 8.dp) {
                        U2DecisionButton(
                            U2DecisionKind.Destructive,
                            stringResource(R.string.community_reject),
                            onClick = { onReject(application) },
                            modifier = Modifier.alpha(if (busy) U2Opacity.disabled else 1f),
                            enabled = !busy,
                        )
                        U2DecisionButton(
                            U2DecisionKind.Accept,
                            stringResource(R.string.community_approve),
                            onClick = { onApprove(application) },
                            modifier = Modifier.u2Flex(1.3f, border = 2.dp).alpha(if (busy || full) U2Opacity.disabled else 1f),
                            enabled = !busy && !full,
                        )
                    }
                }
            }
        }
    }
}

/** “Üyeler”: “Sen”, the username or “Üye · 1a2b3c4d”, over the role, “not in your contacts” or the short id. */
@Composable
private fun Members(group: CommunityCard, busy: Boolean, onRemove: (CommunityMemberCard) -> Unit) {
    // The administrator first, then this account, then the others by name (as on iOS).
    val members = group.members.sortedWith(
        compareByDescending<CommunityMemberCard> { it.isOwner }.thenByDescending { it.isSelf }.thenBy { it.username ?: "~${it.account}" },
    )
    Column(verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionLabelGap)) {
        SectionLabel(stringResource(R.string.community_members, group.members.size))
        GroupedCard(fill = U2Theme.colors.bg) {
            members.forEachIndexed { index, member ->
                if (index > 0) GroupedSeparator(inset = 14.dp)
                // Always a subtitle, so every row has the same height with or without “Çıkar”.
                val detail = when {
                    member.isOwner -> stringResource(R.string.community_admin)
                    member.username == null && !member.isSelf -> stringResource(R.string.community_not_contact)
                    else -> stringResource(R.string.community_member_short, CommunityFormat.shortId(member.account))
                }
                GroupedRow(tile = null, title = memberName(member.account, member.username, member.isSelf), subtitle = detail) {
                    if (group.isOwner && group.active && !member.isOwner && !member.isSelf) {
                        U2TextButton(
                            stringResource(R.string.community_remove), onClick = { onRemove(member) },
                            modifier = Modifier.testTag(CommunityTags.remove(member.account)),
                            tone = U2TextButtonTone.Danger, height = U2Size.iconButton, fillsWidth = false, enabled = !busy,
                        )
                    }
                }
            }
        }
    }
}

/** One-use invitations: made here, shown until used (or the sheet closes), shared through the system. */
@Composable
private fun Invitations(invite: String?, busy: Boolean, onInvite: () -> Unit, onRevoke: () -> Unit) {
    val colors = U2Theme.colors
    val context = LocalContext.current
    val guard = LocalExternalActivityGuard.current
    Column(verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionLabelGap)) {
        SectionLabel(stringResource(R.string.community_invites))
        U2Card(fill = colors.bg) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                U2Text(stringResource(R.string.community_invites_hint), U2TextStyles.captionCompact, color = colors.textSecondary)
                if (invite != null) {
                    SelectionContainer {
                        U2Text(invite, U2TextStyles.monoMeta, Modifier.testTag(CommunityTags.INVITE), color = colors.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    U2PrimaryButton(
                        stringResource(R.string.community_share_invite),
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, invite)
                            val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { guard.launching { context.startActivity(chooser) } }
                        },
                        glyph = PhosphorGlyph.Export,
                        size = U2PrimaryButtonSize.InCard,
                    )
                } else {
                    U2PrimaryButton(
                        stringResource(R.string.community_make_invite),
                        onClick = onInvite,
                        glyph = PhosphorGlyph.ShareNetwork,
                        size = U2PrimaryButtonSize.InCard,
                        enabled = !busy,
                    )
                }
                U2TextButton(stringResource(R.string.community_revoke), onClick = onRevoke, tone = U2TextButtonTone.Danger, height = U2Size.iconButton, enabled = !busy)
            }
        }
    }
}
