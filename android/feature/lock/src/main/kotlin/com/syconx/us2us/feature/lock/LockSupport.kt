package com.syconx.us2us.feature.lock

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.StringRes
import com.syconx.us2us.domain.Biometry
import com.syconx.us2us.domain.harness.BiometricCheck
import com.syconx.us2us.ui.R
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlinx.coroutines.suspendCancellableCoroutine

// Helpers only 03 · Kilit uses.

/** Test tags of the lock: the iOS accessibility identifiers, plus layout probes. */
object LockTags {
    const val USE_CODE = "lock.useCode"
    const val BIOMETRIC_DISC = "lock.biometricDisc"
    const val BRAND = "lock.brand"
    const val STATUS = "lock.status"
    const val BADGE = "lock.badge"
    const val TITLE = "lock.title"
    const val DOTS = "lock.dots"
    const val MESSAGE = "lock.message"
    const val KEYPAD = "lock.keypad"
    const val FORGOT = "lock.forgot"
    const val DEMO_HINT = "lock.demoHint"
    const val FORGOT_SHEET = "lock.forgot.sheet"
    const val FORGOT_TITLE = "lock.forgot.title"
    const val FORGOT_RESTORE = "lock.forgot.restore"
    const val FORGOT_ERASE = "lock.forgot.erase"
    const val FORGOT_RETRY = "lock.forgot.retry"
    const val ERASE_CONFIRM = "lock.erase.confirm"
    const val ERASE_CANCEL = "lock.erase.cancel"
    const val WIPED_BODY = "lock.wiped.body"
    const val WIPED_RESTORE = "lock.wiped.restore"
    const val WIPED_START_OVER = "lock.wiped.startOver"
}

// MARK: - Wait times

object LockFormat {
    /** “1 dakika”, “5 dakika”, “1 saat” / “1 minute”, “5 minutes”, “1 hour”. */
    fun waitDuration(duration: Duration, locale: Locale): String {
        val seconds = max(duration.toDouble(DurationUnit.SECONDS), 60.0)
        val format = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE)
        val measure = if (seconds >= 3_600) {
            Measure(Math.round(seconds / 3_600), MeasureUnit.HOUR)
        } else {
            Measure(Math.round(seconds / 60), MeasureUnit.MINUTE)
        }
        return format.format(measure)
    }
}

// MARK: - Biometry

/** The prompt reason for [this] kind of biometrics. */
@StringRes
@Suppress("UnusedReceiverParameter")
internal fun Biometry.reasonText(): Int = R.string.android_biometric_reason

/**
 * The system biometric prompt as a [BiometricCheck] (strong biometrics only, no device-credential
 * fallback; the negative button cancels). For the fixtures service in debug builds; [activity]
 * returns the activity in front, or `null` when there is none (the check then fails).
 */
class PlatformBiometricCheck(private val activity: () -> Activity?) : BiometricCheck {
    override suspend fun authenticate(reason: String, cancelTitle: String?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val host = activity() ?: return false
        return suspendCancellableCoroutine { continuation ->
            val cancel = CancellationSignal()
            val executor = host.mainExecutor
            val builder = BiometricPrompt.Builder(host)
                .setTitle(APP_NAME)
                .setDescription(reason)
                .setNegativeButton(cancelTitle ?: host.getString(android.R.string.cancel), executor) { _, _ ->
                    if (continuation.isActive) continuation.resume(false)
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            }
            builder.build().authenticate(
                cancel,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
            )
            continuation.invokeOnCancellation { cancel.cancel() }
        }
    }

    private companion object {
        /** The prompt's title: the app's name (never localized). */
        const val APP_NAME = "us2us"
    }
}
