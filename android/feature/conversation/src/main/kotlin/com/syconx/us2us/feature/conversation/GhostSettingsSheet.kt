package com.syconx.us2us.feature.conversation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.ChatSummary
import com.syconx.us2us.domain.DisappearingTimer
import com.syconx.us2us.domain.GhostPeriod
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.bubbles.StateCrossFade
import com.syconx.us2us.ui.bubbles.chipLabelRes
import com.syconx.us2us.ui.bubbles.shortLabelRes
import com.syconx.us2us.ui.components.DottedRule
import com.syconx.us2us.ui.components.GhostCountdown
import com.syconx.us2us.ui.components.MonoKicker
import com.syconx.us2us.ui.components.SectionLabel
import com.syconx.us2us.ui.components.SegmentedChips
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.U2Toggle
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.l10n.U2Format
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.u2Spec
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Opacity
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text

/**
 * 5.5 · Ghost ve süreli mesaj: opened from the header counter (or the composer's timer key). Per
 * chat, for both sides; saving posts a system line in the chat. Content-height sheet, pad 20 18 40,
 * gap 20: the kicker “GHOST MOD · DENİZ”, the live Doto countdown, the explanation; the Ghost
 * toggle and the silence-period chips; a dotted rule; the disappearing-message chips; “Kaydet”.
 */
@Composable
internal fun GhostSettingsSheet(
    chat: ChatSummary,
    onSave: (enabled: Boolean, period: GhostPeriod, disappearing: DisappearingTimer) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isEnabled by rememberSaveable(chat.id.value) { mutableStateOf(chat.ghost.isEnabled) }
    var period by rememberSaveable(chat.id.value) { mutableStateOf(chat.ghost.period) }
    var disappearing by rememberSaveable(chat.id.value) { mutableStateOf(chat.disappearing) }
    val hasChanges = isEnabled != chat.ghost.isEnabled || (isEnabled && period != chat.ghost.period) || disappearing != chat.disappearing
    // As tall as its content; with a large system font or on a small phone it scrolls, so “Kaydet”
    // is always reachable.
    val scroll = rememberScrollState()
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(scroll, enabled = scroll.maxValue > 0)
            .padding(start = 18.dp, top = 20.dp, end = 18.dp)
            .u2Bottom(40.dp)
            .testTag("ghost.sheet"),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Hero(chat, isEnabled, period)
        GhostBlock(isEnabled, { isEnabled = it }, period, { period = it })
        DottedRule(color = U2Theme.colors.dotRule)
        DisappearingBlock(disappearing) { disappearing = it }
        // Always enabled, as drawn; “Kaydet” with nothing changed just closes (saving would restart the timer).
        U2PrimaryButton(
            stringResource(R.string.common_save),
            onClick = { if (hasChanges) onSave(isEnabled, period, disappearing) else onClose() },
            modifier = Modifier.testTag("ghost.save"),
        )
    }
}

@Composable
private fun Hero(chat: ChatSummary, isEnabled: Boolean, period: GhostPeriod) {
    val colors = U2Theme.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MonoKicker(
            stringResource(R.string.conversation_ghost_kicker, chat.peer.username),
            Modifier.testTag("ghost.kicker"),
            glyph = PhosphorGlyph.Ghost,
            glyphSize = 15.dp,
        )
        // Live while the saved setting is shown; the full new period once another one is picked
        // (saving restarts the timer); dimmed while Ghost is off.
        val deadline = chat.ghost.deadline
        val isLive = isEnabled && chat.ghost.isEnabled && period == chat.ghost.period && deadline != null
        val alpha by animateFloatAsState(if (isEnabled) 1f else U2Opacity.disabled, u2Spec(U2Motion.state), label = "countdown")
        Box(
            Modifier.heightIn(min = 56.dp).graphicsLayer { this.alpha = alpha }.testTag("ghost.countdown"),
            contentAlignment = Alignment.Center,
        ) {
            StateCrossFade(if (isLive) null else period) { shown ->
                if (shown == null && deadline != null) {
                    GhostCountdown(deadline)
                } else {
                    U2Text(U2Format.ghostLong((shown ?: period).duration), U2TextStyles.displayCountdownM, color = colors.accent200, maxLines = 1, softWrap = false)
                }
            }
        }
        U2Text(
            stringResource(R.string.conversation_ghost_caption),
            U2TextStyles.sheetCaption,
            Modifier.widthIn(max = 320.dp).testTag("ghost.caption"),
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun GhostBlock(isEnabled: Boolean, onEnabled: (Boolean) -> Unit, period: GhostPeriod, onPeriod: (GhostPeriod) -> Unit) {
    val colors = U2Theme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The reference paints the toggle row 2/3 dp and the label 1 dp above their boxes; painted where drawn.
        U2Toggle(
            isEnabled,
            onEnabled,
            Modifier.fillMaxWidth().testTag("ghost.toggle").graphicsLayer { translationY = -2f / 3f * density },
        ) {
            U2Text(stringResource(R.string.conversation_ghost_toggle), U2TextStyles.rowTitleEmphasis, color = colors.text)
        }
        SectionLabel(stringResource(R.string.conversation_ghost_silence), Modifier.offset(y = (-1).dp), horizontalPadding = 0.dp)
        SegmentedChips(GhostPeriod.entries, period, onPeriod, Modifier.testTag("ghost.periods"), enabled = isEnabled, identifierPrefix = "ghost.period") {
            stringResource(it.chipLabelRes)
        }
    }
}

@Composable
private fun DisappearingBlock(disappearing: DisappearingTimer, onSelect: (DisappearingTimer) -> Unit) {
    val colors = U2Theme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            U2Text(stringResource(R.string.conversation_disappearing_title), U2TextStyles.rowTitleEmphasis, color = colors.text)
            // Painted 1 dp above its box in the reference (as the label above).
            U2Text(
                stringResource(R.string.conversation_disappearing_caption),
                U2TextStyles.captionTighter,
                Modifier.offset(y = (-1).dp),
                color = colors.textSecondary,
            )
        }
        SegmentedChips(DisappearingTimer.entries, disappearing, onSelect, Modifier.testTag("ghost.disappearing"), identifierPrefix = "ghost.disappearing") {
            stringResource(it.shortLabelRes)
        }
    }
}
