package com.syconx.us2us.runtime.vault

import androidx.biometric.BiometricPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** The §5.2 quick-unlock rules, ported from the iOS `QuickUnlockPolicyTests`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QuickUnlockPolicyTest {
    private val now = Instant.ofEpochSecond(1_800_000_000)
    private val available = BiometricAvailability(BiometryKind.FACE, enrolled = true)

    private fun record(failures: Int = 0, needsRewrap: Boolean = false) = QuickUnlockRecord(byteArrayOf(1), failures, needsRewrap)

    private fun status(
        record: QuickUnlockRecord? = record(),
        lastCodeEntry: Instant? = now,
        availability: BiometricAvailability = available,
        keyValid: Boolean = true,
    ) = QuickUnlockPolicy.status(record, lastCodeEntry, availability, now) { keyValid }

    @Test
    fun rules() {
        assertEquals(QuickUnlockStatus.NotEnabled, status(record = null))
        assertEquals(QuickUnlockStatus.Available(BiometryKind.FACE), status())
        assertEquals(QuickUnlockStatus.CodeRequired(CodeRequiredReason.TOO_MANY_FAILURES), status(record = record(failures = 5)))
        assertEquals(QuickUnlockStatus.Available(BiometryKind.FACE), status(record = record(failures = 4)))
        assertEquals(QuickUnlockStatus.CodeRequired(CodeRequiredReason.SEVEN_DAYS_WITHOUT_CODE), status(lastCodeEntry = null))
        // A clock set backwards does not extend the 7 days.
        assertEquals(
            QuickUnlockStatus.CodeRequired(CodeRequiredReason.SEVEN_DAYS_WITHOUT_CODE),
            status(lastCodeEntry = now.plusSeconds(3600)),
        )
        assertEquals(
            QuickUnlockStatus.CodeRequired(CodeRequiredReason.SEVEN_DAYS_WITHOUT_CODE),
            status(lastCodeEntry = now.minusSeconds(7 * 24 * 3600)),
        )
        assertEquals(QuickUnlockStatus.Available(BiometryKind.FACE), status(lastCodeEntry = now.minusSeconds(7 * 24 * 3600 - 1)))
        assertEquals(QuickUnlockStatus.CodeRequired(CodeRequiredReason.ENROLLMENT_CHANGED), status(keyValid = false))
        assertEquals(QuickUnlockStatus.CodeRequired(CodeRequiredReason.KEY_PROBLEM), status(record = record(needsRewrap = true)))
        assertEquals(
            QuickUnlockStatus.CodeRequired(CodeRequiredReason.SYSTEM_LOCKOUT),
            status(availability = available.copy(lockedOut = true)),
        )
        assertEquals(
            QuickUnlockStatus.CodeRequired(CodeRequiredReason.UNAVAILABLE),
            status(availability = available.copy(enrolled = false)),
        )
        assertEquals(QuickUnlockStatus.CodeRequired(CodeRequiredReason.UNAVAILABLE), status(availability = BiometricAvailability.NONE))
    }

    @Test
    fun theKeystoreIsAskedOnlyWhenEverythingElseAllows() {
        var asked = false
        QuickUnlockPolicy.status(record(failures = 5), now, available, now) { asked = true; true }
        QuickUnlockPolicy.status(record(), null, available, now) { asked = true; true }
        QuickUnlockPolicy.status(record(), now, BiometricAvailability.NONE, now) { asked = true; true }
        assertFalse(asked)
        QuickUnlockPolicy.status(record(), now, available, now) { asked = true; true }
        assertTrue(asked)
    }

    @Test
    fun promptErrorsMapToTheRightOutcome() {
        assertEquals(BiometricError.CANCELLED, BiometricOutcomes.map(BiometricPrompt.ERROR_NEGATIVE_BUTTON, 0))
        assertEquals(BiometricError.CANCELLED, BiometricOutcomes.map(BiometricPrompt.ERROR_USER_CANCELED, 0))
        assertEquals(BiometricError.CANCELLED, BiometricOutcomes.map(BiometricPrompt.ERROR_CANCELED, 0))
        // Giving up after a finger was not recognized is a failed attempt.
        assertEquals(BiometricError.FAILED, BiometricOutcomes.map(BiometricPrompt.ERROR_NEGATIVE_BUTTON, 2))
        assertEquals(BiometricError.FAILED, BiometricOutcomes.map(BiometricPrompt.ERROR_TIMEOUT, 1))
        assertEquals(BiometricError.LOCKOUT, BiometricOutcomes.map(BiometricPrompt.ERROR_LOCKOUT, 5))
        assertEquals(BiometricError.LOCKOUT, BiometricOutcomes.map(BiometricPrompt.ERROR_LOCKOUT_PERMANENT, 0))
        assertEquals(BiometricError.UNAVAILABLE, BiometricOutcomes.map(BiometricPrompt.ERROR_NO_BIOMETRICS, 0))
        assertEquals(BiometricError.UNAVAILABLE, BiometricOutcomes.map(BiometricPrompt.ERROR_HW_UNAVAILABLE, 0))
        assertEquals(BiometricError.FAILED, BiometricOutcomes.map(BiometricPrompt.ERROR_UNABLE_TO_PROCESS, 0))
    }

    @Test
    fun theSidecarRoundTrips() {
        val sidecar = VaultSidecar(
            wipeAfterTenFailures = true,
            lastCodeEntry = Instant.ofEpochMilli(1_791_485_520_123),
            quickUnlock = QuickUnlockRecord(ByteArray(256) { it.toByte() }, failures = 3, needsRewrap = true),
            quickUnlockWanted = true,
        )
        assertEquals(sidecar, VaultSidecar.fromJson(sidecar.toJson()))
        assertEquals(VaultSidecar(), VaultSidecar.fromJson(VaultSidecar().toJson()))
        assertNull(VaultSidecar.fromJson(VaultSidecar().toJson()).quickUnlock)
    }

    @Test
    fun anUnreadableSidecarIsTheDefaults() {
        assertEquals(VaultSidecar(), VaultSidecar.fromJson("not json".toByteArray()))
        assertEquals(VaultSidecar(), VaultSidecar.fromJson("""{"quickUnlock":{"failures":2}}""".toByteArray()))
    }

    @Test
    fun theSidecarHoldsNoSecrets() {
        val json = String(VaultSidecar(lastCodeEntry = now, quickUnlock = record(), quickUnlockWanted = true).toJson())
        assertEquals(setOf("wipeAfterTenFailures", "lastCodeEntry", "quickUnlockWanted", "quickUnlock"), org.json.JSONObject(json).keys().asSequence().toSet())
    }
}
