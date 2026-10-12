package com.syconx.us2us.core.username

import com.syconx.us2us.core.text.UnicodeText

/**
 * The username rule as the apps show it while the user types: exactly PROTOCOL.md §3.1, with every
 * reason a name breaks it. Shared with the iOS app's `UsernameRules` through
 * `spec/username-rules.json`.
 *
 * Input is never turned into another name: nothing is dropped, cut or converted. A name that is
 * not valid stays as typed and is reported with its [Violation]s; nothing is looked up, hashed or
 * registered for it.
 *
 * Rules are checked per code point of the normalized name, as the iOS app checks them per
 * `Unicode.Scalar`, so both report the same violations for any input: `İ` lowercases to `i`
 * followed by U+0307 and counts as two.
 */
object UsernameRules {
    const val MIN_LENGTH = Username.MIN_LENGTH
    const val MAX_LENGTH = Username.MAX_LENGTH

    /** A rule a normalized name breaks, in the order the apps report them. */
    enum class Violation {
        /** `ı`, `ş`, `ğ`, `ü`, `ö`, `ç`, or `İ`, which lowercases to `i` followed by U+0307. */
        TURKISH_CHARACTERS,

        /** Any other code point outside `a-z`, `0-9` and `_`. */
        CHARACTERS,

        /** The first code point is not a letter `a-z`; a Turkish letter there counts as [TURKISH_CHARACTERS] only. */
        FIRST_LETTER,

        /** Fewer than [MIN_LENGTH] or more than [MAX_LENGTH] code points. */
        LENGTH,
    }

    /** The Turkish letters as §3.1 normalization leaves them; `İ` is `i` + [COMBINING_DOT_ABOVE]. */
    private val TURKISH_LETTERS = setOf('ı'.code, 'ş'.code, 'ğ'.code, 'ü'.code, 'ö'.code, 'ç'.code)
    private const val COMBINING_DOT_ABOVE = 0x0307

    /**
     * What the search field, onboarding and invite links work with: trim Unicode `White_Space`,
     * drop one leading `@`, then §3.1 normalization ([Username.normalize]). For a valid name the
     * result is exactly what is hashed.
     */
    fun normalize(text: String): String = Username.normalize(UnicodeText.trimWhitespace(text).removePrefix("@"))

    /** Every rule [normalized] breaks, in [Violation] order; empty if it is a valid username. */
    fun violations(normalized: String): List<Violation> {
        val codePoints = normalized.codePoints().toArray()
        var turkish = false
        var other = false
        codePoints.forEachIndexed { index, cp ->
            if (cp in TURKISH_LETTERS || (cp == COMBINING_DOT_ABOVE && index > 0 && codePoints[index - 1] == 'i'.code)) {
                turkish = true
            } else if (!isAllowed(cp)) {
                other = true
            }
        }
        return buildList {
            if (turkish) add(Violation.TURKISH_CHARACTERS)
            if (other) add(Violation.CHARACTERS)
            val first = codePoints.firstOrNull()
            if (first != null && !isLetter(first) && first !in TURKISH_LETTERS) add(Violation.FIRST_LETTER)
            if (codePoints.size !in MIN_LENGTH..MAX_LENGTH) add(Violation.LENGTH)
        }
    }

    /** The first rule [normalized] breaks, or `null` if it is a valid username. */
    fun violation(normalized: String): Violation? = violations(normalized).firstOrNull()

    /** Whether [normalized] matches `^[a-z][a-z0-9_]{2,23}$`. */
    fun isValid(normalized: String): Boolean = violations(normalized).isEmpty()

    /** The username typed in [text], or `null` if its [normalize]d form is not valid. */
    fun username(text: String): Username? = normalize(text).takeIf(::isValid)?.let(::Username)

    private fun isLetter(cp: Int) = cp in 'a'.code..'z'.code

    private fun isAllowed(cp: Int) = isLetter(cp) || cp in '0'.code..'9'.code || cp == '_'.code
}
