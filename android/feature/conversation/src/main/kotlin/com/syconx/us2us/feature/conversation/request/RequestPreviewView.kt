package com.syconx.us2us.feature.conversation.request

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.AvatarPicture
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessageRequest
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.RequestId
import com.syconx.us2us.feature.conversation.ChatHeader
import com.syconx.us2us.feature.conversation.ConversationMessageRow
import com.syconx.us2us.feature.conversation.ConversationRow
import com.syconx.us2us.feature.conversation.ConversationRows
import com.syconx.us2us.feature.conversation.HeaderStatusText
import com.syconx.us2us.feature.report.BlockConfirmationSheet
import com.syconx.us2us.feature.report.BlockConfirmationTags
import com.syconx.us2us.feature.report.ReportSheet
import com.syconx.us2us.feature.report.rememberReportPresenter
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.Avatar
import com.syconx.us2us.ui.components.AvatarSize
import com.syconx.us2us.ui.components.FloatingMenuItem
import com.syconx.us2us.ui.components.FloatingMenuTone
import com.syconx.us2us.ui.components.GhostChip
import com.syconx.us2us.ui.components.U2AnchoredMenu
import com.syconx.us2us.ui.components.U2DecisionButton
import com.syconx.us2us.ui.components.U2DecisionKind
import com.syconx.us2us.ui.components.U2DecisionSize
import com.syconx.us2us.ui.components.U2FlexRow
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.components.u2Flex
import com.syconx.us2us.ui.composer.Composer
import com.syconx.us2us.ui.env.LocalHarnessState
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.env.LocalMessenger
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.env.u2Top
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.nav.LocalRouter
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import java.time.Duration
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 4.4 · İstek önizleme: a request read in context before deciding.
 *
 * Looks like a chat but has no composer: the decision bar takes its place. Until accepted no read
 * receipt is sent, links are inert (no tap, no preview fetch) and media is never decoded: a
 * placeholder says what it is. “Kabul et” turns this screen into 5.1: the decision bar slides down
 * (0.4 s) while the composer fades in and the header settles into the chat's, then the
 * conversation takes over. Engelle (after a confirmation) and Sil go back to 4.3, where the card
 * collapses. “Bildir” (the “…” menu, or beside Engelle in its confirmation) opens the report sheet;
 * when the report blocked the sender, closing it goes back to 4.3 too.
 */
