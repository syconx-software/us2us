package com.syconx.us2us.ui.brand

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlin.math.hypot
import kotlin.math.max

// The cup mascot and the face every character of the set shares.

// MARK: - Face

/**
 * A face in its own units (centre = 0, 0; eyes 13.6 apart): the cups, the padlock, the ghost and
 * the sun all wear the same features, so the set reads as one family.
 */
@Immutable
internal data class StickerFace(
    val eyes: Eyes = Eyes.Dots(),
    val mouth: Mouth = Mouth.Smile,
    val brows: Brows = Brows.None,
    val blush: Boolean = true,
) {
    sealed interface Eyes {
        /** Round dots with a highlight; [look] moves them. */
        data class Dots(val look: Offset = Offset.Zero) : Eyes

        /** Closed, smiling (^ ^). */
        data object Happy : Eyes

        /** Closed, asleep (u u). */
        data object Sleepy : Eyes

        /** Wide open rings with pupils. */
        data class Wide(val look: Offset = Offset.Zero) : Eyes

        data object Hearts : Eyes

        /** Left open, right closed. */
        data object Wink : Eyes

        data object Dizzy : Eyes

        /** Ghost holes. */
        data object Hollow : Eyes

        /** Squeezed shut (> <). */
        data object Squeezed : Eyes

        data object Sunglasses : Eyes

        data object None : Eyes
    }

    enum class Mouth { Smile, Grin, Laugh, O, SmallO, Frown, Sob, Flat, Wavy, Tongue, Smirk, None }

    enum class Brows { None, Raised, Sad, Angry, Quizzical }

    /** Draws the face (art pass only) through [transform] (face units → sticker units). */
    fun draw(pen: StickerPen, transform: Affine) {
        if (!pen.isArt) return
        val ink = BrandStickerInk.ink
        val fine = StickerPen.Weight.FINE
        val line = StickerPen.Weight.LINE * 0.92f
        fun on(path: Path): Path = path.applying(transform)
        fun closedArc(c: Offset, up: Boolean): Path = if (up) {
            StickerShape.quad(p(c.x - 2.7, c.y + 1), p(c.x, c.y - 3.1), p(c.x + 2.7, c.y + 1))
        } else {
            StickerShape.quad(p(c.x - 2.7, c.y - 0.4), p(c.x, c.y + 2.6), p(c.x + 2.7, c.y - 0.4))
        }
        fun dot(c: Offset) {
            pen.fill(on(StickerShape.ellipse(c, 2.2, 2.8)), ink)
            pen.fill(on(StickerShape.circle(p(c.x + 0.75, c.y - 1.05), 0.75)), BrandStickerInk.paper)
        }
        val sides = listOf(-1f, 1f)

        if (blush) {
            for (side in sides) {
                pen.fill(on(StickerShape.ellipse(p(side * 11.2f, 2.4), 2.9, 1.7)), BrandStickerInk.warm.copy(alpha = 0.5f))
            }
        }

        when (val eyes = eyes) {
            is Eyes.Dots -> for (side in sides) dot(p(side * EYE_X + eyes.look.x, EYE_Y + eyes.look.y))
            Eyes.Happy -> for (side in sides) pen.stroke(on(closedArc(p(side * EYE_X, EYE_Y + 0.6f), up = true)), ink, width = line)
            Eyes.Sleepy -> for (side in sides) pen.stroke(on(closedArc(p(side * EYE_X, EYE_Y), up = false)), ink, width = line)
            is Eyes.Wide -> for (side in sides) {
                val c = p(side * EYE_X, EYE_Y - 0.4f)
                pen.fill(on(StickerShape.circle(c, 3.6)), BrandStickerInk.paper)
                pen.stroke(on(StickerShape.circle(c, 3.6)), ink, width = fine)
                val pupil = p(c.x + eyes.look.x, c.y + eyes.look.y)
                pen.fill(on(StickerShape.circle(pupil, 1.85)), ink)
                pen.fill(on(StickerShape.circle(p(pupil.x + 0.6f, pupil.y - 0.7f), 0.6)), BrandStickerInk.paper)
            }
            Eyes.Hearts -> for (side in sides) {
                val heart = on(StickerShape.heart(p(side * EYE_X, EYE_Y - 0.2f), 7))
                pen.fill(heart, BrandStickerInk.warm)
                pen.stroke(heart, ink, width = fine)
            }
            Eyes.Wink -> {
                dot(p(-EYE_X, EYE_Y))
                pen.stroke(on(closedArc(p(EYE_X, EYE_Y + 0.6f), up = true)), ink, width = line)
            }
            Eyes.Dizzy -> for (side in sides) {
                val spiral = StickerShape.spiral(p(side * EYE_X, EYE_Y), 3.3, turns = 1.75f, start = side * 90)
                pen.stroke(on(spiral), ink, width = fine)
            }
            Eyes.Hollow -> for (side in sides) pen.fill(on(StickerShape.ellipse(p(side * EYE_X, EYE_Y), 2.7, 3.7)), ink)
            Eyes.Squeezed -> for (side in sides) {
                val c = p(side * EYE_X, EYE_Y)
                val chevron = StickerShape.polyline(
                    listOf(p(c.x + side * 2, c.y - 2.4f), p(c.x - side * 1.8f, c.y), p(c.x + side * 2, c.y + 2.4f)),
                )
                pen.stroke(on(chevron), ink, width = line)
            }
            Eyes.Sunglasses -> {
                val lenses = Path()
                for (side in sides) lenses.addPath(StickerShape.rounded(rect(side * EYE_X - 4.9f, EYE_Y - 3.2f, 9.8, 6.6), 2.8))
                pen.fill(on(lenses), ink)
                pen.stroke(on(StickerShape.polyline(listOf(p(-2.4, EYE_Y - 1.4f), p(2.4, EYE_Y - 1.4f)))), ink, width = fine)
                for (side in sides) {
                    val g = p(side * EYE_X - 1.8f, EYE_Y - 0.4f)
                    pen.stroke(
                        on(StickerShape.polyline(listOf(p(g.x, g.y + 1.2f), p(g.x + 2.2f, g.y - 1.1f)))),
                        BrandStickerInk.paper.copy(alpha = 0.85f),
                        width = 1.1,
                    )
                }
            }
            Eyes.None -> Unit
        }

        when (brows) {
            Brows.None -> Unit
            Brows.Raised -> for (side in sides) {
                val c = p(side * EYE_X, EYE_Y - 6.2f)
                pen.stroke(on(StickerShape.quad(p(c.x - 2.4f, c.y + 0.7f), p(c.x, c.y - 1.6f), p(c.x + 2.4f, c.y + 0.7f))), ink, width = fine)
            }
            Brows.Sad -> for (side in sides) {
                pen.stroke(on(StickerShape.polyline(listOf(p(side * 9.4f, EYE_Y - 4.2f), p(side * 4.6f, EYE_Y - 6.2f)))), ink, width = fine)
            }
            Brows.Angry -> for (side in sides) {
                pen.stroke(on(StickerShape.polyline(listOf(p(side * 9.4f, EYE_Y - 6.4f), p(side * 4.4f, EYE_Y - 4.4f)))), ink, width = fine)
            }
            Brows.Quizzical -> {
                val left = p(-EYE_X, EYE_Y - 6.6f)
                pen.stroke(
                    on(StickerShape.quad(p(left.x - 2.4f, left.y + 0.8f), p(left.x, left.y - 1.8f), p(left.x + 2.4f, left.y + 0.8f))),
                    ink,
                    width = fine,
                )
                pen.stroke(on(StickerShape.polyline(listOf(p(EYE_X - 2.4f, EYE_Y - 4.8f), p(EYE_X + 2.4f, EYE_Y - 5.1f)))), ink, width = fine)
            }
        }

        val m = p(0, 4.2)
        val smile = StickerShape.quad(p(m.x - 3.4f, m.y - 0.8f), p(m.x, m.y + 2.8f), p(m.x + 3.4f, m.y - 0.8f))
        when (mouth) {
            Mouth.Smile -> pen.stroke(on(smile), ink, width = line)
            Mouth.Grin, Mouth.Laugh -> {
                val w = if (mouth == Mouth.Laugh) 5.4f else 4.0f
                val d = if (mouth == Mouth.Laugh) 9.6f else 6.6f
                val open = PathBuilder()
                    .moveTo(p(m.x - w, m.y - 1.2f))
                    .lineTo(p(m.x + w, m.y - 1.2f))
                    .quadTo(p(m.x, m.y - 1.2f + d), p(m.x - w, m.y - 1.2f))
                    .close()
                    .path
                pen.fill(on(open), ink)
                val tongue = StickerShape.ellipse(p(m.x, m.y - 1.2f + d * 0.5f), w * 0.56f, d * 0.2f)
                pen.clipped(on(open)) { it.fill(on(tongue), BrandStickerInk.warm) }
                pen.stroke(on(open), ink, width = fine)
            }
            Mouth.O -> pen.fill(on(StickerShape.ellipse(p(m.x, m.y + 0.6f), 1.9, 2.4)), ink)
            Mouth.SmallO -> pen.fill(on(StickerShape.ellipse(p(m.x, m.y + 0.2f), 1.25, 1.45)), ink)
            Mouth.Frown -> pen.stroke(on(StickerShape.quad(p(m.x - 3.0f, m.y + 1.4f), p(m.x, m.y - 1.6f), p(m.x + 3.0f, m.y + 1.4f))), ink, width = line)
            Mouth.Sob -> {
                val open = PathBuilder()
                    .moveTo(p(m.x - 4.2f, m.y + 2.6f))
                    .quadTo(p(m.x, m.y - 4.4f), p(m.x + 4.2f, m.y + 2.6f))
                    .quadTo(p(m.x, m.y + 0.6f), p(m.x - 4.2f, m.y + 2.6f))
                    .close()
                    .path
                pen.fill(on(open), ink)
                pen.stroke(on(open), ink, width = fine)
            }
            Mouth.Flat -> pen.stroke(on(StickerShape.polyline(listOf(p(m.x - 2.8f, m.y + 0.4f), p(m.x + 2.8f, m.y + 0.4f)))), ink, width = line)
            Mouth.Wavy -> {
                val wave = StickerShape.smooth(
                    listOf(p(m.x - 4, m.y + 0.6f), p(m.x - 2, m.y - 0.6f), p(m.x, m.y + 0.6f), p(m.x + 2, m.y - 0.6f), p(m.x + 4, m.y + 0.6f)),
                )
                pen.stroke(on(wave), ink, width = fine + 0.2f)
            }
            Mouth.Tongue -> {
                val tongue = on(StickerShape.ellipse(p(m.x + 1.3f, m.y + 2.4f), 1.9, 2.2))
                pen.fill(tongue, BrandStickerInk.warm)
                pen.stroke(tongue, ink, width = fine * 0.85f)
                pen.stroke(on(smile), ink, width = line)
            }
            Mouth.Smirk -> pen.stroke(on(StickerShape.quad(p(m.x - 3.0f, m.y + 0.6f), p(m.x + 1.2f, m.y + 2.6f), p(m.x + 3.6f, m.y - 1.0f))), ink, width = line)
            Mouth.None -> Unit
        }
    }

    companion object {
        const val EYE_Y = -2.6f
        const val EYE_X = 6.8f
    }
}

