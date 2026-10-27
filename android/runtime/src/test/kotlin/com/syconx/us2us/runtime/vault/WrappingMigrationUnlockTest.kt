package com.syconx.us2us.runtime.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WrappingMigrationUnlockTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Store(val items: InMemorySecureItemStore) : SecureItemStore by items {
        var calls = 0
        var fail = false
        override fun migrateAfterSuccessfulUnlock() {
            calls++
            if (fail) throw IOException("simulated optional migration failure")
        }
    }
    private fun controller(f: VaultFixture, store: Store) = VaultController(f.directories, store, f.biometrics,
        f.clock, VaultController.Configuration(1000), f.bootClock, Dispatchers.Unconfined)

    @Test fun creationAndCorrectPinMigrateButWrongPinDoesNot() = runBlocking {
        val f = VaultFixture(temporary.newFolder()); val store = Store(f.items); val vault = controller(f, store)
        val original = vault.create(CODE, CODE); assertEquals(1, store.calls)
        try { vault.unlock(OTHER_CODE); fail("wrong PIN accepted") } catch (_: UnlockException) { }
        assertEquals(1, store.calls)
        assertTrue(original.matches(vault.unlock(CODE))); assertEquals(2, store.calls)
        original.wipe()
    }

    @Test fun optionalFailurePreservesDekAndDoesNotConsumeAPinAttempt() = runBlocking {
        val f = VaultFixture(temporary.newFolder()); val original = f.vault.create(CODE, CODE)
        val store = Store(f.items).also { it.fail = true }; val vault = controller(f, store)
        val opened = vault.unlock(CODE)
        assertTrue(original.matches(opened)); assertEquals(0, vault.unlockStatus().failures)
        store.fail = false; assertTrue(original.matches(vault.unlock(CODE))); assertEquals(2, store.calls)
        original.wipe(); opened.wipe()
    }

    @Test fun successfulBiometricUnlockMigratesButFailedPromptDoesNot() = runBlocking {
        val f = VaultFixture(temporary.newFolder()); val original = f.vault.create(CODE, CODE)
        val store = Store(f.items); val vault = controller(f, store)
        vault.enableQuickUnlock(CODE); store.calls = 0
        f.biometrics.script(BiometricError.CANCELLED, null)
        try { vault.unlockWithBiometrics("test", "code"); fail("cancelled prompt accepted") } catch (_: UnlockException) { }
        assertEquals(0, store.calls)
        assertTrue(original.matches(vault.unlockWithBiometrics("test", "code"))); assertEquals(1, store.calls)
        original.wipe()
    }

    @Test fun optionalFailureDoesNotBreakBiometricUnlock() = runBlocking {
        val f = VaultFixture(temporary.newFolder()); val original = f.vault.create(CODE, CODE)
        val store = Store(f.items); val vault = controller(f, store)
        vault.enableQuickUnlock(CODE); store.calls = 0; store.fail = true
        assertTrue(original.matches(vault.unlockWithBiometrics("test", "code"))); assertEquals(1, store.calls)
        original.wipe()
    }

    @Test fun correctPinChangePreservesKeysAndRunsMaintenance() = runBlocking {
        val f = VaultFixture(temporary.newFolder()); val original = f.vault.create(CODE, CODE)
        val store = Store(f.items); val vault = controller(f, store)
        vault.changeCode(CODE, OTHER_CODE, OTHER_CODE); assertEquals(1, store.calls)
        assertTrue(original.matches(vault.unlock(OTHER_CODE))); assertEquals(2, store.calls)
        original.wipe()
    }
}
