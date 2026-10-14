package com.syconx.us2us.feature.conversation.request

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.bubbles.BubbleMeta
import com.syconx.us2us.ui.bubbles.MessageBubble
import com.syconx.us2us.ui.bubbles.SystemEventPill
import com.syconx.us2us.ui.bubbles.TombstoneBubble
import com.syconx.us2us.ui.components.DotPattern
import com.syconx.us2us.ui.components.DottedRule
import com.syconx.us2us.ui.env.LocalU2Clock
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.l10n.U2Format
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import java.time.Instant
import java.time.ZoneId

/**
 * How a stranger's message is shown before it is accepted: links never become tappable and media
 * is never decoded (inventory 4.3/4.4 notes).
 */
object RequestContent {
    enum class TextKind {
        Plain,

        /** The whole message is one address (“slides.kuzey.dev/meetup”). */
        Link,

        /** Text with an address inside (“… tıkla: bit.ly/3xQ”). */
        ContainsLink,
    }

    /** Local pattern only, nothing is fetched or resolved: scheme optional, a dotted host with a ≥ 2-letter top-level label, optional path. */
    private val linkPattern = Regex("""(?i)(?:https?://)?(?:[a-z0-9-]+\.)+[a-z]{2,}(?:/\S*)?""")

    fun classify(text: String): TextKind {
        val trimmed = text.trim()
        val match = linkPattern.find(trimmed) ?: return TextKind.Plain
        return if (match.range.first == 0 && match.range.last == trimmed.lastIndex) TextKind.Link else TextKind.ContainsLink
    }
}

/** 4.4 date separator: “Bugün 16:12” / “Dün 16:12” / “24 Eyl 16:12” (upper-cased by `monoSectionLabel` in the UI language). */
@Composable
internal fun requestSeparatorText(date: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
    val locale = currentAppLocale()
    val time = U2Format.clockTime(date, locale, zone)
    val day = date.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    return when (day) {
        today -> stringResource(R.string.chats_preview_date_today, time)
        today.minusDays(1) -> stringResource(R.string.chats_preview_date_yesterday, time)
        else -> "${U2Format.dayMonth(date, locale, zone)} $time"
    }
}

/** The 4.4 date separator (pad 6 2, gap 12): dotted rules (`text·28%`, r .8) either side of “BUGÜN 16:12” (`monoSectionLabel`). */
@Composable
internal fun RequestDateSeparator(date: Instant, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val text = requestSeparatorText(date, LocalU2Clock.current.now)
    Row(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag("preview.date")
            .padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DottedRule(Modifier.weight(1f), color = colors.dotRuleDate, pattern = DotPattern.DateRule)
        U2Text(text, U2TextStyles.monoSectionLabel, color = colors.textSecondary, maxLines = 1, softWrap = false)
        DottedRule(Modifier.weight(1f), color = colors.dotRuleDate, pattern = DotPattern.DateRule)
    }
}

/**
 * One message of a request thread, always inert: text never becomes a link; an address is shown
 * broken (`ph:link-break`) with “Bağlantı, kabul edene kadar açılmaz”; media is never loaded, a
 * placeholder names it. [endsGroup]: the last of its group (tail corner and time row).
 */
@Composable
internal fun RequestMessageView(message: Message, endsGroup: Boolean, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val tag = Modifier.testTag("preview.message.${message.id}")
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
        when (val content = message.content) {
            is MessageContent.Text -> {
                val kind = RequestContent.classify(content.text)
                MessageBubble(MessageDirection.Incoming, tag.semantics(mergeDescendants = true) {}, isTail = endsGroup) {
                    if (kind == RequestContent.TextKind.Link) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            PhosphorIcon(PhosphorGlyph.LinkBreak, 16.dp, tint = colors.textSecondary)
                            U2Text(content.text, U2TextStyles.monoLink, color = colors.textStrong, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        }
                        InertNote(stringResource(R.string.chats_preview_link_inert))
                    } else {
                        U2Text(content.text, U2TextStyles.bubble, color = colors.text)
                        if (kind == RequestContent.TextKind.ContainsLink) InertNote(stringResource(R.string.chats_preview_link_inert), PhosphorGlyph.LinkBreak)
                    }
                    if (endsGroup) BubbleMeta(message.sentAt, Modifier.bubbleTrailing())
                }
            }
            MessageContent.Deleted -> TombstoneBubble(message, tag, isTail = endsGroup)
            is MessageContent.System -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SystemEventPill(content.event) }
            else -> MessageBubble(MessageDirection.Incoming, tag.semantics(mergeDescendants = true) {}, isTail = endsGroup) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PhosphorIcon(PhosphorGlyph.EyeSlash, 16.dp, tint = colors.textSecondary)
                    U2Text(mediaName(content), U2TextStyles.bubble, color = colors.text)
                }
                InertNote(stringResource(R.string.chats_requests_flag_media))
                if (endsGroup) BubbleMeta(message.sentAt, Modifier.bubbleTrailing())
            }
        }
    }
}

@Composable
private fun InertNote(text: String, glyph: PhosphorGlyph? = null) {
    val colors = U2Theme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (glyph != null) PhosphorIcon(glyph, 14.dp, tint = colors.textSecondary)
        U2Text(text, U2TextStyles.metaInBubble, color = colors.textSecondary)
    }
}

@Composable
private fun mediaName(content: MessageContent): String = when (content) {
    is MessageContent.Photo -> stringResource(R.string.common_media_photo)
    is MessageContent.Video -> stringResource(R.string.common_media_video)
    is MessageContent.Voice -> stringResource(R.string.common_media_voice)
    is MessageContent.File -> stringResource(R.string.chats_media_file)
    is MessageContent.Location -> stringResource(R.string.common_media_location)
    is MessageContent.LiveLocation -> stringResource(R.string.common_location_live)
    is MessageContent.ViewOnce -> stringResource(if (content.isVideo) R.string.common_media_video else R.string.common_media_photo)
    is MessageContent.TextSticker, is MessageContent.Sticker -> stringResource(R.string.common_chats_preview_sticker)
    else -> ""
}
