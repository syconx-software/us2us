package com.syconx.us2us.live

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.syconx.us2us.runtime.facade.AppRuntime
import com.syconx.us2us.runtime.facade.RuntimeEnvironment
import com.syconx.us2us.runtime.support.ProcessBackgroundTasks
import com.syconx.us2us.runtime.support.RuntimeString
import com.syconx.us2us.runtime.support.RuntimeStrings
import com.syconx.us2us.runtime.support.RuntimeTexts
import com.syconx.us2us.runtime.support.SecurePasteboard
import com.syconx.us2us.runtime.support.SystemCaptureEvents
import com.syconx.us2us.runtime.support.SystemClipboard
import com.syconx.us2us.runtime.vault.ActivityProvider
import com.syconx.us2us.runtime.vault.KeystoreBiometricProtector
import kotlin.time.Duration.Companion.seconds

/**
 * Builds the live service: the runtime on this phone's files, the Android Keystore, biometric quick
 * unlock over the activity in front, and the relay at [create]'s `relayUrl`. One per process.
 */
object LiveMessenger {
    /**
     * @param relayUrl the relay: [RelayEndpoint.PRODUCTION] in release builds, a local relay through
     *   a debug build's `U2RelayURL` ([RelayEndpoint.debugOverride]).
     * @param activities the app's resumed activity (the biometric prompt and the location permission
     *   dialog are shown over it), `null` when none is in front.
     * @param hooks the app's guard for our own system activities.
     */
    fun create(context: Context, relayUrl: String, activities: () -> Activity?, hooks: ExternalActivityHooks): RealMessengerService {
        val application = context.applicationContext as Application
        val biometrics = KeystoreBiometricProtector(application, ActivityProvider { activities() as? FragmentActivity })
        val environment = RuntimeEnvironment.production(application, relayUrl, biometrics)
        val runtime = AppRuntime(
            environment = environment,
            // Awake for the grace period and the teardown after it, also with the screen off.
            backgroundTasks = ProcessBackgroundTasks(application, environment.gracePeriod + TEARDOWN_TIME),
            captureEvents = SystemCaptureEvents(application),
            pasteboard = SecurePasteboard(SystemClipboard(application)),
            strings = runtimeStrings(application, activities),
        )
        return RealMessengerService(runtime, LivePlatform(application, activities, hooks))
    }

    /**
     * The runtime's own words (the biometric prompt, its local lines) in the language the app shows
     * now: read from the activity in front, which carries the per-app language, else from the
     * application.
     */
    fun runtimeStrings(application: Context, activities: () -> Activity?): RuntimeStrings {
        val texts = RuntimeTexts { key, _ -> (activities() ?: application).getString(resource(key)) }
        val language = application.resources.configuration.locales[0]?.language ?: "en"
        return RuntimeStrings(language, texts)
    }

    /** How long the teardown after the grace period may keep the CPU awake (OFFLINE, closing the store). */
    private val TEARDOWN_TIME = 30.seconds

    /** The string resource of [key] (`runtime_` and the key in lower case). */
    fun resource(key: RuntimeString): Int = when (key) {
        RuntimeString.BIOMETRIC_REASON -> R.string.runtime_biometric_reason
        RuntimeString.BIOMETRIC_USE_CODE -> R.string.runtime_biometric_use_code
        RuntimeString.SCREENSHOT_TAKEN_BY_ME -> R.string.runtime_screenshot_taken_by_me
        RuntimeString.SCREEN_RECORDING_BY_ME -> R.string.runtime_screen_recording_by_me
        RuntimeString.SAFETY_NUMBER_CHANGED_LINE -> R.string.runtime_safety_number_changed_line
        RuntimeString.SAFETY_NUMBER_ACCEPTED_LINE -> R.string.runtime_safety_number_accepted_line
        RuntimeString.SAFETY_NUMBER_VERIFIED_LINE -> R.string.runtime_safety_number_verified_line
        RuntimeString.REQUEST_ACCEPTED_LINE -> R.string.runtime_request_accepted_line
        RuntimeString.DELETED_FOR_EVERYONE -> R.string.runtime_deleted_for_everyone
        RuntimeString.LINE_OPEN -> R.string.runtime_line_open
        RuntimeString.LINE_CONNECTING -> R.string.runtime_line_connecting
        RuntimeString.LINE_SYNCING -> R.string.runtime_line_syncing
        RuntimeString.LINE_OFFLINE -> R.string.runtime_line_offline
        RuntimeString.LINE_WAITING_FOR_NETWORK -> R.string.runtime_line_waiting_for_network
        RuntimeString.LINE_REVOKED -> R.string.runtime_line_revoked
        RuntimeString.LINE_LOCKED -> R.string.runtime_line_locked
    }
}
