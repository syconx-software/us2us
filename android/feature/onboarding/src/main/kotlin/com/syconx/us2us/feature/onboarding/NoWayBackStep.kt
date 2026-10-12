package com.syconx.us2us.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.Biometry
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.FaceIdGlyph
import com.syconx.us2us.ui.components.LocalU2LabelStyle
import com.syconx.us2us.ui.components.SetupHeader
import com.syconx.us2us.ui.components.SpinningGlyph
import com.syconx.us2us.ui.components.U2Checkbox
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.U2Toggle
import com.syconx.us2us.ui.components.currentLabelColor
import com.syconx.us2us.ui.components.text.SetupTitle
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.nav.LocalIsLayerVisible
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text

/**
 * 2.3 · Geri dönüş yok — the irreversibility, said plainly; biometric unlock offered; “Kurulumu
 * tamamla” only after the acknowledgement is ticked.
 */
@Composable
internal fun NoWayBackStep(model: NoWayBackStepModel, step: SetupStepLabel, onBack: () -> Unit) {
    val locked = model.isWorking || model.returnsToLock
    // While the account is being made, or once only the lock can go on, there is no way back.
    BackHandler(enabled = LocalIsLayerVisible.current && locked) {}
    U2Screen {
        OnboardingFitOrScroll {
            SetupHeader(
                step = step.step,
                onBack = { if (!locked) onBack() },
                stepCount = step.count,
                modifier = Modifier.testTag(OnboardingTags.header(3)),
                backEnabled = !locked,
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp)
                    .padding(horizontal = U2Spacing.screenGutterL),
                verticalArrangement = Arrangement.spacedBy(U2Spacing.titleToBodyGap),
            ) {
                SetupTitle(R.string.onboarding_nowayback_title, U2TextStyles.displayTitleS, modifier = Modifier.testTag(OnboardingTags.title(3)))
                U2Text(stringResource(R.string.onboarding_nowayback_body), U2TextStyles.body, color = U2Theme.colors.textSecondary, modifier = Modifier.fillMaxWidth())
            }

            FactsCard(
                Modifier
                    .padding(top = 20.dp)
                    .padding(horizontal = U2Spacing.screenGutterS)
                    .testTag(OnboardingTags.FACTS),
            )

            if (model.replacesThisPhone) {
                ReplacementNote(
                    Modifier
                        .padding(top = 14.dp)
                        .padding(horizontal = U2Spacing.screenGutterL),
                )
            }

            if (model.biometry != Biometry.None) {
                BiometryCard(
                    model,
                    Modifier
                        .padding(top = 12.dp)
                        .padding(horizontal = U2Spacing.screenGutterS),
                )
            }

            Spacer(Modifier.weight(1f))

            val rise = U2Transitions.rise()
            AnimatedVisibility(visible = model.failure != null, enter = rise.enter, exit = rise.exit) {
                val failure = model.failure
                if (failure != null) {
                    U2Text(
                        stringResource(errorText(failure)),
                        U2TextStyles.calloutL,
                        color = U2Theme.colors.danger,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = U2Spacing.screenGutterL)
                            .padding(bottom = 14.dp)
                            .semantics { liveRegion = LiveRegionMode.Assertive }
                            .testTag(OnboardingTags.ERROR),
                    )
                }
            }

            U2Checkbox(
                checked = model.acknowledged,
                onCheckedChange = { model.acknowledged = it },
                enabled = !locked,
                modifier = Modifier
                    .padding(horizontal = U2Spacing.screenGutterL)
                    .padding(bottom = 18.dp)
                    .testTag(OnboardingTags.ACKNOWLEDGE),
            ) {
                U2Text(stringResource(R.string.onboarding_nowayback_acknowledge), U2TextStyles.calloutL, color = U2Theme.colors.text)
            }

            U2PrimaryButton(
                onClick = { if (!model.isWorking) model.submit() },
                enabled = model.acknowledged || model.returnsToLock || model.choosesAnotherName,
                modifier = Modifier
                    .padding(horizontal = U2Spacing.screenGutter)
                    .u2Bottom(U2Spacing.ctaBottom)
                    .testTag(OnboardingTags.FINISH),
            ) {
                FinishLabel(stringResource(actionText(model)), isWorking = model.isWorking)
            }
        }
    }
}

/** The CTA's title, with a softly spinning glyph 10 dp before it while the account is made. */
@Composable
private fun FinishLabel(title: String, isWorking: Boolean) {
    val label = LocalU2LabelStyle.current
    val ink = currentLabelColor()
    Row(verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(visible = isWorking, enter = U2Transitions.fade().enter, exit = U2Transitions.fade().exit) {
            SpinningGlyph(size = 20.dp, tint = ink, modifier = Modifier.padding(end = 10.dp))
        }
        U2Text(title, label.style, color = ink)
    }
}

