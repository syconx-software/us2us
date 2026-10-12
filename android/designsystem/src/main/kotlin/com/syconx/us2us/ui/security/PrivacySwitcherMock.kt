package com.syconx.us2us.ui.security

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.LineMark
import com.syconx.us2us.ui.components.LineMarkMetrics
import com.syconx.us2us.ui.theme.U2BoxShadow
import com.syconx.us2us.ui.theme.U2BoxShadows
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2BoxShadow
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text

/** Test tags of the switcher mock (3.4 `r.2` app label and `r.3` card). */
object PrivacySwitcherTags {
    const val APP_LABEL = "privacy.switcher.label"
    const val CARD = "privacy.switcher.card"
}

/**
 * The whole 3.4 drawing: wallpaper, the neighbouring card, the app label and the app's card
 * (282 × 613 at 60, 150, r36) showing the [PrivacyCover] at 0.7015. Everything but the card content
 * is the system's recents screen, drawn here only to compare with
 * `reference/3.4-uygulama-degistirici.png` (the `lock:switcher` harness state).
 */
@Composable
fun PrivacySwitcherMock(snapshot: ImageBitmap?, modifier: Modifier = Modifier) {
    val night = Color(0xFF0B0C14)
    Box(
        modifier
            .fillMaxSize()
            .background(night),
    ) {
        Wallpaper(
            Modifier
                .offset((-40).dp, (-40).dp)
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .requiredSize(482.dp, 954.dp)
                .blur(12.dp),
        )
        Neighbour(Modifier.offset((-196).dp, 176.dp))
        AppLabel(Modifier.offset(60.dp, 104.dp).testTag(PrivacySwitcherTags.APP_LABEL))
        Card(snapshot, Modifier.offset(60.dp, 150.dp).testTag(PrivacySwitcherTags.CARD))
    }
}

/**
 * CSS: two elliptical radial gradients (50 % × 35 % at 25 % / 20 %, 55 % × 40 % at 85 % / 85 %,
 * both fading out at 70 %) over a vertical gradient, on a 482 × 954 box blurred 12.
 */
@Composable
private fun Wallpaper(modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF1D1F33), Color(0xFF0B0C14))))
        blob(Color(53, 59, 128).copy(alpha = 0.7f), radiusX = 0.55f * size.width, radiusY = 0.40f * size.height, center = Offset(0.85f * size.width, 0.85f * size.height))
        blob(Color(145, 132, 217).copy(alpha = 0.45f), radiusX = 0.50f * size.width, radiusY = 0.35f * size.height, center = Offset(0.25f * size.width, 0.20f * size.height))
    }
}

/** An elliptical radial gradient from [color] to transparent at 70 % of the radii. */
private fun DrawScope.blob(color: Color, radiusX: Float, radiusY: Float, center: Offset) {
    val radius = radiusX * 0.7f
    scale(scaleX = 1f, scaleY = radiusY / radiusX, pivot = center) {
        drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center = center, radius = radius), radius = radius, center = center)
    }
}

/** The neighbouring app's card, a light placeholder. */
@Composable
private fun Neighbour(modifier: Modifier) {
    val shape = RoundedCornerShape(32.dp)
    Column(
        modifier
            .wrapContentSize(Alignment.TopStart, unbounded = true)
            .requiredSize(252.dp, 548.dp)
            .u2BoxShadow(shape, U2BoxShadow(Color.Black.copy(alpha = 0.5f), y = 30.dp, blur = 60.dp))
            .clip(shape)
            .background(Color(0xFFE8E9EF))
            .padding(horizontal = 22.dp, vertical = 64.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(124.8.dp, 16.dp).background(Color(0xFFC9CBD6), RoundedCornerShape(4.dp)))
        Box(Modifier.size(187.19.dp, 9.dp).background(Color(0xFFD6D8E1), RoundedCornerShape(4.dp)))
        Box(Modifier.size(166.39.dp, 9.dp).background(Color(0xFFD6D8E1), RoundedCornerShape(4.dp)))
    }
}

/** The app's icon (30 × 30, r7) and name, as the recents screen shows them. */
@Composable
private fun AppLabel(modifier: Modifier) {
    val label = Color(0xFFE9E9ED)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        val shape = RoundedCornerShape(7.dp)
        Box(
            Modifier
                .size(30.dp)
                .clip(shape)
                .background(Color(0xFF161826))
                .background(IconTileGradient)
                .u2InnerStroke(label.copy(alpha = 0.2f), 0.5.dp, shape),
            contentAlignment = Alignment.Center,
        ) {
            LineMark(metrics = LineMarkMetrics(ringDiameter = 6.dp, ringStroke = 1.5.dp, width = 20.dp, barHeight = 1.5.dp), color = Color(0xFFB5ABFC))
        }
        U2Text(stringResource(R.string.infoplist_cfbundledisplayname), U2TextStyles.switcherAppName, color = label)
    }
}

/** The icon tile's `radial-gradient(90% 90% at 30% 15%, #423a6a, #161826 72%)`. */
private val IconTileGradient = object : ShaderBrush() {
    override fun createShader(size: Size): Shader = RadialGradientShader(
        center = Offset(size.width * 0.30f, size.height * 0.15f),
        radius = size.width * 0.9f * 0.72f,
        colors = listOf(Color(0xFF423A6A), Color(0xFF161826)),
    )
}

/** The app's card: the full-size cover at 0.7015, clipped r36, with the inset hairline under the tint. */
@Composable
private fun Card(snapshot: ImageBitmap?, modifier: Modifier) {
    val shape = RoundedCornerShape(36.dp)
    val colors = U2Theme.colors
    Box(
        modifier
            .wrapContentSize(Alignment.TopStart, unbounded = true)
            .requiredSize(282.dp, 613.dp)
            .u2BoxShadow(shape, U2BoxShadows.switcherCard)
            .clip(shape),
    ) {
        Box(
            Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .requiredSize(402.dp, 874.dp)
                .graphicsLayer {
                    scaleX = SWITCHER_SCALE
                    scaleY = SWITCHER_SCALE
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) {
            PrivacyCover(snapshot)
        }
        // CSS corners are circular; the inset hairline (text·10 %) lies under the cover's bg·45 % tint.
        Box(
            Modifier
                .fillMaxSize()
                .u2InnerStroke(colors.text.copy(alpha = 0.10f), 1.dp, shape)
                .u2InnerStroke(colors.coverTint, 1.dp, shape),
        )
    }
}
