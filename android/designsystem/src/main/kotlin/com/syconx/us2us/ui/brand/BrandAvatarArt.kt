package com.syconx.us2us.ui.brand

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import com.syconx.us2us.ui.tokens.U2ColorTokens
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// How a preset avatar is drawn: the stickers' cup mascot, at a sticker's own scale, seen through a
// round window. The art lives in the stickers' 100 × 100 unit box, so the cup, its face and every
// line weight are exactly the stickers'; the disc is a circle in that box (`BrandAvatarFrame`)
// that the view maps onto its frame.

/** The avatar's window on the sticker box and where the cup stands in it. */
internal object BrandAvatarFrame {
    /** The disc's centre and radius in sticker units. */
    val center = Offset(50f, 50f)
    const val RADIUS = 42f

    /** The cup: a sticker's scale (the cups of the set stand at 1.38–1.48), its base a little above the bottom of the disc. */
    const val CUP_SCALE = 1.4f
    val cupBase = Offset(50f, 86.5f)
}

/**
 * How much is drawn: small avatars (chat header, request cards) keep the cup, its face and what it
 * wears; from 40 dp on the little extras around it (sparkles, steam, whiskers) are drawn too.
 */
enum class BrandAvatarDetail {
    Compact,
    Full,
    ;

    companion object {
        const val FULL_FROM_DIAMETER = 40f

        fun of(diameter: Float): BrandAvatarDetail = if (diameter >= FULL_FROM_DIAMETER) Full else Compact
    }
}

/** Colours of the avatar that are not sticker inks. */
internal object BrandAvatarInk {
    /** The light theme's hairline round a pale disc (`text·10%`, as on cards). */
    val lightHairline: Color = U2ColorTokens.strokeCard.resolve(dark = false)
}

/**
 * Draws a preset avatar: the disc, then the cup on it, clipped to the circle, centred in the canvas
 * and as large as fits. [diameterDp] decides the detail (the canvas is in pixels).
 */
internal fun DrawScope.renderBrandAvatar(preset: BrandAvatarPreset, dark: Boolean, diameterDp: Float) {
    val diameter = min(size.width, size.height)
    if (diameter <= 0f) return
    val origin = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
    val disc = Path().apply { addOval(Rect(origin, Size(diameter, diameter))) }
    clipPath(disc) {
        drawPath(disc, preset.backdrop.color(dark))
        val unit = diameter / (BrandAvatarFrame.RADIUS * 2f)
        withTransform({
            translate(origin.x + diameter / 2f, origin.y + diameter / 2f)
            scale(unit, unit, Offset.Zero)
            translate(-BrandAvatarFrame.center.x, -BrandAvatarFrame.center.y)
        }) {
            BrandAvatarArt.draw(preset, StickerPen(this, StickerPen.Pass.Art), BrandAvatarDetail.of(diameterDp), dark)
        }
    }
    if (!dark && preset.backdrop.needsLightHairline) {
        val inset = density * 0.25f
        drawOval(
            BrandAvatarInk.lightHairline,
            topLeft = Offset(origin.x + inset, origin.y + inset),
            size = Size(diameter - inset * 2f, diameter - inset * 2f),
            style = Stroke(width = density * 0.5f),
        )
    }
}

/**
 * One composition for every preset: what floats behind, the accessory's back, the cup, the
 * accessory's front. Points are in the cup's own units (base centre = origin, up = −y; the rim's
 * top is at −39, the face's centre at −16) unless they say otherwise.
 */
internal object BrandAvatarArt {
    /** [dark] only colours what lies on the disc itself (sparkles, marks, steam); the cup and what it wears are printed in the sticker inks. */
    fun draw(preset: BrandAvatarPreset, pen: StickerPen, detail: BrandAvatarDetail, dark: Boolean) {
        val cup = Cup(BrandAvatarFrame.cupBase.x, BrandAvatarFrame.cupBase.y, scale = BrandAvatarFrame.CUP_SCALE, style = preset.cup.style)
        cup.face = preset.expression.face
        if (preset.accessory == BrandAvatarAccessory.Coffee) cup.faceOffset = -4f
        val composer = Composer(pen, cup, detail, dark)
        composer.ornaments(preset)
        composer.behind(preset.accessory)
        if (preset.accessory == BrandAvatarAccessory.GhostSheet) composer.ghost() else cup.draw(pen)
        composer.front(preset.accessory)
    }

