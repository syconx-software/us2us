package com.syconx.us2us.feature.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.CommunitiesService
import com.syconx.us2us.domain.CommunityApplication
import com.syconx.us2us.domain.CommunityCard
import com.syconx.us2us.domain.CommunityFormat
import com.syconx.us2us.domain.CommunityPage
import com.syconx.us2us.domain.CommunitySnapshot
import com.syconx.us2us.domain.CommunityText
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.CenteredTitleHeader
import com.syconx.us2us.ui.components.ConfirmationTags
import com.syconx.us2us.ui.components.DestructiveConfirmation
import com.syconx.us2us.ui.components.GroupedCard
import com.syconx.us2us.ui.components.GroupedNavigationRow
import com.syconx.us2us.ui.components.GroupedRow
import com.syconx.us2us.ui.components.GroupedSection
import com.syconx.us2us.ui.components.GroupedSeparator
import com.syconx.us2us.ui.components.GroupedToggleRow
import com.syconx.us2us.ui.components.IconTile
import com.syconx.us2us.ui.components.IconTileContent
import com.syconx.us2us.ui.components.InfoBanner
import com.syconx.us2us.ui.components.NoteCard
import com.syconx.us2us.ui.components.SectionLabel
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.U2SecondaryButton
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.components.U2TextField
import com.syconx.us2us.ui.components.text.U2DisplayText
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.env.u2Top
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.sheet.U2Sheet
import com.syconx.us2us.ui.sheet.U2SheetStyle
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.rememberU2BackdropSource
import com.syconx.us2us.ui.theme.u2BackdropSource
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Test tags of the communities surface. */
internal object CommunityTags {
    const val SCREEN = "communities.screen"
    const val BACK = "communities.back"
    const val INVITE = "communities.invite"
    const val WARNING = "communities.warning"
    const val ERROR = "communities.error"
    const val CONVERSATION = "communities.conversation"
    const val COMPOSER = "communities.composer"
    const val MANAGE = "communities.manage"
    const val MANAGE_SHEET = "communities.manageSheet"
    fun group(id: String) = "communities.group.$id"
    fun request(account: String) = "communities.request.$account"
    fun remove(account: String) = "communities.remove.$account"
}

/** A form that rises over the screen; each one first says what the relay can see. */
private sealed interface CommunityForm {
    data object Create : CommunityForm
    data object Redeem : CommunityForm
    data class Join(val group: CommunityCard) : CommunityForm
}

/** An irreversible step, confirmed first. */
internal sealed interface CommunityConfirmation {
    val group: String
    data class RemoveMember(override val group: String, val member: String) : CommunityConfirmation
    data class Leave(override val group: String) : CommunityConfirmation
    data class Withdraw(override val group: String) : CommunityConfirmation
    data class Forget(override val group: String) : CommunityConfirmation
    data class Delete(override val group: String) : CommunityConfirmation
}

/**
 * A one-use invitation on screen. It stays only while it can still be unused: a new join request
 * (someone redeemed it), a roster change (an approval), closing the sheet or a lock hides it.
 */
private data class IssuedInvite(val code: String, val group: String, val revision: Long, val requests: Set<String>)

private const val POLL_MILLIS = 10_000L
private const val MAX_TEXT_BYTES = 8192
private const val SEARCH_DEBOUNCE_MILLIS = 300L
private const val SEARCH_MIN_CHARACTERS = 2

/**
 * Independent foreground surface: its failures never stop the direct chat feed. Nothing it shows
 * survives a lock (the app stays composed under the lock screen): on lock every list, draft and
 * invitation is dropped, and the poll starts again after the next unlock.
 */
