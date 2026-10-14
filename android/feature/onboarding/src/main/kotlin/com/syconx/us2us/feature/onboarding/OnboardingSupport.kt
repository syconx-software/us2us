package com.syconx.us2us.feature.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Timing
import com.syconx.us2us.ui.motion.u2Spec
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text

// Helpers only 01–02 use; nothing outside the onboarding feature depends on them.

// MARK: - Test tags (the iOS accessibility identifiers)

/** Test tags of the onboarding screens: the iOS accessibility identifiers, plus a few layout probes. */
object OnboardingTags {
    const val START = "welcome.start"
    const val PAGE2_CTA = "welcome.page2.cta"
    const val PAGE3_CTA = "welcome.page3.cta"
    const val RESTORE = "welcome.restore"
    const val HEADER = "welcome.header"
    const val PAGE_DOTS = "welcome.pageDots"
    const val WELCOME_TITLE = "welcome.title"
    const val WELCOME_BODY = "welcome.body"
    const val CLOCK = "welcome.clock"
    const val GHOST_CARD = "welcome.ghostCard"
    const val NOTIFICATIONS_TITLE = "welcome.notifications.title"
    const val LEGEND = "welcome.legend"
    const val GHOST_KICKER = "welcome.ghost.kicker"
    const val GHOST_METER = "welcome.ghost.meter"
    const val GHOST_TITLE = "welcome.ghost.title"
    const val FEATURES = "welcome.ghost.features"

    const val USERNAME_FIELD = "onboarding.username.field"
    const val USERNAME_STATUS = "onboarding.username.status"
    const val USERNAME_COUNTER = "onboarding.username.counter"
    const val USERNAME_CONTINUE = "onboarding.username.continue"
    const val EXAMPLE_CARD = "onboarding.username.example"
    const val CODE_DOTS = "onboarding.code.dots"
    const val CODE_PROBLEM = "onboarding.code.problem"
    const val KEYPAD = "onboarding.code.keypad"
    const val FACTS = "onboarding.facts"
    const val FACE_ID = "onboarding.faceID"
    const val REPLACES_THIS_PHONE = "onboarding.replacesThisPhone"
    const val ERROR = "onboarding.error"
    const val ACKNOWLEDGE = "onboarding.acknowledge"
    const val FINISH = "onboarding.finish"

    fun rule(index: Int) = "onboarding.username.rule.$index"

    /** The setup header of step [step] (2.1 → 1): every step on the stack stays composed. */
    fun header(step: Int) = "onboarding.$step.header"

    /** The title block of step [step]. */
    fun title(step: Int) = "onboarding.$step.title"
}

// MARK: - Motion

/** [timing] as a finite spec, or the short cross-fade under reduce motion (transitions and cross-fades). */
@Composable
internal fun <T> softSpec(timing: U2Timing = U2Motion.state): FiniteAnimationSpec<T> =
    U2Motion.resolveFinite(timing.spec(), LocalReduceMotion.current)

// MARK: - Dashed outlines (1.2 notification card, 1.3 view-once badge)

/**
 * A CSS `border: 1.5px dashed` drawn inside [shape] *as the reference shows it*: Chrome at 3x
 * snapped the border to 3 device px (1 dp, and laid the content out with a 1 dp border), dashes
 * ≈ 3 dp on, 2 dp off. [phase] is where along the outline the first dash starts.
 */
internal fun Modifier.dashedBorder(
    shape: Shape,
    color: Color,
    lineWidth: Dp = 1.dp,
    dash: FloatArray = floatArrayOf(3f, 2f),
    phase: Float = 0f,
): Modifier = drawBehind {
    val width = lineWidth.toPx()
    val inset = width / 2f
    val outline = shape.createOutline(Size(size.width - width, size.height - width), layoutDirection, this)
    val intervals = FloatArray(dash.size) { dash[it] * density }
    translate(inset, inset) {
        drawOutline(outline, color, style = Stroke(width = width, pathEffect = PathEffect.dashPathEffect(intervals, phase * density)))
    }
}

// MARK: - Bullets, legends, feature rows

/**
 * 2.1 rules: a 4 × 4 `accent400` dot, gap 10, `bullet` (Inter 400 13.5/18.9). The text tints
 * `danger` while the rule is broken (0.36 s cross-fade); TalkBack hears “karşılanmıyor” /
 * “not met” after it.
 */
@Composable
internal fun OnboardingRuleRow(text: String, isBroken: Boolean, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val ink by animateColorAsState(if (isBroken) colors.danger else colors.textSecondary, u2Spec(U2Motion.state), label = "ruleInk")
    val broken = stringResource(R.string.onboarding_a11y_onboarding_username_rule_broken)
    Row(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { if (isBroken) stateDescription = broken },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(4.dp).clearAndSetSemantics {}) { drawCircle(colors.accent400) }
        U2Text(text, U2TextStyles.bullet, color = ink, modifier = Modifier.weight(1f))
    }
}

/**
 * 1.3 feature row: a 20 dp icon slot (18 dp `accent300` glyph at its leading edge), gap 10,
 * `legend` (Inter 400 14/18.9) in `text`.
 */
@Composable
internal fun OnboardingFeatureRow(text: String, modifier: Modifier = Modifier, icon: @Composable () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 20.dp, height = 18.dp).clearAndSetSemantics {}, contentAlignment = Alignment.CenterStart) { icon() }
        U2Text(text, U2TextStyles.legend, color = U2Theme.colors.text, modifier = Modifier.weight(1f))
    }
}

/**
 * The 1.3 view-once mark: an 18 dp circle with a dashed `accent300` ring around “1” (`monoMicro`,
 * Mono 400 9). As drawn: 1 dp, 12 thin dashes on the 53.4 dp centre line (2.67 on / 1.78 off), one
 * centred at the top; 1 dp margins on both sides.
 */
@Composable
internal fun ViewOnceDashedBadge(modifier: Modifier = Modifier) {
    val ink = U2Theme.colors.accent300
    Box(
        modifier
            .padding(horizontal = 1.dp)
            .size(18.dp)
            // The circle path starts at 3 o'clock; 12 dashes put one centred there and therefore one at 12 o'clock.
            .dashedBorder(CircleShape, ink, 1.dp, floatArrayOf(2.67f, 1.78f), phase = 1.335f),
        contentAlignment = Alignment.Center,
    ) {
        U2Text("1", U2TextStyles.monoMicro, color = ink)
    }
}

// MARK: - Large text

/**
 * The design's layout as long as it fits; with very large text (body text grows, Doto headings do
 * not) the same content scrolls instead of running off the bottom, and its weighted spacers
 * collapse. The content is a column at least as tall as the space offered: a `weight` spacer fills
 * the rest while it fits.
 */
@Composable
internal fun OnboardingFitOrScroll(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize()) {
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
