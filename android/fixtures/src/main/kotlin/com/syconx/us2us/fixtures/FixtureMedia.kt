package com.syconx.us2us.fixtures

import com.syconx.us2us.domain.Bytes
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * The fixtures' attachment bytes that need no platform encoder: the 5.1 voice message (a soft hum
 * that follows its waveform, WAV) and the 5.2 file (a four-page “kira-sozlesmesi.pdf”). Made on first
 * use and kept in memory. The photos and the video come from the app's
 * [com.syconx.us2us.domain.harness.DesignArtworkEncoder].
 */
object FixtureMedia {
    private val documents = ConcurrentHashMap<Boolean, Bytes>()

    /**
     * A voice message's stand-in sound: a soft hum (165 Hz with two overtones, in syllable-like
     * swells) whose loudness follows [waveform] (0…255 samples), [duration] long: 8 kHz 16-bit mono
     * WAV, in memory.
     */
    fun voiceWav(duration: Duration, waveform: Bytes): Bytes {
        val rate = 8_000
        val count = max(1, (max(0.5, duration.toDouble(DurationUnit.SECONDS)) * rate).toInt())
        val levels = if (waveform.isEmpty()) listOf(0.5) else waveform.toUnsignedList().map { it / 255.0 }
        val buffer = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".encodeToByteArray())
        buffer.putInt(36 + count * 2)
        buffer.put("WAVEfmt ".encodeToByteArray())
        buffer.putInt(16)
        buffer.putShort(1) // PCM
        buffer.putShort(1) // mono
        buffer.putInt(rate)
        buffer.putInt(rate * 2)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".encodeToByteArray())
        buffer.putInt(count * 2)
        for (index in 0 until count) {
            val t = index.toDouble() / rate
            val level = levels[min(levels.size - 1, (index.toLong() * levels.size / count).toInt())]
            val swell = 0.55 + 0.45 * sin(2 * PI * 3.2 * t)
            val hum = sin(2 * PI * 165 * t) + 0.35 * sin(2 * PI * 330 * t) + 0.15 * sin(2 * PI * 495 * t)
            val value = max(-1.0, min(1.0, hum / 1.5 * level * swell * 0.6))
            buffer.putShort((value * Short.MAX_VALUE).toInt().toShort())
        }
        return Bytes.wrap(buffer.array())
    }

    /** The 5.2 PDF: four A4 pages of a sample lease. */
    fun leasePdf(english: Boolean): Bytes = documents.getOrPut(english) { LeaseDocument.render(english) }
}

/** A plain four-page lease (sample text), drawn with the standard Helvetica faces. */
internal object LeaseDocument {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842

    fun render(english: Boolean): Bytes {
        val title = if (english) "Lease agreement" else "Kira sözleşmesi"
        val clauses = if (english) {
            listOf(
                "1. The landlord lets the flat at Moda, Kadıköy to the tenant for twelve months.",
                "2. The rent is paid by the fifth day of every month.",
                "3. The deposit is returned within thirty days after the keys are handed back.",
                "4. Either side may end the agreement with two months' written notice.",
            )
        } else {
            listOf(
                "1. Ev sahibi, Moda, Kadıköy'deki daireyi on iki ay süreyle kiracıya kiralar.",
                "2. Kira her ayın beşine kadar ödenir.",
                "3. Depozito, anahtarlar teslim edildikten sonra otuz gün içinde iade edilir.",
                "4. Taraflardan her biri iki ay önceden yazılı bildirimle sözleşmeyi sona erdirebilir.",
            )
        }
        val pages = clauses.mapIndexed { index, clause ->
            val pageLabel = if (english) "Page ${index + 1} of 4" else "Sayfa ${index + 1} / 4"
            pageContent(title, pageLabel, clause)
        }
        return Bytes.wrap(PdfWriter.write(pages, PAGE_WIDTH, PAGE_HEIGHT))
    }

    /** One page: the title, the page label, the clause and 18 grey lines standing in for the text. */
    private fun pageContent(title: String, pageLabel: String, clause: String): String = buildString {
        // Top-left coordinates as drawn on iOS, turned into PDF's bottom-up ones.
        fun y(top: Double, size: Double) = PAGE_HEIGHT - top - size
        append("BT /F2 22 Tf 0 g 56 ${fmt(y(64.0, 22.0))} Td ${PdfWriter.text(title)} Tj ET\n")
        append("BT /F1 12 Tf 0.333 g 56 ${fmt(y(100.0, 12.0))} Td ${PdfWriter.text(pageLabel)} Tj ET\n")
        var lineTop = 140.0
        for (line in PdfWriter.wrap(clause, 12.0, (PAGE_WIDTH - 112).toDouble())) {
            append("BT /F1 12 Tf 0.333 g 56 ${fmt(y(lineTop, 12.0))} Td ${PdfWriter.text(line)} Tj ET\n")
            lineTop += 14.4
        }
        append("0.667 g\n")
        for (line in 0 until 18) {
            val width = (PAGE_WIDTH - 112) * if (line % 5 == 4) 0.6 else 1.0
            append("56 ${fmt(PAGE_HEIGHT - (220.0 + line * 26) - 6)} ${fmt(width)} 6 re f\n")
        }
    }

