package com.syconx.us2us.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.syconx.us2us.domain.DeviceTransferFailure
import com.syconx.us2us.domain.MessengerException
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.CenteredTitleHeader
import com.syconx.us2us.ui.components.DotPattern
import com.syconx.us2us.ui.components.DottedMeter
import com.syconx.us2us.ui.components.SpinningGlyph
import com.syconx.us2us.ui.components.U2Card
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.env.KeepScreenAwakeForTransfer
import com.syconx.us2us.ui.env.LocalExternalActivityGuard
import com.syconx.us2us.ui.env.LocalFeatureScreens
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.env.U2Haptics
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.env.u2Top
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.nav.LocalRouter
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Test tags of the transfer (the iOS accessibility identifiers). */
object DeviceTransferTags {
    const val SEND_BACK = "transfer.send.back"
    const val SEND_CONTINUE = "transfer.send.continue"
    const val SEND_DONE = "transfer.send.done"
    const val SEND_FAILED = "transfer.send.failed"
    const val RECEIVE_BACK = "transfer.receive.back"
    const val RECEIVE_CODE = "transfer.receive.code"
    const val RECEIVE_FAILED = "transfer.receive.failed"
    const val RESTORE_CARD = "transfer.restore.card"
    const val RESTORE_START_OVER = "restore.action.startOver"
    const val STATUS_TITLE = "transfer.status.title"
    const val ACTION = "transfer.action"
}

/**
 * The old phone's side of “Yeni telefona taşı” (PROTOCOL.md §5.6): what moves and what does not →
 * the secret code → the code on the new phone → sent.
 */
@Stable
class DeviceTransferSendModel(private val messenger: MessengerService, private val scope: CoroutineScope) {
    sealed interface Stage {
        data object Intro : Stage
        data object Code : Stage
        data object Scanning : Stage
        data class Sending(val fraction: Double) : Stage
        data object Done : Stage
        data class Failed(val failure: DeviceTransferFailure) : Stage
    }

    var stage by mutableStateOf<Stage>(Stage.Intro)
        private set

    /** The window's haptics (the screen sets them). */
    var haptics: U2Haptics = U2Haptics.None

    private var sending: Job? = null

    fun start() {
        stage = Stage.Code
    }

    /** The right code was entered: on to the new phone's. Also “Yeniden dene”. */
    fun scan() {
        stage = Stage.Scanning
    }

    /** The code page's or the scanner's way back. */
    fun backToIntro() {
        stage = Stage.Intro
    }

    /** The code on the new phone was read. */
    fun send(invitation: String) {
        stage = Stage.Sending(0.0)
        sending = scope.launch {
            try {
                messenger.sendAccount(invitation) { fraction ->
                    if (stage is Stage.Sending) stage = Stage.Sending(fraction)
                }
                haptics.success()
                stage = Stage.Done
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                haptics.error()
                stage = Stage.Failed((failure as? MessengerException.DeviceTransfer)?.failure ?: DeviceTransferFailure.Interrupted)
            }
        }
    }

    /** The app left the foreground (it locks): nothing goes on behind the lock. */
    fun appLeft() {
        sending?.cancel()
        sending = null
        if (stage != Stage.Done) stage = Stage.Intro
    }
}

@Composable
internal fun DeviceTransferSendView(modifier: Modifier = Modifier) {
    val router = LocalRouter.current
    val haptics = LocalHaptics.current
    val flow = rememberSettingsFlow()
    val scope = rememberCoroutineScope()
    val vm = remember { DeviceTransferSendModel(flow.messenger, scope) }
    SideEffect { vm.haptics = haptics }

    val guard = LocalExternalActivityGuard.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, vm) {
        val observer = LifecycleEventObserver { _, event ->
            // The camera permission's own activity in front is not leaving (a dialog over the app: only a home press stops it).
            if (event == Lifecycle.Event.ON_STOP && (!guard.isActive || guard.onlyDialogs)) vm.appLeft()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val stage = vm.stage
    // Everything moves while the app is open: the screen stays on until it has.
    KeepScreenAwakeForTransfer(stage is DeviceTransferSendModel.Stage.Sending)
    BackHandler(enabled = stage is DeviceTransferSendModel.Stage.Code || stage is DeviceTransferSendModel.Stage.Scanning || stage is DeviceTransferSendModel.Stage.Failed) {
        vm.backToIntro()
    }

    val fade = U2Transitions.fade()
    AnimatedContent(
        targetState = stage,
        transitionSpec = { fade.enter togetherWith fade.exit },
        // The meter moves without the page fading.
        contentKey = { it::class },
        modifier = modifier.fillMaxSize(),
        label = "transferSend",
    ) { current ->
        when (current) {
            DeviceTransferSendModel.Stage.Intro -> Intro(onBack = router::pop, onContinue = vm::start)
            DeviceTransferSendModel.Stage.Code -> ChangeCodeScreen(
                ChangeCodeModel.Purpose.ConfirmTransfer,
                onConfirmed = vm::scan,
                onBack = vm::backToIntro,
            )
            DeviceTransferSendModel.Stage.Scanning -> LocalFeatureScreens.current.DeviceTransferScan(
                onCode = vm::send,
                onDismiss = vm::backToIntro,
                modifier = Modifier,
            )
            is DeviceTransferSendModel.Stage.Sending -> DeviceTransferStatus(
                title = stringResource(R.string.settings_transfer_send_sending),
                message = stringResource(R.string.settings_transfer_send_sending_body),
                // Read from the model: `current` is the stage the page faded in with.
                progress = (vm.stage as? DeviceTransferSendModel.Stage.Sending)?.fraction ?: current.fraction,
            )
            DeviceTransferSendModel.Stage.Done -> DeviceTransferStatus(
                glyph = PhosphorGlyph.CheckCircle,
                title = stringResource(R.string.settings_transfer_send_done_title),
                message = stringResource(R.string.settings_transfer_send_done_body),
                actionTitle = stringResource(R.string.common_close),
                onAction = router::pop,
                tag = DeviceTransferTags.SEND_DONE,
            )
            is DeviceTransferSendModel.Stage.Failed -> DeviceTransferStatus(
                glyph = PhosphorGlyph.WarningCircle,
                title = stringResource(R.string.settings_transfer_failed_title),
                message = stringResource(current.failure.message),
                actionTitle = stringResource(R.string.settings_transfer_retry),
                onAction = vm::scan,
                tag = DeviceTransferTags.SEND_FAILED,
            )
        }
    }
}

@Composable
private fun Intro(onBack: () -> Unit, onContinue: () -> Unit) {
    U2Screen {
        Column(Modifier.fillMaxSize()) {
            CenteredTitleHeader(
                title = stringResource(R.string.settings_transfer_send_title),
                leading = {
                    U2GlassIconButton(
                        glyph = PhosphorGlyph.CaretLeft,
                        contentDescription = stringResource(R.string.common_a11y_back),
                        onClick = onBack,
                        glyphSize = 22.dp,
                        modifier = Modifier.testTag(DeviceTransferTags.SEND_BACK),
                    )
                },
            )
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = U2Spacing.screenGutterL)
                    .padding(top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionGap),
            ) {
                DeviceTransferSteps(
                    listOf(
                        stringResource(R.string.settings_transfer_send_step1),
                        stringResource(R.string.settings_transfer_send_step2),
                        stringResource(R.string.settings_transfer_send_step3),
                    ),
                )
                DeviceTransferNote(
                    title = stringResource(R.string.settings_transfer_note_title),
                    message = stringResource(R.string.settings_transfer_send_note),
                )
            }
            U2PrimaryButton(
                title = stringResource(R.string.settings_transfer_send_continue),
                onClick = onContinue,
                modifier = Modifier
                    .padding(horizontal = U2Spacing.screenGutterL)
                    .u2Bottom(U2Spacing.ctaBottomOnboarding)
                    .testTag(DeviceTransferTags.SEND_CONTINUE),
            )
        }
    }
}