@Composable
internal fun RequestPreviewContent(requestId: RequestId, modifier: Modifier = Modifier) {
    val messenger = LocalMessenger.current
    val router = LocalRouter.current
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val requests by messenger.requests.collectAsStateWithLifecycle()
    // The request as first shown, kept while the screen leaves after a decision.
    var shown by remember { mutableStateOf(messenger.requests.value.firstOrNull { it.id == requestId }) }
    val request = requests.firstOrNull { it.id == requestId } ?: shown
    if (shown == null && request != null) shown = request
    var acceptedChat by remember { mutableStateOf<ChatId?>(null) }
    var showsMenu by remember { mutableStateOf(false) }
    var confirmsBlock by remember { mutableStateOf(false) }
    val reports = rememberReportPresenter()
    val harness = LocalHarnessState.current
    var reveal by remember { mutableStateOf<AvatarReveal>(AvatarReveal.Hidden) }
    val morph = remember { Animatable(0f) }
    val reduceMotion = LocalReduceMotion.current
    val chatFlow = remember(acceptedChat) { acceptedChat?.let(messenger::chat) }
    val chat = chatFlow?.collectAsStateWithLifecycle()?.value

    // What runs once this screen has gone (Engelle, Sil after the pop): the composition's dispatcher
    // without its end. With animations off the screen leaves at once, and a decision cut short would
    // leave the sender unblocked.
    val outliving = remember(scope) { lazy { CoroutineScope(scope.coroutineContext.minusKey(Job) + SupervisorJob()) } }

    /** Back to 4.3 first, then the decision, so the card is seen collapsing. */
    fun leave(decision: suspend () -> Unit) {
        router.pop()
        outliving.value.launch {
            delay(450)
            decision()
        }
    }

    fun accept(request: MessageRequest) {
        scope.launch {
            val id = messenger.accept(request) ?: return@launch
            haptics.success()
            // The whole morph happens here (0.4 s): bar → composer, header → the chat's, thread →
            // 5.1's column. Then 5.1 replaces this screen without any animation: it is drawn identically.
            acceptedChat = id
            morph.animateTo(1f, U2Motion.resolve(U2Motion.soft(0.4f), reduceMotion).spec())
            // Only if the user is still here (back during the morph keeps 4.3).
            if (router.top == AppRoute.RequestPreview(requestId)) router.replaceTop(AppRoute.Conversation(id), animated = false)
        }
    }

    /** “Bildir”: the report sheet, “Bu kişiyi de engelle” on. From the block confirmation it rises once the confirmation has gone. */
    fun openReport(request: MessageRequest, afterConfirmation: Boolean = false) {
        reports.open(messenger.reportSubject(request), request.sender, offersBlock = true, afterConfirmation = afterConfirmation) {
            messenger.block(request)
        }
    }

    // `chats:4.4-report`: the report sheet over the preview.
    LaunchedEffect(Unit) {
        if (harness?.state == "4.4-report") {
            delay(600)
            request?.let(::openReport)
        }
    }

    fun revealAvatar(request: MessageRequest) {
        if (reveal != AvatarReveal.Hidden) return
        reveal = AvatarReveal.Loading
        scope.launch { reveal = AvatarReveal.Shown(messenger.revealAvatar(request)) }
    }

    val density = LocalDensity.current
    var barHeight by remember { mutableStateOf(161.dp) }
    var composerHeight by remember { mutableStateOf(94.dp) }
    val accepted = morph.value
    U2Screen(modifier.testTag("preview")) {
        if (request != null) {
            Thread(
                request = request,
                accepted = accepted,
                isAccepted = acceptedChat != null,
                bottomPadding = if (acceptedChat != null) composerHeight + 6.dp else barHeight + 16.dp,
                avatar = (reveal as? AvatarReveal.Shown)?.picture ?: request.avatar,
                reveal = reveal,
                onReveal = { revealAvatar(request) },
            )
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            // The C21 composer the decision bar turns into (inert until 5.1 takes over).
            Composer(
                text = "",
                onTextChange = {},
                onSend = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { composerHeight = with(density) { it.height.toDp() } }
                    .graphicsLayer { alpha = accepted }
                    .clearAndSetSemantics {},
            )
            if (request != null) {
                DecisionBar(
                    request = request,
                    accepted = accepted,
                    isAccepted = acceptedChat != null,
                    barHeight = barHeight,
                    onHeight = { barHeight = with(density) { it.toDp() } },
                    onBlock = { confirmsBlock = true },
                    onDelete = { leave { messenger.delete(request) } },
                    onAccept = { accept(request) },
                )
            }
        }
        if (request != null) {
            ChatHeader(
                username = request.sender,
                palette = chat?.peer?.palette ?: AvatarPalette.C,
                onBack = { router.pop() },
                avatar = chat?.peer?.avatar ?: (reveal as? AvatarReveal.Shown)?.picture ?: request.avatar,
                // Nothing to open before the request is accepted.
                onTapPeer = {},
                status = { if (chat == null) HeaderStatusText(stringResource(R.string.common_request_title)) },
                trailing = {
                    if (chat != null) {
                        GhostChip(chat.ghost, onClick = {})
                    } else {
                        U2GlassIconButton(
                            PhosphorGlyph.DotsThree,
                            contentDescription = stringResource(R.string.chats_a11y_more),
                            onClick = { showsMenu = !showsMenu },
                            modifier = Modifier.testTag("preview.more"),
                            diameter = U2Size.iconButtonChat,
                            glyphSize = 20.dp,
                        )
                    }
                },
            )
        }
        val reportTitle = stringResource(R.string.common_report_action)
        val blockTitle = stringResource(R.string.common_request_block)
        val deleteTitle = stringResource(R.string.common_request_delete)
        U2AnchoredMenu(
            isPresented = showsMenu,
            onDismiss = { showsMenu = false },
            items = listOf(
                FloatingMenuItem("report", reportTitle, PhosphorGlyph.Warning) { request?.let(::openReport) },
                FloatingMenuItem("block", blockTitle, PhosphorGlyph.Prohibit, tone = FloatingMenuTone.Destructive) { confirmsBlock = true },
                FloatingMenuItem("delete", deleteTitle, PhosphorGlyph.Trash, tone = FloatingMenuTone.Destructive) {
                    request?.let { leave { messenger.delete(it) } }
                },
            ),
            designTop = 104.dp,
            trailing = 14.dp,
        )
    }
    // Engelle · Bildir · Vazgeç.
    BlockConfirmationSheet(
        item = request?.takeIf { confirmsBlock },
        personName = { it.sender },
        onBlock = { leave { messenger.block(it) } },
        onReport = { openReport(it, afterConfirmation = true) },
        onDismiss = { confirmsBlock = false },
        tags = BlockConfirmationTags("preview.block.confirm", "preview.block.report", "preview.block.cancel"),
    )
    // A report that blocked the sender ends here, as Engelle does: back to 4.3.
    ReportSheet(reports) { report ->
        if (report.didBlock && router.top == AppRoute.RequestPreview(requestId)) router.pop()
    }
}