@Composable
internal fun CommunitiesScreen(service: CommunitiesService, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val unlocked by remember(service) { service.unlocked }.collectAsState(initial = false)
    var snapshot by remember { mutableStateOf(CommunitySnapshot(emptyList(), true)) }
    var directory by remember { mutableStateOf(CommunityPage(emptyList(), null)) }
    var selected by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf<List<CommunityText>>(emptyList()) }
    var applications by remember { mutableStateOf<List<CommunityApplication>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var public by remember { mutableStateOf(false) }
    var invite by remember { mutableStateOf("") }
    var issued by remember { mutableStateOf<IssuedInvite?>(null) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf<CommunityForm?>(null) }
    var confirmation by remember { mutableStateOf<CommunityConfirmation?>(null) }
    var showsManage by remember { mutableStateOf(false) }
    var sheetConfirmation by remember { mutableStateOf<CommunityConfirmation?>(null) }
    val group = snapshot.groups.firstOrNull { it.id == selected }

    fun closeManage() {
        showsManage = false; sheetConfirmation = null; issued = null
    }
    fun closeGroup() {
        closeManage()
        selected = null; messages = emptyList(); applications = emptyList(); draft = ""
    }
    fun clearForLock() {
        closeGroup()
        snapshot = CommunitySnapshot(emptyList(), true); directory = CommunityPage(emptyList(), null)
        query = ""; name = ""; invite = ""; failed = false; form = null; confirmation = null
    }
    suspend fun refresh() {
        val next = service.refresh()
        if (!unlocked) return
        snapshot = next
        val current = next.groups.firstOrNull { it.id == selected }
        if (current != null && !current.pending) {
            messages = service.messages(current.id)
            applications = if (current.isOwner && current.active) service.applications(current.id) else emptyList()
        }
        issued?.let { shown ->
            val unchanged = current != null && current.id == shown.group && current.active && current.revision == shown.revision &&
                applications.all { it.account in shown.requests }
            if (!unchanged) issued = null
        }
    }
    suspend fun search(text: String, after: String? = null) {
        val page = service.search(text.trim(), after)
        if (!unlocked) return
        directory = if (after == null) page else CommunityPage(directory.groups + page.groups, page.next)
    }
    suspend fun carryOut(step: CommunityConfirmation) {
        when (step) {
            is CommunityConfirmation.RemoveMember -> service.remove(step.group, step.member)
            is CommunityConfirmation.Leave -> { service.leave(step.group); closeGroup() }
            is CommunityConfirmation.Withdraw -> service.withdraw(step.group)
            is CommunityConfirmation.Forget -> { service.forget(step.group); closeGroup() }
            is CommunityConfirmation.Delete -> { service.delete(step.group); closeGroup() }
        }
    }
    fun perform(action: suspend () -> Unit) {
        if (busy || !unlocked) return
        scope.launch {
            busy = true; failed = false
            try { action(); refresh() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (unlocked) failed = true }
            finally { busy = false }
        }
    }

    LaunchedEffect(service, unlocked, selected) {
        if (!unlocked) {
            clearForLock()
            return@LaunchedEffect
        }
        failed = false
        while (true) {
            if (!busy) {
                try {
                    refresh()
                } catch (e: CancellationException) {
                    // A lock cancels the request, not this loop: it waits for the unlock instead.
                    currentCoroutineContext().ensureActive()
                } catch (_: Exception) {
                    if (unlocked) failed = true
                }
            }
            delay(POLL_MILLIS)
        }
    }
    // Searches reach the relay: only from two characters on (or on submit), and debounced.
    LaunchedEffect(service, unlocked, query) {
        if (!unlocked || selected != null) return@LaunchedEffect
        val text = query.trim()
        if (text.isNotEmpty() && text.length < SEARCH_MIN_CHARACTERS) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MILLIS)
        try { search(text) } catch (e: CancellationException) { currentCoroutineContext().ensureActive() } catch (_: Exception) { if (unlocked) failed = true }
    }

    val privateName = stringResource(R.string.community_private)
    U2Screen {
        if (group == null) {
            Directory(
                snapshot = snapshot, directory = directory, query = query, onQuery = { query = it },
                busy = busy, failed = failed, privateName = privateName,
                onOpen = { selected = it; failed = false },
                onWithdraw = { confirmation = CommunityConfirmation.Withdraw(it) },
                onJoin = { form = CommunityForm.Join(it) },
                onSearch = { perform { search(query) } },
                onMore = { perform { search(query, directory.next) } },
                onCreate = { failed = false; form = CommunityForm.Create },
                onRedeem = { failed = false; form = CommunityForm.Redeem },
                onClose = onClose,
            )
        } else {
            CommunityConversation(
                group = group,
                title = group.name.ifEmpty { privateName },
                messages = messages,
                draft = draft,
                onDraft = { if (it.encodeToByteArray().size <= MAX_TEXT_BYTES) draft = it },
                canSend = !busy && draft.isNotBlank(),
                onSend = { val text = draft; perform { service.send(group.id, text); if (draft == text) draft = "" } },
                failed = failed,
                warning = snapshot.warning,
                busy = busy,
                onBack = ::closeGroup,
                onManage = { failed = false; showsManage = true },
            )
        }

        // Members, requests, invitations and leaving live in their own sheet, so the conversation keeps the screen.
        U2Sheet(isPresented = showsManage && group != null, onDismissRequest = { if (!busy) closeManage() }, style = U2SheetStyle.FullHeight) {
            group?.let { current ->
                CommunityManageSheet(
                    group = current,
                    title = current.name.ifEmpty { privateName },
                    applications = applications,
                    invite = issued?.takeIf { it.group == current.id }?.code,
                    busy = busy,
                    failed = failed,
                    confirmation = sheetConfirmation,
                    onClose = ::closeManage,
                    onApprove = { application -> perform { service.approve(current.id, application); issued = null } },
                    onReject = { application -> perform { service.reject(current.id, application.account) } },
                    onInvite = {
                        val requests = applications.map { it.account }.toSet()
                        perform { issued = IssuedInvite(service.invite(current.id), current.id, current.revision, requests) }
                    },
                    onRevoke = { perform { service.revokeInvites(current.id); issued = null } },
                    onConfirm = { sheetConfirmation = it },
                    onConfirmed = { step ->
                        perform { try { carryOut(step) } finally { sheetConfirmation = null } }
                    },
                    onCancelConfirmation = { sheetConfirmation = null },
                )
            }
        }
        U2Sheet(item = form, onDismissRequest = { if (!busy) form = null }, style = U2SheetStyle.Content) { current ->
            FormSheet(
                form = current,
                name = name, onName = { name = it }, public = public, onPublic = { public = it },
                invite = invite, onInvite = { invite = it }, busy = busy, failed = failed,
                onCancel = { form = null; invite = "" },
                onSubmit = {
                    when (current) {
                        CommunityForm.Create -> perform { service.create(name.trim(), public); name = ""; form = null }
                        CommunityForm.Redeem -> perform { service.redeem(invite.trim()); invite = ""; form = null }
                        is CommunityForm.Join -> { form = null; perform { service.request(current.group) } }
                    }
                },
            )
        }
        U2Sheet(item = confirmation, onDismissRequest = { confirmation = null }, style = U2SheetStyle.Floating) { current ->
            val (title, message, confirm) = current.texts()
            DestructiveConfirmation(
                title = stringResource(title),
                message = stringResource(message),
                confirmTitle = stringResource(confirm),
                onConfirm = {
                    confirmation = null
                    perform { carryOut(current) }
                },
                onCancel = { confirmation = null },
                confirmGlyph = current.glyph,
                tags = ConfirmationTags("communities.confirm", "communities.cancel"),
                modifier = Modifier.padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 28.dp),
            )
        }
    }
}

