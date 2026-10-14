package com.syconx.us2us.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.ConfirmationTags
import com.syconx.us2us.ui.components.EraseConfirmation
import com.syconx.us2us.ui.components.EraseScope
import com.syconx.us2us.ui.components.OptionCard
import com.syconx.us2us.ui.components.OptionCardTone
import com.syconx.us2us.ui.components.U2ButtonLabel
import com.syconx.us2us.ui.components.U2SecondaryButton
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.components.text.U2DisplayText
import com.syconx.us2us.ui.env.U2Haptics
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 7.1 › Hesap › “Her şeyi sil” (the account is deleted in the app).
 *
 * 1. “Hesabın silinsin mi?” › “Hesabı ve her şeyi sil”: the account is deleted on the relay first,
 *    authenticated, while the danger button spins.
 * 2. The relay confirmed: the sheet goes down, then everything on this phone is erased
 *    ([com.syconx.us2us.ui.env.U2AppModel.eraseEverything], with the preview folder) and the app
 *    cross-fades to 1.1. Never erased under a sheet that is still on screen.
 * 3. The relay did not confirm (offline, a timeout, a server error): nothing is erased (erasing
 *    first would destroy the only credentials that can delete the account). The same sheet
 *    cross-fades to “Hesap silinemedi.” with “Tekrar dene” and “Yalnızca bu telefondan sil” (the
 *    account then stays on the relay until it is removed after 180 days without a connection,
 *    PROTOCOL §4.10).
 *
 * [pause]: how long the decided button keeps spinning before the sheet goes. [armDelay]: the failure
 * step's cross-fade (0 in tests). [scope] runs the arming wait.
 */
@Stable
class EraseAccountModel(
    private val messenger: MessengerService,
    private val scope: CoroutineScope,
    private val pause: Duration = 250.milliseconds,
    private val armDelay: Duration = U2Motion.Duration.screen.toDouble().seconds,
) {
    enum class Step {
        /** “Hesabın silinsin mi?” */
        Confirm,

        /** “Hesap silinemedi.”: try again, or erase this phone only. */
        Failed,
    }

    /** What the sheet's dismissal erases. */
    enum class Erasure {
        /** The relay deleted the account: this phone follows. */
        AccountDeleted,

        /** The user leaves the account on the relay. */
        ThisPhoneOnly,
    }

    var isPresented by mutableStateOf(false)

    var step by mutableStateOf(Step.Confirm)
        private set

    /** The relay request runs, or the erase is decided: the button spins, the other controls ignore taps and the sheet cannot be swiped away. */
    var isWorking by mutableStateOf(false)
        private set

    /** Decided; performed once the sheet has gone. */
    var erasure by mutableStateOf<Erasure?>(null)
        private set

    /** The failure step's controls ignore taps until its cross-fade has finished (no double tap through the danger button onto the card). */
    var isArmed by mutableStateOf(false)
        private set

    /** Failed attempts in this presentation (each one is announced). */
    var failures by mutableIntStateOf(0)
        private set

    /** The window's haptics (the screen sets them). */
    var haptics: U2Haptics = U2Haptics.None

    private var armJob: Job? = null

    /** The 7.1 row: the sheet rises on the confirmation. */
    fun present() {
        if (isPresented || isWorking) return
        reset()
        isPresented = true
    }

    /** “Hesabı ve her şeyi sil”, and “Tekrar dene” after a failure. */
    suspend fun deleteAccount() {
        if (!isPresented || isWorking || erasure != null || !(step == Step.Confirm || isArmed)) return
        isWorking = true
        try {
            messenger.deleteAccountOnRelay()
        } catch (cancelled: CancellationException) {
            isWorking = false
            throw cancelled
        } catch (_: Exception) {
            isWorking = false
            failures += 1
            haptics.error()
            if (step != Step.Failed) {
                step = Step.Failed
                arm()
            }
            return
        }
        decide(Erasure.AccountDeleted)
    }

    /** “Yalnızca bu telefondan sil” (after a failure only). */
    suspend fun eraseThisPhoneOnly() {
        if (!isPresented || step != Step.Failed || !isArmed || isWorking || erasure != null) return
        isWorking = true
        decide(Erasure.ThisPhoneOnly)
    }

    /** “Vazgeç”: nothing happens, the sheet goes down. */
    fun cancel() {
        if (isWorking) return
        isPresented = false
    }

    /** The sheet has gone (also after a swipe down): erase now if that was decided, otherwise start over for the next time. */
    suspend fun sheetDidDismiss(erase: suspend () -> Unit) {
        if (erasure == null) {
            reset()
            return
        }
        erase()
        reset()
    }

    private suspend fun decide(erasure: Erasure) {
        this.erasure = erasure
        haptics.error()
        delay(pause)
        isPresented = false
    }

    private fun arm() {
        isArmed = false
        armJob?.cancel()
        armJob = scope.launch {
            delay(armDelay)
            if (step == Step.Failed) isArmed = true
        }
    }

    private fun reset() {
        armJob?.cancel()
        armJob = null
        step = Step.Confirm
        isWorking = false
        erasure = null
        isArmed = false
        failures = 0
    }

    /** The failure step at once, without animation (the debug harness). */
    fun showFailureForHarness() {
        step = Step.Failed
        isArmed = true
    }
}