    private fun fmt(value: Double): String = if (value == value.toLong().toDouble()) value.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, value)
}

/**
 * A minimal PDF 1.4 writer for the fixture document: pages of text in the standard Helvetica
 * faces (WinAnsi, with the Turkish letters WinAnsi lacks mapped through a `/Differences` array)
 * and filled rectangles.
 */
internal object PdfWriter {
    /** Turkish letters outside WinAnsi, put on unused codes (glyph names of the standard Latin set). */
    private val extraGlyphs = linkedMapOf(
        'ş' to (0x80 to "scedilla"), 'ğ' to (0x81 to "gbreve"), 'ı' to (0x82 to "dotlessi"),
        'İ' to (0x83 to "Idotaccent"), 'Ş' to (0x84 to "Scedilla"), 'Ğ' to (0x85 to "Gbreve"),
    )

    /** WinAnsi codes of the Latin-1 letters the documents use. */
    private val winAnsiLatin = setOf('ç', 'ö', 'ü', 'Ç', 'Ö', 'Ü', 'é', 'â')

    /** Helvetica advance widths (per 1000) for wrapping; other characters count as 556. */
    private val helveticaWidths: Map<Char, Int> = buildMap {
        val ascii = " !\"#\$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~"
        val widths = intArrayOf(
            278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
            556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556, 1015,
            667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, 667, 778, 722, 667, 611, 722, 667,
            944, 667, 667, 611, 278, 278, 278, 469, 556, 333,
            556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, 556, 556, 333, 500, 278, 556, 500,
            722, 500, 500, 500, 334, 260, 334, 584,
        )
        ascii.forEachIndexed { index, char -> put(char, widths[index]) }
        putAll(mapOf('ç' to 500, 'ş' to 500, 'ğ' to 556, 'ı' to 278, 'ö' to 556, 'ü' to 556, 'İ' to 278, 'Ç' to 722, 'Ş' to 667, 'Ğ' to 778, 'Ö' to 778, 'Ü' to 722))
    }

    /** [text] as a PDF string literal in the fonts' encoding. */
    fun text(text: String): String = buildString {
        append('(')
        for (char in text) {
            val code = extraGlyphs[char]?.first ?: when {
                char.code in 0x20..0x7E -> char.code
                char in winAnsiLatin -> char.code
                char == '’' -> 0x92
                else -> '?'.code
            }
            when (code) {
                '('.code, ')'.code, '\\'.code -> append('\\').append(code.toChar())
                in 0x20..0x7E -> append(code.toChar())
                else -> append('\\').append(Integer.toOctalString(code).padStart(3, '0'))
            }
        }
        append(')')
    }

    /** Greedy word wrap of [text] at [size] pt into lines at most [width] wide. */
    fun wrap(text: String, size: Double, width: Double): List<String> {
        fun measure(line: String) = line.sumOf { helveticaWidths[it] ?: 556 } * size / 1000
        val lines = mutableListOf<String>()
        var current = ""
        for (word in text.split(' ')) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && measure(candidate) > width) {
                lines += current
                current = word
            } else {
                current = candidate
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    fun write(pageContents: List<String>, width: Int, height: Int): ByteArray {
        val objects = mutableListOf<ByteArray>()
        fun add(body: String): Int {
            objects += body.encodeToByteArray()
            return objects.size
        }
        val differences = extraGlyphs.values.joinToString(" ") { (code, name) -> "$code /$name" }
        val encoding = "<< /Type /Encoding /BaseEncoding /WinAnsiEncoding /Differences [ $differences ] >>"
        // 1: catalog, 2: pages, 3: regular font, 4: bold font, then a content stream and a page per page.
        add("<< /Type /Catalog /Pages 2 0 R >>")
        val pageIds = pageContents.indices.map { 6 + it * 2 }
        add("<< /Type /Pages /Kids [ ${pageIds.joinToString(" ") { "$it 0 R" }} ] /Count ${pageContents.size} >>")
        add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding $encoding >>")
        add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding $encoding >>")
        for (content in pageContents) {
            val stream = content.encodeToByteArray()
            objects += "<< /Length ${stream.size} >>\nstream\n".encodeToByteArray() + stream + "\nendstream".encodeToByteArray()
            add(
                "<< /Type /Page /Parent 2 0 R /MediaBox [ 0 0 $width $height ] " +
                    "/Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents ${objects.size} 0 R >>",
            )
        }
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
        val offsets = IntArray(objects.size)
        objects.forEachIndexed { index, body ->
            offsets[index] = out.size()
            out.write("${index + 1} 0 obj\n".encodeToByteArray())
            out.write(body)
            out.write("\nendobj\n".encodeToByteArray())
        }
        val xref = out.size()
        out.write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n".encodeToByteArray())
        for (offset in offsets) out.write("%010d 00000 n \n".format(offset).encodeToByteArray())
        out.write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".encodeToByteArray())
        return out.toByteArray()
    }
}
