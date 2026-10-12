package com.syconx.us2us.feature.verification

import com.syconx.us2us.domain.Peer
import com.syconx.us2us.domain.ReportReason
import com.syconx.us2us.domain.VerificationMethod
import com.syconx.us2us.domain.harness.FeatureHarness
import com.syconx.us2us.domain.harness.FixtureControls
import com.syconx.us2us.domain.harness.HarnessPlan
import com.syconx.us2us.domain.harness.HarnessUiState
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.ContactTab
import com.syconx.us2us.domain.nav.LaunchRoute
import com.syconx.us2us.domain.nav.StartScreen
import com.syconx.us2us.feature.report.AddressOnlyReportMailer
import com.syconx.us2us.feature.report.ReportPresenter
import com.syconx.us2us.ui.l10n.U2Strings
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

/**
 * What the 06 harness asks of the screens for one launch (`safety:<chat>/<state>`). The state is
 * applied once, the first time its screen appears; a later screen of the same chat (after the flow
 * moved on) starts plain.
 */
class VerificationUiState(override val state: String, val chat: ChatId) : HarnessUiState {
    private val applied = AtomicBoolean(false)

    private val appliedActions = AtomicBoolean(false)

    /** `true` the first time only: the screen that gets it applies the state. */
    fun claim(): Boolean = applied.compareAndSet(false, true)

    /** The same for the contact screen's “…” states (the menu, the block, the report). */
    fun claimActions(): Boolean = appliedActions.compareAndSet(false, true)

    /** The add-by-QR states: the scanner over an empty screen instead of the contact screen. */
    val isContact: Boolean get() = state.startsWith("contact-") || state == VerificationHarness.FLOW_CONTACT

    override fun toString(): String = "VerificationUiState($state, ${chat.value})"
}

/**
 * Debug harness for 06 · Güvenlik numarası and the contact screen (`safety:<chat>[/<state>]`):
 *
 * | state | screen |
 * |---|---|
 * | `safety` (or none) | 6.1 as drawn (unverified) |
 * | `photos` · `documents` | the contact screen's Fotoğraflar · Belgeler tab |
 * | `menu` | the contact screen's “…” menu (share · Bildir · Engelle) |
 * | `block` | the block confirmation (“deniz engellensin mi?” · Engelle · Bildir · Vazgeç) |
 * | `blocked` | deniz blocked: the mark after the name, the note with “Engeli kaldır”, the menu offering “Engeli kaldır” |
 * | `report` · `report-address` | the report sheet with “Taciz veya zorbalık” chosen · after “E-postayla gönder” without e-mail: the address to copy, deniz blocked |
 * | `safety-verified` | 6.1 already verified: seal on the line, pill, “Doğrulamayı kaldır” |
 * | `changed` | 6.1 with “Güvenlik numarası değişti” and “Doğrulamadan devam et” |
 * | `confirm-accept` | `changed` with the “Doğrulamadan devam et” confirmation |
 * | `confirm` | 6.1 with the “Doğrulandı olarak işaretle” confirmation |
 * | `confirm-remove` | 6.1 verified, with the removal confirmation |
 * | `scan` | 6.2 scanning (the design's placeholder without a camera) |
 * | `mismatch` | 6.2 after a wrong code: “Numaralar uyuşmuyor” |
 * | `mycode` | 6.2 “Kendi kodumu göster” |
 * | `denied` | 6.2 without camera access |
 * | `torch` | 6.2 with the torch on |
 * | `verified` · `verified-manual` | 6.3 after a QR match (“yüz yüze”) · after marking (“elle işaretlendi”) |
 * | `contact-scan` | 6.2 from 4.1 / 4.2 “QR kodla ekle” |
 * | `contact-unknown` · `contact-own` | … after an unknown code · the user's own code |
 * | `contact-mycode` | … “Kendi kodumu göster” (the contact code) |
 * | `contact-verified` | … 6.3 after adding deniz by QR |
 * | `flow-match` | plays 6.1 → 6.2 → (simulated match) → 6.3 → 5.1, 4 s per step |
 * | `flow-mismatch` | plays 6.1 → 6.2 → (wrong code) → my code → scanning → close |
 * | `flow-manual` | plays 6.1 → confirmation → 6.3 → 5.1 |
 * | `flow-remove` | plays 6.1 verified → removal confirmation → unverified |
 * | `flow-contact` | plays 6.2 (add) → (simulated match) → 6.3 → 5.1 |
 *
 * The scripted 6.1 flows start with 5.1 under 6.1 (“Sohbete dön” pops to it).
 */
