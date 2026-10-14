package com.syconx.us2us.feature.onboarding

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.syconx.us2us.domain.AccountCreationFailure
import com.syconx.us2us.domain.Biometry
import com.syconx.us2us.domain.MessengerException
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.RestoreFailure
import com.syconx.us2us.domain.SecretCodeRules
import com.syconx.us2us.domain.SetupInterruption
import com.syconx.us2us.domain.UsernameAvailability
import com.syconx.us2us.domain.UsernameRules
import com.syconx.us2us.domain.nav.OnboardingRoute
import com.syconx.us2us.ui.components.CodeDotsAppearance
import com.syconx.us2us.ui.components.UsernameFieldStatus
import com.syconx.us2us.ui.env.U2Haptics
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.nav.U2LayerMotion
import com.syconx.us2us.ui.nav.U2Router
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// State of 01 · Karşılama and 02 · Kayıt ve gizli kod. Every screen reads a view model; the models
// talk only to `MessengerService`, so the live implementation replaces the fixtures without
// touching the screens. Their work runs on the scope they are given (the flow's composition scope).

// MARK: - Flow

/** “n / 3” in the setup header, or “n / 2” after a restore (no username step). */
@Immutable
data class SetupStepLabel(val step: Int, val count: Int)

/**
 * The whole onboarding: the paged welcome (1.1–1.3), the pushed setup steps (2.1–2.3) and the
 * restore hand-off (7.3 → 2.2 → 2.3).
 *
 * [onReturnToLock]: 2.3 failed but left a vault on this phone, locked ([SetupInterruption]): the
 * lock takes over.
 */
