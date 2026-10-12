package com.syconx.us2us.ui.brand

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import com.syconx.us2us.ui.brand.Cup.Arm
import com.syconx.us2us.ui.brand.Cup.Side
import com.syconx.us2us.ui.brand.StickerFace.Brows
import com.syconx.us2us.ui.brand.StickerFace.Eyes
import com.syconx.us2us.ui.brand.StickerFace.Mouth
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The art of one built-in sticker: a composition drawn twice (paper, then art) in the 100 × 100
 * sticker box.
 */
internal class BrandStickerArtwork(
    /** How far the whole sticker leans, in degrees (a sticker is never quite straight). */
    val tilt: Float,
    /** Draws the sticker; `lines` are its words in the language shown (empty for wordless art). */
    val compose: (StickerPen, List<String>) -> Unit,
) {
    companion object {
        /** The art for a catalogue id; `null` for an id this build cannot draw. */
        fun artwork(id: Int): BrandStickerArtwork? = when (id) {
            1 -> BrandStickerArtwork(-3f, StickerArt::hello)
            2 -> BrandStickerArtwork(0f, StickerArt::together)
            3 -> BrandStickerArtwork(-6f, StickerArt::laugh)
            4 -> BrandStickerArtwork(0f, StickerArt::cry)
            5 -> BrandStickerArtwork(2f, StickerArt::wow)
            6 -> BrandStickerArtwork(3f, StickerArt::love)
            7 -> BrandStickerArtwork(5f, StickerArt::wink)
            8 -> BrandStickerArtwork(-2f, StickerArt::think)
            9 -> BrandStickerArtwork(0f, StickerArt::hug)
            10 -> BrandStickerArtwork(4f, StickerArt::tangled)
            11 -> BrandStickerArtwork(0f, StickerArt::listen)
            101 -> BrandStickerArtwork(-4f, StickerArt::wordsHello)
            102 -> BrandStickerArtwork(3f, StickerArt::wordsLineOpen)
            103 -> BrandStickerArtwork(-3f, StickerArt::wordsHearYou)
            104 -> BrandStickerArtwork(2f, StickerArt::wordsBetweenUs)
            105 -> BrandStickerArtwork(-3f, StickerArt::wordsTalkLater)
            106 -> BrandStickerArtwork(3f, StickerArt::wordsMissYou)
            107 -> BrandStickerArtwork(-2f, StickerArt::wordsGoodMorning)
            108 -> BrandStickerArtwork(3f, StickerArt::wordsGoodNight)
            109 -> BrandStickerArtwork(-6f, StickerArt::wordsOK)
            110 -> BrandStickerArtwork(-2f, StickerArt::wordsThanks)
            111 -> BrandStickerArtwork(4f, StickerArt::wordsHaha)
            112 -> BrandStickerArtwork(-2f, StickerArt::wordsPhone)
            201 -> BrandStickerArtwork(-3f, StickerArt::lock)
            202 -> BrandStickerArtwork(5f, StickerArt::ghost)
            203 -> BrandStickerArtwork(0f, StickerArt::key)
            204 -> BrandStickerArtwork(-3f, StickerArt::notLooking)
            205 -> BrandStickerArtwork(-3f, StickerArt::noTrace)
            206 -> BrandStickerArtwork(2f, StickerArt::shh)
            207 -> BrandStickerArtwork(-2f, StickerArt::endToEnd)
            208 -> BrandStickerArtwork(4f, StickerArt::verified)
            301 -> BrandStickerArtwork(-3f, StickerArt::coffee)
            302 -> BrandStickerArtwork(-2f, StickerArt::onMyWay)
            303 -> BrandStickerArtwork(2f, StickerArt::busy)
            304 -> BrandStickerArtwork(-3f, StickerArt::party)
            305 -> BrandStickerArtwork(3f, StickerArt::sleepy)
            306 -> BrandStickerArtwork(-4f, StickerArt::chill)
            307 -> BrandStickerArtwork(0f, StickerArt::cheers)
            else -> null
        }
    }
}

// MARK: - Pen helpers

/** The string of the string telephone. */
internal fun StickerPen.string(path: Path) = line(path, BrandStickerInk.string, width = StickerPen.Weight.STRING)

/** A ring of the line mark (the app icon's “o”). */
internal fun StickerPen.ring(center: Offset, radius: Number, fill: Color = BrandStickerInk.lilac300, width: Number = 4.2) =
    tube(StickerShape.circle(center, radius), fill, width)

internal fun StickerPen.heart(center: Offset, width: Number, fill: Color = BrandStickerInk.warm) =
    shape(StickerShape.heart(center, width), fill)

internal fun StickerPen.sparkle(center: Offset, radius: Number, fill: Color = BrandStickerInk.lilac100) =
    shape(StickerShape.sparkle(center, radius), fill)

/** A dot-matrix sparkle: a centre dot and four smaller ones around it. */
internal fun StickerPen.dotSparkle(center: Offset, radius: Number, color: Color = BrandStickerInk.string) {
    val r = radius.toFloat()
    shape(StickerShape.circle(center, r * 0.36f), color, outline = false)
    for (angle in listOf(0, 90, 180, 270)) {
        val a = radians(angle)
        shape(StickerShape.circle(p(center.x + r * cos(a), center.y + r * sin(a)), r * 0.22f), color, outline = false)
    }
}

/** Short action lines radiating around [center] (degrees clockwise from 3 o'clock). */
internal fun StickerPen.marks(center: Offset, inner: Number, outer: Number, angles: List<Number>, width: Number = 2.2) {
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
        line(path, BrandStickerInk.ink, width = width)
    }
}

/** A card for words. */
internal fun StickerPen.card(rect: Rect, radius: Number = 14, fill: Color = BrandStickerInk.lilac100) =
    shape(StickerShape.rounded(rect, radius), fill)

