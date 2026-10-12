package com.syconx.us2us.ui.security

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.DotGridBackdrop
import com.syconx.us2us.ui.components.DotGridMask
import com.syconx.us2us.ui.components.LineMark
import com.syconx.us2us.ui.components.LineMarkMetrics
import com.syconx.us2us.ui.components.U2Wordmark
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Opacity
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text

/** Test tags of the cover's parts (3.4 `r.3.2.0` mark, `r.3.2.1` wordmark, `r.3.2.2` “Kilitli”). */
object PrivacyCoverTags {
    const val COVER = "privacy.cover"
    const val MARK = "privacy.cover.mark"
    const val WORDMARK = "privacy.cover.wordmark"
    const val LOCKED = "privacy.cover.locked"
}

/**
 * 3.4: the cover the app shows the moment it stops being in front (the recents screen, a system
 * dialog over it): the current screen blurred (≈13 dp) at 75 %, a `bg·45 %` tint, and the centred
 * line mark, wordmark and “Kilitli”. The design draws it at 0.70 scale inside the switcher card;
 * here it is full size (× 1 / 0.7015). Without a [snapshot]: solid `bg` with the dot grid.
 *
 * Under the lock it is the base without the mark ([showsMark] = `false`).
 */
@Composable
fun PrivacyCover(snapshot: ImageBitmap?, modifier: Modifier = Modifier, showsMark: Boolean = true) {
    val colors = U2Theme.colors
    Box(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .testTag(PrivacyCoverTags.COVER),
        contentAlignment = Alignment.Center,
    ) {
        if (snapshot != null) {
            Image(
                bitmap = snapshot,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                alpha = U2Opacity.coverContent,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            DotGridBackdrop(mask = DotGridMask.Lock, color = colors.dotGridSoft)
        }
        Box(Modifier.fillMaxSize().background(colors.coverTint))
        if (showsMark) {
            val scale = COVER_SCALE
            val locked = stringResource(R.string.common_lock_cover_locked)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp * scale),
                modifier = Modifier.semantics(mergeDescendants = true) { },
            ) {
                LineMark(Modifier.testTag(PrivacyCoverTags.MARK), metrics = LineMarkMetrics.Cover)
                U2Wordmark(Modifier.testTag(PrivacyCoverTags.WORDMARK), style = U2TextStyles.brandWordmarkL.scaled(scale))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp * scale),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag(PrivacyCoverTags.LOCKED),
                ) {
                    PhosphorIcon(PhosphorGlyph.LockSimple, size = 13.dp * scale, tint = colors.textSecondary)
                    U2Text(locked, U2TextStyles.monoMeta.scaled(scale), color = colors.textSecondary)
                }
            }
        }
    }
}

/** The cover is drawn at full size; the design's thumbnail is 0.7015 of it. */
const val COVER_SCALE: Float = 1f / 0.7015f

/** The switcher card's scale of the full-size cover. */
const val SWITCHER_SCALE: Float = 0.7015f

