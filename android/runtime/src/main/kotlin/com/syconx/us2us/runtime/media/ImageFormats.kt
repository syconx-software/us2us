package com.syconx.us2us.runtime.media

import java.io.ByteArrayOutputStream

/** The image containers the runtime tells apart by their first bytes. */
internal enum class ImageFormat(val contentType: String, val fileExtension: String) {
    JPEG("image/jpeg", "jpeg"),
    PNG("image/png", "png"),
    GIF("image/gif", "gif"),
    WEBP("image/webp", "webp"),
    HEIF("image/heic", "heic"),
    BMP("image/bmp", "bmp"),
    ;

    companion object {
        private val heifBrands = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1", "avif", "avis")

        /** The format of [head] (the first bytes of a file), or `null`. */
        fun sniff(head: ByteArray): ImageFormat? = when {
            head.size >= 3 && head.u8(0) == 0xFF && head.u8(1) == 0xD8 && head.u8(2) == 0xFF -> JPEG
            head.size >= 8 && head.startsWith(0, 8, "\u0089PNG\r\n\u001A\n") -> PNG
            head.size >= 6 && (head.startsWith(0, 6, "GIF87a") || head.startsWith(0, 6, "GIF89a")) -> GIF
            head.size >= 12 && head.startsWith(0, 4, "RIFF") && head.startsWith(8, 12, "WEBP") -> WEBP
            head.size >= 12 && head.startsWith(4, 8, "ftyp") && String(head, 8, 4, Charsets.ISO_8859_1) in heifBrands -> HEIF
            head.size >= 2 && head.startsWith(0, 2, "BM") -> BMP
            else -> null
        }
    }
}

/**
 * Image metadata that can identify a person, place, device or time, read from the container
 * itself (no decoder involved): EXIF, XMP, IPTC, maker or other application segments, comments,
 * JFIF thumbnails, extra images (MPF) and bytes after the end of a JPEG; text, time and EXIF
 * chunks of a PNG; comments and foreign application extensions of a GIF; EXIF, XMP and unknown
 * chunks of a WebP. Colour profiles, layout and animation timing are not identifying. Reported as
 * `"{Dictionary}"` names in the spirit of the iOS app's checks; empty for a clean file and for
 * formats this does not read (the app never writes those).
 */
internal object ImageMetadata {
    fun identifying(data: ByteArray): List<String> = when (ImageFormat.sniff(data.copyOfRange(0, minOf(data.size, 32)))) {
        ImageFormat.JPEG -> jpeg(data)
        ImageFormat.PNG -> png(data)
        ImageFormat.GIF -> gif(data)
        ImageFormat.WEBP -> webp(data)
        else -> emptyList()
    }.distinct().sorted()

    // JPEG

    private fun jpeg(b: ByteArray): List<String> {
        val found = mutableListOf<String>()
        var index = 2
        var inScans = false
        while (index < b.size) {
            if (inScans) {
                // Skip entropy-coded data up to the next marker that is not a stuffed byte or a restart.
                while (index < b.size) {
                    if (b.u8(index) == 0xFF && index + 1 < b.size && b.u8(index + 1) != 0x00 && b.u8(index + 1) !in 0xD0..0xD7 && b.u8(index + 1) != 0xFF) break
                    index++
                }
                if (index >= b.size) return found + "{Truncated}"
            }
            if (b.u8(index) != 0xFF) return found + "{Malformed}"
            var markerIndex = index + 1
            while (markerIndex < b.size && b.u8(markerIndex) == 0xFF) markerIndex++
            if (markerIndex >= b.size) return found + "{Malformed}"
            val marker = b.u8(markerIndex)
            if (marker == 0xD9) {
                if (markerIndex + 1 < b.size) found += "{Trailer}"
                return found
            }
            val segment = markerIndex + 1
            if (segment + 2 > b.size) return found + "{Malformed}"
            val length = (b.u8(segment) shl 8) or b.u8(segment + 1)
            if (length < 2 || segment + length > b.size) return found + "{Malformed}"
            jpegSegmentLabel(marker, b, segment + 2, segment + length)?.let { found += it }
            index = segment + length
            if (marker == 0xDA) inScans = true
        }
        return found + "{Truncated}"
    }

