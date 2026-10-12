package com.syconx.us2us.feature.verification

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.syconx.us2us.domain.ContactCode
import com.syconx.us2us.domain.ContactCodeAddition
import com.syconx.us2us.domain.ContactCodeResolution
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.common.preparedOwnContactCode
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.env.LocalMessenger
import com.syconx.us2us.ui.env.U2Haptics
import com.syconx.us2us.ui.l10n.rememberU2Strings
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.nav.LocalRouter
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.sheet.U2FadeCover
import com.syconx.us2us.ui.theme.U2Theme
import kotlinx.coroutines.delay

/**
 * 4.1 / 4.2 › “QR kodla ekle” → 6.2 → (match) 6.3 → 5.1 (flows.md › By QR: “adds the contact
 * **and** verifies it”).
 *
 * Its hosts (4.1, 4.2) present it in their full-screen cover (fade 0.36 s). Close (✕) calls
 * [onClose]. On a match the contact is added and, when the code carries its identity key's
 * fingerprint and the key checks out, verified face to face with that key pinned; 6.3 then replaces
 * the scanner inside the cover (cross-fade 0.5 s), and “Sohbete dön” / close opens the chat
 * ([onOpenChat], default: push 5.1 on the main stack) and closes the cover. A code without a
 * fingerprint opens the chat unverified.
 */
@Composable
internal fun ContactQrScanView(
    onClose: () -> Unit,
    onOpenChat: ((ChatId) -> Unit)?,
    modifier: Modifier = Modifier,
    harness: VerificationUiState? = null,
) {
    val messenger = LocalMessenger.current
    val router = LocalRouter.current
    val haptics = LocalHaptics.current
    val strings = rememberU2Strings()
    val announcer = remember { Announcer() }
    val reduceMotion = LocalReduceMotion.current
    // The app's theme, read before the scanner goes dark (6.3 and the camera buttons' hairline follow it).
    val appDark = U2Theme.isDark
    val currentOnClose by rememberUpdatedState(onClose)
    val currentOnOpenChat by rememberUpdatedState(onOpenChat)
    val model = remember {
        ContactScanModel(
            messenger,
            onClose = { currentOnClose() },
            onOpenChat = { chat -> currentOnOpenChat?.invoke(chat) ?: router.push(AppRoute.Conversation(chat)) },
        )
    }
    SideEffect {
        model.haptics = haptics
        model.announce = { id -> announcer.say(strings[id]) }
    }
    LaunchedEffect(model) {
        model.load()
        harness?.let { applyContactHarness(it, model) }
    }
    val verification = model.verification
    Box(modifier.fillMaxSize()) {
        QrScanView(
            model.scanner,
            chromeDark = appDark,
            modifier = Modifier
                .graphicsLayer { alpha = if (verification == null) 1f else 0f }
                .then(if (verification != null) Modifier.clearAndSetSemantics {} else Modifier),
        )
        val timing = U2Motion.resolve(U2Motion.screen, reduceMotion)
        AnimatedVisibility(verification != null, enter = fadeIn(timing.spec()), exit = fadeOut(timing.spec())) {
            val shown = remember { ShownVerification() }
            if (verification != null) shown.last = verification
            shown.last?.let { current ->
                val peer by remember(current) { current.messenger.peer(current.chatId) }.collectAsState()
                // Back to the app's theme (and status bar) for 6.3.
                U2Theme(dark = appDark) { VerifiedView(current, peer, onDone = model::finish) }
            }
        }
        AnnouncementRegion(announcer)
    }
}

private class ShownVerification {
    var last: VerificationModel? = null
}

