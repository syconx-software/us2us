package com.syconx.us2us.feature.verification

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.Peer
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.ConnectionLine
import com.syconx.us2us.ui.components.DotGridBackdrop
import com.syconx.us2us.ui.components.DotGridMask
import com.syconx.us2us.ui.components.HeaderSpacer
import com.syconx.us2us.ui.components.InfoPill
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.text.U2DisplayText
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.env.u2Top
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.l10n.rememberU2Strings
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text

/**
 * 6.3 · Doğrulandı: the line with the verified seal on its midpoint, “Hat doğrulandı.” (Doto 40,
 * balanced), the paragraph, the date · time · method pill, and “Sohbete dön” (C1). Close and
 * “Sohbete dön” go back to the chat, whose header now carries the seal.
 *
 * Entrance: the seal scales 0.6 → 1 with its glow over 0.5 s (ease-out); title, paragraph and pill
 * fade in over 0.36 s, staggered 0.08 s; the packets keep flowing. Under reduce motion: fades only.
 */
@Composable
internal fun VerifiedView(model: VerificationModel, peer: Peer?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    var textShown by remember { mutableStateOf(false) }
    // After the first frame, so the staggered entrance is never folded into the insertion.
    LaunchedEffect(Unit) { textShown = true }
    U2Screen(
        modifier
            .testTag(VerificationTags.VERIFIED_SCREEN)
            // The screen under 6.3 takes no touches.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        DotGridBackdrop(mask = DotGridMask.Verified, color = U2Theme.colors.dotGridSoft)
        FitOrScroll {
            Row(
                Modifier
                    .fillMaxWidth()
                    .u2Top(U2Spacing.headerTopButtons)
                    .padding(horizontal = U2Spacing.screenGutterS)
                    .height(U2Size.iconButton),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                U2GlassIconButton(
                    PhosphorGlyph.X,
                    contentDescription = stringResource(R.string.common_a11y_close),
                    onClick = onDone,
                    modifier = Modifier.testTag(VerificationTags.VERIFIED_CLOSE),
                )
                Spacer(Modifier.weight(1f))
                HeaderSpacer()
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(36.dp, Alignment.CenterVertically),
            ) {
                if (peer != null) {
                    ConnectionLine(
                        ownUsername = model.ownUsername,
                        ownLabel = stringResource(R.string.verification_safety_you),
                        peer = peer,
                        showsSeal = model.isVerified,
                        animatesSeal = model.verifiedEntrance,
                        entranceDelay = model.verifiedEntranceDelay,
                        modifier = Modifier.testTag(VerificationTags.VERIFIED_LINE),
                    )
                }
                // Only a stored verification is announced: its hosts show 6.3 after one, and nothing here claims more.
                if (model.isVerified) VerifiedText(model, textShown)
            }
            U2PrimaryButton(
                title = stringResource(R.string.verification_verified_back),
                onClick = onDone,
                modifier = Modifier
                    .padding(horizontal = U2Spacing.screenGutter)
                    .u2Bottom(U2Spacing.ctaBottom)
                    .testTag(VerificationTags.VERIFIED_BACK),
            )
        }
    }
}

/** Title, paragraph and the date · time · method pill, fading in one after the other. */
@Composable
private fun VerifiedText(model: VerificationModel, shown: Boolean) {
    val strings = rememberU2Strings()
    @Composable
    fun stagger(step: Int): Float {
        val spec = if (model.verifiedEntrance) {
            U2Motion.soft(U2Motion.Duration.state).delayed(0.12f + model.verifiedEntranceDelay + 0.08f * step).spec<Float>()
        } else {
            snap()
        }
        val alpha by animateFloatAsState(if (shown) 1f else 0f, spec, label = "verifiedText$step")
        return alpha
    }
    val title = stagger(0)
    val body = stagger(1)
    val pill = stagger(2)
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(VerificationTags.VERIFIED_TEXT)
            .padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        U2DisplayText(
            stringResource(R.string.verification_verified_title),
            U2TextStyles.displayTitleLBalanced,
            textAlign = TextAlign.Center,
            isHeader = true,
            modifier = Modifier.graphicsLayer { alpha = title }.testTag(VerificationTags.VERIFIED_TITLE),
        )
        U2Text(
            stringResource(R.string.verification_verified_body, model.peerName),
            U2TextStyles.body,
            color = U2Theme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = body }
                .testTag(VerificationTags.VERIFIED_BODY),
        )
    }
    val line = model.info?.let { VerificationText.verifiedLine(it, strings) }
    if (line != null) {
        InfoPill(line, Modifier.graphicsLayer { alpha = pill }.testTag(VerificationTags.VERIFIED_PILL))
    }
}
