package com.syconx.us2us.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.syconx.us2us.domain.BackupContents
import com.syconx.us2us.domain.BackupPasswordError
import com.syconx.us2us.domain.MessengerException
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.CenteredTitleHeader
import com.syconx.us2us.ui.components.DotPattern
import com.syconx.us2us.ui.components.DottedMeter
import com.syconx.us2us.ui.components.GroupedCard
import com.syconx.us2us.ui.components.GroupedRow
import com.syconx.us2us.ui.components.GroupedSeparator
import com.syconx.us2us.ui.components.IconTile
import com.syconx.us2us.ui.components.IconTileContent
import com.syconx.us2us.ui.components.PasswordField
import com.syconx.us2us.ui.components.PasswordFieldTrailing
import com.syconx.us2us.ui.components.SectionLabel
import com.syconx.us2us.ui.components.U2ButtonLabel
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.env.LocalHarnessState
import com.syconx.us2us.ui.env.LocalU2Clock
import com.syconx.us2us.ui.env.ScreenRecordingProbe
import com.syconx.us2us.ui.env.U2Haptics
import com.syconx.us2us.ui.env.rememberGuardedLauncher
import com.syconx.us2us.ui.env.rememberU2Keyboard
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.u2Spec
import com.syconx.us2us.ui.motion.u2TransitionBlur
import com.syconx.us2us.ui.nav.LocalRouter
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import java.io.File
import java.net.URI
import kotlin.math.floor
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State of 7.2 · Yedeği dışa aktar.
 *
 * The backup password is separate from the secret code and asked twice: the first entry is checked
 * (the PROTOCOL.md §5.5 rules, then the local strength estimate at least “Orta”), then a
 * confirmation field rises under it; only a matching confirmation exports. Every problem is shown
 * under its field. “Güçlü parola oluştur” fills in a random password and shows its card, so that it
 * can be saved in a password manager: hidden until “Göster” (and again when the app leaves the
 * foreground); “Kopyala” hands it to the clipboard, marked sensitive, for a minute, and “Kopyalandı ·
 * 1 dk sonra panodan silinir” says so until the minute is over. The passwords live only in this
 * model, which goes away with the screen; nothing logs or stores them.
 */