/**
 * The sentence above the checkbox: try again, or — the restored backup's account is gone — start a
 * new account, or — a vault is still on this phone — go to the lock, or — the name is taken —
 * choose another (the CTA says so).
 */
@StringRes
internal fun errorText(failure: NoWayBackStepModel.Failure): Int = when (failure) {
    NoWayBackStepModel.Failure.Network -> R.string.onboarding_nowayback_error
    NoWayBackStepModel.Failure.BackupGone -> R.string.onboarding_nowayback_error_backupgone
    NoWayBackStepModel.Failure.BackupDamaged -> R.string.onboarding_nowayback_error_backupdamaged
    NoWayBackStepModel.Failure.AccountKept -> R.string.onboarding_nowayback_error_accountkept
    NoWayBackStepModel.Failure.PreviousVaultKept -> R.string.onboarding_nowayback_error_previousvaultkept
    NoWayBackStepModel.Failure.UsernameTaken -> R.string.onboarding_nowayback_error_usernametaken
}

/** “Kurulumu tamamla”, or where the CTA goes after a failure that finishing again cannot fix. */
@StringRes
internal fun actionText(model: NoWayBackStepModel): Int = when {
    model.returnsToLock -> R.string.onboarding_nowayback_tolock
    model.choosesAnotherName -> R.string.onboarding_nowayback_chooseanothername
    else -> R.string.onboarding_nowayback_finish
}

/** Restoring inside the lock: what finishing costs this phone, said before it happens (the warning line of 7.2, in `danger`). */
@Composable
private fun ReplacementNote(modifier: Modifier = Modifier) {
    val style = U2TextStyles.captionTight
    Row(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag(OnboardingTags.REPLACES_THIS_PHONE),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.height(style.lineHeight.dp), contentAlignment = Alignment.Center) {
            PhosphorIcon(PhosphorGlyph.Warning, size = 18.dp, tint = U2Theme.colors.danger)
        }
        U2Text(stringResource(R.string.onboarding_nowayback_replacesthisphone), style, color = U2Theme.colors.textSecondary, modifier = Modifier.weight(1f))
    }
}

/** Three facts on `surface` r16; rows 52 / 52 / 62 high, pad 0 16, gap 14, 21 dp icons; *solid* 1 dp `divider` separators inset 51. */
@Composable
private fun FactsCard(modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp)),
    ) {
        FactRow(PhosphorGlyph.Prohibit, colors.danger, R.string.onboarding_nowayback_fact_noreset)
        FactSeparator()
        FactRow(PhosphorGlyph.CloudSlash, colors.textSecondary, R.string.onboarding_nowayback_fact_neversent)
        FactSeparator()
        FactRow(
            PhosphorGlyph.FileLock,
            colors.accent300,
            R.string.onboarding_nowayback_fact_backup,
            subtitle = R.string.onboarding_nowayback_fact_backup_path,
            minHeight = 62.dp,
        )
    }
}

@Composable
private fun FactSeparator() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 51.dp)
            .height(1.dp)
            .background(U2Theme.colors.divider)
            .clearAndSetSemantics {},
    )
}

@Composable
private fun FactRow(glyph: PhosphorGlyph, tint: Color, @StringRes title: Int, @StringRes subtitle: Int? = null, minHeight: Dp = 52.dp) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .padding(horizontal = 16.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhosphorIcon(glyph, size = 21.dp, tint = tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            U2Text(stringResource(title), U2TextStyles.rowTitleS, color = U2Theme.colors.text)
            if (subtitle != null) U2Text(stringResource(subtitle), U2TextStyles.caption, color = U2Theme.colors.textSecondary)
        }
    }
}

/**
 * The biometrics card: the design's Face ID glyph (22, stroke 1.7, `accent300`) — a lock for a
 * fingerprint sensor — “Face ID ile aç” + “Tanımazsa kod istenir”, the C14 switch (on by default).
 */
@Composable
private fun BiometryCard(model: NoWayBackStepModel, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .testTag(OnboardingTags.FACE_ID)
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        U2Toggle(
            checked = model.faceIdEnabled,
            onCheckedChange = { model.faceIdEnabled = it },
            enabled = !model.isWorking,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    FaceIdGlyph(size = 22.dp, lineWidth = 1.7f, color = colors.accent300)
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    U2Text(
                        stringResource(R.string.android_biometric_unlock),
                        U2TextStyles.rowTitleS,
                        color = colors.text,
                    )
                    U2Text(stringResource(R.string.onboarding_nowayback_biometry_fallback), U2TextStyles.caption, color = colors.textSecondary)
                }
            }
        }
    }
}
