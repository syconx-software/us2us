package com.syconx.us2us.core.vault

import com.syconx.us2us.core.testing.MutableClock
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class VaultUnlockerTest {
    private val deviceSecret = ByteArray(32) { 0x11 }
    private val dek = DataKey.generate()
    private val code = "472913"
    private val wrong = "000001"
    private val vault = Vault.create(code, deviceSecret, dek, iterations = 1000)

    /** The wall clock the user could set, and the time since boot that only real time moves. */
    private val clock = MutableClock()
    private var sinceBoot: Duration = 50_000.seconds
    private val bootClock = BootClock { sinceBoot }

    /** Real time passes: both clocks advance. */
    private fun advance(duration: Duration) {
        clock.advance(duration)
        sinceBoot += duration
    }

    /** Remembers every state written, like a file next to the vault would. */
    private class RecordingStore : UnlockAttemptStore {
        val saved = mutableListOf<UnlockAttempts>()
        override fun load(): UnlockAttempts = saved.lastOrNull() ?: UnlockAttempts()
        override fun save(attempts: UnlockAttempts) {
            saved += attempts
        }
    }

    private fun unlocker(store: UnlockAttemptStore, wipe: (() -> Unit)? = null) =
        VaultUnlocker(store, clock, bootClock, wipeAfterFailures = wipe != null, wipe = wipe ?: {})

    @Test
    fun `every attempt is recorded as a failure before the code is checked`() {
        val store = RecordingStore()
        val unlocker = unlocker(store)
        assertArrayEquals(dek.toBytes(), unlocker.unlock(vault, code, deviceSecret).toBytes())
        // The failure was on disk during the slow derivation; killing the app there would keep it.
        assertEquals(listOf(UnlockAttempts(1, Duration.ZERO, sinceBoot), UnlockAttempts()), store.saved)

        store.saved.clear()
        assertThrows<WrongSecretCodeException> { unlocker.unlock(vault, wrong, deviceSecret) }
        assertEquals(listOf(UnlockAttempts(1, Duration.ZERO, sinceBoot)), store.saved)
    }

    @Test
    fun `wrong codes are throttled as in the spec, and a success clears the count`() {
        val store = RecordingStore()
        val unlocker = unlocker(store)
        assertEquals(UnlockStatus(0, null, UpcomingDelay(3, 1.minutes)), unlocker.status())
        assertThrows<WrongSecretCodeException> { unlocker.unlock(vault, wrong, deviceSecret) }
        assertEquals(UnlockStatus(1, null, UpcomingDelay(2, 1.minutes)), unlocker.status(), "2 more, then 1 minute")
        assertThrows<WrongSecretCodeException> { unlocker.unlock(vault, wrong, deviceSecret) }
        assertNull(unlocker.nextAttemptAllowedAt(), "D(1..2) = 0")

        assertThrows<WrongSecretCodeException> { unlocker.unlock(vault, wrong, deviceSecret) }
        assertEquals(clock.instant().plusSeconds(60), unlocker.nextAttemptAllowedAt(), "D(3) = 1 min")
        val locked = assertThrows<LockedOutException> { unlocker.unlock(vault, code, deviceSecret) }
        assertEquals(clock.instant().plusSeconds(60), locked.until)
        assertEquals(3, store.load().failures, "a refused attempt is not counted")
        assertEquals(UpcomingDelay(1, 5.minutes), unlocker.status().upcoming)

        advance(1.minutes)
        assertThrows<WrongSecretCodeException> { unlocker.unlock(vault, wrong, deviceSecret) }
        assertEquals(clock.instant().plusSeconds(300), unlocker.nextAttemptAllowedAt(), "D(4) = 5 min")
        advance(5.minutes)
        unlocker.unlock(vault, code, deviceSecret)
        assertEquals(UnlockAttempts(), store.load())
        assertNull(unlocker.nextAttemptAllowedAt())
    }

    @Test
    fun `changing the date or restarting the phone never shortens a delay`() {
        val store = RecordingStore()
        val unlocker = unlocker(store)
        repeat(4) {
            runCatching { unlocker.unlock(vault, wrong, deviceSecret) }
            advance(5.minutes)
        }
        assertEquals(4, store.load().failures)
        assertThrows<WrongSecretCodeException> { unlocker.unlock(vault, wrong, deviceSecret) } // 5th: 15 min

        // Moving the date a day ahead does not help.
        clock.advance(1.days)
        assertEquals(clock.instant().plus(15.minutes.toJavaDuration()), assertThrows<LockedOutException> { unlocker.unlock(vault, code, deviceSecret) }.until)
        // Moving it back does not extend the wait either.
        clock.advance(-(2.days))
        advance(15.minutes)
        assertNull(unlocker.nextAttemptAllowedAt())
        assertThrows<WrongSecretCodeException> { unlocker.unlock(vault, wrong, deviceSecret) } // 6th: 30 min

        // A restart 10 minutes later: only the 2 minutes since boot count.
        clock.advance(10.minutes)
        sinceBoot = 2.minutes
        assertEquals(clock.instant().plus(28.minutes.toJavaDuration()), unlocker.nextAttemptAllowedAt())
        advance(28.minutes)
        assertArrayEquals(dek.toBytes(), unlocker.unlock(vault, code, deviceSecret).toBytes())
    }

    @Test
    fun `the time waited never goes backwards, also when the new boot passes the old reading`() {
        val store = RecordingStore()
        val unlocker = unlocker(store)
        repeat(3) { runCatching { unlocker.unlock(vault, wrong, deviceSecret) } } // 3rd: 1 min
        val reading = sinceBoot
        clock.advance(2.hours)
        sinceBoot = reading - 30.seconds // restarted, and up for almost as long as before
        assertNull(unlocker.nextAttemptAllowedAt())
        advance(1.minutes) // now past the old reading
        assertNull(unlocker.nextAttemptAllowedAt())
        assertEquals(3, unlocker.status().failures)
    }

    @Test
    fun `a restart nobody observed counts only the time after the old reading`() {
        val store = RecordingStore()
        val unlocker = unlocker(store)
        repeat(3) { runCatching { unlocker.unlock(vault, wrong, deviceSecret) } }
        clock.advance(2.hours)
        sinceBoot += 10.seconds
        assertEquals(clock.instant().plusSeconds(50), unlocker.nextAttemptAllowedAt())
    }

    @Test
    fun `an attempt killed during the derivation still counts on the next start`() {
        val store = RecordingStore()
        repeat(2) { assertThrows<WrongSecretCodeException> { unlocker(store).unlock(vault, wrong, deviceSecret) } }
        // The third attempt was written, then the app died before the check finished.
        store.save(UnlockAttempts(3, Duration.ZERO, sinceBoot))
        assertThrows<LockedOutException> { unlocker(store).unlock(vault, code, deviceSecret) }
    }

    @Test
    fun `input that is not six digits and failures that are not a wrong code do not count`() {
        val store = RecordingStore()
        val unlocker = unlocker(store)
        for (input in listOf("", "47291", "4729130", "４７２９１３", "47291a")) {
            assertThrows<MalformedSecretCodeException>(input) { unlocker.unlock(vault, input, deviceSecret) }
        }
        assertThrows<InvalidVaultFileException> { unlocker.unlock(vault.copyOf(20), code, deviceSecret) }
        assertThrows<IllegalArgumentException> { unlocker.unlock(vault, code, ByteArray(5)) }
        assertEquals(UnlockAttempts(), store.load())
    }

    @Test
    fun `the optional wipe fires on the tenth wrong code in a row`() {
        var wiped = 0
        val store = RecordingStore()
        val unlocker = unlocker(store, wipe = { wiped++ })
        repeat(9) {
            assertThrows<WrongSecretCodeException> { unlocker.unlock(vault, wrong, deviceSecret) }
            advance(1.hours)
        }
        assertEquals(0, wiped)
        assertThrows<VaultWipedException> { unlocker.unlock(vault, wrong, deviceSecret) }
        assertEquals(1, wiped)
        assertEquals(UnlockAttempts(), store.load(), "a fresh install starts without delays")
    }
}
