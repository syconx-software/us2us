package com.syconx.us2us.feature.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.SecretCodeRules
import com.syconx.us2us.domain.UnlockAttemptResult
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.CodeDots
import com.syconx.us2us.ui.components.CodeDotsAppearance
import com.syconx.us2us.ui.components.FaceIdGlyph
import com.syconx.us2us.ui.components.NumericKeypad
import com.syconx.us2us.ui.components.SetupHeader
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.text.SetupTitle
import com.syconx.us2us.ui.components.text.U2DisplayText
import com.syconx.us2us.ui.env.LocalAppModel
import com.syconx.us2us.ui.env.LocalHarnessState
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.env.U2Haptics
import com.syconx.us2us.ui.env.U2TimeView
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.env.u2Top
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.l10n.U2Format
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.l10n.u2Plural
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.u2TransitionBlur
import com.syconx.us2us.ui.nav.LocalRouter
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * State of 7.1 › Gizli kodu değiştir: the current code (3.2 layout, through the lock's throttle) →
 * a new code (2.2 layout, the same rules as setup) → the new code again → re-key. Codes live only in
 * this model.
 *
 * The same first step turns Face ID on from 7.1 ([Purpose.EnableBiometrics]): the biometric key
 * wraps the vault key, so the code is needed once; a right code turns it on and the page closes.
 *
 * [onWiped]: the current code was the 10th wrong code in a row and the optional wipe erased
 * everything (PROTOCOL §5.2): the app leaves 7.1 for the lock's “Veriler silindi”.
 * [onUnavailable] ([Purpose.EnableBiometrics]): biometrics cannot be used right now (or the vault
 * could not be read): 7.1 says “Face ID açılamadı.”. [now] is the wall clock the throttle is
 * measured with.
 */
@Stable
class ChangeCodeModel(
    private val messenger: MessengerService,
    private val scope: CoroutineScope,
    val purpose: Purpose = Purpose.ChangeCode,
    private val onChanged: () -> Unit,
    private val onWiped: () -> Unit = {},
    private val onUnavailable: () -> Unit = {},
    private val now: () -> Instant = Instant::now,
) {
    enum class Purpose {
        /** 7.1 › Gizli kodu değiştir: three steps. */
        ChangeCode,

        /** 7.1 › “Face ID ile aç”: the current code only. */
        EnableBiometrics,
    }

    enum class Step(val number: Int) {
        Current(1),
        New(2),
        Confirm(3),
    }

    sealed interface Message {
        /** “Kod yanlış. {n} yanlış daha olursa {süre} beklemen gerekir.” */
        data class Wrong(val attemptsLeft: Int, val delaySeconds: Double) : Message

        /** “Kod yanlış.” */
        data object WrongPlain : Message

        /** “Çok fazla yanlış deneme. {mm:ss} sonra tekrar dene.” */
        data class Throttled(val until: Instant) : Message

        /** The new code is too easy to guess (the core's rules). */
        data object Weak : Message

        /** The new code equals the current one. */
        data object SameAsCurrent : Message

        /** The confirmation differs: back to the new-code step. */
        data object Mismatch : Message

        /** Re-keying failed; the old code still works. */
        data object Failed : Message

        /** The current code could not be checked right now (not counted). */
        data object Unavailable : Message

        /** The vault can no longer be opened on this phone once it locks. */
        data object Unusable : Message
    }

    var step by mutableStateOf(Step.Current)
        private set
    var entered by mutableStateOf("")
        private set
    var appearance by mutableStateOf(CodeDotsAppearance.Normal)
        private set
    var shakeTrigger by mutableIntStateOf(0)
        private set
    var message by mutableStateOf<Message?>(null)
        private set
    var isBusy by mutableStateOf(false)
        private set

    /** The window's haptics (the screen sets them). */
    var haptics: U2Haptics = U2Haptics.None

    private var currentCode = ""
    private var newCode = ""

    init {
        val next = messenger.unlockStatus.value.nextAttemptAllowedAt
        if (next != null && next > now()) {
            message = Message.Throttled(next)
            appearance = CodeDotsAppearance.Dimmed
        }
    }

    val isThrottled: Boolean
        get() = (message as? Message.Throttled)?.let { it.until > now() } ?: false

    fun type(digit: Int) {
        if (isBusy || isThrottled || entered.length >= SecretCodeRules.LENGTH) return
        if (appearance == CodeDotsAppearance.Error) {
            appearance = CodeDotsAppearance.Normal
            entered = ""
        }
        if (message != null && !isThrottled) message = null
        entered += digit.toString()
        if (entered.length == SecretCodeRules.LENGTH) scope.launch { complete() }
    }

    fun deleteLast() {
        if (isBusy || entered.isEmpty() || appearance == CodeDotsAppearance.Error) return
        entered = entered.dropLast(1)
    }

    /** The header's back button: confirm → new; otherwise leave (`false`). */
    fun back(): Boolean {
        if (step != Step.Confirm || isBusy) return false
        advance(Step.New)
        return true
    }

    fun throttleEnded() {
        if (message !is Message.Throttled) return
        message = null
        appearance = CodeDotsAppearance.Normal
    }

    private suspend fun complete() {
        isBusy = true
        // Let the sixth dot be seen (setupAdvance, 0.25 s).
        delay(250.milliseconds)
        val code = entered
        when (step) {
            Step.Current -> checkCurrent(code)
            Step.New -> {
                isBusy = false
                when {
                    messenger.secretCodeProblem(code) != null -> fail(Message.Weak)
                    code == currentCode -> fail(Message.SameAsCurrent)
                    else -> {
                        newCode = code
                        advance(Step.Confirm)
                    }
                }
            }
            Step.Confirm -> {
                if (code != newCode) {
                    // Red dots on this step for 0.8 s, then back to the new code.
                    fail(Message.Mismatch)
                    delay(800.milliseconds)
                    isBusy = false
                    advance(Step.New, keepMessage = true)
                    return
                }
                val changed = try {
                    messenger.changeSecretCode(currentCode, newCode)
                    true
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (changed) {
                    currentCode = ""
                    newCode = ""
                    haptics.success()
                    onChanged()
                } else {
                    fail(Message.Failed)
                    delay(800.milliseconds)
                    isBusy = false
                    advance(Step.New, keepMessage = true)
                }
            }
        }
    }

    private suspend fun checkCurrent(code: String) {
        val result = if (purpose == Purpose.EnableBiometrics) messenger.enableBiometricUnlock(code) else messenger.verifySecretCode(code)
        isBusy = false
        when {
            result == UnlockAttemptResult.Unlocked && purpose == Purpose.EnableBiometrics -> {
                // Face ID is on; the page closes (the dots stay full meanwhile).
                isBusy = true
                haptics.success()
                onChanged()
            }
            result == UnlockAttemptResult.Unlocked -> {
                currentCode = code
                advance(Step.New)
            }
            result == UnlockAttemptResult.VaultUnavailable && purpose == Purpose.EnableBiometrics -> {
                entered = ""
                onUnavailable()
            }
            result is UnlockAttemptResult.WrongCode -> {
                val next = result.status.nextAttemptAllowedAt
                val upcoming = result.status.upcoming
                when {
                    next != null && next > now() -> fail(Message.Throttled(next))
                    upcoming != null -> fail(Message.Wrong(upcoming.attemptsLeft, upcoming.delay.inWholeMilliseconds / 1_000.0))
                    else -> fail(Message.WrongPlain)
                }
            }
            result is UnlockAttemptResult.Throttled -> {
                message = Message.Throttled(result.until)
                appearance = CodeDotsAppearance.Dimmed
                entered = ""
            }
            result == UnlockAttemptResult.VaultUnavailable || result == UnlockAttemptResult.VaultUnusable -> {
                // Not a wrong code: no red dots, no delays ahead.
                message = if (result == UnlockAttemptResult.VaultUnusable) Message.Unusable else Message.Unavailable
                appearance = CodeDotsAppearance.Normal
                entered = ""
            }
            result == UnlockAttemptResult.Wiped -> {
                // Never “Kod yanlış”: nothing is left to change.
                message = null
                appearance = CodeDotsAppearance.Normal
                entered = ""
                haptics.error()
                onWiped()
            }
            else -> entered = "" // Malformed, Interrupted
        }
    }

    /**
     * Error state: dots `danger` + soft shake, the sentence fades in. The dots clear after 0.8 s unless
     * the next digit already did; typing is never blocked.
     */
    private fun fail(message: Message) {
        haptics.error()
        appearance = CodeDotsAppearance.Error
        shakeTrigger += 1
        this.message = message
        val trigger = shakeTrigger
        scope.launch {
            delay(800.milliseconds)
            if (shakeTrigger != trigger || appearance != CodeDotsAppearance.Error) return@launch
            entered = ""
            appearance = if (isThrottled) CodeDotsAppearance.Dimmed else CodeDotsAppearance.Normal
        }
    }

    private fun advance(next: Step, keepMessage: Boolean = false) {
        step = next
        entered = ""
        appearance = CodeDotsAppearance.Normal
        if (!keepMessage) message = null
    }

    /** Debug harness states (`settings:code…`). */
    fun applyHarness(state: String) {
        when (state) {
            "code-wrong" -> {
                entered = "135790"
                appearance = CodeDotsAppearance.Error
                message = Message.Wrong(attemptsLeft = 2, delaySeconds = 60.0)
            }
            "code-new" -> {
                step = Step.New
                currentCode = DesignFixtures.DEMO_SECRET_CODE
                entered = "4719"
            }
            "code-weak" -> {
                step = Step.New
                currentCode = DesignFixtures.DEMO_SECRET_CODE
                entered = "123456"
                appearance = CodeDotsAppearance.Error
                message = Message.Weak
            }
            "code-confirm" -> {
                step = Step.Confirm
                currentCode = DesignFixtures.DEMO_SECRET_CODE
                newCode = "471958"
                entered = "47"
            }
            "code-mismatch" -> {
                step = Step.New
                currentCode = DesignFixtures.DEMO_SECRET_CODE
                message = Message.Mismatch
            }
        }
    }
}

/** Test tags of the code change. */
object ChangeCodeTags {
    const val SCREEN = "changeCode.screen"
    const val BADGE = "changeCode.badge"
    const val TITLE = "changeCode.title"
    const val DOTS = "changeCode.dots"
    const val MESSAGE = "changeCode.message"
    const val KEYPAD = "changeCode.keypad"
    const val FACE_ID_BACK = "faceID.enable.back"
}

/**
 * 7.1 › Gizli kodu değiştir: three steps on one pushed screen. The keypad stays where it is (2.2
 * position); only the upper block cross-fades between steps (0.36 s), the dots reset softly.
 */
@Composable
internal fun ChangeCodeScreen(purpose: ChangeCodeModel.Purpose, modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val router = LocalRouter.current
    val haptics = LocalHaptics.current
    val harness = LocalHarnessState.current as? SettingsUiState
    val flow = rememberSettingsFlow()
    val scope = rememberCoroutineScope()
    val vm = remember {
        val enablesBiometrics = purpose == ChangeCodeModel.Purpose.EnableBiometrics
        ChangeCodeModel(
            messenger = flow.messenger,
            scope = scope,
            purpose = purpose,
            onChanged = {
                flow.toast = if (enablesBiometrics) R.string.android_biometric_enabled_toast else R.string.settings_code_changed_toast
                router.pop()
            },
            onWiped = { app.dataWipedAfterWrongCodes() },
            onUnavailable = {
                flow.toast = R.string.android_biometric_unavailable
                router.pop()
            },
        ).also { model -> harness?.take("code")?.let(model::applyHarness) }
    }
    SideEffect { vm.haptics = haptics }
    // The confirmation step goes back to the new code, as the header's back button does.
    BackHandler(enabled = vm.step == ChangeCodeModel.Step.Confirm && !vm.isBusy) { vm.back() }

    val fade = U2Transitions.fade()
    U2Screen(modifier.testTag(ChangeCodeTags.SCREEN)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (vm.purpose == ChangeCodeModel.Purpose.EnableBiometrics) {
                // One step: the setup header's back button, without a counter.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .u2Top(U2Spacing.headerTopButtons)
                        .padding(horizontal = U2Spacing.screenGutterS)
                        .height(U2Size.iconButton),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    U2GlassIconButton(
                        glyph = PhosphorGlyph.CaretLeft,
                        contentDescription = stringResource(R.string.common_a11y_back),
                        onClick = router::pop,
                        glyphSize = 22.dp,
                        modifier = Modifier.testTag(ChangeCodeTags.FACE_ID_BACK),
                    )
                }
            } else {
                SetupHeader(step = vm.step.number, onBack = { if (!vm.back()) router.pop() })
            }
            AnimatedContent(
                targetState = vm.step,
                transitionSpec = { fade.enter togetherWith fade.exit },
                contentAlignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxWidth(),
                label = "changeCodeStep",
            ) { step ->
                val live = step == vm.step
                Box(Modifier.then(u2TransitionBlur(fade))) {
                    when (step) {
                        ChangeCodeModel.Step.Current -> CurrentBlock(vm, live)
                        ChangeCodeModel.Step.New -> SetupBlock(vm, R.string.settings_code_new_title, R.string.settings_code_new_body, live)
                        ChangeCodeModel.Step.Confirm -> SetupBlock(vm, R.string.settings_code_confirm_title, R.string.settings_code_confirm_body, live)
                    }
                }
            }
            Spacer(
                Modifier
                    .weight(1f)
                    .heightIn(min = 12.dp),
            )
            NumericKeypad(
                onDigit = vm::type,
                onDelete = vm::deleteLast,
                enabled = !vm.isThrottled,
                modifier = Modifier
                    .u2Bottom(56.dp)
                    .testTag(ChangeCodeTags.KEYPAD),
            )
        }
    }
}