/** Paper under something that has none of its own (letters floating free). */
internal fun StickerPen.paperPatch(path: Path) = shape(path, BrandStickerInk.paper, outline = false)

/** A closed copy of an open outline. */
private fun closed(path: Path): Path = Path().also {
    it.addPath(path)
    it.close()
}

// MARK: - Compositions

/** One function per sticker. Order inside each: the string first (it leaves from under the cups), then the characters, then what floats in front. */
internal object StickerArt {
    private val ink = BrandStickerInk

    // MARK: Cups

    /** 1: waving hello, the string trailing off to a ring. */
    fun hello(pen: StickerPen, lines: List<String>) {
        val cup = Cup(44, 84, scale = 1.48, eyes = Eyes.Dots(), mouth = Mouth.Grin, arms = listOf(Arm(Side.Right, 25, -38, bend = -4), Arm(Side.Left, -22, -5, bend = 3)))
        pen.string(StickerShape.smooth(listOf(cup.stringStart, p(52, 90), p(68, 92), p(80, 86), p(85, 79))))
        pen.ring(p(88, 75.5), 3.6)
        cup.draw(pen)
        val hand = cup.hand(cup.arms[0])
        pen.marks(hand, 6.5, 10.5, listOf(-125, -80, -35))
    }

    /** 2: two cups, their string tied into a heart between them. */
    fun together(pen: StickerPen, lines: List<String>) {
        val a = Cup(23, 88, scale = 0.9, tilt = 6, style = CupStyle.Light, eyes = Eyes.Happy, mouth = Mouth.Smile)
        val b = Cup(77, 88, scale = 0.9, tilt = -6, eyes = Eyes.Happy, mouth = Mouth.Smile)
        val string = PathBuilder()
            .moveTo(a.stringStart)
            .curveTo(p(36, 90), p(46, 66), p(50, 57))
            .curveTo(p(43, 50), p(34.5, 42), p(34.5, 31))
            .curveTo(p(34.5, 19), p(48, 18), p(50, 28.5))
            .curveTo(p(52, 18), p(65.5, 19), p(65.5, 31))
            .curveTo(p(65.5, 42), p(57, 50), p(50, 57))
            .curveTo(p(54, 66), p(64, 90), b.stringStart)
            .path
        pen.string(string)
        a.draw(pen)
        b.draw(pen)
        pen.heart(p(50, 39), 9)
        pen.dotSparkle(p(16, 26), 4.6)
        pen.dotSparkle(p(86, 20), 3.8)
    }

    /** 3: laughing so hard the tears fly. */
    fun laugh(pen: StickerPen, lines: List<String>) {
        val cup = Cup(50, 84, scale = 1.46, eyes = Eyes.Squeezed, mouth = Mouth.Laugh, arms = listOf(Arm(Side.Left, -22, -2, bend = 4), Arm(Side.Right, 22, -2, bend = 4)))
        pen.string(StickerShape.smooth(listOf(cup.stringStart, p(58, 91), p(66, 86), p(74, 92), p(82, 86), p(90, 90))))
        cup.draw(pen)
        val left = cup.point(-13.5, -21)
        val right = cup.point(13.5, -21)
        pen.shape(StickerShape.drop(p(left.x - 9, left.y - 4), 8, 70), ink.lilac100)
        pen.shape(StickerShape.drop(p(right.x + 9, right.y - 4), 8, -70), ink.lilac100)
        pen.marks(cup.point(0, -27), 31, 36, listOf(-150, -165, -15, -30))
    }

    /** 4: crying its eyes out into a puddle. */
    fun cry(pen: StickerPen, lines: List<String>) {
        pen.shape(StickerShape.ellipse(p(50, 88.5), 36, 5.5), ink.lilac100)
        val cup = Cup(50, 86, scale = 1.44, eyes = Eyes.Sleepy, mouth = Mouth.Sob, brows = Brows.Sad)
        pen.string(StickerShape.smooth(listOf(cup.stringStart, p(34, 92), p(18, 92), p(9, 86))))
        cup.draw(pen)
        // Two streams down the cheeks, over the base, into the puddle.
        for (side in listOf(-1f, 1f)) {
            val stream = PathBuilder()
                .moveTo(cup.point(side * 6.8f, -15.4))
                .curveTo(cup.point(side * 7.4f, -8), cup.point(side * 9.5f, -4), cup.point(side * 10.5f, 1.5))
                .path
            pen.tube(stream, ink.lilac100, 4.4)
        }
        pen.shape(StickerShape.drop(p(17, 33), 7.5, -25), ink.lilac100)
        pen.shape(StickerShape.drop(p(84, 29), 6.5, 25), ink.lilac100)
        pen.shape(StickerShape.drop(p(10, 50), 5, -35), ink.lilac100)
    }

    /** 5: astonished, hands up. */
    fun wow(pen: StickerPen, lines: List<String>) {
        val cup = Cup(50, 86, scale = 1.42, eyes = Eyes.Wide(), mouth = Mouth.O, brows = Brows.Raised, arms = listOf(Arm(Side.Left, -25, -31, bend = -3), Arm(Side.Right, 25, -31, bend = -3)))
        pen.string(StickerShape.smooth(listOf(cup.stringStart, p(60, 92), p(70, 88), p(68, 82), p(63, 86), p(70, 93), p(82, 91), p(88, 84))))
        cup.draw(pen)
        pen.marks(cup.rimTop, 7, 13, listOf(-135, -90, -45))
    }