    private class Composer(val pen: StickerPen, val cup: Cup, val detail: BrandAvatarDetail, val dark: Boolean) {
        private val ink = BrandStickerInk
        private val isFull: Boolean get() = detail == BrandAvatarDetail.Full

        /** Marks drawn straight on the disc: light on the deep discs, deeper on the pale ones. */
        private val onDisc: Color get() = if (dark) ink.lilac200 else ink.string

        /** A point of the cup, in sticker units. */
        fun c(x: Number, y: Number): Offset = cup.point(x, y)

        /** A path in the cup's units, in sticker units. */
        fun on(path: Path): Path = path.applying(cup.transform)

        // MARK: Around the cup

        /** The extras on the disc behind the cup (full detail only). */
        fun ornaments(preset: BrandAvatarPreset) {
            if (!isFull) return
            val accessory = preset.accessory
            val expression = preset.expression
            when {
                accessory == BrandAvatarAccessory.None && expression == BrandAvatarExpression.Smile -> {
                    pen.dotSparkle(p(80, 30), 3.4, onDisc)
                    pen.dotSparkle(p(21, 44), 2.6, onDisc)
                }
                accessory == BrandAvatarAccessory.None && expression == BrandAvatarExpression.Cool -> {
                    pen.sparkle(p(80, 27), 5.6)
                    pen.sparkle(p(87, 41), 3)
                }
                accessory == BrandAvatarAccessory.None && expression == BrandAvatarExpression.Wink -> {
                    pen.sparkle(p(80, 28), 5.2)
                    pen.dotSparkle(p(70, 17), 2.6, onDisc)
                }
                accessory == BrandAvatarAccessory.None && expression == BrandAvatarExpression.Curious ->
                    marks(c(0, -39), 6, 11, listOf(-140, -90, -40))
                accessory == BrandAvatarAccessory.None && expression == BrandAvatarExpression.Laugh ->
                    marks(c(0, -30), 31, 35.5, listOf(-125, -145, -35, -55))
                accessory == BrandAvatarAccessory.None && preset.cup == BrandAvatarCup.Twin -> {
                    pen.heart(p(81, 31), 7.5)
                    pen.dotSparkle(p(20, 40), 2.8, onDisc)
                }
                accessory == BrandAvatarAccessory.Nightcap -> {
                    pen.sparkle(p(22, 30), 3.6, ink.lilac200)
                    pen.sparkle(p(80, 18), 2.6, ink.lilac200)
                    pen.dotSparkle(p(17, 52), 2.2, onDisc)
                }
                accessory == BrandAvatarAccessory.PartyHat -> {
                    val confetti = listOf(
                        Triple(p(20, 36), ink.warm, 30), Triple(p(30, 21), onDisc, -20), Triple(p(81, 34), onDisc, 50),
                        Triple(p(84, 50), ink.warm, -40), Triple(p(16, 54), ink.lilac400, 10),
                    )
                    for ((center, color, angle) in confetti) {
                        val piece = StickerShape.rounded(rect(-2.6, -1.2, 5.2, 2.4), 1)
                            .applying(Affine.translation(center.x, center.y).rotated(radians(angle)))
                        pen.shape(piece, color, outline = false)
                    }
                }
                accessory == BrandAvatarAccessory.Crown -> {
                    pen.sparkle(p(23, 30), 3.6, ink.lilac100)
                    pen.sparkle(p(79, 27), 2.8, ink.lilac100)
                }
                accessory == BrandAvatarAccessory.GhostSheet -> {
                    pen.line(StickerShape.polyline(listOf(p(14, 52), p(18, 52))), onDisc, width = 2)
                    pen.line(StickerShape.polyline(listOf(p(13, 61), p(17.5, 61))), onDisc, width = 2)
                }
                accessory == BrandAvatarAccessory.Antennae -> {
                    pen.dotSparkle(p(80, 33), 2.6, onDisc)
                    pen.dotSparkle(p(19, 38), 2.2, onDisc)
                }
                else -> Unit
            }
        }

