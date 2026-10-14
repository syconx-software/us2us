package com.syconx.us2us.feature.report

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.ReportReason
import com.syconx.us2us.domain.ReportSubject
import com.syconx.us2us.ui.components.GroupedCard
import com.syconx.us2us.ui.components.GroupedRow
import com.syconx.us2us.ui.components.GroupedSeparator
import com.syconx.us2us.ui.components.GroupedToggleRow
import com.syconx.us2us.ui.components.IconTile
import com.syconx.us2us.ui.components.IconTileContent
import com.syconx.us2us.ui.components.IconTileTone
import com.syconx.us2us.ui.components.SectionLabel
import com.syconx.us2us.ui.components.SheetBar
import com.syconx.us2us.ui.components.U2ButtonLabel
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.components.currentLabelColor
import com.syconx.us2us.ui.components.groupedRowPress
import com.syconx.us2us.ui.components.LocalU2LabelStyle
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.env.currentSafeArea
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.l10n.rememberU2Strings
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.u2Spec
import com.syconx.us2us.ui.motion.u2TransitionBlur
import com.syconx.us2us.ui.sheet.U2Sheet
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import com.syconx.us2us.ui.type.textStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.syconx.us2us.ui.R as DesignR

/** Test tags of the report sheet (the iOS accessibility identifiers). */
object ReportTags {
    const val SHEET = "report.sheet"
    const val BAR = "report.bar"
    const val FORM = "report.form"
    const val INTRO = "report.intro"
    const val NOTE = "report.note"
    const val ALSO_BLOCK = "report.alsoBlock"
    const val PRIVACY = "report.privacy"
    const val SEND = "report.send"
    const val FAILED = "report.failed"
    const val FALLBACK = "report.fallback"
    const val ADDRESS = "report.address"
    const val COPY = "report.copy"
    const val BLOCKED = "report.blocked"
    const val DONE = "report.done"
    const val ANNOUNCEMENT = "report.announcement"

    fun reason(reason: ReportReason): String = "report.reason.${reason.raw}"
}

/**
 * Opens reports (“Bildir”) from a screen: the contact screen's and 4.4's “…”, and every block
 * confirmation. Holds the report on screen ([current]) for [ReportSheet]; the block a report starts
 * runs on a scope that outlives the sheet (and the screen), so leaving does not cut it short.
 */
@Stable
class ReportPresenter internal constructor(
    private val mailer: ReportMailer,
    composition: CoroutineScope,
    private val version: String,
    private val build: String,
) {
    /** The composition's dispatcher (and frame clock), without its end. */
    private val scope by lazy { CoroutineScope(composition.coroutineContext.minusKey(Job) + SupervisorJob()) }

    /** The report on screen, or `null`. */
    var current: ReportModel? by mutableStateOf(null)
        private set

    /** The last report shown, read once its sheet has gone. */
    private var lastShown: ReportModel? = null

    /**
     * Opens the sheet about [subject] (`null`: nobody the phone can name), shown as [personName].
     * “Bu kişiyi de engelle” is offered when [offersBlock] (the person is not blocked yet) and runs
     * [block] once the report left the sheet. From a block confirmation ([afterConfirmation]) the
     * sheet rises once the confirmation has gone (450 ms; one presentation at a time).
     */
    fun open(
        subject: ReportSubject?,
        personName: String,
        offersBlock: Boolean,
        afterConfirmation: Boolean = false,
        mailer: ReportMailer? = null,
        block: suspend () -> Unit,
    ): ReportModel {
        val report = ReportModel(subject ?: ReportSubject.Unknown, personName, offersBlock, mailer ?: this.mailer, scope, block)
        report.version = version
        report.build = build
        report.onClose = { if (current === report) current = null }
        if (afterConfirmation) {
            scope.launch {
                delay(CONFIRMATION_DELAY_MILLIS)
                show(report)
            }
        } else {
            show(report)
        }
        return report
    }

    private fun show(report: ReportModel) {
        lastShown = report
        current = report
    }

    /** The report whose sheet just went (once). */
    internal fun takeLastShown(): ReportModel? = lastShown.also { lastShown = null }

    companion object {
        /** A report opened from a block confirmation rises this long after it. */
        const val CONFIRMATION_DELAY_MILLIS = 450L
    }
}

/** A [ReportPresenter] for this screen, with [LocalReportMailer] or the system's mailer and the app's version. */
@Composable
fun rememberReportPresenter(): ReportPresenter {
    val mailer = rememberReportMailer()
    val context = LocalContext.current
    val composition = rememberCoroutineScope()
    return remember(mailer, composition) {
        val (version, build) = appVersion(context)
        ReportPresenter(mailer, composition, version, build)
    }
}