/** Step 1, the 3.2 composition: lock badge, centred title, dots, message (turning Face ID on: the Face ID glyph and its own title). */
@Composable
private fun CurrentBlock(vm: ChangeCodeModel, live: Boolean) {
    val colors = U2Theme.colors
    val enablesBiometrics = vm.purpose == ChangeCodeModel.Purpose.EnableBiometrics
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.padding(top = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier
                    .size(U2Size.lockIcon)
                    .background(colors.accent900, CircleShape)
                    .u2InnerStroke(colors.accent800, 1.dp, CircleShape)
                    .then(if (live) Modifier.testTag(ChangeCodeTags.BADGE) else Modifier)
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                if (enablesBiometrics) {
                    FaceIdGlyph(size = 24.dp, lineWidth = 1.8f, color = colors.accent300)
                } else {
                    PhosphorIcon(PhosphorGlyph.LockSimple, size = 24.dp, tint = colors.accent300)
                }
            }
            U2DisplayText(
                if (enablesBiometrics) R.string.android_biometric_enable_title else R.string.settings_code_current_title,
                U2TextStyles.displayTitleXS,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = U2Spacing.screenGutterL)
                    .then(if (live) Modifier.testTag(ChangeCodeTags.TITLE) else Modifier),
            )
        }
        DotsAndMessage(vm, live, Modifier.padding(top = 36.dp))
    }
}

