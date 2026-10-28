package com.syconx.us2us.feature.conversation

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.CommunitiesService
import com.syconx.us2us.domain.CommunityApplication
import com.syconx.us2us.domain.CommunityCard
import com.syconx.us2us.domain.CommunityFormat
import com.syconx.us2us.domain.CommunityMemberCard
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.ConfirmationTags
import com.syconx.us2us.ui.components.DestructiveConfirmation
import com.syconx.us2us.ui.components.GroupedCard
import com.syconx.us2us.ui.components.GroupedRow
import com.syconx.us2us.ui.components.GroupedSeparator
import com.syconx.us2us.ui.components.NoteCard
import com.syconx.us2us.ui.components.SectionLabel
import com.syconx.us2us.ui.components.SheetCloseHeader
import com.syconx.us2us.ui.components.U2Card
import com.syconx.us2us.ui.components.U2DangerButton
import com.syconx.us2us.ui.components.U2DecisionButton
import com.syconx.us2us.ui.components.U2DecisionKind
import com.syconx.us2us.ui.components.U2FlexRow
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.U2PrimaryButtonSize
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.components.communityMemberName
import com.syconx.us2us.ui.components.u2Flex
import com.syconx.us2us.ui.env.LocalExternalActivityGuard
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Opacity
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Test tags of a community's members and management sheet. */
internal object CommunityManageTags {
    const val SHEET = "communities.manageSheet"
    const val INVITE = "communities.invite"
    const val ERROR = "communities.error"
    const val MANAGE = "communities.manage"
    const val REJECT_ALL = "communities.rejectAll"
    fun request(account: String) = "communities.request.$account"
    fun remove(account: String) = "communities.remove.$account"
}

/** An irreversible step of the sheet, confirmed in the same sheet first. */
internal sealed interface CommunityConfirmation {
    val group: String
    data class RemoveMember(override val group: String, val member: String) : CommunityConfirmation
    data class Leave(override val group: String) : CommunityConfirmation
    data class Forget(override val group: String) : CommunityConfirmation
    data class Delete(override val group: String) : CommunityConfirmation
    data class RejectAll(override val group: String) : CommunityConfirmation
}

/** The confirmation's title, message and button. */
internal fun CommunityConfirmation.texts(): Triple<Int, Int, Int> = when (this) {
    is CommunityConfirmation.RemoveMember -> Triple(R.string.community_remove_title, R.string.community_remove_message, R.string.community_remove)
    is CommunityConfirmation.Leave -> Triple(R.string.community_leave_title, R.string.community_leave_message, R.string.community_leave)
    is CommunityConfirmation.Forget -> Triple(R.string.community_forget_title, R.string.community_forget_message, R.string.community_forget)
    is CommunityConfirmation.Delete -> Triple(R.string.community_delete_title, R.string.community_delete_message, R.string.community_delete_confirm)
    is CommunityConfirmation.RejectAll -> Triple(
        R.string.common_community_rejectall_title, R.string.common_community_rejectall_message, R.string.common_community_rejectall_confirm,
    )
}

internal val CommunityConfirmation.glyph: PhosphorGlyph
    get() = when (this) {
        is CommunityConfirmation.Leave -> PhosphorGlyph.LinkBreak
        is CommunityConfirmation.RemoveMember, is CommunityConfirmation.RejectAll -> PhosphorGlyph.Prohibit
        is CommunityConfirmation.Forget, is CommunityConfirmation.Delete -> PhosphorGlyph.Trash
    }

/**
 * A one-use invitation on screen. It stays only while it can still be unused: a new join request
 * (someone redeemed it), a roster change (an approval), closing the sheet or a lock hides it.
 */
private data class IssuedInvite(val code: String, val group: String, val revision: Long, val requests: Set<String>)

private const val POLL_MILLIS = 10_000L

/**
 * The state behind [CommunityManageSheet] while it is open: the community's card and join requests,
 * polled every 10 s while unlocked (nothing of it survives a lock), the invitation on screen and
 * the step waiting for its confirmation. [onGone]: the membership ended here (left, deleted,
 * forgotten): the conversation goes with it.
 */
