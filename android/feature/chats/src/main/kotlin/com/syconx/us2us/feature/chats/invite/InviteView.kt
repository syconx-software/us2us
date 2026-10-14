package com.syconx.us2us.feature.chats.invite

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.bubbles.BubbleText
import com.syconx.us2us.ui.bubbles.MessageBubble
import com.syconx.us2us.ui.components.DisclosureDetail
import com.syconx.us2us.ui.components.GroupedCard
import com.syconx.us2us.ui.components.GroupedSeparator
import com.syconx.us2us.ui.components.IconTile
import com.syconx.us2us.ui.components.IconTileContent
import com.syconx.us2us.ui.components.SectionLabel
import com.syconx.us2us.ui.components.SheetBar
import com.syconx.us2us.ui.components.U2ButtonLabel
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.components.groupedRowPress
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.l10n.rememberU2Strings
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.u2TransitionBlur
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import com.syconx.us2us.ui.type.textStyle
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Test tags of the invite sheet (the iOS accessibility identifiers). */
object InviteTags {
    const val SHEET = "invite.sheet"
    const val BAR = "invite.bar"
    const val EDIT = "invite.edit"
    const val DONE = "invite.done"
    const val RESET = "invite.reset"
    const val PREVIEW = "invite.preview"
    const val EDITOR = "invite.editor"
    const val PRIVACY = "invite.privacy"

    fun channel(channel: InviteChannel): String = "invite.channel.${channel.raw}"
}

/** What the `invite:<state>` harness opens the sheet with. */
@Immutable
data class InviteDebugState(
    val startsEditing: Boolean = false,
    val editedText: String? = null,
    val status: InviteChannel? = null,
)

/** A transient result shown on the channel's own row. */
@Immutable
data class InviteStatus(val channel: InviteChannel, val outcome: InviteOutcome)

/**
 * “Davet et / Invite” (proposed; the design has no screen for it, so it is drawn in the design's
 * language): the sheet bar, the ready-made message as the outgoing bubble the friend will get
 * (editable), the channels as a C13 card on `bg` (as in 4.2), and what the app does not learn.
 *
 * Shown as a full-height sheet (7.1 › “Arkadaşını davet et”, the `invite` harness) or pushed inside
 * the 4.2 sheet after an exact search found nothing (with “Geri”).
 *
 * Nothing here touches the contacts or the relay: the user picks the recipient in the SMS app, a
 * mail app, WhatsApp or the share sheet; the message carries only the username and
 * `https://us2us.app/u/<username>`.
 */
