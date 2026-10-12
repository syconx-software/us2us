package com.syconx.us2us.feature.conversation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.bubbles.MessageBubbleView
import com.syconx.us2us.ui.bubbles.StateCrossFade
import com.syconx.us2us.ui.bubbles.titleRes
import com.syconx.us2us.ui.components.DotPattern
import com.syconx.us2us.ui.components.DottedRule
import com.syconx.us2us.ui.components.NoteCard
import com.syconx.us2us.ui.components.SheetCloseHeader
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.l10n.U2Format
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.media.MediaGlyph
import com.syconx.us2us.ui.media.MediaGlyphIcon
import com.syconx.us2us.ui.media.SavedMediaChip
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.u2Spec
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import java.time.Instant

/**
 * 5.4 · Mesaj bilgisi: the message's journey, step by step (there are no notifications, so this is
 * where “with you / on its way / with them” is spelled out; a photo, video or file the other side
 * saved ends in “Kaydedildi”, proposed). Full-height sheet, pad 16 16 24 16, gap 16: centred
 * title and close (36), the message bubble on its side, the timeline card (`bg` r20, pad 18 16)
 * and the “İletilemedi ne demek?” note.
 */
@Composable
internal fun MessageInfoSheet(viewModel: ConversationViewModel, messageId: MessageId, modifier: Modifier = Modifier) {
    val message = viewModel.message(messageId)
    val showsRead by viewModel.messenger.readReceiptsEnabled.collectAsStateWithLifecycle()
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp)
            .testTag("info.sheet"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SheetCloseHeader(stringResource(R.string.conversation_info_title), onClose = { viewModel.info = null }, modifier = Modifier.testTag("info.header"))
        if (message != null) {
            InfoBubble(message)
            InfoTimeline(InfoStep.steps(message, showsRead), viewModel.peerUsername)
        }
        NoteCard(
            stringResource(R.string.conversation_info_note_title),
            stringResource(R.string.conversation_info_note_body),
            Modifier.testTag("info.note"),
        )
    }
}

/** The bubble as a plain, round bubble (the design drops the reply quote and the chips under it here). */
@Composable
private fun InfoBubble(message: Message) {
    val plain = message.copy(replyTo = null, reactions = emptyList(), savedByPeer = false)
    Box(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        contentAlignment = if (message.isOutgoing) Alignment.TopEnd else Alignment.TopStart,
    ) {
        MessageBubbleView(plain, Modifier.testTag("info.bubble").clearAndSetSemantics {}, isTail = false)
    }
}

/** One step of the journey. */
@Immutable
data class InfoStep(val kind: Kind, val isReached: Boolean, val time: Instant?) {
    enum class Kind {
        Sent,
        Delivered,
        Read,
        Failed,

        /** The other side saved the photo, video or file (their SAVED receipt, told whatever the read-receipt setting). */
        Saved,
    }

    companion object {
        /**
         * Gönderildi → İletildi → Okundu (→ Kaydedildi); a failed message ends in İletilemedi.
         * “Okundu” is hidden when read receipts are off (they are mutual); “Kaydedildi” shows only
         * once it happened.
         */
        fun steps(message: Message, showsRead: Boolean): List<InfoStep> {
            val status = message.status ?: MessageStatus.Sending
            val sent = InfoStep(Kind.Sent, status != MessageStatus.Sending, if (status == MessageStatus.Sending) null else message.sentAt)
            if (status == MessageStatus.Failed) return listOf(sent, InfoStep(Kind.Failed, true, message.failedAt))
            val delivered = status == MessageStatus.Delivered || status == MessageStatus.Read
            val steps = mutableListOf(sent, InfoStep(Kind.Delivered, delivered, if (delivered) message.deliveredAt else null))
            if (showsRead) steps += InfoStep(Kind.Read, status == MessageStatus.Read, if (status == MessageStatus.Read) message.readAt else null)
            if (SavedMediaChip.isShown(message)) steps += InfoStep(Kind.Saved, true, message.savedByPeerAt)
            return steps
        }
    }
}

/** The timeline card: rows on a [34 | 1fr | auto] grid (gap 14), joined by a vertical dotted connector. */
@Composable
private fun InfoTimeline(steps: List<InfoStep>, peer: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("info.timeline")
            .background(U2Theme.colors.bg, RoundedCornerShape(U2Radius.cardL))
            .padding(horizontal = 16.dp, vertical = 18.dp),
    ) {
        steps.forEachIndexed { index, step ->
            val isLast = index == steps.lastIndex
            InfoTimelineRow(step, peer, isLast, nextIsReached = if (isLast) false else steps[index + 1].isReached, Modifier.testTag("info.step.${step.kind.name.lowercase()}"))
        }
    }
}