        /** The stickers' action marks, in the disc's colour. */
        private fun marks(center: Offset, inner: Number, outer: Number, angles: List<Number>) {
            for (angle in angles) {
                val a = radians(angle)
                val dx = cos(a)
                val dy = sin(a)
                val path = StickerShape.polyline(
                    listOf(
                        p(center.x + dx * inner.toDouble(), center.y + dy * inner.toDouble()),
                        p(center.x + dx * outer.toDouble(), center.y + dy * outer.toDouble()),
                    ),
                )
                pen.line(path, onDisc, width = 2.2)
            }
        }

        // MARK: Accessories

        /** What goes behind the cup. */
        fun behind(accessory: BrandAvatarAccessory) {
            when (accessory) {
                BrandAvatarAccessory.Headphones -> {
                    val band = StickerShape.curve(p(-21, -21), p(-22.5, -51), p(22.5, -51), p(21, -21))
                    pen.tube(on(band), ink.night, 3.6)
                }
                BrandAvatarAccessory.CatEars -> for (side in listOf(-1f, 1f)) {
                    val ear = PathBuilder()
                        .moveTo(p(side * 19.5f, -36.5))
                        .quadTo(p(side * 18.5f, -44), p(side * 13.6f, -48.5))
                        .quadTo(p(side * 9.5f, -45), p(side * 6.5f, -38.5))
                        .close()
                        .path
                    pen.shape(on(ear), cup.style.body)
                    val inner = PathBuilder()
                        .moveTo(p(side * 16.8f, -38))
                        .quadTo(p(side * 16.4f, -42.6), p(side * 13.9f, -45.2))
                        .quadTo(p(side * 11.6f, -42.6), p(side * 9.8f, -38.6))
                        .close()
                        .path
                    pen.fill(on(inner), ink.warm.copy(alpha = 0.7f))
                }
                BrandAvatarAccessory.Antennae -> for (side in listOf(-1f, 1f)) {
                    val stalk = StickerShape.quad(p(side * 5.5f, -38), p(side * 5.4f, -44), p(side * 8.6f, -47.6))
                    pen.line(on(stalk), ink.ink, width = StickerPen.Weight.LINE)
                    pen.shape(StickerShape.circle(c(side * 8.9f, -48), 2.2f * cup.scale), ink.warm)
                }
                BrandAvatarAccessory.StringHeart -> stringHeart()
                else -> Unit
            }
        }

