package com.syconx.us2us.ui.bubbles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.Reaction
import com.syconx.us2us.domain.ViewOnceState
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.media.SavedChip
import com.syconx.us2us.ui.media.SavedMediaChip
import com.syconx.us2us.ui.media.rememberClockTime
import com.syconx.us2us.ui.media.showsBubbleChips
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.u2Spec
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One message as a C15 bubble, with the conversation's interactions: tap a photo (viewer), a
 * video (player), a file (preview), a view-once bubble (5.6), a place (open in maps), a running
 * live location (the map), “Durdur” in a live location (5.2). A live location switches to its
 * ended state by itself. The reaction and “Kaydetti” chips sit 12 dp in from the bubble's leading
 * edge on both sides (an outgoing bubble's trailing corner holds the time and the state), and the
 * bubble keeps 14 dp free below for them. A built-in sticker has no bubble.
 *
 * A change of kind (text → “herkesten silindi” trace, live → ended, view once → opened) morphs
 * when the column passes the message it is [replacing]: the old bubble shrinks a touch and fades
 * while the new one fades in, and the box between them changes size frame by frame
 * (bottom-aligned), so the rows above glide instead of jumping; [onReplaced] follows.
 *
 * [onLongPress] opens the 5.3 actions (null: none, as for system lines); it shares one gesture
 * with the bubble's own tap so neither swallows the other, and [onPressedChange] reports the
 * finger resting on the bubble (the 5.3 lift).
 */
@Composable
fun MessageBubbleView(
    message: Message,
    modifier: Modifier = Modifier,
    isTail: Boolean = false,
    animatesEntrance: Boolean = false,
    replacing: Message? = null,
    onReplaced: () -> Unit = {},
    onStopLiveLocation: () -> Unit = {},
    onOpenViewOnce: () -> Unit = {},
    onOpenMedia: () -> Unit = {},
    onOpenLocation: () -> Unit = {},
    onOpenLiveMap: () -> Unit = {},
    onToggleVoice: () -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    onPressedChange: (Boolean) -> Unit = {},
) {
    val reduceMotion = LocalReduceMotion.current
    val key = replacing?.let { "${it.id}|${it.contentKind}" }
    var finished by remember { mutableStateOf<String?>(null) }
    val morph = remember { Animatable(0f) }
    val currentOnReplaced by rememberUpdatedState(onReplaced)
    val previous = if (key != null && key != finished) replacing else null
    LaunchedEffect(key) {
        if (key == null || key == finished) return@LaunchedEffect
        morph.snapTo(0f)
        morph.animateTo(1f, U2Motion.resolve(U2Motion.state, reduceMotion).spec())
        finished = key
        morph.snapTo(0f)
        currentOnReplaced()
    }
    val progress: () -> Float = { if (previous == null) 1f else morph.value }
    val handlers = BubbleHandlers(
        message, animatesEntrance, onStopLiveLocation, onOpenViewOnce, onOpenMedia, onOpenLocation, onOpenLiveMap, onToggleVoice, onLongPress, onPressedChange,
    )
    val space by animateDpAsState(if (message.showsBubbleChips) 14.dp else 0.dp, u2Spec(U2Motion.state), label = "chipSpace")
    ChipsLayout(
        modifier = modifier.padding(bottom = space),
        bubble = {
            KindMorphLayout(progress, alignsEnd = message.isOutgoing) {
                Box(Modifier.graphicsLayer { alpha = progress() }) { BubbleContent(message, isTail, handlers) }
                if (previous != null) {
                    val origin = TransformOrigin(if (message.isOutgoing) 1f else 0f, 1f)
                    Box(
                        Modifier
                            .graphicsLayer {
                                val p = progress()
                                alpha = 1f - p
                                val scale = if (reduceMotion) 1f else 1f - 0.06f * p
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = origin
                            }
                            .clearAndSetSemantics {},
                    ) { BubbleContent(previous, isTail, BubbleHandlers.Inert) }
                }
            }
        },
        chips = { BubbleChips(message) },
    )
}

/** The callbacks a bubble's content uses; [Inert] for the fading copy of a morph. */
private class BubbleHandlers(
    val message: Message?,
    val animatesEntrance: Boolean,
    val onStopLiveLocation: () -> Unit,
    val onOpenViewOnce: () -> Unit,
    val onOpenMedia: () -> Unit,
    val onOpenLocation: () -> Unit,
    val onOpenLiveMap: () -> Unit,
    val onToggleVoice: () -> Unit,
    val onLongPress: (() -> Unit)?,
    val onPressedChange: (Boolean) -> Unit,
) {
    companion object {
        val Inert = BubbleHandlers(null, false, {}, {}, {}, {}, {}, {}, null, {})
    }
}

@Composable
private fun BubbleContent(message: Message, isTail: Boolean, handlers: BubbleHandlers) {
    val inert = handlers.message == null
    val longPress = if (inert) null else handlers.onLongPress
    when (val content = message.content) {
        is MessageContent.Text -> {
            val gestures = Modifier.textBubbleGestures(longPress, handlers.onPressedChange)
            val quote = message.replyTo
            if (quote != null) {
                ReplyBubble(message, content.text, quote, gestures, isTail)
            } else {
                TextBubble(message, content.text, gestures, isTail)
            }
        }
        is MessageContent.Photo -> {
            // A photo still being prepared here (or that failed to be) opens once it is sent.
            val open = if (message.isPendingMedia || inert) null else handlers.onOpenMedia
            PhotoBubble(
                message,
                content.source,
                content.caption,
                Modifier.bubbleGestures(open, stringResource(R.string.common_a11y_media_open), longPress, handlers.onPressedChange),
                isTail,
            )
        }
        is MessageContent.Video -> {
            val open = if (message.isPendingMedia || inert) null else handlers.onOpenMedia
            val label = if (message.mediaAvailability == MediaAvailability.Remote) {
                stringResource(R.string.conversation_file_download)
            } else {
                stringResource(R.string.conversation_player_play)
            }
            VideoBubble(
                message,
                content.source,
                content.duration,
                content.byteCount,
                Modifier.bubbleGestures(open, label, longPress, handlers.onPressedChange),
                isTail,
            )
        }
        is MessageContent.Voice -> VoiceBubble(
            message,
            content.duration,
            content.waveform,
            content.playedFraction,
            Modifier.bubbleGestures(null, null, longPress, handlers.onPressedChange),
            isTail,
            onPlay = if (inert) ({}) else handlers.onToggleVoice,
        )
        is MessageContent.File -> {
            val label = if (message.mediaAvailability == MediaAvailability.Remote) {
                stringResource(R.string.conversation_file_download)
            } else {
                stringResource(R.string.common_a11y_media_open)
            }
            FileBubble(
                message,
                content.name,
                content.byteCount,
                content.typeLabel,
                content.pageCount,
                Modifier.bubbleGestures(if (inert) null else handlers.onOpenMedia, label, longPress, handlers.onPressedChange),
                isTail,
            )
        }
        is MessageContent.Location -> LocationBubble(
            message,
            content.name,
            content.locality,
            Modifier.bubbleGestures(
                if (inert) null else handlers.onOpenLocation,
                stringResource(R.string.conversation_location_open_title),
                longPress,
                handlers.onPressedChange,
            ),
            isTail,
        )
        is MessageContent.LiveLocation -> LiveLocationContent(message, content, isTail, handlers, longPress)
        is MessageContent.TextSticker -> TextStickerMessage(message, content.text, Modifier.bubbleGestures(null, null, longPress, handlers.onPressedChange))
        is MessageContent.Sticker -> BrandStickerMessage(
            message,
            content.packId,
            content.stickerId,
            content.emoji,
            content.lang,
            Modifier.bubbleGestures(null, null, longPress, handlers.onPressedChange),
            animatesEntrance = handlers.animatesEntrance && handlers.message?.id == message.id,
        )
        is MessageContent.ViewOnce -> {
            val open = if (content.state == ViewOnceState.Unopened && !inert) handlers.onOpenViewOnce else null
            ViewOnceBubble(
                message,
                content.isVideo,
                content.state,
                Modifier.bubbleGestures(open, null, longPress, handlers.onPressedChange),
                isTail,
            )
        }
        MessageContent.Deleted -> TombstoneBubble(message, Modifier.bubbleGestures(null, null, longPress, handlers.onPressedChange), isTail)
        is MessageContent.System -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SystemEventPill(content.event) }
    }
}

