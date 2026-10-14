package com.syconx.us2us.runtime.media

import java.io.ByteArrayOutputStream

/**
 * Rebuilds a JPEG from the segments a decoder needs: SOI, quantisation and Huffman tables, the
 * frame header, restart interval, then the scans with the tables between them, up to and
 * including EOI. Kept besides: the JFIF header when it carries no thumbnail and an `Adobe`
 * colour-transform marker. Dropped: ICC profiles (the encoder is given sRGB pixels, which receivers
 * assume without a profile), every other APPn (EXIF and XMP in APP1, IPTC in APP13, maker data,
 * JFXX thumbnails, MPF extra images) and comments, also between scans, and every byte after EOI.
 */
internal object JpegScrubber {
    /** The scrubbed JPEG, or `null` for anything that is not a well-formed JPEG. */
    fun scrub(data: ByteArray): ByteArray? {
        val b = data
        if (b.size < 4 || b.u8(0) != 0xFF || b.u8(1) != 0xD8) return null
        val output = ByteArrayOutputStream(b.size)
        output.write(0xFF)
        output.write(0xD8)
        var index = 2
        while (index < b.size) {
            if (b.u8(index) != 0xFF) return null
            var markerIndex = index + 1
            while (markerIndex < b.size && b.u8(markerIndex) == 0xFF) markerIndex++
            if (markerIndex >= b.size) return null
            val marker = b.u8(markerIndex)
            val segment = markerIndex + 1
            if (marker == 0x00 || marker == 0x01 || marker in 0xD0..0xD9) return null // nothing of these belongs before the scan
            if (segment + 2 > b.size) return null
            val length = (b.u8(segment) shl 8) or b.u8(segment + 1)
            if (length < 2 || segment + length > b.size) return null
            if (marker == 0xDA) {
                output.write(0xFF)
                output.write(b, markerIndex, segment + length - markerIndex)
                return scrubScans(b, segment + length, output)
            }
            if (keepsSegment(marker, b, segment + 2, segment + length)) {
                output.write(0xFF)
                output.write(b, markerIndex, segment + length - markerIndex)
            }
            index = segment + length
        }
        return null
    }

    /**
     * The scans from the entropy-coded data of the first one at [start]: entropy-coded data,
     * restart markers, the tables, restart intervals and headers of further scans are kept; APP
     * segments and comments between scans are dropped; the output ends with EOI, and whatever
     * followed it (camera trailers, appended files) is dropped. `null` without an EOI or for
     * anything else between scans (a second frame).
     */
    private fun scrubScans(b: ByteArray, start: Int, output: ByteArrayOutputStream): ByteArray? {
        var index = start
        var entropyCoded = true
        // Every round moves past at least one byte.
        while (index <= b.size) {
            if (entropyCoded) {
                // In entropy-coded data 0xFF is followed by 0x00 (a stuffed byte), fill bytes, a
                // restart marker or the marker that ends the scan.
                var end = index
                while (true) {
                    if (end >= b.size) return null // no EOI
                    if (b.u8(end) != 0xFF) {
                        end++
                        continue
                    }
                    var next = end + 1
                    while (next < b.size && b.u8(next) == 0xFF) next++
                    if (next >= b.size) return null
                    if (b.u8(next) == 0x00 || b.u8(next) in 0xD0..0xD7) {
                        end = next + 1
                        continue
                    }
                    break
                }
                output.write(b, index, end - index)
                index = end
            } else if (index >= b.size || b.u8(index) != 0xFF) {
                return null
            }
            var markerIndex = index
            while (markerIndex < b.size && b.u8(markerIndex) == 0xFF) markerIndex++ // fill bytes
            if (markerIndex >= b.size) return null
            val marker = b.u8(markerIndex)
            if (marker == 0xD9) {
                output.write(0xFF)
                output.write(0xD9)
                return output.toByteArray()
            }
            val segment = markerIndex + 1
            if (segment + 2 > b.size) return null
            val length = (b.u8(segment) shl 8) or b.u8(segment + 1)
            if (length < 2 || segment + length > b.size) return null
            when (marker) {
                0xC4, 0xCC, 0xDA, 0xDB, 0xDC, 0xDD -> { // DHT, DAC, SOS, DQT, DNL, DRI
                    output.write(0xFF)
                    output.write(b, markerIndex, segment + length - markerIndex)
                }
                in 0xE0..0xEF, 0xFE -> Unit // metadata between scans: dropped
                else -> return null
            }
            index = segment + length
            entropyCoded = marker == 0xDA
        }
        return null
    }

    private fun keepsSegment(marker: Int, b: ByteArray, start: Int, end: Int): Boolean = when (marker) {
        // JFIF: identifier(5) version(2) units(1) densities(4) thumbnail size(2), without a thumbnail.
        0xE0 -> b.startsWith(start, end, "JFIF\u0000") && end - start >= 14 && b.u8(start + 12) == 0 && b.u8(start + 13) == 0
        0xEE -> b.startsWith(start, end, "Adobe")
        in 0xE1..0xED, 0xEF, 0xFE -> false // also ICC profiles (APP2): the pixels are sRGB
        else -> true // tables, frame header, restart interval
    }
}

internal fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

internal fun ByteArray.startsWith(start: Int, end: Int, prefix: String): Boolean {
    if (end - start < prefix.length) return false
    for (i in prefix.indices) if (u8(start + i) != prefix[i].code) return false
    return true
}
