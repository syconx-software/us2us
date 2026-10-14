package com.syconx.us2us.ui.nav

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.syconx.us2us.domain.nav.AppRoute

/**
 * Whether the screen this composable is on can be seen right now: `false` on screens kept alive
 * under the top of a [SoftNavigationStack], and on the whole stack while the lock or the privacy
 * cover is up. Views with continuous work (the welcome scene, the line packets, tickers) pause while
 * it is `false` (iOS `\.u2IsLayerVisible`).
 */
val LocalIsLayerVisible = compositionLocalOf { true }

/**
 * The motion of the stack or pager this composable is on (iOS `\.u2LayerMotion`): continuous GPU
 * work holds its last frame until [U2LayerMotion.isSettled].
 */
val LocalLayerMotion = staticCompositionLocalOf<U2LayerMotion?> { null }

/** The main stack's router (rooted at 4.1). Provided at the app root. */
val LocalRouter = staticCompositionLocalOf<U2Router<AppRoute>> {
    error("LocalRouter is not provided: wrap the screen in ProvideU2Environment")
}