/**
 * The 7.1 floating sheet: “Hesabın silinsin mi?” and, when the relay could not delete the account,
 * “Hesap silinemedi.” in the same sheet: the contents cross-fade and the height follows softly
 * (0.5 s, the design curve), as in 3.3.
 *
 * The failure step follows 3.3's layout: the text block, an option card (danger, “Yalnızca bu
 * telefondan sil”) and the C2 “Tekrar dene”, then the C3 “Vazgeç”.
 */
@Composable
internal fun EraseAccountSheet(model: EraseAccountModel, scope: CoroutineScope, modifier: Modifier = Modifier) {
    val screen = U2Motion.resolve(U2Motion.screen, LocalReduceMotion.current)
    val fade = U2Transitions.fade(U2Motion.screen)
    AnimatedContent(
        targetState = model.step,
        transitionSpec = { (fade.enter togetherWith fade.exit).using(SizeTransform(clip = false) { _, _ -> screen.spec() }) },
        contentAlignment = Alignment.TopCenter,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 28.dp),
        label = "eraseStep",
    ) { step ->
        // Only the current step is live (touches, accessibility, UI tests).
        val live = step == model.step
        when (step) {
            EraseAccountModel.Step.Confirm -> EraseConfirmation(
                onConfirm = { scope.launch { model.deleteAccount() } },
                onCancel = model::cancel,
                scope = EraseScope.Account,
                isWorking = model.isWorking && model.step == EraseAccountModel.Step.Confirm,
                tags = if (live) ConfirmationTags(SettingsTags.CONFIRM_DESTRUCTIVE, SettingsTags.CONFIRM_CANCEL) else null,
            )
            EraseAccountModel.Step.Failed -> EraseFailure(model, scope, live)
        }
    }
}

@Composable
private fun EraseFailure(model: EraseAccountModel, scope: CoroutineScope, live: Boolean) {
    // The retry spins until the sheet has gone; “this phone only” just stills every control.
    val retrying = model.isWorking && model.erasure != EraseAccountModel.Erasure.ThisPhoneOnly
    fun Modifier.tag(tag: String): Modifier = if (live) testTag(tag) else this
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        // Text block: pad 6 4 0 4, gap 10 (as 3.3).
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 6.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            U2DisplayText(R.string.settings_erase_failed_title, U2TextStyles.displaySheetTitle.balanced)
            U2Text(stringResource(R.string.settings_erase_failed_body), U2TextStyles.sheetBody, color = U2Theme.colors.textSecondary)
        }
        OptionCard(
            glyph = PhosphorGlyph.Trash,
            title = stringResource(R.string.settings_erase_thisphone_title),
            description = stringResource(R.string.settings_erase_thisphone_body),
            onClick = { if (!model.isWorking) scope.launch { model.eraseThisPhoneOnly() } },
            tone = OptionCardTone.Danger,
            modifier = Modifier.tag(SettingsTags.ERASE_THIS_PHONE),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            U2SecondaryButton(
                onClick = { scope.launch { model.deleteAccount() } },
                enabled = !model.isWorking,
                modifier = Modifier.tag(SettingsTags.ERASE_RETRY),
            ) {
                U2ButtonLabel(
                    stringResource(if (retrying) R.string.settings_erase_confirm_working else R.string.settings_erase_retry),
                    isWorking = retrying,
                )
            }
            U2TextButton(
                title = stringResource(R.string.common_cancel),
                onClick = model::cancel,
                tone = U2TextButtonTone.Accent,
                height = U2Size.textButtonS,
                enabled = !model.isWorking,
                modifier = Modifier.tag(SettingsTags.ERASE_CANCEL),
            )
        }
    }
}
