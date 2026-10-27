package com.syconx.us2us.feature.chats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.ChatSummary
import com.syconx.us2us.domain.CommunityChats
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.Overlay
import com.syconx.us2us.domain.nav.Phase
import com.syconx.us2us.domain.nav.QrScanMode
import com.syconx.us2us.feature.chats.invite.InviteDebugState
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.common.ChatDeletionAnnouncementRegion
import com.syconx.us2us.ui.common.ChatRowActions
import com.syconx.us2us.ui.common.ChatDeletion
import com.syconx.us2us.ui.common.ChatDeletionSheet
import com.syconx.us2us.ui.common.ChatRowMenu
import com.syconx.us2us.ui.common.chatDeletionAccessibilityActions
import com.syconx.us2us.ui.common.MyCodeSheet
import com.syconx.us2us.ui.common.perform
import com.syconx.us2us.ui.common.preparedOwnContactCode
import com.syconx.us2us.ui.components.Avatar
import com.syconx.us2us.ui.components.AvatarSize
import com.syconx.us2us.ui.components.FloatingMenuItem
import com.syconx.us2us.ui.components.LocalU2Ticker
import com.syconx.us2us.ui.components.U2AnchoredMenu
import com.syconx.us2us.ui.components.U2ButtonLabel
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.components.groupedRowPress
import com.syconx.us2us.ui.components.rememberU2Ticker
import com.syconx.us2us.ui.components.u2CardPress
import com.syconx.us2us.ui.env.LocalAppModel
import com.syconx.us2us.ui.env.LocalFeatureScreens
import com.syconx.us2us.ui.env.LocalHarnessState
import com.syconx.us2us.ui.env.LocalOwnAvatar
import com.syconx.us2us.ui.env.u2Top
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.nav.LocalRouter
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.sheet.U2FadeCover
import com.syconx.us2us.ui.sheet.U2Sheet
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.rememberU2BackdropSource
import com.syconx.us2us.ui.theme.u2BackdropSource
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

/** Test tags of 4.1 (the iOS accessibility identifiers). */
object ChatsTags {
    const val LIST = "chats.list"
    const val SETTINGS = "chats.settings"
    const val QR = "chats.qr"
    const val NEW = "chats.new"
    const val CONNECTION = "chats.connection"
    const val REQUESTS = "chats.requests"
    const val ACCESS = "chats.access"
    const val EMPTY_NEW = "chats.empty.new"
    const val TITLE = "chats.title"

    fun chat(id: ChatId): String = "chat.${id.value}"
}

private val presentationIds = AtomicLong()

/** One presentation of the 4.2 sheet. */
@Immutable
data class NewChatPresentation(
    val initialQuery: String = "",
    val debug: NewChatModel.DebugOptions = NewChatModel.DebugOptions(),
    val id: Long = presentationIds.incrementAndGet(),
)

/** One presentation of the own-code sheet: the payload travels with it, so the sheet never shows a value captured before the code was loaded. */
@Immutable
data class MyCodePresentation(val payload: String?, val id: Long = presentationIds.incrementAndGet())

/** One presentation of the invite sheet over 4.1 (the harness, 7.1 has its own). */
@Immutable
data class InvitePresentation(val debug: InviteDebugState, val id: Long = presentationIds.incrementAndGet())

/**
 * 4.1 · Sohbet listesi, the root of the main stack.
 *
 * Top bar (own avatar → 7.1, QR menu, compose → 4.2), the “Sohbetler” title with the connection
 * line that flows while the line is open, the requests card (only when there are requests), and
 * the chat rows with their status, presence dot (accepted contacts only) and Ghost pill / unread
 * badge.
 *
 * The top bar stays put and the list scrolls under it (inventory 4.1); at rest the bar is exactly
 * as drawn, and once rows pass beneath it the chat header's translucent band fades in (proposed).
 * New messages move a row to the top with the soft 0.5 s reorder.
 */
