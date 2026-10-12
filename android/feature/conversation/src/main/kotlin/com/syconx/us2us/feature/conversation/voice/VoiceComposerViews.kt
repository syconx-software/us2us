package com.syconx.us2us.feature.conversation.voice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.SlideOffset
import com.syconx.us2us.domain.VoiceWaveform
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.bubbles.StateCrossFade
import com.syconx.us2us.ui.components.U2ButtonLabel
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.components.text.U2DisplayText
import com.syconx.us2us.ui.composer.ComposerVoiceMode
import com.syconx.us2us.ui.env.LocalU2Clock
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.l10n.U2Format
import com.syconx.us2us.ui.media.MediaGlyph
import com.syconx.us2us.ui.media.MediaGlyphIcon
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.u2PressEffect
import com.syconx.us2us.ui.theme.U2BackdropStyle
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2Backdrop
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

// 5.1 › mic: the recorder's views (proposed: the design draws the idle mic, not the recording).
// They extend the composer's own language: the field's capsule (`surface`, r22, hairline) holds the
// recording, the mic keeps its ring and glow, the menu material floats the lock and the hint,
// `danger` marks what records or stops, `accent300` what plays.

/**
 * The held mic's touch: press and hold, slide left to cancel, up to lock. Lies over the mic button
 * while the draft is empty. A screen reader's double tap starts a hands-free recording instead.
 * The finger is followed in root coordinates: the mic itself moves with it.
 */
@Composable
internal fun VoiceHoldArea(voice: VoiceComposer, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.common_a11y_composer_record)
    val hint = stringResource(R.string.conversation_voice_a11y_recordhint)
    val density = LocalDensity.current.density
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Box(
        modifier
            .testTag("voice.hold")
            .onGloballyPositioned { coordinates[0] = it }
            .pointerInput(voice) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val origin = coordinates[0]?.takeIf { it.isAttached }?.localToRoot(down.position) ?: down.position
                    var lifted = false
                    try {
                        voice.touchChanged(SlideOffset.Zero)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                lifted = true
                                voice.touchEnded()
                                break
                            }
                            val position = coordinates[0]?.takeIf { it.isAttached }?.localToRoot(change.position) ?: change.position
                            val moved = (position - origin) / density
                            voice.touchChanged(SlideOffset(moved.x, moved.y))
                            change.consume()
                        }
                    } finally {
                        if (!lifted) voice.touchFinished()
                    }
                }
            }
            .semantics {
                contentDescription = "$label. $hint"
                role = Role.Button
                onClick {
                    voice.startHandsFree()
                    true
                }
            },
    )
}

/**
 * While a voice message is recorded, this strip takes the field's place (and, while held, the
 * “+” button's): the pulsing dot, the time, the live waveform and “‹ İptal için kaydır”, which
 * slides and fades with the finger; locked, the stop button at its end; in review, play / pause,
 * the whole recording with what was listened to, and its length.
 */
