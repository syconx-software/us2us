package com.syconx.us2us.app.debug

import android.content.Intent
import com.syconx.us2us.domain.nav.LaunchConfig

/**
 * Debug builds: the iOS launch arguments as intent extras, with the same names and values
 * (`adb shell am start … --es U2Route conversation:deniz/5.3 --ez U2FrozenClock true`).
 * A flag may be passed as a boolean extra or as text (`YES`, `true`, `1`).
 */
object LaunchArgs {
    fun read(intent: Intent?): LaunchConfig {
        val extras = intent?.extras ?: return LaunchConfig.from { null }
        return LaunchConfig.from { key ->
            @Suppress("DEPRECATION")
            extras.get(key)?.toString()
        }
    }
}