/** The confirmation's title, message and button. */
internal fun CommunityConfirmation.texts(): Triple<Int, Int, Int> = when (this) {
    is CommunityConfirmation.RemoveMember -> Triple(R.string.community_remove_title, R.string.community_remove_message, R.string.community_remove)
    is CommunityConfirmation.Leave -> Triple(R.string.community_leave_title, R.string.community_leave_message, R.string.community_leave)
    is CommunityConfirmation.Withdraw -> Triple(R.string.community_withdraw_title, R.string.community_withdraw_message, R.string.community_withdraw)
    is CommunityConfirmation.Forget -> Triple(R.string.community_forget_title, R.string.community_forget_message, R.string.community_forget)
    is CommunityConfirmation.Delete -> Triple(R.string.community_delete_title, R.string.community_delete_message, R.string.community_delete_confirm)
}

internal val CommunityConfirmation.glyph: PhosphorGlyph
    get() = when (this) {
        is CommunityConfirmation.Leave, is CommunityConfirmation.Withdraw -> PhosphorGlyph.LinkBreak
        is CommunityConfirmation.RemoveMember -> PhosphorGlyph.Prohibit
        is CommunityConfirmation.Forget, is CommunityConfirmation.Delete -> PhosphorGlyph.Trash
    }

/** The joined communities, the public directory and the ways in. */
@Composable
private fun Directory(
    snapshot: CommunitySnapshot,
    directory: CommunityPage,
    query: String,
    onQuery: (String) -> Unit,
    busy: Boolean,
    failed: Boolean,
    privateName: String,
    onOpen: (String) -> Unit,
    onWithdraw: (String) -> Unit,
    onJoin: (CommunityCard) -> Unit,
    onSearch: () -> Unit,
    onMore: () -> Unit,
    onCreate: () -> Unit,
    onRedeem: () -> Unit,
    onClose: () -> Unit,
) {
    val backdrop = rememberU2BackdropSource()
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 4 } }
    LazyColumn(
        Modifier.fillMaxSize().u2BackdropSource(backdrop).testTag(CommunityTags.SCREEN),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "top") { Spacer(Modifier.u2Top(100.dp)) }
        item(key = "privacy") { InfoBanner(stringResource(R.string.community_privacy)) }
        if (snapshot.warning) item(key = "warning") {
            NoteCard(stringResource(R.string.community_warning_title), stringResource(R.string.community_warning_message), Modifier.testTag(CommunityTags.WARNING))
        }
        if (!snapshot.supported) item(key = "unsupported") { Note(stringResource(R.string.community_unsupported)) }
        if (failed) item(key = "error") { ErrorText() }
        item(key = "joined") {
            GroupedSection(stringResource(R.string.community_joined)) {
                if (snapshot.groups.isEmpty()) GroupedRow(tile = null, title = stringResource(R.string.community_empty))
                snapshot.groups.forEachIndexed { index, card ->
                    if (index > 0) GroupedSeparator()
                    JoinedRow(card, privateName, enabled = !busy, onOpen = { onOpen(card.id) }, onWithdraw = { onWithdraw(card.id) })
                }
            }
        }
        item(key = "discover") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.community_discover))
                U2TextField(
                    query, onQuery, stringResource(R.string.community_search),
                    maxBytes = 120, enabled = snapshot.supported,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                )
                Note(stringResource(R.string.community_search_note))
            }
        }
        if (directory.groups.isNotEmpty()) item(key = "directory") {
            GroupedCard {
                directory.groups.forEachIndexed { index, card ->
                    if (index > 0) GroupedSeparator()
                    GroupedRow(tile = { CommunityTile() }, title = card.name, subtitle = "${card.count}/100 · ${CommunityFormat.shortId(card.id)}") {
                        U2TextButton(
                            stringResource(R.string.community_request), onClick = { onJoin(card) },
                            tone = U2TextButtonTone.Accent, height = U2Size.iconButton, fillsWidth = false,
                            enabled = !busy && card.count < 100 && snapshot.groups.none { it.id == card.id },
                        )
                    }
                }
            }
        }
        if (directory.next != null) item(key = "more") {
            U2TextButton(stringResource(R.string.community_more), onClick = onMore, tone = U2TextButtonTone.Accent, enabled = !busy)
        }
        item(key = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                U2SecondaryButton(stringResource(R.string.community_create), onClick = onCreate, glyph = PhosphorGlyph.Plus, enabled = !busy && snapshot.supported)
                U2SecondaryButton(stringResource(R.string.community_use_invite), onClick = onRedeem, glyph = PhosphorGlyph.QrCode, enabled = !busy && snapshot.supported)
                Note(stringResource(R.string.community_restore))
            }
        }
    }
    ChatsHeaderBackdrop(isVisible = scrolled, source = backdrop)
    CenteredTitleHeader(
        title = stringResource(R.string.community_title),
        leading = {
            U2GlassIconButton(
                glyph = PhosphorGlyph.CaretLeft,
                contentDescription = stringResource(R.string.community_back),
                onClick = onClose,
                modifier = Modifier.testTag(CommunityTags.BACK),
                enabled = !busy,
            )
        },
    )
}

