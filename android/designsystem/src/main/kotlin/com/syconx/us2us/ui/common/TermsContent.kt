package com.syconx.us2us.ui.common

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.CenteredTitleHeader
import com.syconx.us2us.ui.components.GroupedCard
import com.syconx.us2us.ui.components.GroupedNavigationRow
import com.syconx.us2us.ui.components.GroupedRow
import com.syconx.us2us.ui.components.GroupedSeparator
import com.syconx.us2us.ui.components.HeaderSpacer
import com.syconx.us2us.ui.components.IconTile
import com.syconx.us2us.ui.components.IconTileContent
import com.syconx.us2us.ui.components.SectionLabel
import com.syconx.us2us.ui.components.SheetBar
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.env.LocalExternalActivityGuard
import com.syconx.us2us.ui.env.TermsOfUse
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text

/**
 * Where the Terms of Use are asked, as on iOS: before a way into us2us ([BeforeStart], which “Vazgeç” leaves
 * without anything kept) or over an account that is open on this phone but never accepted them ([Required]:
 * there is no way past it but “Kabul ediyorum”).
 */
enum class TermsMode { BeforeStart, Required }

object TermsTags {
    const val SCREEN = "terms.sheet"
    const val REQUIRED = "terms.sheet.required"
    const val DECLINE = "terms.decline"
    const val AGREE = "terms.agree"
    const val FULL = "terms.full"
}

/**
 * The terms' key rules (App Review 1.2 and Play's rules for user-generated content): no tolerance for objectionable
 * content or abusive users, blocking, reporting and how reports are reviewed, then the full text on us2us.app and
 * “Kabul ediyorum”. [onAgree] is called once the user agreed; the caller keeps the acceptance.
 */
@Composable
fun TermsContent(mode: TermsMode, onAgree: () -> Unit, modifier: Modifier = Modifier, onDecline: () -> Unit = {}) {
    val colors = U2Theme.colors
    val context = LocalContext.current
    val guard = LocalExternalActivityGuard.current
    val locale = currentAppLocale()
    val view = LocalView.current
    val accepted = stringResource(R.string.terms_accepted)
    val title = stringResource(R.string.terms_title)
    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .testTag(if (mode == TermsMode.Required) TermsTags.REQUIRED else TermsTags.SCREEN),
    ) {
        if (mode == TermsMode.BeforeStart) {
            SheetBar(title, stringResource(R.string.common_cancel), onDecline, Modifier.testTag(TermsTags.DECLINE))
        } else {
            CenteredTitleHeader(title, leading = { HeaderSpacer() })
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            val intro = if (mode == TermsMode.Required) R.string.terms_intro_required else R.string.terms_intro
            U2Text(stringResource(intro), U2TextStyles.body, Modifier.padding(horizontal = 8.dp), color = colors.textSecondary)
            U2Text(
                stringResource(R.string.terms_zerotolerance), U2TextStyles.rowTitleEmphasis,
                Modifier.padding(start = 8.dp, top = 16.dp, end = 8.dp).testTag("terms.zeroTolerance"),
            )
            SectionLabel(stringResource(R.string.terms_rules_label), Modifier.padding(top = 22.dp))
            GroupedCard(fill = colors.surface) {
                Rule(PhosphorGlyph.WarningCircle, R.string.terms_rule_content, R.string.terms_rule_content_detail, "content")
                GroupedSeparator(inset = 14.dp)
                Rule(PhosphorGlyph.Prohibit, R.string.terms_rule_block, R.string.terms_rule_block_detail, "block")
                GroupedSeparator(inset = 14.dp)
                Rule(PhosphorGlyph.Warning, R.string.terms_rule_report, R.string.terms_rule_report_detail, "report")
                GroupedSeparator(inset = 14.dp)
                Rule(PhosphorGlyph.Clock, R.string.terms_rule_review, R.string.terms_rule_review_detail, "review")
            }
            GroupedCard(Modifier.padding(top = 22.dp), fill = colors.surface) {
                GroupedNavigationRow(
                    tile = { IconTile(IconTileContent.Glyph(PhosphorGlyph.FileLock)) },
                    title = stringResource(R.string.terms_full),
                    detail = "us2us.app",
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(TermsOfUse.url(locale)))
                        try {
                            guard.launching { context.startActivity(intent) }
                        } catch (_: ActivityNotFoundException) {
                            // No browser on this phone: the key rules above are what it can show.
                        }
                    },
                    modifier = Modifier.testTag(TermsTags.FULL),
                )
            }
            U2Text(
                stringResource(R.string.terms_agree_footnote), U2TextStyles.caption,
                Modifier.padding(start = 8.dp, top = 16.dp, end = 8.dp), color = colors.textSecondary,
            )
        }
        U2PrimaryButton(
            title = stringResource(R.string.terms_agree),
            onClick = {
                @Suppress("DEPRECATION")
                view.announceForAccessibility(accepted)
                onAgree()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag(TermsTags.AGREE),
            glyph = PhosphorGlyph.Check,
        )
    }
}

@Composable
private fun Rule(glyph: PhosphorGlyph, @StringRes title: Int, @StringRes detail: Int, id: String) {
    GroupedRow(
        tile = { IconTile(IconTileContent.Glyph(glyph)) },
        title = stringResource(title),
        subtitle = stringResource(detail),
        modifier = Modifier.testTag("terms.rule.$id"),
    )
}