@Stable
class BackupExportModel(
    private val messenger: MessengerService,
    private val scope: CoroutineScope,
    private val clipboard: SensitiveClipboard,
    private val copyLifetime: Duration = SensitiveClipboard.LIFETIME,
) {
    sealed interface Phase {
        data object Editing : Phase

        /** Encrypting on the device (0…1). */
        data class Exporting(val fraction: Double) : Phase

        /** The system's “save as” picker is up. */
        data object Sharing : Phase
    }

    enum class Problem {
        /** The strength estimate is below “Orta”. */
        Weak,

        /** Fewer than 10 characters (§5.5). */
        TooShort,

        /** In `spec/common-backup-passwords.txt` (§5.5). */
        TooCommon,

        /** One character repeated (§5.5). */
        RepeatedCharacter,

        /** A run or a keyboard row (§5.5). */
        Sequence,

        /** The confirmation differs. */
        Mismatch,

        /** Encrypting or writing the file failed; nothing was exported. */
        ExportFailed,
        ;

        /** Shown under the first field, which then draws its error ring. */
        val concernsPassword: Boolean get() = this != Mismatch && this != ExportFailed

        companion object {
            fun of(error: BackupPasswordError): Problem = when (error) {
                BackupPasswordError.TooShort -> TooShort
                BackupPasswordError.TooCommon -> TooCommon
                BackupPasswordError.RepeatedCharacter -> RepeatedCharacter
                BackupPasswordError.SimpleSequence -> Sequence
                BackupPasswordError.Mismatch -> Mismatch
            }
        }
    }

    private var passwordValue by mutableStateOf("")
    private var confirmationValue by mutableStateOf("")

    var password: String
        get() = passwordValue
        set(value) {
            if (value == passwordValue) return
            passwordValue = value
            strength = if (value == generatedPassword) PasswordStrength.Generated else PasswordStrength.estimate(value)
            if (isConfirming && confirmation.isNotEmpty()) confirmation = ""
            problem = null
            endCopyConfirmation()
        }

    var confirmation: String
        get() = confirmationValue
        set(value) {
            if (value == confirmationValue) return
            confirmationValue = value
            if (problem == Problem.Mismatch || problem == Problem.ExportFailed) problem = null
        }

    var strength by mutableStateOf(PasswordStrength.Empty)
        private set

    /** The confirmation field is shown. */
    var isConfirming by mutableStateOf(false)
        private set
    var problem by mutableStateOf<Problem?>(null)
        private set
    var phase by mutableStateOf<Phase>(Phase.Editing)
        private set

    /** The last password “Güçlü parola oluştur” made; shown while the field still holds it. */
    var generatedPassword by mutableStateOf<String?>(null)
        private set

    /** “Kopyala” copied the shown password, and the copy has not expired yet. */
    var didCopy by mutableStateOf(false)
        private set

    /** “Göster”: the generated password is shown in the clear. Off at first, and again whenever the app leaves the foreground. */
    var isRevealed by mutableStateOf(false)
        private set

    /** The screen is being recorded or mirrored: the password stays hidden whatever “Göster” says. */
    var isScreenCaptured by mutableStateOf(false)

    /** The reference state draws the ring on the password field without the keyboard. */
    var highlightsPassword by mutableStateOf(false)

    /** The window's haptics (the screen sets them). */
    var haptics: U2Haptics = U2Haptics.None

    private var copyExpiry: Job? = null

    /** The generated password is in the field (and its card below it). */
    val showsGeneratedPassword: Boolean get() = generatedPassword != null && password == generatedPassword

    /** The card shows the password in the clear (not just its shape). */
    val revealsGeneratedPassword: Boolean get() = isRevealed && !isScreenCaptured

    /** “Göster” / “Gizle”. */
    fun toggleReveal() {
        if (!showsGeneratedPassword || isScreenCaptured) return
        isRevealed = !isRevealed
    }

    /** The app leaves the foreground (or the screen gets recorded): hidden again. The copy itself stays: that is when it is pasted elsewhere. */
    fun hidePassword() {
        isRevealed = false
    }

    val confirmationMatches: Boolean get() = isConfirming && confirmation.isNotEmpty() && confirmation == password

    val canSubmit: Boolean
        get() {
            if (phase != Phase.Editing) return false
            return if (isConfirming) confirmation.isNotEmpty() else password.isNotEmpty()
        }

    /**
     * “Güçlü parola oluştur”: a new random password in the field, counted at its exact strength and
     * shown below it. A confirmation typed so far is cleared.
     */
    fun generatePassword(generate: () -> String = messenger::generateBackupPassword) {
        val value = generate()
        generatedPassword = value
        password = value
    }

    /**
     * “Kopyala”: the generated password to the clipboard for one minute
     * ([SensitiveClipboard.LIFETIME]). The confirmation stays until the minute is over; copying
     * again starts it over.
     */
    fun copyGeneratedPassword(): Boolean {
        val generated = generatedPassword
        if (!showsGeneratedPassword || generated == null) return false
        if (!clipboard.copy(generated, copyLifetime)) return false
        haptics.success()
        didCopy = true
        copyExpiry?.cancel()
        copyExpiry = scope.launch {
            delay(copyLifetime)
            // The clipboard has dropped it by now: say no more than is true.
            didCopy = false
            copyExpiry = null
        }
        return true
    }

    private fun endCopyConfirmation() {
        copyExpiry?.cancel()
        copyExpiry = null
        didCopy = false
    }

    /** “Dışa aktar” / the keyboard's action. Returns the exported file when it is ready to save. */
    suspend fun submit(fileName: String): URI? {
        if (!canSubmit) return null
        // Checked at both steps: the password may have changed since the confirmation field appeared.
        passwordProblem()?.let {
            fail(it)
            return null
        }
        if (!isConfirming) {
            problem = null
            isConfirming = true
            return null
        }
        if (confirmation != password) {
            fail(Problem.Mismatch)
            return null
        }
        problem = null
        phase = Phase.Exporting(0.0)
        return try {
            val uri = messenger.exportBackup(CONTENTS, password, fileName) { fraction -> phase = Phase.Exporting(fraction) }
            phase = Phase.Sharing
            uri
        } catch (refused: MessengerException.BackupPassword) {
            phase = Phase.Editing
            fail(Problem.of(refused.error))
            null
        } catch (cancelled: CancellationException) {
            phase = Phase.Editing
            fail(Problem.ExportFailed)
            throw cancelled
        } catch (_: Exception) {
            phase = Phase.Editing
            fail(Problem.ExportFailed)
            null
        }
    }

    /** The “save as” picker closed; the temporary file is deleted either way. */
    suspend fun finishSharing(uri: URI, saved: Boolean) {
        messenger.finishExport(uri, saved)
        if (saved) {
            password = ""
            confirmation = ""
            generatedPassword = null
            isRevealed = false
        }
        phase = Phase.Editing
    }

    /**
     * The first problem of the password itself: the §5.5 rules (the same the export checks again),
     * then the strength estimate. At least 10 characters also keep it apart from the six-digit code.
     */
    private fun passwordProblem(): Problem? {
        messenger.backupPasswordProblem(password)?.let { return Problem.of(it) }
        return if (strength.isAcceptable) null else Problem.Weak
    }

    private fun fail(problem: Problem) {
        haptics.error()
        this.problem = problem
    }

    /** Debug harness states (`settings:7.2…`). */
    fun applyHarness(state: String) {
        val demo = DesignFixtures.DEMO_EXPORT_PASSWORD
        when (state) {
            "7.2" -> {
                password = demo
                highlightsPassword = true
            }
            "7.2-weak" -> {
                // Long enough for the §5.5 rules, weak by the estimate: the state the live check produces.
                password = "Ahmet1985!"
                problem = Problem.Weak
                highlightsPassword = true
            }
            "7.2-generated" -> generatePassword { HARNESS_GENERATED }
            "7.2-revealed" -> {
                generatePassword { HARNESS_GENERATED }
                isRevealed = true
            }
            "7.2-copied" -> {
                generatePassword { HARNESS_GENERATED }
                isRevealed = true
                didCopy = true
            }
            "7.2-confirm" -> {
                password = demo
                isConfirming = true
                confirmation = demo
            }
            "7.2-mismatch" -> {
                password = demo
                isConfirming = true
                confirmation = demo.dropLast(2)
                problem = Problem.Mismatch
            }
            "7.2-exporting" -> {
                password = demo
                isConfirming = true
                confirmation = demo
                phase = Phase.Exporting(0.4)
            }
        }
    }

    companion object {
        /**
         * What every backup holds (PROTOCOL.md §5.5): the account, its identity key and the contacts.
         * Messages and media are never part of it, so there is nothing to choose.
         */
        val CONTENTS = BackupContents(messages = false, media = false, settingsAndContacts = true)

        private const val HARNESS_GENERATED = "k7mq-x2pd-9tfa-wn3h-b8ze"

        /** `k7mq-x2pd-…` → `••••-••••-…`: the shape (and the dashes that are part of it) without the password. */
        fun masked(password: String): String = password.map { if (it == '-') '-' else '•' }.joinToString("")
    }
}

