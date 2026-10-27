package com.syconx.us2us.ui.theme

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntRect
import kotlin.math.ceil
import kotlin.math.floor
import androidx.compose.ui.unit.Dp
import com.syconx.us2us.ui.tokens.U2Blur
import com.syconx.us2us.ui.tokens.U2ColorScheme

/**
 * Backdrop blurs of the design (tokens.md › Blur & translucency):
 *
 * | style | CSS |
 * |---|---|
 * | header | blur(20px) saturate(140 %) over bg·80 % |
 * | menu | blur(20px) over surface·94 % |
 * | banner | blur(16px) over surface·92 % |
 * | warning | blur(20px) over surface·92 % |
 * | mediaButton | blur(8px) over #0c0d16@45 % |
 *
 * Android blurs the real content behind the surface (a recording of the [U2BackdropSource]
 * drawn through a RenderEffect blur, API 31+), so no material lift has to be compensated as on
 * iOS: the design's tint is drawn at its token opacity, and over a plain `bg` the header is
 * exactly `bg`. Below API 31 only the tint is drawn.
 */
enum class U2BackdropStyle(
    /** The CSS blur (a standard deviation, in dp). */
    val blur: Dp,
    /** `saturate(…)` applied to what shows through (1 = none). */
    val saturation: Float,
) {
    Header(U2Blur.header, 1.4f),
    Menu(U2Blur.menu, 1f),
    Banner(U2Blur.banner, 1f),
    Warning(U2Blur.warning, 1f),
    MediaButton(U2Blur.mediaButton, 1f),
    ;

    /** The tint drawn over the blur. */
    fun tint(colors: U2ColorScheme): Color = when (this) {
        Header -> colors.headerFill
        Menu -> colors.menuFill
        Banner, Warning -> colors.floatingFill
        MediaButton -> colors.onMediaGlass(0.45f)
    }
}

/**
 * The content translucent surfaces blur: mark it with [u2BackdropSource] and pass the same
 * source to the surfaces' [u2Backdrop]. A surface must not be inside its own source (it would
 * draw itself): place them as siblings, the source first.
 */
@Stable
class U2BackdropSource internal constructor() {
    internal var layer: GraphicsLayer? = null
    internal var positionInRoot: Offset = Offset.Zero
}

/** A backdrop source for this composition. */
@Composable
fun rememberU2BackdropSource(): U2BackdropSource = remember { U2BackdropSource() }

/** Records this content for the [source]'s backdrops, and draws it as usual. */
fun Modifier.u2BackdropSource(source: U2BackdropSource): Modifier = this then BackdropSourceElement(source)

/**
 * Fills the background with a design backdrop (blur + token tint), clipped to [shape]. Without a
 * [source] (or below API 31) only the tint is drawn.
 */
fun Modifier.u2Backdrop(style: U2BackdropStyle, shape: Shape = RectangleShape, source: U2BackdropSource? = null): Modifier =
    this then BackdropElement(style, shape, source)

private data class BackdropSourceElement(val source: U2BackdropSource) : ModifierNodeElement<BackdropSourceNode>() {
    override fun create() = BackdropSourceNode(source)

    override fun update(node: BackdropSourceNode) {
        if (node.source !== source) {
            node.source.layer = null
            node.source = source
            node.invalidateDraw()
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "u2BackdropSource"
    }
}

private class BackdropSourceNode(var source: U2BackdropSource) :
    Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var layer: GraphicsLayer? = null

    override fun onAttach() {
        layer = requireGraphicsContext().createGraphicsLayer()
    }

    override fun onDetach() {
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        layer = null
        source.layer = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        source.positionInRoot = coordinates.positionInRoot()
    }

    override fun ContentDrawScope.draw() {
        val recording = layer ?: return drawContent()
        recording.record { this@draw.drawContent() }
        source.layer = recording
        drawLayer(recording)
    }
}

private data class BackdropElement(
    val style: U2BackdropStyle,
    val shape: Shape,
    val source: U2BackdropSource?,
) : ModifierNodeElement<BackdropNode>() {
    override fun create() = BackdropNode(style, shape, source)

    override fun update(node: BackdropNode) {
        node.style = style
        node.shape = shape
        node.source = source
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "u2Backdrop"
        properties["style"] = style
    }
}

/** Samples only the surface and its blur halo, bounded by the source for the same edge clamping. */
internal fun backdropSampleBounds(source: IntSize, surface: Size, offset: Offset, halo: Float): IntRect? {
    val left = floor(offset.x - halo).toInt().coerceIn(0, source.width)
    val top = floor(offset.y - halo).toInt().coerceIn(0, source.height)
    val right = ceil(offset.x + surface.width + halo).toInt().coerceIn(0, source.width)
    val bottom = ceil(offset.y + surface.height + halo).toInt().coerceIn(0, source.height)
    return if (right > left && bottom > top) IntRect(left, top, right, bottom) else null
}

private class BackdropNode(
    var style: U2BackdropStyle,
    var shape: Shape,
    var source: U2BackdropSource?,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {
    private var blurLayer: GraphicsLayer? = null
    private var positionInRoot: Offset = Offset.Zero

    override fun onAttach() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) blurLayer = requireGraphicsContext().createGraphicsLayer()
    }

    override fun onDetach() {
        blurLayer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        blurLayer = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val position = coordinates.positionInRoot()
        if (position != positionInRoot) {
            positionInRoot = position
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        val colors = currentValueOf(LocalU2Colors)
        val outline = shape.createOutline(size, layoutDirection, this)
        val clip = Path().apply {
            when (outline) {
                is Outline.Rectangle -> addRect(outline.rect)
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Generic -> addPath(outline.path)
            }
        }
        clipPath(clip) {
            val content = source?.layer
            val blurred = blurLayer
            if (content != null && blurred != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val sourcePosition = source?.positionInRoot ?: Offset.Zero
                val radius = U2Effects.blurRadiusForSigma(style.blur.toPx())
                val sample = backdropSampleBounds(content.size, size, positionInRoot - sourcePosition, 3 * radius)
                if (sample != null) {
                    // The old full-screen layer blurred every pixel even for a small header.
                    // A conservative three-radius halo keeps the visible Gaussian kernel intact.
                    blurred.clip = true
                    blurred.record(size = IntSize(sample.width, sample.height)) {
                        translate(-sample.left.toFloat(), -sample.top.toFloat()) { drawLayer(content) }
                    }
                    blurred.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                    blurred.colorFilter = if (style.saturation != 1f) {
                        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(style.saturation) })
                    } else {
                        null
                    }
                    translate(sourcePosition.x + sample.left - positionInRoot.x, sourcePosition.y + sample.top - positionInRoot.y) { drawLayer(blurred) }
                }
            }
            drawRect(style.tint(colors))
        }
        drawContent()
    }
}
