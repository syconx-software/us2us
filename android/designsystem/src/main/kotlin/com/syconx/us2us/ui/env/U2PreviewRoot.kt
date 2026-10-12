package com.syconx.us2us.ui.env

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.syconx.us2us.domain.harness.HarnessUiState
import com.syconx.us2us.ui.sheet.U2OverlayHost
import com.syconx.us2us.ui.theme.U2Theme

/**
 * Everything a screen expects around it, for previews and feature shot tests: the theme, the
 * environment of [appModel] (usually a [PreviewAppModel] on the fixtures service), design insets,
 * no haptics, and an overlay host for its sheets and covers.
 *
 * ```
 * val FeatureShots = ShotEnvironment { spec, content ->
 *     val model = remember { PreviewAppModel(FixturesMessengerService(hasAccount = true, clock = DesignClock.frozen(DesignClock.referenceDate))) }
 *     U2PreviewRoot(model, dark = spec.isDark, designInsets = spec.designInsets) { content() }
 * }
 * ```
 */
@Composable
fun U2PreviewRoot(
    appModel: U2AppModel,
    dark: Boolean,
    designInsets: Boolean = true,
    reduceMotion: Boolean = false,
    harnessState: HarnessUiState? = null,
    screens: FeatureScreens = FeatureScreens.None,
    content: @Composable () -> Unit,
) {
    U2Theme(dark = dark, reduceMotion = reduceMotion) {
        ProvideU2Environment(
            appModel = appModel,
            designInsets = designInsets,
            haptics = U2Haptics.None,
            harnessState = harnessState,
            screens = screens,
        ) {
            val avatar = appModel.messenger.ownAvatar.value.picture
            CompositionLocalProvider(LocalOwnAvatar provides avatar) {
                U2OverlayHost { content() }
            }
        }
    }
}
