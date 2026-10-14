package com.syconx.us2us.feature.settings

import androidx.compose.runtime.Immutable
import com.syconx.us2us.domain.BackupPasswordRules
import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * A local estimate of how many guesses a backup password takes (7.2). Nothing leaves the device;
 * nothing is stored.
 *
 * A backup file can be attacked offline without limit (PROTOCOL.md §5.5), so what counts is what a
 * guesser tries first, not which kinds of characters appear. The password is split into the pieces
 * that are cheapest to guess, and their costs add up:
 *
 * - a listed word ([PasswordStrengthWords]: first names, surnames, places, clubs and everyday words,
 *   Turkish and English): 11 bits, or 6 for the very first guesses (`parola`, `sifre`, `qwerty`…),
 *   plus about a bit for capitals and one per leet substitution (`s1fr3`). Turkish letters are
 *   folded to ASCII first, so `şifre` is `sifre`;
 * - a year from 1900 to 2039: 7.1 bits; a six- or eight-digit date: about 15.5;
 * - a run (`abcd`, `4321`) or a keyboard walk (`qwer`, `1q2w3e`, `zaq1`);
 * - a repeat of something earlier (the second `2026` in `kedi20262026`), or a separator symbol used
 *   before (the second `.` in `Elif.Kaya.1990`);
 * - any other character: `log2` of the character pool actually used (26 lower-case, 26 upper-case,
 *   10 digits, 33 symbols, 40 for other letters such as `ğ`).
 *
 * So “name + year + symbol” stays weak whatever its length, while random characters and passphrases
 * of several words count in full. Digits alone count at most `log2(10)` per digit, so a six-digit code
 * is always weak, which also keeps the secret code itself out of the backup password. A password with
 * the generator's shape never counts more than the generator's 99.1 bits; the one this screen
 * generated counts exactly that ([Generated]).
 */
