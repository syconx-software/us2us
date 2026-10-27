package com.syconx.us2us.core.vault

import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Time since the phone booted, including deep sleep: a clock the user cannot set, which the
 * wrong-attempt delays are measured with (PROTOCOL.md §5.2). It restarts from zero when the phone
 * restarts. The Android app passes `{ SystemClock.elapsedRealtimeNanos().nanoseconds }`.
 */
fun interface BootClock {
    fun sinceBoot(): Duration

    companion object {
        /**
         * `System.nanoTime()`: `CLOCK_MONOTONIC` on Linux and Android, i.e. time since boot but
         * without deep sleep, so delays only last longer. Apps on Android use `elapsedRealtime`.
         */
        val SYSTEM = BootClock { System.nanoTime().nanoseconds }
    }
}

/**
 * The consecutive wrong secret codes so far and how long the user has waited since the last one
 * (PROTOCOL.md §5.2).
 *
 * @property waited time waited since the last failure, measured with a [BootClock]: a lower bound
 *   of the real time that never decreases.
 * @property bootClockReading the [BootClock] reading [waited] was last brought up to date with.
 */
data class UnlockAttempts(
    val failures: Int = 0,
    val waited: Duration = Duration.ZERO,
    val bootClockReading: Duration? = null,
) {
    init {
        require(failures >= 0) { "failures must not be negative" }
    }

    /**
     * Brings [waited] up to [reading]. A reading below the stored one means the phone restarted,
     * and only the time since boot counts.
     */
    fun observed(reading: Duration): UnlockAttempts {
        val last = bootClockReading ?: return copy(bootClockReading = reading)
        return copy(waited = waited + if (reading >= last) reading - last else reading, bootClockReading = reading)
    }

    /** How much longer the user has to wait before the next attempt; zero if it is allowed now. */
    fun remainingDelay(): Duration = (UnlockAttemptPolicy.delayAfter(failures) - waited).coerceAtLeast(Duration.ZERO)
}

/**
 * Persists [UnlockAttempts] outside the vault (it must be readable while locked), e.g. in a small
 * file next to the vault file. [save] must be durable when it returns: the attempt is recorded
 * before the code is checked.
 */
interface UnlockAttemptStore {
    fun load(): UnlockAttempts
    fun save(attempts: UnlockAttempts)
}

/** What the unlock screen shows: wrong codes so far, the current wait and what further wrong codes cost. */
data class UnlockStatus(val failures: Int, val nextAttemptAllowedAt: Instant?, val upcoming: UpcomingDelay?)

/** Too many wrong codes in a row; the next attempt is allowed at [until] (PROTOCOL.md §5.2). */
class LockedOutException(val until: Instant) : Exception("next unlock attempt allowed at $until")

/** The optional "wipe after 10 wrong attempts" fired; the app has crypto-shredded everything. */
class VaultWipedException : Exception("too many wrong secret codes; the data was wiped")

/**
 * Unlocks the vault with the secret code under the wrong-attempt rules of PROTOCOL.md §5.2: after
 * the N-th consecutive wrong code the next attempt waits D(N), and every attempt is recorded as a
 * failure **before** the slow key derivation and cleared only after a successful unlock, so
 * killing the app mid-derivation gains nothing. Input that is not six digits and failures that are
 * not a wrong code (a corrupt file) do not count.
 *
 * Delays are measured with [bootClock], never the wall clock, so changing the date or restarting
 * the phone never shortens them; [clock] only tells the user when the next attempt is allowed.
 *
 * @param wipe crypto-shreds everything (§5.3: destroy the device secret and the key slots); called
 *   when [wipeAfterFailures] is on and the 10th wrong code in a row was entered.
 */
class VaultUnlocker(
    private val attempts: UnlockAttemptStore,
    private val clock: Clock = Clock.systemUTC(),
    private val bootClock: BootClock = BootClock.SYSTEM,
    private val wipeAfterFailures: Boolean = false,
    private val wipe: () -> Unit = {},
) {
    /** When the next attempt is allowed, or `null` if it is allowed now. Shown by the unlock screen. */
    @Synchronized
    fun nextAttemptAllowedAt(): Instant? = allowedAt(observedAttempts())

    /** Wrong codes so far, the current wait and what further wrong codes cost. */
    @Synchronized
    fun status(): UnlockStatus {
        val current = observedAttempts()
        return UnlockStatus(current.failures, allowedAt(current), UnlockAttemptPolicy.upcomingDelay(current.failures))
    }

    /**
     * @return the DEK.
     * @throws MalformedSecretCodeException if [code] is not six ASCII digits (not counted).
     * @throws LockedOutException if the delay after the last wrong code has not passed yet.
     * @throws WrongSecretCodeException if [code] is wrong (counted).
     * @throws VaultWipedException if this wrong code triggered the optional wipe.
     * @throws InvalidVaultFileException if the vault file is malformed (not counted).
     * @throws IllegalArgumentException if [deviceSecret] is not 32 bytes (not counted).
     */
    @Synchronized
    fun unlock(vault: ByteArray, code: String, deviceSecret: ByteArray, hardwareKey: VaultHardwareKey? = null): DataKey {
        if (!SecretCodePolicy.isWellFormed(code)) throw MalformedSecretCodeException()
        val previous = observedAttempts()
        allowedAt(previous)?.let { throw LockedOutException(it) }
        val attempt = UnlockAttempts(previous.failures + 1, Duration.ZERO, bootClock.sinceBoot())
        attempts.save(attempt)
        val dek = try {
            Vault.open(vault, code, deviceSecret, hardwareKey)
        } catch (e: WrongSecretCodeException) {
            if (UnlockAttemptPolicy.shouldWipe(attempt.failures, wipeAfterFailures)) {
                wipe()
                attempts.save(UnlockAttempts())
                throw VaultWipedException()
            }
            throw e
        } catch (e: Exception) {
            // Not a guess at the code (corrupt file, missing device secret).
            attempts.save(previous)
            throw e
        }
        attempts.save(UnlockAttempts())
        return dek
    }

    /**
     * The stored attempts with [UnlockAttempts.waited] brought up to date and saved, so the time
     * waited never goes backwards.
     */
    private fun observedAttempts(): UnlockAttempts {
        val stored = attempts.load()
        if (stored.failures == 0) return stored
        val updated = stored.observed(bootClock.sinceBoot())
        try {
            attempts.save(updated)
        } catch (_: Exception) {
            // Losing this update only means waiting longer, never shorter.
        }
        return updated
    }

    private fun allowedAt(current: UnlockAttempts): Instant? {
        val remaining = current.remainingDelay()
        return if (remaining > Duration.ZERO) clock.instant().plusNanos(remaining.inWholeNanoseconds) else null
    }
}
