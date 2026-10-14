package com.syconx.us2us.feature.onboarding

import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.UsernameRules
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.MonoKicker
import com.syconx.us2us.ui.components.SetupHeader
import com.syconx.us2us.ui.components.U2Card
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.UsernameField
import com.syconx.us2us.ui.components.text.SetupTitle
import com.syconx.us2us.ui.env.rememberU2Keyboard
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlin.math.max

/**
 * 2.1 · Kullanıcı adı — pick the username; every rule it breaks is marked while typing,
 * availability is checked once it is valid (debounced; the live service can only say it meets the
 * rules), exact-match discovery is explained.
 *
 * The keyboard does not move the layout (the design is drawn without it): the keyboard's action key
 * continues when the name can be used, and a tap outside the field hides the keyboard to reveal
 * the button.
 */
@Composable
internal fun UsernameStep(
    model: UsernameStepModel,
    step: SetupStepLabel,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    focusesOnAppear: Boolean = true,
    showsFocusChrome: Boolean = false,
) {
    val keyboard = rememberU2Keyboard()
    U2Screen(avoidsKeyboard = false) {
        OnboardingFitOrScroll {
            SetupHeader(step = step.step, onBack = onBack, stepCount = step.count, modifier = Modifier.testTag(OnboardingTags.header(1)))
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp)
                    .padding(horizontal = U2Spacing.screenGutterL),
                verticalArrangement = Arrangement.spacedBy(U2Spacing.titleToBodyGap),
            ) {
                SetupTitle(R.string.onboarding_username_title, U2TextStyles.displayTitleM, modifier = Modifier.testTag(OnboardingTags.title(1)))
                U2Text(stringResource(R.string.onboarding_username_body), U2TextStyles.body, color = U2Theme.colors.textSecondary, modifier = Modifier.fillMaxWidth())
            }

            Column(
                Modifier
                    .padding(top = 22.dp)
                    .padding(horizontal = U2Spacing.screenGutter),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UsernameField(
                    text = model.text,
                    onTextChange = { model.text = it },
                    status = model.fieldStatus,
                    prompt = stringResource(R.string.onboarding_username_placeholder),
                    focusesOnAppear = focusesOnAppear,
                    showsFocusChrome = showsFocusChrome,
                    onSubmit = onContinue,
                    modifier = Modifier.testTag(OnboardingTags.USERNAME_FIELD),
                )
                UsernameStatusRow(model)
            }

            Column(
                Modifier
                    .padding(top = 14.dp)
                    .padding(horizontal = U2Spacing.screenGutterL),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val rules = listOf(
                    R.string.onboarding_username_rule_length to UsernameRules.Violation.Length,
                    R.string.onboarding_username_rule_firstletter to UsernameRules.Violation.FirstLetter,
                    R.string.onboarding_username_rule_characters to UsernameRules.Violation.Characters,
                    R.string.onboarding_username_rule_turkish to UsernameRules.Violation.TurkishCharacters,
                )
                rules.forEachIndexed { index, (text, rule) ->
                    OnboardingRuleRow(stringResource(text), isBroken = model.isBroken(rule), modifier = Modifier.testTag(OnboardingTags.rule(index)))
                }
            }

            ExactMatchCard(
                name = if (model.canShowOwnExample) model.name else null,
                modifier = Modifier
                    .padding(top = 22.dp)
                    .padding(horizontal = U2Spacing.screenGutter)
                    .testTag(OnboardingTags.EXAMPLE_CARD),
            )

            Spacer(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) { detectTapGestures { keyboard.dismiss() } },
            )

            U2PrimaryButton(
                title = stringResource(R.string.onboarding_continue),
                onClick = onContinue,
                enabled = model.canContinue,
                modifier = Modifier
                    .padding(horizontal = U2Spacing.screenGutter)
                    .u2Bottom(U2Spacing.ctaBottom)
                    .testTag(OnboardingTags.USERNAME_CONTINUE),
            )
        }
    }
}

/**
 * “Kurallara uygun” / “Kullanılabilir” / “Alınmış” / “Kontrol ediliyor…” / the first broken rule
 * (`caption`) left, “7 / 24” (`monoCounter`) right. The label cross-fades (0.36 s) and is announced.
 */