@Composable
fun CommunityManageHost(service: CommunitiesService, id: String, title: String, onClose: () -> Unit, onGone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val unlocked by remember(service) { service.unlocked }.collectAsState(initial = false)
    var group by remember { mutableStateOf<CommunityCard?>(null) }
    var applications by remember { mutableStateOf<List<CommunityApplication>>(emptyList()) }
    var issued by remember { mutableStateOf<IssuedInvite?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<CommunityConfirmation?>(null) }

    suspend fun refresh() {
        val next = service.refresh().groups.firstOrNull { it.id == id }
        if (!unlocked) return
        group = next
        applications = if (next != null && next.isOwner && next.active) service.applications(id) else emptyList()
        issued?.let { shown ->
            val unchanged = next != null && next.active && next.revision == shown.revision && applications.all { it.account in shown.requests }
            if (!unchanged) issued = null
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
    suspend fun carryOut(step: CommunityConfirmation) {
        when (step) {
            is CommunityConfirmation.RemoveMember -> service.remove(step.group, step.member)
            is CommunityConfirmation.Leave -> { service.leave(step.group); onGone() }
            is CommunityConfirmation.Forget -> { service.forget(step.group); onGone() }
            is CommunityConfirmation.Delete -> { service.delete(step.group); onGone() }
            is CommunityConfirmation.RejectAll -> service.rejectAll(step.group)
        }
    }

    LaunchedEffect(service, unlocked) {
        if (!unlocked) {
            group = null; applications = emptyList(); issued = null; confirmation = null; failed = false
            return@LaunchedEffect
        }
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

    val current = group
    if (current == null) {
        Column(Modifier.fillMaxSize().testTag(CommunityManageTags.SHEET)) {
            SheetCloseHeader(title, onClose, Modifier.padding(start = 18.dp, top = 12.dp, end = 18.dp))
            if (failed) ErrorText(Modifier.padding(18.dp))
        }
        return
    }
    CommunityManageSheet(
        group = current,
        title = title,
        applications = applications,
        invite = issued?.takeIf { it.group == current.id }?.code,
        busy = busy,
        failed = failed,
        confirmation = confirmation,
        onClose = onClose,
        onApprove = { application -> perform { service.approve(current.id, application); issued = null } },
        onReject = { application -> perform { service.reject(current.id, application.account) } },
        onInvite = {
            val requests = applications.map { it.account }.toSet()
            perform { issued = IssuedInvite(service.invite(current.id), current.id, current.revision, requests) }
        },
        onRevoke = { perform { service.revokeInvites(current.id); issued = null } },
        onConfirm = { confirmation = it },
        onConfirmed = { step -> perform { try { carryOut(step) } finally { confirmation = null } } },
        onCancelConfirmation = { confirmation = null },
    )
}

@Composable
internal fun ErrorText(modifier: Modifier = Modifier) {
    U2Text(stringResource(R.string.community_error), U2TextStyles.callout, modifier.testTag(CommunityManageTags.ERROR), color = U2Theme.colors.danger)
}

/**
 * A community's members and management (proposed, full-height sheet; iOS `CommunityManageSheet`),
 * opened from the conversation header:
 * the owner reviews join requests with the identity key each asks to add, removes members and
 * makes one-use invitations; a member leaves; the owner deletes the community. Irreversible steps
 * are confirmed in the same sheet.
 */
@Composable
internal fun CommunityManageSheet(
    group: CommunityCard,
    title: String,
    applications: List<CommunityApplication>,
    invite: String?,
    busy: Boolean,
    failed: Boolean,
    confirmation: CommunityConfirmation?,
    onClose: () -> Unit,
    onApprove: (CommunityApplication) -> Unit,
    onReject: (CommunityApplication) -> Unit,
    onInvite: () -> Unit,
    onRevoke: () -> Unit,
    onConfirm: (CommunityConfirmation) -> Unit,
    onConfirmed: (CommunityConfirmation) -> Unit,
    onCancelConfirmation: () -> Unit,
) {
    val forward = confirmation != null
    val enter = U2Transitions.soft(DpOffset(if (forward) 16.dp else (-16).dp, 0.dp), blur = 0.dp, timing = U2Motion.screen)
    val exit = U2Transitions.soft(DpOffset(if (forward) (-16).dp else 16.dp, 0.dp), blur = 0.dp, timing = U2Motion.screen)
    Box(Modifier.fillMaxSize().testTag(CommunityManageTags.SHEET), contentAlignment = Alignment.TopCenter) {
        AnimatedContent(
            targetState = confirmation,
            transitionSpec = { (enter.enter togetherWith exit.exit).using(SizeTransform(clip = false)) },
            contentAlignment = Alignment.TopCenter,
            label = "communityManage",
        ) { shown ->
            if (shown != null) {
                val (confirmTitle, message, action) = shown.texts()
                DestructiveConfirmation(
                    title = stringResource(confirmTitle),
                    message = stringResource(message),
                    confirmTitle = stringResource(action),
                    onConfirm = { onConfirmed(shown) },
                    onCancel = onCancelConfirmation,
                    confirmGlyph = shown.glyph,
                    isWorking = busy,
                    tags = ConfirmationTags("communities.confirm", "communities.cancel"),
                    modifier = Modifier.padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 28.dp),
                )
            } else {
                ManagePage(group, title, applications, invite, busy, failed, onClose, onApprove, onReject, onInvite, onRevoke, onConfirm)
            }
        }
    }
}

@Composable
private fun ManagePage(
    group: CommunityCard,
    title: String,
    applications: List<CommunityApplication>,
    invite: String?,
    busy: Boolean,
    failed: Boolean,
    onClose: () -> Unit,
    onApprove: (CommunityApplication) -> Unit,
    onReject: (CommunityApplication) -> Unit,
    onInvite: () -> Unit,
    onRevoke: () -> Unit,
    onConfirm: (CommunityConfirmation) -> Unit,
) {
    val owner = group.isOwner && group.active
    Column(Modifier.fillMaxSize()) {
        SheetCloseHeader(title, onClose, Modifier.padding(start = 18.dp, top = 12.dp, end = 18.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, top = 14.dp, end = 18.dp)
                .u2Bottom(28.dp),
            verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionGap),
        ) {
            if (failed) ErrorText()
            if (owner) {
                Requests(applications, full = group.count >= 100, busy, onApprove, onReject, onRejectAll = { onConfirm(CommunityConfirmation.RejectAll(group.id)) })
            }
            Members(group, busy, onRemove = { onConfirm(CommunityConfirmation.RemoveMember(group.id, it.account)) })
            if (owner) Invitations(invite, busy, onInvite, onRevoke)
            when {
                owner -> U2DangerButton(
                    stringResource(R.string.community_delete), onClick = { onConfirm(CommunityConfirmation.Delete(group.id)) },
                    glyph = PhosphorGlyph.Trash, enabled = !busy,
                )
                group.active -> U2DangerButton(
                    stringResource(R.string.community_leave), onClick = { onConfirm(CommunityConfirmation.Leave(group.id)) },
                    glyph = PhosphorGlyph.LinkBreak, enabled = !busy,
                )
                else -> U2DangerButton(
                    stringResource(R.string.community_forget), onClick = { onConfirm(CommunityConfirmation.Forget(group.id)) },
                    glyph = PhosphorGlyph.Trash, enabled = !busy,
                )
            }
        }
    }
}

/** Join requests: who, the identity key they ask to add, and Reddet · Onayla (the 4.3 decision row). */
@Composable
private fun Requests(
    applications: List<CommunityApplication>,
    full: Boolean,
    busy: Boolean,
    onApprove: (CommunityApplication) -> Unit,
    onReject: (CommunityApplication) -> Unit,
    onRejectAll: () -> Unit,
) {
    val colors = U2Theme.colors
    Column(verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionLabelGap)) {
        SectionLabel(stringResource(R.string.community_applications))
        if (applications.isEmpty()) {
            U2Text(stringResource(R.string.community_requests_empty), U2TextStyles.callout, Modifier.padding(horizontal = 16.dp), color = colors.textSecondary)
            return@Column
        }
        // A queue filled with requests (M-14) is cleared in one step, confirmed first.
        if (applications.size > 1) {
            U2TextButton(
                stringResource(R.string.common_community_rejectall),
                onClick = onRejectAll,
                tone = U2TextButtonTone.Danger,
                enabled = !busy,
                modifier = Modifier.testTag(CommunityManageTags.REJECT_ALL),
            )
        }
        if (full) U2Text(stringResource(R.string.community_full), U2TextStyles.captionCompact, Modifier.padding(horizontal = 16.dp), color = colors.danger)
        applications.forEach { application ->
            U2Card(Modifier.testTag(CommunityManageTags.request(application.account)), fill = colors.bg) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    U2Text(communityMemberName(application.account, null), U2TextStyles.monoUsername, color = colors.text)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        U2Text(stringResource(R.string.community_identity), U2TextStyles.captionCompact, color = colors.textSecondary)
                        SelectionContainer { U2Text(application.fingerprint, U2TextStyles.monoMeta, color = colors.text) }
                    }
                    U2FlexRow(spacing = 8.dp) {
                        U2DecisionButton(
                            U2DecisionKind.Destructive,
                            stringResource(R.string.community_reject),
                            onClick = { onReject(application) },
                            modifier = Modifier.alpha(if (busy) U2Opacity.disabled else 1f),
                            enabled = !busy,
                        )
                        U2DecisionButton(
                            U2DecisionKind.Accept,
                            stringResource(R.string.community_approve),
                            onClick = { onApprove(application) },
                            modifier = Modifier.u2Flex(1.3f, border = 2.dp).alpha(if (busy || full) U2Opacity.disabled else 1f),
                            enabled = !busy && !full,
                        )
                    }
                }
            }
        }
    }
}

