package com.syconx.us2us.domain

import java.text.Normalizer
import java.util.Locale

/**
 * Username rules, the same on every screen and on every platform: `docs/PROTOCOL.md` §3.1. A name
 * is valid when its normalized form matches `^[a-z][a-z0-9_]{2,23}$`: 3–24 characters, a letter
 * a–z first, then a–z, 0–9 and the underscore; no dot. The cases both apps test against are
 * `spec/username-rules.json`.
 *
 * What was typed is never turned into another name: nothing is dropped, cut or converted (“Kağan”
 * stays “kağan” and is refused, it does not become “kaan”). An invalid name is shown as typed with
 * the rules it breaks, and is never looked up, hashed or registered.
 *
 * Rules are checked per code point of the normalized name, as the iOS app checks them per
 * `Unicode.Scalar`, so both report the same violations for any input.
 */
object UsernameRules {
    const val MIN_LENGTH = 3
    const val MAX_LENGTH = 24

    /** A rule a name breaks, in the order they are reported ([entries]). */
    enum class Violation(val raw: String) {
        /** “Türkçe karakter yok: ı, İ, ş, ğ, ü, ö, ç”. */
        TurkishCharacters("turkishCharacters"),

        /** “Küçük harf, rakam ve alt çizgi”: any other character. */
        Characters("characters"),

        /** “Harfle başlar”. */
        FirstLetter("firstLetter"),

        /** “3–24 karakter”. */
        Length("length"),
    }

    /** The Turkish letters as normalization leaves them (lower case; “İ” becomes “i” + U+0307, see [violations]). */
    private val turkishLetters = setOf('ı'.code, 'ş'.code, 'ğ'.code, 'ü'.code, 'ö'.code, 'ç'.code)
    private const val COMBINING_DOT_ABOVE = 0x0307

    /**
     * What typed text stands for: Unicode white space around it and one leading “@” dropped, then
     * the protocol's own normalization (§3.1: white space, NFKC, lower case without a locale, so “I”
     * is “i”, never “ı”). Byte for byte what the core hashes for a valid name.
     */
    fun normalize(input: String): String {
        val trimmed = UnicodeText.trimLeadingWhitespace(input)
        if (trimmed.isEmpty()) return ""
        return protocolNormalize(trimmed.removePrefix("@"))
    }

    /**
     * How the 2.1 field shows what is typed: NFKC and lower case, as the name will be. Nothing is
     * trimmed, dropped or cut. When that would stand for another name (a fullwidth “＠” becomes an
     * “@”, which is then dropped; a spacing accent becomes a space and an accent) the text is shown
     * as typed.
     */
    fun displayForm(typed: String): String {
        val shown = UnicodeText.lowercase(Normalizer.normalize(typed, Normalizer.Form.NFKC))
        return if (normalize(shown) == normalize(typed)) shown else typed
    }

    /** The length the rules count (and the 2.1 counter shows): code points of the normalized name. */
    fun length(name: String): Int = name.codePointCount(0, name.length)

    /** Every rule [name] (already normalized, see [normalize]) breaks, in the order of [Violation.entries]; empty when it is valid. */
    fun violations(name: String): List<Violation> {
        val codePoints = name.codePoints().toArray()
        var turkish = false
        var other = false
        codePoints.forEachIndexed { index, cp ->
            if (cp in turkishLetters || (cp == COMBINING_DOT_ABOVE && index > 0 && codePoints[index - 1] == 'i'.code)) {
                turkish = true
            } else if (!isAllowed(cp)) {
                other = true
            }
        }
        return buildList {
            if (turkish) add(Violation.TurkishCharacters)
            if (other) add(Violation.Characters)
            val first = codePoints.firstOrNull()
            if (first != null && !isLetter(first) && first !in turkishLetters) add(Violation.FirstLetter)
            if (codePoints.size !in MIN_LENGTH..MAX_LENGTH) add(Violation.Length)
        }
    }

    /** The first rule [name] breaks, or `null` when it is valid. */
    fun violation(name: String): Violation? = violations(name).firstOrNull()

    /** Whether [name], exactly as given, is a valid normalized username. */
    fun isValid(name: String): Boolean = violations(name).isEmpty()

    /** Whether [name] is too short: said only once typing pauses. */
    fun isTooShort(name: String): Boolean = length(name) < MIN_LENGTH

    /** §3.1: trim Unicode `White_Space`, NFKC, locale-independent lower case. */
    private fun protocolNormalize(input: String): String =
        UnicodeText.lowercase(Normalizer.normalize(UnicodeText.trimWhitespace(input), Normalizer.Form.NFKC))

    private fun isLetter(cp: Int) = cp in 'a'.code..'z'.code

    private fun isAllowed(cp: Int) = isLetter(cp) || cp in '0'.code..'9'.code || cp == '_'.code
}

/** Text rules both apps apply identically (the same as the core's, which feed hashes and key derivation). */
internal object UnicodeText {
    /**
     * Locale-independent Unicode default lower-case mapping, applied to each code point on its own:
     * full mappings such as `İ` → `i̇` apply, the context-dependent Final_Sigma rule does not. That
     * is what Swift's `lowercased()` does.
     */
    fun lowercase(text: String): String {
        if (text.all { it.code < 0x80 }) return text.lowercase(Locale.ROOT)
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            out.append(String(Character.toChars(cp)).lowercase(Locale.ROOT))
            index += Character.charCount(cp)
        }
        return out.toString()
    }

    /** Removes leading and trailing characters with the Unicode `White_Space` property (Swift trims by it; Kotlin's `trim` does not). */
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

    fun trimLeadingWhitespace(text: String): String {
        var start = 0
        while (start < text.length) {
            val cp = text.codePointAt(start)
            if (!isWhiteSpace(cp)) break
            start += Character.charCount(cp)
        }
        return text.substring(start)
    }

    /** The `White_Space` property (PropList.txt), stable since Unicode 6.3. */
    fun isWhiteSpace(cp: Int): Boolean = when (cp) {
        in 0x0009..0x000D, 0x0020, 0x0085, 0x00A0, 0x1680, in 0x2000..0x200A,
        0x2028, 0x2029, 0x202F, 0x205F, 0x3000,
        -> true
        else -> false
    }
}
