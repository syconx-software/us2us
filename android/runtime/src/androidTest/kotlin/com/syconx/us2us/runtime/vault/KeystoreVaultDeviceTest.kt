package com.syconx.us2us.runtime.vault

import android.content.Context
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syconx.us2us.core.vault.BootClock
import com.syconx.us2us.core.vault.Vault
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import javax.crypto.SecretKey
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The vault on the phone's own Keystore and file system: the device secret wrapped by an
 * AndroidKeyStore key, the files in `noBackupFilesDir`, the real key derivation.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreVaultDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alias = "us2us.secure-items.test"
    private lateinit var root: File
    private lateinit var items: KeystoreItemStore
    private lateinit var directories: AppDirectories

    /** Uptime the test moves forward from the phone's real reading. */
    private class ShiftedBootClock : BootClock {
        @Volatile var shift: Duration = Duration.ZERO

        override fun sinceBoot(): Duration = AndroidBootClock.sinceBoot() + shift
    }

    @Before
    fun setUp() {
        root = File(context.noBackupFilesDir, "vault-device-test").also { it.deleteRecursively() }
        items = KeystoreItemStore(File(root, "items"), alias)
        items.deleteAll()
        directories = AppDirectories(File(root, "us2us")).also { it.prepare() }
    }

    @After
    fun tearDown() {
        items.deleteAll()
        root.deleteRecursively()
    }

    private fun controller(bootClock: BootClock = AndroidBootClock, iterations: Int = 1000) = VaultController(
        directories = directories,
        secureItems = items,
        biometrics = NoBiometrics,
        configuration = VaultController.Configuration(iterations),
        bootClock = bootClock,
    )

    private suspend fun expect(expected: UnlockError, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $expected")
        } catch (e: UnlockException) {
            assertEquals(expected, e.error)
        }
    }

    @Test
    fun itemsAreSealedByANonExportableKeystoreKey() {
        val secret = ByteArray(32) { (it * 7).toByte() }
        items.write("device-secret", secret)
        assertArrayEquals(secret, items.read("device-secret"))
        val file = File(root, "items/device-secret.item")
        val sealed = file.readBytes()
        assertEquals(12 + 32 + 16, sealed.size)
        assertFalse("the item is stored in plaintext", sealed.toList().windowed(32).any { it.toByteArray().contentEquals(secret) })

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keyStore.getKey(alias, null) as SecretKey
        assertNull("the wrapping key is exportable", key.encoded)
        val level = items.securityLevel()
        Log.i("us2us.vault.test", "secure item key: ${securityLevelName(level)}")
        assertNotNull(level)

        // Bound to its name: the same bytes under another name do not open.
        file.copyTo(File(root, "items/other.item"))
        assertNull(items.read("other"))

        // Erasing everything takes the key with it: a copy of the file can never be read again.
        val copy = sealed.copyOf()
        items.deleteAll()
        assertFalse(keyStore.containsAlias(alias))
        File(root, "items").mkdirs()
        file.writeBytes(copy)
        assertNull(items.read("device-secret"))
        // A new key is made on the next write; the old item stays unreadable.
        items.write("fresh", byteArrayOf(1))
        assertNull(items.read("device-secret"))
        assertArrayEquals(byteArrayOf(1), items.read("fresh"))
    }

    @Test
    fun createUnlockWrongCodesAndTheThrottle() = runBlocking {
        val bootClock = ShiftedBootClock()
        val vault = controller(bootClock)
        val created = vault.create(CODE, CODE)
        assertTrue(vault.unlock(CODE).matches(created))
        repeat(3) { expect(UnlockError.Vault(VaultError.WrongCode)) { vault.unlock(OTHER_CODE) } }
        val status = vault.unlockStatus()
        assertEquals(3, status.failures)
        assertNotNull(status.nextAttemptAllowedAt)
        try {
            vault.unlock(CODE)
            fail("unlock during a delay")
        } catch (e: UnlockException) {
            assertTrue((e.error as UnlockError.Vault).error is VaultError.LockedOut)
        }
        // The app starts again: the counter is on disk.
        val restarted = controller(bootClock)
        assertEquals(3, restarted.unlockStatus().failures)
        bootClock.shift += 61.seconds
        assertTrue(restarted.unlock(CODE).matches(created))
        assertEquals(0, restarted.unlockStatus().failures)
    }

    @Test
    fun theOptionalWipeAfterTenShredsTheKeystoreItems() = runBlocking {
        val bootClock = ShiftedBootClock()
        val vault = controller(bootClock)
        vault.create(CODE, CODE)
        vault.setWipeAfterTenFailures(true)
        var wiped = false
        repeat(10) {
            bootClock.shift += 4000.seconds
            try {
                vault.unlock(OTHER_CODE)
            } catch (e: UnlockException) {
                if (e.error == UnlockError.Vault(VaultError.Wiped)) wiped = true
            }
        }
        assertTrue(wiped)
        assertFalse(vault.hasVault)
        assertNull(items.read(DeviceSecretItem.ITEM_NAME))
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
        assertTrue(directories.vault.list().orEmpty().isEmpty())
        expect(UnlockError.Vault(VaultError.NotFound)) { vault.unlock(CODE) }
    }

    @Test
    fun theRealKeyDerivationTakesAboutASecond() = runBlocking {
        val vault = controller(iterations = Vault.ITERATIONS)
        var mark = TimeSource.Monotonic.markNow()
        val created = vault.create(CODE, CODE)
        val createTime = mark.elapsedNow()
        mark = TimeSource.Monotonic.markNow()
        val unlocked = vault.unlock(CODE)
        val unlockTime = mark.elapsedNow()
        mark = TimeSource.Monotonic.markNow()
        expect(UnlockError.Vault(VaultError.WrongCode)) { vault.unlock(OTHER_CODE) }
        val wrongTime = mark.elapsedNow()
        Log.i("us2us.vault.test", "600000 iterations: create $createTime, unlock $unlockTime, wrong code $wrongTime")
        assertTrue(unlocked.matches(created))
    }

    @Test
    fun theStandardDirectoriesAreOutsideBackups() {
        assertEquals(File(context.noBackupFilesDir, "us2us"), AppDirectories.standard(context).root)
    }

    private companion object {
        const val CODE = "482917"
        const val OTHER_CODE = "730415"
    }
}

/** A Keystore security level as the log shows it. */
internal fun securityLevelName(level: Int?): String = when (level) {
    KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
    KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
    null -> "none"
    else -> "level $level"
}