        /** What goes over the cup. */
        fun front(accessory: BrandAvatarAccessory) {
            when (accessory) {
                BrandAvatarAccessory.None, BrandAvatarAccessory.GhostSheet, BrandAvatarAccessory.StringHeart, BrandAvatarAccessory.Antennae -> Unit
                BrandAvatarAccessory.CatEars -> if (isFull) whiskers()
                BrandAvatarAccessory.Headphones -> for (side in listOf(-1f, 1f)) {
                    val shell = StickerShape.rounded(rect(side * 20.6f - 4.4f, -27.2, 8.8, 14.4), 4)
                    pen.shape(on(shell), ink.night)
                    val cushion = StickerShape.rounded(rect(side * 20.6f - (if (side < 0) -1.6f else 4.0f), -24.6, 2.4, 9.2), 1.2)
                    pen.fill(on(cushion), ink.warm)
                }
                BrandAvatarAccessory.Beanie -> {
                    val dome = PathBuilder()
                        .moveTo(p(-20, -37))
                        .curveTo(p(-20, -45.4), p(-11, -48.6), p(0, -48.6))
                        .curveTo(p(11, -48.6), p(20, -45.4), p(20, -37))
                        .close()
                        .path
                    pen.shape(on(dome), ink.warm)
                    val cuff = on(StickerShape.rounded(rect(-22.4, -41.2, 44.8, 9.2), 3.4))
                    pen.shape(cuff, ink.warm)
                    if (pen.isArt) {
                        pen.clipped(cuff) { ribs ->
                            var x = -18f
                            while (x <= 18f) {
                                ribs.stroke(on(StickerShape.polyline(listOf(p(x, -39.4), p(x, -33.8)))), ink.ink.copy(alpha = 0.32f), width = 1.3)
                                x += 4.5f
                            }
                        }
                        pen.stroke(cuff)
                    }
                    pen.shape(StickerShape.circle(c(0, -49.4), 3.3f * cup.scale), ink.paper)
                }
                BrandAvatarAccessory.Glasses -> glasses()
                BrandAvatarAccessory.Flower -> flower(p(13.5, -39.5), 8.6f)
                BrandAvatarAccessory.Cap -> {
                    // A cap worn sideways: a round crown and the brim out to the right.
                    val crown = PathBuilder()
                        .moveTo(p(-20.6, -36.4))
                        .curveTo(p(-21.6, -47.6), p(-12.6, -52.4), p(-2, -52.4))
                        .curveTo(p(9.6, -52.4), p(19.4, -46.6), p(19.4, -37.4))
                        .close()
                        .path
                    val brim = PathBuilder()
                        .moveTo(p(12.6, -41.6))
                        .curveTo(p(19.2, -43.8), p(26.6, -42.8), p(27.6, -38.8))
                        .curveTo(p(28.4, -36), p(19.4, -34.6), p(12.4, -35.2))
                        .close()
                        .path
                    pen.shape(on(brim), ink.lilac600)
                    val crownOn = on(crown)
                    pen.shape(crownOn, ink.night)
                    if (pen.isArt) {
                        pen.clipped(crownOn) { panels ->
                            for ((from, control, to) in listOf(
                                Triple(p(-2, -52), p(-9.6, -46), p(-10.6, -37)),
                                Triple(p(-2, -52), p(4.4, -46), p(6.6, -37)),
                            )) {
                                panels.stroke(on(StickerShape.quad(from, control, to)), ink.lilac400.copy(alpha = 0.85f), width = 1.3)
                            }
                        }
                        pen.stroke(crownOn)
                    }
                    val band = StickerShape.rounded(rect(-21.4, -40.4, 41.8, 5.2), 2.6)
                    pen.shape(on(band), ink.lilac600)
                    pen.shape(StickerShape.circle(c(-2, -52.6), 1.8f * cup.scale), ink.lilac300)
                }
                BrandAvatarAccessory.Scarf -> scarf()
                BrandAvatarAccessory.PartyHat -> {
                    val hat = on(
                        PathBuilder()
                            .moveTo(p(-10.6, -38.4))
                            .lineTo(p(4.8, -53.4))
                            .lineTo(p(10.2, -38.4))
                            .close()
                            .path,
                    )
                    pen.shape(hat, ink.lilac100)
                    if (pen.isArt) {
                        pen.clipped(hat) { stripes ->
                            for (y in listOf(-42.6f, -47.8f)) {
                                stripes.fill(on(StickerShape.polyline(listOf(p(-14, y + 1.4f), p(14, y - 1.6f), p(14, y + 1.4f), p(-14, y + 4.4f)))), ink.warm)
                            }
                        }
                        pen.stroke(hat)
                    }
                    pen.shape(StickerShape.circle(c(5, -53.8), 2.6f * cup.scale), ink.warm)
                }
                BrandAvatarAccessory.Nightcap -> {
                    // The stickers' 305 nightcap, its tip flopped over the right of the rim.
                    val cap = PathBuilder()
                        .moveTo(p(-19.4, -40))
                        .curveTo(p(-18.6, -49), p(-9.4, -53), p(0, -53))
                        .curveTo(p(13.6, -53), p(23.8, -44.2), p(24.4, -30.6))
                        .curveTo(p(20.6, -35), p(19.4, -38.2), p(17.2, -39.6))
                        .close()
                        .path
                    pen.shape(on(cap), ink.night)
                    val band = StickerShape.rounded(rect(-20.8, -42.6, 41.6, 5.6), 2.8)
                    pen.shape(on(band), ink.lilac100)
                    pen.shape(StickerShape.circle(c(24, -29.4), 3.2f * cup.scale), ink.paper)
                }
                BrandAvatarAccessory.Crown -> {
                    val crown = PathBuilder()
                        .moveTo(p(-11, -37.4))
                        .lineTo(p(-12.2, -48))
                        .lineTo(p(-5.6, -42.6))
                        .lineTo(p(0, -50))
                        .lineTo(p(5.6, -42.6))
                        .lineTo(p(12.2, -48))
                        .lineTo(p(11, -37.4))
                        .close()
                        .path
                    pen.shape(on(crown), ink.lilac100)
                    pen.fill(on(StickerShape.circle(p(0, -40.4), 1.9)), ink.warm)
                    for (x in listOf(-12.2f, 0f, 12.2f)) {
                        val y = if (x == 0f) -50f else -48f
                        pen.shape(StickerShape.circle(c(x, y), 1.5f * cup.scale), ink.warm)
                    }
                }
                BrandAvatarAccessory.Bow -> {
                    val bow = StickerShape.bow(p(-11.5, -40.2), 15.5)
                    for (tail in bow.tails) pen.line(on(tail), ink.warm, width = 2.6)
                    for (loop in bow.loops) pen.shape(on(loop), ink.warm)
                    pen.shape(on(bow.knot), ink.warm)
                }
                BrandAvatarAccessory.Coffee -> coffee()
                BrandAvatarAccessory.ChefHat -> chefHat()
            }
        }

