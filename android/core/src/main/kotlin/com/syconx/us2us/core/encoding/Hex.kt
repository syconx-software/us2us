package com.syconx.us2us.core.encoding

/** Lowercase hexadecimal, as used by the cross-implementation test vectors and slot file names. */
object Hex {
    private val DIGITS = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for ((i, b) in bytes.withIndex()) {
            val v = b.toInt() and 0xff
            out[2 * i] = DIGITS[v ushr 4]
            out[2 * i + 1] = DIGITS[v and 0x0f]
        }
        return String(out)
    }

    /** Accepts upper- and lowercase digits. @throws IllegalArgumentException on malformed input. */
    fun decode(text: String): ByteArray {
        require(text.length % 2 == 0) { "hex string has odd length" }
        return ByteArray(text.length / 2) { i ->
            ((digit(text[2 * i]) shl 4) or digit(text[2 * i + 1])).toByte()
        }
    }

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("invalid hex digit '$c'")
    }
}

fun ByteArray.toHex(): String = Hex.encode(this)
