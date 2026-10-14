package com.syconx.us2us.ui.bubbles

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.domain.Reaction
import com.syconx.us2us.domain.SystemEvent
import com.syconx.us2us.domain.ViewOnceState
import com.syconx.us2us.domain.sticker.StickerCatalog
import com.syconx.us2us.domain.sticker.StickerLanguage
import com.syconx.us2us.domain.sticker.StickerResolution
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.brand.BrandSticker
import com.syconx.us2us.ui.components.LineMark
import com.syconx.us2us.ui.components.LineMarkMetrics
import com.syconx.us2us.ui.components.text.GreedyParagraph
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.media.LocalMediaClock
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.theme.U2BoxShadows
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2BoxShadow
import com.syconx.us2us.ui.theme.u2OuterRing
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlin.math.floor

/**
 * The deleted-message trace: transparent, inner 1 dp `divider`, `ph:prohibit` 16 + “Bu mesaj
 * herkesten silindi” Inter 400 *italic* 14.5 `text·68%`. The oblique overhangs the text box: 4 dp
 * of room on its end (taken from the bubble's padding, so the bubble keeps its width).
 */
@Composable
fun TombstoneBubble(message: Message, modifier: Modifier = Modifier, isTail: Boolean = false) {
    val ink = U2Theme.colors.textSecondary
    MessageBubble(
        message.direction,
        modifier.semantics(mergeDescendants = true) {},
        isTail = isTail,
        padding = PaddingValues(start = 13.dp, top = 9.dp, end = 9.dp, bottom = 8.dp),
        fill = BubbleFill.Tombstone,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PhosphorIcon(PhosphorGlyph.Prohibit, 16.dp, tint = ink)
            U2Text(stringResource(R.string.common_message_deleted), U2TextStyles.bubbleTombstone, Modifier.padding(end = 4.dp), color = ink)
        }
    }
}

/**
 * View-once media: padding 9 9 8 13: a 36 dp circle with a 1.5 dp dashed `accent300` ring and “1”
 * (Mono 13), the title `bubbleTitle` and the subtitle `metaInBubble` (wrapped greedily, like the
 * browser that drew the design: “…açmak için / dokun”). Opened: a solid `text·40%` ring and
 * “Açıldı” (proposed).
 */
@Composable
fun ViewOnceBubble(message: Message, isVideo: Boolean, state: ViewOnceState, modifier: Modifier = Modifier, isTail: Boolean = false) {
    val subtitle = when (state) {
        ViewOnceState.Unopened -> R.string.common_viewonce_taptoopen
        ViewOnceState.Opened -> R.string.common_viewonce_opened
        ViewOnceState.Expired -> R.string.common_viewonce_expired
    }
    MessageBubble(
        message.direction,
        modifier.semantics(mergeDescendants = true) {},
        isTail = isTail,
        padding = PaddingValues(start = 9.dp, top = 9.dp, end = 13.dp, bottom = 8.dp),
    ) {
        Row(
            Modifier.padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewOnceBadge(diameter = 36.dp, isOpened = state != ViewOnceState.Unopened)
            Column(Modifier.widthIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                U2Text(
                    stringResource(if (isVideo) R.string.common_media_video else R.string.common_media_photo),
                    U2TextStyles.bubbleTitle,
                    color = bubbleInk(message),
                )
                // CSS greedy wrapping (“…açmak için / dokun”): the widow avoidance of plain text would push “için” down.
                GreedyParagraph(stringResource(subtitle), U2TextStyles.metaInBubble, Modifier.fillMaxWidth(), color = U2Theme.colors.textSecondary)
            }
        }
        BubbleMeta(message.sentAt, Modifier.bubbleTrailing(), status = message.status, disappearing = message.disappearing)
    }
}

/** The view-once mark: a dashed `accent300` ring around “1” (1.3: 18 dp with Mono 9; 5.2: 36 dp with Mono 13). */
@Composable
fun ViewOnceBadge(modifier: Modifier = Modifier, diameter: Dp = 36.dp, isOpened: Boolean = false) {
    val colors = U2Theme.colors
    val ink = if (isOpened) colors.textTertiary else colors.accent300
    Box(modifier.size(diameter).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val width = 1.5.dp.toPx()
            val dash = if (isOpened) null else PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.5.dp.toPx()))
            drawCircle(ink, radius = size.minDimension / 2f - width / 2f, style = Stroke(width, pathEffect = dash))
        }
        U2Text("1", if (diameter < 24.dp) U2TextStyles.monoMicro else U2TextStyles.monoLink, color = ink, maxLines = 1, softWrap = false)
    }
}

/**
 * A Doto text sticker (5.2): no bubble, a card rotated −6°, `accent200`, r22, a 4 dp `#f5f4ff`
 * ring and a soft drop shadow, the word in `displaySticker` `accent900` over a mini line mark;
 * the time beside it.
 */