    /** 6: heart eyes, hearts rising. */
    fun love(pen: StickerPen, lines: List<String>) {
        val cup = Cup(47, 87, scale = 1.46, eyes = Eyes.Hearts, mouth = Mouth.Grin)
        pen.string(StickerShape.smooth(listOf(cup.stringStart, p(60, 92), p(74, 92), p(82, 87))))
        pen.heart(p(86.5, 83), 8.5)
        cup.draw(pen)
        pen.heart(p(80, 22), 13)
        pen.heart(p(90.5, 39), 7.5)
        pen.heart(p(70, 9.5), 6.5)
    }

    /** 7: a wink and a sparkle. */
    fun wink(pen: StickerPen, lines: List<String>) {
        val cup = Cup(48, 87, scale = 1.46, eyes = Eyes.Wink, mouth = Mouth.Tongue, arms = listOf(Arm(Side.Right, 25, -30, bend = -5), Arm(Side.Left, -21, -5, bend = 3)))
        pen.string(StickerShape.smooth(listOf(cup.stringStart, p(36, 92), p(22, 91), p(13, 84), p(15, 77), p(22, 79))))
        cup.draw(pen)
        pen.sparkle(p(86, 22), 8.5)
        pen.dotSparkle(p(73, 12), 3.6)
    }

    /** 8: deep in thought, the string curled into a question mark. */
    fun think(pen: StickerPen, lines: List<String>) {
        val cup = Cup(
            40, 88, scale = 1.38, tilt = -2, eyes = Eyes.Dots(p(1.3, -1.5)), mouth = Mouth.Flat, brows = Brows.Quizzical,
            arms = listOf(Arm(Side.Right, 6.4, -8.4, bend = 6, inFront = true), Arm(Side.Left, -21, -6, bend = 6)),
        )
        pen.string(StickerShape.smooth(listOf(cup.stringStart, p(28, 92), p(14, 90), p(9, 82))))
        cup.draw(pen)
        val question = PathBuilder()
            .moveTo(p(75.5, 16.5))
            .curveTo(p(75.5, 10.5), p(79.5, 7.5), p(84, 7.5))
            .curveTo(p(89, 7.5), p(92.5, 10.5), p(92.5, 15.5))
            .curveTo(p(92.5, 20.5), p(84, 20), p(84, 24.5))
            .lineTo(p(84, 28))
            .path
        pen.line(question, ink.string, width = 3.2)
        pen.shape(StickerShape.circle(p(84, 35.5), 2.4), ink.string, outline = false)
        pen.shape(StickerShape.circle(p(71, 31), 1.6), ink.lilac100)
        pen.shape(StickerShape.circle(p(76, 38.5), 1.1), ink.lilac100)
    }

    /** 9: two cups hugging, their strings tied in a bow. */
    fun hug(pen: StickerPen, lines: List<String>) {
        val a = Cup(31, 86, scale = 0.98, tilt = 6, style = CupStyle.Light, eyes = Eyes.Happy, mouth = Mouth.Smile)
        val b = Cup(69, 86, scale = 0.98, tilt = -6, eyes = Eyes.Happy, mouth = Mouth.Smile)
        // Each reaches round the other: the hand peeks out past the far side.
        a.arms = listOf(Arm(Side.Right, a.local(p(86, 70)), bend = -5))
        b.arms = listOf(Arm(Side.Left, b.local(p(14, 70)), bend = -5))
        val knot = p(50, 91)
        pen.string(StickerShape.quad(a.stringStart, p(40, 92), knot))
        pen.string(StickerShape.quad(b.stringStart, p(60, 92), knot))
        b.drawBehind(pen)
        a.drawBehind(pen)
        a.drawBody(pen)
        b.drawBody(pen)
        val bow = StickerShape.bow(knot, 17)
        for (tail in bow.tails) pen.string(tail)
        for (loop in bow.loops) pen.shape(loop, ink.string)
        pen.shape(bow.knot, ink.string)
        pen.heart(p(50, 25), 12)
        pen.dotSparkle(p(27, 20), 3.8)
        pen.dotSparkle(p(74, 15), 3.2)
    }

    /** 10: dizzy, wound up in its own string. */
    fun tangled(pen: StickerPen, lines: List<String>) {
        val cup = Cup(50, 87, scale = 1.4, tilt = 4, eyes = Eyes.Dizzy, mouth = Mouth.Wavy)
        // Behind: the loops round the back.
        pen.string(StickerShape.smooth(listOf(p(27, 72), p(20, 66), p(22, 58), p(29, 57))))
        pen.string(StickerShape.smooth(listOf(p(73, 80), p(82, 76), p(83, 68), p(75, 64))))
        pen.string(StickerShape.smooth(listOf(cup.stringStart, p(64, 92), p(80, 92), p(91, 85), p(88, 78), p(82, 82))))
        cup.draw(pen)
        // In front: the wraps across the body.
        pen.string(StickerShape.smooth(listOf(p(26, 70), p(50, 76), p(73, 79))))
        pen.string(StickerShape.smooth(listOf(p(29, 57), p(46, 66), p(75, 64))))
        pen.string(StickerShape.smooth(listOf(p(28, 82), p(48, 79), p(72, 86))))
        pen.stroke(StickerShape.ellipse(p(51, 22), 23, 6), ink.ink, width = 1.6, dotted = 3.6)
        pen.sparkle(p(29, 22), 4.6)
        pen.sparkle(p(66, 16.5), 5.4)
        pen.sparkle(p(74, 27), 3.6)
    }

    /** 11: all ears, leaning in towards the sound. */
    fun listen(pen: StickerPen, lines: List<String>) {
        val cup = Cup(
            60, 89, scale = 1.34, tilt = -22, eyes = Eyes.Wide(p(-1.2, -1.2)), mouth = Mouth.SmallO, brows = Brows.Raised,
            arms = listOf(Arm(Side.Left, -25, -34, bend = 3)),
        )
        pen.string(StickerShape.smooth(listOf(cup.stringStart, p(72, 92), p(84, 88), p(88, 80))))
        pen.ring(p(89.5, 75), 3.4)
        cup.draw(pen)
        // Sound arriving: “(((” closing in on the rim.
        val rim = cup.rimTop
        for (radius in listOf(12.0, 19.5, 27.0)) {
            pen.line(StickerShape.arc(rim, radius, 198, 252), ink.ink, width = 2.4)
        }
    }

