package com.syconx.us2us.feature.lock

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.syconx.us2us.domain.Biometry
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.SecretCodeRules
import com.syconx.us2us.domain.UnlockAttemptResult
import com.syconx.us2us.ui.components.CodeDotsAppearance
import com.syconx.us2us.ui.env.U2Haptics
import com.syconx.us2us.ui.motion.U2Motion
import java.net.URI
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The words of the system biometric prompt, in the in-app language: the reason, and “Kodla aç” on its cancel button. */
@Immutable
data class BiometricPromptText(val reason: String, val cancelTitle: String)

/**
 * The lock flow (03): which lock screen shows, the biometric scan, the 3.3 sheet and the restore
 * hand-off. All data goes through `MessengerService`, so the live implementation is a drop-in.
 *
 * - 3.1 shows first when biometric unlock is enabled and enrolled, and scans by itself as soon as
 *   the app is active — on every launch and every return from the background. Failure or cancel → 3.2.
 * - 3.2 the code ([LockCodeModel]). The keypad's biometric key goes back to 3.1.
 * - 3.3 “Kodu unuttum” — a floating sheet: restore (7.3) or erase everything after a second confirmation.
 * - After the optional wipe (the 10th wrong code in a row, PROTOCOL §5.2): “Veriler silindi” at once,
 *   with the two real ways on — a backup, or a new start — instead of “Kod yanlış”.
 *
 * Its work runs on [scope] (the lock's composition scope).
 */
