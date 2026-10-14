package com.syconx.us2us.ui.brand

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.sticker.BrandStickerSelection
import com.syconx.us2us.domain.sticker.CatalogSticker
import com.syconx.us2us.domain.sticker.StickerCatalog
import com.syconx.us2us.domain.sticker.StickerLanguage
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.theme.U2BoxShadows
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2BoxShadow
import com.syconx.us2us.ui.theme.u2OuterRing
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import androidx.compose.ui.res.stringResource
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A sticker chosen in the picker: the catalogue id (`Sticker.sticker_id`) and the language its words were shown in. */
data class StickerSelection(val stickerId: Int, val lang: String)

/**
 * A built-in sticker drawn from its id, in [lang] (`tr` or `en`; anything else draws the app's
 * language). [words] is set only for the legacy `us2us.text` card, whose words travel in the
 * message; catalogue stickers pass `null`.
 *
 * It fills the square it is given (96–160 dp in the chat, 72 in the picker) and is crisp at any
 * size. [animatesEntrance]: the sticker settles in once when it appears (a sticker just sent or
 * received; not history scrolling back into view).
 */
@Composable
fun BrandSticker(
    id: Int,
    words: String?,
    lang: String,
    modifier: Modifier = Modifier,
    animatesEntrance: Boolean = false,
) {
    val sticker = StickerCatalog.sticker(id)
    if (sticker == null) {
        if (words != null) LegacyTextSticker(words, modifier) else Box(modifier)
        return
    }
    val ui = StickerLanguage.forLocale(currentAppLocale())
    BrandStickerView(BrandStickerSelection(sticker, StickerLanguage.fromWireCode(lang) ?: ui), modifier, animatesEntrance)
}

/**
 * A built-in sticker (proposed: the design draws one text sticker, 5.2 “tamam!”): the cup mascot,
 * the string and Doto words, drawn in code on die-cut paper.
 *
 * A text sticker is drawn in the selection's language (the language it was shown in on the
 * sender's side, `Sticker.lang`), never the reader's, so both ends see the same words. TalkBack
 * reads it in the app's language ([CatalogSticker.accessibilityLabel]).
 *
 * [animatesEntrance]: fade + scale 0.86 → 1 + a −5° lean straightening, 0.5 s ease-out, no
 * overshoot. Under reduce motion it is simply there.
 */
@Composable
fun BrandStickerView(selection: BrandStickerSelection, modifier: Modifier = Modifier, animatesEntrance: Boolean = false) {
    val ui = StickerLanguage.forLocale(currentAppLocale())
    val label = selection.sticker.accessibilityLabel(selection.language, ui)
    val reduceMotion = LocalReduceMotion.current
    var hasEntered by remember { mutableStateOf(false) }
    val settled = !animatesEntrance || reduceMotion || hasEntered
    val progress by animateFloatAsState(
        targetValue = if (settled) 1f else 0f,
        animationSpec = U2Motion.out(U2Motion.Duration.screen).spec(),
        label = "stickerEntrance",
    )
    LaunchedEffect(Unit) {
        if (animatesEntrance && !reduceMotion && !hasEntered) hasEntered = true
    }
    BrandStickerCanvas(
        selection.sticker,
        selection.language,
        modifier
            .graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 1f)
                val scale = 0.86f + 0.14f * progress
                scaleX = scale
                scaleY = scale
                rotationZ = -5f * (1f - progress)
                alpha = progress
            }
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Image
            },
    )
}

/**
 * Draws a built-in sticker: the die-cut paper under its shadow (and, in the light theme, a
 * hairline on its edge), then the art. Vector all the way down. No accessibility of its own:
 * [BrandStickerView] adds the label.
 */
@Composable
internal fun BrandStickerCanvas(sticker: CatalogSticker, language: StickerLanguage, modifier: Modifier = Modifier) {
    val artwork = remember(sticker.id) { BrandStickerArtwork.artwork(sticker.id) }
    val lines = sticker.lines?.get(language).orEmpty()
    val frame = BrandStickerFrame.of(U2Theme.isDark)
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val typesetter = remember(measurer) { StickerTypesetter(measurer) }
    Spacer(
        modifier
            .aspectRatio(1f)
            .clipToBounds()
            .clearAndSetSemantics {}
            .drawWithCache {
                val shadow = artwork?.let { StickerShadows.shadow(sticker.id, lines, frame, it, size, typesetter) }
                onDrawBehind {
                    if (artwork != null) renderSticker(artwork, lines, frame, typesetter, shadow)
                }
            },
    )
}

