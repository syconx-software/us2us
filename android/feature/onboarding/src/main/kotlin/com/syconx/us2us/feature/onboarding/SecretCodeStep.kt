package com.syconx.us2us.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.CodeDots
import com.syconx.us2us.ui.components.NumericKeypad
import com.syconx.us2us.ui.components.SetupHeader
import com.syconx.us2us.ui.components.text.SetupTitle
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.nav.LocalIsLayerVisible
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text

/**
 * 2.2 · Gizli kod — six digits, auto-advance, asked twice.
 *
 * set (0–6 dots) → after a 0.25 s hold the code is checked: a weak code turns the dots `danger` with a
 * soft shake and a sentence, then they clear (0.8 s); a good one swaps the title and paragraph to the
 * confirm copy (0.36 s cross-fade) and the dots reset. The confirmation advances to 2.3 or, on a
 * mismatch, flashes the error and returns to the set step.
 */
@Composable
internal fun SecretCodeStep(model: SecretCodeStepModel, step: SetupStepLabel, onBack: () -> Unit) {
    // Back on the confirm step returns to the set step (the stack does not pop it, flows.md › Back behaviour).
    BackHandler(enabled = LocalIsLayerVisible.current && model.step == SecretCodeStepModel.Step.Confirm) { model.backToSet() }
    U2Screen {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SetupHeader(step = step.step, onBack = onBack, stepCount = step.count, modifier = Modifier.testTag(OnboardingTags.header(2)))
            CodeTitleBlock(
                model.step,
                Modifier
                    .padding(top = 18.dp)
                    .padding(horizontal = U2Spacing.screenGutterL),
            )
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                CodeDots(
                    filled = model.entered.size,
                    appearance = model.appearance,
                    shakeTrigger = model.shakeTrigger,
                    modifier = Modifier
                        .padding(top = 44.dp)
                        .testTag(OnboardingTags.CODE_DOTS),
                )
                ProblemSentence(model.problem, Modifier.overlayAt(44.dp + U2Size.codeDot + 16.dp))
            }
            Spacer(Modifier.weight(1f))
            NumericKeypad(
                onDigit = model::type,
                onDelete = model::delete,
                modifier = Modifier
                    .u2Bottom(56.dp)
                    .testTag(OnboardingTags.KEYPAD),
            )
        }
    }
}

/**
 * Title + paragraph of the current step, cross-fading; the block keeps the taller step's height so
 * the dots never move.
 */
@Composable
private fun CodeTitleBlock(step: SecretCodeStepModel.Step, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().testTag(OnboardingTags.title(2))) {
        for (each in SecretCodeStepModel.Step.entries) {
            CodeCopy(each, Modifier.alpha(0f).clearAndSetSemantics {})
        }
        Crossfade(targetState = step, animationSpec = softSpec(U2Motion.state), label = "codeStep") { shown ->
            CodeCopy(shown)
        }
    }
}

@Composable
private fun CodeCopy(step: SecretCodeStepModel.Step, modifier: Modifier = Modifier) {
    val set = step == SecretCodeStepModel.Step.Set
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(U2Spacing.titleToBodyGap)) {
        SetupTitle(if (set) R.string.onboarding_code_title else R.string.onboarding_code_confirm_title, U2TextStyles.displayTitleM)
        U2Text(
            stringResource(if (set) R.string.onboarding_code_body else R.string.onboarding_code_confirm_body),
            U2TextStyles.body,
            color = U2Theme.colors.textSecondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Under the dots (gap 16, as 3.2): `calloutL`, `danger`, centred, at most 300 wide. It fades in
 * 0.36 s and stays until the next digit; it is announced.
 */
@Composable
private fun ProblemSentence(problem: SecretCodeStepModel.Problem?, modifier: Modifier = Modifier) {
    Box(modifier.width(300.dp).semantics { liveRegion = LiveRegionMode.Assertive }, contentAlignment = Alignment.TopCenter) {
        Crossfade(targetState = problem, animationSpec = softSpec(U2Motion.state), label = "codeProblem") { shown ->
            if (shown != null) {
                U2Text(
                    stringResource(if (shown == SecretCodeStepModel.Problem.Weak) R.string.onboarding_code_weak else R.string.onboarding_code_mismatch),
                    U2TextStyles.calloutL,
                    color = U2Theme.colors.danger,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(OnboardingTags.CODE_PROBLEM),
                )
            }
        }
    }
}

/** Places the content [y] below the top of its box without taking any room there (an overlay, like iOS `.overlay` + `.offset`). */
internal fun Modifier.overlayAt(y: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
    layout(placeable.width, 0) { placeable.place(0, y.roundToPx()) }
}
