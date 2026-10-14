package com.syconx.us2us.ui.env

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.os.Build
import com.syconx.us2us.domain.Biometry

/**
 * The biometrics this phone has (iOS `Biometry`): a fingerprint sensor or face recognition. 2.3,
 * the lock and 7.1 offer biometric unlock only when a strong biometric is enrolled ([enrolled]);
 * without one only the code is used. The debug fixtures' `biometry` knob, when set, wins at the
 * call site.
 */
object DeviceBiometrics {
    /** The sensor, whether or not anything is enrolled yet (iOS `Biometry.hardware`). */
    fun hardware(context: Context): Biometry {
        val packages = context.packageManager
        val face = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && packages.hasSystemFeature(PackageManager.FEATURE_FACE)
        val fingerprint = packages.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
        return when {
            fingerprint -> Biometry.Fingerprint
            face -> Biometry.Face
            else -> Biometry.None
        }
    }

    /**
     * What the lock can use right now (iOS `Biometry.enrolled`): a strong biometric that is enrolled,
     * else [Biometry.None]. Below Android 11 the platform cannot say which, so the sensor counts.
     */
    fun enrolled(context: Context): Biometry {
        val kind = hardware(context)
        if (kind == Biometry.None || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return kind
        val manager = context.getSystemService(BiometricManager::class.java) ?: return Biometry.None
        val usable = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        return if (usable) kind else Biometry.None
    }
}