@Composable
fun TextStickerMessage(message: Message, text: String, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val shape = RoundedCornerShape(U2Radius.sticker)
    val label = stringResource(R.string.common_a11y_sticker, text)
    Row(
        modifier
            .padding(start = 8.dp, top = 8.dp)
            .clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(
            Modifier
                .graphicsLayer { rotationZ = -6f }
                .u2BoxShadow(shape, U2BoxShadows.sticker)
                .u2OuterRing(colors.stickerRing, 4.dp, shape)
                .background(colors.accent200, shape)
                .padding(start = 18.dp, top = 12.dp, end = 18.dp, bottom = 11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            U2Text(text, U2TextStyles.displaySticker, color = colors.accent900, maxLines = 1, softWrap = false)
            LineMark(metrics = LineMarkMetrics.Sticker, color = colors.accent700)
        }
        U2Text(BubbleTimeFormat.string(message.sentAt, currentAppLocale()), U2TextStyles.monoTag, color = colors.textSecondary, maxLines = 1, softWrap = false)
    }
}

/** The sticker's side in the conversation; an unknown sticker's emoji and the placeholder are smaller. */
private val StickerSide = 132.dp
private val StickerFallbackSide = 96.dp

/**
 * A built-in sticker (PROTOCOL §8.8) as a message: no bubble, the art (132 dp) and its time row
 * beside it. An unknown sticker shows its emoji large; the legacy text pack is the Doto card.
 */
@Composable
fun BrandStickerMessage(
    message: Message,
    packId: String,
    stickerId: Int,
    emoji: String,
    lang: String,
    modifier: Modifier = Modifier,
    animatesEntrance: Boolean = false,
) {
    val ui = StickerLanguage.forLocale(currentAppLocale())
    when (val resolution = StickerCatalog.resolve(packId, stickerId, emoji, lang, ui)) {
        is StickerResolution.Sticker -> {
            val selection = resolution.selection
            val label = stringResource(R.string.common_a11y_sticker, selection.sticker.accessibilityLabel(selection.language, ui))
            StickerRow(message, label, modifier) {
                BrandSticker(
                    selection.sticker.id,
                    words = null,
                    lang = selection.language.wireCode,
                    modifier = Modifier.size(StickerSide),
                    animatesEntrance = animatesEntrance,
                )
            }
        }
        is StickerResolution.LegacyText -> TextStickerMessage(message, resolution.text, modifier)
        is StickerResolution.Emoji -> StickerRow(message, stringResource(R.string.common_a11y_sticker, resolution.emoji), modifier) {
            StickerEmoji(resolution.emoji, StickerFallbackSide)
        }
        StickerResolution.Unavailable -> StickerRow(message, stringResource(R.string.common_chats_preview_sticker), modifier) {
            StickerPlaceholder(StickerFallbackSide)
        }
    }
}

/** The art and its meta, read as one image: “Sticker: …”, then the time and the state. */
@Composable
private fun StickerRow(message: Message, label: String, modifier: Modifier, art: @Composable () -> Unit) {
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        art()
        BubbleMeta(message.sentAt, Modifier.padding(bottom = 6.dp), status = message.status, disappearing = message.disappearing)
    }
}

/** A received sticker this build does not know: its emoji, large, at a fixed size. */
@Composable
private fun StickerEmoji(emoji: String, side: Dp) {
    val fontSize = with(LocalDensity.current) { (side * 0.62f).toSp() }
    Box(Modifier.size(side), contentAlignment = Alignment.Center) {
        BasicText(emoji, style = TextStyle(fontSize = fontSize, textAlign = TextAlign.Center), maxLines = 1, softWrap = false)
    }
}

/** A received sticker with nothing to draw: a neutral tile with the sticker glyph. */
@Composable
private fun StickerPlaceholder(side: Dp) {
    val colors = U2Theme.colors
    val shape = RoundedCornerShape(U2Radius.sticker)
    Box(
        Modifier
            .size(side)
            .background(colors.fillSubtle, shape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(side)) {
            val width = 1.5.dp.toPx()
            val radius = U2Radius.sticker.toPx()
            drawRoundRect(
                colors.divider,
                topLeft = Offset(width / 2f, width / 2f),
                size = Size(size.width - width, size.height - width),
                cornerRadius = CornerRadius(radius - width / 2f),
                style = Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
            )
        }
        PhosphorIcon(PhosphorGlyph.Sticker, 30.dp, tint = colors.textTertiary)
    }
}

/**
 * The reaction under a bubble: 34 × 27, a capsule in `accent800`, a 2.5 dp `bg` cut-out ring, the
 * emoji at 14 (drawn at the size Chrome gives it: the glyph in an 18-wide advance) and the count
 * (Mono `accent100`) when more than one reacted.
 */