    private fun jpegSegmentLabel(marker: Int, b: ByteArray, start: Int, end: Int): String? = when (marker) {
        0xE0 -> when {
            b.startsWith(start, end, "JFIF\u0000") ->
                if (end - start >= 14 && (b.u8(start + 12) != 0 || b.u8(start + 13) != 0)) "{JFIF}.Thumbnail" else null
            b.startsWith(start, end, "JFXX\u0000") -> "{JFIF}.Thumbnail"
            else -> "{APP0}"
        }
        0xE1 -> when {
            b.startsWith(start, end, "Exif\u0000") -> "{Exif}"
            b.startsWith(start, end, "http://ns.adobe.com/") -> "{XMP}"
            else -> "{APP1}"
        }
        0xE2 -> when {
            b.startsWith(start, end, "ICC_PROFILE\u0000") -> null
            b.startsWith(start, end, "MPF\u0000") -> "{MPF}"
            else -> "{APP2}"
        }
        0xED -> "{IPTC}"
        0xEE -> if (b.startsWith(start, end, "Adobe")) null else "{APP14}"
        in 0xE3..0xEF -> "{APP${marker - 0xE0}}"
        0xFE -> "{Comment}"
        else -> null
    }

    // PNG

    private fun png(b: ByteArray): List<String> {
        val found = mutableListOf<String>()
        val chunks = PngChunks.read(b) ?: return listOf("{Malformed}")
        for (chunk in chunks.list) {
            when {
                chunk.type == "eXIf" -> found += "{Exif}"
                chunk.type !in PngChunks.LAYOUT -> found += "{PNG}.${chunk.type}"
            }
        }
        if (chunks.trailer) found += "{Trailer}"
        return found
    }

    // GIF

    private fun gif(b: ByteArray): List<String> {
        val blocks = GifBlocks.read(b) ?: return listOf("{Malformed}")
        val found = mutableListOf<String>()
        for (block in blocks.list) {
            when (block.kind) {
                GifBlocks.Kind.COMMENT -> found += "{GIF}.Comment"
                GifBlocks.Kind.FOREIGN_APPLICATION -> found += if (block.applicationId?.startsWith("XMP Data") == true) "{XMP}" else "{GIF}.Application"
                GifBlocks.Kind.UNKNOWN_EXTENSION -> found += "{GIF}.Extension"
                else -> Unit
            }
        }
        if (blocks.trailer) found += "{Trailer}"
        return found
    }

    // WebP

    private fun webp(b: ByteArray): List<String> {
        val chunks = WebpChunks.read(b) ?: return listOf("{Malformed}")
        val found = mutableListOf<String>()
        for (chunk in chunks.list) {
            when (chunk.type) {
                "EXIF" -> found += "{Exif}"
                "XMP " -> found += "{XMP}"
                !in WebpChunks.LAYOUT -> found += "{WebP}.${chunk.type.trim()}"
            }
        }
        if (chunks.trailer) found += "{Trailer}"
        return found
    }
}

/**
 * Removes metadata from animated images (and GIF and WebP generally) without decoding them: the
 * platform has no encoder for animated GIF, WebP or PNG, and rewriting a lossy WebP from its pixels
 * would cost quality. What stays is what draws the frames: pixels, palettes, colour profile,
 * frame layout, timing and loop count. `null` for a file that does not parse.
 */
internal object ImageScrubber {
    /** GIF: header, screen descriptor, colour tables, frames, graphic-control and plain-text extensions and the loop count. */
    fun gif(data: ByteArray): ByteArray? {
        val blocks = GifBlocks.read(data) ?: return null
        if (!blocks.complete) return null
        val output = ByteArrayOutputStream(data.size)
        for (block in blocks.list) {
            if (block.kind == GifBlocks.Kind.COMMENT || block.kind == GifBlocks.Kind.FOREIGN_APPLICATION || block.kind == GifBlocks.Kind.UNKNOWN_EXTENSION) continue
            output.write(data, block.start, block.end - block.start)
        }
        return output.toByteArray()
    }

