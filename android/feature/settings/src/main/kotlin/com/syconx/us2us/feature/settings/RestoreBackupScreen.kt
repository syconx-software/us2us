package com.syconx.us2us.feature.settings

import android.content.ActivityNotFoundException
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.BackupFileError
import com.syconx.us2us.domain.BackupFileInfo
import com.syconx.us2us.domain.BackupOpenResult
import com.syconx.us2us.domain.MessengerException
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.RestoreProgress
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.CenteredTitleHeader
import com.syconx.us2us.ui.components.ConfirmationTags
import com.syconx.us2us.ui.components.DestructiveConfirmation
import com.syconx.us2us.ui.components.DotPattern
import com.syconx.us2us.ui.components.DottedMeter
import com.syconx.us2us.ui.components.PasswordField
import com.syconx.us2us.ui.components.PasswordFieldTrailing
import com.syconx.us2us.ui.components.SpinningGlyph
import com.syconx.us2us.ui.components.U2ButtonLabel
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.U2SecondaryButton
import com.syconx.us2us.ui.components.text.GreedyParagraph
import com.syconx.us2us.ui.components.u2CardPress
import com.syconx.us2us.ui.env.LocalAppModel
import com.syconx.us2us.ui.env.LocalHarnessState
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.env.U2Haptics
import com.syconx.us2us.ui.env.rememberGuardedLauncher
import com.syconx.us2us.ui.env.rememberU2Keyboard
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.l10n.U2Format
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.u2Spec
import com.syconx.us2us.ui.motion.u2TransitionBlur
import com.syconx.us2us.ui.nav.LocalIsLayerVisible
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.sheet.U2Sheet
import com.syconx.us2us.ui.sheet.U2SheetStyle
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import com.syconx.us2us.ui.type.textStyle
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * State of 7.3 · Yedekten geri yükle.
 *
 * pick file (system document picker) → backup password → restoring (keys → messages → media,
 * percent + ETA) → done (→ the host continues with a new secret code: 2.2 ×2 → 2.3 → 4.1). Wrong
 * password: danger ring + “Parola yanlış”. Decryption happens only on the device.
 */
@Stable
class RestoreBackupModel(private val messenger: MessengerService, private val scope: CoroutineScope) {
    sealed interface Stage {
        /** No file yet. */
        data object Pick : Stage

        /** Reading the picked file's header. */
        data object Inspecting : Stage

        /** The file is fine; waiting for its password. */
        data object Password : Stage

        /** Checking the password. */
        data object Checking : Stage
        data object WrongPassword : Stage

        /** The password was right, but the server could not be asked whether the backup still works (no connection): try again. */
        data object Unverified : Stage
        data class Restoring(val progress: RestoreProgress) : Stage
        data class Done(val progress: RestoreProgress) : Stage

        /** The picked file can't be restored. */
        data class Failed(val error: BackupFileError) : Stage
    }

    var stage by mutableStateOf<Stage>(Stage.Pick)
        private set
    var file by mutableStateOf<BackupFileInfo?>(null)
        private set

    private var passwordValue by mutableStateOf("")
    var password: String
        get() = passwordValue
        set(value) {
            val changed = value != passwordValue
            passwordValue = value
            if (stage == Stage.WrongPassword && changed) stage = Stage.Password
        }

    /** The window's haptics (the screen sets them). */
    var haptics: U2Haptics = U2Haptics.None

    private var restoreJob: Job? = null

    val isRestoring: Boolean get() = stage is Stage.Restoring

    val progress: RestoreProgress?
        get() = when (val current = stage) {
            is Stage.Restoring -> current.progress
            is Stage.Done -> current.progress
            else -> null
        }

    /** The password was accepted (restoring or done). */
    val isPasswordAccepted: Boolean get() = progress != null

    /** A live account backup: the account and contacts come back, message history never does (PROTOCOL §5.5), so 7.3 lists only the keys. */
    val includesHistory: Boolean get() = messenger.backupsIncludeHistory

    /** Restoring inside the lock (3.3 › 7.3): finishing setup replaces this phone's vault, and its messages with it. */
    val replacesThisPhone: Boolean get() = messenger.hasAccount.value