@Composable
private fun InfoTimelineRow(step: InfoStep, peer: String, isLast: Boolean, nextIsReached: Boolean, modifier: Modifier) {
    val colors = U2Theme.colors
    val locale = currentAppLocale()
    val fill by animateFloatAsState(if (nextIsReached) 1f else 0f, u2Spec(U2Motion.soft(0.5f)), label = "connector")
    Box(modifier.fillMaxWidth().heightIn(min = if (isLast) 34.dp else 56.dp).semantics(mergeDescendants = true) {}) {
        if (!isLast) Connector(fill, Modifier.matchParentSize())
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Node(step)
            Column(
                Modifier
                    .weight(1f)
                    .padding(top = 6.dp, bottom = if (isLast) 0.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                U2Text(stringResource(step.titleRes), U2TextStyles.rowTitleEmphasis, color = if (step.isReached) colors.text else colors.textPending)
                U2Text(detail(step, peer), U2TextStyles.captionTighter, color = if (step.isReached) colors.textSecondary else colors.textPending)
            }
            Box(Modifier.padding(top = 8.dp)) {
                StateCrossFade(step.time?.takeIf { step.isReached }) { time ->
                    if (time != null) U2Text(U2Format.clockTime(time, locale), U2TextStyles.monoMeta, color = colors.textSecondary, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** The dotted connector under the node to the next row (`text·32%`); it fills from the top when the next step is reached (0.5 s). */
@Composable
private fun Connector(fill: Float, modifier: Modifier) {
    val color = U2Theme.colors.dotRuleTimeline
    Box(modifier.clearAndSetSemantics {}, contentAlignment = Alignment.TopStart) {
        Box(Modifier.width(34.dp).fillMaxHeight().padding(top = 34.dp), contentAlignment = Alignment.TopCenter) {
            DottedRule(Modifier.fillMaxHeight().graphicsLayer { alpha = 0.4f }, color = color, pattern = DotPattern.Timeline, orientation = Orientation.Vertical)
            DottedRule(
                Modifier
                    .fillMaxHeight()
                    .graphicsLayer {
                        scaleY = fill
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        clip = true
                    },
                color = color,
                pattern = DotPattern.Timeline,
                orientation = Orientation.Vertical,
            )
        }
    }
}

/** 34 dp `surface` circle with an inner 1 dp `divider` and the C16 glyph. */
@Composable
private fun Node(step: InfoStep) {
    val colors = U2Theme.colors
    val pending = colors.textPending
    Box(
        Modifier
            .size(34.dp)
            .background(colors.surface, CircleShape)
            .u2InnerStroke(colors.divider, 1.dp, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        StateCrossFade(step.isReached) { reached ->
            when (step.kind) {
                InfoStep.Kind.Sent -> PhosphorIcon(PhosphorGlyph.Check, 17.dp, tint = if (reached) colors.textSecondary else pending)
                InfoStep.Kind.Delivered -> PhosphorIcon(PhosphorGlyph.Checks, 17.dp, tint = if (reached) colors.textSecondary else pending)
                InfoStep.Kind.Read -> PhosphorIcon(PhosphorGlyph.Checks, 17.dp, weight = PhosphorWeight.Fill, tint = if (reached) colors.accent300 else pending)
                InfoStep.Kind.Failed -> PhosphorIcon(PhosphorGlyph.WarningCircle, 17.dp, tint = colors.danger)
                InfoStep.Kind.Saved -> MediaGlyphIcon(MediaGlyph.Download, 17.dp, tint = colors.accent300)
            }
        }
    }
}

private val InfoStep.titleRes: Int
    get() = when (kind) {
        InfoStep.Kind.Sent -> MessageStatus.Sent.titleRes
        InfoStep.Kind.Delivered -> MessageStatus.Delivered.titleRes
        InfoStep.Kind.Read -> MessageStatus.Read.titleRes
        InfoStep.Kind.Failed -> MessageStatus.Failed.titleRes
        InfoStep.Kind.Saved -> R.string.conversation_info_saved
    }

@Composable
private fun detail(step: InfoStep, peer: String): String = when (step.kind) {
    InfoStep.Kind.Sent -> stringResource(R.string.conversation_info_sent_detail)
    InfoStep.Kind.Delivered -> stringResource(R.string.conversation_info_delivered_detail, peer)
    InfoStep.Kind.Read -> stringResource(R.string.conversation_info_read_detail, peer)
    InfoStep.Kind.Failed -> stringResource(R.string.conversation_info_failed_detail)
    InfoStep.Kind.Saved -> stringResource(R.string.conversation_info_saved_detail, peer)
}
