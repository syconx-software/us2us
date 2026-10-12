package com.syconx.us2us.ui.brand

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.syconx.us2us.ui.tokens.U2ColorTokens
import com.syconx.us2us.ui.tokens.U2FixedColors
import com.syconx.us2us.ui.tokens.U2FontFamily
import com.syconx.us2us.ui.tokens.U2ThemedColor
import com.syconx.us2us.ui.type.U2Fonts
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

// The drawing kit of the built-in stickers: inks, the die-cut pen, shapes and the type of the
// words. Every sticker is drawn in a 100 × 100 box (one unit = one hundredth of the sticker's
// side), so the art is resolution-independent and every line keeps the same weight at every size.

// MARK: - Inks

/**
 * The sticker inks: Nocturne's accent ramp at its dark-theme values, with `danger` as the one warm
 * note (hearts, blush, the morning sun). They are the same in both themes: a sticker is a printed
 * thing, like a photo; only what lies around it changes with the theme ([BrandStickerFrame]).
 */
object BrandStickerInk {
    /** The die-cut paper around every sticker (`stickerRing`, #f5f4ff). */
    val paper: Color = U2FixedColors.stickerRing

    /** Outlines, faces and words (accent900, #2b2741). */
    val ink: Color = fixed(U2ColorTokens.accent900)

    /** The light cup, cards (accent200, #e7e5fe). */
    val lilac100: Color = fixed(U2ColorTokens.accent200)

    /** Its shade, rims (accent300, #d2cefd). */
    val lilac200: Color = fixed(U2ColorTokens.accent300)

    /** The mascot cup (accent400, #b5abfc, the line of the app icon). */
    val lilac300: Color = fixed(U2ColorTokens.accent400)

    /** The mascot's shade (accent500, #968ae0). */
    val lilac400: Color = fixed(U2ColorTokens.accent500)

    /** The string (accent600, #796cbf). */
    val string: Color = fixed(U2ColorTokens.accent600)

    /** Deep marks: the line mark on cards (accent700, #5d5294). */
    val lilac600: Color = fixed(U2ColorTokens.accent700)

    /** Night (accent800, #423a6a). */
    val night: Color = fixed(U2ColorTokens.accent800)

    /** Hearts, blush, the sun (danger, #f2716a). */
    val warm: Color = fixed(U2ColorTokens.danger)

    /** A token's dark value as a fixed colour. */
    fun fixed(token: U2ThemedColor): Color = token.resolve(dark = true)

    /** A token's light value as a fixed colour, at [opacity]. */
    fun fixedLight(token: U2ThemedColor, opacity: Float): Color = token.opacity(opacity).resolve(dark = false)
}

/**
 * What the theme changes around a sticker: its drop shadow and, in the light theme, a hairline on
 * the edge of the paper (#f5f4ff on #f3f5fe would otherwise disappear).
 */
@Immutable
data class BrandStickerFrame(
    val shadow: Color,
    /** Shadow blur radius (σ) and offset, in units. */
    val shadowRadius: Float,
    val shadowY: Float,
    val hairline: Color?,
) {
    companion object {
        /** The 5.2 sticker's `0 12px 26px #000@35%`, scaled to the art. */
        val Dark = BrandStickerFrame(shadow = Color.Black.copy(alpha = 0.36f), shadowRadius = 2.6f, shadowY = 2.2f, hairline = null)

        val Light = BrandStickerFrame(
            shadow = BrandStickerInk.fixedLight(U2ColorTokens.text, 0.18f),
            shadowRadius = 2.2f,
            shadowY = 1.6f,
            hairline = BrandStickerInk.fixedLight(U2ColorTokens.text, 0.13f),
        )

        fun of(dark: Boolean): BrandStickerFrame = if (dark) Dark else Light
    }
}

// MARK: - Geometry

/** A point in sticker units (or any unit the caller works in). */
internal fun p(x: Number, y: Number): Offset = Offset(x.toFloat(), y.toFloat())

/** A rectangle from its origin and size. */
internal fun rect(x: Number, y: Number, width: Number, height: Number): Rect =
    Rect(x.toFloat(), y.toFloat(), x.toFloat() + width.toFloat(), y.toFloat() + height.toFloat())

internal fun radians(degrees: Number): Double = degrees.toDouble() * PI / 180.0

