package com.syconx.us2us.app.debug

import android.content.Context
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.harness.HarnessPlan
import com.syconx.us2us.domain.nav.LaunchConfig
import kotlinx.coroutines.CoroutineScope

/** Release builds: the live service only; no design data, no harnesses. */
object BuildServices {
    @Suppress("UNUSED_PARAMETER")
    fun usesFixtures(configuration: LaunchConfig, hasLiveService: Boolean): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun fixtures(context: Context, configuration: LaunchConfig, clock: DesignClock, english: Boolean, scope: CoroutineScope): MessengerService? = null

    @Suppress("UNUSED_PARAMETER")
    fun harnessPlan(configuration: LaunchConfig, messenger: MessengerService): HarnessPlan? = null
}
