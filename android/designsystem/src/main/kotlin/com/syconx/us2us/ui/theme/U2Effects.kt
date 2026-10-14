package com.syconx.us2us.ui.theme

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.tokens.U2ShadowLayer
import com.syconx.us2us.ui.tokens.U2ShadowToken
import com.syconx.us2us.ui.tokens.U2ShadowTokens
import kotlin.math.max

/**
 * One CSS `box-shadow` layer (tokens.md › Shadows & glows), resolved to a colour.
 *
 * CSS draws an outer box-shadow from the box's outline grown by [spread] (corner radii grow
 * with it), offset by ([x], [y]), blurred with σ = [blur] / 2, and **only outside the box**:
 * under a transparent outline button the glow does not show through. [u2BoxShadow] draws
 * exactly that, unlike `Modifier.shadow`, which is an elevation shadow.
 */
@Immutable
data class U2BoxShadow(
    val color: Color,
    val x: Dp = 0.dp,
    val y: Dp = 0.dp,
    val blur: Dp,
    val spread: Dp = 0.dp,
)

/** The design's single-layer glows and drops, resolved for the current theme (`U2BoxShadow.*` on iOS). */
object U2BoxShadows {
    /** `0 0 32px -8px accent·70%` — primary outline CTA. */
    val glowButton: U2BoxShadow @Composable @ReadOnlyComposable get() = U2BoxShadow(U2Theme.colors.accentGlow, blur = 32.dp, spread = (-8).dp)

    /** `0 0 26px -8px accent·70%` — 4.4 “Kabul et”. */
    val glowButtonAccept: U2BoxShadow @Composable @ReadOnlyComposable get() = U2BoxShadow(U2Theme.colors.accentGlow, blur = 26.dp, spread = (-8).dp)

    /** `0 0 20px -6px accent·70%` — composer mic button. */
    val glowMic: U2BoxShadow @Composable @ReadOnlyComposable get() = U2BoxShadow(U2Theme.colors.accentGlow, blur = 20.dp, spread = (-6).dp)

    /** `0 0 26px -10px accent` — focused text field (outer part of `glowField`). */
    val glowField: U2BoxShadow @Composable @ReadOnlyComposable get() = U2BoxShadow(U2Theme.colors.accent, blur = 26.dp, spread = (-10).dp)

    /** `0 0 12px accent·70%` — filled code dot. */
    val glowCodeDot: U2BoxShadow @Composable @ReadOnlyComposable get() = U2BoxShadow(U2Theme.colors.accentGlow, blur = 12.dp)

    /** `0 0 70px -12px accent·60%` — 3.1 Face ID disc. */
    val glowFaceID: U2BoxShadow @Composable @ReadOnlyComposable get() = U2BoxShadow(U2Theme.colors.accentGlowSoft, blur = 70.dp, spread = (-12).dp)

    /** `0 0 32px -8px danger·45%` — the danger button's glow. */
    val glowDanger: U2BoxShadow @Composable @ReadOnlyComposable get() = U2BoxShadow(U2Theme.colors.dangerStroke, blur = 32.dp, spread = (-8).dp)

    /** `0 20px 50px -20px accent·60%` — 6.1 QR card. */
    val glowQRCard: U2BoxShadow @Composable @ReadOnlyComposable get() = U2BoxShadow(U2Theme.colors.accentGlowSoft, y = 20.dp, blur = 50.dp, spread = (-20).dp)

    /** `0 0 24px accent·55%` — 6.3 verified seal. */
    val glowSeal: U2BoxShadow @Composable @ReadOnlyComposable get() = U2BoxShadow(U2Theme.colors.accentGlowSeal, blur = 24.dp)

    /** `0 0 12px 2px accent·50%` — light packet on the line. */
    val glowPacket: U2BoxShadow @Composable @ReadOnlyComposable get() = U2BoxShadow(U2Theme.colors.accentGlowPacket, blur = 12.dp, spread = 2.dp)

    /** `0 0 18px accent·80%` — live-location dot. */
    val glowLiveDot: U2BoxShadow @Composable @ReadOnlyComposable get() = U2BoxShadow(U2Theme.colors.accentGlowLive, blur = 18.dp)

    /** `0 0 18px 3px #9184d9@70%` — 6.2 scan line. */
    val glowScanLine: U2BoxShadow @Composable @ReadOnlyComposable get() = U2BoxShadow(U2Theme.colors.scanGlow, blur = 18.dp, spread = 3.dp)

