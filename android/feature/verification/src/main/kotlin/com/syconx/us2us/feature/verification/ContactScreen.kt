package com.syconx.us2us.feature.verification

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.syconx.us2us.domain.Peer
import com.syconx.us2us.domain.nav.ContactTab
import com.syconx.us2us.feature.report.BlockConfirmationSheet
import com.syconx.us2us.feature.report.BlockConfirmationTags
import com.syconx.us2us.feature.report.ReportPresenter
import com.syconx.us2us.feature.report.ReportSheet
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.FloatingMenuItem
import com.syconx.us2us.ui.components.FloatingMenuTone
import com.syconx.us2us.ui.components.SegmentedChips
import com.syconx.us2us.ui.components.U2AnchoredMenu
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.env.ExternalActivityGuard
import com.syconx.us2us.ui.env.LocalExternalActivityGuard
import com.syconx.us2us.ui.env.LocalFeatureScreens
import com.syconx.us2us.ui.env.LocalMessenger
import com.syconx.us2us.ui.env.u2Top
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.l10n.U2Strings
import com.syconx.us2us.ui.l10n.rememberU2Strings
import com.syconx.us2us.ui.media.LocalConversationMediaActivity
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.sheet.U2FadeCover
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlinx.coroutines.launch

/**
 * The contact screen (5.1 › the person in the header), in 6.1's language: the C7 header (back · the
 * name in `monoUsernameL`, with the seal once verified · “…”) over C22 tabs (Doğrulama ·
 * Fotoğraflar · Belgeler, 16 below the header, 20 dp gutters) and the tab's content: 6.1 as drawn
 * ([SafetyNumberTab]), the grid of the chat's photos and videos, the list of its files. Tabs
 * cross-fade (0.36 s).
 *
 * “…” opens the 4.4 menu's look: “Numarayı paylaş” on Doğrulama ‖ “Bildir” · “Engelle” (after the
 * request block's confirmation), “Engeli kaldır” while the person is blocked ([ContactActions]). A
 * blocked person gets `ph:prohibit` after the name.
 *
 * Over it: the photo viewer and the player swiping through the grid ([MediaGalleryViewer], the 5.6
 * fade cover) and a file's preview. Everything decrypted closes with the app (the lock follows), or
 * when its message goes.
 */
@Composable
internal fun ContactScreen(
    verification: VerificationModel,
    peer: Peer?,
    media: ContactMediaModel,
    actions: ContactActions,
    reports: ReportPresenter,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val guard = LocalExternalActivityGuard.current
    val strings = rememberU2Strings()
    val screens = LocalFeatureScreens.current
    val base = LocalMessenger.current
    val scope = rememberCoroutineScope()
    val viewers = remember(base, media) { ContactMediaMessenger(base, media.chatId, media.loaded) }
    val isBlocked = peer?.isBlocked == true
    // Share (Doğrulama only, once the number is known) ‖ Bildir · Engelle, or “Engeli kaldır” while blocked.
    val menuItems = buildList {
        if (media.tab == ContactTab.Verification && verification.info != null) {
            add(
                FloatingMenuItem(id = "share", title = stringResource(R.string.contact_menu_share), glyph = PhosphorGlyph.ShareNetwork) {
                    shareSafetyNumber(context, guard, verification, strings)
                },
            )
        }
        add(
            FloatingMenuItem(id = "report", title = stringResource(R.string.common_report_action), glyph = PhosphorGlyph.Warning, startsGroup = isNotEmpty()) {
                actions.openReport(reports, peer)
            },
        )
        if (isBlocked) {
            add(
                FloatingMenuItem(id = "unblock", title = stringResource(R.string.common_block_unblock), glyph = PhosphorGlyph.Prohibit) {
                    scope.launch { actions.unblock(verification.peerName) }
                },
            )
        } else {
            add(
                FloatingMenuItem(
                    id = "block",
                    title = stringResource(R.string.common_request_block),
                    glyph = PhosphorGlyph.Prohibit,
                    tone = FloatingMenuTone.Destructive,
                ) { actions.requestBlock() },
            )
        }
    }
    // The block is on screen: its haptic and announcement, once.
    LaunchedEffect(isBlocked) { if (isBlocked) actions.blockDidShow() }
    // The chat is not open behind this screen: its changes come as a revision, and the loaded lists are read again.
    val revision by remember(media) { media.messenger.mediaRevision(media.chatId) }.collectAsState()
    LaunchedEffect(revision) { if (revision != 0) media.refreshLoaded() }
    // Our own system activities (a picker, the share sheet) keep what is open; a real background does not.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (!guard.isActive) media.closeMedia() }
    val live by remember(media) { media.messenger.messages(media.chatId) }.collectAsState()
    LaunchedEffect(live, media.gallery, media.filePreview) { if (media.presentedMediaIsGone) media.closeMedia() }

    CompositionLocalProvider(LocalConversationMediaActivity provides media.activity) {
        U2Screen(modifier) {
            Column(Modifier.fillMaxSize()) {
                ContactHeader(
                    name = verification.peerName,
                    isVerified = peer?.isVerified == true,
                    isBlocked = isBlocked,
                    onBack = onBack,
                    onMore = { actions.showsMenu = !actions.showsMenu },
                )
                SegmentedChips(
                    options = ContactTab.entries,
                    selection = media.tab,
                    onSelect = media::show,
                    identifierPrefix = "contact.tab",
                    modifier = Modifier
                        .padding(horizontal = U2Spacing.screenGutter)
                        .padding(top = 16.dp)
                        .testTag(VerificationTags.TABS),
                ) { tab ->
                    stringResource(
                        when (tab) {
                            ContactTab.Verification -> R.string.contact_tab_verification
                            ContactTab.Photos -> R.string.contact_tab_photos
                            ContactTab.Documents -> R.string.contact_tab_documents
                        },
                    )
                }
                Crossfade(media.tab, Modifier.weight(1f).fillMaxWidth(), animationSpec = stateFade(), label = "contactTab") { tab ->
                    when (tab) {
                        ContactTab.Verification -> SafetyNumberTab(
                            verification,
                            peer,
                            onUnblock = { scope.launch { actions.unblock(verification.peerName) } },
                        )
                        ContactTab.Photos -> ContactPhotosGrid(media)
                        ContactTab.Documents -> ContactDocumentsList(media)
                    }
                }
            }
            U2AnchoredMenu(isPresented = actions.showsMenu, onDismiss = { actions.showsMenu = false }, items = menuItems)
        }
        BlockConfirmationSheet(
            item = verification.peerName.takeIf { actions.confirmsBlock },
            personName = { it },
            onBlock = { name -> scope.launch { actions.block(name) } },
            onReport = { actions.openReport(reports, peer, afterConfirmation = true) },
            onDismiss = { actions.confirmsBlock = false },
            tags = BlockConfirmationTags(VerificationTags.BLOCK_CONFIRM, VerificationTags.BLOCK_REPORT, VerificationTags.BLOCK_CANCEL),
        )
        ReportSheet(reports)
        U2FadeCover(item = media.gallery, onEscape = media::closeGallery) { gallery ->
            MediaGalleryViewer(media, gallery, onClose = media::closeGallery)
        }
        U2FadeCover(item = media.filePreview, onEscape = media::closeFilePreview) { id ->
            CompositionLocalProvider(LocalMessenger provides viewers) {
                screens.FilePreview(media.chatId, id, media::closeFilePreview, Modifier.fillMaxSize())
            }
        }
    }
}

