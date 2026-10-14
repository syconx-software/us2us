package com.syconx.us2us.ui.composer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.QuotedMessage
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.bubbles.ReplyQuote
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.u2PressEffect
import com.syconx.us2us.ui.motion.u2Spec
import com.syconx.us2us.ui.theme.U2BoxShadows
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.composerFade
import com.syconx.us2us.ui.theme.u2BoxShadow
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import com.syconx.us2us.ui.type.textStyle
import kotlin.math.roundToInt

/** What the voice recorder behind the mic is doing. */
enum class ComposerVoiceMode {
    Idle,

    /** Recording while the finger holds the mic. */
    Holding,

    /** Recording hands-free. */
    Locked,

    /** Stopped: the recording waits to be listened to, deleted or sent. */
    Review,
}

/**
 * The voice recorder behind the composer's mic (5.1 › mic, proposed; iOS `VoiceComposer` and its
 * views). The conversation implements it; the composer only places what it draws: the
 * recording strip in place of the field, the hold area over the mic, the lock above the held
 * mic and the hint above the row. Its properties are snapshot state.
 */
@Stable
interface ComposerVoice {
    val mode: ComposerVoiceMode

    /** A hint floats above the mic (“Kaydetmek için basılı tut” …). */
    val showsHint: Boolean

    /** Where the held mic is drawn: it follows the finger, left and up. */
    val micOffset: DpOffset

    /** Released, or “Gönder”: the recording goes to the conversation. */
    fun send()

    /** Slid away, or “Sil”: the recording is gone. */
    fun cancel()

    /** The recording strip, laid behind the row (the field and, while held, “+” are hidden). */
    @Composable
    fun RecordingStrip(modifier: Modifier)

    /** The press-and-hold area over the mic (while the draft is empty). */
    @Composable
    fun HoldArea(modifier: Modifier)

    /** The lock to slide up to, above the held mic. */
    @Composable
    fun LockHint(modifier: Modifier)

    /** The hint bubble above the row's end. */
    @Composable
    fun HintBubble(modifier: Modifier)
}

/**
 * C21: the chat composer, floating over the bottom of the conversation: 94 high in the design
 * (padding 14 12 36 12) on `composerFade`; the row (gap 8, bottom-aligned): “+” (C4 44,
 * `ph:plus` 22) · the field (min 44, r22, `surface`, hairline 0.5 dp `text·14%`, padding
 * 0 12 0 16, placeholder “Mesaj” `text·50%`, `ph:timer` and `ph:sticker` 21 in `text·55%`, gap 12)
 * · the mic (44, 1 dp `accent`, `ph:microphone` 21 `accent300`, glow). The field grows to 5 lines;
 * with text the mic becomes send (proposed).
 *
 * Voice messages (proposed; the design draws only the idle mic): with a [voice] recorder,
 * pressing and holding the mic records. Its strip then takes the place of the field and of “+”;
 * the mic fills with `accent`, grows a touch and follows the finger, the lock floats above it.
 * Locked or in review, “+” becomes “Sil” and the mic “Gönder”.
 *
 * [bottomPadding] is the space under the row; `null` is the design's 36 dp above the physical
 * bottom (the navigation bar plus 2 dp, as the home indicator is 34).
 */
