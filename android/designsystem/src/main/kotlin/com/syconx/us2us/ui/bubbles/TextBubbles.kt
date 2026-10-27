package com.syconx.us2us.ui.bubbles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageLinks
import com.syconx.us2us.domain.QuotedMessage
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import com.syconx.us2us.ui.type.textStyle
import kotlin.math.ceil

/** The ink of a bubble's main text: `text` incoming, `accent100` outgoing. */
@Composable
internal fun bubbleInk(message: Message): Color =
    if (message.isOutgoing) U2Theme.colors.onBubbleOutgoing else U2Theme.colors.text

/**
 * A message's text in `bubble` (Inter 400 16/22.08): when it wraps, as wide as its widest line
 * (iOS `Text` in the bubble column), so a wrapped bubble hugs its text instead of filling 290.
 */
@Composable
fun BubbleText(text: String, color: Color, modifier: Modifier = Modifier) {
    val matches = remember(text) { MessageLinks.detect(text) }
    if (matches.isEmpty()) {
        val layout = remember { TextLayoutHolder() }
        U2Text(text, U2TextStyles.bubble, modifier.hugWidestLine(layout, rememberBubbleSpaceWidth()), color = color, onTextLayout = { layout.result = it })
        return
    }
    val invite = LocalMessageInviteAction.current
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val linked = remember(text, color, invite, uri, context) {
        messageLinkText(text, color, invite, matches) { address ->
            try { uri.openUri(address) }
            catch (_: IllegalArgumentException) {
                android.widget.Toast.makeText(context, R.string.message_link_open_failed, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    BubbleText(linked, color, modifier)
}

/** Present only inside an unlocked conversation. A tap opens confirmation, never redeems directly. */
val LocalMessageInviteAction = staticCompositionLocalOf<((String) -> Unit)?> { null }

internal fun messageLinkText(text: String, color: Color, invite: ((String) -> Unit)?, matches: List<MessageLinks.Match> = MessageLinks.detect(text), openWeb: (String) -> Unit): AnnotatedString =
    buildAnnotatedString {
        append(text)
        matches.forEachIndexed { index, match ->
            if (match.target is MessageLinks.Target.CommunityInvite && invite == null) return@forEachIndexed
            addLink(LinkAnnotation.Clickable(
                tag = "message-link-$index",
                styles = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline)),
                linkInteractionListener = {
                    when (val target = match.target) {
                        is MessageLinks.Target.Web -> openWeb(target.url)
                        is MessageLinks.Target.CommunityInvite -> invite?.invoke(target.code)
                    }
                },
            ), match.start, match.end)
        }
    }

/** [BubbleText] for styled text (an underlined link): the invite sheet's preview bubble. */
@Composable
fun BubbleText(text: AnnotatedString, color: Color, modifier: Modifier = Modifier) {
    val layout = remember { TextLayoutHolder() }
    U2Text(text, U2TextStyles.bubble, modifier.hugWidestLine(layout, rememberBubbleSpaceWidth()), color = color, onTextLayout = { layout.result = it })
}

private class TextLayoutHolder {
    var result: TextLayoutResult? = null
}

/** The advance of a space in `bubble`, in px. */
@Composable
private fun rememberBubbleSpaceWidth(): Float {
    val measurer = rememberTextMeasurer()
    val style = U2TextStyles.bubble.textStyle()
    return remember(measurer, style) {
        (measurer.measure("a a", style).size.width - measurer.measure("aa", style).size.width).toFloat().coerceAtLeast(0f)
    }
}

/**
 * Reports the width of the widest line of a wrapped text (the text measures the whole width it
 * wrapped in). As in Core Text, a line that wrapped at a space counts that space: iOS sizes a
 * wrapped `Text` by its lines' typographic widths, trailing whitespace included.
 */
private fun Modifier.hugWidestLine(holder: TextLayoutHolder, spaceWidth: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val result = holder.result
    val width = if (result != null && result.lineCount > 1) {
        val text = result.layoutInput.text.text
        val widest = (0 until result.lineCount).maxOf { line ->
            val visible = result.getLineRight(line) - result.getLineLeft(line)
            val end = result.getLineEnd(line)
            val wrappedAtSpace = line < result.lineCount - 1 && end > 0 && text[end - 1] == ' '
            if (wrappedAtSpace) visible + spaceWidth else visible
        }
        ceil(widest).toInt().coerceIn(constraints.minWidth, placeable.width)
    } else {
        placeable.width
    }
    layout(width, placeable.height) { placeable.placeRelative(0, 0) }
}

/** A text bubble: `bubble` (Inter 400 16/22.08) in `text` / `accent100`, the time row under it. */
@Composable
fun TextBubble(message: Message, text: String, modifier: Modifier = Modifier, isTail: Boolean = false) {
    MessageBubble(message.direction, modifier, isTail = isTail) {
        BubbleText(text, bubbleInk(message))
        BubbleMeta(message.sentAt, Modifier.bubbleTrailing(), status = message.status, disappearing = message.disappearing)
    }
}

/**
 * A reply: the quote block on top (`bg·32%` r14, padding 6 6 6 10), then the text (padding 0 7);
 * max width 270, padding 6 6 8 6.
 */
@Composable
fun ReplyBubble(message: Message, text: String, quote: QuotedMessage, modifier: Modifier = Modifier, isTail: Boolean = false) {
    MessageBubble(
        message.direction,
        modifier,
        isTail = isTail,
        maxWidth = 270.dp,
        padding = PaddingValues(start = 6.dp, top = 6.dp, end = 6.dp, bottom = 8.dp),
    ) {
        ReplyQuote(quote, Modifier.bubbleFill())
        BubbleText(text, bubbleInk(message), Modifier.padding(horizontal = 7.dp))
        BubbleMeta(
            message.sentAt,
            Modifier.padding(end = 7.dp).bubbleTrailing(),
            status = message.status,
            disappearing = message.disappearing,
        )
    }
}

/**
 * The quote block: the author line (`ph:arrow-bend-up-left` 13 + `quoteAuthor` `accent300`,
 * gap 5) over the kind line (a 14 dp glyph + `quoteBody` `text·68%`), with a 40 × 40 r8
 * thumbnail. Also the composer's reply preview.
 */
@Composable
fun ReplyQuote(quote: QuotedMessage, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    Row(
        modifier
            .testTag("bubble.quote")
            .semantics(mergeDescendants = true) {}
            .background(colors.quoteFill, RoundedCornerShape(U2Radius.bubbleQuote))
            .padding(start = 10.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                PhosphorIcon(PhosphorGlyph.ArrowBendUpLeft, 13.dp, tint = colors.accent300)
                U2Text(quote.author, U2TextStyles.quoteAuthor, color = colors.accent300, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                when (quote.kind) {
                    QuotedMessage.Kind.Photo -> PhosphorIcon(PhosphorGlyph.Image, 14.dp, tint = colors.textSecondary)
                    QuotedMessage.Kind.Video -> PhosphorIcon(PhosphorGlyph.Play, 14.dp, weight = PhosphorWeight.Fill, tint = colors.textSecondary)
                    QuotedMessage.Kind.Voice -> PhosphorIcon(PhosphorGlyph.Microphone, 14.dp, tint = colors.textSecondary)
                    is QuotedMessage.Kind.File -> PhosphorIcon(PhosphorGlyph.FilePdf, 14.dp, tint = colors.textSecondary)
                    is QuotedMessage.Kind.Text -> Unit
                }
                U2Text(quoteKindText(quote.kind), U2TextStyles.quoteBody, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        quote.thumbnail?.let { thumbnail ->
            BubbleMediaView(thumbnail, DpSize(40.dp, 40.dp), Modifier.clip(RoundedCornerShape(U2Radius.quoteThumb)))
        }
    }
}

@Composable
private fun quoteKindText(kind: QuotedMessage.Kind): String = when (kind) {
    is QuotedMessage.Kind.Text -> kind.text
    QuotedMessage.Kind.Photo -> stringResource(R.string.common_media_photo)
    QuotedMessage.Kind.Video -> stringResource(R.string.common_media_video)
    QuotedMessage.Kind.Voice -> stringResource(R.string.common_media_voice)
    is QuotedMessage.Kind.File -> kind.name
}
