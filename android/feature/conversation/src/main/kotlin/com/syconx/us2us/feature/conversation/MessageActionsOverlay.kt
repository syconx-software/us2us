package com.syconx.us2us.feature.conversation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.bubbles.BubbleShape
import com.syconx.us2us.ui.bubbles.MessageBubbleView
import com.syconx.us2us.ui.components.FloatingMenu
import com.syconx.us2us.ui.components.FloatingMenuItem
import com.syconx.us2us.ui.components.FloatingMenuMetrics
import com.syconx.us2us.ui.components.FloatingMenuTone
import com.syconx.us2us.ui.env.currentSafeArea
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.media.showsBubbleChips
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.u2PressEffect
import com.syconx.us2us.ui.theme.U2BackdropSource
import com.syconx.us2us.ui.theme.U2BoxShadows
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2BoxShadow
import com.syconx.us2us.ui.theme.u2ShadowMd
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlin.math.max

/**
 * 5.3 · Mesaj eylemleri: long press on a bubble.
 *
 * The chat behind is blurred 14 dp at 55 % under `#06070e@35%` (drawn by the screen). Here: a
 * column centred in the screen above a 30 dp bottom margin (gap 10): the reaction bar (308 × 52,
 * `surface`, r28, `shadowMd`, six emoji and “+”), the lifted bubble (shadow `0 16px 40px #000@40%`)
 * and the action menu (250 wide, rows 46). The bubble glides from where it was in the chat to its
 * place in the column on the sheet timing; bar and menu fade and scale from 0.96,
 * anchored at the bubble. Dismissal reverses on the micro timing.
 */
@Composable
internal fun MessageActionsOverlay(
    viewModel: ConversationViewModel,
    context: ConversationViewModel.ActionsContext,
    message: Message,
    modifier: Modifier = Modifier,
    backdrop: U2BackdropSource? = null,
) {
    val reduceMotion = LocalReduceMotion.current
    val visible = viewModel.actionsVisible
    val lift by animateFloatAsState(
        if (visible) 1f else 0f,
        if (reduceMotion) U2Motion.reducedCrossFade.spec() else if (visible) U2Motion.spring() else U2Motion.soft(0.24f).spec(),
        label = "lift",
    )
    // The glide runs on its own curve: from rest over 0.55 s (open), 0.24 s back (dismiss).
    val glide by animateFloatAsState(
        if (visible) 1f else 0f,
        if (visible) U2Motion.sheet.spec() else U2Motion.micro.spec(),
        label = "glide",
    )
    LaunchedEffect(Unit) { viewModel.liftActions() }
    val isOutgoing = message.isOutgoing
    // A trace or a photo still on this phone only takes no reaction; nothing reaches a blocked person.
    // Communities carry no reactions (Extension v2).
    val showsReactions = !message.isDeleted && !message.isPendingMedia && !viewModel.isBlocked && !viewModel.isCommunity
    val items = menuItems(viewModel, message)
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val safeTop = currentSafeArea.top
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .testTag("actions.overlay")
            .onGloballyPositioned { origin = it.positionInWindow() },
    ) {
        val source = with(density) {
            val frame = context.sourceFrame.translate(-origin)
            DpRect(frame.left.toDp(), frame.top.toDp(), frame.width.toDp(), frame.height.toDp())
        }
        val layout = ActionsLayout.of(maxWidth, maxHeight, safeTop, source, isOutgoing, showsReactions, FloatingMenuMetrics.measuredHeight(items))
        // A tap outside closes the menu.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, indication = null) { viewModel.dismissActions() }
                .clearAndSetSemantics {},
        )
        val shape = BubbleShape(message.direction, context.isTail)
        // The lift shadow lies under the bar and the menu (they come later in the design's column and cover it)…
        LiftedLayer(layout, source, isOutgoing, lift, glide, reduceMotion) {
            Box(
                Modifier
                    .graphicsLayer { alpha = lift }
                    .requiredSize(source.width, source.height - if (message.showsBubbleChips) 14.dp else 0.dp)
                    .u2BoxShadow(shape, U2BoxShadows.bubbleLifted),
            )
        }
        if (showsReactions) {
            ReactionBar(
                viewModel,
                message,
                Modifier
                    .place(layout.reactionBar)
                    .graphicsLayer {
                        alpha = lift
                        val scale = 0.96f + 0.04f * lift
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(if (isOutgoing) 1f else 0f, 1f)
                    },
            )
        }
        FloatingMenu(
            items,
            Modifier
                .place(layout.menu)
                .testTag("actions.menu")
                .graphicsLayer {
                    alpha = lift
                    val scale = 0.96f + 0.04f * lift
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(if (isOutgoing) 1f else 0f, 0f)
                },
            backdrop = backdrop,
        )
        // …the bubble itself is on top: it glides over the bar and the menu as they fade in.
        LiftedLayer(layout, source, isOutgoing, lift, glide, reduceMotion) {
            Box(Modifier.requiredSize(source.width, source.height).testTag("actions.bubble"), contentAlignment = Alignment.TopStart) {
                MessageBubbleView(message, isTail = context.isTail)
            }
        }
    }
}

