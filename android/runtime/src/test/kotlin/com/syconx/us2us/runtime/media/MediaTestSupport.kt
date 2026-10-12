package com.syconx.us2us.runtime.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import kotlin.random.Random

/** A test image: left half red, right half blue (or noise). */
fun testBitmap(width: Int, height: Int, noise: Boolean = false, alpha: Boolean = false): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    if (noise) {
        val random = Random(7)
        val pixels = IntArray(width * height) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    } else {
        val canvas = Canvas(bitmap)
        if (alpha) canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), Paint().apply { color = Color.rgb(230, 51, 26) })
        canvas.drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = if (alpha) Color.argb(128, 26, 51, 230) else Color.rgb(26, 51, 230) })
    }
    return bitmap
}

fun Bitmap.encoded(format: Bitmap.CompressFormat, quality: Int = 90): ByteArray =
    ByteArrayOutputStream().also { compress(format, quality, it) }.toByteArray()

/**
 * A JPEG as a camera would write it: EXIF with a comment and the capture date, GPS, the maker and
 * model, and an orientation.
 */
fun makeCameraJpeg(width: Int, height: Int, orientation: Int = 1, noise: Boolean = false): ByteArray {
    val file = File.createTempFile("camera", ".jpg")
    try {
        file.writeBytes(testBitmap(width, height, noise).encoded(Bitmap.CompressFormat.JPEG))
        val exif = ExifInterface(file)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        exif.setAttribute(ExifInterface.TAG_MAKE, "PhoneMaker")
        exif.setAttribute(ExifInterface.TAG_MODEL, "Model X")
        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "secret place")
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:11 10:00:00")
        exif.setLatLong(41.0082, 28.9784)
        exif.saveAttributes()
        return file.readBytes()
    } finally {
        file.delete()
    }
}

/** The APPn and COM markers of a JPEG before its scan. */
fun jpegMarkers(data: ByteArray): List<Int> {
    val markers = mutableListOf<Int>()
    var index = 2
    while (index + 4 <= data.size && data.u8(index) == 0xFF) {
        val marker = data.u8(index + 1)
        if (marker == 0xDA) break
        markers += marker
        index += 2 + ((data.u8(index + 2) shl 8) or data.u8(index + 3))
    }
    return markers
}

fun ByteArray.contains(text: String): Boolean = String(this, Charsets.ISO_8859_1).contains(text)

/** A PNG chunk with its length and CRC. */
fun pngChunk(type: String, data: ByteArray): ByteArray {
    val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
    val crc = CRC32().apply {
        update(typeBytes)
        update(data)
    }.value
    return be32(data.size) + typeBytes + data + be32(crc.toInt())
}

/** [png] with [chunks] inserted right after IHDR. */
fun insertPngChunks(png: ByteArray, vararg chunks: ByteArray): ByteArray {
    val ihdrEnd = 8 + 12 + 13
    return png.copyOfRange(0, ihdrEnd) + chunks.fold(ByteArray(0)) { all, chunk -> all + chunk } + png.copyOfRange(ihdrEnd, png.size)
}

fun be32(value: Int): ByteArray = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

fun le32(value: Int): ByteArray = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

/**
 * A two-frame animated GIF (4 × 4, two colours) with a loop count, a comment, an XMP application
 * extension and bytes after the trailer.
 */
fun animatedGifWithMetadata(): ByteArray {
    val out = ByteArrayOutputStream()
    out.write("GIF89a".toByteArray(Charsets.ISO_8859_1))
    out.write(byteArrayOf(4, 0, 4, 0, 0x80.toByte(), 0, 0)) // 4×4, global colour table of 2 entries
    out.write(byteArrayOf(0xFF.toByte(), 0, 0, 0, 0, 0xFF.toByte())) // red, blue
    // NETSCAPE2.0 loop forever
    out.write(byteArrayOf(0x21, 0xFF.toByte(), 11) + "NETSCAPE2.0".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(3, 1, 0, 0, 0))
    // Comment
    out.write(byteArrayOf(0x21, 0xFE.toByte(), 12) + "secret place".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0))
    // XMP application extension (as one sub-block, for the test)
    val xmp = "<x:xmpmeta>PhoneMaker</x:xmpmeta>".toByteArray(Charsets.ISO_8859_1)
    out.write(byteArrayOf(0x21, 0xFF.toByte(), 11) + "XMP DataXMP".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(xmp.size.toByte()) + xmp + byteArrayOf(0))
    for (colour in 0..1) {
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 10, 0, 0, 0)) // graphic control: 100 ms
        out.write(byteArrayOf(0x2C, 0, 0, 0, 0, 4, 0, 4, 0, 0)) // image descriptor 4×4
        out.write(byteArrayOf(2)) // LZW minimum code size
        // 16 pixels of one colour: clear, then 16 codes of colour, end (LZW, 3-bit codes).
        out.write(lzwBlock(colour))
        out.write(byteArrayOf(0))
    }
    out.write(0x3B)
    out.write("trailer".toByteArray(Charsets.ISO_8859_1))
    return out.toByteArray()
}

/** One sub-block of LZW data for sixteen pixels of [colour] (code size 2: clear 4, end 5). */
private fun lzwBlock(colour: Int): ByteArray {
    // Without dictionary growth: emit clear before every 2 codes so the code size stays 3 bits.
    val codes = mutableListOf<Int>()
    repeat(8) {
        codes += 4
        codes += colour
        codes += colour
    }
    codes += 5
    var bits = 0L
    var count = 0
    val bytes = ByteArrayOutputStream()
    for (code in codes) {
        bits = bits or (code.toLong() shl count)
        count += 3
        while (count >= 8) {
            bytes.write((bits and 0xFF).toInt())
            bits = bits ushr 8
            count -= 8
        }
    }
    if (count > 0) bytes.write((bits and 0xFF).toInt())
    val data = bytes.toByteArray()
    return byteArrayOf(data.size.toByte()) + data
}