/** “Üyeler”: “Sen”, the username or “Üye · 1a2b3c4d”, over the role, “not in your contacts” or the short id. */
@Composable
private fun Members(group: CommunityCard, busy: Boolean, onRemove: (CommunityMemberCard) -> Unit) {
    // The administrator first, then this account, then the others by name (as on iOS).
    val members = group.members.sortedWith(
        compareByDescending<CommunityMemberCard> { it.isOwner }.thenByDescending { it.isSelf }.thenBy { it.username ?: "~${it.account}" },
    )
    Column(verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionLabelGap)) {
        SectionLabel(stringResource(R.string.community_members, group.members.size))
        GroupedCard(fill = U2Theme.colors.bg) {
            members.forEachIndexed { index, member ->
                if (index > 0) GroupedSeparator(inset = 14.dp)
                // Always a subtitle, so every row has the same height with or without “Çıkar”.
                val detail = when {
                    member.isOwner -> stringResource(R.string.community_admin)
                    member.username == null && !member.isSelf -> stringResource(R.string.community_not_contact)
                    else -> stringResource(R.string.community_member_short, CommunityFormat.shortId(member.account))
                }
                GroupedRow(tile = null, title = communityMemberName(member.account, member.username, member.isSelf), subtitle = detail) {
                    if (group.isOwner && group.active && !member.isOwner && !member.isSelf) {
                        U2TextButton(
                            stringResource(R.string.community_remove), onClick = { onRemove(member) },
                            modifier = Modifier.testTag(CommunityManageTags.remove(member.account)),
                            tone = U2TextButtonTone.Danger, height = U2Size.iconButton, fillsWidth = false, enabled = !busy,
                        )
                    }
                }
            }
        }
    }
}

