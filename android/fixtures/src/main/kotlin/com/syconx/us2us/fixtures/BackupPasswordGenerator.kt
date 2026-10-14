package com.syconx.us2us.fixtures

import com.syconx.us2us.domain.BackupPasswordRules
import java.security.SecureRandom

/**
 * Generated backup passwords (PROTOCOL.md §5.5), as the iOS kit's `BackupPassword.generate()`.
 *
 * Temporary: the core has no generator yet. Remove this once `android/core`'s backup password
 * policy gains `generate()`, and call that instead.
 */
internal object BackupPasswordGenerator {
    private val random = SecureRandom()

    /**
     * 20 symbols drawn uniformly from [BackupPasswordRules.GENERATED_ALPHABET], written as five
     * groups of four joined by `-`, like `k7mq-x2pd-9tfa-wn3h-b8ze` (24 characters).
     */
    fun generate(): String = generate { count -> ByteArray(count).also(random::nextBytes) }

    /** [generate] drawing from [randomBytes] (tests). */
    fun generate(randomBytes: (Int) -> ByteArray): String {
        val alphabet = BackupPasswordRules.GENERATED_ALPHABET
        val count = BackupPasswordRules.GENERATED_SYMBOL_COUNT
        // Rejection sampling: only bytes below the largest multiple of the alphabet size (248) are
        // used, so every symbol is exactly equally likely. A plain `byte % 31` would favour the
        // first eight symbols.
        val limit = 256 / alphabet.length * alphabet.length
        val symbols = StringBuilder(count)
        while (symbols.length < count) {
            for (byte in randomBytes(count)) {
                val value = byte.toInt() and 0xFF
                if (value < limit && symbols.length < count) symbols.append(alphabet[value % alphabet.length])
            }
        }
        return buildString {
            symbols.forEachIndexed { index, symbol ->
                if (index > 0 && index % BackupPasswordRules.GENERATED_GROUP_LENGTH == 0) append(BackupPasswordRules.GENERATED_SEPARATOR)
                append(symbol)
            }
        }
    }
}
