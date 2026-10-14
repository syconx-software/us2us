package com.syconx.us2us.runtime.vault

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager.Authenticators
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds
import android.hardware.biometrics.BiometricPrompt as FrameworkPrompt

/**
 * Biometric quick unlock on the phone: the RSA key pair in the Keystore, wrapping without a
 * prompt, and an unwrap authorized by a real fingerprint match.
 *
 * Needs a secure lock screen and an enrolled fingerprint; skipped otherwise. On the emulator the
 * match comes from the host while the prompt is up: `adb -e emu finger touch 1`.
 */
@RunWith(AndroidJUnit4::class)
class BiometricDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alias = "us2us.biometric-unlock.test"
    private lateinit var root: File
    private lateinit var items: KeystoreItemStore

    @Before
    fun setUp() {
        root = File(context.noBackupFilesDir, "biometric-device-test").also { it.deleteRecursively() }
        items = KeystoreItemStore(File(root, "items"), "us2us.secure-items.biometric-test")
    }

    @After
    fun tearDown() {
        KeystoreBiometricProtector(context, presenter = { _, _, _ -> throw BiometricException(BiometricError.CANCELLED) }, alias = alias).deleteKey()
        items.deleteAll()
        root.deleteRecursively()
    }

    @Test
    fun wrappingNeedsNoPromptAndTheKeyIsHardwareBound() {
        val protector = KeystoreBiometricProtector(context, presenter = { _, _, _ -> error("no prompt while wrapping") }, alias = alias)
        val availability = protector.availability()
        Log.i(TAG, "availability: $availability")
        assumeTrue("no enrolled strong biometrics", availability.isUsable)
        val wrapped = protector.wrap(ByteArray(32) { it.toByte() })
        assertEquals(256, wrapped.size)
        assertTrue(protector.isKeyValid(wrapped))
        Log.i(TAG, "biometric key: ${securityLevelName(protector.securityLevel())}")
        protector.deleteKey()
        assertFalse(protector.isKeyValid(wrapped))
    }

    @Test
    fun aFingerprintMatchUnlocksTheVault(): Unit = runBlocking {
        // androidx.test's empty activity, declared by its own manifest.
        val host = Intent().setClassName(context.packageName, "androidx.test.core.app.InstrumentationActivityInvoker\$EmptyActivity")
        val scenario = ActivityScenario.launch<Activity>(host)
        try {
            val activity = AtomicReference<Activity>()
            scenario.onActivity { activity.set(it) }
            val protector = KeystoreBiometricProtector(context, FrameworkBiometricPresenter { activity.get() }, alias)
            assumeTrue("no enrolled strong biometrics", protector.availability().isUsable)
            val directories = AppDirectories(File(root, "us2us")).also { it.prepare() }
            val vault = VaultController(directories, items, protector, configuration = VaultController.Configuration(1000))
            val created = vault.create(CODE, CODE)
            vault.enableQuickUnlock(CODE)
            assertTrue(vault.quickUnlockStatus() is QuickUnlockStatus.Available)
            Log.i(TAG, "prompt up: touch the sensor (adb -e emu finger touch 1)")
            val unlocked = withTimeout(90.seconds) { vault.unlockWithBiometrics("Unlock us2us", "Use code") }
            assertTrue(unlocked.matches(created))
            assertTrue(vault.quickUnlockStatus() is QuickUnlockStatus.Available)
            Log.i(TAG, "unlocked with the fingerprint")
        } finally {
            scenario.close()
        }
    }

    /**
     * Host-driven (`-e host mismatch`): an unknown finger while the prompt is up
     * (`adb -e emu finger touch 2`), then Back. Giving up after a mismatch is a failed attempt and
     * stays counted; a plain cancel is not.
     */
    @Test
    fun aMismatchThenCancelIsAFailedAttempt(): Unit = runBlocking {
        assumeTrue("host-driven", hostStep() == "mismatch")
        withHostActivity { vault, _ ->
            Log.i(TAG, "prompt up: touch an unknown finger, then press Back")
            try {
                withTimeout(90.seconds) { vault.unlockWithBiometrics("Unlock us2us", "Use code") }
                fail("unlocked without a match")
            } catch (e: UnlockException) {
                assertEquals(UnlockError.Biometric(BiometricError.FAILED), e.error)
            }
            val sidecar = VaultSidecar.fromJson(File(File(root, "us2us/vault"), VaultController.SIDECAR_NAME).readBytes())
            assertEquals(1, sidecar.quickUnlock?.failures)
            Log.i(TAG, "mismatch then cancel counted as a failed attempt")
        }
    }

    /**
     * Host-driven (`-e host enroll`): another fingerprint is enrolled while this test waits. The
     * Keystore invalidates the key, the lock screen asks for the code, and the code re-arms
     * quick unlock with a new key.
     */
    @Test
    fun anEnrollmentChangeAsksForTheCodeOnce(): Unit = runBlocking {
        assumeTrue("host-driven", hostStep() == "enroll")
        val protector = KeystoreBiometricProtector(context, presenter = { _, _, _ -> error("no prompt here") }, alias = alias)
        assumeTrue("no enrolled strong biometrics", protector.availability().isUsable)
        val vault = VaultController(AppDirectories(File(root, "us2us")).also { it.prepare() }, items, protector, configuration = VaultController.Configuration(1000))
        vault.create(CODE, CODE)
        vault.enableQuickUnlock(CODE)
        assertTrue(vault.quickUnlockStatus() is QuickUnlockStatus.Available)
        Log.i(TAG, "waiting: enroll another fingerprint now")
        val changed = QuickUnlockStatus.CodeRequired(CodeRequiredReason.ENROLLMENT_CHANGED)
        withTimeout(240.seconds) {
            while (vault.quickUnlockStatus() != changed) delay(1000)
        }
        Log.i(TAG, "enrollment change seen")
        vault.unlock(CODE)
        assertTrue(vault.quickUnlockStatus() is QuickUnlockStatus.Available)
        Log.i(TAG, "code re-armed quick unlock")
    }

    private fun hostStep(): String? = InstrumentationRegistry.getArguments().getString("host")

    private suspend fun withHostActivity(body: suspend (VaultController, Activity) -> Unit) {
        val host = Intent().setClassName(context.packageName, "androidx.test.core.app.InstrumentationActivityInvoker\$EmptyActivity")
        val scenario = ActivityScenario.launch<Activity>(host)
        try {
            val activity = AtomicReference<Activity>()
            scenario.onActivity { activity.set(it) }
            val protector = KeystoreBiometricProtector(context, FrameworkBiometricPresenter { activity.get() }, alias)
            assumeTrue("no enrolled strong biometrics", protector.availability().isUsable)
            val vault = VaultController(AppDirectories(File(root, "us2us")).also { it.prepare() }, items, protector, configuration = VaultController.Configuration(1000))
            vault.create(CODE, CODE)
            vault.enableQuickUnlock(CODE)
            body(vault, activity.get())
        } finally {
            scenario.close()
        }
    }

    private companion object {
        const val TAG = "us2us.vault.test"
        const val CODE = "482917"
    }
}