@Composable
internal fun VoiceRecordingStrip(voice: VoiceComposer, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val shape = RoundedCornerShape(U2Radius.composerField)
    val mode = voice.mode
    Row(
        modifier
            .testTag("voice.strip")
            .fillMaxWidth()
            .height(U2Size.composer)
            .background(colors.surface, shape)
            .u2InnerStroke(colors.strokeField, 0.5.dp, shape)
            .padding(
                start = if (mode == ComposerVoiceMode.Review) 6.dp else 16.dp,
                end = if (mode == ComposerVoiceMode.Holding) 14.dp else 6.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (mode) {
            ComposerVoiceMode.Idle -> Unit
            ComposerVoiceMode.Holding -> {
                VoiceRecordingDot(voice.isCapturing)
                ElapsedLabel(voice)
                VoiceLevelBars(voice.levels, Modifier.weight(1f).heightIn(max = 24.dp).height(24.dp))
                SlideToCancel(voice)
            }
            ComposerVoiceMode.Locked -> {
                VoiceRecordingDot(voice.isCapturing)
                ElapsedLabel(voice)
                VoiceLevelBars(voice.levels, Modifier.weight(1f).height(24.dp))
                StopButton(voice)
            }
            ComposerVoiceMode.Review -> {
                PreviewButton(voice)
                VoiceLevelBars(voice.levels, Modifier.weight(1f).height(24.dp), wholePlayed = previewFraction(voice))
                ReviewTime(voice)
            }
        }
    }
}

@Composable
private fun ElapsedLabel(voice: VoiceComposer) {
    val time = U2Format.voiceDuration(floor(voice.elapsed.toDouble(DurationUnit.SECONDS)).seconds)
    val description = stringResource(R.string.conversation_voice_a11y_recording, time)
    U2Text(
        time,
        U2TextStyles.monoCountdownChip,
        Modifier.semantics { contentDescription = description },
        color = U2Theme.colors.text,
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
private fun SlideToCancel(voice: VoiceComposer) {
    val colors = U2Theme.colors
    val gesture = voice.gesture
    Row(
        Modifier
            .offset { IntOffset((max(-48f, gesture.translation.x * 0.5f) * density).roundToInt(), 0) }
            .graphicsLayer { alpha = (1 - 0.85 * gesture.cancelProgress).toFloat() }
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhosphorIcon(PhosphorGlyph.CaretLeft, 14.dp, tint = colors.textSecondary)
        U2Text(stringResource(R.string.conversation_voice_slidetocancel), U2TextStyles.preview, color = colors.textSecondary, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun StopButton(voice: VoiceComposer) {
    val colors = U2Theme.colors
    val label = stringResource(R.string.conversation_voice_stop)
    StripButton(onClick = voice::stop, label = label, ring = colors.dangerStrokeSoft) {
        MediaGlyphIcon(MediaGlyph.Stop, 13.dp, tint = colors.danger)
    }
}

@Composable
private fun PreviewButton(voice: VoiceComposer) {
    val colors = U2Theme.colors
    val playing = voice.player.activeId == VoiceComposer.PREVIEW_ID && voice.player.isPlaying
    val label = stringResource(if (playing) R.string.conversation_player_pause else R.string.conversation_voice_preview_play)
    StripButton(onClick = voice::togglePreview, label = label, ring = colors.accent) {
        StateCrossFade(playing) { isPlaying ->
            if (isPlaying) {
                MediaGlyphIcon(MediaGlyph.Pause, 13.dp, tint = colors.accent300)
            } else {
                PhosphorIcon(PhosphorGlyph.Play, 13.dp, weight = PhosphorWeight.Fill, tint = colors.accent300)
            }
        }
    }
}

/** A 32 dp round button with a 1 dp ring and the card press (0.98, 85 %). */
@Composable
private fun StripButton(onClick: () -> Unit, label: String, ring: Color, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .u2PressEffect(source, scale = 0.98f)
            .size(32.dp)
            .border(1.dp, ring, CircleShape)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick {
                    onClick()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

private fun previewFraction(voice: VoiceComposer): Double = voice.player.shownFraction(VoiceComposer.PREVIEW_ID, 0.0)

/** The length; while it is listened to, where it is. */
@Composable
private fun ReviewTime(voice: VoiceComposer) {
    val duration = voice.recording?.duration ?: voice.elapsed
    val active = voice.player.activeId == VoiceComposer.PREVIEW_ID
    val shown = if (active) duration * previewFraction(voice) else duration
    U2Text(
        U2Format.voiceDuration(shown),
        U2TextStyles.monoCountdownChip,
        Modifier.padding(end = 8.dp),
        color = U2Theme.colors.textSecondary,
        maxLines = 1,
        softWrap = false,
    )
}

/**
 * The red recording dot (8 dp `danger`), breathing (1.6 s) while the microphone records; still
 * under reduce motion and on a frozen clock, dimmed once recording stopped (the five-minute limit).
 */
@Composable
internal fun VoiceRecordingDot(isLive: Boolean, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val frozen = LocalU2Clock.current.isFrozen
    var alpha by remember { mutableFloatStateOf(1f) }
    val breathes = isLive && !reduceMotion && !frozen
    LaunchedEffect(breathes) {
        if (!breathes) return@LaunchedEffect
        while (true) {
            androidx.compose.runtime.withFrameMillis { millis ->
                val phase = millis / 1000.0 / 1.6 * 2 * PI
                alpha = (0.4 + 0.6 * (0.5 + 0.5 * cos(phase))).toFloat()
            }
        }
    }
    Box(
        modifier
            .size(8.dp)
            .graphicsLayer { this.alpha = if (breathes) alpha else if (isLive) 1f else 0.5f }
            .background(U2Theme.colors.danger, CircleShape)
            .clearAndSetSemantics {},
    )
}

/**
 * The recorder's waveform, drawn like the bubble's (bars 2.4 wide, round caps, a 4.4 pitch). Live
 * ([wholePlayed] `null`): the newest levels in `accent300`, filling from the leading edge, then
 * moving left as recording goes on. Whole: the recording fitted to the width, the listened part
 * `accent300`, the rest `text·38%`.
 */
@Composable
internal fun VoiceLevelBars(levels: List<Double>, modifier: Modifier = Modifier, wholePlayed: Double? = null) {
    val played = U2Theme.colors.accent300
    val rest = U2Theme.colors.text.copy(alpha = 0.38f)
    Canvas(modifier.clearAndSetSemantics {}) {
        val pitch = 4.4.dp.toPx()
        val bar = 2.4.dp.toPx()
        val capacity = max(1, ((size.width - bar) / pitch).toInt() + 1)
        val shown: List<Double>
        val playedCount: Int
        if (wholePlayed == null) {
            shown = levels.takeLast(capacity)
            playedCount = shown.size
        } else {
            shown = VoiceWaveform.resample(levels, capacity)
            playedCount = (shown.size * wholePlayed).roundToInt()
        }
        shown.forEachIndexed { index, level ->
            val x = bar / 2 + pitch * index
            val height = max(bar, level.coerceIn(0.08, 1.0).toFloat() * size.height)
            drawLine(
                if (index < playedCount) played else rest,
                Offset(x, (size.height - height) / 2),
                Offset(x, (size.height + height) / 2),
                strokeWidth = bar,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * Above the held mic: the lock to slide up to, a 40 dp capsule in the menu material holding
 * `ph:lock-simple` and an up caret, shrinking as the finger rises; the lock turns `accent300` just
 * before it locks.
 */
@Composable
internal fun VoiceLockHint(voice: VoiceComposer, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val progress = voice.gesture.lockProgress.toFloat()
    Column(
        modifier
            .testTag("voice.lock")
            .width(40.dp)
            .height((88 - 32 * progress).dp)
            .u2Backdrop(U2BackdropStyle.Menu, CircleShape)
            .u2InnerStroke(colors.strokeGlass, 0.5.dp, CircleShape)
            .clearAndSetSemantics {},
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PhosphorIcon(PhosphorGlyph.LockSimple, 18.dp, tint = if (progress > 0.85f) colors.accent300 else colors.textSecondary)
        MediaGlyphIcon(MediaGlyph.CaretUp, 14.dp, Modifier.graphicsLayer { alpha = 1f - progress }, tint = colors.textTertiary)
    }
}

/**
 * “Kaydetmek için basılı tut” (or “Şu anda kayıt yapılamıyor”, “Bu sesli mesaj oynatılamıyor”,
 * “Sesli mesaj gönderilemedi”) floating above the mic for two seconds: a capsule in the menu
 * material, `caption` in `text`. Screen readers hear it as it appears.
 */
@Composable
internal fun VoiceHintBubble(hint: VoiceComposer.Hint, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val text = stringResource(
        when (hint) {
            VoiceComposer.Hint.Hold -> R.string.conversation_voice_hint_hold
            VoiceComposer.Hint.Unavailable -> R.string.conversation_voice_hint_unavailable
            VoiceComposer.Hint.Unplayable -> R.string.conversation_voice_hint_unplayable
            VoiceComposer.Hint.SendFailed -> R.string.conversation_voice_hint_sendfailed
        },
    )
    Box(
        modifier
            .testTag("voice.hint")
            .u2Backdrop(U2BackdropStyle.Menu, CircleShape)
            .u2InnerStroke(colors.strokeGlass, 0.5.dp, CircleShape)
            .padding(vertical = 8.dp, horizontal = 14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        U2Text(text, U2TextStyles.caption, color = colors.text, maxLines = 1, softWrap = false)
    }
}

/**
 * The microphone is refused (the floating sheet of 5.2's notices): why it is needed (only while
 * recording, end-to-end encrypted) and “Ayarları aç”.
 */
@Composable
internal fun VoicePermissionSheet(onOpenSettings: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    Column(modifier.padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            U2DisplayText(R.string.conversation_voice_permission_title, U2TextStyles.displaySheetTitle.balanced)
            U2Text(stringResource(R.string.conversation_voice_permission_body), U2TextStyles.sheetBody, color = colors.textSecondary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            U2PrimaryButton(onClick = {
                onClose()
                onOpenSettings()
            }, modifier = Modifier.testTag("voice.permission.settings")) {
                U2ButtonLabel(stringResource(R.string.conversation_voice_permission_settings), glyph = PhosphorGlyph.GearSix)
            }
            U2TextButton(
                onClick = onClose,
                modifier = Modifier.testTag("voice.permission.close"),
                tone = U2TextButtonTone.Accent,
                height = U2Size.textButtonS,
            ) { U2ButtonLabel(stringResource(R.string.common_cancel)) }
        }
    }
}