    /** `0 -12px 40px #000@35%` — 3.3 floating sheet. */
    val sheetFloating: U2BoxShadow get() = U2BoxShadow(Color.Black.copy(alpha = 0.35f), y = (-12).dp, blur = 40.dp)

    /** `0 16px 40px #000@40%` — 5.3 lifted bubble. */
    val bubbleLifted: U2BoxShadow get() = U2BoxShadow(Color.Black.copy(alpha = 0.4f), y = 16.dp, blur = 40.dp)

    /** `0 10px 30px #000@30%` — 5.1 urgent banner. */
    val floatingBanner: U2BoxShadow get() = U2BoxShadow(Color.Black.copy(alpha = 0.3f), y = 10.dp, blur = 30.dp)

    /** `0 14px 40px #000@45%` — 5.6 screenshot warning. */
    val floatingWarning: U2BoxShadow get() = U2BoxShadow(Color.Black.copy(alpha = 0.45f), y = 14.dp, blur = 40.dp)

    /** `0 12px 26px #000@35%` — 5.2 sticker. */
    val sticker: U2BoxShadow get() = U2BoxShadow(Color.Black.copy(alpha = 0.35f), y = 12.dp, blur = 26.dp)

    /** `0 20px 40px #000@45%` — 6.2 phone QR mock. */
    val qrScanCard: U2BoxShadow get() = U2BoxShadow(Color.Black.copy(alpha = 0.45f), y = 20.dp, blur = 40.dp)

    /** `0 34px 70px #000@55%` — 3.4 app-switcher card. */
    val switcherCard: U2BoxShadow get() = U2BoxShadow(Color.Black.copy(alpha = 0.55f), y = 34.dp, blur = 70.dp)

    /** `0 2px 6px #000@25%` — the switch knob. */
    val toggleKnob: U2BoxShadow get() = U2BoxShadow(Color.Black.copy(alpha = 0.25f), y = 2.dp, blur = 6.dp)

    /** Drop part of `shadowMd` — dark `0 6px 18px #000@55%`, light `0 6px 18px #292b31@10%`. */
    val mdDrop: U2BoxShadow
        @Composable @ReadOnlyComposable
        get() = U2BoxShadow(U2ShadowTokens.shadowMd.layers[1].color.resolve(U2Theme.isDark), y = 6.dp, blur = 18.dp)
}

/** Conversions shared by the effects. */
object U2Effects {
    /**
     * The radius Android's blurs take (BlurMaskFilter, RenderEffect / Compose `BlurEffect`) for a
     * Gaussian standard deviation [sigmaPx]: the platform converts a radius r to
     * σ = 0.57735 · r + 0.5, so r = (σ − 0.5) / 0.57735.
     *
     * CSS `box-shadow` blur B is σ = B / 2; CSS `filter: blur(N)` and `backdrop-filter` are σ = N.
     */
    fun blurRadiusForSigma(sigmaPx: Float): Float = max((sigmaPx - 0.5f) / 0.57735f, 0.01f)

    /** A token layer resolved for a theme. */
    internal fun resolve(layer: U2ShadowLayer, dark: Boolean): U2BoxShadow =
        U2BoxShadow(color = layer.color.resolve(dark), x = layer.x, y = layer.y, blur = layer.blur, spread = layer.spread)
}

// ---------------------------------------------------------------------------------------------
// Box shadows

/**
 * Draws CSS box-shadows behind the content, outside [shape] only (`u2BoxShadow` on iOS).
 * The first shadow is painted on top, as CSS lists them. Put it before the background:
 * `Modifier.u2BoxShadow(shape, glow).background(fill, shape)`.
 */
fun Modifier.u2BoxShadow(shape: Shape, vararg shadows: U2BoxShadow): Modifier =
    if (shadows.isEmpty()) this else drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val layers = shadowLayers(outline, shadows.asList())
        onDrawBehind { drawShadowLayers(outline, layers) }
    }

/** `inset 0 0 0 Npx C` — an inner stroke of [width], drawn over the content. */
fun Modifier.u2InnerStroke(color: Color, width: Dp, shape: Shape): Modifier =
    drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val stroke = innerStroke(outline, width.toPx())
        onDrawWithContent {
            drawContent()
            drawInnerStroke(stroke, color)
        }
    }

/** `inset 0 0 0 Npx C` on a rounded rectangle. */
fun Modifier.u2InnerStroke(color: Color, width: Dp, cornerRadius: Dp): Modifier =
    u2InnerStroke(color, width, RoundedCornerShape(cornerRadius))