/** Test tags of 7.2 (the iOS accessibility identifiers). */
object BackupTags {
    const val BACK = "backup.back"
    const val FILE = "backup.file"
    const val CONTENTS_LABEL = "backup.contents.label"
    const val CONTENTS_FOOTNOTE = "backup.contents.footnote"
    const val PASSWORD = "backup.password"
    const val CONFIRM = "backup.password.confirm"
    const val STRENGTH = "backup.strength"
    const val PROBLEM = "backup.problem"
    const val GENERATE = "backup.password.generate"
    const val GENERATED = "backup.password.generated"
    const val REVEAL = "backup.password.reveal"
    const val COPY = "backup.password.copy"
    const val COPIED = "backup.password.copied"
    const val WARNING = "backup.password.warning"
    const val EXPORT = "backup.export"
}

/** Where an exported backup is written once the user picked a place for it. */
internal object BackupDocuments {
    /** What the “save as” picker is asked for: the `.u2u` file is opaque bytes. */
    const val MIME = "application/octet-stream"

    /** Copies the encrypted file at [source] into the picked document; `false` (and the document removed) when that failed. */
    suspend fun write(context: Context, source: URI, target: Uri): Boolean = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val written = runCatching {
            val input = if (source.scheme == "file") File(source).inputStream() else resolver.openInputStream(Uri.parse(source.toString()))
            input?.use { from ->
                resolver.openOutputStream(target, "wt")?.use { to ->
                    from.copyTo(to)
                    true
                }
            } ?: false
        }.getOrDefault(false)
        if (!written) runCatching { DocumentsContract.deleteDocument(resolver, target) }
        written
    }
}

