package com.syconx.us2us.runtime.vault

import com.syconx.us2us.core.vault.BootClock
import com.syconx.us2us.core.vault.UnlockStatus
import com.syconx.us2us.core.vault.Vault
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

/**
 * The vault lifecycle of PROTOCOL.md §5.2 with the owner's rules: a six-digit code entered twice at
 * creation, attempts recorded as failures *before* the code is checked ([LocalVault]), increasing
 * delays after two wrong codes, an optional wipe after 10, biometric quick unlock through a
 * Keystore key bound to the current biometric enrollment, re-wrapping on a code change, and
 * crypto-shredding for "forgot code" / "erase everything". Vaults are format v1 on Android (the
 * hardware step of v2 is not implemented yet).
 *
 * The controller never keeps the DEK: every successful unlock returns a fresh [SecretKeyMaterial]
 * that the caller wipes when the app locks.
 *
 * Every call is serialized; the slow ones (key derivation takes about a second by design) run on
 * [dispatcher]. While the biometric prompt is up other calls go on, and a second unlock is refused
 * with [UnlockError.Busy].
 *
 * @param clock the wall clock, for the 7-day rule and for telling the user when the next attempt is
 *   allowed.
 * @param bootClock time since boot including sleep; the wrong-code delays are measured with it.
 */
