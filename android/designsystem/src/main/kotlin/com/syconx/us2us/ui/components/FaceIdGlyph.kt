package com.syconx.us2us.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import com.syconx.us2us.ui.theme.U2Theme

/**
 * The design's own Face ID glyph (icons/custom/face-id.svg, a 24 × 24 stroke path with round caps
 * and joins), preferred over a platform symbol.
 *
 * [lineWidth] is the stroke in the glyph's 24-unit space and scales with the glyph: 56 @ 1.3
 * (3.1 disc), 30 @ 1.6 (keypad key), 22 @ 1.7 (2.3 card), 18 @ 1.8 (settings tile). Decorative:
 * the control it sits in carries the label.
 */
@Composable
fun FaceIdGlyph(
    size: Dp,
    lineWidth: Float,
    modifier: Modifier = Modifier,
    color: Color = U2Theme.colors.text,
) {
    val path = remember { FaceIdShape.path() }
    Canvas(modifier.size(size)) {
        // Drawn on the 24-unit grid, where the stroke width is given.
        scale(this.size.minDimension / FaceIdShape.GRID, pivot = Offset.Zero) {
            drawPath(path, color, style = Stroke(width = lineWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/**
 * `M2 7V5a3 3 0 0 1 3-3h2 M17 2h2a3 3 0 0 1 3 3v2 M22 17v2a3 3 0 0 1-3 3h-2
 * M7 22H5a3 3 0 0 1-3-3v-2 M8.5 9v1.5 M15.5 9v1.5 M12 9v4.5h-1 M9 16.5c1.7 1.3 4.3 1.3 6 0`
 * on the 24-unit grid.
 */
object FaceIdShape {
    /** The SVG's view box edge. */
    const val GRID: Float = 24f

    /** The outline on the 24-unit grid (corners are quarter circles of radius 3). */
    fun path(): Path = Path().apply {
        fun corner(cx: Float, cy: Float, start: Float) =
            arcTo(Rect(Offset(cx, cy), 3f), startAngleDegrees = start, sweepAngleDegrees = 90f, forceMoveTo = false)

        moveTo(2f, 7f)
        lineTo(2f, 5f)
        corner(5f, 5f, 180f)
        lineTo(7f, 2f)

        moveTo(17f, 2f)
        lineTo(19f, 2f)
        corner(19f, 5f, 270f)
        lineTo(22f, 7f)

        moveTo(22f, 17f)
        lineTo(22f, 19f)
        corner(19f, 19f, 0f)
        lineTo(17f, 22f)

        moveTo(7f, 22f)
        lineTo(5f, 22f)
        corner(5f, 19f, 90f)
        lineTo(2f, 17f)

        // Eyes.
        moveTo(8.5f, 9f)
        lineTo(8.5f, 10.5f)
        moveTo(15.5f, 9f)
        lineTo(15.5f, 10.5f)

        // Nose.
        moveTo(12f, 9f)
        lineTo(12f, 13.5f)
        lineTo(11f, 13.5f)

        // Smile.
        moveTo(9f, 16.5f)
        cubicTo(10.7f, 17.8f, 13.3f, 17.8f, 15f, 16.5f)
    }
}
