package com.syconx.us2us.feature.conversation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.ConversationPhotos
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.bubbles.MessageBubbleView
import com.syconx.us2us.ui.bubbles.MessageStatusSentence
import com.syconx.us2us.ui.bubbles.StateCrossFade
import com.syconx.us2us.ui.bubbles.TypingIndicatorBubble
import com.syconx.us2us.ui.components.communityMemberName
import com.syconx.us2us.ui.env.currentSafeArea
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.u2CollapseProgress
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Spacing
import kotlin.math.roundToInt

/**
 * 5.1 · the message column: bottom-aligned bubbles (gap 6, pad 0 14) that scroll under the
 * translucent header and the composer.
 *
 * - **Any length.** A lazy column laid out from the bottom (the newest row is item 0): it costs
 *   only the rows on screen.
 * - **Anchored at the bottom.** It opens at the newest message; at the bottom it follows arrivals;
 *   scrolled up, what is on screen stays put when messages arrive below.
 * - **Motion.** A new row's height grows in frame by frame while it rises from behind the
 *   composer (sent: on the critically damped spring, from 94 % at its tail corner; received: the
 *   design's 0.36 s ease-out), and the anchor carries the column with it; a removed row, a moving
 *   status sentence and the typing bubble collapse the same way; a change of kind morphs. Reduce
 *   motion: short cross-fades.
 *
 * [bottomPadding] is the composer's height (the column ends 6 dp above it).
 */
@Composable
internal fun ConversationMessageList(
    viewModel: ConversationViewModel,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
    onVerify: () -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val leaving = viewModel.leavingRows
    val arriving = viewModel.arrivingRows
    val kindChanges = viewModel.kindChanges
    val sourceRows = viewModel.displayedRows
    val leavingIds = leaving.mapTo(HashSet()) { it.row.id }
    val removedPhotos = remember(sourceRows, leavingIds) { ConversationRows.albumRemovals(sourceRows, leavingIds) }
    LaunchedEffect(removedPhotos) { removedPhotos.forEach(viewModel::finishLeaving) }
    val separate = leaving.mapTo(HashSet()) { it.row.id } + kindChanges.keys
    val displayed = remember(sourceRows, separate, removedPhotos) {
        ConversationRows.albums(sourceRows.filterNot { it.id in removedPhotos }, separate)
    }
    val hiddenArrivals = displayed.flatMap { it.album.drop(1) }.map { it.id }.filter { it in arriving }
    LaunchedEffect(hiddenArrivals) { hiddenArrivals.forEach(viewModel::finishArrival) }
    val oldest = displayed.firstOrNull()?.id
    LaunchedEffect(viewModel, oldest) {
        snapshotFlow {
            val layout = viewModel.listState.layoutInfo
            val nearTop = layout.totalItemsCount > 0 &&
                (layout.visibleItemsInfo.maxOfOrNull { it.index } ?: -1) >= layout.totalItemsCount - 8
            nearTop && (viewModel.listState.firstVisibleItemIndex > 0 || viewModel.listState.firstVisibleItemScrollOffset > 0)
        }.distinctUntilChanged().collect { nearTop ->
            if (nearTop) viewModel.loadOlderMessages()
        }
    }
    val liftedId = viewModel.actions?.messageId
    val frameRequest = viewModel.frameRequest
    val firstId = displayed.firstOrNull()?.id
    val newestOutgoing = viewModel.rows.lastOrNull()?.message?.isOutgoing ?: false
    val typing = viewModel.isPeerTyping
    // The typing bubble's presence: it grows in and collapses out in the layout, frame by frame.
    val typingProgress = remember { Animatable(0f) }
    var typingInLayout by remember { mutableStateOf(false) }
    LaunchedEffect(typing) {
        if (typing) typingInLayout = true
        typingProgress.animateTo(if (typing) 1f else 0f, insertSpec(newestOutgoing, reduceMotion))
        if (!typing) typingInLayout = false
    }
    val verifyPrompt = viewModel.showsVerifyPrompt
    LazyColumn(
        modifier = modifier.conversationKeyboardDismissal(viewModel.listState).testTag("conversation.list"),
        state = viewModel.listState,
        reverseLayout = true,
        contentPadding = PaddingValues(
            start = U2Spacing.chatGutter,
            top = currentSafeArea.top(114.dp),
            end = U2Spacing.chatGutter,
            // The end of the column: the design's 100 dp bottom padding, of which the composer covers 94.
            bottom = bottomPadding + U2Spacing.bubbleGap,
        ),
    ) {
        if (typingInLayout) {
            item(key = TYPING_KEY) {
                val name = viewModel.peerUsername
                val label = stringResource(R.string.conversation_a11y_typing, name)
                TypingIndicatorBubble(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = U2Spacing.bubbleGap)
                        .arrival(progress = { if (reduceMotion) 1f else typingProgress.value }, sent = false, clips = !typing)
                        .clearAndSetSemantics { contentDescription = label },
                )
            }
        }
        items(displayed.asReversed(), key = { it.id.value }) { row ->
            val isLeaving = leaving.any { it.row.id == row.id }
            RowSlot(
                row = row,
                viewModel = viewModel,
                isFirst = row.id == firstId,
                isLifted = row.id == liftedId,
                capturesFrame = row.id == frameRequest,
                replacing = kindChanges[row.id],
                isLeaving = isLeaving,
                isArriving = row.id in arriving,
            )
        }
        if (verifyPrompt) {
            item(key = VERIFY_KEY) {
                ConversationVerifyPrompt(
                    onVerify,
                    Modifier.fillMaxWidth().padding(bottom = if (displayed.isEmpty()) 0.dp else U2Spacing.bubbleGap * 2),
                )
            }
        }
    }
}