@Composable
fun InviteView(
    username: String,
    closeTitle: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    debug: InviteDebugState = InviteDebugState(),
) {
    val strings = rememberU2Strings()
    val sender = rememberInviteSender()
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val editorFocus = remember { FocusRequester() }

    /** The user's own text; `null` = the template in the current language. */
    var editedText by remember { mutableStateOf<String?>(null) }
    var isEditing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<InviteStatus?>(null) }
    var statusJob by remember { mutableStateOf<Job?>(null) }

    val template = remember(username, strings) { InviteMessage.make(username, strings) }
    val defaultText = template?.text.orEmpty()
    // The draft, unless it is empty (an empty invitation is never sent).
    val sendableDraft = draft.takeIf { it.isNotBlank() }
    val message = template?.let {
        when {
            isEditing -> it.copy(text = sendableDraft ?: it.text)
            editedText != null -> it.copy(text = editedText!!)
            else -> it
        }
    }
    val isDraftChanged = isEditing && draft != defaultText

    fun beginEditing() {
        draft = editedText ?: defaultText
        isEditing = true
        scope.launch {
            // After the cross-fade has started, so the keyboard and the fade do not stall each other.
            delay(200.milliseconds)
            runCatching { editorFocus.requestFocus() }
        }
    }

    fun endEditing() {
        focusManager.clearFocus()
        val text = draft.takeIf { it.isNotBlank() }
        editedText = if (text == null || text == defaultText) null else text
        isEditing = false
    }

    fun show(outcome: InviteOutcome, channel: InviteChannel) {
        if (outcome != InviteOutcome.Sent && outcome != InviteOutcome.Copied) return
        if (outcome == InviteOutcome.Copied) haptics.success()
        status = InviteStatus(channel, outcome)
        statusJob?.cancel()
        statusJob = scope.launch {
            delay(3.seconds)
            status = null
        }
    }

    fun perform(channel: InviteChannel) {
        val current = message ?: return
        if (isEditing) endEditing()
        sender.send(channel, current) { outcome -> show(outcome, channel) }
    }

    LaunchedEffect(Unit) {
        debug.editedText?.let { editedText = it }
        if (debug.startsEditing) beginEditing()
        debug.status?.let { status = InviteStatus(it, if (it == InviteChannel.CopyLink) InviteOutcome.Copied else InviteOutcome.Sent) }
    }
    DisposableEffect(Unit) { onDispose { statusJob?.cancel() } }

    Column(modifier.fillMaxSize().testTag(InviteTags.SHEET)) {
        SheetBar(
            title = stringResource(R.string.invite_title),
            cancelTitle = closeTitle,
            onCancel = {
                focusManager.clearFocus()
                onClose()
            },
            modifier = Modifier.testTag(InviteTags.BAR),
        )
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        ) {
            // “Arkadaşına gidecek mesaj” + Düzenle / Bitti, then the bubble or the editor.
            Column(verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionLabelGap)) {
                MessageHeader(
                    isEditing = isEditing,
                    isDraftChanged = isDraftChanged,
                    onReset = { draft = defaultText },
                    onToggle = { if (isEditing) endEditing() else beginEditing() },
                )
                MessageCard(
                    isEditing = isEditing,
                    message = message,
                    draft = draft,
                    onDraftChange = { draft = InviteMessage.clamped(it) },
                    editorFocus = editorFocus,
                )
            }
            ChannelsSection(
                username = username,
                status = status,
                onChannel = ::perform,
                modifier = Modifier.padding(top = 22.dp),
            )
            PrivacyNote(Modifier.padding(start = 8.dp, top = 16.dp, end = 8.dp))
        }
    }
}

@Composable
private fun MessageHeader(isEditing: Boolean, isDraftChanged: Boolean, onReset: () -> Unit, onToggle: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(stringResource(R.string.invite_message_label), Modifier.weight(1f))
        val fade = U2Transitions.fade()
        AnimatedVisibility(isDraftChanged, enter = fade.enter, exit = fade.exit) {
            U2TextButton(
                title = stringResource(R.string.invite_reset),
                onClick = onReset,
                tone = U2TextButtonTone.Quiet,
                height = 32.dp,
                fillsWidth = false,
                modifier = Modifier
                    .then(u2TransitionBlur(fade))
                    .testTag(InviteTags.RESET),
            )
        }
        U2TextButton(
            onClick = onToggle,
            tone = U2TextButtonTone.Accent,
            height = 32.dp,
            fillsWidth = false,
            modifier = Modifier
                .padding(end = 16.dp)
                .testTag(if (isEditing) InviteTags.DONE else InviteTags.EDIT),
        ) {
            val title = stringResource(if (isEditing) R.string.invite_done else R.string.invite_edit)
            val timing = U2Motion.resolve(U2Motion.state, LocalReduceMotion.current)
            AnimatedContent(
                targetState = title,
                transitionSpec = { fadeIn(timing.spec()) togetherWith fadeOut(timing.spec()) },
                label = "inviteEdit",
            ) { U2ButtonLabel(it) }
        }
    }
}