// MARK: - Cup mascot

/** The colours of a cup: the mascot ([Mid]) and its lighter twin ([Light]). */
@Immutable
internal data class CupStyle(val body: Color, val shade: Color, val lip: Color) {
    companion object {
        /** The mascot: the app icon's line colour. */
        val Mid = CupStyle(body = BrandStickerInk.lilac300, shade = BrandStickerInk.lilac400, lip = BrandStickerInk.lilac200)

        /** The other end of the line. */
        val Light = CupStyle(body = BrandStickerInk.lilac100, shade = BrandStickerInk.lilac200, lip = BrandStickerInk.paper)
    }
}

/**
 * The cup mascot: a paper cup of the string telephone, standing on its base with the string
 * leaving from the base's centre. The same proportions in every sticker (a 33-unit body narrowing
 * from 36 to 26, a 41 × 7.5 rim), scaled as a whole; line weights never scale.
 */
internal class Cup(
    x: Number,
    y: Number,
    scale: Number = 1,
    tilt: Number = 0,
    var style: CupStyle = CupStyle.Mid,
    eyes: StickerFace.Eyes = StickerFace.Eyes.Dots(),
    mouth: StickerFace.Mouth = StickerFace.Mouth.Smile,
    brows: StickerFace.Brows = StickerFace.Brows.None,
    blush: Boolean = true,
    var arms: List<Arm> = emptyList(),
) {
    enum class Side(val sign: Float) { Left(-1f), Right(1f) }

    /** A rubber-hose arm from the shoulder to a round hand. */
    class Arm(
        val side: Side,
        /** The hand, in the cup's own units (base centre = origin, up = −y). */
        val hand: Offset,
        bend: Number = 0,
        /** Drawn over the body (hands on the face, hugging). */
        val inFront: Boolean = false,
        handRadius: Number = 2.9,
        /** Finger lines on the hand (an open hand seen from the front). */
        val fingers: Boolean = false,
    ) {
        /** Sideways bow of the arm (units; positive bows away from the body). */
        val bend: Float = bend.toFloat()

        /** The hand's radius (bigger to cover the eyes). */
        val handRadius: Float = handRadius.toFloat()

        constructor(
            side: Side,
            x: Number,
            y: Number,
            bend: Number = 0,
            inFront: Boolean = false,
            handRadius: Number = 2.9,
            fingers: Boolean = false,
        ) : this(side, p(x, y), bend, inFront, handRadius, fingers)
    }

    /** The base centre in sticker units. */
    val origin: Offset = p(x, y)

    val scale: Float = scale.toFloat()

    /** Leaning around the base centre, in degrees. */
    val tilt: Float = tilt.toFloat()
    var face = StickerFace(eyes, mouth, brows, blush)

    /** Running legs: the feet, in the cup's units (below the base, +y). */
    var feet: List<Offset> = emptyList()

    /** Moves the face up (−) or down (+) on the body. */
    var faceOffset: Float = 0f

    /** Draws the face upright even when the cup lies on its side. */
    var uprightFace: Boolean = false

    val transform: Affine
        get() = Affine.translation(origin.x, origin.y).rotated(radians(tilt)).scaled(scale, scale)

    /** A point of the cup in sticker units. */
    fun point(x: Number, y: Number): Offset = p(x, y).applying(transform)

    /** A sticker point in the cup's own units (to reach for something). */
    fun local(point: Offset): Offset = point.applying(transform.inverted())

    /** The top centre of the rim, in sticker units. */
    val rimTop: Offset get() = point(0, -TOTAL_HEIGHT)

    /** Where the string leaves the cup: a little inside the base, so the string (drawn first) comes out from under it. */
    val stringStart: Offset get() = point(0, -3)

    /** The face centre in the cup's units. */
    val faceCenter: Offset get() = p(0, -16 + faceOffset)

    val bodyPath: Path
        get() {
            val h = HEIGHT
            val top = TOP_WIDTH / 2f
            val base = BASE_WIDTH / 2f
            return PathBuilder()
                .moveTo(p(-top, -h))
                .lineTo(p(top, -h))
                .arcTangent(p(base, 0), p(0, 0), CORNER)
                .arcTangent(p(-base, 0), p(-top, -h), CORNER)
                .close()
                .path
                .applying(transform)
        }

    val lipPath: Path
        get() = StickerShape.rounded(rect(-LIP_WIDTH / 2f, -TOTAL_HEIGHT, LIP_WIDTH, LIP_HEIGHT), LIP_HEIGHT / 2f).applying(transform)

    /** Everything: arms behind, legs, body, rim, face, arms in front. */
    fun draw(pen: StickerPen) {
        drawBehind(pen)
        drawBody(pen)
        drawFront(pen)
    }

    fun drawBehind(pen: StickerPen) {
        for (arm in arms) if (!arm.inFront) drawArm(arm, pen)
        for (foot in feet) drawLeg(foot, pen)
    }

    fun drawBody(pen: StickerPen) {
        val body = bodyPath
        pen.shape(body, style.body)
        if (pen.isArt) {
            pen.clipped(body) { inside ->
                val h = HEIGHT
                val shade = StickerShape.polyline(
                    listOf(
                        p(TOP_WIDTH / 2f - 9.5f, -h - 1), p(TOP_WIDTH / 2f + 1, -h - 1),
                        p(BASE_WIDTH / 2f + 1, 1), p(BASE_WIDTH / 2f - 6.5f, 1),
                    ),
                )
                inside.fill(shade.applying(transform), style.shade)
                // A paper glint down the left side.
                val glint = StickerShape.polyline(listOf(p(-12.9, -26.5), p(-11.6, -17.5)))
                inside.stroke(glint.applying(transform), BrandStickerInk.paper.copy(alpha = 0.75f), width = 2.1f * scale)
                inside.fill(StickerShape.circle(point(-11.0, -13.2), 1.05f * scale), BrandStickerInk.paper.copy(alpha = 0.75f))
            }
            // The outline again, over the shading.
            pen.stroke(body)
        }
        pen.shape(lipPath, style.lip)
        val center = faceCenter
        val faceTransform = if (uprightFace) {
            val c = point(center.x, center.y)
            Affine.translation(c.x, c.y).scaled(scale, scale)
        } else {
            transform.translated(center.x, center.y)
        }
        face.draw(pen, faceTransform)
    }

    fun drawFront(pen: StickerPen) {
        for (arm in arms) if (arm.inFront) drawArm(arm, pen)
    }

    /** The hand's centre in sticker units. */
    fun hand(arm: Arm): Offset = point(arm.hand.x, arm.hand.y)

    private fun drawArm(arm: Arm, pen: StickerPen) {
        val shoulder = p(arm.side.sign * 14.4f, -15)
        val mid = p((shoulder.x + arm.hand.x) / 2f, (shoulder.y + arm.hand.y) / 2f)
        val dx = arm.hand.x - shoulder.x
        val dy = arm.hand.y - shoulder.y
        val length = max(0.001f, hypot(dx, dy))
        var nx = -dy / length
        var ny = dx / length
        if (nx * arm.side.sign < 0) {
            nx = -nx
            ny = -ny
        }
        val control = p(mid.x + nx * arm.bend, mid.y + ny * arm.bend)
        pen.line(StickerShape.quad(shoulder, control, arm.hand).applying(transform))
        pen.shape(StickerShape.circle(arm.hand, arm.handRadius).applying(transform), style.body)
        if (arm.fingers) {
            val r = arm.handRadius
            for (fx in listOf(-r * 0.32f, r * 0.32f)) {
                val finger = StickerShape.polyline(listOf(p(arm.hand.x + fx, arm.hand.y - r * 0.95f), p(arm.hand.x + fx, arm.hand.y - r * 0.25f)))
                pen.stroke(finger.applying(transform), BrandStickerInk.ink, width = StickerPen.Weight.FINE)
            }
        }
    }

    private fun drawLeg(foot: Offset, pen: StickerPen) {
        val hip = p(if (foot.x < 0) -4.5f else 4.5f, -2)
        val knee = p((hip.x + foot.x) / 2f + (if (foot.x < 0) -1.5f else 1.5f), (hip.y + foot.y) / 2f)
        pen.line(StickerShape.quad(hip, knee, foot).applying(transform))
        val shoe = StickerShape.ellipse(p(foot.x + (if (foot.x < 0) -1.4f else 1.4f), foot.y + 0.4f), 3.4, 2.1)
        pen.shape(shoe.applying(transform), BrandStickerInk.ink)
    }

    companion object {
        // Proportions (the cup's own units).
        const val HEIGHT = 33f
        const val TOP_WIDTH = 36f
        const val BASE_WIDTH = 26f
        const val LIP_WIDTH = 41f
        const val LIP_HEIGHT = 7.5f
        const val CORNER = 4.5f

        /** From the base to the top of the rim. */
        const val TOTAL_HEIGHT = HEIGHT + 6f
    }
}