/** 7.2 · Yedeği dışa aktar. */
@Composable
internal fun BackupExportView(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val router = LocalRouter.current
    val haptics = LocalHaptics.current
    val harness = LocalHarnessState.current as? SettingsUiState
    val clock = LocalU2Clock.current
    val keyboard = rememberU2Keyboard()
    val flow = rememberSettingsFlow()
    val scope = rememberCoroutineScope()
    val vm = remember {
        BackupExportModel(flow.messenger, scope, SystemSensitiveClipboard(context)).also { model ->
            harness?.take("7.2")?.let(model::applyHarness)
        }
    }
    SideEffect { vm.haptics = haptics }

    val passwordFocus = remember { FocusRequester() }
    val confirmationFocus = remember { FocusRequester() }
    var passwordFocused by remember { mutableStateOf(false) }
    var confirmationFocused by remember { mutableStateOf(false) }
    val isKeyboardUp = passwordFocused || confirmationFocused

    // Hidden again before the app-switcher snapshot and the lock.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, vm) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) vm.hidePassword() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Recorded or mirrored (Android 15+): the generated password stays hidden, as on iOS.
    ScreenRecordingProbe { captured ->
        vm.isScreenCaptured = captured
        if (captured) vm.hidePassword()
    }
    // Focus moves to the confirmation when it appears (iOS `onChange`); a screen that opens already
    // confirming (a harness state) leaves the keyboard down.
    val opensConfirming = remember { vm.isConfirming }
    LaunchedEffect(vm.isConfirming) {
        if (vm.isConfirming && !opensConfirming) {
            // The field rises first.
            delay(16)
            runCatching { confirmationFocus.requestFocus() }
        }
    }

    // “us2us-yedek-2026-10-08.u2u” / “us2us-backup-2026-10-08.u2u”.
    val fileName = stringResource(R.string.settings_backup_filename, SettingsFormat.fileDate(clock.now))
    val doneToast = R.string.settings_backup_done_toast

    // The file is encrypted into the app's private storage, then the user picks where it goes (the
    // system's “save as” picker); the temporary file is deleted either way.
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    val saveAs = rememberGuardedLauncher(ActivityResultContracts.CreateDocument(BackupDocuments.MIME)) { target ->
        val file = pendingExport?.let(URI::create) ?: return@rememberGuardedLauncher
        pendingExport = null
        scope.launch {
            val saved = target != null && BackupDocuments.write(context, file, target)
            vm.finishSharing(file, saved)
            if (saved) {
                flow.toast = doneToast
                router.pop()
            }
        }
    }

    fun submit() {
        scope.launch {
            val file = vm.submit(fileName) ?: return@launch
            keyboard.dismiss()
            pendingExport = file.toString()
            try {
                saveAs.launch(fileName)
            } catch (_: ActivityNotFoundException) {
                pendingExport = null
                vm.finishSharing(file, saved = false)
            }
        }
    }

    U2Screen(modifier) {
        Column(Modifier.fillMaxSize()) {
            CenteredTitleHeader(
                title = stringResource(R.string.settings_backup_title),
                leading = {
                    U2GlassIconButton(
                        glyph = PhosphorGlyph.CaretLeft,
                        contentDescription = stringResource(R.string.common_a11y_back),
                        onClick = router::pop,
                        modifier = Modifier.testTag(BackupTags.BACK),
                    )
                },
            )
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                BackupContent(
                    vm = vm,
                    fileName = fileName,
                    passwordFocused = passwordFocused,
                    passwordFocus = passwordFocus,
                    confirmationFocus = confirmationFocus,
                    onPasswordFocus = { passwordFocused = it },
                    onConfirmationFocus = { confirmationFocused = it },
                    onGenerate = {
                        keyboard.dismiss()
                        vm.generatePassword()
                    },
                    onSubmit = ::submit,
                    modifier = Modifier.padding(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 24.dp),
                )
            }
            ExportButton(vm, isKeyboardUp, onClick = ::submit)
        }
    }
}

