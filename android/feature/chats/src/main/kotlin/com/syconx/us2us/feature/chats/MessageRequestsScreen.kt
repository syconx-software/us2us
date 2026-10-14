package com.syconx.us2us.feature.chats

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.MessagePreviewKind
import com.syconx.us2us.domain.MessageRequest
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.RequestId
import com.syconx.us2us.feature.report.BlockConfirmationSheet
import com.syconx.us2us.feature.report.ReportSheet
import com.syconx.us2us.feature.report.rememberReportPresenter
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.Avatar
import com.syconx.us2us.ui.components.AvatarSize
import com.syconx.us2us.ui.components.CenteredTitleHeader
import com.syconx.us2us.ui.components.DestructiveConfirmation
import com.syconx.us2us.ui.components.FlagTag
import com.syconx.us2us.ui.components.FloatingMenuItem
import com.syconx.us2us.ui.components.FloatingMenuTone
import com.syconx.us2us.ui.components.HeaderSpacer
import com.syconx.us2us.ui.components.InfoBanner
import com.syconx.us2us.ui.components.U2AnchoredMenu
import com.syconx.us2us.ui.components.U2DecisionButton
import com.syconx.us2us.ui.components.U2DecisionKind
import com.syconx.us2us.ui.components.U2FlexRow
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.text.CSSLineClampText
import com.syconx.us2us.ui.components.u2CardPress
import com.syconx.us2us.ui.components.u2Flex
import com.syconx.us2us.ui.env.LocalAppModel
import com.syconx.us2us.ui.env.LocalHarnessState
import com.syconx.us2us.ui.env.LocalU2Clock
import com.syconx.us2us.ui.env.u2Top
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.l10n.rememberU2Strings
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.u2TransitionBlur
import com.syconx.us2us.ui.nav.LocalRouter
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.sheet.U2Sheet
import com.syconx.us2us.ui.sheet.U2SheetStyle
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.rememberU2BackdropSource
import com.syconx.us2us.ui.theme.u2BackdropSource
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Test tags of 4.3 (the iOS accessibility identifiers). */
object RequestsTags {
    const val BACK = "requests.back"
    const val MORE = "requests.more"
    const val EMPTY = "requests.empty"
    const val BANNER = "requests.banner"

    fun card(id: RequestId): String = "request.${id.value}"

    fun block(id: RequestId): String = "request.${id.value}.block"

    fun delete(id: RequestId): String = "request.${id.value}.delete"

    fun accept(id: RequestId): String = "request.${id.value}.accept"

    /** The whole card (summary and decision row). */
    fun container(id: RequestId): String = "request.${id.value}.card"
}

/**
 * 4.3 · Mesaj istekleri: first messages from people you haven't accepted.
 *
 * Nothing opens until accepted: snippets are text only, media shows as a word plus the “hidden”
 * flag, links are flagged and never tappable. The sender can't tell whether the request was seen.
 * Engelle asks first (the block confirmation, with “Bildir” beside it: the report sheet), Sil is
 * silent, Kabul et moves the chat to 4.1 and opens 5.1. Decided cards fade and collapse (0.36 s).
 */
