package com.syconx.us2us.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.CommunityChat
import com.syconx.us2us.domain.CommunityFormat
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2InnerStroke

/**
 * A community's mark where a person's avatar would be (the chat list, the conversation header): the
 * C12 circle at the same [size], `accent900` with the inner 1 dp `accent800` ring of the C13 tiles
 * and the communities glyph (`ph:chat-circle-dots`) in `accent300`. TalkBack reads its name.
 */
@Composable
fun CommunityAvatar(name: String, modifier: Modifier = Modifier, size: AvatarSize = AvatarSize.ListRow) {
    val colors = U2Theme.colors
    Box(
        modifier
            .size(size.diameter)
            .background(colors.accent900, CircleShape)
            .u2InnerStroke(colors.accent800, 1.dp, CircleShape)
            .clearAndSetSemantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        PhosphorIcon(PhosphorGlyph.ChatCircleDots, size = size.diameter * 0.46f, tint = colors.accent300)
    }
}

/** A community's name as the screens show it: a private one without a name kept here is “Özel topluluk”. */
@Composable
fun communityName(community: CommunityChat): String = community.name.ifEmpty { stringResource(R.string.community_private) }

/** “Sen”, a verified contact's username, else “Üye · 1a2b3c4d”: never a whole account id. */
@Composable
fun communityMemberName(account: String, username: String?, isSelf: Boolean = false): String = when {
    isSelf -> stringResource(R.string.community_you)
    username != null -> "@$username"
    else -> stringResource(R.string.community_member_short, CommunityFormat.shortId(account))
}
