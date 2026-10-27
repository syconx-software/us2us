package com.syconx.us2us.runtime.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.syconx.us2us.core.vault.BootClock
import com.syconx.us2us.core.vault.Vault
import com.syconx.us2us.core.vault.VaultHardwareKey
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
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import java.io.IOException
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
        items.vaultHardwareKey().deleteKey()
        items.deleteAll()
        root.deleteRecursively()
    }

    private fun controller(bootClock: BootClock = AndroidBootClock, iterations: Int = 1000, hardwareKey: VaultHardwareKey? = items.vaultHardwareKey()) = VaultController(
        directories = directories,
        secureItems = items,
        biometrics = NoBiometrics,
        configuration = VaultController.Configuration(iterations),
        bootClock = bootClock,
        hardwareKey = hardwareKey,
    )

    /** A physical-device gate must fail rather than silently passing the emulator's v1 fallback. */
    private fun assertHardwareGate(version: Int) {
        if (InstrumentationRegistry.getArguments().getString("requireHardwareVault") == "true") {
            assertEquals("Physical hardware gate requires a real v2 vault", 2, version)
        }
    }

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
        // A missing key must not be silently replaced while existing ciphertext remains.
        try { items.write("fresh", byteArrayOf(1)); fail("lost key silently replaced") } catch (_: SecureItemException) { }
        assertNull(items.read("device-secret"))
        assertFalse(keyStore.containsAlias(alias))
        items.deleteAll()
        items.write("fresh", byteArrayOf(1))
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
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("$alias.wrap-v2"))
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

    @Test
    fun hardwareStepIsVerifiedAndSurvivesControllerRestart() = runBlocking {
        val original = controller().create(CODE, CODE)
        val version = File(directories.vault, LocalVault.FILE_NAME).readBytes()[4].toInt()
        assertHardwareGate(version)
        assertTrue(controller().unlock(CODE).matches(original))
        if (version == 2) {
            val hardware = items.vaultHardwareKey()
            val level = hardware.securityLevel()
            Log.i("us2us.vault.test", "vault PRF key: ${securityLevelName(level)}")
            assertTrue(level == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT || level == KeyProperties.SECURITY_LEVEL_STRONGBOX)
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertNull(keyStore.getKey("$alias.vault-prf-v2", null).encoded)
            val input = ByteArray(32) { it.toByte() }
            assertArrayEquals(hardware.evaluate(input), hardware.evaluate(input))
            hardware.deleteKey()
            expect(UnlockError.Vault(VaultError.DeviceSecretMissing)) { controller().unlock(CODE) }
            assertEquals(0, controller().unlockStatus().failures)
        } else {
            assertEquals(1, version)
            Log.i("us2us.vault.test", "hardware vault unavailable; verified v1 fallback, not TEE/StrongBox coverage")
        }
        controller().destroyKeys()
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("$alias.vault-prf-v2"))
    }

    @Test
    fun legacyVaultMigrationAndPinChangePreserveTheRealDataKey() = runBlocking {
        val original = controller(hardwareKey = null).create(CODE, CODE)
        val file = File(directories.vault, LocalVault.FILE_NAME)
        assertEquals(1, file.readBytes()[4].toInt())
        assertTrue(controller().unlock(CODE).matches(original))
        val version = file.readBytes()[4].toInt()
        assertHardwareGate(version)
        val before = original.withDataKey { it.databaseKey() }
        controller().changeCode(CODE, OTHER_CODE, OTHER_CODE)
        assertEquals(version, file.readBytes()[4].toInt())
        val reopened = controller().unlock(OTHER_CODE)
        assertTrue(original.matches(reopened))
        assertArrayEquals(before, reopened.withDataKey { it.databaseKey() })
        expect(UnlockError.Vault(VaultError.WrongCode)) { controller().unlock(CODE) }
        original.wipe(); reopened.wipe(); before.fill(0)
    }

    @Test
    fun interruptedHardwareMigrationLeavesTheLegacyVaultUsable() = runBlocking {
        val original = controller(hardwareKey = null).create(CODE, CODE)
        val file = File(directories.vault, LocalVault.FILE_NAME)
        val bytes = file.readBytes()
        val pending = File(directories.vault, LocalVault.FILE_NAME + ".pending").also { assertTrue(it.mkdir()) }
        File(pending, "simulated-interruption").writeText("test")
        assertTrue(controller().unlock(CODE).matches(original))
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(controller(hardwareKey = null).unlock(CODE).matches(original))
        assertTrue(pending.deleteRecursively())
        assertTrue(controller().unlock(CODE).matches(original))
        assertHardwareGate(file.readBytes()[4].toInt())
        assertTrue(controller().unlock(CODE).matches(original))
        original.wipe()
    }

    @Test
    fun legacyWrappingMigrationKeepsAllSecretsAndRetiresOnlyTheOldKey() {
        val expected = mapOf("device-secret" to ByteArray(32) { 0x51 }, "secondary" to byteArrayOf(7, 8, 9))
        writeLegacyWrappingItems(File(root, "items"), alias, expected)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(keyStore.containsAlias(alias))
        items.migrateAfterSuccessfulUnlock()
        val reopened = KeystoreItemStore(File(root, "items"), alias)
        expected.forEach { (name, bytes) -> assertArrayEquals(bytes, reopened.read(name)) }
        assertFalse(keyStore.containsAlias(alias))
        assertTrue(keyStore.containsAlias("$alias.wrap-v2"))
        assertNull(keyStore.getKey("$alias.wrap-v2", null).encoded)
        Log.i("us2us.vault.test", "migrated wrapping key: ${securityLevelName(reopened.securityLevel())}")
        assertTrue(reopened.securityLevel() == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
            || reopened.securityLevel() == KeyProperties.SECURITY_LEVEL_STRONGBOX)
    }

    @Test
    fun interruptedWrappingCommitKeepsLegacyItemsAndRetriesWithoutResurrection() {
        val folder = File(root, "items")
        val expected = mapOf("device-secret" to ByteArray(32) { 0x52 }, "secondary" to byteArrayOf(3, 4))
        writeLegacyWrappingItems(folder, alias, expected)
        val staleCopy = File(folder, "secondary.item").readBytes()
        val blocked = File(folder, SecureItemLayout.MARKER_NAME + ".pending").also { assertTrue(it.mkdir()) }
        File(blocked, "interrupt").writeText("test")
        try { items.migrateAfterSuccessfulUnlock(); fail("expected blocked commit") } catch (_: IOException) { }
        val reopened = KeystoreItemStore(folder, alias)
        expected.forEach { (name, bytes) -> assertArrayEquals(bytes, reopened.read(name)) }
        assertTrue(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
        assertTrue(blocked.deleteRecursively())
        reopened.migrateAfterSuccessfulUnlock()
        reopened.write("device-secret", ByteArray(32) { 0x53 })
        reopened.delete("secondary")
        File(folder, "secondary.item").writeBytes(staleCopy)
        assertNull(KeystoreItemStore(folder, alias).read("secondary"))
        reopened.migrateAfterSuccessfulUnlock()
        assertArrayEquals(ByteArray(32) { 0x53 }, reopened.read("device-secret"))
        assertNull(reopened.read("secondary"))
    }

    @Test
    fun missingMigratedWrappingKeyDoesNotReplaceCiphertextOrGenerateAnotherKey() {
        val folder = File(root, "items")
        writeLegacyWrappingItems(folder, alias, mapOf("device-secret" to ByteArray(32) { 0x54 }))
        items.migrateAfterSuccessfulUnlock()
        val active = File(folder, SecureItemLayout.NEXT_DIRECTORY + "/device-secret.item")
        val before = active.readBytes()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.deleteEntry("$alias.wrap-v2")
        assertNull(KeystoreItemStore(folder, alias).read("device-secret"))
        try { items.write("device-secret", ByteArray(32) { 0x55 }); fail("missing key replaced") } catch (_: IOException) { }
        assertArrayEquals(before, active.readBytes())
        assertFalse(keyStore.containsAlias("$alias.wrap-v2"))
        assertTrue(File(folder, SecureItemLayout.MARKER_NAME).delete())
        try { items.write("fresh", byteArrayOf(1)); fail("missing marker selected an empty legacy generation") } catch (_: IOException) { }
        assertFalse(keyStore.containsAlias(alias))
        assertArrayEquals(before, active.readBytes())
    }

    @Test
    fun wrappingCommitFailureKeepsPinUnlockUsableAndNextUnlockPreservesDek() = runBlocking {
        val folder = File(root, "items")
        writeLegacyWrappingItems(folder, alias, mapOf("secondary" to byteArrayOf(1, 2)))
        val original = LocalVault(directories.vault, DeviceSecretItem(items), LocalVault.Configuration(1000),
            hardwareKey = items.vaultHardwareKey()).create(CODE, CODE)
        val databaseKey = original.withDataKey { it.databaseKey() }
        val blocked = File(folder, SecureItemLayout.MARKER_NAME + ".pending").also { assertTrue(it.mkdir()) }
        File(blocked, "interrupt").writeText("test")
        val first = controller().unlock(CODE)
        assertTrue(first.matches(original)); assertEquals(0, controller().unlockStatus().failures)
        assertTrue(blocked.deleteRecursively())
        val second = controller().unlock(CODE)
        assertTrue(second.matches(original))
        assertArrayEquals(databaseKey, second.withDataKey { it.databaseKey() })
        assertArrayEquals(byteArrayOf(1, 2), items.read("secondary"))
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
        original.wipe(); first.wipe(); second.wipe(); databaseKey.fill(0)
    }

    private companion object {
        const val CODE = "482917"
        const val OTHER_CODE = "730415"
    }
}

/** Test-only reproduction of the previous key policy and exact legacy item format. */
internal fun writeLegacyWrappingItems(directory: File, alias: String, values: Map<String, ByteArray>) {
    directory.mkdirs()
    fun generate(unlocked: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUnlockedDeviceRequired(unlocked).build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }
    var required = true
    val key = try { generate(true) } catch (_: java.security.ProviderException) {
        // The previous production policy did exactly this on API31–34 without a secure lock.
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keys.containsAlias(alias)) keys.deleteEntry(alias)
        required = false
        generate(false)
    }
    Log.i("us2us.vault.test", "legacy wrapping fixture unlocked-device-required=$required")
    for ((name, plaintext) in values) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD("us2us/item/v1".toByteArray() + name.toByteArray())
        VaultFiles.write(File(directory, "$name.item"), cipher.iv + cipher.doFinal(plaintext))
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