/**
 * Live location: active (the countdown and “Durdur”; a tap opens the map) until it is stopped or
 * runs out, then the ended state (5.2 › States), which opens nothing. Switches by itself when the
 * time is up (checked every 30 s).
 */
@Composable
private fun LiveLocationContent(
    message: Message,
    content: MessageContent.LiveLocation,
    isTail: Boolean,
    handlers: BubbleHandlers,
    longPress: (() -> Unit)?,
) {
    val now = rememberClockTime(intervalMillis = 30_000)
    val running = content.isActive && content.expiresAt.isAfter(now)
    val mapLabel = stringResource(R.string.conversation_a11y_livelocation_map)
    StateCrossFade(running) { active ->
        if (active) {
            LiveLocationBubble(
                message,
                content.expiresAt,
                isActive = true,
                modifier = Modifier.bubbleGestures(
                    if (handlers.message == null) null else handlers.onOpenLiveMap,
                    mapLabel,
                    longPress,
                    handlers.onPressedChange,
                ),
                isTail = isTail,
                onStop = handlers.onStopLiveLocation,
            )
        } else {
            EndedLiveLocationBubble(message, Modifier.bubbleGestures(null, null, longPress, handlers.onPressedChange), isTail)
        }
    }
}

/** The reaction and “Kaydetti” chips, popping in and out. */
@Composable
private fun BubbleChips(message: Message) {
    val reaction = message.reactions.firstOrNull()
    var lastReaction by remember { mutableStateOf<Reaction?>(reaction) }
    if (reaction != null) lastReaction = reaction
    val pop = U2Transitions.pop()
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(reaction != null, enter = pop.enter, exit = pop.exit) {
            lastReaction?.let { ReactionChip(it) }
        }
        AnimatedVisibility(SavedMediaChip.isShown(message), enter = pop.enter, exit = pop.exit) { SavedChip() }
    }
}

