package com.syconx.us2us.core.encoding

import kotlin.io.encoding.Base64

/**
 * base64url without padding (RFC 4648 §5), the encoding of every binary value in BRP JSON and
 * headers (PROTOCOL.md §2).
 *
 * Decoding is strict: padding characters, the standard-alphabet characters `+` and `/`, and
 * whitespace are rejected, so that a value has exactly one textual form on every platform.
 */
object Base64Url {
    private val codec = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    fun encode(bytes: ByteArray): String = codec.encode(bytes)

    /** @throws IllegalArgumentException if [text] is not canonical unpadded base64url. */
    fun decode(text: String): ByteArray = codec.decode(text)

    /** Like [decode], but also requires the decoded value to be exactly [length] bytes long. */
    fun decode(text: String, length: Int): ByteArray {
        val bytes = decode(text)
        require(bytes.size == length) { "expected $length bytes, got ${bytes.size}" }
        return bytes
    }
}