/** A frame in dp. */
internal data class DpRect(val x: Dp, val y: Dp, val width: Dp, val height: Dp) {
    val bottom: Dp get() = y + height
    val centerY: Dp get() = y + height / 2
}

private fun Modifier.place(rect: DpRect): Modifier = offset(rect.x, rect.y).size(rect.width, rect.height)

/**
 * The lifted copy of the bubble (or its shadow), placed and moved like the design's lift: it glides
 * from where it was in the chat to its place in the column, while the 1.02 lift becomes the
 * column's scale. Reduce motion: no glide, a cross-fade in place.
 */
@Composable
private fun LiftedLayer(
    layout: ActionsLayout,
    source: DpRect,
    isOutgoing: Boolean,
    lift: Float,
    glide: Float,
    reduceMotion: Boolean,
    content: @Composable () -> Unit,
) {
    val travel = layout.bubble.y + source.height / 2 - source.centerY
    Box(
        Modifier
            .offset { IntOffset(source.x.roundToPx(), (source.y + travel * (if (reduceMotion) 1f else glide)).roundToPx()) }
            .graphicsLayer {
                val scale = if (reduceMotion) layout.bubbleScale else 1.02f + (layout.bubbleScale - 1.02f) * lift
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(if (isOutgoing) 1f else 0f, 0f)
                alpha = if (reduceMotion) lift else 1f
            }
            .clearAndSetSemantics {},
    ) { content() }
}

/** The six quick reactions, then “+” for the full set. */
internal val QuickReactions = listOf("❤️", "😂", "👍", "😮", "😢", "🙏")

/** Every reaction the full bar offers. */
internal val AllReactions = listOf(
    "❤️", "😂", "👍", "😮", "😢", "🙏", "🔥", "🎉", "👏", "😍", "🥲", "😅", "🤔", "😎",
    "🙌", "💯", "😡", "🤝", "✨", "👀", "🫶", "😴", "🤍", "💜",
)

@Composable
private fun ReactionBar(viewModel: ConversationViewModel, message: Message, modifier: Modifier) {
    val colors = U2Theme.colors
    val own = viewModel.ownReaction(message)
    val shape = RoundedCornerShape(U2Radius.reactionBar)
    Box(
        modifier
            .testTag("actions.reactions")
            .u2ShadowMd(shape)
            .clip(shape)
            .background(colors.surface),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (viewModel.showsAllReactions) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                AllReactions.forEach { emoji -> EmojiButton(emoji, emoji == own) { viewModel.react(emoji, message.id) } }
            }
        } else {
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                QuickReactions.forEach { emoji -> EmojiButton(emoji, emoji == own) { viewModel.react(emoji, message.id) } }
                val source = remember { MutableInteractionSource() }
                val label = stringResource(R.string.conversation_a11y_reactions_more)
                Box(
                    Modifier
                        .u2PressEffect(source, scale = 0.98f)
                        .size(40.dp)
                        .background(colors.fillGlass, CircleShape)
                        .clickable(source, indication = null, role = Role.Button) { viewModel.showAllReactions() }
                        .clearAndSetSemantics {
                            contentDescription = label
                            role = Role.Button
                        },
                    contentAlignment = Alignment.Center,
                ) { PhosphorIcon(PhosphorGlyph.Plus, 18.dp, tint = colors.text) }
            }
        }
    }
}