/** The sender's picture on this screen: neither fetched nor decoded before “Profil resmini göster” (PROTOCOL §8.9). */
private sealed interface AvatarReveal {
    data object Hidden : AvatarReveal

    data object Loading : AvatarReveal

    /** What the tap found (the initial when there is none: 4.4 says so). */
    data class Shown(val picture: AvatarPicture) : AvatarReveal
}

/**
 * Messages column (pad 122 14 0, gap 6): the sender's profile block, a date separator per day, the
 * incoming bubbles (only the last of a group carries the tail and the time). Accepted, the column
 * becomes 5.1's: the profile block and the date rule fade out, every bubble cross-fades into 5.1's
 * row and the column glides down to sit above the composer.
 */
@Composable
private fun Thread(
    request: MessageRequest,
    accepted: Float,
    isAccepted: Boolean,
    bottomPadding: Dp,
    avatar: AvatarPicture,
    reveal: AvatarReveal,
    onReveal: () -> Unit,
) {
    val messages = request.messages
    val zone = ZoneId.systemDefault()
    Box(Modifier.fillMaxSize().testTag("preview.thread")) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = if (isAccepted) Arrangement.Bottom else Arrangement.Top,
        ) {
            Column(
                Modifier
                    .padding(horizontal = U2Spacing.chatGutter)
                    .u2Top(122.dp)
                    .padding(bottom = bottomPadding),
                verticalArrangement = Arrangement.spacedBy(U2Spacing.bubbleGap),
            ) {
                Profile(request, avatar, reveal, onReveal, Modifier.graphicsLayer { alpha = 1f - accepted })
                messages.forEachIndexed { index, message ->
                    val startsDay = index == 0 || messages[index - 1].sentAt.atZone(zone).toLocalDate() != message.sentAt.atZone(zone).toLocalDate()
                    if (startsDay) RequestDateSeparator(message.sentAt, Modifier.graphicsLayer { alpha = 1f - accepted })
                    // Both forms stay in place, so they glide together while one dissolves into the
                    // other; the row takes the visible form's height. Media is never decoded until
                    // accepted, so its row only appears then.
                    MorphRow(showsSecond = isAccepted, first = {
                        RequestMessageView(message, endsGroup(index, messages), Modifier.graphicsLayer { alpha = 1f - accepted })
                    }, second = {
                        if (isAccepted || message.content is MessageContent.Text) {
                            ConversationMessageRow(
                                ConversationRow(message, isTail = ConversationRows.isTail(index, messages), showsStatusSentence = false),
                                viewModel = null,
                                modifier = Modifier.graphicsLayer { alpha = accepted }.clearAndSetSemantics {},
                            )
                        }
                    })
                }
            }
        }
    }
}

/** The last message of a group (the next one is 5 min later, another day, or there is none) carries the tail corner and the time. */
private fun endsGroup(index: Int, messages: List<Message>): Boolean {
    val next = messages.getOrNull(index + 1) ?: return true
    if (next.content is MessageContent.System) return true
    val zone = ZoneId.systemDefault()
    val current = messages[index].sentAt
    return Duration.between(current, next.sentAt).seconds >= 300 ||
        current.atZone(zone).toLocalDate() != next.sentAt.atZone(zone).toLocalDate()
}

/** Two forms of one row on top of each other: sized by the first, or by the second when [showsSecond] (4.4 → 5.1 morph). */
@Composable
private fun MorphRow(showsSecond: Boolean, first: @Composable () -> Unit, second: @Composable () -> Unit) {
    Layout(contents = listOf(first, second)) { (firsts, seconds), constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val a = firsts.map { it.measure(loose) }
        val b = seconds.map { it.measure(loose) }
        val sized = if (showsSecond && b.isNotEmpty()) b else a
        val height = sized.maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, height) {
            a.forEach { it.place(0, 0) }
            b.forEach { it.place(0, 0) }
        }
    }
}