@Composable
internal fun Note(text: String, modifier: Modifier = Modifier) {
    U2Text(text, U2TextStyles.caption, modifier.padding(horizontal = 4.dp), color = U2Theme.colors.textSecondary)
}

@Composable
internal fun ErrorText(modifier: Modifier = Modifier) {
    U2Text(stringResource(R.string.community_error), U2TextStyles.callout, modifier.testTag(CommunityTags.ERROR), color = U2Theme.colors.danger)
}

@Composable
private fun CommunityTile() = IconTile(IconTileContent.Glyph(PhosphorGlyph.ChatCircleDots))

@Composable
private fun JoinedRow(card: CommunityCard, privateName: String, enabled: Boolean, onOpen: () -> Unit, onWithdraw: () -> Unit) {
    val title = card.name.ifEmpty { privateName }
    if (card.pending) {
        GroupedRow(tile = { CommunityTile() }, title = title, subtitle = stringResource(R.string.community_pending), modifier = Modifier.testTag(CommunityTags.group(card.id))) {
            U2TextButton(stringResource(R.string.community_withdraw), onClick = onWithdraw, tone = U2TextButtonTone.Danger, height = U2Size.iconButton, fillsWidth = false, enabled = enabled)
        }
    } else {
        GroupedNavigationRow(
            tile = { CommunityTile() },
            title = title,
            onClick = { if (enabled) onOpen() },
            detail = if (card.active) "${card.count}/100" else stringResource(R.string.community_ended),
            modifier = Modifier.testTag(CommunityTags.group(card.id)),
        )
    }
}