@Composable
private fun UsernameStatusRow(model: UsernameStepModel) {
    val label = statusLabel(model.status, model.name)
    val counterDescription = stringResource(R.string.onboarding_a11y_onboarding_username_counter, UsernameRules.length(model.name))
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(20.15.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            contentAlignment = Alignment.TopStart,
        ) {
            Crossfade(targetState = label, animationSpec = softSpec(U2Motion.state), label = "usernameStatus") { shown ->
                if (shown != null) {
                    U2Text(
                        stringResource(shown.text),
                        U2TextStyles.caption,
                        color = shown.tone.color(),
                        modifier = Modifier.testTag(OnboardingTags.USERNAME_STATUS),
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        U2Text(
            model.counter,
            U2TextStyles.monoCounter,
            color = U2Theme.colors.textSecondary,
            modifier = Modifier
                .testTag(OnboardingTags.USERNAME_COUNTER)
                .clearAndSetSemantics { contentDescription = counterDescription },
        )
    }
}

/** The status line's text and ink. */
internal data class UsernameStatusLabel(@param:StringRes val text: Int, val tone: Tone) {
    enum class Tone {
        Positive,
        Negative,
        Neutral,
        ;

        @Composable
        fun color(): Color = when (this) {
            Positive -> U2Theme.colors.online
            Negative -> U2Theme.colors.danger
            Neutral -> U2Theme.colors.textSecondary
        }
    }
}

internal fun statusLabel(status: UsernameStepModel.Status, name: String): UsernameStatusLabel? = when (status) {
    UsernameStepModel.Status.Available -> UsernameStatusLabel(R.string.onboarding_username_status_available, UsernameStatusLabel.Tone.Positive)
    UsernameStepModel.Status.MeetsRules -> UsernameStatusLabel(R.string.onboarding_username_status_meetsrules, UsernameStatusLabel.Tone.Positive)
    UsernameStepModel.Status.Taken -> UsernameStatusLabel(R.string.onboarding_username_status_taken, UsernameStatusLabel.Tone.Negative)
    UsernameStepModel.Status.Checking -> UsernameStatusLabel(R.string.onboarding_username_status_checking, UsernameStatusLabel.Tone.Neutral)
    UsernameStepModel.Status.TooShort -> UsernameStatusLabel(R.string.onboarding_username_status_tooshort, UsernameStatusLabel.Tone.Negative)
    is UsernameStepModel.Status.Invalid -> UsernameStatusLabel(violationText(status.violation, name), UsernameStatusLabel.Tone.Negative)
    UsernameStepModel.Status.Empty, UsernameStepModel.Status.Typing -> null
}

/** The status line for the first rule the name breaks. */
@StringRes
internal fun violationText(violation: UsernameRules.Violation, name: String): Int = when (violation) {
    UsernameRules.Violation.TurkishCharacters -> R.string.onboarding_username_status_turkish
    UsernameRules.Violation.Characters -> R.string.onboarding_username_status_invalid
    UsernameRules.Violation.FirstLetter -> R.string.onboarding_username_status_firstletter
    UsernameRules.Violation.Length ->
        if (UsernameRules.isTooShort(name)) R.string.onboarding_username_status_tooshort else R.string.onboarding_username_status_toolong
}

/**
 * The “TAM EŞLEŞME” card: a shortened handle is not found, the exact one is (`monoMatch` handles,
 * `monoMatchStatus` results with 16 dp icons), and the footnote. With a usable typed name it
 * illustrates that name; otherwise the design's own example (@selin / @selin_n).
 */
@Composable
private fun ExactMatchCard(name: String?, modifier: Modifier = Modifier) {
    val exact = name ?: "selin_n"
    val partial = if (name == null) {
        "selin"
    } else {
        // The exact name cut at its last underscore (or by a third), never empty.
        val cut = name.lastIndexOf('_')
        if (cut > 0) name.substring(0, cut) else name.dropLast(max(1, name.length / 3))
    }
    U2Card(
        modifier = modifier.semantics(mergeDescendants = true) {},
        cornerRadius = 16.dp,
        padding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MonoKicker(stringResource(R.string.onboarding_username_example_kicker))
            MatchGrid(
                cells = listOf(
                    { MatchHandle(partial) },
                    { MatchResult(found = false) },
                    { MatchHandle(exact) },
                    { MatchResult(found = true) },
                ),
            )
            U2Text(
                stringResource(R.string.onboarding_username_example_footnote),
                U2TextStyles.captionTight,
                color = U2Theme.colors.textSecondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MatchHandle(handle: String) {
    U2Text("@$handle", U2TextStyles.monoMatch, color = U2Theme.colors.text, maxLines = 1, softWrap = false, overflow = TextOverflow.MiddleEllipsis)
}

@Composable
private fun MatchResult(found: Boolean) {
    val ink = if (found) U2Theme.colors.online else U2Theme.colors.danger
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        PhosphorIcon(if (found) PhosphorGlyph.CheckCircle else PhosphorGlyph.XCircle, size = 16.dp, tint = ink)
        U2Text(
            stringResource(if (found) R.string.onboarding_username_example_found else R.string.onboarding_username_example_notfound),
            U2TextStyles.monoMatchStatus,
            color = ink,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Two rows of handle · result, as a grid: the results share one column (as wide as the wider result,
 * leading-aligned), the handles take the rest; each row is as high as its taller cell, cells centred;
 * rows 10 apart.
 */
@Composable
private fun MatchGrid(cells: List<@Composable () -> Unit>) {
    Layout(contents = cells) { measurables, constraints ->
        val rowGap = 10.dp.roundToPx()
        val results = listOf(measurables[1].first(), measurables[3].first())
            .map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
        val column = results.maxOf { it.width }
        val handleWidth = (constraints.maxWidth - column).coerceAtLeast(0)
        val handles = listOf(measurables[0].first(), measurables[2].first())
            .map { it.measure(Constraints(maxWidth = handleWidth)) }
        val rowHeights = handles.indices.map { max(handles[it].height, results[it].height) }
        val height = rowHeights.sum() + rowGap
        layout(constraints.maxWidth, height) {
            var y = 0
            for (row in handles.indices) {
                val rowHeight = rowHeights[row]
                handles[row].place(0, y + (rowHeight - handles[row].height) / 2)
                results[row].place(handleWidth, y + (rowHeight - results[row].height) / 2)
                y += rowHeight + rowGap
            }
        }
    }
}
