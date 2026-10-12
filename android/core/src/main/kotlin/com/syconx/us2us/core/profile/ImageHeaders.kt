package com.syconx.us2us.core.profile

/**
 * What the header of a profile photo says (PROTOCOL.md §8.9), read without decoding any pixels.
 *
 * [width] and [height] are the largest found in the header (a HEIF file may list one size per
 * image). [hasMetadata] means something beyond the pixels and an ICC colour profile: EXIF, XMP,
 * IPTC, comments, embedded thumbnails, auxiliary or further images, or bytes after the end of a
 * JPEG (camera trailers, appended files). Decoding is the app's job (ImageIO, `ImageDecoder`); this
 * only decides whether to try.
 */
internal class ImageHeader(val width: Int, val height: Int, val hasMetadata: Boolean)

internal object ImageHeaders {
    /** The header of [data] if it is a JPEG file (it starts with `FF D8 FF`), else `null`. */
    fun jpeg(data: ByteArray): ImageHeader? {
        if (data.size < 4 || data.u8(0) != 0xFF || data.u8(1) != 0xD8 || data.u8(2) != 0xFF) return null
        var i = 2
        var width = -1
        var height = -1
        var metadata = false
        // Marker segments up to the first scan; the frame header (SOFn) carries the size. What
        // follows the first scan only decides whether the file is clean (a receiver needs the size
        // alone, so a file damaged there is still shown, and only a sender refuses it).
        while (true) {
            if (i >= data.size || data.u8(i) != 0xFF) return null
            while (i < data.size && data.u8(i) == 0xFF) i++ // fill bytes
            if (i >= data.size) return null
            val marker = data.u8(i++)
            when (marker) {
                0x01, in 0xD0..0xD7 -> continue // no length
                0xD8, 0xD9 -> return null // a second SOI, or EOI before any scan
            }
            if (i + 2 > data.size) return null
            val length = data.u16(i)
            if (length < 2 || i + length > data.size) return null
            val payload = i + 2
            val payloadLength = length - 2
            when {
                marker == SOS -> {
                    if (width <= 0 || height <= 0) return null
                    return ImageHeader(width, height, metadata || !cleanAfterFirstScan(data, i + length))
                }
                isFrameHeader(marker) -> {
                    if (payloadLength < 6 || width >= 0) return null // one frame per file
                    height = data.u16(payload + 1)
                    width = data.u16(payload + 3)
                }
                marker in 0xE0..0xEF || marker == COM -> if (!isAllowedAppSegment(marker, data, payload, payloadLength)) metadata = true
            }
            i += length
        }
    }

    /**
     * The header of [data] if it is an HEIF file with an HEVC brand (`ftyp` with `heic` or `heix`,
     * as major or compatible brand), else `null`. Sizes come from the `ispe` properties of
     * `meta/iprp/ipco`; at least one is required.
     */
    fun heic(data: ByteArray): ImageHeader? {
        val top = boxes(data, 0, data.size) ?: return null
        val ftyp = top.firstOrNull() ?: return null
        if (ftyp.type != "ftyp" || ftyp.payloadLength < 8) return null
        val brands = (0 until (ftyp.payloadLength / 4)).filter { it != 1 } // index 1 is minor_version
            .map { data.fourCC(ftyp.payload + 4 * it) }
        if (brands.none { it == "heic" || it == "heix" }) return null

        val meta = top.singleOrNull { it.type == "meta" } ?: return null
        val metaChildren = fullBoxChildren(data, meta) ?: return null
        val ipco = metaChildren.singleOrNull { it.type == "iprp" }
            ?.let { boxes(data, it.payload, it.end) }
            ?.singleOrNull { it.type == "ipco" }
            ?: return null
        val sizes = (boxes(data, ipco.payload, ipco.end) ?: return null)
            .filter { it.type == "ispe" }
            .map { ispe ->
                if (ispe.payloadLength < 12) return null
                val width = data.u32(ispe.payload + 4)
                val height = data.u32(ispe.payload + 8)
                if (width !in 1..Int.MAX_VALUE.toLong() || height !in 1..Int.MAX_VALUE.toLong()) return null
                width.toInt() to height.toInt()
            }
        if (sizes.isEmpty()) return null

        // Exactly one item, an HEVC-coded image; anything else (Exif, XMP, thumbnails, depth or
        // other auxiliary images, grids) counts as metadata for a sender.
        val items = metaChildren.singleOrNull { it.type == "iinf" }?.let { itemTypes(data, it) }
        val references = metaChildren.any { it.type == "iref" }
        val metadata = items == null || items != listOf("hvc1") || references
        return ImageHeader(sizes.maxOf { it.first }, sizes.maxOf { it.second }, metadata)
    }

    // JPEG

    private const val SOS = 0xDA
    private const val EOI = 0xD9
    private const val COM = 0xFE

    /** What may stand between scans: DHT, DAC, DNL, DQT, DRI and the next scan's header. */
    private val BETWEEN_SCANS = setOf(0xC4, 0xCC, 0xDC, 0xDB, 0xDD, SOS)

