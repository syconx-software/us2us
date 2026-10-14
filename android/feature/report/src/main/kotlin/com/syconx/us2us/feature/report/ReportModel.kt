package com.syconx.us2us.feature.report

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.syconx.us2us.domain.ReportReason
import com.syconx.us2us.domain.ReportSubject
import com.syconx.us2us.ui.l10n.U2Strings
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.syconx.us2us.ui.R as DesignR

/**
 * The report sheet's state (“Bildir”, App Review 1.2): the reason, an optional note and “Bu kişiyi
 * de engelle” (on by default), then the e-mail to the support address ([ReportMail]) through a mail
 * app or, when this phone has none, the address to copy.
 *
 * The block follows the report only once the report left the sheet
 * ([ReportDelivery.blocks]): handed to a mail app, or the address given to the user. No mail app
 * picked, a mail app that cancelled or a failure blocks nobody; the form stays as it was.
 *
 * @param subject who the report names (`MessengerService.reportSubject`).
 * @param personName how the screens show the person.
 * @param offersBlock `false` when the person is blocked already.
 * @param scope runs the block, which must not end with the sheet (it may wait for the network).
 * @param block blocks the person (the host's own way: a chat, a request).
 */
@Stable
class ReportModel(
    val subject: ReportSubject,
    val personName: String,
    val offersBlock: Boolean,
    private val mailer: ReportMailer,
    private val scope: CoroutineScope,
    private val block: suspend () -> Unit,
) {
    enum class Phase {
        /** Reason, note, the block toggle and “E-postayla gönder”. */
        Form,

        /** The mail app is being handed the report. */
        Sending,

        /** Nothing on this phone can write an e-mail: the address to copy. */
        Address,
    }

    var reason: ReportReason? by mutableStateOf(null)

    private var noteText by mutableStateOf("")

    /** The note, never longer than [ReportMail.MAX_NOTE_LENGTH] characters. */
    var note: String
        get() = noteText
        set(value) {
            noteText = ReportMail.clamped(value)
        }

    var alsoBlock: Boolean by mutableStateOf(true)

    var phase: Phase by mutableStateOf(Phase.Form)
        private set

    /** The mail app could not send the last attempt (the form says so). */
    var failed: Boolean by mutableStateOf(false)
        private set

    /** This report blocked the person. */
    var didBlock: Boolean by mutableStateOf(false)
        private set

    /** “Kopyala” on the address was tapped. */
    var copiedAddress: Boolean by mutableStateOf(false)
        private set

    /** What the sheet says aloud next (a polite live region), or `null`. */
    var announcement: String? by mutableStateOf(null)
        private set

    /** The block this report started (it may still be waiting for the network). */
    var blocking: Job? = null
        private set

    /** Closes the sheet; `blocked`: this report blocked the person. Called once. */
    var onClose: ((blocked: Boolean) -> Unit)? = null

    /** The app's `versionName` and `versionCode`, written into the e-mail. */
    var version: String = "—"
    var build: String = "—"

    private var isClosed = false

    /** A reason is chosen and the form is up. */
    val canSend: Boolean get() = reason != null && phase == Phase.Form

    /** The person is blocked if the report leaves the sheet. */
    val blocksOnSend: Boolean get() = offersBlock && alsoBlock

    // MARK: Actions

    /** “E-postayla gönder”: the e-mail in [strings]' language, then what came of it. */
    suspend fun send(strings: U2Strings, now: Instant = Instant.now()) {
        val reason = reason ?: return
        if (phase != Phase.Form) return
        failed = false
        val mail = ReportMail.make(subject, reason, note, version, build, now, strings)
        phase = Phase.Sending
        val delivery = mailer.deliver(mail)
        if (ReportDelivery.blocks(delivery, blocksOnSend)) {
            didBlock = true
            // Stored at once; the new access key the block asks the relay for can take a while on a
            // poor connection, and the sheet does not wait for it.
            blocking = scope.launch { block() }
        }
        when (delivery) {
            ReportDelivery.Sent, ReportDelivery.HandedOff -> {
                announce(strings, if (delivery == ReportDelivery.Sent) DesignR.string.report_sent else DesignR.string.report_handedoff)
                close()
            }
            ReportDelivery.AddressShown -> {
                phase = Phase.Address
                announce(strings, DesignR.string.report_address_title)
            }
            ReportDelivery.NotSent -> phase = Phase.Form
            ReportDelivery.Failed -> {
                phase = Phase.Form
                failed = true
                announce(strings, DesignR.string.report_failed)
            }
        }
    }

    /** The address on the clipboard ([copy] puts it there); the button then says “Kopyalandı”. */
    fun copyAddress(strings: U2Strings, copy: (String) -> Unit) {
        copy(ReportMail.ADDRESS)
        copiedAddress = true
        announce(strings, DesignR.string.report_address_copied)
    }

    /** “Vazgeç”, “Kapat”, “Tamam”, or the sheet pulled down (once). */
    fun close() {
        if (isClosed) return
        isClosed = true
        onClose?.invoke(didBlock)
    }

    /** The sheet was pulled down or backed out of. Not while the mail app is being handed the report. */
    fun sheetDidDisappear() {
        if (phase == Phase.Sending) return
        close()
    }

    private fun announce(strings: U2Strings, id: Int) {
        var text = strings[id]
        if (didBlock) text += " " + strings.get(DesignR.string.report_blocked, personName)
        announcement = text
    }
}