/**
 * An affine transform with Core Graphics' semantics (x' = a·x + c·y + tx, y' = b·x + d·y + ty):
 * [translated], [rotated] and [scaled] act on a point *before* the transform, as
 * `CGAffineTransform.translatedBy`, `rotated(by:)` and `scaledBy` do, so the compositions read
 * exactly as they were written.
 */
@Immutable
internal data class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val tx: Double, val ty: Double) {
    /** This transform, then [other]. */
    fun concatenating(other: Affine): Affine = Affine(
        a = a * other.a + b * other.c,
        b = a * other.b + b * other.d,
        c = c * other.a + d * other.c,
        d = c * other.b + d * other.d,
        tx = tx * other.a + ty * other.c + other.tx,
        ty = tx * other.b + ty * other.d + other.ty,
    )

    fun translated(x: Number, y: Number): Affine = translation(x, y).concatenating(this)

    fun rotated(radians: Double): Affine = rotation(radians).concatenating(this)

    fun scaled(sx: Number, sy: Number = sx): Affine = scale(sx, sy).concatenating(this)

    fun apply(point: Offset): Offset = Offset(
        (a * point.x + c * point.y + tx).toFloat(),
        (b * point.x + d * point.y + ty).toFloat(),
    )

    fun inverted(): Affine {
        val det = a * d - b * c
        if (abs(det) < 1e-12) return this
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return Affine(ia, ib, ic, id, -(tx * ia + ty * ic), -(tx * ib + ty * id))
    }

    fun toMatrix(): Matrix = Matrix().apply {
        values[Matrix.ScaleX] = a.toFloat()
        values[Matrix.SkewY] = b.toFloat()
        values[Matrix.SkewX] = c.toFloat()
        values[Matrix.ScaleY] = d.toFloat()
        values[Matrix.TranslateX] = tx.toFloat()
        values[Matrix.TranslateY] = ty.toFloat()
    }

    companion object {
        val Identity = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        fun translation(x: Number, y: Number) = Affine(1.0, 0.0, 0.0, 1.0, x.toDouble(), y.toDouble())

        fun rotation(radians: Double) = Affine(cos(radians), sin(radians), -sin(radians), cos(radians), 0.0, 0.0)

        fun scale(sx: Number, sy: Number = sx) = Affine(sx.toDouble(), 0.0, 0.0, sy.toDouble(), 0.0, 0.0)
    }
}

/** A point through [transform]. */
internal fun Offset.applying(transform: Affine): Offset = transform.apply(this)

/** A copy of the path through [transform]. */
internal fun Path.applying(transform: Affine): Path = Path().also {
    it.addPath(this)
    it.transform(transform.toMatrix())
}

/** This path minus [other] (the moon). */
internal fun Path.subtracting(other: Path): Path = Path().also { it.op(this, other, PathOperation.Difference) }

/** This path and [other] as one outline. */
internal fun Path.union(other: Path): Path = Path().also { it.op(this, other, PathOperation.Union) }

/**
 * A path builder with Core Graphics' arc semantics: [arc] adds a circular arc from [start] to
 * [end] degrees (clockwise on screen when `clockwise = false`, as `addArc` reads in a flipped
 * context), joined to the current point by a line; [arcTangent] is `addArc(tangent1End:…)`.
 */
internal class PathBuilder {
    val path = Path()
    private var current: Offset? = null
    private var subpathStart: Offset? = null

    fun moveTo(point: Offset): PathBuilder = apply {
        path.moveTo(point.x, point.y)
        current = point
        subpathStart = point
    }

    fun lineTo(point: Offset): PathBuilder = apply {
        if (current == null) return moveTo(point)
        path.lineTo(point.x, point.y)
        current = point
    }

    fun quadTo(control: Offset, to: Offset): PathBuilder = apply {
        if (current == null) moveTo(control)
        path.quadraticTo(control.x, control.y, to.x, to.y)
        current = to
    }

    fun curveTo(control1: Offset, control2: Offset, to: Offset): PathBuilder = apply {
        if (current == null) moveTo(control1)
        path.cubicTo(control1.x, control1.y, control2.x, control2.y, to.x, to.y)
        current = to
    }