@Composable
fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    quote: QuotedMessage? = null,
    onCancelQuote: () -> Unit = {},
    onAttach: () -> Unit = {},
    onTimer: () -> Unit = {},
    onSticker: () -> Unit = {},
    onRecord: () -> Unit = {},
    bottomPadding: Dp? = null,
    voice: ComposerVoice? = null,
) {
    val colors = U2Theme.colors
    val hasText = text.isNotBlank()
    val mode = voice?.mode ?: ComposerVoiceMode.Idle
    val bottom = bottomPadding ?: (WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 36.dp - U2Size.designHomeIndicator)
    var shownQuote by remember { mutableStateOf(quote) }
    if (quote != null) shownQuote = quote
    Column(
        modifier
            .background(colors.composerFade)
            .padding(start = U2Spacing.chatComposerGutter, top = 14.dp, end = U2Spacing.chatComposerGutter, bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val rise = U2Transitions.rise()
        AnimatedVisibility(quote != null, enter = rise.enter, exit = rise.exit) {
            Row(
                Modifier.padding(start = 52.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                shownQuote?.let { ReplyQuote(it, Modifier.weight(1f)) }
                GlassIconButton(
                    PhosphorGlyph.X,
                    label = stringResource(R.string.common_a11y_reply_cancel),
                    diameter = U2Size.iconButtonSheet,
                    glyphSize = 17.dp,
                    onClick = onCancelQuote,
                )
            }
        }
        RowWithVoiceOverlays(
            strip = {
                // Held: in place of “+” and the field; locked or in review the strip leaves “+” (now “Sil”) free.
                if (voice != null && mode != ComposerVoiceMode.Idle) {
                    voice.RecordingStrip(
                        Modifier.padding(
                            start = if (mode == ComposerVoiceMode.Holding) 0.dp else U2Size.iconButton + 8.dp,
                            end = U2Size.iconButton + 8.dp,
                        ),
                    )
                }
            },
            row = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    LeadingButton(mode, voice, onAttach)
                    ComposerField(text, onTextChange, mode, onTimer, onSticker, Modifier.weight(1f))
                    ComposerActionButton(hasText, voice, onSend, onRecord)
                }
            },
            hint = {
                val fade = U2Transitions.rise()
                AnimatedVisibility(voice?.showsHint == true, enter = fade.enter, exit = fade.exit) { voice?.HintBubble(Modifier) }
            },
        )
    }
}

/** The row with the recording strip behind it (its size) and the hint 10 dp above its end. */
@Composable
private fun RowWithVoiceOverlays(strip: @Composable () -> Unit, row: @Composable () -> Unit, hint: @Composable () -> Unit) {
    Layout(contents = listOf(strip, row, hint)) { (stripMeasurables, rowMeasurables, hintMeasurables), constraints ->
        val main = rowMeasurables.first().measure(constraints)
        val strips = stripMeasurables.map { it.measure(Constraints.fixed(main.width, main.height)) }
        val hints = hintMeasurables.map { it.measure(Constraints()) }
        val gap = 10.dp.roundToPx()
        layout(main.width, main.height) {
            strips.forEach { it.place(0, 0) }
            main.place(0, 0)
            hints.forEach { it.place(main.width - it.width, -(it.height + gap)) }
        }
    }
}

/** “+”, or (a recording locked or in review) “Sil” in `danger`. */
@Composable
private fun LeadingButton(mode: ComposerVoiceMode, voice: ComposerVoice?, onAttach: () -> Unit) {
    val idle = mode == ComposerVoiceMode.Idle
    Box {
        GlassIconButton(
            PhosphorGlyph.Plus,
            label = stringResource(R.string.common_a11y_composer_attach),
            glyphSize = 22.dp,
            modifier = Modifier
                .alpha(if (idle) 1f else 0f)
                .semantics { if (!idle) invisibleToUser() },
            enabled = idle,
            onClick = onAttach,
        )
        val fade = U2Transitions.fade()
        AnimatedVisibility(
            voice != null && (mode == ComposerVoiceMode.Locked || mode == ComposerVoiceMode.Review),
            enter = fade.enter,
            exit = fade.exit,
        ) {
            GlassIconButton(
                PhosphorGlyph.Trash,
                label = stringResource(R.string.conversation_voice_delete),
                tint = U2Theme.colors.danger,
                onClick = { voice?.cancel() },
            )
        }
    }
}

