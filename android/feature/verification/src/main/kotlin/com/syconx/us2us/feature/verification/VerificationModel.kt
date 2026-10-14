package com.syconx.us2us.feature.verification

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.Peer
import com.syconx.us2us.domain.SafetyCodeComparison
import com.syconx.us2us.domain.SafetyNumberInfo
import com.syconx.us2us.domain.SafetyQRCodec
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.QrModules
import com.syconx.us2us.ui.env.U2Haptics
import com.syconx.us2us.ui.motion.U2Motion

/**
 * State and actions of the 06 flow for one chat: 6.1 (safety number), the 6.2 scanner cover and
 * 6.3 (verified), driven only by [MessengerService].
 *
 * Presentation (flows.md):
 * * 6.1 is the safety-number route on the main stack (the contact screen's first tab);
 * * 6.2 is a full-screen cover that fades in and out (0.36 s);
 * * 6.3 is pushed over 6.1 after “Doğrulandı olarak işaretle”, or revealed under the fading cover
 *   (0.5 s) after a match;
 * * “Sohbete dön” / close goes back to the chat.
 */
@Stable
class VerificationModel(val chatId: ChatId, val messenger: MessengerService) {
    var info: SafetyNumberInfo? by mutableStateOf(null)
        private set

    /** The text of the safety number's QR code (`U2SN:` + base32 of the scannable payload). */
    var qrPayload: String? by mutableStateOf(null)
        private set

    var numberChanged: Boolean by mutableStateOf(false)
        private set

    /**
     * A decision about the number on screen was refused because the number changed meanwhile; 6.1
     * now shows the new one and says so.
     */
    var changedWhileOpen: Boolean by mutableStateOf(false)
        private set

    var hasLoaded: Boolean by mutableStateOf(false)
        private set

    /** 6.3 pushed over 6.1. */
    var showsVerified: Boolean by mutableStateOf(false)

    /** 6.3 appears without the push (under the 6.2 cover that fades away over it). */
    var verifiedAppearsInPlace: Boolean by mutableStateOf(false)

    /** Whether 6.3 plays its entrance (seal 0.6 → 1, staggered title). */
    var verifiedEntrance: Boolean by mutableStateOf(true)

    /**
     * How long 6.3 waits before its entrance, in seconds: arriving from the scanner, until the 6.2
     * cover over it has faded away.
     */
    var verifiedEntranceDelay: Float by mutableFloatStateOf(0f)

    // 6.2
    val scanner: QrScannerModel
    var isScannerPresented: Boolean by mutableStateOf(false)

    /** The cover's fade-out, in seconds: 0.36 on close, 0.5 on a match. */
    var scannerFadeOut: Float by mutableFloatStateOf(U2Motion.Duration.state)
        private set

    /** 6.3 › “Sohbete dön”, set by the flow view (the harness drives it too). */
    var exitToChat: (() -> Unit)? = null

    // Confirmations (6.1)
    var confirmsMarkVerified: Boolean by mutableStateOf(false)
    var confirmsRemoval: Boolean by mutableStateOf(false)
    var confirmsAccept: Boolean by mutableStateOf(false)

    /** Haptics of the window (the view sets them). */
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

    init {
        val peerName = messenger.peer(chatId).value?.username ?: chatId.value
        scanner = QrScannerModel(QrScannerModel.Purpose.SafetyNumber(peerName))
        scanner.evaluate = { code ->
            // A match is stored as verified in the same step (bound to the key it matched), before 6.3 says so.
            when (messenger.verifyScannedSafetyCode(code, chatId)) {
                SafetyCodeComparison.NotASafetyCode -> QrScannerModel.Verdict.Ignore
                SafetyCodeComparison.Mismatch -> QrScannerModel.Verdict.Warn(QrScannerModel.Warning.Mismatch)
                SafetyCodeComparison.Match -> QrScannerModel.Verdict.Accept
            }
        }
        scanner.onAccept = { didMatch() }
        scanner.onClose = { closeScanner() }
        scanner.simulatedCodes = {
            val match = info?.let { SafetyQRCodec.encode(it.scannablePayload) }
            match to SafetyQRCodec.encode(Bytes.utf8("not-this-chat's-safety-number"))
        }
    }

    val peer: Peer? get() = messenger.peer(chatId).value
    val peerName: String get() = peer?.username ?: chatId.value
    val ownUsername: String get() = messenger.ownUsername.value
    val isVerified: Boolean get() = info?.isVerified ?: false