    /** An arc of [radius] around [center] from [start] to [end] degrees (from 3 o'clock, towards +y). */
    fun arc(center: Offset, radius: Number, start: Number, end: Number, clockwise: Boolean = false): PathBuilder = apply {
        val r = radius.toFloat()
        val from = start.toDouble()
        val to = end.toDouble()
        var sweep = to - from
        if (!clockwise) {
            while (sweep < 0) sweep += 360.0
            if (sweep > 360.0) sweep %= 360.0
        } else {
            while (sweep > 0) sweep -= 360.0
            if (sweep < -360.0) sweep %= 360.0
        }
        val startPoint = Offset(
            (center.x + r * cos(radians(from))).toFloat(),
            (center.y + r * sin(radians(from))).toFloat(),
        )
        if (current == null) moveTo(startPoint) else lineTo(startPoint)
        if (abs(sweep) > 1e-9) {
            path.arcTo(Rect(center.x - r, center.y - r, center.x + r, center.y + r), from.toFloat(), sweep.toFloat(), false)
        }
        current = Offset(
            (center.x + r * cos(radians(from + sweep))).toFloat(),
            (center.y + r * sin(radians(from + sweep))).toFloat(),
        )
    }

    /**
     * `CGPathAddArcToPoint`: a line from the current point towards [tangent1End], then an arc of
     * [radius] tangent to that line and to the line from [tangent1End] to [tangent2End].
     */
    fun arcTangent(tangent1End: Offset, tangent2End: Offset, radius: Number): PathBuilder = apply {
        val from = current ?: return moveTo(tangent1End)
        val r = radius.toDouble()
        val v1x = (from.x - tangent1End.x).toDouble()
        val v1y = (from.y - tangent1End.y).toDouble()
        val v2x = (tangent2End.x - tangent1End.x).toDouble()
        val v2y = (tangent2End.y - tangent1End.y).toDouble()
        val l1 = hypot(v1x, v1y)
        val l2 = hypot(v2x, v2y)
        if (l1 < 1e-9 || l2 < 1e-9 || r <= 0) return lineTo(tangent1End)
        val u1x = v1x / l1
        val u1y = v1y / l1
        val u2x = v2x / l2
        val u2y = v2y / l2
        val cosTheta = (u1x * u2x + u1y * u2y).coerceIn(-1.0, 1.0)
        val theta = acos(cosTheta)
        if (theta < 1e-9 || abs(PI - theta) < 1e-9) return lineTo(tangent1End)
        val half = theta / 2
        val distance = r / tan(half)
        val t1 = Offset((tangent1End.x + u1x * distance).toFloat(), (tangent1End.y + u1y * distance).toFloat())
        val t2 = Offset((tangent1End.x + u2x * distance).toFloat(), (tangent1End.y + u2y * distance).toFloat())
        var bx = u1x + u2x
        var by = u1y + u2y
        val bl = hypot(bx, by)
        bx /= bl
        by /= bl
        val centerDistance = r / sin(half)
        val cx = tangent1End.x + bx * centerDistance
        val cy = tangent1End.y + by * centerDistance
        lineTo(t1)
        val startAngle = atan2(t1.y - cy, t1.x - cx)
        val endAngle = atan2(t2.y - cy, t2.x - cx)
        var sweep = endAngle - startAngle
        while (sweep > PI) sweep -= 2 * PI
        while (sweep < -PI) sweep += 2 * PI
        path.arcTo(
            Rect((cx - r).toFloat(), (cy - r).toFloat(), (cx + r).toFloat(), (cy + r).toFloat()),
            Math.toDegrees(startAngle).toFloat(),
            Math.toDegrees(sweep).toFloat(),
            false,
        )
        current = t2
    }

    fun close(): PathBuilder = apply {
        path.close()
        current = subpathStart
    }
}

/** Path builders in sticker units. */
internal object StickerShape {
    fun circle(center: Offset, radius: Number): Path {
        val r = radius.toFloat()
        return Path().apply { addOval(Rect(center.x - r, center.y - r, center.x + r, center.y + r)) }
    }

    fun ellipse(center: Offset, rx: Number, ry: Number): Path {
        val x = rx.toFloat()
        val y = ry.toFloat()
        return Path().apply { addOval(Rect(center.x - x, center.y - y, center.x + x, center.y + y)) }
    }