@Composable
private fun ComposerField(
    text: String,
    onTextChange: (String) -> Unit,
    mode: ComposerVoiceMode,
    onTimer: () -> Unit,
    onSticker: () -> Unit,
    modifier: Modifier,
) {
    val colors = U2Theme.colors
    val idle = mode == ComposerVoiceMode.Idle
    if (mode == ComposerVoiceMode.Locked || mode == ComposerVoiceMode.Review) {
        // Locked or in review the keyboard has gone and the strip's buttons lie here: the field
        // leaves the row (an empty slot of its size), so it takes no touch meant for the strip.
        Box(modifier.testTag("composer.field").height(U2Size.composer).semantics { invisibleToUser() })
        return
    }
    val shape = RoundedCornerShape(U2Radius.composerField)
    val style = U2TextStyles.rowTitle.textStyle().merge(color = colors.text)
    val placeholder = stringResource(R.string.common_composer_placeholder)
    Row(
        modifier
            .testTag("composer.field")
            // Under the recording strip while a voice message is recorded.
            .alpha(if (idle) 1f else 0f)
            .semantics { if (!idle) invisibleToUser() }
            .heightIn(min = U2Size.composer)
            .background(colors.surface, shape)
            .u2InnerStroke(colors.strokeField, 0.5.dp, shape)
            .padding(start = 16.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .weight(1f)
                .testTag("composer.input")
                .padding(vertical = (U2Size.composer - U2TextStyles.rowTitle.lineHeight.dp) / 2),
            // While the mic is held the keyboard stays (the field keeps its focus, hidden).
            textStyle = style,
            cursorBrush = SolidColor(colors.accent400),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            minLines = 1,
            maxLines = 5,
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        U2Text(placeholder, U2TextStyles.rowTitle, color = colors.textPlaceholder, maxLines = 1)
                    }
                    inner()
                }
            },
        )
        Row(
            Modifier.height(U2Size.composer),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 2/3 dp lower than centred, like the design's icon font.
            FieldIconButton(PhosphorGlyph.Timer, stringResource(R.string.common_a11y_composer_timer), idle, onTimer)
            FieldIconButton(PhosphorGlyph.Sticker, stringResource(R.string.common_a11y_composer_sticker), idle, onSticker)
        }
    }
}

@Composable
private fun FieldIconButton(glyph: PhosphorGlyph, label: String, enabled: Boolean, onClick: () -> Unit) {
    PhosphorIcon(
        glyph,
        21.dp,
        Modifier
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .graphicsLayer { translationY = 2f / 3f * density },
        tint = U2Theme.colors.textComposerIcon,
    )
}

/**
 * The mic / send button. With text it sends; empty it records (with a voice recorder by press and
 * hold, otherwise with a tap: [onRecord]); a recording locked or in review it sends. While held it
 * fills, grows a touch and follows the finger, with the lock above it.
 */
@Composable
private fun ComposerActionButton(hasText: Boolean, voice: ComposerVoice?, onSend: () -> Unit, onRecord: () -> Unit) {
    val mode = voice?.mode ?: ComposerVoiceMode.Idle
    val sendsRecording = mode == ComposerVoiceMode.Locked || mode == ComposerVoiceMode.Review
    val sends = hasText || sendsRecording
    val holding = mode == ComposerVoiceMode.Holding
    val reduceMotion = LocalReduceMotion.current
    val scale by animateFloatAsState(if (holding && !reduceMotion) 1.3f else 1f, u2Spec(U2Motion.micro), label = "micScale")
    val label = when {
        sendsRecording -> stringResource(R.string.conversation_voice_send)
        sends -> stringResource(R.string.common_a11y_composer_send)
        else -> stringResource(R.string.common_a11y_composer_record)
    }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val offset = voice?.micOffset ?: DpOffset.Zero
    LockHintLayout(
        lock = {
            val rise = U2Transitions.rise()
            AnimatedVisibility(voice != null && holding, enter = rise.enter, exit = rise.exit) { voice?.LockHint(Modifier) }
        },
        button = {
            Box(
                Modifier
                    .offset { IntOffset(offset.x.roundToPx(), offset.y.roundToPx()) }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
            ) {
                Box(
                    Modifier
                        .testTag("composer.action")
                        .u2PressEffect(source, scale = 0.98f)
                        .alpha(if (pressed) 0.85f else 1f)
                        .clickable(source, indication = null, role = Role.Button) {
                            when {
                                sendsRecording -> voice?.send()
                                hasText -> onSend()
                                else -> onRecord()
                            }
                        }
                        // The hold area speaks for the mic.
                        .semantics {
                            contentDescription = label
                            if (voice != null && !sends) invisibleToUser()
                        },
                ) { ComposerMicLabel(sends, holding) }
                if (voice != null && !hasText && (mode == ComposerVoiceMode.Idle || holding)) {
                    voice.HoldArea(Modifier.size(U2Size.iconButton))
                }
            }
        },
    )
}

