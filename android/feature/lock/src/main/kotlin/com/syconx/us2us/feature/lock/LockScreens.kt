package com.syconx.us2us.feature.lock

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.Biometry
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.CodeDots
import com.syconx.us2us.ui.components.ConfirmationTags
import com.syconx.us2us.ui.components.DotGridBackdrop
import com.syconx.us2us.ui.components.DotGridMask
import com.syconx.us2us.ui.components.EraseConfirmation
import com.syconx.us2us.ui.components.FaceIdGlyph
import com.syconx.us2us.ui.components.KeypadBottomLeading
import com.syconx.us2us.ui.components.LineMark
import com.syconx.us2us.ui.components.LineMarkMetrics
import com.syconx.us2us.ui.components.NumericKeypad
import com.syconx.us2us.ui.components.OptionCard
import com.syconx.us2us.ui.components.OptionCardTone
import com.syconx.us2us.ui.components.PulseRings
import com.syconx.us2us.ui.components.U2ButtonLabel
import com.syconx.us2us.ui.components.U2SecondaryButton
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.components.U2Wordmark
import com.syconx.us2us.ui.components.text.U2DisplayText
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.env.u2Top
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.l10n.U2Format
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Timing
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.u2PressEffect
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.theme.U2BoxShadows
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2BoxShadow
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.delay

/** [timing] as a finite spec, or the short cross-fade under reduce motion. */
@Composable
internal fun <T> softSpec(timing: U2Timing = U2Motion.state): FiniteAnimationSpec<T> =
    U2Motion.resolveFinite(timing.spec(), LocalReduceMotion.current)

// MARK: - 3.1 · Face ID

/**
 * 3.1 · Face ID — the lock while the biometric check runs.
 *
 * Dot grid (`text·14%`, 70 % × 38 % at 50 % / 46 %); brand block at y 112 (line mark 52 × 14, gap 12,
 * wordmark Doto 24); the 116 dp disc (`accent900`, 1 dp `accent800` ring, glow
 * `0 0 70px -12px accent·60%`) with the 56 dp Face ID glyph (stroke 1.3, `accent300`) and the two
 * soft scanning rings (2.8 s ease-out, 1.4 s apart — none under reduce motion); “Face ID ile
 * açılıyor” + “Tanımazsa gizli kodun istenir” 28 dp below; C2 “Kodla aç” with `ph:password` at
 * the bottom (pad 0 24 44).
 */
@Composable
internal fun LockBiometricScreen(flow: LockFlowModel, prompt: BiometricPromptText) {
    val colors = U2Theme.colors
    U2Screen(background = Color.Transparent) {
        DotGridBackdrop(mask = DotGridMask.Lock, color = colors.dotGridSoft)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier
                    .u2Top(112.dp)
                    .testTag(LockTags.BRAND)
                    .clearAndSetSemantics { contentDescription = "us2us" },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LineMark(metrics = LineMarkMetrics.Lock)
                U2Wordmark(style = U2TextStyles.brandWordmarkL)
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(bottom = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
            ) {
                BiometricDisc(flow, prompt)
                Column(
                    Modifier
                        .padding(horizontal = U2Spacing.screenGutterL)
                        .testTag(LockTags.STATUS),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    U2Text(
                        stringResource(R.string.android_biometric_unlocking),
                        U2TextStyles.lockStatus,
                        color = colors.text,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading() },
                    )
                    U2Text(stringResource(R.string.lock_faceid_subtitle), U2TextStyles.preview, color = colors.textSecondary, textAlign = TextAlign.Center)
                }
            }
            U2SecondaryButton(
                onClick = flow::useCode,
                modifier = Modifier
                    .padding(horizontal = U2Spacing.screenGutterL)
                    .u2Bottom(U2Spacing.ctaBottom)
                    .testTag(LockTags.USE_CODE),
            ) {
                U2ButtonLabel(stringResource(R.string.lock_faceid_usecode), glyph = PhosphorGlyph.Password)
            }
        }
    }
}