    /**
     * Whether the rest of a JPEG, from the entropy-coded data of its first scan at [start], holds
     * only further scans with their tables and restart markers, and ends with EOI as its very last
     * bytes: no APP segment or comment between scans (§8.9; no encoder of pixels writes one
     * there), no second frame or image, nothing appended after EOI.
     */
    private fun cleanAfterFirstScan(data: ByteArray, start: Int): Boolean {
        var i = start
        var entropyCoded = true
        while (true) {
            if (entropyCoded) {
                // In entropy-coded data, 0xFF is followed by 0x00 (a stuffed byte), fill bytes, a
                // restart marker, or the marker that ends the scan.
                while (true) {
                    if (i >= data.size) return false // no EOI
                    if (data.u8(i) != 0xFF) {
                        i++
                        continue
                    }
                    var next = i + 1
                    while (next < data.size && data.u8(next) == 0xFF) next++
                    if (next >= data.size) return false
                    val code = data.u8(next)
                    if (code == 0x00 || code in 0xD0..0xD7) {
                        i = next + 1
                        continue
                    }
                    i = next
                    break
                }
            } else {
                if (i >= data.size || data.u8(i) != 0xFF) return false
                while (i < data.size && data.u8(i) == 0xFF) i++ // fill bytes
                if (i >= data.size) return false
            }
            val marker = data.u8(i++)
            if (marker == EOI) return i == data.size
            if (marker !in BETWEEN_SCANS) return false
            if (i + 2 > data.size) return false
            val length = data.u16(i)
            if (length < 2 || i + length > data.size) return false
            i += length
            entropyCoded = marker == SOS
        }
    }

    /** SOF0..SOF15 except DHT (C4), JPG (C8) and DAC (CC). */
    private fun isFrameHeader(marker: Int) = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC

    /**
     * APP segments an encoder writes for the pixels alone: JFIF without a thumbnail, an ICC profile
     * and Adobe's colour transform. Everything else (Exif, XMP, IPTC, MPF, JFXX thumbnails,
     * comments) is metadata.
     */
    private fun isAllowedAppSegment(marker: Int, data: ByteArray, payload: Int, length: Int): Boolean = when (marker) {
        0xE0 -> data.startsWith(payload, length, "JFIF\u0000") && length >= 14 && data.u8(payload + 12) == 0 && data.u8(payload + 13) == 0
        0xE2 -> data.startsWith(payload, length, "ICC_PROFILE\u0000")
        0xEE -> data.startsWith(payload, length, "Adobe")
        else -> false
    }

    // ISO base media file format (HEIF)

    private class Box(val type: String, val payload: Int, val end: Int) {
        val payloadLength: Int get() = end - payload
    }

    /** The boxes in `[start, end)`, or `null` if they do not tile the range exactly. */
    private fun boxes(data: ByteArray, start: Int, end: Int): List<Box>? {
        if (end > data.size || start < 0) return null
        val result = mutableListOf<Box>()
        var offset = start.toLong()
        while (offset < end) {
            if (offset + 8 > end) return null
            var size = data.u32(offset.toInt())
            val type = data.fourCC(offset.toInt() + 4)
            var header = 8L
            when (size) {
                0L -> size = end - offset // to the end of the enclosing range
                1L -> {
                    if (offset + 16 > end) return null
                    size = data.u64(offset.toInt() + 8) ?: return null
                    header = 16
                }
            }
            if (size < header || offset + size > end) return null
            result += Box(type, (offset + header).toInt(), (offset + size).toInt())
            offset += size
        }
        return result
    }

    /** Children of a full box (version and flags first), such as `meta`. */
    private fun fullBoxChildren(data: ByteArray, box: Box): List<Box>? =
        if (box.payloadLength < 4) null else boxes(data, box.payload + 4, box.end)

    /** The `item_type` of every `infe` (version 2 or 3) in `iinf`, or `null` if malformed. */
    private fun itemTypes(data: ByteArray, iinf: Box): List<String>? {
        if (iinf.payloadLength < 6) return null
        val version = data.u8(iinf.payload)
        val countLength = if (version == 0) 2 else 4
        val entries = boxes(data, iinf.payload + 4 + countLength, iinf.end) ?: return null
        return entries.map { infe ->
            if (infe.type != "infe" || infe.payloadLength < 4) return null
            val idLength = when (data.u8(infe.payload)) {
                2 -> 2
                3 -> 4
                else -> return null // versions 0 and 1 carry no item type
            }
            val typeOffset = infe.payload + 4 + idLength + 2
            if (typeOffset + 4 > infe.end) return null
            data.fourCC(typeOffset)
        }
    }

    // Byte access

    private fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xff
    private fun ByteArray.u16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
    private fun ByteArray.u32(i: Int): Long = (u16(i).toLong() shl 16) or u16(i + 2).toLong()

    /** A 64-bit box size, or `null` if it does not fit a signed long. */
    private fun ByteArray.u64(i: Int): Long? = ((u32(i) shl 32) or u32(i + 4)).takeIf { it >= 0 }

    private fun ByteArray.fourCC(i: Int): String = String(CharArray(4) { (this[i + it].toInt() and 0xff).toChar() })

    private fun ByteArray.startsWith(offset: Int, length: Int, prefix: String): Boolean =
        length >= prefix.length && prefix.indices.all { this[offset + it].toInt() and 0xff == prefix[it].code }
}
