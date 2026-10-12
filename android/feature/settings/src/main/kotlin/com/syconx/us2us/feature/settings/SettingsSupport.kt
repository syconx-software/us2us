package com.syconx.us2us.feature.settings

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.annotation.StringRes
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.DisappearingTimer
import com.syconx.us2us.domain.GhostPeriod
import com.syconx.us2us.domain.ThemePreference
import com.syconx.us2us.feature.settings.R as SettingsR
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.env.currentSafeArea
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.l10n.AppLanguage
import com.syconx.us2us.ui.l10n.L10n
import com.syconx.us2us.ui.l10n.U2Format
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Timing
import com.syconx.us2us.ui.motion.u2Spec
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import java.util.Locale

// Building blocks only section 07 uses.

/** Test tags of section 07 (the iOS accessibility identifiers, plus the layout ids the shots gate on). */
object SettingsTags {
    const val ROOT = "settings.root"
    const val BACK = "settings.back"
    const val TITLE = "settings.title"
    const val PROFILE = "settings.profile"
    const val AVATAR = "settings.avatar"
    const val QR = "settings.qr"
    const val READ_RECEIPTS = "settings.readReceipts"
    const val BLOCKED = "settings.blocked"
    const val AVATAR_SEARCH = "settings.avatarSearch"
    const val AVATAR_SEARCH_FOOTNOTE = "settings.avatarSearch.footnote"
    const val FACE_ID = "settings.faceID"
    const val CHANGE_CODE = "settings.changeCode"
    const val BACKUP = "settings.backup"
    const val INVITE = "settings.invite"
    const val ABOUT = "settings.about"
    const val ERASE = "settings.erase"
    const val ERASE_RETRY = "settings.erase.retry"
    const val ERASE_THIS_PHONE = "settings.erase.thisPhone"
    const val ERASE_CANCEL = "settings.erase.cancel"
    const val CONFIRM_DESTRUCTIVE = "settings.confirm.destructive"
    const val CONFIRM_CANCEL = "settings.confirm.cancel"
    const val ACCESS = "settings.access"
    const val ACCESS_CHECK = "settings.access.check"
    const val ACCESS_UNANSWERED = "settings.access.unanswered"
    const val SCROLL = "settings.scroll"

    /** The Gizlilik section's label, card and rows (7.1 `r.2.1.*`). */
    const val PRIVACY_LABEL = "settings.section.privacy"
    const val PRIVACY_CARD = "settings.section.privacy.card"
    const val PRIVACY_SEPARATOR = "settings.section.privacy.separator"
    const val READ_RECEIPTS_SWITCH = "settings.readReceipts.switch"

    /** A 7.1 row that opens a picker (`settings.presence` …, the iOS raw values). */
    fun picker(raw: String): String = "settings.$raw"

    const val PICKER_BACK = "picker.back"
    const val PICKER_CARD = "picker.card"

    fun pickerOption(index: Int): String = "picker.option.$index"
}

// MARK: - Status bar fade

/**
 * 7.1 and its pickers scroll their header away, and the rows then pass under the status bar. A
 * short `bg` fade behind the clock keeps it legible; it ends above the header line (y 56), so the
 * resting layout is untouched.
 */
@Composable
internal fun SettingsStatusBarFade(modifier: Modifier = Modifier) {
    val safeArea = currentSafeArea
    val height = (safeArea.top - 6.dp).coerceAtLeast(0.dp)
    val solid = if (height > 0.dp) ((safeArea.top - 20.dp).coerceAtLeast(0.dp) / height) else 0f
    val bg = U2Theme.colors.bg
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics {}
            .background(Brush.verticalGradient(0f to bg, solid to bg, 1f to bg.copy(alpha = 0f))),
    )
}

// MARK: - File card (7.2, 7.3)

/**
 * The backup file card: `surface` r20, pad 14 16, gap 14: file tile 46 × 56 r10 (`accent900` + 1 dp
 * `accent800`, `ph:file-lock` 26 `accent300`) · name `monoFileName` (one line, …) over a `caption`
 * line in `text·68%` · an optional trailing status glyph.
 */
@Composable
internal fun BackupFileCard(
    name: String,
    caption: String,
    modifier: Modifier = Modifier,
    captionColor: Color = U2Theme.colors.textSecondary,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = U2Theme.colors
    val tile = RoundedCornerShape(U2Radius.iconTileL)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp)
            .background(colors.surface, RoundedCornerShape(U2Radius.cardL))
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(U2Size.fileTile.width, U2Size.fileTile.height)
                .background(colors.accent900, tile)
                .u2InnerStroke(colors.accent800, 1.dp, tile)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            PhosphorIcon(PhosphorGlyph.FileLock, size = 26.dp, tint = colors.accent300)
        }
        // The reference's renderer rounds each baseline to a whole point; at the card's position that
        // puts the name 1.5 dp and the caption ⅓ dp lower than the exact CSS line boxes do.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            U2Text(
                name,
                U2TextStyles.monoFileName,
                color = colors.text,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { translationY = 1.5.dp.toPx() },
            )
            U2Text(
                caption,
                U2TextStyles.caption.proportional,
                color = captionColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { translationY = (1f / 3f).dp.toPx() },
            )
        }
        trailing()
    }
}