@Composable
private fun EmojiButton(emoji: String, isSelected: Boolean, onClick: () -> Unit) {
    val colors = U2Theme.colors
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .u2PressEffect(source, scale = 0.98f)
            .size(40.dp)
            .background(if (isSelected) colors.accent900 else colors.accent900.copy(alpha = 0f), CircleShape)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .testTag("reaction.$emoji")
            .semantics {
                contentDescription = emoji
                selected = isSelected
            },
        contentAlignment = Alignment.Center,
    ) {
        // The design's glyph rows (❤️ 249.0–270.67 in the 234–288 bar).
        U2Text(emoji, U2TextStyles.emojiReactionPicker, Modifier.offset(y = (-4f / 3f).dp).clearAndSetSemantics {}, maxLines = 1, softWrap = false)
    }
}

/**
 * Own messages: Yanıtla · Bilgi · Kopyala ‖ Herkesten sil · Benden sil. Incoming: Yanıtla ·
 * Kopyala ‖ Benden sil. A deleted trace: Benden sil. Kopyala exists only for text and is off for
 * disappearing messages. A photo or video still being prepared (or that failed to be) is only on
 * this phone: Benden sil, nothing else. Someone blocked: no Yanıtla and no Herkesten sil (and no
 * reaction bar), nothing reaches them.
 */
@Composable
private fun menuItems(viewModel: ConversationViewModel, message: Message): List<FloatingMenuItem> {
    val id = message.id
    val reply = stringResource(R.string.conversation_actions_reply)
    val info = stringResource(R.string.conversation_actions_info)
    val copy = stringResource(R.string.conversation_actions_copy)
    val copyDisabled = stringResource(R.string.conversation_actions_copy_disabled)
    val save = stringResource(R.string.conversation_file_savetofiles)
    val saveHint = stringResource(R.string.conversation_viewer_save_hint)
    val deleteEveryone = stringResource(R.string.conversation_actions_deleteeveryone)
    val deleteMe = stringResource(R.string.conversation_actions_deleteme)
    if (message.isPendingMedia) {
        return listOf(
            FloatingMenuItem("deleteMe", deleteMe, PhosphorGlyph.TrashSimple, tone = FloatingMenuTone.Destructive) { viewModel.deleteForMe(id) },
        )
    }
    if (viewModel.isCommunity) return communityMenuItems(viewModel, message)
    val items = mutableListOf<FloatingMenuItem>()
    // Blocked: only what stays on this phone (no reply, no “Herkesten sil”).
    val isBlocked = viewModel.isBlocked
    if (!message.isDeleted) {
        if (!isBlocked) items += FloatingMenuItem("reply", reply, PhosphorGlyph.ArrowBendUpLeft) { viewModel.reply(id) }
        if (message.isOutgoing) items += FloatingMenuItem("info", info, PhosphorGlyph.Info) { viewModel.showInfo(id) }
        if (message.copyableText != null) {
            items += FloatingMenuItem(
                "copy", copy, PhosphorGlyph.Copy,
                hint = if (message.isDisappearing) copyDisabled else null,
                isEnabled = !message.isDisappearing,
            ) { viewModel.copy(id) }
        }
        // A file that is here: “Dosyalara kaydet” (proposed). Off for disappearing messages; on
        // something received its second line says that the sender will see it.
        if (message.content is MessageContent.File && message.mediaAvailability.isReady) {
            items += FloatingMenuItem(
                "saveToFiles", save, PhosphorGlyph.Export,
                hint = when {
                    message.isDisappearing -> copyDisabled
                    message.isOutgoing -> null
                    else -> saveHint
                },
                isEnabled = !message.isDisappearing,
            ) { viewModel.saveToFiles(id) }
        }
    }
    if (message.isOutgoing && !message.isDeleted && !isBlocked) {
        items += FloatingMenuItem(
            "deleteEveryone", deleteEveryone, PhosphorGlyph.Trash,
            tone = FloatingMenuTone.Destructive,
            startsGroup = items.isNotEmpty(),
        ) { viewModel.askDeleteForEveryone(id) }
        items += FloatingMenuItem("deleteMe", deleteMe, PhosphorGlyph.TrashSimple, tone = FloatingMenuTone.Destructive) { viewModel.deleteForMe(id) }
    } else {
        items += FloatingMenuItem(
            "deleteMe", deleteMe, PhosphorGlyph.TrashSimple,
            tone = FloatingMenuTone.Destructive,
            startsGroup = items.isNotEmpty(),
        ) { viewModel.deleteForMe(id) }
    }
    return items
}

