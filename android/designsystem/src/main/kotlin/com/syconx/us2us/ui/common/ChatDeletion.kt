package com.syconx.us2us.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.syconx.us2us.ui.components.FloatingMenu
import com.syconx.us2us.ui.components.FloatingMenuMetrics
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.theme.U2BackdropSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.ConfirmationTags
import com.syconx.us2us.ui.components.DestructiveConfirmation
import com.syconx.us2us.ui.components.FloatingMenuItem
import com.syconx.us2us.ui.components.FloatingMenuTone
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.sheet.U2Sheet
import com.syconx.us2us.ui.sheet.U2SheetStyle

/**
 * Deleting on this phone only (4.1 long press, the contact screen's “…”): “Sohbeti sil” deletes the
 * chat's messages and media and keeps the contact, “Kişiyi sil” deletes the contact too. Nothing is
 * sent to the other phone, and deleting is not blocking: the session and identity stay (PROTOCOL
 * §6.4), so if the contact writes again it appears as a new request. Someone blocked stays blocked.
 */
enum class ChatDeletionKind { Chat, Contact }

/** One deletion waiting for its confirmation: what, of which chat, and the name the chat shows. */
@Immutable
data class ChatDeletion(val chat: ChatId, val kind: ChatDeletionKind, val name: String, val isBlocked: Boolean)

/** Test tags of the deletion menu items and the confirmation's buttons. */
object ChatDeletionTags {
    const val DELETE_CHAT = "delete.chat"
    const val DELETE_CONTACT = "delete.contact"
    const val CONFIRM = "delete.confirm"
    const val CANCEL = "delete.cancel"

    /** The long-press menu of a chat row; its rows are `menu.delete.chat` and `menu.delete.contact`. */
    const val MENU = "chat.menu"
}

/** The two destructive items, in their own group (“Sohbeti sil” · “Kişiyi sil”). */
@Composable
fun chatDeletionMenuItems(chat: ChatId, name: String, isBlocked: Boolean, onRequest: (ChatDeletion) -> Unit): List<FloatingMenuItem> = listOf(
    FloatingMenuItem(
        id = ChatDeletionTags.DELETE_CHAT,
        title = stringResource(R.string.common_delete_chat),
        glyph = PhosphorGlyph.TrashSimple,
        tone = FloatingMenuTone.Destructive,
        startsGroup = true,
    ) { onRequest(ChatDeletion(chat, ChatDeletionKind.Chat, name, isBlocked)) },
    FloatingMenuItem(
        id = ChatDeletionTags.DELETE_CONTACT,
        title = stringResource(R.string.common_delete_contact),
        glyph = PhosphorGlyph.Trash,
        tone = FloatingMenuTone.Destructive,
    ) { onRequest(ChatDeletion(chat, ChatDeletionKind.Contact, name, isBlocked)) },
)

/**
 * The confirmation (a floating sheet, like “Tümü silinsin mi?”): it says that the deletion happens
 * on this phone only and cannot be undone, and for a contact that it is not blocked and appears as a
 * new request if it writes again, or for someone blocked that the block stays. [onConfirm] runs once the user chose “Sil”.
 */
@Composable
fun ChatDeletionSheet(item: ChatDeletion?, onConfirm: (ChatDeletion) -> Unit, onDismiss: () -> Unit) {
    U2Sheet(item = item, onDismissRequest = onDismiss, style = U2SheetStyle.Floating) { deletion ->
        val (title, message) = when (deletion.kind) {
            ChatDeletionKind.Chat -> R.string.common_delete_chat_title to R.string.common_delete_chat_message
            ChatDeletionKind.Contact ->
                R.string.common_delete_contact_title to if (deletion.isBlocked) R.string.common_delete_contact_message_blocked else R.string.common_delete_contact_message
        }
        DestructiveConfirmation(
            title = stringResource(title, deletion.name),
            message = stringResource(message),
            confirmTitle = stringResource(R.string.common_request_delete),
            onConfirm = {
                onDismiss()
                onConfirm(deletion)
            },
            onCancel = onDismiss,
            tags = ConfirmationTags(ChatDeletionTags.CONFIRM, ChatDeletionTags.CANCEL),
            modifier = Modifier.padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 28.dp),
        )
    }
}

