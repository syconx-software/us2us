package com.syconx.us2us.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.syconx.us2us.domain.DeviceTransferDeliveryInfo
import com.syconx.us2us.domain.DeviceTransferEvent
import com.syconx.us2us.domain.DeviceTransferFailure
import com.syconx.us2us.domain.DeviceTransferIncoming
import com.syconx.us2us.domain.MessengerException
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.CenteredTitleHeader
import com.syconx.us2us.ui.components.QrCodeCard
import com.syconx.us2us.ui.components.SpinningGlyph
import com.syconx.us2us.ui.components.U2ButtonLabel
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.env.KeepScreenAwakeForTransfer
import com.syconx.us2us.ui.env.LocalAppModel
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.env.U2Haptics
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Spacing
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The new phone's side of the transfer (PROTOCOL.md §5.6): its code on screen until the old phone
 * has read it and sent the account; from there on it is the restore of 7.3, with a new secret code
 * at the end.
 */
@Stable
class DeviceTransferReceiveModel(
    private val messenger: MessengerService,
    private val scope: CoroutineScope,
    /** An invitation is shown for at most this long; then a new one takes its place. */
    private val lifetime: Duration = 10.minutes,
) {
    sealed interface Stage {
        data object Preparing : Stage
        data class Waiting(val invitation: String) : Stage
        data class Receiving(val fraction: Double) : Stage
        data class Delivered(val delivery: DeviceTransferDeliveryInfo) : Stage
        data class Failed(val failure: DeviceTransferFailure) : Stage
    }

    var stage by mutableStateOf<Stage>(Stage.Preparing)
        private set

    /** The window's haptics (the screen sets them). */
    var haptics: U2Haptics = U2Haptics.None

    private var incoming: DeviceTransferIncoming? = null
    private var waiting: Job? = null
    private var expiry: Job? = null

    fun start() {
        stop()
        stage = Stage.Preparing
        waiting = scope.launch {
            val opened = try {
                messenger.startReceivingAccount()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                stage = Stage.Failed((failure as? MessengerException.DeviceTransfer)?.failure ?: DeviceTransferFailure.Unreachable)
                return@launch
            }
            if (!isActive) {
                opened.stop()
                return@launch
            }
            incoming = opened
            val invitation = opened.invitation
            opened.onEvent = { event ->
                when (event) {
                    DeviceTransferEvent.Started -> {
                        expiry?.cancel()
                        stage = Stage.Receiving(0.0)
                    }
                    is DeviceTransferEvent.Progress -> if (stage is Stage.Receiving) stage = Stage.Receiving(event.fraction)
                    DeviceTransferEvent.Interrupted -> show(invitation)
                    DeviceTransferEvent.NoSpace -> {
                        stop()
                        stage = Stage.Failed(DeviceTransferFailure.NoSpace)
                    }
                }
            }
            show(invitation)
            val delivery = try {
                opened.waitForDelivery()
            } catch (_: CancellationException) {
                // Ended on purpose: a new invitation, or the screen went away.
                return@launch
            }
            expiry?.cancel()
            haptics.success()
            opened.stop()
            stage = Stage.Delivered(delivery)
        }
    }

    /** The code is up, and runs out in a while unless a phone connects. */
    private fun show(invitation: String) {
        stage = Stage.Waiting(invitation)
        expiry?.cancel()
        expiry = scope.launch {
            delay(lifetime)
            if (stage is Stage.Waiting) start()
        }
    }

    /** Ends the invitation; a file already handed on stays until [finish]. */
    fun stop() {
        waiting?.cancel()
        waiting = null
        expiry?.cancel()
        expiry = null
        incoming?.stop()
    }

    /** The screen goes away: nothing of the transfer stays on the phone. */
    fun finish() {
        stop()
        incoming?.removeFiles()
        incoming = null
    }

    /** The app left the foreground: a new invitation on the way back (the sockets do not survive it). */
    fun appLeft() {
        if (stage !is Stage.Delivered) stop()
    }

    fun appReturned() {
        if (stage !is Stage.Delivered && waiting == null) start()
    }
}

