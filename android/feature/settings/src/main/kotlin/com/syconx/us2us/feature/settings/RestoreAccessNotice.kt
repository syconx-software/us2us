package com.syconx.us2us.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.RestoreAccessCheck
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.U2ButtonLabel
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.u2TransitionBlur
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * After a restore whose token rotation got no answer (PROTOCOL §5.5 step 3.4) the server has not
 * confirmed yet that the old phone lost its access: until it does, the old phone may still connect
 * and receive what is sent to this account, and this phone does not connect. 7.1 says so at the top,
 * honestly, with “Şimdi kontrol et”; 4.1 shows a short card that leads there. The notice goes as soon
 * as the outcome is known: by this button or by any connection attempt, which asks first (from the
 * first unlock after a launch on).
 */
@Stable
class RestoreAccessModel(private val messenger: MessengerService) {
    /** “Şimdi kontrol et” is running. */
    var isChecking by mutableStateOf(false)
        private set

    /** The last check got no answer: the notice says so under its button. */
    var lastCheckUnanswered by mutableStateOf(false)
        private set

    /** The notice is up: the rotation is unconfirmed (or being checked). */
    val isShown: StateFlow<Boolean> get() = messenger.restoreAccessPending

    /** “Şimdi kontrol et”. `null` when a check is already running. */
    suspend fun check(): RestoreAccessCheck? {
        if (isChecking) return null
        isChecking = true
        lastCheckUnanswered = false
        val result = try {
            messenger.checkRestoreAccess()
        } finally {
            isChecking = false
        }
        lastCheckUnanswered = result == RestoreAccessCheck.Unanswered
        return result
    }

    /** The account this notice was about is gone (erased, wiped, or replaced by a restore): a later notice starts without the old check's answer. */
    fun reset() {
        isChecking = false
        lastCheckUnanswered = false
    }

    /** The debug harness (`settings:7.1-access-…`). */
    fun applyHarness(checking: Boolean, unanswered: Boolean) {
        isChecking = checking
        lastCheckUnanswered = unanswered
    }
}

/**
 * The 7.1 notice: the 5.4 note card's danger ring (`danger·40%`, r20, pad 16, gap 14;
 * `ph:warning-circle` 22 in `danger`, title `noteTitle`, body `captionTight` in `text·68%`) with
 * “Şimdi kontrol et” (C3, `accent300`) under the text. No design reference (proposed).
 */
@Composable
internal fun RestoreAccessNotice(
    model: RestoreAccessModel,
    onResult: (RestoreAccessCheck) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = U2Theme.colors
    val scope = rememberCoroutineScope()
    val fade = U2Transitions.fade()
    Row(
        modifier
            .fillMaxWidth()
            .u2InnerStroke(colors.dangerStrokeSoft, 1.dp, U2Radius.cardL)
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 6.dp)
            .testTag(SettingsTags.ACCESS),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        PhosphorIcon(PhosphorGlyph.WarningCircle, size = 22.dp, tint = colors.danger)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            U2Text(
                stringResource(R.string.settings_access_notice_title),
                U2TextStyles.noteTitle,
                color = colors.text,
                modifier = Modifier.semantics { heading() },
            )
            U2Text(stringResource(R.string.settings_access_notice_body), U2TextStyles.captionTight, color = colors.textSecondary)
            AnimatedVisibility(model.lastCheckUnanswered, enter = fade.enter, exit = fade.exit) {
                U2Text(
                    stringResource(R.string.settings_access_notice_unanswered),
                    U2TextStyles.captionTight,
                    color = colors.danger,
                    modifier = Modifier
                        .then(u2TransitionBlur(fade))
                        .padding(top = 4.dp)
                        .testTag(SettingsTags.ACCESS_UNANSWERED),
                )
            }
            U2TextButton(
                onClick = {
                    if (model.isChecking) return@U2TextButton
                    scope.launch { model.check()?.let(onResult) }
                },
                tone = U2TextButtonTone.Accent,
                height = 40.dp,
                fillsWidth = false,
                modifier = Modifier.testTag(SettingsTags.ACCESS_CHECK),
            ) {
                U2ButtonLabel(
                    stringResource(if (model.isChecking) R.string.settings_access_notice_checking else R.string.settings_access_notice_check),
                    glyphSize = 18.dp,
                    isWorking = model.isChecking,
                )
            }
        }
    }
}