object VerificationHarness : FeatureHarness {
    override val feature: LaunchRoute.Feature = LaunchRoute.Feature.Safety

    const val SAFETY = "safety"
    const val PHOTOS = "photos"
    const val DOCUMENTS = "documents"
    const val MENU = "menu"
    const val BLOCK = "block"
    const val BLOCKED = "blocked"
    const val REPORT = "report"
    const val REPORT_ADDRESS = "report-address"
    const val SAFETY_VERIFIED = "safety-verified"
    const val CHANGED = "changed"
    const val CONFIRM_ACCEPT = "confirm-accept"
    const val CONFIRM = "confirm"
    const val CONFIRM_REMOVE = "confirm-remove"
    const val SCAN = "scan"
    const val MISMATCH = "mismatch"
    const val MY_CODE = "mycode"
    const val DENIED = "denied"
    const val TORCH = "torch"
    const val VERIFIED = "verified"
    const val VERIFIED_MANUAL = "verified-manual"
    const val CONTACT_SCAN = "contact-scan"
    const val CONTACT_UNKNOWN = "contact-unknown"
    const val CONTACT_OWN = "contact-own"
    const val CONTACT_MY_CODE = "contact-mycode"
    const val CONTACT_VERIFIED = "contact-verified"
    const val FLOW_MATCH = "flow-match"
    const val FLOW_MISMATCH = "flow-mismatch"
    const val FLOW_MANUAL = "flow-manual"
    const val FLOW_REMOVE = "flow-remove"
    const val FLOW_CONTACT = "flow-contact"

    /** One step of a scripted flow (screenshots are taken in between). */
    const val STEP_MILLIS = 4_000L

    override val states: List<String> = listOf(
        "deniz/safety", "deniz/photos", "deniz/documents", "deniz/menu", "deniz/block", "deniz/blocked",
        "deniz/report", "deniz/report-address", "deniz/safety-verified", "deniz/changed", "deniz/confirm-accept",
        "deniz/confirm", "deniz/confirm-remove", "deniz/scan", "deniz/mismatch", "deniz/mycode", "deniz/denied",
        "deniz/torch", "deniz/verified", "deniz/verified-manual", "deniz/contact-scan", "deniz/contact-unknown",
        "deniz/contact-own", "deniz/contact-mycode", "deniz/contact-verified", "deniz/flow-match",
        "deniz/flow-mismatch", "deniz/flow-manual", "deniz/flow-remove", "deniz/flow-contact",
    )

    private val known: Set<String> = states.map { it.substringAfter('/') }.toSet()

    override fun plan(target: String?, state: String?, controls: FixtureControls?): HarnessPlan? {
        val chat = ChatId(target?.takeIf { it.isNotEmpty() } ?: return null)
        val id = state ?: SAFETY
        if (id !in known) return null
        val ui = VerificationUiState(id, chat)
        fun plan(tab: ContactTab = ContactTab.Verification, fromChat: Boolean = false, prepare: (suspend () -> Unit)? = null) = HarnessPlan(
            start = StartScreen.Chats,
            stack = listOfNotNull(AppRoute.Conversation(chat).takeIf { fromChat }, AppRoute.SafetyNumber(chat, tab)),
            uiState = ui,
            prepare = prepare,
        )
        return when (id) {
            PHOTOS -> plan(ContactTab.Photos)
            DOCUMENTS -> plan(ContactTab.Documents)
            SAFETY_VERIFIED, CONFIRM_REMOVE -> plan { controls?.markVerified(chat, VerificationMethod.FaceToFace) }
            CHANGED, CONFIRM_ACCEPT -> plan { controls?.setSafetyNumberChanged(chat) }
            BLOCKED -> plan { controls?.setBlocked(chat) }
            VERIFIED -> plan { controls?.markVerified(chat, VerificationMethod.FaceToFace) }
            VERIFIED_MANUAL -> plan { controls?.markVerified(chat, VerificationMethod.Manual) }
            FLOW_MATCH, FLOW_MISMATCH, FLOW_MANUAL -> plan(fromChat = true)
            FLOW_REMOVE -> plan(fromChat = true) { controls?.markVerified(chat, VerificationMethod.FaceToFace) }
            else -> plan()
        }
    }
}

/**
 * Every harness of this module. The app's debug registry reads this list, so a harness added
 * here (one per route feature) is found without changing the app.
 */
val verificationHarnesses: List<FeatureHarness> = listOf(VerificationHarness)

