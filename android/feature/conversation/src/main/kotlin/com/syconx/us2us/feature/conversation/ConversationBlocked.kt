package com.syconx.us2us.feature.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.U2DecisionButton
import com.syconx.us2us.ui.components.U2DecisionKind
import com.syconx.us2us.ui.components.U2DecisionSize
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text

// 5.1 · the chat of someone the user blocked (proposed, in 4.4's language): the history stays,
// read only. The composer's place goes to a bar like 4.4's decision bar with “Engeli kaldır”; the
// header says “Engellendi” where the presence line would be, the Ghost chip is shown but cannot be
// changed, and 5.3 offers only what stays on this phone (no reaction, no reply, no “Herkesten sil”):
// nothing reaches a blocked person (PROTOCOL §6.4).

/** Test tags of the blocked chat. */
object BlockedChatTags {
    const val BAR = "conversation.blocked"
    const val UNBLOCK = "conversation.unblock"
}

/**
 * The bar in the composer's place (4.4's decision bar: `surface`, top radius 30, pad 18 16 40, gap
 * 14): the sentence in `callout` `text·68%`, centred, over “Engeli kaldır” (50 high, r14, inner 1 dp
 * `divider`; its TalkBack hint says what the tap does).
 */
@Composable
fun BlockedChatBar(username: String, onUnblock: () -> Unit, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    Column(
        modifier
            .fillMaxWidth()
            .testTag(BlockedChatTags.BAR)
            .background(colors.surface, RoundedCornerShape(topStart = U2Radius.sheetBar, topEnd = U2Radius.sheetBar))
            .padding(start = 16.dp, top = 18.dp, end = 16.dp)
            .u2Bottom(40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        U2Text(
            stringResource(R.string.conversation_blocked_bar, username),
            U2TextStyles.callout,
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        val hint = stringResource(R.string.conversation_blocked_unblock_hint)
        U2DecisionButton(
            U2DecisionKind.Neutral,
            stringResource(R.string.common_block_unblock),
            onUnblock,
            Modifier
                .testTag(BlockedChatTags.UNBLOCK)
                .semantics {
                    onClick(label = hint) {
                        onUnblock()
                        true
                    }
                },
            size = U2DecisionSize.Bar,
        )
    }
}