    // MARK: Words

    /** 101: “Merhaba!”, a bubble from a small waving cup. */
    fun wordsHello(pen: StickerPen, lines: List<String>) {
        val cup = Cup(19, 94, scale = 0.6, eyes = Eyes.Dots(), mouth = Mouth.Grin, blush = true, arms = listOf(Arm(Side.Left, -24, -32, bend = 4)))
        val bubble = StickerShape.bubble(rect(9, 9, 84, 52), 18, tailX = 36, tailWidth = 12, tip = p(29, 71))
        cup.draw(pen)
        pen.shape(bubble, ink.lilac100)
        pen.words(lines, rect(15, 15, 72, 40), maxSize = 30)
    }

    /** 102: “Hat açık”, the card over the line mark. */
    fun wordsLineOpen(pen: StickerPen, lines: List<String>) {
        val bar = PathBuilder().moveTo(p(29, 79)).lineTo(p(71, 79)).path
        pen.tube(bar, ink.lilac300, 4.2)
        pen.ring(p(22, 79), 6.2)
        pen.ring(p(78, 79), 6.2)
        pen.shape(StickerShape.rounded(rect(39, 77.2, 9, 3.6), 1.8), ink.paper, outline = false)
        pen.card(rect(8, 13, 84, 50), radius = 16, fill = ink.lilac300)
        pen.words(lines, rect(14, 18, 72, 40), maxSize = 26)
    }

    /** 103: “Seni duyuyorum”, a cup below listening up to the words. */
    fun wordsHearYou(pen: StickerPen, lines: List<String>) {
        val cup = Cup(24, 94, scale = 0.64, eyes = Eyes.Dots(p(0.8, -1.4)), mouth = Mouth.Smile, arms = listOf(Arm(Side.Right, 22, -30, bend = -3)))
        pen.string(StickerShape.smooth(listOf(cup.stringStart, p(46, 93), p(68, 92), p(80, 87))))
        pen.ring(p(84, 84), 3.6)
        cup.draw(pen)
        pen.card(rect(6, 6, 88, 50))
        pen.words(lines, rect(12, 11, 76, 40), maxSize = 24)
        // The words reaching the cup: “)))” from the card down to its rim.
        val rim = cup.rimTop
        for (radius in listOf(5.0, 10.0)) {
            pen.line(StickerShape.arc(p(rim.x + 9, rim.y - 2), radius, -60, -10), ink.ink, width = 2.2)
        }
    }

    /** 104: “Aramızda kalsın”, a padlock on the line under the words. */
    fun wordsBetweenUs(pen: StickerPen, lines: List<String>) {
        pen.string(StickerShape.quad(p(20, 80), p(50, 88), p(80, 80)))
        pen.ring(p(15, 79), 4.6)
        pen.ring(p(85, 79), 4.6)
        padlock(pen, center = p(50, 87), width = 15f, face = null)
        pen.card(rect(6, 9, 88, 50))
        pen.words(lines, rect(12, 14, 76, 40), maxSize = 24)
    }

    /** 105: “Sonra konuşuruz”, a bow tied in the string, to remember. */
    fun wordsTalkLater(pen: StickerPen, lines: List<String>) {
        val knot = p(50, 80)
        pen.string(StickerShape.quad(p(19, 80), p(34, 84), knot))
        pen.string(StickerShape.quad(p(81, 80), p(66, 84), knot))
        pen.ring(p(14, 79), 4.6)
        pen.ring(p(86, 79), 4.6)
        val bow = StickerShape.bow(knot, 19)
        for (tail in bow.tails) pen.string(tail)
        for (loop in bow.loops) pen.shape(loop, ink.string)
        pen.shape(bow.knot, ink.string)
        pen.card(rect(6, 9, 88, 50), fill = ink.lilac200)
        pen.words(lines, rect(12, 14, 76, 40), maxSize = 24)
    }

    /** 106: “Özledim”, the string drawn into a heart above the words. */
    fun wordsMissYou(pen: StickerPen, lines: List<String>) {
        val string = PathBuilder()
            .moveTo(p(50, 52))
            .lineTo(p(50, 41))
            .curveTo(p(43, 35), p(34, 29), p(34, 20.5))
            .curveTo(p(34, 9.5), p(48, 8.5), p(50, 16.5))
            .curveTo(p(52, 8.5), p(66, 9.5), p(66, 20.5))
            .curveTo(p(66, 29), p(57, 35), p(50, 41))
            .path
        pen.string(string)
        pen.heart(p(50, 24), 10.5)
        pen.card(rect(6, 47, 88, 40))
        pen.words(lines, rect(12, 51, 76, 32), maxSize = 26)
        pen.dotSparkle(p(18, 26), 4)
        pen.dotSparkle(p(83, 34), 3.4)
    }

    /** 107: “Günaydın”, a sun rising behind the card. */
    fun wordsGoodMorning(pen: StickerPen, lines: List<String>) {
        val sun = p(50, 44)
        var angle = -165.0
        while (angle <= -15.0) {
            val a = radians(angle)
            val from = p(sun.x + 25 * cos(a), sun.y + 25 * sin(a))
            val to = p(sun.x + 35 * cos(a), sun.y + 35 * sin(a))
            pen.line(StickerShape.polyline(listOf(from, to)), ink.warm, width = 3.4, dotted = 5)
            angle += 25.0
        }
        pen.shape(StickerShape.circle(sun, 19), ink.warm)
        StickerFace(eyes = Eyes.Happy, mouth = Mouth.None, blush = false).draw(pen, Affine.translation(sun.x, sun.y - 6.5))
        pen.card(rect(6, 43, 88, 44))
        pen.words(lines, rect(12, 47, 76, 36), maxSize = 26)
    }

