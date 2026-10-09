package com.syconx.us2us.core.session

/**
 * Padding of the serialized `Content` before session encryption (PROTOCOL.md §5.1): append `0x80`,
 * then `0x00` until the length is a multiple of [BLOCK_SIZE]. A full block is added when the input
 * is already aligned, so padding is always present and unambiguous.
 */
object ContentPadding {
    const val BLOCK_SIZE = 160

    private const val MARKER: Byte = 0x80.toByte()

    fun pad(plaintext: ByteArray): ByteArray {
        val paddedLength = (plaintext.size / BLOCK_SIZE + 1) * BLOCK_SIZE
        return plaintext.copyOf(paddedLength).also { it[plaintext.size] = MARKER }
    }

    /** @throws InvalidPaddingException unless [padded] ends in `0x80 0x00*`. */
    fun unpad(padded: ByteArray): ByteArray {
        var end = padded.size - 1
        while (end >= 0 && padded[end] == 0.toByte()) end--
        if (end < 0 || padded[end] != MARKER) throw InvalidPaddingException()
        return padded.copyOf(end)
    }
}

class InvalidPaddingException : Exception("invalid content padding")