    suspend fun pick(uri: URI) {
        stage = Stage.Inspecting
        try {
            val info = messenger.inspectBackup(uri)
            file = info
            password = ""
            stage = Stage.Password
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            haptics.error()
            file = null
            stage = Stage.Failed((failure as? MessengerException.BackupFile)?.error ?: BackupFileError.Unreadable)
        }
    }

    val canSubmitPassword: Boolean
        get() = (stage == Stage.Password || stage == Stage.WrongPassword || stage == Stage.Unverified) && password.isNotEmpty() && file != null

    suspend fun submitPassword() {
        val file = file
        if (!canSubmitPassword || file == null) return
        stage = Stage.Checking
        when (messenger.openBackup(file, password)) {
            BackupOpenResult.Opened -> {
                haptics.success()
                startRestore(file)
            }
            BackupOpenResult.WrongPassword -> {
                haptics.error()
                stage = Stage.WrongPassword
            }
            BackupOpenResult.Unverified -> {
                haptics.error()
                stage = Stage.Unverified
            }
            BackupOpenResult.Damaged -> refuse(BackupFileError.Damaged)
            BackupOpenResult.NoLongerValid -> refuse(BackupFileError.NoLongerValid)
        }
    }

    /** The file can never be restored: the card says why, and another file can be picked. */
    private fun refuse(error: BackupFileError) {
        haptics.error()
        file = null
        password = ""
        stage = Stage.Failed(error)
    }

    private fun startRestore(file: BackupFileInfo) {
        val stream = messenger.restoreBackup(file)
        stage = Stage.Restoring(
            RestoreProgress(
                fraction = 0.0, stage = RestoreProgress.Stage.Keys, messagesDone = 0, messagesTotal = 0, mediaBytes = 0,
                estimatedRemaining = Duration.ZERO, includesHistory = includesHistory,
            ),
        )
        restoreJob = scope.launch {
            var last: RestoreProgress? = null
            try {
                stream.collect { progress ->
                    last = progress
                    stage = Stage.Restoring(progress)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The restore failed; the screen keeps its last state (as iOS: only a cancel ends it).
                return@launch
            }
            val final = last ?: return@launch
            haptics.success()
            stage = Stage.Done(
                final.copy(fraction = 1.0, messagesDone = final.messagesTotal, estimatedRemaining = Duration.ZERO),
            )
        }
    }

    suspend fun cancelRestore() {
        restoreJob?.cancel()
        restoreJob = null
        messenger.cancelRestore()
    }

    /** Debug harness states (`settings:7.3…`). */
    fun applyHarness(state: String) {
        val info = BackupFileInfo(
            uri = URI("file:///dev/null"),
            fileName = DesignFixtures.backupEstimate.fileName,
            byteCount = DesignFixtures.backupEstimate.totalBytes,
            createdAt = DesignFixtures.designBackupDate,
        )
        val demo = DesignFixtures.DEMO_BACKUP_PASSWORD
        when (state) {
            "7.3", "7.3-cancel" -> {
                file = info
                password = demo
                stage = Stage.Restoring(DesignFixtures.restoreProgress)
            }
            "7.3-password" -> file = info
            "7.3-wrong" -> {
                file = info
                password = demo.dropLast(3)
                stage = Stage.WrongPassword
            }
            "7.3-done" -> {
                file = info
                password = demo
                val done = DesignFixtures.restoreProgress
                stage = Stage.Done(
                    done.copy(
                        fraction = 1.0, stage = RestoreProgress.Stage.Media, messagesDone = done.messagesTotal,
                        estimatedRemaining = Duration.ZERO,
                    ),
                )
            }
            else -> return
        }
        if (stage == Stage.Pick) stage = Stage.Password
    }
}

/** Test tags of 7.3 (the iOS accessibility identifiers, plus the layout ids the shots gate on). */
object RestoreTags {
    const val SCREEN = "restore.screen"
    const val CLOSE = "restore.close"
    const val FILE = "restore.file"
    const val FILE_OK = "restore.file.ok"
    const val PICK = "restore.pick"
    const val PASSWORD = "restore.password"
    const val CAPTION = "restore.password.caption"
    const val PROGRESS = "restore.progress"
    const val PERCENT_ROW = "restore.progress.percent"
    const val ETA = "restore.progress.eta"
    const val METER = "restore.progress.meter"
    const val NOTE = "restore.note"
    const val ACTION_PICK = "restore.action.pick"
    const val ACTION_SUBMIT = "restore.action.submit"
    const val ACTION_CANCEL = "restore.action.cancel"
    const val ACTION_CONTINUE = "restore.action.continue"
    const val CANCEL_CONFIRM = "restore.cancel.confirm"
    const val CANCEL_KEEP_GOING = "restore.cancel.keepGoing"

