package com.syconx.us2us.ui.icons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.asAndroidPath
import com.syconx.us2us.shot.ShotPaths
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every Phosphor glyph of the design's screens (ios/design/layout), drawn the way [PhosphorIcon]
 * draws it, against the same pixels of the iOS reference PNGs (dark and light).
 *
 * Each glyph is drawn in its frame (3 px per dp), at its size, in the token colour the layout
 * records, over the background read from the reference around it. Left out: placements whose
 * surroundings are not one flat colour (photos, gradients, neighbours), and placements whose ink is
 * not the token colour (under a dimming scrim or a blur, on media).
 *
 * The references were drawn by Chrome from the Phosphor web font, whose baseline Blink rounds to
 * whole pixels: glyphs sit up to ±3 px (1 dp) off their frame vertically. The iOS app draws the
 * SVG in the frame, as [PhosphorIcon] does, so the shape is compared at the best sub-pixel offset
 * within that range, and the offset is checked separately: none horizontally, at most 3 px (1 dp)
 * vertically. The error is relative to the glyph's ink: 0 is identical, 1 a glyph missing entirely.
 *
 * Writes `android/build/shots/android/designsystem/icons-parity.txt` and side-by-sides of the
 * worst placements (`icons-parity-worst.png`: reference, Android at the best offset, difference × 4).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class IconParityTest {
    private data class Placement(
        val screen: String,
        val id: String,
        val glyph: PhosphorGlyph,
        val weight: PhosphorWeight,
        val size: Double,
        val x: Double,
        val y: Double,
        val color: Int,
        val reference: File,
        val theme: String,
    )

    private class Result(
        val placement: Placement,
        /** The error with the glyph exactly in its frame. */
        val inFrame: Double,
        /** The best offset in px, and the error there. */
        val dx: Double,
        val dy: Double,
        val error: Double,
        val side: BufferedImage,
    )

    /** icons.md: `text-aa` appears only on the 9.3 design note card and is not bundled. */
    private val notBundled = setOf("text-aa")

    /** Glyphs drawn in a transformed layer: 3.3's lock badge is part of the lock screen shrunk and dimmed behind the sheet. */
    private val transformed = setOf("3.3-kodu-unuttum/r.0.0.0.0")
    private val paths = HashMap<Pair<PhosphorGlyph, PhosphorWeight>, android.graphics.Path>()

    @Test
    fun glyphsMatchTheReferences() {
        val placements = placements()
        val results = mutableListOf<Result>()
        val skipped = mutableListOf<String>()
        val images = HashMap<File, BufferedImage>()
        for (p in placements) {
            val image = images.getOrPut(p.reference) { ImageIO.read(p.reference) }
            when (val outcome = compare(p, image)) {
                is Result -> results += outcome
                is String -> skipped += "${p.screen} ${p.id} ${p.glyph.raw} (${p.theme}): $outcome"
            }
        }

        val errors = results.map { it.error }
        val report = buildString {
            appendLine("Phosphor glyphs against the iOS references: relative ink error (0 = identical) at the best sub-pixel offset, the offset, and the error exactly in the frame.")
            appendLine("compared ${results.size}, skipped ${skipped.size} of ${placements.size} placements; ${results.map { it.placement.glyph to it.placement.weight }.toSet().size} glyph/weight combinations")
            appendLine("error: median %.3f, mean %.3f, worst %.3f".format(Locale.ROOT, median(errors), errors.average(), errors.max()))
            appendLine("offset: |dx| max %.2f px; dy %s".format(Locale.ROOT, results.maxOf { abs(it.dx) }, results.groupingBy { it.dy }.eachCount().toSortedMap()))
            appendLine()
            for (r in results.sortedByDescending { it.error }) {
                val p = r.placement
                appendLine(
                    "%-26s %-6s %-12s %-20s %-7s %4.0f  error %.3f at (%+.2f, %+.2f) px   in frame %.3f".format(
                        Locale.ROOT, p.screen, p.theme, p.id, p.glyph.raw, p.weight.name.lowercase(), p.size, r.error, r.dx, r.dy, r.inFrame,
                    ),
                )
            }
            appendLine()
            skipped.forEach { appendLine("skipped: $it") }
        }
        val dir = File(ShotPaths.shotsDir, "android/designsystem").apply { mkdirs() }
        File(dir, "icons-parity.txt").writeText(report)
        writeWorst(results.sortedByDescending { it.error }.take(16), File(dir, "icons-parity-worst.png"))

        val combinations = results.map { it.placement.glyph to it.placement.weight }.toSet()
        assertTrue("too few placements compared (${results.size}):\n$report", results.size >= 250)
        assertTrue("too few glyphs compared (${combinations.size}):\n$report", combinations.size >= 45)
        assertTrue("the box is the glyph size: no horizontal offset\n$report", results.all { abs(it.dx) <= 0.5 })
        assertTrue("vertically within Blink's baseline rounding (1 dp)\n$report", results.all { abs(it.dy) <= 3.0 })
        assertTrue("glyph shapes differ from the references:\n$report", median(errors) < 0.12)
        assertTrue("placements far from the references:\n$report", results.none { it.error > 0.3 })
    }

    private fun placements(): List<Placement> {
        val layouts = File(ShotPaths.designDir, "layout").listFiles { f -> f.extension == "json" }!!.sorted()
        val out = mutableListOf<Placement>()
        for (file in layouts) {
            val layout = JSONObject(file.readText())
            val references = layout.getJSONObject("reference")
            val elements = layout.getJSONArray("elements")
            for (i in 0 until elements.length()) {
                val element = elements.getJSONObject(i)
                if (element.optString("kind") != "icon") continue
                val icon = element.getJSONObject("icon")
                if (icon.getString("name") in notBundled) continue
                if ("${file.nameWithoutExtension}/${element.getString("id")}" in transformed) continue
                val glyph = PhosphorGlyph.fromRaw(icon.getString("name")) ?: error("${file.name}: unknown glyph ${icon.getString("name")}")
                val weight = if (icon.getString("weight") == "fill") PhosphorWeight.Fill else PhosphorWeight.Regular
                val frame = element.getJSONObject("frame")
                val paint = element.getJSONObject("paint").getJSONObject("color")
                for (theme in listOf("dark", "light")) {
                    if (!references.has(theme)) continue
                    out += Placement(
                        screen = file.nameWithoutExtension,
                        id = element.getString("id"),
                        glyph = glyph,
                        weight = weight,
                        size = icon.getDouble("size"),
                        x = frame.getDouble("x"),
                        y = frame.getDouble("y"),
                        color = parseColor(paint.getString(theme)),
                        reference = File(ShotPaths.designDir, references.getString(theme)),
                        theme = theme,
                    )
                }
            }
        }
        return out
    }

    /** `#e9e9ed` or `#e9e9ed@68%` → ARGB. */
    private fun parseColor(value: String): Int {
        val match = Regex("""#([0-9a-fA-F]{6})(?:@(\d+(?:\.\d+)?)%)?""").matchEntire(value.trim()) ?: error("colour $value")
        val rgb = match.groupValues[1].toInt(16)
        val alpha = match.groupValues[2].takeIf { it.isNotEmpty() }?.let { (it.toDouble() * 2.55).roundToInt() } ?: 255
        return (alpha shl 24) or rgb
    }

    /** The comparison, or why the placement cannot be compared. */
    private fun compare(p: Placement, image: BufferedImage): Any {
        val scale = 3.0
        val pad = 9
        val x0 = floor(p.x * scale).toInt() - pad
        val y0 = floor(p.y * scale).toInt() - pad
        val x1 = ceil((p.x + p.size) * scale).toInt() + pad
        val y1 = ceil((p.y + p.size) * scale).toInt() + pad
        if (x0 < 0 || y0 < 0 || x1 > image.width || y1 > image.height) return "off screen"
        val w = x1 - x0
        val h = y1 - y0
        val ref = IntArray(w * h) { image.getRGB(x0 + it % w, y0 + it / w) and 0xFFFFFF }

        // The background, row by row (cards and covers have vertical gradients): the left and right
        // edges of the crop, which must agree with each other and with the top and bottom edges.
        val edge = 3
        val rowBackground = IntArray(h) { y -> medianColor((0 until edge).flatMap { listOf(ref[y * w + it], ref[y * w + w - 1 - it]) }) }
        val background = IntArray(w * h) { rowBackground[it / w] }
        val ring = (0 until w * h).filter { val x = it % w; val y = it / w; x < edge || y < edge || x >= w - edge || y >= h - edge }
        if (ring.count { delta(ref[it], background[it]) > 10 } > ring.size / 20) return "background not flat"
        // The ink: the reference's strongest pixel must be the token colour over that background.
        val strongest = ref.indices.maxBy { delta(ref[it], background[it]) }
        val expectedInk = blend(p.color, background[strongest])
        if (delta(ref[strongest], expectedInk) > 40) {
            return "ink %06x is not the token colour %06x (scrim, blur or media)".format(Locale.ROOT, ref[strongest], expectedInk)
        }

        val inFrame = relativeError(ref, draw(p, background, x0, y0, w, h, 0.0, 0.0), background)
        var best = Triple(0.0, 0.0, inFrame)
        for (iy in -16..16) for (ix in -2..2) {
            val dx = ix * 0.25
            val dy = iy * 0.25
            val error = relativeError(ref, draw(p, background, x0, y0, w, h, dx, dy), background)
            if (error < best.third - 1e-9) best = Triple(dx, dy, error)
        }
        val drawn = draw(p, background, x0, y0, w, h, best.first, best.second)
        val side = BufferedImage(w * 3, h, BufferedImage.TYPE_INT_RGB)
        for (i in 0 until w * h) {
            val x = i % w
            val y = i / w
            side.setRGB(x, y, ref[i])
            side.setRGB(w + x, y, drawn[i])
            val d = (delta(ref[i], drawn[i]) * 4).coerceAtMost(255)
            side.setRGB(2 * w + x, y, (d shl 16) or (d shl 8) or d)
        }
        return Result(p, inFrame, best.first, best.second, best.third, side)
    }

    /**
     * The glyph drawn as [PhosphorIcon] draws it: its outline scaled into a size × size box,
     * anti-aliased, in the token colour, over [background] (source-over, per pixel).
     */
    private fun draw(p: Placement, background: IntArray, x0: Int, y0: Int, w: Int, h: Int, dx: Double, dy: Double): IntArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate((p.x * 3 - x0 + dx).toFloat(), (p.y * 3 - y0 + dy).toFloat())
        val s = (p.size * 3 / 256).toFloat()
        canvas.scale(s, s)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.color }
        canvas.drawPath(paths.getOrPut(p.glyph to p.weight) { p.glyph.path(p.weight).asAndroidPath() }, paint)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()
        for (i in pixels.indices) pixels[i] = blend(pixels[i], background[i])
        return pixels
    }

    /** Σ|reference − drawn| over the ink of either: the share of the glyph that differs. */
    private fun relativeError(ref: IntArray, drawn: IntArray, background: IntArray): Double {
        var diff = 0.0
        var ink = 0.0
        for (i in ref.indices) {
            diff += delta(ref[i], drawn[i])
            ink += max(delta(ref[i], background[i]), delta(drawn[i], background[i]))
        }
        return if (ink == 0.0) 1.0 else diff / ink
    }

    private fun blend(argb: Int, background: Int): Int {
        val a = ((argb ushr 24) and 0xFF) / 255.0
        var out = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val c = (argb shr shift) and 0xFF
            val b = (background shr shift) and 0xFF
            out = out or ((c * a + b * (1 - a)).roundToInt() shl shift)
        }
        return out
    }

    private fun delta(a: Int, b: Int): Int {
        var d = 0
        for (shift in intArrayOf(16, 8, 0)) d = max(d, abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)))
        return d
    }

    private fun medianColor(pixels: List<Int>): Int {
        fun channel(shift: Int) = pixels.map { (it shr shift) and 0xFF }.sorted()[pixels.size / 2]
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun median(values: List<Double>): Double = values.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }

    private fun writeWorst(results: List<Result>, file: File) {
        if (results.isEmpty()) return
        val scale = 2
        val width = results.maxOf { it.side.width } * scale
        val height = results.sumOf { it.side.height * scale + 4 }
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        var y = 0
        for (r in results) {
            for (py in 0 until r.side.height * scale) for (px in 0 until r.side.width * scale) {
                out.setRGB(px, y + py, r.side.getRGB(px / scale, py / scale))
            }
            y += r.side.height * scale + 4
        }
        ImageIO.write(out, "png", file)
    }
}
