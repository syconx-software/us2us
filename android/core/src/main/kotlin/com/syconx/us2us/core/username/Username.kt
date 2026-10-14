package com.syconx.us2us.core.username

import com.syconx.us2us.core.crypto.Pbkdf2
import com.syconx.us2us.core.text.UnicodeText
import java.text.Normalizer

/** Why input is not a valid username (PROTOCOL.md §3.1); the same cases as the Swift kit's `UsernameError`. */
enum class UsernameError {
    TOO_SHORT,
    TOO_LONG,
    MUST_START_WITH_LETTER,
    INVALID_CHARACTERS,
}

/**
 * A normalized, valid username (PROTOCOL.md §3).
 *
 * A username is only a findable label for an account id, so it can change at any time (§3.3).
 * Nothing is derived from it but the key of the account's avatar record for search
 * (`AvatarKey`, §8.10), which is made again after every change. The relay only ever sees [hash].
 */
@JvmInline
value class Username(val value: String) {
    init {
        require(PATTERN.matches(value)) { "not a normalized username" }
    }

    /**
     * `PBKDF2-HMAC-SHA256(UTF-8(username), "BRP/username/v1", 100000, 32)` (§3.2).
     *
     * Deliberately slow (tens of milliseconds); call it off the main thread and cache the result.
     */
    fun hash(): ByteArray = Pbkdf2.hmacSha256(value.toByteArray(Charsets.UTF_8), HASH_SALT, HASH_ITERATIONS, HASH_LENGTH)

    override fun toString(): String = value

    companion object {
        const val MIN_LENGTH = 3
        const val MAX_LENGTH = 24
        const val HASH_ITERATIONS = 100_000
        const val HASH_LENGTH = 32

        private val PATTERN = Regex("^[a-z][a-z0-9_]{${MIN_LENGTH - 1},${MAX_LENGTH - 1}}$")
        private val HASH_SALT = "BRP/username/v1".toByteArray(Charsets.UTF_8)

        /**
         * Applies §3.1 normalization: trim Unicode `White_Space`, NFKC, locale-independent
         * lowercase. The result is not necessarily valid; see [parse].
         */
        fun normalize(input: String): String =
            UnicodeText.lowercase(Normalizer.normalize(UnicodeText.trimWhitespace(input), Normalizer.Form.NFKC))

        /** Normalizes user input and returns the username, or `null` if it is not valid. */
        fun parse(input: String): Username? = normalize(input).takeIf { validate(it) == null }?.let(::Username)

        /** Normalizes user input and returns why it is not a valid username, or `null` if it is one. */
        fun check(input: String): UsernameError? = validate(normalize(input))

        /**
         * Checks `^[a-z][a-z0-9_]{2,23}$` on an already normalized string, in the Swift kit's order
         * so both apps give the same reason. Characters come first, so `ş` reports invalid
         * characters rather than a misleading length.
         */
        private fun validate(normalized: String): UsernameError? = when {
            !normalized.all { it in 'a'..'z' || it in '0'..'9' || it == '_' } -> UsernameError.INVALID_CHARACTERS
            normalized.length < MIN_LENGTH -> UsernameError.TOO_SHORT
            normalized.length > MAX_LENGTH -> UsernameError.TOO_LONG
            normalized[0] !in 'a'..'z' -> UsernameError.MUST_START_WITH_LETTER
            else -> null
        }
    }
}
