package com.syconx.us2us.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.theme.U2Theme
import kotlin.math.max
import kotlin.math.sqrt

/** The radial mask of a dot-grid backdrop: ellipse radii and centre as fractions of the width and height. */
@Immutable
data class DotGridMask(val radiusX: Float, val radiusY: Float, val centerX: Float, val centerY: Float) {
    companion object {
        /** 1.1 welcome: 85 % × 42 % at 50 % / 28 %. */
        val Welcome = DotGridMask(radiusX = 0.85f, radiusY = 0.42f, centerX = 0.5f, centerY = 0.28f)

        /** 3.1 Face ID: 70 % × 38 % at 50 % / 46 %. */
        val Lock = DotGridMask(radiusX = 0.70f, radiusY = 0.38f, centerX = 0.5f, centerY = 0.46f)

        /** 6.3 verified: 70 % × 36 % at 50 % / 40 %. */
        val Verified = DotGridMask(radiusX = 0.70f, radiusY = 0.36f, centerX = 0.5f, centerY = 0.40f)
    }
}

/**
 * C24 — the dot-grid backdrop: dots r 1 (soft edge to 1.6) every 18 dp, faded out by an elliptical
 * radial mask (opaque centre → transparent at 75 %). [color]: `dotGrid` (16 %) on 1.1,
 * `dotGridSoft` (14 %) on 3.1 / 6.3. It fills the offered size.
 */
@Composable
fun DotGridBackdrop(
    modifier: Modifier = Modifier,
    mask: DotGridMask = DotGridMask.Welcome,
    color: Color = U2Theme.colors.dotGrid,
    pitch: Dp = 18.dp,
) {
    Box(
        modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .drawWithCache {
                val step = pitch.toPx()
                val solid = 1.dp.toPx()
                val edge = 1.6.dp.toPx()
                val dot = Brush.radialGradient(
                    0f to color,
                    solid / edge to color,
                    1f to color.copy(alpha = 0f),
                    center = Offset.Zero,
                    radius = edge,
                )
                val cx = size.width * mask.centerX
                val cy = size.height * mask.centerY
                val rx = size.width * mask.radiusX
                val ry = size.height * mask.radiusY
                val width = size.width
                val height = size.height
                // Each dot takes the mask's opacity at its centre.
                val dots = buildList {
                    var y = step / 2
                    while (y < height) {
                        var x = step / 2
                        while (x < width) {
                            val dx = (x - cx) / rx
                            val dy = (y - cy) / ry
                            val alpha = max(0f, 1f - sqrt(dx * dx + dy * dy) / 0.75f)
                            if (alpha > 0.01f) add(Triple(x, y, alpha))
                            x += step
                        }
                        y += step
                    }
                }
                onDrawBehind {
                    for ((x, y, alpha) in dots) {
                        translate(x, y) { drawCircle(dot, radius = edge, center = Offset.Zero, alpha = alpha) }
                    }
                }
            },
    )
}