@Composable
fun ReactionChip(reaction: Reaction, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val emojiSize = with(LocalDensity.current) { ReactionEmojiSize.toSp() }
    Row(
        modifier
            .clearAndSetSemantics { contentDescription = if (reaction.count > 1) "${reaction.emoji} ${reaction.count}" else reaction.emoji }
            .heightIn(min = 27.dp, max = 27.dp)
            .widthIn(min = 34.dp)
            .u2OuterRing(colors.bg, 2.5.dp, CircleShape)
            .background(colors.accent800, CircleShape)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp, 14.dp).offset(y = (-1).dp), contentAlignment = Alignment.CenterStart) {
            BasicText(
                reaction.emoji,
                style = TextStyle(fontSize = emojiSize, lineHeight = emojiSize),
                maxLines = 1,
                softWrap = false,
            )
        }
        if (reaction.count > 1) {
            U2Text(reaction.count.toString(), U2TextStyles.monoTag, color = colors.accent100, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * The emoji size of a reaction chip: Noto Color Emoji draws a heart this size about as large as
 * Apple Color Emoji draws the design's (12.3 dp wide in the 18 dp advance Chrome gives 14 px).
 */
private val ReactionEmojiSize = 11.dp

/**
 * The other side is writing (PROTOCOL §8.4 typing; accepted chats only, optional on both sides):
 * an incoming bubble (`surface`, tail) holding three 7 dp dots in `text·68%` that brighten in
 * turn on the design curve (0.45 s a step). Reduce motion or a frozen clock: the dots stand still.
 */
@Composable
fun TypingIndicatorBubble(modifier: Modifier = Modifier) {
    val still = LocalReduceMotion.current || LocalMediaClock.current.isFrozen
    MessageBubble(
        MessageDirection.Incoming,
        modifier.clearAndSetSemantics {},
        isTail = true,
        padding = PaddingValues(start = 14.dp, top = 9.dp, end = 14.dp, bottom = 8.dp),
    ) {
        if (still) {
            TypingDots(phase = null)
        } else {
            val transition = rememberInfiniteTransition(label = "typing")
            val time by transition.animateFloat(0f, 3f, infiniteRepeatable(tween(1_350, easing = LinearEasing)), label = "typingPhase")
            TypingDots(phase = time)
        }
    }
}

/** Three dots; [phase] 0…3 walks the bright one along (each step eased); `null`: all at rest. */
@Composable
private fun TypingDots(phase: Float?) {
    val color = U2Theme.colors.textSecondary
    Row(Modifier.height(22.08.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (index in 0 until 3) {
            val (alpha, scale) = if (phase == null) {
                0.7f to 1f
            } else {
                val step = floor(phase).toInt()
                val t = U2Motion.SoftEasing.transform(phase - step)
                val from = step % 3
                val to = (step + 1) % 3
                fun opacity(active: Int) = if (active == index) 1f else 0.38f
                fun size(active: Int) = if (active == index) 1.08f else 1f
                (opacity(from) + (opacity(to) - opacity(from)) * t) to (size(from) + (size(to) - size(from)) * t)
            }
            Box(
                Modifier
                    .size(7.dp)
                    .scale(scale)
                    .graphicsLayer { this.alpha = alpha }
                    .background(color, CircleShape),
            )
        }
    }
}

/** A system line in the chat (5.1 item 1 and the 5.5 note's proposed events). */
@Composable
fun SystemEventPill(event: SystemEvent, modifier: Modifier = Modifier) {
    when (event) {
        is SystemEvent.GhostEnabled ->
            SystemPill(stringResource(R.string.common_system_ghost_on, stringResource(event.period.longLabelRes)), modifier, PhosphorGlyph.Ghost)
        SystemEvent.GhostDisabled -> SystemPill(stringResource(R.string.common_system_ghost_off), modifier, PhosphorGlyph.Ghost)
        is SystemEvent.DisappearingChanged ->
            SystemPill(stringResource(R.string.common_system_disappearing, stringResource(event.timer.shortLabelRes)), modifier, PhosphorGlyph.Timer)
        is SystemEvent.ScreenshotTaken -> SystemPill(stringResource(R.string.common_system_screenshot, event.by), modifier, PhosphorGlyph.Camera)
        SystemEvent.SafetyNumberChanged -> SystemPill(stringResource(R.string.common_system_safetynumber_changed), modifier, PhosphorGlyph.WarningCircle)
        SystemEvent.SafetyNumberAccepted -> SystemPill(stringResource(R.string.common_system_safetynumber_accepted), modifier, PhosphorGlyph.Check)
        SystemEvent.SafetyNumberVerified ->
            SystemPill(stringResource(R.string.common_system_safetynumber_verified), modifier, PhosphorGlyph.SealCheck, PhosphorWeight.Fill)
    }
}

/**
 * The 5.1 system pill: `text·6%`, r14, padding 6 12, gap 7: a 14 dp glyph + `monoSystemPill`
 * (Mono 11/15.4) `text·68%`, centred, at most 330 wide.
 */
@Composable
private fun SystemPill(text: String, modifier: Modifier, glyph: PhosphorGlyph?, glyphWeight: PhosphorWeight = PhosphorWeight.Regular) {
    val colors = U2Theme.colors
    Row(
        modifier
            .widthIn(max = 330.dp)
            .semantics(mergeDescendants = true) {}
            .background(colors.fillSubtle, RoundedCornerShape(U2Radius.systemPill))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) PhosphorIcon(glyph, 14.dp, weight = glyphWeight, tint = colors.textSecondary)
        U2Text(text, U2TextStyles.monoSystemPill, color = colors.textSecondary, textAlign = TextAlign.Center)
    }
}
