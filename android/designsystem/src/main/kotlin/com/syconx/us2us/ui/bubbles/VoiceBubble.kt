package com.syconx.us2us.ui.bubbles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.VoicePlaybackRate
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.l10n.U2Format
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.media.MediaGlyph
import com.syconx.us2us.ui.media.MediaGlyphIcon
import com.syconx.us2us.ui.media.MediaProgressRing
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.u2PressEffect
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The one voice message that plays in the open conversation, as the voice bubbles read it
 * (iOS `VoicePlayback` in the environment of 5.1). Its properties are snapshot state, so a bubble
 * redraws when they change; only the active message's bubble reads the live position.
 */
@Stable
interface VoicePlaybackState {
    /** The message playing, paused, or being loaded. */
    val activeId: MessageId?
    val isPlaying: Boolean

    /** The active message's bytes are being downloaded or decrypted. */
    val isLoading: Boolean

    /** A finger drags the active message's position. */
    val isScrubbing: Boolean

    /** The active message's length, once it is loaded (zero before). */
    val duration: Duration

    /** Where the active message is. */
    val currentTime: Duration
    val rate: VoicePlaybackRate

    /** What the bubble of [id] shows as played: the live position while it is active, where it was left in this visit, or [fallback]. */
    fun shownFraction(id: MessageId, fallback: Double): Double

    fun beginScrub(id: MessageId)

    fun scrub(id: MessageId, fraction: Double)

    fun endScrub(id: MessageId)

    /** Moves the active message by [by] (screen readers: five seconds). */
    fun skip(id: MessageId, by: Duration)

    /** 1× → 1,5× → 2× → 1×. */
    fun cycleRate()
}

/** The conversation's voice player; `null` outside 5.1 (4.4, previews): the bubbles show their stored state. */
val LocalVoicePlayback = staticCompositionLocalOf<VoicePlaybackState?> { null }

/** The design's waveform box. */
private val WaveformSize = DpSize(132.dp, 28.dp)

private enum class PlayGlyph { Play, Pause, Download, Loading, Unavailable }

/**
 * A voice message: padding 9 12 8 9: the play button (36, 1 dp `accent`, `ph:play` fill 15
 * `accent300`), the 132 × 28 waveform and the length (Mono 11).
 *
 * Playing (proposed beyond the design's paused bubble, with [LocalVoicePlayback]): the button
 * pauses, the played part of the waveform grows in `accent300`, a sideways drag on the waveform
 * scrubs and a tap on it jumps there, the time says where it is, and a chip under the button
 * switches 1× · 1,5× · 2×. Still on the relay: the download glyph; downloading or decrypting: the
 * ring; held or gone: the warning, disabled. Not played yet (incoming): a 6 dp `accent300` dot
 * before the time; played by the other side (outgoing): `ph:microphone` 13 in `accent300`.
 * Overlays and the time row only: the bubble keeps its size.
 */