@Composable
internal fun ChatsView(modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val messenger = app.messenger
    val router = LocalRouter.current
    val screens = LocalFeatureScreens.current
    val harness = LocalHarnessState.current as? ChatsUiState
    val scope = rememberCoroutineScope()

    val allChats by messenger.chats.collectAsStateWithLifecycle()
    val chats = if (harness?.hidesChats == true) emptyList() else allChats

    var communityRequests by remember { mutableStateOf<CommunityRequestInboxRow?>(null) }
    var inboxRefresh by remember { mutableStateOf(0) }
    val communityInbox = rememberCommunityRequestInbox(messenger.communities, inboxRefresh) { communityRequests = null }
    var newChat by remember { mutableStateOf<NewChatPresentation?>(null) }
    var showsQRMenu by remember { mutableStateOf(false) }
    var myCode by remember { mutableStateOf<MyCodePresentation?>(null) }
    var showsScanner by remember { mutableStateOf(false) }
    var showsCommunities by remember { mutableStateOf(false) }
    // 4.2 › Topluluklar: the sheet goes down first, then the communities rise; one opened there is pushed once they have gone.
    var communitiesAfterSheet by remember { mutableStateOf(false) }
    var pendingCommunity by remember { mutableStateOf<ChatId?>(null) }
    var invite by remember { mutableStateOf<InvitePresentation?>(null) }
    var pendingChat by remember { mutableStateOf<ChatId?>(null) }
    // A long press on a one-to-one chat: a menu at the row with “Sohbeti sil” · “Kişiyi sil”, each
    // after its confirmation ([deletion]; TalkBack's row actions go there directly).
    var rowActions by remember { mutableStateOf<ChatRowActions?>(null) }
    var deletion by remember { mutableStateOf<ChatDeletion?>(null) }
    // The own contact code, loaded up front so “Kodumu göster” rises complete.
    var myCodePayload by remember { mutableStateOf<String?>(null) }
    var harnessApplied by remember { mutableStateOf(false) }

    LaunchedEffect(messenger) { myCodePayload = messenger.preparedOwnContactCode() }

    fun showMyCode() {
        scope.launch {
            if (myCodePayload == null) myCodePayload = messenger.preparedOwnContactCode()
            myCode = MyCodePresentation(myCodePayload)
        }
    }

    // An invite link: 4.2 over 4.1 with the name searched, the same exact lookup as typing it;
    // nothing is sent until “Mesaj yaz”. Whatever is open goes first (the stack pops softly, sheets
    // go down), so the new sheet never meets another presentation.
    fun openInvite(username: String) {
        val isBusy = !router.isAtRoot || newChat != null || myCode != null || showsScanner || showsQRMenu || invite != null
        showsQRMenu = false
        pendingChat = null
        newChat = null
        myCode = null
        invite = null
        showsScanner = false
        router.popToRoot()
        scope.launch {
            if (isBusy) delay((U2Motion.Duration.sheet + 0.25).seconds)
            newChat = NewChatPresentation(initialQuery = username)
        }
    }

    // 4.2 “Mesaj yaz”: the sheet goes down first, then 5.1 is pushed.
    fun openPendingChat() {
        if (communitiesAfterSheet) {
            communitiesAfterSheet = false
            showsCommunities = true
            return
        }
        val chat = pendingChat ?: return
        pendingChat = null
        router.push(AppRoute.Conversation(chat))
    }

    val inbox = InviteLinkInbox.shared
    val isReady = app.phase == Phase.Unlocked && app.overlay == Overlay.None
    LaunchedEffect(isReady, inbox.pending) {
        if (isReady && inbox.pending != null) inbox.take()?.let(::openInvite)
    }

    LaunchedEffect(Unit) {
        if (harnessApplied) return@LaunchedEffect
        harnessApplied = true
        if (harness != null) {
            harness.newChatQuery?.let { newChat = NewChatPresentation(initialQuery = it, debug = harness.newChatDebug) }
            if (harness.showsQRMenu) showsQRMenu = true
            if (harness.showsMyCode) showMyCode()
            harness.invite?.let { invite = InvitePresentation(it) }
        }
        when (ChatsDemo.fromRaw(app.configuration.chatsDemo)) {
            ChatsDemo.QrMyCode -> {
                delay(ChatsDemo.DELAY_MILLIS)
                showsQRMenu = true
                delay(1_200)
                showsQRMenu = false
                showMyCode()
            }
            ChatsDemo.Compose -> {
                delay(ChatsDemo.DELAY_MILLIS)
                newChat = NewChatPresentation()
            }
            ChatsDemo.OpenChat -> {
                delay(ChatsDemo.DELAY_MILLIS)
                messenger.chats.value.firstOrNull()?.let { router.push(AppRoute.Conversation(it.id)) }
            }
            ChatsDemo.PushPop -> {
                delay(ChatsDemo.DELAY_MILLIS)
                repeat(5) {
                    router.push(AppRoute.Conversation(ChatId("deniz")))
                    delay(1_400)
                    router.pop()
                    delay(1_400)
                }
            }
            ChatsDemo.SheetCycle -> {
                delay(ChatsDemo.DELAY_MILLIS)
                repeat(5) {
                    newChat = NewChatPresentation()
                    delay(1_800)
                    newChat = null
                    delay(1_400)
                }
            }
            else -> Unit
        }
    }

    val ticker = rememberU2Ticker()
    val backdrop = rememberU2BackdropSource()
    val listState = rememberLazyListState()
    val scrollThreshold = with(LocalDensity.current) { 2.dp.toPx() }
    val isScrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > scrollThreshold } }

    U2Screen(modifier) {
        CompositionLocalProvider(LocalU2Ticker provides ticker) {
            ChatList(
                chats = chats,
                communityInbox = communityInbox,
                onCommunityRequests = { communityRequests = it },
                listState = listState,
                onNewChat = { newChat = NewChatPresentation() },
                onLongPress = { chat, anchor -> rowActions = ChatRowActions(chat.id, chat.peer.username, chat.peer.isBlocked, anchor) },
                onDelete = { deletion = it },
                modifier = Modifier.u2BackdropSource(backdrop),
            )
        }
        // TalkBack hears “Sohbet silindi” / “Kişi silindi” (also after a deletion on the contact screen).
        ChatDeletionAnnouncementRegion()
        ChatsHeaderBackdrop(isVisible = isScrolled, source = backdrop)
        TopBar(onQr = { showsQRMenu = !showsQRMenu }, onNewChat = { newChat = NewChatPresentation() })
        U2AnchoredMenu(
            isPresented = showsQRMenu,
            onDismiss = { showsQRMenu = false },
            items = listOf(
                FloatingMenuItem(id = "scan", title = stringResource(R.string.chats_qr_add), glyph = PhosphorGlyph.Scan) { showsScanner = true },
                FloatingMenuItem(id = "myCode", title = stringResource(R.string.chats_qr_mine), glyph = PhosphorGlyph.QrCode) { showMyCode() },
            ),
            designTop = 106.dp,
            trailing = 70.dp,
            backdrop = backdrop,
        )
        // A row's long press: the menu at the row (as iOS's context menu).
        ChatRowMenu(item = rowActions, onDismiss = { rowActions = null }, onChoose = { deletion = it }, backdrop = backdrop)
    }

    U2FadeCover(
        isPresented = showsCommunities,
        onEscape = { showsCommunities = false },
        onDismissed = {
            pendingCommunity?.let { chat ->
                pendingCommunity = null
                // A community just created or joined is in 4.1 a moment later; 5.1 leaves a chat that is not listed.
                scope.launch {
                    withTimeoutOrNull(3_000) { messenger.chats.first { list -> list.any { it.id == chat } } }
                    router.push(AppRoute.Conversation(chat))
                }
            }
        },
    ) {
        messenger.communities?.let { service ->
            CommunitiesScreen(
                service,
                onClose = { showsCommunities = false },
                onOpen = { id ->
                    pendingCommunity = CommunityChats.chatId(id)
                    showsCommunities = false
                },
            )
        }
    }
    U2Sheet(
        item = communityRequests,
        onDismissRequest = { communityRequests = null },
        onDismissed = { inboxRefresh += 1 },
        style = com.syconx.us2us.ui.sheet.U2SheetStyle.FullHeight,
    ) { inbox ->
        messenger.communities?.let { service ->
            screens.CommunityManage(service, inbox.group.id, inbox.group.name, onClose = { communityRequests = null })
        }
    }
    U2Sheet(item = newChat, onDismissRequest = { newChat = null }, onDismissed = ::openPendingChat) { presentation ->
        key(presentation.id) {
            NewChatSheetContent(
                presentation = presentation,
                onOpenChat = { chat ->
                    pendingChat = chat
                    newChat = null
                },
                onCancel = { newChat = null },
                onCommunities = if (messenger.communities != null) {
                    {
                        communitiesAfterSheet = true
                        newChat = null
                    }
                } else {
                    null
                },
            )
        }
    }
    U2Sheet(item = myCode, onDismissRequest = { myCode = null }) { presentation ->
        val username by messenger.ownUsername.collectAsStateWithLifecycle()
        MyCodeSheet(username = username, payload = presentation.payload, onClose = { myCode = null })
    }
    U2Sheet(item = invite, onDismissRequest = { invite = null }) { presentation ->
        key(presentation.id) { InviteSheetContent(presentation.debug, onClose = { invite = null }) }
    }
    ChatDeletionSheet(deletion, onConfirm = { chosen -> scope.launch { messenger.perform(chosen) } }, onDismiss = { deletion = null })
    // 6.2 from 4.1: a match adds and verifies; “Sohbete dön” pushes 5.1 under the fading cover.
    U2FadeCover(isPresented = showsScanner, onEscape = { showsScanner = false }) {
        screens.QrScan(QrScanMode.AddContact, onDismiss = { showsScanner = false }, modifier = Modifier)
    }
}

