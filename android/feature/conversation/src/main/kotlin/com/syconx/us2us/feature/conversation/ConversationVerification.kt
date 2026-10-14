package com.syconx.us2us.feature.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.FloatingBanner
import com.syconx.us2us.ui.components.FloatingBannerKind
import com.syconx.us2us.ui.components.SystemPill
import com.syconx.us2us.ui.components.u2CardPress
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.theme.U2BackdropSource
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text

// 5.1 · whether the line is verified (proposed; the design draws only the header seal): a
// first-contact prompt at the top of the history while the safety number was never compared,
// and a banner above the composer while a changed safety number waits for the user. Both open 6.1.

/**
 * 5.1 first row of the history in an unverified chat: the system pill's look with `ph:qr-code`,
 * “Doğrulanmadı · QR ile doğrula”, tappable (6.1). It scrolls away with the history and goes
 * once the chat is verified.
 */
@Composable
internal fun ConversationVerifyPrompt(onVerify: () -> Unit, modifier: Modifier = Modifier) {
    val hint = stringResource(R.string.conversation_verify_prompt_hint)
    Box(modifier.testTag("conversation.verifyPrompt"), contentAlignment = Alignment.Center) {
        SystemPill(
            stringResource(R.string.conversation_verify_prompt),
            Modifier
                .u2CardPress(onVerify)
                .semantics {
                    role = Role.Button
                    onClick(label = hint) {
                        onVerify()
                        true
                    }
                },
            glyph = PhosphorGlyph.QrCode,
        )
    }
}

/**
 * 5.1 banner 6 dp above the composer while a changed safety number waits for the user (sending is
 * held): the urgent banner's look with `ph:warning-circle` 18 `danger`, the sentence in `bullet`
 * `text` and “Karşılaştır” in `rowTitle` `accent300`. The whole banner is the button (6.1).
 */
@Composable
internal fun SafetyNumberChangedBanner(onCompare: () -> Unit, modifier: Modifier = Modifier, backdrop: U2BackdropSource? = null) {
    val colors = U2Theme.colors
    FloatingBanner(
        FloatingBannerKind.Urgent,
        modifier
            .testTag("conversation.safetyChanged")
            .u2CardPress(onCompare)
            .semantics(mergeDescendants = true) { role = Role.Button },
        backdrop = backdrop,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PhosphorIcon(PhosphorGlyph.WarningCircle, 18.dp, tint = colors.danger)
            U2Text(stringResource(R.string.conversation_safetychanged_banner), U2TextStyles.bullet, Modifier.weight(1f), color = colors.text)
            U2Text(stringResource(R.string.conversation_safetychanged_compare), U2TextStyles.rowTitle, color = colors.accent300, maxLines = 1, softWrap = false)
        }
    }
}