/** The 100-unit box centred in the canvas, leaning by the artwork's tilt. */
private fun DrawScope.stickerBox(artwork: BrandStickerArtwork, block: DrawScope.() -> Unit) {
    val unit = min(size.width, size.height) / 100f
    if (unit <= 0f) return
    withTransform({
        translate((size.width - unit * 100f) / 2f, (size.height - unit * 100f) / 2f)
        scale(unit, unit, Offset.Zero)
        rotate(artwork.tilt, Offset(50f, 50f))
    }, block)
}

/** The paper: the whole hairline first, then the whole paper over it, so no hairline shows where two shapes' papers overlap. */
private fun DrawScope.drawPaper(artwork: BrandStickerArtwork, lines: List<String>, frame: BrandStickerFrame) {
    frame.hairline?.let { hairline ->
        artwork.compose(StickerPen(this, StickerPen.Pass.Paper(StickerPen.Weight.CUT + StickerPen.Weight.HAIRLINE, hairline)), lines)
    }
    artwork.compose(StickerPen(this, StickerPen.Pass.Paper(StickerPen.Weight.CUT, BrandStickerInk.paper)), lines)
}

private fun DrawScope.renderSticker(
    artwork: BrandStickerArtwork,
    lines: List<String>,
    frame: BrandStickerFrame,
    typesetter: StickerTypesetter,
    shadow: ImageBitmap?,
) {
    val unit = min(size.width, size.height) / 100f
    if (unit <= 0f) return
    // The shadow is set in points, after the box: straight down whatever the tilt.
    if (shadow != null) drawImage(shadow, topLeft = Offset(0f, frame.shadowY * unit))
    stickerBox(artwork) {
        drawPaper(artwork, lines, frame)
        artwork.compose(StickerPen(this, StickerPen.Pass.Art, typesetter), lines)
    }
}

/** A built-in sticker as a bitmap, [sidePx] square, outside composition (tests, system surfaces). */
internal object BrandStickerBitmap {
    fun render(selection: BrandStickerSelection, dark: Boolean, sidePx: Int, measurer: TextMeasurer): ImageBitmap {
        val image = ImageBitmap(sidePx, sidePx)
        val artwork = BrandStickerArtwork.artwork(selection.sticker.id) ?: return image
        val lines = selection.sticker.lines?.get(selection.language).orEmpty()
        val frame = BrandStickerFrame.of(dark)
        val typesetter = StickerTypesetter(measurer)
        val size = Size(sidePx.toFloat(), sidePx.toFloat())
        val shadow = StickerShadows.shadow(selection.sticker.id, lines, frame, artwork, size, typesetter)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), size) {
            clipRect { renderSticker(artwork, lines, frame, typesetter, shadow) }
        }
        return image
    }
}

/**
 * The paper's drop shadow: the paper's silhouette (hairline included) blurred as a Gaussian of
 * σ = `shadowRadius` units, in the shadow's colour. Made once per sticker, language, size and
 * theme and kept while the app runs (a handful of small bitmaps).
 */
internal object StickerShadows {
    private data class Key(val id: Int, val lines: List<String>, val width: Int, val height: Int, val frame: BrandStickerFrame)