private const val TYPING_KEY = "conversation.typing"
private const val VERIFY_KEY = "conversation.verifyPrompt"

/** Sent: the critically damped spring (no bounce); received: the design's 0.36 s ease-out. */
private fun insertSpec(sent: Boolean, reduceMotion: Boolean): AnimationSpec<Float> = when {
    reduceMotion -> U2Motion.reducedCrossFade.spec<Float>()
    sent -> ConversationMotion.send()
    else -> U2Motion.appear.spec()
}

/** One row of the column with its gap and its state there (leaving, arriving, at rest). */
@Composable
private fun RowSlot(
    row: ConversationRow,
    viewModel: ConversationViewModel,
    isFirst: Boolean,
    isLifted: Boolean,
    capturesFrame: Boolean,
    replacing: Message?,
    isLeaving: Boolean,
    isArriving: Boolean,
) {
    val reduceMotion = LocalReduceMotion.current
    val sent = row.message.isOutgoing && !row.message.isSystem
    // New rows grow in from height 0, then become plain rows.
    val arrival = remember { Animatable(if (isArriving && !reduceMotion) 0f else 1f) }
    val collapse = remember { Animatable(1f) }
    val id = row.id
    LaunchedEffect(isArriving) {
        if (!isArriving) return@LaunchedEffect
        arrival.animateTo(1f, insertSpec(sent, reduceMotion))
        viewModel.finishArrival(id)
    }
    LaunchedEffect(isLeaving) {
        if (!isLeaving) return@LaunchedEffect
        collapse.animateTo(0f, U2Motion.resolve(U2Motion.state, reduceMotion).spec())
        viewModel.finishLeaving(id)
    }
    val content = Modifier.padding(top = if (isFirst) 0.dp else U2Spacing.bubbleGap)
    val slot = when {
        isLeaving && reduceMotion -> Modifier.graphicsLayer { alpha = collapse.value }.clearAndSetSemantics {}
        isLeaving -> Modifier.u2CollapseProgress(collapse.value, U2Radius.bubble).clearAndSetSemantics {}
        else -> Modifier.arrival(progress = { arrival.value }, sent = sent)
    }
    ConversationMessageRow(
        row = row,
        viewModel = viewModel,
        isLifted = isLifted,
        capturesFrame = capturesFrame,
        replacing = replacing,
        isNew = isArriving,
        enabled = !isLeaving,
        modifier = slot.then(content),
    )
}

