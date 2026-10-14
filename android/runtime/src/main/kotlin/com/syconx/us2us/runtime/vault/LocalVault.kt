package com.syconx.us2us.runtime.vault

import com.syconx.us2us.core.vault.BootClock
import com.syconx.us2us.core.vault.DataKey
import com.syconx.us2us.core.vault.InvalidVaultFileException
import com.syconx.us2us.core.vault.LockedOutException
import com.syconx.us2us.core.vault.MalformedSecretCodeException
import com.syconx.us2us.core.vault.SecretCodePolicy
import com.syconx.us2us.core.vault.UnlockAttempts
import com.syconx.us2us.core.vault.UnlockStatus
import com.syconx.us2us.core.vault.Vault
import com.syconx.us2us.core.vault.VaultUnlocker
import com.syconx.us2us.core.vault.VaultWipedException
import com.syconx.us2us.core.vault.WrongSecretCodeException
import java.io.File
import java.io.IOException
import java.time.Clock

/**
 * The on-disk vault of one installation (PROTOCOL.md §5.2): the vault file, the attempt counter
 * and the device secret. Unlocking yields the DEK, from which the database key and the key-slot
 * keys derive (§5.3).
 *
 * The file format and the wrong-attempt rules are the core's ([Vault], [VaultUnlocker]); this
 * class adds the files: `vault.brpv` and `vault-attempts.json` in [directory], both replaced
 * atomically. Android writes format v1 (the hardware step of v2 is not implemented yet), so a v2
 * file reads as [VaultError.Corrupted].
 *
 * One [VaultController] owns these files; the methods are not meant to run concurrently.
 *
 * @param clock the wall clock, used only to tell the user when the next attempt is allowed.
 * @param bootClock time since boot including sleep, which the user cannot set; the delays are
 *   measured with it.
 */
