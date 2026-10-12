package com.syconx.us2us.app.debug

import androidx.compose.runtime.Composable
import com.syconx.us2us.domain.nav.LaunchConfig

/** Release builds: no developer pages, no launch arguments. */
object DebugScreens {
    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun Gallery(configuration: LaunchConfig) = Unit

    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun Provide(configuration: LaunchConfig, content: @Composable () -> Unit) = content()
}
