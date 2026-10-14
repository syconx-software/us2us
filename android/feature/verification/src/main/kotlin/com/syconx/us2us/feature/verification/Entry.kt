package com.syconx.us2us.feature.verification

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.ContactTab
import com.syconx.us2us.domain.nav.Overlay
import com.syconx.us2us.domain.nav.QrScanMode
import com.syconx.us2us.ui.env.LocalAppModel
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.env.LocalHarnessState
import com.syconx.us2us.ui.env.LocalMessenger
import com.syconx.us2us.ui.l10n.rememberU2Strings
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.nav.LocalRouter
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.theme.U2Theme

/**
 * 6.1 Güvenlik numarası: the contact screen for [chat], on [tab] (5.1 › the person in the header).
 * Hosts the whole verification flow of the chat: 6.1, 6.3 pushed over it, and the 6.2 cover.
 */
@Composable
fun SafetyNumberScreen(chat: ChatId, tab: ContactTab, modifier: Modifier = Modifier) {
    val harness = (LocalHarnessState.current as? VerificationUiState)?.takeIf { it.chat == chat }
    if (harness?.isContact == true) {
        ContactScanHarnessHost(harness, modifier)
        return
    }
    val app = LocalAppModel.current
    val router = LocalRouter.current
    val messenger = LocalMessenger.current
    val scope = rememberCoroutineScope()
    val model = remember(chat) { VerificationModel(chat, messenger) }
    val media = remember(chat) { ContactMediaModel(chat, messenger, scope).also { if (tab != ContactTab.Verification) it.show(tab) } }

    fun isOwn(route: AppRoute) = (route is AppRoute.SafetyNumber && route.chat == chat) || route == AppRoute.Conversation(chat)

    // 6.3 “Sohbete dön” / close → 5.1: a pop when 6.1 was opened from the chat, otherwise the chat replaces this screen.
    val backToChat = {
        val path = router.path
        if (path.size >= 2 && path[path.size - 2] == AppRoute.Conversation(chat)) router.pop() else router.replaceTop(AppRoute.Conversation(chat))
    }
    // The chat went while this screen was open (Ghost erased it): back past the chat as well, not
    // onto its empty shell. Not for a lock, which may empty the live service's chats for a while.
    val summary by remember(chat) { messenger.chat(chat) }.collectAsState()
    val isGone = summary == null
    val currentOverlay by rememberUpdatedState(app.overlay)
    LaunchedEffect(isGone) {
        if (!isGone || currentOverlay != Overlay.None) return@LaunchedEffect
        val path = router.path
        val top = path.lastOrNull() ?: return@LaunchedEffect
        if (!isOwn(top)) return@LaunchedEffect
        val below = path.asReversed().dropWhile(::isOwn).firstOrNull()
        if (below != null) router.pop(to = below) else router.popToRoot()
    }
    SafetyNumberFlow(model, media, onBack = router::pop, onBackToChat = backToChat, harness = harness, modifier = modifier)
}

/**
 * 6.2 QR tarama (always dark), the content of a full-screen fade cover. [QrScanMode.AddContact]:
 * 4.1 / 4.2 “QR kodla ekle” (a match adds and verifies the contact, 6.3 follows in the cover and
 * “Sohbete dön” pushes 5.1). [QrScanMode.SafetyNumber]: the chat's safety number (a match verifies it,
 * 6.3 follows in the cover). [onDismiss] closes the cover.
 */
@Composable
fun QrScanCover(mode: QrScanMode, onDismiss: () -> Unit, modifier: Modifier = Modifier, onOpenChat: ((ChatId) -> Unit)? = null) {
    when (mode) {
        QrScanMode.AddContact -> ContactQrScanView(onClose = onDismiss, onOpenChat = onOpenChat, modifier = modifier)
        is QrScanMode.SafetyNumber -> SafetyScanCover(mode.chat, onDismiss, modifier)
    }
}

/** 6.3 Doğrulandı for [chat]; close and “Sohbete dön” call [onDone]. */
@Composable
fun VerifiedScreen(chat: ChatId, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val messenger = LocalMessenger.current
    val model = remember(chat) { VerificationModel(chat, messenger) }
    val peer by remember(chat) { messenger.peer(chat) }.collectAsState()
    LaunchedEffect(model) { model.load() }
    if (!model.hasLoaded) {
        U2Screen(modifier) {}
        return
    }
    VerifiedView(model, peer, onDone, modifier)
}

/** 6.2 for one chat's safety number on its own (not from 6.1): a match shows 6.3 in the cover. */
@Composable
private fun SafetyScanCover(chat: ChatId, onDismiss: () -> Unit, modifier: Modifier) {
    val messenger = LocalMessenger.current
    val haptics = LocalHaptics.current
    val strings = rememberU2Strings()
    val announcer = remember { Announcer() }
    val reduceMotion = LocalReduceMotion.current
    val appDark = U2Theme.isDark
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val model = remember(chat) { VerificationModel(chat, messenger) }
    val peer by remember(chat) { messenger.peer(chat) }.collectAsState()
    SideEffect {
        model.haptics = haptics
        model.announce = { id -> announcer.say(strings[id]) }
    }
    LaunchedEffect(model) {
        model.load()
        model.presentScanner()
        model.scanner.onClose = {
            model.closeScanner()
            currentOnDismiss()
        }
    }
    Box(modifier.fillMaxSize()) {
        QrScanView(model.scanner, chromeDark = appDark, modifier = Modifier.graphicsLayer { alpha = if (model.showsVerified) 0f else 1f })
        val timing = U2Motion.resolve(U2Motion.screen, reduceMotion)
        AnimatedVisibility(model.showsVerified, enter = fadeIn(timing.spec()), exit = fadeOut(timing.spec())) {
            U2Theme(dark = appDark) { VerifiedView(model, peer, onDone = onDismiss) }
        }
        AnnouncementRegion(announcer)
    }
}