    /**
     * What 6.1 shows besides the digits: the peer's key, a pending change and the verification.
     * When any of them changes (a new key arrives while 6.1 is open), 6.1 reloads.
     */
    data class IdentityState(val fingerprint: String?, val changed: Boolean, val verified: Boolean) {
        companion object {
            fun of(peer: Peer?) = IdentityState(peer?.identityFingerprint, peer?.safetyNumberChanged ?: false, peer?.isVerified ?: false)
        }
    }

    // MARK: Loading

    /** Reads the safety number (the views animate what changes: the seal, the pill, the notes). */
    suspend fun load() {
        val info = messenger.safetyNumber(chatId)
        if (info != null && (info.scannablePayload != this.info?.scannablePayload || qrPayload == null)) {
            val payload = SafetyQRCodec.encode(info.scannablePayload)
            // The modules are made once, before the card shows them.
            QrModules.cached(payload)
            qrPayload = payload
        }
        // 6.3 says “Hat doğrulandı.” only while the chat really is verified: if the key changed
        // under it, 6.1 comes back with the new number.
        val hidesVerified = showsVerified && !(info?.isVerified ?: false)
        this.info = info
        numberChanged = info?.hasUnverifiedChange ?: false
        if (hidesVerified) showsVerified = false
        // “Kendi kodumu göster”: the same safety number; both sides see one number.
        scanner.updateOwnCode(QrScannerModel.OwnCode(ownUsername, qrPayload, info?.groups))
        hasLoaded = true
    }

    // MARK: 6.1 actions

    /** “Doğrulandı olarak işaretle” → confirmation. */
    fun requestMarkVerified() {
        confirmsMarkVerified = true
    }

    /**
     * Confirmed: mark manually (bound to the number on screen, the one the user compared) and push
     * 6.3. If the number changed meanwhile nothing is marked: 6.1 shows the new number and says so.
     */
    suspend fun markVerifiedManually() {
        val compared = info?.scannablePayload ?: return
        if (!messenger.markVerified(chatId, compared)) {
            numberChangedMeanwhile()
            return
        }
        changedWhileOpen = false
        load()
        if (!isVerified) return
        verifiedEntrance = true
        verifiedEntranceDelay = 0f
        verifiedAppearsInPlace = false
        haptics.success()
        showsVerified = true
        announce(R.string.verification_verified_title)
    }

    /** “Doğrulamayı kaldır” → confirmation. */
    fun requestRemoval() {
        confirmsRemoval = true
    }

    suspend fun removeVerification() {
        messenger.clearVerification(chatId)
        // The seal and the pill leave softly.
        load()
    }

    /** After a change › “Doğrulamadan devam et” → confirmation. */
    fun requestAccept() {
        confirmsAccept = true
    }

    /**
     * Confirmed: the changed number is accepted without verifying it (sending resumes, no seal),
     * bound to the number on screen; then back to the chat. Refused like marking if the number
     * changed meanwhile.
     */
    suspend fun acceptChangeWithoutVerifying() {
        val shown = info?.scannablePayload ?: return
        if (!messenger.acceptSafetyNumberChange(chatId, shown)) {
            numberChangedMeanwhile()
            return
        }
        changedWhileOpen = false
        load()
        announce(R.string.verification_safety_accepted_announcement)
        exitToChat?.invoke()
    }

    /** A decision about the number on screen was refused: the key changed since it was shown. */
    private suspend fun numberChangedMeanwhile() {
        haptics.error()
        load()
        changedWhileOpen = true
        announce(R.string.verification_safety_changedwhileopen_message)
    }

    // MARK: 6.2

    fun presentScanner() {
        scanner.reset()
        scannerFadeOut = U2Motion.Duration.state
        scanner.isActive = true
        isScannerPresented = true
    }

    /** Close (✕): fade the cover out (0.36 s) back to 6.1. */
    fun closeScanner() {
        scannerFadeOut = U2Motion.Duration.state
        scanner.isActive = false
        scanner.setTorch(false)
        isScannerPresented = false
    }

    /**
     * 6.2 match → 6.3: verified face to face (stored by the match itself, bound to the key it
     * matched); 6.3 is put under the cover, which then fades away over 0.5 s (“replace cover
     * content, cross-fade”).
     */
    private suspend fun didMatch() {
        scanner.isActive = false
        changedWhileOpen = false
        load()
        if (!isVerified) {
            // The key changed again right after the match: back to 6.1.
            closeScanner()
            return
        }
        haptics.success()
        scannerFadeOut = U2Motion.Duration.screen
        verifiedEntrance = true
        verifiedEntranceDelay = scannerFadeOut
        verifiedAppearsInPlace = true
        showsVerified = true
        isScannerPresented = false
        announce(R.string.verification_verified_title)
    }
}