/** Row at design y 56, pad 0 16: own avatar 40 (palette D) · QR + compose (gap 10). */
@Composable
private fun TopBar(onQr: () -> Unit, onNewChat: () -> Unit) {
    val app = LocalAppModel.current
    val router = LocalRouter.current
    val ownUsername by app.messenger.ownUsername.collectAsStateWithLifecycle()
    val settings = stringResource(R.string.common_a11y_settings)
    Row(
        Modifier
            .fillMaxWidth()
            .u2Top(U2Spacing.headerTopButtons)
            .padding(horizontal = U2Spacing.screenGutterS)
            .height(U2Size.iconButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .u2CardPress(onClick = { router.push(AppRoute.Settings) })
                .clearAndSetSemantics {
                    contentDescription = settings
                    role = Role.Button
                    testTag = ChatsTags.SETTINGS
                },
        ) {
            Avatar(ownUsername, AvatarPalette.D, size = AvatarSize.ChatsSelf, picture = LocalOwnAvatar.current)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            U2GlassIconButton(
                glyph = PhosphorGlyph.QrCode,
                contentDescription = stringResource(R.string.common_a11y_qr),
                onClick = onQr,
                modifier = Modifier.testTag(ChatsTags.QR),
            )
            U2GlassIconButton(
                glyph = PhosphorGlyph.NotePencil,
                contentDescription = stringResource(R.string.common_a11y_newchat),
                onClick = onNewChat,
                tint = U2Theme.colors.accent300,
                modifier = Modifier.testTag(ChatsTags.NEW),
            )
        }
    }
}