internal val DeviceTransferFailure.message: Int
    get() = when (this) {
        DeviceTransferFailure.NoLocalNetwork -> R.string.settings_transfer_failed_nolocalnetwork
        DeviceTransferFailure.NoSpace -> R.string.settings_transfer_failed_nospace
        DeviceTransferFailure.Unreachable -> R.string.settings_transfer_failed_unreachable
        DeviceTransferFailure.NotPrepared -> R.string.settings_transfer_failed_notprepared
        DeviceTransferFailure.Interrupted -> R.string.settings_transfer_failed_interrupted
    }

/** Numbered steps of either side. */
@Composable
internal fun DeviceTransferSteps(steps: List<String>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        steps.forEachIndexed { index, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                U2Text(
                    "${index + 1}",
                    U2TextStyles.monoMeta,
                    color = U2Theme.colors.accent300,
                    modifier = Modifier.alignByBaseline().clearAndSetSemantics {},
                )
                U2Text(step, U2TextStyles.body, color = U2Theme.colors.text, modifier = Modifier.alignByBaseline())
            }
        }
    }
}

@Composable
internal fun DeviceTransferNote(title: String, message: String, modifier: Modifier = Modifier) {
    U2Card(modifier) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            U2Text(title, U2TextStyles.noteTitle, color = U2Theme.colors.text)
            U2Text(message, U2TextStyles.captionTight, color = U2Theme.colors.textSecondary)
        }
    }
}

/** A moment of the transfer: a spinner and a meter, or how it ended. */
@Composable
internal fun DeviceTransferStatus(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    glyph: PhosphorGlyph? = null,
    progress: Double? = null,
    actionTitle: String? = null,
    onAction: () -> Unit = {},
    tag: String = DeviceTransferTags.STATUS_TITLE,
) {
    U2Screen(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .u2Top(U2Spacing.headerTopButtons)
                .padding(horizontal = U2Spacing.screenGutterL)
                .u2Bottom(U2Spacing.ctaBottomOnboarding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Spacer(Modifier.weight(1f))
            if (glyph != null) {
                PhosphorIcon(
                    glyph,
                    size = 44.dp,
                    tint = if (glyph == PhosphorGlyph.WarningCircle) U2Theme.colors.danger else U2Theme.colors.accent300,
                )
            } else {
                SpinningGlyph(size = 28.dp, tint = U2Theme.colors.accent300)
            }
            U2Text(
                title,
                U2TextStyles.displayTitleXS,
                color = U2Theme.colors.text,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }.testTag(tag),
            )
            U2Text(message, U2TextStyles.body, color = U2Theme.colors.textSecondary, textAlign = TextAlign.Center)
            if (progress != null) {
                DottedMeter(progress = progress.toFloat(), pattern = DotPattern.Progress, modifier = Modifier.padding(top = 8.dp))
                U2Text(
                    java.text.NumberFormat.getPercentInstance(currentAppLocale()).format(progress),
                    U2TextStyles.monoMeta,
                    color = U2Theme.colors.textSecondary,
                )
            }
            Spacer(Modifier.weight(1f))
            if (actionTitle != null) {
                U2PrimaryButton(title = actionTitle, onClick = onAction, modifier = Modifier.testTag(DeviceTransferTags.ACTION))
            }
        }
    }
}