    /** WebP: the image chunks and the colour profile; EXIF and XMP chunks go and their flags are cleared. */
    fun webp(data: ByteArray): ByteArray? {
        val chunks = WebpChunks.read(data) ?: return null
        val kept = chunks.list.filter { it.type in WebpChunks.LAYOUT }
        if (kept.none { it.type == "VP8 " || it.type == "VP8L" || it.type == "ANMF" }) return null
        val body = ByteArrayOutputStream(data.size)
        body.write("WEBP".toByteArray(Charsets.ISO_8859_1))
        for (chunk in kept) {
            val bytes = data.copyOfRange(chunk.start, chunk.end)
            if (chunk.type == "VP8X" && bytes.size > 8) {
                bytes[8] = (bytes[8].toInt() and (VP8X_EXIF or VP8X_XMP).inv()).toByte()
            }
            body.write(bytes)
        }
        val payload = body.toByteArray()
        val output = ByteArrayOutputStream(payload.size + 8)
        output.write("RIFF".toByteArray(Charsets.ISO_8859_1))
        output.write(le32(payload.size))
        output.write(payload)
        return output.toByteArray()
    }

    /** PNG (also animated): the chunks that describe pixels, colour and animation, up to IEND. */
    fun png(data: ByteArray): ByteArray? {
        val chunks = PngChunks.read(data) ?: return null
        if (!chunks.complete) return null
        val output = ByteArrayOutputStream(data.size)
        output.write(data, 0, PngChunks.SIGNATURE_LENGTH)
        for (chunk in chunks.list) {
            if (chunk.type !in PngChunks.LAYOUT) continue
            output.write(data, chunk.start, chunk.end - chunk.start)
        }
        return output.toByteArray()
    }

    /** Whether a PNG is animated (an `acTL` chunk before its image data). */
    fun isAnimatedPng(data: ByteArray): Boolean {
        val chunks = PngChunks.read(data) ?: return false
        for (chunk in chunks.list) {
            if (chunk.type == "IDAT") return false
            if (chunk.type == "acTL") return true
        }
        return false
    }

    private const val VP8X_EXIF = 0x08
    private const val VP8X_XMP = 0x04

    private fun le32(value: Int): ByteArray = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())
}

/** The chunks of a PNG file. */
internal class PngChunks private constructor(val list: List<Chunk>, val complete: Boolean, val trailer: Boolean) {
    /** One chunk from its length field to its CRC ([start] until [end]). */
    class Chunk(val type: String, val start: Int, val end: Int)

    companion object {
        const val SIGNATURE_LENGTH = 8

        /** Chunks that only describe pixels, colour or animation. */
        val LAYOUT = setOf(
            "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "cHRM", "gAMA", "iCCP", "sBIT", "sRGB", "cICP", "mDCV", "cLLI",
            "bKGD", "hIST", "pHYs", "sPLT", "acTL", "fcTL", "fdAT",
        )

        fun read(b: ByteArray): PngChunks? {
            if (b.size < SIGNATURE_LENGTH || ImageFormat.sniff(b.copyOfRange(0, SIGNATURE_LENGTH)) != ImageFormat.PNG) return null
            val list = mutableListOf<Chunk>()
            var index = SIGNATURE_LENGTH
            while (index + 12 <= b.size) {
                val length = (b.u8(index).toLong() shl 24) or (b.u8(index + 1).toLong() shl 16) or (b.u8(index + 2).toLong() shl 8) or b.u8(index + 3).toLong()
                val end = index + 12L + length
                if (end > b.size) return null
                val type = String(b, index + 4, 4, Charsets.ISO_8859_1)
                list += Chunk(type, index, end.toInt())
                index = end.toInt()
                if (type == "IEND") return PngChunks(list, complete = true, trailer = index < b.size)
            }
            return PngChunks(list, complete = false, trailer = false)
        }
    }
}

/** The RIFF chunks of a WebP file. */
internal class WebpChunks private constructor(val list: List<Chunk>, val trailer: Boolean) {
    /** One chunk from its four-character code to its padding ([start] until [end]). */
    class Chunk(val type: String, val start: Int, val end: Int)