/** The app's `versionName` and `versionCode` (“—” when unknown). */
internal fun appVersion(context: Context): Pair<String, String> = try {
    val manager = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= 33) {
        manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        manager.getPackageInfo(context.packageName, 0)
    }
    (info.versionName?.takeIf { it.isNotEmpty() } ?: "—") to info.longVersionCode.toString()
} catch (_: PackageManager.NameNotFoundException) {
    "—" to "—"
}

/**
 * “Bildir”: [presenter]'s report in a full-height sheet while one is open. Pulled down or backed out
 * of, it closes like “Vazgeç”. [onDismissed] hears the report once its sheet has gone (4.4 goes back
 * to 4.3 when the report blocked the sender).
 */
@Composable
fun ReportSheet(presenter: ReportPresenter, onDismissed: (ReportModel) -> Unit = {}) {
    U2Sheet(
        item = presenter.current,
        onDismissRequest = { presenter.current?.sheetDidDisappear() },
        onDismissed = { presenter.takeLastShown()?.let(onDismissed) },
    ) { model ->
        ReportSheetContent(model)
    }
}

/**
 * The sheet's content (proposed, in the invite sheet's language): the sheet bar (“Vazgeç” ·
 * “Bildir”), one line on where the report goes, the reasons as a C13 card on `bg` with the chosen
 * one checked (`ph:check` `accent300`), an optional note, “Bu kişiyi de engelle” (C14, on by
 * default; not shown for someone blocked already), the privacy line and, pinned under the
 * scrolling form, “E-postayla gönder” (C1, at 45 % until a reason is chosen).
 *
 * When nothing on this phone can write an e-mail the content cross-fades to the support address
 * with “Kopyala” (and, if the block followed, “… engellendi.”) and “Tamam”; “Vazgeç” becomes
 * “Kapat”.
 */
@Composable
fun ReportSheetContent(model: ReportModel, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val isAddress = model.phase == ReportModel.Phase.Address
    Box(modifier.fillMaxSize().testTag(ReportTags.SHEET)) {
        Column(Modifier.fillMaxSize()) {
            SheetBar(
                title = stringResource(DesignR.string.report_title),
                cancelTitle = stringResource(if (isAddress) DesignR.string.common_close else DesignR.string.common_cancel),
                onCancel = {
                    focus.clearFocus()
                    model.close()
                },
                modifier = Modifier.testTag(ReportTags.BAR),
            )
            val fade = U2Transitions.fade()
            AnimatedContent(
                targetState = isAddress,
                transitionSpec = { fade.enter togetherWith fade.exit },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
                label = "reportPhase",
            ) { address ->
                if (address) {
                    AddressContent(model, Modifier.then(u2TransitionBlur(fade)))
                } else {
                    FormContent(model, Modifier.then(u2TransitionBlur(fade)))
                }
            }
        }
        // What iOS announces (sent, the address shown, copied, failed): a polite live region that is
        // always there (a change is what TalkBack reads) and has a size (a node of none is left out
        // of the accessibility tree). It draws nothing and takes no room.
        val announcement = model.announcement.orEmpty()
        Box(
            Modifier
                .size(1.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = announcement
                }
                .testTag(ReportTags.ANNOUNCEMENT),
        )
    }
}

// MARK: Form

/** The form scrolls; “E-postayla gönder” stays pinned to the bottom, above the keyboard while the note is typed. */
@Composable
private fun FormContent(model: ReportModel, modifier: Modifier) {
    val colors = U2Theme.colors
    val strings = rememberU2Strings()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val sending = model.phase == ReportModel.Phase.Sending
    var barHeight by remember { mutableIntStateOf(0) }
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .testTag(ReportTags.FORM)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp + with(density) { barHeight.toDp() }),
        ) {
            U2Text(
                stringResource(DesignR.string.report_intro, model.personName),
                U2TextStyles.callout,
                Modifier.padding(horizontal = 8.dp).testTag(ReportTags.INTRO),
                color = colors.textSecondary,
            )
            Reasons(model, enabled = !sending, Modifier.padding(top = 20.dp))
            NoteSection(model, enabled = !sending, Modifier.padding(top = 22.dp))
            if (model.offersBlock) {
                GroupedCard(Modifier.padding(top = 22.dp), fill = colors.bg) {
                    GroupedToggleRow(
                        tile = { IconTile(IconTileContent.Glyph(PhosphorGlyph.Prohibit), tone = IconTileTone.Danger) },
                        title = stringResource(DesignR.string.report_block),
                        checked = model.alsoBlock,
                        onCheckedChange = { model.alsoBlock = it },
                        subtitle = stringResource(DesignR.string.report_block_subtitle),
                        enabled = !sending,
                        modifier = Modifier.testTag(ReportTags.ALSO_BLOCK),
                    )
                }
            }
            PrivacyNote(Modifier.padding(start = 8.dp, top = 16.dp, end = 8.dp))
        }
        SendBar(
            model = model,
            onSend = {
                focus.clearFocus()
                scope.launch { model.send(strings) }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { barHeight = it.height },
        )
    }
}

