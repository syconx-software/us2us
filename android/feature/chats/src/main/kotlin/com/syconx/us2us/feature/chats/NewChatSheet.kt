package com.syconx.us2us.feature.chats

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.AvatarPicture
import com.syconx.us2us.domain.Peer
import com.syconx.us2us.domain.UsernameRules
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.QrScanMode
import com.syconx.us2us.feature.chats.invite.InviteView
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.common.MyCodeSheet
import com.syconx.us2us.ui.common.preparedOwnContactCode
import com.syconx.us2us.ui.components.Avatar
import com.syconx.us2us.ui.components.AvatarSize
import com.syconx.us2us.ui.components.GroupedCard
import com.syconx.us2us.ui.components.GroupedNavigationRow
import com.syconx.us2us.ui.components.GroupedSeparator
import com.syconx.us2us.ui.components.IconTile
import com.syconx.us2us.ui.components.IconTileContent
import com.syconx.us2us.ui.components.SheetBar
import com.syconx.us2us.ui.components.U2ButtonLabel
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.U2PrimaryButtonSize
import com.syconx.us2us.ui.components.UsernameSearchField
import com.syconx.us2us.ui.env.LocalAppModel
import com.syconx.us2us.ui.env.LocalFeatureScreens
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.l10n.annotatedStringResource
import com.syconx.us2us.ui.media.MediaProgressRing
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.u2TransitionBlur
import com.syconx.us2us.ui.sheet.U2FadeCover
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Test tags of 4.2 (the iOS accessibility identifiers). */
object NewChatTags {
    const val BAR = "newChat.bar"
    const val RESULT = "newChat.result"
    const val WRITE = "newChat.write"
    const val INVITE = "newChat.invite"
    const val INFO = "newChat.info"
    const val SCAN = "newChat.scan"
    const val MY_CODE = "newChat.myCode"
    const val OPTIONS = "newChat.options"
    const val HINT = "newChat.hint"

    /** The search field's chrome (the text field inside is `newChat.field`). */
    const val FIELD = "newChat.search"
}

/** The pages of the 4.2 sheet: the search, “Kodumu göster” and “Davet et” pushed inside it. */
private enum class NewChatPage { Search, MyCode, Invite }

/**
 * 4.2 · Yeni sohbet, the full-height sheet (C18, top at design y 58).
 *
 * Exact-username search only: the result appears for the complete username and nothing else (no
 * similar names, no suggestions, no autocomplete; autocorrect and capitalisation off). Below it
 * “QR kodla ekle” (→ 6.2) and “Kodumu göster” (pushed inside the sheet, proposed: the 6.1 QR card
 * without the peer). After a search found nothing, “Davet et” pushes the invitation.
 */
@Composable
internal fun NewChatSheetContent(
    presentation: NewChatPresentation,
    onOpenChat: (ChatId) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalAppModel.current
    val messenger = app.messenger
    val screens = LocalFeatureScreens.current
    val scope = rememberCoroutineScope()
    // Built with the sheet, so the search page is there from the first frame and the whole sheet moves as one piece.
    val model = remember {
        NewChatModel(messenger, scope).also {
            it.debug = presentation.debug
        }
    }
    var page by remember { mutableStateOf(if (presentation.debug.startsOnMyCode) NewChatPage.MyCode else NewChatPage.Search) }
    var isForward by remember { mutableStateOf(true) }
    var showsScanner by remember { mutableStateOf(false) }
    // Loaded with the sheet, so the pushed “Kodumu göster” page is complete.
    var myCodePayload by remember { mutableStateOf<String?>(null) }
    val ownUsername by messenger.ownUsername.collectAsState()

    LaunchedEffect(Unit) { model.query = presentation.initialQuery }
    LaunchedEffect(messenger) { myCodePayload = messenger.preparedOwnContactCode() }
    DisposableEffect(model) { onDispose { model.cancel() } }

    fun go(to: NewChatPage) {
        isForward = to != NewChatPage.Search
        page = to
    }

    val forward = isForward
    val enter = U2Transitions.soft(DpOffset(if (forward) 16.dp else (-16).dp, 0.dp), blur = 0.dp, timing = U2Motion.screen)
    val exit = U2Transitions.soft(DpOffset(if (forward) (-16).dp else 16.dp, 0.dp), blur = 0.dp, timing = U2Motion.screen)
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        AnimatedContent(
            targetState = page,
            transitionSpec = { (enter.enter togetherWith exit.exit).using(SizeTransform(clip = false)) },
            contentAlignment = Alignment.TopCenter,
            label = "newChatPage",
        ) { shown ->
            when (shown) {
                NewChatPage.Search -> NewChatSearchPage(
                    model = model,
                    onOpenChat = onOpenChat,
                    onCancel = onCancel,
                    onScan = { showsScanner = true },
                    onMyCode = { go(NewChatPage.MyCode) },
                    onInvite = { go(NewChatPage.Invite) },
                )
                NewChatPage.MyCode -> MyCodeSheet(
                    username = ownUsername,
                    payload = myCodePayload,
                    onClose = { go(NewChatPage.Search) },
                    closeTitle = stringResource(R.string.common_back),
                )
                NewChatPage.Invite -> InviteView(
                    username = ownUsername,
                    closeTitle = stringResource(R.string.common_back),
                    onClose = { go(NewChatPage.Search) },
                )
            }
        }
    }
    // 6.2 from 4.2: a match adds and verifies; “Sohbete dön” fades the cover out, then the sheet
    // goes down and 4.1 pushes the chat.
    var chatAfterScanner by remember { mutableStateOf<ChatId?>(null) }
    U2FadeCover(
        isPresented = showsScanner,
        onEscape = { showsScanner = false },
        onDismissed = {
            chatAfterScanner?.let { chat ->
                chatAfterScanner = null
                onOpenChat(chat)
            }
        },
    ) {
        screens.QrScan(QrScanMode.AddContact, onOpenChat = { chatAfterScanner = it }, onDismiss = { showsScanner = false }, modifier = Modifier)
    }
}