@Composable
private fun BackupContent(
    vm: BackupExportModel,
    fileName: String,
    passwordFocused: Boolean,
    passwordFocus: FocusRequester,
    confirmationFocus: FocusRequester,
    onPasswordFocus: (Boolean) -> Unit,
    onConfirmationFocus: (Boolean) -> Unit,
    onGenerate: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = U2Theme.colors
    val state = U2Motion.resolve(U2Motion.state, LocalReduceMotion.current)
    val rise = U2Transitions.rise()
    val drop = U2Transitions.drop()
    val editing = vm.phase == BackupExportModel.Phase.Editing
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        BackupFileCard(name = fileName, caption = stringResource(R.string.settings_backup_file_caption), modifier = Modifier.testTag(BackupTags.FILE))

        ContentsSection()

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.settings_backup_password))
            PasswordField(
                text = vm.password,
                onTextChange = { vm.password = it },
                isError = vm.problem?.concernsPassword == true,
                isHighlighted = vm.highlightsPassword && !passwordFocused,
                enabled = editing,
                imeAction = ImeAction.Next,
                contentType = ContentType.NewPassword,
                revealAllowed = !vm.isScreenCaptured,
                focusRequester = passwordFocus,
                onFocusChange = onPasswordFocus,
                onSubmit = onSubmit,
                modifier = Modifier.testTag(BackupTags.PASSWORD),
            )
            StrengthRow(vm)
            ProblemSlot(vm.problem?.takeIf { it.concernsPassword }, drop.enter + expandVertically(state.spec()), drop.exit + shrinkVertically(state.spec()))
            U2TextButton(
                onClick = onGenerate,
                tone = U2TextButtonTone.Accent,
                height = 40.dp,
                fillsWidth = false,
                enabled = editing,
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .testTag(BackupTags.GENERATE),
            ) {
                U2ButtonLabel(stringResource(R.string.settings_backup_password_generate), glyph = PhosphorGlyph.Password, glyphSize = 18.dp)
            }
            var generated by remember { mutableStateOf(vm.generatedPassword) }
            if (vm.showsGeneratedPassword) generated = vm.generatedPassword
            AnimatedVisibility(
                visible = vm.showsGeneratedPassword,
                enter = rise.enter + expandVertically(state.spec()),
                exit = rise.exit + shrinkVertically(state.spec()),
            ) {
                generated?.let { GeneratedCard(vm, it, editing, Modifier.then(u2TransitionBlur(rise))) }
            }
            AnimatedVisibility(
                visible = vm.isConfirming,
                enter = rise.enter + expandVertically(state.spec()),
                exit = rise.exit + shrinkVertically(state.spec()),
            ) {
                Column(Modifier.then(u2TransitionBlur(rise)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PasswordField(
                        text = vm.confirmation,
                        onTextChange = { vm.confirmation = it },
                        prompt = stringResource(R.string.settings_backup_password_confirm),
                        trailing = if (vm.confirmationMatches) PasswordFieldTrailing.Accepted else PasswordFieldTrailing.Reveal,
                        isError = vm.problem == BackupExportModel.Problem.Mismatch,
                        enabled = editing,
                        imeAction = ImeAction.Done,
                        contentType = ContentType.NewPassword,
                        revealAllowed = !vm.isScreenCaptured,
                        focusRequester = confirmationFocus,
                        onFocusChange = onConfirmationFocus,
                        onSubmit = onSubmit,
                        modifier = Modifier.testTag(BackupTags.CONFIRM),
                    )
                    ProblemSlot(
                        vm.problem?.takeIf { it == BackupExportModel.Problem.Mismatch },
                        drop.enter + expandVertically(state.spec()),
                        drop.exit + shrinkVertically(state.spec()),
                    )
                }
            }
            ProblemSlot(
                vm.problem?.takeIf { it == BackupExportModel.Problem.ExportFailed },
                drop.enter + expandVertically(state.spec()),
                drop.exit + shrinkVertically(state.spec()),
            )
        }

        Row(
            Modifier
                .padding(horizontal = 6.dp)
                .semantics(mergeDescendants = true) {}
                .testTag(BackupTags.WARNING),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.height(U2TextStyles.captionTight.lineHeight.dp), contentAlignment = Alignment.Center) {
                PhosphorIcon(PhosphorGlyph.Warning, size = 18.dp, tint = colors.accent300)
            }
            U2Text(
                stringResource(R.string.settings_backup_password_warning),
                U2TextStyles.captionTight,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** “Yedeğe girecekler”: what the file holds, and (footnote) what it never holds. Nothing to switch: a backup is always the account (§5.5). */
@Composable
private fun ContentsSection() {
    Column(verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionLabelGap)) {
        SectionLabel(stringResource(R.string.settings_backup_contents), Modifier.testTag(BackupTags.CONTENTS_LABEL))
        GroupedCard {
            ContentsRow(PhosphorGlyph.Password, PhosphorWeight.Regular, R.string.settings_backup_contents_account, R.string.settings_backup_contents_account_detail)
            GroupedSeparator()
            ContentsRow(PhosphorGlyph.ShieldCheck, PhosphorWeight.Regular, R.string.settings_backup_contents_identity, R.string.settings_backup_contents_identity_detail)
            GroupedSeparator()
            ContentsRow(PhosphorGlyph.SealCheck, PhosphorWeight.Fill, R.string.settings_backup_contents_contacts, R.string.settings_backup_contents_contacts_detail)
        }
        U2Text(
            stringResource(R.string.settings_backup_contents_footnote),
            U2TextStyles.footnote,
            color = U2Theme.colors.textSecondary,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .testTag(BackupTags.CONTENTS_FOOTNOTE),
        )
    }
}