        // MARK: Pieces

        /** Two rings and a bar over the eyes: the app icon's line mark. */
        private fun glasses() {
            val face = cup.faceCenter
            val eyeY = face.y + StickerFace.EYE_Y
            val x = StickerFace.EYE_X
            val r = 5.2f
            val sides = listOf(-1f, 1f)
            if (pen.isArt) {
                for (side in sides) pen.fill(on(StickerShape.circle(p(side * x, eyeY), r)), ink.paper.copy(alpha = 0.28f))
            }
            for (side in sides) {
                pen.stroke(on(StickerShape.circle(p(side * x, eyeY), r)), ink.ink, width = 2.2)
                pen.stroke(on(StickerShape.polyline(listOf(p(side * (x + r), eyeY - 0.6f), p(side * 17.6f, eyeY - 2.2f)))), ink.ink, width = 1.8)
            }
            pen.stroke(on(StickerShape.quad(p(-x + r, eyeY - 0.4f), p(0, eyeY - 2), p(x - r, eyeY - 0.4f))), ink.ink, width = 2)
        }

        private fun flower(center: Offset, radius: Float) {
            var petals: Path? = null
            for (index in 0 until 5) {
                val a = radians(index * 72 - 90)
                val petalCenter = p(center.x + radius * 0.52f * cos(a), center.y + radius * 0.52f * sin(a))
                val petal = StickerShape.ellipse(Offset.Zero, radius * 0.5f, radius * 0.36f)
                    .applying(Affine.translation(petalCenter.x, petalCenter.y).rotated(a))
                petals = petals?.union(petal) ?: petal
            }
            petals?.let { pen.shape(on(it), ink.paper) }
            pen.shape(on(StickerShape.circle(center, radius * 0.3f)), ink.warm)
        }

        /** The string from under the cup, up its right side into a heart. */
        private fun stringHeart() {
            // Sticker units: the loop sits between the cup's right side and the disc's edge.
            val tip = p(81, 76)
            val string = PathBuilder()
                .moveTo(cup.stringStart)
                .curveTo(p(62, 89), p(76, 85), tip)
                .curveTo(p(77, 70.4), p(73.4, 66), p(73.4, 59.6))
                .curveTo(p(73.4, 54.6), p(79.4, 53.4), p(81, 56.4))
                .curveTo(p(82.6, 53.4), p(88.6, 54.6), p(88.6, 59.6))
                .curveTo(p(88.6, 66), p(85, 70.4), tip)
                .path
            pen.string(string)
            pen.heart(p(81, 63.4), 7.6)
        }

        private fun whiskers() {
            for (side in listOf(-1f, 1f)) {
                for ((dy, slope) in listOf(-1.2f to -1.4f, 1.4f to 1.2f)) {
                    val from = p(side * 14.6f, -12.6f + dy)
                    val to = p(side * 21.4f, -12.6f + dy + slope)
                    pen.line(on(StickerShape.polyline(listOf(from, to))), ink.ink, width = 1.4, onPaper = false)
                }
            }
        }