@Composable
private fun NewChatSearchPage(
    model: NewChatModel,
    onOpenChat: (ChatId) -> Unit,
    onCancel: () -> Unit,
    onScan: () -> Unit,
    onMyCode: () -> Unit,
    onInvite: () -> Unit,
) {
    val app = LocalAppModel.current
    val scope = rememberCoroutineScope()
    val forcesFocusChrome = model.debug.forcesFocusChrome

    fun openChat(peer: Peer) {
        scope.launch { model.startChat(peer)?.let(onOpenChat) }
    }

    LaunchedEffect(Unit) {
        if (ChatsDemo.fromRaw(app.configuration.chatsDemo) != ChatsDemo.Write) return@LaunchedEffect
        delay(2.2.seconds)
        (model.phase as? NewChatModel.Phase.Found)?.let { openChat(it.peer) }
    }

    Column(Modifier.fillMaxSize()) {
        SheetBar(
            title = stringResource(R.string.common_newchat_title),
            cancelTitle = stringResource(R.string.common_cancel),
            onCancel = onCancel,
            modifier = Modifier.testTag(NewChatTags.BAR),
        )
        // Search block (pad 10 16 0, gap 8): the C23 field and the hint.
        Column(Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UsernameSearchField(
                text = model.query,
                onTextChange = { model.query = it },
                isSearching = model.phase == NewChatModel.Phase.Searching,
                focusesOnAppear = !forcesFocusChrome,
                showsFocusChrome = forcesFocusChrome,
                onSubmit = model::submit,
                modifier = Modifier.testTag(NewChatTags.FIELD),
            )
            U2Text(
                stringResource(R.string.common_newchat_hint),
                U2TextStyles.monoMeta,
                color = U2Theme.colors.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(NewChatTags.HINT)
                    .padding(horizontal = 4.dp),
            )
        }
        // Plain when everything fits (swipe-down works anywhere on the sheet); scrollable when the
        // keyboard leaves too little room.
        val scroll = rememberScrollState()
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(scroll, enabled = scroll.maxValue > 0),
        ) {
            Results(model, onOpenChat = ::openChat, onInvite = onInvite)
            Options(model.ownUsername, onScan, onMyCode, Modifier.padding(start = 16.dp, top = 22.dp, end = 16.dp, bottom = 24.dp))
        }
    }
}

/** What the result card shows; the card rises anew when it changes (another name or kind). */
@Immutable
private data class ResultCardState(val name: String, val kind: CardKind, val peer: Peer?)

private sealed interface CardKind {
    data object Found : CardKind

    data object Existing : CardKind

    data object NotFound : CardKind

    data class Invalid(val violation: UsernameRules.Violation) : CardKind

    data object Own : CardKind
}

private fun cardState(model: NewChatModel): ResultCardState? = when (val phase = model.phase) {
    is NewChatModel.Phase.Found -> ResultCardState(model.resultName, if (phase.isExistingChat) CardKind.Existing else CardKind.Found, phase.peer)
    NewChatModel.Phase.NotFound -> ResultCardState(model.resultName, CardKind.NotFound, null)
    is NewChatModel.Phase.Invalid -> ResultCardState(model.resultName, CardKind.Invalid(phase.violation), null)
    NewChatModel.Phase.OwnName -> ResultCardState(model.resultName, CardKind.Own, null)
    NewChatModel.Phase.Idle, NewChatModel.Phase.Pending, NewChatModel.Phase.Searching -> null
}

