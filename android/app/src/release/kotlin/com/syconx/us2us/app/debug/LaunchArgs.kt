package com.syconx.us2us.app.debug

import android.content.Intent
import com.syconx.us2us.domain.nav.LaunchConfig

/** Release builds read no launch argument at all. */
object LaunchArgs {
    @Suppress("UNUSED_PARAMETER")
    fun read(intent: Intent?): LaunchConfig = LaunchConfig.Release
}
