package com.syconx.us2us.runtime.vault

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Hands the biometric prompt the activity it is shown over. */
fun interface ActivityProvider {
    /** The app's resumed activity, or `null` when none is in the foreground. */
    fun resumedActivity(): FragmentActivity?
}

/** Shows a biometric prompt that authorizes [cipher] for one operation and returns it. */
fun interface BiometricPresenter {
    /** @throws BiometricException when the prompt ends without a match. */
    suspend fun authenticate(cipher: Cipher, title: String, negativeButton: String): Cipher
}

/**
 * The production protector: an RSA-2048 key pair in the AndroidKeyStore (StrongBox where the phone
 * has it, else the TEE) whose private key needs a strong biometric match for every use
 * (`setUserAuthenticationRequired`, `AUTH_BIOMETRIC_STRONG` with no timeout; on API 28–29 a
 * validity of -1) and is invalidated as soon as a face or finger is added or removed
 * (`setInvalidatedByBiometricEnrollment`). It never leaves the hardware and is unusable on any
 * other phone.
 *
 * The DEK is wrapped with RSA-OAEP (SHA-256, MGF1-SHA-1) under the public key, so wrapping needs
 * no prompt, as on iOS where the Secure Enclave key wraps with ECIES; only unwrapping needs the
 * match, through a `BiometricPrompt` with a `CryptoObject` that allows Class 3 biometrics only
 * (no device-credential fallback: the app's own code keypad is the fallback).
 */
class KeystoreBiometricProtector(
    private val context: Context,
    private val presenter: BiometricPresenter,
    private val alias: String = DEFAULT_ALIAS,
) : BiometricKeyProtector {
    constructor(context: Context, activities: ActivityProvider) : this(context, FragmentBiometricPresenter(activities))

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    override fun availability(): BiometricAvailability {
        val kind = hardwareKind()
        return when (BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS ->
                BiometricAvailability(if (kind == BiometryKind.NONE) BiometryKind.FINGERPRINT else kind, enrolled = true)
            else -> BiometricAvailability(kind, enrolled = false)
        }
    }

    /** Fingerprint where the phone has a sensor, else face, else iris (the strong modalities). */
    private fun hardwareKind(): BiometryKind {
        val packages = context.packageManager
        return when {
            packages.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) -> BiometryKind.FINGERPRINT
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && packages.hasSystemFeature(PackageManager.FEATURE_FACE) -> BiometryKind.FACE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && packages.hasSystemFeature(PackageManager.FEATURE_IRIS) -> BiometryKind.IRIS
            else -> BiometryKind.NONE
        }
    }

    @Synchronized
    override fun wrap(secret: ByteArray): ByteArray {
        deleteKey()
        try {
            val publicKey = KeystoreKeys.generatePreferringStrongBox { strongBox -> generateKeyPair(strongBox) }.public
            // Encrypt in software with the public key alone: no Keystore operation, no prompt.
            val portable = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_RSA).generatePublic(X509EncodedKeySpec(publicKey.encoded))
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, portable, OAEP)
            return cipher.doFinal(secret)
        } catch (e: GeneralSecurityException) {
            deleteKey()
            throw BiometricException(BiometricError.KEY_FAILURE, e)
        } catch (e: ProviderException) {
            deleteKey()
            throw BiometricException(BiometricError.KEY_FAILURE, e)
        } catch (e: IllegalStateException) {
            // No secure lock screen or nothing enrolled: the Keystore refuses the key.
            deleteKey()
            throw BiometricException(BiometricError.KEY_FAILURE, e)
        }
    }

    override fun isKeyValid(wrapped: ByteArray): Boolean = try {
        decryptCipher()
        true
    } catch (e: BiometricException) {
        // Only a key that is gone or invalidated counts; other trouble shows at the prompt.
        e.error != BiometricError.INVALIDATED
    }

    override suspend fun unwrap(wrapped: ByteArray, reason: String, cancelTitle: String): ByteArray {
        val cipher = withContext(Dispatchers.IO) { decryptCipher() }
        val authorized = presenter.authenticate(cipher, reason, cancelTitle)
        return withContext(Dispatchers.IO) {
            try {
                authorized.doFinal(wrapped)
            } catch (e: KeyPermanentlyInvalidatedException) {
                throw BiometricException(BiometricError.INVALIDATED, e)
            } catch (e: GeneralSecurityException) {
                throw BiometricException(BiometricError.KEY_FAILURE, e)
            } catch (e: ProviderException) {
                throw BiometricException(BiometricError.KEY_FAILURE, e)
            } catch (e: IllegalStateException) {
                throw BiometricException(BiometricError.KEY_FAILURE, e)
            }
        }
    }

    @Synchronized
    override fun deleteKey() {
        try {
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        } catch (_: GeneralSecurityException) {
            // Nothing left to protect if the entry cannot even be listed.
        }
    }

    /** The private key's cipher, ready for the prompt to authorize. */
    private fun decryptCipher(): Cipher {
        try {
            val key = keyStore.getKey(alias, null) as? PrivateKey ?: throw BiometricException(BiometricError.INVALIDATED)
            return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, OAEP) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw BiometricException(BiometricError.INVALIDATED, e)
        } catch (e: GeneralSecurityException) {
            throw BiometricException(BiometricError.KEY_FAILURE, e)
        } catch (e: ProviderException) {
            throw BiometricException(BiometricError.KEY_FAILURE, e)
        }
    }

    private fun generateKeyPair(strongBox: Boolean) = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE).apply {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .setIsStrongBoxBacked(strongBox)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            spec.setUserAuthenticationValidityDurationSeconds(-1)
        }
        initialize(spec.build())
    }.generateKeyPair()

    /** The security level of the key pair (`KeyProperties.SECURITY_LEVEL_*`), or `null` without one. */
    fun securityLevel(): Int? = (keyStore.getKey(alias, null) as? PrivateKey)?.let(KeystoreKeys::securityLevel)

    companion object {
        const val DEFAULT_ALIAS = "us2us.biometric-unlock"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"

        /** SHA-256 for OAEP and SHA-1 for MGF1: the MGF1 digest every Keystore version accepts. */
        private val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    }
}

/**
 * The androidx `BiometricPrompt` over the app's resumed [FragmentActivity]: Class 3 biometrics
 * only, the negative button as the way back to the code keypad, no confirmation step after a
 * face match. No activity in the foreground is a system cancel.
 */
class FragmentBiometricPresenter(private val activities: ActivityProvider) : BiometricPresenter {
    override suspend fun authenticate(cipher: Cipher, title: String, negativeButton: String): Cipher = withContext(Dispatchers.Main.immediate) {
        val activity = activities.resumedActivity() ?: throw BiometricException(BiometricError.CANCELLED)
        suspendCancellableCoroutine { continuation ->
            var mismatches = 0
            var finished = false
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
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

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (finished) return
                    finished = true
                    continuation.resumeWithException(BiometricException(BiometricOutcomes.map(errorCode, mismatches)))
                }
            }
            val prompt = BiometricPrompt(activity, callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setNegativeButtonText(negativeButton)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setConfirmationRequired(false)
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            continuation.invokeOnCancellation {
                Handler(Looper.getMainLooper()).post {
                    finished = true
                    prompt.cancelAuthentication()
                }
            }
        }
    }
}