@Composable
private fun ChatList(
    chats: List<ChatSummary>,
    communityInbox: List<CommunityRequestInboxRow>,
    onCommunityRequests: (CommunityRequestInboxRow) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onNewChat: () -> Unit,
    onLongPress: (ChatSummary, Rect) -> Unit,
    onDelete: (ChatDeletion) -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalAppModel.current
    val messenger = app.messenger
    val router = LocalRouter.current
    val reduceMotion = LocalReduceMotion.current
    val connection by messenger.connection.collectAsStateWithLifecycle()
    val requests by messenger.requests.collectAsStateWithLifecycle()
    val restoreAccessPending by messenger.restoreAccessPending.collectAsStateWithLifecycle()
    val state = U2Motion.resolve(U2Motion.state, reduceMotion)
    val screen = U2Motion.resolve(U2Motion.screen, reduceMotion)
    val fade = U2Transitions.fade()
    val rise = U2Transitions.rise()

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .testTag(ChatsTags.LIST),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item(key = "title", contentType = "title") {
            TitleBlock(connection, Modifier.u2Top(100.dp))
        }
        items(communityInbox, key = { "communityRequests.${it.group.id}" }, contentType = { "card" }) { inbox ->
            RequestsCard(
                count = inbox.count,
                communityName = inbox.group.name,
                onClick = { onCommunityRequests(inbox) },
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 4.dp)
                    .testTag("chats.communityRequests.${inbox.group.id}"),
            )
        }
        item(key = "access", contentType = "card") {
            AnimatedVisibility(
                visible = restoreAccessPending,
                enter = fade.enter + expandVertically(state.spec()),
                exit = fade.exit + shrinkVertically(state.spec()),
            ) {
                // After a restore whose token rotation is unanswered: the old phone may still have access; 7.1 explains and checks.
                RestoreAccessCard(
                    onClick = { router.push(AppRoute.Settings) },
                    modifier = Modifier
                        .padding(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 4.dp)
                        .testTag(ChatsTags.ACCESS),
                )
            }
        }
        item(key = "requests", contentType = "card") {
            AnimatedVisibility(
                visible = requests.isNotEmpty(),
                enter = fade.enter + expandVertically(state.spec()),
                exit = fade.exit + shrinkVertically(state.spec()),
            ) {
                // Under the access card: the 10 dp gap of stacked option cards.
                RequestsCard(
                    count = requests.size.coerceAtLeast(1),
                    onClick = { router.push(AppRoute.Requests) },
                    modifier = Modifier
                        .padding(start = 16.dp, top = if (restoreAccessPending) 6.dp else 18.dp, end = 16.dp, bottom = 4.dp)
                        .testTag(ChatsTags.REQUESTS),
                )
            }
        }
        if (chats.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                EmptyState(onNewChat, Modifier.animateItem(fadeInSpec = tween(rise.timing.millis), placementSpec = null, fadeOutSpec = tween(state.millis)))
            }
        } else {
            itemsIndexed(chats, key = { _, chat -> chat.id.value }, contentType = { _, _ -> "chat" }) { index, chat ->
                Column(
                    Modifier.animateItem(
                        fadeInSpec = tween(state.millis),
                        // Reduce motion: rows never slide into a new order; they cross-fade.
                        placementSpec = if (reduceMotion) null else screen.spec(),
                        fadeOutSpec = tween(state.millis),
                    ),
                ) {
                    // The rule belongs to the row's place, not the row: a row moved to the top has none there.
                    if (index > 0) ChatListSeparator()
                    val source = remember { MutableInteractionSource() }
                    // A long press on a one-to-one chat offers deleting it (communities have their own screen).
                    val isCommunity = CommunityChats.isCommunity(chat.id)
                    // Where the row is, for the menu (read only when pressed; no recomposition).
                    val bounds = remember { RowBounds() }
                    val deletionActions = if (isCommunity) emptyList() else chatDeletionAccessibilityActions(chat.id, chat.peer.username, chat.peer.isBlocked, onDelete)
                    ChatListRow(
                        chat = chat,
                        modifier = Modifier
                            .onGloballyPositioned { bounds.rect = it.boundsInWindow() }
                            .groupedRowPress(source)
                            .rowPress(source, onClick = { router.push(AppRoute.Conversation(chat.id)) }, onLongClick = if (isCommunity) null else ({ onLongPress(chat, bounds.rect) }))
                            .then(if (deletionActions.isEmpty()) Modifier else Modifier.semantics { customActions = deletionActions })
                            .testTag(ChatsTags.chat(chat.id)),
                    )
                }
            }
        }
    }
}