@Composable
fun VoiceBubble(
    message: Message,
    duration: Duration,
    waveform: List<Double>,
    playedFraction: Double,
    modifier: Modifier = Modifier,
    isTail: Boolean = false,
    onPlay: () -> Unit = {},
) {
    val playback = LocalVoicePlayback.current
    val isActive = playback?.activeId == message.id
    SpeedChipLayout(
        modifier = modifier,
        bubble = {
            MessageBubble(
                message.direction,
                isTail = isTail,
                padding = PaddingValues(start = 9.dp, top = 9.dp, end = 12.dp, bottom = 8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    VoicePlayButton(message, duration, playback, isActive, onPlay)
                    VoiceBubbleWaveform(message.id, waveform, playedFraction, isActive, playback)
                    VoiceBubbleTime(duration, isActive, playback)
                }
                Row(
                    Modifier.bubbleTrailing(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VoicePlayedMark(message)
                    BubbleMeta(message.sentAt, status = message.status, disappearing = message.disappearing)
                }
            }
        },
        chip = {
            val fade = U2Transitions.fade(U2Motion.micro)
            AnimatedVisibility(isActive && playback != null, enter = fade.enter, exit = fade.exit) {
                if (playback != null) SpeedChip(playback, rowHeight = if (message.status == null) 11.5f else 15f)
            }
        },
    )
}

/** The bubble, with the speed chip hanging at its bottom-left (it never changes the bubble's size). */
@Composable
private fun SpeedChipLayout(modifier: Modifier, bubble: @Composable () -> Unit, chip: @Composable () -> Unit) {
    Layout(contents = listOf(bubble, chip), modifier = modifier) { (bubbleMeasurables, chipMeasurables), constraints ->
        val main = bubbleMeasurables.first().measure(constraints)
        val chips = chipMeasurables.map { it.measure(Constraints()) }
        layout(main.width, main.height) {
            main.place(0, 0)
            chips.forEach { it.place(0, main.height - it.height) }
        }
    }
}

@Composable
private fun VoicePlayButton(message: Message, duration: Duration, playback: VoicePlaybackState?, isActive: Boolean, onPlay: () -> Unit) {
    val colors = U2Theme.colors
    val availability = message.mediaAvailability
    val glyph = when {
        isActive && playback != null -> when {
            playback.isLoading -> PlayGlyph.Loading
            playback.isPlaying -> PlayGlyph.Pause
            else -> PlayGlyph.Play
        }
        availability == MediaAvailability.Ready -> PlayGlyph.Play
        availability == MediaAvailability.Remote -> PlayGlyph.Download
        availability is MediaAvailability.Downloading -> PlayGlyph.Loading
        else -> PlayGlyph.Unavailable
    }
    val downloaded = (availability as? MediaAvailability.Downloading)?.fraction
    val label = when (glyph) {
        PlayGlyph.Pause -> stringResource(R.string.conversation_voice_pause)
        PlayGlyph.Download -> stringResource(R.string.conversation_file_download)
        PlayGlyph.Loading -> stringResource(R.string.conversation_voice_loading)
        PlayGlyph.Play, PlayGlyph.Unavailable -> stringResource(R.string.common_a11y_voice_play)
    }
    val length = U2Format.voiceDuration(duration)
    val value = when {
        availability != MediaAvailability.Ready -> "$length, ${mediaStateDescription(availability)}"
        message.isOutgoing && message.isVoicePlayed -> stringResource(R.string.conversation_voice_a11y_played, length)
        !message.isOutgoing && !message.isVoicePlayed -> stringResource(R.string.conversation_voice_a11y_unplayed, length)
        else -> length
    }
    val enabled = glyph != PlayGlyph.Unavailable
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val ink = if (glyph == PlayGlyph.Unavailable) colors.textTertiary else colors.accent300
    Box(
        Modifier
            .u2PressEffect(source, scale = 0.98f)
            .alpha(if (pressed) 0.85f else 1f)
            .size(36.dp)
            .u2InnerStroke(colors.accent, 1.dp, CircleShape)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onPlay)
            .semantics {
                contentDescription = label
                stateDescription = value
            },
        contentAlignment = Alignment.Center,
    ) {
        StateCrossFade(glyph) { shown ->
            when (shown) {
                PlayGlyph.Play -> PhosphorIcon(PhosphorGlyph.Play, 15.dp, weight = PhosphorWeight.Fill, tint = ink)
                PlayGlyph.Pause -> MediaGlyphIcon(MediaGlyph.Pause, 14.dp, tint = ink)
                PlayGlyph.Download -> MediaGlyphIcon(MediaGlyph.Download, 16.dp, tint = ink)
                PlayGlyph.Loading -> MediaProgressRing(
                    downloaded,
                    Modifier.size(36.dp).padding(9.dp),
                    tint = colors.accent300,
                    track = colors.accent300.copy(alpha = 0.25f),
                )
                PlayGlyph.Unavailable -> PhosphorIcon(PhosphorGlyph.WarningCircle, 16.dp, tint = ink)
            }
        }
    }
}

/**
 * A voice bubble's waveform with what is played: while its message is the active one the live
 * position; a sideways drag on it scrubs (a vertical one is left to the column), a tap jumps
 * there, a screen reader moves it by five seconds.
 */
@Composable
private fun VoiceBubbleWaveform(id: MessageId, bars: List<Double>, fallback: Double, isActive: Boolean, playback: VoicePlaybackState?) {
    val fraction = playback?.shownFraction(id, fallback) ?: fallback
    val current by rememberUpdatedState(playback)
    val positionLabel = stringResource(R.string.conversation_player_position)
    val time = U2Format.voiceDuration(playback?.currentTime ?: Duration.ZERO)
    val gestures = if (isActive && playback != null) {
        Modifier.pointerInput(id) {
            val slop = 6.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var scrubbing = false
                var last = down.position
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    last = change.position
                    val moved = change.position - down.position
                    if (!scrubbing && (abs(moved.x) > slop || abs(moved.y) > slop)) {
                        // Only sideways: a scroll that begins on the waveform never seeks.
                        if (abs(moved.x) > abs(moved.y)) {
                            scrubbing = true
                            current?.beginScrub(id)
                        } else {
                            break
                        }
                    }
                    if (scrubbing) {
                        change.consume()
                        current?.scrub(id, fractionAt(change.position, size.width.toFloat()))
                    }
                    if (!change.pressed) {
                        if (scrubbing) {
                            current?.endScrub(id)
                        } else if (change.positionChange() == Offset.Zero || moved.getDistance() <= slop) {
                            current?.scrub(id, fractionAt(last, size.width.toFloat()))
                        }
                        return@awaitEachGesture
                    }
                }
                // The column took the drag away: the scrub ends here, so the player is not left paused.
                if (scrubbing && current?.isScrubbing == true) current?.endScrub(id)
            }
        }
    } else {
        Modifier
    }
    Waveform(
        bars,
        fraction,
        Modifier
            .testTag("voice.waveform")
            .size(WaveformSize)
            .then(gestures)
            .semantics {
                if (!isActive) {
                    invisibleToUser()
                } else {
                    contentDescription = positionLabel
                    stateDescription = time
                    customActions = listOf(
                        CustomAccessibilityAction("+5") { current?.skip(id, 5.seconds); true },
                        CustomAccessibilityAction("−5") { current?.skip(id, (-5).seconds); true },
                    )
                }
            },
    )
}

