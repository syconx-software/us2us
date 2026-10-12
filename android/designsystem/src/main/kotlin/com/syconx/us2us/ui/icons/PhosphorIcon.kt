package com.syconx.us2us.ui.icons

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.theme.U2Theme

/** Phosphor weights bundled by the app (regular + fill, icons.md). */
enum class PhosphorWeight {
    Regular,
    Fill,
}

/**
 * A Phosphor 2.1.1 glyph drawn exactly like the design: the CSS font-size of a Phosphor glyph
 * equals its square box, so “ph:ghost 16” is a 16 × 16 dp box (icons.md › Sizing rule). The glyph
 * is one colour, [tint] (the design's token: mostly `text`, `text·68%`, `accent300`, `danger`,
 * `online`).
 *
 * ```
 * PhosphorIcon(PhosphorGlyph.Ghost, size = 16.dp, tint = U2Theme.colors.accent300)
 * PhosphorIcon(PhosphorGlyph.Checks, size = 15.dp, weight = PhosphorWeight.Fill)
 * ```
 *
 * Icons are decorative by default (no semantics); pass [contentDescription] when the glyph carries
 * meaning on its own.
 */
@Composable
fun PhosphorIcon(
    glyph: PhosphorGlyph,
    size: Dp,
    modifier: Modifier = Modifier,
    weight: PhosphorWeight = PhosphorWeight.Regular,
    tint: Color = U2Theme.colors.text,
    contentDescription: String? = null,
) {
    Image(
        painter = rememberVectorPainter(glyph.imageVector(weight)),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

/**
 * The glyph as a vector on its 256-unit viewport, made once per glyph and weight and tinted at
 * draw time. Throws when the design does not use [weight] for this glyph (icons.md).
 */
fun PhosphorGlyph.imageVector(weight: PhosphorWeight = PhosphorWeight.Regular): ImageVector {
    val index = ordinal * 2 + weight.ordinal
    PhosphorCache.vectors[index]?.let { return it }
    val vector = ImageVector.Builder(
        name = assetName(weight),
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = PhosphorCache.GRID,
        viewportHeight = PhosphorCache.GRID,
    ).addPath(pathData = PathParser().parsePathString(pathData(weight)).toNodes(), fill = SolidColor(Color.Black)).build()
    PhosphorCache.vectors[index] = vector
    return vector
}

/** The glyph's outline on Phosphor's 256-unit grid (for drawing it inside a Canvas). */
fun PhosphorGlyph.path(weight: PhosphorWeight = PhosphorWeight.Regular): Path =
    PathParser().parsePathString(pathData(weight)).toPath()

/**
 * Draws the glyph in a [size] box at [topLeft] in [color], as [PhosphorIcon] does, for glyphs
 * inside custom drawing (badges over media, map pins).
 */
fun DrawScope.drawPhosphorGlyph(
    glyph: PhosphorGlyph,
    topLeft: Offset,
    size: Float,
    color: Color,
    weight: PhosphorWeight = PhosphorWeight.Regular,
) {
    val index = glyph.ordinal * 2 + weight.ordinal
    val path = PhosphorCache.paths[index] ?: glyph.path(weight).also { PhosphorCache.paths[index] = it }
    translate(topLeft.x, topLeft.y) {
        scale(size / PhosphorCache.GRID, pivot = Offset.Zero) {
            drawPath(path, color)
        }
    }
}

private fun PhosphorGlyph.pathData(weight: PhosphorWeight): String {
    val data = if (weight == PhosphorWeight.Fill) fillPath else regularPath
    return requireNotNull(data) { "ph:$raw ($weight) is not bundled — see ios/design/icons.md" }
}

/** Vectors and outlines, built on first use. A race builds one twice, which is harmless. */
private object PhosphorCache {
    const val GRID = 256f
    val vectors = arrayOfNulls<ImageVector>(PhosphorGlyph.entries.size * 2)
    val paths = arrayOfNulls<Path>(PhosphorGlyph.entries.size * 2)
}