/** Title block (pad 14 20 0, gap 12): “Sohbetler” (Doto 900 38) and the C20 connection line with its caption. */
@Composable
private fun TitleBlock(connection: com.syconx.us2us.domain.ConnectionState, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = U2Spacing.screenGutter, top = 14.dp, end = U2Spacing.screenGutter),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChatsTitle(Modifier.fillMaxWidth().testTag(ChatsTags.TITLE))
        ConnectionStatusLine(connection, Modifier.fillMaxWidth().testTag(ChatsTags.CONNECTION))
    }
}

/** No chats yet (proposed copy): a short instruction and the way to start. */
@Composable
private fun EmptyState(onNewChat: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = U2Spacing.screenGutterL, top = 120.dp, end = U2Spacing.screenGutterL),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        U2Text(
            stringResource(R.string.chats_list_empty),
            U2TextStyles.callout,
            color = U2Theme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 290.dp),
        )
        U2TextButton(
            onClick = onNewChat,
            tone = U2TextButtonTone.Accent,
            fillsWidth = false,
            modifier = Modifier.testTag(ChatsTags.EMPTY_NEW),
        ) {
            U2ButtonLabel(stringResource(R.string.chats_list_empty_action), glyph = PhosphorGlyph.NotePencil, glyphSize = 20.dp)
        }
    }
}

/** A row's bounds in the window, kept outside the snapshot system (scrolling writes it every frame). */
private class RowBounds {
    var rect: Rect = Rect.Zero
}

/** A row's tap, and its long press when there is one ([onLongClick]); the press look comes from `groupedRowPress`. */
private fun Modifier.rowPress(source: MutableInteractionSource, onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier =
    if (onLongClick == null) {
        clickable(source, indication = null, role = Role.Button, onClick = onClick)
    } else {
        combinedClickable(interactionSource = source, indication = null, role = Role.Button, onLongClick = onLongClick, onClick = onClick)
    }
