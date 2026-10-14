package com.syconx.us2us.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.CenteredTitleHeader
import com.syconx.us2us.ui.components.GroupedNavigationRow
import com.syconx.us2us.ui.components.GroupedRow
import com.syconx.us2us.ui.components.GroupedSection
import com.syconx.us2us.ui.components.GroupedSeparator
import com.syconx.us2us.ui.components.IconTile
import com.syconx.us2us.ui.components.IconTileContent
import com.syconx.us2us.ui.components.LineMark
import com.syconx.us2us.ui.components.LineMarkMetrics
import com.syconx.us2us.ui.components.SheetCloseHeader
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.U2Wordmark
import com.syconx.us2us.ui.env.LocalExternalActivityGuard
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.l10n.L10n
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.nav.LocalRouter
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.sheet.U2Sheet
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Test tags of Hakkında. */
object AboutTags {
    const val SCREEN = "about.screen"
    const val HERO = "about.hero"
    const val PRIVACY = "about.privacy"
    const val SUPPORT = "about.support"
    const val LICENCE_TEXT = "about.licence.text"

    fun licence(id: String): String = "about.licence.$id"
}

/**
 * 7.1 › Hakkında (not drawn; composed from the design's parts): the line mark and wordmark, the
 * version, what the app does and does not do (the design's own promises: 1.2, 2.3, 3.3, 4.4), the
 * privacy policy and support pages on us2us.app (the policy is linked in the app), and the licences
 * shipped with the app.
 */
@Composable
internal fun AboutScreen(modifier: Modifier = Modifier) {
    val router = LocalRouter.current
    val context = LocalContext.current
    val guard = LocalExternalActivityGuard.current
    val locale = currentAppLocale()
    var licence by remember { mutableStateOf<Licence?>(null) }

    // A page of the website, in the app's language, opened in the browser (the site has no
    // analytics, cookies or access logs).
    fun open(page: WebsitePage) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(page.url(locale)))
        try {
            guard.launching { context.startActivity(intent) }
        } catch (_: ActivityNotFoundException) {
            // No browser on this phone: nothing to open.
        }
    }

    U2Screen(modifier.testTag(AboutTags.SCREEN)) {
        Column(Modifier.fillMaxSize()) {
            CenteredTitleHeader(
                title = stringResource(R.string.settings_about_title),
                leading = {
                    U2GlassIconButton(
                        glyph = PhosphorGlyph.CaretLeft,
                        contentDescription = stringResource(R.string.common_a11y_back),
                        onClick = router::pop,
                    )
                },
            )
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 18.dp, end = 16.dp)
                        .u2Bottom(U2Spacing.ctaBottom),
                    verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionGap),
                ) {
                    AboutHero()
                    GroupedSection(stringResource(R.string.settings_about_principles)) {
                        Principle(PhosphorGlyph.CloudSlash, R.string.settings_about_principle_code, R.string.settings_about_principle_code_detail)
                        GroupedSeparator()
                        Principle(PhosphorGlyph.ShieldCheck, R.string.settings_about_principle_e2e, R.string.settings_about_principle_e2e_detail)
                        GroupedSeparator()
                        Principle(
                            PhosphorGlyph.Prohibit,
                            R.string.settings_about_principle_nonotifications,
                            R.string.settings_about_principle_nonotifications_detail,
                        )
                        GroupedSeparator()
                        Principle(PhosphorGlyph.EyeSlash, R.string.settings_about_principle_notracking, R.string.settings_about_principle_notracking_detail)
                    }
                    GroupedSection(stringResource(R.string.settings_about_website)) {
                        GroupedNavigationRow(
                            tile = null,
                            title = stringResource(R.string.settings_about_privacypolicy),
                            detail = WebsitePage.HOST,
                            onClick = { open(WebsitePage.Privacy) },
                            modifier = Modifier.testTag(AboutTags.PRIVACY),
                        )
                        GroupedSeparator(inset = 14.dp)
                        GroupedNavigationRow(
                            tile = null,
                            title = stringResource(R.string.settings_about_support),
                            detail = WebsitePage.HOST,
                            onClick = { open(WebsitePage.Support) },
                            modifier = Modifier.testTag(AboutTags.SUPPORT),
                        )
                    }
                    GroupedSection(stringResource(R.string.settings_about_licences)) {
                        Licence.all.forEachIndexed { index, item ->
                            if (index > 0) GroupedSeparator(inset = 14.dp)
                            GroupedNavigationRow(
                                tile = null,
                                title = item.component,
                                detail = item.kind,
                                onClick = { licence = item },
                                modifier = Modifier.testTag(AboutTags.licence(item.id)),
                            )
                        }
                    }
                }
            }
        }
    }
    U2Sheet(item = licence, onDismissRequest = { licence = null }) { item ->
        LicenceSheet(item, onClose = { licence = null })
    }
}

