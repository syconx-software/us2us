package com.syconx.us2us.feature.verification

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.Peer
import com.syconx.us2us.domain.SafetyNumberInfo
import com.syconx.us2us.feature.report.rememberReportPresenter
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.ConnectionLine
import com.syconx.us2us.ui.components.DestructiveConfirmation
import com.syconx.us2us.ui.components.NoteCard
import com.syconx.us2us.ui.components.QrCodeCard
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.l10n.rememberU2Strings
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.sheet.U2FadeCover
import com.syconx.us2us.ui.sheet.U2Sheet
import com.syconx.us2us.ui.sheet.U2SheetStyle
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlinx.coroutines.launch

/**
 * 6.1 (inside the contact screen's tabs) with 6.3 pushed over it, the 6.2 cover and the
 * confirmations. All state lives in [VerificationModel] (and [ContactMediaModel] for the other
 * tabs); all data comes through the messenger.
 */
@Composable
internal fun SafetyNumberFlow(
    model: VerificationModel,
    media: ContactMediaModel,
    onBack: () -> Unit,
    onBackToChat: () -> Unit,
    harness: VerificationUiState?,
    modifier: Modifier = Modifier,
) {
    val appDark = U2Theme.isDark
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    val strings = rememberU2Strings()
    val announcer = remember { Announcer() }
    val reduceMotion = LocalReduceMotion.current
    val peer by remember(model) { model.messenger.peer(model.chatId) }.collectAsState()
    val identity = VerificationModel.IdentityState.of(peer)
    val actions = remember(model) { ContactActions(model.chatId, model.messenger) }
    val reports = rememberReportPresenter()

    SideEffect {
        model.haptics = haptics
        model.announce = { id -> announcer.say(strings[id]) }
        media.haptics = haptics
        actions.haptics = haptics
        actions.strings = strings
        actions.announce = announcer::say
    }
    LaunchedEffect(model) {
        model.exitToChat = onBackToChat
        model.load()
        harness?.let { applyVerificationHarness(it, model) }
    }
    LaunchedEffect(actions) { harness?.let { applyContactActionsHarness(it, actions, reports, { peer }, strings) } }
    // A new key (or a decision made elsewhere) while 6.1 is open: the number on screen is always the current one.
    LaunchedEffect(identity) { if (model.hasLoaded) model.load() }

    // 6.3 is “pushed”: 6.1 fades out drifting 16 dp to the leading side (at once under a fading 6.2 cover).
    val pushTiming = U2Motion.resolve(U2Motion.screen, reduceMotion)
    val covered by animateFloatAsState(
        if (model.showsVerified) 1f else 0f,
        if (model.verifiedAppearsInPlace && model.showsVerified) snap() else pushTiming.spec(),
        label = "contactCovered",
    )
    Box(modifier.fillMaxSize()) {
        ContactScreen(
            verification = model,
            peer = peer,
            media = media,
            actions = actions,
            reports = reports,
            onBack = onBack,
            modifier = Modifier
                .graphicsLayer {
                    alpha = 1f - covered
                    translationX = if (reduceMotion) 0f else -16.dp.toPx() * covered
                }
                .then(if (model.showsVerified) Modifier.clearAndSetSemantics {} else Modifier),
        )
        val push = U2Transitions.push()
        AnimatedVisibility(
            visible = model.showsVerified,
            enter = if (model.verifiedAppearsInPlace) EnterTransition.None else push.enter,
            exit = push.exit,
        ) {
            VerifiedView(model, peer, onDone = onBackToChat)
        }
        AnnouncementRegion(announcer)
    }

    U2FadeCover(isPresented = model.isScannerPresented, onEscape = model::closeScanner, fadeOut = { model.scannerFadeOut }) {
        QrScanView(model.scanner, chromeDark = appDark, forcesDenied = harness?.state == VerificationHarness.DENIED)
    }

    U2Sheet(isPresented = model.confirmsMarkVerified, onDismissRequest = model::cancelMarkVerified, style = U2SheetStyle.Floating) {
        VerificationConfirmation(
            title = stringResource(R.string.verification_safety_confirm_title),
            message = stringResource(R.string.verification_safety_confirm_message, model.peerName),
            confirmTitle = stringResource(R.string.verification_safety_confirm_mark),
            onConfirm = {
                model.confirmsMarkVerified = false
                scope.launch { model.markVerifiedManually() }
            },
            onCancel = model::cancelMarkVerified,
            modifier = ConfirmationPadding,
        )
    }
    U2Sheet(isPresented = model.confirmsRemoval, onDismissRequest = { model.confirmsRemoval = false }, style = U2SheetStyle.Floating) {
        DestructiveConfirmation(
            title = stringResource(R.string.verification_safety_remove_title),
            message = stringResource(R.string.verification_safety_remove_message),
            confirmTitle = stringResource(R.string.verification_safety_remove_confirm),
            confirmGlyph = null,
            onConfirm = {
                model.confirmsRemoval = false
                scope.launch { model.removeVerification() }
            },
            onCancel = { model.confirmsRemoval = false },
            modifier = ConfirmationPadding,
        )
    }
    U2Sheet(isPresented = model.confirmsAccept, onDismissRequest = model::cancelAccept, style = U2SheetStyle.Floating) {
        VerificationConfirmation(
            title = stringResource(R.string.verification_safety_accept_title),
            message = stringResource(R.string.verification_safety_accept_message),
            confirmTitle = stringResource(R.string.verification_safety_accept_confirm),
            onConfirm = {
                model.confirmsAccept = false
                scope.launch { model.acceptChangeWithoutVerifying() }
            },
            onCancel = model::cancelAccept,
            modifier = ConfirmationPadding,
        )
    }
}