@Stable
class LockFlowModel(
    private val messenger: MessengerService,
    private val scope: CoroutineScope,
    val biometry: Biometry,
    private val haptics: U2Haptics = U2Haptics.None,
    /** DEBUG harness: no automatic scan, no transitions out of the drawn state. */
    val isFrozenForHarness: Boolean = false,
    /** The wipe fired outside the lock (the current code of 7.1 › Gizli kodu değiştir): the lock opens on its outcome. */
    startsWiped: Boolean = false,
    private val onUnlock: () -> Unit,
    private val onErase: () -> Unit,
    /** After the wipe: on to onboarding — `true` straight to 7.3, `false` to 1.1. */
    private val onStartOver: (restoring: Boolean) -> Unit = {},
    /** The wipe fired on the lock: what the app keeps outside the vault about the erased account goes too. */
    private val onWiped: () -> Unit = {},
    /** The wall clock (waits are measured by the vault, not the design clock). */
    now: () -> Instant = Instant::now,
) {
    sealed interface Stage {
        /** 3.1 */
        data object Biometric : Stage

        /** 3.2 */
        data object Code : Stage

        /** 3.3 › “Yedekten geri yükle” → 7.3 with the chosen [file], inside the lock (`null`: 7.3 asks for one). */
        data class Restore(val file: URI?) : Stage

        /** The optional wipe fired: everything on this phone is erased. */
        data object Wiped : Stage
    }

    enum class ForgotStep {
        /** The two honest options. */
        Options,

        /** “Her şey silinsin mi?” — the second confirmation. */
        ConfirmErase,
    }

    var stage: Stage by mutableStateOf(if (startsWiped) Stage.Wiped else if (biometry == Biometry.None) Stage.Code else Stage.Biometric)
        private set

    var isScanning: Boolean by mutableStateOf(false)
        private set

    val code = LockCodeModel(messenger, scope, haptics, onUnlock, now)

    /** 3.3 sheet. */
    var showsForgot: Boolean by mutableStateOf(false)
        private set

    /** 3.3 › “Yedekten geri yükle”: the system picker for the `.u2u` file is up (the screen launches it). */
    var showsBackupPicker: Boolean by mutableStateOf(false)
        private set

    var forgotStep: ForgotStep by mutableStateOf(ForgotStep.Options)
        private set

    /** The confirmation's button ignores taps until its cross-fade has finished (no accidental double tap through the danger card). */
    var confirmationArmed: Boolean by mutableStateOf(false)
        private set

    var isErasing: Boolean by mutableStateOf(false)
        private set

    /** Scan automatically at the next “app became active”. */
    private var autoScanArmed = !isFrozenForHarness && !startsWiped

    /** Invalidates late biometric results (backgrounding, relock). */
    private var scanGeneration = 0
    private val clock = now
    private var stageShownAt = now()

    /** What happens after the sheet has gone (it dismisses first, softly). */
    private var afterForgotDismiss: (() -> Unit)? = null
    private var armJob: Job? = null

    init {
        code.onWiped = { dataWiped() }
    }

    // MARK: Lifecycle

    /** The app became active (or the lock appeared while active): start the armed biometric scan once. */
    fun appBecameActive(prompt: BiometricPromptText) {
        if (!autoScanArmed || stage != Stage.Biometric || showsForgot) return
        autoScanArmed = false
        startScan(prompt)
    }

    /** Back from the background while the lock was already up: lock afresh — 3.1 (scanning again) or an empty 3.2. */
    fun relock() {
        // After the wipe there is nothing left to lock: the outcome stays.
        if (isFrozenForHarness || isErasing || stage == Stage.Wiped) return
        scanGeneration += 1
        isScanning = false
        showsForgot = false
        code.reset()
        autoScanArmed = biometry != Biometry.None
        stage = if (biometry == Biometry.None) Stage.Code else Stage.Biometric
        forgotStep = ForgotStep.Options
        stageShownAt = clock()
    }

    // MARK: 3.1 Biometrics

    /**
     * Runs the system biometric check through the service. Success unlocks (the lock dissolves
     * 0.36 s); failure or cancel cross-fades to 3.2. There is no device-credential fallback.
     */
    fun startScan(prompt: BiometricPromptText) {
        if (biometry == Biometry.None || isScanning || isFrozenForHarness) return
        isScanning = true
        scanGeneration += 1
        val generation = scanGeneration
        val startedAt = clock()
        scope.launch {
            val success = runCatching { messenger.unlockWithBiometrics(prompt.reason, prompt.cancelTitle) }.getOrDefault(false)
            if (generation != scanGeneration) return@launch
            isScanning = false
            if (success) {
                haptics.success()
                onUnlock()
                return@launch
            }
            // Let the screen settle before leaving it (an instant refusal — biometrics locked out —
            // must not flicker 3.2 → 3.1 → 3.2).
            val since = maxOf(startedAt, stageShownAt)
            val shown = java.time.Duration.between(since, clock()).toKotlinDuration()
            val dwell = MIN_DWELL - shown
            if (dwell > Duration.ZERO) delay(dwell)
            if (generation != scanGeneration || stage != Stage.Biometric) return@launch
            show(Stage.Code)
        }
    }

    /** “Kodla aç”: straight to 3.2. A biometric result still in flight is ignored from here on. */
    fun useCode() {
        scanGeneration += 1
        isScanning = false
        show(Stage.Code)
    }

    /** The keypad's biometric key: back to 3.1, scanning. */
    fun useBiometrics(prompt: BiometricPromptText) {
        if (biometry == Biometry.None) return
        code.clearEntry()
        show(Stage.Biometric)
        startScan(prompt)
    }

    // MARK: 3.3 Kodu unuttum

    fun openForgot() {
        forgotStep = ForgotStep.Options
        confirmationArmed = false
        showsForgot = true
    }

    /** “Tekrar dene” (or a swipe down) → 3.2. */
    fun closeForgot() {
        showsForgot = false
    }

    /** Called by the sheet once it has gone. */
    fun forgotDidDismiss() {
        forgotStep = ForgotStep.Options
        val next = afterForgotDismiss
        afterForgotDismiss = null
        next?.invoke()
    }

    /** “Her şeyi sil, baştan başla” → the second confirmation, in the same sheet. */
    fun askEraseConfirmation() {
        confirmationArmed = false
        forgotStep = ForgotStep.ConfirmErase
        armJob?.cancel()
        armJob = scope.launch {
            delay(U2Motion.Duration.screen.toDouble().seconds)
            if (forgotStep == ForgotStep.ConfirmErase) confirmationArmed = true
        }
    }

    /** “Vazgeç” in the confirmation → back to the options. */
    fun cancelErase() {
        armJob?.cancel()
        confirmationArmed = false
        forgotStep = ForgotStep.Options
    }

    /** “Her şeyi sil” (second confirmation): the sheet goes down, then everything is crypto-shredded and the app returns to 1.1. */
    fun confirmErase() {
        if (forgotStep != ForgotStep.ConfirmErase || !confirmationArmed || isErasing) return
        isErasing = true
        haptics.error()
        afterForgotDismiss = { onErase() }
        showsForgot = false
    }

    /** “Yedekten geri yükle”: the sheet goes down, then the system picker for the `.u2u` file opens (7.3 continues with the chosen file). */
    fun chooseBackup() {
        afterForgotDismiss = { showsBackupPicker = true }
        showsForgot = false
    }

    /** The picker closed: with a file, 7.3 takes over inside the lock; cancelled, 3.2 stays. */
    fun backupPickerClosed(file: URI?) {
        showsBackupPicker = false
        if (file != null) backupChosen(file)
    }

    /** 7.3 takes over inside the lock. */
    fun backupChosen(file: URI?) {
        show(Stage.Restore(file))
    }

    /** Back from 7.3 (cancelled) → 3.2. */
    fun leaveRestore() {
        show(Stage.Code)
    }

    // MARK: After the wipe (PROTOCOL §5.2)

    /** The 10th wrong code in a row erased everything: say so now, not “Kod yanlış” until the next launch. Nothing else of the lock is live. */
    fun dataWiped() {
        onWiped()
        showsBackupPicker = false
        scanGeneration += 1
        isScanning = false
        autoScanArmed = false
        showsForgot = false
        afterForgotDismiss = null
        show(Stage.Wiped)
    }

    /** “Yedekten geri yükle”: onboarding, straight to 7.3. */
    fun restoreAfterWipe() {
        if (stage == Stage.Wiped) onStartOver(true)
    }

    /** “Baştan başla”: onboarding at 1.1. */
    fun startOverAfterWipe() {
        if (stage == Stage.Wiped) onStartOver(false)
    }

    // MARK: Harness

    /** Puts the flow in a drawn state at once (DEBUG harness). */
    fun setForHarness(stage: Stage, forgot: ForgotStep? = null) {
        this.stage = stage
        if (forgot != null) {
            forgotStep = forgot
            confirmationArmed = true
            showsForgot = true
        }
    }

    private fun show(next: Stage) {
        if (stage == next) return
        stageShownAt = clock()
        stage = next
    }

    companion object {
        /** A refused scan leaves 3.1 no sooner than this after it showed. */
        val MIN_DWELL: Duration = 700.milliseconds
    }
}