class VaultController(
    private val directories: AppDirectories,
    private val secureItems: SecureItemStore,
    private val biometrics: BiometricKeyProtector,
    private val clock: Clock = Clock.systemUTC(),
    private val configuration: Configuration = Configuration(),
    private val bootClock: BootClock = AndroidBootClock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    data class Configuration(val iterations: Int = Vault.ITERATIONS)

    private val lock = Any()
    private val sidecarFile: File get() = File(directories.vault, SIDECAR_NAME)

    /** Written under [lock] only; read without it by the cheap getters, which must not wait for a key derivation. */
    @Volatile private var sidecar: VaultSidecar = loadSidecar()
    private var unlockInFlight = false

    private fun vault(): LocalVault = LocalVault(
        directory = directories.vault,
        deviceSecrets = DeviceSecretItem(secureItems),
        configuration = LocalVault.Configuration(configuration.iterations, sidecar.wipeAfterTenFailures),
        clock = clock,
        bootClock = bootClock,
    )

    /** Whether a vault file exists; a file check that never waits for a running unlock. */
    val hasVault: Boolean get() = File(directories.vault, LocalVault.FILE_NAME).exists()

    // Create

    /**
     * Creates the vault for a new installation (the code entered twice) and returns the fresh DEK.
     * Leftovers of an earlier installation (a device secret, a biometric key) are removed first.
     *
     * @throws VaultException
     */
    suspend fun create(code: String, confirmation: String): SecretKeyMaterial = serialized {
        val vault = vault()
        if (vault.exists) throw VaultException(VaultError.AlreadyExists)
        SecretCodeError.check(code, confirmation)?.let { throw VaultException(VaultError.InvalidNewCode(it)) }
        removeLeftovers()
        val dek = vault().create(code, confirmation)
        sidecar = VaultSidecar(lastCodeEntry = clock.instant())
        saveSidecar()
        VaultLog.info("vault created, format 1")
        dek
    }

    // Code unlock

    /**
     * Wrong codes so far, the current wait and what further wrong codes cost.
     *
     * @throws VaultException [VaultError.Storage] when the attempt counter cannot be read.
     */
    suspend fun unlockStatus(): UnlockStatus = serialized { vault().status() }

    /**
     * Opens the vault with the code. Throws `UnlockError.Vault(VaultError.Wiped)` when the optional
     * wipe fired: the caller must then erase everything else.
     *
     * @throws UnlockException
     */
    suspend fun unlock(code: String): SecretKeyMaterial = serialized { unlockLocked(code) }

    private fun unlockLocked(code: String): SecretKeyMaterial {
        if (unlockInFlight) throw UnlockException(UnlockError.Busy)
        unlockInFlight = true
        try {
            val dek = try {
                vault().unlock(code)
            } catch (e: VaultException) {
                if (e.error == VaultError.Wiped) removeLeftovers()
                VaultLog.notice("code unlock refused")
                throw UnlockException(UnlockError.Vault(e.error), e)
            }
            sidecar = sidecar.copy(
                lastCodeEntry = clock.instant(),
                quickUnlock = sidecar.quickUnlock?.copy(failures = 0),
            )
            refreshQuickUnlockWrap(dek)
            saveSidecar()
            return dek
        } finally {
            unlockInFlight = false
        }
    }

    /**
     * Re-creates the biometric wrap after a code unlock when the user wants quick unlock but the
     * wrap is missing, broken, or its key was invalidated by an enrollment change (§5.2: "the code
     * is still required … after the enrolled biometrics change"; entering it re-arms quick unlock).
     */
    private fun refreshQuickUnlockWrap(dek: SecretKeyMaterial) {
        if (!sidecar.quickUnlockWanted) return
        if (!biometrics.availability().isUsable) return
        val record = sidecar.quickUnlock
        if (record != null && !record.needsRewrap && biometrics.isKeyValid(record.wrappedKey)) return
        sidecar = try {
            val wrapped = dek.withBytes { biometrics.wrap(it) }
            sidecar.copy(quickUnlock = QuickUnlockRecord(wrapped))
        } catch (_: BiometricException) {
            sidecar.copy(quickUnlock = null)
        }
    }

    // Biometric quick unlock

    suspend fun quickUnlockStatus(): QuickUnlockStatus = serialized { quickUnlockStatusLocked() }

    private fun quickUnlockStatusLocked(): QuickUnlockStatus {
        if (sidecar.quickUnlockWanted && sidecar.quickUnlock == null) return QuickUnlockStatus.CodeRequired(CodeRequiredReason.KEY_PROBLEM)
        val record = sidecar.quickUnlock
        return QuickUnlockPolicy.status(record, sidecar.lastCodeEntry, biometrics.availability(), clock.instant()) {
            record != null && biometrics.isKeyValid(record.wrappedKey)
        }
    }

    /**
     * Turns quick unlock on. The code is required to set it up (§5.2), and entering it is a
     * normal, counted unlock attempt.
     *
     * @throws UnlockException
     */
    suspend fun enableQuickUnlock(code: String) = serialized {
        if (!biometrics.availability().isUsable) throw UnlockException(UnlockError.CodeRequired(CodeRequiredReason.UNAVAILABLE))
        val key = unlockLocked(code)
        try {
            val wrapped = try {
                key.withBytes { biometrics.wrap(it) }
            } catch (e: BiometricException) {
                throw UnlockException(UnlockError.Biometric(e.error), e)
            }
            sidecar = sidecar.copy(quickUnlock = QuickUnlockRecord(wrapped), quickUnlockWanted = true)
            saveSidecar()
        } finally {
            key.wipe()
        }
    }

    suspend fun disableQuickUnlock() = serialized {
        biometrics.deleteKey()
        sidecar = sidecar.copy(quickUnlock = null, quickUnlockWanted = false)
        saveSidecar()
    }

    /**
     * Shows the biometric prompt and returns the DEK. A failed match is counted *before* the prompt
     * appears (killing the app during the prompt gains nothing) and taken back on success or
     * cancel; after 5 the code is required.
     *
     * @param reason the prompt's title ("Unlock us2us").
     * @param cancelTitle its negative button, back to the code keypad ("Use code").
     * @throws UnlockException
     */
    suspend fun unlockWithBiometrics(reason: String, cancelTitle: String): SecretKeyMaterial {
        val (record, previousFailures) = serialized {
            when (val status = quickUnlockStatusLocked()) {
                QuickUnlockStatus.NotEnabled -> throw UnlockException(UnlockError.QuickUnlockNotEnabled)
                is QuickUnlockStatus.CodeRequired -> throw UnlockException(UnlockError.CodeRequired(status.reason))
                is QuickUnlockStatus.Available -> Unit
            }
            val current = sidecar.quickUnlock
            if (unlockInFlight || current == null) throw UnlockException(UnlockError.Busy)
            unlockInFlight = true
            val counted = current.copy(failures = current.failures + 1)
            sidecar = sidecar.copy(quickUnlock = counted)
            saveSidecar()
            counted to current.failures
        }
        try {
            val dek = try {
                biometrics.unwrap(record.wrappedKey, reason, cancelTitle)
            } catch (e: BiometricException) {
                throw serialized { refuseBiometricUnlock(e, previousFailures) }
            } catch (e: CancellationException) {
                // The app took the prompt down itself (it locked or left): a cancel, not a failed match.
                withContext(NonCancellable) {
                    serialized { updateRecord { it.copy(failures = previousFailures) } }
                }
                throw e
            }
            try {
                if (dek.size != Vault.DEVICE_SECRET_LENGTH) {
                    throw serialized { refuseBiometricUnlock(BiometricException(BiometricError.KEY_FAILURE), previousFailures) }
                }
                serialized { updateRecord { it.copy(failures = 0) } }
                return SecretKeyMaterial(dek)
            } finally {
                dek.fill(0)
            }
        } finally {
            synchronized(lock) { unlockInFlight = false }
        }
    }

    /** Books a biometric refusal and returns what to throw. */
    private fun refuseBiometricUnlock(failure: BiometricException, previousFailures: Int): UnlockException {
        when (failure.error) {
            BiometricError.FAILED -> Unit // counted
            BiometricError.CANCELLED, BiometricError.LOCKOUT, BiometricError.UNAVAILABLE ->
                updateRecord { it.copy(failures = previousFailures) }
            BiometricError.INVALIDATED -> {
                biometrics.deleteKey()
                sidecar = sidecar.copy(quickUnlock = null)
                saveSidecar()
            }
            BiometricError.KEY_FAILURE -> updateRecord { it.copy(failures = previousFailures, needsRewrap = true) }
        }
        VaultLog.notice("biometric unlock refused")
        val error = when (failure.error) {
            BiometricError.INVALIDATED -> UnlockError.CodeRequired(CodeRequiredReason.ENROLLMENT_CHANGED)
            BiometricError.KEY_FAILURE -> UnlockError.CodeRequired(CodeRequiredReason.KEY_PROBLEM)
            BiometricError.LOCKOUT -> UnlockError.CodeRequired(CodeRequiredReason.SYSTEM_LOCKOUT)
            BiometricError.UNAVAILABLE -> UnlockError.CodeRequired(CodeRequiredReason.UNAVAILABLE)
            BiometricError.FAILED ->
                if ((sidecar.quickUnlock?.failures ?: 0) >= QuickUnlockPolicy.MAX_FAILURES) {
                    UnlockError.CodeRequired(CodeRequiredReason.TOO_MANY_FAILURES)
                } else {
                    UnlockError.Biometric(BiometricError.FAILED)
                }
            BiometricError.CANCELLED -> UnlockError.Biometric(BiometricError.CANCELLED)
        }
        return UnlockException(error, failure)
    }

    /** Changes the quick-unlock record, if there still is one (it may have been turned off meanwhile). */
    private fun updateRecord(change: (QuickUnlockRecord) -> QuickUnlockRecord) {
        val record = sidecar.quickUnlock ?: return
        sidecar = sidecar.copy(quickUnlock = change(record))
        saveSidecar()
    }

    // Settings and code change

    val wipeAfterTenFailures: Boolean get() = sidecar.wipeAfterTenFailures

    /** The optional "wipe after 10 wrong codes" (default off). The UI offers it only while unlocked. */
    suspend fun setWipeAfterTenFailures(enabled: Boolean) = serialized {
        sidecar = sidecar.copy(wipeAfterTenFailures = enabled)
        saveSidecar()
    }

    /**
     * Re-wraps the DEK under a new code (instant; no data is re-encrypted). The current code is a
     * counted attempt. Biometric quick unlock keeps working: it wraps the DEK, which does not
     * change. If the optional "wipe after 10" fires here, everything is shredded as on the lock
     * screen (the biometric key and its wrap of the DEK included) and the error is
     * [VaultError.Wiped]: the caller must erase the local data.
     *
     * @throws VaultException
     */
    suspend fun changeCode(current: String, new: String, confirmation: String) = serialized {
        try {
            vault().changeCode(current, new, confirmation)
        } catch (e: VaultException) {
            if (e.error == VaultError.Wiped) removeLeftovers()
            throw e
        }
        sidecar = sidecar.copy(lastCodeEntry = clock.instant())
        saveSidecar()
        VaultLog.info("code changed")
    }

    // Crypto-shredding

    /**
     * Destroys every key that protects local data: the device secret and its Keystore key, the
     * vault file and attempt counter, the biometric Keystore key and its wrap. Without them the
     * database, key slots and media are unreadable ciphertext; [AppDirectories.eraseAll] then
     * removes it.
     */
    suspend fun destroyKeys() = serialized {
        try {
            vault().destroy()
        } catch (_: VaultException) {
            // removeLeftovers deletes the device secret's Keystore key as well.
        }
        removeLeftovers()
        VaultLog.info("keys destroyed")
    }

    private fun removeLeftovers() {
        try {
            secureItems.deleteAll()
        } catch (_: IOException) {
            VaultLog.error("secure items not deleted")
        }
        biometrics.deleteKey()
        sidecar = VaultSidecar()
        try {
            VaultFiles.delete(sidecarFile)
        } catch (_: IOException) {
            VaultLog.error("settings not deleted")
        }
    }

    // Sidecar file

    private fun loadSidecar(): VaultSidecar = try {
        VaultFiles.read(sidecarFile)?.let(VaultSidecar::fromJson) ?: VaultSidecar()
    } catch (_: IOException) {
        VaultSidecar()
    }

    private fun saveSidecar() {
        try {
            VaultFiles.write(sidecarFile, sidecar.toJson())
        } catch (_: IOException) {
            VaultLog.error("settings not saved")
        }
    }

    private suspend fun <T> serialized(block: () -> T): T = withContext(dispatcher) { synchronized(lock) { block() } }

    companion object {
        const val SIDECAR_NAME = "vault-settings.json"
    }
}