@Composable
internal fun MessageRequestsView(modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val messenger = app.messenger
    val router = LocalRouter.current
    val harness = LocalHarnessState.current
    val scope = rememberCoroutineScope()
    val triage = remember { RequestTriageModel() }
    val all by messenger.requests.collectAsStateWithLifecycle()

    var showsMenu by remember { mutableStateOf(false) }
    var blockCandidate by remember { mutableStateOf<MessageRequest?>(null) }
    val reports = rememberReportPresenter()
    var confirmsDeleteAll by remember { mutableStateOf(false) }
    // “Kabul et” on a card: the list as it was, while this screen gives way to 5.1 (the card is
    // not seen collapsing behind the incoming chat).
    var frozenRequests by remember { mutableStateOf<List<MessageRequest>?>(null) }

    val requests = frozenRequests ?: triage.requests(all)
    val isEmpty = requests.isEmpty()

    // Kabul et → the chat moves to 4.1 and opens 5.1 in place of this screen.
    fun accept(request: MessageRequest) {
        frozenRequests = triage.requests(messenger.requests.value)
        scope.launch {
            val chat = triage.accept(request, messenger)
            if (chat == null || router.top != AppRoute.Requests) {
                frozenRequests = null
                return@launch
            }
            router.replaceTop(AppRoute.Conversation(chat))
        }
    }

    LaunchedEffect(Unit) {
        val shown = triage.requests(messenger.requests.value)
        when {
            harness?.state == "4.3-block" && shown.isNotEmpty() -> {
                delay(600)
                blockCandidate = shown.first()
            }
            else -> when (ChatsDemo.fromRaw(app.configuration.chatsDemo)) {
                ChatsDemo.Delete -> {
                    delay(ChatsDemo.DELAY_MILLIS)
                    shown.firstOrNull { it.id == RequestId("n_aksoy") }?.let { triage.delete(it, messenger) }
                }
                ChatsDemo.AcceptCard -> {
                    delay(ChatsDemo.DELAY_MILLIS)
                    shown.firstOrNull()?.let(::accept)
                }
                ChatsDemo.OpenCard -> {
                    delay(ChatsDemo.DELAY_MILLIS)
                    shown.firstOrNull()?.let { router.push(AppRoute.RequestPreview(it.id)) }
                }
                else -> Unit
            }
        }
    }

    val backdrop = rememberU2BackdropSource()
    val scroll = rememberScrollState()
    val threshold = with(LocalDensity.current) { 2.dp.toPx() }
    U2Screen(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .u2BackdropSource(backdrop)
                .verticalScroll(scroll),
        ) {
            Column(Modifier.u2Top(100.dp)) {
                InfoBanner(
                    stringResource(R.string.chats_requests_banner),
                    Modifier
                        .padding(start = 16.dp, top = 16.dp, end = 16.dp)
                        .testTag(RequestsTags.BANNER),
                )
                RequestList(
                    all = frozenRequests ?: all,
                    visible = requests.map { it.id }.toSet(),
                    onOpen = { router.push(AppRoute.RequestPreview(it.id)) },
                    onBlock = { blockCandidate = it },
                    onDelete = { scope.launch { triage.delete(it, messenger) } },
                    onAccept = ::accept,
                )
                val fade = U2Transitions.fade()
                AnimatedVisibility(isEmpty, enter = fade.enter, exit = fade.exit) {
                    U2Text(
                        stringResource(R.string.chats_requests_empty),
                        U2TextStyles.callout,
                        color = U2Theme.colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 120.dp)
                            .then(u2TransitionBlur(fade))
                            .testTag(RequestsTags.EMPTY),
                    )
                }
            }
        }
        ChatsHeaderBackdrop(isVisible = scroll.value > threshold, source = backdrop)
        CenteredTitleHeader(
            title = stringResource(R.string.common_chats_requests_title),
            leading = {
                U2GlassIconButton(
                    glyph = PhosphorGlyph.CaretLeft,
                    contentDescription = stringResource(R.string.common_a11y_back),
                    onClick = router::pop,
                    modifier = Modifier.testTag(RequestsTags.BACK),
                )
            },
            trailing = {
                // Nothing to act on without requests: an empty slot keeps the title centred.
                val timing = U2Motion.resolve(U2Motion.state, LocalReduceMotion.current)
                AnimatedContent(
                    targetState = isEmpty,
                    transitionSpec = { fadeIn(timing.spec()) togetherWith fadeOut(timing.spec()) },
                    label = "requestsMore",
                ) { empty ->
                    if (empty) {
                        HeaderSpacer()
                    } else {
                        U2GlassIconButton(
                            glyph = PhosphorGlyph.DotsThree,
                            contentDescription = stringResource(R.string.chats_a11y_more),
                            onClick = { showsMenu = !showsMenu },
                            modifier = Modifier.testTag(RequestsTags.MORE),
                        )
                    }
                }
            },
        )
        U2AnchoredMenu(
            isPresented = showsMenu,
            onDismiss = { showsMenu = false },
            items = listOf(
                FloatingMenuItem(
                    id = "deleteAll",
                    title = stringResource(R.string.chats_requests_menu_deleteall),
                    glyph = PhosphorGlyph.Trash,
                    tone = FloatingMenuTone.Destructive,
                ) { confirmsDeleteAll = true },
            ),
            backdrop = backdrop,
        )
    }

    // Engelle · Bildir · Vazgeç. “Bildir” opens the report sheet once the confirmation has gone,
    // “Bu kişiyi de engelle” on: a report that left the sheet removes the card like Engelle.
    BlockConfirmationSheet(
        item = blockCandidate,
        personName = { it.sender },
        onBlock = { request -> scope.launch { triage.block(request, messenger) } },
        onReport = { request ->
            reports.open(messenger.reportSubject(request), request.sender, offersBlock = true, afterConfirmation = true) {
                triage.block(request, messenger)
            }
        },
        onDismiss = { blockCandidate = null },
    )
    ReportSheet(reports)
    U2Sheet(isPresented = confirmsDeleteAll, onDismissRequest = { confirmsDeleteAll = false }, style = U2SheetStyle.Floating) {
        DestructiveConfirmation(
            title = stringResource(R.string.chats_requests_deleteall_title),
            message = stringResource(R.string.chats_requests_deleteall_message),
            confirmTitle = stringResource(R.string.chats_requests_deleteall_confirm),
            onConfirm = {
                confirmsDeleteAll = false
                scope.launch { triage.deleteAll(messenger.requests.value, messenger) }
            },
            onCancel = { confirmsDeleteAll = false },
            modifier = Modifier.padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 28.dp),
        )
    }
}