/** The disc is also a button: tapping it retries the scan (TalkBack: “Face ID ile aç”). It presses like a key (0.97) without dimming. */
@Composable
private fun BiometricDisc(flow: LockFlowModel, prompt: BiometricPromptText) {
    val colors = U2Theme.colors
    val label = stringResource(R.string.android_biometric_unlock)
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(U2Size.faceIdDisc)
            .u2PressEffect(source)
            .u2BoxShadow(CircleShape, U2BoxShadows.glowFaceID)
            .background(colors.accent900, CircleShape)
            .u2InnerStroke(colors.accent800, 1.dp, CircleShape)
            .clickable(source, indication = null) { flow.startScan(prompt) }
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .testTag(LockTags.BIOMETRIC_DISC),
        contentAlignment = Alignment.Center,
    ) {
        PulseRings(diameter = U2Size.faceIdDisc)
        // The design's own glyph for every kind of biometrics (Android names neither Face ID nor Touch ID).
        FaceIdGlyph(size = 56.dp, lineWidth = 1.3f, color = colors.accent300)
    }
}

// MARK: - 3.2 · Kod

/**
 * 3.2 · Kod — the code keypad on the lock.
 *
 * Top block at y 106 (lock badge 52, gap 16, “Gizli kodunu gir” Doto 30); C10 dots 36 below (all
 * `danger` + a soft shake on a wrong code); the sentence (`calloutL`, `danger`, max 300 wide); C11
 * keypad with the Face ID key bottom-left at y 414; C3 “Kodu unuttum” (pad 0 24 40). While a wait
 * runs the dots dim and the keypad fades to 45 % (0.36 s) with a live `mm:ss`.
 */
@Composable
internal fun LockCodeScreen(flow: LockFlowModel, prompt: BiometricPromptText, showsDemoHint: Boolean) {
    val code = flow.code
    val colors = U2Theme.colors
    U2Screen(background = Color.Transparent) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .u2Top(106.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LockBadge(PhosphorGlyph.LockSimple, colors.accent300, colors.accent900, colors.accent800)
                // One line in Turkish (as drawn); wraps inside the 24 dp gutters when a language needs it.
                U2DisplayText(
                    stringResource(R.string.lock_code_title),
                    U2TextStyles.displayTitleXS.balanced,
                    textAlign = TextAlign.Center,
                    isHeader = true,
                    modifier = Modifier
                        .padding(horizontal = U2Spacing.screenGutterL)
                        .testTag(LockTags.TITLE),
                )
            }
            Column(
                Modifier.padding(top = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CodeDots(
                    filled = code.entered.length,
                    appearance = code.appearance,
                    shakeTrigger = code.shakeTrigger,
                    modifier = Modifier.testTag(LockTags.DOTS),
                )
                Box(
                    Modifier
                        .widthIn(max = 300.dp)
                        .heightIn(min = 42.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    LockMessage(code, frozen = flow.isFrozenForHarness)
                }
            }
            Spacer(Modifier.height(12.dp))
            Spacer(Modifier.weight(1f))
            // Behind the 3.3 sheets only the top block and the dots stay (dimmed by the backdrop); the
            // keypad and “Kodu unuttum” fade out with it, so the shorter “Her şey silinsin mi?” sheet
            // shows nothing else above it.
            val bottomAlpha by animateFloatAsState(if (flow.showsForgot) 0f else 1f, softSpec(U2Motion.state), label = "lockBottom")
            Column(
                Modifier.graphicsLayer { alpha = bottomAlpha },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NumericKeypad(
                    onDigit = code::type,
                    onDelete = code::deleteLast,
                    bottomLeading = if (flow.biometry == Biometry.None) KeypadBottomLeading.Empty else KeypadBottomLeading.FaceId { flow.useBiometrics(prompt) },
                    enabled = !code.isThrottled,
                    modifier = Modifier
                        .padding(bottom = 14.dp)
                        .testTag(LockTags.KEYPAD),
                )
                U2TextButton(
                    title = stringResource(R.string.lock_code_forgot),
                    onClick = flow::openForgot,
                    tone = U2TextButtonTone.Accent,
                    height = U2Size.textButtonS,
                    modifier = Modifier
                        .padding(horizontal = U2Spacing.screenGutterL)
                        .u2Bottom(U2Spacing.ctaBottomOnboarding)
                        .testTag(LockTags.FORGOT),
                )
            }
        }
        if (showsDemoHint) {
            // The fixtures' demo code, outside the layout (hidden in design-comparison runs with a frozen clock).
            U2Text(
                "demo ${DesignFixtures.DEMO_SECRET_CODE}",
                U2TextStyles.monoMicro,
                color = colors.textTertiary,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp)
                    .testTag(LockTags.DEMO_HINT),
            )
        }
    }
}

