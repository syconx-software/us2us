package com.syconx.us2us.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.collectIsPressedAsState
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.motion.u2PressEffect
import com.syconx.us2us.ui.theme.U2BackdropSource
import com.syconx.us2us.ui.theme.U2BackdropStyle
import com.syconx.us2us.ui.theme.U2BoxShadows
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2Backdrop
import com.syconx.us2us.ui.theme.u2BoxShadow
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text

/** A plain design card: `surface`, r18 (or r16 / r20), padded 14 16, as wide as offered. */
@Composable
fun U2Card(
    modifier: Modifier = Modifier,
    fill: Color = U2Theme.colors.surface,
    cornerRadius: Dp = U2Radius.card,
    padding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(fill, RoundedCornerShape(cornerRadius))
            .padding(padding),
        content = content,
    )
}

/** The tone of an option card's tile and title. */
enum class OptionCardTone {
    /** `accent900` tile with an `accent800` ring and an `accent300` glyph. */
    Accent,

    /** `danger·14%` tile, `danger` glyph and title. */
    Danger,
}

/**
 * The 3.3 option card: `bg` r18, pad 14 14 14 16, gap 14: an icon tile 40 r12 · the title
 * `rowTitleEmphasis` over the description `captionTighter` (`text·68%`) · a chevron 15 `text·40%`.
 * It presses softly (scale 0.98 and a slight dim).
 */
@Composable
fun OptionCard(
    glyph: PhosphorGlyph,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: OptionCardTone = OptionCardTone.Accent,
    glyphWeight: PhosphorWeight = PhosphorWeight.Regular,
) {
    val colors = U2Theme.colors
    val shape = RoundedCornerShape(U2Radius.card)
    Row(
        modifier
            .fillMaxWidth()
            .u2CardPress(onClick)
            .background(colors.bg, shape)
            .padding(start = 16.dp, top = 14.dp, end = 14.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(
            IconTileContent.Glyph(glyph),
            size = U2Size.iconTileSheetOption,
            cornerRadius = U2Radius.iconTileXL,
            glyphSize = 21.dp,
            tone = if (tone == OptionCardTone.Danger) IconTileTone.Danger else IconTileTone.Accent,
            glyphWeight = glyphWeight,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            U2Text(title, U2TextStyles.rowTitleEmphasis, color = if (tone == OptionCardTone.Danger) colors.danger else colors.text)
            U2Text(description, U2TextStyles.captionTighter, color = colors.textSecondary)
        }
        PhosphorIcon(PhosphorGlyph.CaretRight, size = 15.dp, tint = colors.textTertiary)
    }
}

/**
 * Cards press softly: scale 0.98 and 85 % opacity while pressed (`CardButtonStyle`). Makes the
 * card a button that runs [onClick].
 */
@Composable
fun Modifier.u2CardPress(onClick: () -> Unit, enabled: Boolean = true): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    return this
        .u2PressEffect(source, scale = 0.98f)
        .u2Opacity(if (pressed) 0.85f else 1f)
        .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) {}
}

/**
 * The 5.4 note card: transparent, r20, inner 1 dp `danger·40%`, pad 16, gap 14: a 22 dp glyph,
 * the title `noteTitle` and the body `captionTight`.
 */
@Composable
fun NoteCard(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    glyph: PhosphorGlyph = PhosphorGlyph.WarningCircle,
) {
    val colors = U2Theme.colors
    Row(
        modifier
            .semantics(mergeDescendants = true) {}
            .fillMaxWidth()
            .u2InnerStroke(colors.dangerStrokeSoft, 1.dp, U2Radius.cardL)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        PhosphorIcon(glyph, size = 22.dp, tint = colors.danger)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            U2Text(title, U2TextStyles.noteTitle, color = colors.text)
            U2Text(message, U2TextStyles.captionTight, color = colors.textSecondary)
        }
    }
}

/**
 * The 4.3 privacy banner: `accent900` r18 + inner 1 dp `accent800`, pad 14 16, gap 12:
 * `shield-check` 22 `accent300` and the `callout` text in `accent200`.
 */