/**
 * A new row at the bottom of the column: its height (with the gap above it) grows from 0 to its
 * own, frame by frame, with the row hanging from the top of that growing space, so it rises from
 * behind the composer while the anchor carries the rows above up with it. It fades in on the way;
 * a sent bubble also grows from 94 % at its tail corner. [clips]: leaving instead (the typing
 * bubble), it fades away in place.
 */
internal fun Modifier.arrival(progress: () -> Float, sent: Boolean, clips: Boolean = false): Modifier = this
    .graphicsLayer {
        val p = progress().coerceIn(0f, 1f)
        alpha = p
        if (clips && p < 1f) {
            clip = true
            shape = RoundedCornerShape(0.dp)
        }
    }
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val p = progress().coerceIn(0f, 1f)
        layout(placeable.width, (placeable.height * p).roundToInt()) { placeable.place(0, 0) }
    }
    .graphicsLayer {
        val p = progress().coerceIn(0f, 1f)
        if (sent && p < 1f) {
            val scale = 0.94f + 0.06f * p
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(1f, 1f)
        }
    }

/**
 * A row of the message column (iOS `ConversationMessageRow`): the bubble aligned to its side, the
 * status sentence under the newest outgoing one, the long press that opens 5.3. [viewModel] `null`
 * draws an inert row (4.4).
 */
@Composable
internal fun ConversationMessageRow(
    row: ConversationRow,
    viewModel: ConversationViewModel?,
    modifier: Modifier = Modifier,
    isLifted: Boolean = false,
    capturesFrame: Boolean = false,
    replacing: Message? = null,
    isNew: Boolean = false,
    enabled: Boolean = true,
) {
    val message = row.message
    val reduceMotion = LocalReduceMotion.current
    // The sentence stays in the layout while it collapses, so the bottom-anchored column glides.
    val sentence = remember { Animatable(if (row.showsStatusSentence) 1f else 0f) }
    var sentenceInLayout by remember { mutableStateOf(row.showsStatusSentence) }
    LaunchedEffect(row.showsStatusSentence) {
        val shows = row.showsStatusSentence
        if (shows) sentenceInLayout = true
        sentence.animateTo(if (shows) 1f else 0f, U2Motion.resolve(U2Motion.state, reduceMotion).spec())
        if (!shows) sentenceInLayout = false
    }
    Column(modifier.fillMaxWidth()) {
        if (message.isSystem) {
            MessageBubbleView(message, Modifier.testTag("message.${message.id}"))
        } else {
            val sender = message.community?.sender
            if (row.showsSender && sender != null) {
                // A community: who wrote it, above the first bubble of the run (never a whole account id).
                U2Text(
                    communityMemberName(sender.account, sender.username),
                    if (sender.username == null) U2TextStyles.captionCompact else U2TextStyles.monoMeta,
                    Modifier.padding(start = 6.dp, top = 6.dp, end = 6.dp, bottom = 4.dp).testTag("message.${message.id}.sender"),
                    color = U2Theme.colors.textSecondary,
                    maxLines = 1,
                )
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = if (message.isOutgoing) Alignment.TopEnd else Alignment.TopStart) {
                if (row.album.size > 1 && viewModel != null) {
                    ConversationPhotoAlbum(row, viewModel, enabled)
                } else RowBubble(row, viewModel, isLifted, capturesFrame, replacing, isNew, enabled)
            }
        }
        val statusMessage = row.album.lastOrNull() ?: message
        val status = if (row.album.size > 1) ConversationPhotos.status(row.album) else statusMessage.status
        if (status != null && (row.showsStatusSentence || sentenceInLayout)) {
            val detail = statusDetail(statusMessage)
            val resend = remember(viewModel, statusMessage.id) { { viewModel?.resend(statusMessage.id) ?: Unit } }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = U2Spacing.bubbleGap)
                    .then(if (reduceMotion) Modifier.graphicsLayer { alpha = sentence.value } else Modifier.u2CollapseProgress(sentence.value, 0.dp))
                    .testTag("message.${message.id}.status"),
            ) {
                StateCrossFade(StatusLine(status, detail), contentAlignment = Alignment.CenterEnd) { line ->
                    MessageStatusSentence(
                        line.status,
                        detail = line.detail,
                        onResend = if (line.status == MessageStatus.Failed && row.showsStatusSentence) resend else null,
                    )
                }
            }
        }
    }
}