@Composable
private fun AboutHero() {
    val context = LocalContext.current
    val version = remember(context) { AboutVersion.of(context) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(U2Theme.colors.surface, RoundedCornerShape(U2Radius.cardL))
            .semantics(mergeDescendants = true) {}
            .testTag(AboutTags.HERO)
            .padding(vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LineMark(metrics = LineMarkMetrics.Lock)
        U2Wordmark(style = U2TextStyles.brandWordmarkL)
        U2Text(
            stringResource(R.string.settings_about_version, version.name, version.build),
            U2TextStyles.monoMeta,
            color = U2Theme.colors.textSecondary,
        )
    }
}

@Composable
private fun Principle(glyph: PhosphorGlyph, @StringRes title: Int, @StringRes detail: Int) {
    GroupedRow(
        tile = { IconTile(IconTileContent.Glyph(glyph)) },
        title = stringResource(title),
        subtitle = stringResource(detail),
        modifier = Modifier.semantics(mergeDescendants = true) {},
    )
}

/** The website's pages the app links to, in Turkish or English (relay/app/content/site.ts › `paths`). */
enum class WebsitePage {
    Privacy,
    Support,
    ;

    /** `https://us2us.app/tr/gizlilik` in Turkish, `/en/privacy` otherwise. */
    fun url(locale: Locale): String {
        val turkish = L10n.isTurkish(locale)
        val path = when (this) {
            Privacy -> if (turkish) "/tr/gizlilik" else "/en/privacy"
            Support -> if (turkish) "/tr/destek" else "/en/support"
        }
        return "https://$HOST$path"
    }

    companion object {
        const val HOST = "us2us.app"
    }
}

/** A licence shipped with the app (`assets/licenses/<resource>.txt`). */
@Immutable
data class Licence(val id: String, val component: String, val kind: String, val resource: String) {
    /** The licence text, or “—” when it is missing. */
    fun text(context: Context): String =
        runCatching { context.assets.open("licenses/$resource.txt").bufferedReader(Charsets.UTF_8).use { it.readText() } }
            .getOrDefault("—")

    companion object {
        val all: List<Licence> = listOf(
            Licence(id = "doto", component = "Doto", kind = "SIL OFL 1.1", resource = "OFL-Doto"),
            Licence(id = "martian", component = "Martian Mono", kind = "SIL OFL 1.1", resource = "OFL-MartianMono"),
            Licence(id = "inter", component = "Inter", kind = "SIL OFL 1.1", resource = "OFL-Inter"),
            Licence(id = "phosphor", component = "Phosphor Icons", kind = "MIT", resource = "Phosphor-LICENSE"),
            Licence(id = "libsignal", component = "libsignal", kind = "AGPL-3.0", resource = "LICENSE.libsignal"),
        )
    }
}

/** The licence text in a full-height sheet (C18), Mono 11 for the legal text. */
@Composable
private fun ColumnScope.LicenceSheet(licence: Licence, onClose: () -> Unit) {
    val context = LocalContext.current
    val text by produceState<String?>(null, licence) { value = withContext(Dispatchers.IO) { licence.text(context) } }
    SheetCloseHeader(
        title = licence.component,
        onClose = onClose,
        modifier = Modifier
            .padding(horizontal = 14.dp)
            .padding(top = 6.dp),
    )
    Column(
        Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState()),
    ) {
        SelectionContainer {
            U2Text(
                text.orEmpty(),
                U2TextStyles.monoMeta,
                color = U2Theme.colors.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 12.dp, end = 20.dp)
                    .u2Bottom(40.dp)
                    .testTag(AboutTags.LICENCE_TEXT),
            )
        }
    }
}
