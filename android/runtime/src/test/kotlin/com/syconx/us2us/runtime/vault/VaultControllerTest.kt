package com.syconx.us2us.runtime.vault

import com.syconx.us2us.core.vault.UpcomingDelay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The vault lifecycle of PROTOCOL.md §5.2, ported from the iOS `VaultControllerTests`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VaultControllerTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: VaultFixture
    private val vault get() = fixture.vault
    private val fingerprint = QuickUnlockStatus.Available(BiometryKind.FINGERPRINT)

    @Before
    fun setUp() {
        fixture = VaultFixture(temporary.newFolder())
    }

    private suspend fun expectUnlockError(expected: UnlockError, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $expected")
        } catch (e: UnlockException) {
            assertEquals(expected, e.error)
        }
    }

    private suspend fun expectVaultError(expected: VaultError, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $expected")
        } catch (e: VaultException) {
            assertEquals(expected, e.error)
        }
    }

    @Test
    fun createThenUnlockGivesTheSameKey() = runBlocking {
        assertFalse(vault.hasVault)
        val created = vault.create(CODE, CODE)
        assertEquals(32, created.size)
        assertTrue(vault.hasVault)
        val unlocked = vault.unlock(CODE)
        assertTrue(unlocked.matches(created))
        // The device secret is an item of our store.
        assertEquals(1, fixture.items.count)
        assertEquals(1, LocalVault(fixture.directories.vault, DeviceSecretItem(fixture.items)).formatVersion)
    }

    @Test
    fun creationRulesComeFromTheCore() = runBlocking {
        expectVaultError(VaultError.InvalidNewCode(SecretCodeError.SEQUENCE)) { vault.create("123456", "123456") }
        expectVaultError(VaultError.InvalidNewCode(SecretCodeError.MISMATCH)) { vault.create(CODE, OTHER_CODE) }
        expectVaultError(VaultError.InvalidNewCode(SecretCodeError.NOT_SIX_DIGITS)) { vault.create("48291", "48291") }
        expectVaultError(VaultError.InvalidNewCode(SecretCodeError.REPEATED_DIGIT)) { vault.create("777777", "777777") }
        expectVaultError(VaultError.InvalidNewCode(SecretCodeError.TOO_COMMON)) { vault.create("121212", "121212") }
        assertFalse(vault.hasVault)
    }

    @Test
    fun aSecondVaultIsRefused() = runBlocking {
        vault.create(CODE, CODE)
        expectVaultError(VaultError.AlreadyExists) { vault.create(OTHER_CODE, OTHER_CODE) }
        assertTrue(vault.unlock(CODE).size == 32)
    }

    @Test
    fun wrongCodesAreThrottledAfterTwo() = runBlocking {
        vault.create(CODE, CODE)
        repeat(2) { expectUnlockError(UnlockError.Vault(VaultError.WrongCode)) { vault.unlock(OTHER_CODE) } }
        val status = vault.unlockStatus()
        assertEquals(2, status.failures)
        assertNull(status.nextAttemptAllowedAt)
        assertEquals(UpcomingDelay(attemptsLeft = 1, delay = 1.minutes), status.upcoming)
        expectUnlockError(UnlockError.Vault(VaultError.WrongCode)) { vault.unlock(OTHER_CODE) }
        // Now even the right code waits.
        try {
            vault.unlock(CODE)
            fail("unlock during a delay")
        } catch (e: UnlockException) {
            val error = e.error as UnlockError.Vault
            assertTrue(error.error is VaultError.LockedOut)
            assertEquals(fixture.clock.instant().plusSeconds(60), (error.error as VaultError.LockedOut).until)
        }
        fixture.pass(61.seconds)
        vault.unlock(CODE)
        assertEquals(0, vault.unlockStatus().failures)
    }

    @Test
    fun malformedInputIsNotCounted() = runBlocking {
        vault.create(CODE, CODE)
        expectUnlockError(UnlockError.Vault(VaultError.MalformedCode)) { vault.unlock("12ab") }
        expectUnlockError(UnlockError.Vault(VaultError.MalformedCode)) { vault.unlock("１２３４５６") }
        expectUnlockError(UnlockError.Vault(VaultError.MalformedCode)) { vault.unlock("4829170") }
        assertEquals(0, vault.unlockStatus().failures)
    }

    @Test
    fun optionalWipeAfterTenDestroysEverything() = runBlocking {
        vault.create(CODE, CODE)
        vault.setWipeAfterTenFailures(true)
        assertTrue(vault.wipeAfterTenFailures)
        var wiped = false
        repeat(10) {
            fixture.pass(4000.seconds)
            try {
                vault.unlock(OTHER_CODE)
            } catch (e: UnlockException) {
                if (e.error == UnlockError.Vault(VaultError.Wiped)) wiped = true
            }
        }
        assertTrue(wiped)
        assertFalse(vault.hasVault)
        assertEquals(0, fixture.items.count)
        // Nothing of the vault remains: no vault file, no attempt counter, no settings.
        assertTrue(fixture.directories.vault.list().orEmpty().isEmpty())
        assertFalse(vault.wipeAfterTenFailures)
    }

    @Test
    fun withoutTheOptionTenWrongCodesOnlyWait() = runBlocking {
        vault.create(CODE, CODE)
        repeat(10) {
            fixture.pass(4000.seconds)
            expectUnlockError(UnlockError.Vault(VaultError.WrongCode)) { vault.unlock(OTHER_CODE) }
        }
        assertTrue(vault.hasVault)
        fixture.pass(1.days)
        vault.unlock(CODE)
        Unit
    }

    @Test
    fun quickUnlockNeedsTheCodeAndThenWorks() = runBlocking {
        val created = vault.create(CODE, CODE)
        assertEquals(QuickUnlockStatus.NotEnabled, vault.quickUnlockStatus())
        expectUnlockError(UnlockError.Vault(VaultError.WrongCode)) { vault.enableQuickUnlock(OTHER_CODE) }
        vault.enableQuickUnlock(CODE)
        assertEquals(fingerprint, vault.quickUnlockStatus())
        val key = vault.unlockWithBiometrics("Unlock us2us", "Use code")
        assertTrue(key.matches(created))
        assertEquals("Unlock us2us" to "Use code", fixture.biometrics.prompts.last())
    }

    @Test
    fun cancelDoesNotCountButFiveFailuresRequireTheCode() = runBlocking {
        vault.create(CODE, CODE)
        vault.enableQuickUnlock(CODE)
        fixture.biometrics.script(BiometricError.CANCELLED, BiometricError.CANCELLED, BiometricError.CANCELLED)
        repeat(3) { expectUnlockError(UnlockError.Biometric(BiometricError.CANCELLED)) { vault.unlockWithBiometrics("r", "c") } }
        assertEquals(fingerprint, vault.quickUnlockStatus())
        fixture.biometrics.script(*Array(5) { BiometricError.FAILED })
        repeat(4) { expectUnlockError(UnlockError.Biometric(BiometricError.FAILED)) { vault.unlockWithBiometrics("r", "c") } }
        expectUnlockError(UnlockError.CodeRequired(CodeRequiredReason.TOO_MANY_FAILURES)) { vault.unlockWithBiometrics("r", "c") }
        assertEquals(QuickUnlockStatus.CodeRequired(CodeRequiredReason.TOO_MANY_FAILURES), vault.quickUnlockStatus())
        vault.unlock(CODE)
        assertEquals(fingerprint, vault.quickUnlockStatus())
    }

    @Test
    fun codeIsRequiredAfterSevenDays() = runBlocking {
        vault.create(CODE, CODE)
        vault.enableQuickUnlock(CODE)
        fixture.pass(6.days)
        assertEquals(fingerprint, vault.quickUnlockStatus())
        fixture.pass(1.days)
        assertEquals(QuickUnlockStatus.CodeRequired(CodeRequiredReason.SEVEN_DAYS_WITHOUT_CODE), vault.quickUnlockStatus())
        expectUnlockError(UnlockError.CodeRequired(CodeRequiredReason.SEVEN_DAYS_WITHOUT_CODE)) { vault.unlockWithBiometrics("r", "c") }
        vault.unlock(CODE)
        assertEquals(fingerprint, vault.quickUnlockStatus())
    }

    @Test
    fun enrollmentChangeRequiresTheCodeAndRearms() = runBlocking {
        vault.create(CODE, CODE)
        vault.enableQuickUnlock(CODE)
        fixture.biometrics.changeEnrollment()
        assertEquals(QuickUnlockStatus.CodeRequired(CodeRequiredReason.ENROLLMENT_CHANGED), vault.quickUnlockStatus())
        vault.unlock(CODE)
        // Entering the code re-created the key for the new enrollment.
        assertEquals(fingerprint, vault.quickUnlockStatus())
        vault.unlockWithBiometrics("r", "c")
        Unit
    }

    @Test
    fun invalidatedKeyIsDiscarded() = runBlocking {
        vault.create(CODE, CODE)
        vault.enableQuickUnlock(CODE)
        fixture.biometrics.script(BiometricError.INVALIDATED)
        val deletionsBefore = fixture.biometrics.deletions
        expectUnlockError(UnlockError.CodeRequired(CodeRequiredReason.ENROLLMENT_CHANGED)) { vault.unlockWithBiometrics("r", "c") }
        assertEquals(deletionsBefore + 1, fixture.biometrics.deletions)
        assertEquals(QuickUnlockStatus.CodeRequired(CodeRequiredReason.KEY_PROBLEM), vault.quickUnlockStatus())
        vault.unlock(CODE)
        assertEquals(fingerprint, vault.quickUnlockStatus())
    }

    @Test
    fun keyFailureAsksForTheCodeOnceAndRewraps() = runBlocking {
        val created = vault.create(CODE, CODE)
        vault.enableQuickUnlock(CODE)
        fixture.biometrics.script(BiometricError.KEY_FAILURE)
        expectUnlockError(UnlockError.CodeRequired(CodeRequiredReason.KEY_PROBLEM)) { vault.unlockWithBiometrics("r", "c") }
        assertEquals(QuickUnlockStatus.CodeRequired(CodeRequiredReason.KEY_PROBLEM), vault.quickUnlockStatus())
        vault.unlock(CODE)
        assertEquals(fingerprint, vault.quickUnlockStatus())
        assertTrue(vault.unlockWithBiometrics("r", "c").matches(created))
    }

    @Test
    fun systemLockoutAndUnavailableHardware() = runBlocking {
        vault.create(CODE, CODE)
        fixture.biometrics.availabilityValue = BiometricAvailability.NONE
        expectUnlockError(UnlockError.CodeRequired(CodeRequiredReason.UNAVAILABLE)) { vault.enableQuickUnlock(CODE) }
        fixture.biometrics.availabilityValue = BiometricAvailability(BiometryKind.FINGERPRINT, enrolled = true)
        vault.enableQuickUnlock(CODE)
        fixture.biometrics.availabilityValue = BiometricAvailability(BiometryKind.FINGERPRINT, enrolled = true, lockedOut = true)
        assertEquals(QuickUnlockStatus.CodeRequired(CodeRequiredReason.SYSTEM_LOCKOUT), vault.quickUnlockStatus())
        fixture.biometrics.availabilityValue = BiometricAvailability(BiometryKind.FINGERPRINT, enrolled = false)
        assertEquals(QuickUnlockStatus.CodeRequired(CodeRequiredReason.UNAVAILABLE), vault.quickUnlockStatus())
    }

    @Test
    fun systemLockoutAtThePromptDoesNotCount() = runBlocking {
        vault.create(CODE, CODE)
        vault.enableQuickUnlock(CODE)
        fixture.biometrics.script(BiometricError.FAILED, BiometricError.LOCKOUT)
        expectUnlockError(UnlockError.Biometric(BiometricError.FAILED)) { vault.unlockWithBiometrics("r", "c") }
        expectUnlockError(UnlockError.CodeRequired(CodeRequiredReason.SYSTEM_LOCKOUT)) { vault.unlockWithBiometrics("r", "c") }
        // One failed match is still on the record; the lockout added nothing.
        fixture.biometrics.script(BiometricError.FAILED, BiometricError.FAILED, BiometricError.FAILED)
        repeat(3) { expectUnlockError(UnlockError.Biometric(BiometricError.FAILED)) { vault.unlockWithBiometrics("r", "c") } }
        fixture.biometrics.script(BiometricError.FAILED)
        expectUnlockError(UnlockError.CodeRequired(CodeRequiredReason.TOO_MANY_FAILURES)) { vault.unlockWithBiometrics("r", "c") }
    }

    @Test
    fun changingTheCodeKeepsQuickUnlock() = runBlocking {
        val created = vault.create(CODE, CODE)
        vault.enableQuickUnlock(CODE)
        vault.changeCode(CODE, OTHER_CODE, OTHER_CODE)
        expectUnlockError(UnlockError.Vault(VaultError.WrongCode)) { vault.unlock(CODE) }
        fixture.pass(1.seconds)
        val key = vault.unlock(OTHER_CODE)
        assertTrue(key.matches(created))
        val quick = vault.unlockWithBiometrics("r", "c")
        assertTrue(quick.matches(created))
    }

    @Test
    fun changingTheCodeChecksTheNewOneFirst() = runBlocking {
        vault.create(CODE, CODE)
        expectVaultError(VaultError.InvalidNewCode(SecretCodeError.SEQUENCE)) { vault.changeCode(CODE, "654321", "654321") }
        expectVaultError(VaultError.InvalidNewCode(SecretCodeError.MISMATCH)) { vault.changeCode(CODE, OTHER_CODE, "590183") }
        // Neither was an attempt at the current code.
        assertEquals(0, vault.unlockStatus().failures)
        expectVaultError(VaultError.WrongCode) { vault.changeCode(OTHER_CODE, "590183", "590183") }
        assertEquals(1, vault.unlockStatus().failures)
        vault.unlock(CODE)
        Unit
    }

    @Test
    fun destroyingKeysShredsTheVault() = runBlocking {
        vault.create(CODE, CODE)
        vault.enableQuickUnlock(CODE)
        vault.destroyKeys()
        assertFalse(vault.hasVault)
        assertEquals(0, fixture.items.count)
        assertFalse(fixture.biometrics.hasKey)
        assertEquals(QuickUnlockStatus.NotEnabled, vault.quickUnlockStatus())
        expectUnlockError(UnlockError.Vault(VaultError.NotFound)) { vault.unlock(CODE) }
    }

    @Test
    fun leftoversOfAnEarlierInstallationAreRemoved() = runBlocking {
        fixture.items.write("stale", byteArrayOf(1, 2, 3))
        vault.create(CODE, CODE)
        assertNull(fixture.items.read("stale"))
        assertTrue(fixture.biometrics.deletions >= 1)
    }

    /**
     * Ported from the iOS security review: the optional wipe firing inside a code change must
     * shred quick unlock as well, or the biometric wrap would still yield the DEK.
     */
    @Test
    fun theOptionalWipeFiringDuringACodeChangeAlsoShredsQuickUnlock() = runBlocking {
        vault.create(CODE, CODE)
        vault.enableQuickUnlock(CODE)
        vault.setWipeAfterTenFailures(true)
        var wiped = false
        repeat(10) {
            fixture.pass(4000.seconds) // past every delay (≤ 1 h), well inside the 7-day window
            try {
                vault.changeCode(OTHER_CODE, "590183", "590183")
            } catch (e: VaultException) {
                if (e.error == VaultError.Wiped) wiped = true
            }
        }
        assertTrue(wiped)
        assertFalse(vault.hasVault)
        assertFalse("the biometric key survived the wipe", fixture.biometrics.hasKey)
        val survivor = try {
            vault.unlockWithBiometrics("r", "c")
        } catch (_: UnlockException) {
            null
        }
        assertNull("after the wipe fired, the fingerprint still yields the DEK", survivor)
    }

    // Android additions

    @Test
    fun attemptsSurviveARestartOfTheApp() = runBlocking {
        vault.create(CODE, CODE)
        repeat(3) { expectUnlockError(UnlockError.Vault(VaultError.WrongCode)) { vault.unlock(OTHER_CODE) } }
        val restarted = fixture.reopened()
        val status = restarted.unlockStatus()
        assertEquals(3, status.failures)
        assertNotNull(status.nextAttemptAllowedAt)
        fixture.pass(61.seconds)
        restarted.unlock(CODE)
        Unit
    }

    @Test
    fun aPhoneRestartNeverShortensTheDelay() = runBlocking {
        vault.create(CODE, CODE)
        repeat(4) {
            fixture.pass(2.minutes)
            expectUnlockError(UnlockError.Vault(VaultError.WrongCode)) { vault.unlock(OTHER_CODE) }
        }
        // 5 minutes after the 4th: 2 minutes pass, then the phone restarts (uptime starts at 0) and
        // the wall clock is set a day ahead. Only time since boot counts.
        fixture.bootClock.advance(2.minutes)
        assertEquals(4, vault.unlockStatus().failures)
        fixture.bootClock.reboot(since = 1.minutes)
        fixture.clock.advance(1.days)
        expectUnlockError(UnlockError.Vault(VaultError.LockedOut(fixture.clock.instant().plusSeconds(120)))) { vault.unlock(CODE) }
        fixture.bootClock.advance(2.minutes)
        vault.unlock(CODE)
        Unit
    }

    @Test
    fun aMissingDeviceSecretIsNotAWrongCode() = runBlocking {
        vault.create(CODE, CODE)
        fixture.items.delete(DeviceSecretItem.ITEM_NAME)
        repeat(5) { expectUnlockError(UnlockError.Vault(VaultError.DeviceSecretMissing)) { vault.unlock(CODE) } }
        assertEquals(0, vault.unlockStatus().failures)
        // The secret is not silently re-created: the vault stays unopenable.
        assertEquals(0, fixture.items.count)
    }

    @Test
    fun aCorruptVaultIsNotAWrongCode() = runBlocking {
        vault.create(CODE, CODE)
        val file = fixture.vaultFile
        file.writeBytes(file.readBytes().also { it[4] = 0x03 }) // v2 is now supported.
        expectUnlockError(UnlockError.Vault(VaultError.Corrupted)) { vault.unlock(CODE) }
        file.writeBytes(file.readBytes().copyOf(20))
        expectUnlockError(UnlockError.Vault(VaultError.Corrupted)) { vault.unlock(CODE) }
        assertEquals(0, vault.unlockStatus().failures)
    }

    @Test
    fun aTamperedVaultReadsAsAWrongCode() = runBlocking {
        vault.create(CODE, CODE)
        val file = fixture.vaultFile
        file.writeBytes(file.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        expectUnlockError(UnlockError.Vault(VaultError.WrongCode)) { vault.unlock(CODE) }
        assertEquals(1, vault.unlockStatus().failures)
    }

    @Test
    fun aCodeUnlockDuringTheBiometricPromptIsBusy() = runBlocking {
        vault.create(CODE, CODE)
        vault.enableQuickUnlock(CODE)
        val gate = CompletableDeferred<Unit>()
        fixture.biometrics.gate = gate
        val prompt = async(Dispatchers.Default) { vault.unlockWithBiometrics("r", "c") }
        while (fixture.biometrics.prompts.isEmpty()) yield()
        expectUnlockError(UnlockError.Busy) { vault.unlock(CODE) }
        expectUnlockError(UnlockError.Busy) { vault.unlockWithBiometrics("r", "c") }
        // Other calls go on while the prompt is up.
        assertEquals(0, vault.unlockStatus().failures)
        gate.complete(Unit)
        assertEquals(32, prompt.await().size)
        vault.unlock(CODE)
        Unit
    }

    @Test
    fun aFailureIsCountedBeforeThePromptAppears() = runBlocking {
        vault.create(CODE, CODE)
        vault.enableQuickUnlock(CODE)
        val gate = CompletableDeferred<Unit>()
        fixture.biometrics.gate = gate
        val prompt = async(Dispatchers.Default) { vault.unlockWithBiometrics("r", "c") }
        while (fixture.biometrics.prompts.isEmpty()) yield()
        // The app is killed while the prompt is up: a fresh controller finds the attempt counted.
        val sidecar = VaultSidecar.fromJson(java.io.File(fixture.directories.vault, VaultController.SIDECAR_NAME).readBytes())
        assertEquals(1, sidecar.quickUnlock?.failures)
        gate.complete(Unit)
        prompt.await()
        val after = VaultSidecar.fromJson(java.io.File(fixture.directories.vault, VaultController.SIDECAR_NAME).readBytes())
        assertEquals(0, after.quickUnlock?.failures)
    }

    @Test
    fun cancellingThePromptFromTheAppDoesNotCount() = runBlocking {
        vault.create(CODE, CODE)
        vault.enableQuickUnlock(CODE)
        fixture.biometrics.gate = CompletableDeferred()
        val prompt = async(Dispatchers.Default) { vault.unlockWithBiometrics("r", "c") }
        while (fixture.biometrics.prompts.isEmpty()) yield()
        prompt.cancel()
        try {
            prompt.await()
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
        val sidecar = VaultSidecar.fromJson(java.io.File(fixture.directories.vault, VaultController.SIDECAR_NAME).readBytes())
        assertEquals(0, sidecar.quickUnlock?.failures)
        fixture.biometrics.gate = null
        vault.unlockWithBiometrics("r", "c")
        Unit
    }

    @Test
    fun quickUnlockSettingsSurviveARestart() = runBlocking {
        val created = vault.create(CODE, CODE)
        vault.enableQuickUnlock(CODE)
        vault.setWipeAfterTenFailures(true)
        val restarted = fixture.reopened()
        assertTrue(restarted.wipeAfterTenFailures)
        assertEquals(fingerprint, restarted.quickUnlockStatus())
        assertTrue(restarted.unlockWithBiometrics("r", "c").matches(created))
        restarted.disableQuickUnlock()
        assertEquals(QuickUnlockStatus.NotEnabled, fixture.reopened().quickUnlockStatus())
        assertFalse(fixture.biometrics.hasKey)
    }

    @Test
    fun biometricUnlockWithoutQuickUnlockIsRefused() = runBlocking {
        vault.create(CODE, CODE)
        expectUnlockError(UnlockError.QuickUnlockNotEnabled) { vault.unlockWithBiometrics("r", "c") }
        assertTrue(fixture.biometrics.prompts.isEmpty())
    }
}
