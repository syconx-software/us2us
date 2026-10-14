package com.syconx.us2us.ui.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import com.syconx.us2us.ui.theme.U2Theme

/**
 * The media controls' glyphs the design file does not bundle (player, downloads, generic files,
 * the voice recorder: proposed states of 5.1 / 5.2 / 5.6), drawn in code on Phosphor's own
 * 256-unit grid so they sit with the bundled set: regular = 16-unit strokes with round caps and
 * joins, fill = solid shapes. Sized like `PhosphorIcon`: “N dp” is an N × N box.
 */
enum class MediaGlyph {
    /** `pause` (fill): two rounded bars. */
    Pause,

    /** `speaker-high` (regular). */
    SpeakerHigh,

    /** `speaker-x` (regular): the speaker with a cross, muted. */
    SpeakerX,

    /** `download-simple` (regular): an arrow into a tray. */
    Download,

    /** `file` (regular): a page with a folded corner. */
    File,

    /** `stop` (fill): a rounded square; stops a voice recording. */
    Stop,

    /** `caret-up` (regular): the voice recorder's “slide up to lock”. */
    CaretUp,

    /** `arrow-down` (regular): the “Kaydetti” chip under a saved photo. */
    ArrowDown,
    ;

    val isFilled: Boolean get() = this == Pause || this == Stop

    /** The glyph's outline in a square of [side] px, from the 256-unit grid. */
    fun path(side: Float): Path {
        val unit = side / 256f
        fun x(v: Float) = v * unit
        val path = Path()
        when (this) {
            Pause -> for (left in floatArrayOf(40f, 144f)) {
                path.addRoundRect(RoundRect(Rect(x(left), x(32f), x(left + 72f), x(224f)), CornerRadius(x(16f))))
            }
            SpeakerHigh, SpeakerX -> {
                // The cone and the magnet.
                path.moveTo(x(80f), x(168f))
                path.lineTo(x(32f), x(168f))
                path.quadraticTo(x(24f), x(168f), x(24f), x(160f))
                path.lineTo(x(24f), x(96f))
                path.quadraticTo(x(24f), x(88f), x(32f), x(88f))
                path.lineTo(x(80f), x(88f))
                path.lineTo(x(152f), x(32f))
                path.lineTo(x(152f), x(224f))
                path.close()
                path.moveTo(x(80f), x(88f))
                path.lineTo(x(80f), x(168f))
                if (this == SpeakerHigh) {
                    path.moveTo(x(192f), x(106.85f))
                    path.arcTo(circle(x(168f), x(128f), x(32f)), -41f, 82f, false)
                    path.moveTo(x(221.7f), x(80f))
                    path.arcTo(circle(x(168f), x(128f), x(72f)), -41.8f, 83.6f, false)
                } else {
                    path.moveTo(x(200f), x(104f))
                    path.lineTo(x(248f), x(152f))
                    path.moveTo(x(248f), x(104f))
                    path.lineTo(x(200f), x(152f))
                }
            }
            Download -> {
                path.moveTo(x(86f), x(110f))
                path.lineTo(x(128f), x(152f))
                path.lineTo(x(170f), x(110f))
                path.moveTo(x(128f), x(40f))
                path.lineTo(x(128f), x(152f))
                path.moveTo(x(216f), x(152f))
                path.lineTo(x(216f), x(208f))
                path.quadraticTo(x(216f), x(216f), x(208f), x(216f))
                path.lineTo(x(48f), x(216f))
                path.quadraticTo(x(40f), x(216f), x(40f), x(208f))
                path.lineTo(x(40f), x(152f))
            }
            File -> {
                path.moveTo(x(200f), x(224f))
                path.lineTo(x(56f), x(224f))
                path.quadraticTo(x(48f), x(224f), x(48f), x(216f))
                path.lineTo(x(48f), x(40f))
                path.quadraticTo(x(48f), x(32f), x(56f), x(32f))
                path.lineTo(x(152f), x(32f))
                path.lineTo(x(208f), x(88f))
                path.lineTo(x(208f), x(216f))
                path.quadraticTo(x(208f), x(224f), x(200f), x(224f))
                path.close()
                path.moveTo(x(152f), x(32f))
                path.lineTo(x(152f), x(88f))
                path.lineTo(x(208f), x(88f))
            }
            Stop -> path.addRoundRect(RoundRect(Rect(x(40f), x(40f), x(216f), x(216f)), CornerRadius(x(16f))))
            CaretUp -> {
                path.moveTo(x(48f), x(160f))
                path.lineTo(x(128f), x(80f))
                path.lineTo(x(208f), x(160f))
            }
            ArrowDown -> {
                path.moveTo(x(128f), x(40f))
                path.lineTo(x(128f), x(216f))
                path.moveTo(x(56f), x(144f))
                path.lineTo(x(128f), x(216f))
                path.lineTo(x(200f), x(144f))
            }
        }
        return path
    }

    private fun circle(cx: Float, cy: Float, r: Float) = Rect(cx - r, cy - r, cx + r, cy + r)
}

/** A [MediaGlyph] in a [size] × [size] box, in [tint]. Decorative (no semantics). */
@Composable
fun MediaGlyphIcon(glyph: MediaGlyph, size: Dp, modifier: Modifier = Modifier, tint: Color = U2Theme.colors.text) {
    Canvas(modifier.size(size)) { drawMediaGlyph(glyph, Offset.Zero, this.size.minDimension, tint) }
}

/** Draws [glyph] in a [side] px box at [topLeft], as [MediaGlyphIcon] does. */
fun DrawScope.drawMediaGlyph(glyph: MediaGlyph, topLeft: Offset, side: Float, color: Color) {
    val path = glyph.path(side)
    translate(topLeft.x, topLeft.y) {
        if (glyph.isFilled) {
            drawPath(path, color)
        } else {
            drawPath(path, color, style = Stroke(width = side * 16f / 256f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
