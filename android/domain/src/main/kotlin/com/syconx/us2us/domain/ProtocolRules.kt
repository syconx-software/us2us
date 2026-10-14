package com.syconx.us2us.domain

import java.time.Instant
import kotlin.math.ln
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

// The few protocol rules and values the screens show, as UI-shaped types. The rules themselves
// live in :core (PROTOCOL.md); the services map the core's answers into these, so the UI modules
// never depend on :core.

/** The delay the user is heading for: after [attemptsLeft] more wrong codes they have to wait [delay] (“2 yanlış daha olursa 1 dakika beklemen gerekir”). */
data class UpcomingDelay(
    /** Wrong codes still possible before the delay, at least 1. */
    val attemptsLeft: Int,
    val delay: Duration,
)

/** What the unlock screen shows before and after an attempt (PROTOCOL §5.2). */
data class UnlockStatus(
    /** Consecutive wrong codes so far. */
    val failures: Int,
    /** When the next attempt is allowed, or `null` for now. */
    val nextAttemptAllowedAt: Instant?,
    /** What further wrong codes will cost. */
    val upcoming: UpcomingDelay?,
) {
    companion object {
        /** No wrong code yet. */
        val Initial = UnlockStatus(failures = 0, nextAttemptAllowedAt = null, upcoming = UnlockThrottle.upcomingDelay(0))
    }
}

/**
 * The wrong-code delays D(N) of PROTOCOL §5.2: none after the 1st and 2nd wrong code, then 1 min,
 * 5 min, 15 min, 30 min and 1 h from the 7th on. The services count with the core's
 * `UnlockAttemptPolicy`; this table is what the screens and previews show.
 */
object UnlockThrottle {
    /** Delay after the n-th consecutive failure, for n = 1, 2, …; the last entry repeats. */
    val delays: List<Duration> = listOf(Duration.ZERO, Duration.ZERO, 1.minutes, 5.minutes, 15.minutes, 30.minutes, 1.hours)

    fun delay(afterFailures: Int): Duration =
        if (afterFailures <= 0) Duration.ZERO else delays[minOf(afterFailures, delays.size) - 1]

    /** What the next wrong codes cost after [afterFailures]; `null` if no further wrong code ever leads to a delay. */
    fun upcomingDelay(afterFailures: Int): UpcomingDelay? {
        val current = maxOf(afterFailures, 0)
        for (next in current + 1..maxOf(current + 1, delays.size)) {
            val delay = delay(next)
            if (delay > Duration.ZERO) return UpcomingDelay(next - current, delay)
        }
        return null
    }

    /** The optional wipe fires on this many wrong codes in a row. */
    const val WIPE_AFTER_FAILURES = 10
}

/** Why a proposed secret code is refused (PROTOCOL §5.2), in the order the rules are checked. */
enum class SecretCodeError {
    /** Not exactly six ASCII digits. */
    NotSixDigits,

    /** Six times the same digit, like `000000`. */
    RepeatedDigit,

    /** A run up or down the digits, also wrapping from 9 to 0: `123456`, `890123`, `654321`. */
    Sequence,

    /** On the bundled list of common codes, like `121212` or `159753`. */
    TooCommon,

    /** The confirmation does not match. */
    Mismatch,
}

/**
 * The biometrics this phone offers for quick unlock (3.1, 7.1). The design draws Face ID; the lock
 * shows the glyph and words of the kind the phone has.
 */
enum class Biometry(val raw: String) {
    /** None enrolled or none at all: the lock opens on the code keypad (3.2). */
    None("none"),

    /** Face recognition (iOS Face ID; the design's 3.1). */
    Face("faceid"),

    /** A fingerprint sensor (iOS Touch ID). */
    Fingerprint("touchid"),
}

/** The secret code's shape (PROTOCOL §5.2). Whether a code is acceptable is the service's [MessengerService.secretCodeProblem]. */
object SecretCodeRules {
    /** Number of digits. */
    const val LENGTH = 6

    /** Whether [code] is exactly six ASCII digits. Nothing is trimmed or normalized. */
    fun isWellFormed(code: String): Boolean = code.length == LENGTH && code.all { it in '0'..'9' }
}

/** Why a proposed backup password is refused (PROTOCOL §5.5), in the order the rules are checked. */
enum class BackupPasswordError {
    /** Fewer than [BackupPasswordRules.MIN_LENGTH] code points after trimming and NFKC. */
    TooShort,

    /** On the bundled list of common passwords. */
    TooCommon,

    /** One character repeated, ignoring case, like `AAAAAaaaaa`. */
    RepeatedCharacter,

    /** A run like `abcdefghij`, `jihgfedcba` or a keyboard row like `poiuytrewq`. */
    SimpleSequence,

    /** The confirmation does not match. */
    Mismatch,
}

/**
 * The backup password's shape (PROTOCOL §5.5) and the generator's format, for the strength meter.
 * Whether a password is acceptable is the service's [MessengerService.backupPasswordProblem]; new
 * passwords come from [MessengerService.generateBackupPassword].
 */
object BackupPasswordRules {
    /** Minimum length in Unicode code points, after trimming and NFKC. */
    const val MIN_LENGTH = 10

    /**
     * The symbols of a generated password: lower-case letters and digits without the look-alikes
     * `0 o 1 l i` (31 symbols).
     */
    const val GENERATED_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

    /** Random symbols in a generated password. */
    const val GENERATED_SYMBOL_COUNT = 20

    /** Symbols per group; groups are joined by [GENERATED_SEPARATOR]. */
    const val GENERATED_GROUP_LENGTH = 4

    /** Joins the groups. It is part of the password. */
    const val GENERATED_SEPARATOR = '-'

    /** The exact strength of a generated password: 20 × log2(31) ≈ 99.08 bits. */
    val generatedEntropyBits: Double = GENERATED_SYMBOL_COUNT * (ln(GENERATED_ALPHABET.length.toDouble()) / ln(2.0))

    /**
     * Whether [password] has the generator's shape: five groups of four symbols of
     * [GENERATED_ALPHABET] joined by `-`. It says nothing about how the symbols were chosen.
     */
    fun isGeneratedFormat(password: String): Boolean {
        val groups = password.split(GENERATED_SEPARATOR)
        return groups.size == GENERATED_SYMBOL_COUNT / GENERATED_GROUP_LENGTH &&
            groups.all { group -> group.length == GENERATED_GROUP_LENGTH && group.all { it in GENERATED_ALPHABET } }
    }
}

/** Limits of the message content the screens enforce while typing (brp.proto, `ContentLimits`). */
object ContentLimits {
    const val MAX_TEXT_BYTES = 8192
    const val MAX_CAPTION_BYTES = 2048
    const val MAX_QUOTE_PREVIEW_CHARACTERS = 200
    const val MAX_THUMBNAIL_BYTES = 10 * 1024
    const val MAX_DISPLAY_NAME_BYTES = 64

    /** brp.proto: “up to 100 amplitude samples”. */
    const val MAX_WAVEFORM_SAMPLES = 100
}