@Composable
private fun MessageCard(
    isEditing: Boolean,
    message: InviteMessage?,
    draft: String,
    onDraftChange: (String) -> Unit,
    editorFocus: FocusRequester,
) {
    val colors = U2Theme.colors
    val shape = RoundedCornerShape(U2Radius.cardL)
    Box(
        Modifier
            .fillMaxWidth()
            .background(colors.bg, shape)
            .padding(14.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        val fade = U2Transitions.fade()
        AnimatedContent(
            targetState = isEditing,
            transitionSpec = { fade.enter togetherWith fade.exit },
            contentAlignment = Alignment.TopEnd,
            label = "inviteCard",
        ) { editing ->
            if (editing) {
                Editor(draft, onDraftChange, editorFocus, Modifier.then(u2TransitionBlur(fade)))
            } else if (message != null) {
                Preview(message, Modifier.then(u2TransitionBlur(fade)))
            }
        }
    }
}

/** The outgoing bubble (C15, tail), the link underlined. */
@Composable
private fun Preview(message: InviteMessage, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.invite_a11y_invite_preview)
    val text = remember(message.text, message.link) { previewText(message) }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
        MessageBubble(
            direction = MessageDirection.Outgoing,
            isTail = true,
            modifier = Modifier
                .testTag(InviteTags.PREVIEW)
                .clearAndSetSemantics {
                    contentDescription = label
                    stateDescription = message.text
                },
        ) {
            // Wrapped, the bubble is as wide as its widest line, as iOS `Text` in the bubble is.
            BubbleText(text, U2Theme.colors.onBubbleOutgoing)
        }
    }
}

private fun previewText(message: InviteMessage): AnnotatedString = buildAnnotatedString {
    append(message.text)
    val start = message.text.indexOf(message.link)
    if (start >= 0) addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, start + message.link.length)
}

@Composable
private fun Editor(draft: String, onDraftChange: (String) -> Unit, focus: FocusRequester, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val shape = RoundedCornerShape(U2Radius.field)
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(
        if (focused) 1f else 0f,
        U2Motion.resolve(U2Motion.state, LocalReduceMotion.current).spec(),
        label = "inviteEditorRing",
    )
    val description = stringResource(R.string.invite_a11y_invite_editor)
    BasicTextField(
        value = draft,
        onValueChange = onDraftChange,
        textStyle = U2TextStyles.bubble.textStyle().copy(color = colors.text),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(1.5.dp, colors.accent.copy(alpha = colors.accent.alpha * ring), shape)
            .padding(horizontal = 9.dp, vertical = 6.dp)
            // The 168–280 frame is the editor's own, inside the 6 / 9 padding (iOS `TextEditor`), and
            // the text sits inside UIKit's text-container insets (8 top and bottom, 5 at the sides);
            // the top one is 2 dp smaller here because the line box already carries its half-leading.
            .heightIn(min = 168.dp, max = 280.dp)
            .padding(start = 5.dp, top = 6.dp, end = 5.dp, bottom = 8.dp)
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = description }
            .testTag(InviteTags.EDITOR),
    )
}

@Composable
private fun ChannelsSection(
    username: String,
    status: InviteStatus?,
    onChannel: (InviteChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionLabelGap)) {
        SectionLabel(stringResource(R.string.invite_channels_label))
        GroupedCard(fill = U2Theme.colors.bg) {
            InviteChannel.entries.forEachIndexed { index, channel ->
                if (index > 0) GroupedSeparator()
                val subtitle = when {
                    status?.channel == channel -> InviteChannelSubtitle.Status(
                        stringResource(if (status.outcome == InviteOutcome.Copied) R.string.invite_copied else R.string.invite_sent),
                    )
                    channel == InviteChannel.CopyLink -> InviteLink.displayString(username)?.let(InviteChannelSubtitle::Link)
                    else -> null
                }
                InviteChannelRow(channel, subtitle, isDone = status?.channel == channel, onSelect = { onChannel(channel) })
            }
        }
    }
}

/** The privacy line (as the 4.2 info row: glyph 18 + `callout`). */
@Composable
private fun PrivacyNote(modifier: Modifier = Modifier) {
    Row(
        modifier
            .testTag(InviteTags.PRIVACY)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PhosphorIcon(PhosphorGlyph.ShieldCheck, size = 18.dp, tint = U2Theme.colors.accent300, modifier = Modifier.padding(top = 1.dp))
        U2Text(stringResource(R.string.invite_privacy), U2TextStyles.callout, color = U2Theme.colors.textSecondary, modifier = Modifier.weight(1f))
    }
}

/** The second line of a channel row. */
sealed interface InviteChannelSubtitle {
    /** “us2us.app/u/selin_n” in mono. */
    data class Link(val text: String) : InviteChannelSubtitle