/** Back · the name (Mono, the seal once verified, `ph:prohibit` once blocked) · “…”, 44 high at design y 56, pad 0 16. */
@Composable
private fun ContactHeader(name: String, isVerified: Boolean, isBlocked: Boolean, onBack: () -> Unit, onMore: () -> Unit) {
    val colors = U2Theme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .u2Top(U2Spacing.headerTopButtons)
            .padding(horizontal = U2Spacing.screenGutterS)
            .height(U2Size.iconButton),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .widthIn(max = 250.dp)
                .semantics(mergeDescendants = true) { heading() }
                .testTag(VerificationTags.TITLE),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            U2Text(
                name,
                U2TextStyles.monoUsernameL,
                color = colors.text,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            val fade = U2Transitions.fade()
            AnimatedVisibility(isVerified, enter = fade.enter, exit = fade.exit) {
                PhosphorIcon(
                    PhosphorGlyph.SealCheck,
                    size = 16.dp,
                    weight = PhosphorWeight.Fill,
                    tint = colors.accent,
                    contentDescription = stringResource(R.string.common_a11y_verified),
                )
            }
            AnimatedVisibility(isBlocked, enter = fade.enter, exit = fade.exit) {
                PhosphorIcon(
                    PhosphorGlyph.Prohibit,
                    size = 16.dp,
                    tint = colors.danger,
                    contentDescription = stringResource(R.string.common_block_blocked),
                    modifier = Modifier.testTag(VerificationTags.BLOCKED_MARK),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            U2GlassIconButton(
                PhosphorGlyph.CaretLeft,
                contentDescription = stringResource(R.string.common_a11y_back),
                onClick = onBack,
                modifier = Modifier.testTag(VerificationTags.BACK),
            )
            Spacer(Modifier.weight(1f))
            U2GlassIconButton(
                PhosphorGlyph.DotsThree,
                contentDescription = stringResource(R.string.contact_a11y_more),
                onClick = onMore,
                modifier = Modifier.testTag(VerificationTags.MORE),
            )
        }
    }
}

/** 6.1 › share: the digits as text, through the system's share sheet. */
internal fun shareSafetyNumber(context: Context, guard: ExternalActivityGuard, model: VerificationModel, strings: U2Strings) {
    val groups = model.info?.groups ?: return
    val text = VerificationText.shareText(model.ownUsername, model.peerName, groups, strings)
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { guard.launching { context.startActivity(chooser) } }
}
