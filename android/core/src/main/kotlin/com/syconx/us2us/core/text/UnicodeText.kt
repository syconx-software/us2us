package com.syconx.us2us.core.text

import java.util.Locale

/**
 * Text rules both apps must apply identically, because their results feed hashes and key
 * derivation (usernames, PROTOCOL.md §3.1; secret codes, §5.2).
 */
internal object UnicodeText {
    /**
     * Locale-independent Unicode default lowercase mapping, applied to each code point on its own:
     * full mappings such as `İ` → `i̇` apply, the context-dependent Final_Sigma rule does not. That
     * is what Swift's `lowercased()` does; Java's [String.lowercase] would turn a final `Σ` into
     * `ς`, so `ΣΣΣΣΣΣΣΣ` would no longer count as one repeated character (§5.2) on Android only.
     */
    fun lowercase(text: String): String {
        if (text.all { it.code < 0x80 }) return text.lowercase(Locale.ROOT)
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            // A lone code point has no casing context, so no conditional mapping can apply.
            out.append(String(Character.toChars(cp)).lowercase(Locale.ROOT))
            index += Character.charCount(cp)
        }
        return out.toString()
    }

    /**
     * Removes leading and trailing characters with the Unicode `White_Space` property. Kotlin's
     * [String.trim] uses Java's notion of whitespace instead, which differs (it keeps U+0085 and
     * strips U+001C..U+001F); Swift trims by `White_Space`, so both platforms use that.
     */
    fun trimWhitespace(text: String): String {
        var start = 0
        var end = text.length
        while (start < end) {
            val cp = text.codePointAt(start)
            if (!isWhiteSpace(cp)) break
            start += Character.charCount(cp)
        }
        while (end > start) {
            val cp = text.codePointBefore(end)
            if (!isWhiteSpace(cp)) break
            end -= Character.charCount(cp)
        }
        return text.substring(start, end)
    }

    /** The `White_Space` property (PropList.txt), stable since Unicode 6.3. */
    private fun isWhiteSpace(cp: Int): Boolean = when (cp) {
        in 0x0009..0x000D, 0x0020, 0x0085, 0x00A0, 0x1680, in 0x2000..0x200A,
        0x2028, 0x2029, 0x202F, 0x205F, 0x3000,
        -> true
        else -> false
    }
}