/** `0 0 0 Npx C` — an outer ring (the online dot's cut-out ring in `bg`), outside [shape] only. */
fun Modifier.u2OuterRing(color: Color, width: Dp, shape: Shape = CircleShape): Modifier =
    u2BoxShadow(shape, U2BoxShadow(color = color, blur = 0.dp, spread = width))

/**
 * Every layer of a shadow token, resolved for the current theme: outer layers behind the
 * content (outside [shape]), inset layers as inner strokes over it. A `drop-shadow` filter
 * token (the map pin) follows the content's alpha instead of [shape].
 */
fun Modifier.u2Shadow(token: U2ShadowToken, shape: Shape): Modifier =
    if (token.isDropShadowFilter) u2DropShadowFilter(token) else this then ShadowTokenElement(token, shape)

/** `shadowMd`: 1 dp ring + soft drop (5.3 reaction bar and action menu). */
fun Modifier.u2ShadowMd(shape: Shape): Modifier = u2Shadow(U2ShadowTokens.shadowMd, shape)

/** `shadowMd` on a rounded rectangle. */
fun Modifier.u2ShadowMd(cornerRadius: Dp): Modifier = u2ShadowMd(RoundedCornerShape(cornerRadius))

/**
 * `filter: drop-shadow(x y blur colour)`: a blurred, tinted copy of the drawn content under it
 * (the map pin). The blur needs API 31; below that the copy is drawn sharp.
 */
fun Modifier.u2DropShadowFilter(token: U2ShadowToken): Modifier = this then DropShadowElement(token)

private data class ShadowTokenElement(val token: U2ShadowToken, val shape: Shape) : ModifierNodeElement<ShadowTokenNode>() {
    override fun create() = ShadowTokenNode(token, shape)

    override fun update(node: ShadowTokenNode) {
        node.token = token
        node.shape = shape
        node.invalidateCache()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "u2Shadow"
        properties["token"] = token.name
    }
}

private class ShadowTokenNode(var token: U2ShadowToken, var shape: Shape) :
    Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private var cacheKey: Any? = null
    private var outline: Outline? = null
    private var outer: List<ShadowLayer> = emptyList()
    private var inner: List<Pair<InnerStroke, Color>> = emptyList()

    fun invalidateCache() {
        cacheKey = null
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        val dark = currentValueOf(LocalU2Colors).isDark
        val key = listOf(size, layoutDirection, density, dark, token, shape)
        if (key != cacheKey) {
            val o = shape.createOutline(size, layoutDirection, this)
            outline = o
            val (insetLayers, outerLayers) = token.layers.partition { it.inset }
            outer = shadowLayers(o, outerLayers.map { U2Effects.resolve(it, dark) })
            inner = insetLayers.map { innerStroke(o, it.spread.toPx()) to it.color.resolve(dark) }
            cacheKey = key
        }
        val o = outline ?: return drawContent()
        drawShadowLayers(o, outer)
        drawContent()
        // CSS paints the first inset layer on top.
        for (i in inner.indices.reversed()) drawInnerStroke(inner[i].first, inner[i].second)
    }
}

private data class DropShadowElement(val token: U2ShadowToken) : ModifierNodeElement<DropShadowNode>() {
    override fun create() = DropShadowNode(token)

    override fun update(node: DropShadowNode) {
        node.token = token
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "u2DropShadowFilter"
        properties["token"] = token.name
    }
}

private class DropShadowNode(var token: U2ShadowToken) :
    Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private var layer: GraphicsLayer? = null

    override fun onAttach() {
        layer = requireGraphicsContext().createGraphicsLayer()
    }

    override fun onDetach() {
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        layer = null
    }

    override fun ContentDrawScope.draw() {
        val shadow = token.layers.firstOrNull()
        val graphicsLayer = layer
        if (shadow == null || graphicsLayer == null) return drawContent()
        val color = shadow.color.resolve(currentValueOf(LocalU2Colors).isDark)
        graphicsLayer.record { this@draw.drawContent() }
        graphicsLayer.colorFilter = ColorFilter.tint(color.copy(alpha = 1f), BlendMode.SrcIn)
        graphicsLayer.alpha = color.alpha
        graphicsLayer.renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val radius = U2Effects.blurRadiusForSigma(shadow.blur.toPx() / 2f)
            BlurEffect(radius, radius, TileMode.Decal)
        } else {
            null
        }
        translate(shadow.x.toPx(), shadow.y.toPx()) { drawLayer(graphicsLayer) }
        drawContent()
    }
}