/**
 * 3.2 — the code entry: six digits auto-submit; a wrong code turns the dots red with a soft shake
 * and a plain sentence; after the free attempts the keypad waits (PROTOCOL §5.2), live `mm:ss`.
 */
@Stable
class LockCodeModel(
    private val messenger: MessengerService,
    private val scope: CoroutineScope,
    private val haptics: U2Haptics = U2Haptics.None,
    private val onUnlock: () -> Unit,
    /** The wall clock: waits are measured by the vault, not the design clock. */
    val now: () -> Instant = Instant::now,
) {
    sealed interface Message {
        /** “Kod yanlış. {n} yanlış daha olursa {süre} beklemen gerekir.” */
        data class Wrong(val attemptsLeft: Int, val delay: Duration) : Message

        /** “Kod yanlış.” (no further delay ahead) */
        data object WrongPlain : Message

        /** “Çok fazla yanlış deneme. {mm:ss} sonra tekrar dene.” */
        data class Throttled(val until: Instant) : Message

        /** “Verilerin şu an açılamadı. Kodun doğru olabilir; …” — not a wrong code, nothing counted. */
        data object Unavailable : Message

        /** “Bu telefon verilerinin kilidini artık açamıyor. …” — not a wrong code; only a backup helps. */
        data object Unusable : Message
    }

    var entered: String by mutableStateOf("")
        private set

    var appearance: CodeDotsAppearance by mutableStateOf(CodeDotsAppearance.Normal)
        private set

    var shakeTrigger: Int by mutableIntStateOf(0)
        private set

    var message: Message? by mutableStateOf(null)
        private set

    var isVerifying: Boolean by mutableStateOf(false)
        private set

    /** DEBUG harness: keep the red dots on screen (the 3.2 reference). */
    var holdsErrorState = false

    /** DEBUG harness: a wait never ends on its own. */
    var isFrozenForHarness = false

    /** The optional wipe fired with this code (the flow shows its outcome). */
    var onWiped: () -> Unit = {}

    private var clearJob: Job? = null
    private var throttleJob: Job? = null
    private var verifyJob: Job? = null

    init {
        resumeThrottleIfNeeded()
    }

    val isThrottled: Boolean
        get() = (message as? Message.Throttled)?.let { it.until > now() } ?: false

    val throttleEnd: Instant? get() = (message as? Message.Throttled)?.until

    fun type(digit: Int) {
        if (isVerifying || isThrottled || entered.length >= SecretCodeRules.LENGTH) return
        if (appearance == CodeDotsAppearance.Error || message != null) {
            // The next digit starts over: the red dots clear, the sentence fades.
            clearJob?.cancel()
            appearance = CodeDotsAppearance.Normal
            entered = ""
            message = null
        }
        entered += digit.toString()
        if (entered.length == SecretCodeRules.LENGTH) verify()
    }

    fun deleteLast() {
        if (isVerifying || entered.isEmpty() || appearance != CodeDotsAppearance.Normal) return
        entered = entered.dropLast(1)
    }

    /** Empties the dots (biometric key, relock) — the sentence and any wait stay. */
    fun clearEntry() {
        if (isVerifying) return
        clearJob?.cancel()
        entered = ""
        if (appearance == CodeDotsAppearance.Error) appearance = CodeDotsAppearance.Normal
    }

    /** A fresh lock: empty dots, no sentence — unless a wait is still running. */
    fun reset() {
        clearJob?.cancel()
        verifyJob?.cancel()
        isVerifying = false
        entered = ""
        appearance = CodeDotsAppearance.Normal
        message = null
        resumeThrottleIfNeeded()
    }

    private fun verify() {
        isVerifying = true
        val code = entered
        verifyJob = scope.launch {
            // Let the sixth dot be seen before anything happens (setupAdvance, 0.25 s).
            delay(250.milliseconds)
            val result = messenger.unlock(code)
            handle(result)
        }
    }

    private fun handle(result: UnlockAttemptResult) {
        when (result) {
            UnlockAttemptResult.Unlocked -> {
                // Stays “verifying” while the lock dissolves (no more input).
                haptics.success()
                onUnlock()
            }
            is UnlockAttemptResult.WrongCode -> {
                isVerifying = false
                haptics.error()
                appearance = CodeDotsAppearance.Error
                shakeTrigger += 1
                val status = result.status
                val until = status.nextAttemptAllowedAt
                val upcoming = status.upcoming
                message = when {
                    until != null && until > now() -> Message.Throttled(until)
                    upcoming != null -> Message.Wrong(upcoming.attemptsLeft, upcoming.delay)
                    else -> Message.WrongPlain
                }
                scheduleThrottleEnd()
                scheduleClear()
            }
            is UnlockAttemptResult.Throttled -> {
                isVerifying = false
                message = Message.Throttled(result.until)
                appearance = CodeDotsAppearance.Dimmed
                entered = ""
                scheduleThrottleEnd()
            }
            UnlockAttemptResult.VaultUnavailable, UnlockAttemptResult.VaultUnusable -> {
                // Never the wrong-code look (red dots, shake, the delays ahead): the code was not
                // rejected and nothing was counted.
                isVerifying = false
                message = if (result == UnlockAttemptResult.VaultUnusable) Message.Unusable else Message.Unavailable
                appearance = CodeDotsAppearance.Normal
                entered = ""
            }
            UnlockAttemptResult.Wiped -> {
                // Never “Kod yanlış”: everything is gone, and the lock says so. No more input: the
                // keypad leaves with the screen.
                clearJob?.cancel()
                throttleJob?.cancel()
                haptics.error()
                message = null
                appearance = CodeDotsAppearance.Normal
                entered = ""
                onWiped()
            }
            UnlockAttemptResult.Malformed, UnlockAttemptResult.Interrupted -> {
                isVerifying = false
                entered = ""
            }
        }
    }

    /** The red dots clear after 0.8 s; the sentence stays until the next digit. */
    private fun scheduleClear() {
        clearJob?.cancel()
        if (holdsErrorState) return
        clearJob = scope.launch {
            delay(800.milliseconds)
            entered = ""
            appearance = if (isThrottled) CodeDotsAppearance.Dimmed else CodeDotsAppearance.Normal
        }
    }

    /** A wait still running (the status survives relaunch in the vault). */
    private fun resumeThrottleIfNeeded() {
        val until = messenger.unlockStatus.value.nextAttemptAllowedAt ?: return
        if (until <= now()) return
        message = Message.Throttled(until)
        appearance = CodeDotsAppearance.Dimmed
        scheduleThrottleEnd()
    }

    /** When the wait is over the keypad comes back (0.36 s) and the sentence fades. */
    private fun scheduleThrottleEnd() {
        throttleJob?.cancel()
        val until = throttleEnd ?: return
        if (isFrozenForHarness) return
        throttleJob = scope.launch {
            val remaining = java.time.Duration.between(now(), until).toKotlinDuration()
            if (remaining > Duration.ZERO) delay(remaining)
            if (throttleEnd != until) return@launch
            message = null
            appearance = CodeDotsAppearance.Normal
        }
    }

    /** DEBUG harness: digits on screen without verifying. */
    fun setEnteredForHarness(digits: String) {
        entered = digits.take(SecretCodeRules.LENGTH - 1)
    }

    /** DEBUG harness: a sentence on screen without an attempt (3.2's “not a wrong code” states). */
    fun setMessageForHarness(message: Message) {
        this.message = message
    }
}
