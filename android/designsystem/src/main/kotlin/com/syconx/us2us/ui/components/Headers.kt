package com.syconx.us2us.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.env.u2Top
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyle
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text

// MARK: - C5 · Onboarding header (1.1–1.3)

/**
 * C5: the wordmark “us2us” (Doto 900 20) left, the pager “n / 3” (Mono 11, `text·68%`) right;
 * the row at design y 66, side padding 24. [page] is one-based.
 */
@Composable
fun OnboardingHeader(page: Int, modifier: Modifier = Modifier, pageCount: Int = 3) {
    val pager = stringResource(R.string.common_a11y_pager, page, pageCount)
    Row(
        modifier
            .fillMaxWidth()
            .u2Top(U2Spacing.onboardingHeaderTop)
            .padding(horizontal = U2Spacing.screenGutterL),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        U2Wordmark()
        Spacer(Modifier.weight(1f))
        U2Text(
            "$page / $pageCount",
            U2TextStyles.monoMeta,
            color = U2Theme.colors.textSecondary,
            modifier = Modifier.clearAndSetSemantics { contentDescription = pager },
        )
    }
}

/** The “us2us” wordmark in Doto (never localized). */
@Composable
fun U2Wordmark(
    modifier: Modifier = Modifier,
    style: U2TextStyle = U2TextStyles.brandWordmark,
    color: Color = U2Theme.colors.text,
) {
    U2Text(WORDMARK, style, color = color, modifier = modifier.clearAndSetSemantics { contentDescription = WORDMARK })
}

private const val WORDMARK = "us2us"

// MARK: - C6 · Setup header (2.1–2.3)

/**
 * C6: a back button (44, `caret-left` 22) left, “n / 3” right with 8 dp trailing padding; the row
 * at design y 56, side padding 16. [step] is one-based. [backEnabled] `false` (iOS `.disabled`)
 * takes the back away without changing its look, as the iOS glass button style draws it.
 */
@Composable
fun SetupHeader(step: Int, onBack: () -> Unit, modifier: Modifier = Modifier, stepCount: Int = 3, backEnabled: Boolean = true) {
    val label = stringResource(R.string.common_a11y_step, step, stepCount)
    Row(
        modifier
            .fillMaxWidth()
            .u2Top(U2Spacing.headerTopButtons)
            .padding(horizontal = U2Spacing.screenGutterS)
            .height(U2Size.iconButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        U2GlassIconButton(
            glyph = PhosphorGlyph.CaretLeft,
            contentDescription = stringResource(R.string.common_a11y_back),
            onClick = onBack,
            glyphSize = 22.dp,
            enabled = backEnabled,
        )
        Spacer(Modifier.weight(1f))
        U2Text(
            "$step / $stepCount",
            U2TextStyles.monoMeta,
            color = U2Theme.colors.textSecondary,
            // The pager's box includes its trailing padding, as the design's span does.
            modifier = Modifier
                .clearAndSetSemantics { contentDescription = label }
                .padding(end = 8.dp),
        )
    }
}

// MARK: - C7 · Centred-title header (4.3, 6.1, 7.2, 7.3)

/**
 * C7: a 44 high row at design y 56: the leading button, a centred `navTitle` (Inter 600 17, one
 * line, at most 250 wide) and the trailing button, or an empty 44 × 44 slot ([HeaderSpacer]).
 */
@Composable
fun CenteredTitleHeader(
    title: String,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit = { HeaderSpacer() },
) {
    Box(
        modifier
            .fillMaxWidth()
            .u2Top(U2Spacing.headerTopButtons)
            .padding(horizontal = U2Spacing.screenGutterS)
            .height(U2Size.iconButton),
        contentAlignment = Alignment.Center,
    ) {
        U2Text(
            title,
            U2TextStyles.navTitle,
            color = U2Theme.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 250.dp).semantics { heading() },
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            leading()
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/** An empty 44 × 44 header slot. */
@Composable
fun HeaderSpacer(modifier: Modifier = Modifier) {
    Spacer(modifier.size(U2Size.iconButton))
}

// MARK: - Sheet headers

/**
 * The 4.2 sheet bar: “Vazgeç” (`rowTitle`, `accent300`) left, the `navTitle` centred over the full
 * width; 48 high, side padding 20.
 */
@Composable
fun SheetBar(title: String, cancelTitle: String, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        U2Text(title, U2TextStyles.navTitle, color = U2Theme.colors.text, modifier = Modifier.semantics { heading() })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            U2TextButton(
                title = cancelTitle,
                onClick = onCancel,
                tone = U2TextButtonTone.Accent,
                height = 48.dp,
                fillsWidth = false,
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

/** The 5.4 sheet header: a centred `navTitle` and the close button (36, `x` 17) at the right; 40 high. */
@Composable
fun SheetCloseHeader(title: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        U2Text(title, U2TextStyles.navTitle, color = U2Theme.colors.text, modifier = Modifier.semantics { heading() })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            U2GlassIconButton(
                glyph = PhosphorGlyph.X,
                contentDescription = stringResource(R.string.common_a11y_close),
                onClick = onClose,
                diameter = U2Size.iconButtonSheet,
                glyphSize = 17.dp,
                modifier = Modifier
                    .padding(end = 2.dp)
                    .testTag("sheet.close"),
            )
        }
    }
}

// MARK: - Section labels and kickers

/**
 * `monoSectionLabel`: Mono 400 10.5, tracking 0.1 em, UPPERCASE in the current language
 * (“GİZLİLİK”), `text·68%`; 16 dp side padding (C13).
 */
@Composable
fun SectionLabel(title: String, modifier: Modifier = Modifier, horizontalPadding: Dp = 16.dp) {
    U2Text(
        title,
        U2TextStyles.monoSectionLabel,
        color = U2Theme.colors.textSecondary,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .semantics { heading() },
    )
}

/**
 * `monoKicker`: Mono 400 11, tracking 0.08 em, UPPERCASE, `accent300`, with an optional glyph
 * (gap 8): “GHOST MOD · 24 SAAT”, “TAM EŞLEŞME”.
 */
@Composable
fun MonoKicker(
    title: String,
    modifier: Modifier = Modifier,
    glyph: PhosphorGlyph? = null,
    glyphSize: Dp = 16.dp,
    color: Color = U2Theme.colors.accent300,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (glyph != null) PhosphorIcon(glyph, size = glyphSize, tint = color)
        U2Text(title, U2TextStyles.monoKicker, color = color)
    }
}
