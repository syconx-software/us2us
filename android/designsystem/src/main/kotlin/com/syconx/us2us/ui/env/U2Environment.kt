package com.syconx.us2us.ui.env

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.syconx.us2us.domain.harness.HarnessUiState
import com.syconx.us2us.ui.nav.LocalRouter

/**
 * Provides what every screen reads from its environment: the app model, the service, the main
 * stack's router, the clock, design-insets mode, haptics, the harness state, the external activity
 * guard, the system bars and the screens features present from each other. The app root wraps
 * everything in it; feature tests and previews wrap a screen in it with a [PreviewAppModel].
 *
 * Theme and reduce motion come from `U2Theme`, which goes inside or outside this, as the caller needs.
 */
@Composable
fun ProvideU2Environment(
    appModel: U2AppModel,
    designInsets: Boolean = false,
    haptics: U2Haptics = rememberViewHaptics(),
    harnessState: HarnessUiState? = null,
    guard: ExternalActivityGuard = remember { ExternalActivityGuard() },
    systemBars: U2SystemBars = remember { U2SystemBars() },
    screens: FeatureScreens = FeatureScreens.None,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalAppModel provides appModel,
        LocalMessenger provides appModel.messenger,
        LocalRouter provides appModel.router,
        LocalU2Clock provides appModel.clock,
        LocalDesignInsets provides designInsets,
        LocalHaptics provides haptics,
        LocalHarnessState provides harnessState,
        LocalExternalActivityGuard provides guard,
        LocalSystemBars provides systemBars,
        LocalFeatureScreens provides screens,
        content = content,
    )
}