// MARK: - Keyboard-aware bottom

/**
 * The CTA's bottom padding: the design's 44 dp over the home indicator, or a calm 12 dp above the
 * keyboard while typing (eased like the keyboard).
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun Modifier.keyboardAwareBottom(isKeyboardUp: Boolean): Modifier {
    val safeArea = currentSafeArea
    // Above the keyboard only while one is actually up (iOS follows the keyboard's own notifications;
    // a focused field with a hardware keyboard keeps the resting place).
    val target = if (isKeyboardUp && WindowInsets.isImeVisible) 12.dp else safeArea.bottom(U2Spacing.ctaBottom).coerceAtLeast(0.dp)
    val padding by animateDpAsState(target, u2Spec(U2Motion.state), label = "keyboardAwareBottom")
    return this.padding(bottom = padding)
}

/** The design's cross-fade timing as a finite spec (the short cross-fade under reduce motion). */
@Composable
internal fun crossFadeSpec(timing: U2Timing): FiniteAnimationSpec<Float> = U2Motion.resolve(timing, LocalReduceMotion.current).spec()

// MARK: - Texts

/** “1 saat”, “24 saat” … (the long Ghost labels of 7.1 and its picker). */
@StringRes
internal fun GhostPeriod.longLabel(): Int = when (this) {
    GhostPeriod.OneHour -> R.string.common_ghost_period_long_1h
    GhostPeriod.SixHours -> R.string.common_ghost_period_long_6h
    GhostPeriod.OneDay -> R.string.common_ghost_period_long_24h
    GhostPeriod.ThreeDays -> R.string.common_ghost_period_long_3d
    GhostPeriod.SevenDays -> R.string.common_ghost_period_long_7d
}

/** Long labels for 7.1 and its picker: “Kapalı”, “30 saniye” … “1 gün”. */
@StringRes
internal fun DisappearingTimer.settingsLabel(): Int = when (this) {
    DisappearingTimer.Off -> R.string.settings_disappearing_off
    DisappearingTimer.ThirtySeconds -> R.string.settings_disappearing_30s
    DisappearingTimer.FiveMinutes -> R.string.settings_disappearing_5m
    DisappearingTimer.OneHour -> R.string.settings_disappearing_1h
    DisappearingTimer.OneDay -> R.string.settings_disappearing_1d
}

/** “Sistem”, “Koyu”, “Açık”. */
@StringRes
internal fun ThemePreference.settingsLabel(): Int = when (this) {
    ThemePreference.System -> R.string.settings_theme_system
    ThemePreference.Dark -> R.string.settings_theme_dark
    ThemePreference.Light -> R.string.settings_theme_light
}

/** A language in its own name (“Türkçe”, “English”): the same in every UI language. */
@Composable
internal fun languageName(language: AppLanguage): String = when (language) {
    AppLanguage.Turkish -> stringResource(SettingsR.string.settings_android_language_turkish)
    AppLanguage.English -> stringResource(SettingsR.string.settings_android_language_english)
    // 7.1 shows what “Sistem” resolved to; the picker names the option itself.
    AppLanguage.System -> languageName(if (L10n.isTurkish(currentAppLocale())) AppLanguage.Turkish else AppLanguage.English)
}

/** Counted nouns with grouped numbers (“3.920 mesaj”, “3,920 messages”). */
internal object SettingsCount {
    @Composable
    fun chats(count: Int): String {
        val number = U2Format.integer(count, currentAppLocale())
        return stringResource(if (count == 1) R.string.settings_count_chats_one else R.string.settings_count_chats_other, number)
    }

    @Composable
    fun messages(count: Int): String {
        val number = U2Format.integer(count, currentAppLocale())
        return stringResource(if (count == 1) R.string.settings_count_messages_one else R.string.settings_count_messages_other, number)
    }
}

internal object SettingsFormat {
    /** “1 dakika”, “5 dakika”, “1 saat” / “1 minute” … (whole hours from an hour on, else minutes). */
    fun duration(seconds: Double, locale: Locale): String {
        val format = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE)
        return if (seconds >= 3_600) {
            format.format(Measure((seconds / 3_600).toLong(), MeasureUnit.HOUR))
        } else {
            format.format(Measure((seconds / 60).toLong(), MeasureUnit.MINUTE))
        }
    }

    /** `yyyy-MM-dd` in ASCII digits, whatever the language (the backup's file name). */
    fun fileDate(date: java.time.Instant, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).format(date.atZone(zone))
}