@Composable
fun InfoBanner(
    message: String,
    modifier: Modifier = Modifier,
    glyph: PhosphorGlyph = PhosphorGlyph.ShieldCheck,
) {
    val colors = U2Theme.colors
    val shape = RoundedCornerShape(U2Radius.card)
    Row(
        modifier
            .semantics(mergeDescendants = true) {}
            .fillMaxWidth()
            .background(colors.accent900, shape)
            .u2InnerStroke(colors.accent800, 1.dp, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        PhosphorIcon(glyph, size = 22.dp, tint = colors.accent300)
        U2Text(message, U2TextStyles.callout, color = colors.accent200, modifier = Modifier.weight(1f))
    }
}

/** The two floating, danger-ringed banners. */
enum class FloatingBannerKind {
    /** 5.1 urgent Ghost banner: r16, pad 11 14, blur 16, `0 10px 30px #000@30%`. */
    Urgent,

    /** 5.6 screenshot warning: r20, pad 14 16, blur 20, `0 14px 40px #000@45%`. */
    Warning,
}

/**
 * Floating danger-ringed banners (5.1 urgent Ghost banner, 5.6 screenshot warning): `surface·92%`
 * over a backdrop blur of what lies behind ([backdrop], see `u2BackdropSource`), an inner ring
 * `danger·45%` and a soft drop shadow. Callers animate it in with `U2Transitions.rise()` / `drop()`.
 */
@Composable
fun FloatingBanner(
    kind: FloatingBannerKind,
    modifier: Modifier = Modifier,
    backdrop: U2BackdropSource? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val urgent = kind == FloatingBannerKind.Urgent
    val shape = RoundedCornerShape(if (urgent) 16.dp else 20.dp)
    Box(
        modifier
            .fillMaxWidth()
            .u2BoxShadow(shape, if (urgent) U2BoxShadows.floatingBanner else U2BoxShadows.floatingWarning)
            .u2Backdrop(if (urgent) U2BackdropStyle.Banner else U2BackdropStyle.Warning, shape, backdrop)
            .u2InnerStroke(U2Theme.colors.dangerStroke, 1.dp, shape)
            .padding(if (urgent) PaddingValues(horizontal = 14.dp, vertical = 11.dp) else PaddingValues(horizontal = 16.dp, vertical = 14.dp)),
        contentAlignment = Alignment.CenterStart,
        content = content,
    )
}

/**
 * The 5.1 system pill: `text·6%`, r14, pad 6 12, gap 7: a 14 dp glyph and `monoSystemPill`
 * (Mono 11/15.4) in `text·68%`, centred, at most 330 wide.
 */
@Composable
fun SystemPill(
    text: String,
    modifier: Modifier = Modifier,
    glyph: PhosphorGlyph? = PhosphorGlyph.Ghost,
    glyphWeight: PhosphorWeight = PhosphorWeight.Regular,
) {
    val colors = U2Theme.colors
    Row(
        modifier
            .semantics(mergeDescendants = true) {}
            .widthIn(max = 330.dp)
            .background(colors.fillSubtle, RoundedCornerShape(U2Radius.systemPill))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) PhosphorIcon(glyph, size = 14.dp, weight = glyphWeight, tint = colors.textSecondary)
        U2Text(text, U2TextStyles.monoSystemPill, color = colors.textSecondary, textAlign = TextAlign.Center)
    }
}

/**
 * A small mono flag line: a 13 dp glyph + `monoTag` (gap 6). 4.3 “Medya kabul edene kadar gizli”
 * (`text·68%`), “Bağlantı içeriyor · açılmaz” (`danger`).
 */
@Composable
fun FlagTag(
    glyph: PhosphorGlyph,
    text: String,
    modifier: Modifier = Modifier,
    color: Color = U2Theme.colors.textSecondary,
) {
    Row(
        modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhosphorIcon(glyph, size = 13.dp, tint = color)
        U2Text(text, U2TextStyles.monoTag, color = color)
    }
}

/** The 6.3 info pill: `text·6%` r14, pad 8 14, gap 8: `clock` 14 + `monoMeta` in `text·68%`. */
@Composable
fun InfoPill(
    text: String,
    modifier: Modifier = Modifier,
    glyph: PhosphorGlyph = PhosphorGlyph.Clock,
) {
    val colors = U2Theme.colors
    Row(
        modifier
            .semantics(mergeDescendants = true) {}
            .background(colors.fillSubtle, RoundedCornerShape(U2Radius.systemPill))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhosphorIcon(glyph, size = 14.dp, tint = colors.textSecondary)
        U2Text(text, U2TextStyles.monoMeta, color = colors.textSecondary)
    }
}