/**
 * 4.1 long press on a one-to-one chat: what the menu is about, and where the pressed row is ([anchor],
 * its bounds in the window) so the menu opens at it.
 */
@Immutable
data class ChatRowActions(val chat: ChatId, val name: String, val isBlocked: Boolean, val anchor: Rect = Rect.Zero)

/** Where [ChatRowMenu] goes: under the row, or above it when there is no room below. */
internal object ChatRowMenuPlacement {
    /** Between the row and the menu. */
    val gap = 6.dp

    /** Kept clear at the window's edges. */
    val margin = 12.dp

    /** Whether a menu [menuHeight] high fits under [anchor] in a window [windowHeight] high (all px). */
    fun opensBelow(anchor: Rect, menuHeight: Float, windowHeight: Float, gap: Float, margin: Float): Boolean =
        anchor.bottom + gap + menuHeight <= windowHeight - margin || anchor.top - gap - menuHeight < margin

    /** The menu's top-left corner in the window, at the row's leading gutter. */
    fun position(
        anchor: Rect,
        menu: IntSize,
        window: IntSize,
        below: Boolean,
        gutter: Float,
        gap: Float,
        margin: Float,
        rtl: Boolean,
    ): IntOffset {
        val x = if (rtl) anchor.right - gutter - menu.width else anchor.left + gutter
        val y = if (below) anchor.bottom + gap else anchor.top - gap - menu.height
        val maxX = (window.width - menu.width - margin).coerceAtLeast(margin)
        val maxY = (window.height - menu.height - margin).coerceAtLeast(margin)
        return IntOffset(x.coerceIn(margin, maxX).roundToInt(), y.coerceIn(margin, maxY).roundToInt())
    }
}

/**
 * 4.1 long press on a one-to-one chat (proposed), the same menu as iOS's context menu: a compact
 * [FloatingMenu] at the pressed row (under it, above it when the row is low on the screen), at the
 * row's leading gutter, with “Sohbeti sil” · “Kişiyi sil” (danger, glyph trailing). Place it over
 * the whole screen, like [com.syconx.us2us.ui.components.U2AnchoredMenu]: it opens with the 5.3 pop
 * (fade + scale from 0.96 at the edge next to the row); a tap outside or back closes it. A choice
 * closes the menu first and, once it has faded, hands its deletion to [onChoose] for its
 * confirmation ([ChatDeletionSheet]), so the two never overlap.
 */
@Composable
fun ChatRowMenu(
    item: ChatRowActions?,
    onDismiss: () -> Unit,
    onChoose: (ChatDeletion) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: U2BackdropSource? = null,
) {
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // The last menu shown, so it can fade out after [item] went.
    var shown by remember { mutableStateOf<ChatRowActions?>(null) }
    if (item != null && item != shown) shown = item
    // Where this overlay sits in the window (the row's bounds are window coordinates).
    val origin = remember { OverlayOrigin() }
    val timing = U2Motion.resolve(if (item != null) U2Motion.state else U2Motion.micro, reduceMotion)
    if (item != null) {
        BackHandler(onBack = onDismiss)
        Box(
            modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .clearAndSetSemantics {},
        )
    }
    Box(modifier.fillMaxSize().onGloballyPositioned { origin.offset = it.positionInWindow() }) {
        val actions = shown ?: return@Box
        val items = chatDeletionMenuItems(actions.chat, actions.name, actions.isBlocked) { deletion ->
            onDismiss()
            scope.launch {
                delay(FloatingMenuMetrics.ACTION_DELAY_MILLIS)
                onChoose(deletion)
            }
        }
        val menuHeight = with(density) { FloatingMenuMetrics.measuredHeight(items).toPx() }
        val windowHeight = LocalWindowInfo.current.containerSize.height.toFloat()
        val below = with(density) {
            ChatRowMenuPlacement.opensBelow(actions.anchor, menuHeight, windowHeight, ChatRowMenuPlacement.gap.toPx(), ChatRowMenuPlacement.margin.toPx())
        }
        val transformOrigin = TransformOrigin(if (rtl) 1f else 0f, if (below) 0f else 1f)
        AnimatedVisibility(
            visible = item != null,
            enter = if (reduceMotion) fadeIn(timing.spec()) else fadeIn(timing.spec()) + scaleIn(timing.spec(), initialScale = 0.96f, transformOrigin = transformOrigin),
            exit = if (reduceMotion) fadeOut(timing.spec()) else fadeOut(timing.spec()) + scaleOut(timing.spec(), targetScale = 0.96f, transformOrigin = transformOrigin),
            modifier = Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val window = IntSize(constraints.maxWidth, constraints.maxHeight)
                val local = actions.anchor.translate(-origin.offset)
                val position = ChatRowMenuPlacement.position(
                    local,
                    IntSize(placeable.width, placeable.height),
                    window,
                    below,
                    gutter = U2Spacing.screenGutter.toPx(),
                    gap = ChatRowMenuPlacement.gap.toPx(),
                    margin = ChatRowMenuPlacement.margin.toPx(),
                    rtl = rtl,
                )
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(position) }
            },
        ) {
            FloatingMenu(items, Modifier.testTag(ChatDeletionTags.MENU).semantics { paneTitle = actions.name }, backdrop = backdrop)
        }
    }
}

