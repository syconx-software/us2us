package com.syconx.us2us.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2OuterRing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text

/**
 * “Kaydetti” under the user's own photo, video or file once the other side saved it to their
 * phone (their SAVED receipt, PROTOCOL §8.4; proposed): the reaction chip's look, 27 high, a
 * capsule in `accent800` with the 2.5 dp `bg` cut-out ring, holding ↓ (11) and the word in
 * `monoTag` `accent100`. It sits next to the reaction, 12 dp in from the bubble's leading edge,
 * overlapping the bubble's bottom by 15 dp (`MessageBubbleView`).
 */
@Composable
fun SavedChip(modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val label = stringResource(R.string.conversation_bubble_saved_a11y)
    Row(
        modifier
            .testTag("bubble.saved")
            .clearAndSetSemantics { contentDescription = label }
            .height(27.dp)
            .u2OuterRing(colors.bg, 2.5.dp, CircleShape)
            .background(colors.accent800, CircleShape)
            .padding(horizontal = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaGlyphIcon(MediaGlyph.ArrowDown, 11.dp, tint = colors.accent100)
        U2Text(stringResource(R.string.conversation_bubble_saved), U2TextStyles.monoTag, color = colors.accent100, maxLines = 1, softWrap = false)
    }
}

/** When the “Kaydetti” chip shows (iOS `SavedMediaChip`). */
object SavedMediaChip {
    /** On the user's own photo, video or file once the other side saved it, never on anything else. */
    fun isShown(message: Message): Boolean {
        if (!message.isOutgoing || !message.savedByPeer || message.isDeleted) return false
        return when (message.content) {
            is MessageContent.Photo, is MessageContent.Video, is MessageContent.File -> true
            else -> false
        }
    }
}

/** Chips hang under the bubble (the reaction, “Kaydetti”): it keeps 14 dp free below. */
val Message.showsBubbleChips: Boolean get() = reactions.isNotEmpty() || SavedMediaChip.isShown(this)
