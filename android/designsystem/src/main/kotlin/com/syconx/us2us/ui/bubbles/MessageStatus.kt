package com.syconx.us2us.ui.bubbles

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import com.syconx.us2us.ui.type.textStyle

/**
 * C16: the delivery state glyph, identical everywhere (1.2 legend, list rows, bubbles, 5.4):
 * Gönderildi `ph:check` `text·68%` · İletildi `ph:checks` `text·68%` · Okundu `ph:checks`
 * **fill** `accent300` · İletilemedi `ph:warning-circle` `danger`; sending `ph:circle-notch`
 * `text·40%`. A change cross-fades (0.36 s). Reads as the state's name.
 */
@Composable
fun MessageStatusIcon(status: MessageStatus, modifier: Modifier = Modifier, size: Dp = 15.dp) {
    val title = stringResource(status.titleRes)
    StateCrossFade(status, modifier.semantics { contentDescription = title }) { shown ->
        val colors = U2Theme.colors
        when (shown) {
            MessageStatus.Sending -> PhosphorIcon(PhosphorGlyph.CircleNotch, size, tint = colors.textTertiary)
            MessageStatus.Sent -> PhosphorIcon(PhosphorGlyph.Check, size, tint = colors.textSecondary)
            MessageStatus.Delivered -> PhosphorIcon(PhosphorGlyph.Checks, size, tint = colors.textSecondary)
            MessageStatus.Read -> PhosphorIcon(PhosphorGlyph.Checks, size, weight = PhosphorWeight.Fill, tint = colors.accent300)
            MessageStatus.Failed -> PhosphorIcon(PhosphorGlyph.WarningCircle, size, tint = colors.danger)
        }
    }
}

/** “Gönderildi”, “İletildi”, “Okundu”, “İletilemedi”. */
@get:StringRes
val MessageStatus.titleRes: Int
    get() = when (this) {
        MessageStatus.Sending -> R.string.common_status_sending
        MessageStatus.Sent -> R.string.common_status_sent
        MessageStatus.Delivered -> R.string.common_status_delivered
        MessageStatus.Read -> R.string.common_status_read
        MessageStatus.Failed -> R.string.common_status_failed
    }

/** The sentence under the newest outgoing bubble (5.1): “Alıcı uygulamayı açınca iletilecek”… */
@get:StringRes
val MessageStatus.sentenceRes: Int?
    get() = when (this) {
        MessageStatus.Sending, MessageStatus.Read -> null
        MessageStatus.Sent -> R.string.common_status_sent_sentence
        MessageStatus.Delivered -> R.string.common_status_delivered_sentence
        MessageStatus.Failed -> R.string.common_status_failed_sentence
    }

/** The 1.2 legend explanations (“alıcı açınca iletilecek”…). */
@get:StringRes
val MessageStatus.legendRes: Int?
    get() = when (this) {
        MessageStatus.Sending, MessageStatus.Read -> null
        MessageStatus.Sent -> R.string.common_status_sent_legend
        MessageStatus.Delivered -> R.string.common_status_delivered_legend
        MessageStatus.Failed -> R.string.common_status_failed_legend
    }

/**
 * 5.1 item 8: the newest outgoing message's state as a sentence, right-aligned under its bubble
 * (padding-end 4, gap 6): a 14 dp glyph, the state in Inter 500 12 `text` and “ · explanation”
 * in Inter 400 12 `text·68%`, always one line in both languages (shrinking to 85 % if it must).
 * [detail] replaces the state's own explanation (“Video hazırlanıyor”); [onResend] adds
 * “Tekrar gönder” to a failed message.
 */
@Composable
fun MessageStatusSentence(
    status: MessageStatus,
    modifier: Modifier = Modifier,
    detail: String? = null,
    onResend: (() -> Unit)? = null,
) {
    val colors = U2Theme.colors
    val title = stringResource(status.titleRes)
    val rest = detail ?: status.sentenceRes?.let { stringResource(it) }
    val text = statusText(title, rest)
    val style = U2TextStyles.statusLine.textStyle()
    Row(
        modifier.fillMaxWidth().padding(end = 4.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MessageStatusIcon(status, size = 14.dp)
        BasicText(
            text,
            Modifier.weight(1f, fill = false),
            style = style.merge(color = colors.textSecondary),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            autoSize = TextAutoSize.StepBased(
                minFontSize = style.fontSize * 0.85f,
                maxFontSize = style.fontSize,
                stepSize = 0.1.sp,
            ),
        )
        if (status == MessageStatus.Failed && onResend != null) {
            U2Text(
                stringResource(R.string.common_status_failed_resend),
                U2TextStyles.statusLineEmphasis,
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onResend,
                ),
                color = colors.accent300,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * 1.2 legend row: a 17 dp glyph in an 18-wide slot, gap 10, the state's name (`legendEmphasis`,
 * `text`) + “ · explanation” (`legend`, `text·68%`).
 */
@Composable
fun MessageStatusLegendRow(status: MessageStatus, modifier: Modifier = Modifier) {
    val title = stringResource(status.titleRes)
    val legend = status.legendRes?.let { stringResource(it) }
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) { MessageStatusIcon(status, size = 17.dp) }
        U2Text(statusText(title, legend), U2TextStyles.legend, color = U2Theme.colors.textSecondary)
    }
}

/** The state's name (weight 500, `text`) and, if any, “ · explanation” in the line's own colour. */
@Composable
private fun statusText(title: String, rest: String?): AnnotatedString {
    val ink = U2Theme.colors.text
    return remember(title, rest, ink) {
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight(500), color = ink)) { append(title) }
            if (rest != null) append(" · $rest")
        }
    }
}