/** The five reasons (C13 on `bg`, separators inset 14), the chosen one checked. */
@Composable
private fun Reasons(model: ReportModel, enabled: Boolean, modifier: Modifier) {
    val haptics = LocalHaptics.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionLabelGap)) {
        SectionLabel(stringResource(DesignR.string.report_reason_label))
        GroupedCard(fill = U2Theme.colors.bg) {
            ReportReason.entries.forEachIndexed { index, reason ->
                if (index > 0) GroupedSeparator(inset = 14.dp)
                val selected = model.reason == reason
                val source = remember { MutableInteractionSource() }
                GroupedRow(
                    tile = null,
                    title = stringResource(reason.titleRes),
                    modifier = Modifier
                        .groupedRowPress(source)
                        .selectable(selected = selected, interactionSource = source, indication = null, enabled = enabled, role = Role.RadioButton) {
                            if (!selected) {
                                haptics.selection()
                                model.reason = reason
                            }
                        }
                        .testTag(ReportTags.reason(reason)),
                ) {
                    ReasonCheck(selected)
                }
            }
        }
    }
}

/** `ph:check` 18 `accent300`: fades and grows in (0.8 → 1) on the chosen row. */
@Composable
private fun ReasonCheck(selected: Boolean) {
    val shown by animateFloatAsState(if (selected) 1f else 0f, u2Spec(U2Motion.micro), label = "reasonCheck")
    PhosphorIcon(
        PhosphorGlyph.Check,
        size = 18.dp,
        tint = U2Theme.colors.accent300,
        modifier = Modifier.graphicsLayer {
            alpha = shown
            val scale = 0.8f + 0.2f * shown
            scaleX = scale
            scaleY = scale
        },
    )
}

/**
 * “Not · isteğe bağlı”: a short text field on `bg` (r16, 108–172 high: iOS's 96–160 editor in its
 * 6 9 padding; text inset 14), at most 500 characters, the `accent` ring while focused.
 */
@Composable
private fun NoteSection(model: ReportModel, enabled: Boolean, modifier: Modifier) {
    val colors = U2Theme.colors
    val shape = RoundedCornerShape(U2Radius.field)
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(if (focused) 1f else 0f, u2Spec(U2Motion.state), label = "reportNoteRing")
    val label = stringResource(DesignR.string.report_a11y_report_note)
    val placeholder = stringResource(DesignR.string.report_note_placeholder)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionLabelGap)) {
        SectionLabel(stringResource(DesignR.string.report_note_label))
        BasicTextField(
            value = model.note,
            onValueChange = { model.note = it },
            enabled = enabled,
            textStyle = U2TextStyles.bubble.textStyle().copy(color = colors.text),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 108.dp, max = 172.dp)
                .background(colors.bg, shape)
                .border(1.5.dp, colors.accent.copy(alpha = colors.accent.alpha * ring), shape)
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = label }
                .testTag(ReportTags.NOTE),
            decorationBox = { field ->
                Box(Modifier.padding(14.dp)) {
                    if (model.note.isEmpty()) {
                        U2Text(placeholder, U2TextStyles.bubble, Modifier.clearAndSetSemantics {}, color = colors.textPlaceholder)
                    }
                    field()
                }
            },
        )
    }
}

/** As the invite's: `ph:shield-check` 18 `accent300` + `callout` `text·68%`. */
@Composable
private fun PrivacyNote(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .testTag(ReportTags.PRIVACY)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PhosphorIcon(PhosphorGlyph.ShieldCheck, size = 18.dp, tint = U2Theme.colors.accent300, modifier = Modifier.padding(top = 1.dp))
        U2Text(stringResource(DesignR.string.report_privacy), U2TextStyles.callout, Modifier.weight(1f), color = U2Theme.colors.textSecondary)
    }
}

/**
 * After a failure the line that says so, then “E-postayla gönder” (pad 12 16, 40 from the bottom or
 * 12 above the keyboard, on the sheet's `surface`; the form fades out in the 16 dp above it).
 */