@Composable
private fun Results(model: NewChatModel, onOpenChat: (Peer) -> Unit, onInvite: () -> Unit) {
    val current = cardState(model)
    val rise = U2Transitions.rise()
    Column(Modifier.fillMaxWidth()) {
        AnimatedContent(
            targetState = current,
            transitionSpec = { (rise.enter togetherWith rise.exit).using(SizeTransform(clip = false)) },
            label = "newChatResult",
        ) { card ->
            if (card != null) {
                // The picture belongs to the card on show; a card fading out keeps the initial.
                val avatar = if (card == current) model.resultAvatar else NewChatModel.ResultAvatar.NoResult
                ResultCardFor(
                    card = card,
                    avatar = avatar,
                    isStarting = model.isStarting,
                    onOpenChat = onOpenChat,
                    onInvite = onInvite,
                    modifier = Modifier
                        .padding(start = 16.dp, top = 18.dp, end = 16.dp)
                        .then(u2TransitionBlur(rise)),
                )
            }
        }
        val found = model.phase as? NewChatModel.Phase.Found
        val reduceMotion = LocalReduceMotion.current
        val timing = U2Motion.resolve(U2Motion.state, reduceMotion)
        AnimatedVisibility(
            visible = found != null && !found.isExistingChat,
            // In after the card (0.36 s later); out at once.
            enter = fadeIn(timing.delayed(timing.seconds).spec()),
            exit = fadeOut(timing.spec()),
        ) {
            InfoRow(found?.peer?.username ?: model.resultName, Modifier.padding(start = 24.dp, top = 16.dp, end = 24.dp))
        }
    }
}

@Composable
private fun ResultCardFor(
    card: ResultCardState,
    avatar: NewChatModel.ResultAvatar,
    isStarting: Boolean,
    onOpenChat: (Peer) -> Unit,
    onInvite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = U2Theme.colors
    when (card.kind) {
        CardKind.Found, CardKind.Existing -> {
            val peer = card.peer ?: return
            val existing = card.kind == CardKind.Existing
            ResultCard(
                username = peer.username,
                palette = peer.palette,
                picture = avatar.picture,
                isLoadingAvatar = avatar == NewChatModel.ResultAvatar.Loading,
                name = peer.username,
                nameColor = colors.text,
                subtitle = stringResource(if (existing) R.string.chats_newchat_existing else R.string.chats_newchat_found),
                status = ResultStatus.Found,
                modifier = modifier,
            ) {
                U2PrimaryButton(
                    onClick = { onOpenChat(peer) },
                    size = U2PrimaryButtonSize.InCard,
                    enabled = !isStarting,
                    modifier = Modifier.testTag(NewChatTags.WRITE),
                ) {
                    U2ButtonLabel(
                        stringResource(if (existing) R.string.chats_newchat_openchat else R.string.chats_newchat_write),
                        glyph = if (existing) PhosphorGlyph.ChatCircleDots else PhosphorGlyph.PaperPlaneTilt,
                        glyphSize = 20.dp,
                    )
                }
            }
        }
        CardKind.NotFound, is CardKind.Invalid -> ResultCard(
            username = "?",
            palette = AvatarPalette.C,
            picture = AvatarPicture.Initials,
            name = card.name,
            nameColor = colors.textSecondary,
            subtitle = stringResource(notFoundSubtitle(card.kind)),
            status = ResultStatus.NotFound,
            modifier = modifier,
        ) {
            // “Davet et”: the invitation inside the 4.2 sheet.
            U2PrimaryButton(onClick = onInvite, size = U2PrimaryButtonSize.InCard, modifier = Modifier.testTag(NewChatTags.INVITE)) {
                U2ButtonLabel(stringResource(R.string.invite_entry_notfound), glyph = PhosphorGlyph.PaperPlaneTilt, glyphSize = 20.dp)
            }
        }
        CardKind.Own -> ResultCard(
            username = card.name,
            palette = AvatarPalette.D,
            picture = avatar.picture,
            name = card.name,
            nameColor = colors.text,
            subtitle = stringResource(R.string.chats_newchat_own),
            status = ResultStatus.None,
            modifier = modifier,
        )
    }
}

/** “Kullanıcı bulunamadı”, or for a name nobody can have, the rule it breaks. */
private fun notFoundSubtitle(kind: CardKind): Int = when ((kind as? CardKind.Invalid)?.violation) {
    null -> R.string.chats_newchat_notfound
    UsernameRules.Violation.TurkishCharacters -> R.string.chats_newchat_invalid_turkish
    UsernameRules.Violation.Characters -> R.string.chats_newchat_invalid_characters
    UsernameRules.Violation.FirstLetter -> R.string.chats_newchat_invalid_firstletter
    // A name too short is not searched (the hint only): this one is too long.
    UsernameRules.Violation.Length -> R.string.chats_newchat_invalid_toolong
}