/**
 * Profile block (pad 8 0 16, gap 10, centred): avatar 64, name, explanation; below them
 * (proposed) “Profil resmini göster”, the sender's picture only on that tap, with a line saying
 * that the server sees the look (§9).
 */
@Composable
private fun Profile(request: MessageRequest, avatar: AvatarPicture, reveal: AvatarReveal, onReveal: () -> Unit, modifier: Modifier) {
    val colors = U2Theme.colors
    Column(
        modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp).testTag("preview.profile"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            Modifier.semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Avatar(request.sender, AvatarPalette.C, Modifier.testTag("preview.avatar"), size = AvatarSize.Profile, picture = avatar)
            U2Text(request.sender, U2TextStyles.monoUsernameXL, Modifier.testTag("preview.name"), color = colors.text, maxLines = 1, softWrap = false)
            U2Text(
                stringResource(R.string.chats_preview_intro),
                U2TextStyles.callout,
                Modifier.widthIn(max = 290.dp).testTag("preview.intro"),
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        when {
            request.offersAvatar && (reveal == AvatarReveal.Hidden || reveal == AvatarReveal.Loading) -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                U2TextButton(
                    onClick = onReveal,
                    modifier = Modifier.testTag("preview.showAvatar"),
                    tone = U2TextButtonTone.Accent,
                    height = 36.dp,
                    fillsWidth = false,
                    enabled = reveal == AvatarReveal.Hidden,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        PhosphorIcon(PhosphorGlyph.Eye, 16.dp, tint = colors.accent300)
                        U2Text(stringResource(R.string.chats_preview_avatar_show), U2TextStyles.button, color = colors.accent300, maxLines = 1)
                    }
                }
                U2Text(
                    stringResource(R.string.chats_preview_avatar_show_note),
                    U2TextStyles.footnote,
                    Modifier.widthIn(max = 290.dp).clearAndSetSemantics {},
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            reveal is AvatarReveal.Shown && reveal.picture.isInitials -> U2Text(
                stringResource(R.string.chats_preview_avatar_none),
                U2TextStyles.footnote,
                Modifier.testTag("preview.noAvatar"),
                color = colors.textSecondary,
            )
        }
    }
}

/**
 * 0,713 402 × 161: `surface`, top radius 30, pad 18 16 40, gap 14: the explanation (`callout`,
 * centred) and Engelle · Sil · Kabul et (50 high, r14, Kabul et at flex 1.4 with the soft glow).
 */
@Composable
private fun DecisionBar(
    request: MessageRequest,
    accepted: Float,
    isAccepted: Boolean,
    barHeight: Dp,
    onHeight: (Int) -> Unit,
    onBlock: () -> Unit,
    onDelete: () -> Unit,
    onAccept: () -> Unit,
) {
    val colors = U2Theme.colors
    val reduceMotion = LocalReduceMotion.current
    val shape = RoundedCornerShape(topStart = U2Radius.sheetBar, topEnd = U2Radius.sheetBar)
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("preview.bar")
            .onSizeChanged { onHeight(it.height) }
            .offset { IntOffset(0, if (reduceMotion) 0 else ((barHeight + 10.dp).toPx() * accepted).roundToInt()) }
            .graphicsLayer { alpha = 1f - accepted }
            .background(colors.surface, shape)
            .padding(start = 16.dp, top = 18.dp, end = 16.dp)
            .u2Bottom(40.dp)
            .then(if (isAccepted) Modifier.clearAndSetSemantics {} else Modifier),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        U2Text(
            stringResource(R.string.chats_preview_decision, request.sender),
            U2TextStyles.callout,
            Modifier.fillMaxWidth().testTag("preview.decision").padding(horizontal = 10.dp),
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        U2FlexRow(Modifier.fillMaxWidth(), spacing = 8.dp) {
            U2DecisionButton(U2DecisionKind.Destructive, stringResource(R.string.common_request_block), { if (!isAccepted) onBlock() }, Modifier.testTag("preview.block"), size = U2DecisionSize.Bar)
            U2DecisionButton(U2DecisionKind.Neutral, stringResource(R.string.common_request_delete), { if (!isAccepted) onDelete() }, Modifier.testTag("preview.delete"), size = U2DecisionSize.Bar)
            U2DecisionButton(
                U2DecisionKind.Accept,
                stringResource(R.string.common_request_accept),
                { if (!isAccepted) onAccept() },
                Modifier.u2Flex(1.4f, border = 2.dp).testTag("preview.accept"),
                size = U2DecisionSize.Bar,
            )
        }
    }
}

