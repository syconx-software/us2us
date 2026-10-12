package com.syconx.us2us.app

import android.app.Application
import com.syconx.us2us.BuildConfig
import com.syconx.us2us.domain.nav.LaunchConfig
import kotlinx.coroutines.cancel

/**
 * Process-wide state lives here rather than in the activity, so a theme or language change that
 * recreates the activity keeps the stack and does not lock again (iOS `AppEnvironment`).
 */
class Us2usApplication : Application() {
    /** The process's services. */
    val services: ServiceLocator by lazy { ServiceLocator(this) }

    /** The launch configuration, read once from the intent that started the process. */
    var launchConfig: LaunchConfig = LaunchConfig.Release
        private set

    /** The app model, once an activity made it. */
    var model: AppModel? = null
        private set

    /**
     * The app model, made the first time an activity asks for it with the [config] of the intent that
     * started the process; later calls (recreated activities, new intents) get the same one.
     */
    fun appModel(config: LaunchConfig): AppModel = model ?: run {
        launchConfig = config
        AppModel(config, services.preferences, services).also { model = it }
    }

    /**
     * Debug builds: forgets the app model, so the next activity starts a new launch from its own
     * intent, as a fresh process would (the instrumented flows make many launches in one process).
     */
    fun startOver() {
        check(BuildConfig.DEBUG) { "us2us: a launch starts over in debug builds only" }
        model?.scope?.cancel()
        model = null
        launchConfig = LaunchConfig.Release
    }
}