    /** 108: “İyi geceler”, a night card under the moon. */
    fun wordsGoodNight(pen: StickerPen, lines: List<String>) {
        pen.card(rect(6, 28, 88, 60), fill = ink.night)
        val moon = StickerShape.circle(p(79, 23), 14).subtracting(StickerShape.circle(p(86.5, 16.5), 12))
        pen.shape(moon, ink.lilac100)
        pen.words(lines, rect(12, 34, 76, 48), color = ink.paper, maxSize = 26)
        pen.sparkle(p(17, 18), 5.5)
        pen.sparkle(p(40, 11), 3.4)
        pen.dotSparkle(p(57, 19), 2.6, ink.lilac200)
    }

    /** 109: “Tamam!”, the 5.2 sticker: a card leaning −6° over the mini line mark. */
    fun wordsOK(pen: StickerPen, lines: List<String>) {
        pen.card(rect(8, 21, 84, 58), radius = 20)
        pen.words(lines, rect(14, 26, 72, 32), maxSize = 30)
        val mark = ink.lilac600
        pen.stroke(StickerShape.circle(p(39.5, 67.5), 3.2), mark, width = 2.2)
        pen.stroke(StickerShape.circle(p(60.5, 67.5), 3.2), mark, width = 2.2)
        pen.stroke(StickerShape.polyline(listOf(p(42.7, 67.5), p(57.3, 67.5))), mark, width = 2.2)
    }

    /** 110: “Teşekkürler”, a ribbon banner and a heart. */
    fun wordsThanks(pen: StickerPen, lines: List<String>) {
        for (side in listOf(-1f, 1f)) {
            val x0 = 50 + side * 33
            val x1 = 50 + side * 46
            val tail = StickerShape.polyline(listOf(p(x0, 56), p(x1, 56), p(x1, 79), p((x0 + x1) / 2f, 72.5), p(x0, 79)))
            pen.shape(closed(tail), ink.lilac400)
        }
        pen.card(rect(4, 33, 92, 31), radius = 6, fill = ink.lilac300)
        pen.words(lines, rect(8, 36, 84, 25), maxSize = 22, tracking = -0.04f)
        pen.heart(p(50, 18), 15)
        pen.sparkle(p(26, 18), 4.6)
        pen.sparkle(p(75, 14), 3.6)
    }

    /** 111: “Haha”, bouncing letters on a burst. */
    fun wordsHaha(pen: StickerPen, lines: List<String>) {
        pen.shape(StickerShape.scallop(p(50, 50), 42, bumps = 9, depth = 6), ink.lilac300)
        val word = lines.firstOrNull().orEmpty()
        val letters = word.codePoints().toArray().map { String(Character.toChars(it)) }
        if (letters.isEmpty()) return
        // Each letter in its own cell, 0.74 em apart, so the leaning letters never touch.
        val size = min(25f, 72f / (letters.size * 0.74f))
        val step = size * 0.74f
        val start = 50f - step * (letters.size - 1) / 2f
        letters.forEachIndexed { index, letter ->
            val up = index % 2 == 0
            val baseline = p(start + step * index, 50 + size * 0.35f + (if (up) -3.5f else 3.5f))
            pen.glyphs(letter, baseline, size, ink.ink, tracking = 0f, rotation = if (up) -9 else 8)
        }
        pen.shape(StickerShape.drop(p(16, 22), 7, 60), ink.lilac100)
        pen.shape(StickerShape.drop(p(86, 76), 6, -120), ink.lilac100)
    }

    /** 112: “Alo?”, a ringing cup and its bubble. */
    fun wordsPhone(pen: StickerPen, lines: List<String>) {
        val cup = Cup(25, 91, scale = 0.72, tilt = -12, eyes = Eyes.Dots(p(1, -1)), mouth = Mouth.Grin, arms = listOf(Arm(Side.Left, -22, -20, bend = 3)))
        pen.string(StickerShape.smooth(listOf(cup.stringStart, p(46, 93), p(70, 92), p(84, 87))))
        pen.ring(p(88, 84), 3.6)
        cup.draw(pen)
        pen.marks(cup.point(0, -22), 22, 27, listOf(-160, -175, 190))
        pen.marks(cup.point(0, -22), 22, 27, listOf(-20, -5, 10))
        val bubble = StickerShape.bubble(rect(26, 7, 68, 44), 15, tailX = 40, tailWidth = 11, tip = p(31, 58))
        pen.shape(bubble, ink.lilac100)
        pen.words(lines, rect(32, 12, 56, 34), maxSize = 26)
    }

    // MARK: Privacy

    /** A padlock (optionally with a face) hanging from its shackle. */
    private fun padlock(pen: StickerPen, center: Offset, width: Float, face: StickerFace?) {
        val height = width * 0.84f
        val body = rect(center.x - width / 2f, center.y - height / 2f + width * 0.12f, width, height)
        val (bodyPath, shackle) = StickerShape.padlock(body, width * 0.62f)
        pen.tube(shackle, ink.lilac200, max(3.2f, width * 0.13f))
        pen.shape(bodyPath, ink.lilac300)
        if (face != null) {
            val scale = width / 34f
            face.draw(pen, Affine.translation(body.center.x, body.center.y - height * 0.1f).scaled(scale, scale))
            pen.fill(StickerShape.keyhole(p(body.center.x, body.bottom - height * 0.2f), height * 0.2f), ink.ink)
        } else {
            pen.fill(StickerShape.keyhole(p(body.center.x, body.center.y + 0.5f), height * 0.44f), ink.ink)
        }
    }