/** The bubble, with [chips] hanging 12 dp in from its leading edge, 15 dp over its bottom (never changing its size). */
@Composable
private fun ChipsLayout(modifier: Modifier, bubble: @Composable () -> Unit, chips: @Composable () -> Unit) {
    Layout(contents = listOf(bubble, chips), modifier = modifier) { (bubbleMeasurables, chipMeasurables), constraints ->
        val main = bubbleMeasurables.first().measure(constraints)
        val hanging = chipMeasurables.map { it.measure(Constraints()) }
        val inset = 12.dp.roundToPx()
        val overlap = 15.dp.roundToPx()
        layout(main.width, main.height) {
            main.place(0, 0)
            hanging.forEach { it.placeRelative(inset, main.height - it.height + overlap) }
        }
    }
}

/**
 * The morph's box: the current bubble's size, or (while a previous bubble is still fading out)
 * the size between the two ([progress] 0 = the old one's, 1 = the new one's). Every child sits on
 * the bottom edge, at the bubble's side.
 */
@Composable
private fun KindMorphLayout(progress: () -> Float, alignsEnd: Boolean, content: @Composable () -> Unit) {
    Layout(content) { measurables: List<Measurable>, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val to = placeables.firstOrNull()
        val from = placeables.getOrNull(1)
        val t = progress().coerceIn(0f, 1f)
        val width = when {
            to == null -> 0
            from == null || t >= 1f -> to.width
            else -> (from.width + (to.width - from.width) * t).roundToInt()
        }
        val height = when {
            to == null -> 0
            from == null || t >= 1f -> to.height
            else -> (from.height + (to.height - from.height) * t).roundToInt()
        }
        val w = max(width, constraints.minWidth)
        val h = max(height, constraints.minHeight)
        layout(w, h) {
            placeables.forEach { p -> p.placeRelative(if (alignsEnd) w - p.width else 0, h - p.height) }
        }
    }
}

/** Observe the initial pass so a child text link can handle taps without swallowing message long presses. */
@Composable
private fun Modifier.textBubbleGestures(onLongPress: (() -> Unit)?, onPressedChange: (Boolean) -> Unit): Modifier {
    if (onLongPress == null) return this
    val longPress by rememberUpdatedState(onLongPress)
    val pressed by rememberUpdatedState(onPressedChange)
    val label = stringResource(R.string.conversation_a11y_message_actions)
    return semantics { onLongClick(label) { longPress?.invoke(); true } }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressed(true)
                try {
                    val finished = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var tracking = true
                        while (tracking) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            tracking = event.changes.count { it.pressed } <= 1 && change != null && change.pressed &&
                                (change.position - down.position).getDistance() <= viewConfiguration.touchSlop
                            // A scroll or a child control can own the movement even while it is
                            // within our slop. Observe after the Main pass before keeping the press.
                            val settled = awaitPointerEvent(PointerEventPass.Final)
                            if (settled.changes.any { it.isConsumed && it.positionChangeIgnoreConsumed() != Offset.Zero }) {
                                tracking = false
                            }
                        }
                        true
                    }
                    if (finished == null) {
                        longPress?.invoke()
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                } finally { pressed(false) }
            }
        }
}

/** The media bubble's own tap and message long press share one gesture. */
@Composable
private fun Modifier.bubbleGestures(
    onTap: (() -> Unit)?,
    tapLabel: String?,
    onLongPress: (() -> Unit)?,
    onPressedChange: (Boolean) -> Unit,
): Modifier {
    if (onTap == null && onLongPress == null) return this
    val tap by rememberUpdatedState(onTap)
    val withLongPress = textBubbleGestures(onLongPress, onPressedChange)
    if (onTap == null) return withLongPress
    return withLongPress
        .semantics {
            onClick(tapLabel) { tap?.invoke(); true }
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { tap?.invoke() },
            )
        }
}