private fun fractionAt(position: Offset, width: Float): Double = if (width <= 0f) 0.0 else (position.x / width).toDouble().coerceIn(0.0, 1.0)

/** A voice bubble's time: the length, or (while its message is the active one) where it is. */
@Composable
private fun VoiceBubbleTime(duration: Duration, isActive: Boolean, playback: VoicePlaybackState?) {
    val shown = if (isActive && playback != null && playback.duration.isPositive()) playback.currentTime else duration
    U2Text(
        U2Format.voiceDuration(shown),
        U2TextStyles.monoVoiceDuration,
        Modifier.testTag("voice.time").clearAndSetSemantics {},
        color = U2Theme.colors.textSecondary,
        maxLines = 1,
        softWrap = false,
    )
}

/** Played by the other side (outgoing): the microphone; not played yet (incoming): the dot. */
@Composable
private fun VoicePlayedMark(message: Message) {
    val colors = U2Theme.colors
    val fade = U2Transitions.fade()
    if (message.isOutgoing) {
        AnimatedVisibility(message.isVoicePlayed, enter = fade.enter, exit = fade.exit) {
            PhosphorIcon(PhosphorGlyph.Microphone, 13.dp, tint = colors.accent300)
        }
    } else {
        AnimatedVisibility(!message.isVoicePlayed && message.mediaAvailability != MediaAvailability.Unavailable, enter = fade.enter, exit = fade.exit) {
            Box(Modifier.size(6.dp).background(colors.accent300, CircleShape).clearAndSetSemantics {})
        }
    }
}

/** 1× · 1,5× · 2× under the play button, centred on the time row. */
@Composable
private fun SpeedChip(playback: VoicePlaybackState, rowHeight: Float) {
    val colors = U2Theme.colors
    val label = playback.rate.label(currentAppLocale())
    val name = stringResource(R.string.conversation_voice_speed)
    // “1,5 kat hız”, not “1,5 multiplied by”.
    val value = stringResource(R.string.conversation_voice_speed_value, label.replace("×", ""))
    Box(
        Modifier
            .offset(x = (9 - 6).dp, y = (-(8 + (rowHeight - 16) / 2 - 6)).dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = playback::cycleRate)
            .semantics {
                contentDescription = name
                stateDescription = value
            }
            .padding(6.dp),
    ) {
        Box(Modifier.size(36.dp, 16.dp).background(colors.fileIconFill, CircleShape), contentAlignment = Alignment.Center) {
            U2Text(label, U2TextStyles.monoTimerInBubble, Modifier.clearAndSetSemantics {}, color = colors.accent300, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * The voice waveform: bars 2.4 wide with round caps at a 4.4 pitch, centred vertically; the
 * played part `accent300`, the rest `text·38%`. More bars than fit at that pitch are drawn closer
 * together. [bars] are heights as fractions of the box (28 dp in the design).
 */
@Composable
fun Waveform(bars: List<Double>, playedFraction: Double, modifier: Modifier = Modifier) {
    val played = U2Theme.colors.accent300
    val rest = U2Theme.colors.text.copy(alpha = 0.38f)
    Canvas(modifier.clearAndSetSemantics {}) {
        val count = bars.size
        val playedCount = (count * playedFraction).roundToInt()
        val scale = size.height / 28.dp.toPx()
        val unit = 1.dp.toPx() * scale
        val pitch = if (count > 1) min(4.4f * unit, (size.width - 4f * unit) / (count - 1)) else 0f
        bars.forEachIndexed { index, height ->
            val x = 2f * unit + pitch * index
            val h = height.toFloat() * size.height
            drawLine(
                if (index < playedCount) played else rest,
                start = Offset(x, (size.height - h) / 2f),
                end = Offset(x, (size.height + h) / 2f),
                strokeWidth = 2.4f * unit,
                cap = StrokeCap.Round,
            )
        }
    }
}