    private val cache = object : LinkedHashMap<Key, ImageBitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ImageBitmap>?): Boolean = size > 96
    }

    fun shadow(id: Int, lines: List<String>, frame: BrandStickerFrame, artwork: BrandStickerArtwork, size: Size, typesetter: StickerTypesetter): ImageBitmap? {
        val width = size.width.roundToInt()
        val height = size.height.roundToInt()
        if (width <= 0 || height <= 0) return null
        val key = Key(id, lines, width, height, frame)
        synchronized(cache) { cache[key]?.let { return it } }
        val image = render(artwork, lines, frame, width, height)
        synchronized(cache) { cache[key] = image }
        return image
    }

    private fun render(artwork: BrandStickerArtwork, lines: List<String>, frame: BrandStickerFrame, width: Int, height: Int): ImageBitmap {
        val silhouette = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(silhouette), Size(width.toFloat(), height.toFloat())) {
            stickerBox(artwork) { drawPaper(artwork, lines, frame) }
        }
        val pixels = IntArray(width * height)
        silhouette.asAndroidBitmap().getPixels(pixels, 0, width, 0, 0, width, height)
        val alpha = FloatArray(pixels.size) { ((pixels[it] ushr 24) and 0xFF) / 255f }
        val unit = min(width, height) / 100f
        gaussian(alpha, width, height, frame.shadowRadius * unit)
        val argb = frame.shadow.toArgb()
        val baseAlpha = (argb ushr 24) and 0xFF
        val rgb = argb and 0x00FFFFFF
        for (index in pixels.indices) {
            val a = (alpha[index] * baseAlpha).roundToInt().coerceIn(0, 255)
            pixels[index] = (a shl 24) or rgb
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /**
     * A Gaussian blur of σ [sigma] px as three box blurs (the CSS filter-effects approximation):
     * box size d = ⌊σ · 3 √(2π) / 4 + 0.5⌋, centred for an odd d, offset left, right and then a
     * d + 1 box for an even one. Outside the canvas is transparent.
     */
    internal fun gaussian(values: FloatArray, width: Int, height: Int, sigma: Float) {
        if (sigma < 0.5f) return
        val d = floor(sigma * 3.0 * sqrt(2.0 * Math.PI) / 4.0 + 0.5).toInt()
        if (d < 1) return
        val boxes: List<Pair<Int, Int>> = if (d % 2 == 1) {
            List(3) { d / 2 to d / 2 }
        } else {
            listOf(d / 2 to d / 2 - 1, d / 2 - 1 to d / 2, d / 2 to d / 2)
        }
        val line = FloatArray(max(width, height))
        val out = FloatArray(max(width, height))
        for ((before, after) in boxes) {
            for (y in 0 until height) {
                for (x in 0 until width) line[x] = values[y * width + x]
                box(line, out, width, before, after)
                for (x in 0 until width) values[y * width + x] = out[x]
            }
            for (x in 0 until width) {
                for (y in 0 until height) line[y] = values[y * width + x]
                box(line, out, height, before, after)
                for (y in 0 until height) values[y * width + x] = out[y]
            }
        }
    }

    /** One box blur over [count] values: each output averages [before] values before it, itself and [after] after it. */
    private fun box(input: FloatArray, output: FloatArray, count: Int, before: Int, after: Int) {
        val window = (before + after + 1).toFloat()
        var sum = 0f
        for (i in 0..min(after, count - 1)) sum += input[i]
        for (i in 0 until count) {
            output[i] = sum / window
            val enter = i + after + 1
            if (enter < count) sum += input[enter]
            val leave = i - before
            if (leave >= 0) sum -= input[leave]
        }
    }
}

/**
 * A received sticker this build cannot draw (a newer pack or id): its emoji, large, the way a
 * jumbo emoji is shown, at a fixed size (it does not follow the font size, like the drawn ones).
 */
@Composable
fun StickerEmojiFallback(emoji: String, modifier: Modifier = Modifier, size: Dp = 96.dp) {
    val label = stringResource(R.string.common_a11y_sticker, emoji)
    val fontSize = with(LocalDensity.current) { (size * 0.62f).toSp() }
    Box(
        modifier
            .size(size)
            .clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(emoji, style = TextStyle(fontSize = fontSize, textAlign = TextAlign.Center), maxLines = 1, softWrap = false)
    }
}

/** The text sticker of builds before the catalogue (`us2us.text`): its words on the 5.2 Doto card. */
@Composable
internal fun LegacyTextSticker(text: String, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val shape = RoundedCornerShape(U2Radius.sticker)
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .graphicsLayer { rotationZ = -6f }
                .u2BoxShadow(shape, U2BoxShadows.sticker)
                .u2OuterRing(colors.stickerRing, 4.dp, shape)
                .background(colors.accent200, shape)
                .padding(start = 18.dp, top = 12.dp, end = 18.dp, bottom = 11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            U2Text(text, U2TextStyles.displaySticker, color = colors.accent900, maxLines = 1, softWrap = false)
        }
    }
}
