package com.syconx.us2us.runtime.vault

import android.annotation.SuppressLint
import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.Key
import java.security.KeyFactory
import java.security.PrivateKey
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/** Shared AndroidKeyStore helpers of the vault's keys. */
internal object KeystoreKeys {
    /**
     * Generates a key in StrongBox where the phone has one and falls back to the TEE when it does
     * not ([StrongBoxUnavailableException]); [generate] receives whether to ask for StrongBox.
     */
    fun <T> generatePreferringStrongBox(generate: (strongBox: Boolean) -> T): T = try {
        generate(true)
    } catch (_: StrongBoxUnavailableException) {
        generate(false)
    }

    /**
     * `KeyProperties.SECURITY_LEVEL_*` of a Keystore key (API 31+), or a coarse answer before:
     * [KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT] for keys inside secure hardware,
     * [KeyProperties.SECURITY_LEVEL_SOFTWARE] otherwise (an emulator). The constants are plain
     * ints compiled into this code, so using them before API 31 is safe.
     */
    @SuppressLint("InlinedApi")
    fun securityLevel(key: Key): Int? {
        val info = try {
            when (key) {
                is SecretKey -> SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
                is PrivateKey -> KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
                else -> return null
            }
        } catch (_: Exception) {
            return null
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.securityLevel
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT else KeyProperties.SECURITY_LEVEL_SOFTWARE
        }
    }
}