    /**
     * A rounded rectangle with continuous corners (the iOS `.continuous` style): each corner eases
     * in over 1.53 × the radius, and a radius too large for the rectangle is limited so the
     * corners meet (a pill keeps its smooth ends).
     */
    fun rounded(rect: Rect, radius: Number): Path {
        val limit = min(rect.width, rect.height) / 2f / CORNER_EXTENT
        val r = min(radius.toFloat(), limit).coerceAtLeast(0f)
        if (r <= 0f) return Path().apply { addRect(rect) }
        val b = PathBuilder()
        b.moveTo(Offset(rect.left + CORNER_EXTENT * r, rect.top))
        // Clockwise on screen: each corner is reached along one edge (`back` points back along it)
        // and left along the next (`out`).
        corner(b, Offset(rect.right, rect.top), back = Offset(-1f, 0f), out = Offset(0f, 1f), r)
        corner(b, Offset(rect.right, rect.bottom), back = Offset(0f, -1f), out = Offset(-1f, 0f), r)
        corner(b, Offset(rect.left, rect.bottom), back = Offset(1f, 0f), out = Offset(0f, -1f), r)
        corner(b, Offset(rect.left, rect.top), back = Offset(0f, 1f), out = Offset(1f, 0f), r)
        b.close()
        return b.path
    }

    /** One continuous corner at [at]: a line to where it starts, then three cubic segments to the next edge. */
    private fun corner(b: PathBuilder, at: Offset, back: Offset, out: Offset, r: Float) {
        fun point(index: Int): Offset {
            val (along, across) = CORNER[index]
            return Offset(at.x + (back.x * along + out.x * across) * r, at.y + (back.y * along + out.y * across) * r)
        }
        b.lineTo(point(0))
        b.curveTo(point(1), point(2), point(3))
        b.curveTo(point(4), point(5), point(6))
        b.curveTo(point(7), point(8), point(9))
    }

    /** How far a continuous corner reaches along each edge, in radii. */
    private const val CORNER_EXTENT = 1.52866483f

    /** The continuous corner's points (edge, curve, curve, …, other edge), in (along, into) radii. */
    private val CORNER: List<Pair<Float, Float>> = listOf(
        1.52866483f to 0f,
        1.08849323f to 0f,
        0.86840689f to 0f,
        0.66993427f to 0.06549600f,
        0.37282392f to 0.17501650f,
        0.17501614f to 0.37282386f,
        0.06549569f to 0.66993493f,
        0f to 0.86840701f,
        0f to 1.08849299f,
        0f to 1.52866483f,
    )

    fun polyline(points: List<Offset>): Path {
        val b = PathBuilder()
        points.forEachIndexed { index, point -> if (index == 0) b.moveTo(point) else b.lineTo(point) }
        return b.path
    }

    fun quad(from: Offset, control: Offset, to: Offset): Path = PathBuilder().moveTo(from).quadTo(control, to).path

    fun curve(from: Offset, control1: Offset, control2: Offset, to: Offset): Path =
        PathBuilder().moveTo(from).curveTo(control1, control2, to).path

    /** A smooth line through [points] (Catmull–Rom as cubic Béziers). */
    fun smooth(points: List<Offset>, tension: Float = 1f): Path {
        val b = PathBuilder()
        val first = points.firstOrNull() ?: return b.path
        b.moveTo(first)
        if (points.size <= 2) {
            if (points.size == 2) b.lineTo(points[1])
            return b.path
        }
        for (index in 0 until points.size - 1) {
            val p0 = points[max(0, index - 1)]
            val p1 = points[index]
            val p2 = points[index + 1]
            val p3 = points[min(points.size - 1, index + 2)]
            val c1 = Offset(p1.x + (p2.x - p0.x) / 6f * tension, p1.y + (p2.y - p0.y) / 6f * tension)
            val c2 = Offset(p2.x - (p3.x - p1.x) / 6f * tension, p2.y - (p3.y - p1.y) / 6f * tension)
            b.curveTo(c1, c2, p2)
        }
        return b.path
    }