/**
 * A community message: Bilgi (own) · Kopyala · Dosyalara kaydet ‖ Herkesten sil (own; every
 * message for the owner) · Benden sil (every message). No reply and no reaction (Extension v2).
 */
@Composable
private fun communityMenuItems(viewModel: ConversationViewModel, message: Message): List<FloatingMenuItem> {
    val id = message.id
    val info = stringResource(R.string.conversation_actions_info)
    val copy = stringResource(R.string.conversation_actions_copy)
    val save = stringResource(R.string.conversation_file_savetofiles)
    val deleteEveryone = stringResource(R.string.conversation_actions_deleteeveryone)
    val deleteMe = stringResource(R.string.conversation_actions_deleteme)
    val items = mutableListOf<FloatingMenuItem>()
    if (!message.isDeleted) {
        if (message.isOutgoing) items += FloatingMenuItem("info", info, PhosphorGlyph.Info) { viewModel.showInfo(id) }
        message.copyableText?.let { items += FloatingMenuItem("copy", copy, PhosphorGlyph.Copy) { viewModel.copy(id) } }
        if (message.content is MessageContent.File && message.mediaAvailability.isReady) {
            items += FloatingMenuItem("saveToFiles", save, PhosphorGlyph.Export) { viewModel.saveToFiles(id) }
        }
        if (message.community?.canDeleteForEveryone == true) {
            items += FloatingMenuItem(
                "deleteEveryone", deleteEveryone, PhosphorGlyph.Trash,
                tone = FloatingMenuTone.Destructive,
                startsGroup = items.isNotEmpty(),
            ) { viewModel.askDeleteForEveryone(id) }
        }
    }
    items += FloatingMenuItem(
        "deleteMe", deleteMe, PhosphorGlyph.TrashSimple,
        tone = FloatingMenuTone.Destructive,
        startsGroup = items.isNotEmpty() && items.none { it.id == "deleteEveryone" },
    ) { viewModel.deleteForMe(id) }
    return items
}

/**
 * Where the 5.3 column goes: centred in the screen above a 30 dp margin, right-aligned (outgoing)
 * or left-aligned (incoming) at the 14 dp gutter. A bubble too tall to fit is scaled down so the
 * bar and the menu stay on screen.
 */
internal data class ActionsLayout(
    val reactionBar: DpRect,
    val bubble: DpRect,
    val bubbleScale: Float,
    val menu: DpRect,
) {
    companion object {
        val reactionBarWidth = 308.dp
        val reactionBarHeight = 52.dp
        val menuWidth = 250.dp
        val gap = 10.dp
        val bottomMargin = 30.dp
        val gutter = 14.dp

        fun of(width: Dp, height: Dp, safeTop: Dp, source: DpRect, isOutgoing: Boolean, showsReactions: Boolean, menuHeight: Dp): ActionsLayout {
            val barHeight = if (showsReactions) reactionBarHeight + gap else 0.dp
            val available = height - bottomMargin
            val minTop = safeTop + 8.dp
            val room = available - minTop - barHeight - gap - menuHeight
            val scale = if (source.height > room) max(0.4f, room / source.height) else 1f
            val bubbleHeight = source.height * scale
            val column = barHeight + bubbleHeight + gap + menuHeight
            val top = maxOf(minTop, (available - column) / 2)
            fun aligned(w: Dp, y: Dp, h: Dp) = DpRect(if (isOutgoing) width - gutter - w else gutter, y, w, h)
            val bubbleY = top + barHeight
            return ActionsLayout(
                reactionBar = aligned(reactionBarWidth, top, reactionBarHeight),
                bubble = DpRect(source.x, bubbleY, source.width * scale, bubbleHeight),
                bubbleScale = scale,
                menu = aligned(menuWidth, bubbleY + bubbleHeight + gap, menuHeight),
            )
        }
    }
}