class LocalVault(
    directory: File,
    private val deviceSecrets: DeviceSecretItem,
    private val configuration: Configuration = Configuration(),
    private val clock: Clock = Clock.systemUTC(),
    private val bootClock: BootClock = AndroidBootClock,
) {
    data class Configuration(
        val iterations: Int = Vault.ITERATIONS,
        /** The optional "wipe after 10 wrong attempts" (default off). */
        val wipeAfterFailures: Boolean = false,
    )

    val vaultFile = File(directory, FILE_NAME)
    private val attempts = FileUnlockAttemptStore(File(directory, ATTEMPTS_NAME))

    val exists: Boolean get() = vaultFile.exists()

    /** The format byte of the vault file on disk (1 or 2), or `null` when there is no readable vault file. */
    val formatVersion: Int?
        get() = VaultFiles.read(vaultFile)?.takeIf { it.size > VERSION_OFFSET }?.let { it[VERSION_OFFSET].toInt() }

    /**
     * Creates the vault for a new installation (the code entered twice) under a fresh device
     * secret and returns the fresh DEK.
     *
     * @throws VaultException [VaultError.InvalidNewCode], [VaultError.AlreadyExists], [VaultError.Storage].
     */
    @Synchronized
    fun create(code: String, confirmation: String): SecretKeyMaterial {
        SecretCodeError.check(code, confirmation)?.let { throw VaultException(VaultError.InvalidNewCode(it)) }
        if (exists) throw VaultException(VaultError.AlreadyExists)
        val dek = DataKey.generate()
        val deviceSecret = DeviceSecretItem.generate()
        try {
            storage("device secret") { deviceSecrets.replace(deviceSecret) }
            val vault = Vault.create(code, deviceSecret, dek, configuration.iterations)
            storage("vault") { VaultFiles.write(vaultFile, vault) }
            storage("attempts") { attempts.save(UnlockAttempts()) }
            return dek.toBytes().let { bytes -> SecretKeyMaterial(bytes).also { bytes.fill(0) } }
        } finally {
            dek.wipe()
            deviceSecret.fill(0)
        }
    }

    /** Wrong codes so far, the current wait and what further wrong codes cost, for the unlock screen. */
    @Synchronized
    fun status(): UnlockStatus = storage("attempts") { unlocker().status() }

    /**
     * Opens the vault and returns the DEK, enforcing the attempt delays.
     *
     * The attempt is written to disk as a failure *before* the code is checked and cleared only on
     * success; otherwise killing the app during the slow key derivation would allow unlimited
     * guesses without delays. Input that is not six digits is refused first and not counted, and
     * so are failures that are not a wrong code (a missing device secret, a Keystore or file
     * error, a corrupt file).
     *
     * @throws VaultException [VaultError.MalformedCode], [VaultError.NotFound], [VaultError.LockedOut],
     *   [VaultError.WrongCode], [VaultError.Wiped], [VaultError.DeviceSecretMissing],
     *   [VaultError.Corrupted], [VaultError.Storage].
     */
    @Synchronized
    fun unlock(code: String): SecretKeyMaterial {
        if (!SecretCodePolicy.isWellFormed(code)) throw VaultException(VaultError.MalformedCode)
        val vault = storage("vault") { VaultFiles.read(vaultFile) } ?: throw VaultException(VaultError.NotFound)
        val unlocker = unlocker()
        storage("attempts") { unlocker.nextAttemptAllowedAt() }?.let { throw VaultException(VaultError.LockedOut(it)) }
        val deviceSecret = loadDeviceSecret()
        try {
            val dek = unlocker.unlock(vault, code, deviceSecret)
            try {
                return dek.toBytes().let { bytes -> SecretKeyMaterial(bytes).also { bytes.fill(0) } }
            } finally {
                dek.wipe()
            }
        } catch (e: WrongSecretCodeException) {
            throw VaultException(VaultError.WrongCode, e)
        } catch (e: VaultWipedException) {
            // The unlocker saves a cleared counter after the wipe; nothing of the vault may remain.
            try {
                attempts.delete()
            } catch (_: IOException) {
                // A cleared counter on its own reveals nothing.
            }
            throw VaultException(VaultError.Wiped, e)
        } catch (e: LockedOutException) {
            throw VaultException(VaultError.LockedOut(e.until), e)
        } catch (e: MalformedSecretCodeException) {
            throw VaultException(VaultError.MalformedCode, e)
        } catch (e: InvalidVaultFileException) {
            throw VaultException(VaultError.Corrupted, e)
        } catch (e: IllegalArgumentException) {
            throw VaultException(VaultError.Corrupted, e)
        } catch (e: IOException) {
            throw VaultException(VaultError.Storage("unlock: ${e.message}"), e)
        } finally {
            deviceSecret.fill(0)
        }
    }

    /**
     * Re-wraps the DEK under a new code (instant; no data is re-encrypted). The current code is a
     * counted attempt, and the optional wipe can fire here as on the lock screen.
     */
    @Synchronized
    fun changeCode(current: String, new: String, confirmation: String) {
        SecretCodeError.check(new, confirmation)?.let { throw VaultException(VaultError.InvalidNewCode(it)) }
        val dek = unlock(current)
        try {
            val deviceSecret = loadDeviceSecret()
            try {
                val vault = dek.withDataKey { key -> Vault.create(new, deviceSecret, key, configuration.iterations) }
                storage("vault") { VaultFiles.write(vaultFile, vault) }
            } finally {
                deviceSecret.fill(0)
            }
        } finally {
            dek.wipe()
        }
    }

    /**
     * Crypto-shreds the vault: without the device secret and the vault file the DEK, and
     * everything encrypted under it, is unrecoverable.
     */
    @Synchronized
    fun destroy() {
        storage("destroy") {
            deviceSecrets.destroy()
            VaultFiles.delete(vaultFile)
            attempts.delete()
        }
    }

    private fun unlocker() = VaultUnlocker(
        attempts = attempts,
        clock = clock,
        bootClock = bootClock,
        wipeAfterFailures = configuration.wipeAfterFailures,
        wipe = ::destroy,
    )

    private fun loadDeviceSecret(): ByteArray =
        storage("device secret") { deviceSecrets.load() } ?: throw VaultException(VaultError.DeviceSecretMissing)

    private inline fun <T> storage(what: String, block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw VaultException(VaultError.Storage("$what: ${e.message}"), e)
    }

    companion object {
        const val FILE_NAME = "vault.brpv"
        const val ATTEMPTS_NAME = "vault-attempts.json"

        /** `"BRPV" ‖ version`: the format byte follows the magic. */
        private const val VERSION_OFFSET = 4
    }
}