    fun step(stage: RestoreProgress.Stage): String = "restore.step.${stage.name.lowercase(Locale.ROOT)}"
}

/**
 * 7.3 · Yedekten geri yükle.
 *
 * Entry points: 1.1 “Yedekten geri yükle” (new device) and 3.3 “Yedekten geri yükle” (forgot the
 * code); inside settings only the debug harness opens it. It opens the system document picker by
 * itself (`U2PickBackup`: the stand-in file instead). [onFinished] continues with a new secret code
 * on this device (2.2 ×2 → 2.3 → 4.1).
 */
@Composable
internal fun RestoreBackupView(
    onClose: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    opensPickerOnAppear: Boolean = true,
    file: URI? = null,
) {
    val app = LocalAppModel.current
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    val harness = LocalHarnessState.current as? SettingsUiState
    // Covered on a stack (2.2 over it after a restore): never opens the picker.
    val isLayerVisible = LocalIsLayerVisible.current
    val keyboard = rememberU2Keyboard()
    val scope = rememberCoroutineScope()
    val vm = remember { RestoreBackupModel(app.messenger, scope) }
    SideEffect { vm.haptics = haptics }

    val passwordFocus = remember { FocusRequester() }
    var passwordFocused by remember { mutableStateOf(false) }
    var showsCancel by remember { mutableStateOf(false) }
    var closesAfterCancelSheet by remember { mutableStateOf(false) }
    var isCancelling by remember { mutableStateOf(false) }

    fun picked(uri: URI) {
        scope.launch {
            vm.pick(uri)
            if (vm.stage == RestoreBackupModel.Stage.Password) runCatching { passwordFocus.requestFocus() }
        }
    }

    val openDocument = rememberGuardedLauncher(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) picked(URI(uri.toString()))
    }

    // The system document picker for the `.u2u` file (any type: providers do not know ours).
    fun presentPicker() {
        if (app.configuration.pickBackup) {
            picked(DesignFixtures.standInBackupFile(context.cacheDir))
            return
        }
        try {
            openDocument.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            // No document provider on this phone: the card stays as it is.
        }
    }

    fun submitPassword() {
        if (!vm.canSubmitPassword) return
        keyboard.dismiss()
        scope.launch { vm.submitPassword() }
    }

    fun close() {
        if (vm.isRestoring) {
            showsCancel = true
        } else {
            keyboard.dismiss()
            onClose()
        }
    }

    fun confirmCancel() {
        isCancelling = true
        scope.launch {
            vm.cancelRestore()
            // 7.3 closes once the sheet has gone.
            closesAfterCancelSheet = true
            showsCancel = false
        }
    }

    fun closeAfterCancelSheet() {
        if (!closesAfterCancelSheet) return
        closesAfterCancelSheet = false
        isCancelling = false
        onClose()
    }

    LaunchedEffect(vm) {
        val state = harness?.take("7.3")
        if (state != null) {
            vm.applyHarness(state)
            if (state == "7.3-cancel") {
                delay(800)
                showsCancel = true
            }
            return@LaunchedEffect
        }
        // A backup chosen before 7.3 opened (3.3 picks it first, as on iOS) goes straight to its password.
        if (file != null) {
            picked(file)
        } else if (opensPickerOnAppear && isLayerVisible) {
            presentPicker()
        }
    }

    // Back while restoring asks first, as the close button does.
    BackHandler(enabled = vm.isRestoring && !showsCancel) { showsCancel = true }