/** State of the add-by-QR flow, behind [MessengerService] only. */
@Stable
class ContactScanModel(
    val messenger: MessengerService,
    private val onClose: () -> Unit,
    private val onOpenChat: (ChatId) -> Unit,
) {
    val scanner = QrScannerModel(QrScannerModel.Purpose.AddContact)

    /** 6.3 for the contact just added and verified. */
    var verification: VerificationModel? by mutableStateOf(null)
        private set

    var haptics: U2Haptics = U2Haptics.None
        set(value) {
            field = value
            scanner.haptics = value
        }

    /** Says a string resource to TalkBack in the UI's language (the view sets it). */
    var announce: (Int) -> Unit = {}
        set(value) {
            field = value
            scanner.announce = value
        }

    /** The contact code being accepted (between evaluate and accept). */
    private var pendingCode: String? = null
    private var isFinishing = false

    init {
        scanner.isActive = true
        scanner.evaluate = { code ->
            when (messenger.resolveContactCode(code)) {
                ContactCodeResolution.NotAContactCode -> QrScannerModel.Verdict.Ignore
                ContactCodeResolution.OwnCode -> QrScannerModel.Verdict.Warn(QrScannerModel.Warning.OwnCode)
                ContactCodeResolution.Unknown -> QrScannerModel.Verdict.Warn(QrScannerModel.Warning.Unrecognized)
                ContactCodeResolution.LookupFailed -> QrScannerModel.Verdict.Warn(QrScannerModel.Warning.LookupFailed)
                ContactCodeResolution.KeyMismatch -> QrScannerModel.Verdict.Warn(QrScannerModel.Warning.ContactKeyMismatch)
                is ContactCodeResolution.Contact -> {
                    pendingCode = code
                    QrScannerModel.Verdict.Accept
                }
            }
        }
        scanner.onAccept = { didMatch() }
        scanner.onClose = { close() }
        // The design's mock is deniz's phone (“deniz’in telefonundaki …”), with the fixtures' key fingerprint.
        scanner.simulatedCodes = { simulatedCode("deniz") to ContactCode.encode("bilinmeyen") }
    }

    /** The own contact code for “Kendi kodumu göster”. */
    suspend fun load() {
        val code = messenger.preparedOwnContactCode() ?: return
        scanner.updateOwnCode(QrScannerModel.OwnCode(messenger.ownUsername.value, code, groups = null))
    }

    private fun close() {
        scanner.isActive = false
        onClose()
    }

    /**
     * Match: add the contact. 6.3 replaces the scanner (cross-fade 0.5 s) only when it was
     * verified (the code carried the key's fingerprint and that key is now pinned); a code without
     * one opens the chat unverified, and a key that does not match verifies nothing.
     */
    private suspend fun didMatch() {
        val code = pendingCode ?: return
        pendingCode = null
        scanner.isActive = false
        when (val added = messenger.addContact(code)) {
            is ContactCodeAddition.Verified -> showVerified(added.chat)
            is ContactCodeAddition.Added -> openUnverified(added.chat)
            ContactCodeAddition.Mismatch -> {
                scanner.isActive = true
                scanner.reject(code, QrScannerModel.Warning.ContactKeyMismatch)
            }
            ContactCodeAddition.Failed -> scanner.isActive = true
        }
    }

    /** Added without a verification: no 6.3, the chat opens (its first-contact prompt offers to compare the safety number). */
    private fun openUnverified(chat: ChatId) {
        announce(R.string.verification_scan_contact_added)
        if (isFinishing) return
        isFinishing = true
        onOpenChat(chat)
        onClose()
    }

    /** 6.3 for the contact just added and verified. */
    private suspend fun showVerified(chat: ChatId) {
        val verification = VerificationModel(chat, messenger)
        verification.load()
        // 6.3 says “Hat doğrulandı.” only for a stored verification.
        if (!verification.isVerified) {
            openUnverified(chat)
            return
        }
        verification.verifiedEntrance = true
        // 6.3 cross-fades in over the scanner (0.5 s); its entrance follows.
        verification.verifiedEntranceDelay = U2Motion.Duration.screen
        haptics.success()
        this.verification = verification
        announce(R.string.verification_verified_title)
    }

    /** 6.3 “Sohbete dön” / close → 5.1 (the chat opens under the fading cover). */
    fun finish() {
        val chat = verification?.chatId ?: return
        if (isFinishing) return
        isFinishing = true
        onOpenChat(chat)
        onClose()
    }

    /** Harness: 6.3 for [username] without scanning. */
    suspend fun simulateMatch(username: String) {
        pendingCode = simulatedCode(username)
        didMatch()
    }

    companion object {
        /** A contact code as the fixtures' phone mock shows it (emulator, harness). */
        fun simulatedCode(username: String): String = ContactCode.encode(username, DesignFixtures.fixtureKeyFingerprint(username))
    }
}

/**
 * Harness host of the add-by-QR scanner (`contact-*` states): an empty screen presenting the
 * scanner in the soft cover, as 4.1 / 4.2 do. Close pops back to 4.1; “Sohbete dön” replaces this
 * route with 5.1 once the cover has faded.
 */
@Composable
internal fun ContactScanHarnessHost(harness: VerificationUiState, modifier: Modifier = Modifier) {
    val router = LocalRouter.current
    var isPresented by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<ChatId?>(null) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Once the route's push has settled.
        delay(400)
        isPresented = true
    }
    LaunchedEffect(closing) {
        if (!closing) return@LaunchedEffect
        val chat = opened
        if (chat != null) {
            delay((U2Motion.Duration.state * 1000).toLong())
            router.replaceTop(AppRoute.Conversation(chat))
        } else {
            router.pop()
        }
    }
    U2Screen(modifier) {}
    U2FadeCover(isPresented = isPresented, onEscape = {
        isPresented = false
        closing = true
    }) {
        ContactQrScanView(
            onClose = {
                isPresented = false
                closing = true
            },
            onOpenChat = { opened = it },
            harness = harness,
        )
    }
}
