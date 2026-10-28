package com.syconx.us2us.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text

// MARK: - C13 · Grouped list (settings style)

/** A titled group: [SectionLabel] 7 dp above a [GroupedCard] (C13). */
@Composable
fun GroupedSection(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionLabelGap)) {
        if (title != null) SectionLabel(title)
        GroupedCard(content = content)
    }
}

/** The card of a group: `surface` (or `bg` on a sheet), r18. Rows inside are separated by [GroupedSeparator]. */
@Composable
fun GroupedCard(
    modifier: Modifier = Modifier,
    fill: Color = U2Theme.colors.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(U2Radius.group)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(fill, shape),
        content = content,
    )
}

/** The dotted separator between grouped rows, inset 57 from the card's left edge ([modifier] applies to the rule itself). */
@Composable
fun GroupedSeparator(modifier: Modifier = Modifier, inset: Dp = U2Spacing.groupedSeparatorInset) {
    DottedRule(Modifier.padding(start = inset).then(modifier))
}

/** What an [IconTile] shows. */
@Immutable
sealed interface IconTileContent {
    data class Glyph(val glyph: PhosphorGlyph) : IconTileContent

    data object FaceId : IconTileContent
}

/** The tone of an [IconTile]. */
enum class IconTileTone {
    /** `accent900` with an inner 1 dp `accent800` and an `accent300` glyph. */
    Accent,

    /** `danger·14%` with a `danger` glyph. */
    Danger,
}

/** C13 icon tile: 30 × 30 r8 `accent900`, inner 1 dp `accent800`, an 18 dp `accent300` glyph. */
@Composable
fun IconTile(
    content: IconTileContent,
    modifier: Modifier = Modifier,
    size: Dp = U2Size.iconTileSettings,
    cornerRadius: Dp = U2Radius.iconTile,
    glyphSize: Dp = 18.dp,
    tone: IconTileTone = IconTileTone.Accent,
    glyphWeight: PhosphorWeight = PhosphorWeight.Regular,
) {
    val colors = U2Theme.colors
    val shape = RoundedCornerShape(cornerRadius)
    val surface = when (tone) {
        IconTileTone.Accent -> Modifier.background(colors.accent900, shape).u2InnerStroke(colors.accent800, 1.dp, shape)
        IconTileTone.Danger -> Modifier.background(colors.dangerTint, shape)
    }
    val ink = if (tone == IconTileTone.Accent) colors.accent300 else colors.danger
    Box(
        modifier
            .size(size)
            .then(surface)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        when (content) {
            is IconTileContent.Glyph -> PhosphorIcon(content.glyph, size = glyphSize, weight = glyphWeight, tint = ink)
            IconTileContent.FaceId -> FaceIdGlyph(size = glyphSize, lineWidth = 1.8f, color = ink)
        }
    }
}

/**
 * C13 row: tile · title (+ subtitle) · trailing detail and chevron, or a switch. At least 48 high
 * (62 with a subtitle); pad 0 14 (9 14 with a subtitle); gap 13.
 *
 * At the design's text size the title column fills and the trailing detail keeps its whole width
 * (the title wraps instead). With larger text the title is laid out first and the detail gets what
 * is left.
 */
@Composable
fun GroupedRow(
    tile: (@Composable () -> Unit)?,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /** The trailing view has a fixed size (a switch): the title always leaves room for it, at any text size. */
    fixedTrailing: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val asDrawn = fixedTrailing || LocalDensity.current.fontScale <= 1f
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = if (subtitle == null) 0.dp else 9.dp),
        horizontalArrangement = Arrangement.spacedBy(U2Spacing.rowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tile?.invoke()
        if (asDrawn) {
            GroupedRowTitle(title, subtitle, Modifier.weight(1f))
        } else {
            GroupedRowTitle(title, subtitle, Modifier)
            // Pushes a trailing check or switch to the edge; a weighted detail (DisclosureDetail)
            // takes nearly all of what the title leaves, so its value is not halved.
            Spacer(Modifier.weight(GroupedRowMetrics.SPACER_WEIGHT))
        }
        trailing()
    }
}

internal object GroupedRowMetrics {
    /** The large-text spacer's share against a weighted trailing detail (1): next to nothing. */
    const val SPACER_WEIGHT = 0.001f
}

@Composable
private fun GroupedRowTitle(title: String, subtitle: String?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        U2Text(title, U2TextStyles.rowTitle, color = U2Theme.colors.text)
        if (subtitle != null) U2Text(subtitle, U2TextStyles.captionCompact, color = U2Theme.colors.textSecondary)
    }
}

/**
 * Trailing detail (`label15`, `text·68%`) and the chevron (`ph:caret-right` 15, `text·40%`), gap 13.
 * At the design's text size the detail never wraps (the row's title does); with larger text it
 * gives way, on one line, truncated.
 */
@Composable
fun RowScope.DisclosureDetail(detail: String? = null, showsChevron: Boolean = true) {
    val asDrawn = LocalDensity.current.fontScale <= 1f
    Row(
        if (asDrawn) Modifier else Modifier.weight(1f),
        horizontalArrangement = Arrangement.spacedBy(13.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (detail != null) {
            U2Text(
                detail,
                U2TextStyles.label15,
                color = U2Theme.colors.textSecondary,
                maxLines = 1,
                softWrap = false,
                overflow = if (asDrawn) TextOverflow.Clip else TextOverflow.Ellipsis,
                modifier = if (asDrawn) Modifier else Modifier.weight(1f, fill = false),
            )
        }
        if (showsChevron) PhosphorIcon(PhosphorGlyph.CaretRight, size = 15.dp, tint = U2Theme.colors.textTertiary)
    }
}

/** A tappable grouped row that pushes a detail screen. Pressed rows tint with `text·6%`. */
@Composable
fun GroupedNavigationRow(
    tile: (@Composable () -> Unit)?,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    detail: String? = null,
) {
    val source = remember { MutableInteractionSource() }
    GroupedRow(
        tile = tile,
        title = title,
        subtitle = subtitle,
        modifier = modifier
            .groupedRowPress(source)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {},
    ) {
        DisclosureDetail(detail)
    }
}

/** A grouped row with a C14 switch; the whole row toggles, with a selection haptic. */
@Composable
fun GroupedToggleRow(
    tile: (@Composable () -> Unit)?,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val haptics = LocalHaptics.current
    val on = stringResource(R.string.common_a11y_on)
    val off = stringResource(R.string.common_a11y_off)
    GroupedRow(
        tile = tile,
        title = title,
        subtitle = subtitle,
        fixedTrailing = true,
        modifier = modifier
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Switch,
            ) {
                haptics.selection()
                onCheckedChange(it)
            }
            .semantics(mergeDescendants = true) { stateDescription = if (checked) on else off },
    ) {
        U2Switch(checked, enabled)
    }
}

/** Pressed grouped rows tint with `text·6%` (soft, 0.16 s in / 0.24 s out). */
@Composable
fun Modifier.groupedRowPress(interactionSource: MutableInteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val tint by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = (if (pressed) U2Motion.press else U2Motion.release).spec(),
        label = "groupedRowPress",
    )
    val fill = U2Theme.colors.fillSubtle
    return drawBehind { if (tint > 0f) drawRect(fill.copy(alpha = fill.alpha * tint)) }
}
