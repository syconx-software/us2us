package com.syconx.us2us.ui.l10n

import java.text.Normalizer
import java.util.Locale

/**
 * Turkish case suffixes for usernames inside sentences, with the typographic apostrophe the design
 * uses. Vowel harmony follows the last vowel *sound*: a final digit is read as its Turkish number
 * word (“kaan2” → “iki”), and a buffer consonant follows a final vowel.
 */
object TurkishSuffix {
    private const val APOSTROPHE = "’"
    private val turkish: Locale = Locale.forLanguageTag("tr-TR")
    private val vowels = setOf('a', 'e', 'ı', 'i', 'o', 'ö', 'u', 'ü')
    private val frontVowels = setOf('e', 'i', 'ö', 'ü')

    /** Digits as spoken: (last vowel, ends with a vowel). */
    private val digits: Map<Int, Pair<Char, Boolean>> = mapOf(
        '0'.code to ('ı' to false), '1'.code to ('i' to false), '2'.code to ('i' to true), '3'.code to ('ü' to false),
        '4'.code to ('ü' to false), '5'.code to ('i' to false), '6'.code to ('ı' to true), '7'.code to ('i' to true),
        '8'.code to ('i' to false), '9'.code to ('u' to false),
    )

    /** The dative (`-e/-a`, `-ye/-ya` after a vowel): “deniz’e”, “ayla_’ya”, “kaan’a”. */
    fun dative(name: String): String {
        val (vowel, endsWithVowel) = sound(name)
        return name + APOSTROPHE + (if (endsWithVowel) "y" else "") + (if (vowel in frontVowels) "e" else "a")
    }

    /**
     * The genitive (`-in/-ın/-un/-ün`, `-nin…` after a vowel): “deniz’in”, “ayla_’nın”,
     * “mert_k’in”, “kaan2’nin”.
     */
    fun genitive(name: String): String {
        val (vowel, endsWithVowel) = sound(name)
        val harmony = when (vowel) {
            'a', 'ı' -> 'ı'
            'o', 'u' -> 'u'
            'ö', 'ü' -> 'ü'
            else -> 'i'
        }
        return name + APOSTROPHE + (if (endsWithVowel) "n" else "") + harmony + "n"
    }

    /** The last vowel heard and whether the name ends in a vowel sound. */
    private fun sound(name: String): Pair<Char, Boolean> {
        val lower = Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(turkish)
        val lastSound = lastCodePoint(lower) { Character.isAlphabetic(it) || isNumber(it) }
        if (lastSound != null) digits[lastSound]?.let { return it }
        val lastVowel = lower.lastOrNull { it in vowels } ?: 'e'
        val endsWithVowel = lastSound != null && lastSound < 0x10000 && lastSound.toChar() in vowels
        return lastVowel to endsWithVowel
    }

    private fun isNumber(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }

    private inline fun lastCodePoint(text: String, predicate: (Int) -> Boolean): Int? {
        var index = text.length
        while (index > 0) {
            val codePoint = text.codePointBefore(index)
            if (predicate(codePoint)) return codePoint
            index -= Character.charCount(codePoint)
        }
        return null
    }
}