@Composable
internal fun DeviceTransferReceiveView(onCancel: () -> Unit, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val vm = remember { DeviceTransferReceiveModel(app.messenger, scope) }
    SideEffect { vm.haptics = haptics }
    // The code stays readable for as long as it takes to fetch the old phone, and what follows runs in the open app.
    KeepScreenAwakeForTransfer(true)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, vm) {
        // Registering replays ON_START: that is what brings the first invitation up.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.appReturned()
                Lifecycle.Event.ON_STOP -> vm.appLeft()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            vm.finish()
        }
    }

    val fade = U2Transitions.fade()
    AnimatedContent(
        targetState = vm.stage,
        transitionSpec = { fade.enter togetherWith fade.exit },
        // The meter moves without the page fading.
        contentKey = { it::class },
        modifier = modifier.fillMaxSize(),
        label = "transferReceive",
    ) { current ->
        when (current) {
            DeviceTransferReceiveModel.Stage.Preparing -> DeviceTransferStatus(
                title = stringResource(R.string.settings_transfer_receive_title),
                message = stringResource(R.string.settings_transfer_receive_preparing),
            )
            is DeviceTransferReceiveModel.Stage.Waiting -> Code(current.invitation, onCancel)
            is DeviceTransferReceiveModel.Stage.Receiving -> DeviceTransferStatus(
                title = stringResource(R.string.settings_transfer_receive_receiving),
                message = stringResource(R.string.settings_transfer_send_sending_body),
                // Read from the model: `current` is the stage the page faded in with.
                progress = (vm.stage as? DeviceTransferReceiveModel.Stage.Receiving)?.fraction ?: current.fraction,
            )
            is DeviceTransferReceiveModel.Stage.Delivered -> RestoreBackupView(
                onClose = onCancel,
                onFinished = onFinished,
                opensPickerOnAppear = false,
                transferred = current.delivery,
            )
            is DeviceTransferReceiveModel.Stage.Failed -> DeviceTransferStatus(
                glyph = PhosphorGlyph.WarningCircle,
                title = stringResource(R.string.settings_transfer_failed_title),
                message = stringResource(
                    if (current.failure == DeviceTransferFailure.NoSpace) R.string.settings_transfer_failed_nospace_here else current.failure.message,
                ),
                actionTitle = stringResource(R.string.settings_transfer_retry),
                onAction = vm::start,
                tag = DeviceTransferTags.RECEIVE_FAILED,
            )
        }
    }
}

@Composable
private fun Code(invitation: String, onCancel: () -> Unit) {
    U2Screen {
        Column(Modifier.fillMaxSize()) {
            CenteredTitleHeader(
                title = stringResource(R.string.settings_transfer_receive_title),
                leading = {
                    U2GlassIconButton(
                        glyph = PhosphorGlyph.CaretLeft,
                        contentDescription = stringResource(R.string.common_a11y_back),
                        onClick = onCancel,
                        glyphSize = 22.dp,
                        modifier = Modifier.testTag(DeviceTransferTags.RECEIVE_BACK),
                    )
                },
            )
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = U2Spacing.screenGutterL)
                    .padding(top = 20.dp)
                    .u2Bottom(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionGap),
            ) {
                QrCodeCard(
                    payload = invitation,
                    label = stringResource(R.string.settings_transfer_receive_a11y_code),
                    modifier = Modifier.testTag(DeviceTransferTags.RECEIVE_CODE),
                )
                DeviceTransferSteps(
                    listOf(
                        stringResource(R.string.settings_transfer_receive_step1),
                        stringResource(R.string.settings_transfer_receive_step2),
                    ),
                )
                DeviceTransferNote(
                    title = stringResource(R.string.settings_transfer_note_title),
                    message = stringResource(R.string.settings_transfer_receive_note),
                )
            }
        }
    }
}

/** What stands in for 7.3's file and password when the account came from the old phone. */
@Composable
internal fun TransferredAccountCard(stage: RestoreBackupModel.Stage, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val isProblem = stage is RestoreBackupModel.Stage.Failed ||
        stage == RestoreBackupModel.Stage.WrongPassword ||
        stage == RestoreBackupModel.Stage.Unverified
    val isOpen = stage is RestoreBackupModel.Stage.Restoring || stage is RestoreBackupModel.Stage.Done
    val caption = when {
        isOpen -> R.string.settings_transfer_restore_ready
        stage == RestoreBackupModel.Stage.Unverified -> R.string.settings_transfer_restore_unverified
        isProblem -> R.string.settings_transfer_restore_failed
        else -> R.string.settings_restore_checking
    }
    BackupFileCard(
        name = stringResource(R.string.settings_transfer_restore_arrived),
        caption = stringResource(caption),
        captionColor = if (isProblem) colors.danger else colors.textSecondary,
        modifier = modifier.testTag(DeviceTransferTags.RESTORE_CARD),
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            when {
                isProblem -> PhosphorIcon(PhosphorGlyph.WarningCircle, size = 22.dp, tint = colors.danger)
                isOpen -> PhosphorIcon(PhosphorGlyph.CheckCircle, size = 22.dp, weight = PhosphorWeight.Fill, tint = colors.online)
                else -> SpinningGlyph(size = 20.dp, tint = colors.textSecondary)
            }
        }
    }
}

/** 7.3's button until the account is open: nothing to pick or type, only to try again. */
@Composable
internal fun TransferredRestoreAction(stage: RestoreBackupModel.Stage, onRetry: () -> Unit, onStartOver: () -> Unit) {
    when (stage) {
        RestoreBackupModel.Stage.Unverified -> U2PrimaryButton(
            title = stringResource(R.string.settings_transfer_retry),
            onClick = onRetry,
            modifier = Modifier.testTag(RestoreTags.ACTION_SUBMIT),
        )
        is RestoreBackupModel.Stage.Failed, RestoreBackupModel.Stage.WrongPassword -> U2PrimaryButton(
            title = stringResource(R.string.settings_transfer_retry),
            onClick = onStartOver,
            modifier = Modifier.testTag(DeviceTransferTags.RESTORE_START_OVER),
        )
        else -> U2PrimaryButton(onClick = {}) {
            U2ButtonLabel(stringResource(R.string.settings_restore_checking), isWorking = true)
        }
    }
}
