package com.syconx.us2us.app.debug

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.harness.FixtureControls
import com.syconx.us2us.domain.harness.HarnessPlan
import com.syconx.us2us.domain.nav.LaunchConfig
import com.syconx.us2us.domain.nav.StartScreen
import com.syconx.us2us.feature.lock.PlatformBiometricCheck
import com.syconx.us2us.feature.media.DesignVideoEncoder
import com.syconx.us2us.feature.settings.avatar.AvatarPhotoScaler
import com.syconx.us2us.fixtures.FixturesMessengerService
import com.syconx.us2us.ui.media.BitmapDesignArtworkEncoder
import java.lang.ref.WeakReference
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CoroutineScope

/**
 * Debug builds: the design's data (`U2Fixtures`), and the harnesses that open every screen in its
 * drawn states (`U2Route`).
 */
object BuildServices {
    /** `U2Fixtures`, or always while the build has no live service. */
    fun usesFixtures(configuration: LaunchConfig, hasLiveService: Boolean): Boolean = configuration.usesFixtures || !hasLiveService

    /**
     * The fixtures service with this phone's platform hooks: the system biometric prompt, the design
     * photos as JPEG and the design video as MP4 (so they can be saved, shared and played), and the
     * profile photo scaled the way the live service scales it.
     */
    fun fixtures(context: Context, configuration: LaunchConfig, clock: DesignClock, english: Boolean, scope: CoroutineScope): MessengerService? {
        val cacheDir = context.cacheDir
        (context.applicationContext as? Application)?.let(ResumedActivity::track)
        return FixturesMessengerService(
            hasAccount = configuration.start != StartScreen.Welcome,
            clock = clock,
            ghostUrgent = configuration.ghostUrgent,
            english = english,
            scope = scope,
            bootClock = { SystemClock.elapsedRealtimeNanos().nanoseconds },
        ).apply {
            biometricCheck = PlatformBiometricCheck { ResumedActivity.current }
            artworkEncoder = BitmapDesignArtworkEncoder { artwork -> DesignVideoEncoder.mp4(artwork, cacheDir) }
            avatarPhotoPreparer = AvatarPhotoScaler
        }
    }

    fun harnessPlan(configuration: LaunchConfig, messenger: MessengerService): HarnessPlan? =
        HarnessRegistry.plan(configuration.launchRoute, messenger as? FixtureControls)
}

/** The activity in front, for the system biometric prompt of the fixtures service. */
private object ResumedActivity : Application.ActivityLifecycleCallbacks {
    private var tracked = false
    private var reference: WeakReference<Activity>? = null

    val current: Activity? get() = reference?.get()

    fun track(application: Application) {
        if (tracked) return
        tracked = true
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        reference = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        if (reference?.get() === activity) reference = null
    }
}