    U2Screen(modifier.testTag(RestoreTags.SCREEN)) {
        Column(Modifier.fillMaxSize()) {
            CenteredTitleHeader(
                title = stringResource(R.string.settings_restore_title),
                leading = {
                    U2GlassIconButton(
                        glyph = PhosphorGlyph.X,
                        contentDescription = stringResource(R.string.common_a11y_close),
                        onClick = ::close,
                        modifier = Modifier.testTag(RestoreTags.CLOSE),
                    )
                },
            )
            RestoreContent(
                vm = vm,
                passwordFocus = passwordFocus,
                passwordFocused = passwordFocused,
                onPasswordFocus = { passwordFocused = it },
                onPick = ::presentPicker,
                onSubmit = ::submitPassword,
                onCancel = { showsCancel = true },
                onFinished = onFinished,
            )
        }
    }

    U2Sheet(
        isPresented = showsCancel,
        onDismissRequest = { if (!isCancelling) showsCancel = false },
        style = U2SheetStyle.Floating,
        isDismissible = !isCancelling,
        onDismissed = ::closeAfterCancelSheet,
    ) {
        DestructiveConfirmation(
            title = stringResource(R.string.settings_restore_cancel_title),
            message = stringResource(R.string.settings_restore_cancel_body),
            confirmTitle = stringResource(R.string.settings_restore_cancel_action),
            onConfirm = ::confirmCancel,
            onCancel = { showsCancel = false },
            confirmGlyph = null,
            cancelTitle = stringResource(R.string.settings_restore_cancel_keepgoing),
            isWorking = isCancelling,
            tags = ConfirmationTags(RestoreTags.CANCEL_CONFIRM, RestoreTags.CANCEL_KEEP_GOING),
            modifier = Modifier.padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 28.dp),
        )
    }
}

@Composable
private fun RestoreContent(
    vm: RestoreBackupModel,
    passwordFocus: FocusRequester,
    passwordFocused: Boolean,
    onPasswordFocus: (Boolean) -> Unit,
    onPick: () -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onFinished: () -> Unit,
) {
    val rise = U2Transitions.rise()
    val fade = U2Transitions.fade()
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 18.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FileCard(vm, onPick)
            val file = vm.file
            AnimatedVisibility(file != null, enter = rise.enter, exit = rise.exit) {
                Column(Modifier.then(u2TransitionBlur(rise)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val stage = vm.stage
                    PasswordField(
                        text = vm.password,
                        onTextChange = { vm.password = it },
                        trailing = when {
                            vm.isPasswordAccepted -> PasswordFieldTrailing.Accepted
                            stage == RestoreBackupModel.Stage.Checking -> PasswordFieldTrailing.Checking
                            else -> PasswordFieldTrailing.Reveal
                        },
                        isError = stage == RestoreBackupModel.Stage.WrongPassword,
                        enabled = !(vm.isPasswordAccepted || stage == RestoreBackupModel.Stage.Checking),
                        imeAction = androidx.compose.ui.text.input.ImeAction.Go,
                        focusRequester = passwordFocus,
                        onFocusChange = onPasswordFocus,
                        onSubmit = onSubmit,
                        modifier = Modifier.testTag(RestoreTags.PASSWORD),
                    )
                    PasswordCaption(
                        stage,
                        Modifier
                            .testTag(RestoreTags.CAPTION)
                            .padding(horizontal = 6.dp),
                    )
                }
            }
        }

        ProgressSlot(
            vm,
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = U2Spacing.screenGutter)
                .keyboardAwareBottom(passwordFocused),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val note = note(vm)
            Box(
                Modifier
                    .fillMaxWidth()
                    .alpha(if (passwordFocused) 0f else 1f)
                    .testTag(RestoreTags.NOTE),
            ) {
                AnimatedContent(note, transitionSpec = { fade.enter togetherWith fade.exit }, label = "restoreNote") { text ->
                    GreedyParagraph(
                        text,
                        U2TextStyles.captionTight,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(u2TransitionBlur(fade)),
                    )
                }
            }
            RestoreAction(vm, onPick, onSubmit, onCancel, onFinished)
        }
    }
}