@Composable
private fun ContentsRow(glyph: PhosphorGlyph, weight: PhosphorWeight, title: Int, detail: Int) {
    GroupedRow(
        tile = { IconTile(IconTileContent.Glyph(glyph), glyphWeight = weight) },
        title = stringResource(title),
        subtitle = stringResource(detail),
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        PhosphorIcon(PhosphorGlyph.Check, size = 18.dp, tint = U2Theme.colors.online)
    }
}

@Composable
private fun StrengthRow(vm: BackupExportModel) {
    val colors = U2Theme.colors
    val strength = vm.strength
    val isEmpty = vm.password.isEmpty()
    val lit = when (strength.level) {
        PasswordStrength.Level.Weak -> colors.danger
        PasswordStrength.Level.Fair -> colors.accent400
        PasswordStrength.Level.Strong, PasswordStrength.Level.VeryStrong -> colors.online
    }
    val litColor by animateColorAsState(lit, u2Spec(U2Motion.state), label = "strengthColor")
    val fraction by animateFloatAsState(if (isEmpty) 0f else strength.meterFraction.toFloat(), u2Spec(U2Motion.state), label = "strengthMeter")
    val label = strengthLabel(strength.level)
    val a11yLabel = stringResource(R.string.settings_a11y_backup_strength)
    val fade = U2Transitions.fade()
    Row(
        Modifier
            .fillMaxWidth()
            .height(U2TextStyles.meta.lineHeight.dp)
            .padding(horizontal = 6.dp)
            .testTag(BackupTags.STRENGTH)
            .clearAndSetSemantics {
                contentDescription = a11yLabel
                stateDescription = if (isEmpty) "" else label
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DottedMeter(
            progress = fraction,
            pattern = DotPattern.Strength,
            trackColor = colors.dotTrackStrength,
            litColor = litColor,
            modifier = Modifier.weight(1f),
        )
        AnimatedVisibility(!isEmpty, enter = fade.enter, exit = fade.exit) {
            Crossfade(strength.level, animationSpec = crossFadeSpec(U2Motion.state), label = "strengthLabel") { level ->
                U2Text(
                    strengthLabel(level),
                    U2TextStyles.meta,
                    color = if (level == PasswordStrength.Level.Weak) colors.danger else colors.text,
                )
            }
        }
    }
}

@Composable
private fun strengthLabel(level: PasswordStrength.Level): String = stringResource(
    when (level) {
        PasswordStrength.Level.Weak -> R.string.settings_backup_strength_weak
        PasswordStrength.Level.Fair -> R.string.settings_backup_strength_fair
        PasswordStrength.Level.Strong -> R.string.settings_backup_strength_strong
        PasswordStrength.Level.VeryStrong -> R.string.settings_backup_strength_verystrong
    },
)

/**
 * The generated password card: a `surface` card, r18: the password in groups (`monoUsernameL`),
 * hidden as `••••-••••-…` until the eye (the password field's 20 dp `ph:eye` in `text·68%`) shows
 * it; the reminder to save it in a password manager; “Kopyala” and, after it, the copy's status line
 * (`ph:check` in `online`, `captionTight`) until the copy expires.
 */
@Composable
private fun GeneratedCard(vm: BackupExportModel, password: String, editing: Boolean, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val revealed = vm.revealsGeneratedPassword
    val fade = U2Transitions.fade()
    val label = stringResource(R.string.settings_a11y_backup_generated)
    val hidden = stringResource(R.string.settings_a11y_backup_generated_hidden)
    val copiedAnnouncement = stringResource(R.string.settings_backup_password_copied)
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(U2Radius.group))
            .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Crossfade(
                revealed,
                animationSpec = crossFadeSpec(U2Motion.micro),
                modifier = Modifier
                    .weight(1f)
                    .testTag(BackupTags.GENERATED)
                    .clearAndSetSemantics {
                        contentDescription = label
                        stateDescription = if (revealed) password.toList().joinToString(" ") else hidden
                    },
                label = "generatedPassword",
            ) { shown ->
                U2Text(
                    if (shown) password else BackupExportModel.masked(password),
                    U2TextStyles.monoUsernameL,
                    color = if (shown) colors.text else colors.textSecondary,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val revealLabel = stringResource(if (revealed) R.string.common_a11y_password_hide else R.string.common_a11y_password_show)
            Box(
                Modifier
                    .size(20.dp)
                    .testTag(BackupTags.REVEAL),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .requiredSize(44.dp)
                        .clickable(
                            remember { MutableInteractionSource() },
                            indication = null,
                            enabled = !vm.isScreenCaptured,
                            role = Role.Button,
                            onClick = vm::toggleReveal,
                        )
                        .semantics { contentDescription = revealLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    PhosphorIcon(if (revealed) PhosphorGlyph.EyeSlash else PhosphorGlyph.Eye, size = 20.dp, tint = colors.textSecondary)
                }
            }
        }
        U2Text(stringResource(R.string.settings_backup_password_generated_hint), U2TextStyles.captionTight, color = colors.textSecondary)
        U2TextButton(
            onClick = { vm.copyGeneratedPassword() },
            tone = U2TextButtonTone.Accent,
            height = 40.dp,
            fillsWidth = false,
            enabled = editing,
            modifier = Modifier.testTag(BackupTags.COPY),
        ) {
            U2ButtonLabel(stringResource(R.string.settings_backup_password_copy), glyph = PhosphorGlyph.Copy, glyphSize = 18.dp)
        }
        AnimatedVisibility(vm.didCopy, enter = fade.enter, exit = fade.exit) {
            Row(
                Modifier
                    .then(u2TransitionBlur(fade))
                    .padding(bottom = 10.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                    .testTag(BackupTags.COPIED),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.height(U2TextStyles.captionTight.lineHeight.dp), contentAlignment = Alignment.Center) {
                    PhosphorIcon(PhosphorGlyph.Check, size = 16.dp, tint = colors.online)
                }
                U2Text(copiedAnnouncement, U2TextStyles.captionTight, color = colors.textSecondary)
            }
        }
    }
}

