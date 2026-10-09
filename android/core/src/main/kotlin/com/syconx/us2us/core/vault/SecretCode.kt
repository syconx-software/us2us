package com.syconx.us2us.core.vault

/**
 * Why a proposed secret code is refused, in the order of PROTOCOL.md §5.2 (the first that applies
 * is reported). The app shows a localized explanation for each.
 */
enum class SecretCodeProblem(
    /** The rule's name in PROTOCOL.md §5.2 and `secret-code.json`. */
    val specName: String,
) {
    /** Not exactly [SecretCodePolicy.LENGTH] ASCII digits. */
    FORMAT("format"),

    /** Six times the same digit, e.g. `000000`. */
    REPEATED_DIGIT("repeated"),

    /** A run up or down the digits, also wrapping from 9 to 0: `123456`, `890123`, `654321`. */
    SEQUENCE("sequence"),

    /** On the bundled list of common codes (`spec/common-secret-codes.txt`), e.g. `121212`. */
    COMMON("common"),
}

/** A new secret code breaks a rule of PROTOCOL.md §5.2. */
class InvalidSecretCodeException(val problem: SecretCodeProblem) : Exception("secret code refused: ${problem.specName}")

/**
 * The secret-code rules of PROTOCOL.md §5.2. The secret code is exactly six ASCII digits; it
 * protects all local data together with the device secret and is never sent anywhere. These checks
 * keep users away from codes that are guessed first.
 */
object SecretCodePolicy {
    const val LENGTH = 6

    private val commonCodes: Set<String> by lazy { BundledList.load("common-secret-codes.txt").toSet() }

    /** Whether [code] is exactly six ASCII digits. Nothing is trimmed or normalized. */
    fun isWellFormed(code: String): Boolean = code.length == LENGTH && code.all { it in '0'..'9' }

    /** Returns the first problem with a newly chosen [code], or `null` if it is acceptable. */
    fun check(code: String): SecretCodeProblem? {
        if (!isWellFormed(code)) return SecretCodeProblem.FORMAT
        val digits = code.map { it - '0' }
        if (digits.all { it == digits[0] }) return SecretCodeProblem.REPEATED_DIGIT
        if (isSequence(digits)) return SecretCodeProblem.SEQUENCE
        if (code in commonCodes) return SecretCodeProblem.COMMON
        return null
    }

    /** Throws [InvalidSecretCodeException] if [code] breaks a rule. */
    fun requireValid(code: String) {
        check(code)?.let { throw InvalidSecretCodeException(it) }
    }

    /** Every step between neighbours is +1 modulo 10, or every step is −1 modulo 10. */
    private fun isSequence(digits: List<Int>): Boolean {
        val steps = digits.zipWithNext { a, b -> (b - a + 10) % 10 }.toSet()
        return steps == setOf(1) || steps == setOf(9)
    }

    /** The PBKDF2 password of the vault key: the six ASCII bytes (§5.2). */
    internal fun keyMaterial(code: String): ByteArray {
        require(isWellFormed(code)) { "a secret code is exactly $LENGTH ASCII digits" }
        return code.toByteArray(Charsets.US_ASCII)
    }

    /** The bundled list, for tests. */
    internal fun commonCodes(): Set<String> = commonCodes
}

/** Line lists bundled from `spec/` into this package (one entry per line, `#` comments). */
internal object BundledList {
    fun load(name: String): List<String> {
        val stream = checkNotNull(BundledList::class.java.getResourceAsStream(name)) {
            "$name is missing from the library resources"
        }
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines -> parse(lines.toList()) }
    }

    fun parse(lines: List<String>): List<String> = lines.map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
}