    /** A heart [width] wide (its height ≈ 0.92 × width), centred on its box. */
    fun heart(center: Offset, width: Number): Path {
        val wd = width.toFloat()
        val w = wd / 2f
        val h = wd * 0.92f
        val top = center.y - h * 0.5f
        val bottom = Offset(center.x, center.y + h * 0.5f)
        val cusp = Offset(center.x, top + h * 0.24f)
        val b = PathBuilder()
        b.moveTo(bottom)
        b.curveTo(p(center.x - w * 0.24f, bottom.y - h * 0.2f), p(center.x - w, top + h * 0.6f), p(center.x - w, top + h * 0.3f))
        b.curveTo(p(center.x - w, top - h * 0.06f), p(center.x - w * 0.08f, top - h * 0.06f), cusp)
        b.curveTo(p(center.x + w * 0.08f, top - h * 0.06f), p(center.x + w, top - h * 0.06f), p(center.x + w, top + h * 0.3f))
        b.curveTo(p(center.x + w, top + h * 0.6f), p(center.x + w * 0.24f, bottom.y - h * 0.2f), bottom)
        b.close()
        return b.path
    }

    /** A four-point sparkle with concave sides. */
    fun sparkle(center: Offset, radius: Number, waist: Float = 0.18f): Path {
        val r = radius.toFloat()
        val k = r * waist
        val b = PathBuilder()
        b.moveTo(p(center.x, center.y - r))
        b.quadTo(p(center.x + k, center.y - k), p(center.x + r, center.y))
        b.quadTo(p(center.x + k, center.y + k), p(center.x, center.y + r))
        b.quadTo(p(center.x - k, center.y + k), p(center.x - r, center.y))
        b.quadTo(p(center.x - k, center.y - k), p(center.x, center.y - r))
        b.close()
        return b.path
    }

    /** A falling drop pointing up, [size] high, turned by [angle] degrees. */
    fun drop(center: Offset, size: Number, angle: Number = 0): Path {
        val s = size.toFloat()
        val r = s * 0.36f
        val tip = p(0, -s * 0.58f)
        val b = PathBuilder()
        b.moveTo(tip)
        b.curveTo(p(r * 0.3f, -s * 0.3f), p(r, -s * 0.1f), p(r, s * 0.1f))
        b.arc(p(0, s * 0.1f), r, 0, 180, clockwise = false)
        b.curveTo(p(-r, -s * 0.1f), p(-r * 0.3f, -s * 0.3f), tip)
        b.close()
        return b.path.applying(Affine.translation(center.x, center.y).rotated(radians(angle)))
    }

    /** A speech bubble: a rounded rect with a tail from its bottom edge ([tailX] the tail's centre) to [tip]. */
    fun bubble(rect: Rect, radius: Number, tailX: Number, tailWidth: Number, tip: Offset): Path {
        val r = min(radius.toFloat(), min(rect.height / 2f, rect.width / 2f))
        val tw = tailWidth.toFloat()
        val left = tailX.toFloat() - tw / 2f
        val right = tailX.toFloat() + tw / 2f
        val b = PathBuilder()
        b.moveTo(p(rect.left + r, rect.top))
        b.arcTangent(p(rect.right, rect.top), p(rect.right, rect.bottom), r)
        b.arcTangent(p(rect.right, rect.bottom), p(rect.left, rect.bottom), r)
        b.lineTo(p(right, rect.bottom))
        b.quadTo(p(right - tw * 0.2f, (rect.bottom + tip.y) / 2f), tip)
        b.quadTo(p(left + tw * 0.1f, rect.bottom + (tip.y - rect.bottom) * 0.3f), p(left, rect.bottom))
        b.arcTangent(p(rect.left, rect.bottom), p(rect.left, rect.top), r)
        b.arcTangent(p(rect.left, rect.top), p(rect.right, rect.top), r)
        b.close()
        return b.path
    }

    /** A round seal with [bumps] scallops. */
    fun scallop(center: Offset, radius: Number, bumps: Int, depth: Number): Path {
        val rr = radius.toDouble()
        val dd = depth.toDouble()
        val b = PathBuilder()
        val steps = bumps * 12
        for (step in 0..steps) {
            val t = step.toDouble() / steps * PI * 2
            val r = rr - dd * 0.5 + dd * 0.5 * cos(bumps * t)
            val point = p(center.x + r * sin(t), center.y - r * cos(t))
            if (step == 0) b.moveTo(point) else b.lineTo(point)
        }
        b.close()
        return b.path
    }