/** A problem under its field, dropping in; the last one stays while it leaves. */
@Composable
private fun ProblemSlot(
    problem: BackupExportModel.Problem?,
    enter: androidx.compose.animation.EnterTransition,
    exit: androidx.compose.animation.ExitTransition,
) {
    var shown by remember { mutableStateOf(problem) }
    if (problem != null) shown = problem
    AnimatedVisibility(problem != null, enter = enter, exit = exit) {
        shown?.let { ProblemCaption(it) }
    }
}

@Composable
private fun ProblemCaption(problem: BackupExportModel.Problem) {
    val text = when (problem) {
        BackupExportModel.Problem.Weak -> R.string.settings_backup_problem_weak
        BackupExportModel.Problem.TooShort -> R.string.settings_backup_problem_tooshort
        BackupExportModel.Problem.TooCommon -> R.string.settings_backup_problem_toocommon
        BackupExportModel.Problem.RepeatedCharacter -> R.string.settings_backup_problem_repeated
        BackupExportModel.Problem.Sequence -> R.string.settings_backup_problem_sequence
        BackupExportModel.Problem.Mismatch -> R.string.settings_backup_problem_mismatch
        BackupExportModel.Problem.ExportFailed -> R.string.settings_backup_problem_exportfailed
    }
    U2Text(
        stringResource(text),
        U2TextStyles.caption,
        color = U2Theme.colors.danger,
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(BackupTags.PROBLEM),
    )
}

@Composable
private fun ExportButton(vm: BackupExportModel, isKeyboardUp: Boolean, onClick: () -> Unit) {
    val phase = vm.phase
    val title = if (phase is BackupExportModel.Phase.Exporting) {
        // “Şifreleniyor… %40” while the file is encrypted.
        stringResource(R.string.settings_backup_exporting_percent, floor(phase.fraction * 100).toInt())
    } else {
        stringResource(R.string.settings_backup_export)
    }
    U2PrimaryButton(
        onClick = { if (vm.phase == BackupExportModel.Phase.Editing) onClick() },
        enabled = vm.canSubmit || vm.phase != BackupExportModel.Phase.Editing,
        modifier = Modifier
            .padding(horizontal = U2Spacing.screenGutter)
            .padding(top = 8.dp)
            .keyboardAwareBottom(isKeyboardUp)
            .testTag(BackupTags.EXPORT),
    ) {
        U2ButtonLabel(title, glyph = PhosphorGlyph.Export, isWorking = phase is BackupExportModel.Phase.Exporting)
    }
}