@Immutable
data class PasswordStrength(
    /** Estimated entropy in bits: `log2` of the guesses needed. */
    val bits: Double,
) {
    enum class Level {
        /** Zayıf: lit dots `danger`; export is refused. */
        Weak,

        /** Orta: `accent400`; the minimum for exporting. */
        Fair,

        /** Güçlü: `online`. */
        Strong,

        /** Çok güçlü: `online`. */
        VeryStrong,
    }

    val level: Level
        get() = when {
            bits < 50 -> Level.Weak
            bits < 60 -> Level.Fair
            bits < 80 -> Level.Strong
            else -> Level.VeryStrong
        }

    /**
     * The lit share of the dotted meter: 88.3 bits fill it, so 14 random lower-case letters and digits
     * (72.4 bits) light 82 %: exactly the 32 dots drawn in 7.2.
     */
    val meterFraction: Double get() = min(1.0, max(0.0, bits / 88.3))

    /**
     * The minimum for exporting: 50 bits (“Orta”). Even at the slowest guessing a backup file allows
     * (PBKDF2, 1,000,000 iterations), that is decades of GPU time; names and years are far below it.
     */
    val isAcceptable: Boolean get() = level >= Level.Fair

    companion object {
        val Empty = PasswordStrength(0.0)

        /** A password from the generator: exactly 20 × log2(31) bits. */
        val Generated = PasswordStrength(BackupPasswordRules.generatedEntropyBits)

        fun estimate(password: String): PasswordStrength {
            if (password.isEmpty()) return Empty
            val scalars = Normalizer.normalize(password, Normalizer.Form.NFC).codePoints().toArray()
            val classes = CharacterClasses(scalars)
            val characterBits = log2(max(classes.pool, 2).toDouble())
            val analysed = scalars.copyOf(min(scalars.size, MAX_ANALYSED_LENGTH))
            var bits = cheapestCover(analysed, characterBits)
            bits += (scalars.size - analysed.size) * characterBits
            if (classes.isDigitsOnly) {
                bits = min(bits, scalars.size * log2(10.0))
            }
            if (BackupPasswordRules.isGeneratedFormat(password)) {
                bits = min(bits, BackupPasswordRules.generatedEntropyBits)
            }
            return PasswordStrength(max(0.0, bits))
        }

        // MARK: Cheapest split

        /** Characters past this point are counted one by one (such a password is far beyond every threshold anyway). */
        private const val MAX_ANALYSED_LENGTH = 128
        private const val MIN_PATTERN_LENGTH = 3
        private const val MAX_PATTERN_LENGTH = 32

        private const val TOP_WORD_BITS = 6.0
        private const val COMMON_WORD_BITS = 11.0

        /** 1900–2039. */
        private val yearBits = log2(140.0)

        /** Day, month and a two-digit year. */
        private val shortDateBits = log2(31.0 * 12 * 100)

        /** Day, month and a year from 1900 to 2039. */
        private val longDateBits = log2(31.0 * 12 * 140)
        private const val SEPARATOR_REPEAT_BITS = 2.0
        private const val KEYBOARD_KEYS = 47.0

        /** The cheapest split of [scalars] into pieces, in bits (shortest path over the positions: each piece is a pattern or one character). */
        private fun cheapestCover(scalars: IntArray, characterBits: Double): Double {
            val count = scalars.size
            if (count == 0) return 0.0
            val lower = IntArray(count) { lowered(scalars[it]) }
            val folded = IntArray(count) { asciiFolding[lower[it]] ?: lower[it] }
            val repeats = earlierRepeatLengths(lower)
            val best = DoubleArray(count + 1) { Double.POSITIVE_INFINITY }
            best[0] = 0.0
            for (end in 1..count) {
                val last = end - 1
                var cost = best[last] + characterBits
                if (CharacterClasses.isSymbol(scalars[last]) && (0 until last).any { scalars[it] == scalars[last] }) {
                    cost = min(cost, best[last] + SEPARATOR_REPEAT_BITS)
                }
                val earliest = max(0, end - MAX_PATTERN_LENGTH)
                if (end - earliest >= MIN_PATTERN_LENGTH) {
                    for (start in earliest..(end - MIN_PATTERN_LENGTH)) {
                        val pattern = patternBits(
                            scalars.copyOfRange(start, end),
                            lower.copyOfRange(start, end),
                            folded.copyOfRange(start, end),
                            repeats[start],
                        )
                        if (pattern != null) cost = min(cost, best[start] + pattern)
                    }
                }
                best[end] = cost
            }
            return best[count]
        }

        /** The cheapest reading of one piece as a pattern, if it is one. */
        private fun patternBits(original: IntArray, lower: IntArray, folded: IntArray, repeatLength: Int): Double? {
            val candidates = mutableListOf(listedWordBits(original, folded), numberBits(original), runBits(lower), keyboardBits(lower))
            if (repeatLength >= original.size) candidates += 1 + log2(original.size.toDouble())
            return candidates.filterNotNull().minOrNull()
        }

        // MARK: Words

        private val longestListedWord: Int by lazy {
            (PasswordStrengthWords.topWords + PasswordStrengthWords.commonWords).maxOf { it.length }
        }

        private fun listedWordBits(original: IntArray, folded: IntArray): Double? {
            if (folded.size > longestListedWord) return null
            var cheapest: Double? = null
            for ((word, substitutions) in spellings(folded)) {
                val base = when (word) {
                    in PasswordStrengthWords.topWords -> TOP_WORD_BITS
                    in PasswordStrengthWords.commonWords -> COMMON_WORD_BITS
                    else -> continue
                }
                val bits = base + capitalizationBits(original) + substitutions
                cheapest = min(cheapest ?: bits, bits)
            }
            return cheapest
        }

        /** The piece as written and, if it has any, with its leet characters read as letters (`1` once as `i` and once as `l`). */
        private fun spellings(folded: IntArray): List<Pair<String, Int>> {
            val spellings = mutableListOf(text(folded) to 0)
            if (folded.none { it in leet }) return spellings
            for (one in intArrayOf('i'.code, 'l'.code)) {
                var substitutions = 0
                val word = IntArray(folded.size) { index ->
                    val scalar = folded[index]
                    val letter = leet[scalar]
                    if (letter != null) {
                        substitutions += 1
                        if (scalar == '1'.code) one else letter
                    } else {
                        scalar
                    }
                }
                spellings += text(word) to substitutions
            }
            return spellings
        }

        private val leet: Map<Int, Int> = mapOf(
            '0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '@' to 'a', '$' to 's', '!' to 'i',
        ).entries.associate { it.key.code to it.value.code }

        /** Capitalised or all capitals: 1 bit; any other mix: the ways to place that many capitals. */
        private fun capitalizationBits(scalars: IntArray): Double {
            val letters = scalars.filter { Character.isAlphabetic(it) }
            val upper = letters.count { Character.isUpperCase(it) }
            val lower = letters.size - upper
            if (upper == 0) return 0.0
            if (lower == 0 || (upper == 1 && Character.isUpperCase(letters.first()))) return 1.0
            var ways = 0.0
            for (capitals in 1..min(upper, lower)) {
                ways += binomial(letters.size, capitals)
            }
            return log2(max(ways, 2.0))
        }

        private fun binomial(n: Int, k: Int): Double {
            var result = 1.0
            for (index in 0 until k) {
                result = result * (n - index) / (index + 1)
            }
            return result
        }

        // MARK: Years and dates

        private fun numberBits(scalars: IntArray): Double? {
            if (scalars.size !in setOf(4, 6, 8) || scalars.any { it !in 0x30..0x39 }) return null
            val digits = scalars.map { it - 0x30 }
            fun number(from: Int, until: Int): Int = (from until until).fold(0) { value, index -> value * 10 + digits[index] }
            fun isDate(day: Int, month: Int): Boolean = day in 1..31 && month in 1..12
            val years = 1900..2039
            return when (digits.size) {
                4 -> if (number(0, 4) in years) yearBits else null
                6 -> {
                    // ddmmyy, yymmdd, mmddyy
                    val isDateLike = isDate(number(0, 2), number(2, 4)) || isDate(number(4, 6), number(2, 4)) ||
                        isDate(number(2, 4), number(0, 2))
                    if (isDateLike) shortDateBits else null
                }
                else -> {
                    // ddmmyyyy, yyyymmdd, mmddyyyy
                    val isDateLike = (isDate(number(0, 2), number(2, 4)) && number(4, 8) in years) ||
                        (isDate(number(6, 8), number(4, 6)) && number(0, 4) in years) ||
                        (isDate(number(2, 4), number(0, 2)) && number(4, 8) in years)
                    if (isDateLike) longDateBits else null
                }
            }
        }

        // MARK: Runs and keyboard walks

        /** `abcd`, `dcba`, `1234`, `4321`. */
        private fun runBits(scalars: IntArray): Double? {
            val isDigits = scalars.all { it in 0x30..0x39 }
            val isLetters = scalars.all { it in 0x61..0x7A }
            if (!isDigits && !isLetters) return null
            val steps = (1 until scalars.size).map { scalars[it] - scalars[it - 1] }
            val isDescending = when {
                steps.all { it == 1 } -> false
                steps.all { it == -1 } -> true
                else -> return null
            }
            return log2(if (isDigits) 10.0 else 26.0) + log2(scalars.size.toDouble()) + (if (isDescending) 1 else 0)
        }

        /** A contiguous stretch of a keyboard row or walk, in either direction. */
        private fun keyboardBits(scalars: IntArray): Double? {
            if (scalars.any { it !in keyboardKeySet }) return null
            val forward = scalars.toList()
            val backward = forward.reversed()
            val isWalk = keyboardWalks.any { walk -> isStretch(forward, walk) || isStretch(backward, walk) }
            return if (isWalk) log2(KEYBOARD_KEYS) + log2(forward.size.toDouble()) + 1 else null
        }

        private fun isStretch(needle: List<Int>, walk: List<Int>): Boolean {
            if (needle.size > walk.size) return false
            return (0..(walk.size - needle.size)).any { start -> walk.subList(start, start + needle.size) == needle }
        }

        /** The rows of the Q (US and Turkish) and Turkish F layouts, and the usual walks across them. */
        private val keyboardWalks: List<List<Int>> = listOf(
            "1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm",
            "qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç", "fgğıodrnhpqw", "uieaütkmlyşx", "jövcçzsb",
            "1q2w3e4r5t6y7u8i9o0p", "q1w2e3r4t5y6u7i8o9p0", "1qaz2wsx3edc4rfv5tgb6yhn7ujm8ik9ol0p",
            "zaq1xsw2cde3vfr4bgt5nhy6mju7", "qazwsxedcrfvtgbyhnujmikolp",
        ).map { it.codePoints().toArray().toList() }

        private val keyboardKeySet: Set<Int> = keyboardWalks.flatten().toSet()

        // MARK: Repeats

        /** For each position, the longest stretch starting there that also starts at an earlier position. */
        private fun earlierRepeatLengths(scalars: IntArray): IntArray {
            val count = scalars.size
            val width = count + 1
            // common[a * width + i]: the common prefix of the stretches at a and i.
            val common = IntArray(width * width)
            val longest = IntArray(count)
            for (a in count - 1 downTo 0) {
                for (i in count - 1 downTo a + 1) {
                    if (scalars[a] != scalars[i]) continue
                    val length = common[(a + 1) * width + i + 1] + 1
                    common[a * width + i] = length
                    longest[i] = max(longest[i], length)
                }
            }
            return longest
        }

        // MARK: Case and Turkish letters

        private fun lowered(scalar: Int): Int = when (scalar) {
            in 0x41..0x5A -> scalar + 0x20
            0x130 -> 'i'.code // İ
            else -> {
                val mapping = String(Character.toChars(scalar)).lowercase(Locale.ROOT)
                if (mapping.codePointCount(0, mapping.length) == 1) mapping.codePointAt(0) else scalar
            }
        }

        private val asciiFolding: Map<Int, Int> = mapOf(
            'ç' to 'c', 'ğ' to 'g', 'ı' to 'i', 'ö' to 'o', 'ş' to 's', 'ü' to 'u', 'â' to 'a', 'î' to 'i', 'û' to 'u',
        ).entries.associate { it.key.code to it.value.code }

        private fun text(scalars: IntArray): String = String(scalars, 0, scalars.size)

        private fun log2(value: Double): Double = ln(value) / ln(2.0)
    }
}

// MARK: - Character classes

private class CharacterClasses(scalars: IntArray) {
    var lower = false
    var upper = false
    var digit = false
    var symbol = false
    var other = false

    init {
        for (scalar in scalars) {
            when {
                scalar in 0x61..0x7A -> lower = true
                scalar in 0x41..0x5A -> upper = true
                scalar in 0x30..0x39 -> digit = true
                isSymbol(scalar) -> symbol = true
                else -> other = true
            }
        }
    }

    val pool: Int get() = (if (lower) 26 else 0) + (if (upper) 26 else 0) + (if (digit) 10 else 0) + (if (symbol) 33 else 0) + (if (other) 40 else 0)

    val isDigitsOnly: Boolean get() = digit && !lower && !upper && !symbol && !other

    companion object {
        fun isSymbol(scalar: Int): Boolean =
            scalar in 0x20..0x2F || scalar in 0x3A..0x40 || scalar in 0x5B..0x60 || scalar in 0x7B..0x7E
    }
}
