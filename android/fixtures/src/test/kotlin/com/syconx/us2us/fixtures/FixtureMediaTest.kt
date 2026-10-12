package com.syconx.us2us.fixtures

import com.syconx.us2us.domain.Bytes
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FixtureMediaTest {
    @Test
    fun `the voice stand-in is an 8 kHz 16-bit mono WAV of the message's length`() {
        val wav = FixtureMedia.voiceWav(9.seconds, Bytes.ofUnsigned(List(30) { (it * 8) % 256 })).toByteArray()
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(wav.size - 8, header.getInt(4))
        assertEquals("WAVEfmt ", String(wav, 8, 8, Charsets.US_ASCII))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(8_000, header.getInt(24))
        assertEquals(16, header.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(9 * 8_000 * 2, header.getInt(40))
        // The hum is never silent where the waveform is loud, and never clips.
        val samples = (0 until 9 * 8_000).map { header.getShort(44 + it * 2).toInt() }
        assertTrue(samples.maxOrNull()!! > 1_000)
        // Half a second at least, even for an empty recording.
        assertEquals(44 + 4_000 * 2, FixtureMedia.voiceWav(0.seconds, Bytes.Empty).size)
    }

    @Test
    fun `the lease is a four-page PDF with a correct cross-reference table`() {
        val pdf = FixtureMedia.leasePdf(english = false).toByteArray()
        val text = String(pdf, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4\n"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        assertEquals(4, Regex("/Type /Page /Parent").findAll(text).count())
        assertTrue("/Count 4" in text)
        // Every xref entry points at its object's header.
        val xref = text.substring(text.lastIndexOf("\nxref\n"))
        val offsets = Regex("(\\d{10}) 00000 n").findAll(xref).map { it.groupValues[1].toInt() }.toList()
        assertEquals(12, offsets.size)
        offsets.forEachIndexed { index, offset -> assertTrue(text.startsWith("${index + 1} 0 obj", offset), "object ${index + 1}") }
        val startXref = text.substringAfter("startxref\n").substringBefore('\n').toInt()
        assertTrue(text.startsWith("xref", startXref))
        // Turkish letters outside WinAnsi go through the font's own encoding.
        assertTrue("/scedilla" in text && "/dotlessi" in text)
        assertTrue("(Kira s\\366zle\\200mesi) Tj" in text)
        assertTrue("(Sayfa 1 / 4) Tj" in text)
        assertSame(FixtureMedia.leasePdf(english = false), FixtureMedia.leasePdf(english = false))
        assertTrue("(Lease agreement) Tj" in String(FixtureMedia.leasePdf(english = true).toByteArray(), Charsets.ISO_8859_1))
    }

    @Test
    fun `long clauses wrap inside the page`() {
        val clause = "4. Taraflardan her biri iki ay önceden yazılı bildirimle sözleşmeyi sona erdirebilir."
        assertEquals(listOf(clause), PdfWriter.wrap(clause, 12.0, 483.0))
        val lines = PdfWriter.wrap("$clause $clause", 12.0, 483.0)
        assertEquals(2, lines.size)
        assertEquals("$clause $clause", lines.joinToString(" "))
        assertEquals(PdfWriter.wrap("short", 12.0, 483.0), listOf("short"))
    }
}