/**
 * The request cards (pad 14 16 30 16). The gap of 10 lives under each card, so a removed card
 * collapses together with its gap. A card leaves only once its collapse has played: one the
 * messenger already dropped stays until then.
 */
@Composable
private fun RequestList(
    all: List<MessageRequest>,
    visible: Set<RequestId>,
    onOpen: (MessageRequest) -> Unit,
    onBlock: (MessageRequest) -> Unit,
    onDelete: (MessageRequest) -> Unit,
    onAccept: (MessageRequest) -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    var shown by remember { mutableStateOf(all) }
    // Cards on screen from the start are simply there; later ones rise in.
    val initial = remember { all.map { it.id }.toSet() }
    val states = remember { mutableStateMapOf<RequestId, MutableTransitionState<Boolean>>() }
    LaunchedEffect(all) { shown = mergeLeaving(shown, all) }
    val rise = U2Transitions.rise()
    val collapse = U2Transitions.collapse()
    val fade = U2Transitions.fade()
    Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 30.dp)) {
        val present = all.map { it.id }.toSet()
        for (request in shown) {
            key(request.id.value) {
                val isVisible = request.id in visible && request.id in present
                val state = states.getOrPut(request.id) { MutableTransitionState(isVisible && request.id in initial) }
                state.targetState = isVisible
                LaunchedEffect(state.isIdle, state.currentState) {
                    if (state.isIdle && !state.currentState && request.id !in present) {
                        shown = shown.filter { it.id != request.id }
                        states.remove(request.id)
                    }
                }
                AnimatedVisibility(
                    visibleState = state,
                    enter = if (reduceMotion) fade.enter else rise.enter,
                    exit = if (reduceMotion) fade.exit else collapse,
                ) {
                    RequestCard(
                        request = request,
                        onOpen = { onOpen(request) },
                        onBlock = { onBlock(request) },
                        onDelete = { onDelete(request) },
                        onAccept = { onAccept(request) },
                        modifier = Modifier
                            .padding(bottom = 10.dp)
                            .then(u2TransitionBlur(if (reduceMotion) fade else rise)),
                    )
                }
            }
        }
    }
}

/** [new] in its order, with the cards of [old] that are gone kept after the card they followed. */
internal fun mergeLeaving(old: List<MessageRequest>, new: List<MessageRequest>): List<MessageRequest> {
    val newIds = new.map { it.id }.toSet()
    val result = new.toMutableList()
    var previous: RequestId? = null
    for (request in old) {
        if (request.id !in newIds) {
            val index = previous?.let { id -> result.indexOfFirst { it.id == id } + 1 } ?: 0
            result.add(index.coerceIn(0, result.size), request)
        }
        previous = request.id
    }
    return result
}