    /** 201: a smiling padlock hanging on the line. */
    fun lock(pen: StickerPen, lines: List<String>) {
        padlock(pen, center = p(50, 63), width = 50f, face = StickerFace(eyes = Eyes.Happy, mouth = Mouth.Smile))
        pen.string(StickerShape.quad(p(16, 25), p(50, 31), p(84, 25)))
        pen.ring(p(11, 24), 5)
        pen.ring(p(89, 24), 5)
        pen.dotSparkle(p(14, 76), 4)
        pen.dotSparkle(p(87, 82), 3.4)
    }

    /** 202: a ghost shaped like a cup, fading into dots. */
    fun ghost(pen: StickerPen, lines: List<String>) {
        val body = PathBuilder()
            .moveTo(p(26, 24))
            .curveTo(p(23, 40), p(18, 58), p(19, 70))
        val hem = listOf(19f, 34.5f, 50f, 65.5f, 81f)
        for (index in 0 until hem.size - 1) {
            val x0 = hem[index]
            val x1 = hem[index + 1]
            body.quadTo(p((x0 + x1) / 2f, if (index % 2 == 0) 78 else 63), p(x1, 70))
        }
        body.curveTo(p(82, 58), p(77, 40), p(74, 24))
        body.close()
        pen.shape(body.path, ink.lilac100)
        pen.shape(StickerShape.rounded(rect(21, 17, 58, 10), 5), ink.paper)
        StickerFace(eyes = Eyes.Hollow, mouth = Mouth.O, blush = true).draw(pen, Affine.translation(50, 45).scaled(1.3, 1.3))
        val rows = listOf(
            Triple(79f, listOf(27f, 39f, 50.5f, 62f, 73.5f), 2.3f),
            Triple(86.5f, listOf(33f, 45f, 57f, 68f), 1.7f),
            Triple(93f, listOf(39.5f, 51.5f, 63f), 1.15f),
        )
        for ((y, xs, r) in rows) {
            for (x in xs) pen.shape(StickerShape.circle(p(x, y), r), ink.lilac300, outline = false)
        }
        pen.line(StickerShape.polyline(listOf(p(9, 40), p(13, 40))), ink.ink, width = 2.2)
        pen.line(StickerShape.polyline(listOf(p(7, 50), p(12, 50))), ink.ink, width = 2.2)
    }

    /** 203: the key; its bow is the line's ring, a heart tag tied on. */
    fun key(pen: StickerPen, lines: List<String>) {
        val tagAnchor = p(27, 55)
        pen.string(StickerShape.smooth(listOf(tagAnchor, p(23, 64), p(22, 72))))
        pen.heart(p(22, 78), 14)
        StickerFace(eyes = Eyes.Happy, mouth = Mouth.Smile, blush = false).draw(pen, Affine.translation(22, 76.5).scaled(0.55, 0.55))
        pen.rotated(-28, p(50, 42)) { key ->
            key.shape(StickerShape.rounded(rect(70, 42, 6, 12), 1.6), ink.lilac300)
            key.shape(StickerShape.rounded(rect(79.5, 42, 6, 8.5), 1.6), ink.lilac300)
            val shaft = PathBuilder().moveTo(p(41, 42)).lineTo(p(88, 42)).path
            key.tube(shaft, ink.lilac300, 7.5)
            key.ring(p(30, 42), 11.5, width = 7.5)
        }
        pen.sparkle(p(86, 70), 6.5)
        pen.dotSparkle(p(70, 84), 3.4)
    }

    /** 204: covering its eyes. */
    fun notLooking(pen: StickerPen, lines: List<String>) {
        val cup = Cup(
            50, 86, scale = 1.46, eyes = Eyes.Happy, mouth = Mouth.Grin,
            arms = listOf(
                Arm(Side.Left, -6.6, -18.4, bend = 7, inFront = true, handRadius = 4.3, fingers = true),
                Arm(Side.Right, 6.6, -18.4, bend = 7, inFront = true, handRadius = 4.3, fingers = true),
            ),
        )
        pen.string(StickerShape.smooth(listOf(cup.stringStart, p(38, 91), p(22, 91), p(12, 85))))
        cup.draw(pen)
        pen.marks(cup.rimTop, 7, 12, listOf(-150, -120, -60, -30), width = 2)
    }

    /** 205: “İz yok”, a card that dissolves into dots. */
    fun noTrace(pen: StickerPen, lines: List<String>) {
        val columns = listOf(
            Triple(72.5f, 2.5f, listOf(27f, 34f, 41f, 48f, 55f, 62f, 69f)),
            Triple(79.5f, 2.0f, listOf(30.5f, 37.5f, 44.5f, 51.5f, 58.5f, 65.5f)),
            Triple(86.0f, 1.5f, listOf(27f, 41f, 48f, 62f)),
            Triple(92.0f, 1.0f, listOf(37.5f, 51.5f, 58.5f)),
        )
        for ((x, r, rows) in columns) {
            for (y in rows) pen.shape(StickerShape.circle(p(x, y), r), ink.lilac300, outline = false)
        }
        pen.card(rect(6, 22, 62, 52))
        pen.words(lines, rect(11, 27, 52, 42), maxSize = 24)
    }