/** Kept outside the snapshot system: read only when the menu is laid out. */
private class OverlayOrigin {
    var offset: Offset = Offset.Zero
}

/**
 * The row's accessibility actions (as iOS's `accessibilityAction`s): “Sohbeti sil” and “Kişiyi sil”
 * go straight to their confirmation, without the menu.
 */
@Composable
fun chatDeletionAccessibilityActions(chat: ChatId, name: String, isBlocked: Boolean, onRequest: (ChatDeletion) -> Unit): List<CustomAccessibilityAction> {
    val deleteChat = stringResource(R.string.common_delete_chat)
    val deleteContact = stringResource(R.string.common_delete_contact)
    return listOf(
        CustomAccessibilityAction(deleteChat) { onRequest(ChatDeletion(chat, ChatDeletionKind.Chat, name, isBlocked)); true },
        CustomAccessibilityAction(deleteContact) { onRequest(ChatDeletion(chat, ChatDeletionKind.Contact, name, isBlocked)); true },
    )
}

/**
 * Runs a confirmed deletion. A deleted chat leaves 4.1 and every screen showing it (they follow the
 * messenger); the announcement is said by 4.1's [ChatDeletionAnnouncementRegion], which stays when
 * the contact screen goes. Returns whether something was deleted.
 */
suspend fun MessengerService.perform(deletion: ChatDeletion): Boolean {
    val deleted = when (deletion.kind) {
        ChatDeletionKind.Chat -> deleteChat(deletion.chat)
        ChatDeletionKind.Contact -> deleteContact(deletion.chat)
    }
    if (deleted) ChatDeletionAnnouncements.deleted(deletion.kind)
    return deleted
}

/** The last deletion, for TalkBack (iOS posts an announcement that outlives the screen). Memory only. */
object ChatDeletionAnnouncements {
    var last: ChatDeletionKind? by mutableStateOf(null)
        private set

    /** Grows with every deletion: the same sentence twice is still a change of the live region. */
    var count: Int by mutableIntStateOf(0)
        private set

    fun deleted(kind: ChatDeletionKind) {
        last = kind
        count++
    }
}

/** A polite live region on 4.1 that says “Sohbet silindi” / “Kişi silindi” after a deletion; it draws nothing. */
@Composable
fun ChatDeletionAnnouncementRegion(modifier: Modifier = Modifier) {
    val kind = ChatDeletionAnnouncements.last
    val text = when (kind) {
        ChatDeletionKind.Chat -> stringResource(R.string.common_a11y_chatdeleted)
        ChatDeletionKind.Contact -> stringResource(R.string.common_a11y_contactdeleted)
        null -> null
    }?.let { if (ChatDeletionAnnouncements.count % 2 == 0) it else "$it​" }
    Box(
        modifier
            .size(1.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                text?.let { contentDescription = it } ?: hideFromAccessibility()
            },
    )
}
