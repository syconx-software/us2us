package com.syconx.us2us.core.profile

import com.google.protobuf.kotlin.toByteString
import com.syconx.us2us.core.proto.ProfileAvatar
import com.syconx.us2us.core.proto.avatarPhoto
import com.syconx.us2us.core.proto.profileAvatar
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Image files for the profile picture tests: real JPEGs from ImageIO and hand-built HEIF headers. */
object TestImages {
    /** A JPEG of [width] × [height] pixels as an encoder writes it from pixels alone (JFIF, no thumbnail). */
    fun jpeg(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, (x * 7 + y * 13) and 0xffffff)
        return ByteArrayOutputStream().also { check(ImageIO.write(image, "jpg", it)) }.toByteArray()
    }

    /**
     * `ProfileAvatar{photo: AvatarPhoto{content_type: "image/jpeg", data}}` whose serialization is
     * exactly [size] bytes, `data` being the deterministic filler `(i * 31 + 7) mod 256`. Only the
     * record codec sees it; it is no image.
     */
    fun photoAvatarOfSize(size: Int): ProfileAvatar {
        var dataSize = size
        repeat(8) {
            val avatar = profileAvatar {
                photo = avatarPhoto {
                    contentType = "image/jpeg"
                    data = ByteArray(dataSize) { i -> (i * 31 + 7).toByte() }.toByteString()
                }
            }
            if (avatar.serializedSize == size) return avatar
            dataSize -= avatar.serializedSize - size
        }
        throw IllegalArgumentException("no photo ProfileAvatar is exactly $size bytes")
    }

    /** [jpeg] with an APP segment inserted right after SOI: [marker] `0xE1` with "Exif\0\0" is EXIF. */
    fun withSegment(jpeg: ByteArray, marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        val segment = byteArrayOf(0xFF.toByte(), marker.toByte(), (length ushr 8).toByte(), length.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
    }

    val EXIF: ByteArray = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(20)

    /** [jpeg] grown to [size] bytes by filler in the entropy-coded data, before EOI (0x55 holds no marker). */
    fun padded(jpeg: ByteArray, size: Int): ByteArray =
        jpeg.copyOf(jpeg.size - 2) + ByteArray(size - jpeg.size) { 0x55 } + jpeg.copyOfRange(jpeg.size - 2, jpeg.size)

    /** [jpeg] with a segment inserted right before EOI, i.e. after the (last) scan. */
    fun withSegmentAfterScan(jpeg: ByteArray, marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        val segment = byteArrayOf(0xFF.toByte(), marker.toByte(), (length ushr 8).toByte(), length.toByte()) + payload
        return jpeg.copyOf(jpeg.size - 2) + segment + jpeg.copyOfRange(jpeg.size - 2, jpeg.size)
    }

    /**
     * The boxes of a HEIF file that the header check reads: `ftyp` with [brands] (the first is the
     * major brand), `meta` with an `iinf` listing [items] by type, an `iprp/ipco` with one `ispe`
     * per entry of [sizes], an `iref` if [reference] is set, and an `mdat` with filler.
     */
    fun heic(
        sizes: List<Pair<Int, Int>> = listOf(256 to 256),
        items: List<String> = listOf("hvc1"),
        brands: List<String> = listOf("heic", "mif1", "heic"),
        reference: Boolean = false,
    ): ByteArray {
        val ftyp = box("ftyp", ascii(brands[0]) + u32(0) + brands.drop(1).fold(ByteArray(0)) { acc, b -> acc + ascii(b) })
        val hdlr = fullBox("hdlr", 0, u32(0) + ascii("pict") + ByteArray(12) + byteArrayOf(0))
        val infes = items.mapIndexed { i, type -> fullBox("infe", 2, u16(i + 1) + u16(0) + ascii(type) + byteArrayOf(0)) }
        val iinf = fullBox("iinf", 0, u16(items.size) + infes.fold(ByteArray(0)) { acc, b -> acc + b })
        val ispes = sizes.map { (w, h) -> fullBox("ispe", 0, u32(w) + u32(h)) }
        val iprp = box("iprp", box("ipco", ispes.fold(ByteArray(0)) { acc, b -> acc + b }))
        val iref = if (reference) fullBox("iref", 0, box("thmb", u16(2) + u16(1) + u16(1))) else ByteArray(0)
        val meta = fullBox("meta", 0, hdlr + fullBox("pitm", 0, u16(1)) + iinf + iprp + iref)
        return ftyp + meta + box("mdat", ByteArray(64) { it.toByte() })
    }

    fun box(type: String, payload: ByteArray): ByteArray = u32(8 + payload.size) + ascii(type) + payload

    private fun fullBox(type: String, version: Int, payload: ByteArray): ByteArray = box(type, byteArrayOf(version.toByte(), 0, 0, 0) + payload)

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private fun u16(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())

    private fun u32(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
}