/**
 * A 4.3 request card: `surface` r20, pad 14 14 14 16, gap 13. Avatar 44 (palette C: strangers are
 * always neutral) · username + age · snippet (`requestSnippet`, `text·84%`, at most 2 lines) ·
 * optional flag; the decision row (40 high, r12; Kabul et at flex 1.3).
 */
@Composable
fun RequestCard(
    request: MessageRequest,
    onOpen: () -> Unit,
    onBlock: () -> Unit,
    onDelete: () -> Unit,
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = U2Theme.colors
    Column(
        modifier
            .fillMaxWidth()
            .testTag(RequestsTags.container(request.id))
            .background(colors.surface, RoundedCornerShape(U2Radius.cardL))
            .padding(start = 16.dp, top = 14.dp, end = 14.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            Modifier
                .u2CardPress(onOpen)
                .semantics(mergeDescendants = true) {}
                .testTag(RequestsTags.card(request.id)),
        ) {
            Summary(request)
        }
        U2FlexRow(spacing = 8.dp) {
            U2DecisionButton(
                U2DecisionKind.Destructive,
                stringResource(R.string.common_request_block),
                onBlock,
                Modifier.testTag(RequestsTags.block(request.id)),
            )
            U2DecisionButton(
                U2DecisionKind.Neutral,
                stringResource(R.string.common_request_delete),
                onDelete,
                Modifier.testTag(RequestsTags.delete(request.id)),
            )
            U2DecisionButton(
                U2DecisionKind.Accept,
                stringResource(R.string.common_request_accept),
                onAccept,
                Modifier
                    .u2Flex(1.3f, border = 2.dp)
                    .testTag(RequestsTags.accept(request.id)),
            )
        }
    }
}

@Composable
private fun Summary(request: MessageRequest) {
    val colors = U2Theme.colors
    val strings = rememberU2Strings()
    val now = LocalU2Clock.current.now
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(request.sender, AvatarPalette.C, size = AvatarSize.Request)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                U2Text(
                    request.sender,
                    U2TextStyles.monoUsername,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                U2Text(
                    ChatsFormat.requestAge(request.receivedAt, now, strings),
                    U2TextStyles.meta,
                    color = colors.textSecondary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            when (val preview = request.preview) {
                is MessagePreviewKind.Text -> CSSLineClampText(preview.text, U2TextStyles.requestSnippet, lines = 2, color = colors.textStrong)
                else -> U2Text(
                    snippet(preview),
                    U2TextStyles.requestSnippet,
                    color = colors.textStrong,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Flag(request.flags)
        }
    }
}

/** The first message as a word for media (never decoded before acceptance). */
@Composable
private fun snippet(preview: MessagePreviewKind): String = when (preview) {
    is MessagePreviewKind.Text -> preview.text
    MessagePreviewKind.Photo, MessagePreviewKind.PhotoViewOnce -> stringResource(R.string.common_media_photo)
    MessagePreviewKind.Video -> stringResource(R.string.common_media_video)
    MessagePreviewKind.Voice -> stringResource(R.string.common_media_voice)
    is MessagePreviewKind.File -> stringResource(R.string.chats_media_file)
    MessagePreviewKind.Location -> stringResource(R.string.common_media_location)
    MessagePreviewKind.LiveLocation, MessagePreviewKind.LiveLocationEnded -> stringResource(R.string.common_location_live)
    MessagePreviewKind.Sticker -> stringResource(R.string.common_chats_preview_sticker)
    MessagePreviewKind.Disappearing -> stringResource(R.string.common_chats_preview_disappearing)
    MessagePreviewKind.Deleted -> stringResource(R.string.common_message_deleted)
}

@Composable
private fun Flag(flags: Set<MessageRequest.Flag>) {
    val colors = U2Theme.colors
    when {
        MessageRequest.Flag.HasLink in flags ->
            FlagTag(PhosphorGlyph.Warning, stringResource(R.string.chats_requests_flag_link), color = colors.danger)
        MessageRequest.Flag.Suspicious in flags ->
            FlagTag(PhosphorGlyph.Warning, stringResource(R.string.chats_requests_flag_suspicious), color = colors.danger)
        MessageRequest.Flag.HasMedia in flags ->
            FlagTag(PhosphorGlyph.EyeSlash, stringResource(R.string.chats_requests_flag_media))
    }
}
