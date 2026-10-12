package com.syconx.us2us.app

import android.app.Application
import android.content.Context
import com.syconx.us2us.BuildConfig
import com.syconx.us2us.app.debug.BuildServices
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.harness.HarnessPlan
import com.syconx.us2us.domain.nav.LaunchConfig
import com.syconx.us2us.live.ExternalActivityHooks
import com.syconx.us2us.live.LiveMessenger
import com.syconx.us2us.live.RelayEndpoint
import com.syconx.us2us.ui.env.U2SystemBars
import kotlinx.coroutines.CoroutineScope

/** Where the app model gets its service and its debug harness plan from. */
interface AppServices {
    /** Whether this launch runs on the design's data (debug builds) rather than the live service. */
    fun usesFixtures(configuration: LaunchConfig): Boolean

    /** The design's data service (debug builds only). */
    fun fixtures(configuration: LaunchConfig, clock: DesignClock, english: Boolean, scope: CoroutineScope): MessengerService

    /** The live service. */
    fun live(configuration: LaunchConfig): MessengerService

    /** The debug harness plan of the launch route (`U2Route feature:…/state`); `null` in release builds or without a state. */
    fun harnessPlan(configuration: LaunchConfig, messenger: MessengerService): HarnessPlan?
}

/**
 * The process's services (manual wiring, no DI framework): the preferences, the system bars, and
 * the messenger service of a launch: the live service, or in debug builds the design's fixtures
 * when the launch asks for them (`U2Fixtures`).
 */
class ServiceLocator(private val context: Context) : AppServices {
    val preferences: AppPreferences by lazy { AppPreferences.of(context) }
    val systemBars = U2SystemBars()

    /** Makes the live service for a launch: the runtime on this phone's vault, against the relay. */
    var liveServiceFactory: ((Context, LaunchConfig) -> MessengerService)? = ::makeLiveService

    /** The live service of this process: one runtime per vault, however many launches (activities) use it. */
    private var liveService: MessengerService? = null

    override fun usesFixtures(configuration: LaunchConfig): Boolean =
        BuildServices.usesFixtures(configuration, hasLiveService = liveServiceFactory != null)

    override fun fixtures(configuration: LaunchConfig, clock: DesignClock, english: Boolean, scope: CoroutineScope): MessengerService =
        BuildServices.fixtures(context, configuration, clock, english, scope) ?: live(configuration)

    override fun live(configuration: LaunchConfig): MessengerService {
        val factory = checkNotNull(liveServiceFactory) { "us2us: the app runtime could not start" }
        return factory(context, configuration)
    }

    override fun harnessPlan(configuration: LaunchConfig, messenger: MessengerService): HarnessPlan? =
        BuildServices.harnessPlan(configuration, messenger)

    /**
     * The production relay; a debug build launched with `U2RelayURL` may use a local relay instead,
     * if the URL passes the network policy (https, or plain http to a loopback address).
     */
    private fun makeLiveService(context: Context, configuration: LaunchConfig): MessengerService = liveService ?: run {
        val application = context.applicationContext as Application
        val relay = (if (BuildConfig.DEBUG) RelayEndpoint.debugOverride(configuration.relayUrl) else null) ?: RelayEndpoint.PRODUCTION
        val activities = ActivityProvider.of(application)
        LiveMessenger.create(application, relay, activities = { activities.current }, hooks = LaunchGuard(application))
            .also { liveService = it }
    }

    /** The guard of the current launch's app model: the live service's permission dialogs are our own, not a background. */
    private class LaunchGuard(private val application: Application) : ExternalActivityHooks {
        private val guard get() = (application as? Us2usApplication)?.model?.guard

        override fun begin() {
            guard?.beginDialog()
        }

        override fun end() {
            guard?.endDialog()
        }
    }
}