/**
 * The platform `BiometricPrompt` over any [Activity], for this test APK, which has no
 * FragmentActivity to host the androidx prompt the app uses. Same Keystore key, same
 * `CryptoObject`, same Class 3 rule and the same outcome mapping.
 */
private class FrameworkBiometricPresenter(private val activity: () -> Activity?) : BiometricPresenter {
    override suspend fun authenticate(cipher: Cipher, title: String, negativeButton: String): Cipher = withContext(Dispatchers.Main) {
        val host = activity() ?: throw BiometricException(BiometricError.CANCELLED)
        suspendCancellableCoroutine { continuation ->
            val cancel = CancellationSignal()
            var mismatches = 0
            var finished = false
            fun fail(code: Int) {
                if (finished) return
                finished = true
                continuation.resumeWithException(BiometricException(BiometricOutcomes.map(code, mismatches)))
            }
            val builder = FrameworkPrompt.Builder(host)
                .setTitle(title)
                .setNegativeButton(negativeButton, host.mainExecutor) { _, _ -> fail(androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG)
            builder.setConfirmationRequired(false)
            builder.build().authenticate(
                FrameworkPrompt.CryptoObject(cipher),
                cancel,
                host.mainExecutor,
                object : FrameworkPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: FrameworkPrompt.AuthenticationResult) {
                        if (finished) return
                        finished = true
                        val authorized = result.cryptoObject?.cipher
                        if (authorized == null) {
                            continuation.resumeWithException(BiometricException(BiometricError.KEY_FAILURE))
                        } else {
                            continuation.resume(authorized)
                        }
                    }

                    override fun onAuthenticationFailed() {
                        mismatches += 1
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = fail(errorCode)
                },
            )
            continuation.invokeOnCancellation { cancel.cancel() }
        }
    }
}
