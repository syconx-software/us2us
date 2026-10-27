package com.syconx.us2us.runtime.vault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.syconx.us2us.core.vault.VaultHardwareKey
import com.syconx.us2us.core.vault.VaultHardwareKeyException
import com.syconx.us2us.core.vault.VaultHardwareKeyMissingException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Android's existing §5.2 HWPRF: HMAC-SHA256(k, codeKey), evaluated in StrongBox or the TEE.
 * No PIN, derived output or key is persisted here. This key is independent of biometric enrollment.
 * A compromised unlocked OS can invoke it; hardware does not enforce the app's attempt delays.
 */
class KeystoreVaultHardwareKey(private val alias: String) : VaultHardwareKey {
    private val keyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    // Creation still checks the actual key's security level; algorithm availability is not proof
    // of hardware backing (notably on emulators).
    override val isAvailable: Boolean get() = true

    @Synchronized
    override fun createKey() = operation {
        deleteKey()
        val key = KeystoreKeys.generatePreferringStrongBox { strongBox ->
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setKeySize(256)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setIsStrongBoxBacked(strongBox)
                // Android recommends API 35+ only: on 31–34 removing the device screen lock
                // can delete such keys, permanently losing a vault. No extra auth prompt.
                .setUnlockedDeviceRequired(Build.VERSION.SDK_INT >= 35)
                .setUserAuthenticationRequired(false)
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER)
                .apply { init(spec) }.generateKey()
        }
        if (!isHardwareKey(key)) {
            keyStore.deleteEntry(alias)
            throw VaultHardwareKeyException("secure vault hardware unavailable")
        }
    }

    @Synchronized
    override fun evaluate(codeKey: ByteArray): ByteArray = operation {
        require(codeKey.size == 32)
        val key = keyStore.getKey(alias, null) as? SecretKey ?: throw VaultHardwareKeyMissingException()
        if (!isHardwareKey(key)) throw VaultHardwareKeyException("secure vault hardware unavailable")
        Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256).run {
            init(key)
            doFinal(codeKey)
        }
    }

    @Synchronized
    override fun deleteKey() = operation { keyStore.deleteEntry(alias) }

    internal fun securityLevel(): Int? = operation {
        (keyStore.getKey(alias, null) as? SecretKey)?.let(KeystoreKeys::securityLevel)
    }

    private fun isHardwareKey(key: SecretKey): Boolean = key.encoded == null &&
        KeystoreKeys.securityLevel(key) in setOf(
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT, KeyProperties.SECURITY_LEVEL_STRONGBOX,
        )

    private inline fun <T> operation(block: () -> T): T = try {
        block()
    } catch (e: GeneralSecurityException) {
        throw VaultHardwareKeyException("vault hardware operation failed", e)
    } catch (e: ProviderException) {
        throw VaultHardwareKeyException("vault hardware operation failed", e)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}