/**
 * 6.1 · Güvenlik numarası, the contact screen's Doğrulama tab: the sen ↔ peer line (seal once
 * verified), the QR card of the scannable fingerprint, the 12 × 5 digits; “QR kodu okut” (C1) and
 * “Doğrulandı olarak işaretle” (C3), “Doğrulamayı kaldır” once verified. A changed number shows a
 * danger note above the line, with “Doğrulamadan devam et” under the buttons; so does a blocked
 * person, with “Engeli kaldır” ([ContactBlockedNote]).
 */
@Composable
internal fun SafetyNumberTab(model: VerificationModel, peer: Peer?, modifier: Modifier = Modifier, onUnblock: () -> Unit = {}) {
    val info = model.info
    FitOrScroll(modifier) {
        val drop = U2Transitions.drop()
        AnimatedVisibility(peer?.isBlocked == true, enter = drop.enter, exit = drop.exit) {
            ContactBlockedNote(
                name = model.peerName,
                onUnblock = onUnblock,
                modifier = Modifier
                    .padding(horizontal = U2Spacing.screenGutterS)
                    .padding(top = 16.dp)
                    .testTag(VerificationTags.BLOCKED_NOTE),
            )
        }
        AnimatedVisibility(model.numberChanged || model.changedWhileOpen, enter = drop.enter, exit = drop.exit) {
            NoteCard(
                title = stringResource(R.string.verification_safety_changed_title),
                message = if (model.changedWhileOpen) {
                    stringResource(R.string.verification_safety_changedwhileopen_message)
                } else {
                    stringResource(R.string.verification_safety_changed_message, model.peerName)
                },
                glyph = PhosphorGlyph.WarningCircle,
                modifier = Modifier
                    .padding(horizontal = U2Spacing.screenGutterS)
                    .padding(top = 16.dp)
                    .testTag(VerificationTags.CHANGED),
            )
        }
        if (peer != null) {
            ConnectionLine(
                ownUsername = model.ownUsername,
                ownLabel = stringResource(R.string.verification_safety_you),
                peer = peer,
                showsSeal = model.isVerified,
                animatesSeal = true,
                modifier = Modifier
                    .padding(top = 20.dp)
                    .testTag(VerificationTags.LINE),
            )
        }
        QrCodeCard(
            payload = model.qrPayload,
            label = stringResource(R.string.verification_safety_a11y_qr),
            modifier = Modifier
                .padding(top = 22.dp)
                .testTag(VerificationTags.QR),
        )
        val groups = info?.groups
        if (groups != null) {
            SafetyDigitsGrid(groups, Modifier.padding(top = 24.dp).testTag(VerificationTags.DIGITS))
        } else {
            Spacer(Modifier.padding(top = 24.dp).height(23.25.dp * 3 + 20.dp))
        }
        val rise = U2Transitions.rise()
        AnimatedVisibility(info?.isVerified == true, enter = rise.enter, exit = rise.exit) {
            info?.let { VerifiedSincePill(it, Modifier.padding(top = 24.dp)) }
        }
        // Spacer(minLength: 24): at least 24, and whatever the screen has left.
        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.weight(1f))
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = U2Spacing.screenGutter)
                .u2Bottom(40.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            U2PrimaryButton(
                title = stringResource(R.string.verification_safety_scan),
                onClick = model::presentScanner,
                glyph = PhosphorGlyph.Scan,
                enabled = info != null,
                modifier = Modifier.testTag(VerificationTags.SCAN),
            )
            U2TextButton(
                title = stringResource(if (model.isVerified) R.string.verification_safety_removeverification else R.string.verification_safety_markverified),
                onClick = { if (model.isVerified) model.requestRemoval() else model.requestMarkVerified() },
                height = U2Size.textButtonS,
                enabled = info != null,
                modifier = Modifier.testTag(VerificationTags.MARK_VERIFIED),
            )
            // After a change: go on without verifying (sending resumes, the chat keeps no seal).
            val fade = U2Transitions.fade()
            AnimatedVisibility(model.numberChanged, enter = fade.enter, exit = fade.exit) {
                U2TextButton(
                    title = stringResource(R.string.verification_safety_acceptchange),
                    onClick = model::requestAccept,
                    height = U2Size.textButtonS,
                    enabled = info != null,
                    modifier = Modifier.testTag(VerificationTags.ACCEPT_CHANGE),
                )
            }
        }
    }
}

/**
 * 6.1 verified: “Doğrulandı · 8 Ekim 2026 · 18:52”, the 6.3 pill's look (`text·6%` r14, pad 8 14,
 * gap 8, `monoMeta` `text·68%`) with the seal (`ph:seal-check` fill 14 `accent`).
 */
@Composable
private fun VerifiedSincePill(info: SafetyNumberInfo, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val strings = rememberU2Strings()
    val text = remember(info, strings) { VerificationText.verifiedSince(info, strings) }
    Row(
        modifier
            .semantics(mergeDescendants = true) {}
            .background(colors.fillSubtle, RoundedCornerShape(U2Radius.systemPill))
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag(VerificationTags.VERIFIED_SINCE),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhosphorIcon(PhosphorGlyph.SealCheck, size = 14.dp, weight = PhosphorWeight.Fill, tint = colors.accent)
        U2Text(text, U2TextStyles.monoMeta, color = colors.textSecondary)
    }
}
