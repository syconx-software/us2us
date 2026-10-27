package com.syconx.us2us.feature.conversation

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.ConversationPhotos
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.bubbles.BubbleMediaView
import com.syconx.us2us.ui.bubbles.BubbleMeta
import com.syconx.us2us.ui.bubbles.BubbleText
import com.syconx.us2us.ui.bubbles.MessageBubble
import com.syconx.us2us.ui.bubbles.mediaStateDescription
import com.syconx.us2us.ui.media.MediaGlassCircle
import com.syconx.us2us.ui.media.MediaStateGlyph
import com.syconx.us2us.ui.media.ReadyGlyph
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Radius

/** One bubble, individually tappable/long-pressable photos; only thumbnails are decoded here. */
@Composable
internal fun ConversationPhotoAlbum(row: ConversationRow, viewModel: ConversationViewModel, enabled: Boolean) {
    val photos = row.album
    val columns = if (photos.size >= 5) 3 else 2
    val edge = ((258f - 3f * (columns - 1)) / columns).dp
    MessageBubble(row.message.direction, Modifier.testTag("photoAlbum.${row.id}"), isTail = row.isTail,
        padding = PaddingValues(5.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            photos.chunked(columns).forEach { line ->
                val width = ((258f - 3f * (line.size - 1)) / line.size).dp
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    line.forEach { message ->
                        val content = message.content as MessageContent.Photo
                        val interaction = remember(message.id) { MutableInteractionSource() }
                        val pressed by interaction.collectIsPressedAsState()
                        val label = stringResource(R.string.conversation_album_photo, photos.indexOf(message) + 1, photos.size)
                        val availability = mediaStateDescription(message.mediaAvailability)
                        Box(Modifier.size(width, edge).clip(RoundedCornerShape(U2Radius.bubbleMedia))
                            .testTag("message.${message.id}")
                            .then(if (pressed || viewModel.frameRequest == message.id) Modifier.onGloballyPositioned {
                                viewModel.frames.set(message.id, it.boundsInWindow())
                            } else Modifier)
                            .graphicsLayer { alpha = if (viewModel.actions?.messageId == message.id) 0f else 1f }
                            .combinedClickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = {
                                viewModel.message(message.id)?.let(viewModel::openMedia)
                            }, onLongClick = {
                                viewModel.message(message.id)?.let { viewModel.openActions(it, row.isTail) }
                            }).semantics { contentDescription = label; stateDescription = availability }, contentAlignment = Alignment.Center) {
                            BubbleMediaView(content.source, DpSize(width, edge))
                            if (!message.mediaAvailability.isReady) {
                                MediaGlassCircle { MediaStateGlyph(message.mediaAvailability, readyGlyph = ReadyGlyph.None) }
                            }
                        }
                    }
                }
            }
        }
        (photos.first().content as MessageContent.Photo).caption?.takeIf { it.isNotBlank() }?.let {
            BubbleText(it, if (row.message.isOutgoing) U2Theme.colors.onBubbleOutgoing else U2Theme.colors.text,
                Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        }
        BubbleMeta(photos.last().sentAt, Modifier.padding(end = 8.dp).bubbleTrailing(), status = ConversationPhotos.status(photos))
    }
}