private enum class ResultStatus { None, Found, NotFound }

/**
 * The 4.2 result card: `bg` r20, pad 16, gap 14: avatar 52 · name (`monoUsernameL`) + subtitle
 * (`caption`, gap 3) · status glyph 22; the C1 in-card button below when there is something to do.
 * While the result's picture is on its way (proposed) a ring travels round the avatar; the picture
 * cross-fades in over the initial.
 */
@Composable
private fun ResultCard(
    username: String,
    palette: AvatarPalette,
    picture: AvatarPicture,
    name: String,
    nameColor: Color,
    subtitle: String,
    status: ResultStatus,
    modifier: Modifier = Modifier,
    isLoadingAvatar: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = U2Theme.colors
    Column(
        modifier
            .fillMaxWidth()
            .testTag(NewChatTags.RESULT)
            .background(colors.bg, RoundedCornerShape(U2Radius.cardL))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            Modifier.semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Avatar(username, palette, size = AvatarSize.ListRow, picture = picture)
                LoadingRingOverlay(isLoadingAvatar)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                U2Text(name, U2TextStyles.monoUsernameL, color = nameColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                U2Text(subtitle, U2TextStyles.caption, color = colors.textSecondary)
            }
            when (status) {
                ResultStatus.None -> Unit
                ResultStatus.Found -> PhosphorIcon(PhosphorGlyph.CheckCircle, size = 22.dp, weight = PhosphorWeight.Fill, tint = colors.online)
                ResultStatus.NotFound -> PhosphorIcon(PhosphorGlyph.XCircle, size = 22.dp, weight = PhosphorWeight.Fill, tint = colors.danger)
            }
        }
        action?.invoke()
    }
}

/** The loading ring fading in and out over the avatar. */
@Composable
private fun LoadingRingOverlay(visible: Boolean) {
    val fade = U2Transitions.fade()
    AnimatedVisibility(visible, enter = fade.enter, exit = fade.exit) {
        AvatarLoadingRing(AvatarSize.ListRow.diameter.value)
    }
}

/** The ring that travels round a result's avatar while its picture is fetched (6 wider than the avatar). */
@Composable
private fun AvatarLoadingRing(diameter: Float) {
    val accent = U2Theme.colors.accent
    MediaProgressRing(
        fraction = null,
        lineWidth = 2.dp,
        tint = accent,
        track = accent.copy(alpha = accent.alpha * 0.18f),
        modifier = Modifier.requiredSize((diameter + 6f).dp),
    )
}

/** Info row (pad 16 24 0, gap 12): `ph:tray` 18 + `callout` with “Mesaj istekleri” emphasised in `text`. */
@Composable
private fun InfoRow(username: String, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val emphasis = stringResource(R.string.chats_newchat_info_requests)
    val text = annotatedStringResource(R.string.chats_newchat_info, listOf(username, emphasis), mapOf(2 to SpanStyle(color = colors.text)))
    Row(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag(NewChatTags.INFO),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PhosphorIcon(PhosphorGlyph.Tray, size = 18.dp, tint = colors.accent300, modifier = Modifier.padding(top = 1.dp))
        U2Text(text, U2TextStyles.callout, color = colors.textSecondary, modifier = Modifier.weight(1f))
    }
}

/** Options group (pad 22 16 0): C13 card on `bg`, 62 dp rows. */
@Composable
private fun Options(ownUsername: String, onScan: () -> Unit, onMyCode: () -> Unit, modifier: Modifier = Modifier) {
    GroupedCard(modifier.testTag(NewChatTags.OPTIONS), fill = U2Theme.colors.bg) {
        GroupedNavigationRow(
            tile = { IconTile(IconTileContent.Glyph(PhosphorGlyph.Scan)) },
            title = stringResource(R.string.chats_qr_add),
            subtitle = stringResource(R.string.chats_qr_add_subtitle),
            onClick = onScan,
            modifier = Modifier.testTag(NewChatTags.SCAN),
        )
        GroupedSeparator()
        GroupedNavigationRow(
            tile = { IconTile(IconTileContent.Glyph(PhosphorGlyph.QrCode)) },
            title = stringResource(R.string.chats_qr_mine),
            subtitle = "@$ownUsername",
            onClick = onMyCode,
            modifier = Modifier.testTag(NewChatTags.MY_CODE),
        )
    }
}
