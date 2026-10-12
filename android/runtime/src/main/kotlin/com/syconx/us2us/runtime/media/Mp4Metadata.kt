package com.syconx.us2us.runtime.media

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Metadata boxes of an MP4 / QuickTime file, found by walking its box tree without reading the
 * media: user data (`udta`: location `©xyz`, make, model, software, dates), `meta` boxes
 * (`mdta` keys such as `com.android.version` or the capture frame rate, `ilst` items), XMP and
 * other `uuid` boxes, at the top level and inside the movie and its tracks. Layout boxes (`mvhd`,
 * `tkhd`, sample tables) are not metadata. A file that cannot be walked is reported, so a caller
 * that requires an empty list fails closed.
 */
internal object Mp4Metadata {
    private val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex", "moof", "traf")
    private val metadataBoxes = setOf("udta", "meta", "uuid", "ilst", "keys", "XMP_", "©xyz", "loci", "cprt")

    fun problems(file: File): List<String> = try {
        RandomAccessFile(file, "r").use { input ->
            val found = mutableListOf<String>()
            walk(input, 0, input.length(), "", found)
            found
        }
    } catch (_: IOException) {
        listOf("unreadable")
    }

    private fun walk(input: RandomAccessFile, from: Long, to: Long, path: String, found: MutableList<String>) {
        var offset = from
        val header = ByteArray(16)
        while (offset + 8 <= to) {
            input.seek(offset)
            input.readFully(header, 0, 8)
            var size = be32(header, 0)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerLength = 8L
            when (size) {
                1L -> {
                    if (offset + 16 > to) throw IOException("truncated box")
                    input.readFully(header, 8, 8)
                    size = (be32(header, 8) shl 32) or be32(header, 12)
                    headerLength = 16
                }
                0L -> size = to - offset // to the end
            }
            if (size < headerLength || offset + size > to) throw IOException("bad box size")
            val name = if (path.isEmpty()) type else "$path.$type"
            when {
                type in metadataBoxes -> found += name
                type in containers -> walk(input, offset + headerLength, offset + size, name, found)
            }
            offset += size
        }
    }

    private fun be32(b: ByteArray, at: Int): Long =
        (b.u8(at).toLong() shl 24) or (b.u8(at + 1).toLong() shl 16) or (b.u8(at + 2).toLong() shl 8) or b.u8(at + 3).toLong()
}