// ---------------------------------------------------------------------------------------------
// Drawing

/** One prepared outer shadow: its path (grown and offset) and a paint with the blur. */
private class ShadowLayer(val path: android.graphics.Path, val paint: android.graphics.Paint)

/** A prepared inner stroke: a shape to stroke, or a path to clip and stroke twice as wide. */
private class InnerStroke(val roundRect: RoundRect?, val path: Path?, val width: Float)

private fun Density.shadowLayers(outline: Outline, shadows: List<U2BoxShadow>): List<ShadowLayer> =
    // CSS paints the first listed shadow on top: draw them last to first.
    shadows.asReversed().mapNotNull { shadow ->
        if (shadow.color.alpha <= 0f) return@mapNotNull null
        val path = outline.grown(shadow.spread.toPx()) ?: return@mapNotNull null
        path.translate(Offset(shadow.x.toPx(), shadow.y.toPx()))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = shadow.color.toArgb()
            style = android.graphics.Paint.Style.FILL
            val sigma = shadow.blur.toPx() / 2f
            if (sigma > 0f) maskFilter = BlurMaskFilter(U2Effects.blurRadiusForSigma(sigma), BlurMaskFilter.Blur.NORMAL)
        }
        ShadowLayer(path.asAndroidPath(), paint)
    }

private fun DrawScope.drawShadowLayers(outline: Outline, layers: List<ShadowLayer>) {
    if (layers.isEmpty()) return
    clipPath(outline.toPath(), ClipOp.Difference) {
        drawIntoCanvas { canvas -> layers.forEach { canvas.nativeCanvas.drawPath(it.path, it.paint) } }
    }
}

private fun innerStroke(outline: Outline, width: Float): InnerStroke {
    val half = width / 2f
    return when (outline) {
        is Outline.Rectangle -> InnerStroke(RoundRect(outline.rect.deflate(half)), null, width)
        is Outline.Rounded -> InnerStroke(outline.roundRect.inflated(-half), null, width)
        is Outline.Generic -> InnerStroke(null, outline.path, width)
    }
}

private fun DrawScope.drawInnerStroke(stroke: InnerStroke, color: Color) {
    if (stroke.width <= 0f || color.alpha <= 0f) return
    val rect = stroke.roundRect
    if (rect != null) {
        drawPath(Path().apply { addRoundRect(rect) }, color, style = Stroke(stroke.width))
    } else if (stroke.path != null) {
        clipPath(stroke.path) { drawPath(stroke.path, color, style = Stroke(stroke.width * 2f)) }
    }
}

/** The outline grown by [by] on every side, corner radii growing (or shrinking) with it, as CSS spread does. */
private fun Outline.grown(by: Float): Path? = when (this) {
    is Outline.Rectangle -> {
        val r = rect.inflate(by)
        if (r.width <= 0f || r.height <= 0f) null else Path().apply { addRect(r) }
    }
    is Outline.Rounded -> {
        val r = roundRect.inflated(by)
        if (r.width <= 0f || r.height <= 0f) null else Path().apply { addRoundRect(r) }
    }
    is Outline.Generic -> {
        val bounds = path.getBounds()
        val w = bounds.width + 2 * by
        val h = bounds.height + 2 * by
        if (w <= 0f || h <= 0f || bounds.width <= 0f || bounds.height <= 0f) {
            null
        } else {
            Path().apply {
                addPath(path)
                val m = Matrix()
                m.translate(bounds.center.x, bounds.center.y)
                m.scale(w / bounds.width, h / bounds.height)
                m.translate(-bounds.center.x, -bounds.center.y)
                transform(m)
            }
        }
    }
}

/** A rounded rect grown by [by] on every side; each non-zero corner radius grows by [by] (never below 0). */
private fun RoundRect.inflated(by: Float): RoundRect {
    fun CornerRadius.grow() = if (x <= 0f && y <= 0f) this else CornerRadius(max(0f, x + by), max(0f, y + by))
    return RoundRect(
        left = left - by,
        top = top - by,
        right = right + by,
        bottom = bottom + by,
        topLeftCornerRadius = topLeftCornerRadius.grow(),
        topRightCornerRadius = topRightCornerRadius.grow(),
        bottomRightCornerRadius = bottomRightCornerRadius.grow(),
        bottomLeftCornerRadius = bottomLeftCornerRadius.grow(),
    )
}

private fun Outline.toPath(): Path = when (this) {
    is Outline.Rectangle -> Path().apply { addRect(rect) }
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
    is Outline.Generic -> path
}
