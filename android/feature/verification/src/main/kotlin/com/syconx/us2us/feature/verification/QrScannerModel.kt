package com.syconx.us2us.feature.verification

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.env.U2Haptics

/**
 * The 6.2 scanner's state, shared by its two entries (flows.md):
 * * [Purpose.SafetyNumber]: 6.1 “QR kodu okut” compares the scanned code with the chat's safety
 *   number ([VerificationModel]);
 * * [Purpose.AddContact]: 4.1 / 4.2 “QR kodla ekle” adds the contact and verifies it at once
 *   ([ContactScanModel]).
 *
 * The owner decides what a scanned text means ([evaluate]) and what a match does ([onAccept]); this
 * model only runs the screen: stage, warning card, torch, and when the camera may run. Scanned texts
 * are never stored or logged; only the last *rejected* one is kept in memory, to warn once.
 */
@Stable
class QrScannerModel(
    val purpose: Purpose,
    /** Milliseconds on a monotonic clock (the 2.5 s a rejected code stays quiet). */
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    sealed interface Purpose {
        /** Scanning [peer]'s safety-number code (hint “deniz’in telefonundaki …”). */
        data class SafetyNumber(val peer: String) : Purpose

        /** Scanning someone's contact code (hint without a name). */
        data object AddContact : Purpose
    }

    enum class Stage {
        /** Camera and viewfinder (the design). */
        Scanning,

        /** “Kendi kodumu göster”: the user's own code; the camera is off. */
        MyCode,
    }

    /** The floating card under the top bar (5.6 style, `danger`). */
    enum class Warning {
        /** “Numaralar uyuşmuyor” (6.2 note). */
        Mismatch,

        /** A us2us contact code nobody could be found for. */
        Unrecognized,

        /** A contact code whose key is not the one the server serves (or this phone holds): nobody is added. */
        ContactKeyMismatch,

        /** The name in a contact code could not be looked up (no connection): not “nobody found”. */
        LookupFailed,

        /** The user's own contact code. */
        OwnCode,
    }

    /** What a scanned text means to the owner. */
    sealed interface Verdict {
        /** Not a us2us code of the expected kind (a URL, a Wi-Fi code …): ignored. */
        data object Ignore : Verdict

        data class Warn(val warning: Warning) : Verdict

        data object Accept : Verdict
    }

    /**
     * What “Kendi kodumu göster” shows: the user's avatar and name, the QR ([payload], the text it
     * encodes) and, for a safety number, the 12 groups of digits.
     */
    @Immutable
    data class OwnCode(val username: String, val payload: String?, val groups: List<String>?)

    var stage: Stage by mutableStateOf(Stage.Scanning)
        private set
    var warning: Warning? by mutableStateOf(null)
        private set
    var torchOn: Boolean by mutableStateOf(false)
        private set

    /** Whether the camera may run (presented and not yet matched). */
    var isActive: Boolean by mutableStateOf(false)

    var ownCode: OwnCode? by mutableStateOf(null)
        private set

    /**
     * Bumped with every warning shown, so the card drops in again for a new warning of the same
     * kind (iOS gives the card the warning as its identity).
     */
    var warningGeneration: Int by mutableStateOf(0)
        private set

    /** Interprets a scanned text (compared on the owner's side, in constant time). */
    var evaluate: suspend (String) -> Verdict = { Verdict.Ignore }

    /** A code was accepted: the owner moves on (6.3). */
    var onAccept: suspend () -> Unit = {}

    /** Close (✕), back. */
    var onClose: () -> Unit = {}

    /** The emulator only: the codes the design's phone mock stands for (match, mismatch). */
    var simulatedCodes: () -> Pair<String?, String> = { null to "" }

    /** Haptics of the window (the view sets them). */
    var haptics: U2Haptics = U2Haptics.None

    /** Says a string resource to TalkBack in the UI's language (the view sets it). */
    var announce: (Int) -> Unit = {}

    private var isEvaluating = false
    private var lastRejected: Pair<String, Long>? = null

    // MARK: Lifecycle

    /** Back to the scanning state (each time the scanner opens). */
    fun reset() {
        stage = Stage.Scanning
        warning = null
        torchOn = false
        lastRejected = null
        isEvaluating = false
    }

    fun close() {
        torchOn = false
        onClose()
    }

    fun updateOwnCode(code: OwnCode?) {
        ownCode = code
    }

    // MARK: Actions

    fun toggleTorch() {
        torchOn = !torchOn
    }

    fun setTorch(on: Boolean) {
        torchOn = on
    }

    /** “Kendi kodumu göster” (cross-fade; the camera and the torch stop). */
    fun showOwnCode() {
        torchOn = false
        warning = null
        stage = Stage.MyCode
    }

    /** “Kodu okut” on the own-code view. */
    fun showScanning() {
        lastRejected = null
        stage = Stage.Scanning
    }

    fun dismissWarning() {
        warning = null
    }

    /** A QR code was read by the camera (or the emulator's mock). */
    suspend fun handle(code: String) {
        if (!isActive || stage != Stage.Scanning || isEvaluating) return
        val last = lastRejected
        val now = elapsedMillis()
        if (last != null && last.first == code && (warning != null || now - last.second < QUIET_MILLIS)) {
            // The same rejected code is still in front of the camera: warn once (until it has been
            // out of sight for 2.5 s).
            lastRejected = code to now
            return
        }
        isEvaluating = true
        try {
            when (val verdict = evaluate(code)) {
                Verdict.Ignore -> Unit
                is Verdict.Warn -> reject(code, verdict.warning)
                Verdict.Accept -> {
                    lastRejected = null
                    torchOn = false
                    warning = null
                    onAccept()
                }
            }
        } finally {
            isEvaluating = false
        }
    }

    /**
     * Shows the warning card for [code] and keeps it from being evaluated again while it stays in
     * front of the camera. The owner calls it too, for a code it accepted but then could not use.
     */
    fun reject(code: String, kind: Warning) {
        lastRejected = code to elapsedMillis()
        haptics.error()
        warning = kind
        warningGeneration++
        announce(texts(kind).first)
    }

    companion object {
        /** How long a rejected code stays quiet after it was last seen. */
        const val QUIET_MILLIS = 2_500L

        /** Title and message of a warning card (string resources). */
        fun texts(warning: Warning): Pair<Int, Int> = when (warning) {
            Warning.Mismatch -> R.string.verification_scan_mismatch_title to R.string.verification_scan_mismatch_message
            Warning.Unrecognized -> R.string.verification_scan_unrecognized_title to R.string.verification_scan_unrecognized_message
            Warning.ContactKeyMismatch -> R.string.verification_scan_mismatch_title to R.string.verification_scan_contact_mismatch_message
            Warning.LookupFailed -> R.string.verification_scan_lookupfailed_title to R.string.verification_scan_lookupfailed_message
            Warning.OwnCode -> R.string.verification_scan_owncode_title to R.string.verification_scan_owncode_message
        }
    }
}
