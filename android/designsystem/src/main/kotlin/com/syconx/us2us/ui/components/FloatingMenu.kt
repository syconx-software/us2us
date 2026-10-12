package com.syconx.us2us.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.syconx.us2us.ui.env.u2Top
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.u2TransitionBlur
import com.syconx.us2us.ui.theme.U2BackdropSource
import com.syconx.us2us.ui.theme.U2BackdropStyle
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2Backdrop
import com.syconx.us2us.ui.theme.u2ShadowMd
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The design's floating menu (5.3 action menu), used everywhere a menu appears: 5.3 actions, the
// 5.1 “+” menu, the 4.1 QR menu and the 4.3 / 4.4 “…” menus.

/** The ink of a menu row. */
enum class FloatingMenuTone { Standard, Destructive }

/**
 * One row of a floating menu: 46 high, pad 0 16, the label `rowTitle` left (an optional
 * `menuHint` under it), a 19 dp glyph right. [startsGroup] puts a 7 dp `text·6%` band above the
 * row instead of a dotted rule. Rows are tagged `menu.<id>` for UI tests.
 */
@Immutable
data class FloatingMenuItem(
    val id: String,
    val title: String,
    val glyph: PhosphorGlyph,
    val hint: String? = null,
    /** `map-pin` is bundled only filled (icons.md). */
    val glyphWeight: PhosphorWeight = PhosphorWeight.Regular,
    val tone: FloatingMenuTone = FloatingMenuTone.Standard,
    val isEnabled: Boolean = true,
    val startsGroup: Boolean = false,
    val action: () -> Unit,
)

/**
 * 5.3: `surface·94%` over a backdrop blur ([backdrop]), r18, `shadowMd`; rows separated by dotted
 * rules (no inset), groups by a 7 dp `fillSubtle` band.
 */
@Composable
fun FloatingMenu(
    items: List<FloatingMenuItem>,
    modifier: Modifier = Modifier,
    width: Dp = 250.dp,
    backdrop: U2BackdropSource? = null,
) {
    val colors = U2Theme.colors
    val shape = RoundedCornerShape(U2Radius.menu)
    Column(
        modifier
            .width(width)
            .u2ShadowMd(shape)
            .clip(shape)
            .u2Backdrop(U2BackdropStyle.Menu, shape, backdrop),
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                if (item.startsGroup) {
                    Box(Modifier.fillMaxWidth().height(FloatingMenuMetrics.groupBand).background(colors.fillSubtle))
                } else {
                    DottedRule(color = colors.dotRule)
                }
            }
            FloatingMenuRow(item)
        }
    }
}

/** Metrics of [FloatingMenu]. */
object FloatingMenuMetrics {
    val rowHeight: Dp = 46.dp
    val ruleHeight: Dp = 2.dp
    val groupBand: Dp = 7.dp

    /** How long a chosen row waits for the closing menu to fade before it acts. */
    const val ACTION_DELAY_MILLIS: Long = 180L

    /** The height of a menu with [items] (rows 46, rules 2, group bands 7). */
    fun height(items: List<FloatingMenuItem>): Dp =
        rowHeight * items.size + items.drop(1).fold(0.dp) { sum, item -> sum + if (item.startsGroup) groupBand else ruleHeight }
}

@Composable
private fun FloatingMenuRow(item: FloatingMenuItem) {
    val colors = U2Theme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val highlight by animateFloatAsState(
        targetValue = if (pressed) 0.07f else 0f,
        animationSpec = (if (pressed) U2Motion.press else U2Motion.release).spec(),
        label = "menuRowPress",
    )
    val ink = when {
        !item.isEnabled -> colors.textDisabled
        item.tone == FloatingMenuTone.Destructive -> colors.danger
        else -> colors.text
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(FloatingMenuMetrics.rowHeight)
            .background(colors.text.copy(alpha = highlight))
            .clickable(source, indication = null, enabled = item.isEnabled, role = Role.Button, onClick = item.action)
            .semantics(mergeDescendants = true) {}
            .testTag("menu.${item.id}")
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            U2Text(item.title, U2TextStyles.rowTitle, color = ink, maxLines = 1)
            if (item.hint != null) U2Text(item.hint, U2TextStyles.menuHint, color = colors.textSecondary, maxLines = 1)
        }
        PhosphorIcon(item.glyph, size = 19.dp, weight = item.glyphWeight, tint = ink)
    }
}


// MARK: - Anchored under a header button

/**
 * A [FloatingMenu] under a header button (4.1 QR, 4.3 / 4.4 “…”). Place it over the whole screen;
 * the menu's top edge sits at design y [designTop], [trailing] dp from the trailing edge. It opens
 * with a fade + scale from 0.96 anchored at the top-trailing corner (the button); a tap outside or
 * back closes it. A chosen row closes the menu first and acts once it has faded (0.18 s), so
 * whatever the row presents never overlaps the fading menu.
 */
@Composable
fun U2AnchoredMenu(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    items: List<FloatingMenuItem>,
    modifier: Modifier = Modifier,
    designTop: Dp = 106.dp,
    trailing: Dp = 16.dp,
    backdrop: U2BackdropSource? = null,
) {
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    val timing = U2Motion.resolve(if (isPresented) U2Motion.state else U2Motion.micro, reduceMotion)
    val origin = TransformOrigin(1f, 0f)
    val pop = U2Transitions.pop(timing)
    if (isPresented) {
        BackHandler(onBack = onDismiss)
        Box(
            modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .clearAndSetSemantics {},
        )
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(
            visible = isPresented,
            enter = if (reduceMotion) fadeIn(timing.spec()) else fadeIn(timing.spec()) + scaleIn(timing.spec(), initialScale = 0.96f, transformOrigin = origin),
            exit = if (reduceMotion) fadeOut(timing.spec()) else fadeOut(timing.spec()) + scaleOut(timing.spec(), targetScale = 0.96f, transformOrigin = origin),
            modifier = Modifier
                .u2Top(designTop)
                .padding(end = trailing),
        ) {
            val closingFirst = remember(items) {
                items.map { item ->
                    item.copy(action = {
                        onDismiss()
                        scope.launch {
                            delay(FloatingMenuMetrics.ACTION_DELAY_MILLIS)
                            item.action()
                        }
                    })
                }
            }
            FloatingMenu(closingFirst, Modifier.then(u2TransitionBlur(pop)), backdrop = backdrop)
        }
    }
}
