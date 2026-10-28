package com.syconx.us2us.runtime.vault

import com.syconx.us2us.core.vault.VaultHardwareKey
import com.syconx.us2us.core.vault.VaultHardwareKeyException
import com.syconx.us2us.core.vault.VaultHardwareKeyMissingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VaultV2MigrationTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Hardware : VaultHardwareKey {
        override var isAvailable = true
        var generation = 0
        var key: ByteArray? = null
        var calls = 0
        var failAt = -1
        var inconsistent = false
        var failDeletion = false
        /** From this call on the key gives other output than before (a key that changed under the vault). */
        var driftFrom = -1
        override fun createKey() { generation++; key = ByteArray(32) { generation.toByte() } }
        override fun evaluate(codeKey: ByteArray): ByteArray {
            calls++
            if (calls == failAt) throw VaultHardwareKeyException("temporary test failure")
            val current = key ?: throw VaultHardwareKeyMissingException()
            if (inconsistent) return ByteArray(32) { calls.toByte() }
            if (driftFrom in 1..calls) return ByteArray(32) { 0x55 }
            return Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(current, "HmacSHA256")); doFinal(codeKey) }
        }
        override fun deleteKey() {
            if (failDeletion) throw VaultHardwareKeyException("temporary deletion error")
            key?.fill(0); key = null
        }
    }

    private fun local(fixture: VaultFixture, hardware: Hardware? = null) = LocalVault(
        fixture.directories.vault, DeviceSecretItem(fixture.items), LocalVault.Configuration(1000),
        fixture.clock, fixture.bootClock, hardware,
    )

    private fun expect(error: VaultError, block: () -> Unit) {
        try { block(); fail("expected $error") } catch (e: VaultException) { assertEquals(error, e.error) }
    }

    @Test
    fun migrationPreservesDekSlotsAndBiometricUnlockAcrossRestart() = runBlocking {
        val fixture = VaultFixture(temporary.newFolder())
        val original = fixture.vault.create(CODE, CODE)
        fixture.vault.enableQuickUnlock(CODE)
        val slots = KeySlotStore(fixture.directories.slots)
        val (id, conversationKey) = original.withDataKey { slots.createSlot(it) }
        val hardware = Hardware()
        fun controller() = VaultController(
            fixture.directories, fixture.items, fixture.biometrics, fixture.clock,
            VaultController.Configuration(1000), fixture.bootClock, Dispatchers.Unconfined, hardware,
        )
        val upgraded = controller().unlock(CODE)
        assertTrue(original.matches(upgraded))
        assertEquals(2, local(fixture, hardware).formatVersion)
        // Sealing evaluates twice (reproducible?), then the candidate is opened once before it replaces v1 (M-6).
        assertEquals(3, hardware.calls)
        assertArrayEquals(conversationKey, upgraded.withDataKey { slots.conversationKey(id, it) })
        assertTrue(original.matches(controller().unlockWithBiometrics("unlock", "code")))
        assertTrue(original.matches(controller().unlock(CODE)))
        assertEquals(1, hardware.generation)
        controller().destroyKeys()
        assertNull(hardware.key)
        assertNull(fixture.items.read(DeviceSecretItem.ITEM_NAME))
    }

    @Test
    fun wrongPinDoesNotMigrateAndHardwareFailuresLeaveV1Usable() = runBlocking {
        val fixture = VaultFixture(temporary.newFolder())
        val original = fixture.vault.create(CODE, CODE)
        val bytes = fixture.vaultFile.readBytes()
        val hardware = Hardware().also { it.failAt = 2 }
        val vault = local(fixture, hardware)
        expect(VaultError.WrongCode) { vault.unlock(OTHER_CODE) }
        assertEquals(0, hardware.calls)
        assertEquals(0, hardware.generation)
        assertTrue(original.matches(vault.unlock(CODE)))
        assertEquals(0, vault.status().failures)
        assertArrayEquals(bytes, fixture.vaultFile.readBytes())
        assertTrue(original.matches(vault.unlock(CODE)))
        assertEquals(2, vault.formatVersion)
        assertEquals(2, hardware.generation)
    }

    @Test
    fun aV2CandidateThatDoesNotOpenAgainNeverReplacesV1() = runBlocking {
        // M-6: the move is checked by opening the candidate before the atomic replacement.
        val fixture = VaultFixture(temporary.newFolder())
        val original = fixture.vault.create(CODE, CODE)
        val bytes = fixture.vaultFile.readBytes()
        val hardware = Hardware().also { it.driftFrom = 3 }
        assertTrue(original.matches(local(fixture, hardware).unlock(CODE)))
        assertArrayEquals("v1 kept", bytes, fixture.vaultFile.readBytes())
        assertEquals(1, local(fixture, hardware).formatVersion)
        assertEquals(DeviceProtection.BASIC, DeviceProtection.of(local(fixture, hardware).formatVersion, 36, null))
        // The next unlock tries again, now with a well-behaved key.
        hardware.driftFrom = -1
        assertTrue(original.matches(local(fixture, hardware).unlock(CODE)))
        assertEquals(2, local(fixture, hardware).formatVersion)
        assertTrue(original.matches(local(fixture, hardware).unlock(CODE)))
    }

    @Test
    fun theControllerReportsBasicProtectionWhileTheUpgradeFailsAndFullOnceItWorked() = runBlocking {
        val fixture = VaultFixture(temporary.newFolder())
        val hardware = Hardware().also { it.isAvailable = false }
        val controller = VaultController(
            fixture.directories, fixture.items, fixture.biometrics, fixture.clock,
            VaultController.Configuration(1000), fixture.bootClock, Dispatchers.Unconfined, hardware,
        )
        controller.create(CODE, CODE)
        assertEquals(DeviceProtection.BASIC, controller.deviceProtection(sdk = 36))
        hardware.isAvailable = true
        hardware.failAt = 1
        controller.unlock(CODE).wipe()
        assertEquals("a failed upgrade is never silent", DeviceProtection.BASIC, controller.deviceProtection(sdk = 36))
        controller.unlock(CODE).wipe()
        assertEquals(DeviceProtection.FULL, controller.deviceProtection(sdk = 36))
        assertEquals(DeviceProtection.FULL, controller.deviceProtection(sdk = 35))
        assertEquals("below Android 15 the key also works while locked", DeviceProtection.LIMITED, controller.deviceProtection(sdk = 34))
        assertEquals(DeviceProtection.LIMITED, controller.deviceProtection(sdk = 28))
    }

    @Test
    fun interruptedAtomicReplacementLeavesV1AndNextUnlockRetries() = runBlocking {
        val fixture = VaultFixture(temporary.newFolder())
        val original = fixture.vault.create(CODE, CODE)
        val bytes = fixture.vaultFile.readBytes()
        val blocked = File(fixture.directories.vault, "vault.brpv.pending").also { it.mkdir() }
        File(blocked, "interrupt").writeText("test")
        val hardware = Hardware()
        assertTrue(original.matches(local(fixture, hardware).unlock(CODE)))
        assertArrayEquals(bytes, fixture.vaultFile.readBytes())
        assertTrue(original.matches(local(fixture).unlock(CODE)))
        blocked.deleteRecursively()
        assertTrue(original.matches(local(fixture, hardware).unlock(CODE)))
        assertEquals(2, local(fixture, hardware).formatVersion)
        assertEquals(2, hardware.generation)
    }

    @Test
    fun v2MissingKeyAndTemporaryErrorDoNotCountOrDowngrade() = runBlocking {
        val fixture = VaultFixture(temporary.newFolder())
        val hardware = Hardware()
        val vault = local(fixture, hardware)
        val original = vault.create(CODE, CODE)
        val bytes = fixture.vaultFile.readBytes()
        expect(VaultError.WrongCode) { vault.unlock(OTHER_CODE) }
        hardware.failAt = hardware.calls + 1
        try { vault.unlock(CODE); fail("hardware failure ignored") } catch (e: VaultException) { assertTrue(e.error is VaultError.Storage) }
        assertEquals(1, vault.status().failures)
        val key = hardware.key
        hardware.key = null
        expect(VaultError.DeviceSecretMissing) { vault.unlock(CODE) }
        expect(VaultError.DeviceSecretMissing) { local(fixture).unlock(CODE) }
        assertEquals(1, vault.status().failures)
        assertArrayEquals(bytes, fixture.vaultFile.readBytes())
        hardware.key = key
        assertTrue(original.matches(vault.unlock(CODE)))
        assertEquals(1, hardware.generation)
    }

    @Test
    fun codeChangeNeverDowngradesOrReplacesTheHardwareKey() = runBlocking {
        val fixture = VaultFixture(temporary.newFolder())
        val hardware = Hardware()
        val vault = local(fixture, hardware)
        val original = vault.create(CODE, CODE)
        vault.changeCode(CODE, OTHER_CODE, OTHER_CODE)
        assertTrue(original.matches(vault.unlock(OTHER_CODE)))
        assertEquals(2, vault.formatVersion)
        assertEquals(1, hardware.generation)
        expect(VaultError.WrongCode) { vault.unlock(CODE) }
    }

    @Test
    fun unsupportedOrInconsistentHardwareCreatesV1AndCanUpgradeLater() = runBlocking {
        val fixture = VaultFixture(temporary.newFolder())
        val hardware = Hardware().also { it.isAvailable = false }
        val vault = local(fixture, hardware)
        val original = vault.create(CODE, CODE)
        assertEquals(1, vault.formatVersion)
        assertEquals(0, hardware.generation)
        hardware.isAvailable = true
        hardware.inconsistent = true
        assertTrue(original.matches(vault.unlock(CODE)))
        assertEquals(1, vault.formatVersion)
        hardware.inconsistent = false
        assertTrue(original.matches(vault.unlock(CODE)))
        assertEquals(2, vault.formatVersion)
    }

    @Test
    fun theOptionalWipeDeletesTheV2HardwareKey() = runBlocking {
        val fixture = VaultFixture(temporary.newFolder())
        val hardware = Hardware()
        val controller = VaultController(
            fixture.directories, fixture.items, fixture.biometrics, fixture.clock,
            VaultController.Configuration(1000), fixture.bootClock, Dispatchers.Unconfined, hardware,
        )
        controller.create(CODE, CODE)
        controller.enableQuickUnlock(CODE)
        controller.setWipeAfterTenFailures(true)
        repeat(10) { index ->
            fixture.pass(4000.seconds)
            try { controller.unlock(OTHER_CODE); fail("wrong code accepted") } catch (e: UnlockException) {
                assertEquals(UnlockError.Vault(if (index == 9) VaultError.Wiped else VaultError.WrongCode), e.error)
            }
        }
        assertNull(hardware.key)
        assertFalse(controller.hasVault)
        assertEquals(0, fixture.items.count)
        assertFalse(fixture.biometrics.hasKey)
    }

    @Test
    fun aFailedV2RewrapLeavesThePreviousCodeAndFileUsable() = runBlocking {
        val fixture = VaultFixture(temporary.newFolder())
        val hardware = Hardware()
        val vault = local(fixture, hardware)
        val original = vault.create(CODE, CODE)
        val bytes = fixture.vaultFile.readBytes()
        hardware.failAt = hardware.calls + 2 // Current PIN succeeds; sealing with the new PIN fails.
        try { vault.changeCode(CODE, OTHER_CODE, OTHER_CODE); fail("rewrap ignored hardware failure") } catch (e: VaultException) {
            assertTrue(e.error is VaultError.Storage)
        }
        assertArrayEquals(bytes, fixture.vaultFile.readBytes())
        assertTrue(original.matches(vault.unlock(CODE)))
        assertEquals(1, hardware.generation)
        assertEquals(0, vault.status().failures)
    }

    @Test
    fun aHardwareDeletionErrorStillDestroysTheOtherVaultSecrets() = runBlocking {
        val fixture = VaultFixture(temporary.newFolder())
        val hardware = Hardware()
        val vault = local(fixture, hardware)
        vault.create(CODE, CODE)
        hardware.failDeletion = true
        try { vault.destroy(); fail("deletion failure hidden") } catch (e: VaultException) {
            assertTrue(e.error is VaultError.Storage)
        }
        assertNull(fixture.items.read(DeviceSecretItem.ITEM_NAME))
        assertFalse(vault.exists)
    }
}
