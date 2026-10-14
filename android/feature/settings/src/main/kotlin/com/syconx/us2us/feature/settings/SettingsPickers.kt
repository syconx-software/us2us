package com.syconx.us2us.feature.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syconx.us2us.domain.AppSettings
import com.syconx.us2us.domain.DisappearingTimer
import com.syconx.us2us.domain.GhostPeriod
import com.syconx.us2us.domain.PresenceVisibility
import com.syconx.us2us.domain.ThemePreference
import com.syconx.us2us.domain.nav.SettingsPicker
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.CenteredTitleHeader
import com.syconx.us2us.ui.components.GroupedCard
import com.syconx.us2us.ui.components.GroupedRow
import com.syconx.us2us.ui.components.GroupedSeparator
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.groupedRowPress
import com.syconx.us2us.ui.env.LocalAppModel
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.l10n.AppLanguage
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.u2Spec
import com.syconx.us2us.ui.nav.LocalRouter
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlinx.coroutines.launch

/** One option of a 7.1 picker. */
@Immutable
internal data class PickerOption(
    val title: String,
    val subtitle: String? = null,
    val isSelected: Boolean,
    val select: () -> Unit,
)

/**
 * A 7.1 picker (proposed: the options in a C13 card, the selected one with a `ph:check` in
 * `accent300`), with an explanation under the card where the choice has consequences (presence is
 * reciprocal; auto-lock is fixed).
 */
@Composable
internal fun SettingsPickerView(picker: SettingsPicker, modifier: Modifier = Modifier) {
    val router = LocalRouter.current
    val haptics = LocalHaptics.current
    val options = pickerOptions(picker)
    val footnote = when (picker) {
        SettingsPicker.Presence -> R.string.settings_presence_footnote
        SettingsPicker.AutoLock -> R.string.settings_autolock_footnote
        SettingsPicker.DefaultGhost -> R.string.settings_defaultghost_footnote
        SettingsPicker.DefaultDisappearing -> R.string.settings_defaultdisappearing_footnote
        SettingsPicker.Theme, SettingsPicker.Language -> null
    }
    U2Screen(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            CenteredTitleHeader(
                title = stringResource(pickerTitle(picker)),
                leading = {
                    U2GlassIconButton(
                        glyph = PhosphorGlyph.CaretLeft,
                        contentDescription = stringResource(R.string.common_a11y_back),
                        onClick = router::pop,
                        modifier = Modifier.testTag(SettingsTags.PICKER_BACK),
                    )
                },
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 18.dp, end = 16.dp)
                    .u2Bottom(U2Spacing.ctaBottom),
                verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionLabelGap),
            ) {
                GroupedCard(Modifier.testTag(SettingsTags.PICKER_CARD)) {
                    options.forEachIndexed { index, option ->
                        if (index > 0) GroupedSeparator(inset = 14.dp)
                        PickerOptionRow(option, Modifier.testTag(SettingsTags.pickerOption(index))) {
                            if (option.isSelected) return@PickerOptionRow
                            haptics.selection()
                            option.select()
                        }
                    }
                }
                if (footnote != null) {
                    U2Text(
                        stringResource(footnote),
                        U2TextStyles.footnote,
                        color = U2Theme.colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
        SettingsStatusBarFade()
    }
}

@Composable
private fun PickerOptionRow(option: PickerOption, modifier: Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shown by animateFloatAsState(if (option.isSelected) 1f else 0f, u2Spec(U2Motion.micro), label = "pickerCheck")
    GroupedRow(
        tile = null,
        title = option.title,
        subtitle = option.subtitle,
        modifier = modifier
            .groupedRowPress(source)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { selected = option.isSelected },
    ) {
        PhosphorIcon(
            PhosphorGlyph.Check,
            size = 18.dp,
            tint = U2Theme.colors.accent300,
            modifier = Modifier.graphicsLayer {
                alpha = shown
                val scale = 0.8f + 0.2f * shown
                scaleX = scale
                scaleY = scale
            },
        )
    }
}

private fun pickerTitle(picker: SettingsPicker): Int = when (picker) {
    SettingsPicker.Presence -> R.string.settings_presence
    SettingsPicker.AutoLock -> R.string.settings_autolock
    SettingsPicker.DefaultGhost -> R.string.settings_defaultghost
    SettingsPicker.DefaultDisappearing -> R.string.settings_defaultdisappearing
    SettingsPicker.Theme -> R.string.settings_theme
    SettingsPicker.Language -> R.string.settings_language
}

@Composable
private fun pickerOptions(picker: SettingsPicker): List<PickerOption> {
    val app = LocalAppModel.current
    val flow = rememberSettingsFlow()
    val messenger = flow.messenger
    val settings by messenger.settings.collectAsStateWithLifecycle()
    val preferences = app.preferences
    fun update(change: (AppSettings) -> AppSettings): () -> Unit = {
        flow.scope.launch { messenger.updateSettings(change(messenger.settings.value)) }
    }
    return when (picker) {
        SettingsPicker.Presence -> listOf(
            PickerOption(
                title = stringResource(R.string.settings_presence_accepted),
                subtitle = stringResource(R.string.settings_presence_accepted_subtitle),
                isSelected = settings.presenceVisibility == PresenceVisibility.AcceptedContacts,
                select = update { it.copy(presenceVisibility = PresenceVisibility.AcceptedContacts) },
            ),
            PickerOption(
                title = stringResource(R.string.settings_presence_nobody),
                subtitle = stringResource(R.string.settings_presence_nobody_subtitle),
                isSelected = settings.presenceVisibility == PresenceVisibility.Nobody,
                select = update { it.copy(presenceVisibility = PresenceVisibility.Nobody) },
            ),
        )
        SettingsPicker.AutoLock -> listOf(
            PickerOption(title = stringResource(R.string.settings_autolock_immediately), isSelected = true, select = {}),
        )
        SettingsPicker.DefaultGhost -> listOf(
            PickerOption(
                title = stringResource(R.string.common_disappearing_off),
                isSelected = settings.defaultGhost == null,
                select = update { it.copy(defaultGhost = null) },
            ),
        ) + GhostPeriod.entries.map { period ->
            PickerOption(
                title = stringResource(period.longLabel()),
                isSelected = settings.defaultGhost == period,
                select = update { it.copy(defaultGhost = period) },
            )
        }
        SettingsPicker.DefaultDisappearing -> DisappearingTimer.entries.map { timer ->
            PickerOption(
                title = stringResource(timer.settingsLabel()),
                isSelected = settings.defaultDisappearing == timer,
                select = update { it.copy(defaultDisappearing = timer) },
            )
        }
        // Applies live: the app's theme follows the preference.
        SettingsPicker.Theme -> ThemePreference.entries.map { theme ->
            PickerOption(
                title = stringResource(theme.settingsLabel()),
                isSelected = preferences.theme == theme,
                select = { preferences.theme = theme },
            )
        }
        // The per-app language: the activity is recreated in it, the app's state survives.
        SettingsPicker.Language -> AppLanguage.entries.map { language ->
            PickerOption(
                title = if (language == AppLanguage.System) stringResource(R.string.settings_language_system) else languageName(language),
                isSelected = preferences.language == language,
                select = { preferences.language = language },
            )
        }
    }
}