    /** “Kopyalandı …” / “Gönderildi”. */
    data class Status(val text: String) : InviteChannelSubtitle
}

/**
 * A C13 row (tile 30 · gap 13 · title `rowTitle` + subtitle · chevron, at least 48 high, 62 with a
 * subtitle) for one channel; done, a filled check pops in where the chevron was.
 */
@Composable
private fun InviteChannelRow(channel: InviteChannel, subtitle: InviteChannelSubtitle?, isDone: Boolean, onSelect: () -> Unit) {
    val colors = U2Theme.colors
    val source = remember { MutableInteractionSource() }
    val title = stringResource(channel.titleRes)
    val hint = stringResource(channel.hintRes)
    Row(
        Modifier
            .fillMaxWidth()
            .groupedRowPress(source)
            .clickable(source, indication = null, role = Role.Button, onClickLabel = hint, onClick = onSelect)
            .semantics(mergeDescendants = true) {}
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = if (subtitle == null) 0.dp else 9.dp)
            .testTag(InviteTags.channel(channel)),
        horizontalArrangement = Arrangement.spacedBy(U2Spacing.rowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(IconTileContent.Glyph(channel.glyph))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            U2Text(title, U2TextStyles.rowTitle, color = colors.text)
            val fade = U2Transitions.fade()
            AnimatedContent(
                targetState = subtitle,
                transitionSpec = { fade.enter togetherWith fade.exit },
                label = "inviteSubtitle",
            ) { shown ->
                when (shown) {
                    is InviteChannelSubtitle.Link -> U2Text(
                        shown.text,
                        U2TextStyles.monoMeta,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    is InviteChannelSubtitle.Status -> U2Text(
                        shown.text,
                        U2TextStyles.captionCompact,
                        color = colors.textSecondary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    null -> Unit
                }
            }
        }
        ChannelTrailing(isDone, showsChevron = channel != InviteChannel.CopyLink)
    }
}

/** Done: a filled check pops in where the chevron was. */
@Composable
private fun ChannelTrailing(isDone: Boolean, showsChevron: Boolean) {
    val colors = U2Theme.colors
    Box(contentAlignment = Alignment.Center) {
        val pop = U2Transitions.pop()
        val fade = U2Transitions.fade()
        AnimatedVisibility(isDone, enter = pop.enter, exit = pop.exit) {
            PhosphorIcon(PhosphorGlyph.CheckCircle, size = 18.dp, weight = PhosphorWeight.Fill, tint = colors.online, modifier = Modifier.then(u2TransitionBlur(pop)))
        }
        AnimatedVisibility(!isDone && showsChevron, enter = fade.enter, exit = fade.exit) {
            Row(Modifier.then(u2TransitionBlur(fade))) { DisclosureDetail() }
        }
    }
}

private val InviteChannel.glyph: PhosphorGlyph
    get() = when (this) {
        InviteChannel.Sms -> PhosphorGlyph.ChatCircleDots
        InviteChannel.WhatsApp -> PhosphorGlyph.WhatsappLogo
        InviteChannel.Email -> PhosphorGlyph.EnvelopeSimple
        InviteChannel.More -> PhosphorGlyph.Export
        InviteChannel.CopyLink -> PhosphorGlyph.Copy
    }

private val InviteChannel.titleRes: Int
    get() = when (this) {
        InviteChannel.Sms -> R.string.invite_channel_sms
        InviteChannel.WhatsApp -> R.string.invite_channel_whatsapp
        InviteChannel.Email -> R.string.invite_channel_email
        InviteChannel.More -> R.string.invite_channel_more
        InviteChannel.CopyLink -> R.string.invite_channel_copy
    }

private val InviteChannel.hintRes: Int
    get() = when (this) {
        InviteChannel.Sms -> R.string.invite_a11y_invite_sms
        InviteChannel.WhatsApp -> R.string.invite_a11y_invite_whatsapp
        InviteChannel.Email -> R.string.invite_a11y_invite_email
        InviteChannel.More -> R.string.invite_a11y_invite_more
        InviteChannel.CopyLink -> R.string.invite_a11y_invite_copy
    }