    /** 206: “Şşş”, finger to the lips. */
    fun shh(pen: StickerPen, lines: List<String>) {
        val cup = Cup(
            33, 90, scale = 1.24, tilt = 3, eyes = Eyes.Dots(p(1.6, 0)), mouth = Mouth.SmallO, brows = Brows.Raised,
            arms = listOf(Arm(Side.Right, 1.2, -6.5, bend = 7, inFront = true, handRadius = 3.4)),
        )
        pen.string(StickerShape.smooth(listOf(cup.stringStart, p(48, 93), p(64, 92), p(76, 87))))
        cup.draw(pen)
        // The finger, up over the lips.
        val finger = PathBuilder().moveTo(cup.point(1.2, -7)).lineTo(cup.point(1.2, -14.6)).path
        pen.tube(finger, CupStyle.Mid.body, 3.6)
        pen.shape(StickerShape.circle(cup.point(1.2, -6.5), 3.4f * 1.24f), CupStyle.Mid.body)
        val bubble = StickerShape.bubble(rect(52, 9, 42, 31), 13, tailX = 62, tailWidth = 9, tip = p(58, 49))
        pen.shape(bubble, ink.lilac100)
        pen.words(lines, rect(56, 13, 34, 23), maxSize = 22)
    }

    /** 207: end to end, two cups lying back to back, the line locked between them. */
    fun endToEnd(pen: StickerPen, lines: List<String>) {
        val a = Cup(36, 56, scale = 0.72, tilt = -90, style = CupStyle.Light, eyes = Eyes.Happy, mouth = Mouth.Smile)
        val b = Cup(64, 56, scale = 0.72, tilt = 90, eyes = Eyes.Happy, mouth = Mouth.Smile)
        a.uprightFace = true
        b.uprightFace = true
        pen.line(StickerShape.arc(p(50, 56), 26, 198, 342), ink.string, width = 2.6, dotted = 5)
        pen.string(StickerShape.polyline(listOf(a.stringStart, b.stringStart)))
        a.draw(pen)
        b.draw(pen)
        padlock(pen, center = p(50, 66), width = 14f, face = null)
        pen.dotSparkle(p(50, 20), 4)
    }

    /** 208: the verified seal. */
    fun verified(pen: StickerPen, lines: List<String>) {
        for (side in listOf(-1f, 1f)) {
            val x0 = 50 + side * 6
            val x1 = 50 + side * 19
            val tail = StickerShape.polyline(
                listOf(p(x0, 62), p(x1, 62), p(x1 + side * 3, 92), p((x0 + x1) / 2f + side * 2, 85), p(x0 + side * 3, 91)),
            )
            pen.shape(closed(tail), ink.string)
        }
        pen.shape(StickerShape.scallop(p(50, 44), 35, bumps = 12, depth = 5), ink.lilac300)
        pen.shape(StickerShape.circle(p(50, 44), 24), ink.lilac100)
        pen.stroke(StickerShape.circle(p(50, 44), 19.5), ink.string, width = 1.8, dotted = 3.6)
        pen.stroke(StickerShape.polyline(listOf(p(39.5, 44.5), p(47, 52), p(61.5, 36.5))), ink.ink, width = 5)
    }

    // MARK: Mood

    /** 301: a coffee break, sleeve on, steam rising into a heart. */
    fun coffee(pen: StickerPen, lines: List<String>) {
        val cup = Cup(50, 89, scale = 1.46, eyes = Eyes.Happy, mouth = Mouth.Smile)
        cup.faceOffset = -4f
        cup.drawBody(pen)
        val sleeve = StickerShape.polyline(listOf(cup.point(-16.1, -12.5), cup.point(16.1, -12.5), cup.point(14.6, -3.5), cup.point(-14.6, -3.5)))
        pen.shape(closed(sleeve), ink.lilac600)
        pen.heart(cup.point(0, -8), 6, ink.lilac100)
        val rim = cup.rimTop
        for ((dx, phase) in listOf(-9f to 1f, 9f to -1f)) {
            val base = p(rim.x + dx, rim.y - 4)
            val steam = StickerShape.smooth(
                listOf(base, p(base.x + phase * 3, base.y - 5), p(base.x - phase * 1.5f, base.y - 11), p(base.x + phase * 2, base.y - 17)),
            )
            pen.line(steam, ink.string, width = 2.8, dotted = 4.6)
        }
        pen.heart(p(rim.x, rim.y - 16), 8)
    }

    /** 302: “Yoldayım!”, running, the string flying behind. */
    fun onMyWay(pen: StickerPen, lines: List<String>) {
        pen.card(rect(7, 74, 86, 20), radius = 10)
        val cup = Cup(
            54, 64, scale = 1.12, tilt = 12, eyes = Eyes.Dots(p(1.2, 0)), mouth = Mouth.Grin,
            arms = listOf(Arm(Side.Right, 23, -24, bend = -4), Arm(Side.Left, -24, -9, bend = 4)),
        )
        cup.feet = listOf(p(11, 7), p(-12, 4))
        pen.string(StickerShape.smooth(listOf(cup.stringStart, p(38, 70), p(26, 62), p(16, 64), p(8, 58))))
        for ((y, x0, length) in listOf(Triple(34f, 8f, 13f), Triple(48f, 5f, 10f))) {
            pen.line(StickerShape.polyline(listOf(p(x0, y), p(x0 + length, y))), ink.ink, width = 2.4)
        }
        cup.draw(pen)
        pen.words(lines, rect(13, 76.5, 74, 15), maxSize = 16)
    }

    /** 303: “Meşgul”, frazzled, holding up a sign. */
    fun busy(pen: StickerPen, lines: List<String>) {
        val cup = Cup(
            30, 90, scale = 1.2, tilt = -3, eyes = Eyes.Squeezed, mouth = Mouth.Wavy, brows = Brows.Sad,
            arms = listOf(Arm(Side.Right, 25, -27, bend = -3), Arm(Side.Left, -21, -5, bend = 3)),
        )
        val hand = cup.hand(cup.arms[0])
        pen.line(StickerShape.polyline(listOf(p(hand.x, hand.y + 2), p(hand.x + 3, 34))), ink.ink, width = 3)
        pen.string(StickerShape.smooth(listOf(cup.stringStart, p(44, 93), p(60, 92), p(70, 86))))
        cup.draw(pen)
        pen.card(rect(38, 6, 56, 32), radius = 8)
        pen.words(lines, rect(42, 10, 48, 24), maxSize = 22)
        pen.shape(StickerShape.drop(cup.point(-22, -34), 7.5, -20), ink.lilac100)
    }

