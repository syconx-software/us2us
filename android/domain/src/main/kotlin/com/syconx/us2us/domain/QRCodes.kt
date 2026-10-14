package com.syconx.us2us.domain

// The two QR payloads the app shows and reads. Both are plain text, because the camera hands QR
// contents back as text; nothing scanned is ever stored or logged.

/**
 * The user's contact code (4.1 / 4.2 / 6.2 “Kodumu göster”, the 7.1 profile QR) and what “QR kodla
 * ekle” reads (flows.md › By QR).
 *
 * `us2us:@<username>#<fingerprint>`: the exact username and the identity key's fingerprint
 * (lower-case hex). Scanning it side by side adds the contact *and* verifies it, so the live
 * implementation checks the fingerprint against the key the relay serves; the fixtures accept any.
 * A code without `#…` (older fixtures) still names the user.
 */
object ContactCode {
    const val PREFIX = "us2us:@"

    /** A decoded contact code. */
    data class Decoded(val username: String, val keyFingerprint: String?)

    fun encode(username: String, keyFingerprint: String? = null): String =
        if (keyFingerprint.isNullOrEmpty()) PREFIX + username else "$PREFIX$username#$keyFingerprint"

    /** The username of a us2us contact code, or `null` for anything else. */
    fun username(text: String): String? = decode(text)?.username

    /** Username and fingerprint of a us2us contact code, or `null` for anything else. */
    fun decode(text: String): Decoded? {
        // Swift counts characters; for the ASCII codes this accepts, that is the UTF-16 length.
        if (!text.startsWith(PREFIX) || text.codePointCount(0, text.length) > 512) return null
        val rest = text.removePrefix(PREFIX)
        val hash = rest.indexOf('#')
        // Codes are written with the normalized name: one that is not exactly valid as it stands is not ours.
        val name = if (hash < 0) rest else rest.substring(0, hash)
        if (!UsernameRules.isValid(name)) return null
        val fingerprint = if (hash < 0) null else rest.substring(hash + 1)
        if (fingerprint != null && !fingerprint.all(::isHexDigit)) return null
        return Decoded(name, fingerprint?.takeIf { it.isNotEmpty() })
    }

    private fun isHexDigit(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
}

/**
 * How a safety number travels in its QR code (6.1 card, 6.2 scan).
 *
 * The scannable fingerprint is binary; QR contents come back as text. So the payload is written as
 * `U2SN:` + RFC 4648 base32 (upper case, no padding): entirely in the QR alphanumeric set, which
 * keeps the code small (37 × 37 modules for the fixture's 60 bytes) and unambiguous to recognise:
 * anything without the prefix is not ours.
 */
object SafetyQRCodec {
    const val PREFIX = "U2SN:"
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(payload: Bytes): String = buildString {
        append(PREFIX)
        var buffer = 0
        var bits = 0
        for (value in payload.toUnsignedList()) {
            buffer = (buffer shl 8) or value
            bits += 8
            while (bits >= 5) {
                append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) append(ALPHABET[(buffer shl (5 - bits)) and 31])
    }

    /** The payload of a us2us safety-number code, or `null` for anything else. */
    fun decode(text: String): Bytes? {
        if (!text.startsWith(PREFIX)) return null
        val body = text.removePrefix(PREFIX)
        if (body.isEmpty() || body.codePointCount(0, body.length) > 512) return null
        val out = ArrayList<Int>(body.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (char in body) {
            val value = ALPHABET.indexOf(char)
            if (value < 0) return null
            buffer = ((buffer shl 5) or value) and 0xFFFF
            bits += 5
            if (bits >= 8) {
                out.add((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
            }
        }
        return Bytes.ofUnsigned(out)
    }

    /** Byte equality without an early exit (the time does not reveal where two payloads differ). */
    fun constantTimeEquals(lhs: Bytes, rhs: Bytes): Boolean {
        if (lhs.size != rhs.size) return false
        var difference = 0
        for (index in 0 until lhs.size) difference = difference or (lhs[index].toInt() xor rhs[index].toInt())
        return difference == 0
    }
}