        private fun scarf() {
            val band = on(
                PathBuilder()
                    .moveTo(p(-15.6, -8.4))
                    .quadTo(p(0, -6.2), p(15.6, -8.4))
                    .lineTo(p(14.8, -2.2))
                    .quadTo(p(0, 0.2), p(-14.8, -2.2))
                    .close()
                    .path,
            )
            val tail = on(
                PathBuilder()
                    .moveTo(p(5.2, -5.4))
                    .lineTo(p(11.6, -5.8))
                    .lineTo(p(12.8, 3.2))
                    .lineTo(p(6.6, 3.6))
                    .close()
                    .path,
            )
            pen.shape(band, ink.warm)
            pen.shape(tail, ink.warm)
            if (pen.isArt) {
                pen.clipped(band) { stripes ->
                    for (x in listOf(-9f, -3f, 3f, 9f)) {
                        stripes.stroke(on(StickerShape.polyline(listOf(p(x - 1, -10), p(x + 1.4f, 1)))), ink.paper.copy(alpha = 0.8f), width = 1.6)
                    }
                }
                pen.clipped(tail) { tailStripes ->
                    tailStripes.stroke(on(StickerShape.polyline(listOf(p(4, -1), p(14, -1.6)))), ink.paper.copy(alpha = 0.8f), width = 1.6)
                }
                pen.stroke(band)
                pen.stroke(tail)
            }
            if (isFull) {
                for (x in listOf(7.8f, 9.8f, 11.8f)) {
                    pen.line(on(StickerShape.polyline(listOf(p(x, 3.6), p(x + 0.2f, 5.6)))), ink.ink, width = 1.3, onPaper = false)
                }
            }
        }

        private fun coffee() {
            val sleeve = PathBuilder()
                .moveTo(c(-16.1, -12.5))
                .lineTo(c(16.1, -12.5))
                .lineTo(c(14.6, -3.5))
                .lineTo(c(-14.6, -3.5))
                .close()
                .path
            pen.shape(sleeve, ink.lilac600)
            pen.heart(c(0, -8), 5.6, ink.lilac100)
            if (!isFull) return
            val rim = cup.rimTop
            for ((dx, phase) in listOf(-7f to 1f, 7f to -1f)) {
                val base = p(rim.x + dx, rim.y - 3.5f)
                val steam = StickerShape.smooth(
                    listOf(base, p(base.x + phase * 2.4f, base.y - 4.4f), p(base.x - phase * 1.4f, base.y - 9), p(base.x + phase * 1.6f, base.y - 13.4f)),
                )
                pen.line(steam, onDisc, width = 2.6, dotted = 4.2)
            }
        }

        private fun chefHat() {
            val band = StickerShape.rounded(rect(-15.6, -44.2, 31.2, 7.4), 2.4)
            val puff = StickerShape.circle(p(-9.6, -46.2), 5.4)
                .union(StickerShape.circle(p(0, -48.2), 5.6))
                .union(StickerShape.circle(p(9.6, -46.2), 5.4))
                .union(StickerShape.rounded(rect(-13.6, -47, 27.2, 6), 2))
            pen.shape(on(puff), ink.paper)
            pen.shape(on(band), ink.paper)
            if (pen.isArt) {
                for (x in listOf(-6f, 0f, 6f)) {
                    pen.stroke(on(StickerShape.polyline(listOf(p(x, -43.6), p(x, -38.2)))), ink.lilac300, width = 1.3)
                }
            }
        }

        /** The cup under a sheet: a cup-shaped ghost with a wavy hem, its rim showing on top (the stickers' 202). */
        fun ghost() {
            val body = PathBuilder()
                .moveTo(p(-19.6, -36))
                .curveTo(p(-20.6, -24), p(-22.6, -12), p(-21.6, -1.6))
            val hem = listOf(-21.6f, -10.8f, 0f, 10.8f, 21.6f)
            for (index in 0 until hem.size - 1) {
                val x0 = hem[index]
                val x1 = hem[index + 1]
                body.quadTo(p((x0 + x1) / 2f, if (index % 2 == 0) 4.2 else -6), p(x1, -1.6))
            }
            body.curveTo(p(22.6, -12), p(20.6, -24), p(19.6, -36))
            body.close()
            pen.shape(on(body.path), ink.lilac100)
            pen.shape(cup.lipPath, ink.paper)
            cup.face.draw(pen, cup.transform.translated(0, -18))
        }
    }
}