@Composable
private fun SendBar(model: ReportModel, onSend: () -> Unit, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val density = LocalDensity.current
    val keyboard = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val bottom = maxOf(12.dp, currentSafeArea.bottom(40.dp) - keyboard)
    val hint = stringResource(DesignR.string.report_send_hint)
    val surface = colors.surface
    Column(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val fade = 16.dp.toPx()
                drawRect(
                    Brush.verticalGradient(listOf(surface.copy(alpha = 0f), surface), startY = -fade, endY = 0f),
                    topLeft = Offset(0f, -fade),
                    size = Size(size.width, fade),
                )
            }
            .background(surface)
            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = bottom),
    ) {
        val fade = U2Transitions.fade()
        AnimatedVisibility(model.failed, enter = fade.enter, exit = fade.exit) {
            U2Text(
                stringResource(DesignR.string.report_failed),
                U2TextStyles.caption,
                Modifier
                    .then(u2TransitionBlur(fade))
                    .padding(start = 8.dp, end = 8.dp, bottom = 10.dp)
                    .testTag(ReportTags.FAILED),
                color = colors.danger,
            )
        }
        U2PrimaryButton(
            onClick = onSend,
            enabled = model.canSend,
            modifier = Modifier
                .testTag(ReportTags.SEND)
                .semantics { if (model.reason == null) stateDescription = hint },
        ) {
            U2ButtonLabel(stringResource(DesignR.string.report_send), glyph = PhosphorGlyph.PaperPlaneTilt, isWorking = model.phase == ReportModel.Phase.Sending)
        }
    }
}

// MARK: No e-mail on this phone

/** The support address to write to (C13 row on `bg`, Mono, “Kopyala”), whether the block followed, and “Tamam”. */
@Composable
private fun AddressContent(model: ReportModel, modifier: Modifier) {
    val colors = U2Theme.colors
    val strings = rememberU2Strings()
    val haptics = LocalHaptics.current
    val context = LocalContext.current
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 12.dp, end = 16.dp)
            .u2Bottom(40.dp)
            .testTag(ReportTags.FALLBACK),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.padding(start = 16.dp, top = 28.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconTile(IconTileContent.Glyph(PhosphorGlyph.PaperPlaneTilt), size = 52.dp, cornerRadius = 16.dp, glyphSize = 24.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                U2Text(
                    stringResource(DesignR.string.report_address_title),
                    U2TextStyles.rowTitleEmphasis,
                    Modifier.semantics { heading() },
                    color = colors.text,
                    textAlign = TextAlign.Center,
                )
                U2Text(stringResource(DesignR.string.report_address_message), U2TextStyles.callout, color = colors.textSecondary, textAlign = TextAlign.Center)
            }
        }
        GroupedCard(Modifier.padding(top = 24.dp), fill = colors.bg) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(start = 16.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionContainer(Modifier.weight(1f)) {
                    U2Text(ReportMail.ADDRESS, U2TextStyles.monoUsername, Modifier.testTag(ReportTags.ADDRESS), color = colors.text, maxLines = 1, softWrap = false)
                }
                U2TextButton(
                    onClick = {
                        haptics.success()
                        model.copyAddress(strings) {
                            context.getSystemService(ClipboardManager::class.java)?.let(ReportPasteboard::copyAddress)
                        }
                    },
                    tone = U2TextButtonTone.Accent,
                    height = 44.dp,
                    fillsWidth = false,
                    modifier = Modifier.testTag(ReportTags.COPY),
                ) {
                    CopyLabel(model.copiedAddress)
                }
            }
        }
        if (model.didBlock) {
            Row(
                Modifier
                    .padding(top = 14.dp)
                    .semantics(mergeDescendants = true) {}
                    .testTag(ReportTags.BLOCKED),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhosphorIcon(PhosphorGlyph.Prohibit, size = 16.dp, tint = colors.danger)
                U2Text(stringResource(DesignR.string.report_blocked, model.personName), U2TextStyles.caption, color = colors.textSecondary)
            }
        }
        U2PrimaryButton(
            title = stringResource(DesignR.string.report_done),
            onClick = model::close,
            modifier = Modifier
                .padding(top = 28.dp)
                .testTag(ReportTags.DONE),
        )
    }
}

/** `ph:copy` + “Kopyala”, cross-fading to `ph:check` + “Kopyalandı”. */
@Composable
private fun CopyLabel(copied: Boolean) {
    val label = LocalU2LabelStyle.current
    val ink = currentLabelColor()
    Crossfade(targetState = copied, animationSpec = U2Motion.resolve(U2Motion.state, LocalReduceMotion.current).spec(), label = "reportCopy") { done ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            PhosphorIcon(if (done) PhosphorGlyph.Check else PhosphorGlyph.Copy, size = 18.dp, tint = ink)
            U2Text(
                stringResource(if (done) DesignR.string.report_address_copied else DesignR.string.report_address_copy),
                label.style,
                color = ink,
                maxLines = 1,
            )
        }
    }
}