    companion object {
        /** Chunks that only hold the image, its alpha, colour profile or animation. */
        val LAYOUT = setOf("VP8 ", "VP8L", "VP8X", "ALPH", "ANIM", "ANMF", "ICCP")
        fun read(b: ByteArray): WebpChunks? {
            if (b.size < 12 || ImageFormat.sniff(b.copyOfRange(0, 12)) != ImageFormat.WEBP) return null
            val riffEnd = 8L + le32(b, 4)
            if (riffEnd > b.size || riffEnd < 12) return null
            val list = mutableListOf<Chunk>()
            var index = 12
            while (index < riffEnd) {
                if (index + 8 > riffEnd) return null
                val size = le32(b, index + 4)
                val end = index + 8L + size + (size and 1L)
                if (end > riffEnd) return null
                list += Chunk(String(b, index, 4, Charsets.ISO_8859_1), index, end.toInt())
                index = end.toInt()
            }
            return WebpChunks(list, trailer = riffEnd < b.size)
        }

        private fun le32(b: ByteArray, at: Int): Long =
            b.u8(at).toLong() or (b.u8(at + 1).toLong() shl 8) or (b.u8(at + 2).toLong() shl 16) or (b.u8(at + 3).toLong() shl 24)
    }
}

/** The blocks of a GIF file. */
internal class GifBlocks private constructor(val list: List<Block>, val complete: Boolean, val trailer: Boolean, val frames: Int) {
    enum class Kind { HEADER, IMAGE, GRAPHIC_CONTROL, PLAIN_TEXT, LOOP, COMMENT, FOREIGN_APPLICATION, UNKNOWN_EXTENSION, END }

    class Block(val kind: Kind, val start: Int, val end: Int, val applicationId: String? = null)

    companion object {
        private val loopApplications = setOf("NETSCAPE2.0", "ANIMEXTS1.0")

        fun read(b: ByteArray): GifBlocks? {
            if (b.size < 13 || ImageFormat.sniff(b.copyOfRange(0, 6)) != ImageFormat.GIF) return null
            val list = mutableListOf<Block>()
            var index = 13
            val packed = b.u8(10)
            if (packed and 0x80 != 0) index += 3 * (1 shl ((packed and 0x07) + 1))
            if (index > b.size) return null
            list += Block(Kind.HEADER, 0, index)
            var frames = 0
            while (index < b.size) {
                val start = index
                when (b.u8(index)) {
                    0x3B -> {
                        list += Block(Kind.END, start, start + 1)
                        return GifBlocks(list, complete = true, trailer = start + 1 < b.size, frames = frames)
                    }
                    0x2C -> {
                        if (index + 10 > b.size) return null
                        val local = b.u8(index + 9)
                        index += 10
                        if (local and 0x80 != 0) index += 3 * (1 shl ((local and 0x07) + 1))
                        index += 1 // LZW minimum code size
                        if (index > b.size) return null
                        index = skipSubBlocks(b, index) ?: return null
                        frames += 1
                        list += Block(Kind.IMAGE, start, index)
                    }
                    0x21 -> {
                        if (index + 2 > b.size) return null
                        val label = b.u8(index + 1)
                        val end = skipSubBlocks(b, index + 2) ?: return null
                        val applicationId = if (label == 0xFF && index + 3 + 11 <= b.size && b.u8(index + 2) == 11) {
                            String(b, index + 3, 11, Charsets.ISO_8859_1)
                        } else {
                            null
                        }
                        val kind = when (label) {
                            0xF9 -> Kind.GRAPHIC_CONTROL
                            0x01 -> Kind.PLAIN_TEXT
                            0xFE -> Kind.COMMENT
                            0xFF -> if (applicationId in loopApplications) Kind.LOOP else Kind.FOREIGN_APPLICATION
                            else -> Kind.UNKNOWN_EXTENSION
                        }
                        list += Block(kind, start, end, applicationId)
                        index = end
                    }
                    else -> return null
                }
            }
            return GifBlocks(list, complete = false, trailer = false, frames = frames)
        }

        /** The index after the data sub-blocks starting at [from] and their terminator, or `null`. */
        private fun skipSubBlocks(b: ByteArray, from: Int): Int? {
            var index = from
            while (true) {
                if (index >= b.size) return null
                val size = b.u8(index)
                index += 1
                if (size == 0) return index
                index += size
            }
        }
    }
}
