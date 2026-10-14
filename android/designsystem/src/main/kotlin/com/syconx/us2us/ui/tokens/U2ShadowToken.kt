package com.syconx.us2us.ui.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * One CSS `box-shadow` layer as tokens.json writes it: `[inset] x y blur spread colour`.
 * An inset layer without blur is an inner stroke of `spread` width; an outer layer
 * without blur is a ring of `spread` width.
 */
@Immutable
data class U2ShadowLayer(
    val inset: Boolean,
    val x: Dp,
    val y: Dp,
    val blur: Dp,
    val spread: Dp,
    val color: U2ThemedColor,
)

/**
 * A shadow token: its layers in CSS order (the first is painted on top).
 * [isDropShadowFilter] marks `filter: drop-shadow(…)`, which follows the drawn glyph's
 * alpha instead of the box (the map pin).
 */
@Immutable
data class U2ShadowToken(
    val name: String,
    val layers: List<U2ShadowLayer>,
    val isDropShadowFilter: Boolean = false,
)

/** The 1.1 welcome scene's palette for one theme (tokens.json › scene3D.palette). */
@Immutable
data class U2ScenePalette(
    val cup: Color,
    val edge: Color,
    val line: Color,
    val core: Color,
    val glow: Color,
    val dust: Color,
    val sky: Color,
    val ground: Color,
    val key: Color,
    val rim: Color,
    /** Additive blending for the glow, pulses and dust (dark); normal blending (light). */
    val additiveBlending: Boolean,
    val wireOp: Float,
    val dustOp: Float,
    val glowOp: Float,
)