    /** 304: a party, hat on, arms up, the string a streamer. */
    fun party(pen: StickerPen, lines: List<String>) {
        val cup = Cup(
            50, 87, scale = 1.4, eyes = Eyes.Happy, mouth = Mouth.Laugh,
            arms = listOf(Arm(Side.Left, -25, -37, bend = -3), Arm(Side.Right, 25, -37, bend = -3)),
        )
        pen.string(
            StickerShape.smooth(
                listOf(cup.stringStart, p(64, 93), p(74, 86), p(68, 80), p(64, 86), p(74, 93), p(86, 90), p(90, 82), p(84, 78), p(81, 84)),
            ),
        )
        cup.draw(pen)
        val hat = PathBuilder()
            .moveTo(cup.point(-9, -39.5))
            .lineTo(cup.point(3, -58))
            .lineTo(cup.point(10, -39.5))
            .close()
            .path
        pen.shape(hat, ink.lilac100)
        if (pen.isArt) {
            pen.clipped(hat) { stripes ->
                for (y in listOf(-44f, -50.5f)) {
                    stripes.fill(
                        StickerShape.polyline(listOf(cup.point(-12, y + 1.6f), cup.point(12, y - 1.6f), cup.point(12, y + 1.6f), cup.point(-12, y + 4.8f))),
                        ink.warm,
                    )
                }
            }
            pen.stroke(hat)
        }
        pen.shape(StickerShape.circle(cup.point(3, -58.5), 3.2), ink.warm)
        val confetti = listOf(
            Triple(p(12, 30), ink.warm, 30), Triple(p(20, 14), ink.string, -20), Triple(p(86, 30), ink.lilac400, 50),
            Triple(p(78, 12), ink.warm, -40), Triple(p(91, 50), ink.string, 10), Triple(p(9, 50), ink.lilac400, -60),
        )
        for ((center, color, angle) in confetti) {
            val piece = StickerShape.rounded(rect(-3, -1.4, 6, 2.8), 1.2)
                .applying(Affine.translation(center.x, center.y).rotated(radians(angle)))
            pen.shape(piece, color, outline = false)
        }
        pen.dotSparkle(p(30, 9), 3.2, ink.warm)
    }

    /** 305: sleepy, nightcap on, “z z Z”. */
    fun sleepy(pen: StickerPen, lines: List<String>) {
        val cup = Cup(44, 88, scale = 1.42, tilt = -4, eyes = Eyes.Sleepy, mouth = Mouth.SmallO)
        pen.string(StickerShape.smooth(listOf(cup.stringStart, p(30, 92), p(16, 90), p(10, 83))))
        cup.draw(pen)
        val cap = PathBuilder()
            .moveTo(cup.point(-19, -38))
            .curveTo(cup.point(-17, -50), cup.point(-6, -58), cup.point(6, -58))
            .curveTo(cup.point(17, -58), cup.point(25, -52), cup.point(27, -44))
            .curveTo(cup.point(23, -42), cup.point(20, -40), cup.point(19, -38))
            .close()
            .path
        pen.shape(cap, ink.night)
        pen.shape(StickerShape.rounded(rect(-20.5, -41.5, 41, 6.5), 3.25).applying(cup.transform), ink.lilac100)
        pen.shape(StickerShape.circle(cup.point(28, -42), 3.8), ink.paper)
        val zs = listOf(p(76, 40) to 10f, p(84, 27) to 13f, p(91, 12) to 16f)
        for ((center, size) in zs) pen.paperPatch(StickerShape.circle(center, size * 0.42f))
        for ((center, size) in zs) {
            pen.glyphs("z", p(center.x, center.y + size * 0.25f), size, ink.ink, tracking = 0f, rotation = -10)
        }
    }

    /** 306: cool, sunglasses on. */
    fun chill(pen: StickerPen, lines: List<String>) {
        val cup = Cup(
            49, 87, scale = 1.46, tilt = -5, eyes = Eyes.Sunglasses, mouth = Mouth.Smirk, blush = false,
            arms = listOf(Arm(Side.Right, 21, -6, bend = 7), Arm(Side.Left, -25, -33, bend = -3)),
        )
        pen.string(StickerShape.smooth(listOf(cup.stringStart, p(62, 92), p(76, 91), p(86, 85))))
        pen.ring(p(89.5, 81.5), 3.4)
        cup.draw(pen)
        pen.sparkle(p(84, 22), 8)
        pen.sparkle(p(92, 40), 4)
    }

    /** 307: cheers, two cups clinking. */
    fun cheers(pen: StickerPen, lines: List<String>) {
        val a = Cup(25, 89, scale = 0.96, tilt = 11, style = CupStyle.Light, eyes = Eyes.Happy, mouth = Mouth.Grin, arms = listOf(Arm(Side.Left, -24, -32, bend = -3)))
        val b = Cup(75, 89, scale = 0.96, tilt = -11, eyes = Eyes.Happy, mouth = Mouth.Grin, arms = listOf(Arm(Side.Right, 24, -32, bend = -3)))
        val knot = p(50, 92.5)
        pen.string(StickerShape.quad(a.stringStart, p(38, 94), knot))
        pen.string(StickerShape.quad(b.stringStart, p(62, 94), knot))
        pen.shape(StickerShape.circle(knot, 2.4), ink.string)
        a.draw(pen)
        b.draw(pen)
        pen.marks(p(50, 47), 7, 13, listOf(-140, -90, -40), width = 2.4)
        pen.dotSparkle(p(50, 22), 4)
    }
}