/** Steps 2 and 3, the 2.2 composition: Doto title (2 lines), paragraph, dots. */
@Composable
private fun SetupBlock(vm: ChangeCodeModel, @StringRes title: Int, @StringRes body: Int, live: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = U2Spacing.screenGutterL, top = 18.dp, end = U2Spacing.screenGutterL),
            verticalArrangement = Arrangement.spacedBy(U2Spacing.titleToBodyGap),
        ) {
            SetupTitle(
                title,
                U2TextStyles.displayTitleM.balanced,
                modifier = if (live) Modifier.testTag(ChangeCodeTags.TITLE) else Modifier,
            )
            U2Text(stringResource(body), U2TextStyles.body, color = U2Theme.colors.textSecondary)
        }
        DotsAndMessage(vm, live, Modifier.padding(top = 44.dp))
    }
}

@Composable
private fun DotsAndMessage(vm: ChangeCodeModel, live: Boolean, modifier: Modifier = Modifier) {
    val fade = U2Transitions.fade()
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CodeDots(
            filled = vm.entered.length,
            appearance = vm.appearance,
            shakeTrigger = vm.shakeTrigger,
            modifier = if (live) Modifier.testTag(ChangeCodeTags.DOTS) else Modifier,
        )
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .fillMaxWidth()
                .heightIn(min = 42.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            AnimatedContent(
                targetState = vm.message,
                transitionSpec = { fade.enter togetherWith fade.exit },
                contentAlignment = Alignment.TopCenter,
                label = "changeCodeMessage",
            ) { message ->
                if (message != null) {
                    Box(Modifier.then(u2TransitionBlur(fade))) {
                        MessageView(message, vm, if (live) Modifier.testTag(ChangeCodeTags.MESSAGE) else Modifier)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageView(message: ChangeCodeModel.Message, vm: ChangeCodeModel, modifier: Modifier) {
    when (message) {
        ChangeCodeModel.Message.WrongPlain -> Sentence(stringResource(R.string.settings_code_wrong), modifier)
        is ChangeCodeModel.Message.Wrong -> {
            val duration = SettingsFormat.duration(message.delaySeconds, currentAppLocale())
            Sentence(u2Plural(R.plurals.settings_code_wrong_warning, message.attemptsLeft, message.attemptsLeft, duration), modifier)
        }
        is ChangeCodeModel.Message.Throttled -> U2TimeView { now ->
            val remaining = java.time.Duration.between(now, message.until).toKotlinDuration()
            val ended = !remaining.isPositive()
            LaunchedEffect(ended) { if (ended) vm.throttleEnded() }
            Sentence(stringResource(R.string.settings_code_throttled, U2Format.minutesSeconds(remaining)), modifier)
        }
        ChangeCodeModel.Message.Weak -> Sentence(stringResource(R.string.settings_code_weak), modifier)
        ChangeCodeModel.Message.SameAsCurrent -> Sentence(stringResource(R.string.settings_code_same), modifier)
        ChangeCodeModel.Message.Mismatch -> Sentence(stringResource(R.string.settings_code_mismatch), modifier)
        ChangeCodeModel.Message.Failed -> Sentence(stringResource(R.string.settings_code_failed), modifier)
        ChangeCodeModel.Message.Unavailable -> Sentence(stringResource(R.string.settings_code_unavailable), modifier)
        ChangeCodeModel.Message.Unusable -> Sentence(stringResource(R.string.settings_code_unusable), modifier)
    }
}

@Composable
private fun Sentence(text: String, modifier: Modifier) {
    U2Text(
        text,
        U2TextStyles.calloutL,
        color = U2Theme.colors.danger,
        textAlign = TextAlign.Center,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