    /** A small open spiral (dizzy eyes, the tangle); [start] in degrees. */
    fun spiral(center: Offset, radius: Number, turns: Float = 1.6f, start: Number = 0): Path {
        val b = PathBuilder()
        val steps = (turns * 36).toInt()
        for (step in 0..steps) {
            val f = step.toDouble() / steps
            val a = radians(start) + f * turns * 2 * PI
            val r = radius.toDouble() * (0.12 + 0.88 * f)
            val point = p(center.x + r * cos(a), center.y + r * sin(a))
            if (step == 0) b.moveTo(point) else b.lineTo(point)
        }
        return b.path
    }

    /** An arc of a circle (angles in degrees, clockwise from 3 o'clock, as on screen). */
    fun arc(center: Offset, radius: Number, from: Number, to: Number): Path =
        PathBuilder().arc(center, radius, from, to, clockwise = false).path

    /** A padlock body ([rect]) with its shackle above. */
    fun padlock(rect: Rect, shackleHeight: Number): Pair<Path, Path> {
        val body = rounded(rect, rect.width * 0.2f)
        val inset = rect.width * 0.22f
        val left = rect.left + inset
        val right = rect.right - inset
        val radius = (right - left) / 2f
        val sh = shackleHeight.toFloat()
        val shackle = PathBuilder()
            .moveTo(p(left, rect.top + 1))
            .lineTo(p(left, rect.top - sh + radius))
            .arc(p((left + right) / 2f, rect.top - sh + radius), radius, 180, 0, clockwise = false)
            .lineTo(p(right, rect.top + 1))
            .path
        return body to shackle
    }

    /** A keyhole centred at [center], [size] high. */
    fun keyhole(center: Offset, size: Number): Path {
        val s = size.toFloat()
        val path = circle(p(center.x, center.y - s * 0.18f), s * 0.26f)
        val foot = PathBuilder()
            .moveTo(p(center.x - s * 0.1f, center.y - s * 0.1f))
            .lineTo(p(center.x + s * 0.1f, center.y - s * 0.1f))
            .lineTo(p(center.x + s * 0.17f, center.y + s * 0.42f))
            .lineTo(p(center.x - s * 0.17f, center.y + s * 0.42f))
            .close()
            .path
        path.addPath(foot)
        return path
    }

    /** A bow knot centred at [center]: two loops, two tails and the knot. */
    class Bow(val loops: List<Path>, val tails: List<Path>, val knot: Path)

    fun bow(center: Offset, size: Number): Bow {
        val s = size.toFloat()
        fun loop(side: Float): Path = PathBuilder()
            .moveTo(center)
            .curveTo(
                p(center.x + side * s * 0.12f, center.y - s * 0.42f),
                p(center.x + side * s * 0.52f, center.y - s * 0.4f),
                p(center.x + side * s * 0.5f, center.y - s * 0.02f),
            )
            .curveTo(
                p(center.x + side * s * 0.48f, center.y + s * 0.3f),
                p(center.x + side * s * 0.12f, center.y + s * 0.2f),
                center,
            )
            .close()
            .path

        fun tail(side: Float): Path = curve(
            center,
            p(center.x + side * s * 0.1f, center.y + s * 0.2f),
            p(center.x + side * s * 0.28f, center.y + s * 0.3f),
            p(center.x + side * s * 0.36f, center.y + s * 0.5f),
        )
        return Bow(listOf(loop(-1f), loop(1f)), listOf(tail(-1f), tail(1f)), ellipse(center, s * 0.09f, s * 0.11f))
    }
}

// MARK: - Type

/**
 * Doto 900 (ROND 100) for the words on stickers. Doto is monospaced (0.6 em), so the size words
 * are set at is computed, not measured; lines are centred on their cap height.
 */
internal object BrandStickerType {
    /** Every Doto glyph advances 600 / 1000 em. */
    const val ADVANCE = 0.6f

    /** Cap height 700 / 1000 em. */
    const val CAP_HEIGHT = 0.7f

    /** Room kept above the caps (İ, Ö, Ü) and below the baseline (ş, g, y) when fitting. */
    const val ACCENT_ROOM = 0.26f

    /** The size the runs are laid out at (px at density 1) and then scaled from. */
    const val REFERENCE_SIZE = 100f

    /** Characters as a reader counts them (Doto has one advance per character). */
    fun length(text: String): Int = text.codePointCount(0, text.length)