/** One-use invitations: made here, shown until used (or the sheet closes), shared through the system. */
@Composable
private fun Invitations(invite: String?, busy: Boolean, onInvite: () -> Unit, onRevoke: () -> Unit) {
    val colors = U2Theme.colors
    val context = LocalContext.current
    val guard = LocalExternalActivityGuard.current
    Column(verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionLabelGap)) {
        SectionLabel(stringResource(R.string.community_invites))
        U2Card(fill = colors.bg) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                U2Text(stringResource(R.string.community_invites_hint), U2TextStyles.captionCompact, color = colors.textSecondary)
                if (invite != null) {
                    SelectionContainer {
                        U2Text(invite, U2TextStyles.monoMeta, Modifier.testTag(CommunityManageTags.INVITE), color = colors.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    U2PrimaryButton(
                        stringResource(R.string.community_share_invite),
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, invite)
                            val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { guard.launching { context.startActivity(chooser) } }
                        },
                        glyph = PhosphorGlyph.Export,
                        size = U2PrimaryButtonSize.InCard,
                    )
                } else {
                    U2PrimaryButton(
                        stringResource(R.string.community_make_invite),
                        onClick = onInvite,
                        glyph = PhosphorGlyph.ShareNetwork,
                        size = U2PrimaryButtonSize.InCard,
                        enabled = !busy,
                    )
                }
                U2TextButton(stringResource(R.string.community_revoke), onClick = onRevoke, tone = U2TextButtonTone.Danger, height = U2Size.iconButton, enabled = !busy)
            }
        }
    }
}