/** The 52 dp lock badge: a filled circle with an optional 1 dp ring and a 24 dp glyph. */
@Composable
private fun LockBadge(glyph: PhosphorGlyph, tint: Color, fill: Color, ring: Color?) {
    Box(
        Modifier
            .size(U2Size.lockIcon)
            .background(fill, CircleShape)
            .then(if (ring != null) Modifier.u2InnerStroke(ring, 1.dp, CircleShape) else Modifier)
            .testTag(LockTags.BADGE)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        PhosphorIcon(glyph, size = 24.dp, tint = tint)
    }
}

/** The sentence under the dots, cross-fading; the wait counts down live. */
@Composable
private fun LockMessage(code: LockCodeModel, frozen: Boolean) {
    val locale = currentAppLocale()
    Crossfade(targetState = code.message, animationSpec = softSpec(U2Motion.state), label = "lockMessage") { message ->
        when (message) {
            null -> Box(Modifier)
            LockCodeModel.Message.WrongPlain -> LockSentence(stringResource(R.string.lock_code_wrong))
            is LockCodeModel.Message.Wrong -> LockSentence(
                pluralStringResource(
                    R.plurals.lock_code_wrong_warning,
                    message.attemptsLeft,
                    message.attemptsLeft,
                    LockFormat.waitDuration(message.delay, locale),
                ),
            )
            is LockCodeModel.Message.Throttled -> LockCountdown(message.until, frozen, code.now) { remaining ->
                LockSentence(stringResource(R.string.lock_code_throttled, U2Format.minutesSeconds(remaining)))
            }
            LockCodeModel.Message.Unavailable -> LockSentence(stringResource(R.string.lock_code_unavailable))
            LockCodeModel.Message.Unusable -> LockSentence(stringResource(R.string.lock_code_unusable))
        }
    }
}

@Composable
private fun LockSentence(text: String) {
    U2Text(
        text,
        U2TextStyles.calloutL,
        color = U2Theme.colors.danger,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag(LockTags.MESSAGE),
    )
}

/**
 * The live wait: re-renders every second with the real time (the wait is measured by the vault, not
 * the design clock). Frozen in the harness at one minute.
 */
@Composable
private fun LockCountdown(until: Instant, frozen: Boolean, now: () -> Instant, content: @Composable (Duration) -> Unit) {
    if (frozen) {
        content(60.seconds)
        return
    }
    var remaining by remember(until) { mutableStateOf(java.time.Duration.between(now(), until).toKotlinDuration()) }
    LaunchedEffect(until) {
        while (true) {
            remaining = java.time.Duration.between(now(), until).toKotlinDuration()
            if (remaining <= Duration.ZERO) break
            // Wake at the next whole second of the remaining time.
            val step = remaining.inWholeMilliseconds % 1_000
            delay(if (step == 0L) 1_000L else step)
        }
    }
    content(remaining.coerceAtLeast(Duration.ZERO))
}

// MARK: - 3.3 · Kodu unuttum

/**
 * 3.3 · Kodu unuttum — the floating sheet over the dimmed lock.
 *
 * No recovery path: only the real options. Pad 18 18 28 18 (the grabber is the sheet's), gap 18: “Kod
 * kurtarılamaz.” (Doto 30, Inter “.”) and the paragraph (`sheetBody`, `text·68%`); two option cards
 * (gap 10); C2 “Tekrar dene”.
 *
 * “Her şeyi sil, baştan başla” asks a second time in the same sheet: the content cross-fades to “Her
 * şey silinsin mi?” with a danger outline button and “Vazgeç”; the sheet's height follows softly
 * (0.5 s, the design curve).
 */
@Composable
internal fun LockForgotSheet(flow: LockFlowModel) {
    val sizeSpec = softSpec<androidx.compose.ui.unit.IntSize>(U2Motion.screen)
    val fadeIn = U2Transitions.fade().enter
    val fadeOut = U2Transitions.fade().exit
    AnimatedContent(
        targetState = flow.forgotStep,
        modifier = Modifier
            .fillMaxWidth()
            .padding(PaddingValues(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 28.dp))
            .testTag(LockTags.FORGOT_SHEET),
        transitionSpec = { (fadeIn togetherWith fadeOut).using(SizeTransform(clip = false) { _, _ -> sizeSpec }) },
        contentAlignment = Alignment.TopCenter,
        label = "lockForgot",
    ) { step ->
        when (step) {
            LockFlowModel.ForgotStep.Options -> ForgotOptions(flow)
            LockFlowModel.ForgotStep.ConfirmErase -> EraseConfirmation(
                onConfirm = flow::confirmErase,
                onCancel = flow::cancelErase,
                isWorking = flow.isErasing,
                tags = ConfirmationTags(LockTags.ERASE_CONFIRM, LockTags.ERASE_CANCEL),
            )
        }
    }
}