/** The progress block, centred in the space between the password and the note; it rises in once the password is accepted. */
@Composable
private fun ProgressSlot(vm: RestoreBackupModel, modifier: Modifier) {
    val rise = U2Transitions.rise()
    Box(modifier, contentAlignment = Alignment.Center) {
        val progress = vm.progress
        var shown by remember { mutableStateOf(progress) }
        if (progress != null) shown = progress
        AnimatedVisibility(progress != null, enter = rise.enter, exit = rise.exit) {
            shown?.let {
                ProgressBlock(
                    it,
                    isDone = vm.stage is RestoreBackupModel.Stage.Done,
                    modifier = Modifier
                        .then(u2TransitionBlur(rise))
                        .padding(horizontal = U2Spacing.screenGutterL),
                )
            }
        }
    }
}

@Composable
private fun FileCard(vm: RestoreBackupModel, onPick: () -> Unit) {
    val colors = U2Theme.colors
    val fade = U2Transitions.fade()
    AnimatedContent(vm.file, transitionSpec = { fade.enter togetherWith fade.exit }, label = "restoreFileCard") { file ->
        if (file != null) {
            BackupFileCard(
                name = file.fileName,
                caption = fileCaption(file),
                modifier = Modifier
                    .then(u2TransitionBlur(fade))
                    .testTag(RestoreTags.FILE),
            ) {
                val label = stringResource(R.string.settings_a11y_restore_fileok)
                PhosphorIcon(
                    PhosphorGlyph.CheckCircle,
                    size = 22.dp,
                    weight = PhosphorWeight.Fill,
                    tint = colors.online,
                    contentDescription = label,
                    modifier = Modifier.testTag(RestoreTags.FILE_OK),
                )
            }
        } else {
            val stage = vm.stage
            val inspecting = stage == RestoreBackupModel.Stage.Inspecting
            BackupFileCard(
                name = stringResource(if (inspecting) R.string.settings_restore_inspecting else R.string.settings_restore_pick),
                caption = pickCaption(stage),
                captionColor = if (stage is RestoreBackupModel.Stage.Failed) colors.danger else colors.textSecondary,
                modifier = Modifier
                    .then(u2TransitionBlur(fade))
                    .u2CardPress(onPick, enabled = !inspecting)
                    .testTag(RestoreTags.PICK),
            ) {
                Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                    Crossfade(inspecting, label = "restorePickGlyph") { working ->
                        if (working) {
                            SpinningGlyph(size = 20.dp, tint = colors.textSecondary)
                        } else {
                            PhosphorIcon(PhosphorGlyph.CaretRight, size = 15.dp, tint = colors.textTertiary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordCaption(stage: RestoreBackupModel.Stage, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val (text, color) = when (stage) {
        RestoreBackupModel.Stage.WrongPassword -> R.string.settings_restore_password_wrong to colors.danger
        RestoreBackupModel.Stage.Unverified -> R.string.settings_restore_password_unverified to colors.danger
        is RestoreBackupModel.Stage.Restoring, is RestoreBackupModel.Stage.Done -> R.string.settings_restore_password_ok to colors.textSecondary
        RestoreBackupModel.Stage.Checking -> R.string.settings_restore_password_checking to colors.textSecondary
        else -> R.string.settings_restore_password_hint to colors.textSecondary
    }
    Crossfade(text to color, animationSpec = crossFadeSpec(U2Motion.state), modifier = modifier, label = "restoreCaption") { (id, ink) ->
        U2Text(
            stringResource(id),
            U2TextStyles.caption,
            color = ink,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun ProgressBlock(progress: RestoreProgress, isDone: Boolean, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val fraction by animateFloatAsState(progress.fraction.toFloat(), u2Spec(U2Motion.micro), label = "restoreProgress")
    Column(modifier.fillMaxWidth().testTag(RestoreTags.PROGRESS), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .testTag(RestoreTags.PERCENT_ROW),
            verticalAlignment = Alignment.Bottom,
        ) {
            PercentText(floor(progress.fraction * 100).toInt())
            Spacer(
                Modifier
                    .weight(1f)
                    .widthIn(min = 8.dp),
            )
            val eta = if (isDone) {
                stringResource(R.string.settings_restore_eta_done)
            } else {
                stringResource(R.string.settings_restore_eta, max(1, (progress.estimatedRemaining.inWholeMilliseconds / 60_000.0).roundToInt()))
            }
            Crossfade(eta, animationSpec = crossFadeSpec(U2Motion.state), modifier = Modifier.padding(bottom = 7.95.dp), label = "restoreEta") { text ->
                U2Text(text, U2TextStyles.monoMeta, color = colors.textSecondary, modifier = Modifier.testTag(RestoreTags.ETA))
            }
        }
        DottedMeter(progress = fraction, pattern = DotPattern.Progress, modifier = Modifier.testTag(RestoreTags.METER))
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            progress.stages.forEach { stage -> StepRow(stage, progress, isDone) }
        }
    }
}

private enum class StepState { Done, Current, Pending }

@Composable
private fun StepRow(step: RestoreProgress.Stage, progress: RestoreProgress, isDone: Boolean) {
    val colors = U2Theme.colors
    val locale = currentAppLocale()
    val state = when {
        isDone || step.ordinal < progress.stage.ordinal -> StepState.Done
        step == progress.stage -> StepState.Current
        else -> StepState.Pending
    }
    val total = U2Format.integer(progress.messagesTotal, locale)
    val media = U2Format.byteCount(progress.mediaBytes, locale)
    val text = when (step) {
        RestoreProgress.Stage.Keys ->
            stringResource(if (state == StepState.Done) R.string.settings_restore_step_keys_done else R.string.settings_restore_step_keys_current)
        RestoreProgress.Stage.Messages -> when (state) {
            StepState.Done -> stringResource(R.string.settings_restore_step_messages_done, total)
            StepState.Current -> stringResource(R.string.settings_restore_step_messages_current, U2Format.integer(progress.messagesDone, locale), total)
            StepState.Pending -> stringResource(R.string.settings_restore_step_messages_pending, total)
        }
        RestoreProgress.Stage.Media ->
            if (state == StepState.Current) {
                stringResource(R.string.settings_restore_step_media_current, media)
            } else {
                stringResource(R.string.settings_restore_step_media, media)
            }
    }
    val ink = when (state) {
        StepState.Done -> colors.textSecondary
        StepState.Current -> colors.text
        StepState.Pending -> colors.textPending
    }
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag(RestoreTags.step(step)),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crossfade(state, animationSpec = crossFadeSpec(U2Motion.micro), modifier = Modifier.size(15.dp), label = "restoreStepGlyph") { shown ->
            Box(Modifier.size(15.dp), contentAlignment = Alignment.Center) {
                when (shown) {
                    StepState.Done -> PhosphorIcon(PhosphorGlyph.Check, size = 15.dp, tint = colors.online)
                    StepState.Current -> SpinningGlyph(size = 15.dp, tint = colors.accent300)
                    StepState.Pending -> PhosphorIcon(PhosphorGlyph.Circle, size = 15.dp, tint = colors.textPending)
                }
            }
        }
        U2Text(text, U2TextStyles.monoCountdownChip, color = ink, maxLines = 1, softWrap = false)
    }
}

/** “62%”: the number in `displayPercent` (Doto 900 76) and the sign in `displayPercentSign` (Doto 900 42) on one baseline, in a 76 dp line box. */
@Composable
private fun PercentText(value: Int) {
    val sign = U2TextStyles.displayPercentSign.textStyle().toSpan()
    val text: AnnotatedString = buildAnnotatedString {
        append(value.toString())
        withStyle(sign) { append("%") }
    }
    U2Text(
        text,
        U2TextStyles.displayPercent.tabular,
        color = U2Theme.colors.text,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .height(U2TextStyles.displayPercent.lineHeight.dp)
            // Doto's glyphs sit 0.67 dp lower in the 76 dp box than in the browser.
            .graphicsLayer { translationY = -0.67.dp.toPx() }
            .clearAndSetSemantics { contentDescription = "$value%" },
    )
}

private fun TextStyle.toSpan(): SpanStyle = toSpanStyle().copy(color = Color.Unspecified)

@Composable
private fun RestoreAction(
    vm: RestoreBackupModel,
    onPick: () -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onFinished: () -> Unit,
) {
    val stage = vm.stage
    val kind = when (stage) {
        RestoreBackupModel.Stage.Pick, RestoreBackupModel.Stage.Inspecting, is RestoreBackupModel.Stage.Failed -> 0
        RestoreBackupModel.Stage.Password, RestoreBackupModel.Stage.Checking, RestoreBackupModel.Stage.WrongPassword,
        RestoreBackupModel.Stage.Unverified,
        -> 1
        is RestoreBackupModel.Stage.Restoring -> 2
        is RestoreBackupModel.Stage.Done -> 3
    }
    Crossfade(kind, animationSpec = crossFadeSpec(U2Motion.state), label = "restoreAction") { shown ->
        when (shown) {
            0 -> {
                val inspecting = vm.stage == RestoreBackupModel.Stage.Inspecting
                U2PrimaryButton(
                    onClick = { if (!inspecting) onPick() },
                    modifier = Modifier.testTag(RestoreTags.ACTION_PICK),
                ) {
                    U2ButtonLabel(
                        stringResource(if (vm.stage is RestoreBackupModel.Stage.Failed) R.string.settings_restore_pickanother else R.string.settings_restore_pick),
                        glyph = PhosphorGlyph.FileLock,
                        isWorking = inspecting,
                    )
                }
            }
            1 -> {
                val checking = vm.stage == RestoreBackupModel.Stage.Checking
                U2PrimaryButton(
                    onClick = { if (!checking) onSubmit() },
                    enabled = vm.canSubmitPassword || checking,
                    modifier = Modifier.testTag(RestoreTags.ACTION_SUBMIT),
                ) {
                    U2ButtonLabel(
                        stringResource(if (checking) R.string.settings_restore_checking else R.string.settings_restore_submit),
                        isWorking = checking,
                    )
                }
            }
            2 -> U2SecondaryButton(
                title = stringResource(R.string.settings_restore_cancel),
                onClick = onCancel,
                modifier = Modifier.testTag(RestoreTags.ACTION_CANCEL),
            )
            else -> U2PrimaryButton(
                title = stringResource(R.string.settings_restore_continue),
                onClick = onFinished,
                modifier = Modifier.testTag(RestoreTags.ACTION_CONTINUE),
            )
        }
    }
}

// MARK: - Texts

@Composable
private fun note(vm: RestoreBackupModel): String {
    val progress = vm.progress
    if (progress != null && !progress.includesHistory) {
        // What an account backup really brings back, and what restoring inside the lock costs this phone.
        return stringResource(if (vm.replacesThisPhone) R.string.settings_restore_note_account_replacing else R.string.settings_restore_note_account)
    }
    return stringResource(
        when (vm.stage) {
            is RestoreBackupModel.Stage.Restoring -> R.string.settings_restore_note_restoring
            is RestoreBackupModel.Stage.Done -> R.string.settings_restore_note_done
            else -> R.string.settings_restore_note_private
        },
    )
}

/** “184 MB · 8 Ekim 2026, 21:14”. */
@Composable
private fun fileCaption(file: BackupFileInfo): String {
    val locale = currentAppLocale()
    val size = U2Format.byteCount(file.byteCount, locale)
    val date = file.createdAt ?: return size
    return stringResource(R.string.settings_restore_file_caption, size, RestoreFormat.day(date, locale), U2Format.clockTime(date, locale))
}

@Composable
private fun pickCaption(stage: RestoreBackupModel.Stage): String = stringResource(
    when ((stage as? RestoreBackupModel.Stage.Failed)?.error) {
        BackupFileError.NotABackup -> R.string.settings_restore_error_notabackup
        BackupFileError.Unreadable -> R.string.settings_restore_error_unreadable
        BackupFileError.Damaged -> R.string.settings_restore_error_damaged
        BackupFileError.NoLongerValid -> R.string.settings_restore_error_nolongervalid
        null -> R.string.settings_restore_pick_caption
    },
)

internal object RestoreFormat {
    /** “8 Ekim 2026” / “October 8, 2026” (the localized `dMMMMyyyy` template, as on iOS). */
    fun day(date: Instant, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMMMyyyy"), locale).format(date.atZone(zone))
}
