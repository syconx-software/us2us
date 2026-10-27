package com.syconx.us2us.feature.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.media.MediaGlyph
import com.syconx.us2us.ui.media.MediaGlyphIcon
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.u2PressEffect
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2InnerStroke
import kotlinx.coroutines.launch

/** Lives above the composer/banners, without changing the message viewport. */
@Composable
internal fun ConversationLatestButton(viewModel: ConversationViewModel, modifier: Modifier = Modifier) {
    val list = viewModel.listState
    val threshold = with(LocalDensity.current) { 80.dp.toPx() }
    val visible by remember(viewModel, threshold) {
        derivedStateOf { viewModel.rows.isNotEmpty() && (list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset >= threshold) }
    }
    val reduceMotion = LocalReduceMotion.current
    // Keep this scope outside AnimatedVisibility: hiding near the bottom must not cancel the trip.
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(R.string.conversation_scroll_latest)
    val colors = U2Theme.colors
    AnimatedVisibility(visible, modifier,
        enter = fadeIn(U2Motion.micro.spec()) + if (reduceMotion) androidx.compose.animation.EnterTransition.None else scaleIn(U2Motion.micro.spec(), initialScale = 0.94f),
        exit = fadeOut(U2Motion.reducedCrossFade.spec())) {
        Box(Modifier.size(48.dp)
            .u2PressEffect(interaction)
            .shadow(4.dp, CircleShape)
            .background(colors.surface.copy(alpha = 0.96f), CircleShape)
            .u2InnerStroke(colors.divider, 0.5.dp, CircleShape)
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = label) {
                scope.launch {
                    if (reduceMotion) list.scrollToItem(0) else list.animateScrollToItem(0)
                }
            }
            .semantics { contentDescription = label }
            .testTag("conversation.latest"), contentAlignment = Alignment.Center) {
            MediaGlyphIcon(MediaGlyph.ArrowDown, 18.dp, tint = colors.accent300)
        }
    }
}