@Composable
private fun FormSheet(
    form: CommunityForm,
    name: String,
    onName: (String) -> Unit,
    public: Boolean,
    onPublic: (Boolean) -> Unit,
    invite: String,
    onInvite: (String) -> Unit,
    busy: Boolean,
    failed: Boolean,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(start = 18.dp, top = 24.dp, end = 18.dp).u2Bottom(40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val title = when (form) {
            CommunityForm.Create -> R.string.community_new
            CommunityForm.Redeem -> R.string.community_invitation
            is CommunityForm.Join -> R.string.community_request_title
        }
        U2DisplayText(stringResource(title), U2TextStyles.displaySheetTitle.balanced, isHeader = true)
        // What the relay can see, before anything is sent to it.
        InfoBanner(stringResource(R.string.community_privacy))
        val canSubmit = when (form) {
            CommunityForm.Create -> {
                U2TextField(name, onName, stringResource(R.string.community_name), maxBytes = 120, fill = U2Theme.colors.bg)
                GroupedCard(fill = U2Theme.colors.bg) {
                    GroupedToggleRow(tile = null, title = stringResource(R.string.community_public_toggle), checked = public, onCheckedChange = onPublic)
                }
                if (!public) U2Text(stringResource(R.string.community_private_note), U2TextStyles.caption, color = U2Theme.colors.textSecondary)
                name.isNotBlank()
            }
            CommunityForm.Redeem -> {
                U2TextField(
                    invite, onInvite, stringResource(R.string.community_invite_code), maxLines = 4, maxBytes = 2048, fill = U2Theme.colors.bg,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                )
                invite.isNotBlank()
            }
            is CommunityForm.Join -> {
                U2Text(form.group.name, U2TextStyles.rowTitle)
                true
            }
        }
        if (failed) U2Text(stringResource(R.string.community_error), U2TextStyles.callout, color = U2Theme.colors.danger)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val submit = when (form) {
                CommunityForm.Create -> R.string.community_create_confirm
                CommunityForm.Redeem -> R.string.community_request
                is CommunityForm.Join -> R.string.community_send_request
            }
            U2PrimaryButton(stringResource(submit), onClick = onSubmit, enabled = !busy && canSubmit, isWorking = busy)
            U2TextButton(stringResource(R.string.common_cancel), onClick = onCancel, tone = U2TextButtonTone.Accent, height = U2Size.textButtonS, enabled = !busy)
        }
    }
}