    /** The size (units) at which [lines] fit a box: the longest line fills the width, the block the height, never above [maxSize]. */
    fun fittedSize(
        lines: List<String>,
        width: Float,
        height: Float,
        maxSize: Float,
        tracking: Float = -0.02f,
        leading: Float = 1f,
    ): Float {
        val longest = (lines.maxOfOrNull(::length) ?: 1).toFloat()
        val byWidth = width / max(1f, longest * (ADVANCE + tracking) - tracking)
        val block = CAP_HEIGHT + max(0, lines.size - 1) * leading + ACCENT_ROOM
        val byHeight = height / block
        return min(maxSize, min(byWidth, byHeight))
    }
}

/** Lays out Doto runs for the pen (one measurer per sticker canvas; layouts are cached). */
internal class StickerTypesetter(private val measurer: TextMeasurer) {
    private val family = U2Fonts.family(U2FontFamily.Doto)

    fun layout(text: String, tracking: Float): TextLayoutResult = measurer.measure(
        text = text,
        style = TextStyle(
            fontFamily = family,
            fontWeight = FontWeight.W900,
            fontSize = BrandStickerType.REFERENCE_SIZE.sp,
            letterSpacing = tracking.em,
            textMotion = TextMotion.Animated,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
        softWrap = false,
        maxLines = 1,
        density = Density(1f, 1f),
    )
}

// MARK: - Pen

/**
 * The paper's strokes as Core Graphics draws them: every point within half the width of the path.
 * The path is cut into short straight pieces, each stroked with round caps; the pieces overlap, so
 * a stroke much wider than a small shape (a dot's paper is 7.2 units round a 1-unit disc) stays
 * solid, where one stroke of the whole curve would turn its inner edge inside out and leave a hole.
 */
internal object PaperStrokes {
    /** The pieces' length in units: far below what shows at sticker sizes. */
    private const val STEP = 0.3f