/** Applies [ui]'s 6.1 → 6.2 → 6.3 state to the flow, once (after its first load). */
internal suspend fun applyVerificationHarness(ui: VerificationUiState, model: VerificationModel) {
    if (ui.chat != model.chatId || ui.isContact || !ui.claim()) return
    val scanner = model.scanner
    suspend fun step() = delay(VerificationHarness.STEP_MILLIS)
    when (ui.state) {
        VerificationHarness.CONFIRM_ACCEPT -> model.requestAccept()
        VerificationHarness.CONFIRM -> model.requestMarkVerified()
        VerificationHarness.CONFIRM_REMOVE -> model.requestRemoval()
        VerificationHarness.SCAN, VerificationHarness.DENIED -> model.presentScanner()
        VerificationHarness.TORCH -> {
            model.presentScanner()
            scanner.setTorch(true)
        }
        VerificationHarness.MY_CODE -> {
            model.presentScanner()
            scanner.showOwnCode()
        }
        VerificationHarness.MISMATCH -> {
            model.presentScanner()
            delay(600)
            scanner.handle(scanner.simulatedCodes().second)
        }
        VerificationHarness.VERIFIED, VerificationHarness.VERIFIED_MANUAL -> {
            model.verifiedEntrance = true
            model.verifiedAppearsInPlace = true
            model.showsVerified = true
        }
        VerificationHarness.FLOW_MATCH -> {
            step()
            model.presentScanner()
            step()
            scanner.simulatedCodes().first?.let { scanner.handle(it) }
            step()
            model.exitToChat?.invoke()
        }
        VerificationHarness.FLOW_MISMATCH -> {
            step()
            model.presentScanner()
            step()
            scanner.handle(scanner.simulatedCodes().second)
            step()
            scanner.showOwnCode()
            step()
            scanner.showScanning()
            step()
            scanner.close()
        }
        VerificationHarness.FLOW_MANUAL -> {
            step()
            model.requestMarkVerified()
            step()
            model.confirmsMarkVerified = false
            model.markVerifiedManually()
            step()
            model.exitToChat?.invoke()
        }
        VerificationHarness.FLOW_REMOVE -> {
            step()
            model.requestRemoval()
            step()
            model.confirmsRemoval = false
            model.removeVerification()
        }
        else -> Unit
    }
}

/**
 * Applies [ui]'s contact-screen state (“…”, the block confirmation, the report sheet) to [actions],
 * once per launch (iOS `VerificationHarness.apply(to:)`).
 */
internal suspend fun applyContactActionsHarness(
    ui: VerificationUiState,
    actions: ContactActions,
    reports: ReportPresenter,
    peer: () -> Peer?,
    strings: U2Strings,
) {
    if (ui.chat != actions.chatId || !ui.claimActions()) return
    when (ui.state) {
        VerificationHarness.MENU -> {
            delay(300)
            actions.showsMenu = true
        }
        VerificationHarness.BLOCK -> {
            delay(600)
            actions.requestBlock()
        }
        VerificationHarness.REPORT -> {
            delay(300)
            actions.openReport(reports, peer()).reason = ReportReason.Harassment
        }
        VerificationHarness.REPORT_ADDRESS -> {
            delay(300)
            actions.mailer = AddressOnlyReportMailer
            val report = actions.openReport(reports, peer())
            report.reason = ReportReason.Harassment
            delay(900)
            report.send(strings)
        }
        else -> Unit
    }
}

/** Applies [ui]'s add-by-QR state to the scanner, once. */
internal suspend fun applyContactHarness(ui: VerificationUiState, model: ContactScanModel) {
    if (!ui.isContact || !ui.claim()) return
    val scanner = model.scanner
    when (ui.state) {
        VerificationHarness.CONTACT_UNKNOWN -> {
            delay(600)
            scanner.handle(scanner.simulatedCodes().second)
        }
        VerificationHarness.CONTACT_OWN -> {
            delay(600)
            model.messenger.ownContactCode()?.let { scanner.handle(it) }
        }
        VerificationHarness.CONTACT_MY_CODE -> scanner.showOwnCode()
        VerificationHarness.CONTACT_VERIFIED -> model.simulateMatch("deniz")
        VerificationHarness.FLOW_CONTACT -> {
            delay(VerificationHarness.STEP_MILLIS)
            scanner.simulatedCodes().first?.let { scanner.handle(it) }
            delay(VerificationHarness.STEP_MILLIS)
            model.finish()
        }
        else -> Unit
    }
}