/** The sentence's state and, for a photo or video still on this phone, what it is doing. */
private data class StatusLine(val status: MessageStatus, val detail: String?)

/** A photo or video sent from here explains itself while it is prepared, and when preparing it failed. */
@Composable
private fun statusDetail(message: Message): String? {
    val isPhoto = message.content is MessageContent.Photo
    return when (message.mediaAvailability) {
        MediaAvailability.Preparing -> stringResource(if (isPhoto) R.string.conversation_photo_preparing_detail else R.string.conversation_video_preparing_detail)
        MediaAvailability.PreparationFailed -> stringResource(
            if (isPhoto) R.string.conversation_photo_preparationfailed_detail else R.string.conversation_video_preparationfailed_detail,
        )
        else -> null
    }
}

@Composable
private fun RowBubble(
    row: ConversationRow,
    viewModel: ConversationViewModel?,
    isLifted: Boolean,
    capturesFrame: Boolean,
    replacing: Message?,
    isNew: Boolean,
    enabled: Boolean,
) {
    val message = row.message
    val id = message.id
    val reduceMotion = LocalReduceMotion.current
    var isPressing by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(if (isPressing && !reduceMotion) 1.02f else 1f, U2Motion.press.spec(), label = "press")
    val current by rememberUpdatedState(row)
    val actionsLabel = stringResource(R.string.conversation_a11y_message_actions)
    val longPress: (() -> Unit)? = if (viewModel != null && enabled && viewModel.canOpenActions(message)) {
        {
            isPressing = false
            viewModel.message(id)?.let { viewModel.openActions(it, current.isTail) }
        }
    } else {
        null
    }
    val reports = viewModel != null && (isPressing || capturesFrame)
    MessageBubbleView(
        message = message,
        modifier = Modifier
            .testTag("message.$id")
            .then(if (reports) Modifier.onGloballyPositioned { viewModel?.frames?.set(id, it.boundsInWindow()) } else Modifier)
            .graphicsLayer {
                scaleX = lift
                scaleY = lift
                transformOrigin = TransformOrigin(if (message.isOutgoing) 1f else 0f, 0.5f)
                alpha = if (isLifted) 0f else 1f
            }
            .then(
                if (longPress != null) {
                    Modifier.semantics {
                        customActions = listOf(CustomAccessibilityAction(actionsLabel) { longPress(); true })
                    }
                } else {
                    Modifier
                },
            ),
        isTail = row.isTail,
        animatesEntrance = isNew,
        replacing = replacing,
        onReplaced = { viewModel?.finishKindChange(id) },
        onStopLiveLocation = { viewModel?.stopLiveLocation(id) },
        onOpenViewOnce = { viewModel?.let { vm -> vm.message(id)?.let(vm::openViewOnce) } },
        onOpenMedia = { viewModel?.let { vm -> vm.message(id)?.let(vm::openMedia) } },
        onOpenLocation = { viewModel?.let { vm -> vm.message(id)?.let(vm::openLocation) } },
        onOpenLiveMap = { viewModel?.let { vm -> vm.message(id)?.let(vm::openLiveMap) } },
        onToggleVoice = { viewModel?.toggleVoice(id) },
        onLongPress = longPress,
        onPressedChange = { pressing -> if (viewModel != null) isPressing = pressing },
    )
}