    fun pieces(path: Path): Path {
        val pieces = android.graphics.Path()
        val measure = android.graphics.PathMeasure(path.asAndroidPath(), false)
        val position = FloatArray(2)
        do {
            val length = measure.length
            if (length <= 0f) continue
            val count = kotlin.math.ceil(length / STEP).toInt().coerceAtLeast(1)
            measure.getPosTan(0f, position, null)
            var x = position[0]
            var y = position[1]
            for (index in 1..count) {
                measure.getPosTan(length * index / count, position, null)
                pieces.moveTo(x, y)
                pieces.lineTo(position[0], position[1])
                x = position[0]
                y = position[1]
            }
        } while (measure.nextContour())
        return pieces.asComposePath()
    }
}

/**
 * Draws a sticker in two passes over the same composition: first the paper (every shape grown by
 * the die-cut margin, under the shadow), then the art (fills, ink outlines, faces, words). A
 * composition calls the same methods in both passes; details inside shapes only draw in the art
 * pass. All coordinates are sticker units: the canvas is already scaled to the 100-unit box.
 */
internal class StickerPen(
    val scope: DrawScope,
    val pass: Pass,
    private val type: StickerTypesetter? = null,
) {
    sealed interface Pass {
        /** The die-cut paper: every shape and line grown by [spread]. */
        data class Paper(val spread: Float, val color: Color) : Pass

        data object Art : Pass
    }

    /** Line weights in units: the same across the whole set. */
    object Weight {
        /** Every outline, arm and mouth. */
        const val LINE = 2.4f

        /** The string. */
        const val STRING = 2.2f

        /** Fine inner marks (brows, pupils' rings). */
        const val FINE = 1.5f

        /** The die-cut paper beyond the outline. */
        const val CUT = 3.6f

        /** The light theme's hairline beyond the paper. */
        const val HAIRLINE = 0.6f
    }

    val isArt: Boolean get() = pass is Pass.Art

    private fun style(width: Float, dotted: Float? = null): Stroke = Stroke(
        width = width,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
        pathEffect = dotted?.let { PathEffect.dashPathEffect(floatArrayOf(0.001f, it), 0f) },
    )

    /**
     * The paper around a path: every point within [width] / 2 of it ([PaperStrokes]).
     */
    private fun paper(path: Path, color: Color, width: Float) {
        scope.drawPath(PaperStrokes.pieces(path), color, style = style(width))
    }

    /** A filled shape with an ink outline. */
    fun shape(path: Path, fill: Color, outline: Boolean = true) {
        when (val pass = pass) {
            is Pass.Paper -> {
                scope.drawPath(path, pass.color)
                paper(path, pass.color, (if (outline) Weight.LINE else 0f) + pass.spread * 2f)
            }
            Pass.Art -> {
                scope.drawPath(path, fill)
                if (outline) scope.drawPath(path, BrandStickerInk.ink, style = style(Weight.LINE))
            }
        }
    }

    /** A thick outlined stroke: a ring, a shackle, the key's bow. */
    fun tube(path: Path, fill: Color, width: Number) {
        val w = width.toFloat()
        when (val pass = pass) {
            is Pass.Paper -> paper(path, pass.color, w + Weight.LINE + pass.spread * 2f)
            Pass.Art -> {
                scope.drawPath(path, BrandStickerInk.ink, style = style(w + Weight.LINE))
                scope.drawPath(path, fill, style = style(w - Weight.LINE))
            }
        }
    }

    /** A line on the paper (the string, arms, motion marks). [dotted] draws round dots that far apart. */
    fun line(path: Path, color: Color = BrandStickerInk.ink, width: Number = Weight.LINE, dotted: Number? = null, onPaper: Boolean = true) {
        val w = width.toFloat()
        when (val pass = pass) {
            is Pass.Paper -> if (onPaper) paper(path, pass.color, w + pass.spread * 2f)
            Pass.Art -> scope.drawPath(path, color, style = style(w, dotted?.toFloat()))
        }
    }

    /** A fill inside the art (faces, shading): nothing on the paper. */
    fun fill(path: Path, color: Color) {
        if (isArt) scope.drawPath(path, color)
    }

    /** A stroke inside the art: nothing on the paper. */
    fun stroke(path: Path, color: Color = BrandStickerInk.ink, width: Number = Weight.LINE, dotted: Number? = null) {
        if (isArt) scope.drawPath(path, color, style = style(width.toFloat(), dotted?.toFloat()))
    }

    /** The pen clipped to [path] for [block] (shading inside a cup). */
    inline fun clipped(path: Path, block: (StickerPen) -> Unit) {
        scope.clipPath(path) { block(this@StickerPen) }
    }

    /** The pen with its drawing rotated by [degrees] around [center] for [block]. */
    inline fun rotated(degrees: Number, center: Offset, block: (StickerPen) -> Unit) {
        scope.withTransform({ rotate(degrees.toFloat(), center) }) { block(this@StickerPen) }
    }

    /**
     * Words in Doto, centred in [box] and set as large as fits (at most [maxSize] units), each line
     * centred on its cap height; the block turned by [rotation] degrees.
     */
    fun words(
        lines: List<String>,
        box: Rect,
        color: Color = BrandStickerInk.ink,
        maxSize: Number,
        tracking: Float = -0.02f,
        leading: Float = 1f,
        rotation: Number = 0,
    ) {
        if (!isArt || lines.isEmpty()) return
        val size = BrandStickerType.fittedSize(lines, box.width, box.height, maxSize.toFloat(), tracking, leading)
        val height = (BrandStickerType.CAP_HEIGHT + (lines.size - 1) * leading) * size
        scope.withTransform({
            translate(box.center.x, box.center.y)
            rotate(rotation.toFloat(), Offset.Zero)
        }) {
            lines.forEachIndexed { index, line ->
                val baseline = -height / 2f + (BrandStickerType.CAP_HEIGHT + index * leading) * size
                glyphs(line, Offset(0f, baseline), size, color, tracking)
            }
        }
    }

    /** One run of Doto, horizontally centred on [baseline], [size] units high, turned by [rotation] degrees. */
    fun glyphs(text: String, baseline: Offset, size: Number, color: Color, tracking: Float = -0.02f, rotation: Number = 0) {
        val typesetter = type ?: return
        if (!isArt || text.isEmpty()) return
        val layout = typesetter.layout(text, tracking)
        val scale = size.toFloat() / BrandStickerType.REFERENCE_SIZE
        // The run's own width: the advances, with the tracking between the glyphs only.
        val width = layout.getLineRight(0) - layout.getLineLeft(0)
        scope.withTransform({
            translate(baseline.x, baseline.y)
            rotate(rotation.toFloat(), Offset.Zero)
            scale(scale, scale, Offset.Zero)
        }) {
            drawText(layout, color = color, topLeft = Offset(-width / 2f - layout.getLineLeft(0), -layout.firstBaseline))
        }
    }
}