@Composable
private fun ForgotOptions(flow: LockFlowModel) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        // Text block: pad 6 4 0 4, gap 10. The title's Inter 700 “.” grows its CSS line box.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 6.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            U2DisplayText(
                stringResource(R.string.lock_forgot_title),
                U2TextStyles.displaySheetTitle.balanced,
                isHeader = true,
                modifier = Modifier.testTag(LockTags.FORGOT_TITLE),
            )
            U2Text(stringResource(R.string.lock_forgot_body), U2TextStyles.sheetBody, color = U2Theme.colors.textSecondary, modifier = Modifier.fillMaxWidth())
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OptionCard(
                glyph = PhosphorGlyph.FileLock,
                title = stringResource(R.string.lock_forgot_restore_title),
                description = stringResource(R.string.lock_forgot_restore_body),
                onClick = flow::chooseBackup,
                modifier = Modifier.testTag(LockTags.FORGOT_RESTORE),
            )
            OptionCard(
                glyph = PhosphorGlyph.Trash,
                title = stringResource(R.string.lock_forgot_erase_title),
                description = stringResource(R.string.lock_forgot_erase_body),
                onClick = flow::askEraseConfirmation,
                tone = OptionCardTone.Danger,
                modifier = Modifier.testTag(LockTags.FORGOT_ERASE),
            )
        }
        U2SecondaryButton(
            title = stringResource(R.string.lock_forgot_retry),
            onClick = flow::closeForgot,
            modifier = Modifier.testTag(LockTags.FORGOT_RETRY),
        )
    }
}

// MARK: - Veriler silindi

/**
 * After the optional wipe (PROTOCOL §5.2: the 10th wrong code in a row crypto-shreds everything) —
 * shown at once, instead of “Kod yanlış” until the next launch. The design has no screen for it
 * (proposed): 3.2's top block (the 52 dp badge at y 106, here in the danger tint, and the Doto
 * title), the sentence in `calloutL` · `text·68%`, and 3.3's two option cards on a `surface` panel:
 * restore a backup, or start over. Nothing else of the lock stays live.
 */
@Composable
internal fun LockWipedScreen(flow: LockFlowModel) {
    val colors = U2Theme.colors
    U2Screen(background = Color.Transparent) {
        LockFitOrScroll {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = U2Spacing.screenGutterL)
                    .u2Top(106.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LockBadge(PhosphorGlyph.Trash, colors.danger, colors.dangerTint, ring = null)
                U2DisplayText(
                    stringResource(R.string.lock_wiped_title),
                    U2TextStyles.displayTitleXS.balanced,
                    textAlign = TextAlign.Center,
                    isHeader = true,
                )
                U2Text(
                    stringResource(R.string.lock_wiped_body),
                    U2TextStyles.calloutL,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .testTag(LockTags.WIPED_BODY),
                )
            }
            Spacer(Modifier.height(28.dp))
            Spacer(Modifier.weight(1f))
            Column(
                Modifier
                    .padding(horizontal = U2Spacing.screenGutterS)
                    .u2Bottom(U2Spacing.ctaBottomOnboarding)
                    .background(colors.surface, RoundedCornerShape(U2Radius.cardL))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OptionCard(
                    glyph = PhosphorGlyph.FileLock,
                    title = stringResource(R.string.lock_forgot_restore_title),
                    description = stringResource(R.string.lock_wiped_restore_body),
                    onClick = flow::restoreAfterWipe,
                    modifier = Modifier.testTag(LockTags.WIPED_RESTORE),
                )
                OptionCard(
                    glyph = PhosphorGlyph.Plus,
                    title = stringResource(R.string.lock_wiped_startover_title),
                    description = stringResource(R.string.lock_wiped_startover_body),
                    onClick = flow::startOverAfterWipe,
                    modifier = Modifier.testTag(LockTags.WIPED_START_OVER),
                )
            }
        }
    }
}

/** The design's layout while it fits; with very large text the same content scrolls and its weighted spacers collapse. */
@Composable
private fun LockFitOrScroll(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val minHeight = maxHeight
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minHeight),
            content = content,
        )
    }
}