/** The mic, with the lock hint 16 dp above it, behind it (the mic rises into it as the finger slides up). */
@Composable
private fun LockHintLayout(lock: @Composable () -> Unit, button: @Composable () -> Unit) {
    Layout(contents = listOf(lock, button)) { (lockMeasurables, buttonMeasurables), constraints ->
        val main = buttonMeasurables.first().measure(constraints)
        val locks = lockMeasurables.map { it.measure(Constraints()) }
        val gap = 16.dp.roundToPx()
        layout(main.width, main.height) {
            locks.forEach { it.place(((main.width - it.width) / 2f).roundToInt(), -(it.height + gap)) }
            main.place(0, 0)
        }
    }
}

/**
 * C21's mic (44, 1 dp `accent`, the glyph `accent300`, the mic glow); the paper plane cross-fades
 * in to send. Recording (proposed): filled with `accent`, the glyph in `bg`.
 */
@Composable
private fun ComposerMicLabel(sends: Boolean, isRecording: Boolean) {
    val colors = U2Theme.colors
    val send by animateFloatAsState(if (sends) 1f else 0f, u2Spec(U2Motion.micro), label = "send")
    val recording by animateFloatAsState(if (isRecording) 1f else 0f, u2Spec(U2Motion.micro), label = "recording")
    val ink = lerpColor(colors.accent300, colors.bg, recording)
    Box(
        Modifier
            .size(U2Size.iconButton)
            .u2BoxShadow(CircleShape, U2BoxShadows.glowMic)
            .background(colors.accent.copy(alpha = colors.accent.alpha * recording), CircleShape)
            .u2InnerStroke(colors.accent, 1.dp, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.graphicsLayer { translationY = 2f / 3f * density }, contentAlignment = Alignment.Center) {
            PhosphorIcon(
                PhosphorGlyph.Microphone,
                21.dp,
                Modifier.graphicsLayer {
                    alpha = 1f - send
                    scaleX = 1f - 0.2f * send
                    scaleY = 1f - 0.2f * send
                },
                tint = ink,
            )
            PhosphorIcon(
                PhosphorGlyph.PaperPlaneTilt,
                21.dp,
                Modifier.graphicsLayer {
                    alpha = send
                    scaleX = 0.8f + 0.2f * send
                    scaleY = 0.8f + 0.2f * send
                },
                tint = ink,
            )
        }
    }
}

private fun lerpColor(from: Color, to: Color, t: Float): Color = androidx.compose.ui.graphics.lerp(from, to, t.coerceIn(0f, 1f))

/**
 * C4: a round glass button, `text·9%` (pressed 14 %) with a 0.5 dp `text·18%` hairline and a
 * centred glyph (21; 22 for “+”, nudged 1/3 dp down like the design's icon font).
 */
@Composable
private fun GlassIconButton(
    glyph: PhosphorGlyph,
    label: String,
    modifier: Modifier = Modifier,
    diameter: Dp = U2Size.iconButton,
    glyphSize: Dp = 21.dp,
    tint: Color = U2Theme.colors.text,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = U2Theme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val nudge = when {
        diameter != U2Size.iconButton -> 0f
        glyphSize == 21.dp -> 2f / 3f
        glyphSize == 22.dp -> 1f / 3f
        else -> 0f
    }
    Box(
        modifier
            .u2PressEffect(source)
            .size(diameter)
            .background(colors.text.copy(alpha = if (pressed) 0.14f else 0.09f), CircleShape)
            .u2InnerStroke(colors.strokeGlass, 0.5.dp, CircleShape)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        PhosphorIcon(glyph, glyphSize, Modifier.graphicsLayer { translationY = nudge * density }, tint = tint)
    }
}