@Stable
class OnboardingFlowModel(
    private val messenger: MessengerService,
    private val scope: CoroutineScope,
    haptics: U2Haptics = U2Haptics.None,
    /** The biometrics this phone has (2.3 offers them when there are any). */
    biometry: () -> Biometry = { Biometry.None },
    private val onFinished: () -> Unit,
    private val onReturnToLock: () -> Unit = {},
) {
    enum class Mode {
        /** New account: 2.1 → 2.2 → 2.3. */
        Create,

        /** After a restore (7.3): 2.2 → 2.3, no username step. */
        Restore,
    }

    val router = U2Router<OnboardingRoute>()
    val pager = WelcomePagerModel(scope)
    val username = UsernameStepModel(messenger, scope)
    val code = SecretCodeStepModel(messenger, scope, haptics)
    val finish = NoWayBackStepModel(scope, haptics, biometry)

    var mode: Mode by mutableStateOf(Mode.Create)
        private set

    /** Hides the keyboard (2.1 “Devam”); set by the screen. */
    var dismissKeyboard: () -> Unit = {}

    /**
     * The confirmed code, held only until the account is created — also while another name is
     * chosen after 2.3 found the first one taken.
     */
    private var confirmedCode: String? = null

    init {
        code.onConfirmed = { confirmed -> codeConfirmed(confirmed) }
        finish.onSubmit = { createAccount() }
        finish.onReturnToLock = {
            confirmedCode = null
            onReturnToLock()
        }
        finish.onChooseAnotherName = { chooseAnotherName() }
    }

    /** “n / 3”, or “n / 2” after a restore (no username step). */
    fun stepLabel(route: OnboardingRoute): SetupStepLabel {
        val step = route.setupStep ?: 1
        return if (mode == Mode.Restore) SetupStepLabel(maxOf(step - 1, 1), 2) else SetupStepLabel(step, 3)
    }

    // MARK: Navigation

    /** 1.3 “Hesabımı oluştur” → 2.1. */
    fun startSetup() {
        mode = Mode.Create
        finish.replacesThisPhone = false
        router.push(OnboardingRoute.Username)
    }

    /** 1.1 “Yedekten geri yükle” → 7.3 (the document picker opens there). */
    fun startRestore() {
        router.push(OnboardingRoute.Restore)
    }

    /**
     * 3.3 › 7.3 restored a backup inside the lock: onboarding starts at 2.2 (“1 / 2”) for the new
     * code on this device → 2.3 → 4.1.
     */
    fun continueAfterRestore() {
        mode = Mode.Restore
        // The locked vault on this phone is replaced when 2.3 finishes.
        finish.replacesThisPhone = true
        code.reset()
        router.setPath(listOf(OnboardingRoute.SecretCode))
    }

    /** 7.3 finished on this device → 2.2 set a new code. */
    fun restoreFinished() {
        mode = Mode.Restore
        finish.replacesThisPhone = false
        code.reset()
        router.push(OnboardingRoute.SecretCode)
    }

    /**
     * 2.1 “Devam” (only when the name can be used): on to 2.2 — or, with the code kept from a setup
     * that found the name taken, back to 2.3.
     */
    fun usernameContinue() {
        if (!username.canContinue) return
        dismissKeyboard()
        if (confirmedCode != null) {
            finish.resume()
            router.push(OnboardingRoute.NoWayBack)
            return
        }
        code.reset()
        router.push(OnboardingRoute.SecretCode)
    }

    /** 2.3 “Başka bir ad seç”: back to 2.1, the taken name still in the field and marked; the code chosen on 2.2 is kept. */
    private fun chooseAnotherName() {
        router.pop(to = OnboardingRoute.Username)
    }

    /** Back on 2.2: from the confirm step to the set step, else pop. */
    fun codeBack() {
        if (code.step == SecretCodeStepModel.Step.Confirm) code.backToSet() else router.pop()
    }

    /**
     * Edge-swipe back is allowed everywhere except on 2.2's confirm step (flows.md › Back
     * behaviour: back returns to the set step there) and on 2.3 once only the lock can go on.
     */
    fun allowsSwipeBack(route: OnboardingRoute): Boolean {
        if (route == OnboardingRoute.NoWayBack && finish.returnsToLock) return false
        return !(route == OnboardingRoute.SecretCode && code.step == SecretCodeStepModel.Step.Confirm)
    }

    /**
     * The path changed (button, swipe or harness): leaving 2.3 for 2.2 starts the code over — a
     * confirmed code is never shown again.
     */
    fun pathChanged(old: List<OnboardingRoute>, new: List<OnboardingRoute>) {
        if (old.lastOrNull() == OnboardingRoute.NoWayBack && new.lastOrNull() == OnboardingRoute.SecretCode) {
            confirmedCode = null
            code.reset()
        }
        if (new.isEmpty() && mode == Mode.Restore && finish.replacesThisPhone) {
            // Backed out of a restore that began on the lock (3.3 › 7.3): the vault on this phone is
            // still there, locked, so 1.1 could only offer a new account that cannot be made. Back to
            // the lock; the opened backup and its password go.
            confirmedCode = null
            finish.replacesThisPhone = false
            mode = Mode.Create
            scope.launch { runCatching { messenger.cancelRestore() } }
            onReturnToLock()
            return
        }
        if (new.lastOrNull() == null || new.lastOrNull() == OnboardingRoute.Username) {
            if (mode == Mode.Restore && OnboardingRoute.Restore !in new) mode = Mode.Create
        }
        // Back on the welcome pages: a code kept for another name goes.
        if (new.isEmpty()) confirmedCode = null
    }

    private fun codeConfirmed(confirmed: String) {
        confirmedCode = confirmed
        finish.prepare()
        router.push(OnboardingRoute.NoWayBack)
    }

    /**
     * 2.3 “Kurulumu tamamla”: create (or finish restoring) the account, then the app replaces the
     * root with 4.1. A failure stays on 2.3 and says why.
     */
    private suspend fun createAccount(): NoWayBackStepModel.Failure? {
        val secret = confirmedCode ?: return NoWayBackStepModel.Failure.Network
        // Found taken earlier in this setup (2.3 › back to 2.2 › the code again): the relay would
        // only refuse it once more, after the keys and the proof of work.
        if (mode == Mode.Create && username.status == UsernameStepModel.Status.Taken) return NoWayBackStepModel.Failure.UsernameTaken
        try {
            when (mode) {
                Mode.Create -> messenger.createAccount(username.name, secret, finish.faceIdEnabled)
                Mode.Restore -> messenger.completeRestore(secret, finish.faceIdEnabled)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: MessengerException.Restore) {
            return when (failure.failure) {
                RestoreFailure.BackupNoLongerValid -> NoWayBackStepModel.Failure.BackupGone
                RestoreFailure.BackupDamaged -> NoWayBackStepModel.Failure.BackupDamaged
            }
        } catch (interrupted: MessengerException.SetupInterrupted) {
            confirmedCode = null
            return when (interrupted.interruption) {
                SetupInterruption.AccountKept -> NoWayBackStepModel.Failure.AccountKept
                SetupInterruption.PreviousVaultKept -> NoWayBackStepModel.Failure.PreviousVaultKept
            }
        } catch (refused: MessengerException.AccountCreation) {
            return when (refused.failure) {
                AccountCreationFailure.UsernameTaken -> {
                    // Nothing was kept: the code stays for the next name.
                    username.markTaken(username.name)
                    NoWayBackStepModel.Failure.UsernameTaken
                }
            }
        } catch (_: Exception) {
            return NoWayBackStepModel.Failure.Network
        }
        confirmedCode = null
        onFinished()
        return null
    }

    /** Harness: sets what the drawn state needs without running the flow. */
    fun setForHarness(mode: Mode, path: List<OnboardingRoute>, confirmedCode: String? = null) {
        this.mode = mode
        this.confirmedCode = confirmedCode
        router.setPath(path)
    }

    /** Tests: the code held for 2.3. */
    internal val heldCode: String? get() = confirmedCode
}

/** The design id shown in the setup header (“n / 3”) for each step. */
val OnboardingRoute.setupStep: Int?
    get() = when (this) {
        OnboardingRoute.Username -> 1
        OnboardingRoute.SecretCode -> 2
        OnboardingRoute.NoWayBack -> 3
        OnboardingRoute.Restore -> null
    }

// MARK: - 1.1 → 1.3 pager

/** The paged welcome: the committed page, the live swipe offset and the drawn position. */
@Stable
class WelcomePagerModel(private val scope: CoroutineScope) {
    /** The page the chrome (pager label, dots, CTA) shows. */
    var page: Int by mutableIntStateOf(0)
        private set

    private var drag by mutableFloatStateOf(0f)

    /** The swipe in pages while the finger is down (−1…1; positive = forward). */
    var dragProgress: Float
        get() = drag
        set(value) {
            changeJob?.cancel()
            drag = value
            position = page + value
            updateMotion()
        }

    /** Where the pages are drawn (page + live swipe, animated between pages). */
    var position: Float by mutableFloatStateOf(0f)
        private set

    /** Reduce motion: page changes are the short cross-fade. Set by the screen. */
    var reduceMotion: Boolean = false

    /**
     * Whether the pages are moving (a finger on them, a change settling), published to the pages as
     * `LocalLayerMotion`: the 3D scene on 1.1 renders only while it is settled, so a page change never
     * shares its frames with the scene's render loop.
     */
    val motion = U2LayerMotion()

    private var changesInFlight = 0
    private var changeJob: Job? = null

    private fun updateMotion() {
        motion.updateMoving(drag != 0f || changesInFlight > 0)
    }

    /**
     * Changes page on the design curve (0.5 s); after a swipe the change settles on the critically
     * damped spring instead, so the motion carries on from the finger without a pause.
     */
    fun go(to: Int, spec: AnimationSpec<Float>? = null, initialVelocity: Float = 0f) {
        val target = to.coerceIn(0, PAGE_COUNT - 1)
        page = target
        drag = 0f
        settle(target.toFloat(), spec ?: U2Motion.screen.spec(), initialVelocity)
    }

    fun advance() = go(page + 1)

    /**
     * Ends a swipe: commit when far or fast enough, else settle back — at the finger's speed
     * ([velocity] in pages per second, positive = forward).
     */
    fun endDrag(predictedProgress: Float, velocity: Float = 0f) {
        val progress = drag
        val forward = progress > 0.3f || predictedProgress > 0.6f
        val backward = progress < -0.3f || predictedProgress < -0.6f
        val spring = U2Motion.spring<Float>()
        when {
            forward && page < PAGE_COUNT - 1 -> go(page + 1, spring, U2Motion.settleVelocity(velocity, page + 1 - position))
            backward && page > 0 -> go(page - 1, spring, U2Motion.settleVelocity(velocity, page - 1 - position))
            else -> {
                drag = 0f
                settle(page.toFloat(), spring, U2Motion.settleVelocity(velocity, page - position))
            }
        }
    }

    private fun settle(target: Float, spec: AnimationSpec<Float>, initialVelocity: Float) {
        changeJob?.cancel()
        changesInFlight += 1
        updateMotion()
        val resolved = U2Motion.resolve(spec, reduceMotion)
        val from = position
        changeJob = scope.launch {
            try {
                animate(from, target, if (reduceMotion) 0f else initialVelocity, resolved) { value, _ -> position = value }
            } finally {
                changesInFlight = maxOf(0, changesInFlight - 1)
                updateMotion()
            }
        }
    }

    /** Harness: shows [page] at once. */
    fun setForHarness(page: Int) {
        changeJob?.cancel()
        this.page = page.coerceIn(0, PAGE_COUNT - 1)
        drag = 0f
        position = this.page.toFloat()
    }

    companion object {
        const val PAGE_COUNT = 3
    }
}

// MARK: - 2.1 Kullanıcı adı

/**
 * 2.1 state: the name as typed, the rules it breaks, the debounced availability check and what
 * the screen shows for it.
 */
@Stable
class UsernameStepModel(
    private val messenger: MessengerService,
    private val scope: CoroutineScope,
    /** The pause after typing before the relay is asked. */
    var debounce: Duration = 400.milliseconds,
) {
    sealed interface Status {
        /** Nothing typed. */
        data object Empty : Status

        /** Typing; the check waits for a pause (≈ 400 ms). */
        data object Typing : Status

        /** Fewer than 3 characters after a pause. */
        data object TooShort : Status

        /** The name breaks a rule (the first one): nothing is asked. */
        data class Invalid(val violation: UsernameRules.Violation) : Status

        data object Checking : Status

        /** Valid; whether it is free is known only at setup (live service). */
        data object MeetsRules : Status

        data object Available : Status

        data object Taken : Status
    }

    private var typed by mutableStateOf("")

    /** What the field shows, as typed (the field shows it in lower case). */
    var text: String
        get() = typed
        set(value) {
            if (value == typed) return
            typed = value
            textChanged()
        }

    var status: Status by mutableStateOf(Status.Empty)
        private set

    /** The normalized name (what is checked and registered). */
    var name: String by mutableStateOf("")
        private set

    /** Every rule [name] breaks ([UsernameRules.violations]). */
    var violations: List<UsernameRules.Violation> by mutableStateOf(emptyList())
        private set

    /** Typing paused since the last change: a short name is said only now. */
    private var hasPaused by mutableStateOf(false)

    /** Names 2.3 found taken in this setup (the relay's 409). */
    private val takenAtSetup = mutableSetOf<String>()
    private var checkJob: Job? = null

    val canContinue: Boolean
        get() = (status == Status.Available || status == Status.MeetsRules) && UsernameRules.isValid(name)

    /** “7 / 24”. */
    val counter: String get() = "${UsernameRules.length(name)} / ${UsernameRules.MAX_LENGTH}"

    /** The example card illustrates the typed name once it can be used. */
    val canShowOwnExample: Boolean
        get() = (status == Status.Available || status == Status.MeetsRules) && UsernameRules.isValid(name) && name.length >= 4

    val fieldStatus: UsernameFieldStatus
        get() = when (status) {
            Status.Checking -> UsernameFieldStatus.Checking
            Status.Available, Status.MeetsRules -> UsernameFieldStatus.Positive
            Status.Taken -> UsernameFieldStatus.Negative
            // A broken rule shows no verdict in the field, only the danger label and the tinted rule.
            else -> UsernameFieldStatus.None
        }

    /** Whether [rule] is shown in `danger`: every rule the name breaks, except “too short” while typing goes on. */
    fun isBroken(rule: UsernameRules.Violation): Boolean {
        if (rule !in violations) return false
        if (rule == UsernameRules.Violation.Length && UsernameRules.isTooShort(name)) return hasPaused
        return true
    }

    /** 2.3 found the name taken: back on 2.1 it stays in the field, marked taken, and is not offered again in this setup. */
    fun markTaken(taken: String) {
        takenAtSetup += taken
        if (taken != name) return
        checkJob?.cancel()
        status = Status.Taken
    }

    private fun textChanged() {
        val normalized = UsernameRules.normalize(typed)
        if (normalized == name) return
        name = normalized
        violations = UsernameRules.violations(normalized)
        hasPaused = false
        scheduleCheck()
    }

    private fun scheduleCheck() {
        checkJob?.cancel()
        val candidate = name
        if (candidate.isEmpty()) {
            status = Status.Empty
            return
        }
        // A broken rule shows at once, except “too short”: the first letters of a name are not a mistake.
        val shortOnly = violations == listOf(UsernameRules.Violation.Length) && UsernameRules.isTooShort(candidate)
        val first = violations.firstOrNull()
        if (first != null && !shortOnly) {
            if (status != Status.Invalid(first)) status = Status.Invalid(first)
        } else if (status != Status.Typing) {
            status = Status.Typing
        }
        val valid = violations.isEmpty()
        checkJob = scope.launch {
            delay(debounce)
            hasPaused = true
            if (!valid) {
                if (shortOnly) status = Status.TooShort
                return@launch
            }
            if (candidate in takenAtSetup) {
                status = Status.Taken
                return@launch
            }
            status = Status.Checking
            val result = messenger.usernameAvailability(candidate)
            if (name != candidate) return@launch
            status = when (result) {
                UsernameAvailability.Available -> Status.Available
                UsernameAvailability.MeetsRules -> Status.MeetsRules
                UsernameAvailability.Taken -> Status.Taken
                is UsernameAvailability.Invalid ->
                    if (result.violation == UsernameRules.Violation.Length && UsernameRules.isTooShort(candidate)) Status.TooShort else Status.Invalid(result.violation)
                UsernameAvailability.Empty -> Status.Empty
                UsernameAvailability.Checking -> Status.Checking
            }
        }
    }

    /** Harness: shows [name] in [status] without asking the relay. */
    fun setForHarness(name: String, status: Status) {
        text = name
        checkJob?.cancel()
        this.name = UsernameRules.normalize(name)
        violations = UsernameRules.violations(this.name)
        hasPaused = true
        this.status = status
    }
}

// MARK: - 2.2 Gizli kod

/**
 * 2.2 state: six digits, auto-advance, entered twice; weak codes and a mismatch flash the dots
 * `danger` with a sentence, then start over.
 */
@Stable
class SecretCodeStepModel(
    private val messenger: MessengerService,
    private val scope: CoroutineScope,
    private val haptics: U2Haptics = U2Haptics.None,
) {
    enum class Step { Set, Confirm }

    enum class Problem {
        /** The code was refused (repeated digit, run, too common). */
        Weak,

        /** The confirmation was different. */
        Mismatch,
    }

    var step: Step by mutableStateOf(Step.Set)
        private set

    /** Digits typed so far (never shown, only counted). */
    var entered: List<Int> by mutableStateOf(emptyList())
        private set

    var appearance: CodeDotsAppearance by mutableStateOf(CodeDotsAppearance.Normal)
        private set

    var shakeTrigger: Int by mutableIntStateOf(0)
        private set

    var problem: Problem? by mutableStateOf(null)
        private set

    /** Keys ignored while the last dot is held or an error plays. */
    var isBusy: Boolean by mutableStateOf(false)
        private set

    var onConfirmed: (String) -> Unit = {}

    /** Harness: hold the drawn state (no automatic reset or advance). */
    var isFrozenForHarness = false

    private var firstCode: List<Int>? = null
    private var job: Job? = null

    fun type(digit: Int) {
        if (isBusy || entered.size >= SecretCodeRules.LENGTH || digit !in 0..9) return
        if (problem != null) problem = null
        entered = entered + digit
        if (entered.size == SecretCodeRules.LENGTH) submit()
    }

    fun delete() {
        if (isBusy || entered.isEmpty()) return
        entered = entered.dropLast(1)
    }

    /** Back from the confirm step: choose the code again. */
    fun backToSet() {
        job?.cancel()
        step = Step.Set
        problem = null
        clearDots()
        firstCode = null
        isBusy = false
    }

    /** A fresh set step (entering 2.2, or returning to it from 2.3). */
    fun reset() {
        job?.cancel()
        step = Step.Set
        problem = null
        appearance = CodeDotsAppearance.Normal
        entered = emptyList()
        firstCode = null
        isBusy = false
    }

    private fun submit() {
        isBusy = true
        val code = entered
        job = scope.launch {
            delay(HOLD)
            if (isFrozenForHarness) return@launch
            evaluate(code)
        }
    }

    private fun evaluate(code: List<Int>) {
        val string = code.joinToString("")
        when (step) {
            Step.Set -> if (messenger.secretCodeProblem(string) != null) {
                fail(Problem.Weak, backToSet = false)
            } else {
                firstCode = code
                step = Step.Confirm
                clearDots()
                isBusy = false
            }
            Step.Confirm -> if (code == firstCode) {
                firstCode = null
                onConfirmed(string)
            } else {
                fail(Problem.Mismatch, backToSet = true)
            }
        }
    }

    /**
     * Dots `danger` + soft shake + the sentence; after 0.8 s the dots clear (and a mismatch returns
     * to the set step). The sentence stays until the next digit.
     */
    private fun fail(problem: Problem, backToSet: Boolean) {
        haptics.error()
        appearance = CodeDotsAppearance.Error
        shakeTrigger += 1
        this.problem = problem
        job = scope.launch {
            delay(ERROR_HOLD)
            if (backToSet) {
                firstCode = null
                step = Step.Set
            }
            clearDots()
            isBusy = false
        }
    }

    private fun clearDots() {
        appearance = CodeDotsAppearance.Normal
        entered = emptyList()
    }

    /** Harness: [count] dots of [step], with [problem] on show. */
    fun setForHarness(step: Step, entered: Int, problem: Problem? = null) {
        isFrozenForHarness = true
        this.step = step
        this.entered = List(entered) { 1 }
        this.problem = problem
        appearance = if (problem == null) CodeDotsAppearance.Normal else CodeDotsAppearance.Error
        isBusy = problem != null
    }

    companion object {
        /** The last dot stays visible before the code is checked (0.25 s). */
        val HOLD: Duration = 250.milliseconds

        /** Error dots stay red, then the code starts over (0.8 s). */
        val ERROR_HOLD: Duration = 800.milliseconds
    }
}

// MARK: - 2.3 Geri dönüş yok

/** 2.3 state: the acknowledgement, the biometric preference and the account creation call. */
@Stable
class NoWayBackStepModel(
    private val scope: CoroutineScope,
    private val haptics: U2Haptics = U2Haptics.None,
    private val hardwareBiometry: () -> Biometry = { Biometry.None },
) {
    /** Why “Kurulumu tamamla” failed (the sentence above the checkbox). */
    enum class Failure {
        /** Creating or restoring did not reach the relay: check the connection and try again. */
        Network,

        /**
         * After a restore: the relay refuses the backup (its account was deleted, or it was restored
         * on another phone). Trying again cannot help; a new account can be created.
         */
        BackupGone,

        /** After a restore: the backup can't be read, or it is damaged. Another copy might help. */
        BackupDamaged,

        /** The new account was saved, then a later step failed: it is kept, locked under the new code. Only the lock goes on from here. */
        AccountKept,

        /** Restoring inside the lock was refused before anything was erased: the vault this phone held is untouched. Only the lock goes on. */
        PreviousVaultKept,

        /** The relay refused the name: someone registered it first. Nothing was kept; the CTA goes back to 2.1 for another name. */
        UsernameTaken,
    }

    var acknowledged: Boolean by mutableStateOf(false)
    var faceIdEnabled: Boolean by mutableStateOf(true)

    /** Restoring inside the lock (3.3 › 7.3): finishing erases the vault on this phone, with its messages, which the backup does not hold. */
    var replacesThisPhone: Boolean by mutableStateOf(false)

    var biometry: Biometry by mutableStateOf(Biometry.None)
        private set

    var isWorking: Boolean by mutableStateOf(false)
        private set

    var failure: Failure? by mutableStateOf(null)
        private set

    val failed: Boolean get() = failure != null

    /** Creates the account: `null` when it is done, otherwise why it failed. */
    var onSubmit: suspend () -> Failure? = { Failure.Network }

    /** The CTA after a failure that left a vault on this phone. */
    var onReturnToLock: () -> Unit = {}

    /** The CTA after the name was found taken. */
    var onChooseAnotherName: () -> Unit = {}

    /** A vault is on this phone, locked: trying again would fail, so the CTA goes back to the lock and the way back is closed. */
    val returnsToLock: Boolean get() = failure == Failure.AccountKept || failure == Failure.PreviousVaultKept

    /** The name was taken: the CTA goes back to 2.1 to choose another. */
    val choosesAnotherName: Boolean get() = failure == Failure.UsernameTaken

    val canFinish: Boolean get() = returnsToLock || choosesAnotherName || (acknowledged && !isWorking)

    /** Called each time 2.3 is entered: unticked, biometrics on when available. */
    fun prepare() {
        biometry = hardwareBiometry()
        acknowledged = false
        faceIdEnabled = biometry != Biometry.None
        failure = null
        isWorking = false
    }

    /** Back on 2.3 with another name: the failure goes; the acknowledgement and the biometric choice stay as they were. */
    fun resume() {
        failure = null
        isWorking = false
    }

    fun submit() {
        if (!canFinish) return
        if (returnsToLock) {
            onReturnToLock()
            return
        }
        if (choosesAnotherName) {
            onChooseAnotherName()
            return
        }
        isWorking = true
        failure = null
        scope.launch {
            val result = onSubmit()
            if (result != null) {
                haptics.error()
                isWorking = false
                failure = result
            }
        }
    }

    fun setForHarness(acknowledged: Boolean, failure: Failure? = null, biometry: Biometry = Biometry.Face) {
        this.biometry = biometry
        this.acknowledged = acknowledged
        faceIdEnabled = biometry != Biometry.None
        this.failure = failure
        isWorking = false
    }
}

/** How far the page at [index] is from the drawn [position], in pages (−1…1), for its layer. */
internal fun pageDistance(index: Int, position: Float): Float = (index - position).coerceIn(-1f, 1f)

/** Whether a page at [index] is (partly) on screen at [position]. */
internal fun pageIsVisible(index: Int, position: Float): Boolean = abs(index - position) < 1f
